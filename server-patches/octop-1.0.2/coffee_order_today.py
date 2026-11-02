"""Publish a verified Luckin Coffee pickup code from MCP results into Today."""

from __future__ import annotations

import hashlib
import json
import re
import time
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

from octop.infra.db.pool import DatabasePool
from octop.infra.db.repos.agents import AgentRepo
from octop.infra.today.cards import (
    TodayCardDraft,
    TodayCardPublisher,
    TodayEvidenceInput,
)

_ORDER_DETAIL_TOOL = "my_coffee_queryorderdetailinfo"
_FAILED_STATUS_MARKERS = (
    "取消",
    "关闭",
    "失败",
    "退款",
    "退单",
    "cancel",
    "closed",
    "failed",
    "refund",
)
_PICKUP_CODE_RE = re.compile(r"^[A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*$")
_PICKUP_QR_RE = re.compile(r"^[A-Za-z0-9+/=_-]+$")
_CHINA_TZ = ZoneInfo("Asia/Shanghai")


@dataclass(frozen=True)
class CoffeeOrderSnapshot:
    order_id: str
    pickup_code: str
    qr_payload: str
    status_text: str
    shop_name: str
    product_name: str
    amount: str
    ready_at: str


def coffee_order_tool_kind(tool_name: str) -> str:
    """Return the supported Luckin order-detail tool name for an MCP-prefixed tool."""
    base = str(tool_name or "").strip().rsplit("/", 1)[-1].lower()
    normalized = base.replace("-", "_")
    if (
        normalized == _ORDER_DETAIL_TOOL
        or normalized.endswith(f"_{_ORDER_DETAIL_TOOL}")
    ):
        return _ORDER_DETAIL_TOOL
    return ""


def parse_coffee_order_result(
    tool_name: str,
    *,
    artifact: Any = None,
    content: Any = None,
) -> CoffeeOrderSnapshot | None:
    """Extract a real pickup code from a successful order-detail response."""
    if not coffee_order_tool_kind(tool_name):
        return None

    payload = _artifact_payload(artifact)
    text = _content_text(content)
    if payload is None:
        payload = _json_payload(text)
    if payload is None or _lookup(payload, "success") is False:
        return None

    detail = _mapping(_lookup(payload, "data"))
    if not detail:
        detail = payload
    code_info = _lookup(detail, "takeMealCodeInfo", "take_meal_code_info")
    pickup_code = _pickup_code(_lookup(code_info, "code"))
    if not pickup_code:
        return None

    status_text = _text(
        _lookup(detail, "orderStatusName", "order_status_name", "statusText")
    )
    if _is_failed_status(status_text):
        return None

    order_id = _text(_lookup(detail, "orderId", "order_id", "orderIdStr"))
    if not order_id:
        return None

    shop = _mapping(_lookup(detail, "shopInfo", "shop_info"))
    shop_name = _text(_lookup(shop, "deptName", "shopName", "name"))
    commodities = _lookup(detail, "orderCommodityList", "order_commodity_list")
    product_name = _product_name(commodities)
    amount = _amount(
        _lookup(detail, "orderPayAmount", "discountPrice", "payAmount")
    )
    ready_at = _ready_at(
        _lookup(detail, "takeMealTime", "take_meal_time"),
        _lookup(detail, "aboutTime", "about_time"),
    )

    return CoffeeOrderSnapshot(
        order_id=order_id,
        pickup_code=pickup_code,
        qr_payload=_pickup_qr_payload(_lookup(code_info, "takeOrderId", "take_order_id")),
        status_text=status_text or "订单状态已更新",
        shop_name=shop_name,
        product_name=product_name,
        amount=amount,
        ready_at=ready_at,
    )


def build_coffee_order_summary(snapshot: CoffeeOrderSnapshot) -> str:
    pickup_lines = [f"取餐码：{snapshot.pickup_code}"]
    if snapshot.qr_payload:
        pickup_lines.append(f"取餐二维码：{snapshot.qr_payload}")
    if snapshot.shop_name:
        pickup_lines.append(f"门店：{snapshot.shop_name}")
    if snapshot.ready_at:
        pickup_lines.append(f"预计可取：{snapshot.ready_at}")

    order_lines = [f"状态：{snapshot.status_text}"]
    if snapshot.product_name:
        order_lines.append(f"饮品：{snapshot.product_name}")
    if snapshot.amount:
        order_lines.append(f"实付：{snapshot.amount}")
    if snapshot.order_id:
        order_lines.append(f"订单号：{snapshot.order_id}")

    return "\n\n".join(
        (
            "【取餐信息】\n" + "\n".join(pickup_lines),
            "【订单】\n" + "\n".join(order_lines),
        )
    )


