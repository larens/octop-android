"""Mirror successful Didi ride MCP results into Today."""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from typing import Any

from langchain.agents.middleware import AgentMiddleware
from langchain_core.messages import ToolMessage
from langgraph.prebuilt.tool_node import ToolCallRequest
from langgraph.types import Command

from octop.infra.agents.middleware.thread_artifacts import current_thread_id
from octop.infra.today.didi_ride_cards import publish_didi_ride_today_card

logger = logging.getLogger(__name__)


class DidiRideTodayMiddleware(AgentMiddleware[Any, Any]):
    """Publish a stable Today card after read-only Didi order status results."""

    def __init__(self, *, db: Any, agent_id: str, user_id: int | None) -> None:
        super().__init__()
        self._db = db
        self._agent_id = str(agent_id or "").strip()
        self._user_id = user_id

    def wrap_tool_call(
        self,
        request: ToolCallRequest,
        handler: Callable[[ToolCallRequest], ToolMessage | Command[Any]],
    ) -> ToolMessage | Command[Any]:
        result = handler(request)
        self._record(request, result)
        return result

    async def awrap_tool_call(
        self,
        request: ToolCallRequest,
        handler: Callable[[ToolCallRequest], Awaitable[ToolMessage | Command[Any]]],
    ) -> ToolMessage | Command[Any]:
        result = await handler(request)
        self._record(request, result)
        return result

    def _record(
        self,
        request: ToolCallRequest,
        result: ToolMessage | Command[Any],
    ) -> None:
        if not isinstance(result, ToolMessage):
            return
        if getattr(result, "status", None) == "error":
            return
        if not self._agent_id or self._user_id is None:
            return
        thread_id = current_thread_id()
        if not thread_id:
            return
        tool_call = request.tool_call
        tool_name = str(tool_call.get("name") or "")
        raw_args = tool_call.get("args")
        try:
            publish_didi_ride_today_card(
                self._db,
                agent_id=self._agent_id,
                user_id=int(self._user_id),
                thread_id=thread_id,
                tool_name=tool_name,
                args=raw_args if isinstance(raw_args, dict) else None,
                artifact=result.artifact,
                content=result.content,
            )
        except Exception:
            logger.warning(
                "failed to publish Didi Today card for agent=%s tool=%s",
                self._agent_id,
                tool_name,
                exc_info=True,
            )


__all__ = ["DidiRideTodayMiddleware"]
