from __future__ import annotations

import json
import unittest

import xiaomi_health_alert as alert


class FakeConnection:
    def __init__(self, pool: "FakePool") -> None:
        self.pool = pool
        self.sql = ""
        self.params: tuple = ()
        self.rows: list[dict] = []

    def execute(self, sql: str, params: tuple | None = None) -> "FakeConnection":
        self.sql = " ".join(sql.split())
        self.params = tuple(params or ())
        self.rows = []
        if self.sql.startswith("SELECT value FROM settings"):
            key = self.params[0]
            if key in self.pool.settings:
                self.rows = [{"value": self.pool.settings[key]}]
        elif "FROM agents" in self.sql:
            wanted = set(self.params)
            self.rows = [
                {"agent_id": agent_id, "username": username}
                for agent_id, username in self.pool.usernames.items()
                if agent_id in wanted
            ]
        elif self.sql.startswith("INSERT INTO settings"):
            key, value = self.params
            self.pool.settings[key] = value
        elif self.sql.startswith("DELETE FROM settings"):
            self.pool.settings.pop(self.params[0], None)
        return self

    def fetchone(self) -> dict | None:
        return self.rows[0] if self.rows else None

    def fetchall(self) -> list[dict]:
        return list(self.rows)


class FakeContext:
    def __init__(self, pool: "FakePool") -> None:
        self.pool = pool

    def __enter__(self) -> FakeConnection:
        return FakeConnection(self.pool)

    def __exit__(self, *_args: object) -> bool:
        return False


class FakePool:
    def __init__(self) -> None:
        self.settings: dict[str, str] = {}
        self.usernames = {"MD13RJ": "qizixing", "18CCXQ": "larens"}

    def connect(self) -> FakeContext:
        return FakeContext(self)

    def transaction(self) -> FakeContext:
        return FakeContext(self)


class AlertTests(unittest.TestCase):
    def setUp(self) -> None:
        self.pool = FakePool()
        self.sent: list[str] = []

    def sender(self, text: str) -> bool:
        self.sent.append(text)
        return True

    def notify(self, **kwargs: object) -> bool:
        return alert.notify_sync_outcome(
            self.pool, sender=self.sender, **kwargs
        )

    def test_first_failure_sends_once_and_stores_state(self) -> None:
        self.assertTrue(self.notify(auth_failures=["MD13RJ"], now=1000))
        self.assertEqual(1, len(self.sent))
        self.assertIn("MD13RJ（qizixing）", self.sent[0])
        self.assertIn("首次", self.sent[0])
        state = json.loads(self.pool.settings["xiaomi_health.alert:MD13RJ"])
        self.assertEqual(1000, state["since"])
        self.assertEqual(1000, state["last_notified_at"])

    def test_failure_within_day_is_deduped(self) -> None:
        self.notify(auth_failures=["MD13RJ"], now=1000)
        self.assertFalse(
            self.notify(auth_failures=["MD13RJ"], now=1000 + 60 * 60)
        )
        self.assertEqual(1, len(self.sent))

    def test_reminder_after_day(self) -> None:
        self.notify(auth_failures=["MD13RJ"], now=1000)
        self.assertTrue(
            self.notify(
                auth_failures=["MD13RJ"],
                now=1000 + alert.REMINDER_SECONDS,
            )
        )
        self.assertEqual(2, len(self.sent))
        self.assertIn("仍在失败", self.sent[1])

    def test_recovery_sends_message_and_removes_state(self) -> None:
        self.notify(auth_failures=["MD13RJ"], now=1000)
        self.assertTrue(self.notify(succeeded=["MD13RJ"], now=2000))
        self.assertEqual(2, len(self.sent))
        self.assertIn("已恢复同步", self.sent[1])
        self.assertNotIn("xiaomi_health.alert:MD13RJ", self.pool.settings)

    def test_recovery_without_failure_is_silent(self) -> None:
        self.assertFalse(self.notify(succeeded=["MD13RJ"], now=1000))
        self.assertEqual([], self.sent)

    def test_failed_delivery_keeps_state_for_retry(self) -> None:
        self.assertFalse(
            alert.notify_sync_outcome(
                self.pool,
                auth_failures=["MD13RJ"],
                now=1000,
                sender=lambda _text: False,
            )
        )
        self.assertNotIn("xiaomi_health.alert:MD13RJ", self.pool.settings)
        self.assertTrue(self.notify(auth_failures=["MD13RJ"], now=1001))
        self.assertEqual(1, len(self.sent))

    def test_multiple_agents_share_one_message(self) -> None:
        self.assertTrue(
            self.notify(
                auth_failures=["MD13RJ", "18CCXQ"],
                now=1000,
            )
        )
        self.assertEqual(1, len(self.sent))
        self.assertIn("MD13RJ（qizixing）", self.sent[0])
        self.assertIn("18CCXQ（larens）", self.sent[0])


if __name__ == "__main__":
    unittest.main()
