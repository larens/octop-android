"""Publish an evening Today card with today's model cost and token usage.

Prices are the vendors' official CNY list prices per one million tokens,
checked on 2026-11-14. DeepSeek prices follow Beijing-time peak/off-peak.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from octop.infra.db.pool import DatabasePool
from octop.infra.db.repos.agents import AgentRepo
from octop.infra.today.cards import (
    TodayCardDraft,
    TodayCardPublisher,
    TodayEvidenceInput,
)

logger = logging.getLogger(__name__)

USAGE_CARD_START_HOUR = 21
USAGE_DEDUPE_PREFIX = "auto:usage:daily:"
_DEFAULT_TIMEZONE = "Asia/Shanghai"
_MICROS_PER_UNIT = 1_000_000
_SOURCE_LABELS = {
    "chat": "聊天",
    "cron": "定时任务",
    "proactive": "主动关怀",
    "router": "路由",
    "task": "任务",
}


@dataclass(frozen=True)
class ModelPrice:
    """CNY micros per one million tokens."""

    cache_hit_micros: int
    input_micros: int
    output_micros: int


_MIMO_PRICES = {
    "mimo-v2.6-flash": ModelPrice(20_000, 1_000_000, 2_000_000),
    "mimo-v2.6-pro": ModelPrice(25_000, 3_000_000, 6_000_000),
    "mimo-v2.6-pro-ultraspeed": ModelPrice(250_000, 30_000_000, 60_000_000),
}
_DEEPSEEK_FLASH_OFFPEAK = ModelPrice(20_000, 1_000_000, 4_000_000)
_DEEPSEEK_FLASH_PEAK = ModelPrice(40_000, 2_000_000, 8_000_000)
_DEEPSEEK_PRO_OFFPEAK = ModelPrice(150_000, 4_500_000, 13_500_000)
_DEEPSEEK_PRO_PEAK = ModelPrice(300_000, 9_000_000, 27_000_000)
_DEEPSEEK_PRICES = {
    "deepseek-flash": (_DEEPSEEK_FLASH_PEAK, _DEEPSEEK_FLASH_OFFPEAK),
    "deepseek-v4-flash": (_DEEPSEEK_FLASH_PEAK, _DEEPSEEK_FLASH_OFFPEAK),
    "deepseek-v4.1-flash": (_DEEPSEEK_FLASH_PEAK, _DEEPSEEK_FLASH_OFFPEAK),
    "deepseek-v4-pro": (_DEEPSEEK_PRO_PEAK, _DEEPSEEK_PRO_OFFPEAK),
    "deepseek-v4.1-pro": (_DEEPSEEK_PRO_PEAK, _DEEPSEEK_PRO_OFFPEAK),
}


@dataclass(frozen=True)
class ModelCost:
    model: str
    total_tokens: int
    input_tokens: int
    uncached_input_tokens: int
    cache_read_tokens: int
    output_tokens: int
    model_calls: int
    cost_micros: int
    priced: bool


@dataclass(frozen=True)
class SourceCost:
    source: str
    total_tokens: int
    cost_micros: int
    priced: bool


@dataclass(frozen=True)
class CostBreakdown:
    models: tuple[ModelCost, ...]
    sources: tuple[SourceCost, ...]
    cost_micros: int
    cache_savings_micros: int
    input_cost_micros: int
    cache_cost_micros: int
    output_cost_micros: int
    unpriced_models: tuple[str, ...]


@dataclass(frozen=True)
class UsageSnapshot:
    total_tokens: int
    input_tokens: int
    output_tokens: int
    cache_read_tokens: int
    reasoning_tokens: int
    model_calls: int
    turns: int
    threads: int
    by_source: tuple[SourceCost, ...]
    by_model: tuple[ModelCost, ...]
    cost_micros: int
    cache_savings_micros: int
    input_cost_micros: int
    cache_cost_micros: int
    output_cost_micros: int
    unpriced_models: tuple[str, ...]
    previous_cost_micros: int
    previous_unpriced_models: tuple[str, ...]
    previous_total_tokens: int
    top_thread_id: str
    top_thread_title: str

    @property
    def cache_hit_percent(self) -> int:
        if self.input_tokens <= 0:
            return 0
        percent = round(self.cache_read_tokens / self.input_tokens * 100)
        return max(0, min(100, percent))


def _timezone(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name)
    except ZoneInfoNotFoundError:
        return ZoneInfo(_DEFAULT_TIMEZONE)


def _as_int(value: Any, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _format_tokens(value: int) -> str:
    if value >= 1_000_000:
        scaled = f"{value / 1_000_000:.1f}"
        return f"{scaled.removesuffix('.0')}M"
    if value >= 1_000:
        scaled = f"{value / 1_000:.1f}"
        return f"{scaled.removesuffix('.0')}k"
    return str(value)


def _format_money(micros: int) -> str:
    if micros <= 0:
        return "¥0"
    yuan = micros / _MICROS_PER_UNIT
    if yuan < 0.01:
        return "<¥0.01"
    return f"¥{yuan:.2f}"


def _format_share(part: int, total: int) -> str:
    if total <= 0:
        return "0.0%"
    return f"{part / total * 100:.1f}%"


def _normalize_model(value: str) -> str:
    name = (value or "").strip().lower()
    if "/" in name:
        name = name.rsplit("/", 1)[-1].strip()
    return name


def _model_price(model: str, *, peak: bool) -> ModelPrice | None:
    name = _normalize_model(model)
    flat = _MIMO_PRICES.get(name)
    if flat is not None:
        return flat
    pair = _DEEPSEEK_PRICES.get(name)
    if pair is None:
        return None
    return pair[0] if peak else pair[1]


def _source_label(source: str) -> str:
    return _SOURCE_LABELS.get(source, source or "其他")


def _source_line(snapshot: UsageSnapshot) -> str:
    if not snapshot.by_source:
        return "暂无来源明细"
    visible = snapshot.by_source[:2]
    parts = [
        f"{_source_label(item.source)}：{_format_money(item.cost_micros)}"
        for item in visible
    ]
    remaining = snapshot.by_source[2:]
    if remaining:
        parts.append(
            f"其他：{_format_money(sum(item.cost_micros for item in remaining))}"
        )
    return " · ".join(parts)


def _model_line(snapshot: UsageSnapshot) -> str:
    if not snapshot.by_model:
        return "暂无模型明细"
    parts = []
    for item in snapshot.by_model[:2]:
        if item.priced:
            cost = _format_money(item.cost_micros)
        elif item.cost_micros > 0:
            cost = f"{_format_money(item.cost_micros)}+"
        else:
            cost = "未计价"
        parts.append(f"{item.model}：{cost} · {_format_tokens(item.total_tokens)}")
    if len(snapshot.by_model) > 2:
        parts.append(f"其他 {len(snapshot.by_model) - 2} 个模型")
    return " · ".join(parts)


def _cost_share_line(snapshot: UsageSnapshot) -> str:
    total = snapshot.cost_micros
    if total <= 0:
        return "暂无可计价花费"
    return " · ".join(
        (
            f"未命中 {_format_share(snapshot.input_cost_micros, total)}",
            f"缓存 {_format_share(snapshot.cache_cost_micros, total)}",
            f"输出 {_format_share(snapshot.output_cost_micros, total)}",
        )
    )


def _comparison_line(snapshot: UsageSnapshot) -> str:
    previous = snapshot.previous_cost_micros
    current = snapshot.cost_micros
    if previous <= 0:
        if snapshot.previous_unpriced_models:
            return "昨天有未计价模型，暂无法比较花费"
        return "昨天没有花费记录"
    delta = current - previous
    if delta == 0:
        return f"与昨天持平（昨天 {_format_money(previous)}）"
    percent = round(abs(delta) / previous * 100)
    if percent == 0:
        return f"与昨天基本持平（昨天 {_format_money(previous)}）"
    direction = "多" if delta > 0 else "少"
    return f"比昨天{direction} {percent}%（昨天 {_format_money(previous)}）"


def format_usage_summary(snapshot: UsageSnapshot) -> str:
    overview = "\n".join(
        (
            f"总花费：{_format_money(snapshot.cost_micros)} · 按官方价估算",
            f"缓存节省：{_format_money(snapshot.cache_savings_micros)}",
            f"总 Token：{_format_tokens(snapshot.total_tokens)}"
            f" · 缓存命中：{snapshot.cache_hit_percent}%",
            f"模型调用：{snapshot.model_calls}"
            f" · 会话：{snapshot.threads} · 轮次：{snapshot.turns}"
            f" · 推理：{_format_tokens(snapshot.reasoning_tokens)}",
        )
    )
    return "\n\n".join(
        (
            f"【今日花费】\n{overview}",
            f"【花费构成】\n{_cost_share_line(snapshot)}",
            f"【来源花费】\n{_source_line(snapshot)}",
            f"【模型花费】\n{_model_line(snapshot)}",
            f"【对比昨日】\n{_comparison_line(snapshot)}",
        )
    )


@dataclass
class _Aggregate:
    total_tokens: int = 0
    input_tokens: int = 0
    uncached_input_tokens: int = 0
    cache_read_tokens: int = 0
    output_tokens: int = 0
    model_calls: int = 0
    cost_micros: int = 0
    priced: bool = True


_COST_SQL = (
    "SELECT source, model, "
    "CASE WHEN strftime('%w', datetime(ts, 'unixepoch', '+8 hours')) BETWEEN '1' AND '5' "
    "AND (CAST(strftime('%H', datetime(ts, 'unixepoch', '+8 hours')) AS INTEGER) "
    "BETWEEN 9 AND 11 "
    "OR CAST(strftime('%H', datetime(ts, 'unixepoch', '+8 hours')) AS INTEGER) "
    "BETWEEN 14 AND 17) THEN 1 ELSE 0 END AS peak, "
    "COALESCE(SUM(total_tokens), 0) AS total_tokens, "
    "COALESCE(SUM(input_tokens), 0) AS input_tokens, "
    "COALESCE(SUM(uncached_input_tokens), 0) AS uncached_input_tokens, "
    "COALESCE(SUM(cache_read_tokens), 0) AS cache_read_tokens, "
    "COALESCE(SUM(output_tokens), 0) AS output_tokens, "
    "COALESCE(SUM(model_calls), 0) AS model_calls "
    "FROM usage_log WHERE agent_id = ? AND user_id = ? AND ts >= ? AND ts < ? "
    "GROUP BY source, model, peak"
)


def _cost_breakdown(
    conn: Any,
    *,
    agent_id: str,
    user_id: int,
    start: int,
    end: int,
) -> CostBreakdown:
    rows = conn.execute(
        _COST_SQL,
        (agent_id, user_id, start, end),
    ).fetchall()
    models: dict[str, _Aggregate] = {}
    sources: dict[str, _Aggregate] = {}
    input_cost_micros = 0
    cache_cost_micros = 0
    output_cost_micros = 0
    cache_savings_micros = 0
    for row in rows:
        model = _normalize_model(str(row["model"] or "unknown"))
        source = str(row["source"] or "other")
        total_tokens = _as_int(row["total_tokens"])
        input_tokens = _as_int(row["input_tokens"])
        uncached_tokens = _as_int(row["uncached_input_tokens"])
        cache_read_tokens = _as_int(row["cache_read_tokens"])
        output_tokens = _as_int(row["output_tokens"])
        model_calls = _as_int(row["model_calls"])
        model_agg = models.setdefault(model, _Aggregate())
        source_agg = sources.setdefault(source, _Aggregate())
        for agg in (model_agg, source_agg):
            agg.total_tokens += total_tokens
            agg.input_tokens += input_tokens
            agg.uncached_input_tokens += uncached_tokens
            agg.cache_read_tokens += cache_read_tokens
            agg.output_tokens += output_tokens
            agg.model_calls += model_calls

        price = _model_price(model, peak=bool(_as_int(row["peak"])))
        if price is None:
            model_agg.priced = False
            source_agg.priced = False
            continue
        row_input_cost = uncached_tokens * price.input_micros // _MICROS_PER_UNIT
        row_cache_cost = (
            cache_read_tokens * price.cache_hit_micros // _MICROS_PER_UNIT
        )
        row_output_cost = output_tokens * price.output_micros // _MICROS_PER_UNIT
        row_cost = row_input_cost + row_cache_cost + row_output_cost
        model_agg.cost_micros += row_cost
        source_agg.cost_micros += row_cost
        input_cost_micros += row_input_cost
        cache_cost_micros += row_cache_cost
        output_cost_micros += row_output_cost
        cache_savings_micros += (
            cache_read_tokens * max(0, price.input_micros - price.cache_hit_micros)
            // _MICROS_PER_UNIT
        )

    model_costs = tuple(
        ModelCost(
            model=model,
            total_tokens=agg.total_tokens,
            input_tokens=agg.input_tokens,
            uncached_input_tokens=agg.uncached_input_tokens,
            cache_read_tokens=agg.cache_read_tokens,
            output_tokens=agg.output_tokens,
            model_calls=agg.model_calls,
            cost_micros=agg.cost_micros,
            priced=agg.priced,
        )
        for model, agg in sorted(
            models.items(),
            key=lambda item: (item[1].cost_micros, item[1].total_tokens),
            reverse=True,
        )
    )
    source_costs = tuple(
        SourceCost(
            source=source,
            total_tokens=agg.total_tokens,
            cost_micros=agg.cost_micros,
            priced=agg.priced,
        )
        for source, agg in sorted(
            sources.items(),
            key=lambda item: (item[1].cost_micros, item[1].total_tokens),
            reverse=True,
        )
    )
    cost_micros = input_cost_micros + cache_cost_micros + output_cost_micros
    return CostBreakdown(
        models=model_costs,
        sources=source_costs,
        cost_micros=cost_micros,
        cache_savings_micros=cache_savings_micros,
        input_cost_micros=input_cost_micros,
        cache_cost_micros=cache_cost_micros,
        output_cost_micros=output_cost_micros,
        unpriced_models=tuple(
            model for model, agg in models.items() if not agg.priced
        ),
    )


def build_usage_snapshot(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    now: int,
    timezone_name: str = _DEFAULT_TIMEZONE,
) -> UsageSnapshot | None:
    tz = _timezone(timezone_name)
    local = datetime.fromtimestamp(now, tz)
    day_start = local.replace(hour=0, minute=0, second=0, microsecond=0)
    today_start = int(day_start.timestamp())
    tomorrow_start = int((day_start + timedelta(days=1)).timestamp())
    yesterday_start = int((day_start - timedelta(days=1)).timestamp())
    scope = (agent_id, user_id, today_start, tomorrow_start)

    with db.connect() as conn:
        totals = conn.execute(
            "SELECT COALESCE(SUM(input_tokens), 0) AS input_tokens, "
            "COALESCE(SUM(output_tokens), 0) AS output_tokens, "
            "COALESCE(SUM(cache_read_tokens), 0) AS cache_read_tokens, "
            "COALESCE(SUM(reasoning_tokens), 0) AS reasoning_tokens, "
            "COALESCE(SUM(total_tokens), 0) AS total_tokens, "
            "COALESCE(SUM(model_calls), 0) AS model_calls, "
            "COUNT(*) AS turns, COUNT(DISTINCT thread_id) AS threads "
            "FROM usage_log WHERE agent_id = ? AND user_id = ? AND ts >= ? AND ts < ?",
            scope,
        ).fetchone()
        total_tokens = _as_int(totals["total_tokens"] if totals is not None else 0)
        if total_tokens <= 0:
            return None

        today_cost = _cost_breakdown(
            conn,
            agent_id=agent_id,
            user_id=user_id,
            start=today_start,
            end=tomorrow_start,
        )
        previous_cost = _cost_breakdown(
            conn,
            agent_id=agent_id,
            user_id=user_id,
            start=yesterday_start,
            end=today_start,
        )

        previous_row = conn.execute(
            "SELECT COALESCE(SUM(total_tokens), 0) AS tokens FROM usage_log "
            "WHERE agent_id = ? AND user_id = ? AND ts >= ? AND ts < ?",
            (agent_id, user_id, yesterday_start, today_start),
        ).fetchone()
        previous_tokens = _as_int(previous_row["tokens"] if previous_row is not None else 0)

        thread_row = conn.execute(
            "SELECT u.thread_id AS thread_id, t.title AS title "
            "FROM usage_log u JOIN threads t "
            "ON t.thread_id = u.thread_id AND t.agent_id = u.agent_id "
            "AND t.user_id = u.user_id "
            "WHERE u.agent_id = ? AND u.user_id = ? AND u.ts >= ? AND u.ts < ? "
            "AND u.thread_id != '' "
            "GROUP BY u.thread_id, t.title ORDER BY SUM(u.total_tokens) DESC LIMIT 1",
            scope,
        ).fetchone()
        if thread_row is None:
            return None

    return UsageSnapshot(
        total_tokens=total_tokens,
        input_tokens=_as_int(totals["input_tokens"]),
        output_tokens=_as_int(totals["output_tokens"]),
        cache_read_tokens=_as_int(totals["cache_read_tokens"]),
        reasoning_tokens=_as_int(totals["reasoning_tokens"]),
        model_calls=_as_int(totals["model_calls"]),
        turns=_as_int(totals["turns"]),
        threads=_as_int(totals["threads"]),
        by_source=today_cost.sources,
        by_model=today_cost.models,
        cost_micros=today_cost.cost_micros,
        cache_savings_micros=today_cost.cache_savings_micros,
        input_cost_micros=today_cost.input_cost_micros,
        cache_cost_micros=today_cost.cache_cost_micros,
        output_cost_micros=today_cost.output_cost_micros,
        unpriced_models=today_cost.unpriced_models,
        previous_cost_micros=previous_cost.cost_micros,
        previous_unpriced_models=previous_cost.unpriced_models,
        previous_total_tokens=previous_tokens,
        top_thread_id=str(thread_row["thread_id"]),
        top_thread_title=str(thread_row["title"] or "").strip(),
    )


def build_usage_draft(
    snapshot: UsageSnapshot,
    *,
    date_key: str,
    occurred_at: int,
) -> TodayCardDraft:
    return TodayCardDraft(
        dedupe_key=f"{USAGE_DEDUPE_PREFIX}{date_key}",
        kind="usage",
        title=f"今日 AI 花费 · {_format_money(snapshot.cost_micros)}",
        summary=format_usage_summary(snapshot),
        why_now="每天晚上按实际模型计价汇总当天 AI 花费，方便判断成本是否异常。",
        action_prompt=(
            "如今天花费明显高于平时，先检查定时任务是否重复触发；"
            "缓存命中偏低时优先复用上下文。"
        ),
        risk_level="A0",
        confidence=0.99,
        occurred_at=occurred_at,
        evidence=[
            TodayEvidenceInput(
                source_type="conversation",
                reference=snapshot.top_thread_id,
                title="今日用量最高的会话",
                excerpt=(
                    f"{snapshot.top_thread_title or '对话'} · "
                    f"今日 {snapshot.threads} 个会话、{snapshot.model_calls} 次模型调用、"
                    f"约 {_format_money(snapshot.cost_micros)}"
                ),
                occurred_at=occurred_at,
            )
        ],
    )


def _dismiss_previous_usage_cards(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    current_key: str,
    now: int,
) -> None:
    with db.transaction() as conn:
        conn.execute(
            "UPDATE today_cards SET lifecycle = 'dismissed', snoozed_until = NULL, "
            "updated_at = ? WHERE agent_id = ? AND user_id = ? "
            "AND dedupe_key LIKE 'agent:auto:usage:%' AND dedupe_key != ? "
            "AND lifecycle != 'dismissed'",
            (now, agent_id, user_id, current_key),
        )


def publish_usage_today_card(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    now: int | None = None,
    timezone_name: str = _DEFAULT_TIMEZONE,
) -> str | None:
    now_seconds = int(now if now is not None else time.time())
    local = datetime.fromtimestamp(now_seconds, _timezone(timezone_name))
    if local.hour < USAGE_CARD_START_HOUR:
        return None
    try:
        snapshot = build_usage_snapshot(
            db,
            agent_id=agent_id,
            user_id=user_id,
            now=now_seconds,
            timezone_name=timezone_name,
        )
        if snapshot is None:
            return None
        draft = build_usage_draft(
            snapshot,
            date_key=local.date().isoformat(),
            occurred_at=now_seconds,
        )
        publisher = TodayCardPublisher(db, agent_repo=AgentRepo(db))
        published = publisher.publish(draft, agent_id=agent_id, user_id=user_id)
        _dismiss_previous_usage_cards(
            db,
            agent_id=agent_id,
            user_id=user_id,
            current_key=f"agent:{draft.dedupe_key}",
            now=now_seconds,
        )
        return str(published["card_id"])
    except Exception:
        logger.warning("failed to publish usage Today card", exc_info=True)
        return None


__all__ = [
    "UsageSnapshot",
    "build_usage_draft",
    "build_usage_snapshot",
    "format_usage_summary",
    "publish_usage_today_card",
]
