"""Deterministic, budget-bounded Context Pack compilation.

The Router must never concatenate an unbounded conversation history.  This
module accepts already-ranked candidate references and keeps only the smallest
set that fits the configured token envelope.
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from typing import Any


DEFAULT_CONTEXT_POLICY: dict[str, Any] = {
    "policy_version": "router-phase0-v1",
    "max_total_tokens": 6_000,
    "max_item_chars": 8_000,
    "recent_discussion": {"max_items": 8, "max_tokens": 2_800},
    "short_term_memory": {"max_items": 6, "max_tokens": 1_200, "min_score": 0.2},
    "knowledge_base": {"max_items": 5, "max_tokens": 1_600, "min_score": 0.25},
    "task_capsules": {"max_items": 6, "max_tokens": 1_200},
}
MAX_OMITTED_REFS = 200
MAX_REF_CHARS = 1_000


def estimate_text_tokens(text: str) -> int:
    """Estimate tokens conservatively, including CJK and emoji-heavy text.

    A four-characters-per-token heuristic is acceptable for English but can
    underestimate Chinese by roughly 4x. CJK, Hangul, kana and emoji therefore
    count as one token each; other characters retain the four-per-token rate.
    """
    normalized = str(text or "")
    if not normalized:
        return 0
    weighted = 0.0
    for char in normalized:
        codepoint = ord(char)
        if (
            0x2E80 <= codepoint <= 0x9FFF
            or 0xAC00 <= codepoint <= 0xD7AF
            or 0xF900 <= codepoint <= 0xFAFF
            or 0x1F000 <= codepoint <= 0x1FAFF
        ):
            weighted += 1.0
        else:
            weighted += 0.25
    return max(1, int(math.ceil(weighted)))


def _section(policy: Mapping[str, Any], name: str) -> Mapping[str, Any]:
    raw = policy.get(name, {})
    return raw if isinstance(raw, Mapping) else {}


def _tokens(item: Mapping[str, Any]) -> int:
    try:
        tokens = max(0, int(item.get("tokens", 0)))
    except (TypeError, ValueError):
        tokens = 0
    if tokens > 0:
        return tokens
    text = _content(item, limit=8_000)
    return estimate_text_tokens(text)


def _score(item: Mapping[str, Any]) -> float:
    try:
        return float(item.get("score", 0.0))
    except (TypeError, ValueError):
        return 0.0


def _ref(item: Mapping[str, Any], fallback: str) -> str:
    value = item.get("ref") or item.get("reference") or fallback
    return str(value)[:MAX_REF_CHARS]


def _content(item: Mapping[str, Any], *, limit: int) -> str:
    value = item.get("content") or item.get("text") or item.get("summary") or item.get("excerpt")
    return str(value or "")[:limit]


def _take_section(
    *,
    name: str,
    candidates: Sequence[Mapping[str, Any]],
    section_policy: Mapping[str, Any],
    remaining_total: int,
    item_limit: int,
    omitted: list[dict[str, Any]],
    omission_counts: dict[str, int],
) -> tuple[list[dict[str, Any]], int]:
    max_items = max(0, min(int(section_policy.get("max_items", 0)), len(candidates)))
    section_tokens = max(0, int(section_policy.get("max_tokens", 0)))
    allowed = min(section_tokens, remaining_total)
    picked: list[dict[str, Any]] = []
    used = 0
    for index, item in enumerate(candidates):
        ref = _ref(item, f"{name}:{index}")
        if index >= max_items:
            _record_omission(
                omitted,
                omission_counts,
                ref=ref,
                reason="section_item_limit",
            )
            continue
        tokens = _tokens(item)
        if tokens > allowed - used:
            _record_omission(
                omitted,
                omission_counts,
                ref=ref,
                reason="token_budget",
            )
            continue
        picked.append(
            {
                "ref": ref,
                "tokens": tokens,
                "content": _content(item, limit=item_limit),
            }
        )
        used += tokens
    return picked, used


def _record_omission(
    omitted: list[dict[str, Any]],
    counts: dict[str, int],
    *,
    ref: str,
    reason: str,
) -> None:
    counts[reason] = counts.get(reason, 0) + 1
    if len(omitted) < MAX_OMITTED_REFS:
        omitted.append({"ref": ref, "reason": reason})


def _active_policy(policy: Mapping[str, Any] | None) -> dict[str, Any]:
    active = dict(DEFAULT_CONTEXT_POLICY)
    if not policy:
        return active
    for name, value in policy.items():
        if name in {
            "recent_discussion",
            "short_term_memory",
            "knowledge_base",
            "task_capsules",
        } and isinstance(value, Mapping):
            section = dict(DEFAULT_CONTEXT_POLICY[name])
            section.update(value)
            active[name] = section
        else:
            active[name] = value
    return active


def compile_context_pack(
    *,
    recent_discussion: Sequence[Mapping[str, Any]] = (),
    short_term_memory: Sequence[Mapping[str, Any]] = (),
    knowledge_base: Sequence[Mapping[str, Any]] = (),
    task_capsules: Sequence[Mapping[str, Any]] = (),
    policy: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Return a bounded pack and a manifest suitable for ``context_pack_manifests``.

    ``recent_discussion`` is expected in chronological order. Memory and knowledge
    candidates are expected to be retrieval results; they are sorted by score and
    score thresholded here so the caller cannot accidentally pass an entire store.
    """
    active_policy = _active_policy(policy)
    item_limit = max(100, int(active_policy.get("max_item_chars", 8_000)))
    total_limit = max(0, int(active_policy.get("max_total_tokens", 0)))

    recent_policy = _section(active_policy, "recent_discussion")
    recent_all = list(recent_discussion)
    recent_limit = max(0, int(recent_policy.get("max_items", 0)))
    recent_items = recent_all[-recent_limit:] if recent_limit else []
    tasks_policy = _section(active_policy, "task_capsules")
    tasks = sorted(
        task_capsules,
        key=lambda item: (int(item.get("priority", 0)), int(item.get("updated_at", 0))),
        reverse=True,
    )
    memory_policy = _section(active_policy, "short_term_memory")
    memory_min = float(memory_policy.get("min_score", 0.0))
    memories = sorted(
        (item for item in short_term_memory if _score(item) >= memory_min),
        key=_score,
        reverse=True,
    )
    knowledge_policy = _section(active_policy, "knowledge_base")
    knowledge_min = float(knowledge_policy.get("min_score", 0.0))
    knowledge = sorted(
        (item for item in knowledge_base if _score(item) >= knowledge_min),
        key=_score,
        reverse=True,
    )

    omitted: list[dict[str, Any]] = []
    omission_counts: dict[str, int] = {}
    for index, item in enumerate(short_term_memory):
        if _score(item) < memory_min:
            _record_omission(
                omitted,
                omission_counts,
                ref=_ref(item, f"short_term_memory:{index}"),
                reason="score_below_threshold",
            )
    for index, item in enumerate(knowledge_base):
        if _score(item) < knowledge_min:
            _record_omission(
                omitted,
                omission_counts,
                ref=_ref(item, f"knowledge_base:{index}"),
                reason="score_below_threshold",
            )
    for index, item in enumerate(recent_all[: max(0, len(recent_all) - recent_limit)]):
        _record_omission(
            omitted,
            omission_counts,
            ref=_ref(item, f"recent_discussion:{index}"),
            reason="section_item_limit",
        )

    sections: list[dict[str, Any]] = []
    used_total = 0
    for name, candidates, section_policy in (
        ("task_capsules", tasks, tasks_policy),
        ("recent_discussion", recent_items, recent_policy),
        ("short_term_memory", memories, memory_policy),
        ("knowledge_base", knowledge, knowledge_policy),
    ):
        selection_order = list(reversed(candidates)) if name == "recent_discussion" else candidates
        picked, used = _take_section(
            name=name,
            candidates=selection_order,
            section_policy=section_policy,
            remaining_total=max(0, total_limit - used_total),
            item_limit=item_limit,
            omitted=omitted,
            omission_counts=omission_counts,
        )
        if name == "recent_discussion":
            picked.reverse()
        if picked:
            sections.append({"kind": name, "tokens": used, "items": picked})
        used_total += used

    included_refs = [
        str(item["ref"])
        for section in sections
        for item in section["items"]
    ]
    return {
        "policy_version": str(active_policy.get("policy_version", "router-phase0-v1")),
        "used_tokens": used_total,
        "sections": sections,
        "included_refs": included_refs,
        "omitted_refs": omitted,
        "omitted_count": sum(omission_counts.values()),
        "omitted_by_reason": dict(sorted(omission_counts.items())),
        "omitted_refs_truncated": sum(omission_counts.values()) > len(omitted),
    }
