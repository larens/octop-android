"""Deterministic Today aggregation for connected read-only sources."""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from typing import Any

from octop.infra.connectors.crypto import decrypt_credentials
from octop.infra.connectors.gateway.registry import call_gateway_tool
from octop.infra.db.pool import DatabasePool
from octop.infra.db.repos.connectors import ConnectorRepo
from octop.infra.db.repos.secrets import SecretRepo
from octop.infra.today.cron_cards import cron_result_has_new_information, has_cron_card_kind

logger = logging.getLogger(__name__)

_REFRESH_TTL_SECONDS = 20 * 60
_CONVERSATION_WINDOW_SECONDS = 2 * 24 * 60 * 60
_MAX_CONVERSATIONS = 3
_MAX_MAIL = 8
_MAX_NEWS = 8
_DEFAULT_NEWS_QUERIES = ("AI Agent", "云计算 数据库", "网络安全")
_LOW_VALUE_MAIL = re.compile(
    r"(newsletter|promotion|promo|marketing|1[- ]for[- ]1|payday|discount|"
    r"sale|coupon|unsubscribe|优惠|促销|限时|折扣|广告)",
    re.IGNORECASE,
)
_LOCKS: dict[tuple[str, int], asyncio.Lock] = {}


@dataclass(frozen=True)
class _Evidence:
    source_type: str
    source_record_id: str
    event_type: str
    occurred_at: int
    privacy_class: str
    title: str | None
    excerpt: str | None = None
    url: str | None = None


@dataclass(frozen=True)
class _Card:
    dedupe_key: str
    kind: str
    title: str
    summary: str
    why_now: str
    action_prompt: str
    occurred_at: int
    evidence: tuple[_Evidence, ...]
    risk_level: str = "A2"
    confidence: float = 0.9


@dataclass(frozen=True)
class _SourceResult:
    cards: tuple[_Card, ...]
    loaded: bool


def _compact(value: Any, limit: int = 280) -> str:
    text = " ".join(str(value or "").split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def _timestamp_ms(value: Any, *, fallback: int) -> int:
    try:
        raw = int(value)
    except (TypeError, ValueError):
        return fallback * 1000
    if raw <= 0:
        return fallback * 1000
    return raw if raw > 100_000_000_000 else raw * 1000


def _mail_timestamp(value: Any, *, fallback: int) -> int:
    try:
        parsed = parsedate_to_datetime(str(value or ""))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp())
    except (TypeError, ValueError, OverflowError):
        return fallback


def _card_id(agent_id: str, dedupe_key: str) -> str:
    digest = hashlib.sha256(f"{agent_id}|{dedupe_key}".encode()).hexdigest()[:24]
    return f"today-auto-{digest}"


def _evidence_id(item: _Evidence) -> str:
    identity = "|".join(
        (
            item.source_type,
            item.source_record_id,
            item.event_type,
            item.url or "",
            item.title or "",
        )
    )
    return hashlib.sha256(identity.encode()).hexdigest()


def _today_key(now: int) -> str:
    return datetime.fromtimestamp(now, tz=timezone.utc).astimezone().date().isoformat()


def _is_fresh(
    existing: dict[str, int],
    *,
    kind: str,
    now: int,
) -> bool:
    updated_at = existing.get(kind)
    return updated_at is not None and now - updated_at < _REFRESH_TTL_SECONDS


def _existing_auto_cards(db: DatabasePool, *, agent_id: str, user_id: int) -> dict[str, int]:
    with db.connect() as conn:
        rows = conn.execute(
            "SELECT kind, MAX(updated_at) AS updated_at FROM today_cards "
            "WHERE agent_id = ? AND user_id = ? AND dedupe_key LIKE 'auto:%' "
            "GROUP BY kind",
            (agent_id, user_id),
        ).fetchall()
    return {str(row["kind"]): int(row["updated_at"]) for row in rows}


def _message_content(message_json: str) -> str:
    try:
        payload = json.loads(message_json)
    except (TypeError, json.JSONDecodeError):
        return ""
    if not isinstance(payload, dict):
        return ""
    data = payload.get("data")
    if isinstance(data, dict):
        content = data.get("content")
        if isinstance(content, str):
            return _compact(content, 220)
    content = payload.get("content")
    return _compact(content, 220) if isinstance(content, str) else ""


