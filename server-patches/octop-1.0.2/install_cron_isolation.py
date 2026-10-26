"""Keep scheduled task executions in cron-only sessions, outside conversation history."""

from __future__ import annotations

from pathlib import Path


path = Path("/opt/octop/venv/lib/python3.12/site-packages/octop/infra/cron/tools.py")
source = path.read_text()
old = """                session_key=session_key,
                enabled=enabled,
"""
new = """                session_key=f"{agent_id}:cron:{cron_id}:dm",
                enabled=enabled,
"""
if new not in source:
    if old not in source:
        raise SystemExit("Octop 1.0.2 cron tools anchor not found")
    path.write_text(source.replace(old, new, 1))
