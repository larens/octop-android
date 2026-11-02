"""Publish Didi ride status from successful MCP tool results into Today."""

from __future__ import annotations

import hashlib
import json
import re
import time
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from octop.infra.db.pool import DatabasePool
from octop.infra.db.repos.agents import AgentRepo
from octop.infra.today.cards import (
    TodayCardDraft,
    TodayCardPublisher,
    TodayEvidenceInput,
)

_QUERY_TOOL = "taxi_query_order"
_CREATE_TOOL = "taxi_create_order"
_RIDE_TOOLS = frozenset({_QUERY_TOOL, _CREATE_TOOL})

_STATUS_TEXT = {
    0: "正在匹配司机",
    1: "司机已接单",
    2: "司机已到达",
    3: "订单状态未知",
    4: "行程中",
    5: "行程已完成",
    6: "订单已被系统取消",
    7: "订单已取消",
    8: "订单已结束",
    9: "订单已结束",
    10: "订单已结束",
    11: "客服已关闭订单",
    12: "行程未能完成",
}

_NO_ORDER_MARKERS = (
    "没有未完成",
    "没有进行中",
    "暂无未完成",
    "暂无进行中",
    "无未完成订单",
)

_PLATE_PATTERN = re.compile(
    r"[\u4e00-\u9fff][A-Za-z][A-Za-z0-9·]{5,8}"
)
_ORDER_ID_PATTERN = re.compile(
    r"(?:订单号|订单\s*ID|order[_\s-]?id)\s*[：:为]?\s*([A-Za-z0-9_-]{6,})",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class DidiRideSnapshot:
    order_id: str
    status_code: int | None
    status_text: str
    driver_name: str
    car_model: str
    car_plate: str
    distance_km: str
    eta_minutes: str
    phase: str
    origin: str
    destination: str


def didi_ride_tool_kind(tool_name: str) -> str:
    """Return the base Didi ride tool name for a prefixed MCP tool."""
    base = str(tool_name or "").strip().rsplit("/", 1)[-1].lower()
    for name in _RIDE_TOOLS:
        if base == name or base.endswith(f"_{name}"):
            return name
    return ""


def parse_didi_ride_result(
    tool_name: str,
    args: Mapping[str, Any] | None,
    *,
    artifact: Any = None,
    content: Any = None,
) -> DidiRideSnapshot | None:
    """Extract a stable ride snapshot from MCP structured content or text."""
    kind = didi_ride_tool_kind(tool_name)
    if not kind:
        return None
    raw_args = dict(args or {})
    payload = _artifact_payload(artifact)
    text = _content_text(content)
    if payload is None:
        payload = _json_payload(text)
    if text and any(marker in text for marker in _NO_ORDER_MARKERS):
        return None

    status_code = _status_code(_lookup(payload, "statusCode", "status_code"))
    status_text = _text(_lookup(payload, "statusText", "status_text"))
    driver = _mapping(_lookup(payload, "driver"))
    trip = _mapping(_lookup(payload, "map", "trip"))
    origin = _place(_lookup(payload, "from", "origin", "start"))
    destination = _place(_lookup(payload, "to", "destination", "end"))
    order_id = _text(
        _lookup(payload, "orderId", "order_id", "orderID")
        or raw_args.get("order_id")
        or _label_value(text, "订单号", "订单 ID", "order_id")
    )
    car_plate = _text(
        _lookup(driver, "carPlate", "car_plate", "plate")
        or _label_value(text, "车牌号", "车牌")
    )
    car_model = _text(
        _lookup(driver, "carModel", "car_model", "vehicle")
        or _label_value(text, "车型", "车辆")
    )
    driver_name = _text(
        _lookup(driver, "name", "driverName", "driver_name")
        or _label_value(text, "司机", "司机姓名")
    )
    distance_km = _text(
        _lookup(trip, "distanceKm", "distance_km", "distance")
        or _label_value(text, "距接驾点", "距终点", "距离")
    )
    eta_minutes = _text(
        _lookup(trip, "eta", "etaMinutes", "eta_minutes")
        or _label_value(text, "预计到达", "预计时间", "预计")
    )
    phase = _text(_lookup(trip, "phase"))
    if not origin:
        origin = _label_value(text, "起点", "出发地")
    if not destination:
        destination = _label_value(text, "终点", "目的地")
    if status_code is None:
        status_code = _status_code_from_text(text)
    if not status_text and status_code is not None:
        status_text = _STATUS_TEXT.get(status_code, "")
    if not status_text:
        status_text = _status_text_from_text(text)
    if not order_id:
        order_id = _order_id_from_text(text)
    if not car_plate:
        plate_match = _PLATE_PATTERN.search(text)
        car_plate = plate_match.group(0) if plate_match else ""

    if kind == _CREATE_TOOL and not status_text:
        status_text = "正在匹配司机"
    if kind == _CREATE_TOOL and not order_id:
        return None
    if kind == _QUERY_TOOL and status_code is None and not order_id:
        return None
    if kind == _QUERY_TOOL and not any(
        (order_id, car_plate, car_model, driver_name, status_code is not None)
    ):
        return None

    return DidiRideSnapshot(
        order_id=order_id,
        status_code=status_code,
        status_text=status_text,
        driver_name=driver_name,
        car_model=car_model,
        car_plate=car_plate,
        distance_km=distance_km,
        eta_minutes=eta_minutes,
        phase=phase,
        origin=origin,
        destination=destination,
    )


def build_didi_ride_summary(snapshot: DidiRideSnapshot) -> str:
    vehicle_lines = []
    if snapshot.car_plate:
        vehicle_lines.append(f"车牌号：{snapshot.car_plate}")
    if snapshot.car_model:
        vehicle_lines.append(f"车型：{snapshot.car_model}")
    if snapshot.driver_name:
        vehicle_lines.append(f"司机：{snapshot.driver_name}")
    if not vehicle_lines:
        vehicle_lines.append(
            "正在为你匹配车辆。"
            if snapshot.status_code in {None, 0}
            else "车辆信息暂未返回。"
        )

    trip_lines = [f"状态：{snapshot.status_text or '行程状态已更新'}"]
    distance_label = _distance_label(snapshot.distance_km)
    eta_label = _eta_label(snapshot.eta_minutes)
    if distance_label:
        phase_label = "距终点" if snapshot.phase == "to_dropoff" else "距接驾点"
        detail = f"{phase_label}：{distance_label}"
        if eta_label and snapshot.phase != "to_dropoff":
            detail += f" · 预计 {eta_label}到达"
        trip_lines.append(detail)
    elif eta_label:
        trip_lines.append(f"预计：{eta_label}")
    if snapshot.origin and snapshot.destination:
        trip_lines.append(f"{snapshot.origin} → {snapshot.destination}")
    elif snapshot.destination:
        trip_lines.append(f"目的地：{snapshot.destination}")
    if snapshot.order_id:
        trip_lines.append(f"订单号：{snapshot.order_id}")

    return "\n\n".join(
        (
            "【车辆信息】\n" + "\n".join(vehicle_lines),
            "【行程】\n" + "\n".join(trip_lines),
        )
    )


def publish_didi_ride_today_card(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    thread_id: str,
    tool_name: str,
    args: Mapping[str, Any] | None,
    artifact: Any = None,
    content: Any = None,
) -> dict[str, Any] | None:
    """Publish or update one stable Today card for a Didi order."""
    snapshot = parse_didi_ride_result(
        tool_name,
        args,
        artifact=artifact,
        content=content,
    )
    if snapshot is None:
        return None
    identity = snapshot.order_id or snapshot.car_plate
    if not identity:
        return None

    now = int(time.time())
    summary = build_didi_ride_summary(snapshot)
    title = _ride_title(snapshot)
    evidence = TodayEvidenceInput(
        source_type="conversation",
        reference=thread_id,
        title="滴滴行程结果",
        excerpt="完整下单、查询与车辆信息保留在会话记录中。",
        occurred_at=now,
    )
    draft = TodayCardDraft(
        dedupe_key=f"didi:ride:{identity}",
        kind="task",
        title=title,
        summary=summary,
        why_now="滴滴 MCP 返回了最新行程状态；车辆与接驾进度会按订单持续更新。",
        action_prompt="查看会话中的完整下单与查询记录；需要取消或继续查询时，先确认当前订单状态。",
        risk_level="A1",
        evidence=[evidence],
        confidence=0.99,
        occurred_at=now,
    )
    published = TodayCardPublisher(db, agent_repo=AgentRepo(db)).publish(
        draft,
        agent_id=agent_id,
        user_id=user_id,
    )
    with db.transaction() as conn:
        conn.execute(
            "UPDATE today_cards SET execution_thread_id = ?, execution_turn_id = NULL, "
            "action_state = NULL, action_error = NULL, action_updated_at = ? "
            "WHERE agent_id = ? AND card_id = ?",
            (thread_id, now, agent_id, str(published["card_id"])),
        )
    return published


def _ride_tool_kind(tool_name: str) -> str:
    return didi_ride_tool_kind(tool_name)


def _ride_title(snapshot: DidiRideSnapshot) -> str:
    if snapshot.status_code == 5:
        return "网约车 · 行程已完成"
    if snapshot.status_code in {6, 7}:
        return "网约车 · 订单已取消"
    if snapshot.car_plate:
        return f"网约车 · {snapshot.car_plate}"
    if snapshot.status_text:
        return f"网约车 · {snapshot.status_text}"
    return "网约车 · 正在叫车"


def _artifact_payload(artifact: Any) -> Mapping[str, Any] | None:
    if isinstance(artifact, Mapping):
        structured = artifact.get("structured_content") or artifact.get(
            "structuredContent"
        )
        if isinstance(structured, Mapping):
            return structured
        if any(
            key in artifact
            for key in ("statusCode", "status_code", "orderId", "order_id", "driver")
        ):
            return artifact
    return None


def _content_text(content: Any) -> str:
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, Mapping):
        for key in ("text", "content"):
            value = content.get(key)
            if isinstance(value, str):
                return value.strip()
        return ""
    if isinstance(content, Sequence) and not isinstance(content, (str, bytes, bytearray)):
        parts = [_content_text(item) for item in content]
        return "\n".join(part for part in parts if part)
    return ""


