"""User-approved dispatch plans for bounded multi-Worker execution.

Plans are persisted before any Worker Task or Run exists. Approval reserves the
whole plan budget once, then child Runs consume their assigned slice of that
reservation instead of opening a new user/agent/global budget for every retry.
"""

from __future__ import annotations

import hashlib
import json
import time
from collections.abc import Mapping, Sequence
from typing import Any

from octop.infra.conversation.budget import RouterBudgetGate, _day_period
from octop.infra.conversation.service import (
    BudgetExceededError,
    ConversationService,
    InvalidTransitionError,
    NotFoundError,
    RouterPhase0Error,
    _budget_payload,
    _clip,
    _int_value,
    _json_text,
    _json_value,
    _new_id,
    _now,
    _row_value,
)
from octop.infra.conversation.shadow import classify_shadow_route

PLAN_STATUSES = frozenset(
    {
        "draft",
        "awaiting_approval",
        "approved",
        "rejected",
        "executing",
        "completed",
        "partial",
        "failed",
        "cancelled",
    }
)
PLAN_TERMINAL_STATUSES = frozenset(
    {"rejected", "completed", "partial", "failed", "cancelled"}
)
PLAN_ITEM_TERMINAL_STATUSES = frozenset({"succeeded", "failed", "cancelled"})
PLAN_ITEM_STATUSES = frozenset(
    {
        "waiting_dependency",
        "ready",
        "queued",
        "running",
        "retrying",
        "succeeded",
        "failed",
        "cancelled",
    }
)
MAX_PLAN_ITEMS = 4
DEFAULT_ITEM_TOKEN_LIMIT = 128_000
DEFAULT_BUDGET_EXTENSION_TOKENS = 64_000
MAX_BUDGET_EXTENSIONS = 1
MAX_TOTAL_ATTEMPTS = 5


def _setting_int(
    settings_repo: Any,
    key: str,
    default: int,
    *,
    minimum: int,
    maximum: int,
) -> int:
    try:
        raw = settings_repo.get(key) if settings_repo is not None else None
        value = int(raw) if raw is not None and str(raw).strip() else int(default)
    except (TypeError, ValueError):
        value = int(default)
    return max(minimum, min(maximum, value))


def _conversation_plan_error(message: str) -> RouterPhase0Error:
    return RouterPhase0Error(message)


def _canonical_hash(payload: Any) -> str:
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _scope_remaining(
    conn: Any,
    *,
    scope_type: str,
    scope_id: str,
    period_start: int,
    period_end: int,
    configured_limit: int,
) -> int:
    row = conn.execute(
        "SELECT spent_tokens, reserved_tokens, limit_tokens FROM budget_ledgers "
        "WHERE scope_type = ? AND scope_id = ? AND period_start = ? AND period_end = ?",
        (scope_type, scope_id, period_start, period_end),
    ).fetchone()
    if row is None:
        return max(0, int(configured_limit))
    limit_tokens = min(int(configured_limit), _int_value(row, "limit_tokens"))
    used = _int_value(row, "spent_tokens") + _int_value(row, "reserved_tokens")
    return max(0, limit_tokens - used)


