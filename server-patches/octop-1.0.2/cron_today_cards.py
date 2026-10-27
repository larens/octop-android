"""Project scheduled-task results into the Today card stream."""

from __future__ import annotations

import hashlib
import json
import logging
import re
import time
from typing import Any
from urllib.parse import urlsplit

from octop.infra.db.pool import DatabasePool
from octop.infra.db.repos.agents import AgentRepo
from octop.infra.today.cards import (
    TodayCardDraft,
    TodayCardPublisher,
    TodayEvidenceInput,
)

logger = logging.getLogger(__name__)

_URL_RE = re.compile(r"https?://[^\s<>\]\)\}]+")
_ITEM_RE = re.compile(r"^(?:[-*+]\s+|\d+[.)、]\s*|\*\*\d+[.、]\s*)")
_MARKDOWN_LINK_RE = re.compile(r"\[([^\]]+)\]\((https?://[^)]+)\)")
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[。；;])")
_TRAILING_URL_PUNCTUATION = ".,;:!?，。；：！？、）)]}>'\"*`"
_TODAY_KIND_BY_TASK_KIND = {"news": "briefing"}
_AUTO_KIND_BY_TODAY_KIND = {"briefing": "news", "inbox": "mail"}


def _compact(value: Any, limit: int = 280) -> str:
    text = " ".join(str(value or "").split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def classify_cron_kind(name: str, prompt: str) -> str:
    """Choose the Today card presentation without asking another model to classify."""
    text = f"{name}\n{prompt}".lower()
    if any(word in text for word in ("新闻", "资讯", "快讯", "简报", "关注领域")):
        return "news"
    if any(word in text for word in ("日程", "会议", "日历")):
        return "calendar"
    if any(word in text for word in ("健康", "运动", "睡眠", "步数", "心率")):
        return "health"
    if any(word in text for word in ("邮件", "收件箱", "消息", "聊天记录")):
        return "inbox"
    if any(word in text for word in ("回顾", "复盘", "总结", "周报", "日报")):
        return "review"
    if any(word in text for word in ("调研", "研究", "追踪", "关注")):
        return "research"
    return "task"


def _clean_inline(value: str) -> str:
    text = _MARKDOWN_LINK_RE.sub(r"\1", value)
    text = _URL_RE.sub("", text)
    text = text.replace("**", "").replace("__", "").replace("`", "")
    text = re.sub(r"^[#>]+\s*", "", text)
    text = _ITEM_RE.sub("", text)
    return _compact(text, 320)


def _summary_candidates(text: str) -> list[str]:
    candidates: list[str] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "|", "```", "---")):
            continue
        if not _ITEM_RE.match(line):
            continue
        cleaned = _clean_inline(line)
        if len(cleaned) < 8:
            continue
        if cleaned.startswith("武老师") and ("运行" in cleaned or "简报" in cleaned):
            continue
        candidates.append(_compact(cleaned, 120))
        if len(candidates) >= 3:
            break
    return candidates


def task_result_summary(text: str, *, task_name: str, status: str, error: str | None) -> str:
    if status == "error":
        reason = _compact(error or "未返回具体错误", 220)
        return f"本次执行失败：{reason}"
    candidates = _summary_candidates(text)
    if candidates:
        return "\n".join(f"• {item}" for item in candidates)
    first_paragraph = next(
        (
            cleaned
            for raw in text.splitlines()
            if (cleaned := _clean_inline(raw))
            and len(cleaned) >= 16
            and not cleaned.startswith("武老师")
        ),
        "",
    )
    return first_paragraph or f"「{task_name}」已执行完成，查看完整结果与来源。"


def _message_content(message_json: str) -> str:
    try:
        payload = json.loads(message_json)
    except (TypeError, json.JSONDecodeError):
        return ""
    if not isinstance(payload, dict):
        return ""
    data = payload.get("data")
    content = data.get("content") if isinstance(data, dict) else payload.get("content")
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        return "".join(
            str(block.get("text") or "")
            if isinstance(block, dict)
            else str(block)
            for block in content
        ).strip()
    return ""


