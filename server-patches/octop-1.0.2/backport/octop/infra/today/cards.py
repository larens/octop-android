"""Restricted, evidence-backed publication of agent-authored Today cards."""

from __future__ import annotations

import hashlib
import json
import re
import time
from typing import TYPE_CHECKING, Annotated, Any, Literal
from urllib.parse import urlsplit

from langchain_core.tools import StructuredTool
from langgraph.config import get_config
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

if TYPE_CHECKING:
    from octop.infra.db.pool import DatabasePool
    from octop.infra.db.repos.agents import AgentRepo

TodayRiskLevel = Literal["A0", "A1", "A2", "A3", "A4"]
TodayCardKind = Literal["briefing", "research", "task", "calendar", "health", "inbox", "review", "signal"]
TodayEvidenceKind = Literal["mobile_event", "web", "conversation"]

_RISK_GUIDANCE = {
    "A0": "本卡仅聚合和记录信息，不代表已执行任何外部动作。",
    "A1": "本卡用于提醒；用户在采取行动前应先核对来源。",
    "A2": "本卡只包含建议或草稿，尚未执行，也不代表用户已确认。",
    "A3": (
        "[A3 · 需逐次确认] 当前仅为待确认建议，尚未执行。请先说明写入对象、"
        "影响范围和可撤销性；写入必须经过逐次 HITL 确认，只有工具返回结构化成功"
        "结果后才能报告完成。"
    ),
    "A4": (
        "[A4 · 高风险，需逐次确认] 当前仅为待确认建议，尚未执行，也不继承历史授权。"
        "请先说明外部影响、权限范围和失败代价；发送、删除、付费或权限变更必须经过"
        "逐次 HITL 确认，只有工具返回结构化成功结果后才能报告完成。"
    ),
}
_EXECUTION_CLAIMS = re.compile(
    r"(已发送|已提交|已执行|已删除|已支付|已授权|已完成|已经发送|已经执行)"
)
_MAX_EVIDENCE = 100


class TodayEvidenceInput(BaseModel):
    """A source the user can inspect: a phone event, a web URL, or a conversation."""

    model_config = ConfigDict(extra="forbid")

    source_type: TodayEvidenceKind
    reference: str = Field(
        min_length=1,
        max_length=500,
        description=(
            "Inspectable reference. mobile_event: event_id from mobile_events; "
            "web: the http(s) URL; conversation: an existing thread_id."
        ),
    )
    title: str | None = Field(default=None, max_length=500)
    excerpt: str | None = Field(default=None, max_length=2000)
    url: str | None = Field(default=None, max_length=2000)
    occurred_at: int | None = None

    @field_validator("reference", "title", "excerpt", "url")
    @classmethod
    def strip_text(cls, value: str | None) -> str | None:
        if value is None:
            return None
        cleaned = value.strip()
        return cleaned or None

    @model_validator(mode="after")
    def validate_inspectable_reference(self) -> TodayEvidenceInput:
        if self.source_type == "web":
            url = self.url or self.reference
            parts = urlsplit(url)
            if parts.scheme not in {"http", "https"} or not parts.netloc:
                raise ValueError("web evidence requires an http(s) URL")
        return self


class TodayCardDraft(BaseModel):
    """The complete structured payload required before a card enters Today."""

    model_config = ConfigDict(extra="forbid")

    dedupe_key: str = Field(
        min_length=1,
        max_length=200,
        description="Stable business identity. Reuse it to update the same card without duplicates.",
    )
    kind: TodayCardKind
    title: str = Field(min_length=1, max_length=200)
    summary: str = Field(min_length=1, max_length=5200)
    why_now: str = Field(min_length=1, max_length=1000)
    action_prompt: str = Field(min_length=1, max_length=8000)
    risk_level: TodayRiskLevel
    evidence: list[TodayEvidenceInput] = Field(min_length=1, max_length=_MAX_EVIDENCE)
    confidence: float = Field(default=0.75, ge=0.0, le=1.0)
    occurred_at: int | None = None
    goal_id: str | None = Field(default=None, max_length=200)

    @field_validator("dedupe_key", "title", "summary", "why_now", "action_prompt")
    @classmethod
    def require_non_blank_text(cls, value: str) -> str:
        cleaned = value.strip()
        if not cleaned:
            raise ValueError("must not be blank")
        return cleaned

    @field_validator("goal_id")
    @classmethod
    def strip_optional_text(cls, value: str | None) -> str | None:
        if value is None:
            return None
        cleaned = value.strip()
        return cleaned or None

    @model_validator(mode="after")
    def reject_unverified_high_risk_claims(self) -> TodayCardDraft:
        if self.risk_level in {"A3", "A4"}:
            candidate = "\n".join(
                (self.title, self.summary, self.why_now, self.action_prompt)
            )
            match = _EXECUTION_CLAIMS.search(candidate)
            if match is not None:
                raise ValueError(
                    f"{self.risk_level} cards cannot claim execution before confirmation "
                    f"(found {match.group(1)!r})"
                )
        return self


