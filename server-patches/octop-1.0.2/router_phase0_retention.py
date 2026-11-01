"""Bounded retention maintenance for Router Phase 0 traces and ledgers.

The command is intentionally manual and dry-run by default. Enabling automated
deletion is an operator decision made after the retention policy is accepted.
"""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import time
from collections.abc import Mapping
from pathlib import Path
from typing import Any


SETTING_PREFIX = "router.retention."
DEFAULT_RETENTION_DAYS = {
    "event_days": 90,
    "payload_days": 30,
    "context_pack_days": 90,
    "budget_ledger_days": 400,
    "task_run_days": 180,
}
TERMINAL_RUN_STATUSES = ("succeeded", "failed", "cancelled", "interrupted")
TERMINAL_TASK_STATUSES = ("done", "failed", "cancelled", "expired")
BATCH_SIZE = 500


def _positive_int(value: Any, fallback: int) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return fallback
    return parsed if parsed > 0 else fallback


def load_policy(
    conn: sqlite3.Connection,
    overrides: Mapping[str, Any] | None = None,
) -> dict[str, int]:
    """Read policy from settings, environment, then explicit overrides."""
    policy = dict(DEFAULT_RETENTION_DAYS)
    rows = conn.execute(
        "SELECT key, value FROM settings WHERE key LIKE ?",
        (f"{SETTING_PREFIX}%",),
    ).fetchall()
    stored = {str(row[0])[len(SETTING_PREFIX) :]: row[1] for row in rows}
    for key, default in DEFAULT_RETENTION_DAYS.items():
        env_key = f"OCTOP_ROUTER_RETENTION_{key.upper()}"
        policy[key] = _positive_int(
            (overrides or {}).get(key, os.environ.get(env_key, stored.get(key))),
            default,
        )
    if policy["payload_days"] > policy["event_days"]:
        raise ValueError("payload_days cannot exceed event_days")
    return policy


def _cutoff(now: int, days: int) -> int:
    return now - days * 86_400


def _count(
    conn: sqlite3.Connection,
    sql: str,
    params: tuple[Any, ...],
) -> int:
    return int(conn.execute(sql, params).fetchone()[0] or 0)


def _batch_update(
    conn: sqlite3.Connection,
    *,
    select_sql: str,
    update_sql: str,
    params: tuple[Any, ...],
    apply: bool,
) -> int:
    """Redact rows in bounded batches so a maintenance run is interruptible."""
    if not apply:
        return _count(conn, f"SELECT COUNT(*) FROM ({select_sql})", params)
    total = 0
    while True:
        ids = [
            row[0]
            for row in conn.execute(
                f"{select_sql} LIMIT ?",
                (*params, BATCH_SIZE),
            ).fetchall()
        ]
        if not ids:
            break
        placeholders = ",".join("?" for _ in ids)
        conn.execute(update_sql.format(placeholders=placeholders), ids)
        total += len(ids)
        conn.commit()
    return total


def _batch_delete(
    conn: sqlite3.Connection,
    *,
    select_sql: str,
    delete_table: str,
    key_column: str,
    params: tuple[Any, ...],
    apply: bool,
) -> int:
    if not apply:
        return _count(conn, f"SELECT COUNT(*) FROM ({select_sql})", params)
    total = 0
    while True:
        keys = [
            row[0]
            for row in conn.execute(
                f"{select_sql} LIMIT ?",
                (*params, BATCH_SIZE),
            ).fetchall()
        ]
        if not keys:
            break
        placeholders = ",".join("?" for _ in keys)
        conn.execute(
            f"DELETE FROM {delete_table} WHERE {key_column} IN ({placeholders})",
            keys,
        )
        total += len(keys)
        conn.commit()
    return total


