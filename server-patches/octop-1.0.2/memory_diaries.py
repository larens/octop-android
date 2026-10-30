"""Daily work, learning, and agent-growth diaries for the mobile Memory tab.

The upstream Episode extractor deliberately ignores technical work. That is
useful for emotional recall, but it makes the mobile diary read like a short
incident list. This module builds a local-calendar-day diary from the complete
user/assistant conversation stream instead.
"""

from __future__ import annotations

import json
import logging
import re
import uuid
from collections import defaultdict
from datetime import UTC, date, datetime, time, timedelta
from typing import Any
from zoneinfo import ZoneInfo

from octop_memory.domain.datetime import as_utc
from octop_memory.ports.llm._protocol import LLMClientError
from octop_memory.types import DigestRecord

logger = logging.getLogger(__name__)

DIARY_VERSION = "mobile-diary/v2"
DIARY_TZ = ZoneInfo("Asia/Shanghai")

_EMOTIONS = {
    "neutral",
    "happy",
    "sad",
    "angry",
    "anxious",
    "excited",
    "frustrated",
    "grateful",
    "tired",
    "reflective",
}

_PROMPT = """\
You are the user's long-running personal agent, writing a daily diary from the
full conversation record. This is not an emotion-only diary and not a task
log. Help the user recognize what they actually moved forward, what they
learned, and how the agent participated and improved.

Write in Simplified Chinese unless the source is predominantly another
language. Use a warm, observant voice. The diary should feel like someone who
worked alongside the user that day, not like a dashboard report.

For each day with meaningful material:
- title: <= 24 Chinese characters, concrete, no date and no clickbait.
- summary: 3-5 compact paragraphs, ideally 350-800 Chinese characters.
  Cover, when supported by the input:
  1. The main work or life thread and the concrete progress made.
  2. What the user explored, learned, decided, or changed their mind about.
  3. How the agent helped, what it had to understand, and what it learned
     about the user's preferences or working style.
  4. What remains open, risky, or worth following up next time.
  For a sparse day, write one short honest paragraph instead of padding.
- growth: <= 180 Chinese characters describing the agent's own useful lesson:
  a better collaboration pattern, a corrected assumption, or a capability it
  had to acquire. Leave empty if there is no real growth to report.
- emotion: one of neutral, happy, sad, angry, anxious, excited, frustrated,
  grateful, tired, reflective. This is the overall tone, not the user's mood.
- source_count: the number of source excerpts used for that day.

Rules:
- Do not invent tools, outcomes, decisions, numbers, or feelings.
- Technical work, coding, research, writing, planning, and tool use DO belong
  in this diary. Do not discard them merely because they are not emotional.
- Scheduled-task output can be mentioned as work the agent completed, but do
  not expose internal prompts, secrets, tokens, or private URLs.
- Keep personal details only when they are relevant to the day's progress.
- Output JSON only, with no markdown fences or commentary.
- Return at most one entry per date.

OUTPUT SCHEMA:
{{
  "diaries": [
    {{
      "date": "YYYY-MM-DD",
      "title": "...",
      "summary": "...",
      "growth": "...",
      "emotion": "reflective",
      "source_count": 12
    }}
  ]
}}

SOURCE DAYS:
{days_json}
"""


def _clamp_int(raw: Any, *, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(raw)
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(maximum, value))


def _day_start(day: date) -> datetime:
    return datetime.combine(day, time.min, tzinfo=DIARY_TZ).astimezone(UTC)


def _day_end(day: date) -> datetime:
    return _day_start(day + timedelta(days=1))


def _normalise_content(value: Any, limit: int) -> str:
    text = str(value or "").strip()
    text = re.sub(r"\n{3,}", "\n\n", text)
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + "\n[truncated]"


def _pick_events(events: list[Any]) -> list[Any]:
    users = [event for event in events if event.event_type == "user_message"]
    assistants = [event for event in events if event.event_type == "assistant_message"]
    picked = users[:3] + users[-3:] + assistants[:1] + assistants[-3:]
    unique = {event.id: event for event in picked}
    return sorted(unique.values(), key=lambda event: event.timestamp)


