#!/usr/bin/env python3
"""Stop, back up, validate, and atomically switch one live memory database."""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from memory_safe_repair import memory_namespace, validate

logger = logging.getLogger("octop-memory-switch")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _service_active(service: str) -> bool:
    return subprocess.run(
        ["systemctl", "is-active", "--quiet", service],
        check=False,
    ).returncode == 0


def _service_pid(service: str) -> int | None:
    raw = subprocess.run(
        ["systemctl", "show", "-p", "MainPID", "--value", service],
        capture_output=True,
        text=True,
        check=False,
    ).stdout.strip()
    try:
        pid = int(raw)
    except ValueError:
        return None
    return pid if pid > 0 else None


def _memory_handles(path: Path) -> list[str]:
    wanted = {path, Path(str(path) + "-wal"), Path(str(path) + "-shm")}
    found: list[str] = []
    for proc in Path("/proc").iterdir():
        if not proc.name.isdigit():
            continue
        fd_dir = proc / "fd"
        try:
            entries = list(fd_dir.iterdir())
        except OSError:
            continue
        for entry in entries:
            try:
                raw_target = os.readlink(entry)
            except OSError:
                continue
            target = Path(raw_target.removesuffix(" (deleted)"))
            if target in wanted:
                found.append(f"{proc.name}:{entry.name} -> {raw_target}")
    return sorted(found)


def _wait_inactive(service: str, timeout: int = 45) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not _service_active(service):
            return
        time.sleep(1)
    raise RuntimeError(f"{service} did not stop within {timeout}s")


def _wait_health(url: str, timeout: int) -> None:
    deadline = time.monotonic() + timeout
    last_error = "health endpoint did not return 200"
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                if response.status == 200:
                    payload = json.loads(response.read())
                    if payload.get("ok") is True and payload.get("db") is True:
                        return
                    last_error = f"unexpected health payload: {payload}"
        except (OSError, ValueError, json.JSONDecodeError, urllib.error.URLError) as exc:
            last_error = str(exc)
        time.sleep(2)
    raise RuntimeError(f"health check failed after {timeout}s: {last_error}")


