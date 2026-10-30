"""User-owned web interest monitors and manual checks."""

from __future__ import annotations

import time
from typing import Any, Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field, field_validator, model_validator

from octop.api.common.agent import require_agent_owner_row
from octop.api.deps import current_user, get_server
from octop.infra.errors import ErrorCode, OctopError
from octop.infra.interest_monitor import next_run_at
from octop.infra.utils.ulid import new_ulid

router = APIRouter()
Schedule = Literal["manual", "morning", "evening", "twice_daily"]


class InterestMonitorCreateBody(BaseModel):
    description: str = Field(min_length=3, max_length=500)
    schedule: Schedule
    goal_id: str | None = Field(default=None, max_length=100)

    @field_validator("description")
    @classmethod
    def strip_description(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("description must not be blank")
        return value


class InterestMonitorPatchBody(BaseModel):
    enabled: bool | None = None
    schedule: Schedule | None = None


class InterestMonitorResponse(BaseModel):
    monitor_id: str
    description: str
    schedule: str
    enabled: bool
    baseline_established: bool
    last_run_at: int | None
    last_status: str | None
    last_error: str | None
    last_result_count: int
    next_run_at: int | None
    created_at: int
    updated_at: int
    delivery_backoff_until: int | None = None
    feedback_backoff_reason: str | None = None
    feedback_backoff_level: int = 0
    goal_id: str | None = None
    goal_title: str | None = None
    goal_needs_review: bool = False


class InterestMonitorRunResponse(BaseModel):
    monitor: InterestMonitorResponse
    result_count: int
    new_count: int
    published_count: int


class TodayGoalCreateBody(BaseModel):
    title: str = Field(min_length=2, max_length=160)
    definition: str = Field(min_length=3, max_length=1000)
    success_rules: str = Field(min_length=3, max_length=2000)
    review_at: int | None = None

    @field_validator("title", "definition", "success_rules")
    @classmethod
    def strip_goal_text(cls, value: str) -> str:
        return value.strip()

    @model_validator(mode="after")
    def validate_review_at(self) -> TodayGoalCreateBody:
        if self.review_at is not None and self.review_at <= int(time.time()):
            raise ValueError("review_at must be in the future")
        return self


class TodayGoalPatchBody(BaseModel):
    title: str | None = Field(default=None, min_length=2, max_length=160)
    definition: str | None = Field(default=None, min_length=3, max_length=1000)
    success_rules: str | None = Field(default=None, min_length=3, max_length=2000)
    status: Literal["active", "paused", "completed"] | None = None
    review_at: int | None = None
    confirm: bool = False

    @field_validator("title", "definition", "success_rules")
    @classmethod
    def strip_optional_goal_text(cls, value: str | None) -> str | None:
        return value.strip() if value is not None else None


class TodayGoalResponse(BaseModel):
    goal_id: str
    title: str
    definition: str
    success_rules: str
    source: str
    status: str
    review_at: int | None
    last_confirmed_at: int
    needs_review: bool
    created_at: int
    updated_at: int


def _goal_response(row: Any, *, now: int | None = None) -> TodayGoalResponse:
    current = int(time.time()) if now is None else now
    review_at = int(row["review_at"]) if row["review_at"] is not None else None
    return TodayGoalResponse(
        goal_id=str(row["goal_id"]),
        title=str(row["title"]),
        definition=str(row["definition"]),
        success_rules=str(row["success_rules"]),
        source=str(row["source"]),
        status=str(row["status"]),
        review_at=review_at,
        last_confirmed_at=int(row["last_confirmed_at"]),
        needs_review=review_at is not None and review_at <= current,
        created_at=int(row["created_at"]),
        updated_at=int(row["updated_at"]),
    )


def _response(row: Any) -> InterestMonitorResponse:
    review_at = int(row["goal_review_at"]) if "goal_review_at" in row.keys() and row["goal_review_at"] is not None else None
    return InterestMonitorResponse(
        monitor_id=str(row["monitor_id"]),
        description=str(row["query_text"]),
        schedule=str(row["schedule"]),
        enabled=bool(row["enabled"]),
        baseline_established=bool(row["baseline_established"]),
        last_run_at=int(row["last_run_at"]) if row["last_run_at"] is not None else None,
        last_status=row["last_status"],
        last_error=row["last_error"],
        last_result_count=int(row["last_result_count"] or 0),
        next_run_at=int(row["next_run_at"]) if row["next_run_at"] is not None else None,
        delivery_backoff_until=(
            int(row["delivery_backoff_until"]) if row["delivery_backoff_until"] is not None else None
        ),
        feedback_backoff_reason=row["feedback_backoff_reason"],
        feedback_backoff_level=int(row["feedback_backoff_level"] or 0),
        created_at=int(row["created_at"]),
        updated_at=int(row["updated_at"]),
        goal_id=str(row["goal_id"]) if row["goal_id"] is not None else None,
        goal_title=str(row["goal_title"]) if "goal_title" in row.keys() and row["goal_title"] is not None else None,
        goal_needs_review=review_at is not None and review_at <= int(time.time()),
    )


_MONITOR_SELECT = (
    "SELECT m.*, g.title AS goal_title, g.review_at AS goal_review_at "
    "FROM interest_monitors m LEFT JOIN today_goals g ON g.goal_id = m.goal_id "
)


def _owned_row(server: Any, agent_id: str, user_id: int, monitor_id: str) -> Any:
    with server.services.db.connect() as conn:
        row = conn.execute(
            _MONITOR_SELECT + "WHERE m.agent_id = ? AND m.user_id = ? AND m.monitor_id = ?",
            (agent_id, user_id, monitor_id),
        ).fetchone()
    if row is None:
        raise OctopError(ErrorCode.NOT_FOUND, "interest monitor not found")
    return row


def _owned_goal(server: Any, agent_id: str, user_id: int, goal_id: str) -> Any:
    with server.services.db.connect() as conn:
        row = conn.execute(
            "SELECT * FROM today_goals WHERE agent_id = ? AND user_id = ? AND goal_id = ?",
            (agent_id, user_id, goal_id),
        ).fetchone()
    if row is None:
        raise OctopError(ErrorCode.NOT_FOUND, "Today goal not found")
    return row


@router.get("/agents/{agent_id}/today/goals", response_model=list[TodayGoalResponse])
async def list_today_goals(
    agent_id: str,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> list[TodayGoalResponse]:
    require_agent_owner_row(agent_id, user=user, as_user=None, server=server)
    with server.services.db.connect() as conn:
        rows = conn.execute(
            "SELECT * FROM today_goals WHERE agent_id = ? AND user_id = ? "
            "ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'paused' THEN 1 ELSE 2 END, updated_at DESC",
            (agent_id, user.id),
        ).fetchall()
    return [_goal_response(row) for row in rows]


@router.post(
    "/agents/{agent_id}/today/goals",
    response_model=TodayGoalResponse,
    status_code=201,
)
async def create_today_goal(
    agent_id: str,
    body: TodayGoalCreateBody,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> TodayGoalResponse:
    require_agent_owner_row(agent_id, user=user, as_user=None, server=server)
    now = int(time.time())
    goal_id = new_ulid()
    with server.services.db.transaction() as conn:
        conn.execute(
            "INSERT INTO today_goals("
            "goal_id, agent_id, user_id, title, definition, success_rules, source, status, "
            "review_at, last_confirmed_at, created_at, updated_at"
            ") VALUES (?, ?, ?, ?, ?, ?, 'user_explicit', 'active', ?, ?, ?, ?)",
            (
                goal_id,
                agent_id,
                user.id,
                body.title,
                body.definition,
                body.success_rules,
                body.review_at,
                now,
                now,
                now,
            ),
        )
        row = conn.execute("SELECT * FROM today_goals WHERE goal_id = ?", (goal_id,)).fetchone()
    assert row is not None
    return _goal_response(row, now=now)


@router.patch(
    "/agents/{agent_id}/today/goals/{goal_id}",
    response_model=TodayGoalResponse,
)
async def patch_today_goal(
    agent_id: str,
    goal_id: str,
    body: TodayGoalPatchBody,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> TodayGoalResponse:
    require_agent_owner_row(agent_id, user=user, as_user=None, server=server)
    existing = _owned_goal(server, agent_id, user.id, goal_id)
    now = int(time.time())
    title = body.title.strip() if body.title is not None else str(existing["title"])
    definition = body.definition.strip() if body.definition is not None else str(existing["definition"])
    success_rules = body.success_rules.strip() if body.success_rules is not None else str(existing["success_rules"])
    status = body.status or str(existing["status"])
    review_at = body.review_at if body.review_at is not None else existing["review_at"]
    last_confirmed_at = now if body.confirm else int(existing["last_confirmed_at"])
    with server.services.db.transaction() as conn:
        conn.execute(
            "UPDATE today_goals SET title = ?, definition = ?, success_rules = ?, status = ?, "
            "review_at = ?, last_confirmed_at = ?, updated_at = ? WHERE goal_id = ?",
            (title, definition, success_rules, status, review_at, last_confirmed_at, now, goal_id),
        )
        row = conn.execute("SELECT * FROM today_goals WHERE goal_id = ?", (goal_id,)).fetchone()
    assert row is not None
    return _goal_response(row, now=now)


@router.get("/agents/{agent_id}/interest-monitors", response_model=list[InterestMonitorResponse])
async def list_interest_monitors(
    agent_id: str,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> list[InterestMonitorResponse]:
    require_agent_owner_row(agent_id, user=user, as_user=None, server=server)
    with server.services.db.connect() as conn:
        rows = conn.execute(
            _MONITOR_SELECT + "WHERE m.agent_id = ? AND m.user_id = ? ORDER BY m.created_at DESC",
            (agent_id, user.id),
        ).fetchall()
    return [_response(row) for row in rows]


@router.post(
    "/agents/{agent_id}/interest-monitors",
    response_model=InterestMonitorResponse,
    status_code=201,
)
async def create_interest_monitor(
    agent_id: str,
    body: InterestMonitorCreateBody,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> InterestMonitorResponse:
    require_agent_owner_row(agent_id, user=user, as_user=None, server=server)
    now = int(time.time())
    monitor_id = new_ulid()
    following = next_run_at(body.schedule, now=now, timezone_name=server.services.config.default_timezone)
    with server.services.db.transaction() as conn:
        if body.goal_id is not None:
            goal = conn.execute(
                "SELECT 1 FROM today_goals WHERE goal_id = ? AND agent_id = ? AND user_id = ? AND status = 'active'",
                (body.goal_id, agent_id, user.id),
            ).fetchone()
            if goal is None:
                raise OctopError(ErrorCode.NOT_FOUND, "active Today goal not found")
        conn.execute(
            "INSERT INTO interest_monitors("
            "monitor_id, agent_id, user_id, query_text, schedule, enabled, baseline_established, "
            "last_run_at, last_status, last_error, last_result_count, next_run_at, created_at, updated_at, goal_id"
            ") VALUES (?, ?, ?, ?, ?, 1, 0, NULL, NULL, NULL, 0, ?, ?, ?, ?)",
            (monitor_id, agent_id, user.id, body.description, body.schedule, following, now, now, body.goal_id),
        )
        row = conn.execute(_MONITOR_SELECT + "WHERE m.monitor_id = ?", (monitor_id,)).fetchone()
    assert row is not None
    return _response(row)


@router.patch(
    "/agents/{agent_id}/interest-monitors/{monitor_id}",
    response_model=InterestMonitorResponse,
)
async def patch_interest_monitor(
    agent_id: str,
    monitor_id: str,
    body: InterestMonitorPatchBody,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> InterestMonitorResponse:
    require_agent_owner_row(agent_id, user=user, as_user=None, server=server)
    existing = _owned_row(server, agent_id, user.id, monitor_id)
    schedule = body.schedule or str(existing["schedule"])
    enabled = bool(existing["enabled"]) if body.enabled is None else body.enabled
    now = int(time.time())
    following = next_run_at(schedule, now=now, timezone_name=server.services.config.default_timezone) if enabled else None
    with server.services.db.transaction() as conn:
        conn.execute(
            "UPDATE interest_monitors SET schedule = ?, enabled = ?, next_run_at = ?, "
            "delivery_backoff_until = NULL, feedback_backoff_reason = NULL, "
            "feedback_backoff_level = 0, updated_at = ? "
            "WHERE monitor_id = ?",
            (schedule, int(enabled), following, now, monitor_id),
        )
        row = conn.execute(_MONITOR_SELECT + "WHERE m.monitor_id = ?", (monitor_id,)).fetchone()
    assert row is not None
    return _response(row)


@router.post(
    "/agents/{agent_id}/interest-monitors/{monitor_id}/run",
    response_model=InterestMonitorRunResponse,
)
async def run_interest_monitor(
    agent_id: str,
    monitor_id: str,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> InterestMonitorRunResponse:
    require_agent_owner_row(agent_id, user=user, as_user=None, server=server)
    _owned_row(server, agent_id, user.id, monitor_id)
    service = server.app_runtime.interest_monitor_service
    assert service is not None
    result = await service.run(monitor_id, manual=True)
    row = _owned_row(server, agent_id, user.id, monitor_id)
    return InterestMonitorRunResponse(monitor=_response(row), **result)


@router.delete("/agents/{agent_id}/interest-monitors/{monitor_id}", status_code=204)
async def delete_interest_monitor(
    agent_id: str,
    monitor_id: str,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> None:
    require_agent_owner_row(agent_id, user=user, as_user=None, server=server)
    _owned_row(server, agent_id, user.id, monitor_id)
    now = int(time.time())
    with server.services.db.transaction() as conn:
        affected = [
            str(row["card_id"])
            for row in conn.execute(
                "SELECT DISTINCT card_id FROM today_web_evidence "
                "WHERE agent_id = ? AND monitor_id = ?",
                (agent_id, monitor_id),
            ).fetchall()
        ]
        conn.execute("DELETE FROM interest_monitors WHERE monitor_id = ?", (monitor_id,))
        service = server.app_runtime.interest_monitor_service
        assert service is not None
        for card_id in affected:
            card = conn.execute(
                "SELECT dedupe_key FROM today_cards WHERE agent_id = ? AND card_id = ?",
                (agent_id, card_id),
            ).fetchone()
            if card is None:
                continue
            parts = str(card["dedupe_key"]).split(":")
            window = parts[-2] if len(parts) >= 4 else parts[-1]
            service._refresh_briefing(  # noqa: SLF001 - same feature boundary
                conn, agent_id=agent_id, card_id=card_id, window=window, now=now
            )
