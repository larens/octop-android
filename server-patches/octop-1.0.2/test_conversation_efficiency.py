"""Tests for conversation efficiency patches."""

from __future__ import annotations

import asyncio
import os
import unittest
from types import SimpleNamespace
from unittest import mock

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from octop_harness.middleware.prompt_budget import (
    DEFAULT_FALLBACK_PROMPT_BUDGET,
    DEFAULT_SUMMARY_KEEP_MESSAGES,
    ENV_PROMPT_BUDGET,
    ENV_SUMMARY_KEEP_MESSAGES,
    tune_summarization_keep,
)
from octop_harness.middleware.tool_trace_governance import (
    ToolTracePolicy,
    acompact_failure_bursts,
    afold_historical_tool_results,
    compact_failure_bursts,
    fold_historical_tool_results,
)
from octop_harness.middleware.turn_aware_profile import TurnAwareProfile
from octop_harness.middleware.turn_model import max_input_tokens_from_model

from install_conversation_efficiency import patch_agent_source


class FakeBackend:
    def __init__(self, *, error: str | None = None) -> None:
        self.files: dict[str, str] = {}
        self.error = error

    def write(self, path: str, content: str) -> SimpleNamespace:
        if self.error:
            return SimpleNamespace(error=self.error, path=None)
        self.files[path] = content
        return SimpleNamespace(error=None, path=path)

    async def awrite(self, path: str, content: str) -> SimpleNamespace:
        return self.write(path, content)


class FakeFactory:
    def __init__(self, profile: dict[str, object] | None) -> None:
        self.profile = profile

    def get_chat_model(self, _ref: str) -> SimpleNamespace:
        return SimpleNamespace(profile=self.profile)


def _pair(
    *,
    call_id: str,
    content: str,
    name: str = "web_fetch",
    status: str = "success",
) -> tuple[AIMessage, ToolMessage]:
    call = {
        "name": name,
        "args": {"url": "https://example.com/article"},
        "id": call_id,
        "type": "tool_call",
    }
    return (
        AIMessage(content="", tool_calls=[call], id=f"ai-{call_id}"),
        ToolMessage(
            content=content,
            tool_call_id=call_id,
            name=name,
            id=f"tool-{call_id}",
            status=status,
        ),
    )


