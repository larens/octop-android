"""Install the turn deletion route into Octop 1.0.2."""

from __future__ import annotations

from pathlib import Path


path = Path("/opt/octop/venv/lib/python3.12/site-packages/octop/api/routers/chat/history.py")
source = path.read_text()
import_anchor = "from octop.infra.agents.threads.fork import fork_dashboard_thread\n"
route_anchor = '@router.delete("/agents/{agent_id}/threads/{thread_id}", status_code=204, summary="Delete thread")\n'
route = '''@router.delete(
    "/agents/{agent_id}/threads/{thread_id}/turns/{message_id}",
    status_code=204,
    summary="Delete conversation turn",
)
async def delete_conversation_turn(
    agent_id: str,
    thread_id: str,
    message_id: str,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> Response:
    """Remove one complete exchange from history and future model context."""
    _require_thread(server, agent_id, thread_id, user, as_user)
    hub = server.app_runtime.gateway.ws_hub
    if hub.is_turn_active(thread_id):
        raise OctopError(ErrorCode.AGENT_BUSY, "cannot delete while the turn is active")
    harness = server.app_runtime.agent_registry.get_agent(agent_id)
    remaining, _removed = await delete_checkpoint_turn(
        harness,
        thread_id=thread_id,
        message_id=message_id,
    )
    from octop.infra.history.projection import message_inputs  # noqa: PLC0415

    server.services.thread_message_repo.replace_all(thread_id, message_inputs(remaining))
    return Response(status_code=204)


'''
if "delete_checkpoint_turn" not in source:
    if import_anchor not in source or route_anchor not in source:
        raise SystemExit("Octop 1.0.2 history.py anchors not found")
    source = source.replace(
        import_anchor,
        import_anchor + "from octop.infra.agents.threads.turn_delete import delete_checkpoint_turn\n",
        1,
    )
    source = source.replace(route_anchor, route + route_anchor, 1)
    path.write_text(source)