def _conversation_card(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    now: int,
) -> _SourceResult:
    since = now - _CONVERSATION_WINDOW_SECONDS
    with db.connect() as conn:
        rows = conn.execute(
            "SELECT t.thread_id, t.title, t.last_active FROM threads t "
            "WHERE t.agent_id = ? AND t.user_id = ? AND t.last_active >= ? "
            "AND COALESCE(t.session_key, '') NOT LIKE '%cron%' "
            "AND NOT EXISTS ("
            "SELECT 1 FROM today_cards c WHERE c.agent_id = t.agent_id "
            "AND c.user_id = t.user_id AND c.execution_thread_id = t.thread_id"
            ") AND EXISTS ("
            "SELECT 1 FROM thread_messages m "
            "WHERE m.thread_id = t.thread_id AND m.role = 'ai'"
            ") ORDER BY t.last_active DESC LIMIT ?",
            (agent_id, user_id, since, _MAX_CONVERSATIONS * 3),
        ).fetchall()
        evidence: list[_Evidence] = []
        titles: list[str] = []
        last_active = 0
        for row in rows:
            thread_id = str(row["thread_id"])
            message = conn.execute(
                "SELECT message_json FROM thread_messages "
                "WHERE thread_id = ? AND role = 'ai' "
                "ORDER BY created_at DESC, seq DESC LIMIT 1",
                (thread_id,),
            ).fetchone()
            excerpt = _message_content(str(message["message_json"])) if message else ""
            if not cron_result_has_new_information(
                excerpt,
                task_kind="conversation",
            ):
                continue
            title = _compact(row["title"] or "未命名会话", 96)
            titles.append(title)
            last_active = max(last_active, int(row["last_active"]))
            evidence.append(
                _Evidence(
                    source_type="conversation",
                    source_record_id=thread_id,
                    event_type="conversation.context",
                    occurred_at=int(row["last_active"]),
                    privacy_class="private_user_content",
                    title=title,
                    excerpt=excerpt,
                )
            )
            if len(evidence) >= _MAX_CONVERSATIONS:
                break

    if not evidence:
        return _SourceResult(cards=(), loaded=True)

    count = len(evidence)
    card = _Card(
        dedupe_key=f"auto:conversation:{_today_key(now)}",
        kind="conversation",
        title=f"{count} 个会话仍待收尾" if count > 1 else "一个会话仍待收尾",
        summary="最近 2 天有真实对话但还没有明确收尾，优先从最近一轮继续。",
        why_now="这些会话仍未形成明确结论或下一步，继续跟进可以减少上下文散落。",
        action_prompt=(
            "请帮我继续处理这些尚未收尾的会话："
            + "；".join(titles)
            + "。先给出每个会话当前最重要的结论和下一步；写操作前先征得我的确认。"
        ),
        occurred_at=last_active or now,
        evidence=tuple(evidence),
        confidence=0.86,
    )
    return _SourceResult(cards=(card,), loaded=True)


def _connector_credentials(
    db: DatabasePool,
    *,
    user_id: int,
    kind: str,
) -> dict[str, Any] | None:
    repo = ConnectorRepo(db)
    row = repo.get_by_user_kind(user_id, kind)
    if row is None or row.status != "active" or not row.credential_blob:
        return None
    return decrypt_credentials(SecretRepo(db), row.credential_blob)


