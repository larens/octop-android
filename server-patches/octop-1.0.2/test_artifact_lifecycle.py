from __future__ import annotations

import sqlite3
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from install_artifact_lifecycle import patch_middleware, patch_thread_repo


class _Store:
    def __init__(self) -> None:
        self.appended: list[tuple[str, list[str], str]] = []
        self.removed: list[tuple[str, list[str], str]] = []

    def append_artifacts(
        self,
        thread_id: str,
        paths: list[str],
        *,
        agent_id: str = "",
    ) -> None:
        self.appended.append((thread_id, list(paths), agent_id))

    def remove_artifacts(
        self,
        thread_id: str,
        paths: list[str],
        *,
        agent_id: str = "",
    ) -> None:
        self.removed.append((thread_id, list(paths), agent_id))


class ArtifactLifecycleInstallerTest(unittest.TestCase):
    def test_installer_patches_are_idempotent(self) -> None:
        middleware = """
logger = logging.getLogger(__name__)

    def _record(self) -> None:
        tool_call = request.tool_call
        name = str(tool_call.get("name") or "")
        if not is_artifact_tool_name(name):
            return
        thread_id = current_thread_id()
        if not thread_id:
            return
        raw_args = tool_call.get("args")
        paths = extract_artifact_paths(
            tool_name=name,
            args=raw_args if isinstance(raw_args, (str, Mapping)) else None,
            result=result.content,
            workspace_dir=self._workspace_dir,
        )
        if not paths:
            return
        self._append(thread_id, paths)
        room = peer_room_thread_id(thread_id)
        if room:
            self._append(room, paths)
        self._maybe_record_pending_plan(thread_id, paths)

    def _maybe_record_pending_plan(self, thread_id: str, paths: Sequence[str]) -> None:
        pass
"""
        repo = """
    def delete(self, thread_id: str) -> None:
        pass
"""

        patched_middleware = patch_middleware(middleware)
        patched_repo = patch_thread_repo(repo)

        self.assertIn("_ARTIFACT_REMOVAL_TOOL_BASES", patched_middleware)
        self.assertIn("def _remove(", patched_middleware)
        self.assertIn("def remove_artifacts(", patched_repo)
        self.assertEqual(patched_middleware, patch_middleware(patched_middleware))
        self.assertEqual(patched_repo, patch_thread_repo(patched_repo))


class ArtifactLifecycleBehaviorTest(unittest.TestCase):
    def test_successful_delete_removes_current_and_peer_artifact(self) -> None:
        from langchain_core.messages import ToolMessage
        from octop.infra.agents.middleware.thread_artifacts import (
            ThreadArtifactsMiddleware,
        )

        store = _Store()
        middleware = ThreadArtifactsMiddleware(
            thread_repo=store,
            workspace_dir=Path("/workspace"),
            agent_id="agent-1",
        )
        deleted = str(Path("/workspace/generated/report.html"))
        request = SimpleNamespace(
            tool_call={"name": "delete", "args": {"file_path": deleted}},
        )
        result = ToolMessage(
            content=f"Deleted {deleted}",
            tool_call_id="call-1",
            name="delete",
            status="success",
        )

        with patch(
            "octop.infra.agents.middleware.thread_artifacts.current_thread_id",
            return_value="room~member",
        ):
            middleware._record(request, result)

        expected_paths = [deleted]
        self.assertEqual(
            [("room~member", expected_paths, "agent-1"), ("room", expected_paths, "agent-1")],
            store.removed,
        )
        self.assertEqual([], store.appended)

    def test_failed_delete_does_not_change_artifact_index(self) -> None:
        from langchain_core.messages import ToolMessage
        from octop.infra.agents.middleware.thread_artifacts import (
            ThreadArtifactsMiddleware,
        )

        store = _Store()
        middleware = ThreadArtifactsMiddleware(
            thread_repo=store,
            workspace_dir=Path("/workspace"),
            agent_id="agent-1",
        )
        deleted = str(Path("/workspace/generated/report.html"))
        request = SimpleNamespace(
            tool_call={"name": "delete", "args": {"file_path": deleted}},
        )
        result = ToolMessage(
            content=f"Error deleting {deleted}",
            tool_call_id="call-1",
            name="delete",
            status="error",
        )

        with patch(
            "octop.infra.agents.middleware.thread_artifacts.current_thread_id",
            return_value="thread-1",
        ):
            middleware._record(request, result)

        self.assertEqual([], store.removed)
        self.assertEqual([], store.appended)

    def test_repo_removes_absolute_and_workspace_relative_aliases(self) -> None:
        from octop.infra.db.repos.threads import ThreadRepo

        class DB:
            def __init__(self, conn: sqlite3.Connection) -> None:
                self.conn = conn

            @contextmanager
            def transaction(self):
                yield self.conn

        with tempfile.TemporaryDirectory() as tmp:
            conn = sqlite3.connect(":memory:")
            conn.row_factory = sqlite3.Row
            conn.execute("CREATE TABLE threads(thread_id TEXT, artifacts TEXT)")
            conn.execute(
                "INSERT INTO threads VALUES (?, ?)",
                (
                    "thread-1",
                    '[{"path":"/data/agent-workspace/.octop/workspaces/agent-1/generated/report.html","agent_id":"agent-1"},'
                    '{"path":"/.octop/workspaces/agent-1/generated/keep.md","agent_id":"agent-1"},'
                    '{"path":"/.octop/workspaces/agent-1/generated/shared.md","agent_id":"agent-1"},'
                    '{"path":"/.octop/workspaces/agent-2/generated/shared.md","agent_id":"agent-2"}]',
                ),
            )

            repo = ThreadRepo(DB(conn))
            repo.remove_artifacts(
                "thread-1",
                [
                    "/.octop/workspaces/agent-1/generated/report.html",
                    "/.octop/workspaces/agent-1/generated/shared.md",
                ],
                agent_id="agent-1",
            )

            raw = conn.execute(
                "SELECT artifacts FROM threads WHERE thread_id = ?",
                ("thread-1",),
            ).fetchone()["artifacts"]
            self.assertNotIn("report.html", raw)
            self.assertIn("keep.md", raw)
            self.assertNotIn("/.octop/workspaces/agent-1/generated/shared.md", raw)
            self.assertIn("/.octop/workspaces/agent-2/generated/shared.md", raw)
            conn.close()


if __name__ == "__main__":
    unittest.main()
