"""Natural mobile user portraits derived from the rich User entity page.

The upstream entity page is an evidence-oriented memory document with atom
citations, headings, and operational details. That is useful for recall, but
it reads like a CRM record when shown as "what I know about you" in the mobile
Memory tab. This adapter keeps the upstream payload intact and adds a cached,
narrative rendition for that surface.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from octop_memory.application import dashboard_data
from octop_memory.ports.llm._protocol import LLMClientError

logger = logging.getLogger(__name__)

PROFILE_VERSION = "mobile-profile/v2"
DEFAULT_CACHE_DIR = Path(
    os.environ.get("OCTOP_MOBILE_PROFILE_CACHE_DIR", "/opt/octop/state/mobile-profiles")
)

_PROMPT = """\
You are the user's long-running personal agent. Write the narrative profile
shown in the mobile Memory tab under the heading "what I know about you".

This must read like an attentive understanding of one person, not a CRM record,
resume, or list of extracted facts. Write in Simplified Chinese, in the first
person as the agent, addressing the user as "你".

Use 4-5 paragraphs, ideally 600-1000 Chinese characters:
1. If supported, start with how the relationship and preferred address began.
2. Describe the main work thread and the longer-term product/research interests.
   Explain the pattern you infer from them, rather than merely listing roles.
3. Describe how the user records, explores, or collaborates. Look for habits
   that reveal working style: how they collect material, make decisions, or
   prefer the agent to act.
4. Bring in family rhythm, commuting, reading, communities, or tools only at a
   human level when they help explain the user's pace or priorities.
5. End with a grounded observation about what it is like to work alongside this
   person. Avoid praise, diagnosis, and generic claims such as "you are
   passionate and detail-oriented".

Also write a separate brief for the collapsed Memory card. Use 2-3 sentences,
ideally 90-150 Chinese characters. Focus on the most recent and actionable
things I should remember now: the current work mainline, live interests or
decisions, and one collaboration boundary or working rhythm. Do not spend the
brief on biography, family chronology, address origins, or a full personality
portrait; the card is an entry point to the complete narrative below.

Rules:
- Do not use markdown headings, bullets, numbering, tables, or citations.
- Do not expose atom/entity IDs, street addresses, exact birthdays, account
  balances, prices, employee counts, or other low-value sensitive fields.
- Prefer a revealing detail over a dense inventory of facts, but never invent
  projects, communities, habits, outcomes, tools, or feelings.
- Use hedged language when inferring ("我大概能理解", "看起来", "更像") instead
  of asserting a psychological diagnosis.
- Do not mention prompts, memory records, summaries, or that you were asked to
  write a profile.
- Keep the text warm and observant, never flattering or promotional.
- Output JSON only, with no markdown fence or commentary.

OUTPUT SCHEMA:
{{"narrative": "...", "brief": "..."}}

PREFERRED ADDRESS:
{preferred_address}