def _backup_live(target: Path, backup_root: Path, candidate: Path) -> tuple[Path, dict[str, Any]]:
    backup_root.mkdir(parents=True, exist_ok=True)
    backup = backup_root / f"memory-switch-{time.strftime('%Y%m%d-%H%M%S')}"
    if backup.exists():
        backup = backup_root / f"{backup.name}-{os.getpid()}"
    backup.mkdir(mode=0o700)
    metadata: dict[str, Any] = {
        "target": str(target),
        "candidate": str(candidate),
        "created_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "files": {},
    }
    for source in (target, Path(str(target) + "-wal"), Path(str(target) + "-shm")):
        if not source.exists():
            continue
        destination = backup / source.name
        shutil.copy2(source, destination)
        stat = source.stat()
        metadata["files"][source.name] = {
            "size": stat.st_size,
            "mode": stat.st_mode & 0o777,
            "uid": stat.st_uid,
            "gid": stat.st_gid,
            "sha256": _sha256(destination),
        }
    shutil.copy2(candidate, backup / "candidate.sqlite")
    (backup / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return backup, metadata


def _check_candidate(candidate: Path) -> tuple[str, dict[str, Any]]:
    with sqlite3.connect(f"file:{candidate}?mode=ro", uri=True, timeout=60) as conn:
        ns = memory_namespace(conn)
        phantom = int(
            conn.execute(
                f'SELECT count(*) FROM "{ns}_atoms" '
                "WHERE id LIKE 'thr_%' OR assertion = 'harness-checkpoint-v1'"
            ).fetchone()[0]
        )
    if phantom:
        raise RuntimeError(f"candidate contains {phantom} checkpoint phantom atom(s)")
    return ns, validate(candidate, ns)


def _fsync_file(path: Path) -> None:
    with path.open("rb") as handle:
        os.fsync(handle.fileno())


def _install(candidate: Path, target: Path, metadata: dict[str, Any]) -> None:
    old = metadata["files"].get(target.name)
    if old is None:
        raise RuntimeError(f"backup metadata is missing {target.name}")
    temporary = target.parent / f".{target.name}.switch-{os.getpid()}.tmp"
    try:
        shutil.copy2(candidate, temporary)
        os.chmod(temporary, int(old["mode"]))
        os.chown(temporary, int(old["uid"]), int(old["gid"]))
        _fsync_file(temporary)
        for suffix in ("-wal", "-shm", "-journal"):
            target.with_name(target.name + suffix).unlink(missing_ok=True)
        os.replace(temporary, target)
        directory_fd = os.open(target.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        temporary.unlink(missing_ok=True)


def _restore(backup: Path, target: Path, metadata: dict[str, Any]) -> None:
    source = backup / target.name
    if not source.is_file():
        raise RuntimeError(f"rollback backup is missing {source}")
    _install(source, target, metadata)
    for suffix in ("-wal", "-shm", "-journal"):
        saved = backup / (target.name + suffix)
        if saved.is_file():
            shutil.copy2(saved, target.with_name(target.name + suffix))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--agent-id")
    parser.add_argument("--target", type=Path)
    parser.add_argument("--service", default="octop.service")
    parser.add_argument("--backup-root", type=Path, default=Path("/opt/octop/backups"))
    parser.add_argument("--health-url", default="http://127.0.0.1:8088/api/health")
    parser.add_argument("--health-timeout", type=int, default=180)
    parser.add_argument("--apply", action="store_true", help="perform the switch")
    parser.add_argument("--keep-stopped", action="store_true")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    if args.target is None:
        if not args.agent_id:
            parser.error("--target or --agent-id is required")
        args.target = (
            Path("/data/agent-workspace/.octop/workspaces")
            / args.agent_id
            / ".octop/memory.sqlite"
        )
    target = args.target.resolve()
    if target.name != "memory.sqlite":
        parser.error("target must be a memory.sqlite file")
    candidate = args.candidate.resolve()
    if not candidate.is_file():
        parser.error(f"candidate does not exist: {candidate}")
    if candidate == target:
        parser.error("candidate and target must be different files")

    namespace, validation = _check_candidate(candidate)
    plan = {
        "candidate": str(candidate),
        "candidate_sha256": _sha256(candidate),
        "namespace": namespace,
        "target": str(target),
        "service": args.service,
        "service_active": _service_active(args.service),
        "validation": validation,
        "apply": args.apply,
    }
    if not args.apply:
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        return 0

    backup: Path | None = None
    metadata: dict[str, Any] | None = None
    try:
        subprocess.run(["systemctl", "stop", args.service], check=True)
        _wait_inactive(args.service)
        if _service_active(args.service):
            raise RuntimeError(f"{args.service} is still active")
        handles = _memory_handles(target)
        if handles:
            raise RuntimeError("live process still holds the memory database: " + "; ".join(handles))
        backup, metadata = _backup_live(target, args.backup_root, candidate)
        logger.info("backup created at %s", backup)
        _install(candidate, target, metadata)
        logger.info("installed %s", target)
        if args.keep_stopped:
            print(json.dumps({"ok": True, "stopped": True, "backup": str(backup)}, indent=2))
            return 0
        subprocess.run(["systemctl", "start", args.service], check=True)
        _wait_health(args.health_url, args.health_timeout)
        print(
            json.dumps(
                {"ok": True, "service": args.service, "backup": str(backup)},
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0
    except Exception as exc:
        logger.exception("memory switch failed: %s", exc)
        if backup is not None and metadata is not None:
            try:
                subprocess.run(["systemctl", "stop", args.service], check=False)
                _wait_inactive(args.service)
                _restore(backup, target, metadata)
                subprocess.run(["systemctl", "start", args.service], check=True)
                _wait_health(args.health_url, args.health_timeout)
                logger.error("rolled back to %s", backup)
            except Exception:
                logger.exception("automatic rollback failed")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