class DispatchPlanService:
    """Owns plan validation, approval, execution transitions, and aggregation."""

    def __init__(
        self,
        db: Any,
        settings_repo: Any,
        *,
        timezone_name: str = "Asia/Shanghai",
        budget_gate: RouterBudgetGate | None = None,
        max_concurrency: int = 2,
        max_attempts: int = 3,
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
        self._max_concurrency = max(1, int(max_concurrency))
        self._max_attempts = max(1, min(5, int(max_attempts)))
        self._budget_extension_tokens = _setting_int(
            settings_repo,
            "router.worker.budget_extension_tokens",
            DEFAULT_BUDGET_EXTENSION_TOKENS,
            minimum=0,
            maximum=1_000_000,
        )

    @property
    def default_worker_token_limit(self) -> int:
        return max(1_000, self._budget_gate.config_for("worker").run_tokens)

    def create_plan(
        self,
        *,
        conversation_id: str,
        trace_id: str,
        context_pack_id: str | None,
        objective: str,
        items: Sequence[Mapping[str, Any]],
    ) -> dict[str, Any]:
        normalized_objective = _clip(str(objective or ""), 20_000)
        if not normalized_objective:
            raise _conversation_plan_error("dispatch plan objective is required")
        root_decision = classify_shadow_route(normalized_objective)
        if root_decision["intent"] == "side_effect_task":
            raise _conversation_plan_error(
                "Phase 3.5 only accepts read-only dispatch plans"
            )
        normalized = self._normalize_items(items)
        config = self._budget_gate.config_for("worker")
        now = _now()
        plan_id = _new_id("plan")
        hard_tokens = sum(int(item["token_limit"]) for item in normalized)
        estimated_tokens = sum(int(item["estimated_tokens"]) for item in normalized)
        hard_cost = hard_tokens * config.cost_per_1k_tokens_micros // 1_000
        soft_cost = estimated_tokens * config.cost_per_1k_tokens_micros // 1_000
        max_concurrency = min(MAX_PLAN_ITEMS, self._max_concurrency)
        plan_hash = self._hash_plan(
            objective=normalized_objective,
            items=normalized,
            max_concurrency=max_concurrency,
            max_attempts_per_worker=self._max_attempts,
            cost_per_1k_tokens_micros=config.cost_per_1k_tokens_micros,
            version=1,
        )
        with self._db.transaction() as conn:
            conversation = conn.execute(
                "SELECT conversation_id FROM main_conversations WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchone()
            if conversation is None:
                raise NotFoundError("main conversation not found")
            conn.execute(
                "INSERT INTO dispatch_plans("
                "plan_id, conversation_id, trace_id, context_pack_id, objective, status, "
                "version, plan_hash, planning_tokens, estimated_tokens, hard_token_limit, "
                "soft_cost_micros, hard_cost_limit_micros, cost_per_1k_tokens_micros, "
                "max_concurrency, max_attempts_per_worker, created_at, proposed_at"
                ") VALUES (?, ?, ?, ?, ?, 'awaiting_approval', 1, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    plan_id,
                    conversation_id,
                    _clip(trace_id, 200),
                    _clip(context_pack_id, 200) if context_pack_id else None,
                    normalized_objective,
                    plan_hash,
                    estimated_tokens,
                    hard_tokens,
                    soft_cost,
                    hard_cost,
                    config.cost_per_1k_tokens_micros,
                    max_concurrency,
                    self._max_attempts,
                    now,
                    now,
                ),
            )
            for index, item in enumerate(normalized):
                conn.execute(
                    "INSERT INTO dispatch_plan_items("
                    "plan_item_id, plan_id, item_key, title, objective, worker_role, "
                    "depends_on_json, status, sort_order, estimated_tokens, token_limit, "
                    "estimated_cost_micros, cost_limit_micros, created_at, updated_at"
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        _new_id("planitem"),
                        plan_id,
                        item["key"],
                        item["title"],
                        item["objective"],
                        item["worker_role"],
                        _json_text(item["depends_on"], default=[]),
                        "waiting_dependency" if item["depends_on"] else "ready",
                        index,
                        item["estimated_tokens"],
                        item["token_limit"],
                        item["estimated_tokens"]
                        * config.cost_per_1k_tokens_micros
                        // 1_000,
                        item["token_limit"] * config.cost_per_1k_tokens_micros // 1_000,
                        now,
                        now,
                    ),
                )
        plan = self.get_plan(
            agent_id=self._agent_id_for_conversation(conversation_id),
            user_id=self._user_id_for_conversation(conversation_id),
            plan_id=plan_id,
        )
        self._conversation.append_event(
            conversation_id=conversation_id,
            trace_id=str(plan["trace_id"]),
            event_type="plan.proposed",
            status="awaiting_approval",
            summary=f"Proposed {len(plan['items'])} Worker steps for approval",
            plan_id=plan_id,
            payload=plan,
        )
        return plan

    def get_plan(
        self,
        *,
        agent_id: str,
        user_id: int,
        plan_id: str,
    ) -> dict[str, Any]:
        with self._db.connect() as conn:
            row = self._load_plan_row(
                conn,
                plan_id=plan_id,
                agent_id=agent_id,
                user_id=int(user_id),
            )
            return self._plan_payload(conn, row)

    def approve_plan(
        self,
        *,
        agent_id: str,
        user_id: int,
        plan_id: str,
        plan_version: int,
        plan_hash: str,
    ) -> dict[str, Any]:
        now = _now()
        with self._db.transaction() as conn:
            plan_row = self._load_plan_row(
                conn,
                plan_id=plan_id,
                agent_id=agent_id,
                user_id=int(user_id),
            )
            if str(_row_value(plan_row, "status")) != "awaiting_approval":
                raise InvalidTransitionError("dispatch plan is not awaiting approval")
            if int(_row_value(plan_row, "version")) != int(plan_version):
                raise InvalidTransitionError("dispatch plan version no longer matches")
            current_hash = self._hash_rows(conn, plan_row)
            if current_hash != str(plan_hash) or current_hash != str(
                _row_value(plan_row, "plan_hash")
            ):
                raise InvalidTransitionError(
                    "dispatch plan changed; refresh and approve the new version"
                )
            item_rows = self._item_rows(conn, plan_id)
            if not item_rows or len(item_rows) > MAX_PLAN_ITEMS:
                raise RouterPhase0Error("dispatch plan has an invalid Worker count")
            config = self._budget_gate.config_for("worker")
            if (
                int(config.cost_per_1k_tokens_micros)
                != _int_value(plan_row, "cost_per_1k_tokens_micros")
            ):
                raise InvalidTransitionError(
                    "Worker pricing changed; refresh the plan before approval"
                )
            specs = self._budget_specs(
                plan_row=plan_row,
                config=config,
                now=now,
            )
            reservations = self._reserve_specs(conn, specs)
            budget_json = _json_text(
                {
                    "reservations": reservations,
                    "cost_per_1k_tokens_micros": config.cost_per_1k_tokens_micros,
                    "reserved_at": now,
                },
                default={},
            )
            conversation_id = str(_row_value(plan_row, "conversation_id"))
            trace_id = str(_row_value(plan_row, "trace_id"))
            context_pack_id = _row_value(plan_row, "context_pack_id")
            for item in item_rows:
                task_id = _new_id("task")
                capsule = {
                    "trace_id": trace_id,
                    "context_pack_id": context_pack_id,
                    "read_only": True,
                    "plan_id": plan_id,
                    "plan_item_id": str(_row_value(item, "plan_item_id")),
                    "worker_role": str(_row_value(item, "worker_role")),
                }
                conn.execute(
                    "INSERT INTO conversation_tasks("
                    "task_id, conversation_id, task_type, title, subject_key, objective, "
                    "status, requires_user, capsule_json, priority, created_at, updated_at, "
                    "expires_at, plan_id, plan_item_id"
                    ") VALUES (?, ?, 'read_only', ?, ?, ?, 'open', 0, ?, 0, ?, ?, NULL, ?, ?)",
                    (
                        task_id,
                        conversation_id,
                        str(_row_value(item, "title")),
                        f"plan:{plan_id}:{_row_value(item, 'item_key')}",
                        str(_row_value(item, "objective")),
                        _json_text(capsule, default={}),
                        now,
                        now,
                        plan_id,
                        str(_row_value(item, "plan_item_id")),
                    ),
                )
                conn.execute(
                    "UPDATE dispatch_plan_items SET child_task_id = ?, updated_at = ? "
                    "WHERE plan_item_id = ?",
                    (task_id, now, str(_row_value(item, "plan_item_id"))),
                )
            conn.execute(
                "UPDATE dispatch_plans SET status = 'executing', approved_at = ?, "
                "budget_json = ? WHERE plan_id = ?",
                (now, budget_json, plan_id),
            )
            self._unlock_waiting_items(conn, plan_id=plan_id, now=now)
            result_row = self._load_plan_row(
                conn,
                plan_id=plan_id,
                agent_id=agent_id,
                user_id=int(user_id),
            )
            result = self._plan_payload(conn, result_row)
        self._append_plan_event(result, "plan.approved", "approved")
        self._append_plan_event(result, "plan.started", "executing")
        return result

    def reject_plan(
        self,
        *,
        agent_id: str,
        user_id: int,
        plan_id: str,
        plan_version: int,
        plan_hash: str,
    ) -> dict[str, Any]:
        now = _now()
        with self._db.transaction() as conn:
            row = self._load_plan_row(
                conn,
                plan_id=plan_id,
                agent_id=agent_id,
                user_id=int(user_id),
            )
            if str(_row_value(row, "status")) != "awaiting_approval":
                raise InvalidTransitionError("dispatch plan is not awaiting approval")
            if int(_row_value(row, "version")) != int(plan_version):
                raise InvalidTransitionError("dispatch plan version no longer matches")
            current_hash = self._hash_rows(conn, row)
            if current_hash != str(plan_hash):
                raise InvalidTransitionError("dispatch plan hash no longer matches")
            conn.execute(
                "UPDATE dispatch_plans SET status = 'rejected', finished_at = ?, "
                "result_summary = 'Rejected by the user' WHERE plan_id = ?",
                (now, plan_id),
            )
            updated = self._load_plan_row(
                conn,
                plan_id=plan_id,
                agent_id=agent_id,
                user_id=int(user_id),
            )
            result = self._plan_payload(conn, updated)
        self._append_plan_event(result, "plan.rejected", "rejected")
        return result

    def cancel_plan(
        self,
        *,
        agent_id: str,
        user_id: int,
        plan_id: str,
    ) -> dict[str, Any]:
        now = _now()
        with self._db.transaction() as conn:
            row = self._load_plan_row(
                conn,
                plan_id=plan_id,
                agent_id=agent_id,
                user_id=int(user_id),
            )
            status = str(_row_value(row, "status"))
            if status in PLAN_TERMINAL_STATUSES:
                return self._plan_payload(conn, row)
            actual_tokens = self._plan_actual_tokens(conn, plan_id)
            conn.execute(
                "UPDATE conversation_tasks SET status = 'cancelled', updated_at = ? "
                "WHERE plan_id = ? AND status IN ('open', 'in_progress', 'needs_user')",
                (now, plan_id),
            )
            conn.execute(
                "UPDATE task_runs SET status = 'cancelled', failure_class = 'plan_cancelled', "
                "finished_at = ?, last_progress_at = ?, lease_until = NULL "
                "WHERE task_id IN (SELECT child_task_id FROM dispatch_plan_items "
                "WHERE plan_id = ? AND child_task_id IS NOT NULL) "
                "AND status IN ('queued', 'running', 'waiting_user')",
                (now, now, plan_id),
            )
            conn.execute(
                "UPDATE dispatch_plan_items SET status = 'cancelled', "
                "result_summary = CASE WHEN status IN ('succeeded', 'failed') "
                "THEN result_summary ELSE 'Plan cancelled' END, updated_at = ? "
                "WHERE plan_id = ? AND status NOT IN ('succeeded', 'failed', 'cancelled')",
                (now, plan_id),
            )
            if status in {"executing", "approved"}:
                self._settle_budget(
                    conn,
                    plan_row=row,
                    actual_tokens=actual_tokens,
                )
            conn.execute(
                "UPDATE dispatch_plans SET status = 'cancelled', actual_tokens = ?, "
                "actual_cost_micros = ?, finished_at = ?, "
                "result_summary = 'Cancelled by the user' WHERE plan_id = ?",
                (
                    actual_tokens,
                    self._cost_for_tokens(row, actual_tokens),
                    now,
                    plan_id,
                ),
            )
            updated = self._load_plan_row(
                conn,
                plan_id=plan_id,
                agent_id=agent_id,
                user_id=int(user_id),
            )
            result = self._plan_payload(conn, updated)
        self._append_plan_event(result, "plan.cancelled", "cancelled")
        return result

    def worker_budget_context(self, *, task_id: str) -> dict[str, Any] | None:
        with self._db.connect() as conn:
            row = conn.execute(
                "SELECT i.*, p.conversation_id, p.trace_id, p.status AS plan_status "
                "FROM dispatch_plan_items i "
                "JOIN dispatch_plans p ON p.plan_id = i.plan_id "
                "WHERE i.child_task_id = ?",
                (task_id,),
            ).fetchone()
            if row is None:
                return None
            used = self._task_actual_tokens(conn, task_id)
            token_limit = max(0, _int_value(row, "token_limit"))
            depends_on = _json_value(
                _row_value(row, "depends_on_json"), default=[]
            )
            dependency_summaries: list[dict[str, str]] = []
            if depends_on:
                placeholders = ", ".join("?" for _ in depends_on)
                dependency_rows = conn.execute(
                    f"SELECT item_key, title, result_summary "
                    f"FROM dispatch_plan_items WHERE plan_id = ? "
                    f"AND item_key IN ({placeholders}) ORDER BY sort_order ASC",
                    (str(_row_value(row, "plan_id")), *depends_on),
                ).fetchall()
                dependency_summaries = [
                    {
                        "key": str(_row_value(dep, "item_key")),
                        "title": str(_row_value(dep, "title")),
                        "result_summary": _clip(
                            str(_row_value(dep, "result_summary", "")),
                            2_000,
                        ),
                    }
                    for dep in dependency_rows
                ]
            return {
                "plan_id": str(_row_value(row, "plan_id")),
                "plan_item_id": str(_row_value(row, "plan_item_id")),
                "conversation_id": str(_row_value(row, "conversation_id")),
                "trace_id": str(_row_value(row, "trace_id")),
                "token_limit": token_limit,
                "used_tokens": used,
                "remaining_tokens": max(0, token_limit - used),
                "dependency_summaries": dependency_summaries,
            }

    def on_worker_started(self, *, task_id: str) -> None:
        plan = self._update_item_for_task(
            task_id=task_id,
            item_status="running",
            summary="",
        )
        if plan is not None:
            self._append_plan_event(plan, "plan.progress", "executing")

    def on_worker_retry(self, *, task_id: str, summary: str) -> None:
        plan = self._update_item_for_task(
            task_id=task_id,
            item_status="retrying",
            summary=_clip(summary, 2_000),
        )
        if plan is not None:
            self._append_plan_event(plan, "plan.progress", "executing")

    def on_worker_progress(self, *, task_id: str, tokens_used: int) -> None:
        with self._db.transaction() as conn:
            row = conn.execute(
                "SELECT i.plan_id, i.actual_tokens FROM dispatch_plan_items i "
                "JOIN dispatch_plans p ON p.plan_id = i.plan_id "
                "WHERE i.child_task_id = ? AND p.status = 'executing'",
                (task_id,),
            ).fetchone()
            if row is None:
                return
            conn.execute(
                "UPDATE dispatch_plan_items SET actual_tokens = ?, "
                "last_progress_at = ?, updated_at = ? WHERE child_task_id = ?",
                (
                    max(_int_value(row, "actual_tokens"), max(0, int(tokens_used))),
                    _now(),
                    _now(),
                    task_id,
                ),
            )
            plan_row = conn.execute(
                "SELECT p.*, c.agent_id, c.user_id FROM dispatch_plans p "
                "JOIN main_conversations c ON c.conversation_id = p.conversation_id "
                "WHERE p.plan_id = ?",
                (str(_row_value(row, "plan_id")),),
            ).fetchone()
            plan = self._plan_payload(conn, plan_row) if plan_row is not None else None
        if plan is not None:
            self._append_plan_event(plan, "plan.progress", "executing")

    def on_worker_budget_exhausted(
        self,
        *,
        task_id: str,
        run_id: str,
        message: str,
    ) -> dict[str, Any] | None:
        """Pause one Worker for an explicit, bounded budget extension request."""
        now = _now()
        with self._db.transaction() as conn:
            row = conn.execute(
                "SELECT i.*, p.status AS plan_status, p.hard_token_limit, "
                "p.budget_request_json, p.budget_extension_count, "
                "p.cost_per_1k_tokens_micros, c.agent_id, c.user_id "
                "FROM dispatch_plan_items i "
                "JOIN dispatch_plans p ON p.plan_id = i.plan_id "
                "JOIN main_conversations c ON c.conversation_id = p.conversation_id "
                "WHERE i.child_task_id = ?",
                (task_id,),
            ).fetchone()
            if row is None or str(_row_value(row, "plan_status")) != "executing":
                return None
            if _int_value(row, "budget_extension_count") >= MAX_BUDGET_EXTENSIONS:
                return None
            existing = _json_value(
                _row_value(row, "budget_request_json"),
                default={},
            )
            if isinstance(existing, Mapping) and existing.get("request_id"):
                return None

            plan_id = str(_row_value(row, "plan_id"))
            plan_item_id = str(_row_value(row, "plan_item_id"))
            item_limit = max(0, _int_value(row, "token_limit"))
            used_tokens = max(
                self._task_actual_tokens(conn, task_id),
                _int_value(row, "actual_tokens"),
            )
            config = self._budget_gate.config_for("worker")
            agent_id = str(_row_value(row, "agent_id"))
            user_id = int(_row_value(row, "user_id"))
            day_start, day_end = _day_period(now, self._timezone)
            remaining = {
                "user": _scope_remaining(
                    conn,
                    scope_type="user_daily",
                    scope_id=str(user_id),
                    period_start=day_start,
                    period_end=day_end,
                    configured_limit=config.user_daily_tokens,
                ),
                "agent": _scope_remaining(
                    conn,
                    scope_type="agent_daily",
                    scope_id=agent_id,
                    period_start=day_start,
                    period_end=day_end,
                    configured_limit=config.agent_daily_tokens,
                ),
                "global": _scope_remaining(
                    conn,
                    scope_type="global_daily",
                    scope_id="all",
                    period_start=day_start,
                    period_end=day_end,
                    configured_limit=config.global_daily_tokens,
                ),
                "worker_surface": _scope_remaining(
                    conn,
                    scope_type="surface_daily",
                    scope_id=f"worker:{agent_id}:{user_id}",
                    period_start=day_start,
                    period_end=day_end,
                    configured_limit=config.surface_daily_tokens,
                ),
            }
            available = min(remaining.values())
            price_matches = (
                int(config.cost_per_1k_tokens_micros)
                == _int_value(row, "cost_per_1k_tokens_micros")
            )
            requested_tokens = min(self._budget_extension_tokens, available)
            can_continue = (
                self._budget_extension_tokens > 0
                and requested_tokens >= 1_000
                and price_matches
            )
            if not can_continue:
                requested_tokens = 0
            request = {
                "request_id": _new_id("budgetreq"),
                "plan_item_id": plan_item_id,
                "item_key": str(_row_value(row, "item_key")),
                "title": str(_row_value(row, "title")),
                "status": "awaiting_approval" if can_continue else "unavailable",
                "reason": (
                    _clip(message, 1_000)
                    if price_matches
                    else "Worker pricing changed; this plan cannot safely continue"
                ),
                "used_tokens": used_tokens,
                "old_token_limit": item_limit,
                "proposed_token_limit": item_limit + requested_tokens,
                "requested_tokens": requested_tokens,
                "requested_cost_micros": (
                    requested_tokens * config.cost_per_1k_tokens_micros // 1_000
                ),
                "cost_per_1k_tokens_micros": config.cost_per_1k_tokens_micros,
                "remaining_user_tokens": remaining["user"],
                "remaining_agent_tokens": remaining["agent"],
                "remaining_global_tokens": remaining["global"],
                "remaining_worker_surface_tokens": remaining["worker_surface"],
                "can_continue": can_continue,
                "max_extensions": MAX_BUDGET_EXTENSIONS,
                "created_at": now,
            }
            conn.execute(
                "UPDATE dispatch_plan_items SET status = 'retrying', "
                "result_summary = ?, last_progress_at = ?, updated_at = ? "
                "WHERE plan_item_id = ?",
                (
                    _clip(
                        "Worker reached its token budget; waiting for user decision",
                        2_000,
                    ),
                    now,
                    now,
                    plan_item_id,
                ),
            )
            conn.execute(
                "UPDATE task_runs SET status = 'failed', failure_class = "
                "'budget_exhausted', finished_at = ?, last_progress_at = ?, "
                "lease_until = NULL WHERE run_id = ? "
                "AND status IN ('queued', 'running', 'waiting_user')",
                (now, now, run_id),
            )
            conn.execute(
                "UPDATE conversation_tasks SET status = 'needs_user', updated_at = ? "
                "WHERE task_id = ? AND status IN ('open', 'in_progress', 'needs_user')",
                (now, task_id),
            )
            conn.execute(
                "UPDATE dispatch_plans SET budget_request_json = ? WHERE plan_id = ?",
                (_json_text(request, default={}), plan_id),
            )
            plan_row = conn.execute(
                "SELECT p.*, c.agent_id, c.user_id FROM dispatch_plans p "
                "JOIN main_conversations c ON c.conversation_id = p.conversation_id "
                "WHERE p.plan_id = ?",
                (plan_id,),
            ).fetchone()
            plan = self._plan_payload(conn, plan_row) if plan_row is not None else None
        if plan is not None:
            self._append_plan_event(plan, "plan.budget_exhausted", "executing")
        return plan

    def extend_budget(
        self,
        *,
        agent_id: str,
        user_id: int,
        plan_id: str,
        plan_version: int,
        plan_hash: str,
        budget_request_id: str,
    ) -> dict[str, Any]:
        """Approve one exact budget request and enqueue exactly one more attempt."""
        now = _now()
        with self._db.transaction() as conn:
            plan_row = self._load_plan_row(
                conn,
                plan_id=plan_id,
                agent_id=agent_id,
                user_id=int(user_id),
            )
            if str(_row_value(plan_row, "status")) != "executing":
                raise InvalidTransitionError("dispatch plan is not executing")
            if int(_row_value(plan_row, "version")) != int(plan_version):
                raise InvalidTransitionError("dispatch plan version no longer matches")
            current_hash = self._hash_rows(conn, plan_row)
            if current_hash != str(plan_hash) or current_hash != str(
                _row_value(plan_row, "plan_hash")
            ):
                raise InvalidTransitionError(
                    "dispatch plan changed; refresh before continuing"
                )
            if _int_value(plan_row, "budget_extension_count") >= MAX_BUDGET_EXTENSIONS:
                raise InvalidTransitionError("this plan already used its budget extension")
            request = _json_value(
                _row_value(plan_row, "budget_request_json"),
                default={},
            )
            if not isinstance(request, Mapping) or str(
                request.get("request_id") or ""
            ) != str(budget_request_id):
                raise InvalidTransitionError("budget request is no longer active")
            if not bool(request.get("can_continue")):
                raise BudgetExceededError(
                    str(request.get("reason") or "no budget is available to continue")
                )

            extra_tokens = int(request.get("requested_tokens") or 0)
            if extra_tokens < 1_000:
                raise BudgetExceededError("the approved budget extension is too small")
            plan_item_id = str(request.get("plan_item_id") or "")
            item_row = conn.execute(
                "SELECT * FROM dispatch_plan_items WHERE plan_item_id = ? "
                "AND plan_id = ?",
                (plan_item_id, plan_id),
            ).fetchone()
            if item_row is None or str(_row_value(item_row, "status")) not in {
                "retrying",
                "running",
                "queued",
            }:
                raise InvalidTransitionError("Worker is no longer waiting for budget")
            task_id = _row_value(item_row, "child_task_id")
            if not task_id:
                raise InvalidTransitionError("Worker task is unavailable")

            config = self._budget_gate.config_for("worker")
            if (
                int(config.cost_per_1k_tokens_micros)
                != int(request.get("cost_per_1k_tokens_micros") or -1)
            ):
                raise InvalidTransitionError(
                    "Worker pricing changed; abandon and regenerate the plan"
                )
            new_total = _int_value(plan_row, "hard_token_limit") + extra_tokens
            new_cost_limit = (
                _int_value(plan_row, "hard_cost_limit_micros")
                + int(request.get("requested_cost_micros") or 0)
            )
            specs = self._budget_extension_specs(
                conn,
                plan_row=plan_row,
                request=request,
                config=config,
                extra_tokens=extra_tokens,
                new_total_tokens=new_total,
                new_total_cost_micros=new_cost_limit,
                now=now,
            )
            reservations = self._reserve_specs(conn, specs)
            budget_raw = _json_value(
                _row_value(plan_row, "budget_json"),
                default={},
            )
            if not isinstance(budget_raw, Mapping):
                raise RouterPhase0Error("dispatch plan budget reservation is invalid")
            all_reservations = list(budget_raw.get("reservations") or [])
            all_reservations.extend(reservations)
            budget_json = _json_text(
                {
                    **dict(budget_raw),
                    "reservations": all_reservations,
                    "last_extension_at": now,
                },
                default={},
            )

            conn.execute(
                "UPDATE dispatch_plan_items SET estimated_tokens = estimated_tokens + ?, "
                "token_limit = token_limit + ?, cost_limit_micros = cost_limit_micros + ?, "
                "result_summary = 'Budget extended; queued for one more attempt', "
                "status = 'queued', updated_at = ? WHERE plan_item_id = ?",
                (
                    extra_tokens,
                    extra_tokens,
                    int(request.get("requested_cost_micros") or 0),
                    now,
                    plan_item_id,
                ),
            )
            conn.execute(
                "UPDATE dispatch_plans SET hard_token_limit = hard_token_limit + ?, "
                "estimated_tokens = estimated_tokens + ?, "
                "hard_cost_limit_micros = hard_cost_limit_micros + ?, "
                "budget_json = ?, budget_request_json = '{}', "
                "budget_extension_count = budget_extension_count + 1, "
                "budget_extended_tokens = budget_extended_tokens + ?, version = version + 1 "
                "WHERE plan_id = ?",
                (
                    extra_tokens,
                    extra_tokens,
                    int(request.get("requested_cost_micros") or 0),
                    budget_json,
                    extra_tokens,
                    plan_id,
                ),
            )
            updated_plan = conn.execute(
                "SELECT * FROM dispatch_plans WHERE plan_id = ?",
                (plan_id,),
            ).fetchone()
            if updated_plan is None:
                raise NotFoundError("dispatch plan not found")
            new_hash = self._hash_rows(conn, updated_plan)
            conn.execute(
                "UPDATE dispatch_plans SET plan_hash = ? WHERE plan_id = ?",
                (new_hash, plan_id),
            )
            attempt_row = conn.execute(
                "SELECT COALESCE(MAX(attempt_no), 0) FROM task_runs WHERE task_id = ?",
                (str(task_id),),
            ).fetchone()
            next_attempt = int(attempt_row[0] if attempt_row is not None else 0) + 1
            if next_attempt > MAX_TOTAL_ATTEMPTS:
                raise InvalidTransitionError("Worker cannot create another attempt")
            conn.execute(
                "INSERT INTO task_runs(run_id, task_id, worker_thread_id, status, "
                "attempt_no, lease_until, created_at) "
                "VALUES (?, ?, NULL, 'queued', ?, ?, ?)",
                (_new_id("run"), str(task_id), next_attempt, now, now),
            )
            conn.execute(
                "UPDATE conversation_tasks SET status = 'in_progress', updated_at = ? "
                "WHERE task_id = ? AND status = 'needs_user'",
                (now, str(task_id)),
            )
            result_row = self._load_plan_row(
                conn,
                plan_id=plan_id,
                agent_id=agent_id,
                user_id=int(user_id),
            )
            result = self._plan_payload(conn, result_row)
        self._append_plan_event(result, "plan.budget_extended", "executing")
        self._append_plan_event(result, "plan.progress", "executing")
        return result

    def on_worker_terminal(self, *, task_id: str) -> dict[str, Any] | None:
        with self._db.transaction() as conn:
            task = conn.execute(
                "SELECT t.status, t.plan_id, i.plan_item_id "
                "FROM conversation_tasks t "
                "LEFT JOIN dispatch_plan_items i ON i.child_task_id = t.task_id "
                "WHERE t.task_id = ?",
                (task_id,),
            ).fetchone()
            if task is None or _row_value(task, "plan_id") is None:
                return None
            plan_id = str(_row_value(task, "plan_id"))
            item_status = {
                "done": "succeeded",
                "failed": "failed",
                "cancelled": "cancelled",
            }.get(str(_row_value(task, "status")))
            if item_status is None:
                return None
            task_row = conn.execute(
                "SELECT capsule_json FROM conversation_tasks WHERE task_id = ?",
                (task_id,),
            ).fetchone()
            capsule = _json_value(
                _row_value(task_row, "capsule_json"),
                default={},
            )
            summary = str(capsule.get("result") or "") if isinstance(capsule, Mapping) else ""
            conn.execute(
                "UPDATE dispatch_plan_items SET status = ?, result_summary = ?, "
                "last_progress_at = ?, updated_at = ? WHERE child_task_id = ?",
                (item_status, _clip(summary, 2_000), _now(), _now(), task_id),
            )
            self._unlock_waiting_items(conn, plan_id=plan_id, now=_now())
            completed = self._finalize_if_done(conn, plan_id=plan_id)
            row = conn.execute(
                "SELECT p.*, c.agent_id, c.user_id FROM dispatch_plans p "
                "JOIN main_conversations c ON c.conversation_id = p.conversation_id "
                "WHERE p.plan_id = ?",
                (plan_id,),
            ).fetchone()
            if row is None:
                return None
            plan = self._plan_payload(conn, row)
        if completed is not None:
            event_type, status = completed
            self._append_plan_event(plan, event_type, status)
        else:
            self._append_plan_event(plan, "plan.progress", "executing")
        return plan

    def _normalize_items(
        self,
        items: Sequence[Mapping[str, Any]],
    ) -> list[dict[str, Any]]:
        if not items:
            raise RouterPhase0Error("dispatch plan requires at least one Worker item")
        if len(items) > MAX_PLAN_ITEMS:
            raise RouterPhase0Error(f"dispatch plan supports at most {MAX_PLAN_ITEMS} Workers")
        config = self._budget_gate.config_for("worker")
        normalized: list[dict[str, Any]] = []
        keys: set[str] = set()
        for index, raw in enumerate(items):
            key = _clip(str(raw.get("key") or f"worker_{index + 1}"), 80)
            if not key or key in keys:
                raise RouterPhase0Error("dispatch plan Worker keys must be unique")
            title = _clip(str(raw.get("title") or ""), 200)
            objective = _clip(str(raw.get("objective") or ""), 20_000)
            if not title or not objective:
                raise RouterPhase0Error("each dispatch plan Worker needs a title and objective")
            decision = classify_shadow_route(objective)
            if decision["intent"] == "side_effect_task":
                raise RouterPhase0Error(
                    f"Worker {key} requests a side effect and is outside Phase 3.5"
                )
            token_limit = int(raw.get("token_limit") or config.run_tokens)
            token_limit = max(1_000, min(token_limit, max(1_000, config.run_tokens)))
            depends_on = [
                _clip(str(dep), 80)
                for dep in (raw.get("depends_on") or [])
                if str(dep).strip()
            ]
            normalized.append(
                {
                    "key": key,
                    "title": title,
                    "objective": objective,
                    "worker_role": _clip(
                        str(raw.get("worker_role") or "research"), 80
                    ),
                    "depends_on": list(dict.fromkeys(depends_on)),
                    "estimated_tokens": token_limit,
                    "token_limit": token_limit,
                }
            )
            keys.add(key)
        key_set = {item["key"] for item in normalized}
        for item in normalized:
            unknown = set(item["depends_on"]) - key_set
            if unknown:
                raise RouterPhase0Error(
                    f"Worker {item['key']} depends on unknown Workers: {sorted(unknown)}"
                )
            if item["key"] in item["depends_on"]:
                raise RouterPhase0Error(f"Worker {item['key']} cannot depend on itself")
        graph = {item["key"]: list(item["depends_on"]) for item in normalized}
        visiting: set[str] = set()
        visited: set[str] = set()

        def visit(key: str) -> None:
            if key in visiting:
                raise RouterPhase0Error("dispatch plan dependencies contain a cycle")
            if key in visited:
                return
            visiting.add(key)
            for dependency in graph[key]:
                visit(dependency)
            visiting.remove(key)
            visited.add(key)

        for key in graph:
            visit(key)
        return normalized

    def _hash_plan(
        self,
        *,
        objective: str,
        items: Sequence[Mapping[str, Any]],
        max_concurrency: int,
        max_attempts_per_worker: int,
        cost_per_1k_tokens_micros: int,
        version: int,
    ) -> str:
        return _canonical_hash(
            {
                "version": int(version),
                "objective": objective,
                "planning_tokens": 0,
                "max_concurrency": int(max_concurrency),
                "max_attempts_per_worker": int(max_attempts_per_worker),
                "cost_per_1k_tokens_micros": int(cost_per_1k_tokens_micros),
                "items": [
                    {
                        "key": item["key"],
                        "title": item["title"],
                        "objective": item["objective"],
                        "worker_role": item["worker_role"],
                        "depends_on": list(item["depends_on"]),
                        "estimated_tokens": int(item["estimated_tokens"]),
                        "token_limit": int(item["token_limit"]),
                    }
                    for item in items
                ],
            }
        )

    def _hash_rows(self, conn: Any, plan_row: Any) -> str:
        rows = self._item_rows(conn, str(_row_value(plan_row, "plan_id")))
        items = [
            {
                "key": str(_row_value(row, "item_key")),
                "title": str(_row_value(row, "title")),
                "objective": str(_row_value(row, "objective")),
                "worker_role": str(_row_value(row, "worker_role")),
                "depends_on": _json_value(
                    _row_value(row, "depends_on_json"), default=[]
                ),
                "estimated_tokens": _int_value(row, "estimated_tokens"),
                "token_limit": _int_value(row, "token_limit"),
            }
            for row in rows
        ]
        return self._hash_plan(
            objective=str(_row_value(plan_row, "objective")),
            items=items,
            max_concurrency=_int_value(plan_row, "max_concurrency"),
            max_attempts_per_worker=_int_value(
                plan_row, "max_attempts_per_worker"
            ),
            cost_per_1k_tokens_micros=_int_value(
                plan_row, "cost_per_1k_tokens_micros"
            ),
            version=_int_value(plan_row, "version"),
        )

    def _budget_specs(
        self,
        *,
        plan_row: Any,
        config: Any,
        now: int,
    ) -> list[dict[str, Any]]:
        plan_id = str(_row_value(plan_row, "plan_id"))
        agent_id = str(_row_value(plan_row, "agent_id"))
        user_id = int(_row_value(plan_row, "user_id"))
        tokens = _int_value(plan_row, "hard_token_limit")
        cost = _int_value(plan_row, "hard_cost_limit_micros")
        day_start, day_end = _day_period(now, self._timezone)
        return [
            {
                "scope_type": "plan",
                "scope_id": plan_id,
                "period_start": now - 60,
                "period_end": now + 7 * 24 * 60 * 60,
                "tokens": tokens,
                "cost_micros": cost,
                "limit_tokens": tokens,
                "limit_cost_micros": cost,
            },
            {
                "scope_type": "surface_daily",
                "scope_id": f"worker:{agent_id}:{user_id}",
                "period_start": day_start,
                "period_end": day_end,
                "tokens": tokens,
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
                "tokens": tokens,
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
                "tokens": tokens,
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
                "tokens": tokens,
                "cost_micros": cost,
                "limit_tokens": config.global_daily_tokens,
                "limit_cost_micros": (
                    config.global_daily_tokens * config.cost_per_1k_tokens_micros // 1_000
                ),
            },
        ]

    def _budget_extension_specs(
        self,
        conn: Any,
        *,
        plan_row: Any,
        request: Mapping[str, Any],
        config: Any,
        extra_tokens: int,
        new_total_tokens: int,
        new_total_cost_micros: int,
        now: int,
    ) -> list[dict[str, Any]]:
        raw = _json_value(_row_value(plan_row, "budget_json"), default={})
        reservations = raw.get("reservations") if isinstance(raw, Mapping) else None
        if not isinstance(reservations, list):
            raise RouterPhase0Error("dispatch plan budget reservation is invalid")
        plan_reservation = next(
            (
                item
                for item in reservations
                if isinstance(item, Mapping) and item.get("scope_type") == "plan"
            ),
            None,
        )
        if not isinstance(plan_reservation, Mapping):
            raise RouterPhase0Error("dispatch plan reservation is missing")

        plan_id = str(_row_value(plan_row, "plan_id"))
        agent_id = str(_row_value(plan_row, "agent_id"))
        user_id = int(_row_value(plan_row, "user_id"))
        rate = int(config.cost_per_1k_tokens_micros)
        extra_cost = int(request.get("requested_cost_micros") or 0)
        day_start, day_end = _day_period(now, self._timezone)
        plan_period_start = int(plan_reservation["period_start"])
        plan_period_end = int(plan_reservation["period_end"])
        conn.execute(
            "UPDATE budget_ledgers SET limit_tokens = ?, limit_cost_micros = ?, "
            "updated_at = ? WHERE scope_type = 'plan' AND scope_id = ? "
            "AND period_start = ? AND period_end = ?",
            (
                new_total_tokens,
                new_total_cost_micros,
                now,
                plan_id,
                plan_period_start,
                plan_period_end,
            ),
        )
        return [
            {
                "scope_type": "plan",
                "scope_id": plan_id,
                "period_start": plan_period_start,
                "period_end": plan_period_end,
                "tokens": extra_tokens,
                "cost_micros": extra_cost,
                "limit_tokens": new_total_tokens,
                "limit_cost_micros": new_total_cost_micros,
            },
            {
                "scope_type": "surface_daily",
                "scope_id": f"worker:{agent_id}:{user_id}",
                "period_start": day_start,
                "period_end": day_end,
                "tokens": extra_tokens,
                "cost_micros": extra_cost,
                "limit_tokens": config.surface_daily_tokens,
                "limit_cost_micros": (
                    config.surface_daily_tokens * rate // 1_000
                ),
            },
            {
                "scope_type": "user_daily",
                "scope_id": str(user_id),
                "period_start": day_start,
                "period_end": day_end,
                "tokens": extra_tokens,
                "cost_micros": extra_cost,
                "limit_tokens": config.user_daily_tokens,
                "limit_cost_micros": config.user_daily_tokens * rate // 1_000,
            },
            {
                "scope_type": "agent_daily",
                "scope_id": agent_id,
                "period_start": day_start,
                "period_end": day_end,
                "tokens": extra_tokens,
                "cost_micros": extra_cost,
                "limit_tokens": config.agent_daily_tokens,
                "limit_cost_micros": config.agent_daily_tokens * rate // 1_000,
            },
            {
                "scope_type": "global_daily",
                "scope_id": "all",
                "period_start": day_start,
                "period_end": day_end,
                "tokens": extra_tokens,
                "cost_micros": extra_cost,
                "limit_tokens": config.global_daily_tokens,
                "limit_cost_micros": config.global_daily_tokens * rate // 1_000,
            },
        ]

    def _reserve_specs(
        self,
        conn: Any,
        specs: Sequence[Mapping[str, Any]],
    ) -> list[dict[str, Any]]:
        reservations: list[dict[str, Any]] = []
        for spec in specs:
            scope_type = str(spec["scope_type"])
            scope_id = str(spec["scope_id"])
            period_start = int(spec["period_start"])
            period_end = int(spec["period_end"])
            tokens = int(spec["tokens"])
            cost_micros = int(spec["cost_micros"])
            self._conversation._ensure_budget_ledger(
                conn,
                scope_type=scope_type,
                scope_id=scope_id,
                period_start=period_start,
                period_end=period_end,
                limit_tokens=int(spec["limit_tokens"]),
                limit_cost_micros=int(spec["limit_cost_micros"]),
            )
            cursor = conn.execute(
                "UPDATE budget_ledgers SET reserved_tokens = reserved_tokens + ?, "
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
                raise BudgetExceededError(
                    f"dispatch plan blocked by {scope_type}:{scope_id}"
                )
            row = conn.execute(
                "SELECT * FROM budget_ledgers WHERE scope_type = ? AND scope_id = ? "
                "AND period_start = ? AND period_end = ?",
                (scope_type, scope_id, period_start, period_end),
            ).fetchone()
            assert row is not None
            reservations.append(
                {
                    **_budget_payload(row),
                    "reservation_tokens": tokens,
                    "reservation_cost_micros": cost_micros,
                }
            )
        return reservations

    def _unlock_waiting_items(self, conn: Any, *, plan_id: str, now: int) -> None:
        for _ in range(MAX_PLAN_ITEMS + 1):
            rows = self._item_rows(conn, plan_id)
            by_key = {str(_row_value(row, "item_key")): row for row in rows}
            changed = False
            for row in rows:
                if str(_row_value(row, "status")) not in {"ready", "waiting_dependency"}:
                    continue
                dependencies = _json_value(
                    _row_value(row, "depends_on_json"), default=[]
                )
                states = [
                    str(_row_value(by_key[str(dep)], "status"))
                    for dep in dependencies
                    if str(dep) in by_key
                ]
                if dependencies and (
                    not states
                    or any(state not in PLAN_ITEM_TERMINAL_STATUSES for state in states)
                ):
                    continue
                child_task_id = _row_value(row, "child_task_id")
                if (
                    not dependencies
                    or all(state == "succeeded" for state in states)
                ) and child_task_id:
                    conn.execute(
                        "UPDATE dispatch_plan_items SET status = 'queued', updated_at = ? "
                        "WHERE plan_item_id = ?",
                        (now, str(_row_value(row, "plan_item_id"))),
                    )
                    conn.execute(
                        "UPDATE conversation_tasks SET status = 'in_progress', updated_at = ? "
                        "WHERE task_id = ? AND status IN ('open', 'needs_user')",
                        (now, str(child_task_id)),
                    )
                    active = conn.execute(
                        "SELECT 1 FROM task_runs WHERE task_id = ? "
                        "AND status IN ('queued', 'running', 'waiting_user') LIMIT 1",
                        (str(child_task_id),),
                    ).fetchone()
                    if active is None:
                        conn.execute(
                            "INSERT INTO task_runs("
                            "run_id, task_id, worker_thread_id, status, attempt_no, "
                            "lease_until, created_at"
                            ") VALUES (?, ?, NULL, 'queued', 1, ?, ?)",
                            (_new_id("run"), str(child_task_id), now, now),
                        )
                else:
                    conn.execute(
                        "UPDATE dispatch_plan_items SET status = 'failed', "
                        "result_summary = 'A dependency did not complete', updated_at = ? "
                        "WHERE plan_item_id = ?",
                        (now, str(_row_value(row, "plan_item_id"))),
                    )
                    if child_task_id:
                        conn.execute(
                            "UPDATE conversation_tasks SET status = 'failed', updated_at = ? "
                            "WHERE task_id = ? AND status IN ('open', 'needs_user', 'in_progress')",
                            (now, str(child_task_id)),
                        )
                changed = True
            if not changed:
                return

    def _finalize_if_done(
        self,
        conn: Any,
        *,
        plan_id: str,
    ) -> tuple[str, str] | None:
        rows = self._item_rows(conn, plan_id)
        statuses = [str(_row_value(row, "status")) for row in rows]
        if not rows or any(status not in PLAN_ITEM_TERMINAL_STATUSES for status in statuses):
            return None
        success_count = statuses.count("succeeded")
        if success_count == len(statuses):
            status = "completed"
            event_type = "plan.completed"
            summary = "All Worker steps completed"
        elif success_count == 0:
            status = "failed"
            event_type = "plan.failed"
            summary = "No Worker step completed"
        else:
            status = "partial"
            event_type = "plan.completed"
            summary = f"{success_count} of {len(statuses)} Worker steps completed"
        actual_tokens = self._plan_actual_tokens(conn, plan_id)
        plan_row = conn.execute(
            "SELECT * FROM dispatch_plans WHERE plan_id = ?", (plan_id,)
        ).fetchone()
        if plan_row is None:
            return None
        self._settle_budget(
            conn,
            plan_row=plan_row,
            actual_tokens=actual_tokens,
        )
        conn.execute(
            "UPDATE dispatch_plans SET status = ?, actual_tokens = ?, "
            "actual_cost_micros = ?, result_summary = ?, finished_at = ? "
            "WHERE plan_id = ?",
            (
                status,
                actual_tokens,
                self._cost_for_tokens(plan_row, actual_tokens),
                summary,
                _now(),
                plan_id,
            ),
        )
        return event_type, status

    def _settle_budget(
        self,
        conn: Any,
        *,
        plan_row: Any,
        actual_tokens: int,
    ) -> None:
        raw = _json_value(_row_value(plan_row, "budget_json"), default={})
        if not isinstance(raw, Mapping):
            raise RouterPhase0Error("dispatch plan budget reservation is invalid")
        reservations = raw.get("reservations")
        if not isinstance(reservations, list) or not reservations:
            return
        total_limit = max(0, _int_value(plan_row, "hard_token_limit"))
        enforced_tokens = min(max(0, int(actual_tokens)), total_limit)
        rate = _int_value(plan_row, "cost_per_1k_tokens_micros")
        actual_cost = enforced_tokens * rate // 1_000
        aggregated: dict[tuple[str, str, int, int], dict[str, int]] = {}
        for reservation in reservations:
            if not isinstance(reservation, Mapping):
                continue
            key = (
                str(reservation["scope_type"]),
                str(reservation["scope_id"]),
                int(reservation["period_start"]),
                int(reservation["period_end"]),
            )
            values = aggregated.setdefault(
                key,
                {"reserved_tokens": 0, "reserved_cost_micros": 0},
            )
            values["reserved_tokens"] += int(
                reservation.get("reservation_tokens") or 0
            )
            values["reserved_cost_micros"] += int(
                reservation.get("reservation_cost_micros") or 0
            )
        for (
            scope_type,
            scope_id,
            period_start,
            period_end,
        ), values in aggregated.items():
            reserved_tokens = values["reserved_tokens"]
            reserved_cost = values["reserved_cost_micros"]
            cursor = conn.execute(
                "UPDATE budget_ledgers SET reserved_tokens = reserved_tokens - ?, "
                "reserved_cost_micros = reserved_cost_micros - ?, "
                "spent_tokens = spent_tokens + ?, spent_cost_micros = spent_cost_micros + ?, "
                "updated_at = ? WHERE scope_type = ? AND scope_id = ? "
                "AND period_start = ? AND period_end = ? "
                "AND reserved_tokens >= ? AND reserved_cost_micros >= ? "
                "AND spent_tokens + ? <= limit_tokens "
                "AND spent_cost_micros + ? <= limit_cost_micros",
                (
                    reserved_tokens,
                    reserved_cost,
                    enforced_tokens,
                    actual_cost,
                    _now(),
                    scope_type,
                    scope_id,
                    period_start,
                    period_end,
                    reserved_tokens,
                    reserved_cost,
                    enforced_tokens,
                    actual_cost,
                ),
            )
            if cursor.rowcount != 1:
                raise BudgetExceededError("dispatch plan budget settlement failed")
        conn.execute(
            "UPDATE dispatch_plans SET budget_json = '{}', "
            "budget_request_json = '{}' WHERE plan_id = ?",
            (str(_row_value(plan_row, "plan_id")),),
        )

    def _update_item_for_task(
        self,
        *,
        task_id: str,
        item_status: str,
        summary: str,
    ) -> dict[str, Any] | None:
        if item_status not in PLAN_ITEM_STATUSES:
            raise RouterPhase0Error(f"unknown plan item status: {item_status}")
        with self._db.transaction() as conn:
            row = conn.execute(
                "SELECT i.plan_id FROM dispatch_plan_items i "
                "JOIN dispatch_plans p ON p.plan_id = i.plan_id "
                "WHERE i.child_task_id = ? AND p.status = 'executing'",
                (task_id,),
            ).fetchone()
            if row is None:
                return None
            conn.execute(
                "UPDATE dispatch_plan_items SET status = ?, result_summary = ?, "
                "last_progress_at = ?, updated_at = ? WHERE child_task_id = ?",
                (item_status, summary, _now(), _now(), task_id),
            )
            plan_row = conn.execute(
                "SELECT p.*, c.agent_id, c.user_id FROM dispatch_plans p "
                "JOIN main_conversations c ON c.conversation_id = p.conversation_id "
                "WHERE p.plan_id = ?",
                (str(_row_value(row, "plan_id")),),
            ).fetchone()
            return self._plan_payload(conn, plan_row) if plan_row is not None else None

    def _load_plan_row(
        self,
        conn: Any,
        *,
        plan_id: str,
        agent_id: str,
        user_id: int,
    ) -> Any:
        row = conn.execute(
            "SELECT p.*, c.agent_id, c.user_id FROM dispatch_plans p "
            "JOIN main_conversations c ON c.conversation_id = p.conversation_id "
            "WHERE p.plan_id = ? AND c.agent_id = ? AND c.user_id = ?",
            (plan_id, agent_id, int(user_id)),
        ).fetchone()
        if row is None:
            raise NotFoundError("dispatch plan not found")
        return row

    def _item_rows(self, conn: Any, plan_id: str) -> list[Any]:
        return conn.execute(
            "SELECT * FROM dispatch_plan_items WHERE plan_id = ? "
            "ORDER BY sort_order ASC, created_at ASC",
            (plan_id,),
        ).fetchall()

    def _plan_payload(self, conn: Any, plan_row: Any) -> dict[str, Any]:
        plan_id = str(_row_value(plan_row, "plan_id"))
        rows = self._item_rows(conn, plan_id)
        items: list[dict[str, Any]] = []
        for row in rows:
            task_id = _row_value(row, "child_task_id")
            task_row = (
                conn.execute(
                    "SELECT status FROM conversation_tasks WHERE task_id = ?",
                    (str(task_id),),
                ).fetchone()
                if task_id
                else None
            )
            run_count = (
                conn.execute(
                    "SELECT COUNT(*) FROM task_runs WHERE task_id = ?", (str(task_id),)
                ).fetchone()[0]
                if task_id
                else 0
            )
            actual_tokens = (
                max(
                    self._task_actual_tokens(conn, str(task_id)),
                    _int_value(row, "actual_tokens"),
                )
                if task_id
                else _int_value(row, "actual_tokens")
            )
            items.append(
                {
                    "plan_item_id": str(_row_value(row, "plan_item_id")),
                    "key": str(_row_value(row, "item_key")),
                    "child_task_id": task_id,
                    "child_task_status": (
                        str(_row_value(task_row, "status")) if task_row is not None else None
                    ),
                    "title": str(_row_value(row, "title")),
                    "objective": str(_row_value(row, "objective")),
                    "worker_role": str(_row_value(row, "worker_role")),
                    "depends_on": _json_value(
                        _row_value(row, "depends_on_json"), default=[]
                    ),
                    "status": str(_row_value(row, "status")),
                    "estimated_tokens": _int_value(row, "estimated_tokens"),
                    "token_limit": _int_value(row, "token_limit"),
                    "estimated_cost_micros": _int_value(
                        row, "estimated_cost_micros"
                    ),
                    "cost_limit_micros": _int_value(row, "cost_limit_micros"),
                    "actual_tokens": actual_tokens,
                    "actual_cost_micros": (
                        actual_tokens
                        * _int_value(plan_row, "cost_per_1k_tokens_micros")
                        // 1_000
                    ),
                    "attempt_count": int(run_count or 0),
                    "last_progress_at": _row_value(row, "last_progress_at"),
                    "result_summary": str(_row_value(row, "result_summary", "")),
                }
            )
        actual_tokens = sum(int(item["actual_tokens"]) for item in items)
        config = self._budget_gate.config_for("worker")
        now = _now()
        day_start, day_end = _day_period(now, self._timezone)
        agent_id = str(_row_value(plan_row, "agent_id"))
        user_id = int(_row_value(plan_row, "user_id"))
        hard_tokens = _int_value(plan_row, "hard_token_limit")
        return {
            "plan_id": plan_id,
            "conversation_id": str(_row_value(plan_row, "conversation_id")),
            "trace_id": str(_row_value(plan_row, "trace_id")),
            "context_pack_id": _row_value(plan_row, "context_pack_id"),
            "objective": str(_row_value(plan_row, "objective")),
            "status": str(_row_value(plan_row, "status")),
            "version": _int_value(plan_row, "version"),
            "plan_hash": str(_row_value(plan_row, "plan_hash")),
            "estimated_tokens": _int_value(plan_row, "estimated_tokens"),
            "hard_token_limit": hard_tokens,
            "soft_cost_micros": _int_value(plan_row, "soft_cost_micros"),
            "hard_cost_limit_micros": _int_value(
                plan_row, "hard_cost_limit_micros"
            ),
            "actual_tokens": actual_tokens,
            "actual_cost_micros": (
                actual_tokens
                * _int_value(plan_row, "cost_per_1k_tokens_micros")
                // 1_000
            ),
            "max_concurrency": _int_value(plan_row, "max_concurrency"),
            "budget_extension_count": _int_value(
                plan_row, "budget_extension_count"
            ),
            "budget_extended_tokens": _int_value(
                plan_row, "budget_extended_tokens"
            ),
            "budget_request": (
                _json_value(
                    _row_value(plan_row, "budget_request_json"),
                    default={},
                )
                or None
            ),
            "result_summary": str(_row_value(plan_row, "result_summary", "")),
            "created_at": _int_value(plan_row, "created_at"),
            "proposed_at": _int_value(plan_row, "proposed_at"),
            "approved_at": _row_value(plan_row, "approved_at"),
            "finished_at": _row_value(plan_row, "finished_at"),
            "budget_preview": {
                "max_concurrency": _int_value(plan_row, "max_concurrency"),
                "max_attempts_per_worker": _int_value(
                    plan_row, "max_attempts_per_worker"
                ),
                "user_remaining_tokens": _scope_remaining(
                    conn,
                    scope_type="user_daily",
                    scope_id=str(user_id),
                    period_start=day_start,
                    period_end=day_end,
                    configured_limit=config.user_daily_tokens,
                ),
                "agent_remaining_tokens": _scope_remaining(
                    conn,
                    scope_type="agent_daily",
                    scope_id=agent_id,
                    period_start=day_start,
                    period_end=day_end,
                    configured_limit=config.agent_daily_tokens,
                ),
                "global_remaining_tokens": _scope_remaining(
                    conn,
                    scope_type="global_daily",
                    scope_id="all",
                    period_start=day_start,
                    period_end=day_end,
                    configured_limit=config.global_daily_tokens,
                ),
                "worker_surface_remaining_tokens": _scope_remaining(
                    conn,
                    scope_type="surface_daily",
                    scope_id=f"worker:{agent_id}:{user_id}",
                    period_start=day_start,
                    period_end=day_end,
                    configured_limit=config.surface_daily_tokens,
                ),
            },
            "items": items,
        }

    def _plan_actual_tokens(self, conn: Any, plan_id: str) -> int:
        rows = conn.execute(
            "SELECT i.child_task_id, i.actual_tokens FROM dispatch_plan_items i "
            "WHERE i.plan_id = ?",
            (plan_id,),
        ).fetchall()
        total = 0
        for row in rows:
            task_id = _row_value(row, "child_task_id")
            usage = self._task_actual_tokens(conn, str(task_id)) if task_id else 0
            total += max(usage, _int_value(row, "actual_tokens"))
        return total

    def _task_actual_tokens(self, conn: Any, task_id: str) -> int:
        row = conn.execute(
            "SELECT COALESCE(SUM(total_tokens), 0) FROM usage_log WHERE task_id = ?",
            (task_id,),
        ).fetchone()
        return max(0, int(row[0] if row is not None else 0))

    def _cost_for_tokens(self, plan_row: Any, tokens: int) -> int:
        return (
            max(0, int(tokens))
            * _int_value(plan_row, "cost_per_1k_tokens_micros")
            // 1_000
        )

    def _append_plan_event(
        self,
        plan: Mapping[str, Any],
        event_type: str,
        status: str,
    ) -> None:
        payload = (
            self._compact_progress_payload(plan)
            if event_type == "plan.progress"
            else plan
        )
        self._conversation.append_event(
            conversation_id=str(plan["conversation_id"]),
            trace_id=str(plan["trace_id"]),
            event_type=event_type,
            status=status,
            summary=str(plan.get("result_summary") or event_type.replace(".", " ")),
            plan_id=str(plan["plan_id"]),
            payload=payload,
        )

    def _compact_progress_payload(
        self,
        plan: Mapping[str, Any],
    ) -> dict[str, Any]:
        """Keep polling snapshots small without losing the fields the mobile card needs."""
        compact = dict(plan)
        compact["objective"] = _clip(str(plan.get("objective") or ""), 2_000)
        compact["items"] = [
            {
                **dict(item),
                "objective": "",
                "result_summary": _clip(
                    str(item.get("result_summary") or ""),
                    600,
                ),
            }
            for item in plan.get("items", [])
            if isinstance(item, Mapping)
        ]
        return compact

    def _agent_id_for_conversation(self, conversation_id: str) -> str:
        with self._db.connect() as conn:
            row = conn.execute(
                "SELECT agent_id FROM main_conversations WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchone()
        if row is None:
            raise NotFoundError("main conversation not found")
        return str(row[0])

    def _user_id_for_conversation(self, conversation_id: str) -> int:
        with self._db.connect() as conn:
            row = conn.execute(
                "SELECT user_id FROM main_conversations WHERE conversation_id = ?",
                (conversation_id,),
            ).fetchone()
        if row is None:
            raise NotFoundError("main conversation not found")
        return int(row[0])


__all__ = [
    "DispatchPlanService",
    "MAX_PLAN_ITEMS",
    "PLAN_ITEM_STATUSES",
    "PLAN_STATUSES",
]
