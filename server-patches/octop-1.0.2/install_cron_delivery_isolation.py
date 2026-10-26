"""Create a missing dedicated cron session before each scheduled delivery."""

from __future__ import annotations

from pathlib import Path


path = Path("/opt/octop/venv/lib/python3.12/site-packages/octop/infra/cron/delivery.py")
source = path.read_text()
old = '''        async def _locked() -> None:
            if command.fresh_thread:
'''
new = '''        async def _locked() -> None:
            await self._gateway.thread_registry.get_or_create_by_key(
                session_key=command.session_key,
                agent_id=command.agent_id,
                user_id=command.user_id,
                channel_type="cron",
            )
            if command.fresh_thread:
'''
if new not in source:
    if old not in source:
        raise SystemExit("Octop 1.0.2 cron delivery anchor not found")
    path.write_text(source.replace(old, new, 1))
