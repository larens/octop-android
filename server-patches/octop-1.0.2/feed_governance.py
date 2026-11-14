"""Mirror proactive cards into a bounded feed and inject a small digest.

Proactive cards (Today/health/news/calendar/signal) are not conversations.  The
model only needs a short, stable digest of the currently unread events, plus a
stable ``event_id`` it can quote when the user asks about one item.

The mirror is idempotent: ``(agent_id, dedupe_key)`` is unique and updates only
when the source content or lifecycle changed.  Digests are deterministic for an
unchanged feed so they do not churn the prompt prefix.
"""

from __future__ import annotations

import asyncio
import contextlib
import hashlib
import json
import logging
import os
import sqlite3
import threading
import time
from collections.abc import Awaitable, Callable, Mapping
from pathlib import Path
from typing import Any

from langchain.agents.middleware import AgentMiddleware, ModelRequest, ModelResponse

try:
    from deepagents.middleware._utils import append_to_system_message
except Exception:  # pragma: no cover - defensive for older deepagents
    from langchain_core.messages import SystemMessage

    def append_to_system_message(system_message: Any, text: str) -> SystemMessage:
        existing = ""
        if system_message is not None:
            content = getattr(system_message, "content", "")
            existing = content if isinstance(content, str) else ""
        joined = f"{existing}\n\n{text}" if existing else text
        return SystemMessage(content=joined)

try:
    from octop_harness.middleware.runtime import runtime_config
except Exception:  # pragma: no cover - local test import path
    def runtime_config(request: ModelRequest) -> dict[str, Any]:
        runtime = getattr(request, "runtime", None)
        configurable = getattr(runtime, "configurable", None)
        return {"configurable": configurable} if isinstance(configurable, dict) else {}


logger = logging.getLogger(__name__)

ENV_ENABLED = "OCTOP_FEED_DIGEST_ENABLED"
ENV_DB_PATH = "OCTOP_FEED_DB_PATH"
ENV_MAX_EVENTS = "OCTOP_FEED_DIGEST_MAX_EVENTS"
ENV_MAX_CHARS = "OCTOP_FEED_DIGEST_MAX_CHARS"
ENV_MAX_AGE_DAYS = "OCTOP_FEED_DIGEST_MAX_AGE_DAYS"
ENV_SYNC_SECONDS = "OCTOP_FEED_SYNC_INTERVAL_SECONDS"

DEFAULT_MAX_EVENTS = 4
DEFAULT_MAX_CHARS = 1_600
DEFAULT_MAX_AGE_DAYS = 14
DEFAULT_SYNC_SECONDS = 300
_CACHE_SECONDS = 45
_DIGEST_HEADER = (
    "<feed_digest>\n"
    "Unread proactive feed events, newest first. Quote an event id only when the "
    "user asks about that specific item.\n"
)
_DIGEST_FOOTER = "</feed_digest>"


def _env_bool(name: str, default: bool) -> bool:
    raw = str(os.environ.get(name, "") or "").strip().lower()
    if not raw:
        return default
    return raw not in {"0", "false", "no", "off"}


