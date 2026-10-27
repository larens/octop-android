"""Isolate scheduled deliveries and route their output through the dashboard."""

from __future__ import annotations

from pathlib import Path


site = Path("/opt/octop/venv/lib/python3.12/site-packages/octop")
delivery_path = site / "infra/cron/delivery.py"
threads_path = site / "infra/gateway/threads.py"
gateway_path = site / "infra/gateway/gateway.py"

source = delivery_path.read_text()

# The first deployment moved the session creation out of the lock but left the
# temporary string literal. Remove it before matching either anchor so reruns
# are idempotent and do not create two session rows.
legacy_call = '''        await self._gateway.thread_registry.get_or_create_by_key(
            session_key=command.session_key,
            agent_id=command.agent_id,
            user_id=command.user_id,
            channel_type="cron",
        )
'''
if legacy_call in source:
    source = source.replace(legacy_call, "", 1)
source = source.replace(
    '"""Run one delivery under the target channel session lock."""\n\n\n',
    '"""Run one delivery under the target channel session lock."""\n\n',
    1,
)

pristine = '''        async def _locked() -> None:
            if command.fresh_thread:
'''
flawed = '''        async def _locked() -> None:
            await self._gateway.thread_registry.get_or_create_by_key(
                session_key=command.session_key,
                agent_id=command.agent_id,
                user_id=command.user_id,
                channel_type="cron",
            )
            if command.fresh_thread:
'''
fixed = '''        await self._gateway.thread_registry.get_or_create_by_key(
            session_key=command.session_key,
            agent_id=command.agent_id,
            user_id=command.user_id,
            channel_type=ThreadRegistry.CHANNEL_CRON,
        )

        async def _locked() -> None:
            if command.fresh_thread:
'''
if fixed not in source:
    if flawed in source:
        source = source.replace(flawed, fixed, 1)
    elif pristine in source:
        source = source.replace(pristine, fixed, 1)
    else:
        raise SystemExit("Octop 1.0.2 cron delivery anchor not found")

notify_old = '''    async def _notify_best_effort(
        self,
        session: SessionRow,
        agent_id: str,
        text: str,
    ) -> None:
        if session.channel_type != ThreadRegistry.CHANNEL_DASHBOARD:
            return
'''
notify_new = '''    async def _notify_best_effort(
        self,
        session: SessionRow,
        agent_id: str,
        text: str,
    ) -> None:
        if session.channel_type not in {
            ThreadRegistry.CHANNEL_DASHBOARD,
            ThreadRegistry.CHANNEL_CRON,
        }:
            return
'''
if notify_new not in source:
    if notify_old not in source:
        raise SystemExit("Octop 1.0.2 cron notification anchor not found")
    source = source.replace(notify_old, notify_new, 1)
delivery_path.write_text(source)

source = threads_path.read_text()
channel_old = '''    CHANNEL_MCP = "mcp"
    CHAT_TYPE_DM = "dm"
'''
channel_new = '''    CHANNEL_MCP = "mcp"
    CHANNEL_CRON = "cron"
    CHAT_TYPE_DM = "dm"
'''
if "CHANNEL_CRON" not in source:
    if channel_old not in source:
        raise SystemExit("Octop 1.0.2 thread channel anchor not found")
    source = source.replace(channel_old, channel_new, 1)
virtual_old = '''            cls.CHANNEL_MCP,
        }
'''
virtual_new = '''            cls.CHANNEL_MCP,
            cls.CHANNEL_CRON,
        }
'''
if virtual_new not in source:
    if virtual_old not in source:
        raise SystemExit("Octop 1.0.2 virtual channel anchor not found")
    source = source.replace(virtual_old, virtual_new, 1)
threads_path.write_text(source)

source = gateway_path.read_text()
routing_old = '''    def _channel_id_for_session(session: SessionRow) -> str:
        if session.channel_type == ThreadRegistry.CHANNEL_DASHBOARD:
            return WS_CHANNEL_ID
'''
routing_new = '''    def _channel_id_for_session(session: SessionRow) -> str:
        if session.channel_type in {
            ThreadRegistry.CHANNEL_DASHBOARD,
            ThreadRegistry.CHANNEL_CRON,
        }:
            return WS_CHANNEL_ID
'''
if routing_new not in source:
    if routing_old not in source:
        raise SystemExit("Octop 1.0.2 cron channel routing anchor not found")
    source = source.replace(routing_old, routing_new, 1)
    gateway_path.write_text(source)
