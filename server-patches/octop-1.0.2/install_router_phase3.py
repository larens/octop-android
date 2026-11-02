#!/usr/bin/env python3
"""Install the bounded read-only Router Worker introduced by Phase 3."""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from patch_paths import backups_dir, octop_site


PACK_DIR = Path(__file__).resolve().parent


def _replace(
    source: str,
    *,
    marker: str,
    old: str,
    new: str,
    label: str,
) -> str:
    if marker in source:
        return source
    if old not in source:
        raise RuntimeError(f"{label} anchor not found")
    return source.replace(old, new, 1)


def patch_thread_registry(source: str) -> str:
    source = _replace(
        source,
        marker='CHANNEL_WORKER = "worker"',
        old='    CHANNEL_MCP = "mcp"\n',
        new='    CHANNEL_MCP = "mcp"\n    CHANNEL_WORKER = "worker"\n',
        label="thread registry worker channel",
    )
    if "cls.CHANNEL_WORKER," not in source:
        if "            cls.CHANNEL_CRON,\n        }" in source:
            source = source.replace(
                "            cls.CHANNEL_CRON,\n        }",
                "            cls.CHANNEL_CRON,\n            cls.CHANNEL_WORKER,\n        }",
                1,
            )
        elif "            cls.CHANNEL_MCP,\n        }" in source:
            source = source.replace(
                "            cls.CHANNEL_MCP,\n        }",
                "            cls.CHANNEL_MCP,\n            cls.CHANNEL_WORKER,\n        }",
                1,
            )
        else:
            raise RuntimeError("thread registry virtual channel anchor not found")
    source = _replace(
        source,
        marker="def worker_key(",
        old="    def __init__(self, *, session_repo: SessionRepo, thread_repo: ThreadRepo) -> None:\n",
        new="""\
    @staticmethod
    def worker_key(*, agent_id: str, task_id: str, run_id: str) -> str:
        \"\"\"Return the isolated, hidden Thread key for one attempted Worker Run.\"\"\"
        return ThreadRegistry.make_key(
            agent_id=agent_id,
            channel_type=ThreadRegistry.CHANNEL_WORKER,
            channel_subject_id=task_id,
            channel_chat_type=run_id,
        )

    def __init__(self, *, session_repo: SessionRepo, thread_repo: ThreadRepo) -> None:
""",
        label="thread registry worker key",
    )
    source = _replace(
        source,
        marker="exclude_channel_types=(self.CHANNEL_WORKER,)",
        old="""\
    def list_threads(self, *, agent_id: str, user_id: int, limit: int = 50) -> list[ThreadRow]:
        return self._threads.list_by_agent_user(agent_id=agent_id, user_id=user_id, limit=limit)
""",
        new="""\
    def list_threads(self, *, agent_id: str, user_id: int, limit: int = 50) -> list[ThreadRow]:
        return self._threads.list_by_agent_user(
            agent_id=agent_id,
            user_id=user_id,
            limit=limit,
            exclude_channel_types=(self.CHANNEL_WORKER,),
        )
""",
        label="thread registry hidden worker listing",
    )
    return source


def patch_thread_repo(source: str) -> str:
    source = _replace(
        source,
        marker="from typing import Any\n",
        old="from dataclasses import dataclass, field\n",
        new="from dataclasses import dataclass, field\nfrom typing import Any\n",
        label="thread repo typing import",
    )
    return _replace(
        source,
        marker="exclude_channel_types: tuple[str, ...] = ()",
        old="""\
    def list_by_agent_user(
        self, *, agent_id: str, user_id: int, limit: int = 50
    ) -> list[ThreadRow]:
        with self._db.connect() as conn:
            rows = conn.execute(
                "SELECT * FROM threads WHERE agent_id = ? AND user_id = ? "
                "ORDER BY pinned DESC, "
                "CASE WHEN last_active > 0 THEN last_active ELSE created_at END DESC, "
                "thread_id DESC LIMIT ?",
                (agent_id, user_id, limit),
            ).fetchall()
        return map_rows(rows, ThreadRow)
""",
        new="""\
    def list_by_agent_user(
        self,
        *,
        agent_id: str,
        user_id: int,
        limit: int = 50,
        exclude_channel_types: tuple[str, ...] = (),
    ) -> list[ThreadRow]:
        clauses = ["agent_id = ?", "user_id = ?"]
        params: list[Any] = [agent_id, user_id]
        if exclude_channel_types:
            placeholders = ", ".join("?" for _ in exclude_channel_types)
            clauses.append(f"channel_type NOT IN ({placeholders})")
            params.extend(exclude_channel_types)
        params.append(limit)
        with self._db.connect() as conn:
            rows = conn.execute(
                f"SELECT * FROM threads WHERE {' AND '.join(clauses)} "
                "ORDER BY pinned DESC, "
                "CASE WHEN last_active > 0 THEN last_active ELSE created_at END DESC, "
                "thread_id DESC LIMIT ?",
                tuple(params),
            ).fetchall()
        return map_rows(rows, ThreadRow)
""",
        label="thread repo worker exclusion",
    )


