"""Read-only Phase 0 APIs for the stable mobile main conversation."""

from __future__ import annotations

from typing import Any, Literal, cast

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field

from octop.api.common.agent import require_agent_row
from octop.api.deps import current_user, get_server
from octop.infra.conversation.orchestrator import (
    RouterWorkerService,
    WorkerDispatchRejected,
    WorkerDispatchUnavailable,
)
from octop.infra.conversation.service import ConversationService, NotFoundError
from octop.infra.errors import ErrorCode, OctopError
from octop.infra.gateway.threads import ThreadRegistry

router = APIRouter()
TaskStatus = Literal["open", "in_progress", "needs_user", "done", "failed", "cancelled", "expired"]


class ReadOnlyTaskBody(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    objective: str = Field(min_length=1, max_length=20_000)
    subject_key: str | None = Field(default=None, max_length=500)


def _effective_user_id(user: Any, as_user: int | None) -> int:
    return as_user if as_user is not None else int(user.id)


def _service(server: Any) -> ConversationService:
    if server.services is None:
        raise OctopError(ErrorCode.INTERNAL_ERROR, "server services are unavailable")
    return ConversationService(server.services.db)


def _not_found(message: str) -> OctopError:
    return OctopError(ErrorCode.NOT_FOUND, message)


def _worker_service(server: Any) -> RouterWorkerService:
    runtime = getattr(server, "app_runtime", None)
    worker = getattr(runtime, "router_worker_service", None)
    if worker is None:
        raise OctopError(
            ErrorCode.INTERNAL_ERROR,
            "Router Worker service is unavailable",
            status=503,
        )
    return cast(RouterWorkerService, worker)


def _conversation(
    *,
    agent_id: str,
    user: Any,
    as_user: int | None,
    server: Any,
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    user_id = _effective_user_id(user, as_user)
    registry = server.app_runtime.gateway.thread_registry
    session_key = ThreadRegistry.dashboard_key(agent_id=agent_id, user_id=user_id)
    return _service(server).ensure_main_conversation(
        agent_id=agent_id,
        user_id=user_id,
        router_thread_id=registry.get_bound_thread_id(session_key),
    )


@router.get(
    "/agents/{agent_id}/main-conversation",
    summary="Get the stable main conversation",
)
async def get_main_conversation(
    agent_id: str,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    """Return the user's per-agent stable conversation without changing Thread APIs."""
    return _conversation(agent_id=agent_id, user=user, as_user=as_user, server=server)


@router.get(
    "/agents/{agent_id}/main-conversation/events",
    summary="Read main conversation events",
)
async def list_main_conversation_events(
    agent_id: str,
    after_seq: int = Query(default=0, ge=0),
    limit: int = Query(default=100, ge=1, le=500),
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> list[dict[str, Any]]:
    conversation = _conversation(agent_id=agent_id, user=user, as_user=as_user, server=server)
    return _service(server).list_events(
        agent_id=agent_id,
        user_id=_effective_user_id(user, as_user),
        conversation_id=conversation["conversation_id"],
        after_seq=after_seq,
        limit=limit,
    )


@router.get(
    "/agents/{agent_id}/tasks",
    summary="List Router tasks",
)
async def list_tasks(
    agent_id: str,
    status: TaskStatus | None = None,
    limit: int = Query(default=50, ge=1, le=500),
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> list[dict[str, Any]]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    return _service(server).list_tasks(
        agent_id=agent_id,
        user_id=_effective_user_id(user, as_user),
        status=status,
        limit=limit,
    )


@router.get(
    "/agents/{agent_id}/tasks/worker-status",
    summary="Read the read-only Worker dispatch status",
)
async def get_worker_status(
    agent_id: str,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    return _worker_service(server).status()


@router.post(
    "/agents/{agent_id}/tasks/read-only",
    summary="Queue one bounded read-only Worker task",
)
async def submit_read_only_task(
    agent_id: str,
    body: ReadOnlyTaskBody,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    try:
        return await _worker_service(server).submit_read_only(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            title=body.title,
            objective=body.objective,
            subject_key=body.subject_key,
        )
    except WorkerDispatchUnavailable as exc:
        raise OctopError(
            ErrorCode.INTERNAL_ERROR,
            f"read-only Worker dispatch is unavailable: {exc}",
            status=503,
        ) from exc
    except WorkerDispatchRejected as exc:
        raise OctopError(ErrorCode.FORBIDDEN, str(exc)) from exc


@router.get(
    "/agents/{agent_id}/tasks/{task_id}",
    summary="Read a Router task with runs",
)
async def get_task(
    agent_id: str,
    task_id: str,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    try:
        return _service(server).get_task(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            task_id=task_id,
        )
    except NotFoundError as exc:
        raise _not_found(str(exc)) from exc


@router.get(
    "/agents/{agent_id}/traces/{trace_id}",
    summary="Inspect one bounded Router trace",
)
async def get_trace(
    agent_id: str,
    trace_id: str,
    include_payload: bool = False,
    limit: int = Query(default=500, ge=1, le=500),
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    if include_payload and not bool(getattr(user, "is_admin", False)):
        raise OctopError(ErrorCode.FORBIDDEN, "include_payload requires admin")
    try:
        return _service(server).get_trace(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            trace_id=trace_id,
            limit=limit,
            include_payload=include_payload,
        )
    except NotFoundError as exc:
        raise _not_found(str(exc)) from exc