async def _load_mail(db: DatabasePool, *, user_id: int, now: int) -> _SourceResult:
    creds = _connector_credentials(db, user_id=user_id, kind="qq-mail")
    if creds is None:
        return _SourceResult(cards=(), loaded=False)
    try:
        raw = await asyncio.wait_for(
            asyncio.to_thread(
                call_gateway_tool,
                "qq-mail",
                creds,
                "search_emails",
                {"query": "UNSEEN", "limit": _MAX_MAIL},
            ),
            timeout=12,
        )
        messages = json.loads(raw)
    except Exception:
        logger.warning("Today mail refresh failed; preserving other sources")
        return _SourceResult(cards=(), loaded=False)
    if not isinstance(messages, list):
        return _SourceResult(cards=(), loaded=False)

    evidence: list[_Evidence] = []
    senders: list[str] = []
    subjects: list[str] = []
    newest = 0
    for item in messages[:_MAX_MAIL]:
        if not isinstance(item, dict):
            continue
        sender = _compact(item.get("from") or "未知发件人", 120)
        subject = _compact(item.get("subject") or "（无主题）", 160)
        if _LOW_VALUE_MAIL.search(f"{sender} {subject}"):
            continue
        uid = _compact(item.get("uid") or subject, 200)
        occurred_at = _mail_timestamp(item.get("date"), fallback=now)
        newest = max(newest, occurred_at)
        senders.append(sender)
        subjects.append(subject)
        evidence.append(
            _Evidence(
                source_type="mail",
                source_record_id=uid,
                event_type="mail.unread",
                occurred_at=occurred_at,
                privacy_class="private_user_content",
                title=subject,
                excerpt=sender,
            )
        )
    if not evidence:
        return _SourceResult(cards=(), loaded=True)

    count = len(evidence)
    sender_count = len(dict.fromkeys(senders))
    summary = f"来自 {sender_count} 位发件人；先看：{'；'.join(subjects[:2])}"
    card = _Card(
        dedupe_key=f"auto:mail:{_today_key(now)}",
        kind="mail",
        title=f"{count} 封未读邮件" if count > 1 else "1 封未读邮件",
        summary=_compact(summary, 280),
        why_now="未读邮件中可能包含需要回复、等待结果或今天截止的事项。",
        action_prompt=(
            f"请帮我整理这 {count} 封未读邮件：{'；'.join(subjects)}。"
            "只根据发件人、主题和时间提炼需要回复、需要行动、等待结果和仅供了解的事项；"
            "不要读取或转发无关正文，不要自动回复、发送或删除，任何邮件写操作先征得我的确认。"
        ),
        occurred_at=newest or now,
        evidence=tuple(evidence),
        confidence=0.94,
    )
    return _SourceResult(cards=(card,), loaded=True)


def _news_queries(db: DatabasePool, *, agent_id: str, user_id: int) -> tuple[str, ...]:
    with db.connect() as conn:
        rows = conn.execute(
            "SELECT query_text FROM interest_monitors "
            "WHERE agent_id = ? AND user_id = ? AND enabled = 1 "
            "ORDER BY updated_at DESC LIMIT 3",
            (agent_id, user_id),
        ).fetchall()
    topics = tuple(
        _compact(row["query_text"], 80)
        for row in rows
        if _compact(row["query_text"])
    )
    return topics or _DEFAULT_NEWS_QUERIES


async def _load_news(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    now: int,
) -> _SourceResult:
    creds = _connector_credentials(db, user_id=user_id, kind="tencent-news")
    if creds is None:
        return _SourceResult(cards=(), loaded=False)
    queries = _news_queries(db, agent_id=agent_id, user_id=user_id)

    async def search(query: str) -> list[dict[str, Any]]:
        try:
            raw = await asyncio.wait_for(
                asyncio.to_thread(
                    call_gateway_tool,
                    "tencent-news",
                    creds,
                    "search_news",
                    {"query": query, "limit": 3},
                ),
                timeout=12,
            )
            payload = json.loads(raw)
            items = payload.get("news_list") if isinstance(payload, dict) else None
            return [item for item in items or [] if isinstance(item, dict)]
        except Exception:
            logger.warning("Today news refresh failed for one topic")
            return []

    groups = await asyncio.gather(*(search(query) for query in queries))
    if not any(groups):
        return _SourceResult(cards=(), loaded=False)

    evidence: list[_Evidence] = []
    seen: set[str] = set()
    newest = 0
    for items in groups:
        for item in items:
            title = _compact(item.get("title"), 160)
            url = _compact(item.get("url"), 2000)
            if not title or not url.startswith(("http://", "https://")):
                continue
            identity = url or re.sub(r"\W+", "", title.lower())
            if identity in seen:
                continue
            seen.add(identity)
            occurred_at = _timestamp_ms(item.get("timestamp"), fallback=now) // 1000
            newest = max(newest, occurred_at)
            source = _compact(item.get("source"), 80)
            abstract = _compact(item.get("abstract"), 260)
            evidence.append(
                _Evidence(
                    source_type="web_search",
                    source_record_id=_compact(item.get("id") or url, 500),
                    event_type="news.search_result",
                    occurred_at=occurred_at,
                    privacy_class="public_web",
                    title=title,
                    excerpt=_compact(
                        f"{source} · {abstract}" if source and abstract else abstract or source,
                        280,
                    ),
                    url=url,
                )
            )
            if len(evidence) >= _MAX_NEWS:
                break
        if len(evidence) >= _MAX_NEWS:
            break

    if not evidence:
        return _SourceResult(cards=(), loaded=True)
    topics = "、".join(queries)
    card = _Card(
        dedupe_key=f"auto:news:{_today_key(now)}",
        kind="news",
        title=f"关注领域有 {len(evidence)} 条新动态",
        summary=f"已合并重复来源，覆盖 {topics}。",
        why_now="这些结果与你的长期关注领域相关，适合集中浏览而不是逐条推送。",
        action_prompt=(
            f"请基于这张 Today 新闻卡，梳理 {topics} 今天最值得关注的 2-3 个变化。"
            "合并重复报道，说明影响和后续观察点，并保留原始来源链接。"
        ),
        occurred_at=newest or now,
        evidence=tuple(evidence),
        confidence=0.82,
    )
    return _SourceResult(cards=(card,), loaded=True)


