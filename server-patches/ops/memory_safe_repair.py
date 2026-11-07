#!/usr/bin/env python3
"""Rebuild a corrupt Octop memory SQLite database without losing readable rows.

This tool is intentionally offline-only.  Stop ``octop.service`` (and any
other process that may open the database) before running it.  It never
deletes a WAL or SHM file belonging to a live process.

Typical recovery flow:

1. Freeze the service and copy the live database, WAL, and SHM into a
   recovery directory.
2. Resume/stop the service.
3. Let SQLite replay the copied WAL.
4. Run this tool on a copy; it rebuilds the atoms b-tree, drops the damaged
   FTS schema, vacuums the file, and recreates/rebuilds every FTS index.
5. Validate the result, install it while the service is stopped, then start
   the service.
"""

from __future__ import annotations

import argparse
from contextlib import closing
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import time
import traceback
from pathlib import Path


def quote_ident(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


def _live_memory_path(path: Path) -> bool:
    resolved = path.resolve()
    roots = (
        Path("/data/agent-workspace/.octop/workspaces"),
        Path("/opt/octop/agents"),
    )
    return resolved.name == "memory.sqlite" and any(
        resolved.is_relative_to(root) for root in roots
    )


def _require_offline(paths: tuple[Path, ...], service: str = "octop.service") -> None:
    if not any(_live_memory_path(path) for path in paths):
        return
    active = subprocess.run(
        ["systemctl", "is-active", "--quiet", service],
        check=False,
    ).returncode == 0
    if active:
        raise RuntimeError(
            f"{service} is active; stop it and use memory_safe_switch.py before touching a live memory database"
        )
    wanted = set()
    for path in paths:
        if _live_memory_path(path):
            wanted.update(
                {
                    path.resolve(),
                    Path(str(path.resolve()) + "-wal"),
                    Path(str(path.resolve()) + "-shm"),
                }
            )
    held: list[str] = []
    for proc in Path("/proc").iterdir():
        if not proc.name.isdigit():
            continue
        try:
            entries = list((proc / "fd").iterdir())
        except OSError:
            continue
        for entry in entries:
            try:
                raw_target = os.readlink(entry)
            except OSError:
                continue
            if Path(raw_target.removesuffix(" (deleted)")) in wanted:
                held.append(f"{proc.name}:{entry.name} -> {raw_target}")
    if held:
        raise RuntimeError("memory database still has open handles: " + "; ".join(sorted(held)))


def scalar(conn: sqlite3.Connection, sql: str, params: tuple[object, ...] = ()) -> object:
    row = conn.execute(sql, params).fetchone()
    return None if row is None else row[0]


def memory_namespace(conn: sqlite3.Connection) -> str:
    names = [
        str(row[0])
        for row in conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '%_atoms'"
        )
    ]
    candidates = [
        name.removesuffix("_atoms")
        for name in names
        if not name.endswith("_fts") and "_fts_" not in name
    ]
    if len(candidates) != 1:
        raise RuntimeError(f"cannot determine memory namespace from atoms tables: {candidates}")
    return candidates[0]


def detach_table(conn: sqlite3.Connection, table: str) -> None:
    """Remove a table (and its indexes) from the schema without reading its btree."""
    conn.execute("PRAGMA writable_schema=ON")
    try:
        conn.execute("DELETE FROM sqlite_master WHERE name=? OR tbl_name=?", (table, table))
        version = int(scalar(conn, "PRAGMA schema_version") or 0)
        conn.execute(f"PRAGMA schema_version={version + 1}")
    finally:
        conn.execute("PRAGMA writable_schema=OFF")


