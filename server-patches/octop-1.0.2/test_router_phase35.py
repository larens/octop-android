#!/usr/bin/env python3
"""Tests for Router Phase 3.5 dispatch plans and pre-approved Worker budgets."""

from __future__ import annotations

import json
import tempfile
import time
import unittest
from pathlib import Path

from octop.infra.conversation.budget import RouterBudgetGate
from octop.infra.conversation.service import (
    BudgetExceededError,
    ConversationService,
    InvalidTransitionError,
    RouterPhase0Error,
)
from octop.infra.db.migrate import run_migrations
from octop.infra.db.pool import SqlitePool
from octop.infra.db.repos.agents import AgentRepo
from octop.infra.db.repos.users import UserRepo

from router_phase35_plans import DispatchPlanService
from router_phase3_orchestrator import auto_plan_items, should_propose_auto_plan


class MemorySettings:
    def __init__(self, values: dict[str, str] | None = None) -> None:
        self.values = dict(values or {})

    def get(self, key: str) -> str | None:
        return self.values.get(key)


class RouterPhase35Test(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.db = SqlitePool(Path(self.temp.name) / "octop.db")
        run_migrations(self.db)
        self._ensure_phase35_attempt_column()
        UserRepo(self.db).create(username="alice", password_hash="h", role="user")
        AgentRepo(self.db).create(agent_id="agent-a", user_id=1, name="Agent A")
        self.settings = MemorySettings(
            {
                "router.budget.mode": "enforce",
                "router.budget.enforced_surfaces": "worker",
                "router.budget.cost_per_1k_tokens_micros": "1000",
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
        self.plans = DispatchPlanService(
            self.db,
            self.settings,
            timezone_name="Asia/Shanghai",
            budget_gate=self.gate,
            max_attempts=2,
        )

    def tearDown(self) -> None:
        self.db.close()
        self.temp.cleanup()

    def _ensure_phase35_attempt_column(self) -> None:
        """Support testing against a candidate package that predates this column."""
        with self.db.connect() as conn:
            columns = {
                str(row["name"])
                for row in conn.execute("PRAGMA table_info(dispatch_plans)")
            }
        if "max_attempts_per_worker" not in columns:
            with self.db.connect() as conn:
                conn.execute(
                    "ALTER TABLE dispatch_plans ADD COLUMN "
                    "max_attempts_per_worker INTEGER NOT NULL DEFAULT 3"
                )

    def _plan(self) -> dict:
        return self.plans.create_plan(
            conversation_id=str(self.main["conversation_id"]),
            trace_id="trace-plan",
            context_pack_id="pack-plan",
            objective="调研两个城市的天气并汇总",
            items=[
                {
                    "key": "city_a",
                    "title": "调研城市 A",
                    "objective": "阅读城市 A 的天气预报",
                    "worker_role": "research",
                    "depends_on": [],
                    "token_limit": 128_000,
                },
                {
                    "key": "summary",
                    "title": "汇总两个城市",
                    "objective": "分析天气差异并给出简短汇总",
                    "worker_role": "analysis",
                    "depends_on": ["city_a"],
                    "token_limit": 64_000,
                },
            ],
        )

    def test_proposal_creates_no_task_run_or_model_usage(self) -> None:
        plan = self._plan()

        self.assertEqual(plan["status"], "awaiting_approval")
        self.assertEqual(len(plan["items"]), 2)
        self.assertEqual(plan["hard_token_limit"], 192_000)
        self.assertEqual(
            plan["budget_preview"]["max_attempts_per_worker"],
            2,
        )
        self.assertEqual(plan["items"][1]["status"], "waiting_dependency")
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM conversation_tasks").fetchone()[0],
                0,
            )
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM task_runs").fetchone()[0],
                0,
            )
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM usage_log").fetchone()[0],
                0,
            )
            event = conn.execute(
                "SELECT event_type, plan_id, payload_json FROM conversation_events "
                "ORDER BY seq DESC LIMIT 1"
            ).fetchone()
        self.assertEqual(event["event_type"], "plan.proposed")
        self.assertEqual(event["plan_id"], plan["plan_id"])

    def test_approval_requires_exact_hash_and_reserves_budget_once(self) -> None:
        plan = self._plan()
        with self.assertRaises(InvalidTransitionError):
            self.plans.approve_plan(
                agent_id="agent-a",
                user_id=1,
                plan_id=plan["plan_id"],
                plan_version=plan["version"],
                plan_hash="0" * 64,
            )
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM conversation_tasks").fetchone()[0],
                0,
            )

        approved = self.plans.approve_plan(
            agent_id="agent-a",
            user_id=1,
            plan_id=plan["plan_id"],
            plan_version=plan["version"],
            plan_hash=plan["plan_hash"],
        )
        self.assertEqual(approved["status"], "executing")
        self.assertEqual(
            [item["status"] for item in approved["items"]],
            ["queued", "waiting_dependency"],
        )
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM conversation_tasks").fetchone()[0],
                2,
            )
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM task_runs").fetchone()[0],
                1,
            )
            ledger = conn.execute(
                "SELECT reserved_tokens, spent_tokens FROM budget_ledgers "
                "WHERE scope_type = 'plan' AND scope_id = ?",
                (plan["plan_id"],),
            ).fetchone()
        self.assertEqual(ledger["reserved_tokens"], 192_000)
        self.assertEqual(ledger["spent_tokens"], 0)

    def test_preapproved_worker_does_not_reserve_aggregate_budget_again(self) -> None:
        plan = self._plan()
        approved = self.plans.approve_plan(
            agent_id="agent-a",
            user_id=1,
            plan_id=plan["plan_id"],
            plan_version=plan["version"],
            plan_hash=plan["plan_hash"],
        )
        task_id = str(approved["items"][0]["child_task_id"])
        context = self.plans.worker_budget_context(task_id=task_id)
        self.assertIsNotNone(context)
        assert context is not None
        with self.db.connect() as conn:
            before = {
                row["scope_type"]: (row["reserved_tokens"], row["spent_tokens"])
                for row in conn.execute("SELECT * FROM budget_ledgers")
            }
        lease = self.gate.start_preapproved(
            surface="worker",
            agent_id="agent-a",
            user_id=1,
            conversation_id=str(self.main["conversation_id"]),
            trace_id=str(context["trace_id"]),
            plan_id=str(context["plan_id"]),
            plan_item_id=str(context["plan_item_id"]),
            remaining_tokens=int(context["remaining_tokens"]),
        )
        self.assertTrue(lease.enforced)
        lease.observe({"total_tokens": 321})
        lease.finish(actual_tokens=321)
        with self.db.connect() as conn:
            after = {
                row["scope_type"]: (row["reserved_tokens"], row["spent_tokens"])
                for row in conn.execute("SELECT * FROM budget_ledgers")
            }
            settled_event = conn.execute(
                "SELECT event_type FROM conversation_events "
                "WHERE event_type = 'budget.plan_item_settled' ORDER BY seq DESC LIMIT 1"
            ).fetchone()
        self.assertEqual(before, after)
        self.assertIsNotNone(settled_event)

    def test_dependency_unlock_and_plan_aggregation(self) -> None:
        plan = self._plan()
        approved = self.plans.approve_plan(
            agent_id="agent-a",
            user_id=1,
            plan_id=plan["plan_id"],
            plan_version=plan["version"],
            plan_hash=plan["plan_hash"],
        )
        first_task = str(approved["items"][0]["child_task_id"])
        first_run = self._first_run(first_task)
        self.conversation.transition_run(run_id=first_run, status="running")
        self.conversation.transition_run(run_id=first_run, status="succeeded")
        self.conversation.transition_task(task_id=first_task, status="done")
        self.conversation.record_usage(
            agent_id="agent-a",
            user_id=1,
            model="deepseek-v4-flash",
            input_tokens=700,
            output_tokens=300,
            total_tokens=1_000,
            source="worker",
            trace_id="trace-plan",
            task_id=first_task,
            run_id=first_run,
        )
        progressed = self.plans.on_worker_terminal(task_id=first_task)
        self.assertIsNotNone(progressed)
        assert progressed is not None
        self.assertEqual(progressed["status"], "executing")
        self.assertEqual(
            [item["status"] for item in progressed["items"]],
            ["succeeded", "queued"],
        )

        second_task = str(progressed["items"][1]["child_task_id"])
        second_run = self._first_run(second_task)
        self.conversation.transition_run(run_id=second_run, status="running")
        self.conversation.transition_run(run_id=second_run, status="succeeded")
        self.conversation.transition_task(task_id=second_task, status="done")
        self.conversation.record_usage(
            agent_id="agent-a",
            user_id=1,
            model="deepseek-v4-flash",
            input_tokens=600,
            output_tokens=400,
            total_tokens=1_000,
            source="worker",
            trace_id="trace-plan",
            task_id=second_task,
            run_id=second_run,
        )
        completed = self.plans.on_worker_terminal(task_id=second_task)
        self.assertIsNotNone(completed)
        assert completed is not None
        self.assertEqual(completed["status"], "completed")
        self.assertEqual(completed["actual_tokens"], 2_000)
        with self.db.connect() as conn:
            ledgers = conn.execute(
                "SELECT reserved_tokens, spent_tokens FROM budget_ledgers"
            ).fetchall()
        self.assertTrue(ledgers)
        self.assertTrue(all(row["reserved_tokens"] == 0 for row in ledgers))
        self.assertTrue(all(row["spent_tokens"] == 2_000 for row in ledgers))

    def test_reject_and_validation_do_not_create_execution_objects(self) -> None:
        plan = self._plan()
        rejected = self.plans.reject_plan(
            agent_id="agent-a",
            user_id=1,
            plan_id=plan["plan_id"],
            plan_version=plan["version"],
            plan_hash=plan["plan_hash"],
        )
        self.assertEqual(rejected["status"], "rejected")
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM conversation_tasks").fetchone()[0],
                0,
            )
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM task_runs").fetchone()[0],
                0,
            )
        with self.assertRaises(RouterPhase0Error):
            self.plans.create_plan(
                conversation_id=str(self.main["conversation_id"]),
                trace_id="trace-cycle",
                context_pack_id=None,
                objective="循环依赖测试",
                items=[
                    {
                        "key": "a",
                        "title": "A",
                        "objective": "阅读 A",
                        "depends_on": ["b"],
                    },
                    {
                        "key": "b",
                        "title": "B",
                        "objective": "阅读 B",
                        "depends_on": ["a"],
                    },
                ],
            )
        with self.assertRaises(RouterPhase0Error):
            self.plans.create_plan(
                conversation_id=str(self.main["conversation_id"]),
                trace_id="trace-side-effect",
                context_pack_id=None,
                objective="发送邮件给所有人",
                items=[
                    {
                        "key": "send",
                        "title": "发送",
                        "objective": "发送邮件并通知所有人",
                    }
                ],
            )

    def test_budget_failure_rolls_back_the_whole_plan(self) -> None:
        limited_settings = MemorySettings(
            {
                "router.budget.mode": "enforce",
                "router.budget.enforced_surfaces": "worker",
                "router.budget.global_daily_tokens": "100000",
            }
        )
        gate = RouterBudgetGate(
            self.db,
            limited_settings,
            timezone_name="Asia/Shanghai",
            service=self.conversation,
        )
        plans = DispatchPlanService(
            self.db,
            limited_settings,
            timezone_name="Asia/Shanghai",
            budget_gate=gate,
        )
        plan = plans.create_plan(
            conversation_id=str(self.main["conversation_id"]),
            trace_id="trace-budget",
            context_pack_id=None,
            objective="阅读并汇总天气",
            items=[
                {
                    "key": "one",
                    "title": "读取",
                    "objective": "阅读天气预报",
                    "token_limit": 128_000,
                }
            ],
        )
        with self.assertRaises(BudgetExceededError):
            plans.approve_plan(
                agent_id="agent-a",
                user_id=1,
                plan_id=plan["plan_id"],
                plan_version=plan["version"],
                plan_hash=plan["plan_hash"],
            )
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM conversation_tasks").fetchone()[0],
                0,
            )
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM task_runs").fetchone()[0],
                0,
            )
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM budget_ledgers").fetchone()[0],
                0,
            )
            self.assertEqual(
                conn.execute(
                    "SELECT status FROM dispatch_plans WHERE plan_id = ?",
                    (plan["plan_id"],),
                ).fetchone()["status"],
                "awaiting_approval",
            )

    def test_pricing_change_requires_replanning(self) -> None:
        plan = self._plan()
        self.settings.values["router.budget.cost_per_1k_tokens_micros"] = "2000"

        with self.assertRaises(InvalidTransitionError):
            self.plans.approve_plan(
                agent_id="agent-a",
                user_id=1,
                plan_id=plan["plan_id"],
                plan_version=plan["version"],
                plan_hash=plan["plan_hash"],
            )
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM conversation_tasks").fetchone()[0],
                0,
            )
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM budget_ledgers").fetchone()[0],
                0,
            )

    def test_cancel_stops_active_workers_and_settles_actual_usage(self) -> None:
        plan = self._plan()
        approved = self.plans.approve_plan(
            agent_id="agent-a",
            user_id=1,
            plan_id=plan["plan_id"],
            plan_version=plan["version"],
            plan_hash=plan["plan_hash"],
        )
        first_task = str(approved["items"][0]["child_task_id"])
        first_run = self._first_run(first_task)
        self.conversation.transition_run(run_id=first_run, status="running")
        self.conversation.record_usage(
            agent_id="agent-a",
            user_id=1,
            model="deepseek-v4-flash",
            input_tokens=300,
            output_tokens=200,
            total_tokens=500,
            source="worker",
            trace_id="trace-plan",
            task_id=first_task,
            run_id=first_run,
        )
        cancelled = self.plans.cancel_plan(
            agent_id="agent-a",
            user_id=1,
            plan_id=plan["plan_id"],
        )
        self.assertEqual(cancelled["status"], "cancelled")
        self.assertTrue(
            all(
                item["status"] in {"succeeded", "failed", "cancelled"}
                for item in cancelled["items"]
            )
        )
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute(
                    "SELECT COUNT(*) FROM task_runs WHERE status IN "
                    "('queued', 'running', 'waiting_user')"
                ).fetchone()[0],
                0,
            )
            ledgers = conn.execute(
                "SELECT reserved_tokens, spent_tokens FROM budget_ledgers"
            ).fetchall()
        self.assertTrue(ledgers)
        self.assertTrue(all(row["reserved_tokens"] == 0 for row in ledgers))
        self.assertTrue(all(row["spent_tokens"] == 500 for row in ledgers))

    def test_progress_events_are_compact_and_dependencies_are_available(self) -> None:
        plan = self._plan()
        approved = self.plans.approve_plan(
            agent_id="agent-a",
            user_id=1,
            plan_id=plan["plan_id"],
            plan_version=plan["version"],
            plan_hash=plan["plan_hash"],
        )
        first_task = str(approved["items"][0]["child_task_id"])
        self.plans.on_worker_started(task_id=first_task)
        with self.db.connect() as conn:
            conn.execute(
                "UPDATE dispatch_plan_items SET status = 'succeeded', "
                "result_summary = ? WHERE child_task_id = ?",
                ("城市 A 明天晴，最高 26 度", first_task),
            )
            progress = conn.execute(
                "SELECT payload_json FROM conversation_events "
                "WHERE event_type = 'plan.progress' ORDER BY seq DESC LIMIT 1"
            ).fetchone()
        payload = json.loads(progress["payload_json"])
        self.assertEqual(payload["objective"], plan["objective"])
        self.assertTrue(all(item["objective"] == "" for item in payload["items"]))

        second_task = str(approved["items"][1]["child_task_id"])
        context = self.plans.worker_budget_context(task_id=second_task)
        self.assertIsNotNone(context)
        assert context is not None
        self.assertEqual(
            context["dependency_summaries"][0]["result_summary"],
            "城市 A 明天晴，最高 26 度",
        )

    def test_budget_exhaustion_pauses_worker_and_requests_user_decision(self) -> None:
        plan = self._plan()
        approved = self.plans.approve_plan(
            agent_id="agent-a",
            user_id=1,
            plan_id=plan["plan_id"],
            plan_version=plan["version"],
            plan_hash=plan["plan_hash"],
        )
        task_id = str(approved["items"][0]["child_task_id"])
        run_id = self._first_run(task_id)
        self.conversation.transition_run(run_id=run_id, status="running")
        self.conversation.record_usage(
            agent_id="agent-a",
            user_id=1,
            model="deepseek-v4-flash",
            input_tokens=90_000,
            output_tokens=38_000,
            total_tokens=128_000,
            source="worker",
            trace_id="trace-plan",
            task_id=task_id,
            run_id=run_id,
        )

        paused = self.plans.on_worker_budget_exhausted(
            task_id=task_id,
            run_id=run_id,
            message="Worker reached its token budget",
        )

        self.assertIsNotNone(paused)
        assert paused is not None
        request = paused["budget_request"]
        self.assertEqual(request["status"], "awaiting_approval")
        self.assertTrue(request["can_continue"])
        self.assertEqual(request["requested_tokens"], 64_000)
        self.assertEqual(request["used_tokens"], 128_000)
        self.assertEqual(paused["items"][0]["status"], "retrying")
        with self.db.connect() as conn:
            task = conn.execute(
                "SELECT status FROM conversation_tasks WHERE task_id = ?",
                (task_id,),
            ).fetchone()
            run = conn.execute(
                "SELECT status, failure_class FROM task_runs WHERE run_id = ?",
                (run_id,),
            ).fetchone()
            event = conn.execute(
                "SELECT event_type FROM conversation_events "
                "WHERE event_type = 'plan.budget_exhausted'"
            ).fetchone()
        self.assertEqual(task["status"], "needs_user")
        self.assertEqual(run["status"], "failed")
        self.assertEqual(run["failure_class"], "budget_exhausted")
        self.assertIsNotNone(event)

    def test_budget_continue_reserves_delta_and_enqueues_one_attempt(self) -> None:
        plan = self._plan()
        approved = self.plans.approve_plan(
            agent_id="agent-a",
            user_id=1,
            plan_id=plan["plan_id"],
            plan_version=plan["version"],
            plan_hash=plan["plan_hash"],
        )
        task_id = str(approved["items"][0]["child_task_id"])
        run_id = self._first_run(task_id)
        self.conversation.transition_run(run_id=run_id, status="running")
        self.conversation.record_usage(
            agent_id="agent-a",
            user_id=1,
            model="deepseek-v4-flash",
            input_tokens=80_000,
            output_tokens=48_000,
            total_tokens=128_000,
            source="worker",
            trace_id="trace-plan",
            task_id=task_id,
            run_id=run_id,
        )
        paused = self.plans.on_worker_budget_exhausted(
            task_id=task_id,
            run_id=run_id,
            message="Worker reached its token budget",
        )
        assert paused is not None
        request = paused["budget_request"]

        with self.assertRaises(InvalidTransitionError):
            self.plans.extend_budget(
                agent_id="agent-a",
                user_id=1,
                plan_id=plan["plan_id"],
                plan_version=paused["version"],
                plan_hash=paused["plan_hash"],
                budget_request_id="wrong-request",
            )
        extended = self.plans.extend_budget(
            agent_id="agent-a",
            user_id=1,
            plan_id=plan["plan_id"],
            plan_version=paused["version"],
            plan_hash=paused["plan_hash"],
            budget_request_id=str(request["request_id"]),
        )

        self.assertIsNone(extended["budget_request"])
        self.assertEqual(extended["version"], paused["version"] + 1)
        self.assertEqual(extended["hard_token_limit"], 256_000)
        self.assertEqual(extended["budget_extension_count"], 1)
        self.assertEqual(extended["budget_extended_tokens"], 64_000)
        self.assertEqual(extended["items"][0]["status"], "queued")
        self.assertEqual(extended["items"][0]["token_limit"], 192_000)
        with self.db.connect() as conn:
            plan_ledger = conn.execute(
                "SELECT reserved_tokens, limit_tokens FROM budget_ledgers "
                "WHERE scope_type = 'plan' AND scope_id = ?",
                (plan["plan_id"],),
            ).fetchone()
            runs = conn.execute(
                "SELECT run_id, attempt_no, status FROM task_runs "
                "WHERE task_id = ? ORDER BY attempt_no",
                (task_id,),
            ).fetchall()
            task = conn.execute(
                "SELECT status FROM conversation_tasks WHERE task_id = ?",
                (task_id,),
            ).fetchone()
        self.assertEqual(plan_ledger["reserved_tokens"], 256_000)
        self.assertEqual(plan_ledger["limit_tokens"], 256_000)
        self.assertEqual(
            [(row["attempt_no"], row["status"]) for row in runs],
            [(1, "failed"), (2, "queued")],
        )
        self.assertEqual(task["status"], "in_progress")

        second_run = str(runs[1]["run_id"])
        self.conversation.transition_run(run_id=second_run, status="running")
        self.conversation.transition_run(run_id=second_run, status="succeeded")
        self.conversation.transition_task(task_id=task_id, status="done")
        self.conversation.record_usage(
            agent_id="agent-a",
            user_id=1,
            model="deepseek-v4-flash",
            input_tokens=800,
            output_tokens=200,
            total_tokens=1_000,
            source="worker",
            trace_id="trace-plan",
            task_id=task_id,
            run_id=second_run,
        )
        progressed = self.plans.on_worker_terminal(task_id=task_id)
        self.assertIsNotNone(progressed)
        assert progressed is not None
        self.assertEqual(progressed["status"], "executing")
        summary_task = str(progressed["items"][1]["child_task_id"])
        summary_run = self._first_run(summary_task)
        self.conversation.transition_run(run_id=summary_run, status="running")
        self.conversation.transition_run(run_id=summary_run, status="succeeded")
        self.conversation.transition_task(task_id=summary_task, status="done")
        self.conversation.record_usage(
            agent_id="agent-a",
            user_id=1,
            model="deepseek-v4-flash",
            input_tokens=800,
            output_tokens=200,
            total_tokens=1_000,
            source="worker",
            trace_id="trace-plan",
            task_id=summary_task,
            run_id=summary_run,
        )
        completed = self.plans.on_worker_terminal(task_id=summary_task)
        self.assertIsNotNone(completed)
        assert completed is not None
        self.assertEqual(completed["status"], "completed")
        self.assertEqual(completed["actual_tokens"], 130_000)
        with self.db.connect() as conn:
            final_ledgers = conn.execute(
                "SELECT reserved_tokens, spent_tokens FROM budget_ledgers"
            ).fetchall()
        self.assertTrue(all(row["reserved_tokens"] == 0 for row in final_ledgers))
        self.assertTrue(all(row["spent_tokens"] == 130_000 for row in final_ledgers))

    def test_budget_request_can_be_abandoned_and_cannot_extend_twice(self) -> None:
        plan = self._plan()
        approved = self.plans.approve_plan(
            agent_id="agent-a",
            user_id=1,
            plan_id=plan["plan_id"],
            plan_version=plan["version"],
            plan_hash=plan["plan_hash"],
        )
        task_id = str(approved["items"][0]["child_task_id"])
        run_id = self._first_run(task_id)
        self.conversation.transition_run(run_id=run_id, status="running")
        self.conversation.record_usage(
            agent_id="agent-a",
            user_id=1,
            model="deepseek-v4-flash",
            input_tokens=70_000,
            output_tokens=58_000,
            total_tokens=128_000,
            source="worker",
            trace_id="trace-plan",
            task_id=task_id,
            run_id=run_id,
        )
        paused = self.plans.on_worker_budget_exhausted(
            task_id=task_id,
            run_id=run_id,
            message="Worker reached its token budget",
        )
        assert paused is not None
        cancelled = self.plans.cancel_plan(
            agent_id="agent-a",
            user_id=1,
            plan_id=plan["plan_id"],
        )
        self.assertEqual(cancelled["status"], "cancelled")
        self.assertIsNone(cancelled["budget_request"])
        with self.db.connect() as conn:
            ledgers = conn.execute(
                "SELECT reserved_tokens, spent_tokens FROM budget_ledgers"
            ).fetchall()
        self.assertTrue(ledgers)
        self.assertTrue(all(row["reserved_tokens"] == 0 for row in ledgers))
        self.assertTrue(all(row["spent_tokens"] == 128_000 for row in ledgers))

        second = self.plans.create_plan(
            conversation_id=str(self.main["conversation_id"]),
            trace_id="trace-plan-2",
            context_pack_id=None,
            objective="再次阅读天气",
            items=[
                {
                    "key": "one",
                    "title": "读取",
                    "objective": "阅读天气预报",
                }
            ],
        )
        second_approved = self.plans.approve_plan(
            agent_id="agent-a",
            user_id=1,
            plan_id=second["plan_id"],
            plan_version=second["version"],
            plan_hash=second["plan_hash"],
        )
        second_task = str(second_approved["items"][0]["child_task_id"])
        second_run = self._first_run(second_task)
        self.conversation.transition_run(run_id=second_run, status="running")
        first_pause = self.plans.on_worker_budget_exhausted(
            task_id=second_task,
            run_id=second_run,
            message="Worker reached its token budget",
        )
        assert first_pause is not None
        request = first_pause["budget_request"]
        self.plans.extend_budget(
            agent_id="agent-a",
            user_id=1,
            plan_id=second["plan_id"],
            plan_version=first_pause["version"],
            plan_hash=first_pause["plan_hash"],
            budget_request_id=str(request["request_id"]),
        )
        queued_runs = [
            row
            for row in self._runs(second_task)
            if row["status"] == "queued"
        ]
        self.assertEqual(len(queued_runs), 1)
        second_pause = self.plans.on_worker_budget_exhausted(
            task_id=second_task,
            run_id=str(queued_runs[0]["run_id"]),
            message="Worker reached its token budget again",
        )
        self.assertIsNone(second_pause)

    def test_auto_plan_requires_complex_read_only_work_and_builds_dependency(self) -> None:
        decision = {
            "intent": "read_only_task",
            "confidence": 0.74,
            "suggested_task": {
                "title": "比较两个城市天气",
                "objective": "比较北京和上海的天气并汇总",
            },
        }
        self.assertTrue(
            should_propose_auto_plan(
                text="比较北京和上海的天气并汇总",
                decision=decision,
            )
        )
        self.assertFalse(
            should_propose_auto_plan(
                text="现在几点",
                decision={"intent": "direct_reply", "confidence": 0.58},
            )
        )
        items = auto_plan_items(
            objective="比较北京和上海的天气并汇总",
            title=None,
            suggested_task=decision["suggested_task"],
            default_token_limit=128_000,
        )
        self.assertEqual([item["key"] for item in items], ["research", "synthesis"])
        self.assertEqual(items[1]["depends_on"], ["research"])
        self.assertEqual(
            sum(int(item["token_limit"]) for item in items),
            128_000,
        )

    def _first_run(self, task_id: str) -> str:
        with self.db.connect() as conn:
            row = conn.execute(
                "SELECT run_id FROM task_runs WHERE task_id = ? "
                "AND status = 'queued' ORDER BY attempt_no DESC LIMIT 1",
                (task_id,),
            ).fetchone()
        if row is None:
            raise AssertionError(f"queued run not found for {task_id}")
        return str(row["run_id"])

    def _runs(self, task_id: str) -> list[dict]:
        with self.db.connect() as conn:
            return [
                dict(row)
                for row in conn.execute(
                    "SELECT * FROM task_runs WHERE task_id = ? ORDER BY attempt_no",
                    (task_id,),
                ).fetchall()
            ]


if __name__ == "__main__":
    unittest.main()