def last_ai_text(db: DatabasePool, thread_id: str) -> str:
    with db.connect() as conn:
        rows = conn.execute(
            "SELECT message_json FROM thread_messages "
            "WHERE thread_id = ? AND role IN ('ai', 'assistant') "
            "ORDER BY seq DESC LIMIT 30",
            (thread_id,),
        ).fetchall()
    for row in rows:
        text = _message_content(str(row["message_json"]))
        if text:
            return text
    return ""


def _clean_url(value: str) -> str:
    return value.rstrip(_TRAILING_URL_PUNCTUATION)


def _web_title(raw_line: str, segment: str) -> str:
    common = _clean_inline(raw_line).split("（", 1)[0].strip(" -—:：")
    label = _clean_inline(segment).split("（", 1)[0].strip(" -—:：")
    label = re.sub(r"[\s→\-—:：;；,，.。()（）]+$", "", label)
    if len(label) < 8:
        label = common
    return _compact(label, 120)


def _web_evidence(text: str, *, occurred_at: int, limit: int = 99) -> list[TodayEvidenceInput]:
    items: list[TodayEvidenceInput] = []
    seen: set[str] = set()
    title_counts: dict[str, int] = {}
    for raw_line in text.splitlines():
        for segment in _SENTENCE_SPLIT_RE.split(raw_line):
            for match in _URL_RE.finditer(segment):
                url = _clean_url(match.group(0))
                if not url or url in seen:
                    continue
                seen.add(url)
                label = _web_title(raw_line, segment)
                if not label:
                    label = urlsplit(url).netloc or url
                title_key = label.lower()
                if title_counts.get(title_key, 0):
                    label = _compact(f"{label} · {urlsplit(url).netloc or '来源'}", 120)
                title_counts[title_key] = title_counts.get(title_key, 0) + 1
                items.append(
                    TodayEvidenceInput(
                        source_type="web",
                        reference=url,
                        url=url,
                        title=label,
                        occurred_at=occurred_at - len(items),
                    )
                )
                if len(items) >= limit:
                    return items
    return items


def _resolved_evidence_rows(
    items: list[TodayEvidenceInput],
    *,
    occurred_at: int,
) -> list[tuple[Any, ...]]:
    rows: list[tuple[Any, ...]] = []
    for item in items:
        if item.source_type == "conversation":
            source_type = "conversation"
            source_record_id = item.reference
            event_type = "conversation.context"
            privacy_class = "private_user_content"
            url = None
        else:
            url = str(item.url or item.reference)
            source_type = "web_search"
            source_record_id = url
            event_type = "web.search_result"
            privacy_class = "public_web"
        evidence_id = hashlib.sha256(
            f"{source_type}|{item.reference}|{url or ''}".encode()
        ).hexdigest()
        rows.append(
            (
                evidence_id,
                source_type,
                source_record_id,
                event_type,
                int(item.occurred_at) if item.occurred_at else occurred_at,
                privacy_class,
                url,
                item.title,
                item.excerpt,
            )
        )
    return rows


def _dismiss_automatic_kind(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    kind: str,
    now: int,
) -> None:
    auto_kind = _AUTO_KIND_BY_TODAY_KIND.get(kind)
    if auto_kind is None:
        return
    with db.transaction() as conn:
        conn.execute(
            "UPDATE today_cards SET lifecycle = 'dismissed', snoozed_until = NULL, updated_at = ? "
            "WHERE agent_id = ? AND user_id = ? AND dedupe_key LIKE ? "
            "AND lifecycle != 'dismissed'",
            (now, agent_id, user_id, f"auto:{auto_kind}:%"),
        )


