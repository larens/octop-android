#!/usr/bin/env python3
"""Copy custom SQL migrations and move upstream collisions to unused versions."""

from __future__ import annotations

import argparse
import json
import re
import shutil
from pathlib import Path


MIGRATION_RE = re.compile(r"^(\d{3})_(.+)\.sql$")
PG_RE = re.compile(r"^(\d{3})_(.+)\.pg\.sql$")
SQLITE_RE = re.compile(r"^(\d{3})_(.+)\.sql$")


def _logical_name(path: Path) -> tuple[int, str, str] | None:
    match = PG_RE.match(path.name)
    if match:
        return int(match.group(1)), match.group(2), "pg.sql"
    match = SQLITE_RE.match(path.name)
    if match:
        return int(match.group(1)), match.group(2), "sql"
    return None


def _migration_files(root: Path) -> list[Path]:
    return sorted(path for path in root.iterdir() if path.is_file() and _logical_name(path))


def copy_custom_migrations(custom_dir: Path, migrations_dir: Path) -> set[str]:
    custom_names: set[str] = set()
    for source in sorted(custom_dir.glob("*.sql")):
        if not MIGRATION_RE.match(source.name):
            continue
        target = migrations_dir / source.name
        if target.exists():
            if target.read_bytes() == source.read_bytes():
                custom_names.add(source.name)
                continue
            raise RuntimeError(f"custom migration collides by filename: {source.name}")
        shutil.copy2(source, target)
        custom_names.add(source.name)
    if not custom_names:
        raise RuntimeError(f"no custom migrations found in {custom_dir}")
    return custom_names


def _set_schema_version(path: Path, version: int) -> None:
    text = path.read_text(encoding="utf-8")
    updated, count = re.subn(
        r"UPDATE\s+_schema_version\s+SET\s+version\s*=\s*\d+\s*;",
        f"UPDATE _schema_version SET version = {version};",
        text,
    )
    if count != 1:
        raise RuntimeError(f"expected one schema-version update in {path}, found {count}")
    path.write_text(updated, encoding="utf-8")


def reconcile(custom_dir: Path, migrations_dir: Path) -> dict[int, list[str]]:
    custom_names = copy_custom_migrations(custom_dir, migrations_dir)
    changes: dict[int, list[str]] = {}
    next_version = max(version for version, _, _ in filter(None, map(_logical_name, _migration_files(migrations_dir)))) + 1

    while True:
        by_version: dict[int, list[Path]] = {}
        for path in _migration_files(migrations_dir):
            parsed = _logical_name(path)
            assert parsed is not None
            by_version.setdefault(parsed[0], []).append(path)

        collision = None
        for version, paths in sorted(by_version.items()):
            stems = {
                parsed[1]
                for path in paths
                for parsed in [_logical_name(path)]
                if parsed is not None
            }
            if len(stems) > 1 or len(paths) > 2:
                collision = version
                break
        if collision is None:
            break

        paths = by_version[collision]
        stems: dict[str, list[Path]] = {}
        for path in paths:
            parsed = _logical_name(path)
            assert parsed is not None
            stems.setdefault(parsed[1], []).append(path)

        custom_stems = {stem for stem, files in stems.items() if any(path.name in custom_names for path in files)}
        upstream_stems = set(stems) - custom_stems
        if len(custom_stems) != 1 or not upstream_stems:
            raise RuntimeError(
                f"cannot reconcile migration version {collision:03d}: "
                f"custom={sorted(custom_stems)} upstream={sorted(upstream_stems)}"
            )

        for stem in sorted(upstream_stems):
            for source in sorted(stems[stem]):
                parsed = _logical_name(source)
                assert parsed is not None
                suffix = ".pg.sql" if parsed[2] == "pg.sql" else ".sql"
                target = migrations_dir / f"{next_version:03d}_{stem}{suffix}"
                if target.exists():
                    raise RuntimeError(f"refusing to overwrite migration: {target}")
                source.rename(target)
                _set_schema_version(target, next_version)
                changes.setdefault(collision, []).append(f"{source.name} -> {target.name}")
            next_version += 1

    return changes


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--custom-dir", type=Path, required=True)
    parser.add_argument("--migrations-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    changes = reconcile(args.custom_dir, args.migrations_dir)
    report = {"changes": {str(key): value for key, value in changes.items()}}
    if args.report:
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