def run_retention(
    db_path: str | Path,
    *,
    apply: bool = False,
    now: int | None = None,
    policy_overrides: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Redact or delete expired Router records and return a machine-readable report."""
    path = Path(db_path)
    if not path.is_file():
        raise FileNotFoundError(path)
    current = int(time.time()) if now is None else int(now)
    conn = sqlite3.connect(path, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout = 10000")
    conn.execute("PRAGMA foreign_keys = ON")
    try:
        policy = load_policy(conn, policy_overrides)
        cutoffs = {key: _cutoff(current, days) for key, days in policy.items()}
        report: dict[str, Any] = {
            "apply": bool(apply),
            "now": current,
            "policy_days": policy,
            "cutoffs": cutoffs,
            "counts": {},
            "audit_recorded": False,
        }
        counts = report["counts"]

        counts["event_payloads_redacted"] = _batch_update(
            conn,
            select_sql=(
                "SELECT event_id FROM conversation_events "
                "WHERE created_at < ? AND payload_json IS NOT NULL"
            ),
            update_sql=(
                "UPDATE conversation_events SET payload_json = NULL "
                "WHERE event_id IN ({placeholders})"
            ),
            params=(cutoffs["payload_days"],),
            apply=apply,
        )

        counts["context_pack_refs_cleared"] = _batch_update(
            conn,
            select_sql=(
                "SELECT event_id FROM conversation_events "
                "WHERE payload_ref IN ("
                "SELECT 'context-pack://' || pack_id FROM context_pack_manifests "
                "WHERE created_at < ?"
                ")"
            ),
            update_sql=(
                "UPDATE conversation_events SET payload_ref = NULL "
                "WHERE event_id IN ({placeholders})"
            ),
            params=(cutoffs["context_pack_days"],),
            apply=apply,
        )

        counts["context_pack_manifests_deleted"] = _batch_delete(
            conn,
            select_sql=(
                "SELECT pack_id FROM context_pack_manifests "
                "WHERE created_at < ?"
            ),
            delete_table="context_pack_manifests",
            key_column="pack_id",
            params=(cutoffs["context_pack_days"],),
            apply=apply,
        )

        counts["conversation_events_deleted"] = _batch_delete(
            conn,
            select_sql=(
                "SELECT event_id FROM conversation_events WHERE created_at < ?"
            ),
            delete_table="conversation_events",
            key_column="event_id",
            params=(cutoffs["event_days"],),
            apply=apply,
        )

        counts["budget_ledgers_deleted"] = _batch_delete(
            conn,
            select_sql="SELECT id FROM budget_ledgers WHERE period_end < ?",
            delete_table="budget_ledgers",
            key_column="id",
            params=(cutoffs["budget_ledger_days"],),
            apply=apply,
        )

        terminal_runs = ",".join("?" for _ in TERMINAL_RUN_STATUSES)
        terminal_tasks = ",".join("?" for _ in TERMINAL_TASK_STATUSES)
        counts["terminal_task_runs_deleted"] = _batch_delete(
            conn,
            select_sql=(
                "SELECT r.run_id FROM task_runs r "
                "WHERE r.created_at < ? "
                f"AND r.status IN ({terminal_runs}) "
                "AND EXISTS ("
                "SELECT 1 FROM conversation_tasks t "
                "WHERE t.task_id = r.task_id "
                f"AND t.status IN ({terminal_tasks})"
                ")"
            ),
            delete_table="task_runs",
            key_column="run_id",
            params=(
                cutoffs["task_run_days"],
                *TERMINAL_RUN_STATUSES,
                *TERMINAL_TASK_STATUSES,
            ),
            apply=apply,
        )

        if apply:
            conn.execute(
                "INSERT INTO audit_log(ts, actor, action, target, payload) "
                "VALUES (?, ?, ?, ?, ?)",
                (
                    current,
                    "system:router-retention",
                    "router.retention.applied",
                    "router_phase0",
                    json.dumps(report, ensure_ascii=False, sort_keys=True),
                ),
            )
            conn.commit()
            report["audit_recorded"] = True
        return report
    finally:
        conn.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--db",
        default=os.environ.get("OCTOP_DB_PATH", "/opt/octop/octop.db"),
        help="SQLite database path",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Apply changes; without this flag the command is a dry run",
    )
    parser.add_argument(
        "--now",
        type=int,
        help="Override current Unix time for deterministic verification",
    )
    args = parser.parse_args(argv)
    report = run_retention(args.db, apply=args.apply, now=args.now)
    print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