def _env_int(name: str, default: int, *, minimum: int = 0) -> int:
    raw = str(os.environ.get(name, "") or "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return max(minimum, value)


def resolve_db_path() -> Path:
    explicit = str(os.environ.get(ENV_DB_PATH, "") or "").strip()
    if explicit:
        return Path(explicit)
    direct = str(os.environ.get("OCTOP_DB_PATH", "") or "").strip()
    if direct:
        return Path(direct)
    home = str(os.environ.get("OCTOP_HOME", "") or "").strip()
    if home:
        return Path(home) / "octop.db"
    return Path("/opt/octop/octop.db")


def _connect(path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(f"file:{path}?mode=rw", uri=True, timeout=5)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=5000")
    return conn


def _clip(value: Any, limit: int) -> str:
    text = " ".join(str(value or "").split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def _content_hash(*parts: Any) -> str:
    payload = "\0".join(str(part or "") for part in parts)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _epoch_seconds(value: Any) -> int:
    """Today cards mix second and millisecond timestamps (calendar uses ms)."""
    try:
        number = int(value or 0)
    except (TypeError, ValueError):
        return 0
    return number // 1000 if number > 10_000_000_000 else number


def _event_id(agent_id: str, dedupe_key: str) -> str:
    raw = hashlib.sha256(f"{agent_id}\0{dedupe_key}".encode("utf-8")).hexdigest()
    return f"fe_{raw[:24]}"


def _lifecycle(value: Any, *, snoozed_until: Any, now: int) -> str:
    state = str(value or "").strip().lower()
    if state == "unread":
        return "unread"
    if state == "snoozed":
        try:
            until = _epoch_seconds(snoozed_until)
        except (TypeError, ValueError):
            until = 0
        return "snoozed" if until > now else "unread"
    if state in {"read", "dismissed", "expired"}:
        return state
    return "unread"


def sync_today_cards(
    conn: sqlite3.Connection,
    *,
    agent_id: str | None = None,
    user_id: int | None = None,
    now: int | None = None,
    limit: int = 2_000,
) -> dict[str, int]:
    """Upsert recent Today cards into ``feed_events``; never deletes rows."""
    current = int(now if now is not None else time.time())
    clauses: list[str] = []
    params: list[Any] = []
    if agent_id is not None:
        clauses.append("agent_id = ?")
        params.append(agent_id)
    if user_id is not None:
        clauses.append("user_id = ?")
        params.append(int(user_id))
    where = f"WHERE {' AND '.join(clauses)} " if clauses else ""
    params.append(max(1, min(int(limit), 10_000)))
    rows = conn.execute(
        "SELECT agent_id, user_id, card_id, dedupe_key, kind, title, summary, why_now, "
        "lifecycle, occurred_at, snoozed_until, updated_at "
        f"FROM today_cards {where}ORDER BY updated_at DESC LIMIT ?",
        tuple(params),
    ).fetchall()

    scanned = 0
    changed = 0
    for row in rows:
        scanned += 1
        row_agent = str(row["agent_id"])
        row_user = int(row["user_id"])
        dedupe = str(row["dedupe_key"] or row["card_id"])
        state = _lifecycle(
            row["lifecycle"],
            snoozed_until=row["snoozed_until"],
            now=current,
        )
        content_hash = _content_hash(
            row["kind"],
            row["title"],
            row["summary"],
            row["why_now"],
            row["occurred_at"],
        )
        cursor = conn.execute(
            "INSERT INTO feed_events("
            "event_id, agent_id, user_id, source, source_ref, dedupe_key, content_hash, "
            "kind, title, summary, why_now, lifecycle, occurred_at, created_at, updated_at, "
            "payload_json"
            ") VALUES (?, ?, ?, 'today', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(agent_id, dedupe_key) DO UPDATE SET "
            "user_id = excluded.user_id, source_ref = excluded.source_ref, "
            "content_hash = excluded.content_hash, kind = excluded.kind, "
            "title = excluded.title, summary = excluded.summary, "
            "why_now = excluded.why_now, lifecycle = excluded.lifecycle, "
            "occurred_at = excluded.occurred_at, updated_at = excluded.updated_at, "
            "payload_json = excluded.payload_json "
            "WHERE feed_events.content_hash != excluded.content_hash "
            "OR feed_events.lifecycle != excluded.lifecycle "
            "OR feed_events.title != excluded.title "
            "OR feed_events.summary != excluded.summary",
            (
                _event_id(row_agent, f"today:{row_agent}:{dedupe}"),
                row_agent,
                row_user,
                f"today:{row_agent}:{row['card_id']}",
                f"today:{row_agent}:{dedupe}",
                content_hash,
                str(row["kind"] or "today"),
                _clip(row["title"], 200),
                _clip(row["summary"], 1_200),
                _clip(row["why_now"], 600),
                state,
                _epoch_seconds(row["occurred_at"] or row["updated_at"] or current),
                current,
                current,
                json.dumps(
                    {"card_id": row["card_id"], "kind": row["kind"]},
                    ensure_ascii=False,
                    sort_keys=True,
                ),
            ),
        )
        changed += max(0, int(cursor.rowcount or 0))
    if changed:
        conn.commit()
    return {"scanned": scanned, "changed": changed}


def fetch_feed_candidates(
    conn: sqlite3.Connection,
    *,
    agent_id: str,
    user_id: int,
    limit: int = DEFAULT_MAX_EVENTS,
    max_chars: int = DEFAULT_MAX_CHARS,
    max_age_days: int = DEFAULT_MAX_AGE_DAYS,
    now: int | None = None,
) -> list[dict[str, Any]]:
    current = int(now if now is not None else time.time())
    cutoff = current - max(1, int(max_age_days)) * 86_400
    rows = conn.execute(
        "SELECT event_id, kind, title, summary, "
        "CASE WHEN occurred_at > 10000000000 THEN occurred_at / 1000 "
        "ELSE occurred_at END AS occurred_at "
        "FROM feed_events "
        "WHERE agent_id = ? AND user_id = ? "
        "AND lifecycle = 'unread' "
        "AND (CASE WHEN occurred_at > 10000000000 THEN occurred_at / 1000 "
        "ELSE occurred_at END) >= ? "
        "ORDER BY (CASE WHEN occurred_at > 10000000000 THEN occurred_at / 1000 "
        "ELSE occurred_at END) DESC, event_id DESC LIMIT ?",
        (agent_id, int(user_id), cutoff, max(1, min(int(limit), 20))),
    ).fetchall()
    items: list[dict[str, Any]] = []
    used = len(_DIGEST_HEADER) + len(_DIGEST_FOOTER)
    for row in rows:
        when = time.strftime("%m-%d %H:%M", time.localtime(int(row["occurred_at"])))
        line = (
            f"- [{row['event_id']}] {when} · {row['kind']} · "
            f"{_clip(row['title'], 60)} · {_clip(row['summary'], 120)}"
        )
        if items and used + len(line) + 1 > max_chars:
            break
        items.append(
            {
                "ref": f"feed://event/{row['event_id']}",
                "content": line,
                "tokens": max(1, len(line) // 2),
                "event_id": str(row["event_id"]),
                "kind": str(row["kind"]),
                "occurred_at": int(row["occurred_at"]),
                "score": 0.5,
            }
        )
        used += len(line) + 1
    return items


def render_feed_digest(items: list[Mapping[str, Any]]) -> str:
    if not items:
        return ""
    lines = [str(item.get("content") or "") for item in items if item.get("content")]
    if not lines:
        return ""
    return _DIGEST_HEADER + "\n".join(lines) + "\n" + _DIGEST_FOOTER


class FeedPolicy:
    def __init__(
        self,
        *,
        enabled: bool,
        db_path: Path,
        max_events: int,
        max_chars: int,
        max_age_days: int,
        sync_seconds: int,
    ) -> None:
        self.enabled = enabled
        self.db_path = db_path
        self.max_events = max_events
        self.max_chars = max_chars
        self.max_age_days = max_age_days
        self.sync_seconds = sync_seconds

    @classmethod
    def from_env(cls) -> FeedPolicy:
        return cls(
            enabled=_env_bool(ENV_ENABLED, True),
            db_path=resolve_db_path(),
            max_events=_env_int(ENV_MAX_EVENTS, DEFAULT_MAX_EVENTS, minimum=1),
            max_chars=_env_int(ENV_MAX_CHARS, DEFAULT_MAX_CHARS, minimum=200),
            max_age_days=_env_int(ENV_MAX_AGE_DAYS, DEFAULT_MAX_AGE_DAYS, minimum=1),
            sync_seconds=_env_int(ENV_SYNC_SECONDS, DEFAULT_SYNC_SECONDS, minimum=0),
        )


class FeedDigestMiddleware(AgentMiddleware[Any, Any]):
    """Append a bounded unread-feed digest to the outbound system message."""

    def __init__(self, *, policy: FeedPolicy | None = None) -> None:
        super().__init__()
        self._policy = policy or FeedPolicy.from_env()
        self._lock = threading.Lock()
        self._last_sync: dict[tuple[str, int], float] = {}
        self._cache: dict[tuple[str, int], tuple[float, str]] = {}

    def wrap_model_call(
        self,
        request: ModelRequest,
        handler: Callable[[ModelRequest], ModelResponse],
    ) -> ModelResponse:
        return handler(self.prepare(request))

    async def awrap_model_call(
        self,
        request: ModelRequest,
        handler: Callable[[ModelRequest], Awaitable[ModelResponse]],
    ) -> ModelResponse:
        return await handler(await asyncio.to_thread(self.prepare, request))

    def prepare(self, request: ModelRequest) -> ModelRequest:
        if not self._policy.enabled:
            return request
        try:
            digest = self._digest(request)
            if not digest:
                return request
            return request.override(
                system_message=append_to_system_message(request.system_message, digest)
            )
        except Exception:  # digest injection is best-effort
            logger.warning("feed digest injection failed", exc_info=True)
            return request

    def _identity(self, request: ModelRequest) -> tuple[str, int] | None:
        configurable = runtime_config(request).get("configurable")
        if not isinstance(configurable, Mapping):
            return None
        source = str(configurable.get("source") or "")
        if source == "worker":
            return None
        agent_id = str(configurable.get("agent_id") or "").strip()
        raw_user = configurable.get("user")
        if not agent_id or raw_user is None:
            return None
        try:
            user_id = int(raw_user)
        except (TypeError, ValueError):
            return None
        return agent_id, user_id

    def _digest(self, request: ModelRequest) -> str:
        identity = self._identity(request)
        if identity is None:
            return ""
        now = time.time()
        with self._lock:
            cached = self._cache.get(identity)
            if cached is not None and now - cached[0] < _CACHE_SECONDS:
                return cached[1]
            should_sync = (
                self._policy.sync_seconds > 0
                and now - self._last_sync.get(identity, 0.0) >= self._policy.sync_seconds
            )
            if should_sync:
                self._last_sync[identity] = now

        agent_id, user_id = identity
        digest = ""
        event_count = 0
        try:
            with contextlib.closing(_connect(self._policy.db_path)) as conn:
                if should_sync:
                    try:
                        sync_today_cards(conn, agent_id=agent_id, user_id=user_id, now=int(now))
                    except sqlite3.Error:
                        logger.debug("feed mirror sync skipped", exc_info=True)
                items = fetch_feed_candidates(
                    conn,
                    agent_id=agent_id,
                    user_id=user_id,
                    limit=self._policy.max_events,
                    max_chars=self._policy.max_chars,
                    max_age_days=self._policy.max_age_days,
                    now=int(now),
                )
                event_count = len(items)
                digest = render_feed_digest(items)
        except (OSError, sqlite3.Error):
            logger.debug("feed digest skipped path=%s", self._policy.db_path, exc_info=True)

        with self._lock:
            previous = self._cache.get(identity)
            self._cache[identity] = (now, digest)
        if digest and (previous is None or previous[1] != digest):
            logger.info(
                "feed digest injected agent=%s user=%s events=%d chars=%d",
                agent_id,
                user_id,
                event_count,
                len(digest),
            )
        return digest


def install_feed_digest(middleware: Any, *, policy: FeedPolicy | None = None) -> Any:
    """Wrap a summarization middleware so every model request sees the digest."""
    if middleware is None or getattr(middleware, "_octop_feed_digest_installed", False):
        return middleware
    feed = FeedDigestMiddleware(policy=policy)
    original_sync = getattr(middleware, "wrap_model_call", None)
    original_async = getattr(middleware, "awrap_model_call", None)

    if callable(original_sync):
        def wrap_model_call(
            request: ModelRequest,
            handler: Callable[[ModelRequest], ModelResponse],
        ) -> ModelResponse:
            return original_sync(feed.prepare(request), handler)

        middleware.wrap_model_call = wrap_model_call

    if callable(original_async):
        async def awrap_model_call(
            request: ModelRequest,
            handler: Callable[[ModelRequest], Awaitable[ModelResponse]],
        ) -> ModelResponse:
            prepared = await asyncio.to_thread(feed.prepare, request)
            return await original_async(prepared, handler)

        middleware.awrap_model_call = awrap_model_call

    middleware._octop_feed_digest_installed = True
    return middleware


__all__ = [
    "FeedDigestMiddleware",
    "FeedPolicy",
    "fetch_feed_candidates",
    "install_feed_digest",
    "render_feed_digest",
    "resolve_db_path",
    "sync_today_cards",
]