class ToolTraceGovernanceTests(unittest.TestCase):
    def test_historical_large_result_is_offloaded_and_current_turn_is_kept(self) -> None:
        old_ai, old_tool = _pair(call_id="call-old", content="a" * 5_000)
        new_ai, new_tool = _pair(call_id="call-new", content="b" * 5_000)
        messages = [HumanMessage(content="old"), old_ai, old_tool, HumanMessage(content="new"), new_ai, new_tool]
        backend = FakeBackend()

        folded = fold_historical_tool_results(
            messages,
            backend=backend,
            policy=ToolTracePolicy(min_chars=2_000, preview_chars=200),
        )

        self.assertEqual(len(folded), len(messages))
        self.assertIsNot(folded[2], old_tool)
        self.assertIs(folded[5], new_tool)
        self.assertEqual(folded[2].tool_call_id, old_tool.tool_call_id)
        self.assertEqual(folded[2].id, old_tool.id)
        self.assertIn("/large_tool_results/call-old", folded[2].content)
        self.assertIn("tool: web_fetch", folded[2].content)
        self.assertEqual(backend.files["/large_tool_results/call-old"], old_tool.content)

    def test_second_pass_does_not_rewrite_or_change_the_stub(self) -> None:
        ai, tool = _pair(call_id="call-old", content="a" * 5_000)
        messages = [HumanMessage(content="old"), ai, tool, HumanMessage(content="new")]
        backend = FakeBackend()
        policy = ToolTracePolicy(min_chars=2_000, preview_chars=120)

        first = fold_historical_tool_results(messages, backend=backend, policy=policy)
        writes_after_first = dict(backend.files)
        second = fold_historical_tool_results(first, backend=backend, policy=policy)

        self.assertEqual(len(backend.files), 1)
        self.assertEqual(backend.files, writes_after_first)
        self.assertEqual(second[2].content, first[2].content)

    def test_small_historical_result_stays_verbatim(self) -> None:
        ai, tool = _pair(call_id="call-small", content="ok")
        messages = [HumanMessage(content="old"), ai, tool, HumanMessage(content="new")]
        folded = fold_historical_tool_results(
            messages,
            backend=FakeBackend(),
            policy=ToolTracePolicy(min_chars=2_000),
        )
        self.assertIs(folded[2], tool)

    def test_offload_failure_degrades_to_pointer_without_raising(self) -> None:
        ai, tool = _pair(call_id="call-old", content="a" * 5_000)
        messages = [HumanMessage(content="old"), ai, tool, HumanMessage(content="new")]
        folded = fold_historical_tool_results(
            messages,
            backend=FakeBackend(error="disk full"),
            policy=ToolTracePolicy(min_chars=2_000, preview_chars=80),
        )
        self.assertIn("artifact: unavailable", folded[2].content)
        self.assertIn("characters: 5000", folded[2].content)

    def test_async_folding_matches_sync_behavior(self) -> None:
        ai, tool = _pair(call_id="call-old", content="a" * 5_000)
        messages = [HumanMessage(content="old"), ai, tool, HumanMessage(content="new")]
        backend = FakeBackend()
        folded = asyncio.run(
            afold_historical_tool_results(
                messages,
                backend=backend,
                policy=ToolTracePolicy(min_chars=2_000, preview_chars=120),
            )
        )
        self.assertIn("/large_tool_results/call-old", folded[2].content)
        self.assertEqual(backend.files["/large_tool_results/call-old"], tool.content)

    def test_summary_message_is_not_a_user_turn_boundary(self) -> None:
        old_ai, old_tool = _pair(call_id="call-old", content="a" * 5_000)
        kept_ai, kept_tool = _pair(call_id="call-kept", content="b" * 5_000)
        summary = HumanMessage(
            content="summarized history",
            additional_kwargs={"lc_source": "summarization"},
        )
        messages = [HumanMessage(content="old"), old_ai, old_tool, summary, kept_ai, kept_tool]
        folded = fold_historical_tool_results(
            messages,
            backend=FakeBackend(),
            policy=ToolTracePolicy(min_chars=2_000, preview_chars=80),
        )
        self.assertIsNot(folded[2], old_tool)
        self.assertIsNot(folded[5], kept_tool)

    def test_disabled_policy_returns_original_messages(self) -> None:
        ai, tool = _pair(call_id="call-old", content="a" * 5_000)
        messages = [HumanMessage(content="old"), ai, tool, HumanMessage(content="new")]
        folded = fold_historical_tool_results(
            messages,
            backend=FakeBackend(),
            policy=ToolTracePolicy(enabled=False),
        )
        self.assertEqual(folded, messages)

    def test_consecutive_failures_collapse_to_one_capsule(self) -> None:
        messages = [HumanMessage(content="old")]
        for number in range(4):
            messages.extend(
                _pair(
                    call_id=f"call-fail-{number}",
                    content=f"Error invoking tool with kwargs {{'attempt': {number}}}",
                    name="feishu-cli im",
                    status="error",
                )
            )
        messages.append(HumanMessage(content="new"))
        backend = FakeBackend()

        compacted = compact_failure_bursts(
            messages,
            backend=backend,
            policy=ToolTracePolicy(min_failure_burst=4, preview_chars=120),
        )

        self.assertEqual(len(compacted), 4)
        self.assertEqual(compacted[2].tool_call_id, "call-fail-0")
        self.assertEqual(compacted[2].id, "tool-call-fail-0")
        self.assertIn("historical tool failure capsule", compacted[2].content)
        self.assertIn("failures: 4 consecutive", compacted[2].content)
        self.assertEqual(
            compacted[2].additional_kwargs["octop_tool_trace"]["removed_tool_call_ids"],
            ["call-fail-1", "call-fail-2", "call-fail-3"],
        )
        self.assertEqual(len(backend.files), 1)
        artifact = next(iter(backend.files.values()))
        self.assertIn("## failure 4", artifact)
        self.assertIn("attempt': 3", artifact)

    def test_failure_burst_stops_at_success_or_different_error(self) -> None:
        messages = [HumanMessage(content="old")]
        for number in range(2):
            messages.extend(
                _pair(
                    call_id=f"call-fail-{number}",
                    content="Error invoking tool with kwargs {'attempt': 1}",
                    name="feishu-cli im",
                    status="error",
                )
            )
        messages.extend(
            _pair(
                call_id="call-ok",
                content="ok",
                name="feishu-cli im",
            )
        )
        for number in range(2, 4):
            messages.extend(
                _pair(
                    call_id=f"call-fail-{number}",
                    content="Error invoking tool with kwargs {'attempt': 1}",
                    name="feishu-cli im",
                    status="error",
                )
            )
        messages.append(HumanMessage(content="new"))

        compacted = compact_failure_bursts(
            messages,
            backend=FakeBackend(),
            policy=ToolTracePolicy(min_failure_burst=4),
        )

        self.assertEqual(compacted, messages)

    def test_current_turn_failures_are_not_collapsed(self) -> None:
        messages = [HumanMessage(content="old"), HumanMessage(content="new")]
        for number in range(4):
            messages.extend(
                _pair(
                    call_id=f"call-fail-{number}",
                    content="Error invoking tool",
                    name="feishu-cli im",
                    status="error",
                )
            )

        compacted = compact_failure_bursts(
            messages,
            backend=FakeBackend(),
            policy=ToolTracePolicy(min_failure_burst=4),
        )

        self.assertEqual(compacted, messages)

    def test_async_failure_capsule_matches_sync_behavior(self) -> None:
        messages = [HumanMessage(content="old")]
        for number in range(4):
            messages.extend(
                _pair(
                    call_id=f"call-fail-{number}",
                    content="Error invoking tool",
                    name="feishu-cli im",
                    status="error",
                )
            )
        messages.append(HumanMessage(content="new"))
        backend = FakeBackend()

        compacted = asyncio.run(
            acompact_failure_bursts(
                messages,
                backend=backend,
                policy=ToolTracePolicy(min_failure_burst=4),
            )
        )

        self.assertEqual(len(compacted), 4)
        self.assertIn("failures: 4 consecutive", compacted[2].content)
        self.assertEqual(len(backend.files), 1)


