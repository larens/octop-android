"""Scheduled, change-only web monitoring for Today cards."""

from __future__ import annotations

import asyncio
import hashlib
import logging
import time
import xml.etree.ElementTree as ET
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import datetime, time as clock_time, timedelta
from email.utils import parsedate_to_datetime
from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import httpx

_SEARCH_URL = "https://www.bing.com/search"
_USER_AGENT = "Octop-Interest-Monitor/1.0"
_TIMEOUT_SECONDS = 25.0
_MAX_RESULTS = 10
_MAX_RESULTS_PER_RUN = 3
_MAX_PROMPT_EVIDENCE = 30
_MAX_FEEDBACK_BACKOFF_DAYS = 30
_FEEDBACK_BACKOFF_BASE_DAYS = {
    "too_frequent": 3,
    "irrelevant": 7,
    "poor_source": 3,
}
logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class SearchResult:
    title: str
    url: str
    excerpt: str
    published_at: int | None
    fingerprint: str


def _normalize_url(value: str) -> str:
    parts = urlsplit(value.strip())
    query = urlencode(
        sorted(
            (key, val)
            for key, val in parse_qsl(parts.query, keep_blank_values=True)
            if not key.lower().startswith("utm_")
        )
    )
    return urlunsplit((parts.scheme.lower(), parts.netloc.lower(), parts.path, query, ""))


def _fingerprint(title: str, url: str) -> str:
    normalized = f"{_normalize_url(url)}|{' '.join(title.lower().split())}"
    return hashlib.sha256(normalized.encode()).hexdigest()


def source_domain(url: str) -> str:
    host = (urlsplit(url).hostname or "").strip().lower()
    return host.removeprefix("www.")


def feedback_backoff_seconds(feedback_type: str, *, level: int) -> int:
    """Return a bounded exponential backoff for a feedback signal."""
    base_days = _FEEDBACK_BACKOFF_BASE_DAYS[feedback_type]
    days = min(_MAX_FEEDBACK_BACKOFF_DAYS, base_days * (2 ** max(level - 1, 0)))
    return days * 24 * 60 * 60


def _published_at(value: str | None) -> int | None:
    if not value:
        return None
    try:
        return int(parsedate_to_datetime(value).timestamp())
    except (TypeError, ValueError, OverflowError):
        return None


async def search_bing_rss(query: str) -> list[SearchResult]:
    async with httpx.AsyncClient(
        timeout=_TIMEOUT_SECONDS,
        follow_redirects=True,
        headers={"User-Agent": _USER_AGENT},
    ) as client:
        response = await client.get(_SEARCH_URL, params={"q": query, "format": "rss"})
        response.raise_for_status()
    root = ET.fromstring(response.content)
    results: list[SearchResult] = []
    for item in root.findall("./channel/item")[:_MAX_RESULTS]:
        title = (item.findtext("title") or "").strip()
        url = (item.findtext("link") or "").strip()
        if not title or not url or urlsplit(url).scheme not in {"http", "https"}:
            continue
        excerpt = " ".join((item.findtext("description") or "").split())[:500]
        results.append(
            SearchResult(
                title=title[:300],
                url=_normalize_url(url)[:2000],
                excerpt=excerpt,
                published_at=_published_at(item.findtext("pubDate")),
                fingerprint=_fingerprint(title, url),
            )
        )
    return results


def next_run_at(schedule: str, *, now: int, timezone_name: str) -> int | None:
    if schedule == "manual":
        return None
    try:
        zone = ZoneInfo(timezone_name)
    except ZoneInfoNotFoundError:
        zone = ZoneInfo("UTC")
    local_now = datetime.fromtimestamp(now, tz=zone)
    hours = {
        "morning": (8,),
        "evening": (20,),
        "twice_daily": (8, 20),
    }[schedule]
    candidates = [
        datetime.combine(local_now.date(), clock_time(hour), tzinfo=zone)
        for hour in hours
    ]
    future = [candidate for candidate in candidates if candidate > local_now]
    picked = min(future) if future else datetime.combine(
        local_now.date() + timedelta(days=1), clock_time(hours[0]), tzinfo=zone
    )
    return int(picked.timestamp())