def _same_card(conn: Any, row: Any, card: _Card) -> bool:
    evidence_signature = tuple(
        sorted(_evidence_id(item) for item in card.evidence)
    )
    stored_signature = tuple(
        sorted(
            str(item["evidence_id"])
            for item in conn.execute(
                "SELECT evidence_id FROM today_card_evidence "
                "WHERE agent_id = ? AND card_id = ?",
                (row["agent_id"], row["card_id"]),
            ).fetchall()
        )
    )
    return (
        str(row["kind"]) == card.kind
        and str(row["title"]) == card.title
        and str(row["summary"]) == card.summary
        and str(row["why_now"]) == card.why_now
        and str(row["action_prompt"]) == card.action_prompt
        and int(row["occurred_at"]) == card.occurred_at
        and str(row["risk_level"]) == card.risk_level
        and float(row["confidence"]) == card.confidence
        and stored_signature == evidence_signature
    )


def _upsert_card(
    conn: Any,
    *,
    agent_id: str,
    user_id: int,
    now: int,
    card: _Card,
) -> None:
    card_id = _card_id(agent_id, card.dedupe_key)
    existing = conn.execute(
        "SELECT * FROM today_cards WHERE agent_id = ? AND dedupe_key = ?",
        (agent_id, card.dedupe_key),
    ).fetchone()
    if existing is not None and _same_card(conn, existing, card):
        return

    lifecycle = (
        "dismissed"
        if existing is not None and str(existing["lifecycle"]) == "dismissed"
        else "unread"
    )
    if existing is None:
        conn.execute(
            "INSERT INTO today_cards("
            "agent_id, card_id, user_id, dedupe_key, kind, title, summary, why_now, "
            "confidence, lifecycle, occurred_at, snoozed_until, action_prompt, "
            "created_at, updated_at, risk_level"
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?)",
            (
                agent_id,
                card_id,
                user_id,
                card.dedupe_key,
                card.kind,
                card.title,
                card.summary,
                card.why_now,
                card.confidence,
                lifecycle,
                card.occurred_at,
                card.action_prompt,
                now,
                now,
                card.risk_level,
            ),
        )
    else:
        conn.execute(
            "UPDATE today_cards SET kind = ?, title = ?, summary = ?, why_now = ?, "
            "confidence = ?, lifecycle = ?, occurred_at = ?, snoozed_until = NULL, "
            "action_prompt = ?, updated_at = ?, risk_level = ? "
            "WHERE agent_id = ? AND card_id = ?",
            (
                card.kind,
                card.title,
                card.summary,
                card.why_now,
                card.confidence,
                lifecycle,
                card.occurred_at,
                card.action_prompt,
                now,
                card.risk_level,
                agent_id,
                card_id,
            ),
        )
        conn.execute(
            "DELETE FROM today_card_evidence WHERE agent_id = ? AND card_id = ?",
            (agent_id, card_id),
        )

    for item in card.evidence:
        conn.execute(
            "INSERT INTO today_card_evidence("
            "agent_id, card_id, evidence_id, source_type, source_record_id, "
            "event_type, occurred_at, privacy_class, url, title, excerpt, created_at"
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                agent_id,
                card_id,
                _evidence_id(item),
                item.source_type,
                item.source_record_id,
                item.event_type,
                item.occurred_at,
                item.privacy_class,
                item.url,
                item.title,
                item.excerpt,
                now,
            ),
        )


