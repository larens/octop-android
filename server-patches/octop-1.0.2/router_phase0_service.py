"""Persistence and governance primitives for the mobile main conversation.

This module intentionally depends only on the small database-pool protocol.  The
Router and Worker layers can use it without importing FastAPI or the agent runtime.
"""

from __future__ import annotations

import json
import time
import uuid
from collections.abc import Mapping, Sequence
from typing import Any

from octop.infra.conversation.context import MAX_OMITTED_REFS

TASK_STATUSES = frozenset(
    {"open", "in_progress", "needs_user", "done", "failed", "cancelled", "expired"}
)
RUN_STATUSES = frozenset(
    {"queued", "running", "waiting_user", "succeeded", "failed", "cancelled", "interrupted"}
)
TERMINAL_TASK_STATUSES = frozenset({"done", "failed", "cancelled", "expired"})
TERMINAL_RUN_STATUSES = frozenset({"succeeded", "failed", "cancelled", "interrupted"})

TASK_TRANSITIONS: dict[str, frozenset[str]] = {
    "open": frozenset({"in_progress", "needs_user", "done", "failed", "cancelled", "expired"}),
    "in_progress": frozenset({"needs_user", "done", "failed", "cancelled", "expired"}),
    "needs_user": frozenset({"in_progress", "cancelled", "expired"}),
    "done": frozenset(),
    "failed": frozenset(),
    "cancelled": frozenset(),
    "expired": frozenset(),
}

RUN_TRANSITIONS: dict[str, frozenset[str]] = {
    "queued": frozenset({"running", "failed", "cancelled", "interrupted"}),
    "running": frozenset({"waiting_user", "succeeded", "failed", "cancelled", "interrupted"}),
    "waiting_user": frozenset({"running", "failed", "cancelled", "interrupted"}),
    "succeeded": frozenset(),
    "failed": frozenset(),
    "cancelled": frozenset(),
    "interrupted": frozenset(),
}

MAX_SUMMARY_CHARS = 2_000
MAX_TEXT_CHARS = 20_000
MAX_JSON_CHARS = 256_000
DEFAULT_MAX_ATTEMPTS = 3
HARD_MAX_ATTEMPTS = 5


class RouterPhase0Error(ValueError):
    """Base error for invalid Phase 0 operations."""


class NotFoundError(RouterPhase0Error):
    """The row does not exist inside the requested ownership scope."""


class InvalidTransitionError(RouterPhase0Error):
    """A state machine transition is not allowed."""


class RetryLimitExceededError(RouterPhase0Error):
    """A task has exhausted its bounded attempts."""


class BudgetExceededError(RouterPhase0Error):
    """A hard budget reservation would exceed its limit."""


def _now() -> int:
    return int(time.time())


def _new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex}"


def _row_value(row: Any, key: str, default: Any = None) -> Any:
    if row is None:
        return default
    if isinstance(row, Mapping):
        return row.get(key, default)
    try:
        return row[key]
    except (IndexError, KeyError, TypeError):
        return default


def _clip(value: str, limit: int) -> str:
    text = " ".join((value or "").split())
    if len(text) <= limit:
        return text
    return text[: limit - 3].rstrip() + "..."


def _json_text(value: Any, *, default: Any) -> str:
    payload = default if value is None else value
    text = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    if len(text) > MAX_JSON_CHARS:
        raise RouterPhase0Error("JSON payload exceeds the Phase 0 size limit")
    return text


def _json_value(raw: Any, *, default: Any) -> Any:
    if raw is None or raw == "":
        return default
    try:
        return json.loads(str(raw))
    except (TypeError, ValueError):
        return default


def _int_value(row: Any, key: str) -> int:
    return int(_row_value(row, key, 0) or 0)


def _conversation_payload(row: Any) -> dict[str, Any]:
    state = _json_value(_row_value(row, "state_json"), default={})
    return {
        "conversation_id": str(_row_value(row, "conversation_id")),
        "agent_id": str(_row_value(row, "agent_id")),
        "user_id": int(_row_value(row, "user_id")),
        "router_thread_id": _row_value(row, "router_thread_id"),
        "state": state,
        "summary": str(_row_value(row, "summary", "")),
        "last_conversation_seq": _int_value(row, "last_conversation_seq"),
        "created_at": _int_value(row, "created_at"),
        "updated_at": _int_value(row, "updated_at"),
        "archived_at": _row_value(row, "archived_at"),
    }


def _task_payload(row: Any, *, runs: Sequence[dict[str, Any]] | None = None) -> dict[str, Any]:
    payload = {
        "task_id": str(_row_value(row, "task_id")),
        "conversation_id": str(_row_value(row, "conversation_id")),
        "task_type": str(_row_value(row, "task_type")),
        "title": str(_row_value(row, "title")),
        "subject_key": str(_row_value(row, "subject_key")),
        "objective": str(_row_value(row, "objective")),
        "status": str(_row_value(row, "status")),
        "requires_user": bool(_row_value(row, "requires_user")),
        "capsule": _json_value(_row_value(row, "capsule_json"), default={}),
        "priority": _int_value(row, "priority"),
        "created_at": _int_value(row, "created_at"),
        "updated_at": _int_value(row, "updated_at"),
        "expires_at": _row_value(row, "expires_at"),
        "plan_id": _row_value(row, "plan_id"),
        "plan_item_id": _row_value(row, "plan_item_id"),
        "accepted_at": _row_value(row, "accepted_at"),
        "acceptance_source": _row_value(row, "acceptance_source"),
        "outcome_ref": _row_value(row, "outcome_ref"),
    }
    if runs is not None:
        payload["runs"] = list(runs)
    return payload


def _run_payload(row: Any) -> dict[str, Any]:
    return {
        "run_id": str(_row_value(row, "run_id")),
        "task_id": str(_row_value(row, "task_id")),
        "worker_thread_id": _row_value(row, "worker_thread_id"),
        "status": str(_row_value(row, "status")),
        "attempt_no": _int_value(row, "attempt_no"),
        "failure_class": _row_value(row, "failure_class"),
        "started_at": _row_value(row, "started_at"),
        "finished_at": _row_value(row, "finished_at"),
        "last_progress_at": _row_value(row, "last_progress_at"),
        "lease_until": _row_value(row, "lease_until"),
        "created_at": _int_value(row, "created_at"),
    }


def _event_payload(row: Any, *, include_payload: bool) -> dict[str, Any]:
    payload = {
        "event_id": str(_row_value(row, "event_id")),
        "conversation_id": str(_row_value(row, "conversation_id")),
        "trace_id": str(_row_value(row, "trace_id")),
        "task_id": _row_value(row, "task_id"),
        "run_id": _row_value(row, "run_id"),
        "parent_event_id": _row_value(row, "parent_event_id"),
        "plan_id": _row_value(row, "plan_id"),
        "plan_item_id": _row_value(row, "plan_item_id"),
        "seq": _int_value(row, "seq"),
        "schema_version": _int_value(row, "schema_version"),
        "event_type": str(_row_value(row, "event_type")),
        "status": _row_value(row, "status"),
        "summary": str(_row_value(row, "summary", "")),
        "payload_ref": _row_value(row, "payload_ref"),
        "usage": _json_value(_row_value(row, "usage_json"), default={}),
        "created_at": _int_value(row, "created_at"),
    }
    if include_payload:
        payload["payload"] = _json_value(_row_value(row, "payload_json"), default=None)
    return payload


