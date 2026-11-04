#!/usr/bin/env python3
"""Tests for Router Phase 4 task approvals and idempotent write receipts."""

from __future__ import annotations

import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from octop.infra.conversation.budget import RouterBudgetGate
from octop.infra.conversation.service import (
    ConversationService,
    InvalidTransitionError,
)
from octop.infra.db.migrate import run_migrations
from octop.infra.db.pool import SqlitePool
from octop.infra.db.repos.agents import AgentRepo
from octop.infra.db.repos.users import UserRepo

from router_phase4_actions import (
    WriteActionService,
    WriteConflictError,
    WriteFeatureDisabledError,
)


class MemorySettings:
    def __init__(self, values: dict[str, str] | None = None) -> None:
        self.values = dict(values or {})

    def get(self, key: str) -> str | None:
        return self.values.get(key)


class RouterPhase4Test(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp.name) / "octop.db"
        self.db = SqlitePool(self.db_path)
        run_migrations(self.db)
        UserRepo(self.db).create(username="alice", password_hash="h", role="user")
        AgentRepo(self.db).create(agent_id="agent-a", user_id=1, name="Agent A")
        self.settings = MemorySettings(
            {
                "router.budget.mode": "enforce",
                "router.budget.enforced_surfaces": "worker",
                "router.budget.cost_per_1k_tokens_micros": "1000",
                "router.side_effect_writes.enabled": "1",
                "router.side_effect_writes.approval_ttl_seconds": "600",
                "router.side_effect_writes.execution_ttl_seconds": "600",
            }
        )
        self.conversation = ConversationService(self.db)
        self.main = self.conversation.ensure_main_conversation(
            agent_id="agent-a",
            user_id=1,
        )
        self.gate = RouterBudgetGate(
            self.db,
            self.settings,
            timezone_name="Asia/Shanghai",
            service=self.conversation,
        )
        self.writes = self._service()

    def tearDown(self) -> None:
        self.db.close()
        self.temp.cleanup()

    def _service(self) -> WriteActionService:
        return WriteActionService(
            self.db,
            self.settings,
            timezone_name="Asia/Shanghai",
            budget_gate=self.gate,
        )

    def _propose(
        self,
        *,
        title: str = "给项目负责人发邮件",
        action_payload: dict | None = None,
        idempotency_key: str = "idem_phase4_test_0001",
    ) -> dict:
        return self.writes.create_task(
            agent_id="agent-a",
            user_id=1,
            conversation_id=str(self.main["conversation_id"]),
            title=title,
            objective="发送一封固定内容的测试邮件",
            action_name="email.send",
            action_payload=action_payload or {"to": "owner@example.com", "body": "ok"},
            token_limit=8_000,
            idempotency_key=idempotency_key,
        )

    def _approve(self, proposal: dict) -> dict:
        approval = proposal["approval"]
        return self.writes.approve(
            agent_id="agent-a",
            user_id=1,
            task_id=approval["task_id"],
            approval_id=approval["approval_id"],
            action_hash=approval["action_hash"],
            version=approval["version"],
        )

    def _claim(self, proposal: dict, *, key: str | None = None) -> dict:
        approval = proposal["approval"]
        return self.writes.claim_write(
            agent_id="agent-a",
            user_id=1,
            task_id=approval["task_id"],
            approval_id=approval["approval_id"],
            action_hash=approval["action_hash"],
            idempotency_key=key or approval["idempotency_key"],
        )

    def test_proposal_creates_task_and_approval_but_no_run_or_receipt(self) -> None:
        proposal = self._propose()

        self.assertTrue(proposal["requires_approval"])
        self.assertFalse(proposal["dispatched"])
        self.assertEqual(proposal["approval"]["status"], "pending")
        self.assertEqual(proposal["task"]["status"], "needs_user")
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM conversation_tasks").fetchone()[0],
                1,
            )
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM task_runs").fetchone()[0],
                0,
            )
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM write_receipts").fetchone()[0],
                0,
            )
            event = conn.execute(
                "SELECT event_type, payload_json FROM conversation_events "
                "ORDER BY seq DESC LIMIT 1"
            ).fetchone()
        self.assertEqual(event["event_type"], "task.approval_required")
        self.assertIn("action_hash", event["payload_json"])
        events = self.conversation.list_events(
            agent_id="agent-a",
            user_id=1,
            conversation_id=str(self.main["conversation_id"]),
            include_plan_payload=True,
        )
        self.assertEqual(events[-1]["event_type"], "task.approval_required")
        self.assertEqual(
            events[-1]["payload"]["approval_id"],
            proposal["approval"]["approval_id"],
        )

    def test_approval_requires_exact_hash_and_reserves_budget_once(self) -> None:
        proposal = self._propose()
        approval = proposal["approval"]
        with self.assertRaises(WriteConflictError):
            self.writes.approve(
                agent_id="agent-a",
                user_id=1,
                task_id=approval["task_id"],
                approval_id=approval["approval_id"],
                action_hash="0" * 64,
                version=approval["version"],
            )
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM task_runs").fetchone()[0],
                0,
            )

        approved = self._approve(proposal)
        self.assertEqual(approved["approval"]["status"], "approved")
        self.assertEqual(approved["task"]["status"], "in_progress")
        self.assertIsNotNone(approved["task"]["accepted_at"])
        self.assertEqual(
            approved["task"]["acceptance_source"],
            "task_approval",
        )
        repeated = self._approve(proposal)
        self.assertEqual(
            repeated["approval"]["run_id"],
            approved["approval"]["run_id"],
        )
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM task_runs").fetchone()[0],
                1,
            )
            ledger = conn.execute(
                "SELECT reserved_tokens, spent_tokens, limit_tokens "
                "FROM budget_ledgers WHERE scope_type = 'run'"
            ).fetchone()
        self.assertEqual(ledger["reserved_tokens"], 8_000)
        self.assertEqual(ledger["spent_tokens"], 0)
        self.assertEqual(ledger["limit_tokens"], 8_000)

    def test_expired_approval_never_creates_a_run(self) -> None:
        proposal = self._propose()
        approval = proposal["approval"]
        with self.db.connect() as conn:
            conn.execute(
                "UPDATE task_approvals SET expires_at = 1 WHERE approval_id = ?",
                (approval["approval_id"],),
            )
        with self.assertRaises(InvalidTransitionError):
            self._approve(proposal)
        with self.db.connect() as conn:
            task = conn.execute(
                "SELECT status FROM conversation_tasks WHERE task_id = ?",
                (approval["task_id"],),
            ).fetchone()
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM task_runs").fetchone()[0],
                0,
            )
        self.assertEqual(task["status"], "expired")

    def test_claim_returns_one_receipt_and_never_executes_twice(self) -> None:
        proposal = self._propose()
        self._approve(proposal)

        first = self._claim(proposal)
        second = self._claim(proposal)

        self.assertTrue(first["should_execute"])
        self.assertFalse(second["should_execute"])
        self.assertEqual(
            first["receipt"]["receipt_id"],
            second["receipt"]["receipt_id"],
        )
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM write_receipts").fetchone()[0],
                1,
            )
            run = conn.execute("SELECT status, attempt_no FROM task_runs").fetchone()
        self.assertEqual((run["status"], run["attempt_no"]), ("running", 1))

    def test_same_key_for_another_action_is_a_conflict(self) -> None:
        first = self._propose(idempotency_key="idem_shared_phase4")
        self._approve(first)
        first_claim = self._claim(first)
        self.assertTrue(first_claim["should_execute"])

        second = self._propose(
            title="创建日历事项",
            action_payload={"title": "Review", "at": "2026-11-04T10:00:00+08:00"},
            idempotency_key="idem_other_phase4",
        )
        self._approve(second)
        with self.assertRaises(WriteConflictError):
            self._claim(second, key="idem_shared_phase4")

    def test_success_records_outcome_and_settles_budget(self) -> None:
        proposal = self._propose()
        self._approve(proposal)
        claim = self._claim(proposal)
        receipt_id = claim["receipt"]["receipt_id"]

        completed = self.writes.complete_write(
            agent_id="agent-a",
            user_id=1,
            receipt_id=receipt_id,
            outcome={"message_id": "msg-1"},
            outcome_ref="mail:msg-1",
            actual_tokens=2_500,
        )
        repeated = self.writes.complete_write(
            agent_id="agent-a",
            user_id=1,
            receipt_id=receipt_id,
            outcome={"message_id": "ignored-on-retry"},
            outcome_ref="mail:ignored",
            actual_tokens=9_999,
        )

        self.assertEqual(completed["status"], "succeeded")
        self.assertEqual(completed["outcome_ref"], "mail:msg-1")
        self.assertEqual(completed["actual_tokens"], 2_500)
        self.assertEqual(repeated["outcome_ref"], "mail:msg-1")
        with self.db.connect() as conn:
            task = conn.execute(
                "SELECT status, accepted_at, acceptance_source, outcome_ref "
                "FROM conversation_tasks WHERE task_id = ?",
                (proposal["approval"]["task_id"],),
            ).fetchone()
            run = conn.execute("SELECT status FROM task_runs").fetchone()
            ledgers = conn.execute(
                "SELECT reserved_tokens, spent_tokens FROM budget_ledgers"
            ).fetchall()
        self.assertEqual(task["status"], "done")
        self.assertIsNotNone(task["accepted_at"])
        self.assertEqual(task["acceptance_source"], "task_approval")
        self.assertEqual(task["outcome_ref"], "mail:msg-1")
        self.assertEqual(run["status"], "succeeded")
        self.assertTrue(all(row["reserved_tokens"] == 0 for row in ledgers))
        self.assertTrue(all(row["spent_tokens"] == 2_500 for row in ledgers))

    def test_uncertain_result_stops_and_cannot_auto_retry(self) -> None:
        proposal = self._propose()
        self._approve(proposal)
        claim = self._claim(proposal)
        receipt_id = claim["receipt"]["receipt_id"]

        uncertain = self.writes.mark_result_uncertain(
            agent_id="agent-a",
            user_id=1,
            receipt_id=receipt_id,
            error="response was lost after the provider accepted the request",
            actual_tokens=1_200,
        )
        duplicate = self._claim(proposal)

        self.assertEqual(uncertain["status"], "result_uncertain")
        self.assertFalse(duplicate["should_execute"])
        self.assertEqual(
            duplicate["receipt"]["receipt_id"],
            uncertain["receipt_id"],
        )
        with self.db.connect() as conn:
            task = conn.execute(
                "SELECT status FROM conversation_tasks WHERE task_id = ?",
                (proposal["approval"]["task_id"],),
            ).fetchone()
            run = conn.execute(
                "SELECT status, failure_class FROM task_runs"
            ).fetchone()
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM task_runs").fetchone()[0],
                1,
            )
        self.assertEqual(task["status"], "needs_user")
        self.assertEqual(run["status"], "failed")
        self.assertEqual(run["failure_class"], "result_uncertain")

    def test_expired_executor_lease_becomes_uncertain_without_retry(self) -> None:
        proposal = self._propose()
        self._approve(proposal)
        claim = self._claim(proposal)
        with self.db.connect() as conn:
            conn.execute(
                "UPDATE task_runs SET lease_until = 1 WHERE run_id = ?",
                (claim["receipt"]["run_id"],),
            )

        recovered = self.writes.recover_expired_writes(now=2)

        self.assertEqual(len(recovered), 1)
        self.assertEqual(recovered[0]["status"], "result_uncertain")
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM task_runs").fetchone()[0],
                1,
            )
            self.assertEqual(
                conn.execute(
                    "SELECT COUNT(*) FROM budget_ledgers WHERE reserved_tokens > 0"
                ).fetchone()[0],
                0,
            )

    def test_concurrent_claims_only_one_executor_wins(self) -> None:
        proposal = self._propose()
        self._approve(proposal)

        def claim_once() -> dict:
            return self._service().claim_write(
                agent_id="agent-a",
                user_id=1,
                task_id=proposal["approval"]["task_id"],
                approval_id=proposal["approval"]["approval_id"],
                action_hash=proposal["approval"]["action_hash"],
                idempotency_key=proposal["approval"]["idempotency_key"],
            )

        with ThreadPoolExecutor(max_workers=6) as pool:
            results = list(pool.map(lambda _: claim_once(), range(6)))

        self.assertEqual(
            sum(1 for result in results if result["should_execute"]),
            1,
        )
        self.assertEqual(
            len({result["receipt"]["receipt_id"] for result in results}),
            1,
        )
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM write_receipts").fetchone()[0],
                1,
            )

    def test_feature_and_budget_controls_fail_closed(self) -> None:
        self.settings.values["router.side_effect_writes.enabled"] = "0"
        with self.assertRaises(WriteFeatureDisabledError):
            self._propose()

        self.settings.values["router.side_effect_writes.enabled"] = "1"
        self.settings.values["router.budget.mode"] = "shadow"
        with self.assertRaises(WriteFeatureDisabledError):
            self._propose()


if __name__ == "__main__":
    unittest.main()
