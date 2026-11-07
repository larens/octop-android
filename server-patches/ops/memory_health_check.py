#!/usr/bin/env python3
"""Check every Octop memory SQLite store without modifying it."""

from __future__ import annotations

import argparse
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
from contextlib import closing
from pathlib import Path
from typing import Any
from urllib.parse import quote

DEFAULT_ROOTS = (
    Path("/data/agent-workspace/.octop/workspaces"),
    Path("/opt/octop/agents"),
)
DEFAULT_STATE_FILE = Path("/opt/octop/state/memory-health/latest.json")
DEFAULT_ALERT_STATE_FILE = Path("/opt/octop/state/memory-health/alert-state.json")
CHECK_RETRIES = 3

logger = logging.getLogger("octop-memory-health")


def _sqlite_uri(path: Path) -> str:
    return f"file:{quote(path.resolve().as_posix(), safe='/')}?mode=ro"


def _discover(roots: list[Path]) -> list[Path]:
    found: set[Path] = set()
    for root in roots:
        if not root.is_dir():
            continue
        for path in root.glob("*/.octop/memory.sqlite"):
            if path.is_file():
                found.add(path.resolve())
    return sorted(found)


def _memory_namespaces(conn: sqlite3.Connection) -> list[str]:
    names = [
        str(row[0])
        for row in conn.execute(
            "SELECT name FROM sqlite_master "
            "WHERE type='table' AND name LIKE '%_atoms' "
            "AND name NOT LIKE '%_fts%'"
        )
    ]
    return sorted({name.removesuffix("_atoms") for name in names})


def _scalar(conn: sqlite3.Connection, sql: str) -> Any:
    row = conn.execute(sql).fetchone()
    return None if row is None else row[0]


def _check_database(path: Path) -> dict[str, Any]:
    started = time.perf_counter()
    result: dict[str, Any] = {
        "path": str(path),
        "ok": False,
        "errors": [],
        "warnings": [],
        "namespaces": {},
    }
    for attempt in range(1, CHECK_RETRIES + 1):
        try:
            with closing(sqlite3.connect(_sqlite_uri(path), uri=True, timeout=30)) as conn:
                conn.execute("PRAGMA query_only=ON")
                conn.execute("PRAGMA busy_timeout=30000")
                quick_rows = [str(row[0]) for row in conn.execute("PRAGMA quick_check").fetchall()]
                result["quick_check"] = quick_rows[0] if quick_rows else "missing"
                if result["quick_check"] != "ok":
                    result["errors"].append("quick_check: " + "; ".join(quick_rows[:10]))

                namespaces = _memory_namespaces(conn)
                if not namespaces:
                    result["errors"].append("no memory namespace found")
                for ns in namespaces:
                    prefix = "" if ns == "memory" else ns + "_"
                    atoms_table = f"{prefix}atoms"
                    atoms_fts_table = f"{prefix}atoms_fts"
                    tables = {
                        str(row[0])
                        for row in conn.execute(
                            "SELECT name FROM sqlite_master WHERE type='table'"
                        )
                    }
                    item: dict[str, Any] = {
                        "atoms": int(_scalar(conn, f'SELECT count(*) FROM "{atoms_table}"')),
                        "checkpoints": (
                            int(_scalar(conn, 'SELECT count(*) FROM "checkpoints"'))
                            if "checkpoints" in tables
                            else None
                        ),
                        "writes": (
                            int(_scalar(conn, 'SELECT count(*) FROM "writes"'))
                            if "writes" in tables
                            else None
                        ),
                    }
                    if atoms_fts_table not in tables:
                        item["atoms_fts"] = None
                        result["errors"].append(f"{ns}: missing {atoms_fts_table}")
                    else:
                        item["atoms_fts"] = int(
                            _scalar(conn, f'SELECT count(*) FROM "{atoms_fts_table}"')
                        )
                        if item["atoms"] != item["atoms_fts"]:
                            result["errors"].append(
                                f"{ns}: atoms/FTS mismatch {item['atoms']} != {item['atoms_fts']}"
                            )
                    phantom = int(
                        _scalar(
                            conn,
                            f'SELECT count(*) FROM "{atoms_table}" '
                            "WHERE id LIKE 'thr_%' OR assertion = 'harness-checkpoint-v1'",
                        )
                    )
                    item["checkpoint_phantom_atoms"] = phantom
                    if phantom:
                        result["errors"].append(f"{ns}: {phantom} checkpoint phantoms in atoms")
                    try:
                        conn.execute(
                            f'SELECT count(*) FROM "{atoms_fts_table}" '
                            f'WHERE "{atoms_fts_table}" MATCH \'memory\''
                        ).fetchone()
                    except sqlite3.DatabaseError as exc:
                        result["errors"].append(f"{ns}: FTS probe failed: {exc}")
                    result["namespaces"][ns] = item

                foreign_key_rows = conn.execute("PRAGMA foreign_key_check").fetchall()
                result["foreign_key_violations"] = len(foreign_key_rows)
                if foreign_key_rows:
                    result["errors"].append(
                        f"{len(foreign_key_rows)} foreign key violation(s)"
                    )
            result["ok"] = not result["errors"]
            break
        except sqlite3.DatabaseError as exc:
            if attempt == CHECK_RETRIES:
                result["errors"].append(f"{type(exc).__name__}: {exc}")
            else:
                time.sleep(2)
    result["duration_ms"] = round((time.perf_counter() - started) * 1000, 1)
    result["file_bytes"] = path.stat().st_size
    for suffix in ("-wal", "-shm"):
        sidecar = path.with_name(path.name + suffix)
        result[f"{suffix.lstrip('-')}_bytes"] = sidecar.stat().st_size if sidecar.exists() else 0
    return result


