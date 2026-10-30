"""Authenticated, idempotent ingestion of user-selected phone data."""

from __future__ import annotations

import hashlib
import json
import time
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Literal

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field, field_validator, model_validator

from octop.api.common.agent import require_agent_owner_row
from octop.api.deps import current_user, get_server
from octop.infra.errors import ErrorCode, OctopError
from octop.infra.interest_monitor import feedback_backoff_seconds, source_domain
from octop.infra.today.automatic import refresh_automatic_today_cards
from octop.infra.today.health_cards import publish_health_today_cards
from octop.infra.today.cron_cards import sync_completed_cron_today_cards

router = APIRouter()


class MobileEventBody(BaseModel):
    event_id: str = Field(min_length=1, max_length=200)
    source_type: Literal[
        "share_inbox", "system_calendar", "health_connect", "xiaomi_health"
    ]
    source_record_id: str | None = Field(default=None, max_length=500)
    event_type: str = Field(min_length=1, max_length=100)
    occurred_at: int
    observed_at: int
    payload: dict[str, Any]
    privacy_class: Literal[
        "private_user_content",
        "private_calendar",
        "sensitive_health",
    ]

    @field_validator("event_id", "event_type")
    @classmethod
    def strip_required_text(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("must not be blank")
        return value


class MobileIngestBody(BaseModel):
    device_id: str = Field(min_length=1, max_length=200)
    events: list[MobileEventBody] = Field(max_length=50)

    @field_validator("device_id")
    @classmethod
    def strip_device_id(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("must not be blank")
        return value


class MobileIngestResponse(BaseModel):
    accepted_event_ids: list[str]
    rejected_event_ids: list[str]


class TodayEvidenceResponse(BaseModel):
    event_id: str | None = None
    source_type: str
    source_record_id: str | None = None
    event_type: str
    occurred_at: int
    privacy_class: str
    url: str | None = None
    title: str | None = None
    excerpt: str | None = None


class TodayCardResponse(BaseModel):
    card_id: str
    kind: str
    title: str
    summary: str
    why_now: str
    confidence: float
    risk_level: str
    lifecycle: str
    occurred_at: int
    snoozed_until: int | None
    action_prompt: str
    created_at: int
    updated_at: int
    evidence: list[TodayEvidenceResponse]
    goal_id: str | None = None
    goal_title: str | None = None
    goal_needs_review: bool = False
    execution_thread_id: str | None = None
    execution_turn_id: str | None = None
    action_state: str | None = None
    action_error: str | None = None
    action_updated_at: int | None = None


class TodaySourceCoverageResponse(BaseModel):
    source_type: Literal[
        "system_calendar",
        "health_connect",
        "share_inbox",
        "interest_web",
    ]
    state: Literal["ok", "stale", "pending", "error", "muted"]
    last_success_at: int | None = None
    next_run_at: int | None = None
    item_count: int = 0
    issue_count: int = 0


class TodayCoverageResponse(BaseModel):
    generated_at: int
    sources: list[TodaySourceCoverageResponse]


class TodayCardPatchBody(BaseModel):
    lifecycle: Literal["unread", "read", "snoozed", "dismissed"]
    snoozed_until: int | None = None

    @model_validator(mode="after")
    def validate_snooze(self) -> TodayCardPatchBody:
        if self.lifecycle == "snoozed":
            if self.snoozed_until is None or self.snoozed_until <= int(time.time()):
                raise ValueError("snoozed_until must be in the future")
        elif self.snoozed_until is not None:
            raise ValueError("snoozed_until is only valid for snoozed cards")
        return self


class TodayCardFeedbackBody(BaseModel):
    feedback_type: Literal["useful", "irrelevant", "too_frequent", "poor_source"]


class TodayCardActionBody(BaseModel):
    state: Literal["prepared", "running", "waiting_confirmation", "completed", "failed"]
    thread_id: str | None = Field(default=None, max_length=200)
    turn_id: str | None = Field(default=None, max_length=200)
    error: str | None = Field(default=None, max_length=2000)

    @model_validator(mode="after")
    def validate_action(self) -> TodayCardActionBody:
        if self.state in {"running", "waiting_confirmation", "completed"} and not self.thread_id:
            raise ValueError("thread_id is required for this action state")
        if self.state != "failed" and self.error is not None:
            raise ValueError("error is only valid for failed actions")
        return self


def _card_id(agent_id: str, dedupe_key: str) -> str:
    digest = hashlib.sha256(f"{agent_id}|{dedupe_key}".encode()).hexdigest()[:24]
    return f"today-{digest}"


def _timestamp_ms(value: int) -> int:
    return value if value > 100_000_000_000 else value * 1000


def _payload_text(payload: dict[str, Any], key: str) -> str:
    value = payload.get(key)
    return str(value).strip() if isinstance(value, (str, int, float)) else ""


def _truncate(value: str, limit: int = 280) -> str:
    compact = " ".join(value.split())
    return compact if len(compact) <= limit else compact[: limit - 1].rstrip() + "…"


def _payload_bool(payload: dict[str, Any], key: str) -> bool:
    value = payload.get(key)
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes"}
    return False


def _payload_int(payload: dict[str, Any], key: str) -> int | None:
    value = payload.get(key)
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _payload_timestamp_ms(payload: dict[str, Any], key: str) -> int | None:
    value = _payload_int(payload, key)
    return _timestamp_ms(value) if value is not None else None


def _local_day_bounds_ms(now: int) -> tuple[int, int]:
    local_now = datetime.fromtimestamp(now, tz=timezone.utc).astimezone()
    day_start = local_now.replace(hour=0, minute=0, second=0, microsecond=0)
    next_day_start = day_start + timedelta(days=1)
    return int(day_start.timestamp() * 1000), int(next_day_start.timestamp() * 1000)


def _calendar_time_range_label(starts_ms: int, ends_ms: int | None) -> str:
    starts_at = datetime.fromtimestamp(starts_ms / 1000, tz=timezone.utc).astimezone()
    start_label = starts_at.strftime("%H:%M")
    if ends_ms is None or ends_ms <= starts_ms:
        return start_label
    ends_at = datetime.fromtimestamp(ends_ms / 1000, tz=timezone.utc).astimezone()
    if ends_at.date() != starts_at.date():
        return start_label
    return f"{start_label}-{ends_at.strftime('%H:%M')}"


def _calendar_card_copy(
    payload: dict[str, Any],
    *,
    starts_ms: int,
    now: int,
) -> tuple[str, str, float]:
    now_ms = now * 1000
    starts_at = datetime.fromtimestamp(starts_ms / 1000, tz=timezone.utc).astimezone()
    now_at = datetime.fromtimestamp(now, tz=timezone.utc).astimezone()
    ends_ms = _payload_timestamp_ms(payload, "ends_at")
    day_delta = (starts_at.date() - now_at.date()).days
    time_label = _calendar_time_range_label(starts_ms, ends_ms)
    date_label = f"{starts_at.month}月{starts_at.day}日 {time_label}"

    organizer = _payload_text(payload, "organizer")
    has_attendee_data = _payload_bool(payload, "has_attendee_data")
    is_organizer = _payload_bool(payload, "is_organizer")
    is_meeting = _payload_bool(payload, "is_meeting") or has_attendee_data or (
        "@" in organizer and not organizer.isdigit()
    )
    is_invitation = _payload_bool(payload, "is_invitation") or (
        is_meeting and not is_organizer and bool(organizer) and not organizer.isdigit()
    )
    event_label = "会议邀请" if is_invitation else ("会议" if is_meeting else "日程")

    if day_delta == 0:
        day_label = "今天"
    elif day_delta == 1:
        day_label = "明天"
    elif day_delta == -1:
        day_label = "昨天"
    else:
        day_label = f"{starts_at.month}月{starts_at.day}日"
    location = _payload_text(payload, "location")
    schedule_label = time_label if day_delta == 0 else f"{day_label} {time_label}"
    summary = f"{schedule_label} · {event_label}"
    if location:
        summary += f" · 地点：{location}"

    if _payload_int(payload, "event_status") == 2:
        why_now = f"这场{event_label}已取消，建议核对是否需要调整安排。"
    elif ends_ms is not None and now_ms >= ends_ms:
        when = f"今天 {time_label}" if day_delta == 0 else date_label
        why_now = f"{when} 的{event_label}已结束，保留作日程记录，不需要现在处理。"
    elif starts_ms <= now_ms:
        when = f"今天 {time_label}" if day_delta == 0 else date_label
        why_now = f"{when} 的{event_label}已开始；建议确认当前是否还需要跟进。"
    elif day_delta == 0:
        minutes = max(1, round((starts_ms - now_ms) / 60_000))
        if minutes <= 120:
            why_now = (
                f"今天 {time_label} 开始，约 {minutes} 分钟后；"
                f"建议现在确认参会信息和材料。"
            )
        else:
            why_now = (
                f"今天 {time_label} 开始，约 {minutes // 60} 小时后；"
                f"建议先确认议程与准备事项。"
            )
    elif day_delta == 1:
        why_now = f"明天 {time_label} 开始；今天可提前确认材料，但不是当前最高优先级。"
    else:
        why_now = (
            f"将在 {day_delta} 天后（{date_label}）发生；"
            f"保留为后续提醒，不需要现在优先处理。"
        )

    confidence = 0.96 if is_meeting else 0.9
    return summary, why_now, confidence


def _calendar_content_key(payload: dict[str, Any], occurred_ms: int) -> tuple[str, str, int, int | None, str]:
    return (
        _payload_text(payload, "calendar_id"),
        _payload_text(payload, "title"),
        _payload_timestamp_ms(payload, "starts_at") or occurred_ms,
        _payload_timestamp_ms(payload, "ends_at"),
        _payload_text(payload, "location"),
    )


def _today_action_prompt(*, kind: str, title: str, summary: str) -> str:
    """Keep the user-visible opener concise; diagnostics stay in the card backend."""
    if kind == "calendar":
        return (
            f"请帮我准备「{title}」（{summary}）。"
            "先列出最值得确认的事项和需要准备的材料；"
            "如需修改日程、发送消息或执行其他写操作，先征得我的确认。"
        )
    if kind == "health":
        return (
            f"请结合「{summary}」和我今天的安排，给出一个克制、可执行的调整建议。"
            "不要作医学诊断。"
        )
    if kind == "inbox":
        return f"请帮我整理「{title}」，提取可以执行的下一步；不要自动发送或删除任何内容。"
    return f"请帮我继续处理「{title}」，先给出最有价值的判断和下一步建议；写操作前先征得我的确认。"


def _today_card_fields(
    event: MobileEventBody,
    *,
    now: int,
) -> tuple[str, str, str, str, str, float] | None:
    now_ms = now * 1000
    occurred_ms = _timestamp_ms(event.occurred_at)
    payload = event.payload
    source_key = event.source_record_id or event.event_id

    if event.source_type == "system_calendar":
        if not now_ms - 12 * 60 * 60 * 1000 <= occurred_ms <= now_ms + 7 * 24 * 60 * 60 * 1000:
            return None
        title = _payload_text(payload, "title") or "近期日程"
        summary, why_now, confidence = _calendar_card_copy(
            payload,
            starts_ms=occurred_ms,
            now=now,
        )
        calendar_uid = _payload_text(payload, "calendar_uid")
        if calendar_uid:
            calendar_event_key = f"{_payload_text(payload, 'calendar_id')}:{calendar_uid}"
        else:
            # Android source IDs end with the start timestamp. Strip it so moving
            # the same event updates one card while preserving evidence revisions.
            calendar_event_key = source_key.rsplit(":", 1)[0] if event.source_record_id else source_key
        dedupe_key = f"calendar:{calendar_event_key}"
        return dedupe_key, "calendar", title, summary, why_now, confidence

    if event.source_type == "share_inbox":
        text = _truncate(_payload_text(payload, "text"))
        attachments = payload.get("attachments")
        attachment_count = len(attachments) if isinstance(attachments, list) else 0
        summary = text or (f"包含 {attachment_count} 个附件" if attachment_count else "一条待整理的分享内容")
        why_now = "这是你主动分享到 Octop、尚未处理的内容。"
        dedupe_key = f"share:{source_key}"
        return dedupe_key, "inbox", "待处理的分享内容", summary, why_now, 1.0

    if event.source_type == "health_connect":
        if not now_ms - 2 * 24 * 60 * 60 * 1000 <= occurred_ms <= now_ms + 60 * 60 * 1000:
            return None
        date = _payload_text(payload, "date") or datetime.fromtimestamp(
            occurred_ms / 1000, tz=timezone.utc
        ).date().isoformat()
        if event.event_type == "health.steps_30d":
            summary = f"{date} · {_payload_text(payload, 'steps') or '—'} 步"
        elif event.event_type == "health.sleep_session":
            start = int(payload.get("starts_at") or event.occurred_at)
            end = int(payload.get("ends_at") or start)
            hours = max(0.0, (_timestamp_ms(end) - _timestamp_ms(start)) / 3_600_000)
            summary = f"{date} · 睡眠 {hours:.1f} 小时"
        else:
            summary = f"{date} · 新增一条运动记录"
        why_now = "这是最近 2 天的健康事实，仅用于生活方式回顾，不构成医学建议。"
        dedupe_key = f"health:{event.event_type}:{source_key}"
        return dedupe_key, "health", "近期健康记录", summary, why_now, 0.85

    return None


def _upsert_today_card(
    conn: Any,
    *,
    agent_id: str,
    user_id: int,
    event: MobileEventBody,
    now: int,
) -> None:
    fields = _today_card_fields(event, now=now)
    if fields is None:
        return
    dedupe_key, kind, title, summary, why_now, confidence = fields
    card_id = _card_id(agent_id, dedupe_key)
    action_prompt = _today_action_prompt(kind=kind, title=title, summary=summary)
    conn.execute(
        "INSERT INTO today_cards("
        "agent_id, card_id, user_id, dedupe_key, kind, title, summary, why_now, "
        "confidence, lifecycle, occurred_at, snoozed_until, action_prompt, created_at, updated_at"
        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'unread', ?, NULL, ?, ?, ?) "
        "ON CONFLICT(agent_id, dedupe_key) DO UPDATE SET "
        "title = excluded.title, summary = excluded.summary, why_now = excluded.why_now, "
        "confidence = excluded.confidence, occurred_at = excluded.occurred_at, "
        "action_prompt = excluded.action_prompt, updated_at = excluded.updated_at",
        (
            agent_id,
            card_id,
            user_id,
            dedupe_key,
            kind,
            title,
            summary,
            why_now,
            confidence,
            event.occurred_at,
            action_prompt,
            now,
            now,
        ),
    )
    conn.execute(
        "INSERT INTO today_evidence(agent_id, card_id, event_id, created_at) "
        "VALUES (?, ?, ?, ?) ON CONFLICT(agent_id, card_id, event_id) DO NOTHING",
        (agent_id, card_id, event.event_id, now),
    )


def _insert_event(
    conn: Any,
    *,
    agent_id: str,
    user_id: int,
    device_id: str,
    event: MobileEventBody,
    received_at: int,
) -> None:
    conn.execute(
        "INSERT INTO mobile_events("
        "agent_id, user_id, device_id, event_id, source_type, source_record_id, "
        "event_type, occurred_at, observed_at, payload_json, privacy_class, received_at"
        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
        "ON CONFLICT(agent_id, event_id) DO NOTHING",
        (
            agent_id,
            user_id,
            device_id,
            event.event_id,
            event.source_type,
            event.source_record_id,
            event.event_type,
            event.occurred_at,
            event.observed_at,
            json.dumps(event.payload, ensure_ascii=False, separators=(",", ":")),
            event.privacy_class,
            received_at,
        ),
    )


def _backfill_today_cards(conn: Any, *, agent_id: str, user_id: int, now: int) -> None:
    rows = conn.execute(
        "SELECT m.* FROM mobile_events m WHERE m.agent_id = ? AND m.user_id = ? "
        "AND NOT EXISTS (SELECT 1 FROM today_evidence e "
        "WHERE e.agent_id = m.agent_id AND e.event_id = m.event_id) "
        "ORDER BY m.received_at DESC LIMIT 500",
        (agent_id, user_id),
    ).fetchall()
    for row in rows:
        try:
            event = MobileEventBody(
                event_id=str(row["event_id"]),
                source_type=str(row["source_type"]),
                source_record_id=row["source_record_id"],
                event_type=str(row["event_type"]),
                occurred_at=int(row["occurred_at"]),
                observed_at=int(row["observed_at"]),
                payload=json.loads(str(row["payload_json"])),
                privacy_class=str(row["privacy_class"]),
            )
        except (TypeError, ValueError, json.JSONDecodeError):
            continue
        _upsert_today_card(conn, agent_id=agent_id, user_id=user_id, event=event, now=now)


def _refresh_calendar_card_copy(
    conn: Any,
    *,
    agent_id: str,
    user_id: int,
    now: int,
) -> None:
    """Refresh relative date copy without changing card order or read state."""
    rows = conn.execute(
        "SELECT card_id, title, occurred_at FROM today_cards "
        "WHERE agent_id = ? AND user_id = ? AND kind = 'calendar'",
        (agent_id, user_id),
    ).fetchall()
    for row in rows:
        evidence = conn.execute(
            "SELECT m.payload_json FROM today_evidence e "
            "JOIN mobile_events m ON m.agent_id = e.agent_id AND m.event_id = e.event_id "
            "WHERE e.agent_id = ? AND e.card_id = ? AND m.source_type = 'system_calendar' "
            "ORDER BY m.received_at DESC, e.created_at DESC LIMIT 1",
            (agent_id, row["card_id"]),
        ).fetchone()
        if evidence is None:
            continue
        try:
            payload = json.loads(str(evidence["payload_json"]))
        except (TypeError, ValueError, json.JSONDecodeError):
            continue
        starts_ms = _payload_timestamp_ms(payload, "starts_at") or int(row["occurred_at"])
        summary, why_now, confidence = _calendar_card_copy(
            payload,
            starts_ms=starts_ms,
            now=now,
        )
        action_prompt = _today_action_prompt(
            kind="calendar",
            title=str(row["title"]),
            summary=summary,
        )
        conn.execute(
            "UPDATE today_cards SET summary = ?, why_now = ?, confidence = ?, action_prompt = ? "
            "WHERE agent_id = ? AND user_id = ? AND card_id = ?",
            (summary, why_now, confidence, action_prompt, agent_id, user_id, row["card_id"]),
        )


def _merge_duplicate_calendar_cards(
    conn: Any,
    *,
    agent_id: str,
    user_id: int,
) -> None:
    """Merge legacy provider-ID duplicates without deleting evidence or feedback."""
    rows = conn.execute(
        "SELECT card_id, lifecycle, summary, occurred_at, updated_at FROM today_cards "
        "WHERE agent_id = ? AND user_id = ? AND kind = 'calendar' AND lifecycle != 'dismissed'",
        (agent_id, user_id),
    ).fetchall()
    groups: dict[tuple[str, str, int, int | None, str], list[dict[str, Any]]] = {}
    for row in rows:
        evidence = conn.execute(
            "SELECT m.payload_json FROM today_evidence e "
            "JOIN mobile_events m ON m.agent_id = e.agent_id AND m.event_id = e.event_id "
            "WHERE e.agent_id = ? AND e.card_id = ? AND m.source_type = 'system_calendar' "
            "ORDER BY m.received_at DESC, e.created_at DESC LIMIT 1",
            (agent_id, row["card_id"]),
        ).fetchone()
        if evidence is None:
            continue
        try:
            payload = json.loads(str(evidence["payload_json"]))
        except (TypeError, ValueError, json.JSONDecodeError):
            continue
        key = _calendar_content_key(payload, int(row["occurred_at"]))
        groups.setdefault(key, []).append(dict(row))

    for duplicates in groups.values():
        if len(duplicates) < 2:
            continue
        canonical = max(
            duplicates,
            key=lambda item: (
                str(item["lifecycle"]) == "unread",
                "近期日程" not in str(item["summary"]),
                int(item["updated_at"]),
                str(item["card_id"]),
            ),
        )
        canonical_id = str(canonical["card_id"])
        for duplicate in duplicates:
            duplicate_id = str(duplicate["card_id"])
            if duplicate_id == canonical_id:
                continue
            evidence_rows = conn.execute(
                "SELECT event_id, created_at FROM today_evidence "
                "WHERE agent_id = ? AND card_id = ?",
                (agent_id, duplicate_id),
            ).fetchall()
            for evidence in evidence_rows:
                conn.execute(
                    "INSERT INTO today_evidence(agent_id, card_id, event_id, created_at) "
                    "VALUES (?, ?, ?, ?) ON CONFLICT(agent_id, card_id, event_id) DO NOTHING",
                    (agent_id, canonical_id, evidence["event_id"], evidence["created_at"]),
                )
            conn.execute(
                "UPDATE today_cards SET lifecycle = 'dismissed', snoozed_until = NULL "
                "WHERE agent_id = ? AND user_id = ? AND card_id = ?",
                (agent_id, user_id, duplicate_id),
            )


_MOBILE_FRESHNESS_SECONDS = {
    "system_calendar": 36 * 60 * 60,
    "health_connect": 72 * 60 * 60,
    "share_inbox": 30 * 24 * 60 * 60,
}


def _today_source_coverage(
    conn: Any,
    *,
    agent_id: str,
    user_id: int,
    now: int,
) -> list[TodaySourceCoverageResponse]:
    sources: list[TodaySourceCoverageResponse] = []
    recent_since = now - 30 * 24 * 60 * 60
    mobile_rows = conn.execute(
        "SELECT source_type, MAX(received_at) AS last_success_at, "
        "SUM(CASE WHEN received_at >= ? THEN 1 ELSE 0 END) AS item_count "
        "FROM mobile_events WHERE agent_id = ? AND user_id = ? "
        "GROUP BY source_type",
        (recent_since, agent_id, user_id),
    ).fetchall()
    for row in mobile_rows:
        source_type = str(row["source_type"])
        if source_type not in _MOBILE_FRESHNESS_SECONDS:
            continue
        last_success_at = int(row["last_success_at"])
        stale = now - last_success_at > _MOBILE_FRESHNESS_SECONDS[source_type]
        sources.append(
            TodaySourceCoverageResponse(
                source_type=source_type,
                state="stale" if stale else "ok",
                last_success_at=last_success_at,
                item_count=int(row["item_count"] or 0),
            )
        )

    monitor_rows = conn.execute(
        "SELECT last_run_at, last_status, next_run_at, delivery_backoff_until "
        "FROM interest_monitors WHERE agent_id = ? AND user_id = ? AND enabled = 1",
        (agent_id, user_id),
    ).fetchall()
    if monitor_rows:
        successful = [
            int(row["last_run_at"])
            for row in monitor_rows
            if row["last_run_at"] is not None and row["last_status"] == "ok"
        ]
        next_runs = [
            int(row["next_run_at"])
            for row in monitor_rows
            if row["next_run_at"] is not None
        ]
        error_count = sum(1 for row in monitor_rows if row["last_status"] == "error")
        muted_count = sum(
            1
            for row in monitor_rows
            if row["delivery_backoff_until"] is not None
            and int(row["delivery_backoff_until"]) > now
        )
        issue_count = sum(
            1
            for row in monitor_rows
            if row["last_status"] == "error"
            or (
                row["delivery_backoff_until"] is not None
                and int(row["delivery_backoff_until"]) > now
            )
        )
        last_success_at = max(successful) if successful else None
        if muted_count == len(monitor_rows):
            state = "muted"
        elif error_count:
            state = "error"
        elif last_success_at is None:
            state = "pending"
        elif now - last_success_at > 48 * 60 * 60:
            state = "stale"
        else:
            state = "ok"
        sources.append(
            TodaySourceCoverageResponse(
                source_type="interest_web",
                state=state,
                last_success_at=last_success_at,
                next_run_at=min(next_runs) if next_runs else None,
                item_count=len(monitor_rows),
                issue_count=issue_count,
            )
        )

    order = {
        "system_calendar": 0,
        "health_connect": 1,
        "share_inbox": 2,
        "interest_web": 3,
    }
    return sorted(sources, key=lambda item: order[item.source_type])


def _evidence_by_card(conn: Any, agent_id: str, card_ids: list[str]) -> dict[str, list[TodayEvidenceResponse]]:
    evidence: dict[str, list[TodayEvidenceResponse]] = {card_id: [] for card_id in card_ids}
    if not card_ids:
        return evidence
    placeholders = ",".join("?" for _ in card_ids)
    rows = conn.execute(
        "SELECT e.card_id, m.event_id, m.source_type, m.source_record_id, m.event_type, "
        "m.occurred_at, m.privacy_class FROM today_evidence e "
        "JOIN mobile_events m ON m.agent_id = e.agent_id AND m.event_id = e.event_id "
        f"WHERE e.agent_id = ? AND e.card_id IN ({placeholders}) ORDER BY e.created_at DESC",
        (agent_id, *card_ids),
    ).fetchall()
    for row in rows:
        evidence[str(row["card_id"])].append(
            TodayEvidenceResponse(
                event_id=str(row["event_id"]),
                source_type=str(row["source_type"]),
                source_record_id=row["source_record_id"],
                event_type=str(row["event_type"]),
                occurred_at=int(row["occurred_at"]),
                privacy_class=str(row["privacy_class"]),
            )
        )
    web_rows = conn.execute(
        "SELECT card_id, fingerprint, url, title, excerpt, published_at, observed_at "
        f"FROM today_web_evidence WHERE agent_id = ? AND card_id IN ({placeholders}) "
        "ORDER BY observed_at DESC",
        (agent_id, *card_ids),
    ).fetchall()
    for row in web_rows:
        evidence[str(row["card_id"])].append(
            TodayEvidenceResponse(
                source_type="web_search",
                source_record_id=str(row["fingerprint"]),
                event_type="web.search_result",
                occurred_at=int(row["published_at"] or row["observed_at"]),
                privacy_class="public_web",
                url=str(row["url"]),
                title=str(row["title"]),
                excerpt=str(row["excerpt"]),
            )
        )
    tool_rows = conn.execute(
        "SELECT card_id, evidence_id, source_type, source_record_id, event_type, "
        "occurred_at, privacy_class, url, title, excerpt "
        f"FROM today_card_evidence WHERE agent_id = ? AND card_id IN ({placeholders}) "
        "ORDER BY created_at DESC, occurred_at DESC",
        (agent_id, *card_ids),
    ).fetchall()
    for row in tool_rows:
        evidence[str(row["card_id"])].append(
            TodayEvidenceResponse(
                event_id=None,
                source_type=str(row["source_type"]),
                source_record_id=str(row["source_record_id"] or row["evidence_id"]),
                event_type=str(row["event_type"]),
                occurred_at=int(row["occurred_at"]),
                privacy_class=str(row["privacy_class"]),
                url=str(row["url"]) if row["url"] is not None else None,
                title=str(row["title"]) if row["title"] is not None else None,
                excerpt=str(row["excerpt"]) if row["excerpt"] is not None else None,
            )
        )
    return evidence


def _cards_from_rows(conn: Any, agent_id: str, rows: list[Any]) -> list[TodayCardResponse]:
    card_ids = [str(row["card_id"]) for row in rows]
    evidence = _evidence_by_card(conn, agent_id, card_ids)
    goal_ids = {str(row["goal_id"]) for row in rows if "goal_id" in row.keys() and row["goal_id"] is not None}
    goals: dict[str, Any] = {}
    if goal_ids:
        placeholders = ",".join("?" for _ in goal_ids)
        goals = {
            str(goal["goal_id"]): goal
            for goal in conn.execute(
                f"SELECT goal_id, title, review_at FROM today_goals WHERE goal_id IN ({placeholders})",
                tuple(goal_ids),
            ).fetchall()
        }
    now = int(time.time())
    return [
        TodayCardResponse(
            card_id=str(row["card_id"]),
            kind=str(row["kind"]),
            title=str(row["title"]),
            summary=str(row["summary"]),
            why_now=str(row["why_now"]),
            confidence=float(row["confidence"]),
            risk_level=(
                str(row["risk_level"])
                if "risk_level" in row.keys() and row["risk_level"] is not None
                else "A2"
            ),
            lifecycle=str(row["lifecycle"]),
            occurred_at=int(row["occurred_at"]),
            snoozed_until=int(row["snoozed_until"]) if row["snoozed_until"] is not None else None,
            action_prompt=str(row["action_prompt"]),
            created_at=int(row["created_at"]),
            updated_at=int(row["updated_at"]),
            evidence=evidence[str(row["card_id"])],
            goal_id=str(row["goal_id"]) if "goal_id" in row.keys() and row["goal_id"] is not None else None,
            goal_title=(
                str(goals[str(row["goal_id"])]["title"])
                if "goal_id" in row.keys() and row["goal_id"] is not None and str(row["goal_id"]) in goals
                else None
            ),
            goal_needs_review=(
                goals[str(row["goal_id"])]["review_at"] is not None
                and int(goals[str(row["goal_id"])]["review_at"]) <= now
                if "goal_id" in row.keys() and row["goal_id"] is not None and str(row["goal_id"]) in goals
                else False
            ),
            execution_thread_id=(
                str(row["execution_thread_id"])
                if "execution_thread_id" in row.keys() and row["execution_thread_id"] is not None
                else None
            ),
            execution_turn_id=(
                str(row["execution_turn_id"])
                if "execution_turn_id" in row.keys() and row["execution_turn_id"] is not None
                else None
            ),
            action_state=(
                str(row["action_state"])
                if "action_state" in row.keys() and row["action_state"] is not None
                else None
            ),
            action_error=(
                str(row["action_error"])
                if "action_error" in row.keys() and row["action_error"] is not None
                else None
            ),
            action_updated_at=(
                int(row["action_updated_at"])
                if "action_updated_at" in row.keys() and row["action_updated_at"] is not None
                else None
            ),
        )
        for row in rows
    ]


def _reconcile_today_actions(conn: Any, *, server: Any, agent_id: str, user_id: int, now: int) -> None:
    rows = conn.execute(
        "SELECT card_id, execution_thread_id, action_state FROM today_cards "
        "WHERE agent_id = ? AND user_id = ? AND execution_thread_id IS NOT NULL "
        "AND action_state IN ('prepared', 'running', 'waiting_confirmation')",
        (agent_id, user_id),
    ).fetchall()
    if not rows:
        return
    gateway = server.app_runtime.gateway
    awaiting = gateway.processor.hitl_coordinator.store.pending_thread_ids(
        agent_id=agent_id,
        user_id=user_id,
    )
    for row in rows:
        thread_id = str(row["execution_thread_id"])
        state = "waiting_confirmation" if thread_id in awaiting else (
            "running" if gateway.ws_hub.is_turn_active(thread_id) else None
        )
        if state is not None and state != str(row["action_state"]):
            conn.execute(
                "UPDATE today_cards SET action_state = ?, action_error = NULL, action_updated_at = ?, "
                "updated_at = ? WHERE agent_id = ? AND card_id = ?",
                (state, now, now, agent_id, str(row["card_id"])),
            )


@router.post(
    "/agents/{agent_id}/mobile/ingest",
    response_model=MobileIngestResponse,
    summary="Ingest phone data events",
)
async def ingest_mobile_events(
    agent_id: str,
    body: MobileIngestBody,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> MobileIngestResponse:
    require_agent_owner_row(agent_id, user=user, as_user=None, server=server)
    assert server.services is not None
    now = int(time.time())
    with server.services.db.transaction() as conn:
        conn.execute(
            "INSERT INTO mobile_devices(agent_id, user_id, device_id, first_seen_at, last_seen_at) "
            "VALUES (?, ?, ?, ?, ?) "
            "ON CONFLICT(agent_id, device_id) DO UPDATE SET last_seen_at = excluded.last_seen_at",
            (agent_id, user.id, body.device_id, now, now),
        )
        for event in body.events:
            _insert_event(
                conn,
                agent_id=agent_id,
                user_id=user.id,
                device_id=body.device_id,
                event=event,
                received_at=now,
            )
            _upsert_today_card(
                conn,
                agent_id=agent_id,
                user_id=user.id,
                event=event,
                now=now,
            )
    return MobileIngestResponse(
        accepted_event_ids=[event.event_id for event in body.events],
        rejected_event_ids=[],
    )


@router.get(
    "/agents/{agent_id}/today/cards",
    response_model=list[TodayCardResponse],
    summary="List actionable Today cards",
)
async def list_today_cards(
    agent_id: str,
    limit: int = Query(default=50, ge=1, le=100),
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> list[TodayCardResponse]:
    require_agent_owner_row(agent_id, user=user, as_user=None, server=server)
    assert server.services is not None
    now = int(time.time())
    now_ms = now * 1000
    _, tomorrow_start_ms = _local_day_bounds_ms(now)
    seven_day_end_ms = now_ms + 7 * 24 * 60 * 60 * 1000
    sync_completed_cron_today_cards(
        server.services.db,
        agent_id=agent_id,
        user_id=user.id,
    )
    await refresh_automatic_today_cards(
        server.services.db,
        agent_id=agent_id,
        user_id=user.id,
        now=now,
    )
    publish_health_today_cards(
        server.services.db,
        agent_id=agent_id,
        user_id=user.id,
        now=now,
    )
    with server.services.db.transaction() as conn:
        _backfill_today_cards(conn, agent_id=agent_id, user_id=user.id, now=now)
        _refresh_calendar_card_copy(conn, agent_id=agent_id, user_id=user.id, now=now)
        _merge_duplicate_calendar_cards(conn, agent_id=agent_id, user_id=user.id)
        _reconcile_today_actions(conn, server=server, agent_id=agent_id, user_id=user.id, now=now)
        conn.execute(
            "UPDATE today_cards SET lifecycle = 'read', snoozed_until = NULL, updated_at = ? "
            "WHERE agent_id = ? AND user_id = ? AND lifecycle = 'snoozed' AND snoozed_until <= ?",
            (now, agent_id, user.id, now),
        )
        rows = conn.execute(
            "SELECT * FROM today_cards WHERE agent_id = ? AND user_id = ? "
            "AND lifecycle != 'dismissed' "
            "AND (lifecycle != 'snoozed' OR snoozed_until <= ?) "
            "ORDER BY CASE "
            "WHEN kind = 'calendar' AND occurred_at >= ? AND occurred_at < ? THEN 0 "
            "WHEN lifecycle = 'unread' AND kind != 'calendar' THEN 1 "
            "WHEN kind = 'calendar' AND occurred_at >= ? AND occurred_at < ? THEN 2 "
            "WHEN kind = 'calendar' AND occurred_at >= ? THEN 3 "
            "ELSE 4 END, "
            "CASE WHEN kind = 'calendar' AND occurred_at >= ? THEN occurred_at END ASC, "
            "updated_at DESC LIMIT ?",
            (
                agent_id,
                user.id,
                now,
                now_ms,
                tomorrow_start_ms,
                tomorrow_start_ms,
                seven_day_end_ms,
                now_ms,
                now_ms,
                limit,
            ),
        ).fetchall()
        return _cards_from_rows(conn, agent_id, list(rows))


@router.get(
    "/agents/{agent_id}/today/sources",
    response_model=TodayCoverageResponse,
    summary="Show freshness and coverage for active Today sources",
)
async def get_today_source_coverage(
    agent_id: str,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> TodayCoverageResponse:
    require_agent_owner_row(agent_id, user=user, as_user=None, server=server)
    assert server.services is not None
    now = int(time.time())
    with server.services.db.connect() as conn:
        sources = _today_source_coverage(
            conn,
            agent_id=agent_id,
            user_id=user.id,
            now=now,
        )
    return TodayCoverageResponse(generated_at=now, sources=sources)


@router.patch(
    "/agents/{agent_id}/today/cards/{card_id}",
    response_model=TodayCardResponse,
    summary="Update a Today card lifecycle",
)
async def patch_today_card(
    agent_id: str,
    card_id: str,
    body: TodayCardPatchBody,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> TodayCardResponse:
    require_agent_owner_row(agent_id, user=user, as_user=None, server=server)
    assert server.services is not None
    now = int(time.time())
    snoozed_until = body.snoozed_until if body.lifecycle == "snoozed" else None
    with server.services.db.transaction() as conn:
        cursor = conn.execute(
            "UPDATE today_cards SET lifecycle = ?, snoozed_until = ?, updated_at = ? "
            "WHERE agent_id = ? AND user_id = ? AND card_id = ?",
            (body.lifecycle, snoozed_until, now, agent_id, user.id, card_id),
        )
        if cursor.rowcount != 1:
            raise OctopError(ErrorCode.NOT_FOUND, "Today card not found")
        row = conn.execute(
            "SELECT * FROM today_cards WHERE agent_id = ? AND card_id = ?",
            (agent_id, card_id),
        ).fetchone()
        assert row is not None
        return _cards_from_rows(conn, agent_id, [row])[0]


@router.patch(
    "/agents/{agent_id}/today/cards/{card_id}/action",
    response_model=TodayCardResponse,
    summary="Record a Today card execution state",
)
async def patch_today_card_action(
    agent_id: str,
    card_id: str,
    body: TodayCardActionBody,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> TodayCardResponse:
    require_agent_owner_row(agent_id, user=user, as_user=None, server=server)
    assert server.services is not None
    now = int(time.time())
    with server.services.db.transaction() as conn:
        row = conn.execute(
            "SELECT * FROM today_cards WHERE agent_id = ? AND user_id = ? AND card_id = ?",
            (agent_id, user.id, card_id),
        ).fetchone()
        if row is None:
            raise OctopError(ErrorCode.NOT_FOUND, "Today card not found")
        if body.thread_id is not None:
            thread = conn.execute(
                "SELECT thread_id FROM threads WHERE thread_id = ? AND agent_id = ? AND user_id = ?",
                (body.thread_id, agent_id, user.id),
            ).fetchone()
            if thread is None:
                raise OctopError(ErrorCode.NOT_FOUND, "Execution thread not found")
        thread_id = body.thread_id
        if body.state != "prepared" and thread_id is None and row["execution_thread_id"] is not None:
            thread_id = str(row["execution_thread_id"])
        conn.execute(
            "UPDATE today_cards SET execution_thread_id = ?, execution_turn_id = ?, action_state = ?, "
            "action_error = ?, action_updated_at = ?, updated_at = ? "
            "WHERE agent_id = ? AND user_id = ? AND card_id = ?",
            (
                thread_id,
                body.turn_id,
                body.state,
                body.error if body.state == "failed" else None,
                now,
                now,
                agent_id,
                user.id,
                card_id,
            ),
        )
        updated = conn.execute(
            "SELECT * FROM today_cards WHERE agent_id = ? AND user_id = ? AND card_id = ?",
            (agent_id, user.id, card_id),
        ).fetchone()
        assert updated is not None
        return _cards_from_rows(conn, agent_id, [updated])[0]


@router.post(
    "/agents/{agent_id}/today/cards/{card_id}/feedback",
    response_model=TodayCardResponse,
    summary="Record structured feedback for a Today card",
)
async def create_today_card_feedback(
    agent_id: str,
    card_id: str,
    body: TodayCardFeedbackBody,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> TodayCardResponse:
    require_agent_owner_row(agent_id, user=user, as_user=None, server=server)
    assert server.services is not None
    now = int(time.time())
    with server.services.db.transaction() as conn:
        row = conn.execute(
            "SELECT * FROM today_cards WHERE agent_id = ? AND user_id = ? AND card_id = ?",
            (agent_id, user.id, card_id),
        ).fetchone()
        if row is None:
            raise OctopError(ErrorCode.NOT_FOUND, "Today card not found")
        conn.execute(
            "INSERT INTO today_card_feedback("
            "feedback_id, agent_id, card_id, user_id, feedback_type, created_at"
            ") VALUES (?, ?, ?, ?, ?, ?)",
            (uuid.uuid4().hex, agent_id, card_id, user.id, body.feedback_type, now),
        )
        linked_monitors = conn.execute(
            "SELECT DISTINCT m.monitor_id, m.delivery_backoff_until, m.feedback_backoff_level "
            "FROM today_web_evidence w JOIN interest_monitors m ON m.monitor_id = w.monitor_id "
            "WHERE w.agent_id = ? AND w.card_id = ? AND m.user_id = ?",
            (agent_id, card_id, user.id),
        ).fetchall()
        if body.feedback_type == "useful":
            for monitor in linked_monitors:
                conn.execute(
                    "UPDATE interest_monitors SET delivery_backoff_until = NULL, "
                    "feedback_backoff_reason = NULL, feedback_backoff_level = 0, updated_at = ? "
                    "WHERE monitor_id = ?",
                    (now, monitor["monitor_id"]),
                )
        elif body.feedback_type == "too_frequent":
            for monitor in linked_monitors:
                level = int(monitor["feedback_backoff_level"] or 0) + 1
                backoff_until = max(
                    int(monitor["delivery_backoff_until"] or 0),
                    now + feedback_backoff_seconds(body.feedback_type, level=level),
                )
                conn.execute(
                    "UPDATE interest_monitors SET delivery_backoff_until = ?, "
                    "feedback_backoff_reason = ?, feedback_backoff_level = ?, updated_at = ? "
                    "WHERE monitor_id = ?",
                    (backoff_until, body.feedback_type, level, now, monitor["monitor_id"]),
                )
            feedback_count = conn.execute(
                "SELECT COUNT(*) FROM today_card_feedback "
                "WHERE agent_id = ? AND card_id = ? AND user_id = ? "
                "AND feedback_type = 'too_frequent'",
                (agent_id, card_id, user.id),
            ).fetchone()[0]
            card_backoff_until = now + feedback_backoff_seconds(
                body.feedback_type,
                level=min(max(int(feedback_count), 1), 2),
            )
            conn.execute(
                "UPDATE today_cards SET lifecycle = 'snoozed', snoozed_until = ?, "
                "updated_at = ? WHERE agent_id = ? AND card_id = ?",
                (card_backoff_until, now, agent_id, card_id),
            )
        elif body.feedback_type == "irrelevant":
            for monitor in linked_monitors:
                level = int(monitor["feedback_backoff_level"] or 0) + 1
                conn.execute(
                    "UPDATE interest_monitors SET enabled = 0, next_run_at = NULL, "
                    "delivery_backoff_until = NULL, feedback_backoff_reason = ?, "
                    "feedback_backoff_level = ?, updated_at = ? "
                    "WHERE monitor_id = ?",
                    ("irrelevant", level, now, monitor["monitor_id"]),
                )
        elif body.feedback_type == "poor_source":
            domains = {
                source_domain(str(item["url"]))
                for item in conn.execute(
                    "SELECT DISTINCT url FROM today_web_evidence "
                    "WHERE agent_id = ? AND card_id = ?",
                    (agent_id, card_id),
                ).fetchall()
            }
            for domain in sorted(item for item in domains if item):
                preference = conn.execute(
                    "SELECT poor_source_count, muted_until FROM interest_source_preferences "
                    "WHERE agent_id = ? AND user_id = ? AND domain = ?",
                    (agent_id, user.id, domain),
                ).fetchone()
                count = int(preference["poor_source_count"] or 0) + 1 if preference is not None else 1
                muted_until = max(
                    int(preference["muted_until"] or 0) if preference is not None else 0,
                    now + feedback_backoff_seconds("poor_source", level=count),
                )
                conn.execute(
                    "INSERT INTO interest_source_preferences("
                    "agent_id, user_id, domain, poor_source_count, muted_until, updated_at"
                    ") VALUES (?, ?, ?, ?, ?, ?) "
                    "ON CONFLICT(agent_id, user_id, domain) DO UPDATE SET "
                    "poor_source_count = excluded.poor_source_count, "
                    "muted_until = excluded.muted_until, updated_at = excluded.updated_at",
                    (agent_id, user.id, domain, count, muted_until, now),
                )
        if body.feedback_type in {"too_frequent", "irrelevant"}:
            if body.feedback_type == "irrelevant":
                conn.execute(
                    "UPDATE today_cards SET lifecycle = 'dismissed', snoozed_until = NULL, "
                    "updated_at = ? WHERE agent_id = ? AND card_id = ?",
                    (now, agent_id, card_id),
                )
            row = conn.execute(
                "SELECT * FROM today_cards WHERE agent_id = ? AND card_id = ?",
                (agent_id, card_id),
            ).fetchone()
            assert row is not None
        return _cards_from_rows(conn, agent_id, [row])[0]
