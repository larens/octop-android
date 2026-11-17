#!/usr/bin/env python3
"""Install the Router butler: dispatch-only tools for the entry agent.

The butler is an ordinary expert agent named by ``router.butler.agent_id``.
When ``router.butler.mode`` is ``assist`` or ``auto`` it keeps only
``agent_list`` / ``ask_agent`` / memory recall / ``current_time``; every other
tool is hidden from the model and rejected at call time. Assignees (Workers)
are untouched.
"""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from patch_paths import backups_dir, octop_site


PACK_DIR = Path(__file__).resolve().parent

TEAM_HOST_CALL = "        applied = self._apply_team_host_config(applied, row)\n"
BUTLER_CALL = "        applied = self._apply_butler_config(applied, row)\n"

METHOD_ANCHOR = (
    "    def _apply_team_host_config(self, cfg: HarnessAgentConfig, row: Any)"
    " -> HarnessAgentConfig:\n"
)

BUTLER_METHODS = '''\
    def _is_butler(self, row: Any) -> bool:
        """True when *row* is the configured Router butler agent."""
        from octop.infra.agents.butler import butler_settings, is_butler_agent

        if row is None:
            return False
        return is_butler_agent(
            str(getattr(row, "agent_id", "") or ""),
            butler_settings(getattr(self._repos, "settings_repo", None)),
        )

    def _apply_butler_config(self, cfg: HarnessAgentConfig, row: Any) -> HarnessAgentConfig:
        """Hide every non-dispatch tool for the butler agent."""
        if not self._is_butler(row):
            return cfg
        from octop.infra.agents.butler import butler_tools_disabled

        updates: dict[str, Any] = {}
        if "tools_disabled" in _HARNESS_AGENT_CONFIG_FIELDS:
            updates["tools_disabled"] = butler_tools_disabled(
                frozenset(getattr(cfg, "tools_disabled", ()) or ())
            )
        if "mcp_server_configs" in _HARNESS_AGENT_CONFIG_FIELDS:
            updates["mcp_server_configs"] = {}
        if "skills_dir" in _HARNESS_AGENT_CONFIG_FIELDS:
            updates["skills_dir"] = None
        if "tools" in _HARNESS_AGENT_CONFIG_FIELDS:
            updates["tools"] = None
        if "subagents_auto_load" in _HARNESS_AGENT_CONFIG_FIELDS:
            updates["subagents_auto_load"] = False
        return replace(cfg, **updates) if updates else cfg

'''

MIDDLEWARE_ANCHOR = "            OctopUiOffloadMiddleware(),\n        ]\n"
MIDDLEWARE_ADDITION = '''\
        if self._is_butler(row):
            from octop.infra.agents.middleware.butler_tools import ButlerToolsMiddleware

            agent_middleware.insert(0, ButlerToolsMiddleware())
'''

MCP_TEAM_ANCHOR = '''\
        # Team hosts only dispatch — never attach MCP tools.
        if is_team_agent(self._agent_manager.get_row(agent_id)):
            return None
'''
MCP_BUTLER_ADDITION = '''\
        # The Router butler only dispatches; MCP tools belong to Workers.
        from octop.infra.agents.butler import butler_settings, is_butler_agent

        settings_repo = getattr(getattr(self._agent_manager, "_repos", None), "settings_repo", None)
        if is_butler_agent(agent_id, butler_settings(settings_repo)):
            return None
'''

MODE_ANCHOR = '''\
        mode, execute_path = self._sync_and_resolve_conversation_mode(
            agent_id, thread_id, meta=meta, user_text=user_text
        )
'''
MODE_ADDITION = '''\
        butler_turn = False
        if mode in ("ask", "plan"):
            from octop.infra.agents.butler import butler_settings, is_butler_agent

            settings_repo = getattr(
                getattr(self._agent_manager, "_repos", None), "settings_repo", None
            )
            if is_butler_agent(agent_id, butler_settings(settings_repo)):
                # The butler never executes directly.  Dispatch must keep
                # working when the composer is in Ask/Plan, so stamp this turn
                # as Craft; the butler tool denylist still limits it to
                # ask_agent and light recall.
                mode = "craft"
                butler_turn = True
'''

