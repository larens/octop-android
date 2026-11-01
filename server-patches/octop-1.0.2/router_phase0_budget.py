"""Phase 0 budget admission for scheduled and proactive background work.

The default mode is ``shadow``: it records whether a call would have been
blocked but does not change user-visible behavior. ``enforce`` is intentionally
limited to configured background surfaces so an initial cap cannot interrupt
dashboard chat.
"""

from __future__ import annotations

import logging
import os
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, time as datetime_time, timezone
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

try:
    from router_phase0_service import (
        BudgetExceededError,
        ConversationService,
        NotFoundError,
        RouterPhase0Error,
    )
    from router_phase0_context import estimate_text_tokens
except ImportError:  # Local patch-pack tests import modules by filename.
    from octop.infra.conversation.service import (
        BudgetExceededError,
        ConversationService,
        NotFoundError,
        RouterPhase0Error,
    )
    from octop.infra.conversation.context import estimate_text_tokens


logger = logging.getLogger(__name__)

DEFAULT_MODE = "shadow"
VALID_MODES = frozenset({"shadow", "enforce"})
DEFAULT_ENFORCED_SURFACES = frozenset({"cron", "proactive"})

RUN_TOKEN_DEFAULTS = {
    "interactive": 80_000,
    "cron": 60_000,
    "proactive": 30_000,
    "worker": 60_000,
}
SURFACE_DAILY_TOKEN_DEFAULTS = {
    "interactive": 300_000,
    "cron": 180_000,
    "proactive": 30_000,
    "worker": 240_000,
}
USER_DAILY_TOKEN_DEFAULT = 400_000
AGENT_DAILY_TOKEN_DEFAULT = 600_000
GLOBAL_DAILY_TOKEN_DEFAULT = 2_000_000

_ENV_KEYS = {
    "mode": "OCTOP_ROUTER_BUDGET_MODE",
    "enforced_surfaces": "OCTOP_ROUTER_BUDGET_ENFORCED_SURFACES",
    "user_daily_tokens": "OCTOP_ROUTER_BUDGET_USER_DAILY_TOKENS",
    "agent_daily_tokens": "OCTOP_ROUTER_BUDGET_AGENT_DAILY_TOKENS",
    "global_daily_tokens": "OCTOP_ROUTER_BUDGET_GLOBAL_DAILY_TOKENS",
    "cost_per_1k_tokens_micros": "OCTOP_ROUTER_BUDGET_COST_PER_1K_TOKENS_MICROS",
}


def estimate_tokens(text: str) -> int:
    """Conservative token estimate used only when the runtime reports no usage."""
    return estimate_text_tokens(text)


def usage_tokens(usage: Any) -> int:
    if not isinstance(usage, dict):
        return 0
    try:
        explicit = int(usage.get("total_tokens", 0))
    except (TypeError, ValueError):
        explicit = 0
    if explicit > 0:
        return explicit
    try:
        return max(0, int(usage.get("input_tokens", 0))) + max(
            0, int(usage.get("output_tokens", 0))
        )
    except (TypeError, ValueError):
        return 0


def _env_int(name: str, default: int) -> int:
    try:
        return max(0, int(os.environ.get(name, default)))
    except (TypeError, ValueError):
        return max(0, int(default))


def _setting(settings_repo: Any, key: str, default: str = "") -> str:
    env_key = _ENV_KEYS.get(key)
    if env_key:
        env_value = os.environ.get(env_key)
        if env_value is not None and env_value.strip():
            return env_value.strip()
    try:
        value = settings_repo.get(f"router.budget.{key}") if settings_repo is not None else None
    except Exception:
        logger.warning("router budget setting read failed key=%s", key, exc_info=True)
        value = None
    return str(value).strip() if value is not None and str(value).strip() else default


def _setting_int(settings_repo: Any, key: str, default: int) -> int:
    env_key = _ENV_KEYS.get(key)
    if env_key:
        raw_env = os.environ.get(env_key)
        if raw_env is not None and raw_env.strip():
            try:
                return max(0, int(raw_env))
            except ValueError:
                pass
    try:
        value = settings_repo.get(f"router.budget.{key}") if settings_repo is not None else None
        return max(0, int(value)) if value is not None and str(value).strip() else max(0, default)
    except (TypeError, ValueError):
        return max(0, default)
    except Exception:
        logger.warning("router budget setting read failed key=%s", key, exc_info=True)
        return max(0, default)


def _zone(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name or "UTC")
    except ZoneInfoNotFoundError:
        return ZoneInfo("UTC")


def _day_period(now: int, timezone_name: str) -> tuple[int, int]:
    tz = _zone(timezone_name)
    local = datetime.fromtimestamp(now, tz=timezone.utc).astimezone(tz)
    start = datetime.combine(local.date(), datetime_time.min, tzinfo=tz)
    end = datetime.combine(local.date(), datetime_time.max, tzinfo=tz)
    return int(start.timestamp()), int(end.timestamp()) + 1


