from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from install_mobile_today_backport import (
    REQUIRED_PATHS,
    install_backport_files,
    patch_app_source,
)


APP_SOURCE = """\
    from octop.api.routers.decision import router as decision_router

    _mount_routers(
        app,
        [
            _RouterMount(decision_router, "/api", ["settings"]),
        ],
    )
"""


class MobileTodayBackportTest(unittest.TestCase):
    def test_mounts_interest_router_once(self) -> None:
        patched = patch_app_source(APP_SOURCE)

        self.assertIn("interest_monitors_router", patched)
        self.assertEqual(patched, patch_app_source(patched))
        self.assertEqual(1, patched.count("interest_monitors import router"))
        self.assertEqual(1, patched.count("_RouterMount(interest_monitors_router"))

    def test_requires_every_backport_module_before_copying(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "backport"
            target = root / "site"
            for relative in REQUIRED_PATHS[:-1]:
                path = source / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(relative.name, encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "backport is incomplete"):
                install_backport_files(
                    source_root=source,
                    target_site=target,
                    backup_root=root / "backups",
                    timestamp="test",
                )
            self.assertFalse(target.exists())

    def test_backs_up_replaced_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "backport"
            target = root / "site"
            for relative in REQUIRED_PATHS:
                path = source / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"new {relative}", encoding="utf-8")
            old = target / REQUIRED_PATHS[-1]
            old.parent.mkdir(parents=True)
            old.write_text("old", encoding="utf-8")

            changed = install_backport_files(
                source_root=source,
                target_site=target,
                backup_root=root / "backups",
                timestamp="test",
            )

            self.assertEqual(len(REQUIRED_PATHS), len(changed))
            backup = (
                root
                / "backups/mobile-today-backport-test"
                / REQUIRED_PATHS[-1]
            )
            self.assertEqual("old", backup.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
