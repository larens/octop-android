"""Shadow Router observations for Phase 0.

This module never dispatches a Worker and never changes the existing dashboard
turn. It records a deterministic routing suggestion plus a bounded Context Pack
so the proposal can be evaluated against real traffic without extra model cost.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import time
import uuid
from collections.abc import Mapping, Sequence
from typing import Any

try:
    from octop.infra.conversation.context import compile_context_pack, estimate_text_tokens
    from octop.infra.conversation.service import ConversationService
except ImportError:  # Local patch-pack tests import modules by filename.
    from router_phase0_context import compile_context_pack, estimate_text_tokens
    from router_phase0_service import ConversationService


logger = logging.getLogger(__name__)

_MEMORY_LIMIT = 6
_KNOWLEDGE_LIMIT = 4
_KNOWLEDGE_CHAR_BUDGET = 7_000

_READ_ONLY_TERMS = (
    "查一下",
    "查询",
    "搜索",
    "搜一下",
    "找一下",
    "帮我找",
    "看一下",
    "读一下",
    "总结",
    "整理",
    "分析",
    "对比",
    "日历",
    "日程",
    "邮件",
    "文档",
    "知识库",
    "新闻",
    "search",
    "find ",
    "look up",
    "summarize",
    "summarise",
    "analyze",
    "analyse",
    "compare",
    "calendar",
    "email",
    "document",
    "knowledge base",
    "news",
)
_SIDE_EFFECT_TERMS = (
    "发送",
    "发给",
    "回复",
    "删除",
    "新建",
    "创建",
    "修改",
    "更新",
    "预约",
    "预订",
    "下单",
    "支付",
    "付款",
    "提交",
    "取消",
    "提醒我",
    "send ",
    "reply",
    "delete",
    "create",
    "update",
    "book ",
    "reserve",
    "order ",
    "pay ",
    "submit",
    "cancel",
    "remind me",
)


def _clip(value: Any, limit: int) -> str:
    text = " ".join(str(value or "").split())
    return text if len(text) <= limit else text[: limit - 3].rstrip() + "..."


def _estimate(text: str) -> int:
    return estimate_text_tokens(text)


def _short_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def _setting_enabled(
    settings_repo: Any,
    *,
    key: str,
    env_key: str,
    default: bool = True,
) -> bool:
    raw = os.environ.get(env_key)
    if raw is None:
        try:
            raw = settings_repo.get(key) if settings_repo is not None else None
        except Exception:
            logger.debug("shadow retrieval setting read failed key=%s", key, exc_info=True)
            raw = None
    if raw is None or str(raw).strip() == "":
        return default
    return str(raw).strip().lower() not in {"0", "false", "no", "off"}


def _message_text(raw: str) -> str:
    try:
        payload = json.loads(raw)
    except (TypeError, ValueError):
        return _clip(raw, 4_000)
    if not isinstance(payload, Mapping):
        return _clip(payload, 4_000)
    data = payload.get("data")
    for container in (data, payload):
        if not isinstance(container, Mapping):
            continue
        content = container.get("content")
        if isinstance(content, str):
            return _clip(content, 4_000)
        if isinstance(content, list):
            parts: list[str] = []
            for block in content:
                if isinstance(block, str):
                    parts.append(block)
                elif isinstance(block, Mapping):
                    text = block.get("text") or block.get("content")
                    if isinstance(text, str):
                        parts.append(text)
            if parts:
                return _clip("\n".join(parts), 4_000)
    return ""


def _mapping_items(payload: Any) -> list[Mapping[str, Any]]:
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, Mapping)]
    if not isinstance(payload, Mapping):
        return []
    for key in ("items", "atoms", "results", "data"):
        value = payload.get(key)
        items = _mapping_items(value)
        if items:
            return items
    return []


def _first_text(item: Mapping[str, Any], *keys: str) -> str:
    for key in keys:
        value = item.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def _memory_score(item: Mapping[str, Any]) -> float:
    levels = {"low": 0.3, "medium": 0.6, "high": 0.9}
    scores = [
        levels.get(str(item.get(key) or "").strip().lower(), 0.0)
        for key in ("importance", "confidence")
    ]
    explicit = item.get("score")
    if explicit is not None:
        try:
            scores.append(max(0.0, min(1.0, float(explicit))))
        except (TypeError, ValueError):
            pass
    return max(scores, default=0.5)


def _looks_like_scheduled_task(text: str) -> bool:
    lowered = text.lower()
    return any(
        term in lowered
        for term in ("定时任务", "触发规则", "cron:", "cron job", "scheduled task")
    )


def _memory_candidates_from_payload(
    payload: Any,
    *,
    limit: int = _MEMORY_LIMIT,
) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in _mapping_items(payload):
        text = _first_text(
            item,
            "assertion",
            "summary",
            "text",
            "content",
            "description",
            "verbatim_quote",
        )
        if not text or _looks_like_scheduled_task(text):
            continue
        atom_id = _first_text(item, "id", "atom_id", "uuid")
        ref = f"memory://atom/{atom_id or _short_hash(text)}"
        if ref in seen:
            continue
        seen.add(ref)
        content = _clip(text, 4_000)
        candidates.append(
            {
                "ref": ref,
                "summary": content,
                "tokens": _estimate(content),
                "score": _memory_score(item),
            }
        )
        if len(candidates) >= limit:
            break
    return candidates


def _shadow_user(server: Any, user_id: int) -> Any:
    manager = getattr(server, "user_manager", None)
    if manager is None:
        return None
    try:
        return manager.get_by_id(user_id)
    except Exception:
        logger.debug("shadow retrieval user lookup failed user=%s", user_id, exc_info=True)
        return None


async def _retrieve_memory_candidates(
    server: Any,
    *,
    agent_id: str,
    user_id: int,
    query: str,
    enabled: bool,
) -> list[dict[str, Any]]:
    if not enabled:
        return []
    user = _shadow_user(server, user_id)
    if user is None:
        return []
    try:
        from octop.api.common.memory_client import call_memory_rpc  # noqa: PLC0415

        payload = await asyncio.to_thread(
            call_memory_rpc,
            agent_id=agent_id,
            method="list_atoms",
            params={
                "query": query,
                "include_deprecated": False,
                "order_by": "created_at",
                "order": "desc",
                "limit": _MEMORY_LIMIT,
            },
            user=user,
            as_user=None,
            server=server,
        )
        return _memory_candidates_from_payload(payload)
    except Exception:
        logger.debug("shadow memory retrieval skipped agent=%s", agent_id, exc_info=True)
        return []


async def _retrieve_knowledge_candidates(
    server: Any,
    *,
    agent_id: str,
    user_id: int,
    query: str,
    explicit_knowledge_base_ids: Sequence[str] | None,
    enabled: bool,
) -> list[dict[str, Any]]:
    if not enabled:
        return []
    services = getattr(server, "services", None)
    runtime = getattr(server, "app_runtime", None)
    registry = getattr(runtime, "agent_registry", None)
    if services is None or registry is None:
        return []
    user = _shadow_user(server, user_id)
    if user is None:
        return []
    try:
        if explicit_knowledge_base_ids is None:
            selected_ids = list(registry.default_knowledge_base_ids(agent_id) or ())
        else:
            selected_ids = list(
                dict.fromkeys(
                    str(item) for item in explicit_knowledge_base_ids if str(item).strip()
                )
            )
        if not selected_ids:
            return []
        knowledge_repo = services.knowledge_repo
        visible_bases = (
            knowledge_repo.list_all()
            if bool(getattr(user, "is_admin", False))
            else knowledge_repo.list_visible(user_id)
        )
        from octop.infra.knowledge.retrieve import retrieve_context  # noqa: PLC0415

        context = await retrieve_context(
            services,
            user_id=user_id,
            is_admin=bool(getattr(user, "is_admin", False)),
            query=query,
            knowledge_base_ids=selected_ids,
            k=_KNOWLEDGE_LIMIT,
            char_budget=_KNOWLEDGE_CHAR_BUDGET,
            visible_bases=visible_bases,
        )
        if not context.strip():
            return []
        return [
            {
                "ref": f"knowledge://retrieval/{_short_hash(query + chr(0) + ','.join(selected_ids))}",
                "excerpt": _clip(context, _KNOWLEDGE_CHAR_BUDGET),
                "tokens": _estimate(context),
                "score": 0.9,
            }
        ]
    except Exception:
        logger.debug("shadow knowledge retrieval skipped agent=%s", agent_id, exc_info=True)
        return []


async def _auto_retrieval_candidates(
    server: Any,
    *,
    agent_id: str,
    user_id: int,
    query: str,
    explicit_knowledge_base_ids: Sequence[str] | None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    services = getattr(server, "services", None)
    settings_repo = getattr(services, "settings_repo", None)
    memory_enabled = _setting_enabled(
        settings_repo,
        key="router.context.memory_enabled",
        env_key="OCTOP_ROUTER_CONTEXT_MEMORY_ENABLED",
    )
    knowledge_enabled = _setting_enabled(
        settings_repo,
        key="router.context.knowledge_enabled",
        env_key="OCTOP_ROUTER_CONTEXT_KNOWLEDGE_ENABLED",
    )
    memory_result, knowledge_result = await asyncio.gather(
        _retrieve_memory_candidates(
            server,
            agent_id=agent_id,
            user_id=user_id,
            query=query,
            enabled=memory_enabled,
        ),
        _retrieve_knowledge_candidates(
            server,
            agent_id=agent_id,
            user_id=user_id,
            query=query,
            explicit_knowledge_base_ids=explicit_knowledge_base_ids,
            enabled=knowledge_enabled,
        ),
        return_exceptions=True,
    )
    memory_candidates = memory_result if isinstance(memory_result, list) else []
    knowledge_candidates = knowledge_result if isinstance(knowledge_result, list) else []
    return memory_candidates, knowledge_candidates


def classify_shadow_route(
    text: str,
    *,
    has_attachments: bool = False,
    target_agent_ids: Sequence[str] = (),
) -> dict[str, Any]:
    """Return a deterministic Phase 0 routing proxy without invoking an LLM."""
    normalized = str(text or "").strip()
    lowered = f" {normalized.lower()} "
    read_hits = [term for term in _READ_ONLY_TERMS if term in lowered]
    side_effect_hits = [term for term in _SIDE_EFFECT_TERMS if term in lowered]
    input_kinds = ["text"]
    if has_attachments:
        input_kinds.append("attachment")
    if target_agent_ids:
        input_kinds.append("agent_mention")

    if side_effect_hits:
        intent = "side_effect_task"
        confidence = 0.86 if read_hits else 0.8
        task_type = "side_effect"
    elif read_hits:
        intent = "read_only_task"
        confidence = 0.74
        task_type = "read_only"
    else:
        intent = "direct_reply"
        confidence = 0.58
        task_type = "router_reply"

    requires_task = intent != "direct_reply"
    suggested_task = None
    if requires_task:
        suggested_task = {
            "task_type": task_type,
            "title": _clip(normalized or "Handle user request", 96),
            "subject_key": f"message:{_short_hash(normalized)}",
            "objective": _clip(normalized, 4_000),
            "requires_user": intent == "side_effect_task",
        }
    return {
        "policy_version": "shadow-heuristic-v1",
        "intent": intent,
        "confidence": confidence,
        "requires_task": requires_task,
        "suggested_task": suggested_task,
        "reasons": {
            "read_only_terms": read_hits,
            "side_effect_terms": side_effect_hits,
            "input_kinds": input_kinds,
        },
        "dispatch_enabled": False,
    }


def _recent_candidates(
    thread_message_repo: Any,
    *,
    thread_id: str,
    limit: int = 12,
) -> list[dict[str, Any]]:
    try:
        rows, _has_more = thread_message_repo.page(thread_id, limit=limit)
    except Exception:
        logger.warning("shadow router recent-history read failed thread=%s", thread_id)
        return []
    candidates: list[dict[str, Any]] = []
    for row in rows:
        text = _message_text(str(row.message_json))
        if not text:
            continue
        candidates.append(
            {
                "ref": f"thread://{thread_id}/seq/{row.seq}",
                "content": text,
                "tokens": _estimate(text),
                "created_at": int(row.created_at),
            }
        )
    return candidates


def _task_candidates(tasks: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    for task in tasks:
        capsule = task.get("capsule")
        capsule_text = (
            json.dumps(capsule, ensure_ascii=False, sort_keys=True)
            if isinstance(capsule, Mapping) and capsule
            else ""
        )
        content = capsule_text or str(task.get("objective") or "")
        content = _clip(content, 8_000)
        if not content:
            continue
        candidates.append(
            {
                "ref": f"task://{task['task_id']}",
                "summary": f"{task.get('title', '')}: {content}",
                "tokens": _estimate(content),
                "priority": int(task.get("priority", 0) or 0),
                "updated_at": int(task.get("updated_at", 0) or 0),
            }
        )
    return candidates


async def observe_shadow_turn(
    server: Any,
    *,
    agent_id: str,
    user_id: int,
    thread_id: str,
    session_key: str,
    text: str,
    has_attachments: bool = False,
    target_agent_ids: Sequence[str] = (),
    knowledge_base_ids: Sequence[str] | None = None,
    memory_candidates: Sequence[Mapping[str, Any]] = (),
    knowledge_candidates: Sequence[Mapping[str, Any]] = (),
) -> dict[str, Any]:
    """Persist one shadow routing sample; never changes the active turn."""
    services = getattr(server, "services", None)
    runtime = getattr(server, "app_runtime", None)
    if services is None or runtime is None:
        raise RuntimeError("router shadow observer requires an initialized server")
    service = ConversationService(services.db)
    conversation = service.ensure_main_conversation(
        agent_id=agent_id,
        user_id=user_id,
        router_thread_id=None,
    )
    conversation_id = str(conversation["conversation_id"])
    trace_id = f"shadow:{agent_id}:{uuid.uuid4().hex}"
    current_text = str(text or "").strip()
    if not memory_candidates or not knowledge_candidates:
        auto_memory, auto_knowledge = await _auto_retrieval_candidates(
            server,
            agent_id=agent_id,
            user_id=user_id,
            query=current_text,
            explicit_knowledge_base_ids=knowledge_base_ids,
        )
        if not memory_candidates:
            memory_candidates = auto_memory
        if not knowledge_candidates:
            knowledge_candidates = auto_knowledge
    recent = _recent_candidates(
        services.repos.thread_message_repo,
        thread_id=thread_id,
        limit=12,
    )
    if current_text:
        recent.append(
            {
                "ref": f"turn://current/{_short_hash(current_text)}",
                "content": _clip(current_text, 8_000),
                "tokens": _estimate(current_text),
                "created_at": int(time.time()),
            }
        )
    tasks = service.list_active_tasks(
        agent_id=agent_id,
        user_id=user_id,
        conversation_id=conversation_id,
        limit=20,
    )
    context_pack = compile_context_pack(
        task_capsules=_task_candidates(tasks),
        recent_discussion=recent,
        short_term_memory=memory_candidates,
        knowledge_base=knowledge_candidates,
    )
    pack = service.record_context_pack(
        conversation_id=conversation_id,
        trace_id=trace_id,
        policy_version=str(context_pack["policy_version"]),
        model="shadow-heuristic",
        used_tokens=int(context_pack["used_tokens"]),
        sections=context_pack["sections"],
        included_refs=context_pack["included_refs"],
        omitted_refs=context_pack["omitted_refs"],
    )
    decision = classify_shadow_route(
        current_text,
        has_attachments=has_attachments,
        target_agent_ids=target_agent_ids,
    )
    service.append_event(
        conversation_id=conversation_id,
        trace_id=trace_id,
        event_type="router.received",
        status="observed",
        summary="Observed one dashboard turn in shadow mode",
        payload={
            "agent_id": agent_id,
            "thread_id": thread_id,
            "session_key": session_key,
            "text_excerpt": _clip(current_text, 500),
            "input_kinds": decision["reasons"]["input_kinds"],
            "shadow": True,
        },
    )
    service.append_event(
        conversation_id=conversation_id,
        trace_id=trace_id,
        event_type="context.pack.compiled",
        status="recorded",
        summary=(
            f"Compiled {context_pack['used_tokens']} context tokens; "
            f"omitted {context_pack['omitted_count']} refs"
        ),
        payload_ref=f"context-pack://{pack['pack_id']}",
        payload={
            "pack_id": pack["pack_id"],
            "used_tokens": context_pack["used_tokens"],
            "omitted_count": context_pack["omitted_count"],
            "omitted_by_reason": context_pack["omitted_by_reason"],
            "omitted_refs_truncated": context_pack["omitted_refs_truncated"],
        },
    )
    service.append_event(
        conversation_id=conversation_id,
        trace_id=trace_id,
        event_type="router.shadow.decision",
        status=str(decision["intent"]),
        summary=(
            f"Shadow suggestion: {decision['intent']} "
            f"(confidence {decision['confidence']:.2f})"
        ),
        payload=decision,
    )
    return {
        "trace_id": trace_id,
        "conversation_id": conversation_id,
        "decision": decision,
        "context_pack": pack,
    }


def maybe_schedule_shadow_router(
    server: Any,
    *,
    agent_id: str,
    user_id: int,
    thread_id: str,
    session_key: str,
    text: str,
    has_attachments: bool = False,
    target_agent_ids: Sequence[str] = (),
    knowledge_base_ids: Sequence[str] | None = None,
) -> asyncio.Task[Any] | None:
    """Best-effort scheduling that cannot alter the live chat path."""
    if not str(text or "").strip():
        return None
    try:
        task = asyncio.create_task(
            observe_shadow_turn(
                server,
                agent_id=agent_id,
                user_id=user_id,
                thread_id=thread_id,
                session_key=session_key,
                text=text,
                has_attachments=has_attachments,
                target_agent_ids=target_agent_ids,
                knowledge_base_ids=knowledge_base_ids,
            ),
            name=f"router-shadow-{agent_id}",
        )
    except RuntimeError:
        return None

    def _done(done: asyncio.Task[Any]) -> None:
        try:
            done.result()
        except asyncio.CancelledError:
            return
        except Exception:
            logger.warning("router shadow observation failed", exc_info=True)

    task.add_done_callback(_done)
    return task


__all__ = [
    "classify_shadow_route",
    "maybe_schedule_shadow_router",
    "observe_shadow_turn",
]
