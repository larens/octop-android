"""Move existing jobs off the dashboard session without creating visible threads."""

from __future__ import annotations

import sqlite3


path = "/opt/octop/octop.db"
with sqlite3.connect(path) as conn:
    rows = conn.execute("SELECT cron_id, agent_id FROM cron_jobs").fetchall()
    for cron_id, agent_id in rows:
        conn.execute(
            "UPDATE cron_jobs SET session_key = ? WHERE cron_id = ?",
            (f"{agent_id}:cron:{cron_id}:dm", cron_id),
        )
print(f"migrated {len(rows)} scheduled tasks")