def patch_message_keys(source: str) -> str:
    return _replace(
        source,
        marker="ThreadRegistry.CHANNEL_WORKER,",
        old="        ThreadRegistry.CHANNEL_MCP,\n    ):",
        new="        ThreadRegistry.CHANNEL_MCP,\n        ThreadRegistry.CHANNEL_WORKER,\n    ):",
        label="worker user attribution",
    )


def patch_processor(source: str) -> str:
    source = _replace(
        source,
        marker='usage_source = str(meta.get("usage_source") or "chat")',
        old="""\
        im_meta = session_metadata_from_message(msg)
        meta = msg.metadata or {}
        locale = resolve_user_locale(
""",
        new="""\
        im_meta = session_metadata_from_message(msg)
        meta = msg.metadata or {}
        usage_source = str(meta.get("usage_source") or "chat")
        usage_trace_id = str(meta.get("trace_id") or "").strip() or None
        usage_task_id = str(meta.get("task_id") or "").strip() or None
        usage_run_id = str(meta.get("run_id") or "").strip() or None
        locale = resolve_user_locale(
""",
        label="processor usage attribution state",
    )
    source = _replace(
        source,
        marker="task_id=usage_task_id,",
        old="""\
            self._record_turn_usage(
                agent_id=agent_id,
                user_id=user_id,
                thread_id=thread_id,
                usage=usage_tracker.usage,
            )
""",
        new="""\
            self._record_turn_usage(
                agent_id=agent_id,
                user_id=user_id,
                thread_id=thread_id,
                usage=usage_tracker.usage,
                source=usage_source,
                trace_id=usage_trace_id,
                task_id=usage_task_id,
                run_id=usage_run_id,
            )
""",
        label="processor worker usage call",
    )
    source = _replace(
        source,
        marker="run_id: str | None = None,\n    ) -> None:\n        if self._usage_repo",
        old="""\
    def _record_turn_usage(
        self,
        *,
        agent_id: str,
        user_id: int,
        thread_id: str,
        usage: dict[str, Any] | None,
    ) -> None:
        if self._usage_repo is None or not usage:
            return
        record_turn_usage(
            self._usage_repo,
            agent_id=agent_id,
            user_id=user_id,
            thread_id=thread_id,
            usage=usage,
        )
""",
        new="""\
    def _record_turn_usage(
        self,
        *,
        agent_id: str,
        user_id: int,
        thread_id: str,
        usage: dict[str, Any] | None,
        source: str = "chat",
        trace_id: str | None = None,
        task_id: str | None = None,
        run_id: str | None = None,
    ) -> None:
        if self._usage_repo is None or not usage:
            return
        record_turn_usage(
            self._usage_repo,
            agent_id=agent_id,
            user_id=user_id,
            thread_id=thread_id,
            usage=usage,
            source=source,
            trace_id=trace_id,
            task_id=task_id,
            run_id=run_id,
        )
""",
        label="processor usage attribution method",
    )
    return source


