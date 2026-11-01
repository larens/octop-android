#!/usr/bin/env python3
"""Tests for Router Phase 0 persistence, state machines, tracing, and budgets."""

from __future__ import annotations

import asyncio
import json
import os
import sqlite3
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from install_router_phase0 import (
    patch_chat_turn_source,
    patch_cron_delivery_source,
    patch_cron_job_source,
    patch_cron_manager_source,
    patch_proactive_scheduler_source,
    patch_proactive_service_source,
    patch_server_source,
    patch_source,
    patch_usage_record_source,
    patch_usage_repo_source,
)
from patch_paths import octop_site
from router_phase0_budget import RouterBudgetGate
from router_phase0_context import compile_context_pack, estimate_text_tokens
from router_phase0_retention import run_retention
from router_phase0_service import (
    BudgetExceededError,
    ConversationService,
    InvalidTransitionError,
    NotFoundError,
    RetryLimitExceededError,
    RouterPhase0Error,
)
from router_phase0_shadow import (
    _memory_candidates_from_payload,
    classify_shadow_route,
    observe_shadow_turn,
)


ROOT = Path(__file__).resolve().parent
MIGRATION = ROOT / "migrations/031_router_phase0.sql"
ROUTER_ENV_KEYS = (
    "OCTOP_ROUTER_BUDGET_MODE",
    "OCTOP_ROUTER_BUDGET_ENFORCED_SURFACES",
    "OCTOP_ROUTER_BUDGET_USER_DAILY_TOKENS",
    "OCTOP_ROUTER_BUDGET_AGENT_DAILY_TOKENS",
    "OCTOP_ROUTER_BUDGET_GLOBAL_DAILY_TOKENS",
    "OCTOP_ROUTER_BUDGET_COST_PER_1K_TOKENS_MICROS",
)


class SqliteDB:
    dialect = "sqlite"

    def __init__(self) -> None:
        self.conn = sqlite3.connect(":memory:", isolation_level=None)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA foreign_keys = ON")

    @contextmanager
    def connect(self):
        yield self.conn

    @contextmanager
    def transaction(self):
        self.conn.execute("BEGIN IMMEDIATE")
        try:
            yield self.conn
            self.conn.execute("COMMIT")
        except Exception:
            self.conn.execute("ROLLBACK")
            raise

    def close(self) -> None:
        self.conn.close()


class MemorySettings:
    def __init__(self, values: dict[str, str]) -> None:
        self.values = dict(values)

    def get(self, key: str):
        return self.values.get(key)


class FakeThreadMessages:
    def __init__(self, rows=None) -> None:
        self.rows = list(rows or [])

    def page(self, thread_id: str, limit: int = 100):
        return self.rows[:limit], False


@contextmanager
def clean_router_env():
    saved = {key: os.environ.pop(key, None) for key in ROUTER_ENV_KEYS}
    try:
        yield
    finally:
        for key, value in saved.items():
            if value is not None:
                os.environ[key] = value


def available_router_source_root() -> Path | None:
    configured = os.environ.get("OCTOP_ROUTER_SOURCE")
    candidates = [Path(configured)] if configured else []
    candidates.append(octop_site())
    candidates.extend(sorted(Path("/tmp").glob("octop-router-*/octop"), reverse=True))
    for candidate in candidates:
        if (candidate / "api/app.py").is_file():
            return candidate
    return None