class PromptBudgetTests(unittest.TestCase):
    def test_undeclared_model_uses_conservative_fallback(self) -> None:
        profile = TurnAwareProfile(
            {"max_input_tokens": 1_000_000},
            factory=FakeFactory({}),
            pick_default_ref=lambda: "Xiaomi MiMo/mimo-v2.6-flash",
        )
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop(ENV_PROMPT_BUDGET, None)
            self.assertEqual(profile["max_input_tokens"], DEFAULT_FALLBACK_PROMPT_BUDGET)

    def test_declared_model_limit_wins(self) -> None:
        profile = TurnAwareProfile(
            {"max_input_tokens": 1_000_000},
            factory=FakeFactory({"max_input_tokens": 128_000}),
            pick_default_ref=lambda: "Xiaomi MiMo/mimo-v2.6-flash",
        )
        self.assertEqual(profile["max_input_tokens"], 128_000)

    def test_env_override_is_used_for_undeclared_model(self) -> None:
        profile = TurnAwareProfile(
            {"max_input_tokens": 1_000_000},
            factory=FakeFactory({}),
            pick_default_ref=lambda: "Xiaomi MiMo/mimo-v2.6-flash",
        )
        with mock.patch.dict(os.environ, {ENV_PROMPT_BUDGET: "72000"}, clear=False):
            self.assertEqual(profile["max_input_tokens"], 72_000)

    def test_turn_model_reader_falls_back_for_undeclared_model(self) -> None:
        model = SimpleNamespace(profile={})
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop(ENV_PROMPT_BUDGET, None)
            self.assertEqual(
                max_input_tokens_from_model(model, fallback=1_000_000),
                DEFAULT_FALLBACK_PROMPT_BUDGET,
            )

    def test_summary_keep_uses_messages_for_undeclared_model(self) -> None:
        helper = SimpleNamespace(keep=("fraction", 0.10))
        middleware = SimpleNamespace(_lc_helper=helper)
        model = SimpleNamespace(profile={})

        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop(ENV_PROMPT_BUDGET, None)
            os.environ.pop(ENV_SUMMARY_KEEP_MESSAGES, None)
            tune_summarization_keep(middleware, model=model)

        self.assertEqual(helper.keep, ("messages", DEFAULT_SUMMARY_KEEP_MESSAGES))
        self.assertEqual(
            middleware._octop_summary_keep_messages,
            DEFAULT_SUMMARY_KEEP_MESSAGES,
        )

    def test_summary_keep_does_not_override_declared_model_window(self) -> None:
        helper = SimpleNamespace(keep=("fraction", 0.10))
        middleware = SimpleNamespace(_lc_helper=helper)
        model = SimpleNamespace(profile={"max_input_tokens": 128_000})

        tune_summarization_keep(middleware, model=model)

        self.assertEqual(helper.keep, ("fraction", 0.10))

    def test_summary_keep_preserves_explicit_message_window(self) -> None:
        helper = SimpleNamespace(keep=("messages", 20))
        middleware = SimpleNamespace(_lc_helper=helper)
        model = SimpleNamespace(profile={})

        tune_summarization_keep(middleware, model=model)

        self.assertEqual(helper.keep, ("messages", 20))