def _json_payload(text: str) -> Mapping[str, Any] | None:
    stripped = text.strip()
    if not stripped:
        return None
    candidates = [stripped]
    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", stripped, re.DOTALL)
    if fenced:
        candidates.append(fenced.group(1))
    for candidate in candidates:
        try:
            value = json.loads(candidate)
        except (TypeError, json.JSONDecodeError):
            continue
        if isinstance(value, Mapping):
            return value
        if isinstance(value, list):
            mapping = next((item for item in value if isinstance(item, Mapping)), None)
            if mapping is not None:
                return mapping
    return None


def _lookup(value: Any, *names: str) -> Any:
    if not names:
        return None
    if isinstance(value, Mapping):
        for name in names:
            if name in value:
                return value[name]
        for child in value.values():
            found = _lookup(child, *names)
            if found not in (None, "", [], {}):
                return found
    elif isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        for child in value:
            found = _lookup(child, *names)
            if found not in (None, "", [], {}):
                return found
    return None


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _place(value: Any) -> str:
    if isinstance(value, Mapping):
        return _text(_lookup(value, "name", "address", "display_name"))
    return _text(value)


def _label_value(text: str, *labels: str) -> str:
    if not text:
        return ""
    escaped = "|".join(re.escape(label) for label in labels)
    match = re.search(
        rf"(?:^|\n)\s*(?:{escaped})\s*[：:]\s*([^\n]+)",
        text,
        re.IGNORECASE,
    )
    return _text(match.group(1)) if match else ""


