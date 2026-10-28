"""Build morning sleep and evening health cards from synced mobile health events."""

from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Any, Iterable, Mapping

from octop.infra.db.pool import DatabasePool
from octop.infra.db.repos.agents import AgentRepo
from octop.infra.today.cards import (
    TodayCardDraft,
    TodayCardPublisher,
    TodayEvidenceInput,
)

logger = logging.getLogger(__name__)

_HEALTH_SOURCES = ("health_connect", "android_health_connect", "xiaomi_health")
_LOOKBACK_DAYS = 16
_ALERT_KINDS = ("sedentary", "stress", "heart", "oxygen")
_EVENT_TITLES = {
    "health.steps_30d": "步数",
    "health.distance_daily": "活动距离",
    "health.active_calories_daily": "活动热量",
    "health.heart_rate_daily": "心率",
    "health.resting_heart_rate_daily": "静息心率",
    "health.oxygen_saturation_daily": "血氧",
    "health.hrv_daily": "HRV",
    "health.stress_daily": "压力",
    "health.standing_daily": "站立与久坐",
    "health.sleep_session": "睡眠",
    "health.exercise_session": "运动记录",
}
_EXERCISE_NAMES = {
    "badminton": "羽毛球",
    "biking": "骑行",
    "biking_stationary": "室内骑行",
    "dancing": "舞蹈",
    "elliptical": "椭圆机",
    "football_american": "橄榄球",
    "football_australian": "澳式足球",
    "golf": "高尔夫",
    "hiking": "徒步",
    "high_intensity_interval_training": "高强度间歇",
    "pilates": "普拉提",
    "rowing": "划船",
    "rowing_machine": "划船机",
    "running": "跑步",
    "running_treadmill": "跑步机",
    "soccer": "足球",
    "stair_climbing": "爬楼",
    "stair_climbing_machine": "爬楼机",
    "strength_training": "力量训练",
    "stretching": "拉伸",
    "swimming_open_water": "公开水域游泳",
    "swimming_pool": "泳池游泳",
    "tennis": "网球",
    "walking": "步行",
    "weightlifting": "举重",
    "yoga": "瑜伽",
    "other_workout": "其他运动",
}
_METRIC_LABELS = {
    "sleep": ("睡眠时长", "小时"),
    "deep_sleep": ("深睡", "小时"),
    "awake": ("夜醒", "分钟"),
    "steps": ("步数", "步"),
    "standing": ("有效站立", "小时"),
    "sedentary": ("最长久坐", "分钟"),
    "heart": ("心率", "bpm"),
    "stress": ("压力指数", "分"),
    "oxygen": ("血氧", "%"),
}


@dataclass(frozen=True)
class HealthEvent:
    event_id: str
    source_record_id: str | None
    source_type: str
    event_type: str
    occurred_at: int
    observed_at: int
    payload: Mapping[str, Any]
    offset_minutes: int


@dataclass(frozen=True)
class HealthCard:
    dedupe_key: str
    title: str
    summary: str
    why_now: str
    action_prompt: str
    occurred_at: int
    evidence: tuple[TodayEvidenceInput, ...]


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


def _clamp_offset(value: Any) -> int | None:
    offset = _as_int(value, 9999)
    return offset if -840 <= offset <= 840 else None


def _local_datetime(epoch_seconds: int, offset_minutes: int) -> datetime:
    tz = timezone(timedelta(minutes=offset_minutes))
    return datetime.fromtimestamp(epoch_seconds, tz=tz)


def _duration_text(minutes: int) -> str:
    hours, remainder = divmod(max(0, minutes), 60)
    if hours and remainder:
        return f"{hours} 小时 {remainder} 分"
    if hours:
        return f"{hours} 小时"
    return f"{remainder} 分钟"


def _clock_text(value: datetime) -> str:
    return value.strftime("%H:%M")