SOURCE PROFILE:
{profile_markdown}
"""


def _cache_dir(value: Path | None = None) -> Path:
    return value or DEFAULT_CACHE_DIR


def _as_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() in {"1", "true", "yes", "on"}


def _safe_identifier(memory: Any, entity_id: str) -> str:
    namespace = str(getattr(memory, "namespace", "default") or "default")
    digest = hashlib.sha256(f"{namespace}\0{entity_id}".encode()).hexdigest()
    return digest[:32]


def _source_digest(raw: str, subject: str | None) -> str:
    return hashlib.sha256(f"{PROFILE_VERSION}\0{subject or ''}\0{raw}".encode()).hexdigest()


def _read_cache(path: Path, *, source_digest: str) -> tuple[str, str] | None:
    try:
        payload = json.loads(path.read_text())
    except (FileNotFoundError, OSError, TypeError, json.JSONDecodeError):
        return None
    if not isinstance(payload, dict):
        return None
    if payload.get("version") != PROFILE_VERSION or payload.get("source_digest") != source_digest:
        return None
    narrative = str(payload.get("narrative") or "").strip()
    brief = str(payload.get("brief") or "").strip()
    return (narrative, brief) if narrative else None


def _write_cache(
    path: Path,
    *,
    source_digest: str,
    narrative: str,
    brief: str,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    payload = {
        "version": PROFILE_VERSION,
        "source_digest": source_digest,
        "narrative": narrative,
        "brief": brief,
        "generated_at": datetime.now(UTC).isoformat(),
    }
    try:
        temporary.write_text(json.dumps(payload, ensure_ascii=False))
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def _preferred_address(raw: str, subject: str | None) -> str | None:
    patterns = (
        r"被助手称为[「“\"]([^」”\"]{1,24})[」”\"]",
        r"以[「“\"]([^」”\"]{1,24})[」”\"]称呼",
        r"(?:称呼我|叫我)(?:为|作|做|是|[：:])?[「“\"]?([^」”\"，,。；;\s]{1,24})",
    )
    for pattern in patterns:
        match = re.search(pattern, raw)
        if match:
            candidate = match.group(1).strip()
            if candidate not in {"你", "您", "我", "自己", "用户"}:
                return candidate
    return subject


def _primary_user_entity_id(memory: Any) -> str:
    user_entities = [
        entity
        for entity in memory.list_entities(limit=10_000)
        if str(getattr(entity, "entity_type", "")).lower() == "user"
    ]
    user_entities.sort(key=lambda entity: getattr(entity, "atom_count", 0), reverse=True)
    if not user_entities:
        return "user"
    return str(getattr(user_entities[0], "id", "user") or "user")


def _strip_json_fence(raw: str) -> str:
    text = raw.strip()
    if text.startswith("```"):
        first_newline = text.find("\n")
        text = text[first_newline + 1 :] if first_newline >= 0 else text
        text = text.removesuffix("```").strip()
    return text


def _clean_profile_text(value: str) -> str:
    text = re.sub(r"(?m)^\s*#{1,6}\s*", "", value.strip())
    text = re.sub(r"(?m)^\s*[-*]\s+", "", text)
    text = re.sub(r"\s*\((?:atom-)?[0-9a-f]{6,}(?:-[0-9a-f]+)*\)", "", text)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def _parse_profile(raw_output: str) -> tuple[str, str]:
    payload = json.loads(_strip_json_fence(raw_output))
    if not isinstance(payload, dict):
        raise ValueError("profile output is not an object")
    narrative = _clean_profile_text(str(payload.get("narrative") or ""))
    if not narrative:
        raise ValueError("profile narrative is empty")
    brief = _clean_profile_text(str(payload.get("brief") or ""))
    if not brief:
        brief = narrative.split("\n\n", 1)[0][:240]
    return narrative[:4_000], brief[:600]


def _generate(
    llm: Any,
    *,
    raw: str,
    subject: str | None,
) -> tuple[str, str]:
    prompt = _PROMPT.format(
        preferred_address=_preferred_address(raw, subject) or "未明确",
        profile_markdown=raw[:18_000],
    )
    output = llm.call_llm(
        prompt,
        tier="heavy",
        temperature=0.25,
        max_tokens=2_400,
        response_format="json",
    )
    return _parse_profile(output)


def terminal_about_me_with_narrative(
    memory: Any,
    llm: Any,
    params: dict[str, Any],
    *,
    base_payload: dict[str, Any] | None = None,
    cache_dir: Path | None = None,
) -> dict[str, Any]:
    """Keep the upstream About Me payload and add a natural profile narrative."""
    payload = base_payload or dashboard_data.terminal_about_me(memory, params)
    raw = str(payload.get("profile_markdown") or "").strip()
    if not raw:
        return payload

    subject = str(payload.get("profile_subject") or "").strip() or None
    source_digest = _source_digest(raw, subject)
    entity_id = _primary_user_entity_id(memory)
    cache_path = _cache_dir(cache_dir) / f"{_safe_identifier(memory, entity_id)}.json"
    cached = _read_cache(cache_path, source_digest=source_digest)
    narrative, brief = cached or ("", "")
    refresh = _as_bool(params.get("refresh", False))

    if (not narrative or refresh) and llm is not None:
        try:
            narrative, brief = _generate(llm, raw=raw, subject=subject)
            _write_cache(
                cache_path,
                source_digest=source_digest,
                narrative=narrative,
                brief=brief,
            )
        except (LLMClientError, ValueError, TypeError, json.JSONDecodeError):
            logger.warning("mobile profile narrative generation failed; returning raw page", exc_info=True)

    if narrative:
        payload["profile_narrative"] = narrative
        payload["profile_brief"] = brief
        payload["profile_narrative_version"] = PROFILE_VERSION
        payload["profile_narrative_refreshed"] = refresh
    return payload


__all__ = ["PROFILE_VERSION", "terminal_about_me_with_narrative"]