def _timestamp_seconds(value: int | None, *, fallback: int) -> int:
    if value is None:
        return fallback
    return value // 1000 if value > 100_000_000_000 else value


def _text(value: Any) -> str:
    return str(value).strip() if value is not None else ""


def _payload_title_excerpt(payload_json: str) -> tuple[str, str]:
    try:
        payload = json.loads(payload_json)
    except (TypeError, json.JSONDecodeError):
        return "", ""
    if not isinstance(payload, dict):
        return "", ""
    title = _text(payload.get("title"))[:500]
    excerpt = _text(payload.get("text") or payload.get("summary") or payload.get("excerpt"))
    return title, excerpt[:2000]


def _evidence_id(item: TodayEvidenceInput, *, source_type: str, url: str | None) -> str:
    identity = f"{source_type}|{item.reference}|{url or ''}"
    return hashlib.sha256(identity.encode()).hexdigest()


class TodayCardPublisher:
    """Validate ownership and source references before persisting a Today card."""

    def __init__(self, db: DatabasePool, *, agent_repo: AgentRepo) -> None:
        self._db = db
        self._agent_repo = agent_repo

    def publish(self, draft: TodayCardDraft, *, agent_id: str, user_id: int) -> dict[str, Any]:
        owner = self._agent_repo.get(agent_id)
        if owner is None or owner.user_id != user_id:
            raise PermissionError("Today cards can only be published by the agent owner")

        now = int(time.time())
        with self._db.transaction() as conn:
            if draft.goal_id is not None:
                goal = conn.execute(
                    "SELECT goal_id FROM today_goals "
                    "WHERE goal_id = ? AND agent_id = ? AND user_id = ?",
                    (draft.goal_id, agent_id, user_id),
                ).fetchone()
                if goal is None:
                    raise ValueError("goal_id does not belong to this agent and user")

            evidence = self._resolve_evidence(
                conn,
                agent_id=agent_id,
                user_id=user_id,
                items=draft.evidence,
                now=now,
            )
            occurred_at = _timestamp_seconds(
                draft.occurred_at,
                fallback=max(item["occurred_at"] for item in evidence),
            )
            dedupe_key = draft.dedupe_key
            if not dedupe_key.startswith("agent:"):
                dedupe_key = f"agent:{dedupe_key}"
            card_id = (
                "today-agent-"
                + hashlib.sha256(f"{agent_id}|{dedupe_key}".encode()).hexdigest()[:24]
            )
            action_prompt = f"{_RISK_GUIDANCE[draft.risk_level]}\n{draft.action_prompt}"
            existing = conn.execute(
                "SELECT * FROM today_cards WHERE agent_id = ? AND dedupe_key = ?",
                (agent_id, dedupe_key),
            ).fetchone()
            if existing is not None and int(existing["user_id"]) != user_id:
                raise PermissionError("Today card belongs to another user")

            existing_evidence = (
                {
                    str(row["evidence_id"])
                    for row in conn.execute(
                        "SELECT evidence_id FROM today_card_evidence "
                        "WHERE agent_id = ? AND card_id = ?",
                        (agent_id, card_id),
                    ).fetchall()
                }
                if existing is not None
                else set()
            )
            incoming_evidence = {str(item["evidence_id"]) for item in evidence}
            same_fields = existing is not None and (
                _text(existing["kind"]) == draft.kind
                and _text(existing["title"]) == draft.title
                and _text(existing["summary"]) == draft.summary
                and _text(existing["why_now"]) == draft.why_now
                and float(existing["confidence"]) == draft.confidence
                and int(existing["occurred_at"]) == occurred_at
                and _text(existing["action_prompt"]) == action_prompt
                and _text(existing["risk_level"]) == draft.risk_level
                and (
                    (str(existing["goal_id"]) if existing["goal_id"] is not None else None)
                    == draft.goal_id
                )
                and existing_evidence == incoming_evidence
            )

            if existing is None:
                conn.execute(
                    "INSERT INTO today_cards("
                    "agent_id, card_id, user_id, dedupe_key, kind, title, summary, why_now, "
                    "confidence, lifecycle, occurred_at, snoozed_until, action_prompt, "
                    "created_at, updated_at, goal_id, risk_level"
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'unread', ?, NULL, ?, ?, ?, ?, ?)",
                    (
                        agent_id,
                        card_id,
                        user_id,
                        dedupe_key,
                        draft.kind,
                        draft.title,
                        draft.summary,
                        draft.why_now,
                        draft.confidence,
                        occurred_at,
                        action_prompt,
                        now,
                        now,
                        draft.goal_id,
                        draft.risk_level,
                    ),
                )
                changed = True
            elif same_fields:
                changed = False
            else:
                existing_lifecycle = _text(existing["lifecycle"])
                existing_snoozed_until = int(existing["snoozed_until"] or 0)
                preserve_snooze = (
                    existing_lifecycle == "snoozed" and existing_snoozed_until > now
                )
                lifecycle = (
                    "dismissed"
                    if existing_lifecycle == "dismissed"
                    else "snoozed" if preserve_snooze else "unread"
                )
                snoozed_until = existing_snoozed_until if preserve_snooze else None
                conn.execute(
                    "UPDATE today_cards SET kind = ?, title = ?, summary = ?, why_now = ?, "
                    "confidence = ?, lifecycle = ?, occurred_at = ?, snoozed_until = ?, "
                    "action_prompt = ?, updated_at = ?, goal_id = ?, risk_level = ? "
                    "WHERE agent_id = ? AND card_id = ?",
                    (
                        draft.kind,
                        draft.title,
                        draft.summary,
                        draft.why_now,
                        draft.confidence,
                        lifecycle,
                        occurred_at,
                        snoozed_until,
                        action_prompt,
                        now,
                        draft.goal_id,
                        draft.risk_level,
                        agent_id,
                        card_id,
                    ),
                )
                changed = True

            if changed and existing is not None:
                conn.execute(
                    "DELETE FROM today_card_evidence WHERE agent_id = ? AND card_id = ?",
                    (agent_id, card_id),
                )
            if changed:
                for item in evidence:
                    conn.execute(
                        "INSERT INTO today_card_evidence("
                        "agent_id, card_id, evidence_id, source_type, source_record_id, "
                        "event_type, occurred_at, privacy_class, url, title, excerpt, created_at"
                        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        (
                            agent_id,
                            card_id,
                            item["evidence_id"],
                            item["source_type"],
                            item["source_record_id"],
                            item["event_type"],
                            item["occurred_at"],
                            item["privacy_class"],
                            item["url"],
                            item["title"],
                            item["excerpt"],
                            now,
                        ),
                    )

            stored = conn.execute(
                "SELECT lifecycle, updated_at FROM today_cards "
                "WHERE agent_id = ? AND card_id = ?",
                (agent_id, card_id),
            ).fetchone()
            assert stored is not None
            return {
                "card_id": card_id,
                "dedupe_key": dedupe_key,
                "risk_level": draft.risk_level,
                "goal_id": draft.goal_id,
                "evidence_count": len(evidence),
                "lifecycle": _text(stored["lifecycle"]),
                "changed": changed,
                "updated_at": int(stored["updated_at"]),
            }

    def _resolve_evidence(
        self,
        conn: Any,
        *,
        agent_id: str,
        user_id: int,
        items: list[TodayEvidenceInput],
        now: int,
    ) -> list[dict[str, Any]]:
        resolved: list[dict[str, Any]] = []
        for item in items:
            if item.source_type == "mobile_event":
                row = conn.execute(
                    "SELECT event_id, source_type, source_record_id, event_type, occurred_at, "
                    "privacy_class, payload_json FROM mobile_events "
                    "WHERE agent_id = ? AND user_id = ? AND event_id = ?",
                    (agent_id, user_id, item.reference),
                ).fetchone()
                if row is None:
                    raise ValueError(
                        f"mobile_event evidence not found for this agent/user: {item.reference}"
                    )
                payload_title, payload_excerpt = _payload_title_excerpt(
                    str(row["payload_json"])
                )
                resolved.append(
                    {
                        "evidence_id": _evidence_id(
                            item, source_type=str(row["source_type"]), url=None
                        ),
                        "source_type": str(row["source_type"]),
                        "source_record_id": (
                            str(row["source_record_id"])
                            if row["source_record_id"] is not None
                            else str(row["event_id"])
                        ),
                        "event_type": str(row["event_type"]),
                        "occurred_at": _timestamp_seconds(
                            item.occurred_at or int(row["occurred_at"]),
                            fallback=now,
                        ),
                        "privacy_class": str(row["privacy_class"]),
                        "url": item.url,
                        "title": item.title or payload_title or None,
                        "excerpt": item.excerpt or payload_excerpt or None,
                    }
                )
            elif item.source_type == "web":
                url = str(item.url or item.reference).strip()
                resolved.append(
                    {
                        "evidence_id": _evidence_id(
                            item, source_type="web_search", url=url
                        ),
                        "source_type": "web_search",
                        "source_record_id": url,
                        "event_type": "web.search_result",
                        "occurred_at": _timestamp_seconds(item.occurred_at, fallback=now),
                        "privacy_class": "public_web",
                        "url": url,
                        "title": item.title or url,
                        "excerpt": item.excerpt,
                    }
                )
            else:
                row = conn.execute(
                    "SELECT title, last_active FROM threads "
                    "WHERE thread_id = ? AND agent_id = ? AND user_id = ?",
                    (item.reference, agent_id, user_id),
                ).fetchone()
                if row is None:
                    raise ValueError(
                        f"conversation evidence not found for this agent/user: {item.reference}"
                    )
                resolved.append(
                    {
                        "evidence_id": _evidence_id(
                            item, source_type="conversation", url=None
                        ),
                        "source_type": "conversation",
                        "source_record_id": item.reference,
                        "event_type": "conversation.context",
                        "occurred_at": _timestamp_seconds(
                            item.occurred_at or int(row["last_active"]),
                            fallback=now,
                        ),
                        "privacy_class": "private_user_content",
                        "url": item.url,
                        "title": item.title or _text(row["title"]) or "对话上下文",
                        "excerpt": item.excerpt,
                    }
                )
        return resolved


