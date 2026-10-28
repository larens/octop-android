"""Encrypt Xiaomi Health connection settings into the Octop secrets table."""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path

from octop.infra.connectors.crypto import encrypt_credentials
from octop.infra.db.pool import SqlitePool
from octop.infra.db.repos.secrets import SecretRepo
from xiaomi_health_sync import CREDENTIALS_KEY


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except json.JSONDecodeError:
        print("expected JSON on stdin", file=sys.stderr)
        return 2
    required = ("xiaomi_user_id", "pass_token", "agent_id", "user_id")
    missing = [key for key in required if not payload.get(key)]
    if missing:
        print(f"missing fields: {', '.join(missing)}", file=sys.stderr)
        return 2
    credentials = {
        "xiaomi_user_id": str(payload["xiaomi_user_id"]),
        "pass_token": str(payload["pass_token"]),
        "region": str(payload.get("region") or "cn"),
        "agent_id": str(payload["agent_id"]),
        "user_id": int(payload["user_id"]),
    }
    db_path = Path("/opt/octop/octop.db")
    pool = SqlitePool(db_path)
    try:
        secret_repo = SecretRepo(pool)
        blob = encrypt_credentials(secret_repo, credentials)
        now = int(time.time())
        with pool.transaction() as conn:
            updated = conn.execute(
                "UPDATE secrets SET v = ?, rotated_at = ? WHERE k = ?",
                (blob, now, CREDENTIALS_KEY),
            )
            if updated.rowcount == 0:
                conn.execute(
                    "INSERT INTO secrets(k, v, created_at) VALUES (?, ?, ?)",
                    (CREDENTIALS_KEY, blob, now),
                )
    finally:
        pool.close()
    print(
        f"configured Xiaomi Health for agent={credentials['agent_id']} "
        f"user={credentials['user_id']}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
