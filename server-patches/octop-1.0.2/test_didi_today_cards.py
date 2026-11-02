from __future__ import annotations

import importlib.util
import sys
import types
import unittest
from contextlib import contextmanager
from pathlib import Path

from install_didi_today_cards import patch_manager_source


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
    path = Path(__file__).with_name("didi_ride_today.py")
    spec = importlib.util.spec_from_file_location("didi_ride_today", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules["didi_ride_today"] = module
    spec.loader.exec_module(module)
    return module


didi = _load_module()


class DidiRideParsingTest(unittest.TestCase):
    def test_matches_prefixed_mcp_tool_names_only(self) -> None:
        self.assertEqual(
            "taxi_query_order",
            didi.didi_ride_tool_kind("didi__instance_taxi_query_order"),
        )
        self.assertEqual(
            "taxi_create_order",
            didi.didi_ride_tool_kind("taxi_create_order"),
        )
        self.assertEqual("", didi.didi_ride_tool_kind("maps_textsearch"))

    def test_structured_vehicle_result_builds_compact_summary(self) -> None:
        snapshot = didi.parse_didi_ride_result(
            "didi__instance_taxi_query_order",
            {"order_id": "order-123"},
            artifact={
                "structured_content": {
                    "statusCode": 1,
                    "statusText": "司机已接单",
                    "driver": {
                        "name": "张师傅",
                        "phone": "13800000000",
                        "carModel": "白色比亚迪秦 PLUS",
                        "carPlate": "京A·12345",
                    },
                    "map": {
                        "distanceKm": "1.2",
                        "eta": "3",
                        "phase": "to_pickup",
                    },
                    "from": {"name": "望京 SOHO"},
                    "to": {"name": "首都机场 T3"},
                }
            },
        )

        assert snapshot is not None
        summary = didi.build_didi_ride_summary(snapshot)
        self.assertIn("车牌号：京A·12345", summary)
        self.assertIn("车型：白色比亚迪秦 PLUS", summary)
        self.assertIn("司机：张师傅", summary)
        self.assertIn("状态：司机已接单", summary)
        self.assertIn("距接驾点：1.2 公里 · 预计 3 分钟到达", summary)
        self.assertIn("望京 SOHO → 首都机场 T3", summary)
        self.assertNotIn("13800000000", summary)

    def test_text_fallback_handles_driver_arrived_status(self) -> None:
        content = [
            {
                "type": "text",
                "text": (
                    "订单号：ORDER-7788\n"
                    "状态：司机已到达\n"
                    "司机：李师傅\n"
                    "车牌号：沪B·67890\n"
                    "车型：黑色大众帕萨特\n"
                    "距接驾点：80 米\n"
                    "起点：静安寺\n"
                    "终点：虹桥火车站"
                ),
            }
        ]

        snapshot = didi.parse_didi_ride_result(
            "didi__instance_taxi_query_order",
            {"order_id": "ORDER-7788"},
            content=content,
        )

        assert snapshot is not None
        summary = didi.build_didi_ride_summary(snapshot)
        self.assertIn("状态：司机已到达", summary)
        self.assertIn("车牌号：沪B·67890", summary)
        self.assertIn("静安寺 → 虹桥火车站", summary)

    def test_empty_active_order_does_not_publish(self) -> None:
        snapshot = didi.parse_didi_ride_result(
            "didi__instance_taxi_query_order",
            None,
            content=[{"type": "text", "text": "您当前没有未完成的订单。"}],
        )

        self.assertIsNone(snapshot)

    def test_query_matching_status_is_not_treated_as_missing(self) -> None:
        snapshot = didi.parse_didi_ride_result(
            "didi__instance_taxi_query_order",
            {"order_id": "ORDER-MATCHING"},
            artifact={
                "structured_content": {
                    "statusCode": 0,
                    "statusText": "正在匹配司机",
                }
            },
        )

        assert snapshot is not None
        self.assertEqual(0, snapshot.status_code)
        self.assertEqual("正在匹配司机", snapshot.status_text)

    def test_create_result_starts_a_matching_card(self) -> None:
        snapshot = didi.parse_didi_ride_result(
            "didi__instance_taxi_create_order",
            {},
            artifact={
                "structured_content": {
                    "orderId": "ORDER-CREATED-1",
                    "status": "created",
                }
            },
        )

        assert snapshot is not None
        summary = didi.build_didi_ride_summary(snapshot)
        self.assertIn("正在为你匹配车辆", summary)
        self.assertIn("订单号：ORDER-CREATED-1", summary)
        self.assertIn("状态：正在匹配司机", summary)

    def test_dedupe_card_id_is_stable_for_an_order(self) -> None:
        first = didi.ride_card_id("agent-1", "ORDER-1")
        second = didi.ride_card_id("agent-1", "ORDER-1")

        self.assertEqual(first, second)
        self.assertNotEqual(first, didi.ride_card_id("agent-2", "ORDER-1"))

    def test_manager_patch_preserves_local_middleware_import_indentation(self) -> None:
        source = """\
        from octop.infra.agents.middleware.reasoning import ReasoningRequestMiddleware
        from octop.infra.agents.middleware.thread_artifacts import ThreadArtifactsMiddleware

        agent_middleware = [
            ThreadArtifactsMiddleware(
                thread_repo=self._repos.thread_repo,
                workspace_dir=harness_workspace,
                agent_id=row.agent_id,
            ),
        ]
"""

        patched = patch_manager_source(source)

        self.assertIn(
            "        from octop.infra.agents.middleware.didi_ride_today import "
            "DidiRideTodayMiddleware\n",
            patched,
        )
        self.assertIn(
            "        from octop.infra.agents.middleware.thread_artifacts import "
            "ThreadArtifactsMiddleware\n",
            patched,
        )
        self.assertNotIn("\nfrom octop.infra.agents.middleware.thread_artifacts", patched)
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


class DidiRidePublishTest(unittest.TestCase):
    def test_publisher_uses_task_kind_and_links_execution_thread(self) -> None:
        captured: dict[str, object] = {}
        original_publisher = didi.TodayCardPublisher
        original_agent_repo = didi.AgentRepo

        class FakePublisher:
            def __init__(self, db: object, *, agent_repo: object) -> None:
                captured["db"] = db
                captured["agent_repo"] = agent_repo

            def publish(self, draft: object, *, agent_id: str, user_id: int):
                captured["draft"] = draft
                captured["agent_id"] = agent_id
                captured["user_id"] = user_id
                return {"card_id": "today-agent-test"}

        didi.TodayCardPublisher = FakePublisher
        didi.AgentRepo = lambda db: object()
        try:
            db = _Db()
            result = didi.publish_didi_ride_today_card(
                db,
                agent_id="agent-1",
                user_id=7,
                thread_id="thread-1",
                tool_name="didi__instance_taxi_query_order",
                args={"order_id": "ORDER-1"},
                artifact={
                    "structured_content": {
                        "statusCode": 1,
                        "driver": {
                            "name": "张师傅",
                            "carModel": "比亚迪秦",
                            "carPlate": "京A·12345",
                        },
                    }
                },
            )
        finally:
            didi.TodayCardPublisher = original_publisher
            didi.AgentRepo = original_agent_repo

        assert result is not None
        draft = captured["draft"]
        self.assertEqual("task", draft.kind)
        self.assertEqual("didi:ride:ORDER-1", draft.dedupe_key)
        self.assertEqual("A1", draft.risk_level)
        self.assertIn("网约车 · 京A·12345", draft.title)
        self.assertEqual("thread-1", db.conn.statements[0][1][0])
        self.assertEqual("today-agent-test", db.conn.statements[0][1][-1])


if __name__ == "__main__":
    unittest.main()