class InstallerPatchTests(unittest.TestCase):
    def test_fresh_source_gets_keep_and_tool_trace_governance(self) -> None:
        source = (
            "            mw = harden_summarization_middleware(orig_factory(*args, **kwargs))\n"
            "            captured.append(mw)\n"
        )

        patched = patch_agent_source(source)

        self.assertIn("tune_summarization_keep", patched)
        self.assertIn("install_tool_trace_governance", patched)
        self.assertEqual(patched.count("install_tool_trace_governance"), 2)

    def test_existing_governance_patch_gets_keep_policy(self) -> None:
        source = (
            "            mw = harden_summarization_middleware(orig_factory(*args, **kwargs))\n"
            "            from octop_harness.middleware.tool_trace_governance import (\n"
            "                install_tool_trace_governance,\n"
            "            )\n"
            "\n"
            "            _tool_trace_backend = kwargs.get(\"backend\")\n"
            "            if _tool_trace_backend is None and len(args) > 1:\n"
            "                _tool_trace_backend = args[1]\n"
            "            mw = install_tool_trace_governance(mw, backend=_tool_trace_backend)\n"
            "            captured.append(mw)\n"
        )

        patched = patch_agent_source(source)

        self.assertEqual(patched, patch_agent_source(patched))
        self.assertEqual(patched.count("install_tool_trace_governance"), 2)
        self.assertLess(
            patched.index("tune_summarization_keep"),
            patched.index("install_tool_trace_governance"),
        )

    def test_partial_context_governance_is_completed_independently(self) -> None:
        rolling = (
            "            mw = tune_summarization_keep(mw, model=_summary_model)\n"
            "            from octop_harness.middleware.rolling_summary import install_rolling_summary\n"
            "\n"
            "            mw = install_rolling_summary(mw)\n"
            "            captured.append(mw)\n"
        )
        feed = (
            "            mw = tune_summarization_keep(mw, model=_summary_model)\n"
            "            from octop_harness.middleware.feed_governance import install_feed_digest\n"
            "\n"
            "            mw = install_feed_digest(mw)\n"
            "            captured.append(mw)\n"
        )

        for source in (rolling, feed):
            patched = patch_agent_source(source)

            self.assertEqual(patched.count("mw = install_rolling_summary(mw)"), 1)
            self.assertEqual(patched.count("mw = install_feed_digest(mw)"), 1)
            self.assertLess(
                patched.index("mw = install_rolling_summary(mw)"),
                patched.index("mw = install_feed_digest(mw)"),
            )
            self.assertEqual(patched, patch_agent_source(patched))


if __name__ == "__main__":
    unittest.main()