def _budget_payload(row: Any) -> dict[str, Any]:
    return {
        "scope_type": str(_row_value(row, "scope_type")),
        "scope_id": str(_row_value(row, "scope_id")),
        "period_start": _int_value(row, "period_start"),
        "period_end": _int_value(row, "period_end"),
        "reserved_tokens": _int_value(row, "reserved_tokens"),
        "spent_tokens": _int_value(row, "spent_tokens"),
        "reserved_cost_micros": _int_value(row, "reserved_cost_micros"),
        "spent_cost_micros": _int_value(row, "spent_cost_micros"),
        "limit_tokens": _int_value(row, "limit_tokens"),
        "limit_cost_micros": _int_value(row, "limit_cost_micros"),
        "updated_at": _int_value(row, "updated_at"),
    }


def _context_pack_payload(row: Any) -> dict[str, Any]:
    omitted = _json_value(_row_value(row, "omitted_refs_json"), default=[])
    omitted_refs = omitted if isinstance(omitted, list) else []
    omitted_by_reason: dict[str, int] = {}
    for item in omitted_refs:
        if not isinstance(item, Mapping):
            continue
        reason = str(item.get("reason") or "unknown")
        omitted_by_reason[reason] = omitted_by_reason.get(reason, 0) + 1
    return {
        "pack_id": str(_row_value(row, "pack_id")),
        "conversation_id": str(_row_value(row, "conversation_id")),
        "trace_id": str(_row_value(row, "trace_id")),
        "policy_version": str(_row_value(row, "policy_version")),
        "model": str(_row_value(row, "model")),
        "used_tokens": _int_value(row, "used_tokens"),
        "sections": _json_value(_row_value(row, "sections_json"), default=[]),
        "included_refs": _json_value(_row_value(row, "included_refs_json"), default=[]),
        "omitted_refs": omitted_refs,
        "omitted_count": len(omitted_refs),
        "omitted_by_reason": dict(sorted(omitted_by_reason.items())),
        "omitted_refs_truncated": len(omitted_refs) >= MAX_OMITTED_REFS,
        "created_at": _int_value(row, "created_at"),
    }


