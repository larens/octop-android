#!/usr/bin/env python3
"""Install tool-trace governance and the undeclared-model prompt budget."""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from patch_paths import backups_dir, octop_site


PACK_DIR = Path(__file__).resolve().parent
HARNESS_DIR = octop_site().parent / "octop_harness"


def _replace_once(source: str, old: str, new: str, *, label: str) -> str:
    if old not in source:
        raise RuntimeError(f"{label} anchor not found")
    return source.replace(old, new, 1)


def patch_agent_source(source: str) -> str:
    if "tune_summarization_keep" not in source:
        old = "            mw = harden_summarization_middleware(orig_factory(*args, **kwargs))\n"
        new = old + """\
            from octop_harness.middleware.prompt_budget import tune_summarization_keep

            _summary_model = args[0] if args else kwargs.get("model")
            mw = tune_summarization_keep(mw, model=_summary_model)
"""
        source = _replace_once(source, old, new, label="summarization keep policy")

    if "install_rolling_summary" not in source:
        anchor = "            mw = tune_summarization_keep(mw, model=_summary_model)\n"
        rolling = """\
            from octop_harness.middleware.rolling_summary import install_rolling_summary

            mw = install_rolling_summary(mw)
"""
        source = _replace_once(
            source,
            anchor,
            anchor + rolling,
            label="rolling summary middleware",
        )

    if "install_feed_digest" not in source:
        anchor = (
            "            mw = install_rolling_summary(mw)\n"
            if "install_rolling_summary" in source
            else "            mw = tune_summarization_keep(mw, model=_summary_model)\n"
        )
        feed = """\
            from octop_harness.middleware.feed_governance import install_feed_digest

            mw = install_feed_digest(mw)
"""
        source = _replace_once(
            source,
            anchor,
            anchor + feed,
            label="feed digest middleware",
        )

    if "install_tool_trace_governance" not in source:
        anchor = "            captured.append(mw)\n"
        governance = """\
            from octop_harness.middleware.tool_trace_governance import (
                install_tool_trace_governance,
            )

            _tool_trace_backend = kwargs.get("backend")
            if _tool_trace_backend is None and len(args) > 1:
                _tool_trace_backend = args[1]
            mw = install_tool_trace_governance(mw, backend=_tool_trace_backend)
            captured.append(mw)
"""
        source = _replace_once(source, anchor, governance, label="tool trace governance")
    return source


def patch_turn_aware_source(source: str) -> str:
    if "resolve_prompt_budget" in source:
        return source
    source = _replace_once(
        source,
        "from octop_harness.middleware.turn_model import resolve_turn_model_ref\n",
        "from octop_harness.middleware.prompt_budget import resolve_prompt_budget\n"
        "from octop_harness.middleware.turn_model import resolve_turn_model_ref\n",
        label="turn-aware profile import",
    )
    old = """\
    def _active(self) -> Mapping[str, Any]:
        ref = self._resolve_ref()
        try:
            model = self._factory.get_chat_model(ref)
        except DEFENSIVE_OP_ERRORS:
            logger.debug("turn-aware profile: factory miss for %r", ref, exc_info=True)
            return self
        result: Mapping[str, Any] = self
        profile = getattr(model, "profile", None)
        if profile is self:
            return result
        if isinstance(profile, Mapping):
            mit = profile.get("max_input_tokens") if hasattr(profile, "get") else None
            if isinstance(mit, int) and mit > 0:
                return profile
        return result

    def __getitem__(self, key: str) -> Any:
        active = self._active()
        if active is self:
            return super().__getitem__(key)
        return active[key]

    def get(self, key: str, default: Any = None) -> Any:
        active = self._active()
        if active is self:
            return super().get(key, default)
        return active.get(key, default)

    def __contains__(self, key: object) -> bool:
        active = self._active()
        if active is self:
            return super().__contains__(key)
        return key in active
"""
    new = """\
    def _active_profile(self) -> Mapping[str, Any] | None:
        ref = self._resolve_ref()
        try:
            model = self._factory.get_chat_model(ref)
        except DEFENSIVE_OP_ERRORS:
            logger.debug("turn-aware profile: factory miss for %r", ref, exc_info=True)
            return None
        profile = getattr(model, "profile", None)
        if profile is self or not isinstance(profile, Mapping):
            return None
        return profile

    def _max_input_tokens(self) -> int:
        declared = 0
        active = self._active_profile()
        if active is not None:
            raw = active.get("max_input_tokens") if hasattr(active, "get") else None
            if isinstance(raw, int) and raw > 0:
                declared = raw
            elif hasattr(active, "get"):
                context = active.get("context_window")
                if isinstance(context, int) and context > 0:
                    declared = context
        elif declared <= 0:
            raw = super().get("max_input_tokens")
            if isinstance(raw, int) and raw > 0:
                declared = raw
            else:
                context = super().get("context_window")
                if isinstance(context, int) and context > 0:
                    declared = context
        return resolve_prompt_budget(declared)

    def _active(self) -> Mapping[str, Any]:
        profile = self._active_profile()
        if profile is None:
            return self
        mit = profile.get("max_input_tokens") if hasattr(profile, "get") else None
        if isinstance(mit, int) and mit > 0:
            return profile
        return self

    def __getitem__(self, key: str) -> Any:
        if key == "max_input_tokens":
            value = self._max_input_tokens()
            if value <= 0:
                raise KeyError(key)
            return value
        active = self._active()
        if active is self:
            return super().__getitem__(key)
        return active[key]

    def get(self, key: str, default: Any = None) -> Any:
        if key == "max_input_tokens":
            value = self._max_input_tokens()
            return value if value > 0 else default
        active = self._active()
        if active is self:
            return super().get(key, default)
        return active.get(key, default)

    def __contains__(self, key: object) -> bool:
        if key == "max_input_tokens":
            return self._max_input_tokens() > 0
        active = self._active()
        if active is self:
            return super().__contains__(key)
        return key in active
"""
    return _replace_once(source, old, new, label="turn-aware profile methods")


