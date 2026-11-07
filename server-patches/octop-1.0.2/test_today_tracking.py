from __future__ import annotations

import importlib.util
import sqlite3
import sys
import types
import unittest
from contextlib import contextmanager
from pathlib import Path


def _load_cards_module():
    tools = types.ModuleType("langchain_core.tools")

    class StructuredTool:
        @classmethod
        def from_function(cls, **kwargs):
            return kwargs

    tools.StructuredTool = StructuredTool
    sys.modules["langchain_core"] = types.ModuleType("langchain_core")
    sys.modules["langchain_core.tools"] = tools

    config = types.ModuleType("langgraph.config")
    config.get_config = lambda: {"configurable": {}}
    sys.modules["langgraph"] = types.ModuleType("langgraph")
    sys.modules["langgraph.config"] = config

    path = Path(__file__).with_name("backport") / "octop/infra/today/cards.py"
    spec = importlib.util.spec_from_file_location("today_cards_tracking", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


cards = _load_cards_module()


class FakeDatabase:
    def __init__(self) -> None:
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        self.conn.executescript(
            """
            CREATE TABLE today_cards (
                agent_id TEXT NOT NULL,
                card_id TEXT NOT NULL,
                user_id INTEGER NOT NULL,
                dedupe_key TEXT NOT NULL,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                why_now TEXT NOT NULL,
                confidence REAL NOT NULL,
                lifecycle TEXT NOT NULL,
                occurred_at INTEGER NOT NULL,
                snoozed_until INTEGER,
                action_prompt TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                goal_id TEXT,
                risk_level TEXT NOT NULL,
                tracking_json TEXT,
                PRIMARY KEY (agent_id, card_id)
            );
            CREATE TABLE today_card_evidence (
                agent_id TEXT NOT NULL,
                card_id TEXT NOT NULL,
                evidence_id TEXT NOT NULL,
                source_type TEXT NOT NULL,
                source_record_id TEXT,
                event_type TEXT NOT NULL,
                occurred_at INTEGER NOT NULL,
                privacy_class TEXT NOT NULL,
                url TEXT,
                title TEXT,
                excerpt TEXT,
                created_at INTEGER NOT NULL
            );
            """
        )

    @contextmanager
    def transaction(self):
        yield self.conn
        self.conn.commit()


class FakeAgent:
    user_id = 7


class FakeAgentRepo:
    def get(self, agent_id: str):
        return FakeAgent() if agent_id == "agent-1" else None


def evidence(reference: str = "https://example.com/competition"):
    return cards.TodayEvidenceInput(
        source_type="web",
        reference=reference,
        title="赛事规则",
        excerpt="官方公布的赛程与评分口径。",
    )


def tracking(*, stage: str, summary: str = "赛事进展", missing=None):
    return cards.TodayTrackingInput(
        subject="AI 上生态城影像大赛",
        stage=stage,
        status="in_progress",
        progress=40,
        phases=[
            cards.TodayTrackingPhase(
                label="征集",
                starts_at=1_760_000_000,
                ends_at=1_760_500_000,
                state="done",
            ),
            cards.TodayTrackingPhase(
                label="专家评审",
                starts_at=1_760_500_001,
                ends_at=1_761_000_000,
                state="active",
            ),
        ],
        metrics=[cards.TodayTrackingMetric(label="传播分", value="50%")],
        missing=missing or [],
        next_milestone="专家评审结束",
        next_check_at=1_760_900_000,
        next_action="补录四平台数据",
    )


def draft(*, stage: str, summary: str = "赛事进展", missing=None):
    return cards.TodayCardDraft(
        dedupe_key="competition:ai-eco-city",
        kind="task",
        title=f"影像大赛 · {stage}",
        summary=summary,
        why_now="当前阶段有新变化。",
        action_prompt="查看赛事进展并补齐缺失数据。",
        risk_level="A1",
        evidence=[evidence()],
        tracking=tracking(stage=stage, summary=summary, missing=missing),
    )


class TodayTrackingValidationTest(unittest.TestCase):
    def test_normalizes_millisecond_timestamps(self) -> None:
        phase = cards.TodayTrackingPhase(
            label="初审",
            starts_at=1_760_000_000_000,
            ends_at=1_760_500_000_000,
        )
        self.assertEqual(1_760_000_000, phase.starts_at)
        self.assertEqual(1_760_500_000, phase.ends_at)

    def test_rejects_multiple_active_phases(self) -> None:
        with self.assertRaises(ValueError):
            cards.TodayTrackingInput(
                subject="事项",
                stage="初审",
                phases=[
                    cards.TodayTrackingPhase(label="初审", state="active"),
                    cards.TodayTrackingPhase(label="复审", state="active"),
                ],
            )

    def test_deduplicates_missing_items(self) -> None:
        value = cards.TodayTrackingInput(
            subject="事项",
            stage="初审",
            missing=[" 抖音 ", "抖音", "", "视频号"],
        )
        self.assertEqual(["抖音", "视频号"], value.missing)


class TodayTrackingPublishTest(unittest.TestCase):
    def setUp(self) -> None:
        self.db = FakeDatabase()
        self.publisher = cards.TodayCardPublisher(
            self.db,
            agent_repo=FakeAgentRepo(),
        )

    def publish(self, value):
        return self.publisher.publish(value, agent_id="agent-1", user_id=7)

    def test_reuses_one_card_and_notifies_only_on_stage_or_missing_change(self) -> None:
        first = self.publish(draft(stage="初审"))
        self.assertTrue(first["notify"])
        self.assertEqual("tracking_started", first["notify_reason"])
        self.assertFalse(first["stage_changed"])

        same_stage = self.publish(draft(stage="初审", summary="补充了初审口径"))
        self.assertTrue(same_stage["changed"])
        self.assertFalse(same_stage["notify"])
        self.assertEqual(first["card_id"], same_stage["card_id"])

        next_stage = self.publish(draft(stage="专家评审"))
        self.assertTrue(next_stage["notify"])
        self.assertTrue(next_stage["stage_changed"])
        self.assertEqual("stage_changed", next_stage["notify_reason"])
        self.assertEqual(first["card_id"], next_stage["card_id"])

        missing = self.publish(
            draft(stage="专家评审", missing=["抖音播放量", "小红书互动数"])
        )
        self.assertTrue(missing["notify"])
        self.assertEqual("missing_updated", missing["notify_reason"])

        rows = self.db.conn.execute("SELECT * FROM today_cards").fetchall()
        self.assertEqual(1, len(rows))
        stored = cards._tracking_from_json(rows[0]["tracking_json"])
        self.assertIsNotNone(stored)
        self.assertEqual("专家评审", stored.stage)
        self.assertEqual(["抖音播放量", "小红书互动数"], stored.missing)


if __name__ == "__main__":
    unittest.main()
