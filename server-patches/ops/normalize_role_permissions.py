#!/usr/bin/env python3
"""Drop permission keys unknown to the running Octop build from role templates.

An older build can leave legacy keys in ``user_role.permissions``. The dashboard
returns those keys verbatim, and the create-user form posts them back; the
server then rejects the request with ``unknown permission keys``. Run this after
an upgrade, or whenever ``POST /api/users`` returns 400 for a valid form.

    /opt/octop/venv/bin/python /opt/octop/ops/normalize_role_permissions.py
    /opt/octop/venv/bin/python /opt/octop/ops/normalize_role_permissions.py --apply
"""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
import time
from pathlib import Path

DEFAULT_DB = Path("/opt/octop/octop.db")


def _known_permissions() -> set[str]:
    from octop.infra.users.permissions import PERMISSIONS

    return set(PERMISSIONS)


def _normalize(keys: list[object], known: set[str]) -> list[str]:
    return list(dict.fromkeys(str(key) for key in keys if str(key) in known))


def _load_row_permissions(raw: object) -> list[object] | None:
    if raw is None:
        return []
    try:
        parsed = json.loads(str(raw))
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, list) else None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--db",
        default=os.environ.get("OCTOP_DB_PATH", str(DEFAULT_DB)),
        help="SQLite database path",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Write changes; default is a dry run",
    )
    parser.add_argument(
        "--include-users",
        action="store_true",
        help="Also normalize users.permissions (off by default)",
    )
    args = parser.parse_args()

    db_path = Path(args.db)
    if not db_path.is_file():
        print(f"database not found: {db_path}", file=sys.stderr)
        return 2
    known = _known_permissions()
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    changes: list[tuple[str, str, str, list[str]]] = []
    try:
        for row in conn.execute(
            "SELECT user_role_id, user_role_name, permissions FROM user_role"
        ):
            current = _load_row_permissions(row["permissions"])
            if current is None:
                print(f"skipping role {row['user_role_id']}: invalid JSON")
                continue
            kept = _normalize(current, known)
            if kept != current:
                changes.append(
                    ("role", str(row["user_role_id"]), str(row["user_role_name"]), kept)
                )
                print(
                    f"role {row['user_role_id']} ({row['user_role_name']}): "
                    f"drop {[k for k in current if str(k) not in known]}"
                )
        if args.include_users:
            for row in conn.execute("SELECT id, username, permissions FROM users"):
                current = _load_row_permissions(row["permissions"])
                if current is None:
                    print(f"skipping user {row['username']}: invalid JSON")
                    continue
                kept = _normalize(current, known)
                if kept != current:
                    changes.append(("user", str(row["id"]), str(row["username"]), kept))
                    print(
                        f"user {row['username']}: "
                        f"drop {[k for k in current if str(k) not in known]}"
                    )
        if not changes:
            print("no changes")
            return 0
        if not args.apply:
            print("dry-run; pass --apply to write")
            return 0
        now = int(time.time())
        with conn:
            for kind, ident, name, kept in changes:
                payload = json.dumps(kept, ensure_ascii=False)
                if kind == "role":
                    conn.execute(
                        "UPDATE user_role SET permissions = ?, updated_at = ? "
                        "WHERE user_role_id = ?",
                        (payload, now, ident),
                    )
                else:
                    conn.execute(
                        "UPDATE users SET permissions = ? WHERE id = ?",
                        (payload, int(ident)),
                    )
        print(f"applied {len(changes)} change(s)")
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