def publish_coffee_order_today_card(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    thread_id: str,
    tool_name: str,
    artifact: Any = None,
    content: Any = None,
) -> dict[str, Any] | None:
    """Publish one stable Today card after Luckin returns a real pickup code."""
    snapshot = parse_coffee_order_result(
        tool_name,
        artifact=artifact,
        content=content,
    )
    if snapshot is None:
        return None

    now = int(time.time())
    title = f"咖啡 · {snapshot.product_name or '取餐码'}"
    evidence = TodayEvidenceInput(
        source_type="conversation",
        reference=thread_id,
        title="瑞幸咖啡订单结果",
        excerpt="真实取餐码来自瑞幸 MCP 的订单详情结果。",
        occurred_at=now,
    )
    draft = TodayCardDraft(
        dedupe_key=f"luckin:coffee:{snapshot.order_id}",
        kind="task",
        title=title,
        summary=build_coffee_order_summary(snapshot),
        why_now="瑞幸 MCP 已返回真实取餐码，到店取餐时可直接出示。",
        action_prompt="查看来源会话中的瑞幸订单；取餐码只以工具实际返回为准。",
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


def _artifact_payload(artifact: Any) -> Mapping[str, Any] | None:
    if isinstance(artifact, Mapping):
        structured = artifact.get("structured_content") or artifact.get(
            "structuredContent"
        )
        if isinstance(structured, Mapping):
            return structured
        if any(
            key in artifact
            for key in ("orderId", "order_id", "takeMealCodeInfo", "take_meal_code_info")
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


def _pickup_code(value: Any) -> str:
    code = _text(value).strip('"\'：:# ')
    if not 3 <= len(code) <= 20:
        return ""
    if not any(char.isdigit() for char in code):
        return ""
    return code if _PICKUP_CODE_RE.fullmatch(code) else ""


def _pickup_qr_payload(value: Any) -> str:
    payload = _text(value).strip()
    if not 8 <= len(payload) <= 256:
        return ""
    return payload if _PICKUP_QR_RE.fullmatch(payload) else ""


def _is_failed_status(status_text: str) -> bool:
    lowered = status_text.lower()
    return any(marker in lowered for marker in _FAILED_STATUS_MARKERS)


def _product_name(commodities: Any) -> str:
    if isinstance(commodities, Sequence) and not isinstance(
        commodities, (str, bytes, bytearray)
    ):
        for item in commodities:
            mapping = _mapping(item)
            name = _text(_lookup(mapping, "commodityName", "productName", "name"))
            if name:
                return name
    return ""


def _amount(value: Any) -> str:
    text = _text(value)
    if not text:
        return ""
    try:
        return f"¥{float(text):.2f}"
    except (TypeError, ValueError):
        return text


def _ready_at(take_meal_time: Any, about_time: Any) -> str:
    explicit = _text(take_meal_time)
    if explicit:
        return explicit
    try:
        timestamp = float(about_time)
    except (TypeError, ValueError):
        return ""
    if timestamp <= 0:
        return ""
    seconds = timestamp / 1000 if timestamp > 100_000_000_000 else timestamp
    return datetime.fromtimestamp(seconds, _CHINA_TZ).strftime("%H:%M")


def _text(value: Any) -> str:
    return str(value).strip() if value is not None else ""


def coffee_order_card_id(agent_id: str, order_id: str) -> str:
    """Return the deterministic card id used by the Today publisher."""
    dedupe_key = f"agent:luckin:coffee:{order_id}"
    return "today-agent-" + hashlib.sha256(
        f"{agent_id}|{dedupe_key}".encode()
    ).hexdigest()[:24]


__all__ = [
    "CoffeeOrderSnapshot",
    "build_coffee_order_summary",
    "coffee_order_card_id",
    "coffee_order_tool_kind",
    "parse_coffee_order_result",
    "publish_coffee_order_today_card",
]
