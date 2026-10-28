"""Persist stable health trends as de-identified long-term memory atoms.

Raw daily health events remain in Octop's mobile event store. Long-term memory
only receives 7-day summaries after enough samples exist, and never receives
account identifiers, cookies, pass tokens, service tokens, or raw sensor data.
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping

LOG = logging.getLogger("health_trend_memory")
SOURCE_TYPE = "xiaomi_health"
ENTITY_NAME = "健康趋势观察"
ENTITY_TYPE = "Fact"
TREND_PREFIX = "[health-trend:v1:"
RECENT_DAYS = 7
HISTORY_DAYS = 14
LOOKBACK_DAYS = 45
MIN_HISTORY_SAMPLES = 7
MIN_RECENT_SAMPLES = 5
CST = timezone(timedelta(hours=8))

_WORKOUT_LABELS = {
    "badminton": "羽毛球",
    "biking": "骑行",
    "biking_stationary": "室内骑行",
    "elliptical": "椭圆机",
    "hiking": "徒步",
    "other_workout": "其他运动",
    "rowing": "划船",
    "running": "跑步",
    "running_treadmill": "跑步机",
    "strength_training": "力量训练",
    "swimming_pool": "泳池游泳",
    "walking": "步行",
    "yoga": "瑜伽",
}


@dataclass(frozen=True)
class TrendEvent:
    event_id: str
    event_type: str
    occurred_at: int
    observed_at: int
    payload: Mapping[str, Any]


@dataclass(frozen=True)
class TrendObservation:
    key: str
    assertion: str
    confidence: str


@dataclass(frozen=True)
class SleepNight:
    day: date
    duration_minutes: int
    deep_minutes: int
    awake_minutes: int
    observed_at: int


def _as_int(value: Any, default: int = 0) -> int:
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return default


def _as_float(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _epoch_seconds(value: Any, default: int = 0) -> int:
    number = _as_int(value, default)
    if number <= 0:
        return default
    return number // 1000 if number >= 100_000_000_000 else number


def _row_value(row: Any, name: str, default: Any = None) -> Any:
    try:
        return row[name]
    except (KeyError, IndexError, TypeError):
        return default


def _event_date(event: TrendEvent) -> date:
    raw_date = event.payload.get("date")
    if isinstance(raw_date, str):
        try:
            return date.fromisoformat(raw_date[:10])
        except ValueError:
            pass
    offset = _as_int(event.payload.get("timezone_offset_minutes"), 480)
    offset = max(-840, min(840, offset))
    tz = timezone(timedelta(minutes=offset))
    return datetime.fromtimestamp(event.occurred_at, tz=tz).date()


def parse_trend_events(rows: Iterable[Any]) -> list[TrendEvent]:
    events: list[TrendEvent] = []
    for row in rows:
        try:
            raw_payload = _row_value(row, "payload")
            if isinstance(raw_payload, str):
                payload = json.loads(raw_payload)
            elif isinstance(raw_payload, Mapping):
                payload = dict(raw_payload)
            else:
                raw_payload = _row_value(row, "payload_json", "{}")
                payload = json.loads(raw_payload) if isinstance(raw_payload, str) else {}
            if not isinstance(payload, Mapping):
                continue
            occurred_at = _epoch_seconds(_row_value(row, "occurred_at"))
            if occurred_at <= 0:
                continue
            observed_at = _epoch_seconds(
                _row_value(row, "observed_at"),
                occurred_at,
            )
            event_type = str(_row_value(row, "event_type", ""))
            if not event_type.startswith("health."):
                continue
            events.append(
                TrendEvent(
                    event_id=str(_row_value(row, "event_id", "")),
                    event_type=event_type,
                    occurred_at=occurred_at,
                    observed_at=observed_at or occurred_at,
                    payload=payload,
                )
            )
        except (TypeError, ValueError, json.JSONDecodeError):
            LOG.debug("skip malformed health trend event", exc_info=True)
    return events


def _latest_daily_values(
    events: Iterable[TrendEvent],
    event_type: str,
    value: Callable[[Mapping[str, Any]], float],
) -> dict[date, float]:
    selected: dict[date, tuple[int, int, float]] = {}
    for event in events:
        if event.event_type != event_type:
            continue
        parsed = value(event.payload)
        if parsed <= 0:
            continue
        day = _event_date(event)
        rank = (event.observed_at, event.occurred_at)
        current = selected.get(day)
        if current is None or rank > (current[0], current[1]):
            selected[day] = (event.observed_at, event.occurred_at, parsed)
    return {day: item[2] for day, item in selected.items()}


def _sleep_nights(events: Iterable[TrendEvent]) -> dict[date, SleepNight]:
    selected: dict[date, SleepNight] = {}
    for event in events:
        if event.event_type != "health.sleep_session":
            continue
        ends_at = _epoch_seconds(event.payload.get("ends_at"))
        if ends_at <= 0:
            continue
        offset = _as_int(event.payload.get("timezone_offset_minutes"), 480)
        offset = max(-840, min(840, offset))
        end_local = datetime.fromtimestamp(
            ends_at,
            tz=timezone(timedelta(minutes=offset)),
        )
        if end_local.hour >= 12:
            continue
        duration = _as_int(event.payload.get("duration_minutes"))
        if duration <= 0:
            duration = max(0, (ends_at - event.occurred_at) // 60)
        if duration < 45:
            continue
        stages = event.payload.get("stages_minutes")
        deep = _as_int(stages.get("deep")) if isinstance(stages, Mapping) else 0
        awake = _as_int(stages.get("awake")) if isinstance(stages, Mapping) else 0
        candidate = SleepNight(
            day=end_local.date(),
            duration_minutes=duration,
            deep_minutes=deep,
            awake_minutes=awake,
            observed_at=event.observed_at,
        )
        current = selected.get(candidate.day)
        if current is None or candidate.observed_at >= current.observed_at:
            selected[candidate.day] = candidate
    return selected


def _in_window(day: date, start: date, end: date) -> bool:
    return start <= day <= end


def _values_between(
    values: Mapping[date, float],
    start: date,
    end: date,
) -> list[float]:
    return [
        value
        for day, value in sorted(values.items())
        if _in_window(day, start, end)
    ]


def _average(values: Iterable[float]) -> float:
    numbers = list(values)
    return sum(numbers) / len(numbers) if numbers else 0.0


def _minutes_text(minutes: int) -> str:
    hours, remainder = divmod(max(0, minutes), 60)
    return f"{hours}小时{remainder}分" if hours else f"{remainder}分钟"


def _change_text(current: float, previous: float) -> str:
    if previous <= 0:
        return ""
    percent = round((current - previous) / previous * 100)
    if abs(percent) < 3:
        return f"，与前 7 天基本持平"
    direction = "上升" if percent > 0 else "下降"
    return f"，较前 7 天{direction} {abs(percent)}%"


def _sample_range(day: date, days: int) -> tuple[date, date]:
    return day - timedelta(days=days - 1), day


def _activity_trend(events: list[TrendEvent], through: date) -> TrendObservation | None:
    steps = _latest_daily_values(
        events,
        "health.steps_30d",
        lambda payload: _as_float(payload.get("steps")),
    )
    current_start, current_end = _sample_range(through, RECENT_DAYS)
    previous_start, previous_end = _sample_range(
        current_start - timedelta(days=1),
        RECENT_DAYS,
    )
    history_start = through - timedelta(days=HISTORY_DAYS - 1)
    history_steps = _values_between(steps, history_start, through)
    recent_steps = _values_between(steps, current_start, current_end)
    previous_steps = _values_between(steps, previous_start, previous_end)
    if (
        len(history_steps) < MIN_HISTORY_SAMPLES
        or len(recent_steps) < MIN_RECENT_SAMPLES
    ):
        return None

    parts = [
        f"近 7 天日均 {round(_average(recent_steps)):,} 步"
        + _change_text(_average(recent_steps), _average(previous_steps))
    ]
    sample_notes = [f"步数 {len(recent_steps)}/7 天"]

    standing = _latest_daily_values(
        events,
        "health.standing_daily",
        lambda payload: _as_float(payload.get("stand_hours")),
    )
    recent_standing = _values_between(standing, current_start, current_end)
    if len(recent_standing) >= MIN_RECENT_SAMPLES:
        parts.append(f"日均 {_average(recent_standing):.1f} 个有效站立小时")
        sample_notes.append(f"站立 {len(recent_standing)}/7 天")

    sedentary = _latest_daily_values(
        events,
        "health.standing_daily",
        lambda payload: _as_float(payload.get("longest_sedentary_minutes")),
    )
    recent_sedentary = _values_between(sedentary, current_start, current_end)
    if len(recent_sedentary) >= MIN_RECENT_SAMPLES:
        parts.append(
            f"最长久坐（按站立间隔估算）日均 {round(_average(recent_sedentary))} 分钟"
            f"、单日最长 {round(max(recent_sedentary))} 分钟"
        )

    workouts = [
        event
        for event in events
        if event.event_type == "health.exercise_session"
        and _in_window(_event_date(event), current_start, current_end)
    ]
    if workouts:
        total_minutes = sum(
            _as_int(event.payload.get("duration_minutes")) for event in workouts
        )
        names = [
            str(event.payload.get("exercise_type_name") or "other_workout")
            for event in workouts
        ]
        main_name = max(set(names), key=names.count)
        parts.append(
            f"记录运动 {len(workouts)} 次、共 {total_minutes} 分钟"
            f"，主要为{_WORKOUT_LABELS.get(main_name, main_name)}"
        )
    else:
        parts.append("近 7 天未记录运动")

    assertion = (
        f"{TREND_PREFIX}activity] 截至 {through.isoformat()}，"
        + "；".join(parts)
        + f"。有效样本：{'，'.join(sample_notes)}。"
    )
    confidence = "high" if len(recent_steps) == RECENT_DAYS else "medium"
    return TrendObservation("activity", assertion, confidence)


def _sleep_trend(events: list[TrendEvent], through: date) -> TrendObservation | None:
    nights = _sleep_nights(events)
    current_start, current_end = _sample_range(through, RECENT_DAYS)
    previous_start, previous_end = _sample_range(
        current_start - timedelta(days=1),
        RECENT_DAYS,
    )
    history_start = through - timedelta(days=HISTORY_DAYS - 1)
    history = [
        night
        for day, night in nights.items()
        if _in_window(day, history_start, through)
    ]
    recent = [
        night
        for day, night in sorted(nights.items())
        if _in_window(day, current_start, current_end)
    ]
    if len(history) < MIN_HISTORY_SAMPLES or len(recent) < MIN_RECENT_SAMPLES:
        return None
    previous = [
        night
        for day, night in sorted(nights.items())
        if _in_window(day, previous_start, previous_end)
    ]
    average_duration = _average(night.duration_minutes for night in recent)
    parts = [
        f"近 7 天有 {len(recent)} 晚有效睡眠，平均 {_minutes_text(round(average_duration))}"
    ]
    if previous:
        delta = round(average_duration - _average(night.duration_minutes for night in previous))
        if abs(delta) >= 10:
            direction = "延长" if delta > 0 else "缩短"
            parts.append(f"较前 7 晚平均{direction} {_minutes_text(abs(delta))}")
        else:
            parts.append("较前 7 晚平均时长基本稳定")

    deep_samples = [night.deep_minutes for night in recent if night.deep_minutes > 0]
    if len(deep_samples) >= MIN_RECENT_SAMPLES:
        average_deep = _average(deep_samples)
        ratio = round(average_deep / average_duration * 100) if average_duration > 0 else 0
        parts.append(
            f"深睡平均 {_minutes_text(round(average_deep))}、约占 {ratio}%"
        )
    awake_samples = [night.awake_minutes for night in recent if night.awake_minutes > 0]
    if len(awake_samples) >= MIN_RECENT_SAMPLES:
        parts.append(f"夜醒平均 {_minutes_text(round(_average(awake_samples)))}")

    assertion = (
        f"{TREND_PREFIX}sleep] 截至 {through.isoformat()}，"
        + "；".join(parts)
        + "。仅用于生活方式观察，不构成医疗判断。"
    )
    confidence = "high" if len(recent) == RECENT_DAYS else "medium"
    return TrendObservation("sleep", assertion, confidence)


def _recovery_trend(events: list[TrendEvent], through: date) -> TrendObservation | None:
    current_start, current_end = _sample_range(through, RECENT_DAYS)
    history_start = through - timedelta(days=HISTORY_DAYS - 1)
    heart = _latest_daily_values(
        events,
        "health.heart_rate_daily",
        lambda payload: _as_float(payload.get("average_bpm")),
    )
    oxygen = _latest_daily_values(
        events,
        "health.oxygen_saturation_daily",
        lambda payload: _as_float(payload.get("min_percent")),
    )
    stress = _latest_daily_values(
        events,
        "health.stress_daily",
        lambda payload: _as_float(payload.get("average_score")),
    )
    recent_heart = _values_between(heart, current_start, current_end)
    recent_oxygen = _values_between(oxygen, current_start, current_end)
    recent_stress = _values_between(stress, current_start, current_end)
    history_heart = _values_between(heart, history_start, through)
    history_oxygen = _values_between(oxygen, history_start, through)
    if (
        len(history_heart) < MIN_HISTORY_SAMPLES
        and len(history_oxygen) < MIN_HISTORY_SAMPLES
    ):
        return None
    if (
        len(recent_heart) < MIN_RECENT_SAMPLES
        and len(recent_oxygen) < MIN_RECENT_SAMPLES
        and len(recent_stress) < MIN_RECENT_SAMPLES
    ):
        return None

    parts: list[str] = []
    if len(recent_heart) >= MIN_RECENT_SAMPLES:
        parts.append(
            f"日均全天心率约 {round(_average(recent_heart))} bpm"
            f"（日均范围 {round(min(recent_heart))}–{round(max(recent_heart))} bpm）"
        )
    if len(recent_oxygen) >= MIN_RECENT_SAMPLES:
        parts.append(
            f"每日最低血氧平均 {_average(recent_oxygen):.1f}%"
            f"、最低 {min(recent_oxygen):.1f}%"
        )
    if len(recent_stress) >= MIN_RECENT_SAMPLES:
        parts.append(f"日均压力指数约 {round(_average(recent_stress))}")
    elif recent_stress:
        parts.append(f"压力仅 {len(recent_stress)}/7 天有样本，暂不形成趋势")
    else:
        parts.append("压力本期无有效样本")

    assertion = (
        f"{TREND_PREFIX}recovery] 截至 {through.isoformat()}，"
        + "；".join(parts)
        + "。以上为设备记录的趋势摘要，仅供生活方式参考，不是医疗诊断。"
    )
    confidence = "high" if (
        len(recent_heart) == RECENT_DAYS or len(recent_oxygen) == RECENT_DAYS
    ) else "medium"
    return TrendObservation("recovery", assertion, confidence)


def build_health_trends(
    events: Iterable[TrendEvent],
    now: datetime | None = None,
) -> list[TrendObservation]:
    local_now = now or datetime.now(CST)
    if local_now.tzinfo is None:
        local_now = local_now.replace(tzinfo=CST)
    else:
        local_now = local_now.astimezone(CST)
    today = local_now.date()
    completed_day = today - timedelta(days=1)
    observations = [
        _activity_trend(list(events), completed_day),
        _sleep_trend(list(events), today),
        _recovery_trend(list(events), completed_day),
    ]
    return [item for item in observations if item is not None]


def load_health_trend_events(
    pool: Any,
    *,
    agent_id: str,
    user_id: int,
    now: datetime | None = None,
) -> list[TrendEvent]:
    local_now = now or datetime.now(CST)
    if local_now.tzinfo is None:
        local_now = local_now.replace(tzinfo=CST)
    cutoff = int(
        (
            local_now.astimezone(CST) - timedelta(days=LOOKBACK_DAYS)
        ).timestamp()
        * 1000
    )
    with pool.connect() as conn:
        rows = conn.execute(
            """
            SELECT event_id, event_type, occurred_at, observed_at, payload_json
            FROM mobile_events
            WHERE agent_id = ? AND user_id = ? AND source_type = ?
              AND occurred_at >= ?
            ORDER BY occurred_at DESC
            LIMIT 5000
            """,
            (agent_id, user_id, SOURCE_TYPE, cutoff),
        ).fetchall()
    return parse_trend_events(rows)


def _memory_backend(pool: Any, agent_id: str) -> tuple[str, dict[str, Any]] | None:
    with pool.connect() as conn:
        row = conn.execute(
            "SELECT config_json FROM agents WHERE agent_id = ? OR id = ? LIMIT 1",
            (agent_id, agent_id),
        ).fetchone()
    config: dict[str, Any] = {}
    if row is not None:
        raw_config = _row_value(row, "config_json", "{}")
        try:
            parsed = json.loads(raw_config) if isinstance(raw_config, str) else raw_config
            config = parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            LOG.warning("invalid agent memory configuration; skipping health trend memory")
            return None

    memory = config.get("memory")
    backend = memory.get("backend") if isinstance(memory, dict) else None
    if isinstance(backend, dict):
        backend_type = str(backend.get("type") or "sqlite").lower()
        if backend_type == "postgres":
            dsn = str(backend.get("dsn") or "").strip()
            return ("postgres", {"dsn": dsn}) if dsn else None
        if backend_type == "sqlite":
            explicit = str(backend.get("db_path") or "").strip()
            if explicit:
                return "sqlite", {"db_path": explicit}

    backend_config = config.get("backend")
    root = (
        str(backend_config.get("root_dir") or "").strip()
        if isinstance(backend_config, dict)
        else ""
    )
    root = root or os.environ.get("OCTOP_AGENT_WORKSPACE", "/data/agent-workspace")
    workspace = str(config.get("workspace_dir") or f"/.octop/workspaces/{agent_id}")
    system_files = str(config.get("system_files_path") or ".octop")
    db_path = (
        Path(root)
        / workspace.lstrip("/\\")
        / system_files.lstrip("/\\")
        / "memory.sqlite"
    )
    return "sqlite", {"db_path": str(db_path)}


def _trend_key(assertion: str) -> str | None:
    if not assertion.startswith(TREND_PREFIX):
        return None
    remainder = assertion[len(TREND_PREFIX):]
    return remainder.split("]", 1)[0] if "]" in remainder else None


def publish_health_trends(
    observations: Iterable[TrendObservation],
    memory: Any,
    *,
    actor: str = "auto",
) -> dict[str, int]:
    entity = memory.find_entity_by_name(ENTITY_NAME, entity_type=ENTITY_TYPE)
    if entity is None:
        entity = memory.find_entity_by_alias(ENTITY_NAME)
    existing = {}
    if entity is not None:
        for atom in memory.list_atoms(entity_id=entity.id, limit=100):
            key = _trend_key(str(getattr(atom, "assertion", "")))
            if key is not None:
                existing[key] = atom

    created = 0
    replaced = 0
    unchanged = 0
    for observation in observations:
        current = existing.get(observation.key)
        if current is not None and current.assertion == observation.assertion:
            unchanged += 1
            continue
        if current is not None:
            memory.replace_atom(
                current.id,
                assertion=observation.assertion,
                actor=actor,
                note="更新完整健康周期的趋势摘要",
            )
            replaced += 1
            continue
        memory.create_atom(
            observation.assertion,
            entity_name=ENTITY_NAME,
            entity_type=ENTITY_TYPE,
            kind="Fact",
            importance="medium",
            confidence=observation.confidence,
            verbatim_quote=observation.assertion,
            actor=actor,
            note="由已完成健康周期聚合生成，不含账号凭据或原始传感器明细",
        )
        created += 1
    return {"created": created, "replaced": replaced, "unchanged": unchanged}


def sync_health_trend_memory(
    pool: Any,
    *,
    agent_id: str,
    user_id: int,
    now: datetime | None = None,
    memory_factory: Callable[..., Any] | None = None,
) -> dict[str, int]:
    local_now = now or datetime.now(CST)
    events = load_health_trend_events(
        pool,
        agent_id=agent_id,
        user_id=user_id,
        now=local_now,
    )
    observations = build_health_trends(events, now=local_now)
    if not observations:
        return {"created": 0, "replaced": 0, "unchanged": 0}

    backend = _memory_backend(pool, agent_id)
    if backend is None:
        LOG.info("health trend memory skipped: unsupported or missing backend")
        return {"created": 0, "replaced": 0, "unchanged": 0}
    backend_type, backend_config = backend
    if backend_type == "sqlite":
        db_path = Path(str(backend_config.get("db_path") or ""))
        if not db_path.is_file():
            LOG.warning("health trend memory database does not exist")
            return {"created": 0, "replaced": 0, "unchanged": 0}
        try:
            db_path.chmod(0o600)
        except OSError:
            LOG.warning("failed to restrict health memory database permissions")

    if memory_factory is None:
        from octop_memory import Memory  # noqa: PLC0415

        memory_factory = Memory
    memory = memory_factory(
        namespace=f"agent_{agent_id}",
        backend=backend_type,
        backend_config=backend_config,
    )
    result = publish_health_trends(observations, memory)
    LOG.info(
        "health trend memory created=%d replaced=%d unchanged=%d",
        result["created"],
        result["replaced"],
        result["unchanged"],
    )
    return result


__all__ = [
    "TrendEvent",
    "TrendObservation",
    "build_health_trends",
    "load_health_trend_events",
    "parse_trend_events",
    "publish_health_trends",
    "sync_health_trend_memory",
]
