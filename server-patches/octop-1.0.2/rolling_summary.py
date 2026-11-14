"""Incremental rolling summarization for the main conversation.

deepagents already replaces evicted history with a summary message, so a later
compaction sees ``[previous summary, new evicted messages]``.  This wrapper
makes that contract explicit and bounded:

* the previous summary is always preserved as its own block, even when the
  summarization input budget would otherwise trim it away;
* only messages evicted since that summary are included in the new input;
* the model is asked to merge, not rewrite from scratch.

Any failure falls back to the original summarizer.  The checkpointer and user
history are never modified here.
"""

from __future__ import annotations

import logging
import os
import re
from collections.abc import Mapping, Sequence
from typing import Any

from langchain_core.messages import HumanMessage, get_buffer_string

logger = logging.getLogger(__name__)

ENV_ENABLED = "OCTOP_ROLLING_SUMMARY_ENABLED"
ENV_PREVIOUS_CHARS = "OCTOP_ROLLING_SUMMARY_PREVIOUS_CHARS"
ENV_NEW_CHARS = "OCTOP_ROLLING_SUMMARY_NEW_CHARS"

DEFAULT_PREVIOUS_CHARS = 6_000
DEFAULT_NEW_CHARS = 16_000
_SUMMARY_BLOCK_RE = re.compile(r"<summary>(.*?)</summary>", re.S)
ROLLING_MARKER = "[rolling summary update]"


def _env_bool(name: str, default: bool) -> bool:
    raw = str(os.environ.get(name, "") or "").strip().lower()
    if not raw:
        return default
    return raw not in {"0", "false", "no", "off"}


def _env_int(name: str, default: int) -> int:
    raw = str(os.environ.get(name, "") or "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return max(500, value)


class RollingSummaryPolicy:
    def __init__(self, *, enabled: bool, previous_chars: int, new_chars: int) -> None:
        self.enabled = enabled
        self.previous_chars = previous_chars
        self.new_chars = new_chars

    @classmethod
    def from_env(cls) -> RollingSummaryPolicy:
        return cls(
            enabled=_env_bool(ENV_ENABLED, True),
            previous_chars=_env_int(ENV_PREVIOUS_CHARS, DEFAULT_PREVIOUS_CHARS),
            new_chars=_env_int(ENV_NEW_CHARS, DEFAULT_NEW_CHARS),
        )


def _is_summary_message(message: Any) -> bool:
    if not isinstance(message, HumanMessage):
        return False
    kwargs = getattr(message, "additional_kwargs", None) or {}
    return isinstance(kwargs, Mapping) and kwargs.get("lc_source") == "summarization"


def _summary_text(message: Any) -> str:
    content = getattr(message, "content", "")
    text = content if isinstance(content, str) else str(content or "")
    match = _SUMMARY_BLOCK_RE.search(text)
    return (match.group(1) if match else text).strip()


def build_incremental_input(
    messages: Sequence[Any],
    *,
    previous_chars: int = DEFAULT_PREVIOUS_CHARS,
    new_chars: int = DEFAULT_NEW_CHARS,
) -> list[Any] | None:
    """Return a synthetic one-message input, or ``None`` without a prior summary."""
    previous = [_summary_text(message) for message in messages if _is_summary_message(message)]
    previous = [text for text in previous if text]
    if not previous:
        return None
    fresh = [message for message in messages if not _is_summary_message(message)]
    if not fresh:
        return None
    previous_text = "\n\n".join(previous)
    if len(previous_text) > previous_chars:
        previous_text = previous_text[: previous_chars - 1].rstrip() + "…"
    fresh_text = get_buffer_string(fresh)
    if len(fresh_text) > new_chars:
        fresh_text = fresh_text[: new_chars - 1].rstrip() + "…"
    content = (
        f"{ROLLING_MARKER}\n"
        "Update the running conversation summary. Merge the previous summary with "
        "the new turns; do not restart from scratch. Keep durable facts, user "
        "preferences and constraints, decisions, open tasks, and file/artifact "
        "references. Drop greetings, retries, and superseded details.\n\n"
        "<previous_summary>\n"
        f"{previous_text}\n"
        "</previous_summary>\n\n"
        "<new_turns>\n"
        f"{fresh_text}\n"
        "</new_turns>"
    )
    return [HumanMessage(content=content)]


def install_rolling_summary(
    middleware: Any,
    *,
    policy: RollingSummaryPolicy | None = None,
) -> Any:
    """Patch one summarization middleware instance in place."""
    active = policy or RollingSummaryPolicy.from_env()
    if middleware is None or not active.enabled:
        return middleware
    if getattr(middleware, "_octop_rolling_summary_installed", False):
        return middleware
    if not hasattr(middleware, "_lc_helper"):
        return middleware

    original_sync = getattr(middleware, "_create_summary", None)
    original_async = getattr(middleware, "_acreate_summary", None)

    if callable(original_sync):
        def _create_summary(messages: list[Any]) -> str:
            try:
                incremental = build_incremental_input(
                    messages,
                    previous_chars=active.previous_chars,
                    new_chars=active.new_chars,
                )
                if incremental is None:
                    return original_sync(messages)
                return original_sync(incremental)
            except Exception:  # never block compaction
                logger.warning("rolling summary fallback", exc_info=True)
                return original_sync(messages)

        middleware._create_summary = _create_summary

    if callable(original_async):
        async def _acreate_summary(messages: list[Any]) -> str:
            try:
                incremental = build_incremental_input(
                    messages,
                    previous_chars=active.previous_chars,
                    new_chars=active.new_chars,
                )
                if incremental is None:
                    return await original_async(messages)
                return await original_async(incremental)
            except Exception:  # never block compaction
                logger.warning("rolling summary fallback", exc_info=True)
                return await original_async(messages)

        middleware._acreate_summary = _acreate_summary

    middleware._octop_rolling_summary_installed = True
    return middleware


__all__ = [
    "ROLLING_MARKER",
    "RollingSummaryPolicy",
    "build_incremental_input",
    "install_rolling_summary",
]
