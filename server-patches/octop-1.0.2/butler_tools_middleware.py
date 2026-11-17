"""Reject non-dispatch tool calls on the Router butler.

``tools_disabled`` already hides them from the model, but a stale tool
reference or an MCP tool the catalog does not know about must not slip
through.  This middleware is the hard boundary and is only mounted on the
butler agent, never on Workers.

It also appends the dispatch contract to the system message on every model
call, so the butler gets its role even when the agent has no stored prompt
(for example before workspace bootstrap).
"""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from typing import Any

from deepagents.middleware._utils import append_to_system_message
from langchain.agents.middleware import AgentMiddleware, ModelRequest, ModelResponse
from langchain_core.messages import ToolMessage
from langgraph.prebuilt.tool_node import ToolCallRequest
from langgraph.types import Command

from octop.infra.agents.butler import BUTLER_PROMPT, BUTLER_TOOLS_ALLOWED

logger = logging.getLogger(__name__)

_DENIED = (
    "`{tool}` is not available to the butler. Hand this work to an expert with "
    "`ask_agent` and report the expert's result."
)


def _deny(tool_call: dict[str, Any]) -> ToolMessage:
    name = str(tool_call.get("name", ""))
    logger.info("butler tool blocked tool=%s", name)
    return ToolMessage(
        content=_DENIED.format(tool=name),
        tool_call_id=str(tool_call.get("id", "")),
        status="error",
    )


class ButlerToolsMiddleware(AgentMiddleware[Any, Any]):
    """Allow only dispatch, memory recall and time on the butler."""

    def wrap_model_call(
        self,
        request: ModelRequest,
        handler: Callable[[ModelRequest], ModelResponse],
    ) -> ModelResponse:
        return handler(self._with_contract(request))

    async def awrap_model_call(
        self,
        request: ModelRequest,
        handler: Callable[[ModelRequest], Awaitable[ModelResponse]],
    ) -> ModelResponse:
        return await handler(self._with_contract(request))

    def _with_contract(self, request: ModelRequest) -> ModelRequest:
        system = getattr(request, "system_message", None)
        if system is not None and BUTLER_PROMPT.splitlines()[0] in str(
            getattr(system, "content", "")
        ):
            return request
        return request.override(
            system_message=append_to_system_message(system, BUTLER_PROMPT)
        )

    def wrap_tool_call(
        self,
        request: ToolCallRequest,
        handler: Callable[[ToolCallRequest], ToolMessage | Command[Any]],
    ) -> ToolMessage | Command[Any]:
        tool_call = request.tool_call
        name = str(tool_call.get("name", ""))
        if not name or name in BUTLER_TOOLS_ALLOWED:
            return handler(request)
        return _deny(tool_call)

    async def awrap_tool_call(
        self,
        request: ToolCallRequest,
        handler: Callable[[ToolCallRequest], Awaitable[ToolMessage | Command[Any]]],
    ) -> ToolMessage | Command[Any]:
        tool_call = request.tool_call
        name = str(tool_call.get("name", ""))
        if not name or name in BUTLER_TOOLS_ALLOWED:
            return await handler(request)
        return _deny(tool_call)


__all__ = ["ButlerToolsMiddleware"]
