from __future__ import annotations

import importlib.util
import os
import tempfile
import unittest
from pathlib import Path

OPS_SCRIPT_CANDIDATES = (
    Path(__file__).parent / "ops/storage_maintenance.py",
    Path(__file__).parent.parent / "ops/storage_maintenance.py",
)


def _load_module():
    script = next(path for path in OPS_SCRIPT_CANDIDATES if path.is_file())
    spec = importlib.util.spec_from_file_location("storage_maintenance", script)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class StorageMaintenanceTest(unittest.TestCase):
    def test_release_dirs_are_newest_first_and_require_manifest(self) -> None:
        module = _load_module()
        with tempfile.TemporaryDirectory() as tmp:
            releases = Path(tmp)
            for name, stamp in (("old", 100), ("new", 300), ("middle", 200)):
                release = releases / name
                release.mkdir()
                (release / "manifest.json").write_text("{}", encoding="utf-8")
                os.utime(release, (stamp, stamp))
            (releases / "not-a-release").mkdir()
            discovered = module._release_dirs(releases)
            self.assertEqual(["new", "middle", "old"], [path.name for path in discovered])

    def test_journal_config_is_atomic_and_bounded(self) -> None:
        module = _load_module()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "journald.conf.d/50-octop-storage.conf"
            module._write_journal_config(path, "512M")
            text = path.read_text(encoding="utf-8")
            self.assertIn("SystemMaxUse=512M", text)
            self.assertIn("MaxRetentionSec=14day", text)
            self.assertFalse(path.with_name(path.name + ".tmp").exists())


if __name__ == "__main__":
    unittest.main()
