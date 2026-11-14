"""Fold historical tool results before a model request.

The model-visible conversation is intentionally different from the user's
history.  Tool results from earlier turns are offloaded to the agent workspace
and replaced by a small pointer, while results from the current turn stay
verbatim so the active task can still read them.

This only changes one ``ModelRequest``.  Checkpoints and ``thread_messages``
keep the complete results for replay and dashboard history.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import re
import threading
from collections.abc import Awaitable, Callable, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any

from langchain.agents.middleware import AgentMiddleware, ModelRequest, ModelResponse
from langchain_core.messages import AIMessage, AnyMessage, HumanMessage, ToolMessage

try:
    from deepagents.backends.utils import sanitize_tool_call_id
except Exception:  # pragma: no cover - defensive for older deepagents
    def sanitize_tool_call_id(value: str) -> str:  # type: ignore[misc]
        return re.sub(r"[^A-Za-z0-9_.-]+", "_", str(value or "tool"))


logger = logging.getLogger(__name__)

ENV_ENABLED = "OCTOP_TOOL_TRACE_OFFLOAD_ENABLED"
ENV_MIN_CHARS = "OCTOP_TOOL_TRACE_MIN_CHARS"
ENV_PREVIEW_CHARS = "OCTOP_TOOL_TRACE_PREVIEW_CHARS"
ENV_PREFIX = "OCTOP_TOOL_TRACE_PREFIX"
ENV_FAILURE_BURST = "OCTOP_TOOL_TRACE_FAILURE_BURST"

DEFAULT_MIN_CHARS = 2_000
DEFAULT_PREVIEW_CHARS = 700
DEFAULT_PREFIX = "/large_tool_results"
DEFAULT_FAILURE_BURST = 4
_STUB_MARKER = "[historical tool result compacted]"
_FAILURE_CAPSULE_MARKER = "[historical tool failure capsule]"
_EXISTING_STUB_MARKERS = (
    _STUB_MARKER,
    _FAILURE_CAPSULE_MARKER,
    "Tool result too large, the result of this tool call",
)


def _env_bool(name: str, default: bool) -> bool:
    raw = str(os.environ.get(name, "") or "").strip().lower()
    if not raw:
        return default
    return raw not in {"0", "false", "no", "off"}


def _env_nonnegative_int(name: str, default: int) -> int:
    raw = str(os.environ.get(name, "") or "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        return default
    return max(0, value)


@dataclass(frozen=True)
class ToolTracePolicy:
    enabled: bool = True
    min_chars: int = DEFAULT_MIN_CHARS
    preview_chars: int = DEFAULT_PREVIEW_CHARS
    artifact_prefix: str = DEFAULT_PREFIX
    max_args_chars: int = 240
    min_failure_burst: int = DEFAULT_FAILURE_BURST

    @classmethod
    def from_env(cls) -> ToolTracePolicy:
        return cls(
            enabled=_env_bool(ENV_ENABLED, True),
            min_chars=_env_nonnegative_int(ENV_MIN_CHARS, DEFAULT_MIN_CHARS),
            preview_chars=_env_nonnegative_int(ENV_PREVIEW_CHARS, DEFAULT_PREVIEW_CHARS),
            artifact_prefix=str(os.environ.get(ENV_PREFIX, DEFAULT_PREFIX) or DEFAULT_PREFIX).rstrip("/")
            or DEFAULT_PREFIX,
            min_failure_burst=_env_nonnegative_int(ENV_FAILURE_BURST, DEFAULT_FAILURE_BURST),
        )


@dataclass
class _OffloadCache:
    """Avoid rewriting the same artifact on every request."""

    _written: set[tuple[str, str]] = field(default_factory=set)
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def seen(self, path: str, content: str) -> bool:
        key = (path, hashlib.sha256(content.encode("utf-8")).hexdigest())
        with self._lock:
            return key in self._written

    def mark(self, path: str, content: str) -> None:
        key = (path, hashlib.sha256(content.encode("utf-8")).hexdigest())
        with self._lock:
            self._written.add(key)

    def clear(self) -> None:
        with self._lock:
            self._written.clear()


def _text_content(message: AnyMessage) -> str:
    content = getattr(message, "content", "")
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for block in content:
            if isinstance(block, dict) and block.get("type") == "text":
                parts.append(str(block.get("text") or ""))
            elif isinstance(block, str):
                parts.append(block)
        return "\n".join(parts)
    return str(content or "")


def _media_blocks(message: AnyMessage) -> list[Any]:
    content = getattr(message, "content", None)
    if not isinstance(content, list):
        return []
    return [block for block in content if isinstance(block, dict) and block.get("type") != "text"]


def _already_compacted(text: str) -> bool:
    return any(marker in text for marker in _EXISTING_STUB_MARKERS)


def _tool_call_index(messages: Sequence[AnyMessage]) -> dict[str, dict[str, Any]]:
    index: dict[str, dict[str, Any]] = {}
    for message in messages:
        if not isinstance(message, AIMessage):
            continue
        for call in message.tool_calls or []:
            call_id = call.get("id")
            if isinstance(call_id, str) and call_id:
                index[call_id] = call
    return index


def _is_summary_message(message: AnyMessage) -> bool:
    return (
        isinstance(message, HumanMessage)
        and getattr(message, "additional_kwargs", {}).get("lc_source") == "summarization"
    )


def _current_turn_start(messages: Sequence[AnyMessage]) -> int:
    """Index of the last real user message; earlier messages are historical."""
    last_summary = -1
    for index in range(len(messages) - 1, -1, -1):
        message = messages[index]
        if _is_summary_message(message):
            last_summary = max(last_summary, index)
            continue
        if isinstance(message, HumanMessage) and not _is_summary_message(message):
            if index < last_summary:
                return len(messages)
            return index
    return 0


def _summarize_text(text: str, limit: int) -> str:
    collapsed = " ".join(text.split())
    if len(collapsed) <= limit:
        return collapsed
    return collapsed[: max(0, limit - 1)].rstrip() + "…"


def _tool_name(message: ToolMessage, call: Mapping[str, Any] | None) -> str:
    name = call.get("name") if call else None
    if not isinstance(name, str) or not name:
        name = getattr(message, "name", None)
    return str(name or "tool")


def _args_summary(call: Mapping[str, Any] | None, *, limit: int) -> str:
    if not call:
        return ""
    args = call.get("args")
    if args is None:
        return ""
    try:
        raw = json.dumps(args, ensure_ascii=False, sort_keys=True)
    except (TypeError, ValueError):
        raw = str(args)
    return _summarize_text(raw, limit)


def _artifact_path(message: ToolMessage, *, prefix: str) -> str:
    raw_id = str(getattr(message, "tool_call_id", "") or "")
    safe_id = sanitize_tool_call_id(raw_id) if raw_id else f"unknown-{hashlib.sha256(_text_content(message).encode()).hexdigest()[:16]}"
    return f"{prefix}/{safe_id}"


def _stub_text(
    *,
    message: ToolMessage,
    call: Mapping[str, Any] | None,
    path: str | None,
    chars: int,
    preview_chars: int,
    max_args_chars: int,
) -> str:
    name = _tool_name(message, call)
    args = _args_summary(call, limit=max_args_chars)
    preview = _summarize_text(_text_content(message), preview_chars)
    status = str(getattr(message, "status", None) or "ok")
    lines = [
        _STUB_MARKER,
        f"tool: {name}",
        f"tool_call_id: {getattr(message, 'tool_call_id', '') or ''}",
    ]
    if args:
        lines.append(f"args: {args}")
    lines.extend(
        [
            f"status: {status}",
            f"characters: {chars}",
            f"artifact: {path or 'unavailable'}",
            f"summary: {preview}",
        ]
    )
    return "\n".join(lines)


def _replacement_content(message: ToolMessage, stub: str) -> str | list[Any]:
    media = _media_blocks(message)
    if not media:
        return stub
    return [{"type": "text", "text": stub}, *media]


def _replacement_message(
    message: ToolMessage,
    *,
    stub: str,
    path: str | None,
    chars: int,
) -> ToolMessage:
    kwargs = dict(getattr(message, "additional_kwargs", None) or {})
    kwargs["octop_tool_trace"] = {
        "compacted": True,
        "characters": chars,
        "artifact": path,
    }
    return ToolMessage(
        content=_replacement_content(message, stub),
        tool_call_id=message.tool_call_id,
        name=message.name,
        id=message.id,
        artifact=message.artifact,
        status=message.status,
        additional_kwargs=kwargs,
        response_metadata=dict(getattr(message, "response_metadata", None) or {}),
    )


def _candidate(message: AnyMessage, *, policy: ToolTracePolicy) -> bool:
    if not isinstance(message, ToolMessage):
        return False
    text = _text_content(message)
    if not text or len(text) < policy.min_chars:
        return False
    return not _already_compacted(text)


def _write_artifact_sync(*, backend: Any, path: str, content: str, cache: _OffloadCache) -> str | None:
    if cache.seen(path, content):
        return path
    try:
        result = backend.write(path, content)
    except Exception:
        logger.warning("tool trace offload failed path=%s", path, exc_info=True)
        return None
    if result is None or getattr(result, "error", None):
        logger.warning("tool trace offload rejected path=%s error=%s", path, getattr(result, "error", None))
        return None
    cache.mark(path, content)
    return str(getattr(result, "path", None) or path)


async def _write_artifact_async(*, backend: Any, path: str, content: str, cache: _OffloadCache) -> str | None:
    if cache.seen(path, content):
        return path
    try:
        result = await backend.awrite(path, content)
    except Exception:
        logger.warning("tool trace offload failed path=%s", path, exc_info=True)
        return None
    if result is None or getattr(result, "error", None):
        logger.warning("tool trace offload rejected path=%s error=%s", path, getattr(result, "error", None))
        return None
    cache.mark(path, content)
    return str(getattr(result, "path", None) or path)


@dataclass(frozen=True)
class _FailurePair:
    ai_index: int
    tool_index: int
    call: Mapping[str, Any]
    tool: ToolMessage


def _is_failure(message: ToolMessage) -> bool:
    status = str(getattr(message, "status", "") or "").strip().lower()
    return status in {"error", "failed", "failure"}


def _failure_pair(
    messages: Sequence[AnyMessage],
    index: int,
    *,
    turn_start: int,
) -> _FailurePair | None:
    if index + 1 >= turn_start:
        return None
    ai = messages[index]
    tool = messages[index + 1]
    if not isinstance(ai, AIMessage) or not isinstance(tool, ToolMessage):
        return None
    calls = ai.tool_calls or []
    if len(calls) != 1 or _text_content(ai).strip() or not _is_failure(tool):
        return None
    call = calls[0]
    if str(call.get("id") or "") != str(tool.tool_call_id or ""):
        return None
    return _FailurePair(ai_index=index, tool_index=index + 1, call=call, tool=tool)


def _failure_signature(pair: _FailurePair) -> tuple[str, str]:
    name = _tool_name(pair.tool, pair.call)
    first_line = _text_content(pair.tool).splitlines()[0] if _text_content(pair.tool) else ""
    first_line = re.sub(r"\s+with kwargs\s*\{.*", "", first_line)
    return name, _summarize_text(first_line, 160)


def _failure_burst_payload(pairs: Sequence[_FailurePair], *, max_args_chars: int) -> str:
    parts: list[str] = []
    for number, pair in enumerate(pairs, start=1):
        parts.append(
            "\n".join(
                [
                    f"## failure {number}",
                    f"tool_call_id: {pair.tool.tool_call_id or ''}",
                    f"tool: {_tool_name(pair.tool, pair.call)}",
                    f"args: {_args_summary(pair.call, limit=max_args_chars)}",
                    f"status: {getattr(pair.tool, 'status', None) or 'error'}",
                    "content:",
                    _text_content(pair.tool),
                ]
            )
        )
    return "\n\n".join(parts)


def _failure_capsule_text(
    pairs: Sequence[_FailurePair],
    *,
    path: str | None,
    max_args_chars: int,
    preview_chars: int,
) -> str:
    first = pairs[0]
    last = pairs[-1]
    last_error = _summarize_text(_text_content(last.tool), preview_chars)
    lines = [
        _FAILURE_CAPSULE_MARKER,
        f"tool: {_tool_name(first.tool, first.call)}",
        f"failures: {len(pairs)} consecutive",
        f"first_tool_call_id: {first.tool.tool_call_id or ''}",
        f"last_tool_call_id: {last.tool.tool_call_id or ''}",
        f"first_args: {_args_summary(first.call, limit=max_args_chars)}",
        f"last_args: {_args_summary(last.call, limit=max_args_chars)}",
        f"last_error: {last_error}",
        f"artifact: {path or 'unavailable'}",
        "detail: read the artifact only when the exact failed output is needed.",
    ]
    return "\n".join(lines)


def _failure_capsule_message(
    pairs: Sequence[_FailurePair],
    *,
    content: str,
    path: str | None,
) -> ToolMessage:
    first = pairs[0].tool
    kwargs = dict(getattr(first, "additional_kwargs", None) or {})
    kwargs["octop_tool_trace"] = {
        "compacted": True,
        "reason": "failure_burst",
        "failures": len(pairs),
        "artifact": path,
        "removed_tool_call_ids": [
            str(pair.tool.tool_call_id or "") for pair in pairs[1:]
        ],
    }
    return ToolMessage(
        content=content,
        tool_call_id=first.tool_call_id,
        name=first.name,
        id=first.id,
        artifact=first.artifact,
        status=first.status,
        additional_kwargs=kwargs,
        response_metadata=dict(getattr(first, "response_metadata", None) or {}),
    )


def _failure_burst_path(pairs: Sequence[_FailurePair], *, prefix: str) -> str:
    raw_id = str(pairs[0].tool.tool_call_id or "")
    safe_id = sanitize_tool_call_id(raw_id) if raw_id else hashlib.sha256(
        _text_content(pairs[0].tool).encode()
    ).hexdigest()[:16]
    return f"{prefix}/failure_burst_{safe_id}"


def compact_failure_bursts(
    messages: Sequence[AnyMessage],
    *,
    backend: Any,
    policy: ToolTracePolicy | None = None,
    cache: _OffloadCache | None = None,
) -> list[AnyMessage]:
    """Collapse consecutive historical failures into one model-visible capsule."""
    active_policy = policy or ToolTracePolicy.from_env()
    if (
        not active_policy.enabled
        or backend is None
        or active_policy.min_failure_burst <= 0
    ):
        return list(messages)

    turn_start = _current_turn_start(messages)
    offload_cache = cache or _OffloadCache()
    output: list[AnyMessage] = []
    index = 0
    changed = False
    while index < len(messages):
        if index >= turn_start:
            output.extend(messages[index:])
            break
        first = _failure_pair(messages, index, turn_start=turn_start)
        if first is None:
            output.append(messages[index])
            index += 1
            continue

        pairs = [first]
        signature = _failure_signature(first)
        cursor = index + 2
        while cursor < turn_start:
            pair = _failure_pair(messages, cursor, turn_start=turn_start)
            if pair is None or _failure_signature(pair) != signature:
                break
            pairs.append(pair)
            cursor += 2

        if len(pairs) < active_policy.min_failure_burst:
            output.extend(messages[index:cursor])
            index = cursor
            continue

        payload = _failure_burst_payload(pairs, max_args_chars=active_policy.max_args_chars)
        path = _write_artifact_sync(
            backend=backend,
            path=_failure_burst_path(pairs, prefix=active_policy.artifact_prefix),
            content=payload,
            cache=offload_cache,
        )
        capsule = _failure_capsule_text(
            pairs,
            path=path,
            max_args_chars=active_policy.max_args_chars,
            preview_chars=active_policy.preview_chars,
        )
        output.append(messages[first.ai_index])
        output.append(_failure_capsule_message(pairs, content=capsule, path=path))
        index = cursor
        changed = True

    return output if changed else list(messages)


async def acompact_failure_bursts(
    messages: Sequence[AnyMessage],
    *,
    backend: Any,
    policy: ToolTracePolicy | None = None,
    cache: _OffloadCache | None = None,
) -> list[AnyMessage]:
    """Async variant of :func:`compact_failure_bursts`."""
    active_policy = policy or ToolTracePolicy.from_env()
    if (
        not active_policy.enabled
        or backend is None
        or active_policy.min_failure_burst <= 0
    ):
        return list(messages)

    turn_start = _current_turn_start(messages)
    offload_cache = cache or _OffloadCache()
    groups: list[tuple[list[_FailurePair], int]] = []
    index = 0
    while index < turn_start:
        first = _failure_pair(messages, index, turn_start=turn_start)
        if first is None:
            index += 1
            continue
        pairs = [first]
        signature = _failure_signature(first)
        cursor = index + 2
        while cursor < turn_start:
            pair = _failure_pair(messages, cursor, turn_start=turn_start)
            if pair is None or _failure_signature(pair) != signature:
                break
            pairs.append(pair)
            cursor += 2
        if len(pairs) >= active_policy.min_failure_burst:
            groups.append((pairs, cursor))
        index = cursor

    if not groups:
        return list(messages)

    paths = await asyncio.gather(
        *(
            _write_artifact_async(
                backend=backend,
                path=_failure_burst_path(pairs, prefix=active_policy.artifact_prefix),
                content=_failure_burst_payload(
                    pairs,
                    max_args_chars=active_policy.max_args_chars,
                ),
                cache=offload_cache,
            )
            for pairs, _ in groups
        )
    )
    replacements = {
        pairs[0].ai_index: (
            pairs[0],
            _failure_capsule_message(
                pairs,
                content=_failure_capsule_text(
                    pairs,
                    path=path,
                    max_args_chars=active_policy.max_args_chars,
                    preview_chars=active_policy.preview_chars,
                ),
                path=path,
            ),
            end,
        )
        for (pairs, end), path in zip(groups, paths, strict=True)
    }
    output: list[AnyMessage] = []
    index = 0
    while index < len(messages):
        replacement = replacements.get(index)
        if replacement is None:
            output.append(messages[index])
            index += 1
            continue
        first, capsule, end = replacement
        output.append(messages[first.ai_index])
        output.append(capsule)
        index = end
    return output


def fold_historical_tool_results(
    messages: Sequence[AnyMessage],
    *,
    backend: Any,
    policy: ToolTracePolicy | None = None,
    cache: _OffloadCache | None = None,
) -> list[AnyMessage]:
    """Return a model-visible copy with historical tool results compacted."""
    active_policy = policy or ToolTracePolicy.from_env()
    if not active_policy.enabled or backend is None:
        return list(messages)

    turn_start = _current_turn_start(messages)
    calls = _tool_call_index(messages)
    offload_cache = cache or _OffloadCache()
    output = list(messages)
    changed = False
    for index, message in enumerate(messages):
        if index >= turn_start or not _candidate(message, policy=active_policy):
            continue
        assert isinstance(message, ToolMessage)
        text = _text_content(message)
        raw_path = _artifact_path(message, prefix=active_policy.artifact_prefix)
        path = _write_artifact_sync(
            backend=backend,
            path=raw_path,
            content=text,
            cache=offload_cache,
        )
        stub = _stub_text(
            message=message,
            call=calls.get(str(message.tool_call_id or "")),
            path=path,
            chars=len(text),
            preview_chars=active_policy.preview_chars,
            max_args_chars=active_policy.max_args_chars,
        )
        output[index] = _replacement_message(message, stub=stub, path=path, chars=len(text))
        changed = True
    return output if changed else list(messages)


async def afold_historical_tool_results(
    messages: Sequence[AnyMessage],
    *,
    backend: Any,
    policy: ToolTracePolicy | None = None,
    cache: _OffloadCache | None = None,
) -> list[AnyMessage]:
    """Async variant of :func:`fold_historical_tool_results`."""
    active_policy = policy or ToolTracePolicy.from_env()
    if not active_policy.enabled or backend is None:
        return list(messages)

    turn_start = _current_turn_start(messages)
    calls = _tool_call_index(messages)
    offload_cache = cache or _OffloadCache()
    candidates: list[tuple[int, ToolMessage, str, str]] = []
    for index, message in enumerate(messages):
        if index >= turn_start or not _candidate(message, policy=active_policy):
            continue
        assert isinstance(message, ToolMessage)
        text = _text_content(message)
        candidates.append((index, message, text, _artifact_path(message, prefix=active_policy.artifact_prefix)))
    if not candidates:
        return list(messages)

    paths = await asyncio.gather(
        *(
            _write_artifact_async(
                backend=backend,
                path=path,
                content=text,
                cache=offload_cache,
            )
            for _, _, text, path in candidates
        )
    )
    output = list(messages)
    for (index, message, text, _), path in zip(candidates, paths, strict=True):
        stub = _stub_text(
            message=message,
            call=calls.get(str(message.tool_call_id or "")),
            path=path,
            chars=len(text),
            preview_chars=active_policy.preview_chars,
            max_args_chars=active_policy.max_args_chars,
        )
        output[index] = _replacement_message(message, stub=stub, path=path, chars=len(text))
    return output


class ToolTraceGovernanceMiddleware(AgentMiddleware[Any, Any]):
    """Compact historical tool results before the wrapped model call."""

    name = "ToolTraceGovernanceMiddleware"

    def __init__(
        self,
        *,
        backend: Any,
        policy: ToolTracePolicy | None = None,
    ) -> None:
        super().__init__()
        self._backend = backend
        self._policy = policy or ToolTracePolicy.from_env()
        self._cache = _OffloadCache()

    def prepare(self, request: ModelRequest) -> ModelRequest:
        messages = compact_failure_bursts(
            request.messages,
            backend=self._backend,
            policy=self._policy,
            cache=self._cache,
        )
        messages = fold_historical_tool_results(
            messages,
            backend=self._backend,
            policy=self._policy,
            cache=self._cache,
        )
        return request.override(messages=messages)

    async def aprepare(self, request: ModelRequest) -> ModelRequest:
        messages = await acompact_failure_bursts(
            request.messages,
            backend=self._backend,
            policy=self._policy,
            cache=self._cache,
        )
        messages = await afold_historical_tool_results(
            messages,
            backend=self._backend,
            policy=self._policy,
            cache=self._cache,
        )
        return request.override(messages=messages)

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
        return await handler(await self.aprepare(request))


def install_tool_trace_governance(
    middleware: Any,
    *,
    backend: Any,
    policy: ToolTracePolicy | None = None,
) -> Any:
    """Wrap a SummarizationMiddleware so compaction sees the folded request first."""
    if middleware is None or getattr(middleware, "_octop_tool_trace_installed", False):
        return middleware
    governance = ToolTraceGovernanceMiddleware(backend=backend, policy=policy)
    original_sync = getattr(middleware, "wrap_model_call", None)
    original_async = getattr(middleware, "awrap_model_call", None)

    if callable(original_sync):
        def wrap_model_call(request: ModelRequest, handler: Callable[[ModelRequest], ModelResponse]) -> ModelResponse:
            return original_sync(governance.prepare(request), handler)

        middleware.wrap_model_call = wrap_model_call

    if callable(original_async):
        async def awrap_model_call(
            request: ModelRequest,
            handler: Callable[[ModelRequest], Awaitable[ModelResponse]],
        ) -> ModelResponse:
            return await original_async(await governance.aprepare(request), handler)

        middleware.awrap_model_call = awrap_model_call

    middleware._octop_tool_trace_installed = True
    return middleware


__all__ = [
    "ToolTraceGovernanceMiddleware",
    "ToolTracePolicy",
    "acompact_failure_bursts",
    "afold_historical_tool_results",
    "compact_failure_bursts",
    "fold_historical_tool_results",
    "install_tool_trace_governance",
]
