from __future__ import annotations

import importlib.util
import json
import tempfile
import time
import unittest
from pathlib import Path

HEALTH_SCRIPT_CANDIDATES = (
    Path(__file__).parent / "ops/memory_health_check.py",
    Path(__file__).parent.parent / "ops/memory_health_check.py",
)


def _load_module():
    script = next(path for path in HEALTH_SCRIPT_CANDIDATES if path.is_file())
    spec = importlib.util.spec_from_file_location("memory_health_check", script)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class MemoryHealthAlertTest(unittest.TestCase):
    def setUp(self) -> None:
        self.module = _load_module()
        self.sent: list[tuple[str, str, str]] = []
        self.module._send_webhook = lambda url, fmt, text: self.sent.append((url, fmt, text))

    def _payload(self, ok: bool) -> dict:
        return {
            "ok": ok,
            "checked_at": "2026-11-06T12:00:00+0800",
            "service": {"active": True, "main_pid": 123},
            "errors": [] if ok else ["database is malformed"],
            "warnings": [],
        }

    def test_failure_and_recovery_are_sent_once(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp) / "alert.json"
            failed = self.module._deliver_alert(
                self._payload(False),
                previous={"ok": True},
                state_file=state,
                webhook="https://example.test/hook",
                fmt="generic",
                repeat_seconds=21600,
            )
            self.assertTrue(failed["delivered"])
            duplicate = self.module._deliver_alert(
                self._payload(False),
                previous={"ok": False},
                state_file=state,
                webhook="https://example.test/hook",
                fmt="generic",
                repeat_seconds=21600,
            )
            self.assertFalse(duplicate["attempted"])
            recovered = self.module._deliver_alert(
                self._payload(True),
                previous={"ok": False},
                state_file=state,
                webhook="https://example.test/hook",
                fmt="generic",
                repeat_seconds=21600,
            )
            self.assertTrue(recovered["delivered"])
            self.assertEqual(2, len(self.sent))

    def test_failure_is_repeated_after_interval(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp) / "alert.json"
            state.write_text(
                json.dumps({"last_sent_at": int(time.time()) - 21601, "last_ok": False}),
                encoding="utf-8",
            )
            result = self.module._deliver_alert(
                self._payload(False),
                previous={"ok": False},
                state_file=state,
                webhook="https://example.test/hook",
                fmt="generic",
                repeat_seconds=21600,
            )
            self.assertTrue(result["delivered"])
            self.assertEqual(1, len(self.sent))

    def test_unconfigured_webhook_does_not_send(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            result = self.module._deliver_alert(
                self._payload(False),
                previous={"ok": True},
                state_file=Path(tmp) / "alert.json",
                webhook=None,
                fmt="generic",
                repeat_seconds=21600,
            )
            self.assertFalse(result["configured"])
            self.assertFalse(result["attempted"])
            self.assertEqual([], self.sent)


if __name__ == "__main__":
    unittest.main()
