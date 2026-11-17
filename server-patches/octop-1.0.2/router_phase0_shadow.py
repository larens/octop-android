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
import re
import time
import uuid
from collections.abc import Mapping, Sequence
from typing import Any

try:
    from router_phase0_context import compile_context_pack, estimate_text_tokens
    from router_phase0_service import ConversationService
except ImportError:  # Installed modules import the site-package siblings.
    from octop.infra.conversation.context import compile_context_pack, estimate_text_tokens
    from octop.infra.conversation.service import ConversationService


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
    "读取",
    "查看",
    "列出",
    "生成",
    "制作",
    "预览",
    "日历",
    "日程",
    "邮件",
    "文档",
    "文件",
    "下载",
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
    "read only",
    "only read",
    "lookup",
    "chart",
    "html",
)
_SIDE_EFFECT_PATTERNS = (
    ("send", re.compile(r"(?:发送给|发送到|发送邮件|发送消息|帮我发送|直接发送|并发送|再发送)")),
    ("reply_external", re.compile(r"(?:帮我回复|回复邮件|回复消息|回复给|reply\s+to)", re.I)),
    ("delete", re.compile(r"(?:删除|删掉|\bdelete\b|\bremove\b)", re.I)),
    ("create_task", re.compile(r"(?:新建任务|创建任务|建一个任务|create\s+(?:a\s+)?task)", re.I)),
    (
        "modify",
        re.compile(
            r"(?:修改|更新|调整).{0,12}(?:日程|任务|会议|文件|设置|日历)"
            r"|\b(?:update|modify)\s+(?:the\s+)?(?:calendar|event|task|file|setting)",
            re.I,
        ),
    ),
    ("reserve", re.compile(r"(?:预约|预订|\bbook\b|\breserve\b)", re.I)),
    (
        "order",
        re.compile(
            r"(?:帮我下单|直接下单|执行下单|现在就下单|确认下单|提交订单"
            r"|\bplace\s+order\b|\bcreate\s*order\b|\bsubmit\s+order\b)",
            re.I,
        ),
    ),
    (
        "pay",
        re.compile(
            r"(?:发起支付|完成支付|立即支付|帮我支付|付款给"
            r"|\bpay\s+(?:for|the|with)\b)",
            re.I,
        ),
    ),
    ("submit", re.compile(r"(?:提交|\bsubmit\b)", re.I)),
    ("cancel", re.compile(r"(?:取消|\bcancel\b)", re.I)),
    ("reminder", re.compile(r"(?:提醒我|\bremind\s+me\b)", re.I)),
    (
        "retry",
        re.compile(r"(?:重试|重新跑|重跑|重新执行|\bretry\b|\brerun\b)", re.I),
    ),
    (
        "write_state",
        re.compile(
            r"(?:写入|另存|落入.{0,12}记忆|存到.{0,12}(?:记忆|知识库)"
            r"|\bwrite\s+to\b|\bsave\s+to\s+(?:memory|the\s+knowledge\s+base)\b)",
            re.I,
        ),
    ),
)
_NEGATION_RE = re.compile(
    r"(?:不要|不用|无需|禁止|不允许|不能|别|do\s+not|don't|without)",
    re.I,
)
_HOW_TO_RE = re.compile(
    r"(?:(?:怎么|如何|怎样)把|^\s*how\s+(?:do|can|should|to)\b)",
    re.I,
)
_MEMORY_RECALL_RE = re.compile(
    r"(?:你还记得|还记得|上次我(?:说|提)|之前我(?:说|提)|我的偏好|我习惯|"
    r"我一般|我以前|记忆里|recall|do you remember|what did i)",
    re.I,
)
_FEED_FOLLOWUP_RE = re.compile(
    r"(?:(?:这条|那条|这个|那个|上一条|刚才那条).{0,6}"
    r"(?:新闻|推送|事件|提醒|卡片|消息)"
    r"|(?:新闻|推送|事件|提醒|卡片).{0,6}(?:是什么|怎么回事|展开|详情|说说))",
    re.I,
)
_MULTI_STEP_RE = re.compile(
    r"(?:先.{0,24}(?:再|然后)|然后.{0,24}(?:再|并)|分[步两三四五六七八九十]+步|"
    r"端到端|依次执行|先做.{0,24}再做|step\s+by\s+step)",
    re.I,
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


def _setting_value(
    settings_repo: Any,
    *,
    key: str,
    env_key: str,
    default: str = "",
) -> str:
    raw = os.environ.get(env_key)
    if raw is None:
        try:
            raw = settings_repo.get(key) if settings_repo is not None else None
        except Exception:
            logger.debug("shadow setting read failed key=%s", key, exc_info=True)
            raw = None
    if raw is None:
        return default
    return str(raw).strip()


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
    feed_event_count: int = 0,
) -> dict[str, Any]:
    """Return a deterministic Phase 0 routing proxy without invoking an LLM."""
    normalized = str(text or "").strip()
    lowered = f" {normalized.lower()} "
    read_hits = [term for term in _READ_ONLY_TERMS if term in lowered]
    side_effect_hits: list[str] = []
    for label, pattern in _SIDE_EFFECT_PATTERNS:
        for match in pattern.finditer(lowered):
            prefix = lowered[max(0, match.start() - 32) : match.start()]
            if _NEGATION_RE.search(prefix):
                continue
            side_effect_hits.append(label)
            break
    input_kinds = ["text"]
    if has_attachments:
        input_kinds.append("attachment")
    if target_agent_ids:
        input_kinds.append("agent_mention")

    if side_effect_hits:
        intent = "side_effect_task"
        multi_step = bool(_MULTI_STEP_RE.search(normalized)) or len(set(side_effect_hits)) >= 2
        route_class = "multi_step_worker" if multi_step else "side_effect_task"
        confidence = 0.86 if read_hits else 0.8
        task_type = "side_effect"
    elif feed_event_count > 0 and _FEED_FOLLOWUP_RE.search(normalized):
        intent = "feed_event"
        route_class = "feed_event"
        confidence = 0.78
        task_type = "router_reply"
    elif _MEMORY_RECALL_RE.search(normalized):
        intent = "memory_recall"
        route_class = "memory_recall"
        confidence = 0.72
        task_type = "router_reply"
    elif read_hits and not _HOW_TO_RE.search(normalized):
        intent = "read_only_task"
        route_class = "read_only_tool"
        confidence = 0.74
        task_type = "read_only"
    else:
        intent = "direct_reply"
        route_class = "direct_reply"
        confidence = 0.58
        task_type = "router_reply"

    requires_task = route_class in {
        "read_only_tool",
        "side_effect_task",
        "multi_step_worker",
    }
    suggested_task = None
    if requires_task:
        suggested_task = {
            "task_type": task_type,
            "title": _clip(normalized or "Handle user request", 96),
            "subject_key": f"message:{_short_hash(normalized)}",
            "objective": _clip(normalized, 4_000),
            "requires_user": route_class == "side_effect_task",
        }
    return {
        "policy_version": "shadow-heuristic-v3",
        "intent": intent,
        "route_class": route_class,
        "confidence": confidence,
        "requires_task": requires_task,
        "worker_required": route_class == "multi_step_worker",
        "requires_confirmation": route_class == "side_effect_task",
        "suggested_task": suggested_task,
        "reasons": {
            "read_only_terms": read_hits,
            "side_effect_terms": side_effect_hits,
            "input_kinds": input_kinds,
            "feed_followup": route_class == "feed_event",
            "memory_recall": route_class == "memory_recall",
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


def project_task_capsule(task: Mapping[str, Any]) -> dict[str, str]:
    """Project an internal task into the bounded main-conversation capsule."""
    raw = task.get("capsule")
    capsule: Mapping[str, Any] = raw if isinstance(raw, Mapping) else {}
    goal = _clip(
        capsule.get("goal")
        or capsule.get("objective")
        or task.get("objective")
        or task.get("title")
        or "",
        600,
    )
    result = _clip(
        capsule.get("result")
        or capsule.get("output_excerpt")
        or capsule.get("summary")
        or "",
        900,
    )
    evidence = _clip(
        capsule.get("evidence")
        or capsule.get("context_pack_id")
        or capsule.get("trace_id")
        or "",
        300,
    )
    status = str(task.get("status") or "open")
    next_action = _clip(
        capsule.get("next_action")
        or (
            "等待用户确认"
            if status == "needs_user" or bool(task.get("requires_user"))
            else ("等待执行结果" if status in {"open", "in_progress"} else "")
        ),
        200,
    )
    return {
        "goal": goal,
        "result": result,
        "evidence": evidence,
        "status": status,
        "next_action": next_action,
    }


def render_task_capsule(task: Mapping[str, Any]) -> str:
    capsule = project_task_capsule(task)
    parts = [f"目标: {capsule['goal'] or task.get('title', '')}"]
    if capsule["result"]:
        parts.append(f"结果: {capsule['result']}")
    if capsule["evidence"]:
        parts.append(f"引用: {capsule['evidence']}")
    parts.append(f"状态: {capsule['status']}")
    if capsule["next_action"]:
        parts.append(f"后续: {capsule['next_action']}")
    return "\n".join(parts)


def _task_candidates(tasks: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    for task in tasks:
        content = _clip(render_task_capsule(task), 8_000)
        if not content:
            continue
        candidates.append(
            {
                "ref": f"task://{task['task_id']}",
                "summary": content,
                "tokens": _estimate(content),
                "priority": int(task.get("priority", 0) or 0),
                "updated_at": int(task.get("updated_at", 0) or 0),
            }
        )
    return candidates


def _feed_candidates(
    services: Any,
    *,
    agent_id: str,
    user_id: int,
    limit: int = 4,
) -> list[dict[str, Any]]:
    try:
        from octop.infra.conversation.feed import fetch_feed_candidates
    except ImportError:
        try:
            from feed_governance import fetch_feed_candidates
        except ImportError:
            return []
    try:
        with services.db.connect() as conn:
            items = fetch_feed_candidates(
                conn,
                agent_id=agent_id,
                user_id=user_id,
                limit=limit,
            )
    except Exception:
        logger.debug("shadow feed candidates skipped agent=%s", agent_id, exc_info=True)
        return []
    for item in items:
        item["tokens"] = max(1, _estimate(str(item.get("content") or "")))
    return items


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
    feed_items = _feed_candidates(
        services,
        agent_id=agent_id,
        user_id=user_id,
    )
    context_pack = compile_context_pack(
        task_capsules=_task_candidates(tasks),
        recent_discussion=recent,
        feed_events=feed_items,
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
        feed_event_count=len(feed_items),
    )
    settings_repo = getattr(services, "settings_repo", None)
    dispatch_enabled = _setting_enabled(
        settings_repo,
        key="router.worker_dispatch.enabled",
        env_key="OCTOP_ROUTER_WORKER_DISPATCH_ENABLED",
        default=False,
    )
    decision["feed_event_count"] = len(feed_items)
    decision["worker_dispatch_enabled"] = dispatch_enabled
    decision["worker_dispatch_candidate"] = bool(
        decision["worker_required"] and dispatch_enabled
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
        event_type="router.gate.decision",
        status=(
            "worker_candidate"
            if decision["worker_dispatch_candidate"]
            else "inline"
        ),
        summary=(
            f"Gate: {decision['route_class']} "
            f"(worker_required={decision['worker_required']}, "
            f"dispatch_enabled={dispatch_enabled})"
        ),
        payload={
            "route_class": decision["route_class"],
            "worker_required": decision["worker_required"],
            "requires_confirmation": decision["requires_confirmation"],
            "worker_dispatch_enabled": dispatch_enabled,
            "worker_dispatch_candidate": decision["worker_dispatch_candidate"],
            "feed_event_count": len(feed_items),
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
        "agent_id": agent_id,
        "decision": decision,
        "context_pack": pack,
        "feed_events": feed_items,
    }


async def _maybe_propose_auto_plan(
    server: Any,
    *,
    observed: Mapping[str, Any],
    text: str,
    has_attachments: bool,
    target_agent_ids: Sequence[str],
) -> None:
    runtime = getattr(server, "app_runtime", None)
    worker = getattr(runtime, "router_worker_service", None)
    if worker is None or not bool(getattr(worker, "ready", False)):
        return
    services = getattr(server, "services", None)
    settings_repo = getattr(services, "settings_repo", None)
    butler_mode = _setting_value(
        settings_repo,
        key="router.butler.mode",
        env_key="OCTOP_ROUTER_BUTLER_MODE",
        default="off",
    ).lower()
    butler_agent_id = _setting_value(
        settings_repo,
        key="router.butler.agent_id",
        env_key="OCTOP_ROUTER_BUTLER_AGENT_ID",
    )
    observed_agent_id = str(observed.get("agent_id") or "")
    is_butler = bool(butler_agent_id) and observed_agent_id == butler_agent_id and (
        butler_mode in {"assist", "auto"}
    )
    planning_enabled = _setting_enabled(
        settings_repo,
        key="router.planning.auto_enabled",
        env_key="OCTOP_ROUTER_PLANNING_AUTO_ENABLED",
        default=False,
    )
    if not is_butler and not planning_enabled:
        return
    from octop.infra.conversation.orchestrator import (  # noqa: PLC0415
        should_propose_auto_plan,
    )

    decision = observed.get("decision")
    if not isinstance(decision, Mapping) or not should_propose_auto_plan(
        text=text,
        decision=decision,
        has_attachments=has_attachments,
        target_agent_ids=target_agent_ids,
    ):
        return
    try:
        await worker.propose_dispatch_plan_from_observation(
            observed=observed,
            objective=text,
        )
    except Exception:
        logger.warning(
            "automatic dispatch-plan proposal failed agent=%s",
            getattr(worker, "agent_id", ""),
            exc_info=True,
        )


async def _observe_and_maybe_plan(
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
) -> Mapping[str, Any]:
    observed = await observe_shadow_turn(
        server,
        agent_id=agent_id,
        user_id=user_id,
        thread_id=thread_id,
        session_key=session_key,
        text=text,
        has_attachments=has_attachments,
        target_agent_ids=target_agent_ids,
        knowledge_base_ids=knowledge_base_ids,
    )
    await _maybe_propose_auto_plan(
        server,
        observed=observed,
        text=text,
        has_attachments=has_attachments,
        target_agent_ids=target_agent_ids,
    )
    return observed


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
            _observe_and_maybe_plan(
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