def _service_pid(service: str) -> tuple[bool, int | None]:
    active = subprocess.run(
        ["systemctl", "is-active", "--quiet", service],
        check=False,
    ).returncode == 0
    raw = subprocess.run(
        ["systemctl", "show", "-p", "MainPID", "--value", service],
        capture_output=True,
        text=True,
        check=False,
    ).stdout.strip()
    try:
        pid = int(raw)
    except ValueError:
        pid = None
    return active, pid if pid and pid > 0 else None


def _deleted_memory_handles(pid: int | None) -> list[str]:
    if pid is None:
        return []
    fd_dir = Path(f"/proc/{pid}/fd")
    found: list[str] = []
    if not fd_dir.is_dir():
        return found
    for entry in fd_dir.iterdir():
        try:
            target = os.readlink(entry)
        except OSError:
            continue
        if "memory.sqlite" in target and "(deleted)" in target:
            found.append(f"{entry.name} -> {target}")
    return sorted(found)


def _disk_checks(roots: list[Path], *, warn_percent: float, fail_percent: float) -> list[dict[str, Any]]:
    checks: list[dict[str, Any]] = []
    seen_devices: set[int] = set()
    for root in roots:
        if not root.exists():
            continue
        stat = root.stat()
        if stat.st_dev in seen_devices:
            continue
        seen_devices.add(stat.st_dev)
        usage = shutil.disk_usage(root)
        percent = usage.used / usage.total * 100 if usage.total else 0
        checks.append(
            {
                "root": str(root),
                "used_percent": round(percent, 1),
                "free_bytes": usage.free,
                "warn": percent >= warn_percent,
                "error": percent >= fail_percent,
            }
        )
    return checks


