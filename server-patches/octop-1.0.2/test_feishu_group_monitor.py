from __future__ import annotations

import sqlite3
import tempfile
import time
import unittest
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import feishu_group_monitor as monitor

ZONE = ZoneInfo("Asia/Shanghai")


def epoch(text: str) -> int:
    return int(datetime.strptime(text, "%Y-%m-%d %H:%M").replace(tzinfo=ZONE).timestamp())


def raw(
    message_id: str,
    created: str,
    text: str,
    *,
    thread_id: str | None = "omt_1",
    official: bool = False,
    sender: str = "群友",
) -> monitor.RawMessage:
    return monitor.RawMessage(
        message_id=message_id,
        chat_id="oc_1",
        thread_id=thread_id,
        sender_id=f"ou_{sender}",
        sender_name=sender,
        sender_type="app" if official else "user",
        is_official=official,
        created_at=epoch(created),
        text=text,
        link="https://applink.feishu.cn/client/thread/open?open_thread_id=omt_1",
    )


class TextRulesTest(unittest.TestCase):
    def test_clean_text_strips_card_chrome_and_boilerplate(self) -> None:
        text = monitor.clean_text(
            "<card>\n关于扣费：无法确认到账时点。\n---\n💡 更多使用问题请转移至社区\n"
            "· 021794011942883fdbddc0200190532c659cb53af00005d7bc4d0 ·\n</card>"
        )
        self.assertEqual(text, "关于扣费：无法确认到账时点。")

    def test_post_content_is_unwrapped(self) -> None:
        payload = (
            '{"zh_cn":{"title":"","content":[[{"tag":"text","text":"套餐配额"},'
            '{"tag":"text","text":"异常"}]]}}'
        )
        self.assertEqual(monitor.message_text(payload, "post"), "套餐配额 异常")

    def test_official_sender_detection(self) -> None:
        self.assertTrue(monitor.is_official_sender({"sender_type": "app", "name": "TRAE 宝"}))
        self.assertTrue(monitor.is_official_sender({"id_type": "app_id", "name": "任意"}))
        self.assertTrue(monitor.is_official_sender({"name": "MiniMax 小助手"}))
        self.assertFalse(monitor.is_official_sender({"sender_type": "user", "name": "用户987"}))

    def test_topic_and_group_name(self) -> None:
        self.assertEqual(monitor.topic_of("扣费后积分什么时候到账"), "积分扣费与到账")
        self.assertEqual(monitor.topic_of("套餐配额不对"), "套餐与订阅")
        self.assertIsNone(monitor.topic_of("大家好"))
        self.assertEqual(monitor.short_group_name("TRAE 用户交流群 1️⃣3️⃣｜官方"), "TRAE")
        self.assertEqual(
            monitor.short_group_name("MiniMax开放平台用户交流群1️⃣"), "MiniMax"
        )