def _dismiss_all_automatic(
    conn: Any,
    *,
    agent_id: str,
    user_id: int,
    kind: str,
    now: int,
) -> None:
    conn.execute(
        "UPDATE today_cards SET lifecycle = 'dismissed', snoozed_until = NULL, updated_at = ? "
        "WHERE agent_id = ? AND user_id = ? AND dedupe_key LIKE ? "
        "AND lifecycle != 'dismissed'",
        (now, agent_id, user_id, f"auto:{kind}:%"),
    )


def _dismiss_previous(
    conn: Any,
    *,
    agent_id: str,
    user_id: int,
    kind: str,
    current_key: str,
    now: int,
) -> None:
    conn.execute(
        "UPDATE today_cards SET lifecycle = 'dismissed', snoozed_until = NULL, updated_at = ? "
        "WHERE agent_id = ? AND user_id = ? AND kind = ? "
        "AND dedupe_key LIKE ? AND dedupe_key != ? AND lifecycle != 'dismissed'",
        (now, agent_id, user_id, kind, f"auto:{kind}:%", current_key),
    )


async def refresh_automatic_today_cards(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    now: int,
) -> None:
    """Refresh connected conversation, mail and news sources without blocking Today."""
    lock = _LOCKS.setdefault((agent_id, user_id), asyncio.Lock())
    async with lock:
        existing = _existing_auto_cards(db, agent_id=agent_id, user_id=user_id)
        mail_owned = has_cron_card_kind(
            db,
            agent_id=agent_id,
            user_id=user_id,
            kind="inbox",
        )
        news_owned = has_cron_card_kind(
            db,
            agent_id=agent_id,
            user_id=user_id,
            kind="briefing",
        )
        conversation = _conversation_card(
            db,
            agent_id=agent_id,
            user_id=user_id,
            now=now,
        )
        mail = (
            _SourceResult(cards=(), loaded=False)
            if mail_owned or _is_fresh(existing, kind="mail", now=now)
            else await _load_mail(db, user_id=user_id, now=now)
        )
        news = (
            _SourceResult(cards=(), loaded=False)
            if news_owned or _is_fresh(existing, kind="news", now=now)
            else await _load_news(
                db,
                agent_id=agent_id,
                user_id=user_id,
                now=now,
            )
        )

        results = {
            "conversation": conversation,
            "mail": mail,
            "news": news,
        }
        with db.transaction() as conn:
            if mail_owned:
                _dismiss_all_automatic(
                    conn,
                    agent_id=agent_id,
                    user_id=user_id,
                    kind="mail",
                    now=now,
                )
            if news_owned:
                _dismiss_all_automatic(
                    conn,
                    agent_id=agent_id,
                    user_id=user_id,
                    kind="news",
                    now=now,
                )
            for kind, result in results.items():
                if result.cards:
                    for card in result.cards:
                        _upsert_card(
                            conn,
                            agent_id=agent_id,
                            user_id=user_id,
                            now=now,
                            card=card,
                        )
                    _dismiss_previous(
                        conn,
                        agent_id=agent_id,
                        user_id=user_id,
                        kind=kind,
                        current_key=result.cards[0].dedupe_key,
                        now=now,
                    )
                elif result.loaded:
                    _dismiss_previous(
                        conn,
                        agent_id=agent_id,
                        user_id=user_id,
                        kind=kind,
                        current_key=f"auto:{kind}:{_today_key(now)}",
                        now=now,
                    )
