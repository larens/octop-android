#!/usr/bin/env python3
"""Bound Octop release and journal storage without touching live data."""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import shutil
import sqlite3
import subprocess
import time
from pathlib import Path
from typing import Any

DEFAULT_ROOT = Path("/opt/octop")
DEFAULT_STATE_FILE = Path("/opt/octop/state/storage-maintenance/latest.json")
DEFAULT_ARTIFACT_ROOT = Path("/data/agent-workspace")
DEFAULT_DB_PATH = Path("/opt/octop/octop.db")
JOURNAL_MAX_USE_RE = re.compile(r"^[1-9][0-9]*[KMG]$")
DEFAULT_TOOL_ARTIFACT_DAYS = 30
DEFAULT_HISTORY_DAYS = 90
DEFAULT_FEED_DAYS = 90
DEFAULT_CONTEXT_PACK_DAYS = 90
BATCH_SIZE = 500


def _resolve(path: Path) -> Path | None:
    try:
        return path.resolve(strict=True)
    except OSError:
        return None


def _release_dirs(releases: Path) -> list[Path]:
    if not releases.is_dir():
        return []
    return sorted(
        (
            path
            for path in releases.iterdir()
            if path.is_dir()
            and not path.is_symlink()
            and (path / "manifest.json").is_file()
        ),
        key=lambda path: (path.stat().st_mtime, path.name),
        reverse=True,
    )


def _bluegreen_busy(lock_path: Path) -> bool:
    try:
        handle = lock_path.open("a")
    except OSError:
        return True
    try:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        return True
    else:
        fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        return False
    finally:
        handle.close()


def _processes_using(path: Path) -> list[str]:
    wanted = str(path)
    found: list[str] = []
    for proc in Path("/proc").iterdir():
        if not proc.name.isdigit():
            continue
        for name in ("cwd", "exe"):
            try:
                target = os.readlink(proc / name)
            except OSError:
                continue
            if target == wanted or target.startswith(wanted + "/"):
                found.append(f"{proc.name}:{name} -> {target}")
    return sorted(found)


def _journal_config(max_use: str) -> Path:
    return Path("/etc/systemd/journald.conf.d/50-octop-storage.conf")


