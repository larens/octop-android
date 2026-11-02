from __future__ import annotations

import importlib.util
import json
import sys
import types
import unittest
from contextlib import contextmanager
from pathlib import Path

from install_coffee_today_cards import patch_manager_source


def _install_module(name: str, **attributes: object) -> None:
    module = types.ModuleType(name)
    module.__dict__.update(attributes)
    sys.modules[name] = module


def _load_module():
    octop = types.ModuleType("octop")
    octop.__path__ = []
    sys.modules["octop"] = octop
    for name in (
        "octop.infra",
        "octop.infra.db",
        "octop.infra.db.repos",
        "octop.infra.today",
    ):
        package = types.ModuleType(name)
        package.__path__ = []
        sys.modules[name] = package
    _install_module("octop.infra.db.pool", DatabasePool=object)
    _install_module("octop.infra.db.repos.agents", AgentRepo=object)

    class Draft:
        def __init__(self, **values: object) -> None:
            self.__dict__.update(values)

    class Evidence:
        def __init__(self, **values: object) -> None:
            self.__dict__.update(values)

    class Publisher:
        def __init__(self, db: object, *, agent_repo: object) -> None:
            self.db = db
            self.agent_repo = agent_repo

    _install_module(
        "octop.infra.today.cards",
        TodayCardDraft=Draft,
        TodayCardPublisher=Publisher,
        TodayEvidenceInput=Evidence,
    )
    path = Path(__file__).with_name("coffee_order_today.py")
    spec = importlib.util.spec_from_file_location("coffee_order_today", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["coffee_order_today"] = module
    spec.loader.exec_module(module)
    return module


coffee = _load_module()

REAL_ORDER_DETAIL = {
    "code": 0,
    "msg": "success",
    "data": {
        "orderId": "7703368209932943369",
        "orderStatus": 30,
        "orderStatusName": "精心制作中",
        "aboutTime": 1793580814000,
        "takeMealTime": "",
        "takeMealCodeInfo": {"code": "972", "takeOrderId": "aufckp8XAAk="},
        "shopInfo": {
            "deptId": 611463,
            "deptName": "元中心快手总部店",
        },
        "orderPayAmount": 10.9,
        "orderCommodityList": [
            {
                "commodityId": 4807,
                "commodityName": "精萃澳瑞白",
                "payableMoney": 10.9,
            }
        ],
    },
    "success": True,
}


class CoffeeOrderParsingTest(unittest.TestCase):
    def test_matches_prefixed_luckin_order_detail_tool_only(self) -> None:
        self.assertEqual(
            "my_coffee_queryorderdetailinfo",
            coffee.coffee_order_tool_kind("my-coffee_queryOrderDetailInfo"),
        )
        self.assertEqual(
            "my_coffee_queryorderdetailinfo",
            coffee.coffee_order_tool_kind(
                "my-coffee__instance_my-coffee_queryOrderDetailInfo"
            ),
        )
        self.assertEqual("", coffee.coffee_order_tool_kind("my-coffee_createOrder"))

    def test_real_tool_content_publishes_the_verified_pickup_code(self) -> None:
        snapshot = coffee.parse_coffee_order_result(
            "my-coffee_queryOrderDetailInfo",
            content=[
                {
                    "type": "text",
                    "text": json.dumps(REAL_ORDER_DETAIL, ensure_ascii=False),
                }
            ],
        )

        assert snapshot is not None
        self.assertEqual("972", snapshot.pickup_code)
        self.assertEqual("aufckp8XAAk=", snapshot.qr_payload)
        self.assertEqual("7703368209932943369", snapshot.order_id)
        self.assertEqual("精心制作中", snapshot.status_text)
        self.assertEqual("元中心快手总部店", snapshot.shop_name)
        self.assertEqual("精萃澳瑞白", snapshot.product_name)
        self.assertEqual("¥10.90", snapshot.amount)
        self.assertEqual("08:53", snapshot.ready_at)

        summary = coffee.build_coffee_order_summary(snapshot)
        self.assertIn("【取餐信息】", summary)
        self.assertIn("取餐码：972", summary)
        self.assertIn("取餐二维码：aufckp8XAAk=", summary)
        self.assertIn("门店：元中心快手总部店", summary)
        self.assertIn("预计可取：08:53", summary)
        self.assertIn("饮品：精萃澳瑞白", summary)

    def test_placeholder_pickup_code_does_not_publish(self) -> None:
        pending = {
            **REAL_ORDER_DETAIL,
            "data": {
                **REAL_ORDER_DETAIL["data"],
                "orderStatus": 10,
                "orderStatusName": "待付款",
                "takeMealCodeInfo": {"code": "生成中"},
            },
        }

        snapshot = coffee.parse_coffee_order_result(
            "my-coffee_queryOrderDetailInfo",
            content=[{"type": "text", "text": json.dumps(pending, ensure_ascii=False)}],
        )

        self.assertIsNone(snapshot)

    def test_cancelled_order_does_not_publish_a_stale_code(self) -> None:
        cancelled = {
            **REAL_ORDER_DETAIL,
            "data": {
                **REAL_ORDER_DETAIL["data"],
                "orderStatusName": "订单已取消",
            },
        }

        snapshot = coffee.parse_coffee_order_result(
            "my-coffee_queryOrderDetailInfo",
            content=[
                {"type": "text", "text": json.dumps(cancelled, ensure_ascii=False)}
            ],
        )

        self.assertIsNone(snapshot)

    def test_card_id_is_stable_per_order_and_agent(self) -> None:
        first = coffee.coffee_order_card_id("agent-1", "ORDER-1")
        second = coffee.coffee_order_card_id("agent-1", "ORDER-1")

        self.assertEqual(first, second)
        self.assertNotEqual(first, coffee.coffee_order_card_id("agent-2", "ORDER-1"))

    def test_manager_patch_is_idempotent_with_existing_ride_middleware(self) -> None:
        source = """\
        from octop.infra.agents.middleware.didi_ride_today import DidiRideTodayMiddleware
        from octop.infra.agents.middleware.thread_artifacts import ThreadArtifactsMiddleware

        agent_middleware = [
            ThreadArtifactsMiddleware(
                thread_repo=self._repos.thread_repo,
                workspace_dir=harness_workspace,
                agent_id=row.agent_id,
            ),
            DidiRideTodayMiddleware(
                db=self._repos.db,
                agent_id=row.agent_id,
                user_id=row.user_id,
            ),
        ]
"""

        patched = patch_manager_source(source)

        self.assertIn(
            "from octop.infra.agents.middleware.coffee_order_today import "
            "CoffeeOrderTodayMiddleware",
            patched,
        )
        self.assertIn("CoffeeOrderTodayMiddleware(", patched)
        self.assertIn("DidiRideTodayMiddleware(", patched)
        self.assertEqual(patched, patch_manager_source(patched))


class _Conn:
    def __init__(self) -> None:
        self.statements: list[tuple[str, tuple[object, ...]]] = []

    def execute(self, statement: str, params: tuple[object, ...]) -> None:
        self.statements.append((statement, params))


class _Db:
    def __init__(self) -> None:
        self.conn = _Conn()

    @contextmanager
    def transaction(self):
        yield self.conn


class CoffeeOrderPublishTest(unittest.TestCase):
    def test_publisher_links_execution_thread_and_uses_real_code(self) -> None:
        captured: dict[str, object] = {}
        original_publisher = coffee.TodayCardPublisher
        original_agent_repo = coffee.AgentRepo

        class FakePublisher:
            def __init__(self, db: object, *, agent_repo: object) -> None:
                captured["db"] = db
                captured["agent_repo"] = agent_repo

            def publish(self, draft: object, *, agent_id: str, user_id: int):
                captured["draft"] = draft
                captured["agent_id"] = agent_id
                captured["user_id"] = user_id
                return {"card_id": "today-agent-test"}

        coffee.TodayCardPublisher = FakePublisher
        coffee.AgentRepo = lambda db: object()
        try:
            db = _Db()
            result = coffee.publish_coffee_order_today_card(
                db,
                agent_id="agent-1",
                user_id=7,
                thread_id="thread-1",
                tool_name="my-coffee_queryOrderDetailInfo",
                content=[
                    {
                        "type": "text",
                        "text": json.dumps(REAL_ORDER_DETAIL, ensure_ascii=False),
                    }
                ],
            )
        finally:
            coffee.TodayCardPublisher = original_publisher
            coffee.AgentRepo = original_agent_repo

        assert result is not None
        draft = captured["draft"]
        self.assertEqual("task", draft.kind)
        self.assertEqual("luckin:coffee:7703368209932943369", draft.dedupe_key)
        self.assertEqual("咖啡 · 精萃澳瑞白", draft.title)
        self.assertEqual("A1", draft.risk_level)
        self.assertIn("取餐码：972", draft.summary)
        self.assertIn("取餐二维码：aufckp8XAAk=", draft.summary)
        self.assertEqual("thread-1", db.conn.statements[0][1][0])
        self.assertEqual("today-agent-test", db.conn.statements[0][1][-1])


if __name__ == "__main__":
    unittest.main()