@dataclass(frozen=True)
class BudgetConfig:
    mode: str
    enforced_surfaces: frozenset[str]
    run_tokens: int
    surface_daily_tokens: int
    user_daily_tokens: int
    agent_daily_tokens: int
    global_daily_tokens: int
    cost_per_1k_tokens_micros: int


@dataclass
class BudgetLease:
    gate: "RouterBudgetGate"
    trace_id: str
    surface: str
    agent_id: str
    user_id: int
    conversation_id: str
    mode: str
    enforced: bool
    would_block: bool
    block_reasons: list[str]
    reservations: list[dict[str, Any]]
    estimated_tokens: int
    created_at: int
    actual_tokens: int = 0
    _finished: bool = field(default=False, init=False)
    _overflow: bool = field(default=False, init=False)

    def observe(self, usage: Any) -> int:
        """Update cumulative usage and stop an enforced run at its reservation."""
        observed = usage_tokens(usage)
        self.actual_tokens = max(self.actual_tokens, observed)
        if self.enforced and self.actual_tokens > self.estimated_tokens:
            self._overflow = True
            raise BudgetExceededError(
                f"router {self.surface} run exceeded {self.estimated_tokens} tokens"
            )
        return self.actual_tokens

    def finish(
        self,
        *,
        actual_tokens: int | None = None,
        failed: bool = False,
    ) -> None:
        if self._finished:
            return
        self._finished = True
        if actual_tokens is not None:
            self.actual_tokens = max(self.actual_tokens, max(0, int(actual_tokens)))
        self.gate._finish_lease(self, failed=failed)