class PriorityRulesTest(unittest.TestCase):
    def test_unresolved_payment_after_six_hours_is_p0(self) -> None:
        now = epoch("2026-11-07 09:00")
        rows = [
            raw("m1", "2026-11-06 20:00", "扣费了但积分没到账，权益下午才结束，合理吗"),
            raw("m2", "2026-11-06 20:30", "我也遇到了，扣费和到账对不上"),
            raw(
                "m3",
                "2026-11-07 08:39",
                "无法确认到账时点，建议邮件 feedback@mail.trae.cn",
                official=True,
                sender="TRAE 宝",
            ),
        ]
        priority, reasons = monitor.classify(
            rows,
            now=now,
            first_seen_at=rows[0].created_at,
            mention_me=False,
            official_reply_at=rows[-1].created_at,
            resolved=False,
        )
        self.assertEqual(priority, "p0")
        self.assertIn("扣费", "".join(reasons))

    def test_resolved_official_reply_drops_to_p2(self) -> None:
        rows = [raw("m1", "2026-11-07 08:00", "扣费异常", official=True, sender="官方")]
        priority, _ = monitor.classify(
            rows,
            now=epoch("2026-11-07 09:00"),
            first_seen_at=rows[0].created_at,
            mention_me=False,
            official_reply_at=rows[0].created_at,
            resolved=True,
        )
        self.assertEqual(priority, "p2")

    def test_single_feedback_stays_in_today(self) -> None:
        rows = [raw("m1", "2026-11-07 08:00", "这个审核已经三天了还没过")]
        priority, _ = monitor.classify(
            rows,
            now=epoch("2026-11-07 09:00"),
            first_seen_at=rows[0].created_at,
            mention_me=False,
            official_reply_at=None,
            resolved=False,
        )
        self.assertEqual(priority, "p2")

    def test_chatter_is_dropped(self) -> None:
        rows = [raw("m1", "2026-11-07 08:00", "好的")]
        priority, _ = monitor.classify(
            rows,
            now=epoch("2026-11-07 09:00"),
            first_seen_at=rows[0].created_at,
            mention_me=False,
            official_reply_at=None,
            resolved=False,
        )
        self.assertEqual(priority, "p3")


class PushWindowTest(unittest.TestCase):
    def test_quiet_hours_wrap_midnight(self) -> None:
        self.assertTrue(monitor.is_quiet_hours(epoch("2026-11-07 23:30"), ZONE))
        self.assertTrue(monitor.is_quiet_hours(epoch("2026-11-07 07:00"), ZONE))
        self.assertFalse(monitor.is_quiet_hours(epoch("2026-11-07 09:00"), ZONE))

    def test_p1_waits_for_digest_window(self) -> None:
        allowed, reason = monitor.should_push(
            priority="p1",
            state="new",
            now=epoch("2026-11-07 11:00"),
            zone=ZONE,
            last_push_at=None,
            immediate_pushes_today=0,
            deadline_or_incident=False,
        )
        self.assertFalse(allowed)
        self.assertIn("汇总窗口", reason)

    def test_p0_is_silenced_at_night_without_deadline(self) -> None:
        allowed, _ = monitor.should_push(
            priority="p0",
            state="new",
            now=epoch("2026-11-07 23:30"),
            zone=ZONE,
            last_push_at=None,
            immediate_pushes_today=0,
            deadline_or_incident=False,
        )
        self.assertFalse(allowed)

    def test_daily_cap_and_24h_dedupe(self) -> None:
        now = epoch("2026-11-07 09:00")
        blocked, reason = monitor.should_push(
            priority="p0",
            state="new",
            now=now,
            zone=ZONE,
            last_push_at=now - 3600,
            immediate_pushes_today=0,
            deadline_or_incident=True,
        )
        self.assertFalse(blocked)
        self.assertIn("24 小时", reason)
        blocked, reason = monitor.should_push(
            priority="p0",
            state="new",
            now=now,
            zone=ZONE,
            last_push_at=None,
            immediate_pushes_today=3,
            deadline_or_incident=True,
        )
        self.assertFalse(blocked)
        self.assertIn("3 条", reason)


class FakeCli:
    def __init__(self, batches: list[list[dict]]) -> None:
        self.batches = batches
        self.calls = 0
        self.dms: list[str] = []

    def messages(self, chat_id: str, *, since: int, zone) -> list[dict]:
        batch = self.batches[min(self.calls, len(self.batches) - 1)]
        self.calls += 1
        return batch

    def direct_message(self, open_id: str, text: str) -> None:
        self.dms.append(text)


def message(
    message_id: str,
    created: str,
    text: str,
    *,
    official: bool = False,
    sender: str = "用户A",
) -> dict:
    return {
        "message_id": message_id,
        "chat_id": "oc_1",
        "thread_id": "omt_1",
        "create_time": created,
        "content": text,
        "msg_type": "text",
        "message_app_link": "https://applink.feishu.cn/client/thread/open?open_thread_id=omt_1",
        "sender": {
            "id": "app_1" if official else f"ou_{sender}",
            "id_type": "app_id" if official else "open_id",
            "sender_type": "app" if official else "user",
            "name": sender,
        },
    }


class MonitorIntegrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.tmp.name) / "octop.db"
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        conn.execute("CREATE TABLE _schema_version (version INTEGER NOT NULL)")
        conn.execute(
            """CREATE TABLE today_cards (
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
              execution_thread_id TEXT,
              execution_turn_id TEXT,
              action_state TEXT,
              action_error TEXT,
              action_updated_at INTEGER,
              risk_level TEXT NOT NULL DEFAULT 'A2',
              tracking_json TEXT,
              PRIMARY KEY (agent_id, card_id),
              UNIQUE (agent_id, dedupe_key)
            )"""
        )
        migration = Path(__file__).with_name("migrations") / "036_feishu_group_monitor.sql"
        conn.executescript(migration.read_text())
        conn.execute(
            "INSERT INTO feishu_group_watch(chat_id, chat_name, agent_id, user_id, enabled, "
            "min_priority, last_scan_at, created_at, updated_at) "
            "VALUES ('oc_1', 'TRAE 用户交流群 1️⃣3️⃣｜官方', '18CCXQ', 2, 1, 'p2', NULL, 0, 0)"
        )
        conn.commit()
        conn.close()

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def _monitor(self, cli: FakeCli) -> monitor.FeishuGroupMonitor:
        db = monitor.DatabaseProxy(self.db_path)
        return monitor.FeishuGroupMonitor(db, cli=cli, zone=ZONE)

    def test_collect_publishes_p0_card_and_only_pushes_once(self) -> None:
        now = epoch("2026-11-07 09:00")
        batch = [
            message("m1", "2026-11-06 20:00", "扣费了但积分没到账，权益下午才结束", sender="用户A"),
            message("m2", "2026-11-06 20:30", "我也遇到了，扣费和到账对不上", sender="用户B"),
            message(
                "m3",
                "2026-11-07 08:39",
                "无法确认到账时点，建议邮件 feedback@mail.trae.cn",
                official=True,
                sender="TRAE 宝",
            ),
        ]
        cli = FakeCli([batch, batch])
        first = self._monitor(cli).collect(now=now)
        self.assertEqual(len(first), 1)
        payload = first[0].payload
        self.assertEqual(payload["event"], "today.card_changed")
        self.assertEqual(payload["priority"], "p0")
        self.assertIn("需处理", payload["title"])
        self.assertTrue(first[0].dm_text)

        second = self._monitor(cli).collect(now=now + 600)
        self.assertEqual(second, [])

        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        card = conn.execute("SELECT * FROM today_cards").fetchone()
        self.assertEqual(card["kind"], "signal")
        self.assertIn("官方", card["summary"])
        event = conn.execute("SELECT * FROM feishu_group_event").fetchone()
        self.assertEqual(event["priority"], "p0")
        self.assertEqual(event["notify_count"], 1)
        conn.close()

    def test_dm_is_best_effort_and_never_raises(self) -> None:
        class Broken:
            def direct_message(self, open_id: str, text: str) -> None:
                raise RuntimeError("bot not available")

        intent = monitor.PushIntent(
            user_id=2,
            agent_id="18CCXQ",
            payload={},
            dm_text="需处理",
        )
        monitor.deliver([intent], cli=Broken())

    def test_chatter_never_reaches_today(self) -> None:
        cli = FakeCli([[message("m1", "2026-11-07 08:00", "好的")]])
        intents = self._monitor(cli).collect(now=epoch("2026-11-07 09:00"))
        self.assertEqual(intents, [])
        conn = sqlite3.connect(self.db_path)
        self.assertEqual(conn.execute("SELECT COUNT(*) FROM today_cards").fetchone()[0], 0)
        conn.close()


if __name__ == "__main__":
    unittest.main()
