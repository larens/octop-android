"""Keep scheduled task executions in cron-only sessions, outside conversation history."""

from __future__ import annotations

from patch_paths import octop_site


path = octop_site() / "infra/cron/tools.py"
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