MODE_CLEAR_OLD = '''\
        if mode in ("ask", "plan"):
            request.pop("mcp_servers", None)
            request["skills"] = []
'''
MODE_CLEAR_NEW = '''\
        if mode in ("ask", "plan") or butler_turn:
            request.pop("mcp_servers", None)
            request["skills"] = []
'''

SYNC_OLD = (
    "        if is_team_agent(self.get_row(agent_id)):\n"
    "            disabled = set(host_tools_disabled(disabled))\n"
    "        self.sync_tools_disabled(agent_id, disabled)\n"
)
SYNC_NEW = '''\
        row = self.get_row(agent_id)
        if is_team_agent(row):
            disabled = set(host_tools_disabled(disabled))
        elif self._is_butler(row):
            from octop.infra.agents.butler import butler_tools_disabled

            disabled = set(butler_tools_disabled(disabled))
        self.sync_tools_disabled(agent_id, disabled)
'''


def patch_manager_source(source: str) -> str:
    if BUTLER_CALL not in source:
        if TEAM_HOST_CALL not in source:
            raise RuntimeError("Octop 1.0.2 team-host call anchor not found")
        source = source.replace(TEAM_HOST_CALL, TEAM_HOST_CALL + BUTLER_CALL, 1)
    if "def _apply_butler_config(" not in source:
        if METHOD_ANCHOR not in source:
            raise RuntimeError("Octop 1.0.2 team-host method anchor not found")
        source = source.replace(METHOD_ANCHOR, BUTLER_METHODS + METHOD_ANCHOR, 1)
    if "ButlerToolsMiddleware()" not in source:
        if MIDDLEWARE_ANCHOR not in source:
            raise RuntimeError("Octop 1.0.2 middleware list anchor not found")
        source = source.replace(MIDDLEWARE_ANCHOR, MIDDLEWARE_ANCHOR + MIDDLEWARE_ADDITION, 1)
    if "butler_tools_disabled(disabled)" not in source:
        if SYNC_OLD not in source:
            raise RuntimeError("Octop 1.0.2 tools sync anchor not found")
        source = source.replace(SYNC_OLD, SYNC_NEW, 1)
    return source


def patch_processor_source(source: str) -> str:
    if "is_butler_agent(agent_id, butler_settings(settings_repo))" not in source:
        if MCP_TEAM_ANCHOR not in source:
            raise RuntimeError("Octop 1.0.2 processor MCP anchor not found")
        source = source.replace(MCP_TEAM_ANCHOR, MCP_TEAM_ANCHOR + MCP_BUTLER_ADDITION, 1)
    if "butler_turn = False" not in source:
        if MODE_ANCHOR not in source:
            raise RuntimeError("Octop 1.0.2 processor mode anchor not found")
        source = source.replace(MODE_ANCHOR, MODE_ANCHOR + MODE_ADDITION, 1)
    if MODE_CLEAR_OLD in source:
        source = source.replace(MODE_CLEAR_OLD, MODE_CLEAR_NEW, 1)
    elif MODE_CLEAR_NEW not in source:
        raise RuntimeError("Octop 1.0.2 processor mode-clear anchor not found")
    return source


def main() -> None:
    site = octop_site()
    manager_path = site / "infra/agents/manager.py"
    processor_path = site / "infra/gateway/process/processor.py"
    butler_target = site / "infra/agents/butler.py"
    middleware_target = site / "infra/agents/middleware/butler_tools.py"

    backup = backups_dir() / f"butler-dispatch-{time.strftime('%Y%m%d-%H%M%S')}"
    backup.mkdir(parents=True, exist_ok=True)
    shutil.copy2(manager_path, backup / "manager.py")
    shutil.copy2(processor_path, backup / "processor.py")

    shutil.copyfile(PACK_DIR / "butler_policy.py", butler_target)
    shutil.copyfile(PACK_DIR / "butler_tools_middleware.py", middleware_target)

    source = manager_path.read_text(encoding="utf-8")
    manager_path.write_text(patch_manager_source(source), encoding="utf-8")
    source = processor_path.read_text(encoding="utf-8")
    processor_path.write_text(patch_processor_source(source), encoding="utf-8")
    print(processor_path)
    print(manager_path)


if __name__ == "__main__":
    main()