class ConversationService:
    """Owns the Phase 0 state machines, trace ledger, budget ledger, and projection data."""

    def __init__(self, db: Any) -> None:
        self._db = db

    def ensure_main_conversation(
        self,
        *,
        agent_id: str,
        user_id: int,
        router_thread_id: str | None = None,
    ) -> dict[str, Any]:
        now = _now()
        with self._db.transaction() as conn:
            row = conn.execute(
                "SELECT * FROM main_conversations WHERE agent_id = ? AND user_id = ?",
                (agent_id, user_id),
            ).fetchone()
            if row is None:
                conn.execute(
                    "INSERT INTO main_conversations("
                    "conversation_id, agent_id, user_id, router_thread_id, state_json, summary, "
                    "last_conversation_seq, created_at, updated_at"
                    ") VALUES (?, ?, ?, ?, '{}', '', 0, ?, ?) "
                    "ON CONFLICT(agent_id, user_id) DO NOTHING",
                    (_new_id("conv"), agent_id, user_id, router_thread_id, now, now),
                )
                row = conn.execute(
                    "SELECT * FROM main_conversations WHERE agent_id = ? AND user_id = ?",
                    (agent_id, user_id),
                ).fetchone()
            elif (
                router_thread_id is not None
                and _row_value(row, "router_thread_id") != router_thread_id
            ):
                conn.execute(
                    "UPDATE main_conversations SET router_thread_id = ?, updated_at = ? "
                    "WHERE conversation_id = ?",
                    (router_thread_id, now, _row_value(row, "conversation_id")),
                )
                row = conn.execute(
                    "SELECT * FROM main_conversations WHERE conversation_id = ?",
                    (_row_value(row, "conversation_id"),),
                ).fetchone()
        if row is None:
            raise RouterPhase0Error("failed to create or load the main conversation")
        return _conversation_payload(row)

    def get_conversation(
        self, *, agent_id: str, user_id: int, conversation_id: str
    ) -> dict[str, Any]:
        with self._db.connect() as conn:
            row = conn.execute(
                "SELECT * FROM main_conversations "
                "WHERE conversation_id = ? AND agent_id = ? AND user_id = ?",
                (conversation_id, agent_id, user_id),
            ).fetchone()
        if row is None:
            raise NotFoundError("main conversation not found")
        return _conversation_payload(row)

    def create_task(
        self,
        *,
        conversation_id: str,
        task_type: str,
        title: str,
        subject_key: str,
        objective: str,
        priority: int = 0,
        requires_user: bool = False,
        expires_at: int | None = None,
        capsule: Any = None,
    ) -> dict[str, Any]:
        task_id = _new_id("task")
        now = _now()
        capsule_json = _json_text(capsule, default={}) if capsule is not None else "{}"
        with self._db.transaction() as conn:
            conversation = conn.execute(
                "SELECT conversation_id FROM main_conversations WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchone()
            if conversation is None:
                raise NotFoundError("main conversation not found")
            conn.execute(
                "INSERT INTO conversation_tasks("
                "task_id, conversation_id, task_type, title, subject_key, objective, status, "
                "requires_user, capsule_json, priority, created_at, updated_at, expires_at"
                ") VALUES (?, ?, ?, ?, ?, ?, 'open', ?, ?, ?, ?, ?, ?)",
                (
                    task_id,
                    conversation_id,
                    _clip(task_type, 80),
                    _clip(title, 200),
                    _clip(subject_key, 500),
                    _clip(objective, MAX_TEXT_CHARS),
                    1 if requires_user else 0,
                    capsule_json,
                    int(priority),
                    now,
                    now,
                    expires_at,
                ),
            )
            row = conn.execute(
                "SELECT * FROM conversation_tasks WHERE task_id = ?", (task_id,)
            ).fetchone()
        assert row is not None
        return _task_payload(row, runs=[])

    def create_read_only_task(
        self,
        *,
        conversation_id: str,
        title: str,
        subject_key: str,
        objective: str,
        capsule: Any,
        max_attempts: int = DEFAULT_MAX_ATTEMPTS,
        priority: int = 0,
        available_at: int = 0,
    ) -> dict[str, Any]:
        """Atomically deduplicate and queue one read-only task and its first Run."""
        if max_attempts < 1 or max_attempts > HARD_MAX_ATTEMPTS:
            raise RouterPhase0Error(f"max_attempts must be between 1 and {HARD_MAX_ATTEMPTS}")
        now = _now()
        task_id = _new_id("task")
        run_id = _new_id("run")
        capsule_json = _json_text(capsule, default={})
        clipped_subject = _clip(subject_key, 500)
        with self._db.transaction() as conn:
            conversation = conn.execute(
                "SELECT conversation_id FROM main_conversations WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchone()
            if conversation is None:
                raise NotFoundError("main conversation not found")
            existing = conn.execute(
                "SELECT * FROM conversation_tasks "
                "WHERE conversation_id = ? AND task_type = 'read_only' AND subject_key = ? "
                "AND status IN ('needs_user', 'in_progress', 'open') "
                "ORDER BY priority DESC, updated_at DESC LIMIT 1",
                (conversation_id, clipped_subject),
            ).fetchone()
            if existing is not None:
                run_rows = conn.execute(
                    "SELECT * FROM task_runs WHERE task_id = ? ORDER BY attempt_no DESC",
                    (_row_value(existing, "task_id"),),
                ).fetchall()
                return {
                    "task": _task_payload(existing),
                    "runs": [_run_payload(row) for row in run_rows],
                    "deduplicated": True,
                }
            conn.execute(
                "INSERT INTO conversation_tasks("
                "task_id, conversation_id, task_type, title, subject_key, objective, status, "
                "requires_user, capsule_json, priority, created_at, updated_at, expires_at"
                ") VALUES (?, ?, 'read_only', ?, ?, ?, 'open', 0, ?, ?, ?, ?, NULL)",
                (
                    task_id,
                    conversation_id,
                    _clip(title, 200),
                    clipped_subject,
                    _clip(objective, MAX_TEXT_CHARS),
                    capsule_json,
                    int(priority),
                    now,
                    now,
                ),
            )
            conn.execute(
                "INSERT INTO task_runs("
                "run_id, task_id, worker_thread_id, status, attempt_no, lease_until, created_at"
                ") VALUES (?, ?, NULL, 'queued', 1, ?, ?)",
                (run_id, task_id, max(now, int(available_at or 0)), now),
            )
            conn.execute(
                "UPDATE conversation_tasks SET status = 'in_progress', updated_at = ? "
                "WHERE task_id = ?",
                (now, task_id),
            )
            task_row = conn.execute(
                "SELECT * FROM conversation_tasks WHERE task_id = ?", (task_id,)
            ).fetchone()
            run_row = conn.execute("SELECT * FROM task_runs WHERE run_id = ?", (run_id,)).fetchone()
        if task_row is None or run_row is None:
            raise RouterPhase0Error("failed to create the read-only task")
        return {
            "task": _task_payload(task_row),
            "runs": [_run_payload(run_row)],
            "deduplicated": False,
        }

    def transition_task(
        self,
        *,
        task_id: str,
        status: str,
        capsule: Any = None,
    ) -> dict[str, Any]:
        if status not in TASK_STATUSES:
            raise InvalidTransitionError(f"unknown task status: {status}")
        now = _now()
        with self._db.transaction() as conn:
            row = conn.execute(
                "SELECT * FROM conversation_tasks WHERE task_id = ?", (task_id,)
            ).fetchone()
            if row is None:
                raise NotFoundError("task not found")
            current = str(_row_value(row, "status"))
            if current != status and status not in TASK_TRANSITIONS[current]:
                raise InvalidTransitionError(f"task cannot move from {current} to {status}")
            capsule_json = _row_value(row, "capsule_json", "{}")
            if capsule is not None:
                capsule_json = _json_text(capsule, default={})
            conn.execute(
                "UPDATE conversation_tasks SET status = ?, capsule_json = ?, updated_at = ? "
                "WHERE task_id = ?",
                (status, capsule_json, now, task_id),
            )
            updated = conn.execute(
                "SELECT * FROM conversation_tasks WHERE task_id = ?", (task_id,)
            ).fetchone()
        assert updated is not None
        return _task_payload(updated)

    def create_run(
        self,
        *,
        task_id: str,
        worker_thread_id: str | None = None,
        max_attempts: int = DEFAULT_MAX_ATTEMPTS,
        available_at: int = 0,
    ) -> dict[str, Any]:
        if max_attempts < 1 or max_attempts > HARD_MAX_ATTEMPTS:
            raise RouterPhase0Error(f"max_attempts must be between 1 and {HARD_MAX_ATTEMPTS}")
        run_id = _new_id("run")
        now = _now()
        with self._db.transaction() as conn:
            task = conn.execute(
                "SELECT status FROM conversation_tasks WHERE task_id = ?", (task_id,)
            ).fetchone()
            if task is None:
                raise NotFoundError("task not found")
            task_status = str(_row_value(task, "status"))
            if task_status in TERMINAL_TASK_STATUSES:
                raise InvalidTransitionError(f"cannot run terminal task {task_status}")
            attempts = conn.execute(
                "SELECT COALESCE(MAX(attempt_no), 0) FROM task_runs WHERE task_id = ?",
                (task_id,),
            ).fetchone()
            next_attempt = int(attempts[0] if attempts is not None else 0) + 1
            if next_attempt > max_attempts:
                raise RetryLimitExceededError(f"task {task_id} exhausted {max_attempts} attempts")
            conn.execute(
                "INSERT INTO task_runs("
                "run_id, task_id, worker_thread_id, status, attempt_no, lease_until, created_at"
                ") VALUES (?, ?, ?, 'queued', ?, ?, ?)",
                (
                    run_id,
                    task_id,
                    worker_thread_id,
                    next_attempt,
                    max(now, int(available_at or 0)),
                    now,
                ),
            )
            if task_status in {"open", "needs_user"}:
                conn.execute(
                    "UPDATE conversation_tasks SET status = 'in_progress', updated_at = ? "
                    "WHERE task_id = ?",
                    (now, task_id),
                )
            row = conn.execute("SELECT * FROM task_runs WHERE run_id = ?", (run_id,)).fetchone()
        assert row is not None
        return _run_payload(row)

    def get_run(self, *, run_id: str) -> dict[str, Any]:
        with self._db.connect() as conn:
            row = conn.execute("SELECT * FROM task_runs WHERE run_id = ?", (run_id,)).fetchone()
        if row is None:
            raise NotFoundError("run not found")
        return _run_payload(row)

    def claim_next_read_only_run(
        self,
        *,
        now: int,
        lease_seconds: int,
    ) -> dict[str, Any] | None:
        """Atomically claim one due read-only Run and return its task/context."""
        lease_until = int(now) + max(1, int(lease_seconds))
        with self._db.transaction() as conn:
            row = conn.execute(
                """
                UPDATE task_runs
                   SET status = 'running',
                       started_at = COALESCE(started_at, ?),
                       last_progress_at = ?,
                       lease_until = ?
                 WHERE run_id = (
                       SELECT r.run_id
                         FROM task_runs r
                         JOIN conversation_tasks t ON t.task_id = r.task_id
                        WHERE r.status = 'queued'
                          AND COALESCE(r.lease_until, 0) <= ?
                          AND t.task_type = 'read_only'
                          AND t.status IN ('open', 'in_progress')
                          AND t.requires_user = 0
                          AND (t.expires_at IS NULL OR t.expires_at > ?)
                          AND NOT EXISTS (
                              SELECT 1
                                FROM task_runs active
                               WHERE active.task_id = r.task_id
                                 AND active.run_id <> r.run_id
                                 AND active.status IN ('queued', 'running', 'waiting_user')
                          )
                        ORDER BY t.priority DESC, r.created_at ASC
                        LIMIT 1
                 )
                   AND status = 'queued'
                RETURNING *
                """,
                (now, now, lease_until, now, now),
            ).fetchone()
            if row is None:
                return None
            run = _run_payload(row)
            task_row = conn.execute(
                "SELECT * FROM conversation_tasks WHERE task_id = ?",
                (run["task_id"],),
            ).fetchone()
            if task_row is None:
                raise NotFoundError("claimed task not found")
            task = _task_payload(task_row)
            conversation_row = conn.execute(
                "SELECT * FROM main_conversations WHERE conversation_id = ?",
                (task["conversation_id"],),
            ).fetchone()
            if conversation_row is None:
                raise NotFoundError("claimed conversation not found")
        return {
            "run": run,
            "task": task,
            "conversation": _conversation_payload(conversation_row),
        }

    def bind_run_worker_thread(self, *, run_id: str, worker_thread_id: str) -> dict[str, Any]:
        with self._db.transaction() as conn:
            cursor = conn.execute(
                "UPDATE task_runs SET worker_thread_id = ?, last_progress_at = ? "
                "WHERE run_id = ? AND status = 'running'",
                (worker_thread_id, _now(), run_id),
            )
            if cursor.rowcount != 1:
                raise InvalidTransitionError("run is no longer running")
            row = conn.execute("SELECT * FROM task_runs WHERE run_id = ?", (run_id,)).fetchone()
        if row is None:
            raise NotFoundError("run not found")
        return _run_payload(row)

    def renew_run_lease(self, *, run_id: str, lease_until: int) -> bool:
        """Extend a live lease. A false result means another actor ended the Run."""
        now = _now()
        with self._db.transaction() as conn:
            cursor = conn.execute(
                "UPDATE task_runs SET lease_until = ?, last_progress_at = ? "
                "WHERE run_id = ? AND status = 'running'",
                (int(lease_until), now, run_id),
            )
        return bool(cursor.rowcount == 1)

    def recover_expired_runs(self, *, now: int) -> list[dict[str, Any]]:
        """Mark leases lost by a crashed process as interrupted, without retry loops."""
        with self._db.transaction() as conn:
            rows = conn.execute(
                "SELECT * FROM task_runs "
                "WHERE status = 'running' AND lease_until IS NOT NULL AND lease_until <= ? "
                "ORDER BY created_at ASC",
                (now,),
            ).fetchall()
            recovered: list[dict[str, Any]] = []
            for row in rows:
                run_id = str(_row_value(row, "run_id"))
                cursor = conn.execute(
                    "UPDATE task_runs SET status = 'interrupted', failure_class = ?, "
                    "finished_at = ?, last_progress_at = ?, lease_until = NULL "
                    "WHERE run_id = ? AND status = 'running'",
                    ("lease_expired", now, now, run_id),
                )
                if cursor.rowcount == 1:
                    updated = conn.execute(
                        "SELECT * FROM task_runs WHERE run_id = ?", (run_id,)
                    ).fetchone()
                    if updated is not None:
                        recovered.append(_run_payload(updated))
            return recovered

    def list_resumable_read_only_tasks(
        self,
        *,
        now: int,
        limit: int = 20,
    ) -> list[dict[str, Any]]:
        """Find interrupted tasks that have no queued/running attempt."""
        with self._db.connect() as conn:
            rows = conn.execute(
                """
                SELECT t.*
                  FROM conversation_tasks t
                 WHERE t.task_type = 'read_only'
                   AND t.status = 'in_progress'
                   AND t.requires_user = 0
                   AND (t.expires_at IS NULL OR t.expires_at > ?)
                   AND NOT EXISTS (
                       SELECT 1 FROM task_runs r
                        WHERE r.task_id = t.task_id
                          AND r.status IN ('queued', 'running', 'waiting_user')
                   )
                 ORDER BY t.priority DESC, t.updated_at ASC
                 LIMIT ?
                """,
                (now, max(1, min(int(limit), 100))),
            ).fetchall()
        return [_task_payload(row) for row in rows]

    def cancel_task(self, *, task_id: str) -> dict[str, Any]:
        now = _now()
        with self._db.transaction() as conn:
            task_row = conn.execute(
                "SELECT * FROM conversation_tasks WHERE task_id = ?", (task_id,)
            ).fetchone()
            if task_row is None:
                raise NotFoundError("task not found")
            status = str(_row_value(task_row, "status"))
            if status in TERMINAL_TASK_STATUSES:
                return _task_payload(task_row)
            conn.execute(
                "UPDATE conversation_tasks SET status = 'cancelled', updated_at = ? "
                "WHERE task_id = ?",
                (now, task_id),
            )
            conn.execute(
                "UPDATE task_runs SET status = 'cancelled', failure_class = ?, "
                "finished_at = ?, last_progress_at = ?, lease_until = NULL "
                "WHERE task_id = ? AND status IN ('queued', 'running', 'waiting_user')",
                ("user_cancelled", now, now, task_id),
            )
            updated = conn.execute(
                "SELECT * FROM conversation_tasks WHERE task_id = ?", (task_id,)
            ).fetchone()
        if updated is None:
            raise NotFoundError("task not found")
        return _task_payload(updated)

    def transition_run(
        self,
        *,
        run_id: str,
        status: str,
        failure_class: str | None = None,
        lease_until: int | None = None,
    ) -> dict[str, Any]:
        if status not in RUN_STATUSES:
            raise InvalidTransitionError(f"unknown run status: {status}")
        now = _now()
        with self._db.transaction() as conn:
            row = conn.execute("SELECT * FROM task_runs WHERE run_id = ?", (run_id,)).fetchone()
            if row is None:
                raise NotFoundError("run not found")
            current = str(_row_value(row, "status"))
            if current != status and status not in RUN_TRANSITIONS[current]:
                raise InvalidTransitionError(f"run cannot move from {current} to {status}")
            started_at = _row_value(row, "started_at")
            if status == "running" and started_at is None:
                started_at = now
            finished_at = now if status in TERMINAL_RUN_STATUSES else _row_value(row, "finished_at")
            progress_at = now
            conn.execute(
                "UPDATE task_runs SET status = ?, failure_class = ?, started_at = ?, "
                "finished_at = ?, last_progress_at = ?, lease_until = ? WHERE run_id = ?",
                (
                    status,
                    _clip(failure_class, 100) if failure_class else None,
                    started_at,
                    finished_at,
                    progress_at,
                    lease_until,
                    run_id,
                ),
            )
            updated = conn.execute("SELECT * FROM task_runs WHERE run_id = ?", (run_id,)).fetchone()
        assert updated is not None
        return _run_payload(updated)

    def list_tasks(
        self,
        *,
        agent_id: str,
        user_id: int,
        conversation_id: str | None = None,
        status: str | None = None,
        limit: int = 50,
    ) -> list[dict[str, Any]]:
        if status is not None and status not in TASK_STATUSES:
            raise RouterPhase0Error(f"unknown task status: {status}")
        clauses = ["c.agent_id = ?", "c.user_id = ?"]
        params: list[Any] = [agent_id, user_id]
        if conversation_id is not None:
            clauses.append("t.conversation_id = ?")
            params.append(conversation_id)
        if status is not None:
            clauses.append("t.status = ?")
            params.append(status)
        params.append(max(1, min(int(limit), 500)))
        with self._db.connect() as conn:
            rows = conn.execute(
                "SELECT t.* FROM conversation_tasks t "
                "JOIN main_conversations c ON c.conversation_id = t.conversation_id "
                f"WHERE {' AND '.join(clauses)} "
                "ORDER BY t.priority DESC, t.updated_at DESC LIMIT ?",
                tuple(params),
            ).fetchall()
        return [_task_payload(row) for row in rows]

    def list_active_tasks(
        self,
        *,
        agent_id: str,
        user_id: int,
        conversation_id: str | None = None,
        limit: int = 20,
    ) -> list[dict[str, Any]]:
        """Return the task capsules worth considering for the next Router turn."""
        clauses = [
            "c.agent_id = ?",
            "c.user_id = ?",
            "t.status IN ('needs_user', 'in_progress', 'open')",
        ]
        params: list[Any] = [agent_id, user_id]
        if conversation_id is not None:
            clauses.append("t.conversation_id = ?")
            params.append(conversation_id)
        params.append(max(1, min(int(limit), 50)))
        with self._db.connect() as conn:
            rows = conn.execute(
                "SELECT t.* FROM conversation_tasks t "
                "JOIN main_conversations c ON c.conversation_id = t.conversation_id "
                f"WHERE {' AND '.join(clauses)} "
                "ORDER BY CASE t.status "
                "WHEN 'needs_user' THEN 0 WHEN 'in_progress' THEN 1 ELSE 2 END, "
                "t.priority DESC, t.updated_at DESC LIMIT ?",
                tuple(params),
            ).fetchall()
        return [_task_payload(row) for row in rows]

    def get_task(self, *, agent_id: str, user_id: int, task_id: str) -> dict[str, Any]:
        with self._db.connect() as conn:
            row = conn.execute(
                "SELECT t.* FROM conversation_tasks t "
                "JOIN main_conversations c ON c.conversation_id = t.conversation_id "
                "WHERE t.task_id = ? AND c.agent_id = ? AND c.user_id = ?",
                (task_id, agent_id, user_id),
            ).fetchone()
            if row is None:
                raise NotFoundError("task not found")
            run_rows = conn.execute(
                "SELECT * FROM task_runs WHERE task_id = ? ORDER BY attempt_no DESC",
                (task_id,),
            ).fetchall()
        return _task_payload(row, runs=[_run_payload(run) for run in run_rows])

    def get_task_context(self, *, task_id: str) -> dict[str, Any]:
        """Internal queue lookup: task plus owning conversation, without user scoping."""
        with self._db.connect() as conn:
            row = conn.execute(
                "SELECT t.*, c.agent_id AS owner_agent_id, c.user_id AS owner_user_id "
                "FROM conversation_tasks t "
                "JOIN main_conversations c ON c.conversation_id = t.conversation_id "
                "WHERE t.task_id = ?",
                (task_id,),
            ).fetchone()
            if row is None:
                raise NotFoundError("task not found")
            run_rows = conn.execute(
                "SELECT * FROM task_runs WHERE task_id = ? ORDER BY attempt_no DESC",
                (task_id,),
            ).fetchall()
        return {
            "task": _task_payload(row),
            "agent_id": str(_row_value(row, "owner_agent_id")),
            "user_id": int(_row_value(row, "owner_user_id")),
            "runs": [_run_payload(run) for run in run_rows],
        }

    def append_event(
        self,
        *,
        conversation_id: str,
        trace_id: str,
        event_type: str,
        status: str | None = None,
        summary: str = "",
        task_id: str | None = None,
        run_id: str | None = None,
        parent_event_id: str | None = None,
        plan_id: str | None = None,
        plan_item_id: str | None = None,
        payload_ref: str | None = None,
        payload: Any = None,
        usage: Any = None,
        event_id: str | None = None,
    ) -> dict[str, Any]:
        event_id = event_id or _new_id("evt")
        trace_id = _clip(trace_id, 200)
        event_type = _clip(event_type, 120)
        if not trace_id or not event_type:
            raise RouterPhase0Error("trace_id and event_type are required")
        usage_json = _json_text(usage, default={})
        now = _now()
        with self._db.transaction() as conn:
            existing = conn.execute(
                "SELECT * FROM conversation_events WHERE event_id = ?", (event_id,)
            ).fetchone()
            if existing is not None:
                if str(_row_value(existing, "conversation_id")) != conversation_id:
                    raise RouterPhase0Error("event_id is already used by another conversation")
                return _event_payload(existing, include_payload=True)
            conversation = conn.execute(
                "SELECT last_conversation_seq FROM main_conversations WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchone()
            if conversation is None:
                raise NotFoundError("main conversation not found")
            linked_plan_id, linked_plan_item_id = self._validate_event_references(
                conn,
                conversation_id=conversation_id,
                task_id=task_id,
                run_id=run_id,
                parent_event_id=parent_event_id,
            )
            plan_id = plan_id or linked_plan_id
            plan_item_id = plan_item_id or linked_plan_item_id
            event_payload = payload
            if task_id is not None and (
                plan_id is not None or plan_item_id is not None
            ):
                if event_payload is None:
                    event_payload = {}
                if isinstance(event_payload, Mapping):
                    event_payload = dict(event_payload)
                    event_payload.setdefault("plan_id", plan_id)
                    event_payload.setdefault("plan_item_id", plan_item_id)
            payload_json = (
                _json_text(event_payload, default={})
                if event_payload is not None
                else None
            )
            seq_row = conn.execute(
                "UPDATE main_conversations "
                "SET last_conversation_seq = last_conversation_seq + 1, updated_at = ? "
                "WHERE conversation_id = ? RETURNING last_conversation_seq",
                (now, conversation_id),
            ).fetchone()
            if seq_row is None:
                raise NotFoundError("main conversation not found")
            seq = int(
                seq_row[0] if not isinstance(seq_row, Mapping) else seq_row["last_conversation_seq"]
            )
            conn.execute(
                "INSERT INTO conversation_events("
                "event_id, conversation_id, trace_id, task_id, run_id, parent_event_id, "
                "plan_id, plan_item_id, seq, schema_version, event_type, status, summary, "
                "payload_ref, payload_json, usage_json, created_at"
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)",
                (
                    event_id,
                    conversation_id,
                    trace_id,
                    task_id,
                    run_id,
                    parent_event_id,
                    plan_id,
                    plan_item_id,
                    seq,
                    event_type,
                    _clip(status, 80) if status else None,
                    _clip(summary, MAX_SUMMARY_CHARS),
                    _clip(payload_ref, 1_000) if payload_ref else None,
                    payload_json,
                    usage_json,
                    now,
                ),
            )
            row = conn.execute(
                "SELECT * FROM conversation_events WHERE event_id = ?", (event_id,)
            ).fetchone()
        assert row is not None
        return _event_payload(row, include_payload=True)

    def _validate_event_references(
        self,
        conn: Any,
        *,
        conversation_id: str,
        task_id: str | None,
        run_id: str | None,
        parent_event_id: str | None,
    ) -> tuple[str | None, str | None]:
        linked_plan_id: str | None = None
        linked_plan_item_id: str | None = None
        if task_id is not None:
            task = conn.execute(
                "SELECT conversation_id, plan_id, plan_item_id "
                "FROM conversation_tasks WHERE task_id = ?",
                (task_id,),
            ).fetchone()
            if task is None:
                raise NotFoundError("task not found")
            if str(_row_value(task, "conversation_id")) != conversation_id:
                raise RouterPhase0Error("task does not belong to this conversation")
            linked_plan_id = _row_value(task, "plan_id")
            linked_plan_item_id = _row_value(task, "plan_item_id")

        if run_id is not None:
            run = conn.execute(
                "SELECT r.task_id, t.conversation_id FROM task_runs r "
                "JOIN conversation_tasks t ON t.task_id = r.task_id "
                "WHERE r.run_id = ?",
                (run_id,),
            ).fetchone()
            if run is None:
                raise NotFoundError("run not found")
            run_task_id = str(_row_value(run, "task_id"))
            if task_id is not None and task_id != run_task_id:
                raise RouterPhase0Error("run does not belong to the supplied task")
            if str(_row_value(run, "conversation_id")) != conversation_id:
                raise RouterPhase0Error("run does not belong to this conversation")

        if parent_event_id is not None:
            parent = conn.execute(
                "SELECT conversation_id FROM conversation_events WHERE event_id = ?",
                (parent_event_id,),
            ).fetchone()
            if parent is None:
                raise NotFoundError("parent event not found")
            if str(_row_value(parent, "conversation_id")) != conversation_id:
                raise RouterPhase0Error("parent event does not belong to this conversation")
        return linked_plan_id, linked_plan_item_id

    def list_events(
        self,
        *,
        agent_id: str,
        user_id: int,
        conversation_id: str | None = None,
        after_seq: int = 0,
        limit: int = 100,
        include_payload: bool = False,
        include_plan_payload: bool = False,
    ) -> list[dict[str, Any]]:
        clauses = ["c.agent_id = ?", "c.user_id = ?", "e.seq > ?"]
        params: list[Any] = [agent_id, user_id, max(0, int(after_seq))]
        if conversation_id is not None:
            clauses.append("e.conversation_id = ?")
            params.append(conversation_id)
        params.append(max(1, min(int(limit), 500)))
        with self._db.connect() as conn:
            rows = conn.execute(
                "SELECT e.* FROM conversation_events e "
                "JOIN main_conversations c ON c.conversation_id = e.conversation_id "
                f"WHERE {' AND '.join(clauses)} ORDER BY e.seq ASC LIMIT ?",
                tuple(params),
            ).fetchall()
        return [
            _event_payload(
                row,
                include_payload=include_payload
                or (
                    include_plan_payload
                    and str(_row_value(row, "event_type", "")).startswith(
                        ("plan.", "task.approval_")
                    )
                ),
            )
            for row in rows
        ]

    def get_trace(
        self,
        *,
        agent_id: str,
        user_id: int,
        trace_id: str,
        limit: int = 500,
        include_payload: bool = False,
    ) -> dict[str, Any]:
        with self._db.connect() as conn:
            rows = conn.execute(
                "SELECT e.* FROM conversation_events e "
                "JOIN main_conversations c ON c.conversation_id = e.conversation_id "
                "WHERE e.trace_id = ? AND c.agent_id = ? AND c.user_id = ? "
                "ORDER BY e.seq ASC LIMIT ?",
                (trace_id, agent_id, user_id, max(1, min(int(limit), 500))),
            ).fetchall()
            pack_rows = conn.execute(
                "SELECT p.* FROM context_pack_manifests p "
                "JOIN main_conversations c ON c.conversation_id = p.conversation_id "
                "WHERE p.trace_id = ? AND c.agent_id = ? AND c.user_id = ? "
                "ORDER BY p.created_at DESC",
                (trace_id, agent_id, user_id),
            ).fetchall()
        if not rows and not pack_rows:
            raise NotFoundError("trace not found")
        task_ids = list(
            dict.fromkeys(
                str(_row_value(row, "task_id")) for row in rows if _row_value(row, "task_id")
            )
        )
        tasks = [
            self.get_task(agent_id=agent_id, user_id=user_id, task_id=task_id)
            for task_id in task_ids
        ]
        return {
            "trace_id": trace_id,
            "events": [_event_payload(row, include_payload=include_payload) for row in rows],
            "tasks": tasks,
            "context_packs": [_context_pack_payload(row) for row in pack_rows],
        }

    def record_context_pack(
        self,
        *,
        conversation_id: str,
        trace_id: str,
        policy_version: str,
        model: str,
        used_tokens: int,
        sections: Any,
        included_refs: Any,
        omitted_refs: Any,
    ) -> dict[str, Any]:
        pack_id = _new_id("pack")
        now = _now()
        sections_json = _json_text(sections, default=[])
        included_json = _json_text(included_refs, default=[])
        omitted_json = _json_text(omitted_refs, default=[])
        with self._db.transaction() as conn:
            conversation = conn.execute(
                "SELECT conversation_id FROM main_conversations WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchone()
            if conversation is None:
                raise NotFoundError("main conversation not found")
            conn.execute(
                "INSERT INTO context_pack_manifests("
                "pack_id, conversation_id, trace_id, policy_version, model, used_tokens, "
                "sections_json, included_refs_json, omitted_refs_json, created_at"
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    pack_id,
                    conversation_id,
                    _clip(trace_id, 200),
                    _clip(policy_version, 80),
                    _clip(model, 200),
                    max(0, int(used_tokens)),
                    sections_json,
                    included_json,
                    omitted_json,
                    now,
                ),
            )
            row = conn.execute(
                "SELECT * FROM context_pack_manifests WHERE pack_id = ?", (pack_id,)
            ).fetchone()
        assert row is not None
        return _context_pack_payload(row)

    def get_context_pack(
        self,
        *,
        conversation_id: str,
        pack_id: str,
    ) -> dict[str, Any]:
        with self._db.connect() as conn:
            row = conn.execute(
                "SELECT * FROM context_pack_manifests WHERE pack_id = ? AND conversation_id = ?",
                (pack_id, conversation_id),
            ).fetchone()
        if row is None:
            raise NotFoundError("context pack not found")
        return _context_pack_payload(row)

    def _ensure_budget_ledger(
        self,
        conn: Any,
        *,
        scope_type: str,
        scope_id: str,
        period_start: int,
        period_end: int,
        limit_tokens: int,
        limit_cost_micros: int,
    ) -> None:
        if period_end <= period_start:
            raise RouterPhase0Error("budget period_end must be after period_start")
        if limit_tokens < 0 or limit_cost_micros < 0:
            raise RouterPhase0Error("budget limits must be non-negative")
        conn.execute(
            "INSERT INTO budget_ledgers("
            "scope_type, scope_id, period_start, period_end, reserved_tokens, spent_tokens, "
            "reserved_cost_micros, spent_cost_micros, limit_tokens, limit_cost_micros, updated_at"
            ") VALUES (?, ?, ?, ?, 0, 0, 0, 0, ?, ?, ?) "
            "ON CONFLICT(scope_type, scope_id, period_start, period_end) DO NOTHING",
            (
                scope_type,
                scope_id,
                period_start,
                period_end,
                limit_tokens,
                limit_cost_micros,
                _now(),
            ),
        )

    def reserve_budget(
        self,
        *,
        scope_type: str,
        scope_id: str,
        period_start: int,
        period_end: int,
        tokens: int,
        cost_micros: int,
        limit_tokens: int,
        limit_cost_micros: int,
    ) -> dict[str, Any]:
        if tokens < 0 or cost_micros < 0:
            raise RouterPhase0Error("budget reservations must be non-negative")
        with self._db.transaction() as conn:
            self._ensure_budget_ledger(
                conn,
                scope_type=scope_type,
                scope_id=scope_id,
                period_start=period_start,
                period_end=period_end,
                limit_tokens=limit_tokens,
                limit_cost_micros=limit_cost_micros,
            )
            cursor = conn.execute(
                "UPDATE budget_ledgers SET "
                "reserved_tokens = reserved_tokens + ?, "
                "reserved_cost_micros = reserved_cost_micros + ?, updated_at = ? "
                "WHERE scope_type = ? AND scope_id = ? AND period_start = ? AND period_end = ? "
                "AND spent_tokens + reserved_tokens + ? <= limit_tokens "
                "AND spent_cost_micros + reserved_cost_micros + ? <= limit_cost_micros",
                (
                    tokens,
                    cost_micros,
                    _now(),
                    scope_type,
                    scope_id,
                    period_start,
                    period_end,
                    tokens,
                    cost_micros,
                ),
            )
            if cursor.rowcount != 1:
                raise BudgetExceededError("hard budget limit would be exceeded")
            row = conn.execute(
                "SELECT * FROM budget_ledgers "
                "WHERE scope_type = ? AND scope_id = ? AND period_start = ? AND period_end = ?",
                (scope_type, scope_id, period_start, period_end),
            ).fetchone()
        assert row is not None
        return _budget_payload(row)

    def check_budget(
        self,
        *,
        scope_type: str,
        scope_id: str,
        period_start: int,
        period_end: int,
        tokens: int,
        cost_micros: int,
        limit_tokens: int,
        limit_cost_micros: int,
        baseline_tokens: int = 0,
        baseline_cost_micros: int = 0,
    ) -> dict[str, Any]:
        """Read-only admission check used by Phase 0 shadow evaluation."""
        if period_end <= period_start:
            raise RouterPhase0Error("budget period_end must be after period_start")
        if any(
            value < 0
            for value in (
                tokens,
                cost_micros,
                limit_tokens,
                limit_cost_micros,
                baseline_tokens,
                baseline_cost_micros,
            )
        ):
            raise RouterPhase0Error("budget values must be non-negative")
        with self._db.connect() as conn:
            row = conn.execute(
                "SELECT * FROM budget_ledgers "
                "WHERE scope_type = ? AND scope_id = ? AND period_start = ? AND period_end = ?",
                (scope_type, scope_id, period_start, period_end),
            ).fetchone()
        if row is None:
            return {
                "scope_type": scope_type,
                "scope_id": scope_id,
                "would_block": baseline_tokens + tokens > limit_tokens
                or baseline_cost_micros + cost_micros > limit_cost_micros,
                "reason": (
                    "limit_exceeded"
                    if baseline_tokens + tokens > limit_tokens
                    or baseline_cost_micros + cost_micros > limit_cost_micros
                    else None
                ),
                "projected_tokens": baseline_tokens + tokens,
                "projected_cost_micros": baseline_cost_micros + cost_micros,
                "baseline_tokens": baseline_tokens,
                "baseline_cost_micros": baseline_cost_micros,
                "limit_tokens": limit_tokens,
                "limit_cost_micros": limit_cost_micros,
            }
        ledger_tokens = _int_value(row, "spent_tokens") + _int_value(row, "reserved_tokens")
        ledger_cost = _int_value(row, "spent_cost_micros") + _int_value(row, "reserved_cost_micros")
        projected_tokens = max(ledger_tokens, baseline_tokens) + tokens
        projected_cost = max(ledger_cost, baseline_cost_micros) + cost_micros
        active_token_limit = min(_int_value(row, "limit_tokens"), limit_tokens)
        active_cost_limit = min(_int_value(row, "limit_cost_micros"), limit_cost_micros)
        would_block = projected_tokens > active_token_limit or projected_cost > active_cost_limit
        return {
            "scope_type": scope_type,
            "scope_id": scope_id,
            "would_block": would_block,
            "reason": "limit_exceeded" if would_block else None,
            "projected_tokens": projected_tokens,
            "projected_cost_micros": projected_cost,
            "baseline_tokens": baseline_tokens,
            "baseline_cost_micros": baseline_cost_micros,
            "limit_tokens": active_token_limit,
            "limit_cost_micros": active_cost_limit,
        }

    def reserve_budgets(self, reservations: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
        """Atomically reserve multiple ledger scopes for one model run.

        A failure rolls back every scope, preventing a partial policy from
        admitting work against only part of the hierarchy.
        """
        results: list[dict[str, Any]] = []
        with self._db.transaction() as conn:
            for reservation in reservations:
                scope_type = str(reservation.get("scope_type") or "")
                scope_id = str(reservation.get("scope_id") or "")
                period_start = int(reservation.get("period_start", 0))
                period_end = int(reservation.get("period_end", 0))
                tokens = int(reservation.get("tokens", 0))
                cost_micros = int(reservation.get("cost_micros", 0))
                limit_tokens = int(reservation.get("limit_tokens", 0))
                limit_cost_micros = int(reservation.get("limit_cost_micros", 0))
                if not scope_type or not scope_id:
                    raise RouterPhase0Error("budget scope_type and scope_id are required")
                if (
                    period_end <= period_start
                    or tokens < 0
                    or cost_micros < 0
                    or limit_tokens < 0
                    or limit_cost_micros < 0
                ):
                    raise RouterPhase0Error("invalid budget reservation")
                self._ensure_budget_ledger(
                    conn,
                    scope_type=scope_type,
                    scope_id=scope_id,
                    period_start=period_start,
                    period_end=period_end,
                    limit_tokens=limit_tokens,
                    limit_cost_micros=limit_cost_micros,
                )
                cursor = conn.execute(
                    "UPDATE budget_ledgers SET "
                    "reserved_tokens = reserved_tokens + ?, "
                    "reserved_cost_micros = reserved_cost_micros + ?, updated_at = ? "
                    "WHERE scope_type = ? AND scope_id = ? "
                    "AND period_start = ? AND period_end = ? "
                    "AND spent_tokens + reserved_tokens + ? <= limit_tokens "
                    "AND spent_cost_micros + reserved_cost_micros + ? <= limit_cost_micros",
                    (
                        tokens,
                        cost_micros,
                        _now(),
                        scope_type,
                        scope_id,
                        period_start,
                        period_end,
                        tokens,
                        cost_micros,
                    ),
                )
                if cursor.rowcount != 1:
                    raise BudgetExceededError(
                        f"hard budget limit would be exceeded for {scope_type}:{scope_id}"
                    )
                row = conn.execute(
                    "SELECT * FROM budget_ledgers "
                    "WHERE scope_type = ? AND scope_id = ? "
                    "AND period_start = ? AND period_end = ?",
                    (scope_type, scope_id, period_start, period_end),
                ).fetchone()
                assert row is not None
                result = _budget_payload(row)
                # Ledger balances are cumulative across concurrent reservations.
                # Settlement must release only this caller's reservation, not the
                # aggregate balance observed after the UPDATE.
                result["reservation_tokens"] = tokens
                result["reservation_cost_micros"] = cost_micros
                results.append(result)
        return results

    def release_budget(
        self,
        *,
        scope_type: str,
        scope_id: str,
        period_start: int,
        period_end: int,
        tokens: int,
        cost_micros: int,
    ) -> dict[str, Any]:
        if tokens < 0 or cost_micros < 0:
            raise RouterPhase0Error("budget releases must be non-negative")
        with self._db.transaction() as conn:
            cursor = conn.execute(
                "UPDATE budget_ledgers SET "
                "reserved_tokens = reserved_tokens - ?, "
                "reserved_cost_micros = reserved_cost_micros - ?, updated_at = ? "
                "WHERE scope_type = ? AND scope_id = ? AND period_start = ? AND period_end = ? "
                "AND reserved_tokens >= ? AND reserved_cost_micros >= ?",
                (
                    tokens,
                    cost_micros,
                    _now(),
                    scope_type,
                    scope_id,
                    period_start,
                    period_end,
                    tokens,
                    cost_micros,
                ),
            )
            if cursor.rowcount != 1:
                raise RouterPhase0Error("budget release exceeds the active reservation")
            row = conn.execute(
                "SELECT * FROM budget_ledgers "
                "WHERE scope_type = ? AND scope_id = ? AND period_start = ? AND period_end = ?",
                (scope_type, scope_id, period_start, period_end),
            ).fetchone()
        assert row is not None
        return _budget_payload(row)

    def settle_budget(
        self,
        *,
        scope_type: str,
        scope_id: str,
        period_start: int,
        period_end: int,
        reserved_tokens: int,
        reserved_cost_micros: int,
        spent_tokens: int,
        spent_cost_micros: int,
    ) -> dict[str, Any]:
        values = (reserved_tokens, reserved_cost_micros, spent_tokens, spent_cost_micros)
        if any(value < 0 for value in values):
            raise RouterPhase0Error("budget settlement values must be non-negative")
        if spent_tokens > reserved_tokens:
            raise RouterPhase0Error("spent tokens exceed the active reservation")
        if spent_cost_micros > reserved_cost_micros:
            raise RouterPhase0Error("spent cost exceeds the active reservation")
        with self._db.transaction() as conn:
            cursor = conn.execute(
                "UPDATE budget_ledgers SET "
                "reserved_tokens = reserved_tokens - ?, "
                "reserved_cost_micros = reserved_cost_micros - ?, "
                "spent_tokens = spent_tokens + ?, "
                "spent_cost_micros = spent_cost_micros + ?, updated_at = ? "
                "WHERE scope_type = ? AND scope_id = ? AND period_start = ? AND period_end = ? "
                "AND reserved_tokens >= ? AND reserved_cost_micros >= ? "
                "AND spent_tokens + ? <= limit_tokens "
                "AND spent_cost_micros + ? <= limit_cost_micros",
                (
                    reserved_tokens,
                    reserved_cost_micros,
                    spent_tokens,
                    spent_cost_micros,
                    _now(),
                    scope_type,
                    scope_id,
                    period_start,
                    period_end,
                    reserved_tokens,
                    reserved_cost_micros,
                    spent_tokens,
                    spent_cost_micros,
                ),
            )
            if cursor.rowcount != 1:
                raise BudgetExceededError(
                    "budget settlement exceeds the active reservation or hard limit"
                )
            row = conn.execute(
                "SELECT * FROM budget_ledgers "
                "WHERE scope_type = ? AND scope_id = ? AND period_start = ? AND period_end = ?",
                (scope_type, scope_id, period_start, period_end),
            ).fetchone()
        assert row is not None
        return _budget_payload(row)

    def settle_budgets(
        self,
        settlements: Sequence[Mapping[str, Any]],
    ) -> list[dict[str, Any]]:
        """Atomically settle all scopes held by one run."""
        results: list[dict[str, Any]] = []
        with self._db.transaction() as conn:
            for settlement in settlements:
                scope_type = str(settlement.get("scope_type") or "")
                scope_id = str(settlement.get("scope_id") or "")
                period_start = int(settlement.get("period_start", 0))
                period_end = int(settlement.get("period_end", 0))
                reserved_tokens = int(settlement.get("reserved_tokens", 0))
                reserved_cost_micros = int(settlement.get("reserved_cost_micros", 0))
                spent_tokens = int(settlement.get("spent_tokens", 0))
                spent_cost_micros = int(settlement.get("spent_cost_micros", 0))
                values = (
                    reserved_tokens,
                    reserved_cost_micros,
                    spent_tokens,
                    spent_cost_micros,
                )
                if any(value < 0 for value in values):
                    raise RouterPhase0Error("budget settlement values must be non-negative")
                if spent_tokens > reserved_tokens:
                    raise RouterPhase0Error("spent tokens exceed the active reservation")
                if spent_cost_micros > reserved_cost_micros:
                    raise RouterPhase0Error("spent cost exceeds the active reservation")
                cursor = conn.execute(
                    "UPDATE budget_ledgers SET "
                    "reserved_tokens = reserved_tokens - ?, "
                    "reserved_cost_micros = reserved_cost_micros - ?, "
                    "spent_tokens = spent_tokens + ?, "
                    "spent_cost_micros = spent_cost_micros + ?, updated_at = ? "
                    "WHERE scope_type = ? AND scope_id = ? "
                    "AND period_start = ? AND period_end = ? "
                    "AND reserved_tokens >= ? AND reserved_cost_micros >= ? "
                    "AND spent_tokens + ? <= limit_tokens "
                    "AND spent_cost_micros + ? <= limit_cost_micros",
                    (
                        reserved_tokens,
                        reserved_cost_micros,
                        spent_tokens,
                        spent_cost_micros,
                        _now(),
                        scope_type,
                        scope_id,
                        period_start,
                        period_end,
                        reserved_tokens,
                        reserved_cost_micros,
                        spent_tokens,
                        spent_cost_micros,
                    ),
                )
                if cursor.rowcount != 1:
                    raise BudgetExceededError(
                        "budget settlement exceeds the active reservation or hard limit"
                    )
                row = conn.execute(
                    "SELECT * FROM budget_ledgers "
                    "WHERE scope_type = ? AND scope_id = ? "
                    "AND period_start = ? AND period_end = ?",
                    (scope_type, scope_id, period_start, period_end),
                ).fetchone()
                assert row is not None
                results.append(_budget_payload(row))
        return results

    def get_budget(
        self,
        *,
        scope_type: str,
        scope_id: str,
        period_start: int,
        period_end: int,
    ) -> dict[str, Any]:
        with self._db.connect() as conn:
            row = conn.execute(
                "SELECT * FROM budget_ledgers "
                "WHERE scope_type = ? AND scope_id = ? AND period_start = ? AND period_end = ?",
                (scope_type, scope_id, period_start, period_end),
            ).fetchone()
        if row is None:
            raise NotFoundError("budget ledger not found")
        return _budget_payload(row)

    def record_usage(
        self,
        *,
        agent_id: str,
        user_id: int,
        thread_id: str = "",
        model: str = "",
        input_tokens: int = 0,
        output_tokens: int = 0,
        total_tokens: int = 0,
        source: str = "router",
        trace_id: str | None = None,
        task_id: str | None = None,
        run_id: str | None = None,
    ) -> None:
        input_count = max(0, int(input_tokens))
        output_count = max(0, int(output_tokens))
        total_count = max(0, int(total_tokens)) or input_count + output_count
        with self._db.transaction() as conn:
            conn.execute(
                "INSERT INTO usage_log("
                "ts, agent_id, user_id, thread_id, model, input_tokens, output_tokens, "
                "total_tokens, source, trace_id, task_id, run_id"
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    _now(),
                    agent_id,
                    user_id,
                    thread_id,
                    model,
                    input_count,
                    output_count,
                    total_count,
                    source,
                    trace_id,
                    task_id,
                    run_id,
                ),
            )

    def has_usage_for_run(self, *, run_id: str) -> bool:
        with self._db.connect() as conn:
            row = conn.execute(
                "SELECT 1 FROM usage_log WHERE run_id = ? LIMIT 1",
                (run_id,),
            ).fetchone()
        return row is not None

    def sum_usage_tokens(
        self,
        *,
        period_start: int,
        period_end: int,
        agent_id: str | None = None,
        user_id: int | None = None,
        source: str | None = None,
        task_id: str | None = None,
    ) -> int:
        """Read actual token usage for shadow budget comparisons."""
        clauses = ["ts >= ?", "ts < ?"]
        params: list[Any] = [int(period_start), int(period_end)]
        if agent_id is not None:
            clauses.append("agent_id = ?")
            params.append(agent_id)
        if user_id is not None:
            clauses.append("user_id = ?")
            params.append(int(user_id))
        if source is not None:
            clauses.append("source = ?")
            params.append(source)
        if task_id is not None:
            clauses.append("task_id = ?")
            params.append(task_id)
        with self._db.connect() as conn:
            row = conn.execute(
                "SELECT COALESCE(SUM(total_tokens), 0) AS total_tokens FROM usage_log "
                f"WHERE {' AND '.join(clauses)}",
                tuple(params),
            ).fetchone()
        if row is None:
            return 0
        value = row["total_tokens"] if isinstance(row, Mapping) else row[0]
        return max(0, int(value or 0))
