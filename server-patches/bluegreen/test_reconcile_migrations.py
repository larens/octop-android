from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from reconcile_migrations import reconcile


def migration(version: int, name: str, dialect: str = "sqlite", target: int | None = None) -> str:
    suffix = ".pg.sql" if dialect == "postgresql" else ".sql"
    return (
        f"CREATE TABLE {name} (id INTEGER);\n"
        f"UPDATE _schema_version SET version = {target or version};\n"
        f"/* {suffix} */\n"
    )


class ReconcileMigrationsTest(unittest.TestCase):
    def test_existing_identical_custom_migration_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            migrations = root / "migrations"
            custom = root / "custom"
            migrations.mkdir()
            custom.mkdir()
            text = migration(22, "mobile")
            (migrations / "022_mobile.sql").write_text(text)
            (custom / "022_mobile.sql").write_text(text)

            changes = reconcile(custom, migrations)

            self.assertEqual({}, changes)
            self.assertEqual(text, (migrations / "022_mobile.sql").read_text())

    def test_moves_duplicate_upstream_pair_after_custom_migrations(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            migrations = root / "migrations"
            custom = root / "custom"
            migrations.mkdir()
            custom.mkdir()
            (migrations / "022_upstream.sql").write_text(migration(22, "upstream"))
            (migrations / "022_upstream.pg.sql").write_text(migration(22, "upstream", "postgresql"))
            (migrations / "029_latest.sql").write_text(migration(29, "latest"))
            (custom / "022_mobile.sql").write_text(migration(22, "mobile"))
            (custom / "022_mobile.pg.sql").write_text(migration(22, "mobile", "postgresql"))
            (custom / "029_custom.sql").write_text(migration(29, "custom"))

            changes = reconcile(custom, migrations)

            self.assertEqual(22, next(iter(changes)))
            self.assertTrue((migrations / "030_upstream.sql").exists())
            self.assertTrue((migrations / "030_upstream.pg.sql").exists())
            self.assertIn("version = 30", (migrations / "030_upstream.sql").read_text())
            self.assertFalse((migrations / "022_upstream.sql").exists())

    def test_resolves_multiple_upstream_collisions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            migrations = root / "migrations"
            custom = root / "custom"
            migrations.mkdir()
            custom.mkdir()
            for version in (22, 23):
                (migrations / f"{version:03d}_upstream.sql").write_text(
                    migration(version, f"upstream_{version}")
                )
                (custom / f"{version:03d}_mobile.sql").write_text(
                    migration(version, f"mobile_{version}")
                )

            reconcile(custom, migrations)

            versions = sorted(
                int(path.name[:3])
                for path in migrations.glob("*.sql")
                if ".pg.sql" not in path.name
            )
            self.assertEqual(versions, sorted(set(versions)))
            self.assertTrue((migrations / "024_upstream.sql").exists())
            self.assertTrue((migrations / "025_upstream.sql").exists())


if __name__ == "__main__":
    unittest.main()