def _tool_ctx() -> tuple[str, int]:
    cfg = get_config().get("configurable") or {}
    agent_id = cfg.get("agent_id")
    user_raw = cfg.get("user")
    if not agent_id:
        raise ValueError("missing configurable.agent_id")
    if user_raw is None:
        raise ValueError("missing configurable.user")
    return str(agent_id), int(user_raw)


def _json(data: Any) -> str:
    return json.dumps(data, ensure_ascii=False, indent=2)


def build_today_card_tools(publisher: TodayCardPublisher) -> list[StructuredTool]:
    """Return the restricted Today publishing tool for the current agent."""

    async def today_card_publish(
        dedupe_key: Annotated[
            str,
            Field(
                description=(
                    "Stable business key. Reuse it when the same ongoing matter changes, "
                    "so Today updates one card instead of creating duplicates."
                )
            ),
        ],
        kind: Annotated[TodayCardKind, Field(description="Display category.")],
        title: Annotated[str, Field(description="Short, concrete card title.")],
        summary: Annotated[str, Field(description="What changed, stated without hype.")],
        why_now: Annotated[
            str,
            Field(description="Why this matters now and which goal/interest it relates to."),
        ],
        action_prompt: Annotated[
            str,
            Field(description="Instruction used when the user hands this card to the agent."),
        ],
        risk_level: Annotated[
            TodayRiskLevel,
            Field(description="A0 record, A1 notify, A2 prepare, A3 private write, A4 high impact."),
        ],
        evidence: Annotated[
            list[TodayEvidenceInput],
            Field(
                min_length=1,
                max_length=_MAX_EVIDENCE,
                description=(
                    "Inspectable evidence. mobile_event must reference an ingested event; "
                    "web must contain an http(s) URL; conversation must reference an owned thread."
                ),
            ),
        ],
        goal_id: Annotated[
            str | None,
            Field(description="Optional existing goal owned by the same agent and user."),
        ] = None,
        confidence: Annotated[float, Field(ge=0.0, le=1.0)] = 0.75,
        occurred_at: Annotated[
            int | None,
            Field(description="Optional event time in epoch seconds or milliseconds."),
        ] = None,
    ) -> str:
        try:
            agent_id, user_id = _tool_ctx()
            result = publisher.publish(
                TodayCardDraft(
                    dedupe_key=dedupe_key,
                    kind=kind,
                    title=title,
                    summary=summary,
                    why_now=why_now,
                    action_prompt=action_prompt,
                    risk_level=risk_level,
                    evidence=evidence,
                    goal_id=goal_id,
                    confidence=confidence,
                    occurred_at=occurred_at,
                ),
                agent_id=agent_id,
                user_id=user_id,
            )
            return _json(result)
        except Exception as exc:
            return _json({"error": str(exc)})

    return [
        StructuredTool.from_function(
            coroutine=today_card_publish,
            name="today_card_publish",
            description=(
                "Publish or update one evidence-backed card in the user's Today inbox. "
                "Use only for a material, actionable change tied to an explicit goal or interest. "
                "Every card needs inspectable evidence and an A0-A4 risk level. "
                "A3/A4 cards are confirmation-only; this tool never marks them executed."
            ),
        )
    ]
