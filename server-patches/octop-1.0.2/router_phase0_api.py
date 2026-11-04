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
from octop.infra.conversation.service import (
    BudgetExceededError,
    ConversationService,
    InvalidTransitionError,
    NotFoundError,
    RouterPhase0Error,
)
from octop.infra.conversation.write_actions import WriteFeatureDisabledError
from octop.infra.errors import ErrorCode, OctopError
from octop.infra.gateway.threads import ThreadRegistry

router = APIRouter()
TaskStatus = Literal["open", "in_progress", "needs_user", "done", "failed", "cancelled", "expired"]


class ReadOnlyTaskBody(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    objective: str = Field(min_length=1, max_length=20_000)
    subject_key: str | None = Field(default=None, max_length=500)


class DispatchPlanItemBody(BaseModel):
    key: str = Field(min_length=1, max_length=80)
    title: str = Field(min_length=1, max_length=200)
    objective: str = Field(min_length=1, max_length=20_000)
    worker_role: str = Field(default="research", max_length=80)
    depends_on: list[str] = Field(default_factory=list, max_length=4)
    token_limit: int | None = Field(default=None, ge=1_000, le=4_000_000)


class ProposeDispatchPlanBody(BaseModel):
    objective: str = Field(min_length=1, max_length=20_000)
    title: str | None = Field(default=None, max_length=200)
    items: list[DispatchPlanItemBody] = Field(default_factory=list, max_length=4)


class ApproveDispatchPlanBody(BaseModel):
    plan_version: int = Field(ge=1)
    plan_hash: str = Field(min_length=64, max_length=64)


class ContinueDispatchPlanBudgetBody(ApproveDispatchPlanBody):
    budget_request_id: str = Field(min_length=1, max_length=200)


class ProposeWriteTaskBody(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    objective: str = Field(min_length=1, max_length=20_000)
    action_name: str = Field(min_length=1, max_length=160)
    action_payload: dict[str, Any] = Field(default_factory=dict)
    subject_key: str | None = Field(default=None, max_length=500)
    token_limit: int | None = Field(default=None, ge=1_000, le=4_000_000)
    idempotency_key: str | None = Field(default=None, min_length=8, max_length=200)


class TaskApprovalDecisionBody(BaseModel):
    action_hash: str = Field(min_length=64, max_length=64)
    version: int = Field(ge=1)


class ClaimWriteBody(BaseModel):
    approval_id: str = Field(min_length=1, max_length=200)
    action_hash: str = Field(min_length=64, max_length=64)
    idempotency_key: str = Field(min_length=8, max_length=200)


class CompleteWriteBody(BaseModel):
    outcome: dict[str, Any] = Field(default_factory=dict)
    outcome_ref: str | None = Field(default=None, max_length=500)
    actual_tokens: int = Field(default=0, ge=0)


class FailWriteBody(BaseModel):
    error: str = Field(min_length=1, max_length=2_000)
    actual_tokens: int = Field(default=0, ge=0)


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


def _plan_error(exc: Exception) -> OctopError:
    if isinstance(exc, WriteFeatureDisabledError):
        return OctopError(ErrorCode.INTERNAL_ERROR, str(exc), status=503)
    if isinstance(exc, NotFoundError):
        return OctopError(ErrorCode.NOT_FOUND, str(exc))
    if isinstance(exc, (InvalidTransitionError, BudgetExceededError, RouterPhase0Error)):
        return OctopError(ErrorCode.INTERNAL_ERROR, str(exc), status=409)
    return OctopError(ErrorCode.INTERNAL_ERROR, str(exc))


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
        include_plan_payload=True,
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
    "/agents/{agent_id}/tasks/side-effect",
    summary="Propose a side-effecting task and wait for task-level approval",
)
async def propose_write_task(
    agent_id: str,
    body: ProposeWriteTaskBody,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    conversation = _conversation(
        agent_id=agent_id,
        user=user,
        as_user=as_user,
        server=server,
    )
    worker = _worker_service(server)
    try:
        return worker.write_actions.create_task(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            conversation_id=str(conversation["conversation_id"]),
            title=body.title,
            objective=body.objective,
            action_name=body.action_name,
            action_payload=body.action_payload,
            subject_key=body.subject_key,
            token_limit=body.token_limit,
            idempotency_key=body.idempotency_key,
        )
    except Exception as exc:
        raise _plan_error(exc) from exc


@router.post(
    "/agents/{agent_id}/tasks/read-only",
    summary="Propose a read-only Worker task for explicit approval (legacy path)",
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


@router.post(
    "/agents/{agent_id}/dispatch-plans/propose",
    summary="Propose a multi-Worker dispatch plan without executing it",
)
async def propose_dispatch_plan(
    agent_id: str,
    body: ProposeDispatchPlanBody,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    try:
        return await _worker_service(server).propose_dispatch_plan(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            objective=body.objective,
            title=body.title,
            items=[item.model_dump() for item in body.items],
        )
    except WorkerDispatchRejected as exc:
        raise OctopError(ErrorCode.FORBIDDEN, str(exc)) from exc
    except Exception as exc:
        raise _plan_error(exc) from exc


@router.get(
    "/agents/{agent_id}/dispatch-plans/{plan_id}",
    summary="Read a dispatch plan and its child Worker progress",
)
async def get_dispatch_plan(
    agent_id: str,
    plan_id: str,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    try:
        return _worker_service(server).dispatch_plans.get_plan(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            plan_id=plan_id,
        )
    except Exception as exc:
        raise _plan_error(exc) from exc


@router.post(
    "/agents/{agent_id}/dispatch-plans/{plan_id}/approve",
    summary="Approve an exact dispatch plan version and reserve its budget",
)
async def approve_dispatch_plan(
    agent_id: str,
    plan_id: str,
    body: ApproveDispatchPlanBody,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    worker = _worker_service(server)
    if not worker.ready:
        raise OctopError(
            ErrorCode.INTERNAL_ERROR,
            f"Worker dispatch is not ready: {worker.config.reason}",
            status=503,
        )
    try:
        return worker.dispatch_plans.approve_plan(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            plan_id=plan_id,
            plan_version=body.plan_version,
            plan_hash=body.plan_hash,
        )
    except Exception as exc:
        raise _plan_error(exc) from exc


@router.post(
    "/agents/{agent_id}/dispatch-plans/{plan_id}/reject",
    summary="Reject a dispatch plan without creating Tasks or Runs",
)
async def reject_dispatch_plan(
    agent_id: str,
    plan_id: str,
    body: ApproveDispatchPlanBody,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    try:
        return _worker_service(server).dispatch_plans.reject_plan(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            plan_id=plan_id,
            plan_version=body.plan_version,
            plan_hash=body.plan_hash,
        )
    except Exception as exc:
        raise _plan_error(exc) from exc


@router.post(
    "/agents/{agent_id}/dispatch-plans/{plan_id}/budget/continue",
    summary="Approve one bounded budget extension and enqueue one more Worker attempt",
)
async def continue_dispatch_plan_budget(
    agent_id: str,
    plan_id: str,
    body: ContinueDispatchPlanBudgetBody,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    worker = _worker_service(server)
    if not worker.ready:
        raise OctopError(
            ErrorCode.INTERNAL_ERROR,
            f"Worker dispatch is not ready: {worker.config.reason}",
            status=503,
        )
    try:
        return worker.dispatch_plans.extend_budget(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            plan_id=plan_id,
            plan_version=body.plan_version,
            plan_hash=body.plan_hash,
            budget_request_id=body.budget_request_id,
        )
    except Exception as exc:
        raise _plan_error(exc) from exc


@router.post(
    "/agents/{agent_id}/dispatch-plans/{plan_id}/cancel",
    summary="Cancel a dispatch plan and all unfinished child Workers",
)
async def cancel_dispatch_plan(
    agent_id: str,
    plan_id: str,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    try:
        return await _worker_service(server).cancel_dispatch_plan(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            plan_id=plan_id,
        )
    except Exception as exc:
        raise _plan_error(exc) from exc


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


@router.post(
    "/agents/{agent_id}/tasks/{task_id}/approvals/{approval_id}/approve",
    summary="Approve one exact side-effecting task action",
)
async def approve_task_action(
    agent_id: str,
    task_id: str,
    approval_id: str,
    body: TaskApprovalDecisionBody,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    try:
        return _worker_service(server).write_actions.approve(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            task_id=task_id,
            approval_id=approval_id,
            action_hash=body.action_hash,
            version=body.version,
        )
    except Exception as exc:
        raise _plan_error(exc) from exc


@router.post(
    "/agents/{agent_id}/tasks/{task_id}/approvals/{approval_id}/reject",
    summary="Reject a side-effecting task without creating a write receipt",
)
async def reject_task_action(
    agent_id: str,
    task_id: str,
    approval_id: str,
    body: TaskApprovalDecisionBody,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    try:
        return _worker_service(server).write_actions.reject(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            task_id=task_id,
            approval_id=approval_id,
            action_hash=body.action_hash,
            version=body.version,
        )
    except Exception as exc:
        raise _plan_error(exc) from exc


@router.get(
    "/agents/{agent_id}/tasks/{task_id}/writes",
    summary="Read task approvals and write receipts",
)
async def get_task_writes(
    agent_id: str,
    task_id: str,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    try:
        return _worker_service(server).write_actions.get_task_writes(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            task_id=task_id,
        )
    except Exception as exc:
        raise _plan_error(exc) from exc


@router.post(
    "/agents/{agent_id}/tasks/{task_id}/write/claim",
    summary="Claim an approved write exactly once and return its durable receipt",
)
async def claim_task_write(
    agent_id: str,
    task_id: str,
    body: ClaimWriteBody,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    try:
        return _worker_service(server).write_actions.claim_write(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            task_id=task_id,
            approval_id=body.approval_id,
            action_hash=body.action_hash,
            idempotency_key=body.idempotency_key,
        )
    except Exception as exc:
        raise _plan_error(exc) from exc


@router.post(
    "/agents/{agent_id}/tasks/{task_id}/write/{receipt_id}/complete",
    summary="Record a successful side effect and its idempotent receipt",
)
async def complete_task_write(
    agent_id: str,
    task_id: str,
    receipt_id: str,
    body: CompleteWriteBody,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    try:
        writes = _worker_service(server).write_actions
        result = writes.complete_write(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            receipt_id=receipt_id,
            outcome=body.outcome,
            outcome_ref=body.outcome_ref,
            actual_tokens=body.actual_tokens,
        )
        if result["task_id"] != task_id:
            raise NotFoundError("write receipt not found for task")
        return result
    except Exception as exc:
        raise _plan_error(exc) from exc


@router.post(
    "/agents/{agent_id}/tasks/{task_id}/write/{receipt_id}/fail",
    summary="Record a known write failure without replay",
)
async def fail_task_write(
    agent_id: str,
    task_id: str,
    receipt_id: str,
    body: FailWriteBody,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    try:
        writes = _worker_service(server).write_actions
        result = writes.mark_write_failed(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            receipt_id=receipt_id,
            error=body.error,
            actual_tokens=body.actual_tokens,
        )
        if result["task_id"] != task_id:
            raise NotFoundError("write receipt not found for task")
        return result
    except Exception as exc:
        raise _plan_error(exc) from exc


@router.post(
    "/agents/{agent_id}/tasks/{task_id}/write/{receipt_id}/uncertain",
    summary="Mark a write result uncertain and forbid automatic replay",
)
async def mark_task_write_uncertain(
    agent_id: str,
    task_id: str,
    receipt_id: str,
    body: FailWriteBody,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    try:
        writes = _worker_service(server).write_actions
        result = writes.mark_result_uncertain(
            agent_id=agent_id,
            user_id=_effective_user_id(user, as_user),
            receipt_id=receipt_id,
            error=body.error,
            actual_tokens=body.actual_tokens,
        )
        if result["task_id"] != task_id:
            raise NotFoundError("write receipt not found for task")
        return result
    except Exception as exc:
        raise _plan_error(exc) from exc


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