def _source_days(memory: Any, *, days: int) -> tuple[list[dict[str, Any]], dict[str, list[Any]]]:
    now = datetime.now(UTC)
    local_today = now.astimezone(DIARY_TZ).date()
    first_day = local_today - timedelta(days=days - 1)
    raw_events = memory.list_raw(
        after=_day_start(first_day),
        before=now,
        limit=10_000,
    )

    grouped: dict[str, list[Any]] = defaultdict(list)
    for event in raw_events:
        if event.event_type not in ("user_message", "assistant_message"):
            continue
        local_day = as_utc(event.timestamp).astimezone(DIARY_TZ).date().isoformat()
        grouped[local_day].append(event)

    total_budget = 64_000
    day_budget = max(2_600, min(9_000, total_budget // max(1, days)))
    source_days: list[dict[str, Any]] = []
    for day_key in sorted(grouped):
        day_events = grouped[day_key]
        sessions: dict[str, list[Any]] = defaultdict(list)
        for event in day_events:
            sessions[event.session_id or "untitled"].append(event)

        session_payloads: list[dict[str, Any]] = []
        used_chars = 0
        ordered_sessions = sorted(
            sessions.items(),
            key=lambda item: max(event.timestamp for event in item[1]),
            reverse=True,
        )
        for session_id, items in ordered_sessions:
            excerpts: list[dict[str, str]] = []
            for event in _pick_events(items):
                content = _normalise_content(event.content, 1_500)
                if not content:
                    continue
                cost = len(content)
                if used_chars + cost > day_budget:
                    break
                excerpts.append(
                    {
                        "time": as_utc(event.timestamp).astimezone(DIARY_TZ).strftime("%H:%M"),
                        "role": "user" if event.event_type == "user_message" else "assistant",
                        "content": content,
                    }
                )
                used_chars += cost
            if excerpts:
                session_payloads.append(
                    {
                        "session": session_id[-8:],
                        "event_count": len(items),
                        "excerpts": excerpts,
                    }
                )
            if used_chars >= day_budget:
                break

        if session_payloads:
            source_days.append(
                {
                    "date": day_key,
                    "event_count": len(day_events),
                    "session_count": len(sessions),
                    "sessions": session_payloads,
                }
            )
    return source_days, grouped


def _strip_json_fence(raw: str) -> str:
    text = raw.strip()
    if text.startswith("```"):
        first_newline = text.find("\n")
        text = text[first_newline + 1 :] if first_newline >= 0 else text
        text = text.removesuffix("```").strip()
    return text


def _fallback_summary(source_day: dict[str, Any]) -> tuple[str, str]:
    user_lines: list[str] = []
    agent_lines: list[str] = []
    for session in source_day.get("sessions", []):
        for excerpt in session.get("excerpts", []):
            content = str(excerpt.get("content") or "").strip()
            if not content:
                continue
            first_line = re.sub(r"\s+", " ", content).strip()
            if len(first_line) > 180:
                first_line = first_line[:180].rstrip() + "…"
            if excerpt.get("role") == "user":
                user_lines.append(first_line)
            else:
                agent_lines.append(first_line)

    paragraphs = []
    if user_lines:
        paragraphs.append("今天主要围绕这些线索展开：" + "；".join(user_lines[:4]) + "。")
    if agent_lines:
        paragraphs.append("协作过程中，我整理并推进了：" + "；".join(agent_lines[-2:]) + "。")
    paragraphs.append("这一天可用的会话记录还不够完整，先保留一条可回看的工作线索。")
    return "\n\n".join(paragraphs), "需要从更完整的会话里继续提炼今天的判断和工作方式。"


def _parse_diaries(
    raw_output: str,
    source_days: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    data = json.loads(_strip_json_fence(raw_output))
    if not isinstance(data, dict) or not isinstance(data.get("diaries"), list):
        raise ValueError("diary output is missing the diaries array")

    by_date = {item["date"]: item for item in source_days}
    entries: list[dict[str, Any]] = []
    seen_dates: set[str] = set()
    for item in data["diaries"]:
        if not isinstance(item, dict):
            continue
        day_key = str(item.get("date") or "").strip()
        source = by_date.get(day_key)
        if source is None or day_key in seen_dates:
            continue
        summary = str(item.get("summary") or "").strip()
        if not summary:
            continue
        emotion = str(item.get("emotion") or "reflective").strip().lower()
        if emotion not in _EMOTIONS:
            emotion = "reflective"
        entries.append(
            {
                "date": day_key,
                "title": str(item.get("title") or "").strip()[:80],
                "summary": summary[:2_400],
                "growth": str(item.get("growth") or "").strip()[:800],
                "emotion": emotion,
                "source_count": int(source.get("event_count") or 0),
                "session_count": int(source.get("session_count") or 0),
            }
        )
        seen_dates.add(day_key)
    return entries


def _generate(
    memory: Any,
    llm: Any,
    *,
    days: int,
) -> list[dict[str, Any]]:
    source_days, _grouped = _source_days(memory, days=days)
    if not source_days:
        return []

    entries: list[dict[str, Any]] = []
    if llm is not None:
        prompt = _PROMPT.format(
            days_json=json.dumps(source_days, ensure_ascii=False, indent=2),
        )
        try:
            raw_output = llm.call_llm(
                prompt,
                tier="heavy",
                temperature=0.25,
                max_tokens=12_000,
                response_format="json",
            )
            entries = _parse_diaries(raw_output, source_days)
        except (LLMClientError, ValueError, TypeError, json.JSONDecodeError):
            logger.warning("daily diary LLM generation failed; using fallback", exc_info=True)
            entries = []
    else:
        logger.warning("daily diary LLM is unavailable; using fallback")

    if not entries:
        entries = []
        for source_day in source_days:
            summary, growth = _fallback_summary(source_day)
            entries.append(
                {
                    "date": source_day["date"],
                    "title": "",
                    "summary": summary,
                    "growth": growth,
                    "emotion": "reflective",
                    "source_count": source_day["event_count"],
                    "session_count": source_day["session_count"],
                }
            )

    now = datetime.now(UTC)
    for entry in entries:
        local_day = date.fromisoformat(entry["date"])
        payload = {
            **entry,
            "version": DIARY_VERSION,
        }
        memory.upsert_digest(
            DigestRecord(
                id=str(uuid.uuid4()),
                period_kind="daily",
                period_key=entry["date"],
                period_start=_day_start(local_day),
                period_end=_day_end(local_day),
                markdown=json.dumps(payload, ensure_ascii=False),
                episode_ids=[],
                llm_version=DIARY_VERSION,
                created_at=now,
                updated_at=now,
            )
        )
    return entries


def _cached(memory: Any, *, days: int, limit: int) -> list[dict[str, Any]]:
    first_day = datetime.now(UTC).astimezone(DIARY_TZ).date() - timedelta(days=days - 1)
    records = memory.list_digests(period_kind="daily", limit=min(200, max(limit * 2, days * 2)))
    items: list[dict[str, Any]] = []
    for record in records:
        if record.llm_version != DIARY_VERSION or record.period_key < first_day.isoformat():
            continue
        try:
            payload = json.loads(record.markdown)
        except (TypeError, json.JSONDecodeError):
            continue
        if not isinstance(payload, dict) or not payload.get("summary"):
            continue
        payload["id"] = record.id
        payload["period_key"] = record.period_key
        payload["occurred_at"] = as_utc(record.period_start).isoformat()
        payload["updated_at"] = as_utc(record.updated_at).isoformat()
        items.append(payload)
    items.sort(key=lambda item: item["period_key"], reverse=True)
    return items[:limit]


def list_memory_diaries(memory: Any, llm: Any, params: dict[str, Any]) -> dict[str, Any]:
    """Return cached daily diaries, generating them on first use or refresh."""
    days = _clamp_int(params.get("days"), default=7, minimum=1, maximum=30)
    limit = _clamp_int(params.get("limit"), default=60, minimum=1, maximum=90)
    refresh = bool(params.get("refresh", False))

    items = _cached(memory, days=days, limit=limit)
    generated = False
    if not items or refresh:
        generated_entries = _generate(memory, llm, days=days)
        generated = bool(generated_entries)
        if generated_entries:
            items = _cached(memory, days=days, limit=limit)
    return {
        "items": items,
        "generated": generated,
        "version": DIARY_VERSION,
    }


__all__ = ["DIARY_VERSION", "list_memory_diaries"]