def _write_journal_config(path: Path, max_use: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = (
        "# Managed by Octop storage maintenance.\n"
        "[Journal]\n"
        f"SystemMaxUse={max_use}\n"
        "SystemMaxFileSize=64M\n"
        "MaxRetentionSec=14day\n"
        "Compress=yes\n"
    )
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(payload, encoding="utf-8")
    temporary.replace(path)


def _run(command: list[str]) -> dict[str, Any]:
    completed = subprocess.run(
        command,
        capture_output=True,
        text=True,
        check=False,
    )
    return {
        "command": command,
        "returncode": completed.returncode,
        "stdout": completed.stdout.strip(),
        "stderr": completed.stderr.strip(),
    }


def _write_state(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def _cleanup_tree(
    root: Path,
    *,
    days: int,
    now: int,
    apply: bool,
) -> dict[str, Any]:
    """Delete expired regular files below ``root``; never follow symlinks."""
    result: dict[str, Any] = {
        "root": str(root),
        "retention_days": days,
        "exists": root.exists(),
        "candidates": 0,
        "bytes": 0,
        "removed": 0,
        "removed_bytes": 0,
        "errors": [],
    }
    if days <= 0 or not root.is_dir():
        return result
    cutoff = now - days * 86_400
    stack = [root]
    expired: list[tuple[Path, int]] = []
    while stack:
        current = stack.pop()
        try:
            entries = list(os.scandir(current))
        except OSError as exc:
            result["errors"].append(f"{current}: {exc}")
            continue
        for entry in entries:
            try:
                if entry.is_symlink():
                    continue
                if entry.is_dir(follow_symlinks=False):
                    stack.append(Path(entry.path))
                    continue
                if not entry.is_file(follow_symlinks=False):
                    continue
                stat = entry.stat(follow_symlinks=False)
            except OSError as exc:
                result["errors"].append(f"{entry.path}: {exc}")
                continue
            if stat.st_mtime < cutoff:
                expired.append((Path(entry.path), int(stat.st_size)))
    result["candidates"] = len(expired)
    result["bytes"] = sum(size for _, size in expired)
    if not apply:
        return result
    for path, size in expired:
        try:
            path.unlink()
        except OSError as exc:
            result["errors"].append(f"{path}: {exc}")
            continue
        result["removed"] += 1
        result["removed_bytes"] += size
    return result


def _table_exists(conn: sqlite3.Connection, table: str) -> bool:
    row = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        (table,),
    ).fetchone()
    return row is not None


def _delete_expired_rows(
    conn: sqlite3.Connection,
    *,
    table: str,
    column: str,
    cutoff: int,
    apply: bool,
) -> int:
    if not _table_exists(conn, table):
        return 0
    if not apply:
        return int(
            conn.execute(
                f'SELECT COUNT(*) FROM "{table}" WHERE {column} < ?',
                (cutoff,),
            ).fetchone()[0]
            or 0
        )
    total = 0
    while True:
        cursor = conn.execute(
            f'DELETE FROM "{table}" WHERE rowid IN ('
            f'SELECT rowid FROM "{table}" WHERE {column} < ? LIMIT ?'
            ")",
            (cutoff, BATCH_SIZE),
        )
        removed = int(cursor.rowcount or 0)
        conn.commit()
        total += removed
        if removed < BATCH_SIZE:
            break
    return total


def _cleanup_database(
    path: Path,
    *,
    feed_days: int,
    context_pack_days: int,
    now: int,
    apply: bool,
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "path": str(path),
        "exists": path.is_file(),
        "feed_events": {"retention_days": feed_days, "expired": 0},
        "context_pack_manifests": {
            "retention_days": context_pack_days,
            "expired": 0,
        },
    }
    if not path.is_file():
        return result
    try:
        conn = sqlite3.connect(f"file:{path}?mode=rw", uri=True, timeout=10)
        try:
            conn.execute("PRAGMA busy_timeout=10000")
            if feed_days > 0:
                result["feed_events"]["expired"] = _delete_expired_rows(
                    conn,
                    table="feed_events",
                    column="occurred_at",
                    cutoff=now - feed_days * 86_400,
                    apply=apply,
                )
            if context_pack_days > 0:
                result["context_pack_manifests"]["expired"] = _delete_expired_rows(
                    conn,
                    table="context_pack_manifests",
                    column="created_at",
                    cutoff=now - context_pack_days * 86_400,
                    apply=apply,
                )
        finally:
            conn.close()
    except sqlite3.Error as exc:
        result["error"] = str(exc)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--release-keep", type=int, default=4)
    parser.add_argument("--journal-max-use", default="512M")
    parser.add_argument("--state-file", type=Path, default=DEFAULT_STATE_FILE)
    parser.add_argument("--artifact-root", type=Path, default=DEFAULT_ARTIFACT_ROOT)
    parser.add_argument(
        "--tool-artifact-days", type=int, default=DEFAULT_TOOL_ARTIFACT_DAYS
    )
    parser.add_argument("--history-days", type=int, default=DEFAULT_HISTORY_DAYS)
    parser.add_argument("--feed-days", type=int, default=DEFAULT_FEED_DAYS)
    parser.add_argument(
        "--context-pack-days", type=int, default=DEFAULT_CONTEXT_PACK_DAYS
    )
    parser.add_argument("--db", type=Path, default=DEFAULT_DB_PATH)
    parser.add_argument(
        "--apply",
        action="store_true",
        help="delete expired releases, artifacts, rows, and apply the journald limit",
    )
    args = parser.parse_args()
    if args.release_keep < 2:
        parser.error("--release-keep must be at least 2")
    if not JOURNAL_MAX_USE_RE.fullmatch(args.journal_max_use):
        parser.error("--journal-max-use must look like 512M")
    for name in (
        "tool_artifact_days",
        "history_days",
        "feed_days",
        "context_pack_days",
    ):
        if getattr(args, name) < 0:
            parser.error(f"--{name.replace('_', '-')} cannot be negative")

    root = args.root.resolve()
    if not root.is_dir():
        parser.error(f"root does not exist: {root}")
    releases = root / "releases"
    state_dir = root / "state/bluegreen"
    current = _resolve(state_dir / "current")
    previous = _resolve(state_dir / "previous")
    available = _release_dirs(releases)
    newest = available[: args.release_keep]
    keep = {path for path in (current, previous, *newest) if path is not None}
    candidates = [path for path in available if path not in keep]

    busy = _bluegreen_busy(Path("/run/lock/octop-bluegreen.lock"))
    skipped: list[dict[str, str]] = []
    removable: list[Path] = []
    for path in candidates:
        users = _processes_using(path)
        if users:
            skipped.append({"path": str(path), "reason": "; ".join(users)})
        else:
            removable.append(path)

    usage_before = shutil.disk_usage(root)
    now = int(time.time())
    artifact_root = args.artifact_root.resolve()
    purge = args.apply and not busy
    artifacts = {
        "large_tool_results": _cleanup_tree(
            artifact_root / "large_tool_results",
            days=args.tool_artifact_days,
            now=now,
            apply=purge,
        ),
        "conversation_history": _cleanup_tree(
            artifact_root / "conversation_history",
            days=args.history_days,
            now=now,
            apply=purge,
        ),
    }
    database = _cleanup_database(
        args.db.resolve(),
        feed_days=args.feed_days,
        context_pack_days=args.context_pack_days,
        now=now,
        apply=purge,
    )
    journal_path = _journal_config(args.journal_max_use)
    plan: dict[str, Any] = {
        "ok": True,
        "applied": args.apply,
        "checked_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "root": str(root),
        "bluegreen_busy": busy,
        "current": str(current) if current else None,
        "previous": str(previous) if previous else None,
        "keep": [str(path) for path in sorted(keep)],
        "remove": [str(path) for path in removable],
        "skipped": skipped,
        "free_bytes_before": usage_before.free,
        "journal_config": str(journal_path),
        "artifacts": artifacts,
        "database": database,
    }

    if not args.apply:
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        return 0

    if busy:
        plan["ok"] = False
        plan["error"] = "blue-green operation is running; no cleanup performed"
        _write_state(args.state_file, plan)
        print(json.dumps(plan, ensure_ascii=False, indent=2))
        return 1

    removed: list[str] = []
    for path in removable:
        shutil.rmtree(path)
        removed.append(str(path))

    _write_journal_config(journal_path, args.journal_max_use)
    operations = [
        _run(["systemctl", "daemon-reload"]),
        _run(["systemctl", "restart", "systemd-journald"]),
        _run(["journalctl", f"--vacuum-size={args.journal_max_use}"]),
    ]
    usage_after = shutil.disk_usage(root)
    plan.update(
        {
            "removed": removed,
            "free_bytes_after": usage_after.free,
            "freed_bytes": usage_after.free - usage_before.free,
            "operations": operations,
        }
    )
    if any(item["returncode"] != 0 for item in operations):
        plan["ok"] = False
        plan["error"] = "one or more journal maintenance commands failed"
    _write_state(args.state_file, plan)
    print(json.dumps(plan, ensure_ascii=False, indent=2))
    return 0 if plan["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
