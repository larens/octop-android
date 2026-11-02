#!/usr/bin/env python3
"""Smoke tests for the installed Router Phase 3 read-only Worker boundary."""

from __future__ import annotations

import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace

from octop.infra.conversation.orchestrator import (
    RouterWorkerService,
    WorkerDispatchRejected,
    worker_config,
)
from octop.infra.conversation.service import ConversationService
from octop.infra.db.migrate import run_migrations
from octop.infra.db.pool import SqlitePool
from octop.infra.db.repos.agents import AgentRepo
from octop.infra.db.repos.sessions import SessionRepo
from octop.infra.db.repos.threads import ThreadRepo
from octop.infra.db.repos.users import UserRepo
from octop.infra.gateway.threads import ThreadRegistry


class MemorySettings:
    def __init__(self, values: dict[str, str] | None = None) -> None:
        self.values = dict(values or {})

    def get(self, key: str) -> str | None:
        return self.values.get(key)


class RouterPhase3Test(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.db = SqlitePool(Path(self.temp.name) / "octop.db")
        run_migrations(self.db)
        UserRepo(self.db).create(username="alice", password_hash="h", role="user")
        AgentRepo(self.db).create(agent_id="agent-a", user_id=1, name="Agent A")
        self.service = ConversationService(self.db)
        self.registry = ThreadRegistry(
            session_repo=SessionRepo(self.db),
            thread_repo=ThreadRepo(self.db),
        )

    def tearDown(self) -> None:
        self.db.close()
        self.temp.cleanup()

    def test_worker_is_fail_closed_without_dispatch_and_enforced_budget(self) -> None:
        self.assertFalse(worker_config(MemorySettings()).ready)
        self.assertFalse(
            worker_config(
                MemorySettings(
                    {
                        "router.worker_dispatch.enabled": "true",
                        "router.budget.mode": "shadow",
                        "router.budget.enforced_surfaces": "worker",
                    }
                )
            ).ready
        )
        self.assertTrue(
            worker_config(
                MemorySettings(
                    {
                        "router.worker_dispatch.enabled": "true",
                        "router.budget.mode": "enforce",
                        "router.budget.enforced_surfaces": "worker",
                    }
                )
            ).ready
        )

    async def test_read_only_queue_claims_once_and_hides_worker_threads(self) -> None:
        conversation = self.service.ensure_main_conversation(
            agent_id="agent-a", user_id=1
        )
        queued = self.service.create_read_only_task(
            conversation_id=str(conversation["conversation_id"]),
            title="Check calendar",
            subject_key="calendar:tomorrow",
            objective="Read tomorrow's calendar",
            capsule={"trace_id": "trace-1", "context_pack_id": "pack-1"},
            max_attempts=3,
        )
        claimed = self.service.claim_next_read_only_run(
            now=int(time.time()) + 1,
            lease_seconds=30,
        )
        self.assertIsNotNone(claimed)
        self.assertEqual(claimed["run"]["run_id"], queued["runs"][0]["run_id"])
        self.assertIsNone(
            self.service.claim_next_read_only_run(
                now=int(time.time()) + 2,
                lease_seconds=30,
            )
        )

        dashboard = await self.registry.get_or_create(
            agent_id="agent-a",
            user_id=1,
            channel_type=ThreadRegistry.CHANNEL_DASHBOARD,
            channel_subject_id="1",
        )
        await self.registry.get_or_create_by_key(
            session_key=ThreadRegistry.worker_key(
                agent_id="agent-a",
                task_id=str(queued["task"]["task_id"]),
                run_id=str(queued["runs"][0]["run_id"]),
            ),
            agent_id="agent-a",
            user_id=1,
            channel_type=ThreadRegistry.CHANNEL_WORKER,
            channel_metadata={"hidden": True},
        )
        self.assertEqual(
            [
                row.thread_id
                for row in self.registry.list_threads(agent_id="agent-a", user_id=1)
            ],
            [dashboard],
        )

    async def test_side_effect_submission_is_rejected_before_queueing(self) -> None:
        server = SimpleNamespace(
            services=SimpleNamespace(
                db=self.db,
                settings_repo=MemorySettings(
                    {
                        "router.worker_dispatch.enabled": "true",
                        "router.budget.mode": "enforce",
                        "router.budget.enforced_surfaces": "worker",
                    }
                ),
                config=SimpleNamespace(default_timezone="Asia/Shanghai"),
            ),
            app_runtime=SimpleNamespace(
                gateway=SimpleNamespace(thread_registry=self.registry)
            ),
        )
        worker = RouterWorkerService(server)
        with self.assertRaises(WorkerDispatchRejected):
            await worker.submit_read_only(
                agent_id="agent-a",
                user_id=1,
                title="Delete files",
                objective="删除所有文件并发送通知",
            )
        with self.db.connect() as conn:
            self.assertEqual(
                conn.execute("SELECT COUNT(*) FROM conversation_tasks").fetchone()[0], 0
            )

    def test_worker_config_has_bounded_attempts_and_concurrency(self) -> None:
        config = worker_config(
            MemorySettings(
                {
                    "router.worker_dispatch.enabled": "true",
                    "router.budget.mode": "enforce",
                    "router.budget.enforced_surfaces": "worker",
                    "router.worker.max_attempts": "99",
                    "router.worker.max_concurrency": "99",
                }
            )
        )
        self.assertLessEqual(config.max_attempts, 5)
        self.assertLessEqual(config.max_concurrency, 8)


if __name__ == "__main__":
    unittest.main()