def rebuild_atoms(
    conn: sqlite3.Connection,
    ns: str,
    fallback: Path | None = None,
    exclude_checkpoint_atoms: bool = False,
) -> tuple[int, int, list[str], list[str]]:
    atoms = f"{ns}_atoms"
    row = conn.execute(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
        (atoms,),
    ).fetchone()
    if row is None or not row[0]:
        raise RuntimeError(f"{atoms} has no CREATE TABLE statement")
    create_sql = str(row[0])
    index_rows = [
        (str(item[0]), str(item[1]))
        for item in conn.execute(
            "SELECT name, sql FROM sqlite_master "
            "WHERE type='index' AND tbl_name=? AND sql IS NOT NULL",
            (atoms,),
        )
    ]
    indexes = [sql for _name, sql in index_rows]
    index_names = [name for name, _sql in index_rows]
    columns = [str(item[1]) for item in conn.execute(f"PRAGMA table_info({quote_ident(atoms)})")]
    if not columns:
        raise RuntimeError(f"{atoms} has no columns")
    column_sql = ", ".join(quote_ident(column) for column in columns)
    atom_ids = [str(row[0]) for row in conn.execute(f"SELECT id FROM {quote_ident(atoms)} ORDER BY id")]
    before = len(atom_ids)
    fallback_conn = None
    if fallback is not None:
        fallback_conn = sqlite3.connect(f"file:{fallback}?mode=ro", uri=True, timeout=60)
    rows: list[tuple[object, ...]] = []
    recovered_from_fallback: list[str] = []
    excluded_checkpoint_atoms: list[str] = []
    try:
        for atom_id in atom_ids:
            try:
                values = conn.execute(
                    f"SELECT rowid, {column_sql} FROM {quote_ident(atoms)} WHERE id=?",
                    (atom_id,),
                ).fetchone()
            except sqlite3.DatabaseError:
                values = None
            if values is None:
                if fallback_conn is None:
                    raise RuntimeError(f"cannot read atom {atom_id} and no fallback was supplied")
                values = fallback_conn.execute(
                    f"SELECT rowid, {column_sql} FROM {quote_ident(atoms)} WHERE id=?",
                    (atom_id,),
                ).fetchone()
                if values is None:
                    raise RuntimeError(f"cannot read atom {atom_id} from current or fallback database")
                recovered_from_fallback.append(atom_id)
            if exclude_checkpoint_atoms and (
                str(values[1]).startswith("thr_") or values[5] == "harness-checkpoint-v1"
            ):
                excluded_checkpoint_atoms.append(str(values[1]))
                continue
            rows.append(values)
    finally:
        if fallback_conn is not None:
            fallback_conn.close()

    for suffix in ("ai", "ad", "au"):
        conn.execute(f"DROP TRIGGER IF EXISTS {quote_ident(f'{ns}_atoms_{suffix}')}")
    conn.execute("PRAGMA writable_schema=ON")
    try:
        for name in index_names:
            conn.execute("DELETE FROM sqlite_master WHERE type='index' AND name=?", (name,))
    finally:
        conn.execute("PRAGMA writable_schema=OFF")

    old = f"{atoms}_rebuild_old_{int(time.time())}"
    conn.execute(f"ALTER TABLE {quote_ident(atoms)} RENAME TO {quote_ident(old)}")
    conn.execute(create_sql)
    placeholders = ",".join("?" for _ in range(len(columns) + 1))
    conn.executemany(
        f"INSERT INTO {quote_ident(atoms)}(rowid, {column_sql}) VALUES ({placeholders})",
        rows,
    )
    after = int(scalar(conn, f"SELECT count(*) FROM {quote_ident(atoms)}") or 0)
    if after != before:
        raise RuntimeError(f"atoms row count changed while rebuilding: {before} -> {after}")
    detach_table(conn, old)
    for sql in indexes:
        conn.execute(sql)
    return before, after, recovered_from_fallback, excluded_checkpoint_atoms


