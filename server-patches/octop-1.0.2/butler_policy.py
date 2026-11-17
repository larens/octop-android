"""Butler policy for Router mode.

The butler is the user's single entry point: an ordinary expert agent whose
job is to understand the request, hand it to the right expert via
``ask_agent``, and summarise what came back.  It must not do the work itself,
so every tool outside the dispatch allowlist is hidden and rejected.

The Worker side is unaffected: assignees keep their full workspace, model and
tool set.
"""

from __future__ import annotations

import json
import logging
import os
from collections.abc import Mapping
from typing import Any

from octop.infra.agents.settings.tool_catalog import BUILTIN_TOOL_CATALOG

logger = logging.getLogger(__name__)

BUTLER_MODES: tuple[str, ...] = ("off", "assist", "auto")

# Dispatch plus light recall. Everything else belongs to the Worker.
BUTLER_TOOLS_ALLOWED: frozenset[str] = frozenset(
    {
        "agent_list",
        "ask_agent",
        "memory_search",
        "memory_get",
        "current_time",
    }
)

BUTLER_TOOLS_DISABLED: frozenset[str] = frozenset(
    entry.name for entry in BUILTIN_TOOL_CATALOG if entry.name not in BUTLER_TOOLS_ALLOWED
)

BUTLER_PROMPT = """\
You are the user's butler: the single entry point for this conversation.

Your job is to understand the request, hand execution to the right expert with
`ask_agent`, then summarise the expert's result. You do not do the work
yourself: no file access, no shell, no browsing, no MCP/connector calls.

Rules:
- Pick one expert per request. Fan out only when the request has clearly
  independent parts.
- If the right expert is not obvious, call `agent_list` first and copy an exact
  `agent_id` from the roster (for example `JETA0K`). Never invent an expert
  name; if a name is rejected, retry with a real id from `agent_list`.
- Give the expert everything it needs: goal, constraints, relevant context.
- Report the expert's answer faithfully. Never invent results.
- Confirm with the user before any side effect (send, delete, pay, book,
  order, submit).
- Dispatching works in every composer mode. Never tell the user to switch to
  Craft or any other mode before you can hand work to an expert.
- When a "Worker 执行计划" card is waiting for confirmation, tell the user to
  approve or reassign it instead of doing the work yourself.
- Keep the reply short: what you dispatched, to whom, and what came back.
"""


def _read(settings_repo: Any, key: str, env_key: str) -> str:
    raw = os.environ.get(env_key)
    if raw is None:
        try:
            raw = settings_repo.get(key) if settings_repo is not None else None
        except Exception:
            logger.debug("butler setting read failed key=%s", key, exc_info=True)
            raw = None
    return "" if raw is None else str(raw).strip()


def butler_settings(settings_repo: Any = None) -> dict[str, Any]:
    """Return ``{mode, agent_id, experts}`` for the configured butler."""
    mode = (_read(settings_repo, "router.butler.mode", "OCTOP_ROUTER_BUTLER_MODE") or "off").lower()
    if mode not in BUTLER_MODES:
        logger.warning("unknown router.butler.mode=%r, treating as off", mode)
        mode = "off"
    agent_id = _read(settings_repo, "router.butler.agent_id", "OCTOP_ROUTER_BUTLER_AGENT_ID")
    raw_experts = _read(settings_repo, "router.butler.experts", "OCTOP_ROUTER_BUTLER_EXPERTS")
    experts: dict[str, str] = {}
    if raw_experts:
        try:
            parsed = json.loads(raw_experts)
        except ValueError:
            logger.warning("router.butler.experts is not valid JSON; ignoring")
            parsed = None
        if isinstance(parsed, Mapping):
            experts = {
                str(role).strip(): str(target).strip()
                for role, target in parsed.items()
                if str(role).strip() and str(target).strip()
            }
    return {"mode": mode, "agent_id": agent_id, "experts": experts}


def is_butler_agent(agent_id: str, settings: Mapping[str, Any] | None = None) -> bool:
    """True only for the explicitly configured agent; an empty id means off."""
    cfg = dict(settings or {})
    expected = str(cfg.get("agent_id") or "").strip()
    if not expected or str(agent_id or "") != expected:
        return False
    return str(cfg.get("mode") or "off") in {"assist", "auto"}


def butler_tools_disabled(
    disabled: frozenset[str] | set[str] | tuple[str, ...] = (),
) -> frozenset[str]:
    """Denylist for the butler: caller's denylist plus catalog minus allowlist."""
    return frozenset(disabled) | BUTLER_TOOLS_DISABLED


def butler_system_prompt(existing: str | None) -> str:
    """Prepend the dispatch-only contract to the agent's own prompt."""
    base = str(existing or "").strip()
    return f"{BUTLER_PROMPT}\n\n{base}" if base else BUTLER_PROMPT


__all__ = [
    "BUTLER_MODES",
    "BUTLER_PROMPT",
    "BUTLER_TOOLS_ALLOWED",
    "BUTLER_TOOLS_DISABLED",
    "butler_settings",
    "butler_system_prompt",
    "butler_tools_disabled",
    "is_butler_agent",
]
