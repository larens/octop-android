from __future__ import annotations

import sqlite3
import tarfile
import tempfile
import unittest
from pathlib import Path

from octop.infra.backup.system_archive import _add_dir


class MemorySnapshotArchiveTest(unittest.TestCase):
    def test_workspace_archive_snapshots_memory_db_without_sidecars(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            workspace = root / "workspace"
            memory_dir = workspace / ".octop"
            memory_dir.mkdir(parents=True)
            db_path = memory_dir / "memory.sqlite"
            conn = sqlite3.connect(db_path)
            try:
                self.assertEqual("wal", conn.execute("PRAGMA journal_mode=WAL").fetchone()[0])
                conn.execute("CREATE TABLE atoms(id TEXT PRIMARY KEY, value TEXT NOT NULL)")
                conn.execute("INSERT INTO atoms VALUES ('atom-1', 'kept in WAL')")
                conn.commit()
                self.assertTrue(Path(f"{db_path}-wal").is_file())

                archive = root / "backup.tar.gz"
                with tarfile.open(archive, "w:gz") as tf:
                    _add_dir(
                        tf,
                        workspace,
                        "workspaces/agent-a",
                        skip_chats=False,
                        snapshot_memory_db=True,
                        scratch_dir=root / "scratch",
                    )
            finally:
                conn.close()

            with tarfile.open(archive, "r:gz") as tf:
                names = set(tf.getnames())
                self.assertIn("workspaces/agent-a/.octop/memory.sqlite", names)
                self.assertNotIn("workspaces/agent-a/.octop/memory.sqlite-wal", names)
                self.assertNotIn("workspaces/agent-a/.octop/memory.sqlite-shm", names)
                tf.extract(
                    "workspaces/agent-a/.octop/memory.sqlite",
                    root / "extracted",
                    filter=tarfile.data_filter,
                )

            restored = root / "extracted/workspaces/agent-a/.octop/memory.sqlite"
            with sqlite3.connect(restored) as restored_conn:
                self.assertEqual(
                    ("atom-1", "kept in WAL"),
                    restored_conn.execute("SELECT id, value FROM atoms").fetchone(),
                )


if __name__ == "__main__":
    unittest.main()