class RouterBudgetGate:
    """Admission controller shared by cron and proactive execution."""

    def __init__(
        self,
        db: Any,
        settings_repo: Any,
        *,
        timezone_name: str = "Asia/Shanghai",
        service: ConversationService | None = None,
    ) -> None:
        self._db = db
        self._settings = settings_repo
        self._timezone = timezone_name or "UTC"
        self._service = service or ConversationService(db)

    def config_for(self, surface: str) -> BudgetConfig:
        normalized = surface if surface in RUN_TOKEN_DEFAULTS else "worker"
        mode = _setting(self._settings, "mode", DEFAULT_MODE).lower()
        if mode not in VALID_MODES:
            mode = DEFAULT_MODE
        surfaces_raw = _setting(
            self._settings,
            "enforced_surfaces",
            ",".join(sorted(DEFAULT_ENFORCED_SURFACES)),
        )
        enforced = frozenset(
            token.strip().lower()
            for token in surfaces_raw.split(",")
            if token.strip()
        )
        return BudgetConfig(
            mode=mode,
            enforced_surfaces=enforced,
            run_tokens=_setting_int(
                self._settings,
                f"run_tokens.{normalized}",
                RUN_TOKEN_DEFAULTS[normalized],
            ),
            surface_daily_tokens=_setting_int(
                self._settings,
                f"daily_tokens.{normalized}",
                SURFACE_DAILY_TOKEN_DEFAULTS[normalized],
            ),
            user_daily_tokens=_setting_int(
                self._settings,
                "user_daily_tokens",
                USER_DAILY_TOKEN_DEFAULT,
            ),
            agent_daily_tokens=_setting_int(
                self._settings,
                "agent_daily_tokens",
                AGENT_DAILY_TOKEN_DEFAULT,
            ),
            global_daily_tokens=_setting_int(
                self._settings,
                "global_daily_tokens",
                GLOBAL_DAILY_TOKEN_DEFAULT,
            ),
            cost_per_1k_tokens_micros=_setting_int(
                self._settings,
                "cost_per_1k_tokens_micros",
                0,
            ),
        )

    def start(
        self,
        *,
        surface: str,
        agent_id: str,
        user_id: int,
        source_id: str,
    ) -> BudgetLease:
        normalized_surface = surface if surface in RUN_TOKEN_DEFAULTS else "worker"
        config = self.config_for(normalized_surface)
        enforced = config.mode == "enforce" and normalized_surface in config.enforced_surfaces
        trace_id = f"{normalized_surface}:{source_id}:{uuid.uuid4().hex}"
        now = int(time.time())
        try:
            conversation = self._service.ensure_main_conversation(
                agent_id=agent_id,
                user_id=user_id,
                router_thread_id=None,
            )
        except Exception:
            if enforced:
                raise
            logger.warning(
                "router budget shadow could not provision conversation surface=%s source=%s",
                normalized_surface,
                source_id,
                exc_info=True,
            )
            return BudgetLease(
                gate=self,
                trace_id=trace_id,
                surface=normalized_surface,
                agent_id=agent_id,
                user_id=user_id,
                conversation_id="",
                mode=config.mode,
                enforced=False,
                would_block=False,
                block_reasons=[],
                reservations=[],
                estimated_tokens=config.run_tokens,
                created_at=now,
            )

        estimate = config.run_tokens
        cost_micros = estimate * config.cost_per_1k_tokens_micros // 1_000
        day_start, day_end = _day_period(now, self._timezone)
        specs = [
            {
                "scope_type": "run",
                "scope_id": trace_id,
                "period_start": now - 60,
                "period_end": now + 7_200,
                "tokens": estimate,
                "cost_micros": cost_micros,
                "limit_tokens": config.run_tokens,
                "limit_cost_micros": (
                    config.run_tokens * config.cost_per_1k_tokens_micros // 1_000
                ),
            },
            {
                "scope_type": "surface_daily",
                "scope_id": f"{normalized_surface}:{agent_id}:{user_id}",
                "period_start": day_start,
                "period_end": day_end,
                "tokens": estimate,
                "cost_micros": cost_micros,
                "limit_tokens": config.surface_daily_tokens,
                "limit_cost_micros": (
                    config.surface_daily_tokens * config.cost_per_1k_tokens_micros // 1_000
                ),
            },
            {
                "scope_type": "user_daily",
                "scope_id": str(user_id),
                "period_start": day_start,
                "period_end": day_end,
                "tokens": estimate,
                "cost_micros": cost_micros,
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
                "tokens": estimate,
                "cost_micros": cost_micros,
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
                "tokens": estimate,
                "cost_micros": cost_micros,
                "limit_tokens": config.global_daily_tokens,
                "limit_cost_micros": (
                    config.global_daily_tokens * config.cost_per_1k_tokens_micros // 1_000
                ),
            },
        ]

        if enforced:
            try:
                reservations = self._service.reserve_budgets(specs)
            except BudgetExceededError as exc:
                self._append_budget_event(
                    conversation_id=conversation["conversation_id"],
                    trace_id=trace_id,
                    event_type="budget.blocked",
                    status="blocked",
                    summary=f"{normalized_surface} blocked by hard budget",
                    payload={
                        "surface": normalized_surface,
                        "mode": config.mode,
                        "reason": str(exc),
                        "estimated_tokens": estimate,
                    },
                )
                raise
            lease = BudgetLease(
                gate=self,
                trace_id=trace_id,
                surface=normalized_surface,
                agent_id=agent_id,
                user_id=user_id,
                conversation_id=conversation["conversation_id"],
                mode=config.mode,
                enforced=True,
                would_block=False,
                block_reasons=[],
                reservations=reservations,
                estimated_tokens=estimate,
                created_at=now,
            )
            self._append_budget_event(
                conversation_id=lease.conversation_id,
                trace_id=trace_id,
                event_type="budget.reserved",
                status="reserved",
                summary=f"Reserved {estimate} tokens for {normalized_surface}",
                payload={
                    "surface": normalized_surface,
                    "mode": config.mode,
                    "estimated_tokens": estimate,
                    "scopes": [
                        {
                            "scope_type": item["scope_type"],
                            "scope_id": item["scope_id"],
                        }
                        for item in reservations
                    ],
                },
            )
            return lease

        checks: list[dict[str, Any]] = []
        for spec in specs:
            scope_type = str(spec["scope_type"])
            baseline_tokens = 0
            if scope_type != "run":
                if scope_type == "surface_daily":
                    baseline_tokens = self._service.sum_usage_tokens(
                        period_start=int(spec["period_start"]),
                        period_end=int(spec["period_end"]),
                        agent_id=agent_id,
                        user_id=user_id,
                        source=normalized_surface,
                    )
                elif scope_type == "user_daily":
                    baseline_tokens = self._service.sum_usage_tokens(
                        period_start=int(spec["period_start"]),
                        period_end=int(spec["period_end"]),
                        user_id=user_id,
                    )
                elif scope_type == "agent_daily":
                    baseline_tokens = self._service.sum_usage_tokens(
                        period_start=int(spec["period_start"]),
                        period_end=int(spec["period_end"]),
                        agent_id=agent_id,
                    )
                elif scope_type == "global_daily":
                    baseline_tokens = self._service.sum_usage_tokens(
                        period_start=int(spec["period_start"]),
                        period_end=int(spec["period_end"]),
                    )
            checks.append(
                self._service.check_budget(
                    scope_type=scope_type,
                    scope_id=str(spec["scope_id"]),
                    period_start=int(spec["period_start"]),
                    period_end=int(spec["period_end"]),
                    tokens=int(spec["tokens"]),
                    cost_micros=int(spec["cost_micros"]),
                    limit_tokens=int(spec["limit_tokens"]),
                    limit_cost_micros=int(spec["limit_cost_micros"]),
                    baseline_tokens=baseline_tokens,
                )
            )
        blocked = [item for item in checks if item["would_block"]]
        lease = BudgetLease(
            gate=self,
            trace_id=trace_id,
            surface=normalized_surface,
            agent_id=agent_id,
            user_id=user_id,
            conversation_id=conversation["conversation_id"],
            mode=config.mode,
            enforced=False,
            would_block=bool(blocked),
            block_reasons=[
                f"{item['scope_type']}:{item['scope_id']}" for item in blocked
            ],
            reservations=[],
            estimated_tokens=estimate,
            created_at=now,
        )
        self._append_budget_event(
            conversation_id=lease.conversation_id,
            trace_id=trace_id,
            event_type="budget.shadow_checked",
            status="would_block" if blocked else "allowed",
            summary=(
                f"{normalized_surface} would be blocked"
                if blocked
                else f"{normalized_surface} would be allowed"
            ),
            payload={
                "surface": normalized_surface,
                "mode": config.mode,
                "estimated_tokens": estimate,
                "would_block": bool(blocked),
                "checks": checks,
            },
        )
        return lease

    def _finish_lease(self, lease: BudgetLease, *, failed: bool) -> None:
        if not lease.conversation_id:
            return
        actual = max(0, lease.actual_tokens)
        enforced_tokens = min(actual, lease.estimated_tokens)
        status = "failed" if failed else "completed"
        if not lease.enforced:
            self._append_budget_event(
                conversation_id=lease.conversation_id,
                trace_id=lease.trace_id,
                event_type="budget.shadow_settled",
                status=status,
                summary=(
                    f"Shadow {lease.surface} used {actual} tokens"
                    + ("; would have blocked" if lease.would_block else "")
                ),
                payload={
                    "surface": lease.surface,
                    "mode": lease.mode,
                    "actual_tokens": actual,
                    "estimated_tokens": lease.estimated_tokens,
                    "enforced_tokens": enforced_tokens,
                    "overrun_tokens": max(0, actual - lease.estimated_tokens),
                    "would_block": lease.would_block,
                    "block_reasons": lease.block_reasons,
                },
                usage={"total_tokens": actual},
            )
            return
        reservations = lease.reservations
        if not reservations:
            return
        cost_rate = self.config_for(lease.surface).cost_per_1k_tokens_micros
        actual_cost = enforced_tokens * cost_rate // 1_000
        settlements = [
            {
                "scope_type": item["scope_type"],
                "scope_id": item["scope_id"],
                "period_start": item["period_start"],
                "period_end": item["period_end"],
                "reserved_tokens": item["reserved_tokens"],
                "reserved_cost_micros": item["reserved_cost_micros"],
                "spent_tokens": enforced_tokens,
                "spent_cost_micros": actual_cost,
            }
            for item in reservations
        ]
        try:
            self._service.settle_budgets(settlements)
        except (BudgetExceededError, RouterPhase0Error):
            logger.error(
                "router budget settlement failed trace=%s surface=%s",
                lease.trace_id,
                lease.surface,
                exc_info=True,
            )
            self._append_budget_event(
                conversation_id=lease.conversation_id,
                trace_id=lease.trace_id,
                event_type="budget.settlement_failed",
                status="error",
                summary=f"{lease.surface} budget settlement failed",
                payload={
                    "surface": lease.surface,
                    "actual_tokens": enforced_tokens,
                    "overflow": lease._overflow,
                },
            )
            return
        self._append_budget_event(
            conversation_id=lease.conversation_id,
            trace_id=lease.trace_id,
            event_type="budget.settled",
            status=status,
            summary=f"{lease.surface} used {enforced_tokens} tokens",
            payload={
                "surface": lease.surface,
                "mode": lease.mode,
                "actual_tokens": enforced_tokens,
                "overflow": lease._overflow,
            },
            usage={"total_tokens": enforced_tokens},
        )

    def _append_budget_event(
        self,
        *,
        conversation_id: str,
        trace_id: str,
        event_type: str,
        status: str,
        summary: str,
        payload: dict[str, Any],
        usage: dict[str, Any] | None = None,
    ) -> None:
        try:
            self._service.append_event(
                conversation_id=conversation_id,
                trace_id=trace_id,
                event_type=event_type,
                status=status,
                summary=summary,
                payload=payload,
                usage=usage,
            )
        except (NotFoundError, RouterPhase0Error):
            logger.warning(
                "router budget event write failed trace=%s type=%s",
                trace_id,
                event_type,
                exc_info=True,
            )


__all__ = [
    "BudgetConfig",
    "BudgetLease",
    "RouterBudgetGate",
    "estimate_tokens",
    "usage_tokens",
]