class InterestMonitorService:
    def __init__(
        self,
        db: Any,
        *,
        timezone_name: str,
        search: Any = search_bing_rss,
        notify: Callable[[int, dict[str, Any]], Awaitable[None]] | None = None,
    ) -> None:
        self._db = db
        self._timezone_name = timezone_name
        self._search = search
        self._notify = notify
        self._lock = asyncio.Lock()

    def replace_database(self, db: Any) -> None:
        self._db = db

    async def run_due(self) -> None:
        now = int(time.time())
        with self._db.connect() as conn:
            rows = conn.execute(
                "SELECT m.monitor_id FROM interest_monitors m LEFT JOIN today_goals g ON g.goal_id = m.goal_id "
                "WHERE m.enabled = 1 AND (m.goal_id IS NULL OR g.status = 'active') "
                "AND (m.delivery_backoff_until IS NULL OR m.delivery_backoff_until <= ?) "
                "AND next_run_at IS NOT NULL AND next_run_at <= ? ORDER BY next_run_at LIMIT 20",
                (now, now),
            ).fetchall()
        for row in rows:
            try:
                await self.run(str(row["monitor_id"]))
            except Exception as exc:
                logger.warning("interest monitor %s failed: %s", row["monitor_id"], exc)

    async def run(self, monitor_id: str, *, manual: bool = False) -> dict[str, Any]:
        async with self._lock:
            with self._db.connect() as conn:
                row = conn.execute(
                    "SELECT * FROM interest_monitors WHERE monitor_id = ?", (monitor_id,)
                ).fetchone()
                muted_domains = (
                    {
                        str(item["domain"])
                        for item in conn.execute(
                            "SELECT domain FROM interest_source_preferences "
                            "WHERE agent_id = ? AND user_id = ? "
                            "AND muted_until IS NOT NULL AND muted_until > ?",
                            (row["agent_id"], row["user_id"], int(time.time())),
                        ).fetchall()
                    }
                    if row is not None
                    else set()
                )
            if row is None:
                raise KeyError(monitor_id)
            now = int(time.time())
            schedule = str(row["schedule"])
            following = next_run_at(schedule, now=now, timezone_name=self._timezone_name)
            try:
                searched = await self._search(str(row["query_text"]))
            except Exception as exc:
                message = str(exc).strip()[:500] or exc.__class__.__name__
                with self._db.transaction() as conn:
                    conn.execute(
                        "UPDATE interest_monitors SET last_run_at = ?, last_status = 'error', "
                        "last_error = ?, next_run_at = ?, updated_at = ? WHERE monitor_id = ?",
                        (now, message, following, now, monitor_id),
                    )
                raise

            results = [result for result in searched if source_domain(result.url) not in muted_domains]
            changed_card_id: str | None = None
            with self._db.transaction() as conn:
                seen = {
                    str(item["fingerprint"])
                    for item in conn.execute(
                        "SELECT fingerprint FROM interest_results WHERE monitor_id = ?",
                        (monitor_id,),
                    ).fetchall()
                }
                baseline = int(row["baseline_established"] or 0) == 1
                new_results = [result for result in results if result.fingerprint not in seen]
                for result in results:
                    conn.execute(
                        "INSERT INTO interest_results("
                        "monitor_id, fingerprint, title, url, excerpt, published_at, first_seen_at"
                        ") VALUES (?, ?, ?, ?, ?, ?, ?) "
                        "ON CONFLICT(monitor_id, fingerprint) DO NOTHING",
                        (
                            monitor_id,
                            result.fingerprint,
                            result.title,
                            result.url,
                            result.excerpt,
                            result.published_at,
                            now,
                        ),
                    )
                selected = new_results[:_MAX_RESULTS_PER_RUN] if baseline else []
                if selected:
                    changed_card_id = self._publish_briefing(
                        conn, row=row, results=selected, now=now, manual=manual
                    )
                conn.execute(
                    "UPDATE interest_monitors SET baseline_established = 1, last_run_at = ?, "
                    "last_status = 'ok', last_error = NULL, last_result_count = ?, "
                    "next_run_at = ?, updated_at = ? WHERE monitor_id = ?",
                    (now, len(results), following, now, monitor_id),
                )
            if changed_card_id is not None:
                await self._notify_best_effort(
                    user_id=int(row["user_id"]),
                    agent_id=str(row["agent_id"]),
                    card_id=changed_card_id,
                )
            return {
                "result_count": len(results),
                "new_count": len(new_results),
                "published_count": len(selected),
            }

    def _briefing_key(self, *, now: int, manual: bool) -> tuple[str, str]:
        try:
            zone = ZoneInfo(self._timezone_name)
        except ZoneInfoNotFoundError:
            zone = ZoneInfo("UTC")
        local_now = datetime.fromtimestamp(now, tz=zone)
        window = "manual" if manual else ("morning" if local_now.hour < 14 else "evening")
        return local_now.date().isoformat(), window

    def _publish_briefing(
        self,
        conn: Any,
        *,
        row: Any,
        results: list[SearchResult],
        now: int,
        manual: bool,
    ) -> str:
        monitor_id = str(row["monitor_id"])
        agent_id = str(row["agent_id"])
        local_date, window = self._briefing_key(now=now, manual=manual)
        goal_id = str(row["goal_id"]) if row["goal_id"] is not None else "unbound"
        dedupe_key = f"briefing:{local_date}:{window}:{goal_id}"
        card_id = f"today-web-{hashlib.sha256(f'{agent_id}|{dedupe_key}'.encode()).hexdigest()[:24]}"
        conn.execute(
            "INSERT INTO today_cards("
            "agent_id, card_id, user_id, dedupe_key, kind, title, summary, why_now, "
            "confidence, lifecycle, occurred_at, snoozed_until, action_prompt, created_at, updated_at, goal_id"
            ") VALUES (?, ?, ?, ?, 'research', '', '', '', 0.75, 'unread', ?, NULL, '', ?, ?, ?) "
            "ON CONFLICT(agent_id, dedupe_key) DO NOTHING",
            (agent_id, card_id, int(row["user_id"]), dedupe_key, now, now, now, row["goal_id"]),
        )
        for result in results:
            conn.execute(
                "INSERT INTO today_web_evidence("
                "agent_id, card_id, monitor_id, fingerprint, url, title, excerpt, published_at, observed_at"
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                "ON CONFLICT(agent_id, card_id, fingerprint) DO NOTHING",
                (
                    agent_id,
                    card_id,
                    monitor_id,
                    result.fingerprint,
                    result.url,
                    result.title,
                    result.excerpt,
                    result.published_at,
                    now,
                ),
            )
        self._refresh_briefing(conn, agent_id=agent_id, card_id=card_id, window=window, now=now)
        return card_id

    @staticmethod
    def _refresh_briefing(conn: Any, *, agent_id: str, card_id: str, window: str, now: int) -> None:
        rows = conn.execute(
            "SELECT w.title, w.url, w.excerpt, w.published_at, w.observed_at, m.query_text, "
            "g.title AS goal_title, g.success_rules AS goal_success_rules, g.review_at AS goal_review_at "
            "FROM today_web_evidence w JOIN interest_monitors m ON m.monitor_id = w.monitor_id "
            "LEFT JOIN today_goals g ON g.goal_id = m.goal_id "
            "WHERE w.agent_id = ? AND w.card_id = ? "
            "ORDER BY w.observed_at DESC, w.published_at DESC",
            (agent_id, card_id),
        ).fetchall()
        if not rows:
            conn.execute("DELETE FROM today_cards WHERE agent_id = ? AND card_id = ?", (agent_id, card_id))
            return
        topic_count = len({str(item["query_text"]) for item in rows})
        goal_title = next((str(item["goal_title"]) for item in rows if item["goal_title"] is not None), None)
        goal_review_at = next((int(item["goal_review_at"]) for item in rows if item["goal_review_at"] is not None), None)
        needs_review = goal_review_at is not None and goal_review_at <= now
        label = {"morning": "晨间", "evening": "晚间", "manual": "即时"}.get(window, "兴趣")
        titles = [str(item["title"]) for item in rows]
        summary = "；".join(titles[:3])
        if len(titles) > 3:
            summary += f"；另有 {len(titles) - 3} 条"
        relevance = f"这些变化关联目标“{goal_title}”。" if goal_title else "这些变化来自你明确创建的关注主题。"
        review_note = " 目标已到复核时间，建议先确认目标仍然有效。" if needs_review else ""
        why_now = f"{topic_count} 个关注主题自上次检查后出现了 {len(rows)} 条新变化。{relevance}{review_note}"
        prompt_lines = [
            "请核查并综合分析这份 Today 兴趣简报，按关注主题归纳变化和可能影响。",
            f"简报窗口：{label}；主题数：{topic_count}；线索数：{len(rows)}。",
        ]
        if goal_title:
            prompt_lines.append(f"关联目标：{goal_title}")
            prompt_lines.append(f"成功标准：{rows[0]['goal_success_rules']}")
        if needs_review:
            prompt_lines.append("该目标已到复核时间；请先询问我是否继续，而不是直接推进外部动作。")
        for item in rows[:_MAX_PROMPT_EVIDENCE]:
            prompt_lines.append(
                f"- 主题：{item['query_text']}\n  标题：{item['title']}\n  链接：{item['url']}"
            )
        prompt_lines.append("请区分网页事实与推断，并在采取任何外部写操作前征得我的确认。")
        occurred_at = max(int(item["published_at"] or item["observed_at"]) for item in rows)
        conn.execute(
            "UPDATE today_cards SET title = ?, summary = ?, why_now = ?, lifecycle = 'unread', "
            "occurred_at = ?, snoozed_until = NULL, action_prompt = ?, updated_at = ? "
            "WHERE agent_id = ? AND card_id = ?",
            (
                f"{label}兴趣简报 · {len(rows)} 条变化",
                summary,
                why_now,
                occurred_at,
                "\n".join(prompt_lines),
                now,
                agent_id,
                card_id,
            ),
        )

    async def _notify_best_effort(self, *, user_id: int, agent_id: str, card_id: str) -> None:
        if self._notify is None:
            return
        try:
            with self._db.connect() as conn:
                row = conn.execute(
                    "SELECT g.review_at FROM today_cards c LEFT JOIN today_goals g ON g.goal_id = c.goal_id "
                    "WHERE c.agent_id = ? AND c.card_id = ?",
                    (agent_id, card_id),
                ).fetchone()
            needs_review = row is not None and row["review_at"] is not None and int(row["review_at"]) <= int(time.time())
            await self._notify(
                user_id,
                {
                    "type": "dashboard_push",
                    "event": "today.card_changed",
                    "agent_id": agent_id,
                    "card_id": card_id,
                    "priority": "low" if needs_review else "normal",
                    "text": "Today 有新的兴趣简报",
                },
            )
        except Exception:
            logger.warning("failed to push Today card notification card=%s", card_id, exc_info=True)
