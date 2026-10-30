"""Install the turn deletion route into Octop 1.0.2."""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from patch_paths import backups_dir, octop_site


module_source = Path(__file__).with_name("turn_delete.py")
module_target = octop_site() / "infra/agents/threads/turn_delete.py"
module_target.parent.mkdir(parents=True, exist_ok=True)
if module_target.exists() and module_target.read_bytes() != module_source.read_bytes():
    backup = backups_dir() / f"turn-delete-{time.strftime('%Y%m%d-%H%M%S')}"
    backup.mkdir(parents=True, exist_ok=True)
    shutil.copy2(module_target, backup / "turn_delete.py")
shutil.copy2(module_source, module_target)

path = octop_site() / "api/routers/chat/history.py"
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
