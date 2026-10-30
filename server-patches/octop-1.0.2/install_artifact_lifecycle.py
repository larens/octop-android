"""Remove deleted files from thread artifact indexes after successful tools."""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from patch_paths import backups_dir, octop_site


def patch_middleware(source: str) -> str:
    if "_ARTIFACT_REMOVAL_TOOL_BASES" in source:
        return source

    anchor = 'logger = logging.getLogger(__name__)\n'
    if anchor not in source:
        raise RuntimeError("thread artifact logger anchor not found")
    source = source.replace(
        anchor,
        anchor
        + '\n_ARTIFACT_REMOVAL_TOOL_BASES = frozenset({"delete", "remove_file"})\n',
        1,
    )

    old_record = '''        tool_call = request.tool_call
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
'''
    new_record = '''        tool_call = request.tool_call
        name = str(tool_call.get("name") or "")
        base_name = name.rsplit("/", 1)[-1].lower()
        removing = base_name in _ARTIFACT_REMOVAL_TOOL_BASES
        if not removing and not is_artifact_tool_name(name):
            return
        thread_id = current_thread_id()
        if not thread_id:
            return
        raw_args = tool_call.get("args")
        # ``write_file`` only supplies the supported path-key extraction here;
        # the delete tool itself is already known to have succeeded.
        paths = extract_artifact_paths(
            tool_name="write_file" if removing else name,
            args=raw_args if isinstance(raw_args, (str, Mapping)) else None,
            result=None if removing else result.content,
            workspace_dir=self._workspace_dir,
        )
        if not paths:
            return
        room = peer_room_thread_id(thread_id)
        if removing:
            self._remove(thread_id, paths)
            if room:
                self._remove(room, paths)
            return
        self._append(thread_id, paths)
        if room:
            self._append(room, paths)
        self._maybe_record_pending_plan(thread_id, paths)
'''
    if old_record not in source:
        raise RuntimeError("thread artifact record block not found")
    source = source.replace(old_record, new_record, 1)

    append_anchor = '''    def _maybe_record_pending_plan(self, thread_id: str, paths: Sequence[str]) -> None:
'''
    remove_method = '''    def _remove(self, thread_id: str, paths: Sequence[str]) -> None:
        remover = getattr(self._threads, "remove_artifacts", None)
        if remover is None:
            return
        try:
            remover(
                thread_id,
                paths,
                agent_id=self._agent_id,
            )
        except Exception:
            logger.warning(
                "Failed to remove thread artifacts for %s",
                thread_id,
                exc_info=True,
            )

'''
    if append_anchor not in source:
        raise RuntimeError("thread artifact pending-plan anchor not found")
    return source.replace(append_anchor, remove_method + append_anchor, 1)


def patch_thread_repo(source: str) -> str:
    if "def remove_artifacts(" in source:
        return source
    anchor = '''    def delete(self, thread_id: str) -> None:
'''
    method = '''    def remove_artifacts(
        self,
        thread_id: str,
        paths: Sequence[str],
        *,
        agent_id: str = "",
    ) -> None:
        """Remove successful deletions from the thread's artifact index."""
        aid = (agent_id or "").strip()
        targets = {
            p.strip().replace("\\\\", "/")
            for p in paths
            if isinstance(p, str) and p.strip()
        }
        if not targets or not aid:
            return
        marker = f"/.octop/workspaces/{aid}/"

        def identity(path: str) -> str:
            normalized = path.strip().replace("\\\\", "/")
            marker_at = normalized.find(marker)
            return normalized[marker_at + len(marker) :] if marker_at >= 0 else normalized

        target_ids = {identity(path) for path in targets}
        with self._db.transaction() as conn:
            row = conn.execute(
                "SELECT artifacts FROM threads WHERE thread_id = ?",
                (thread_id,),
            ).fetchone()
            if row is None:
                return
            current = parse_thread_artifacts(row["artifacts"])
            remaining = [
                artifact
                for artifact in current
                if not (
                    artifact.agent_id in {"", aid}
                    and identity(artifact.path) in target_ids
                )
            ]
            if remaining == current:
                return
            conn.execute(
                "UPDATE threads SET artifacts = ? WHERE thread_id = ?",
                (
                    json.dumps(serialize_thread_artifacts(remaining), ensure_ascii=False),
                    thread_id,
                ),
            )

'''
    if anchor not in source:
        raise RuntimeError("thread repo delete anchor not found")
    return source.replace(anchor, method + anchor, 1)


def main() -> None:
    site = octop_site()
    backup = backups_dir() / f"artifact-lifecycle-{time.strftime('%Y%m%d-%H%M%S')}"
    backup.mkdir(parents=True, exist_ok=True)

    targets = (
        (
            site / "infra/agents/middleware/thread_artifacts.py",
            patch_middleware,
            "thread_artifacts.py",
        ),
        (
            site / "infra/db/repos/threads.py",
            patch_thread_repo,
            "threads.py",
        ),
    )
    for path, patcher, backup_name in targets:
        source = path.read_text(encoding="utf-8")
        shutil.copy2(path, backup / backup_name)
        path.write_text(patcher(source), encoding="utf-8")
        print(path)


if __name__ == "__main__":
    main()
