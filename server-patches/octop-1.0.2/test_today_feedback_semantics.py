from __future__ import annotations

import unittest

from install_today_feedback_semantics import (
    NEW_BRANCH,
    OLD_BRANCH,
    PUBLISHER_NEW,
    PUBLISHER_OLD,
    RESPONSE_NEW,
    RESPONSE_OLD,
    patch_publisher,
    patch_source,
)


BASE_SOURCE = OLD_BRANCH + RESPONSE_OLD


class TodayFeedbackSemanticsTest(unittest.TestCase):
    def test_invalid_feedback_disables_linked_monitor(self) -> None:
        patched = patch_source(BASE_SOURCE)

        self.assertIn(NEW_BRANCH, patched)
        self.assertIn('body.feedback_type == "irrelevant"', patched)
        self.assertIn("enabled = 0, next_run_at = NULL", patched)
        invalid_branch = patched.split('elif body.feedback_type == "irrelevant":', 1)[1]
        self.assertNotIn("feedback_backoff_seconds", invalid_branch)

    def test_too_frequent_snoozes_every_card_and_returns_updated_row(self) -> None:
        patched = patch_source(BASE_SOURCE)

        self.assertIn("card_backoff_until", patched)
        self.assertIn("lifecycle = 'snoozed'", patched)
        self.assertIn("min(max(int(feedback_count), 1), 2)", patched)
        self.assertIn(RESPONSE_NEW, patched)
        self.assertIn('if body.feedback_type in {"too_frequent", "irrelevant"}', patched)

    def test_publisher_preserves_an_active_snooze(self) -> None:
        patched = patch_publisher(PUBLISHER_OLD)

        self.assertIn(PUBLISHER_NEW, patched)
        self.assertIn("preserve_snooze", patched)
        self.assertIn("snoozed_until > now", patched)

    def test_installer_is_idempotent(self) -> None:
        patched = patch_source(BASE_SOURCE)

        self.assertEqual(patched, patch_source(patched))
        self.assertEqual(
            patch_publisher(PUBLISHER_OLD),
            patch_publisher(patch_publisher(PUBLISHER_OLD)),
        )


if __name__ == "__main__":
    unittest.main()