def rebuild_table_by_pk(
    conn: sqlite3.Connection,
    table: str,
    key_columns: tuple[str, ...],
    fallback: Path | None = None,
) -> tuple[int, int, list[str]]:
    row = conn.execute(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
        (table,),
    ).fetchone()
    if row is None or not row[0]:
        raise RuntimeError(f"{table} has no CREATE TABLE statement")
    create_sql = str(row[0])
    indexes = [
        str(item[0])
        for item in conn.execute(
            "SELECT sql FROM sqlite_master "
            "WHERE type='index' AND tbl_name=? AND sql IS NOT NULL",
            (table,),
        )
    ]
    columns = [str(item[1]) for item in conn.execute(f"PRAGMA table_info({quote_ident(table)})")]
    column_sql = ", ".join(quote_ident(column) for column in columns)
    key_sql = ", ".join(quote_ident(column) for column in key_columns)
    keys = conn.execute(f"SELECT {key_sql} FROM {quote_ident(table)}").fetchall()
    before = len(keys)
    fallback_conn = None
    if fallback is not None:
        fallback_conn = sqlite3.connect(f"file:{fallback}?mode=ro", uri=True, timeout=60)
    rows: list[tuple[object, ...]] = []
    recovered_from_fallback: list[str] = []
    where = " AND ".join(f"{quote_ident(column)}=?" for column in key_columns)
    try:
        for key in keys:
            params = tuple(key)
            try:
                values = conn.execute(
                    f"SELECT {column_sql} FROM {quote_ident(table)} WHERE {where}",
                    params,
                ).fetchone()
            except sqlite3.DatabaseError:
                values = None
            if values is None:
                if fallback_conn is None:
                    raise RuntimeError(f"cannot read {table} key {params} and no fallback was supplied")
                values = fallback_conn.execute(
                    f"SELECT {column_sql} FROM {quote_ident(table)} WHERE {where}",
                    params,
                ).fetchone()
                if values is None:
                    raise RuntimeError(f"cannot read {table} key {params} from current or fallback")
                recovered_from_fallback.append(repr(params))
            rows.append(tuple(values))
    finally:
        if fallback_conn is not None:
            fallback_conn.close()

    old = f"{table}_rebuild_old_{int(time.time())}"
    conn.execute(f"ALTER TABLE {quote_ident(table)} RENAME TO {quote_ident(old)}")
    conn.execute(create_sql)
    placeholders = ",".join("?" for _ in columns)
    conn.executemany(
        f"INSERT INTO {quote_ident(table)}({column_sql}) VALUES ({placeholders})",
        rows,
    )
    after = int(scalar(conn, f"SELECT count(*) FROM {quote_ident(table)}") or 0)
    if after != before:
        raise RuntimeError(f"{table} row count changed while rebuilding: {before} -> {after}")
    conn.execute(f"DROP TABLE {quote_ident(old)}")
    for sql in indexes:
        conn.execute(sql)
    return before, after, recovered_from_fallback


def remove_fts_schema(conn: sqlite3.Connection, ns: str) -> list[str]:
    names = [
        str(row[0])
        for row in conn.execute(
            "SELECT name FROM sqlite_master "
            "WHERE name LIKE ? OR name LIKE ?",
            (f"{ns}_%_fts", f"{ns}_%_fts_%"),
        )
    ]
    triggers = [
        str(row[0])
        for row in conn.execute(
            "SELECT name FROM sqlite_master "
            "WHERE type='trigger' AND name LIKE ? AND sql LIKE '%_fts%'",
            (f"{ns}_%",),
        )
    ]
    if not names:
        return triggers
    conn.execute("PRAGMA writable_schema=ON")
    try:
        for name in names:
            conn.execute("DELETE FROM sqlite_master WHERE name=?", (name,))
        for name in triggers:
            conn.execute("DELETE FROM sqlite_master WHERE type='trigger' AND name=?", (name,))
        version = int(scalar(conn, "PRAGMA schema_version") or 0)
        conn.execute(f"PRAGMA schema_version={version + 1}")
    finally:
        conn.execute("PRAGMA writable_schema=OFF")
    return triggers


def rebuild_fts(path: Path, ns: str) -> None:
    from octop_memory.storage.backends.sqlite import SqliteMemoryBackend

    with closing(sqlite3.connect(path, timeout=60, isolation_level=None)) as conn:
        conn.execute(
            f"INSERT INTO {quote_ident(ns + '_meta')}(key, value) "
            "VALUES ('fts_text_version', '1') "
            "ON CONFLICT(key) DO UPDATE SET value='1'"
        )
    backend = SqliteMemoryBackend(namespace=ns, db_path=path)
    backend.close()


def check_db(path: Path, ns: str) -> dict[str, object]:
    with closing(sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=60)) as conn:
        conn.row_factory = sqlite3.Row
        quick = str(conn.execute("PRAGMA quick_check").fetchone()[0])
        integrity = str(conn.execute("PRAGMA integrity_check").fetchone()[0])
        foreign_keys = len(conn.execute("PRAGMA foreign_key_check").fetchall())
        counts = {}
        for suffix in ("atoms", "candidates", "episodes", "raw_events", "memory_nodes"):
            table = f"{ns}_{suffix}"
            counts[table] = int(scalar(conn, f"SELECT count(*) FROM {quote_ident(table)}") or 0)
        for suffix in ("checkpoints", "writes"):
            counts[suffix] = int(scalar(conn, f"SELECT count(*) FROM {quote_ident(suffix)}") or 0)
        fts_counts = {}
        for suffix in ("memory_fts", "raw_events_fts", "candidates_fts", "atoms_fts", "episodes_fts"):
            table = f"{ns}_{suffix}"
            try:
                fts_counts[table] = int(scalar(conn, f"SELECT count(*) FROM {quote_ident(table)}") or 0)
            except sqlite3.DatabaseError as exc:
                fts_counts[table] = f"ERROR: {exc}"
    return {
        "quick_check": quick,
        "integrity_check": integrity,
        "foreign_key_violations": foreign_keys,
        "counts": counts,
        "fts_counts": fts_counts,
    }


