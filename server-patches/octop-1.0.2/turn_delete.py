"""Delete one complete user/assistant turn from a LangGraph checkpoint."""

from __future__ import annotations

from typing import Any

from octop.infra.errors import ErrorCode, OctopError


def _message_id(message: Any) -> str:
    value = message.get("id") if isinstance(message, dict) else getattr(message, "id", None)
    return str(value).strip() if value else ""


def _message_role(message: Any) -> str:
    if isinstance(message, dict):
        return str(message.get("role") or message.get("type") or "").lower()
    return str(getattr(message, "role", None) or getattr(message, "type", "")).lower()


def without_selected_turn(messages: list[Any], message_id: str) -> tuple[list[Any], list[str]]:
    """Remove the user message and all replies up to the next user message."""
    selected = next((i for i, item in enumerate(messages) if _message_id(item) == message_id), -1)
    if selected < 0:
        raise OctopError(ErrorCode.NOT_FOUND, "message not found in thread")

    start = next(
        (i for i in range(selected, -1, -1) if _message_role(messages[i]) in ("user", "human")),
        -1,
    )
    if start < 0:
        raise OctopError(ErrorCode.NOT_FOUND, "message is not part of a user turn")
    end = next(
        (
            i
            for i in range(start + 1, len(messages))
            if _message_role(messages[i]) in ("user", "human")
        ),
        len(messages),
    )
    removed = [_message_id(item) for item in messages[start:end]]
    if any(not item for item in removed):
        raise OctopError(ErrorCode.AGENT_FAILED, "turn contains a message without an id")
    return messages[:start] + messages[end:], removed


async def delete_checkpoint_turn(
    harness: Any,
    *,
    thread_id: str,
    message_id: str,
) -> tuple[list[Any], list[str]]:
    """Rewrite graph state without one turn and return the resulting transcript."""
    from langchain_core.messages import RemoveMessage
    from langgraph.graph.message import REMOVE_ALL_MESSAGES
    from octop.infra.agents.threads.fork import load_checkpoint_messages

    messages = await load_checkpoint_messages(harness, thread_id)
    remaining, removed = without_selected_turn(messages, message_id)
    graph = getattr(harness, "graph", None)
    update = getattr(graph, "aupdate_state", None) if graph is not None else None
    if update is None:
        raise OctopError(ErrorCode.AGENT_NOT_RUNNING, "agent checkpoint cannot be edited")
    await update(
        {"configurable": {"thread_id": thread_id}},
        {"messages": [RemoveMessage(id=REMOVE_ALL_MESSAGES), *remaining]},
    )
    return remaining, removed