def has_cron_card_kind(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    kind: str,
) -> bool:
    """Whether an enabled scheduled task owns this category in Today."""
    with db.connect() as conn:
        row = conn.execute(
            "SELECT 1 FROM today_cards c "
            "JOIN cron_jobs j ON j.agent_id = c.agent_id AND j.user_id = c.user_id "
            "AND (c.dedupe_key = 'cron:' || j.cron_id "
            "OR c.dedupe_key = 'agent:cron:' || j.cron_id) "
            "WHERE c.agent_id = ? AND c.user_id = ? AND c.kind = ? AND j.enabled = 1 LIMIT 1",
            (agent_id, user_id, kind),
        ).fetchone()
    return row is not None


def publish_cron_task_card(
    db: DatabasePool,
    *,
    cron_id: str,
    task_name: str,
    prompt: str,
    agent_id: str,
    user_id: int,
    thread_id: str,
    text: str,
    last_run_at: int,
    status: str = "ok",
    error: str | None = None,
    mark_unread: bool = False,
) -> dict[str, Any]:
    """Publish one stable card per task and link it to the canonical run thread."""
    with db.connect() as conn:
        thread = conn.execute(
            "SELECT title FROM threads "
            "WHERE thread_id = ? AND agent_id = ? AND user_id = ?",
            (thread_id, agent_id, user_id),
        ).fetchone()
    if thread is None:
        raise ValueError("cron result thread does not belong to this agent and user")

    task_kind = classify_cron_kind(task_name, prompt)
    kind = _TODAY_KIND_BY_TASK_KIND.get(task_kind, task_kind)
    title = _compact(task_name or thread["title"] or "定时任务", 200)
    summary = task_result_summary(
        text,
        task_name=title,
        status=status,
        error=error,
    )
    evidence = [
        *_web_evidence(text, occurred_at=last_run_at),
        TodayEvidenceInput(
            source_type="conversation",
            reference=thread_id,
            title="定时任务结果",
            excerpt="完整执行记录、过程与来源保留在会话中。",
            occurred_at=last_run_at,
        ),
    ]
    draft = TodayCardDraft(
        dedupe_key=f"cron:{cron_id}",
        kind=kind,
        title=title,
        summary=summary,
        why_now="这是该定时任务最近一次运行的结果，已同步到 Today。",
        action_prompt=(
            f"查看「{title}」的本次执行结果；如需继续处理，请基于现有结果推进，并保留来源。"
        ),
        risk_level="A1",
        evidence=evidence,
        confidence=0.98,
        occurred_at=last_run_at,
    )
    publisher = TodayCardPublisher(db, agent_repo=AgentRepo(db))
    published = publisher.publish(draft, agent_id=agent_id, user_id=user_id)
    card_id = str(published["card_id"])
    action_state = "completed" if status == "ok" else "failed"

    with db.transaction() as conn:
        current = conn.execute(
            "SELECT lifecycle, execution_thread_id, action_state, action_error, action_updated_at "
            "FROM today_cards WHERE agent_id = ? AND card_id = ?",
            (agent_id, card_id),
        ).fetchone()
        assert current is not None
        should_link = (
            str(current["execution_thread_id"] or "") != thread_id
            or str(current["action_state"] or "") != action_state
            or (str(current["action_error"] or "") or None) != (error or None)
            or int(current["action_updated_at"] or 0) != int(last_run_at)
        )
        should_reopen = mark_unread and (
            str(current["lifecycle"]) != "unread"
            or int(current["action_updated_at"] or 0) != int(last_run_at)
        )
        if should_link or should_reopen:
            conn.execute(
                "UPDATE today_cards SET "
                "execution_thread_id = ?, execution_turn_id = NULL, action_state = ?, "
                "action_error = ?, action_updated_at = ?, "
                "lifecycle = CASE WHEN ? THEN 'unread' ELSE lifecycle END, "
                "snoozed_until = CASE WHEN ? THEN NULL ELSE snoozed_until END, "
                "updated_at = ? WHERE agent_id = ? AND card_id = ?",
                (
                    thread_id,
                    action_state,
                    error,
                    last_run_at,
                    1 if should_reopen else 0,
                    1 if should_reopen else 0,
                    int(time.time()) if should_reopen else int(published["updated_at"]),
                    agent_id,
                    card_id,
                ),
            )
        desired_evidence = _resolved_evidence_rows(evidence, occurred_at=last_run_at)
        existing_evidence = [
            (
                str(row["evidence_id"]),
                str(row["source_type"]),
                str(row["source_record_id"] or ""),
                str(row["event_type"]),
                int(row["occurred_at"]),
                str(row["privacy_class"]),
                str(row["url"] or ""),
                str(row["title"] or ""),
                str(row["excerpt"] or ""),
            )
            for row in conn.execute(
                "SELECT evidence_id, source_type, source_record_id, event_type, occurred_at, "
                "privacy_class, url, title, excerpt FROM today_card_evidence "
                "WHERE agent_id = ? AND card_id = ? ORDER BY evidence_id",
                (agent_id, card_id),
            ).fetchall()
        ]
        comparable_existing = sorted([
            (item[0], item[1], item[2], item[3], item[4], item[5], item[6], item[7], item[8] or "")
            for item in existing_evidence
        ])
        comparable_desired = sorted([
            (item[0], item[1], item[2], item[3], item[4], item[5], item[6], item[7] or "", item[8] or "")
            for item in desired_evidence
        ])
        if comparable_existing != comparable_desired:
            conn.execute(
                "DELETE FROM today_card_evidence WHERE agent_id = ? AND card_id = ?",
                (agent_id, card_id),
            )
            created_at = int(time.time())
            for item in desired_evidence:
                conn.execute(
                    "INSERT INTO today_card_evidence("
                    "agent_id, card_id, evidence_id, source_type, source_record_id, "
                    "event_type, occurred_at, privacy_class, url, title, excerpt, created_at"
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (agent_id, card_id, *item, created_at),
                )

    _dismiss_automatic_kind(
        db,
        agent_id=agent_id,
        user_id=user_id,
        kind=kind,
        now=max(int(time.time()), int(last_run_at)),
    )
    return {
        "card_id": card_id,
        "changed": bool(published["changed"]),
        "notify": bool(published["changed"] or should_reopen),
        "kind": kind,
    }