def validate(path: Path, ns: str) -> dict[str, object]:
    result = check_db(path, ns)
    if result["quick_check"] != "ok":
        raise RuntimeError(f"quick_check failed: {result['quick_check']}")
    if result["integrity_check"] != "ok":
        raise RuntimeError(f"integrity_check failed: {result['integrity_check']}")
    if result["foreign_key_violations"]:
        raise RuntimeError(f"foreign_key_check found {result['foreign_key_violations']} violations")
    for table, count in result["fts_counts"].items():
        if isinstance(count, str):
            raise RuntimeError(f"{table}: {count}")
    atoms = result["counts"][f"{ns}_atoms"]
    atoms_fts = result["fts_counts"][f"{ns}_atoms_fts"]
    if atoms != atoms_fts:
        raise RuntimeError(f"atoms/FTS row mismatch: {atoms} != {atoms_fts}")
    return result


def repair(
    source: Path,
    output: Path,
    fallback: Path | None = None,
    exclude_checkpoint_atoms: bool = False,
) -> dict[str, object]:
    _require_offline((source, output))
    if not source.is_file():
        raise RuntimeError(f"source database does not exist: {source}")
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, output)
    for suffix in ("-wal", "-shm"):
        candidate = output.with_name(output.name + suffix)
        candidate.unlink(missing_ok=True)

    with closing(sqlite3.connect(output, timeout=60, isolation_level=None)) as conn:
        ns = memory_namespace(conn)
        before = int(scalar(conn, f"SELECT count(*) FROM {quote_ident(ns + '_atoms')}") or 0)
        remove_fts_schema(conn, ns)
        checkpoint_before, checkpoint_after, checkpoint_fallback = rebuild_table_by_pk(
            conn,
            "checkpoints",
            ("thread_id", "checkpoint_ns", "checkpoint_id"),
            fallback,
        )
        writes_before, writes_after, writes_fallback = rebuild_table_by_pk(
            conn,
            "writes",
            ("thread_id", "checkpoint_ns", "checkpoint_id", "task_id", "idx"),
            fallback,
        )
        atoms_before, atoms_after, fallback_ids, excluded_checkpoint_atoms = rebuild_atoms(
            conn,
            ns,
            fallback,
            exclude_checkpoint_atoms,
        )
        conn.execute("VACUUM")
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")

    rebuild_fts(output, ns)
    result = validate(output, ns)
    result.update(
        {
            "namespace": ns,
            "source": str(source),
            "output": str(output),
            "atoms_before": before,
            "atoms_rebuilt_from": atoms_before,
            "atoms_after": atoms_after,
            "atoms_recovered_from_fallback": fallback_ids,
            "checkpoint_atoms_excluded": excluded_checkpoint_atoms,
            "checkpoints_before": checkpoint_before,
            "checkpoints_after": checkpoint_after,
            "checkpoints_recovered_from_fallback": checkpoint_fallback,
            "writes_before": writes_before,
            "writes_after": writes_after,
            "writes_recovered_from_fallback": writes_fallback,
        }
    )
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--fallback",
        type=Path,
        help="older consistent copy used only for rows the current database cannot read",
    )
    parser.add_argument("--check", action="store_true", help="only validate --source")
    parser.add_argument(
        "--exclude-checkpoint-atoms",
        action="store_true",
        help="drop LangGraph checkpoint rows that were incorrectly written into the memory atoms table",
    )
    args = parser.parse_args()
    try:
        if args.check:
            with closing(sqlite3.connect(args.source, timeout=60)) as conn:
                ns = memory_namespace(conn)
            result = validate(args.source, ns)
        else:
            result = repair(
                args.source,
                args.output,
                args.fallback,
                args.exclude_checkpoint_atoms,
            )
    except Exception as exc:
        traceback.print_exc()
        print(f"memory_safe_repair: FAILED: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    import json

    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
