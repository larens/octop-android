#!/usr/bin/env python3
"""Bound Octop release and journal storage without touching live data."""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any

DEFAULT_ROOT = Path("/opt/octop")
DEFAULT_STATE_FILE = Path("/opt/octop/state/storage-maintenance/latest.json")
JOURNAL_MAX_USE_RE = re.compile(r"^[1-9][0-9]*[KMG]$")


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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--release-keep", type=int, default=4)
    parser.add_argument("--journal-max-use", default="512M")
    parser.add_argument("--state-file", type=Path, default=DEFAULT_STATE_FILE)
    parser.add_argument(
        "--apply",
        action="store_true",
        help="delete old releases and apply the journald limit",
    )
    args = parser.parse_args()
    if args.release_keep < 2:
        parser.error("--release-keep must be at least 2")
    if not JOURNAL_MAX_USE_RE.fullmatch(args.journal_max_use):
        parser.error("--journal-max-use must look like 512M")

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
