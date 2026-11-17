"""Tests for the Router butler policy and its tool boundary."""

from __future__ import annotations

import asyncio
import json
import os
import unittest
from types import SimpleNamespace

from langchain_core.messages import SystemMessage
from octop.infra.agents.butler import (
    BUTLER_PROMPT,
    BUTLER_TOOLS_ALLOWED,
    butler_settings,
    butler_system_prompt,
    butler_tools_disabled,
    is_butler_agent,
)
from octop.infra.agents.middleware.butler_tools import ButlerToolsMiddleware
from install_butler_dispatch import (
    MCP_TEAM_ANCHOR,
    MODE_ANCHOR,
    MODE_CLEAR_OLD,
    patch_processor_source,
)
from router_phase0_shadow import _maybe_propose_auto_plan


class _Settings:
    def __init__(self, values: dict[str, str]) -> None:
        self._values = values

    def get(self, key: str, default: object = None) -> object:
        return self._values.get(key, default)


def _request(name: str) -> SimpleNamespace:
    return SimpleNamespace(tool_call={"name": name, "id": f"call-{name}"})


class ButlerPolicyTest(unittest.TestCase):
    def test_mode_and_agent_gate(self) -> None:
        settings = {"mode": "assist", "agent_id": "agent-butler", "experts": {}}

        self.assertTrue(is_butler_agent("agent-butler", settings))
        self.assertFalse(is_butler_agent("agent-expert", settings))
        self.assertFalse(is_butler_agent("agent-butler", {**settings, "mode": "off"}))
        self.assertFalse(is_butler_agent("agent-butler", {**settings, "agent_id": ""}))

    def test_settings_parse_experts_json(self) -> None:
        settings = butler_settings(
            _Settings(
                {
                    "router.butler.mode": "AUTO",
                    "router.butler.agent_id": "agent-butler",
                    "router.butler.experts": json.dumps(
                        {"research": "agent-research", " ": "ignored"}
                    ),
                }
            )
        )

        self.assertEqual(settings["mode"], "auto")
        self.assertEqual(settings["experts"], {"research": "agent-research"})

    def test_invalid_mode_and_experts_fall_back(self) -> None:
        settings = butler_settings(
            _Settings({"router.butler.mode": "wild", "router.butler.experts": "not-json"})
        )

        self.assertEqual(settings["mode"], "off")
        self.assertEqual(settings["experts"], {})

    def test_tools_disabled_keeps_only_dispatch(self) -> None:
        disabled = butler_tools_disabled()

        self.assertIn("write_file", disabled)
        self.assertIn("execute", disabled)
        self.assertIn("read_file", disabled)
        self.assertIn("browser_use", disabled)
        self.assertIn("send_file_to_user", disabled)
        for name in BUTLER_TOOLS_ALLOWED:
            self.assertNotIn(name, disabled)

    def test_prompt_prepends_contract(self) -> None:
        prompt = butler_system_prompt("你叫小八。")

        self.assertIn("butler", prompt)
        self.assertTrue(prompt.endswith("你叫小八。"))
        self.assertIn("butler", butler_system_prompt(None))


class ButlerToolsMiddlewareTest(unittest.TestCase):
    def _run(self, name: str) -> tuple[bool, object]:
        called = False

        async def handler(request: object) -> object:
            nonlocal called
            called = True
            return "handled"

        result = asyncio.run(ButlerToolsMiddleware().awrap_tool_call(_request(name), handler))
        return called, result

    def test_allows_dispatch(self) -> None:
        called, result = self._run("ask_agent")

        self.assertTrue(called)
        self.assertEqual(result, "handled")

    def test_blocks_execution(self) -> None:
        called, result = self._run("execute")

        self.assertFalse(called)
        self.assertEqual(getattr(result, "status", None), "error")
        self.assertIn("ask_agent", str(getattr(result, "content", "")))

    def test_model_call_injects_dispatch_contract_once(self) -> None:
        middleware = ButlerToolsMiddleware()
        request = SimpleNamespace(system_message=None)
        request.override = lambda **kwargs: SimpleNamespace(**kwargs)

        injected = middleware._with_contract(request)

        self.assertIn("butler", str(injected.system_message.content))
        existing = SimpleNamespace(system_message=SystemMessage(content=BUTLER_PROMPT))
        self.assertIs(middleware._with_contract(existing), existing)


class ButlerProcessorPatchTest(unittest.TestCase):
    def test_butler_turns_escape_ask_and_plan_modes_idempotently(self) -> None:
        source = f"class Processor:\n{MCP_TEAM_ANCHOR}{MODE_ANCHOR}{MODE_CLEAR_OLD}"

        patched = patch_processor_source(source)

        self.assertIn("butler_turn = False", patched)
        self.assertIn('mode = "craft"', patched)
        self.assertIn('if mode in ("ask", "plan") or butler_turn:', patched)
        self.assertEqual(patch_processor_source(patched), patched)


class _Worker:
    ready = True

    def __init__(self) -> None:
        self.objectives: list[str] = []

    async def propose_dispatch_plan_from_observation(
        self, *, observed: object, objective: str
    ) -> None:
        self.objectives.append(objective)


class ButlerAutoPlanGateTest(unittest.IsolatedAsyncioTestCase):
    _ENV_KEYS = (
        "OCTOP_ROUTER_BUTLER_MODE",
        "OCTOP_ROUTER_BUTLER_AGENT_ID",
        "OCTOP_ROUTER_PLANNING_AUTO_ENABLED",
    )

    def setUp(self) -> None:
        self._saved = {key: os.environ.pop(key, None) for key in self._ENV_KEYS}

    def tearDown(self) -> None:
        for key, value in self._saved.items():
            if value is not None:
                os.environ[key] = value

    async def _propose(self, values: dict[str, str], observed_agent_id: str) -> list[str]:
        worker = _Worker()
        server = SimpleNamespace(
            app_runtime=SimpleNamespace(router_worker_service=worker),
            services=SimpleNamespace(settings_repo=_Settings(values)),
        )
        await _maybe_propose_auto_plan(
            server,
            observed={
                "agent_id": observed_agent_id,
                "decision": {"intent": "read_only_task", "confidence": 0.9},
            },
            text="帮我对比两个方案并总结",
            has_attachments=False,
            target_agent_ids=(),
        )
        return worker.objectives

    async def test_only_the_configured_butler_proposes(self) -> None:
        values = {"router.butler.mode": "assist", "router.butler.agent_id": "agent-butler"}

        self.assertEqual(len(await self._propose(values, "agent-butler")), 1)
        self.assertEqual(await self._propose(values, "agent-expert"), [])

    async def test_mode_off_disables_proposals(self) -> None:
        values = {"router.butler.mode": "off", "router.butler.agent_id": "agent-butler"}

        self.assertEqual(await self._propose(values, "agent-butler"), [])

    async def test_legacy_planning_flag_still_works(self) -> None:
        self.assertEqual(
            len(await self._propose({"router.planning.auto_enabled": "1"}, "agent-expert")),
            1,
        )


if __name__ == "__main__":
    unittest.main()