def parse_health_events(rows: Iterable[Mapping[str, Any]]) -> list[HealthEvent]:
    events: list[HealthEvent] = []
    for row in rows:
        try:
            payload = row.get("payload")
            if isinstance(payload, str):
                payload = json.loads(payload)
            if not isinstance(payload, Mapping):
                raw = row.get("payload_json")
                payload = json.loads(raw) if isinstance(raw, str) else {}
            event_type = str(row.get("event_type") or "")
            if not event_type.startswith("health."):
                continue
            occurred_at = _epoch_seconds(row.get("occurred_at"))
            observed_at = _epoch_seconds(row.get("observed_at"), occurred_at)
            if occurred_at <= 0:
                continue
            offset = _clamp_offset(payload.get("timezone_offset_minutes"))
            if offset is None:
                offset = int(datetime.now().astimezone().utcoffset().total_seconds() // 60)
            events.append(
                HealthEvent(
                    event_id=str(row.get("event_id") or row.get("id") or ""),
                    source_record_id=(
                        str(row["source_record_id"])
                        if row.get("source_record_id") not in (None, "")
                        else None
                    ),
                    source_type=str(row.get("source_type") or "health_connect"),
                    event_type=event_type,
                    occurred_at=occurred_at,
                    observed_at=observed_at or occurred_at,
                    payload=payload,
                    offset_minutes=offset,
                )
            )
        except (TypeError, ValueError, json.JSONDecodeError):
            logger.debug("skip malformed mobile health event", exc_info=True)
    return events


def _event_date(event: HealthEvent) -> date:
    raw_date = event.payload.get("date")
    if isinstance(raw_date, str):
        try:
            return date.fromisoformat(raw_date[:10])
        except ValueError:
            pass
    return _local_datetime(event.occurred_at, event.offset_minutes).date()


def _preferred_events(events: Iterable[HealthEvent]) -> list[HealthEvent]:
    """Use Xiaomi cloud as the canonical source once it has any useful data."""
    values = list(events)
    cloud_events = [
        event
        for event in values
        if event.event_type != "health.sync_window"
        and event.payload.get("data_source") == "xiaomi_health_cloud"
    ]
    if cloud_events:
        return cloud_events
    return [
        event
        for event in values
        if event.source_type in _HEALTH_SOURCES
    ]


def _latest_event(
    events: Iterable[HealthEvent],
    event_type: str,
    target_date: date,
) -> HealthEvent | None:
    candidates = [
        event
        for event in events
        if event.event_type == event_type and _event_date(event) == target_date
    ]
    return max(
        candidates,
        key=lambda event: (
            event.payload.get("data_source") == "xiaomi_health_cloud",
            event.observed_at,
            event.occurred_at,
        ),
        default=None,
    )


def _sleep_events(events: Iterable[HealthEvent], target_date: date) -> list[HealthEvent]:
    events = _preferred_events(events)
    selected: dict[str, HealthEvent] = {}
    for event in events:
        if event.event_type != "health.sleep_session":
            continue
        ends_at = _epoch_seconds(event.payload.get("ends_at"))
        if ends_at <= 0:
            continue
        end_local = _local_datetime(ends_at, event.offset_minutes)
        duration = _as_int(event.payload.get("duration_minutes"))
        if duration <= 0:
            duration = max(0, (ends_at - event.occurred_at) // 60)
        if end_local.date() != target_date or end_local.hour >= 12 or duration < 45:
            continue
        selected[event.event_id] = event
    return sorted(selected.values(), key=lambda event: event.occurred_at)


def _sleep_advice(duration: int, deep: int, awake: int) -> list[str]:
    advice: list[str] = []
    if duration < 360:
        advice.append("今晚把入睡时间提前 30–45 分钟，先保证连续睡眠时长。")
    elif duration > 600:
        advice.append("明早固定起床时间，并在白天增加一次短时户外活动。")
    if awake > 30:
        advice.append("睡前 1 小时减少屏幕、酒精和咖啡因，降低夜间清醒时间。")
    if deep > 0 and deep * 100 < duration * 12:
        advice.append("今天避免高强度训练叠加，给恢复留出空间。")
    if not advice:
        advice.append("保持今天的入睡与起床时间，别在周末一次偏移太多。")
    if len(advice) < 2:
        advice.append("起床后接触 10–20 分钟自然光，帮助稳定今天的节律。")
    return advice[:3]


def _sleep_totals(
    events: list[HealthEvent],
    target_date: date,
) -> tuple[list[HealthEvent], int, int, int, int, int]:
    sleep_events = _sleep_events(events, target_date)
    duration = sum(
        _as_int(event.payload.get("duration_minutes"))
        or max(
            0,
            (
                _epoch_seconds(event.payload.get("ends_at"))
                - _epoch_seconds(event.payload.get("starts_at"), event.occurred_at)
            )
            // 60,
        )
        for event in sleep_events
    )
    stage_totals: dict[str, int] = {}
    for event in sleep_events:
        stages = event.payload.get("stages_minutes")
        if not isinstance(stages, Mapping):
            continue
        for name, minutes in stages.items():
            stage_totals[str(name)] = stage_totals.get(str(name), 0) + _as_int(minutes)
    sleep_score = next(
        (
            _as_int(event.payload.get("sleep_score"))
            for event in sleep_events
            if _as_int(event.payload.get("sleep_score")) > 0
        ),
        0,
    )
    return (
        sleep_events,
        duration,
        stage_totals.get("deep", 0),
        stage_totals.get("rem", 0),
        stage_totals.get("awake", 0) + stage_totals.get("out_of_bed", 0),
        sleep_score,
    )


def _sleep_quality(duration: int, deep: int, awake: int) -> str:
    if duration < 360:
        return "偏短"
    if duration > 600:
        return "偏长"
    if deep > 0 and deep * 100 >= duration * 15 and awake <= 30:
        return "较好"
    return "一般"


def _sleep_line(events: list[HealthEvent], target_date: date) -> str:
    sleep_events, duration, deep, rem, awake, score = _sleep_totals(events, target_date)
    if not sleep_events or duration <= 0:
        return "昨晚睡眠：设备未提供"
    starts_at = min(
        _epoch_seconds(event.payload.get("starts_at"), event.occurred_at)
        for event in sleep_events
    )
    ends_at = max(_epoch_seconds(event.payload.get("ends_at")) for event in sleep_events)
    starts_local = _local_datetime(starts_at, sleep_events[0].offset_minutes)
    ends_local = _local_datetime(ends_at, sleep_events[0].offset_minutes)
    quality = _sleep_quality(duration, deep, awake)
    parts = [
        f"昨晚 {_clock_text(starts_local)}–{_clock_text(ends_local)}，"
        f"{_duration_text(duration)}，质量{quality}"
    ]
    if deep > 0:
        parts.append(f"深睡 {_duration_text(deep)}")
    if rem > 0:
        parts.append(f"REM {_duration_text(rem)}")
    if awake > 0:
        parts.append(f"夜醒 {_duration_text(awake)}")
    if score > 0:
        parts.append(f"评分 {score}")
    return " · ".join(parts)


def _activity_snapshot(events: list[HealthEvent], target_date: date) -> str:
    values: list[str] = []
    steps_event = _latest_event(events, "health.steps_30d", target_date)
    if steps_event and _as_int(steps_event.payload.get("steps")) > 0:
        values.append(f"{_as_int(steps_event.payload.get('steps')):,} 步")
    standing_event = _latest_event(events, "health.standing_daily", target_date)
    if standing_event and _as_int(standing_event.payload.get("stand_hours")) > 0:
        values.append(f"站立 {_as_int(standing_event.payload.get('stand_hours'))} 小时")
    exercise_events = [
        event
        for event in events
        if event.event_type == "health.exercise_session"
        and _local_datetime(
            _epoch_seconds(event.payload.get("starts_at"), event.occurred_at),
            event.offset_minutes,
        ).date()
        == target_date
    ]
    if exercise_events:
        minutes = sum(_as_int(event.payload.get("duration_minutes")) for event in exercise_events)
        values.append(f"运动 {minutes} 分钟")
    return "今日活动：" + (" · ".join(values) if values else "尚未同步到今日活动记录")


def _series_for_metric(
    events: list[HealthEvent],
    event_type: str,
    field: str,
    target_date: date,
    *,
    days: int = 7,
) -> list[float | None]:
    values: list[float | None] = []
    for offset in range(days - 1, -1, -1):
        event = _latest_event(events, event_type, target_date - timedelta(days=offset))
        value = _as_float(event.payload.get(field)) if event else 0.0
        values.append(round(value, 1) if value > 0 else None)
    return values


def _sleep_series(
    events: list[HealthEvent],
    target_date: date,
    field: str,
    *,
    days: int = 7,
) -> list[float | None]:
    values: list[float | None] = []
    for offset in range(days - 1, -1, -1):
        _, duration, deep, _, awake, _ = _sleep_totals(
            events,
            target_date - timedelta(days=offset),
        )
        value = {
            "duration": duration,
            "deep": deep,
            "awake": awake,
        }.get(field, 0)
        values.append(float(value) if value > 0 else None)
    return values


def _visual_metric(
    key: str,
    value: float | int | None,
    display: str,
    *,
    target: float | int | None = None,
    series: list[float | None] | None = None,
    chart: str = "bar",
) -> dict[str, Any]:
    metric: dict[str, Any] = {
        "k": key,
        "v": value,
        "d": display,
        "u": _METRIC_LABELS.get(key, (key, ""))[1],
        "c": chart,
    }
    if target is not None and target > 0:
        metric["t"] = target
    if series:
        metric["s"] = series
    return metric


def _visualization_section(window: str, metrics: list[dict[str, Any]]) -> str:
    payload = json.dumps(
        {"w": window, "m": metrics[:6]},
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return f"【健康可视化】\n{payload}"


def _summary_with_visualization(
    sections: list[str],
    window: str,
    metrics: list[dict[str, Any]],
) -> str:
    for count in range(min(6, len(metrics)), 0, -1):
        summary = "\n\n".join(
            [*sections, _visualization_section(window, metrics[:count])]
        )
        if len(summary) <= 1200:
            return summary
    return "\n\n".join(sections)[:1200]


def build_morning_card(events: list[HealthEvent], local_now: datetime) -> HealthCard | None:
    if not 5 <= local_now.hour < 12:
        return None
    events = _preferred_events(events)
    sleep_events, duration, deep, rem, awake, sleep_score = _sleep_totals(
        events,
        local_now.date(),
    )
    if not sleep_events or duration <= 0:
        return None

    starts_at = min(
        _epoch_seconds(event.payload.get("starts_at"), event.occurred_at)
        for event in sleep_events
    )
    ends_at = max(
        _epoch_seconds(event.payload.get("ends_at"))
        for event in sleep_events
    )
    starts_local = _local_datetime(starts_at, sleep_events[0].offset_minutes)
    ends_local = _local_datetime(ends_at, sleep_events[0].offset_minutes)
    quality = _sleep_quality(duration, deep, awake)

    overview = [
        f"昨晚 {_clock_text(starts_local)}–{_clock_text(ends_local)}，共 {_duration_text(duration)}，质量{quality}。"
    ]
    if deep > 0 or rem > 0 or awake > 0:
        overview.append(
            f"深睡 {_duration_text(deep)} · REM {_duration_text(rem)} · 夜醒 {_duration_text(awake)}"
        )
    else:
        overview.append("设备未提供睡眠阶段，以下只按时长判断。")
    if sleep_score > 0:
        overview.append(f"设备睡眠评分：{sleep_score}。")
    overview.append(_activity_snapshot(events, local_now.date()))
    advice = _sleep_advice(duration, deep, awake)
    origins = sorted(
        {
            str(event.payload.get("data_origin") or "")
            for event in sleep_events
            if event.payload.get("data_origin")
        }
    )
    source_note = (
        "来自小米运动健康云端（com.mi.health）。"
        if any(
            event.payload.get("data_source") == "xiaomi_health_cloud"
            for event in sleep_events
        )
        else (
            f"来自 Health Connect（{', '.join(origins)}）。"
            if origins
            else "来自 Health Connect。"
        )
    )
    metrics = [
        _visual_metric(
            "sleep",
            duration,
            _duration_text(duration),
            target=480,
            series=_sleep_series(events, local_now.date(), "duration"),
            chart="ratio",
        ),
        _visual_metric(
            "deep_sleep",
            deep,
            _duration_text(deep),
            series=_sleep_series(events, local_now.date(), "deep"),
        ),
        _visual_metric(
            "awake",
            awake,
            _duration_text(awake),
            series=_sleep_series(events, local_now.date(), "awake"),
            chart="line",
        ),
    ]
    summary = _summary_with_visualization(
        [
            "【睡眠概览】\n" + "\n".join(overview),
            "【改善建议】\n" + "\n".join(f"• {item}" for item in advice),
            f"【数据说明】\n{source_note}睡眠质量按云端记录的时长与阶段做生活方式判断，不是医学评估。",
        ],
        "morning",
        metrics,
    )
    evidence = tuple(
        TodayEvidenceInput(
            source_type="mobile_event",
            reference=event.event_id,
            title="昨晚睡眠记录",
            excerpt=(
                f"{_duration_text(_as_int(event.payload.get('duration_minutes')))}"
                f" · {event.payload.get('data_origin') or 'Health Connect'}"
            ),
            occurred_at=event.occurred_at,
        )
        for event in sleep_events[:8]
    )
    return HealthCard(
        dedupe_key=f"auto:health:morning:{local_now.date().isoformat()}",
        title=f"昨晚睡眠：{_duration_text(duration)}，质量{quality}",
        summary=summary,
        why_now="早晨适合回顾昨夜睡眠，并据此安排今天的精力与运动强度。",
        action_prompt=(
            "结合昨晚睡眠和今天的安排，给我 1–2 个具体可执行的作息调整建议；"
            "不要做医疗诊断。"
        ),
        occurred_at=ends_at,
        evidence=evidence,
    )


def _metric_text(
    events: list[HealthEvent],
    event_type: str,
    target_date: date,
    formatter: Any,
) -> str | None:
    event = _latest_event(events, event_type, target_date)
    return formatter(event.payload) if event else None


def _hrv_reference(events: list[HealthEvent], target_date: date) -> str:
    today_event = _latest_event(events, "health.hrv_daily", target_date)
    if today_event is None:
        return "压力（HRV 参考）：设备未提供"
    today_value = _as_float(today_event.payload.get("average_ms"))
    if today_value <= 0:
        return "压力（HRV 参考）：设备未提供"
    baseline_values = [
        _as_float(event.payload.get("average_ms"))
        for event in events
        if event.event_type == "health.hrv_daily"
        and target_date - timedelta(days=14) <= _event_date(event) < target_date
        and _as_float(event.payload.get("average_ms")) > 0
    ]
    if not baseline_values:
        return f"压力（HRV 参考）：{today_value:.0f} ms，基线不足"
    baseline = sum(baseline_values) / len(baseline_values)
    delta = (today_value - baseline) / baseline * 100
    direction = "高于" if delta >= 0 else "低于"
    return f"压力（HRV 参考）：{today_value:.0f} ms，{direction} 14 日基线 {abs(delta):.0f}%"


def _stress_reference(events: list[HealthEvent], target_date: date) -> str:
    today_event = _latest_event(events, "health.stress_daily", target_date)
    if today_event is None:
        return _hrv_reference(events, target_date)
    today_value = _as_float(today_event.payload.get("average_score"))
    if today_value <= 0:
        return _hrv_reference(events, target_date)
    baseline_values = [
        _as_float(event.payload.get("average_score"))
        for event in events
        if event.event_type == "health.stress_daily"
        and target_date - timedelta(days=14) <= _event_date(event) < target_date
        and _as_float(event.payload.get("average_score")) > 0
    ]
    if not baseline_values:
        return f"压力：{today_value:.0f}，基线不足"
    baseline = sum(baseline_values) / len(baseline_values)
    delta = (today_value - baseline) / baseline * 100
    direction = "高于" if delta >= 0 else "低于"
    return f"压力：{today_value:.0f}，{direction} 14 日基线 {abs(delta):.0f}%"


def _stress_disclaimer(events: list[HealthEvent], target_date: date) -> str:
    stress_event = _latest_event(events, "health.stress_daily", target_date)
    if (
        stress_event is not None
        and _as_float(stress_event.payload.get("average_score")) > 0
    ):
        return "压力为设备记录的压力指数，仅供生活方式参考，不是医疗判断。"
    hrv_event = _latest_event(events, "health.hrv_daily", target_date)
    if hrv_event is not None and _as_float(hrv_event.payload.get("average_ms")) > 0:
        return "压力仅为 HRV 变化参考，不是压力测量或医疗判断。"
    return "压力和 HRV 均未提供，不据此推断身体状态。"


def build_daily_health_card(events: list[HealthEvent], local_now: datetime) -> HealthCard | None:
    if local_now.hour < 18:
        return None
    target_date = local_now.date()
    events = _preferred_events(events)
    today_events = [
        event
        for event in events
        if event.event_type != "health.sleep_session" and _event_date(event) == target_date
    ]
    health_data_events = [
        event for event in today_events if event.event_type != "health.sync_window"
    ]
    if not today_events:
        return None
    sleep_events, sleep_duration, _, _, sleep_awake, _ = _sleep_totals(
        events,
        target_date,
    )
    sleep_line = _sleep_line(events, target_date)

    steps = _metric_text(
        events,
        "health.steps_30d",
        target_date,
        lambda payload: f"{_as_int(payload.get('steps')):,} 步",
    )
    heart = _metric_text(
        events,
        "health.heart_rate_daily",
        target_date,
        lambda payload: (
            f"平均 {_as_int(payload.get('average_bpm'))} bpm"
            f" · 最低 {_as_int(payload.get('min_bpm'))}"
            f" · 最高 {_as_int(payload.get('max_bpm'))}"
        ),
    )
    resting_heart = _metric_text(
        events,
        "health.resting_heart_rate_daily",
        target_date,
        lambda payload: f"{_as_int(payload.get('average_bpm'))} bpm",
    )
    oxygen = _metric_text(
        events,
        "health.oxygen_saturation_daily",
        target_date,
        lambda payload: (
            f"平均 {_as_float(payload.get('average_percent')):.0f}%"
            f" · 最低 {_as_float(payload.get('min_percent')):.0f}%"
            f"（{_as_int(payload.get('measurements'))} 次）"
        ),
    )
    calories = _metric_text(
        events,
        "health.active_calories_daily",
        target_date,
        lambda payload: f"{_as_int(payload.get('active_calories_kcal'))} 千卡",
    )
    distance = _metric_text(
        events,
        "health.distance_daily",
        target_date,
        lambda payload: f"{_as_int(payload.get('distance_meters')) / 1000:.2f} 公里",
    )
    floors = _metric_text(
        events,
        "health.floors_daily",
        target_date,
        lambda payload: f"{_as_int(payload.get('floors'))} 层",
    )
    standing = _metric_text(
        events,
        "health.standing_daily",
        target_date,
        lambda payload: f"{_as_int(payload.get('stand_hours'))} 个有效小时",
    )

    exercise_events = [
        event
        for event in events
        if event.event_type == "health.exercise_session"
        and _local_datetime(
            _epoch_seconds(event.payload.get("starts_at"), event.occurred_at),
            event.offset_minutes,
        ).date()
        == target_date
    ]
    exercise_lines: list[str] = []
    exercise_minutes = 0
    seen_sessions: set[str] = set()
    for event in sorted(exercise_events, key=lambda item: item.occurred_at):
        key = event.source_record_id or event.event_id
        if key in seen_sessions:
            continue
        seen_sessions.add(key)
        minutes = _as_int(event.payload.get("duration_minutes"))
        exercise_minutes += minutes
        raw_name = str(event.payload.get("exercise_type_name") or "other_workout")
        name = _EXERCISE_NAMES.get(raw_name, raw_name)
        exercise_lines.append(f"{name} {minutes} 分钟")

    overview = [
        f"• 步数：{steps or '设备未提供'}",
        f"• 心率：{heart or '设备未提供'}",
        f"• 血氧：{oxygen or '设备未提供'}",
        f"• 有效站立：{standing or '设备未提供'}",
        f"• 静息心率：{resting_heart or '设备未提供'}",
        f"• 辅助参考：{_stress_reference(events, target_date)}",
    ]
    activity = [
        f"• 运动：{'、'.join(exercise_lines[:4]) if exercise_lines else '今天没有运动记录'}",
        f"• 活动热量：{calories or '设备未提供'}",
        f"• 距离：{distance or '设备未提供'}",
        f"• 爬楼：{floors or '设备未提供'}",
    ]

    advice: list[str] = []
    if sleep_events and sleep_duration < 360:
        advice.append("昨晚睡眠偏短，今晚优先提前 30–45 分钟入睡，明早仍按固定时间起床。")
    elif sleep_events and sleep_awake > 30:
        advice.append("昨晚夜醒偏多，睡前 1 小时减少屏幕、酒精和咖啡因，给连续睡眠留出空间。")
    steps_event = _latest_event(events, "health.steps_30d", target_date)
    step_value = _as_int(steps_event.payload.get("steps")) if steps_event else 0
    if steps and step_value < 4000:
        advice.append("今天步数偏少，晚饭后安排 15–20 分钟轻松步行即可，不必补成高强度训练。")
    elif steps and step_value < 8000:
        advice.append("今天离常见活动目标不远，再补一段 10–15 分钟步行就够。")
    elif steps:
        advice.append("今天的活动量已经不错，晚上优先恢复和按时入睡。")
    if exercise_events and exercise_minutes < 20:
        advice.append("今天的运动时长较短，明天可预留一段完整的 20–30 分钟活动。")
    elif not exercise_events:
        advice.append("明天挑一个容易执行的时段，安排 20 分钟步行或低强度运动。")
    oxygen_event = _latest_event(events, "health.oxygen_saturation_daily", target_date)
    if oxygen_event and _as_float(oxygen_event.payload.get("min_percent")) < 95:
        advice.append("血氧记录出现过低于 95% 的读数；若反复出现或伴随不适，优先咨询医生，不要只依赖穿戴设备判断。")
    hrv_event = _latest_event(events, "health.hrv_daily", target_date)
    if hrv_event and "低于 14 日基线" in _hrv_reference(events, target_date):
        advice.append("HRV 较本机基线偏低，今晚减少高强度刺激，留出 5–10 分钟慢呼吸或散步。")
    stress_event = _latest_event(events, "health.stress_daily", target_date)
    if stress_event and "高于 14 日基线" in _stress_reference(events, target_date):
        advice.append("今天的压力读数高于近期基线，安排 5–10 分钟慢走或慢呼吸，并减少连续高强度安排。")
    standing_event = _latest_event(events, "health.standing_daily", target_date)
    if standing_event:
        stand_hours = _as_int(standing_event.payload.get("stand_hours"))
        if 0 < stand_hours < 10:
            advice.append("今天有效站立小时偏少，明天每坐 45–60 分钟起身活动 2–3 分钟。")
        else:
            advice.append("每坐 45–60 分钟起身活动 2–3 分钟，保持今天的活动节奏。")

    visual_metrics: list[dict[str, Any]] = []
    if steps_event:
        step_value = _as_int(steps_event.payload.get("steps"))
        visual_metrics.append(
            _visual_metric(
                "steps",
                step_value,
                f"{step_value:,}",
                target=8000,
                series=_series_for_metric(
                    events,
                    "health.steps_30d",
                    "steps",
                    target_date,
                ),
                chart="line",
            )
        )
    heart_event = _latest_event(events, "health.heart_rate_daily", target_date)
    if heart_event:
        heart_value = _as_float(heart_event.payload.get("average_bpm"))
        if heart_value > 0:
            visual_metrics.append(
                _visual_metric(
                    "heart",
                    heart_value,
                    f"{heart_value:.0f}",
                    series=_series_for_metric(
                        events,
                        "health.heart_rate_daily",
                        "average_bpm",
                        target_date,
                    ),
                    chart="columns",
                )
            )
    if oxygen_event:
        oxygen_value = _as_float(oxygen_event.payload.get("average_percent"))
        if oxygen_value > 0:
            visual_metrics.append(
                _visual_metric(
                    "oxygen",
                    oxygen_value,
                    f"{oxygen_value:.0f}",
                    series=_series_for_metric(
                        events,
                        "health.oxygen_saturation_daily",
                        "average_percent",
                        target_date,
                    ),
                    chart="columns",
                )
            )
    if sleep_events:
        visual_metrics.append(
            _visual_metric(
                "sleep",
                sleep_duration,
                _duration_text(sleep_duration),
                target=480,
                series=_sleep_series(events, target_date, "duration"),
                chart="ratio",
            )
        )
    if standing_event:
        stand_hours = _as_int(standing_event.payload.get("stand_hours"))
        visual_metrics.append(
            _visual_metric(
                "standing",
                stand_hours,
                str(stand_hours),
                target=12,
                series=_series_for_metric(
                    events,
                    "health.standing_daily",
                    "stand_hours",
                    target_date,
                ),
                chart="columns",
            )
        )

    sections = []
    if sleep_events:
        sections.append(f"【昨晚睡眠】\n{sleep_line}")
    sections.extend(
        [
            "【今日概览】\n" + "\n".join(overview),
            "【运动与活动】\n" + "\n".join(activity),
            "【改善建议】\n" + "\n".join(f"• {item}" for item in advice[:3]),
            "【数据说明】\n"
            + _source_note(health_data_events)
            + _stress_disclaimer(events, target_date),
        ]
    )
    summary = _summary_with_visualization(
        sections,
        "evening",
        visual_metrics,
    )
    evidence: list[TodayEvidenceInput] = []
    for event in sorted(
        {
            event.event_id: event
            for event in health_data_events
        }.values(),
        key=lambda item: (item.observed_at, item.occurred_at),
        reverse=True,
    )[:12]:
        evidence.append(
            TodayEvidenceInput(
                source_type="mobile_event",
                reference=event.event_id,
                title=_EVENT_TITLES.get(event.event_type, "健康指标"),
                excerpt=(
                    f"{event.payload.get('date') or _event_date(event).isoformat()}"
                    " · 小米运动健康云端记录"
                ),
                occurred_at=event.occurred_at,
            )
        )
    for event in sleep_events[:3]:
        evidence.append(
            TodayEvidenceInput(
                source_type="mobile_event",
                reference=event.event_id,
                title="昨晚睡眠",
                excerpt="昨晚睡眠时长与阶段 · 小米运动健康云端记录",
                occurred_at=event.occurred_at,
            )
        )
    if not evidence:
        return None
    title_parts = ["晚间健康"]
    if steps:
        title_parts.append(steps)
    elif standing:
        title_parts.append(f"站立 {standing.split(' ', 1)[0]} 小时")
    return HealthCard(
        dedupe_key=f"auto:health:daily:{target_date.isoformat()}",
        title=" · ".join(title_parts),
        summary=summary,
        why_now="晚间适合收束昨晚睡眠、今天的活动、恢复状态与睡眠准备。",
        action_prompt=(
            "基于今天的压力、心率、血氧、步数和运动记录，给我 1–3 个可执行的生活建议；"
            "不要做医疗诊断，缺少数据时直接说明。"
        ),
        occurred_at=int(
            local_now.replace(hour=19, minute=0, second=0, microsecond=0).timestamp()
        ),
        evidence=tuple(evidence),
    )


def _recent_baseline(
    events: list[HealthEvent],
    event_type: str,
    field: str,
    target_date: date,
    *,
    days: int = 14,
) -> tuple[float, int]:
    values = [
        _as_float(event.payload.get(field))
        for event in events
        if event.event_type == event_type
        and target_date - timedelta(days=days) <= _event_date(event) < target_date
        and _as_float(event.payload.get(field)) > 0
    ]
    return (sum(values) / len(values), len(values)) if values else (0.0, 0)


def _alert_card(
    *,
    kind: str,
    title: str,
    observation: str,
    advice: str,
    event: HealthEvent,
    metric: dict[str, Any],
    local_now: datetime,
    why_now: str,
    action_prompt: str,
) -> HealthCard:
    summary = _summary_with_visualization(
        [
            f"【需要留意】\n{observation}",
            f"【现在可以做】\n• {advice}",
            "【数据说明】\n"
            f"{_source_note([event])}这是一条生活方式提醒，不替代医疗判断；"
            "若不适持续或加重，请咨询医生。",
        ],
        "alert",
        [metric],
    )
    return HealthCard(
        dedupe_key=(
            f"auto:health:alert:{kind}:{local_now.date().isoformat()}"
        ),
        title=title,
        summary=summary,
        why_now=why_now,
        action_prompt=action_prompt,
        occurred_at=event.occurred_at,
        evidence=(
            TodayEvidenceInput(
                source_type="mobile_event",
                reference=event.event_id,
                title=_EVENT_TITLES.get(event.event_type, "健康指标"),
                excerpt=f"{local_now.date().isoformat()} · 小米运动健康云端记录",
                occurred_at=event.occurred_at,
            ),
        ),
    )


def build_daytime_alert_cards(
    events: list[HealthEvent],
    local_now: datetime,
) -> list[HealthCard]:
    if not 9 <= local_now.hour < 18:
        return []
    events = _preferred_events(events)
    target_date = local_now.date()
    cards: list[HealthCard] = []

    oxygen_event = _latest_event(
        events,
        "health.oxygen_saturation_daily",
        target_date,
    )
    if oxygen_event:
        average = _as_float(oxygen_event.payload.get("average_percent"))
        minimum = _as_float(oxygen_event.payload.get("min_percent"))
        measurements = _as_int(oxygen_event.payload.get("measurements"))
        if minimum > 0 and (minimum < 90 or (average < 93 and measurements >= 3)):
            cards.append(
                _alert_card(
                    kind="oxygen",
                    title=f"血氧偏低：最低 {minimum:.0f}%",
                    observation=(
                        f"今天设备记录的最低血氧为 {minimum:.0f}%，"
                        f"平均 {average:.0f}%。"
                    ),
                    advice="先静坐复测一次，避免剧烈运动；若反复偏低或伴随胸闷、气短，及时就医。",
                    event=oxygen_event,
                    metric=_visual_metric(
                        "oxygen",
                        minimum,
                        f"{minimum:.0f}",
                        series=_series_for_metric(
                            events,
                            "health.oxygen_saturation_daily",
                            "min_percent",
                            target_date,
                        ),
                        chart="line",
                    ),
                    local_now=local_now,
                    why_now="当前血氧读数低于常用参考范围，值得先复测并观察身体感受。",
                    action_prompt="结合今天的血氧读数，给我安全、保守的复测与就医判断建议；不要做诊断。",
                )
            )

    heart_event = _latest_event(events, "health.heart_rate_daily", target_date)
    if heart_event:
        average = _as_float(heart_event.payload.get("average_bpm"))
        minimum = _as_float(heart_event.payload.get("min_bpm"))
        measurements = _as_int(heart_event.payload.get("measurements"))
        baseline, baseline_count = _recent_baseline(
            events,
            "health.heart_rate_daily",
            "average_bpm",
            target_date,
        )
        elevated = average >= 100 or (
            baseline_count >= 3
            and average >= 85
            and average >= baseline * 1.2
        )
        unusually_low = minimum > 0 and minimum < 40 and measurements >= 6
        if average > 0 and (elevated or unusually_low):
            detail = (
                f"今天平均心率 {average:.0f} bpm，最低 {minimum:.0f} bpm。"
                if minimum > 0
                else f"今天平均心率 {average:.0f} bpm。"
            )
            if baseline_count >= 3:
                detail += f"近期平均约 {baseline:.0f} bpm。"
            cards.append(
                _alert_card(
                    kind="heart",
                    title=(
                        f"心率需要留意：平均 {average:.0f} bpm"
                        if elevated
                        else f"心率需要留意：最低 {minimum:.0f} bpm"
                    ),
                    observation=detail,
                    advice=(
                        "先静坐 5 分钟并复测；若持续明显偏离平时、伴随头晕或心悸，及时咨询医生。"
                    ),
                    event=heart_event,
                    metric=_visual_metric(
                        "heart",
                        average,
                        f"{average:.0f}",
                        series=_series_for_metric(
                            events,
                            "health.heart_rate_daily",
                            "average_bpm",
                            target_date,
                        ),
                        chart="line",
                    ),
                    local_now=local_now,
                    why_now="今天的心率与近期记录相比出现了值得复核的变化。",
                    action_prompt="解释今天的心率变化，并给出复测和何时寻求医疗帮助的保守建议。",
                )
            )

    stress_event = _latest_event(events, "health.stress_daily", target_date)
    if stress_event:
        value = _as_float(stress_event.payload.get("average_score"))
        baseline, baseline_count = _recent_baseline(
            events,
            "health.stress_daily",
            "average_score",
            target_date,
        )
        elevated = value >= 75 or (
            baseline_count >= 3
            and value >= 45
            and value >= baseline * 1.25
        )
        if value > 0 and elevated:
            detail = f"今天的压力指数为 {value:.0f}。"
            if baseline_count >= 3:
                detail += f"近 14 日平均约 {baseline:.0f}。"
            cards.append(
                _alert_card(
                    kind="stress",
                    title=f"压力偏高：当前指数 {value:.0f}",
                    observation=detail,
                    advice="离开连续任务 5–10 分钟，慢走、补水或做几轮慢呼吸，再决定下一件事。",
                    event=stress_event,
                    metric=_visual_metric(
                        "stress",
                        value,
                        f"{value:.0f}",
                        series=_series_for_metric(
                            events,
                            "health.stress_daily",
                            "average_score",
                            target_date,
                        ),
                        chart="line",
                    ),
                    local_now=local_now,
                    why_now="当前压力读数高于你的近期基线，短暂打断连续负荷更合适。",
                    action_prompt="根据当前压力读数，给我一个 5–10 分钟内能完成的恢复动作。",
                )
            )

    standing_event = _latest_event(events, "health.standing_daily", target_date)
    if standing_event:
        sedentary_minutes = _as_int(
            standing_event.payload.get("longest_sedentary_minutes")
        )
        if sedentary_minutes >= 90:
            cards.append(
                _alert_card(
                    kind="sedentary",
                    title=f"久坐提醒：已连续约 {_duration_text(sedentary_minutes)}",
                    observation=(
                        f"小米运动健康根据站立间隔估算，今天最长连续未站立约 "
                        f"{_duration_text(sedentary_minutes)}。"
                    ),
                    advice="现在起身走动 2–3 分钟，接水或做一次简单拉伸，然后再继续手头任务。",
                    event=standing_event,
                    metric=_visual_metric(
                        "sedentary",
                        sedentary_minutes,
                        _duration_text(sedentary_minutes),
                        series=_series_for_metric(
                            events,
                            "health.standing_daily",
                            "longest_sedentary_minutes",
                            target_date,
                        ),
                    ),
                    local_now=local_now,
                    why_now="连续久坐已经超过 90 分钟，现在打断比晚上一次性补活动更有效。",
                    action_prompt="结合当前久坐时长，给我一个 2–3 分钟就能完成的站立活动。",
                )
            )

    return cards


def _default_offset(events: list[HealthEvent], now_seconds: int) -> int:
    if events:
        return max(events, key=lambda event: event.observed_at).offset_minutes
    return int(datetime.now().astimezone().utcoffset().total_seconds() // 60)


def _source_note(events: Iterable[HealthEvent]) -> str:
    health_events = [
        event for event in events if event.event_type != "health.sync_window"
    ]
    source_types = {event.source_type for event in health_events}
    labels = []
    if source_types.intersection({"health_connect", "android_health_connect"}):
        labels.append("Health Connect")
    if any(
        event.source_type == "xiaomi_health"
        and event.payload.get("data_source") == "xiaomi_health_cloud"
        for event in health_events
    ):
        labels.append("小米运动健康云端")
    if "xiaomi_health" in source_types:
        if not any(
            event.payload.get("data_source") == "xiaomi_health_cloud"
            for event in health_events
        ):
            labels.append("小米运动健康（系统步数）")
    if not labels:
        return "数据来源未标注。"
    return f"数据来自{'、'.join(labels)}。"


def build_health_cards(
    events: list[HealthEvent],
    now: int | datetime | None = None,
    offset_minutes: int | None = None,
) -> list[HealthCard]:
    if isinstance(now, datetime):
        now_seconds = int(now.timestamp())
        inferred_offset = int((now.utcoffset() or timedelta()).total_seconds() // 60)
    else:
        now_seconds = _epoch_seconds(now, int(time.time()))
        inferred_offset = None
    offset = _clamp_offset(offset_minutes)
    if offset is None:
        offset = inferred_offset if inferred_offset is not None else _default_offset(events, now_seconds)
    local_now = _local_datetime(now_seconds, offset)
    cards = [
        build_morning_card(events, local_now),
        build_daily_health_card(events, local_now),
        *build_daytime_alert_cards(events, local_now),
    ]
    return [card for card in cards if card is not None]


def _column_names(conn: Any, table: str) -> set[str]:
    names: set[str] = set()
    for row in conn.execute(f"PRAGMA table_info({table})").fetchall():
        try:
            names.add(str(row["name"]))
        except (TypeError, IndexError, KeyError):
            names.add(str(row[1]))
    return names


def _fetch_health_rows(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    now_seconds: int,
) -> list[Mapping[str, Any]]:
    with db.connect() as conn:
        columns = _column_names(conn, "mobile_events")
        if not columns or "event_type" not in columns or "occurred_at" not in columns:
            return []
        event_id = "event_id" if "event_id" in columns else "id"
        payload_column = "payload_json" if "payload_json" in columns else "payload"
        source_record = (
            "source_record_id" if "source_record_id" in columns else "NULL AS source_record_id"
        )
        observed_at = (
            "observed_at" if "observed_at" in columns else "occurred_at AS observed_at"
        )
        source_type = (
            "source_type" if "source_type" in columns else "'health_connect' AS source_type"
        )
        source_placeholders = ",".join("?" for _ in _HEALTH_SOURCES)
        where = ["agent_id = ?", f"source_type IN ({source_placeholders})", "occurred_at >= ?"]
        params: list[Any] = [
            agent_id,
            *_HEALTH_SOURCES,
            now_seconds - _LOOKBACK_DAYS * 24 * 60 * 60,
        ]
        if "user_id" in columns:
            where.append("user_id = ?")
            params.append(user_id)
        rows = conn.execute(
            "SELECT "
            f"{event_id} AS event_id, "
            f"{source_record}, "
            f"{source_type}, "
            "event_type, occurred_at, "
            f"{observed_at}, "
            f"{payload_column} AS payload_json "
            "FROM mobile_events WHERE "
            + " AND ".join(where)
            + " ORDER BY observed_at DESC, occurred_at DESC LIMIT 2000",
            tuple(params),
        ).fetchall()
    return [dict(row) for row in rows]


def _dismiss_inactive_alerts(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    target_date: date,
    active_keys: set[str],
) -> None:
    all_keys = {
        f"agent:auto:health:alert:{kind}:{target_date.isoformat()}"
        for kind in _ALERT_KINDS
    }
    stale_keys = sorted(all_keys - active_keys)
    if not stale_keys:
        return
    placeholders = ",".join("?" for _ in stale_keys)
    with db.transaction() as conn:
        conn.execute(
            "UPDATE today_cards SET lifecycle = 'dismissed', snoozed_until = NULL, "
            "updated_at = ? WHERE agent_id = ? AND user_id = ? "
            f"AND dedupe_key IN ({placeholders}) AND lifecycle != 'dismissed'",
            (
                int(time.time()),
                agent_id,
                user_id,
                *stale_keys,
            ),
        )


def publish_health_today_cards(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    now: int | datetime | None = None,
) -> list[str]:
    now_seconds = (
        int(now.timestamp())
        if isinstance(now, datetime)
        else _epoch_seconds(now, int(time.time()))
    )
    try:
        rows = _fetch_health_rows(
            db,
            agent_id=agent_id,
            user_id=user_id,
            now_seconds=now_seconds,
        )
        events = parse_health_events(rows)
        cards = build_health_cards(events, now=now_seconds)
        publisher = TodayCardPublisher(db, agent_repo=AgentRepo(db))
        published_ids: list[str] = []
        active_alert_keys: set[str] = set()
        for card in cards:
            try:
                published = publisher.publish(
                    TodayCardDraft(
                        dedupe_key=card.dedupe_key,
                        kind="health",
                        title=card.title,
                        summary=card.summary,
                        why_now=card.why_now,
                        action_prompt=card.action_prompt,
                        risk_level="A1",
                        evidence=list(card.evidence),
                        confidence=0.95,
                        occurred_at=card.occurred_at,
                    ),
                    agent_id=agent_id,
                    user_id=user_id,
                )
                published_ids.append(str(published["card_id"]))
                if card.dedupe_key.startswith("auto:health:alert:"):
                    active_alert_keys.add(f"agent:{card.dedupe_key}")
            except Exception:
                logger.warning(
                    "failed to publish health Today card for %s",
                    card.dedupe_key,
                    exc_info=True,
                )
        try:
            _dismiss_inactive_alerts(
                db,
                agent_id=agent_id,
                user_id=user_id,
                target_date=_local_datetime(
                    now_seconds,
                    _default_offset(events, now_seconds),
                ).date(),
                active_keys=active_alert_keys,
            )
        except Exception:
            logger.warning("failed to dismiss inactive health alerts", exc_info=True)
        return published_ids
    except Exception:
        logger.warning("failed to build health Today cards", exc_info=True)
        return []


__all__ = [
    "HealthCard",
    "HealthEvent",
    "build_daytime_alert_cards",
    "build_daily_health_card",
    "build_health_cards",
    "build_morning_card",
    "parse_health_events",
    "publish_health_today_cards",
]
