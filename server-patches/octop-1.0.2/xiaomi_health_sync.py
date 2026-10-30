"""Sync Xiaomi Mi Health cloud data into Octop mobile events.

Run with the Octop virtualenv:
    /opt/octop/venv/bin/python xiaomi_health_sync.py [--full]

Credentials are read from Octop's Fernet-encrypted secrets table. The first run
backfills 30 days; later runs refresh the last three days plus recent workouts.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, time as dt_time, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping

from octop.infra.connectors.crypto import decrypt_credentials, encrypt_credentials
from octop.infra.db.pool import SqlitePool
from octop.infra.db.repos.secrets import SecretRepo
from octop.infra.today.health_cards import publish_health_today_cards

from health_trend_memory import sync_health_trend_memory
from xiaomi_health_cloud import (
    MiHealthAuthError,
    MiHealthCloudClient,
    optional_float,
    optional_int,
    parse_value,
)

LOG = logging.getLogger("xiaomi_health_sync")
CREDENTIALS_KEY = "xiaomi_health_cloud"
LAST_SYNC_KEY = "xiaomi_health.last_sync"
SOURCE_TYPE = "xiaomi_health"
DEVICE_ID = "xiaomi-health-cloud"
PRIVACY_CLASS = "sensitive_health"
DEFAULT_LOOKBACK_DAYS = 30
INCREMENTAL_DAYS = 3
WORKOUT_DAYS = 7
CST = timezone(timedelta(hours=8))
DATA_SOURCE = "xiaomi_health_cloud"
DATA_ORIGIN = "com.mi.health"

WORKOUT_NAMES = {
    "outdoor_running": "running",
    "indoor_running": "running_treadmill",
    "treadmill": "running_treadmill",
    "walking": "walking",
    "outdoor_walking": "walking",
    "outdoor_cycling": "biking",
    "indoor_cycling": "biking_stationary",
    "pool_swimming": "swimming_pool",
    "open_water_swimming": "swimming_open_water",
    "strength": "strength_training",
    "strength_training": "strength_training",
    "yoga": "yoga",
    "hiking": "hiking",
    "rowing": "rowing",
    "elliptical": "elliptical",
    "jump_rope": "other_workout",
}


@dataclass(frozen=True)
class EventDraft:
    event_id: str
    source_record_id: str
    event_type: str
    occurred_at: int
    observed_at: int
    payload: Mapping[str, Any]
    title: str
    summary: str


def _day_start(day: date) -> datetime:
    return datetime.combine(day, dt_time.min, tzinfo=CST)


def _ms(value: int | float | None, *, fallback: int) -> int:
    parsed = int(value or 0)
    if parsed <= 0:
        return fallback
    return parsed if parsed >= 100_000_000_000 else parsed * 1000


def _seconds_from_item(item: Mapping[str, Any]) -> int:
    value = int(item.get("time") or 0)
    if value >= 100_000_000_000:
        value //= 1000
    return value


def _item_observed_ms(item: Mapping[str, Any], fallback: int) -> int:
    return _ms(item.get("update_time"), fallback=fallback)


def _event_payload(**values: Any) -> dict[str, Any]:
    return {
        **values,
        "data_source": DATA_SOURCE,
        "data_origins": DATA_ORIGIN,
        "timezone_offset_minutes": 480,
    }


def _latest_reports(
    reports: Iterable[Mapping[str, Any]],
) -> dict[date, Mapping[str, Any]]:
    selected: dict[date, tuple[tuple[int, int], Mapping[str, Any]]] = {}
    for report in reports:
        timestamp = _seconds_from_item(report)
        if timestamp <= 0:
            continue
        report_date = datetime.fromtimestamp(timestamp, tz=CST).date()
        rank = (
            int(report.get("update_time") or 0),
            int(report.get("watermark") or 0),
        )
        existing = selected.get(report_date)
        if existing is None or rank > existing[0]:
            selected[report_date] = (rank, report)
    return {key: value[1] for key, value in selected.items()}


def _daily_events(
    client: MiHealthCloudClient,
    start: datetime,
    end: datetime,
) -> list[EventDraft]:
    start_seconds = int(start.timestamp())
    end_seconds = int(end.timestamp())
    steps = _latest_reports(
        client.fetch_daily_reports("steps", start_seconds, end_seconds)
    )
    calories = _latest_reports(
        client.fetch_daily_reports("calories", start_seconds, end_seconds)
    )
    drafts: list[EventDraft] = []
    for day in sorted(steps):
        step_payload = parse_value(steps[day])
        step_count = int(step_payload.get("steps") or 0)
        distance = float(step_payload.get("distance") or 0)
        calorie_payload = (
            parse_value(calories[day]) if day in calories else {}
        )
        calories_value = int(
            calorie_payload.get("calories")
            or step_payload.get("calories")
            or 0
        )
        if step_count <= 0:
            continue
        occurred_at = int(_day_start(day).timestamp() * 1000)
        observed_at = max(
            _item_observed_ms(steps[day], occurred_at),
            _item_observed_ms(calories[day], occurred_at)
            if day in calories
            else occurred_at,
        )
        common = {
            "date": day.isoformat(),
            "timezone_offset_minutes": 480,
        }
        drafts.extend(
            [
                EventDraft(
                    event_id=f"xiaomi-cloud:steps:{day.isoformat()}",
                    source_record_id=f"daily_report:{day.isoformat()}:steps",
                    event_type="health.steps_30d",
                    occurred_at=occurred_at,
                    observed_at=observed_at,
                    payload=_event_payload(
                        **common,
                        steps=step_count,
                        distance_meters=round(distance),
                        active_calories_kcal=calories_value,
                    ),
                    title="小米运动健康云端",
                    summary=f"{day.isoformat()} · {step_count:,} 步",
                ),
                EventDraft(
                    event_id=f"xiaomi-cloud:distance:{day.isoformat()}",
                    source_record_id=f"daily_report:{day.isoformat()}:steps",
                    event_type="health.distance_daily",
                    occurred_at=occurred_at,
                    observed_at=observed_at,
                    payload=_event_payload(
                        **common, distance_meters=round(distance)
                    ),
                    title="小米运动健康云端",
                    summary=f"{day.isoformat()} · 距离 {distance / 1000:.2f} 公里",
                ),
                EventDraft(
                    event_id=f"xiaomi-cloud:calories:{day.isoformat()}",
                    source_record_id=(
                        f"daily_report:{day.isoformat()}:calories"
                    ),
                    event_type="health.active_calories_daily",
                    occurred_at=occurred_at,
                    observed_at=observed_at,
                    payload=_event_payload(
                        **common, active_calories_kcal=calories_value
                    ),
                    title="小米运动健康云端",
                    summary=f"{day.isoformat()} · 活动热量 {calories_value} 千卡",
                ),
            ]
        )
    return drafts


def _daily_goal_events(
    client: MiHealthCloudClient,
    start: datetime,
    end: datetime,
) -> list[EventDraft]:
    reports = _latest_reports(
        client.fetch_daily_reports(
            "goal",
            int(start.timestamp()),
            int(end.timestamp()),
            tag="daily_fitness",
        )
    )
    drafts: list[EventDraft] = []
    for day, report in sorted(reports.items()):
        payload = parse_value(report)
        active_minutes: int | None = None
        goal_items = payload.get("goal_items")
        if isinstance(goal_items, list):
            for item in goal_items:
                if (
                    not isinstance(item, dict)
                    or optional_int(item.get("field")) != 4
                ):
                    continue
                active_minutes = optional_int(item.get("achieved_value"))
                break
        if active_minutes is None:
            active_minutes = optional_int(payload.get("active_minutes"))
        if active_minutes is None or active_minutes < 0:
            continue
        occurred_at = int(_day_start(day).timestamp() * 1000)
        drafts.append(
            EventDraft(
                event_id=f"xiaomi-cloud:active-minutes:{day.isoformat()}",
                source_record_id=f"daily_goal:{day.isoformat()}:active_minutes",
                event_type="health.activity_minutes_daily",
                occurred_at=occurred_at,
                observed_at=_item_observed_ms(report, occurred_at),
                payload=_event_payload(
                    date=day.isoformat(),
                    activity_minutes=active_minutes,
                ),
                title="小米运动健康云端",
                summary=f"{day.isoformat()} · 中高强度活动 {active_minutes} 分钟",
            )
        )
    return drafts


def _sleep_events(
    client: MiHealthCloudClient,
    start: datetime,
    end: datetime,
) -> list[EventDraft]:
    reports = _latest_reports(
        client.fetch_daily_reports(
            "sleep", int(start.timestamp()), int(end.timestamp())
        )
    )
    drafts: list[EventDraft] = []
    for report_day, report in sorted(reports.items()):
        payload = parse_value(report)
        segments = payload.get("segment_details")
        if not isinstance(segments, list):
            continue
        for index, segment in enumerate(
            sorted(
                (item for item in segments if isinstance(item, dict)),
                key=lambda item: int(item.get("bedtime") or 0),
            )
        ):
            starts_at = int(segment.get("bedtime") or 0)
            ends_at = int(segment.get("wake_up_time") or 0)
            duration = int(segment.get("duration") or 0)
            if starts_at <= 0 or ends_at <= starts_at or duration <= 0:
                continue
            stages = {
                name: int(segment.get(name) or 0)
                for name in (
                    "sleep_deep_duration",
                    "sleep_light_duration",
                    "sleep_rem_duration",
                    "sleep_awake_duration",
                )
                if int(segment.get(name) or 0) > 0
            }
            stage_payload = {
                "deep": stages.get("sleep_deep_duration", 0),
                "light": stages.get("sleep_light_duration", 0),
                "rem": stages.get("sleep_rem_duration", 0),
                "awake": stages.get("sleep_awake_duration", 0),
            }
            observed_at = _item_observed_ms(
                report, ends_at * 1000
            )
            drafts.append(
                EventDraft(
                    event_id=(
                        f"xiaomi-cloud:sleep:{report_day.isoformat()}:"
                        f"{starts_at}"
                    ),
                    source_record_id=f"daily_report:{report_day.isoformat()}:sleep",
                    event_type="health.sleep_session",
                    occurred_at=starts_at * 1000,
                    observed_at=observed_at,
                    payload=_event_payload(
                        starts_at=starts_at,
                        ends_at=ends_at,
                        duration_minutes=duration,
                        stages_minutes=stage_payload,
                        sleep_score=optional_int(payload.get("sleep_score")),
                        data_origin=DATA_ORIGIN,
                    ),
                    title="小米运动健康云端",
                    summary=(
                        f"睡眠 {duration // 60} 小时 {duration % 60} 分"
                    ),
                )
            )
    return drafts


def _daily_metric_events(
    client: MiHealthCloudClient,
    key: str,
    event_type: str,
    start: datetime,
    end: datetime,
) -> list[EventDraft]:
    records = client.fetch_key(
        key, int(start.timestamp() * 1000), int(end.timestamp() * 1000)
    )
    values: dict[date, list[tuple[int, float, Mapping[str, Any]]]] = (
        defaultdict(list)
    )
    for item in records:
        timestamp = _seconds_from_item(item)
        payload = parse_value(item)
        raw_value = (
            payload.get("bpm")
            if key in ("heart_rate", "resting_heart_rate")
            else payload.get("spo2")
            if key == "spo2"
            else payload.get("stress")
        )
        value = optional_float(raw_value)
        if timestamp <= 0 or value is None:
            continue
        local_day = datetime.fromtimestamp(timestamp, tz=CST).date()
        values[local_day].append((timestamp, value, item))

    drafts: list[EventDraft] = []
    for day, samples in sorted(values.items()):
        numbers = [sample[1] for sample in samples]
        occurred_at = int(_day_start(day).timestamp() * 1000)
        observed_at = max(
            _item_observed_ms(sample[2], occurred_at)
            for sample in samples
        )
        common = {
            "date": day.isoformat(),
            "measurements": len(numbers),
        }
        if key in ("heart_rate", "resting_heart_rate"):
            payload = _event_payload(
                **common,
                average_bpm=round(sum(numbers) / len(numbers)),
                min_bpm=round(min(numbers)),
                max_bpm=round(max(numbers)),
            )
            summary = (
                f"{day.isoformat()} · 平均 {round(sum(numbers) / len(numbers))} bpm"
            )
        elif key == "spo2":
            payload = _event_payload(
                **common,
                average_percent=round(sum(numbers) / len(numbers), 1),
                min_percent=round(min(numbers), 1),
            )
            summary = (
                f"{day.isoformat()} · 平均血氧 "
                f"{round(sum(numbers) / len(numbers))}%"
            )
        else:
            average = round(sum(numbers) / len(numbers))
            payload = _event_payload(
                **common,
                average_score=average,
                min_score=round(min(numbers)),
                max_score=round(max(numbers)),
            )
            summary = f"{day.isoformat()} · 平均压力 {average}"
        drafts.append(
            EventDraft(
                event_id=f"xiaomi-cloud:{key}:{day.isoformat()}",
                source_record_id=f"{key}:{day.isoformat()}",
                event_type=event_type,
                occurred_at=occurred_at,
                observed_at=observed_at,
                payload=payload,
                title="小米运动健康云端",
                summary=summary,
            )
        )
    return drafts


def _standing_events(
    client: MiHealthCloudClient,
    start: datetime,
    end: datetime,
) -> list[EventDraft]:
    records = client.fetch_key(
        "valid_stand",
        int(start.timestamp() * 1000),
        int(end.timestamp() * 1000),
    )
    intervals: dict[date, list[tuple[int, int, Mapping[str, Any]]]] = (
        defaultdict(list)
    )
    for item in records:
        payload = parse_value(item)
        starts_at = int(payload.get("start_time") or 0)
        ends_at = int(payload.get("end_time") or 0)
        if starts_at <= 0 or ends_at < starts_at:
            continue
        local_day = datetime.fromtimestamp(starts_at, tz=CST).date()
        intervals[local_day].append((starts_at, ends_at, item))

    drafts: list[EventDraft] = []
    for day, day_intervals in sorted(intervals.items()):
        day_start = int(_day_start(day).timestamp())
        window_start = day_start + 8 * 3600
        window_end = min(
            day_start + 22 * 3600,
            int(end.timestamp()) if day == end.astimezone(CST).date()
            else day_start + 22 * 3600,
        )
        unique_hours = {
            datetime.fromtimestamp(starts_at, tz=CST).hour
            for starts_at, _, _ in day_intervals
        }
        clipped = sorted(
            (
                max(starts_at, window_start),
                min(ends_at, window_end),
            )
            for starts_at, ends_at, _ in day_intervals
            if ends_at > window_start and starts_at < window_end
        )
        merged: list[tuple[int, int]] = []
        for starts_at, ends_at in clipped:
            if ends_at <= starts_at:
                continue
            if merged and starts_at <= merged[-1][1]:
                merged[-1] = (merged[-1][0], max(merged[-1][1], ends_at))
            else:
                merged.append((starts_at, ends_at))
        longest_gap = 0
        if merged and window_end > window_start:
            cursor = window_start
            for starts_at, ends_at in merged:
                longest_gap = max(longest_gap, starts_at - cursor)
                cursor = max(cursor, ends_at)
            longest_gap = max(longest_gap, window_end - cursor)
        observed_at = max(
            _item_observed_ms(item, day_start * 1000)
            for _, _, item in day_intervals
        )
        drafts.append(
            EventDraft(
                event_id=f"xiaomi-cloud:standing:{day.isoformat()}",
                source_record_id=f"valid_stand:{day.isoformat()}",
                event_type="health.standing_daily",
                occurred_at=day_start * 1000,
                observed_at=observed_at,
                payload=_event_payload(
                    date=day.isoformat(),
                    stand_hours=len(unique_hours),
                    stand_events=len(day_intervals),
                    longest_sedentary_minutes=longest_gap // 60,
                    sedentary_is_estimated=True,
                ),
                title="小米运动健康云端",
                summary=(
                    f"{day.isoformat()} · 站立 {len(unique_hours)} 个有效小时"
                ),
            )
        )
    return drafts


def _workout_events(
    client: MiHealthCloudClient,
    start: datetime,
    end: datetime,
) -> list[EventDraft]:
    records = client.fetch_sport_records(
        int(start.timestamp()), int(end.timestamp())
    )
    drafts: list[EventDraft] = []
    for item in records:
        payload = parse_value(item)
        starts_at = int(payload.get("start_time") or item.get("time") or 0)
        duration_seconds = int(payload.get("duration") or 0)
        ends_at = int(payload.get("end_time") or 0)
        if ends_at <= starts_at and duration_seconds > 0:
            ends_at = starts_at + duration_seconds
        if starts_at <= 0 or ends_at <= starts_at:
            continue
        raw_type = str(
            item.get("category") or item.get("key") or "other_workout"
        )
        exercise_type = WORKOUT_NAMES.get(raw_type, raw_type)
        duration_minutes = max(
            int(duration_seconds // 60),
            int((ends_at - starts_at) // 60),
        )
        observed_at = _item_observed_ms(item, ends_at * 1000)
        source_id = str(
            item.get("time") or f"{starts_at}:{raw_type}"
        )
        drafts.append(
            EventDraft(
                event_id=f"xiaomi-cloud:workout:{source_id}",
                source_record_id=source_id,
                event_type="health.exercise_session",
                occurred_at=starts_at * 1000,
                observed_at=observed_at,
                payload=_event_payload(
                    starts_at=starts_at,
                    ends_at=ends_at,
                    duration_minutes=duration_minutes,
                    exercise_type=exercise_type,
                    exercise_type_name=exercise_type,
                    raw_exercise_type=raw_type,
                    distance_meters=optional_float(
                        payload.get("distance")
                    ),
                    calories_kcal=optional_float(
                        payload.get("calories")
                        or payload.get("total_cal")
                    ),
                    average_heart_rate_bpm=optional_int(
                        payload.get("avg_hrm")
                    ),
                    max_heart_rate_bpm=optional_int(
                        payload.get("max_hrm")
                    ),
                    total_steps=optional_int(
                        payload.get("steps")
                        or payload.get("total_steps")
                    ),
                    data_origin=DATA_ORIGIN,
                ),
                title="小米运动健康云端",
                summary=f"{exercise_type} · {duration_minutes} 分钟",
            )
        )
    return drafts


def _sync_window_event(
    today: date,
    now: datetime,
) -> EventDraft | None:
    window = None
    if 5 <= now.hour < 12:
        window = "morning"
    elif now.hour >= 18:
        window = "evening"
    if window is None:
        return None
    return EventDraft(
        event_id=f"xiaomi-cloud:sync:{today.isoformat()}:{window}",
        source_record_id=f"{today.isoformat()}:{window}",
        event_type="health.sync_window",
        occurred_at=int(now.timestamp() * 1000),
        observed_at=int(now.timestamp() * 1000),
        payload=_event_payload(date=today.isoformat(), window=window),
        title="小米运动健康云端",
        summary=f"健康数据同步检查 · {window}",
    )


def _load_credentials(
    pool: SqlitePool,
) -> tuple[dict[str, Any], bytes]:
    secret_repo = SecretRepo(pool)
    blob = secret_repo.get(CREDENTIALS_KEY)
    if blob is None:
        raise RuntimeError(
            "Xiaomi Health credentials are not configured in Octop secrets"
        )
    return decrypt_credentials(secret_repo, blob), blob


def _store_credentials(
    pool: SqlitePool,
    credentials: dict[str, Any],
) -> None:
    secret_repo = SecretRepo(pool)
    blob = encrypt_credentials(secret_repo, credentials)
    now = int(time.time())
    with pool.transaction() as conn:
        updated = conn.execute(
            "UPDATE secrets SET v = ?, rotated_at = ? WHERE k = ?",
            (blob, now, CREDENTIALS_KEY),
        )
        if updated.rowcount == 0:
            conn.execute(
                "INSERT INTO secrets(k, v, created_at) VALUES (?, ?, ?)",
                (CREDENTIALS_KEY, blob, now),
            )


def _read_last_sync(pool: SqlitePool) -> datetime | None:
    with pool.connect() as conn:
        row = conn.execute(
            "SELECT value FROM settings WHERE key = ?",
            (LAST_SYNC_KEY,),
        ).fetchone()
    if row is None:
        return None
    try:
        parsed = datetime.fromisoformat(str(row["value"]))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=CST)


def _write_last_sync(pool: SqlitePool, value: datetime) -> None:
    with pool.transaction() as conn:
        conn.execute(
            "INSERT INTO settings(key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (LAST_SYNC_KEY, value.isoformat()),
        )


def _upsert_events(
    pool: SqlitePool,
    *,
    agent_id: str,
    user_id: int,
    drafts: Iterable[EventDraft],
) -> int:
    now_ms = int(time.time() * 1000)
    received_at = int(time.time())
    rows = []
    for draft in drafts:
        rows.append(
            (
                agent_id,
                user_id,
                DEVICE_ID,
                draft.event_id,
                SOURCE_TYPE,
                draft.source_record_id,
                draft.event_type,
                draft.occurred_at,
                draft.observed_at or now_ms,
                json.dumps(draft.payload, ensure_ascii=False, separators=(",", ":")),
                PRIVACY_CLASS,
                received_at,
            )
        )
    if not rows:
        return 0
    with pool.transaction() as conn:
        conn.executemany(
            """
            INSERT INTO mobile_events(
                agent_id, user_id, device_id, event_id, source_type,
                source_record_id, event_type, occurred_at, observed_at,
                payload_json, privacy_class, received_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(agent_id, event_id) DO UPDATE SET
                user_id = excluded.user_id,
                device_id = excluded.device_id,
                source_type = excluded.source_type,
                source_record_id = excluded.source_record_id,
                event_type = excluded.event_type,
                occurred_at = excluded.occurred_at,
                observed_at = excluded.observed_at,
                payload_json = excluded.payload_json,
                privacy_class = excluded.privacy_class,
                received_at = excluded.received_at
            """,
            rows,
        )
    return len(rows)


def sync(*, force_full: bool = False, publish_cards: bool = True) -> dict[str, int]:
    db_path = Path(os.environ.get("OCTOP_DB", "/opt/octop/octop.db"))
    pool = SqlitePool(db_path)
    try:
        credentials, _ = _load_credentials(pool)
        agent_id = str(credentials["agent_id"])
        octop_user_id = int(credentials["user_id"])
        now = datetime.now(tz=CST)
        last_sync = _read_last_sync(pool)
        full = force_full or last_sync is None
        lookback_days = DEFAULT_LOOKBACK_DAYS if full else INCREMENTAL_DAYS
        start = _day_start(now.date() - timedelta(days=lookback_days - 1))
        workout_start = (
            start
            if full
            else _day_start(now.date() - timedelta(days=WORKOUT_DAYS - 1))
        )

        with MiHealthCloudClient(
            xiaomi_user_id=str(credentials["xiaomi_user_id"]),
            pass_token=str(credentials["pass_token"]),
            region=str(credentials.get("region") or "cn"),
        ) as client:
            drafts: list[EventDraft] = []
            drafts.extend(_daily_events(client, start, now))
            try:
                drafts.extend(_daily_goal_events(client, start, now))
            except Exception:
                LOG.warning(
                    "Xiaomi daily activity goal is unavailable",
                    exc_info=True,
                )
            drafts.extend(_sleep_events(client, start, now))
            drafts.extend(
                _daily_metric_events(
                    client,
                    "heart_rate",
                    "health.heart_rate_daily",
                    start,
                    now,
                )
            )
            try:
                drafts.extend(
                    _daily_metric_events(
                        client,
                        "resting_heart_rate",
                        "health.resting_heart_rate_daily",
                        start,
                        now,
                    )
                )
            except Exception:
                LOG.warning(
                    "Xiaomi resting heart rate is unavailable",
                    exc_info=True,
                )
            drafts.extend(
                _daily_metric_events(
                    client,
                    "spo2",
                    "health.oxygen_saturation_daily",
                    start,
                    now,
                )
            )
            drafts.extend(
                _daily_metric_events(
                    client,
                    "stress",
                    "health.stress_daily",
                    start,
                    now,
                )
            )
            drafts.extend(_standing_events(client, start, now))
            drafts.extend(_workout_events(client, workout_start, now))
            sync_window = _sync_window_event(now.date(), now)
            if sync_window is not None:
                drafts.append(sync_window)
            if client.pass_token != credentials["pass_token"]:
                credentials["pass_token"] = client.pass_token
                _store_credentials(pool, credentials)

        by_type: dict[str, int] = defaultdict(int)
        for draft in drafts:
            by_type[draft.event_type] += 1
        upserted = _upsert_events(
            pool,
            agent_id=agent_id,
            user_id=octop_user_id,
            drafts=drafts,
        )
        if publish_cards:
            publish_health_today_cards(
                pool,
                agent_id=agent_id,
                user_id=octop_user_id,
                now=now,
            )
        try:
            sync_health_trend_memory(
                pool,
                agent_id=agent_id,
                user_id=octop_user_id,
                now=now,
            )
        except Exception:
            LOG.warning("health trend memory update failed", exc_info=True)
        _write_last_sync(pool, now)
        LOG.info(
            "synced=%d full=%s agent=%s event_types=%s",
            upserted,
            full,
            agent_id,
            dict(sorted(by_type.items())),
        )
        return {"upserted": upserted, **by_type}
    finally:
        pool.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--full",
        action="store_true",
        help="Backfill the configured 30-day window",
    )
    parser.add_argument(
        "--no-cards",
        action="store_true",
        help="Only sync events; do not publish Today cards",
    )
    args = parser.parse_args()
    logging.basicConfig(
        level=logging.WARNING,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    LOG.setLevel(logging.INFO)
    logging.getLogger("health_trend_memory").setLevel(logging.INFO)
    # httpx logs full request URLs at INFO, including signed Xiaomi auth URLs.
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    try:
        sync(force_full=args.full, publish_cards=not args.no_cards)
    except MiHealthAuthError:
        LOG.error("Xiaomi login expired; scan the Mi Health login QR again")
        return 2
    except Exception:
        LOG.exception("Xiaomi Health sync failed")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