class RouterPhase0Test(unittest.TestCase):
    def test_router_retention_is_dry_run_bounded_and_auditable(self) -> None:
        now = 2_000_000_000
        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "retention.db"
            conn = sqlite3.connect(db_path)
            conn.executescript(
                """
                PRAGMA foreign_keys = ON;
                CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE conversation_tasks (
                    task_id TEXT PRIMARY KEY,
                    status TEXT NOT NULL
                );
                CREATE TABLE task_runs (
                    run_id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL REFERENCES conversation_tasks(task_id),
                    status TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                );
                CREATE TABLE conversation_events (
                    event_id TEXT PRIMARY KEY,
                    payload_ref TEXT,
                    payload_json TEXT,
                    created_at INTEGER NOT NULL
                );
                CREATE TABLE context_pack_manifests (
                    pack_id TEXT PRIMARY KEY,
                    created_at INTEGER NOT NULL
                );
                CREATE TABLE budget_ledgers (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    period_end INTEGER NOT NULL
                );
                CREATE TABLE audit_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL,
                    actor TEXT,
                    action TEXT NOT NULL,
                    target TEXT,
                    payload TEXT
                );
                """
            )
            conn.execute("INSERT INTO conversation_tasks VALUES ('task_done', 'done')")
            conn.execute(
                "INSERT INTO conversation_tasks VALUES ('task_active', 'in_progress')"
            )
            conn.execute(
                "INSERT INTO task_runs VALUES ('run_done', 'task_done', 'succeeded', ?)",
                (now - 181 * 86400,),
            )
            conn.execute(
                "INSERT INTO task_runs VALUES ('run_active', 'task_active', 'succeeded', ?)",
                (now - 181 * 86400,),
            )
            conn.execute(
                "INSERT INTO context_pack_manifests VALUES ('pack_old', ?)",
                (now - 91 * 86400,),
            )
            conn.execute(
                "INSERT INTO context_pack_manifests VALUES ('pack_new', ?)",
                (now - 89 * 86400,),
            )
            conn.execute(
                "INSERT INTO conversation_events VALUES "
                "('event_old', 'context-pack://pack_old', '{\"private\":true}', ?)",
                (now - 91 * 86400,),
            )
            conn.execute(
                "INSERT INTO conversation_events VALUES "
                "('event_middle', 'context-pack://pack_new', '{\"private\":true}', ?)",
                (now - 31 * 86400,),
            )
            conn.execute(
                "INSERT INTO conversation_events VALUES "
                "('event_new', NULL, '{\"private\":true}', ?)",
                (now - 29 * 86400,),
            )
            conn.execute(
                "INSERT INTO budget_ledgers(period_end) VALUES (?)",
                (now - 401 * 86400,),
            )
            conn.commit()

            dry_run = run_retention(db_path, now=now)
            self.assertFalse(dry_run["apply"])
            self.assertEqual(dry_run["counts"]["conversation_events_deleted"], 1)
            self.assertEqual(dry_run["counts"]["terminal_task_runs_deleted"], 1)
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM conversation_events").fetchone()[0],
                3,
            )

            applied = run_retention(db_path, now=now, apply=True)
            self.assertTrue(applied["audit_recorded"])
            self.assertEqual(applied["counts"]["event_payloads_redacted"], 2)
            self.assertEqual(applied["counts"]["context_pack_refs_cleared"], 1)
            self.assertEqual(applied["counts"]["conversation_events_deleted"], 1)
            self.assertEqual(
                conn.execute(
                    "SELECT payload_json FROM conversation_events "
                    "WHERE event_id = 'event_middle'"
                ).fetchone()[0],
                None,
            )
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM task_runs").fetchone()[0],
                1,
            )
            audit = conn.execute(
                "SELECT action, payload FROM audit_log ORDER BY id DESC LIMIT 1"
            ).fetchone()
            self.assertEqual(audit[0], "router.retention.applied")
            self.assertEqual(json.loads(audit[1])["policy_days"]["event_days"], 90)
            conn.close()

    def setUp(self) -> None:
        self.db = SqliteDB()
        self.db.conn.executescript(
            """
            CREATE TABLE users(id INTEGER PRIMARY KEY);
            CREATE TABLE agents(agent_id TEXT PRIMARY KEY);
            CREATE TABLE threads(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              thread_id TEXT NOT NULL UNIQUE,
              agent_id TEXT NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
              user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
              channel_type TEXT NOT NULL,
              session_key TEXT NOT NULL,
              last_active INTEGER NOT NULL,
              created_at INTEGER NOT NULL
            );
            CREATE TABLE usage_log(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              ts INTEGER NOT NULL,
              agent_id TEXT NOT NULL REFERENCES agents(agent_id) ON DELETE CASCADE,
              user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
              thread_id TEXT NOT NULL DEFAULT '',
              model TEXT NOT NULL DEFAULT '',
              input_tokens INTEGER NOT NULL DEFAULT 0,
              output_tokens INTEGER NOT NULL DEFAULT 0,
              total_tokens INTEGER NOT NULL DEFAULT 0,
              source TEXT NOT NULL DEFAULT 'chat'
            );
            CREATE TABLE _schema_version(version INTEGER NOT NULL);
            INSERT INTO _schema_version(version) VALUES (30);
            INSERT INTO users(id) VALUES (1), (2);
            INSERT INTO agents(agent_id) VALUES ('agent-a'), ('agent-b');
            INSERT INTO threads(
              thread_id, agent_id, user_id, channel_type, session_key, last_active, created_at
            ) VALUES
              ('old-dashboard', 'agent-a', 1, 'dashboard', 'agent-a:dashboard:1:dm', 100, 90),
              ('new-dashboard', 'agent-a', 1, 'dashboard', 'agent-a:dashboard:1:dm', 200, 150),
              ('cli-thread', 'agent-a', 1, 'cli', 'agent-a:cli:1:dm', 300, 250),
              ('other-dashboard', 'agent-b', 2, 'dashboard', 'agent-b:dashboard:2:dm', 120, 110);
            """
        )
        self.db.conn.executescript(MIGRATION.read_text(encoding="utf-8"))
        self.service = ConversationService(self.db)

    def tearDown(self) -> None:
        self.db.close()

    def conversation(self, agent_id: str = "agent-a", user_id: int = 1):
        return self.service.ensure_main_conversation(
            agent_id=agent_id,
            user_id=user_id,
            router_thread_id="new-dashboard",
        )

    def test_migration_backfills_latest_dashboard_thread_and_usage_columns(self) -> None:
        rows = self.db.conn.execute(
            "SELECT agent_id, user_id, router_thread_id FROM main_conversations "
            "ORDER BY agent_id"
        ).fetchall()
        self.assertEqual(
            [tuple(row) for row in rows],
            [
                ("agent-a", 1, "new-dashboard"),
                ("agent-b", 2, "other-dashboard"),
            ],
        )
        self.assertEqual(
            self.db.conn.execute("SELECT version FROM _schema_version").fetchone()[0], 31
        )
        columns = {
            row["name"] for row in self.db.conn.execute("PRAGMA table_info(usage_log)")
        }
        self.assertTrue({"trace_id", "task_id", "run_id"} <= columns)

    def test_main_conversation_is_unique_and_rebinds_without_replacing_id(self) -> None:
        first = self.conversation()
        second = self.service.ensure_main_conversation(
            agent_id="agent-a",
            user_id=1,
            router_thread_id="old-dashboard",
        )
        self.assertEqual(first["conversation_id"], second["conversation_id"])
        self.assertEqual(second["router_thread_id"], "old-dashboard")
        count = self.db.conn.execute(
            "SELECT COUNT(*) FROM main_conversations WHERE agent_id = 'agent-a' AND user_id = 1"
        ).fetchone()[0]
        self.assertEqual(count, 1)

        created = self.service.ensure_main_conversation(
            agent_id="agent-b",
            user_id=1,
            router_thread_id=None,
        )
        self.assertTrue(created["conversation_id"].startswith("conv_"))
        self.assertIsNone(created["router_thread_id"])

    def test_task_and_run_state_machines_are_bounded(self) -> None:
        conversation = self.conversation()
        task = self.service.create_task(
            conversation_id=conversation["conversation_id"],
            task_type="calendar_conflict",
            title="Check calendar conflicts",
            subject_key="week-44-calendar",
            objective="Find conflicts and propose two alternatives",
        )
        run = self.service.create_run(task_id=task["task_id"], max_attempts=3)
        self.assertEqual(run["attempt_no"], 1)
        task_after = self.service.get_task(
            agent_id="agent-a", user_id=1, task_id=task["task_id"]
        )
        self.assertEqual(task_after["status"], "in_progress")

        self.service.transition_run(run_id=run["run_id"], status="running")
        finished = self.service.transition_run(run_id=run["run_id"], status="succeeded")
        self.assertEqual(finished["status"], "succeeded")

        for _ in range(2):
            self.service.create_run(task_id=task["task_id"], max_attempts=3)
        with self.assertRaises(RetryLimitExceededError):
            self.service.create_run(task_id=task["task_id"], max_attempts=3)
        with self.assertRaises(RouterPhase0Error):
            self.service.create_run(task_id=task["task_id"], max_attempts=99)

        done = self.service.transition_task(
            task_id=task["task_id"],
            status="done",
            capsule={
                "key_findings": ["Wednesday 10:00 conflicts with the client call"],
                "next_step": "Wait for the user to choose",
            },
        )
        self.assertEqual(done["capsule"]["next_step"], "Wait for the user to choose")
        with self.assertRaises(InvalidTransitionError):
            self.service.transition_task(task_id=task["task_id"], status="in_progress")

    def test_new_run_resumes_a_needs_user_task(self) -> None:
        conversation = self.conversation()
        task = self.service.create_task(
            conversation_id=conversation["conversation_id"],
            task_type="calendar_conflict",
            title="Check calendar conflicts",
            subject_key="week-44-calendar",
            objective="Resume after the user chooses a slot",
            requires_user=True,
        )
        self.service.create_run(task_id=task["task_id"])
        self.service.transition_task(task_id=task["task_id"], status="needs_user")
        resumed = self.service.create_run(task_id=task["task_id"])
        self.assertEqual(resumed["attempt_no"], 2)
        task_after = self.service.get_task(
            agent_id="agent-a", user_id=1, task_id=task["task_id"]
        )
        self.assertEqual(task_after["status"], "in_progress")

    def test_events_are_ordered_idempotent_and_scope_checked(self) -> None:
        conversation = self.conversation()
        first = self.service.append_event(
            conversation_id=conversation["conversation_id"],
            trace_id="trace-1",
            event_type="router.received",
            status="received",
            summary="Received user message",
            event_id="evt-fixed",
            usage={"input_tokens": 20},
        )
        duplicate = self.service.append_event(
            conversation_id=conversation["conversation_id"],
            trace_id="trace-1",
            event_type="router.received",
            event_id="evt-fixed",
        )
        second = self.service.append_event(
            conversation_id=conversation["conversation_id"],
            trace_id="trace-1",
            event_type="task.updated",
            status="running",
            summary="Checking the calendar",
        )
        self.assertEqual(first["seq"], 1)
        self.assertEqual(duplicate["seq"], 1)
        self.assertEqual(second["seq"], 2)
        self.assertEqual(len(self.service.list_events(
            agent_id="agent-a",
            user_id=1,
            conversation_id=conversation["conversation_id"],
        )), 2)
        trace = self.service.get_trace(
            agent_id="agent-a", user_id=1, trace_id="trace-1"
        )
        self.assertEqual([event["seq"] for event in trace["events"]], [1, 2])
        with self.assertRaises(NotFoundError):
            self.service.get_trace(
                agent_id="agent-a", user_id=2, trace_id="trace-1"
            )

    def test_event_references_cannot_cross_conversations(self) -> None:
        own = self.conversation()
        other = self.service.ensure_main_conversation(
            agent_id="agent-b",
            user_id=2,
        )
        own_task = self.service.create_task(
            conversation_id=own["conversation_id"],
            task_type="lookup",
            title="Own task",
            subject_key="own",
            objective="Read only",
        )
        other_task = self.service.create_task(
            conversation_id=other["conversation_id"],
            task_type="lookup",
            title="Other task",
            subject_key="other",
            objective="Read only",
        )
        other_run = self.service.create_run(task_id=other_task["task_id"])
        other_event = self.service.append_event(
            conversation_id=other["conversation_id"],
            trace_id="trace-other",
            event_type="task.updated",
            task_id=other_task["task_id"],
        )

        with self.assertRaises(RouterPhase0Error):
            self.service.append_event(
                conversation_id=own["conversation_id"],
                trace_id="trace-cross-task",
                event_type="task.updated",
                task_id=other_task["task_id"],
            )
        with self.assertRaises(RouterPhase0Error):
            self.service.append_event(
                conversation_id=own["conversation_id"],
                trace_id="trace-cross-run",
                event_type="task.updated",
                run_id=other_run["run_id"],
            )
        with self.assertRaises(RouterPhase0Error):
            self.service.append_event(
                conversation_id=own["conversation_id"],
                trace_id="trace-mixed",
                event_type="task.updated",
                task_id=own_task["task_id"],
                run_id=other_run["run_id"],
            )
        with self.assertRaises(RouterPhase0Error):
            self.service.append_event(
                conversation_id=own["conversation_id"],
                trace_id="trace-parent",
                event_type="task.updated",
                parent_event_id=other_event["event_id"],
            )

    def test_budget_reservation_and_settlement_are_hard_limits(self) -> None:
        period = {"period_start": 100, "period_end": 200}
        ledger = self.service.reserve_budget(
            scope_type="task",
            scope_id="task-1",
            tokens=80,
            cost_micros=80_000,
            limit_tokens=100,
            limit_cost_micros=100_000,
            **period,
        )
        self.assertEqual(ledger["reserved_tokens"], 80)
        with self.assertRaises(BudgetExceededError):
            self.service.reserve_budget(
                scope_type="task",
                scope_id="task-1",
                tokens=30,
                cost_micros=30_000,
                limit_tokens=100,
                limit_cost_micros=100_000,
                **period,
            )
        self.service.release_budget(
            scope_type="task",
            scope_id="task-1",
            tokens=20,
            cost_micros=20_000,
            **period,
        )
        self.service.reserve_budget(
            scope_type="task",
            scope_id="task-1",
            tokens=30,
            cost_micros=30_000,
            limit_tokens=100,
            limit_cost_micros=100_000,
            **period,
        )
        settled = self.service.settle_budget(
            scope_type="task",
            scope_id="task-1",
            reserved_tokens=90,
            reserved_cost_micros=90_000,
            spent_tokens=75,
            spent_cost_micros=75_000,
            **period,
        )
        self.assertEqual(settled["reserved_tokens"], 0)
        self.assertEqual(settled["spent_tokens"], 75)

        self.service.reserve_budget(
            scope_type="task",
            scope_id="task-2",
            tokens=10,
            cost_micros=10_000,
            limit_tokens=100,
            limit_cost_micros=100_000,
            **period,
        )
        with self.assertRaises(RouterPhase0Error):
            self.service.settle_budget(
                scope_type="task",
                scope_id="task-2",
                reserved_tokens=10,
                reserved_cost_micros=10_000,
                spent_tokens=11,
                spent_cost_micros=10_000,
                **period,
            )

    def test_multi_scope_budget_reservation_rolls_back_atomically(self) -> None:
        period = {"period_start": 100, "period_end": 200}
        with self.assertRaises(BudgetExceededError):
            self.service.reserve_budgets(
                [
                    {
                        "scope_type": "task",
                        "scope_id": "task-ok",
                        "tokens": 10,
                        "cost_micros": 10_000,
                        "limit_tokens": 100,
                        "limit_cost_micros": 100_000,
                        **period,
                    },
                    {
                        "scope_type": "user",
                        "scope_id": "user-1",
                        "tokens": 120,
                        "cost_micros": 120_000,
                        "limit_tokens": 100,
                        "limit_cost_micros": 100_000,
                        **period,
                    },
                ]
            )
        rows = self.db.conn.execute(
            "SELECT scope_type, reserved_tokens FROM budget_ledgers "
            "WHERE period_start = ? AND period_end = ?",
            (period["period_start"], period["period_end"]),
        ).fetchall()
        self.assertEqual([tuple(row) for row in rows], [])

    def test_shadow_budget_reports_block_without_consuming_or_stopping_work(self) -> None:
        settings = MemorySettings(
            {
                "router.budget.mode": "shadow",
                    "router.budget.user_daily_tokens": "5",
                    "router.budget.run_tokens.cron": "10",
            }
        )
        with clean_router_env():
            gate = RouterBudgetGate(self.db, settings, timezone_name="Asia/Shanghai")
            lease = gate.start(
                surface="cron",
                agent_id="agent-a",
                user_id=1,
                source_id="cron-shadow",
            )
            self.assertFalse(lease.enforced)
            self.assertTrue(lease.would_block)
            self.assertEqual(lease.reservations, [])
            self.assertEqual(
                self.db.conn.execute("SELECT COUNT(*) FROM budget_ledgers").fetchone()[0],
                0,
            )
            lease.finish(actual_tokens=25)

        events = self.service.list_events(
            agent_id="agent-a",
            user_id=1,
            conversation_id=lease.conversation_id,
            include_payload=True,
        )
        self.assertEqual(
            [event["event_type"] for event in events],
            ["budget.shadow_checked", "budget.shadow_settled"],
        )
        self.assertEqual(events[0]["status"], "would_block")
        self.assertEqual(events[1]["usage"], {"total_tokens": 25})
        self.assertEqual(events[1]["payload"]["actual_tokens"], 25)
        self.assertEqual(events[1]["payload"]["estimated_tokens"], 10)
        self.assertEqual(events[1]["payload"]["enforced_tokens"], 10)
        self.assertEqual(events[1]["payload"]["overrun_tokens"], 15)

    def test_enforce_mode_blocks_before_work_when_any_scope_is_exhausted(self) -> None:
        settings = MemorySettings(
            {
                "router.budget.mode": "enforce",
                "router.budget.user_daily_tokens": "10",
            }
        )
        with clean_router_env():
            gate = RouterBudgetGate(self.db, settings, timezone_name="Asia/Shanghai")
            with self.assertRaises(BudgetExceededError):
                gate.start(
                    surface="cron",
                    agent_id="agent-a",
                    user_id=1,
                    source_id="cron-blocked",
                )
        self.assertEqual(
            self.db.conn.execute("SELECT COUNT(*) FROM budget_ledgers").fetchone()[0],
            0,
        )
        blocked = self.db.conn.execute(
            "SELECT status FROM conversation_events WHERE event_type = 'budget.blocked'"
        ).fetchall()
        self.assertEqual([row[0] for row in blocked], ["blocked"])

    def test_enforced_lease_stops_after_usage_crosses_the_run_reservation(self) -> None:
        settings = MemorySettings(
            {
                "router.budget.mode": "enforce",
                "router.budget.run_tokens.cron": "100",
                "router.budget.daily_tokens.cron": "1000",
                "router.budget.user_daily_tokens": "1000",
                "router.budget.agent_daily_tokens": "1000",
                "router.budget.global_daily_tokens": "1000",
                "router.budget.cost_per_1k_tokens_micros": "1000",
            }
        )
        with clean_router_env():
            gate = RouterBudgetGate(self.db, settings, timezone_name="Asia/Shanghai")
            lease = gate.start(
                surface="cron",
                agent_id="agent-a",
                user_id=1,
                source_id="cron-overflow",
            )
            with self.assertRaises(BudgetExceededError):
                lease.observe({"total_tokens": 101})
            lease.finish(failed=True)

        run_ledger = self.db.conn.execute(
            "SELECT reserved_tokens, spent_tokens FROM budget_ledgers "
            "WHERE scope_type = 'run' AND scope_id = ?",
            (lease.trace_id,),
        ).fetchone()
        self.assertEqual(tuple(run_ledger), (0, 100))

    def test_context_pack_and_usage_keep_trace_identifiers(self) -> None:
        conversation = self.conversation()
        pack = self.service.record_context_pack(
            conversation_id=conversation["conversation_id"],
            trace_id="trace-pack",
            policy_version="router-v1",
            model="test-model",
            used_tokens=1234,
            sections=[
                {"kind": "recent_turns", "tokens": 600},
                {"kind": "task_capsules", "tokens": 400},
            ],
            included_refs=["memory://recent", "knowledge://kb-1"],
            omitted_refs=["thread://old-worker"],
        )
        self.assertEqual(pack["sections"][0]["kind"], "recent_turns")
        trace = self.service.get_trace(
            agent_id="agent-a", user_id=1, trace_id="trace-pack"
        )
        self.assertEqual(trace["context_packs"][0]["pack_id"], pack["pack_id"])
        self.service.record_usage(
            agent_id="agent-a",
            user_id=1,
            thread_id="new-dashboard",
            model="test-model",
            input_tokens=100,
            output_tokens=40,
            total_tokens=140,
            trace_id="trace-pack",
            task_id="task-1",
            run_id="run-1",
        )
        row = self.db.conn.execute(
            "SELECT trace_id, task_id, run_id FROM usage_log ORDER BY id DESC LIMIT 1"
        ).fetchone()
        self.assertEqual(tuple(row), ("trace-pack", "task-1", "run-1"))

    def test_context_pack_is_bounded_and_explains_omissions(self) -> None:
        pack = compile_context_pack(
            task_capsules=[
                {
                    "ref": "task://task-1",
                    "summary": "Waiting for the user to choose a slot",
                    "tokens": 3,
                    "priority": 2,
                    "updated_at": 20,
                }
            ],
            recent_discussion=[
                {"ref": "turn://1", "content": "Older turn", "tokens": 4},
                {"ref": "turn://2", "content": "Latest turn", "tokens": 4},
            ],
            short_term_memory=[
                {"ref": "memory://useful", "summary": "Prefers afternoons", "tokens": 2, "score": 0.8},
                {"ref": "memory://weak", "summary": "Unrelated", "tokens": 2, "score": 0.1},
            ],
            knowledge_base=[
                {"ref": "kb://doc", "excerpt": "Process notes", "tokens": 4, "score": 0.9}
            ],
            policy={
                "max_total_tokens": 8,
                "recent_discussion": {"max_items": 2, "max_tokens": 8},
            },
        )
        self.assertEqual(pack["used_tokens"], 7)
        self.assertEqual(
            [section["kind"] for section in pack["sections"]],
            ["task_capsules", "recent_discussion"],
        )
        self.assertEqual(
            pack["included_refs"], ["task://task-1", "turn://2"]
        )
        reasons = {(item["ref"], item["reason"]) for item in pack["omitted_refs"]}
        self.assertIn(("turn://1", "token_budget"), reasons)
        self.assertIn(("memory://weak", "score_below_threshold"), reasons)
        self.assertIn(("memory://useful", "token_budget"), reasons)
        self.assertIn(("kb://doc", "token_budget"), reasons)

    def test_context_pack_caps_manifest_omissions_and_estimates_missing_tokens(self) -> None:
        oversized = compile_context_pack(
            recent_discussion=[
                {
                    "ref": "turn://oversized",
                    "content": "x" * 80,
                    "tokens": 0,
                }
            ],
            policy={"max_total_tokens": 10, "recent_discussion": {"max_items": 1}},
        )
        self.assertEqual(oversized["used_tokens"], 0)
        self.assertEqual(oversized["included_refs"], [])
        self.assertEqual(oversized["omitted_by_reason"], {"token_budget": 1})

        many = compile_context_pack(
            knowledge_base=[
                {
                    "ref": f"kb://{index}",
                    "excerpt": "unrelated",
                    "tokens": 1,
                    "score": 0,
                }
                for index in range(250)
            ],
        )
        self.assertEqual(many["omitted_count"], 250)
        self.assertEqual(len(many["omitted_refs"]), 200)
        self.assertTrue(many["omitted_refs_truncated"])
        self.assertEqual(many["omitted_by_reason"], {"score_below_threshold": 250})

    def test_token_estimator_does_not_underprice_chinese_or_emoji(self) -> None:
        self.assertEqual(estimate_text_tokens(""), 0)
        self.assertEqual(estimate_text_tokens("abcd"), 1)
        self.assertEqual(estimate_text_tokens("你好世界"), 4)
        self.assertEqual(estimate_text_tokens("🙂"), 1)
        self.assertGreater(estimate_text_tokens("检查明天的日程冲突"), 2)

    def test_shadow_router_classifies_without_dispatch(self) -> None:
        self.assertEqual(classify_shadow_route("你好")["intent"], "direct_reply")
        self.assertEqual(
            classify_shadow_route("请只回复 OK，不要调用任何工具。")["intent"],
            "direct_reply",
        )
        self.assertEqual(
            classify_shadow_route("帮我看一下明天的日历")["intent"],
            "read_only_task",
        )
        self.assertEqual(
            classify_shadow_route("把总结发送给张三")["intent"],
            "side_effect_task",
        )
        self.assertEqual(
            classify_shadow_route("读取文件后回复一句话")["intent"],
            "read_only_task",
        )
        self.assertEqual(
            classify_shadow_route(
                "只做订单预览，搜索拿铁，绝对不要调用 createOrder。"
            )["intent"],
            "read_only_task",
        )
        self.assertEqual(
            classify_shadow_route("汇总需要我回复的邮件")["intent"],
            "read_only_task",
        )
        self.assertEqual(
            classify_shadow_route("请发送邮件给张三")["intent"],
            "side_effect_task",
        )
        self.assertEqual(
            classify_shadow_route("删除这个任务")["intent"],
            "side_effect_task",
        )
        self.assertEqual(
            classify_shadow_route(
                "只做订单预览，如果现在打烊，说明最早可取时间；不要提交订单。"
            )["intent"],
            "read_only_task",
        )
        self.assertEqual(
            classify_shadow_route(
                "取暖费1200，每年12月前在微信~支付~城市服务里缴纳。"
            )["intent"],
            "direct_reply",
        )
        self.assertEqual(
            classify_shadow_route("帮忙看下失败的任务重新跑一下")["intent"],
            "side_effect_task",
        )
        self.assertEqual(
            classify_shadow_route("把这次讨论落入记忆里，敏感数据另存")["intent"],
            "side_effect_task",
        )
        self.assertEqual(
            classify_shadow_route("没有看到文件，给我一个可以直接下载的")["intent"],
            "read_only_task",
        )
        self.assertEqual(
            classify_shadow_route("怎么把修好的文件下载到手机里转发")["intent"],
            "direct_reply",
        )
        self.assertEqual(
            classify_shadow_route("知识库里怎么没有找到这些文档")["intent"],
            "read_only_task",
        )
        mixed = classify_shadow_route("查一下后直接发送")
        self.assertEqual(mixed["intent"], "side_effect_task")
        self.assertFalse(mixed["dispatch_enabled"])
        self.assertTrue(mixed["suggested_task"]["requires_user"])

    def test_memory_retrieval_payload_is_bounded_scored_and_deduplicated(self) -> None:
        candidates = _memory_candidates_from_payload(
            {
                "items": [
                    {
                        "id": "atom-1",
                        "assertion": "用户偏好下午开会",
                        "importance": "high",
                    },
                    {
                        "id": "atom-1",
                        "assertion": "重复记录",
                        "importance": "high",
                    },
                    {
                        "assertion": "定时任务触发规则不应进入主会话上下文",
                        "importance": "high",
                    },
                    {
                        "id": "atom-2",
                        "assertion": "用户正在改造 Router",
                        "importance": "medium",
                        "confidence": "high",
                    },
                ]
            },
            limit=2,
        )
        self.assertEqual(len(candidates), 2)
        self.assertEqual(candidates[0]["ref"], "memory://atom/atom-1")
        self.assertEqual(candidates[0]["score"], 0.9)
        self.assertNotIn("定时任务", candidates[1]["summary"])
        self.assertEqual(candidates[1]["ref"], "memory://atom/atom-2")

    def test_shadow_observation_writes_bounded_context_manifest_and_trace(self) -> None:
        conversation = self.conversation()
        task = self.service.create_task(
            conversation_id=conversation["conversation_id"],
            task_type="calendar_conflict",
            title="Check tomorrow",
            subject_key="tomorrow-calendar",
            objective="Find tomorrow's conflicts",
            requires_user=True,
        )
        self.service.transition_task(
            task_id=task["task_id"],
            status="needs_user",
            capsule={"next_step": "Ask the user which meeting to move"},
        )
        messages = FakeThreadMessages(
            [
                SimpleNamespace(
                    seq=1,
                    message_json=json.dumps({"content": "上一轮讨论"}),
                    created_at=90,
                )
            ]
        )
        server = SimpleNamespace(
            services=SimpleNamespace(
                db=self.db,
                repos=SimpleNamespace(thread_message_repo=messages),
            ),
            app_runtime=SimpleNamespace(),
        )
        result = asyncio.run(
            observe_shadow_turn(
                server,
                agent_id="agent-a",
                user_id=1,
                thread_id="new-dashboard",
                session_key="agent-a:dashboard:1:dm",
                text="帮我查一下明天的日历",
                has_attachments=True,
                target_agent_ids=("agent-a",),
                memory_candidates=[
                    {
                        "ref": "memory://useful",
                        "summary": "Prefers afternoon meetings",
                        "tokens": 3,
                        "score": 0.8,
                    },
                    {
                        "ref": "memory://weak",
                        "summary": "Unrelated preference",
                        "tokens": 3,
                        "score": 0.1,
                    },
                ],
                knowledge_candidates=[
                    {
                        "ref": "kb://calendar-policy",
                        "excerpt": "Calendar conflict policy",
                        "tokens": 4,
                        "score": 0.9,
                    }
                ],
            )
        )

        self.assertEqual(result["decision"]["intent"], "read_only_task")
        self.assertFalse(result["decision"]["dispatch_enabled"])
        self.assertLessEqual(result["context_pack"]["used_tokens"], 6_000)
        self.assertIn(f"task://{task['task_id']}", result["context_pack"]["included_refs"])
        self.assertNotIn("memory://weak", result["context_pack"]["included_refs"])
        events = self.service.list_events(
            agent_id="agent-a",
            user_id=1,
            conversation_id=conversation["conversation_id"],
            include_payload=True,
        )
        self.assertEqual(
            [event["event_type"] for event in events],
            ["router.received", "context.pack.compiled", "router.shadow.decision"],
        )
        trace = self.service.get_trace(
            agent_id="agent-a",
            user_id=1,
            trace_id=result["trace_id"],
        )
        self.assertEqual(len(trace["context_packs"]), 1)
        self.assertEqual(
            self.db.conn.execute("SELECT COUNT(*) FROM task_runs").fetchone()[0],
            0,
        )

    def test_shadow_observation_uses_bounded_auto_retrieval_when_not_supplied(self) -> None:
        conversation = self.conversation()
        server = SimpleNamespace(
            services=SimpleNamespace(
                db=self.db,
                repos=SimpleNamespace(thread_message_repo=FakeThreadMessages()),
            ),
            app_runtime=SimpleNamespace(),
        )
        with patch(
            "router_phase0_shadow._auto_retrieval_candidates",
            new=AsyncMock(
                return_value=(
                    [
                        {
                            "ref": "memory://atom/auto",
                            "summary": "The user prefers concise answers",
                            "tokens": 5,
                            "score": 0.8,
                        }
                    ],
                    [
                        {
                            "ref": "knowledge://retrieval/auto",
                            "excerpt": "Project constraints",
                            "tokens": 3,
                            "score": 0.9,
                        }
                    ],
                )
            ),
        ):
            result = asyncio.run(
                observe_shadow_turn(
                    server,
                    agent_id="agent-a",
                    user_id=1,
                    thread_id="new-dashboard",
                    session_key="agent-a:dashboard:1:dm",
                    text="继续按这个方案推进",
                )
            )
        self.assertIn("memory://atom/auto", result["context_pack"]["included_refs"])
        self.assertIn("knowledge://retrieval/auto", result["context_pack"]["included_refs"])
        self.assertEqual(
            self.service.list_events(
                agent_id="agent-a",
                user_id=1,
                conversation_id=conversation["conversation_id"],
            )[0]["event_type"],
            "router.received",
        )

    def test_installer_patch_is_idempotent(self) -> None:
        source = (
            "    from octop.api.routers.decision import router as decision_router\n"
            '            _RouterMount(update.router, "/api", ["update"]),\n'
        )
        once = patch_source(source)
        twice = patch_source(once)
        self.assertEqual(once, twice)
        self.assertIn("conversation_router import router", once)
        self.assertIn('_RouterMount(conversation_router, "/api", ["conversation"])', once)

    def test_installer_patch_functions_are_idempotent_on_available_source_tree(self) -> None:
        source_root = available_router_source_root()
        if source_root is None:
            self.skipTest("Octop 1.0.2 source tree is not available")
        checks = [
            ("api/app.py", patch_source, "conversation_router import router"),
            ("api/routers/chat/turn.py", patch_chat_turn_source, "knowledge_base_ids=("),
            ("infra/cron/manager.py", patch_cron_manager_source, "budget_gate=RouterBudgetGate("),
            ("infra/cron/job.py", patch_cron_job_source, "budget_lease.finish("),
            ("infra/cron/delivery.py", patch_cron_delivery_source, "command.budget_lease.observe"),
            ("infra/db/repos/usage.py", patch_usage_repo_source, "trace_id, task_id, run_id"),
            (
                "infra/gateway/process/usage_record.py",
                patch_usage_record_source,
                "trace_id: str | None = None",
            ),
            (
                "infra/proactive/scheduler.py",
                patch_proactive_scheduler_source,
                "user_id=target_session.user_id",
            ),
            ("infra/proactive/service.py", patch_proactive_service_source, "router budget blocked"),
            ("infra/server.py", patch_server_source, "router_budget = RouterBudgetGate("),
        ]
        for relative, patcher, marker in checks:
            with self.subTest(path=relative):
                source = (source_root / relative).read_text(encoding="utf-8")
                patched = patcher(source)
                self.assertEqual(patcher(patched), patched)
                self.assertIn(marker, patched)
                compile(patched, relative, "exec")


if __name__ == "__main__":
    unittest.main()
