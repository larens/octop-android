"""Tests for feed governance, rolling summaries, router gating, and retention."""

from __future__ import annotations

import importlib.util
import os
import sqlite3
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from langchain_core.messages import HumanMessage, SystemMessage

import feed_governance
from feed_governance import (
    FeedPolicy,
    fetch_feed_candidates,
    render_feed_digest,
    sync_today_cards,
)
from rolling_summary import ROLLING_MARKER, build_incremental_input, install_rolling_summary
from router_phase0_context import compile_context_pack
from router_phase0_shadow import (
    classify_shadow_route,
    project_task_capsule,
    render_task_capsule,
)


ROOT = Path(__file__).resolve().parent
OPS_SCRIPT_CANDIDATES = (
    ROOT / "ops/storage_maintenance.py",
    ROOT.parent / "ops/storage_maintenance.py",
)


def _storage_module():
    script = next(path for path in OPS_SCRIPT_CANDIDATES if path.is_file())
    spec = importlib.util.spec_from_file_location("storage_maintenance", script)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _feed_db(path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.executescript(
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
          lifecycle TEXT NOT NULL,
          occurred_at INTEGER NOT NULL,
          snoozed_until INTEGER,
          updated_at INTEGER NOT NULL,
          PRIMARY KEY (agent_id, card_id)
        );
        CREATE TABLE feed_events (
          event_id TEXT PRIMARY KEY,
          agent_id TEXT NOT NULL,
          user_id INTEGER NOT NULL,
          source TEXT NOT NULL,
          source_ref TEXT NOT NULL,
          dedupe_key TEXT NOT NULL,
          content_hash TEXT NOT NULL,
          kind TEXT NOT NULL,
          title TEXT NOT NULL,
          summary TEXT NOT NULL DEFAULT '',
          why_now TEXT NOT NULL DEFAULT '',
          lifecycle TEXT NOT NULL,
          occurred_at INTEGER NOT NULL,
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL,
          payload_json TEXT NOT NULL DEFAULT '{}',
          UNIQUE (agent_id, dedupe_key)
        );
        """
    )
    return conn


class FeedGovernanceTest(unittest.TestCase):
    def test_sync_is_idempotent_and_digest_is_bounded(self) -> None:
        now = 1_800_000_000
        with tempfile.TemporaryDirectory() as tmp:
            conn = _feed_db(Path(tmp) / "db.sqlite")
            rows = [
                ("a1", "c1", "calendar", "周会", "明天 10:00 与张三开会", now - 60),
                ("a1", "c2", "news", "新闻", "模型发布", now - 120),
                ("a1", "c3", "health", "睡眠", "昨晚 7 小时", now - 180),
                ("a1", "c4", "signal", "提醒", "该交周报了", now - 240),
                ("a1", "c5", "task", "任务", "已完成", now - 300),
            ]
            for agent, card, kind, title, summary, occurred in rows:
                conn.execute(
                    "INSERT INTO today_cards VALUES "
                    "(?, ?, 1, ?, ?, ?, ?, 'because', ?, ?, NULL, ?)",
                    (
                        agent,
                        card,
                        f"{agent}:{card}",
                        kind,
                        title,
                        summary,
                        "read" if card == "c5" else "unread",
                        occurred,
                        occurred,
                    ),
                )
            conn.commit()

            first = sync_today_cards(conn, agent_id="a1", user_id=1, now=now)
            second = sync_today_cards(conn, agent_id="a1", user_id=1, now=now)
            self.assertEqual(first["scanned"], 5)
            self.assertEqual(first["changed"], 5)
            self.assertEqual(second["changed"], 0)
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM feed_events").fetchone()[0], 5
            )

            items = fetch_feed_candidates(
                conn, agent_id="a1", user_id=1, limit=3, max_chars=600, now=now
            )
            self.assertLessEqual(len(items), 3)
            self.assertTrue(all(item["ref"].startswith("feed://event/") for item in items))
            digest = render_feed_digest(items)
            self.assertIn("<feed_digest>", digest)
            self.assertNotIn("已完成", digest)

            conn.execute("UPDATE today_cards SET lifecycle = 'dismissed' WHERE card_id = 'c1'")
            conn.commit()
            changed = sync_today_cards(conn, agent_id="a1", user_id=1, now=now + 1)
            self.assertEqual(changed["changed"], 1)
            state = conn.execute(
                "SELECT lifecycle FROM feed_events WHERE source_ref LIKE '%:c1'"
            ).fetchone()[0]
            self.assertEqual(state, "dismissed")
            conn.close()

    def test_expired_snooze_becomes_unread(self) -> None:
        now = 1_800_000_000
        with tempfile.TemporaryDirectory() as tmp:
            conn = _feed_db(Path(tmp) / "db.sqlite")
            conn.execute(
                "INSERT INTO today_cards VALUES "
                "('a1', 'c1', 1, 'a1:c1', 'signal', '提醒', '内容', 'why', "
                "'snoozed', ?, ?, ?)",
                (now - 10, now - 1, now - 10),
            )
            conn.commit()
            sync_today_cards(conn, agent_id="a1", user_id=1, now=now)
            state = conn.execute("SELECT lifecycle FROM feed_events").fetchone()[0]
            self.assertEqual(state, "unread")
            conn.close()

    def test_millisecond_calendar_timestamps_are_normalized(self) -> None:
        now = 1_800_000_000
        with tempfile.TemporaryDirectory() as tmp:
            conn = _feed_db(Path(tmp) / "db.sqlite")
            conn.execute(
                "INSERT INTO today_cards VALUES "
                "('a1', 'c1', 1, 'a1:c1', 'calendar', '周会', '明天 10:00', 'why', "
                "'unread', ?, NULL, ?)",
                ((now - 60) * 1000, now - 60),
            )
            conn.commit()
            sync_today_cards(conn, agent_id="a1", user_id=1, now=now)
            stored = conn.execute("SELECT occurred_at FROM feed_events").fetchone()[0]
            self.assertEqual(stored, now - 60)
            items = fetch_feed_candidates(conn, agent_id="a1", user_id=1, now=now)
            self.assertEqual(len(items), 1)
            self.assertIn("周会", render_feed_digest(items))
            conn.close()

    def test_middleware_appends_digest_and_skips_worker(self) -> None:
        now = int(time.time())
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "db.sqlite"
            conn = _feed_db(path)
            conn.execute(
                "INSERT INTO feed_events VALUES "
                "('fe_1', 'a1', 1, 'today', 'today:a1:c1', 'today:a1:c1', 'h', "
                "'calendar', '周会', '明天 10:00', '', 'unread', ?, ?, ?, '{}')",
                (now - 30, now - 30, now - 30),
            )
            conn.commit()
            conn.close()

            policy = FeedPolicy(
                enabled=True,
                db_path=path,
                max_events=3,
                max_chars=800,
                max_age_days=14,
                sync_seconds=0,
            )
            middleware = feed_governance.FeedDigestMiddleware(policy=policy)
            from langchain.agents.middleware import ModelRequest

            request = ModelRequest(
                model=mock.Mock(),
                messages=[HumanMessage(content="今天有什么")],
                system_message=SystemMessage(content="base"),
            )
            with mock.patch.object(
                feed_governance,
                "runtime_config",
                return_value={
                    "configurable": {
                        "agent_id": "a1",
                        "user": "1",
                        "source": "chat",
                    }
                },
            ):
                prepared = middleware.prepare(request)
            rendered = str(prepared.system_message.content)
            self.assertIn("<feed_digest>", rendered)
            self.assertIn("fe_1", rendered)

            with mock.patch.object(
                feed_governance,
                "runtime_config",
                return_value={
                    "configurable": {
                        "agent_id": "a1",
                        "user": "1",
                        "source": "worker",
                    }
                },
            ):
                untouched = middleware.prepare(request)
            self.assertEqual(untouched.system_message.content, "base")


class RollingSummaryTest(unittest.TestCase):
    def test_incremental_input_keeps_previous_summary_and_new_turns(self) -> None:
        previous = HumanMessage(
            content="<summary>用户偏好下午开会</summary>",
            additional_kwargs={"lc_source": "summarization"},
        )
        messages = [
            previous,
            HumanMessage(content="新决定：周五交付"),
            HumanMessage(content="收到"),
        ]
        incremental = build_incremental_input(messages)
        self.assertIsNotNone(incremental)
        text = incremental[0].content
        self.assertIn(ROLLING_MARKER, text)
        self.assertIn("用户偏好下午开会", text)
        self.assertIn("周五交付", text)
        self.assertNotIn("<summary>用户偏好下午开会</summary>", text)

        self.assertIsNone(build_incremental_input([HumanMessage(content="没有摘要")]))
        self.assertIsNone(build_incremental_input([previous]))

    def test_install_wraps_only_second_compaction(self) -> None:
        class Fake:
            _lc_helper = object()

            def __init__(self) -> None:
                self.inputs: list[list] = []

            def _create_summary(self, messages: list) -> str:
                self.inputs.append(messages)
                return "summary"

            async def _acreate_summary(self, messages: list) -> str:
                return self._create_summary(messages)

        mw = Fake()
        install_rolling_summary(mw)
        first = [HumanMessage(content="第一轮"), HumanMessage(content="第二轮")]
        self.assertEqual(mw._create_summary(first), "summary")
        self.assertEqual(mw.inputs[-1], first)
        second = [
            HumanMessage(
                content="<summary>第一轮摘要</summary>",
                additional_kwargs={"lc_source": "summarization"},
            ),
            HumanMessage(content="第三轮"),
        ]
        self.assertEqual(mw._create_summary(second), "summary")
        self.assertIsNot(mw.inputs[-1], second)
        self.assertEqual(len(mw.inputs[-1]), 1)
        self.assertIn(ROLLING_MARKER, mw.inputs[-1][0].content)


class RouterGatingTest(unittest.TestCase):
    def test_route_classes(self) -> None:
        self.assertEqual(classify_shadow_route("你好")["route_class"], "direct_reply")
        self.assertEqual(
            classify_shadow_route("你还记得我上次说的交付时间吗")["route_class"],
            "memory_recall",
        )
        self.assertEqual(
            classify_shadow_route("这条新闻展开说说", feed_event_count=2)["route_class"],
            "feed_event",
        )
        read = classify_shadow_route("帮我看一下明天的日历")
        self.assertEqual(read["intent"], "read_only_task")
        self.assertEqual(read["route_class"], "read_only_tool")
        self.assertFalse(read["worker_required"])
        side = classify_shadow_route("把总结发送给张三")
        self.assertEqual(side["route_class"], "side_effect_task")
        self.assertTrue(side["requires_confirmation"])
        self.assertFalse(side["worker_required"])
        multi = classify_shadow_route("先查日程，然后再把总结发送给张三")
        self.assertEqual(multi["route_class"], "multi_step_worker")
        self.assertTrue(multi["worker_required"])
        self.assertFalse(multi["dispatch_enabled"])

    def test_task_capsule_is_bounded_and_canonical(self) -> None:
        task = {
            "task_id": "task-1",
            "title": "整理资料",
            "objective": "整理全部资料" * 200,
            "status": "done",
            "requires_user": 0,
            "capsule": {"result": "完成" * 2_000, "trace_id": "trace-1"},
        }
        capsule = project_task_capsule(task)
        self.assertEqual(capsule["status"], "done")
        self.assertLessEqual(len(capsule["goal"]), 600)
        self.assertLessEqual(len(capsule["result"]), 900)
        text = render_task_capsule(task)
        self.assertIn("目标:", text)
        self.assertIn("结果:", text)
        self.assertIn("状态: done", text)

    def test_feed_section_is_budgeted(self) -> None:
        pack = compile_context_pack(
            feed_events=[
                {
                    "ref": f"feed://event/{index}",
                    "content": "- [fe] 11-07 10:00 · news · 标题 · 摘要",
                    "tokens": 4,
                    "occurred_at": 100 + index,
                }
                for index in range(8)
            ],
            policy={"max_total_tokens": 100, "feed_events": {"max_items": 3}},
        )
        sections = {section["kind"]: section for section in pack["sections"]}
        self.assertIn("feed_events", sections)
        self.assertEqual(len(sections["feed_events"]["items"]), 3)


class ArtifactRetentionTest(unittest.TestCase):
    def test_cleanup_tree_skips_new_files_and_symlinks(self) -> None:
        module = _storage_module()
        now = 1_800_000_000
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "large_tool_results"
            root.mkdir()
            old = root / "old.txt"
            new = root / "new.txt"
            old.write_text("old", encoding="utf-8")
            new.write_text("new", encoding="utf-8")
            os.utime(old, (now - 40 * 86_400, now - 40 * 86_400))
            os.utime(new, (now - 1 * 86_400, now - 1 * 86_400))
            link = root / "link.txt"
            link.symlink_to(old)

            dry = module._cleanup_tree(root, days=30, now=now, apply=False)
            self.assertEqual(dry["candidates"], 1)
            self.assertTrue(old.exists())
            self.assertTrue(link.exists())

            applied = module._cleanup_tree(root, days=30, now=now, apply=True)
            self.assertEqual(applied["removed"], 1)
            self.assertFalse(old.exists())
            self.assertTrue(new.exists())
            self.assertTrue(link.is_symlink())

    def test_database_cleanup_respects_cutoff(self) -> None:
        module = _storage_module()
        now = 1_800_000_000
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "octop.db"
            conn = sqlite3.connect(path)
            conn.executescript(
                """
                CREATE TABLE feed_events (event_id TEXT PRIMARY KEY, occurred_at INTEGER);
                CREATE TABLE context_pack_manifests (pack_id TEXT PRIMARY KEY, created_at INTEGER);
                """
            )
            conn.execute("INSERT INTO feed_events VALUES ('old', ?)", (now - 100 * 86_400,))
            conn.execute("INSERT INTO feed_events VALUES ('new', ?)", (now - 1 * 86_400,))
            conn.execute(
                "INSERT INTO context_pack_manifests VALUES ('old', ?)",
                (now - 100 * 86_400,),
            )
            conn.commit()
            conn.close()

            dry = module._cleanup_database(
                path, feed_days=90, context_pack_days=90, now=now, apply=False
            )
            self.assertEqual(dry["feed_events"]["expired"], 1)
            applied = module._cleanup_database(
                path, feed_days=90, context_pack_days=90, now=now, apply=True
            )
            self.assertEqual(applied["feed_events"]["expired"], 1)
            self.assertEqual(applied["context_pack_manifests"]["expired"], 1)
            conn = sqlite3.connect(path)
            self.assertEqual(
                [row[0] for row in conn.execute("SELECT event_id FROM feed_events")],
                ["new"],
            )
            conn.close()


if __name__ == "__main__":
    unittest.main()