def patch_server(source: str) -> str:
    source = _replace(
        source,
        marker="router_worker_service: Any | None = None",
        old="    bridge_manager: Any | None = None\n",
        new="    bridge_manager: Any | None = None\n    router_worker_service: Any | None = None\n",
        label="server worker runtime field",
    )
    source = _replace(
        source,
        marker="self.router_worker_service.retarget_services(services)",
        old="""\
        if self.trajectory_service is not None:
            from octop.infra.history.trajectory.store import TrajectoryStore  # noqa: PLC0415

            self.trajectory_service.replace_store(TrajectoryStore(services.trajectory_event_repo))
""",
        new="""\
        if self.trajectory_service is not None:
            from octop.infra.history.trajectory.store import TrajectoryStore  # noqa: PLC0415

            self.trajectory_service.replace_store(TrajectoryStore(services.trajectory_event_repo))
        if self.router_worker_service is not None:
            self.router_worker_service.retarget_services(services)
""",
        label="server worker runtime rebind",
    )
    source = _replace(
        source,
        marker="router_worker_service = RouterWorkerService(self)",
        old="""\
        self.app_runtime = AppRuntime(
            agent_registry=registry,
            gateway=gateway,
            cron_manager=cron_mgr,
            user_manager=user_mgr,
            proactive_scheduler=proactive_scheduler,
            trajectory_service=trajectory_service,
            history_archive=history_archive,
            bridge_manager=bridge_mgr,
        )
""",
        new="""\
        self.app_runtime = AppRuntime(
            agent_registry=registry,
            gateway=gateway,
            cron_manager=cron_mgr,
            user_manager=user_mgr,
            proactive_scheduler=proactive_scheduler,
            trajectory_service=trajectory_service,
            history_archive=history_archive,
            bridge_manager=bridge_mgr,
        )
        from octop.infra.conversation.orchestrator import RouterWorkerService  # noqa: PLC0415

        router_worker_service = RouterWorkerService(self)
        self.app_runtime.router_worker_service = router_worker_service
        await router_worker_service.start()
""",
        label="server worker startup",
    )
    return _replace(
        source,
        marker="await rt.router_worker_service.shutdown()",
        old="""\
            if self.app_runtime is not None:
                rt = self.app_runtime
                await rt.proactive_scheduler.shutdown()
""",
        new="""\
            if self.app_runtime is not None:
                rt = self.app_runtime
                if rt.router_worker_service is not None:
                    await rt.router_worker_service.shutdown()
                await rt.proactive_scheduler.shutdown()
""",
        label="server worker shutdown",
    )


def main() -> None:
    site = octop_site()
    source_paths = {
        site / "infra/gateway/threads.py": patch_thread_registry,
        site / "infra/db/repos/threads.py": patch_thread_repo,
        site / "infra/gateway/process/message_keys.py": patch_message_keys,
        site / "infra/gateway/process/processor.py": patch_processor,
        site / "infra/server.py": patch_server,
    }
    source_updates = {
        path: patcher(path.read_text(encoding="utf-8"))
        for path, patcher in source_paths.items()
    }
    module_targets = {
        site / "infra/conversation/service.py": PACK_DIR / "router_phase0_service.py",
        site / "api/routers/conversation_router.py": PACK_DIR / "router_phase0_api.py",
        site / "infra/conversation/orchestrator.py": PACK_DIR
        / "router_phase3_orchestrator.py",
    }
    changed_modules = {
        target: source
        for target, source in module_targets.items()
        if not target.exists() or target.read_bytes() != source.read_bytes()
    }
    changed_sources = {
        path: patched
        for path, patched in source_updates.items()
        if patched != path.read_text(encoding="utf-8")
    }
    if not changed_modules and not changed_sources:
        print(site / "infra/conversation/orchestrator.py")
        return

    backup = backups_dir() / f"router-phase3-{time.strftime('%Y%m%d-%H%M%S')}"
    backup.mkdir(parents=True, exist_ok=True)
    for path in changed_sources:
        shutil.copy2(path, backup / path.name)
    for target in changed_modules:
        if target.exists():
            shutil.copy2(target, backup / target.name)
    for target, source in changed_modules.items():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    for path, patched in changed_sources.items():
        path.write_text(patched, encoding="utf-8")
    print(site / "infra/conversation/orchestrator.py")


if __name__ == "__main__":
    main()