def _write_state(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    tmp.replace(path)


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def _alert_text(payload: dict[str, Any], *, recovered: bool) -> str:
    service = payload["service"]
    lines = [
        "Octop 记忆健康检查已恢复" if recovered else "Octop 记忆健康检查失败",
        f"时间: {payload['checked_at']}",
        f"服务: {'active' if service['active'] else 'inactive'}"
        f" (pid={service['main_pid'] or 'none'})",
    ]
    errors = payload.get("errors") or []
    warnings = payload.get("warnings") or []
    if errors:
        lines.append("错误:")
        lines.extend(f"- {item}" for item in errors[:8])
        if len(errors) > 8:
            lines.append(f"- 另有 {len(errors) - 8} 条错误")
    if warnings:
        lines.append("警告:")
        lines.extend(f"- {item}" for item in warnings[:5])
    return "\n".join(lines)


def _send_webhook(url: str, fmt: str, text: str) -> None:
    if fmt == "feishu":
        body = {"msg_type": "text", "content": {"text": text}}
    else:
        body = {"text": text}
    request = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        if not 200 <= response.status < 300:
            raise RuntimeError(f"webhook returned HTTP {response.status}")


def _deliver_alert(
    payload: dict[str, Any],
    *,
    previous: dict[str, Any],
    state_file: Path,
    webhook: str | None,
    fmt: str,
    repeat_seconds: int,
) -> dict[str, Any]:
    now = int(time.time())
    previous_ok = previous.get("ok")
    recovered = previous_ok is False and bool(payload["ok"])
    alert_state = _read_json(state_file)
    last_sent = int(alert_state.get("last_sent_at") or 0)
    due = recovered or (
        not payload["ok"]
        and (previous_ok is not False or now - last_sent >= repeat_seconds)
    )
    result: dict[str, Any] = {
        "configured": bool(webhook),
        "attempted": False,
        "delivered": False,
        "recovered": recovered,
    }
    if not webhook or not due:
        return result

    text = _alert_text(payload, recovered=recovered)
    result["attempted"] = True
    try:
        _send_webhook(webhook, fmt, text)
    except (OSError, RuntimeError, ValueError, urllib.error.URLError) as exc:
        result["error"] = f"{type(exc).__name__}: {exc}"
        return result

    result["delivered"] = True
    alert_state.update(
        {
            "last_sent_at": now,
            "last_ok": payload["ok"],
            "last_text": text,
        }
    )
    state_file.parent.mkdir(parents=True, exist_ok=True)
    state_file.write_text(
        json.dumps(alert_state, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", action="append", type=Path, default=None)
    parser.add_argument("--service", default="octop.service")
    parser.add_argument("--state-file", type=Path, default=DEFAULT_STATE_FILE)
    parser.add_argument("--disk-warn-percent", type=float, default=85.0)
    parser.add_argument("--disk-fail-percent", type=float, default=95.0)
    parser.add_argument(
        "--alert-webhook",
        default=os.environ.get("OCTOP_ALERT_WEBHOOK_URL") or None,
        help="optional webhook for state-change alerts",
    )
    parser.add_argument(
        "--alert-format",
        choices=("generic", "feishu"),
        default=os.environ.get("OCTOP_ALERT_WEBHOOK_FORMAT", "generic"),
    )
    parser.add_argument(
        "--alert-state-file",
        type=Path,
        default=DEFAULT_ALERT_STATE_FILE,
    )
    parser.add_argument(
        "--alert-repeat-seconds",
        type=int,
        default=6 * 60 * 60,
    )
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    roots = args.root or list(DEFAULT_ROOTS)
    databases = _discover(roots)
    service_active, service_pid = _service_pid(args.service)
    deleted_handles = _deleted_memory_handles(service_pid) if service_active else []
    disk = _disk_checks(
        roots,
        warn_percent=args.disk_warn_percent,
        fail_percent=args.disk_fail_percent,
    )

    errors: list[str] = []
    warnings: list[str] = []
    if not service_active:
        errors.append(f"{args.service} is not active")
    if deleted_handles:
        errors.append(
            f"{args.service} holds deleted memory SQLite handles: " + "; ".join(deleted_handles)
        )
    if not databases:
        errors.append("no memory.sqlite files found")
    for item in disk:
        if item["error"]:
            errors.append(f"disk {item['root']} is {item['used_percent']}% full")
        elif item["warn"]:
            warnings.append(f"disk {item['root']} is {item['used_percent']}% full")

    results = [_check_database(path) for path in databases]
    for item in results:
        errors.extend(f"{item['path']}: {message}" for message in item["errors"])
        warnings.extend(f"{item['path']}: {message}" for message in item["warnings"])

    previous = _read_json(args.state_file)
    payload = {
        "ok": not errors,
        "checked_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "service": {
            "name": args.service,
            "active": service_active,
            "main_pid": service_pid,
            "deleted_memory_handles": deleted_handles,
        },
        "disk": disk,
        "errors": errors,
        "warnings": warnings,
        "databases": results,
    }
    payload["alerting"] = _deliver_alert(
        payload,
        previous=previous,
        state_file=args.alert_state_file,
        webhook=args.alert_webhook,
        fmt=args.alert_format,
        repeat_seconds=args.alert_repeat_seconds,
    )
    if payload["alerting"].get("error"):
        warnings.append("alert delivery failed: " + payload["alerting"]["error"])
    _write_state(args.state_file, payload)
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    for message in warnings:
        logger.warning(message)
    for message in errors:
        logger.error(message)
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