def _status_code(value: Any) -> int | None:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return None
    return number if 0 <= number <= 99 else None


def _status_code_from_text(text: str) -> int | None:
    if not text:
        return None
    match = re.search(r"statusCode\s*[：:=]\s*(\d+)", text, re.IGNORECASE)
    return _status_code(match.group(1)) if match else None


def _status_text_from_text(text: str) -> str:
    if not text:
        return ""
    labeled = _label_value(text, "状态", "订单状态")
    if labeled:
        return labeled
    return next((label for label in _STATUS_TEXT.values() if label in text), "")


def _order_id_from_text(text: str) -> str:
    match = _ORDER_ID_PATTERN.search(text or "")
    return _text(match.group(1)) if match else ""


def _distance_label(value: str) -> str:
    text = _text(value)
    if not text:
        return ""
    if (
        "公里" in text
        or "米" in text
        or re.search(r"\b(?:km|m)\b", text, re.IGNORECASE)
    ):
        return text
    return f"{text} 公里"


def _eta_label(value: str) -> str:
    text = _text(value)
    if not text:
        return ""
    if "分钟" in text or re.search(r"\bmin\b", text, re.IGNORECASE):
        return text if "分钟" in text else text.replace("min", "分钟")
    return f"{text} 分钟"


def _text(value: Any) -> str:
    return str(value).strip() if value is not None else ""


def ride_card_id(agent_id: str, identity: str) -> str:
    """Return the deterministic card id used by the Today publisher."""
    dedupe_key = f"agent:didi:ride:{identity}"
    return "today-agent-" + hashlib.sha256(
        f"{agent_id}|{dedupe_key}".encode()
    ).hexdigest()[:24]


__all__ = [
    "DidiRideSnapshot",
    "build_didi_ride_summary",
    "didi_ride_tool_kind",
    "parse_didi_ride_result",
    "publish_didi_ride_today_card",
    "ride_card_id",
]
