"""Task-scoped approvals and idempotent receipts for side-effecting actions.

Phase 4 deliberately does not reuse the dashboard Thread HITL flow. A Thread
HITL pauses the whole conversation, while Router work needs independent tasks
that can wait for confirmation in parallel. An approval binds one exact action
to a Task/Run/Attempt before an executor may claim it. Every claim produces a
durable receipt, so retries and lost responses can return the same outcome
instead of executing the side effect twice.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import time
import uuid
from collections.abc import Mapping, Sequence
from typing import Any

from octop.infra.conversation.budget import RouterBudgetGate, _day_period
from octop.infra.conversation.service import (
    BudgetExceededError,
    ConversationService,
    InvalidTransitionError,
    NotFoundError,
    RouterPhase0Error,
    _clip,
    _int_value,
    _json_text,
    _json_value,
    _new_id,
    _now,
    _row_value,
)

logger = logging.getLogger(__name__)

DEFAULT_APPROVAL_TTL_SECONDS = 15 * 60
DEFAULT_EXECUTION_TTL_SECONDS = 15 * 60
MAX_ACTION_BYTES = 64 * 1024


class WriteFeatureDisabledError(RouterPhase0Error):
    """Side-effecting Router writes are not enabled on this deployment."""


class WriteConflictError(RouterPhase0Error):
    """An idempotency key or approval was reused with different action data."""


def _setting(settings_repo: Any, key: str, default: str = "") -> str:
    env_key = {
        "router.side_effect_writes.enabled": "OCTOP_ROUTER_SIDE_EFFECT_WRITES_ENABLED",
        "router.side_effect_writes.approval_ttl_seconds": (
            "OCTOP_ROUTER_SIDE_EFFECT_APPROVAL_TTL_SECONDS"
        ),
        "router.side_effect_writes.execution_ttl_seconds": (
            "OCTOP_ROUTER_SIDE_EFFECT_EXECUTION_TTL_SECONDS"
        ),
    }.get(key)
    if env_key and os.environ.get(env_key) is not None:
        return str(os.environ.get(env_key) or "")
    try:
        value = settings_repo.get(key) if settings_repo is not None else None
    except Exception:
        logger.warning("router write setting read failed key=%s", key, exc_info=True)
        value = None
    return str(value).strip() if value is not None and str(value).strip() else default


def _setting_bool(settings_repo: Any, key: str, default: bool) -> bool:
    return _setting(settings_repo, key, "1" if default else "0").lower() not in {
        "0",
        "false",
        "no",
        "off",
    }


def _setting_int(
    settings_repo: Any,
    key: str,
    default: int,
    *,
    minimum: int,
    maximum: int,
) -> int:
    try:
        value = int(_setting(settings_repo, key, str(default)))
    except (TypeError, ValueError):
        value = int(default)
    return max(minimum, min(maximum, value))


def _canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _action_hash(action_name: str, action_payload: Mapping[str, Any]) -> str:
    canonical = _canonical_json(
        {
            "action_name": action_name,
            "payload": dict(action_payload),
        }
    )
    if len(canonical.encode("utf-8")) > MAX_ACTION_BYTES:
        raise RouterPhase0Error("write action payload is too large")
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _approval_payload(row: Any) -> dict[str, Any]:
    return {
        "approval_id": str(_row_value(row, "approval_id")),
        "conversation_id": str(_row_value(row, "conversation_id")),
        "task_id": str(_row_value(row, "task_id")),
        "run_id": _row_value(row, "run_id"),
        "trace_id": str(_row_value(row, "trace_id")),
        "attempt_no": _row_value(row, "attempt_no"),
        "task_title": str(_row_value(row, "task_title", "")),
        "task_objective": str(_row_value(row, "task_objective", "")),
        "task_status": str(_row_value(row, "task_status", "")),
        "accepted_at": _row_value(row, "task_accepted_at"),
        "acceptance_source": _row_value(row, "task_acceptance_source"),
        "outcome_ref": _row_value(row, "task_outcome_ref"),
        "action_name": str(_row_value(row, "action_name")),
        "action": _json_value(_row_value(row, "action_json"), default={}),
        "action_hash": str(_row_value(row, "action_hash")),
        "idempotency_key": str(_row_value(row, "idempotency_key")),
        "status": str(_row_value(row, "status")),
        "version": _int_value(row, "version"),
        "token_limit": _int_value(row, "token_limit"),
        "expires_at": _int_value(row, "expires_at"),
        "execution_expires_at": _row_value(row, "execution_expires_at"),
        "decided_at": _row_value(row, "decided_at"),
        "created_at": _int_value(row, "created_at"),
        "updated_at": _int_value(row, "updated_at"),
    }


def _receipt_payload(row: Any) -> dict[str, Any]:
    return {
        "receipt_id": str(_row_value(row, "receipt_id")),
        "conversation_id": str(_row_value(row, "conversation_id")),
        "task_id": str(_row_value(row, "task_id")),
        "run_id": str(_row_value(row, "run_id")),
        "trace_id": str(_row_value(row, "trace_id")),
        "attempt_no": _int_value(row, "attempt_no"),
        "approval_id": str(_row_value(row, "approval_id")),
        "action_name": str(_row_value(row, "action_name")),
        "action_hash": str(_row_value(row, "action_hash")),
        "idempotency_key": str(_row_value(row, "idempotency_key")),
        "request": _json_value(_row_value(row, "request_json"), default={}),
        "status": str(_row_value(row, "status")),
        "outcome": _json_value(_row_value(row, "outcome_json"), default={}),
        "outcome_ref": _row_value(row, "outcome_ref"),
        "actual_tokens": _int_value(row, "actual_tokens"),
        "accepted_at": _row_value(row, "accepted_at"),
        "error": _row_value(row, "error"),
        "created_at": _int_value(row, "created_at"),
        "updated_at": _int_value(row, "updated_at"),
        "finished_at": _row_value(row, "finished_at"),
    }


class WriteActionService:
    """Owns task-level approval, budget reservation, claims, and receipts."""

    def __init__(
        self,
        db: Any,
        settings_repo: Any,
        *,
        timezone_name: str = "Asia/Shanghai",
        budget_gate: RouterBudgetGate | None = None,
    ) -> None:
        self._db = db
        self._settings = settings_repo
        self._timezone = timezone_name or "Asia/Shanghai"
        self._conversation = ConversationService(db)
        self._budget_gate = budget_gate or RouterBudgetGate(
            db,
            settings_repo,
            timezone_name=self._timezone,
            service=self._conversation,
        )
        self._approval_ttl = _setting_int(
            settings_repo,
            "router.side_effect_writes.approval_ttl_seconds",
            DEFAULT_APPROVAL_TTL_SECONDS,
            minimum=60,
            maximum=86_400,
        )
        self._execution_ttl = _setting_int(
            settings_repo,
            "router.side_effect_writes.execution_ttl_seconds",
            DEFAULT_EXECUTION_TTL_SECONDS,
            minimum=60,
            maximum=86_400,
        )

    @property
    def enabled(self) -> bool:
        return _setting_bool(
            self._settings,
            "router.side_effect_writes.enabled",
            False,
        )

    @property
    def status(self) -> dict[str, Any]:
        budget = self._budget_gate.config_for("worker")
        budget_ready = budget.mode == "enforce" and "worker" in budget.enforced_surfaces
        return {
            "enabled": self.enabled,
            "ready": self.enabled and budget_ready,
            "approval_ttl_seconds": self._approval_ttl,
            "execution_ttl_seconds": self._execution_ttl,
            "budget_mode": budget.mode,
            "worker_budget_enforced": "worker" in budget.enforced_surfaces,
        }

    def _require_enabled(self) -> None:
        if not self.enabled:
            raise WriteFeatureDisabledError("side-effecting Router writes are disabled")

    def _require_budget_ready(self) -> Any:
        config = self._budget_gate.config_for("worker")
        if config.mode != "enforce" or "worker" not in config.enforced_surfaces:
            raise WriteFeatureDisabledError(
                "side-effecting writes require enforce mode with worker budgets enabled"
            )
        return config

    def create_task(
        self,
        *,
        agent_id: str,
        user_id: int,
        conversation_id: str,
        title: str,
        objective: str,
        action_name: str,
        action_payload: Mapping[str, Any],
        subject_key: str | None = None,
        token_limit: int | None = None,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        self._require_enabled()
        config = self._require_budget_ready()
        normalized_action = _clip(action_name, 160)
        if not normalized_action:
            raise RouterPhase0Error("write action name is required")
        if not isinstance(action_payload, Mapping):
            raise RouterPhase0Error("write action payload must be an object")
        normalized_title = _clip(title, 200)
        normalized_objective = _clip(objective, 20_000)
        if not normalized_title or not normalized_objective:
            raise RouterPhase0Error("write task title and objective are required")
        now = _now()
        trace_id = f"write:{_new_id('trace')}"
        action_hash = _action_hash(normalized_action, action_payload)
        normalized_token_limit = max(
            1_000,
            min(
                int(token_limit or config.run_tokens),
                max(1_000, int(config.run_tokens)),
            ),
        )
        normalized_idempotency_key = _clip(
            idempotency_key or f"idem_{uuid.uuid4().hex}",
            200,
        )
        task_id = _new_id("task")
        approval_id = _new_id("approval")
        capsule = {
            "kind": "side_effect",
            "action_name": normalized_action,
            "action_hash": action_hash,
            "idempotency_key": normalized_idempotency_key,
            "token_limit": normalized_token_limit,
            "trace_id": trace_id,
        }
        with self._db.transaction() as conn:
            conversation = conn.execute(
                "SELECT conversation_id FROM main_conversations "
                "WHERE conversation_id = ? AND agent_id = ? AND user_id = ?",
                (conversation_id, agent_id, int(user_id)),
            ).fetchone()
            if conversation is None:
                raise NotFoundError("main conversation not found")
            conn.execute(
                "INSERT INTO conversation_tasks("
                "task_id, conversation_id, task_type, title, subject_key, objective, status, "
                "requires_user, capsule_json, priority, created_at, updated_at, expires_at"
                ") VALUES (?, ?, 'side_effect', ?, ?, ?, 'needs_user', 1, ?, 0, ?, ?, ?)",
                (
                    task_id,
                    conversation_id,
                    normalized_title,
                    _clip(subject_key or f"write:{approval_id}", 500),
                    normalized_objective,
                    _json_text(capsule, default={}),
                    now,
                    now,
                    now + self._approval_ttl,
                ),
            )
            conn.execute(
                "INSERT INTO task_approvals("
                "approval_id, conversation_id, task_id, trace_id, action_name, action_json, "
                "action_hash, idempotency_key, status, version, token_limit, expires_at, "
                "created_at, updated_at"
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', 1, ?, ?, ?, ?)",
                (
                    approval_id,
                    conversation_id,
                    task_id,
                    trace_id,
                    normalized_action,
                    _json_text(dict(action_payload), default={}),
                    action_hash,
                    normalized_idempotency_key,
                    normalized_token_limit,
                    now + self._approval_ttl,
                    now,
                    now,
                ),
            )
        approval = self._get_approval(
            agent_id=agent_id,
            user_id=user_id,
            task_id=task_id,
            approval_id=approval_id,
        )
        self._append_event(
            approval=approval,
            event_type="task.approval_required",
            status="pending",
            summary="Confirm this write before Router can run it",
        )
        return {
            "task": self._conversation.get_task(
                agent_id=agent_id,
                user_id=user_id,
                task_id=task_id,
            ),
            "approval": approval,
            "requires_approval": True,
            "dispatched": False,
        }

    def approve(
        self,
        *,
        agent_id: str,
        user_id: int,
        task_id: str,
        approval_id: str,
        action_hash: str,
        version: int,
    ) -> dict[str, Any]:
        self._require_enabled()
        config = self._require_budget_ready()
        now = _now()
        expired_approval: dict[str, Any] | None = None
        with self._db.transaction() as conn:
            row = self._load_approval_row(
                conn,
                agent_id=agent_id,
                user_id=user_id,
                task_id=task_id,
                approval_id=approval_id,
            )
            current = str(_row_value(row, "status"))
            if current in {"approved", "consumed"}:
                return {
                    "task": self._task_from_row(
                        conn,
                        str(_row_value(row, "task_id")),
                    ),
                    "approval": self._approval_from_conn(conn, approval_id),
                    "requires_approval": False,
                    "dispatched": False,
                }
            if current != "pending":
                raise InvalidTransitionError("task approval is no longer pending")
            if int(version) != _int_value(row, "version"):
                raise InvalidTransitionError("task approval version no longer matches")
            stored_hash = str(_row_value(row, "action_hash"))
            if str(action_hash) != stored_hash:
                raise WriteConflictError("write action hash does not match the approval")
            expires_at = _int_value(row, "expires_at")
            if now >= expires_at:
                conn.execute(
                    "UPDATE task_approvals SET status = 'expired', updated_at = ? "
                    "WHERE approval_id = ? AND status = 'pending'",
                    (now, approval_id),
                )
                conn.execute(
                    "UPDATE conversation_tasks SET status = 'expired', updated_at = ? "
                    "WHERE task_id = ? AND status = 'needs_user'",
                    (now, task_id),
                )
                expired_approval = self._approval_from_conn(conn, approval_id)

            specs: list[dict[str, Any]] = []
            if expired_approval is None:
                task_row = conn.execute(
                    "SELECT * FROM conversation_tasks WHERE task_id = ?", (task_id,)
                ).fetchone()
                if task_row is None:
                    raise NotFoundError("task not found")
                if str(_row_value(task_row, "status")) != "needs_user":
                    raise InvalidTransitionError("task is not waiting for approval")

                token_limit = min(
                    _int_value(row, "token_limit"),
                    max(1_000, int(config.run_tokens)),
                )
                specs = self._budget_specs(
                    task_id=task_id,
                    trace_id=str(_row_value(row, "trace_id")),
                    agent_id=agent_id,
                    user_id=int(user_id),
                    token_limit=token_limit,
                    now=now,
                    config=config,
                )

        if expired_approval is not None:
            self._append_event(
                approval=expired_approval,
                event_type="task.approval_expired",
                status="expired",
                summary="Write approval expired before it was confirmed",
            )
            raise InvalidTransitionError("task approval has expired")

        try:
            reservations = self._conversation.reserve_budgets(specs)
        except BudgetExceededError:
            self._append_event(
                approval=self._approval_from_conn_with_connection(
                    agent_id=agent_id,
                    user_id=user_id,
                    task_id=task_id,
                    approval_id=approval_id,
                ),
                event_type="task.approval_budget_blocked",
                status="blocked",
                summary="Write approval could not reserve its bounded budget",
            )
            raise

        budget_json = _json_text(
            {
                "reservations": reservations,
                "cost_per_1k_tokens_micros": config.cost_per_1k_tokens_micros,
                "reserved_at": now,
            },
            default={},
        )
        run_id = _new_id("run")
        try:
            with self._db.transaction() as conn:
                current_row = self._load_approval_row(
                    conn,
                    agent_id=agent_id,
                    user_id=user_id,
                    task_id=task_id,
                    approval_id=approval_id,
                )
                if str(_row_value(current_row, "status")) != "pending":
                    raise InvalidTransitionError("task approval changed while reserving budget")
                conn.execute(
                    "INSERT INTO task_runs("
                    "run_id, task_id, worker_thread_id, status, attempt_no, created_at"
                    ") VALUES (?, ?, NULL, 'queued', 1, ?)",
                    (run_id, task_id, now),
                )
                conn.execute(
                    "UPDATE task_approvals SET run_id = ?, attempt_no = 1, status = 'approved', "
                    "budget_json = ?, execution_expires_at = ?, decided_at = ?, "
                    "decided_by_user_id = ?, updated_at = ? WHERE approval_id = ?",
                    (
                        run_id,
                        budget_json,
                        now + self._execution_ttl,
                        now,
                        int(user_id),
                        now,
                        approval_id,
                    ),
                )
                conn.execute(
                    "UPDATE conversation_tasks SET status = 'in_progress', "
                    "accepted_at = ?, acceptance_source = 'task_approval', updated_at = ? "
                    "WHERE task_id = ? AND status = 'needs_user'",
                    (now, now, task_id),
                )
        except Exception:
            self._release_budget(reservations)
            raise

        approval = self._approval_from_conn_with_connection(
            agent_id=agent_id,
            user_id=user_id,
            task_id=task_id,
            approval_id=approval_id,
        )
        self._append_event(
            approval=approval,
            event_type="task.approval_approved",
            status="approved",
            summary="Write approved once with an idempotent receipt",
        )
        return {
            "task": self._conversation.get_task(
                agent_id=agent_id,
                user_id=user_id,
                task_id=task_id,
            ),
            "approval": approval,
            "requires_approval": False,
            "dispatched": False,
        }

    def reject(
        self,
        *,
        agent_id: str,
        user_id: int,
        task_id: str,
        approval_id: str,
        action_hash: str,
        version: int,
    ) -> dict[str, Any]:
        self._require_enabled()
        now = _now()
        with self._db.transaction() as conn:
            row = self._load_approval_row(
                conn,
                agent_id=agent_id,
                user_id=user_id,
                task_id=task_id,
                approval_id=approval_id,
            )
            current = str(_row_value(row, "status"))
            if current == "rejected":
                return {
                    "task": self._task_from_row(conn, task_id),
                    "approval": self._approval_from_conn(conn, approval_id),
                    "requires_approval": False,
                    "dispatched": False,
                }
            if current != "pending":
                raise InvalidTransitionError("task approval is no longer pending")
            if int(version) != _int_value(row, "version"):
                raise InvalidTransitionError("task approval version no longer matches")
            if str(action_hash) != str(_row_value(row, "action_hash")):
                raise WriteConflictError("write action hash does not match the approval")
            conn.execute(
                "UPDATE task_approvals SET status = 'rejected', decided_at = ?, "
                "decided_by_user_id = ?, updated_at = ? WHERE approval_id = ?",
                (now, int(user_id), now, approval_id),
            )
            conn.execute(
                "UPDATE conversation_tasks SET status = 'cancelled', updated_at = ? "
                "WHERE task_id = ? AND status = 'needs_user'",
                (now, task_id),
            )
        approval = self._approval_from_conn_with_connection(
            agent_id=agent_id,
            user_id=user_id,
            task_id=task_id,
            approval_id=approval_id,
        )
        self._append_event(
            approval=approval,
            event_type="task.approval_rejected",
            status="rejected",
            summary="Write was rejected and will not run",
        )
        return {
            "task": self._conversation.get_task(
                agent_id=agent_id,
                user_id=user_id,
                task_id=task_id,
            ),
            "approval": approval,
            "requires_approval": False,
            "dispatched": False,
        }

    def claim_write(
        self,
        *,
        agent_id: str,
        user_id: int,
        task_id: str,
        approval_id: str,
        action_hash: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        """Atomically start one approved write or return its original receipt."""
        self._require_enabled()
        now = _now()
        with self._db.transaction() as conn:
            existing = conn.execute(
                "SELECT r.* FROM write_receipts r "
                "JOIN main_conversations c ON c.conversation_id = r.conversation_id "
                "WHERE r.idempotency_key = ? AND c.agent_id = ? AND c.user_id = ?",
                (idempotency_key, agent_id, int(user_id)),
            ).fetchone()
            if existing is not None:
                if (
                    str(_row_value(existing, "task_id")) != task_id
                    or str(_row_value(existing, "approval_id")) != approval_id
                    or str(_row_value(existing, "action_hash")) != action_hash
                ):
                    raise WriteConflictError(
                        "idempotency key is already bound to another write action"
                    )
                return {
                    "receipt": _receipt_payload(existing),
                    "should_execute": False,
                    "deduplicated": True,
                }

            approval = self._load_approval_row(
                conn,
                agent_id=agent_id,
                user_id=user_id,
                task_id=task_id,
                approval_id=approval_id,
            )
            if str(_row_value(approval, "status")) != "approved":
                raise InvalidTransitionError("write approval has not been granted")
            if str(_row_value(approval, "action_hash")) != action_hash:
                raise WriteConflictError("write action hash does not match the approval")
            if str(_row_value(approval, "idempotency_key")) != idempotency_key:
                raise WriteConflictError(
                    "idempotency key does not match the approved action"
                )
            execution_expires_at = _int_value(approval, "execution_expires_at")
            if execution_expires_at <= 0 or now >= execution_expires_at:
                raise InvalidTransitionError("approved action expired before execution")
            run_id = str(_row_value(approval, "run_id"))
            attempt_no = _int_value(approval, "attempt_no")
            run_row = conn.execute(
                "SELECT * FROM task_runs WHERE run_id = ? AND task_id = ?",
                (run_id, task_id),
            ).fetchone()
            if run_row is None or str(_row_value(run_row, "status")) != "queued":
                raise InvalidTransitionError("approved write run is not queued")
            if _int_value(run_row, "attempt_no") != attempt_no:
                raise InvalidTransitionError("approved write attempt no longer matches")

            receipt_id = _new_id("receipt")
            action = _json_value(_row_value(approval, "action_json"), default={})
            request_payload = {
                "action_name": str(_row_value(approval, "action_name")),
                "action": action,
            }
            conn.execute(
                "INSERT INTO write_receipts("
                "receipt_id, conversation_id, task_id, run_id, trace_id, attempt_no, "
                "approval_id, action_name, action_hash, idempotency_key, request_json, "
                "status, created_at, updated_at"
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'processing', ?, ?)",
                (
                    receipt_id,
                    str(_row_value(approval, "conversation_id")),
                    task_id,
                    run_id,
                    str(_row_value(approval, "trace_id")),
                    attempt_no,
                    approval_id,
                    str(_row_value(approval, "action_name")),
                    action_hash,
                    idempotency_key,
                    _json_text(request_payload, default={}),
                    now,
                    now,
                ),
            )
            conn.execute(
                "UPDATE task_approvals SET status = 'consumed', consumed_at = ?, "
                "updated_at = ? WHERE approval_id = ? AND status = 'approved'",
                (now, now, approval_id),
            )
            conn.execute(
                "UPDATE task_runs SET status = 'running', started_at = ?, "
                "last_progress_at = ?, lease_until = ? WHERE run_id = ? AND status = 'queued'",
                (now, now, now + self._execution_ttl, run_id),
            )
            receipt = conn.execute(
                "SELECT * FROM write_receipts WHERE receipt_id = ?", (receipt_id,)
            ).fetchone()

        claimed = _receipt_payload(receipt)
        self._append_receipt_event(
            receipt=claimed,
            event_type="task.write_started",
            status="processing",
            summary="Approved write started exactly once",
        )
        return {
            "receipt": claimed,
            "should_execute": True,
            "deduplicated": False,
        }

    def complete_write(
        self,
        *,
        agent_id: str,
        user_id: int,
        receipt_id: str,
        outcome: Mapping[str, Any] | None = None,
        outcome_ref: str | None = None,
        actual_tokens: int = 0,
    ) -> dict[str, Any]:
        self._require_enabled()
        now = _now()
        with self._db.connect() as conn:
            row = self._load_receipt_row(
                conn,
                agent_id=agent_id,
                user_id=user_id,
                receipt_id=receipt_id,
            )
        if str(_row_value(row, "status")) == "succeeded":
            return _receipt_payload(row)
        if str(_row_value(row, "status")) != "processing":
            raise InvalidTransitionError(
                "only a processing write can be marked successful"
            )

        approval = self._approval_from_conn_with_connection(
            agent_id=agent_id,
            user_id=user_id,
            task_id=str(_row_value(row, "task_id")),
            approval_id=str(_row_value(row, "approval_id")),
        )
        reservations = self._reservations_for_approval(approval)
        accepted_at = int(approval.get("accepted_at") or now)
        normalized_tokens = max(0, int(actual_tokens))
        self._settle_budget(reservations, normalized_tokens)
        with self._db.transaction() as conn:
            conn.execute(
                "UPDATE write_receipts SET status = 'succeeded', outcome_json = ?, "
                "outcome_ref = ?, actual_tokens = ?, accepted_at = ?, finished_at = ?, "
                "updated_at = ? WHERE receipt_id = ? AND status = 'processing'",
                (
                    _json_text(dict(outcome or {}), default={}),
                    _clip(outcome_ref, 500) if outcome_ref else None,
                    normalized_tokens,
                    accepted_at,
                    now,
                    now,
                    receipt_id,
                ),
            )
            conn.execute(
                "UPDATE task_runs SET status = 'succeeded', finished_at = ?, "
                "last_progress_at = ?, lease_until = NULL WHERE run_id = ? AND status = 'running'",
                (now, now, str(_row_value(row, "run_id"))),
            )
            conn.execute(
                "UPDATE conversation_tasks SET status = 'done', outcome_ref = ?, "
                "updated_at = ? WHERE task_id = ? AND status = 'in_progress'",
                (
                    _clip(outcome_ref, 500) if outcome_ref else None,
                    now,
                    str(_row_value(row, "task_id")),
                ),
            )
            updated = conn.execute(
                "SELECT * FROM write_receipts WHERE receipt_id = ?", (receipt_id,)
            ).fetchone()
        receipt = _receipt_payload(updated)
        self._append_receipt_event(
            receipt=receipt,
            event_type="task.write_succeeded",
            status="succeeded",
            summary="Write completed and its receipt was recorded",
        )
        return receipt

    def mark_write_failed(
        self,
        *,
        agent_id: str,
        user_id: int,
        receipt_id: str,
        error: str,
        actual_tokens: int = 0,
    ) -> dict[str, Any]:
        return self._finish_unsuccessful(
            agent_id=agent_id,
            user_id=user_id,
            receipt_id=receipt_id,
            receipt_status="failed",
            failure_class="write_failed",
            task_status="failed",
            error=error,
            actual_tokens=actual_tokens,
        )

    def mark_result_uncertain(
        self,
        *,
        agent_id: str,
        user_id: int,
        receipt_id: str,
        error: str,
        actual_tokens: int = 0,
    ) -> dict[str, Any]:
        """Stop without replay. The task stays visible for manual reconciliation."""
        return self._finish_unsuccessful(
            agent_id=agent_id,
            user_id=user_id,
            receipt_id=receipt_id,
            receipt_status="result_uncertain",
            failure_class="result_uncertain",
            task_status="needs_user",
            error=error,
            actual_tokens=actual_tokens,
        )

    def get_task_writes(
        self,
        *,
        agent_id: str,
        user_id: int,
        task_id: str,
    ) -> dict[str, Any]:
        with self._db.connect() as conn:
            task = conn.execute(
                "SELECT t.* FROM conversation_tasks t "
                "JOIN main_conversations c ON c.conversation_id = t.conversation_id "
                "WHERE t.task_id = ? AND c.agent_id = ? AND c.user_id = ?",
                (task_id, agent_id, int(user_id)),
            ).fetchone()
            if task is None:
                raise NotFoundError("task not found")
            approvals = conn.execute(
                "SELECT a.*, t.title AS task_title, t.objective AS task_objective, "
                "t.status AS task_status, t.accepted_at AS task_accepted_at, "
                "t.acceptance_source AS task_acceptance_source, "
                "t.outcome_ref AS task_outcome_ref FROM task_approvals a "
                "JOIN conversation_tasks t ON t.task_id = a.task_id "
                "WHERE a.task_id = ? ORDER BY a.created_at DESC",
                (task_id,),
            ).fetchall()
            receipts = conn.execute(
                "SELECT * FROM write_receipts WHERE task_id = ? ORDER BY created_at DESC",
                (task_id,),
            ).fetchall()
        return {
            "task_id": task_id,
            "approvals": [_approval_payload(row) for row in approvals],
            "receipts": [_receipt_payload(row) for row in receipts],
        }

    def recover_expired_writes(
        self,
        *,
        now: int | None = None,
    ) -> list[dict[str, Any]]:
        """Move abandoned in-flight writes to result_uncertain, never to retry."""
        if not self.enabled:
            return []
        cutoff = int(now if now is not None else _now())
        with self._db.connect() as conn:
            rows = conn.execute(
                "SELECT r.receipt_id, c.agent_id, c.user_id "
                "FROM write_receipts r "
                "JOIN task_runs run ON run.run_id = r.run_id "
                "JOIN main_conversations c ON c.conversation_id = r.conversation_id "
                "WHERE r.status = 'processing' "
                "AND run.status = 'running' "
                "AND run.lease_until IS NOT NULL AND run.lease_until <= ? "
                "ORDER BY r.created_at ASC",
                (cutoff,),
            ).fetchall()
        recovered: list[dict[str, Any]] = []
        for row in rows:
            try:
                recovered.append(
                    self.mark_result_uncertain(
                        agent_id=str(_row_value(row, "agent_id")),
                        user_id=int(_row_value(row, "user_id")),
                        receipt_id=str(_row_value(row, "receipt_id")),
                        error="write executor lease expired; no automatic replay",
                    )
                )
            except InvalidTransitionError:
                # Another recovery or executor completed the receipt first.
                continue
        return recovered

    def _finish_unsuccessful(
        self,
        *,
        agent_id: str,
        user_id: int,
        receipt_id: str,
        receipt_status: str,
        failure_class: str,
        task_status: str,
        error: str,
        actual_tokens: int,
    ) -> dict[str, Any]:
        self._require_enabled()
        now = _now()
        with self._db.connect() as conn:
            row = self._load_receipt_row(
                conn,
                agent_id=agent_id,
                user_id=user_id,
                receipt_id=receipt_id,
            )
        if str(_row_value(row, "status")) == receipt_status:
            return _receipt_payload(row)
        if str(_row_value(row, "status")) != "processing":
            raise InvalidTransitionError("write receipt is already terminal")
        approval = self._approval_from_conn_with_connection(
            agent_id=agent_id,
            user_id=user_id,
            task_id=str(_row_value(row, "task_id")),
            approval_id=str(_row_value(row, "approval_id")),
        )
        normalized_tokens = max(0, int(actual_tokens))
        self._settle_budget(
            self._reservations_for_approval(approval),
            normalized_tokens,
        )
        with self._db.transaction() as conn:
            conn.execute(
                "UPDATE write_receipts SET status = ?, error = ?, actual_tokens = ?, "
                "finished_at = ?, updated_at = ? WHERE receipt_id = ? AND status = 'processing'",
                (
                    receipt_status,
                    _clip(error, 2_000),
                    normalized_tokens,
                    now,
                    now,
                    receipt_id,
                ),
            )
            conn.execute(
                "UPDATE task_runs SET status = 'failed', failure_class = ?, "
                "finished_at = ?, last_progress_at = ?, lease_until = NULL "
                "WHERE run_id = ? AND status = 'running'",
                (
                    failure_class,
                    now,
                    now,
                    str(_row_value(row, "run_id")),
                ),
            )
            conn.execute(
                "UPDATE conversation_tasks SET status = ?, updated_at = ? "
                "WHERE task_id = ? AND status = 'in_progress'",
                (task_status, now, str(_row_value(row, "task_id"))),
            )
            updated = conn.execute(
                "SELECT * FROM write_receipts WHERE receipt_id = ?", (receipt_id,)
            ).fetchone()
        receipt = _receipt_payload(updated)
        self._append_receipt_event(
            receipt=receipt,
            event_type=(
                "task.write_result_uncertain"
                if receipt_status == "result_uncertain"
                else "task.write_failed"
            ),
            status=receipt_status,
            summary=(
                "Write result is uncertain; Router will not retry it automatically"
                if receipt_status == "result_uncertain"
                else "Write failed without an automatic replay"
            ),
        )
        return receipt

    def _budget_specs(
        self,
        *,
        task_id: str,
        trace_id: str,
        agent_id: str,
        user_id: int,
        token_limit: int,
        now: int,
        config: Any,
    ) -> list[dict[str, Any]]:
        cost = max(0, token_limit) * int(config.cost_per_1k_tokens_micros) // 1_000
        day_start, day_end = _day_period(now, self._timezone)
        specs = [
            {
                "scope_type": "run",
                "scope_id": trace_id,
                "period_start": now - 60,
                "period_end": now + self._execution_ttl,
                "tokens": token_limit,
                "cost_micros": cost,
                "limit_tokens": token_limit,
                "limit_cost_micros": cost,
            },
            {
                "scope_type": "task_daily",
                "scope_id": f"write:{task_id}",
                "period_start": day_start,
                "period_end": day_end,
                "tokens": token_limit,
                "cost_micros": cost,
                "limit_tokens": config.task_daily_tokens,
                "limit_cost_micros": (
                    config.task_daily_tokens * config.cost_per_1k_tokens_micros // 1_000
                ),
            },
            {
                "scope_type": "surface_daily",
                "scope_id": f"worker:{agent_id}:{user_id}",
                "period_start": day_start,
                "period_end": day_end,
                "tokens": token_limit,
                "cost_micros": cost,
                "limit_tokens": config.surface_daily_tokens,
                "limit_cost_micros": (
                    config.surface_daily_tokens
                    * config.cost_per_1k_tokens_micros
                    // 1_000
                ),
            },
            {
                "scope_type": "user_daily",
                "scope_id": str(user_id),
                "period_start": day_start,
                "period_end": day_end,
                "tokens": token_limit,
                "cost_micros": cost,
                "limit_tokens": config.user_daily_tokens,
                "limit_cost_micros": (
                    config.user_daily_tokens * config.cost_per_1k_tokens_micros // 1_000
                ),
            },
            {
                "scope_type": "agent_daily",
                "scope_id": agent_id,
                "period_start": day_start,
                "period_end": day_end,
                "tokens": token_limit,
                "cost_micros": cost,
                "limit_tokens": config.agent_daily_tokens,
                "limit_cost_micros": (
                    config.agent_daily_tokens * config.cost_per_1k_tokens_micros // 1_000
                ),
            },
            {
                "scope_type": "global_daily",
                "scope_id": "all",
                "period_start": day_start,
                "period_end": day_end,
                "tokens": token_limit,
                "cost_micros": cost,
                "limit_tokens": config.global_daily_tokens,
                "limit_cost_micros": (
                    config.global_daily_tokens * config.cost_per_1k_tokens_micros // 1_000
                ),
            },
        ]
        return specs

    def _reservations_for_approval(
        self,
        approval: Mapping[str, Any],
    ) -> list[dict[str, Any]]:
        with self._db.connect() as conn:
            row = conn.execute(
                "SELECT budget_json FROM task_approvals WHERE approval_id = ?",
                (approval["approval_id"],),
            ).fetchone()
        payload = _json_value(_row_value(row, "budget_json"), default={})
        reservations = (
            payload.get("reservations") if isinstance(payload, Mapping) else None
        )
        return [
            dict(item)
            for item in (reservations or [])
            if isinstance(item, Mapping)
        ]

    def _settle_budget(
        self,
        reservations: Sequence[Mapping[str, Any]],
        actual_tokens: int,
    ) -> None:
        if not reservations:
            return
        settlements = []
        for item in reservations:
            reserved_tokens = int(
                item.get("reservation_tokens", item.get("reserved_tokens", 0)) or 0
            )
            reserved_cost = int(
                item.get(
                    "reservation_cost_micros",
                    item.get("reserved_cost_micros", 0),
                )
                or 0
            )
            spent_tokens = min(max(0, actual_tokens), reserved_tokens)
            spent_cost = int(item.get("cost_per_1k_tokens_micros", 0) or 0)
            if spent_cost <= 0:
                spent_cost = reserved_cost * spent_tokens // max(1, reserved_tokens)
            else:
                spent_cost = spent_cost * spent_tokens // 1_000
            settlements.append(
                {
                    "scope_type": str(item["scope_type"]),
                    "scope_id": str(item["scope_id"]),
                    "period_start": int(item["period_start"]),
                    "period_end": int(item["period_end"]),
                    "reserved_tokens": reserved_tokens,
                    "reserved_cost_micros": reserved_cost,
                    "spent_tokens": spent_tokens,
                    "spent_cost_micros": min(spent_cost, reserved_cost),
                }
            )
        self._conversation.settle_budgets(settlements)

    def _release_budget(self, reservations: Sequence[Mapping[str, Any]]) -> None:
        for item in reservations:
            try:
                self._conversation.release_budget(
                    scope_type=str(item["scope_type"]),
                    scope_id=str(item["scope_id"]),
                    period_start=int(item["period_start"]),
                    period_end=int(item["period_end"]),
                    tokens=int(
                        item.get(
                            "reservation_tokens",
                            item.get("reserved_tokens", 0),
                        )
                        or 0
                    ),
                    cost_micros=int(
                        item.get(
                            "reservation_cost_micros",
                            item.get("reserved_cost_micros", 0),
                        )
                        or 0
                    ),
                )
            except Exception:
                logger.error(
                    "failed to release write budget scope=%s:%s",
                    item.get("scope_type"),
                    item.get("scope_id"),
                    exc_info=True,
                )

    def _get_approval(
        self,
        *,
        agent_id: str,
        user_id: int,
        task_id: str,
        approval_id: str,
    ) -> dict[str, Any]:
        return self._approval_from_conn_with_connection(
            agent_id=agent_id,
            user_id=user_id,
            task_id=task_id,
            approval_id=approval_id,
        )

    def _approval_from_conn_with_connection(
        self,
        *,
        agent_id: str,
        user_id: int,
        task_id: str,
        approval_id: str,
    ) -> dict[str, Any]:
        with self._db.connect() as conn:
            return self._approval_from_conn(
                conn,
                approval_id,
                agent_id=agent_id,
                user_id=user_id,
                task_id=task_id,
            )

    def _approval_from_conn(
        self,
        conn: Any,
        approval_id: str,
        *,
        agent_id: str | None = None,
        user_id: int | None = None,
        task_id: str | None = None,
    ) -> dict[str, Any]:
        clauses = ["a.approval_id = ?"]
        params: list[Any] = [approval_id]
        if agent_id is not None:
            clauses.append("c.agent_id = ?")
            params.append(agent_id)
        if user_id is not None:
            clauses.append("c.user_id = ?")
            params.append(int(user_id))
        if task_id is not None:
            clauses.append("a.task_id = ?")
            params.append(task_id)
        row = conn.execute(
            "SELECT a.*, t.title AS task_title, t.objective AS task_objective, "
            "t.status AS task_status, t.accepted_at AS task_accepted_at, "
            "t.acceptance_source AS task_acceptance_source, "
            "t.outcome_ref AS task_outcome_ref FROM task_approvals a "
            "JOIN conversation_tasks t ON t.task_id = a.task_id "
            "JOIN main_conversations c ON c.conversation_id = a.conversation_id "
            f"WHERE {' AND '.join(clauses)}",
            tuple(params),
        ).fetchone()
        if row is None:
            raise NotFoundError("task approval not found")
        return _approval_payload(row)

    def _load_approval_row(
        self,
        conn: Any,
        *,
        agent_id: str,
        user_id: int,
        task_id: str,
        approval_id: str,
    ) -> Any:
        row = conn.execute(
            "SELECT a.*, t.title AS task_title, t.objective AS task_objective, "
            "t.status AS task_status, t.accepted_at AS task_accepted_at, "
            "t.acceptance_source AS task_acceptance_source, "
            "t.outcome_ref AS task_outcome_ref FROM task_approvals a "
            "JOIN conversation_tasks t ON t.task_id = a.task_id "
            "JOIN main_conversations c ON c.conversation_id = a.conversation_id "
            "WHERE a.approval_id = ? AND a.task_id = ? "
            "AND c.agent_id = ? AND c.user_id = ?",
            (approval_id, task_id, agent_id, int(user_id)),
        ).fetchone()
        if row is None:
            raise NotFoundError("task approval not found")
        return row

    def _load_receipt_row(
        self,
        conn: Any,
        *,
        agent_id: str,
        user_id: int,
        receipt_id: str,
    ) -> Any:
        row = conn.execute(
            "SELECT r.* FROM write_receipts r "
            "JOIN main_conversations c ON c.conversation_id = r.conversation_id "
            "WHERE r.receipt_id = ? AND c.agent_id = ? AND c.user_id = ?",
            (receipt_id, agent_id, int(user_id)),
        ).fetchone()
        if row is None:
            raise NotFoundError("write receipt not found")
        return row

    def _task_from_row(self, conn: Any, task_id: str) -> dict[str, Any]:
        row = conn.execute(
            "SELECT * FROM conversation_tasks WHERE task_id = ?", (task_id,)
        ).fetchone()
        if row is None:
            raise NotFoundError("task not found")
        return {
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
            "accepted_at": _row_value(row, "accepted_at"),
            "acceptance_source": _row_value(row, "acceptance_source"),
            "outcome_ref": _row_value(row, "outcome_ref"),
        }

    def _append_event(
        self,
        *,
        approval: Mapping[str, Any],
        event_type: str,
        status: str,
        summary: str,
    ) -> None:
        payload = dict(approval)
        payload["outcome_ref"] = None
        self._conversation.append_event(
            conversation_id=str(approval["conversation_id"]),
            trace_id=str(approval["trace_id"]),
            event_type=event_type,
            status=status,
            summary=summary,
            task_id=str(approval["task_id"]),
            run_id=(
                str(approval["run_id"])
                if approval.get("run_id") is not None
                else None
            ),
            payload=payload,
        )

    def _append_receipt_event(
        self,
        *,
        receipt: Mapping[str, Any],
        event_type: str,
        status: str,
        summary: str,
    ) -> None:
        self._conversation.append_event(
            conversation_id=str(receipt["conversation_id"]),
            trace_id=str(receipt["trace_id"]),
            event_type=event_type,
            status=status,
            summary=summary,
            task_id=str(receipt["task_id"]),
            run_id=str(receipt["run_id"]),
            payload={
                **dict(receipt),
                "task_title": self._task_title(str(receipt["task_id"])),
            },
        )

    def _task_title(self, task_id: str) -> str:
        with self._db.connect() as conn:
            row = conn.execute(
                "SELECT title FROM conversation_tasks WHERE task_id = ?",
                (task_id,),
            ).fetchone()
        return str(_row_value(row, "title", ""))