def patch_turn_model_source(source: str) -> str:
    if "resolve_prompt_budget" in source:
        return source
    source = _replace_once(
        source,
        "from octop_harness.backends.utils import DEFENSIVE_OP_ERRORS\n",
        "from octop_harness.backends.utils import DEFENSIVE_OP_ERRORS\n"
        "from octop_harness.middleware.prompt_budget import resolve_prompt_budget\n",
        label="turn-model import",
    )
    old = """\
    profile = getattr(model, "profile", None)
    if isinstance(profile, Mapping):
        raw = profile.get("max_input_tokens")
        if isinstance(raw, int) and raw > 0:
            return raw
        # TurnAwareProfile may need .get to resolve dynamically
        getter = getattr(profile, "get", None)
        if callable(getter):
            raw = getter("max_input_tokens")
            if isinstance(raw, int) and raw > 0:
                return raw
    return fallback if fallback > 0 else 0
"""
    new = """\
    profile = getattr(model, "profile", None)
    if isinstance(profile, Mapping):
        raw = profile.get("max_input_tokens")
        if isinstance(raw, int) and raw > 0:
            return raw
        # TurnAwareProfile may need .get to resolve dynamically
        getter = getattr(profile, "get", None)
        if callable(getter):
            raw = getter("max_input_tokens")
            if isinstance(raw, int) and raw > 0:
                return raw
    budget = resolve_prompt_budget(0)
    if budget > 0:
        return budget
    return fallback if fallback > 0 else 0
"""
    return _replace_once(source, old, new, label="turn-model max input fallback")


def _copy_module(name: str, *, target_dir: Path, target_name: str | None = None) -> Path:
    source = PACK_DIR / name
    target = target_dir / (target_name or name)
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists() or target.read_bytes() != source.read_bytes():
        shutil.copy2(source, target)
    return target


def main() -> None:
    harness = HARNESS_DIR
    if not harness.is_dir():
        raise RuntimeError(f"octop_harness not found at {harness}")

    targets = {
        harness / "agent.py": patch_agent_source,
        harness / "middleware/turn_aware_profile.py": patch_turn_aware_source,
        harness / "middleware/turn_model.py": patch_turn_model_source,
    }
    originals = {path: path.read_text(encoding="utf-8") for path in targets}
    updated = {path: patcher(originals[path]) for path, patcher in targets.items()}

    backup = backups_dir() / f"conversation-efficiency-{time.strftime('%Y%m%d-%H%M%S')}"
    backup.mkdir(parents=True, exist_ok=True)
    for path in targets:
        shutil.copy2(path, backup / path.name)

    _copy_module("prompt_budget.py", target_dir=harness / "middleware")
    _copy_module("tool_trace_governance.py", target_dir=harness / "middleware")
    _copy_module("rolling_summary.py", target_dir=harness / "middleware")
    _copy_module("feed_governance.py", target_dir=harness / "middleware")
    _copy_module(
        "feed_governance.py",
        target_dir=octop_site() / "infra/conversation",
        target_name="feed.py",
    )

    changed = False
    for path, source in updated.items():
        if source != originals[path]:
            path.write_text(source, encoding="utf-8")
            changed = True
    if not changed:
        print(harness / "middleware/tool_trace_governance.py")
        return
    print(harness / "middleware/tool_trace_governance.py")


if __name__ == "__main__":
    main()
