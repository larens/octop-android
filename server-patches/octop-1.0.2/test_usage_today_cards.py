from __future__ import annotations

import importlib.util
import sys
import types
import unittest
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path

from install_usage_today_cards import (
    patch_card_kind_source,
    patch_mobile_ingest_source,
)

CST = timezone(timedelta(hours=8))


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

    _install_module(
        "octop.infra.today.cards",
        TodayCardDraft=Draft,
        TodayCardPublisher=object,
        TodayEvidenceInput=Evidence,
    )
    path = Path(__file__).with_name("usage_today_cards.py")
    spec = importlib.util.spec_from_file_location("usage_today_cards", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


usage = _load_module()


class _Result:
    def __init__(self, rows: list[dict[str, object]]) -> None:
        self._rows = rows

    def fetchone(self):
        return self._rows[0] if self._rows else None

    def fetchall(self):
        return list(self._rows)


class _Conn:
    def __init__(
        self,
        *,
        totals: dict[str, object],
        model_rows: list[dict[str, object]],
        previous_model_rows: list[dict[str, object]],
        previous_tokens: int,
        thread_row: dict[str, object] | None,
        today_start: int,
    ) -> None:
        self.totals = totals
        self.model_rows = model_rows
        self.previous_model_rows = previous_model_rows
        self.previous_tokens = previous_tokens
        self.thread_row = thread_row
        self.today_start = today_start
        self.updates: list[tuple[object, ...]] = []

    def execute(self, sql: str, params: tuple[object, ...] = ()) -> _Result:
        if "GROUP BY source, model, peak" in sql:
            start = int(params[2])
            rows = self.model_rows if start == self.today_start else self.previous_model_rows
            return _Result(rows)
        if "JOIN threads" in sql:
            return _Result([self.thread_row] if self.thread_row is not None else [])
        if "COUNT(DISTINCT thread_id)" in sql:
            return _Result([self.totals])
        if "SUM(total_tokens)" in sql:
            return _Result([{"tokens": self.previous_tokens}])
        if sql.startswith("UPDATE today_cards"):
            self.updates.append(params)
            return _Result([])
        raise AssertionError(f"unexpected SQL: {sql}")


class _Db:
    def __init__(
        self,
        *,
        totals: dict[str, object] | None = None,
        model_rows: list[dict[str, object]] | None = None,
        previous_model_rows: list[dict[str, object]] | None = None,
        previous_tokens: int = 0,
        thread_row: dict[str, object] | None = None,
    ) -> None:
        day_start = datetime(2026, 11, 14, tzinfo=CST)
        today_start = int(day_start.timestamp())
        self.conn = _Conn(
            totals=totals
            or {
                "input_tokens": 6_574_227,
                "output_tokens": 137_394,
                "cache_read_tokens": 4_931_584,
                "reasoning_tokens": 106_700,
                "total_tokens": 6_711_621,
                "model_calls": 135,
                "turns": 15,
                "threads": 9,
            },
            model_rows=model_rows
            or [
                {
                    "source": "cron",
                    "model": "mimo-v2.6-flash",
                    "peak": 0,
                    "total_tokens": 5_579_038,
                    "input_tokens": 5_468_888,
                    "uncached_input_tokens": 1_325_464,
                    "cache_read_tokens": 4_143_424,
                    "output_tokens": 110_150,
                    "model_calls": 120,
                },
                {
                    "source": "chat",
                    "model": "mimo-v2.6-flash",
                    "peak": 0,
                    "total_tokens": 1_131_627,
                    "input_tokens": 1_104_441,
                    "uncached_input_tokens": 316_281,
                    "cache_read_tokens": 788_160,
                    "output_tokens": 27_186,
                    "model_calls": 14,
                },
                {
                    "source": "proactive",
                    "model": "Xiaomi MiMo/mimo-v2.6-flash",
                    "peak": 0,
                    "total_tokens": 956,
                    "input_tokens": 898,
                    "uncached_input_tokens": 898,
                    "cache_read_tokens": 0,
                    "output_tokens": 58,
                    "model_calls": 1,
                },
            ],
            previous_model_rows=previous_model_rows
            or [
                {
                    "source": "chat",
                    "model": "deepseek-flash",
                    "peak": 0,
                    "total_tokens": 2_524_803,
                    "input_tokens": 2_512_824,
                    "uncached_input_tokens": 384_696,
                    "cache_read_tokens": 2_128_128,
                    "output_tokens": 11_979,
                    "model_calls": 17,
                },
                {
                    "source": "cron",
                    "model": "mimo-v2.6-flash",
                    "peak": 0,
                    "total_tokens": 600_754,
                    "input_tokens": 592_368,
                    "uncached_input_tokens": 177_392,
                    "cache_read_tokens": 414_976,
                    "output_tokens": 8_386,
                    "model_calls": 20,
                },
                {
                    "source": "cron",
                    "model": "mimo-v2.6-flash",
                    "peak": 1,
                    "total_tokens": 1_298_580,
                    "input_tokens": 1_279_142,
                    "uncached_input_tokens": 340_454,
                    "cache_read_tokens": 938_688,
                    "output_tokens": 19_438,
                    "model_calls": 24,
                },
            ],
            previous_tokens=previous_tokens or 4_424_137,
            thread_row=thread_row
            or {
                "thread_id": "thr_01M633WTR5Z34AT431TBF4GA6T",
                "title": "日间 AI 与科技行业信号",
            },
            today_start=today_start,
        )
        self.transactions = 0
        self.connects = 0

    @contextmanager
    def connect(self):
        self.connects += 1
        yield self.conn

    @contextmanager
    def transaction(self):
        self.transactions += 1
        yield self.conn


def _now(hour: int = 21, minute: int = 30) -> int:
    return int(datetime(2026, 11, 14, hour, minute, tzinfo=CST).timestamp())


class UsageSnapshotTest(unittest.TestCase):
    def test_builds_phone_readable_metrics_and_comparison(self) -> None:
        snapshot = usage.build_usage_snapshot(
            _Db(),
            agent_id="agent-1",
            user_id=2,
            now=_now(),
        )

        assert snapshot is not None
        self.assertEqual(6_711_621, snapshot.total_tokens)
        self.assertEqual(75, snapshot.cache_hit_percent)
        self.assertEqual(2_016_062, snapshot.cost_micros)
        self.assertEqual(4_832_951, snapshot.cache_savings_micros)
        self.assertEqual(1_075_740, snapshot.previous_cost_micros)
        self.assertEqual(
            ("mimo-v2.6-flash",),
            tuple(item.model for item in snapshot.by_model),
        )
        summary = usage.format_usage_summary(snapshot)
        self.assertIn("总花费：¥2.02 · 按官方价估算", summary)
        self.assertIn("缓存节省：¥4.83", summary)
        self.assertIn("总 Token：6.7M · 缓存命中：75%", summary)
        self.assertIn("模型调用：135 · 会话：9 · 轮次：15 · 推理：106.7k", summary)
        self.assertIn("未命中 81.5% · 缓存 4.9% · 输出 13.6%", summary)
        self.assertIn("定时任务：¥1.63 · 聊天：¥0.39 · 其他：<¥0.01", summary)
        self.assertIn("mimo-v2.6-flash：¥2.02 · 6.7M", summary)
        self.assertIn("比昨天多 87%（昨天 ¥1.08）", summary)

    def test_draft_uses_a_bounded_usage_kind_and_conversation_evidence(self) -> None:
        snapshot = usage.build_usage_snapshot(
            _Db(),
            agent_id="agent-1",
            user_id=2,
            now=_now(),
        )
        assert snapshot is not None

        draft = usage.build_usage_draft(
            snapshot,
            date_key="2026-11-14",
            occurred_at=_now(),
        )

        self.assertEqual("usage", draft.kind)
        self.assertEqual("auto:usage:daily:2026-11-14", draft.dedupe_key)
        self.assertEqual("今日 AI 花费 · ¥2.02", draft.title)
        self.assertEqual("A0", draft.risk_level)
        self.assertEqual(1, len(draft.evidence))
        self.assertEqual("conversation", draft.evidence[0].source_type)
        self.assertEqual("thr_01M633WTR5Z34AT431TBF4GA6T", draft.evidence[0].reference)

    def test_zero_usage_does_not_publish(self) -> None:
        db = _Db(totals={"total_tokens": 0})

        self.assertIsNone(
            usage.publish_usage_today_card(
                db,
                agent_id="agent-1",
                user_id=2,
                now=_now(),
            )
        )


class UsagePublicationTest(unittest.TestCase):
    def test_publishes_only_in_the_evening(self) -> None:
        calls: list[object] = []
        original_publisher = usage.TodayCardPublisher

        class FakePublisher:
            def __init__(self, db: object, *, agent_repo: object) -> None:
                calls.append(db)

        usage.TodayCardPublisher = FakePublisher
        try:
            db = _Db()
            self.assertIsNone(
                usage.publish_usage_today_card(
                    db,
                    agent_id="agent-1",
                    user_id=2,
                    now=_now(hour=20),
                )
            )
        finally:
            usage.TodayCardPublisher = original_publisher
        self.assertEqual([], calls)
        self.assertEqual(0, db.connects)
        self.assertEqual(0, db.transactions)

    def test_publishes_one_stable_card_and_dismisses_older_days(self) -> None:
        captured: dict[str, object] = {}
        original_publisher = usage.TodayCardPublisher
        original_agent_repo = usage.AgentRepo

        class FakePublisher:
            def __init__(self, db: object, *, agent_repo: object) -> None:
                captured["db"] = db
                captured["agent_repo"] = agent_repo

            def publish(self, draft: object, *, agent_id: str, user_id: int):
                captured["draft"] = draft
                captured["agent_id"] = agent_id
                captured["user_id"] = user_id
                return {"card_id": "today-agent-usage"}

        usage.TodayCardPublisher = FakePublisher
        usage.AgentRepo = lambda db: object()
        try:
            db = _Db()
            card_id = usage.publish_usage_today_card(
                db,
                agent_id="agent-1",
                user_id=2,
                now=_now(),
            )
        finally:
            usage.TodayCardPublisher = original_publisher
            usage.AgentRepo = original_agent_repo

        self.assertEqual("today-agent-usage", card_id)
        draft = captured["draft"]
        self.assertEqual("usage", draft.kind)
        self.assertEqual("auto:usage:daily:2026-11-14", draft.dedupe_key)
        self.assertEqual(1, db.transactions)
        self.assertEqual("agent:auto:usage:daily:2026-11-14", db.conn.updates[0][-1])


class UsageInstallerTest(unittest.TestCase):
    def test_card_kind_patch_preserves_signal_and_is_idempotent(self) -> None:
        source = (
            'TodayCardKind = Literal["briefing", "research", "task", "calendar", '
            '"health", "inbox", "review", "signal"]'
        )

        patched = patch_card_kind_source(source)

        self.assertIn('"signal", "usage"]', patched)
        self.assertEqual(patched, patch_card_kind_source(patched))

    def test_mobile_ingest_patch_is_idempotent(self) -> None:
        source = (
            "from octop.infra.today.health_cards import publish_health_today_cards\n"
            "\n"
            "    publish_health_today_cards(\n"
            "        server.services.db,\n"
            "        agent_id=agent_id,\n"
            "        user_id=user.id,\n"
            "        now=now,\n"
            "    )\n"
        )

        patched = patch_mobile_ingest_source(source)

        self.assertIn("from octop.infra.today.usage_cards import publish_usage_today_card", patched)
        self.assertIn("publish_usage_today_card(", patched)
        self.assertEqual(patched, patch_mobile_ingest_source(patched))


if __name__ == "__main__":
    unittest.main()
