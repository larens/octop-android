#!/usr/bin/env python3
"""Build an isolated candidate data home from a production Octop home."""

from __future__ import annotations

import argparse
import json
import os
import secrets
import shutil
import sqlite3
from pathlib import Path
from typing import Any

from argon2 import PasswordHasher


COPY_DIRS = (
    "agents",
    "browser-profiles",
    "connector-cli",
    "desktop",
    "expert_market",
    "plugins",
    "security",
    "skill-packages",
    "state",
    "xiaomi_health",
)


def _copy_tree(source: Path, target: Path, *, ignore: Any = None) -> None:
    if source.is_dir():
        # Never follow the managed-release links in state/bluegreen into a copy.
        shutil.copytree(source, target, dirs_exist_ok=True, symlinks=True, ignore=ignore)
    elif source.exists():
        shutil.copy2(source, target)


def _backup_database(source: Path, target: Path) -> None:
    src = sqlite3.connect(f"file:{source}?mode=ro", uri=True)
    dst = sqlite3.connect(target)
    try:
        src.backup(dst)
    finally:
        dst.close()
        src.close()


def _disable_side_effects(db_path: Path) -> str:
    with sqlite3.connect(db_path) as conn:
        active_agent = conn.execute(
            "SELECT a.agent_id "
            "FROM agents AS a "
            "LEFT JOIN today_cards AS t ON t.agent_id = a.agent_id "
            "WHERE a.enabled = 1 "
            "GROUP BY a.agent_id, a.id "
            "ORDER BY COUNT(t.agent_id) DESC, a.id "
            "LIMIT 1"
        ).fetchone()
        if active_agent is None:
            raise RuntimeError("no enabled agent found in candidate database")
        conn.execute(
            "UPDATE agents SET enabled = CASE WHEN agent_id = ? THEN 1 ELSE 0 END",
            (active_agent[0],),
        )
        conn.execute("UPDATE channels SET enabled = 0")
        conn.execute("UPDATE cron_jobs SET enabled = 0")
        for table in ("interest_monitors", "proactive_care_config"):
            columns = {row[1] for row in conn.execute(f'PRAGMA table_info("{table}")')}
            if "enabled" in columns:
                conn.execute(f'UPDATE "{table}" SET enabled = 0')
        conn.commit()
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    return str(active_agent[0])


def _create_test_user(db_path: Path, agent_id: str) -> dict[str, str]:
    password = "Candidate-" + secrets.token_urlsafe(18)
    with sqlite3.connect(db_path) as conn:
        row = conn.execute(
            "SELECT u.username "
            "FROM users AS u "
            "LEFT JOIN today_cards AS t ON t.user_id = u.id "
            "WHERE u.disabled = 0 "
            "GROUP BY u.id, u.username "
            "ORDER BY COUNT(t.user_id) DESC, u.id "
            "LIMIT 1"
        ).fetchone()
        if row is None:
            raise RuntimeError("no enabled user found in candidate database")
        username = str(row[0])
        conn.execute(
            "UPDATE users SET password_hash = ?, login_failed_count = 0, login_locked_until = 0 "
            "WHERE username = ?",
            (PasswordHasher().hash(password), username),
        )
        conn.commit()
    return {"username": username, "password": password, "agent_id": agent_id}


def prepare(production_home: Path, candidate_home: Path, port: int) -> dict[str, object]:
    if candidate_home.exists():
        shutil.rmtree(candidate_home)
    candidate_home.mkdir(parents=True, mode=0o700)

    config = json.loads((production_home / "config.json").read_text(encoding="utf-8"))
    config["bind_host"] = "127.0.0.1"
    config["port"] = port
    tls = config.setdefault("tls", {})
    tls["enabled"] = False
    (candidate_home / "config.json").write_text(
        json.dumps(config, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    for name in COPY_DIRS:
        source = production_home / name
        if source.exists():
            ignore = (
                shutil.ignore_patterns("bluegreen")
                if name == "state"
                else None
            )
            _copy_tree(source, candidate_home / name, ignore=ignore)

    for name in ("credential.txt", "memory-slim-control.json"):
        source = production_home / name
        if source.exists():
            shutil.copy2(source, candidate_home / name)

    embedding_models = production_home / "embedding_models"
    if embedding_models.exists():
        (candidate_home / "embedding_models").symlink_to(embedding_models, target_is_directory=True)

    (candidate_home / "logs").mkdir(mode=0o700, exist_ok=True)
    (candidate_home / "backups").mkdir(mode=0o700, exist_ok=True)
    (candidate_home / "state/mobile-profiles").mkdir(parents=True, exist_ok=True)
    (candidate_home / "octop.db-wal").unlink(missing_ok=True)
    (candidate_home / "octop.db-shm").unlink(missing_ok=True)
    _backup_database(production_home / "octop.db", candidate_home / "octop.db")
    active_agent_id = _disable_side_effects(candidate_home / "octop.db")
    credentials = _create_test_user(candidate_home / "octop.db", active_agent_id)
    (candidate_home / "candidate-test.json").write_text(
        json.dumps(credentials, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    os.chmod(candidate_home / "candidate-test.json", 0o600)

    with sqlite3.connect(f"file:{candidate_home / 'octop.db'}?mode=ro", uri=True) as conn:
        return {
            "home": str(candidate_home),
            "port": port,
            "quick_check": conn.execute("PRAGMA quick_check").fetchone()[0],
            "schema_version": conn.execute("SELECT version FROM _schema_version").fetchone()[0],
            "users": conn.execute("SELECT COUNT(*) FROM users").fetchone()[0],
            "agents": conn.execute("SELECT COUNT(*) FROM agents").fetchone()[0],
        }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--production-home", type=Path, default=Path("/opt/octop"))
    parser.add_argument("--candidate-home", type=Path, required=True)
    parser.add_argument("--port", type=int, default=18088)
    args = parser.parse_args()
    result = prepare(args.production_home, args.candidate_home, args.port)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