def sync_completed_cron_today_cards(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
) -> list[str]:
    """Backfill cards for completed task runs, including runs from before this patch."""
    with db.connect() as conn:
        rows = conn.execute(
            "SELECT j.cron_id, j.name, j.prompt, j.last_run_at, j.last_status, j.last_error, "
            "t.thread_id FROM cron_jobs j JOIN threads t "
            "ON t.agent_id = j.agent_id AND t.user_id = j.user_id "
            "AND t.session_key = j.session_key "
            "WHERE j.agent_id = ? AND j.user_id = ? AND j.last_run_at IS NOT NULL "
            "AND j.last_status IN ('ok', 'error') ORDER BY j.last_run_at DESC",
            (agent_id, user_id),
        ).fetchall()

    changed: list[str] = []
    for row in rows:
        try:
            thread_id = str(row["thread_id"])
            status = str(row["last_status"])
            text = last_ai_text(db, thread_id)
            if not text and status == "error":
                text = f"执行失败：{row['last_error'] or '未返回具体错误'}"
            if not text:
                logger.warning(
                    "skip Today card for cron=%s: result thread has no assistant text",
                    row["cron_id"],
                )
                continue
            result = publish_cron_task_card(
                db,
                cron_id=str(row["cron_id"]),
                task_name=str(row["name"]),
                prompt=str(row["prompt"]),
                agent_id=agent_id,
                user_id=user_id,
                thread_id=thread_id,
                text=text,
                last_run_at=int(row["last_run_at"]),
                status=status,
                error=str(row["last_error"]) if row["last_error"] else None,
                mark_unread=False,
            )
            if result["changed"]:
                changed.append(str(result["card_id"]))
        except Exception:
            logger.warning(
                "failed to sync Today card for cron=%s",
                row["cron_id"],
                exc_info=True,
            )
    return changed


__all__ = [
    "classify_cron_kind",
    "has_cron_card_kind",
    "last_ai_text",
    "publish_cron_task_card",
    "sync_completed_cron_today_cards",
    "task_result_summary",
]
