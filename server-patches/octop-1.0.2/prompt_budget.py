"""Conservative prompt budget for models without declared context metadata.

Octop routes the main conversation to MiMo, but several MiMo entries do not
declare ``context_window`` / ``max_input_tokens``.  The harness then falls back
to the compile-time seed model (DeepSeek, 1M tokens) when it computes
summarization thresholds.  That keeps the main conversation from compacting
until it is far larger than is useful.

This module supplies a bounded fallback that only applies when a model has no
declared prompt limit.  A model that declares its own limit keeps it.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from typing import Any


ENV_PROMPT_BUDGET = "OCTOP_PROMPT_BUDGET_TOKENS"
ENV_SUMMARY_KEEP_MESSAGES = "OCTOP_SUMMARY_KEEP_MESSAGES"
DEFAULT_FALLBACK_PROMPT_BUDGET = 80_000
DEFAULT_SUMMARY_KEEP_MESSAGES = 8


def configured_prompt_budget() -> int:
    """Return the fallback prompt budget; ``0`` disables the fallback."""
    raw = str(os.environ.get(ENV_PROMPT_BUDGET, "") or "").strip()
    if not raw:
        return DEFAULT_FALLBACK_PROMPT_BUDGET
    try:
        value = int(raw)
    except ValueError:
        return DEFAULT_FALLBACK_PROMPT_BUDGET
    return value if value > 0 else 0


def resolve_prompt_budget(declared: int | None) -> int:
    """Prefer a model's declared prompt cap, else the configured fallback."""
    if isinstance(declared, int) and declared > 0:
        return declared
    return configured_prompt_budget()


def configured_summary_keep_messages() -> int:
    """Return how many recent messages compaction keeps; ``0`` disables the override."""
    raw = str(os.environ.get(ENV_SUMMARY_KEEP_MESSAGES, "") or "").strip()
    if not raw:
        return DEFAULT_SUMMARY_KEEP_MESSAGES
    try:
        value = int(raw)
    except ValueError:
        return DEFAULT_SUMMARY_KEEP_MESSAGES
    return value if value > 0 else 0


def _declared_model_limit(model: Any) -> int:
    profile = getattr(model, "profile", None)
    if not isinstance(profile, Mapping):
        return 0
    for key in ("max_input_tokens", "context_window"):
        value = profile.get(key) if hasattr(profile, "get") else None
        if isinstance(value, int) and value > 0:
            return value
    return 0


def uses_fallback_prompt_budget(model: Any) -> bool:
    """Whether the model resolves to the configured fallback rather than its own limit."""
    fallback = configured_prompt_budget()
    if fallback <= 0:
        return False
    declared = _declared_model_limit(model)
    return declared <= 0 or declared == fallback


def tune_summarization_keep(middleware: Any, *, model: Any) -> Any:
    """Keep a bounded recent-message window when compaction uses the fallback budget."""
    keep_messages = configured_summary_keep_messages()
    helper = getattr(middleware, "_lc_helper", None)
    if keep_messages <= 0 or helper is None or not uses_fallback_prompt_budget(model):
        return middleware
    current = getattr(helper, "keep", None)
    if isinstance(current, tuple) and len(current) == 2 and current[0] == "messages":
        return middleware
    helper.keep = ("messages", keep_messages)
    middleware._octop_summary_keep_messages = keep_messages
    return middleware


__all__ = [
    "DEFAULT_FALLBACK_PROMPT_BUDGET",
    "DEFAULT_SUMMARY_KEEP_MESSAGES",
    "ENV_PROMPT_BUDGET",
    "ENV_SUMMARY_KEEP_MESSAGES",
    "configured_prompt_budget",
    "configured_summary_keep_messages",
    "resolve_prompt_budget",
    "tune_summarization_keep",
    "uses_fallback_prompt_budget",
]
