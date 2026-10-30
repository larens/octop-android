"""Focused tests for the narrative mobile profile adapter."""

from __future__ import annotations

import tempfile
from dataclasses import dataclass
from pathlib import Path

from octop.infra.memory.mobile_profiles import PROFILE_VERSION, terminal_about_me_with_narrative


@dataclass
class Entity:
    id: str
    entity_type: str
    atom_count: int


class FakeMemory:
    namespace = "agent-test"

    def list_entities(self, *, limit=100):
        assert limit >= 1
        return [Entity(id="entity-user", entity_type="User", atom_count=42)]


class FakeLLM:
    def __init__(self) -> None:
        self.calls = 0

    def call_llm(self, prompt, **_kwargs):
        self.calls += 1
        assert "PREFERRED ADDRESS" in prompt
        assert "示例用户" in prompt
        return """{
          "narrative": "我认识你，是从合成测试资料开始的。\\n\\n你的工作主线一直围绕产品和 AI 展开，比起追逐单点热点，你更像是在持续把想法沉淀成可复用的方法。\\n\\n你收集材料的方式很随手，但背后的意图很稳定：先抓住正在发生的变化，再慢慢整理成判断。\\n\\n和你一起工作时，我大概能理解你为什么总要求先说依据、再做确认。重要的不是速度本身，而是每一步都知道自己在相信什么。",
          "brief": "最近你的主线仍是产品与 AI，正在把零散信号整理成可复用的判断。你持续关注工具变化，也更看重能不能稳定落地。和我协作时，先说依据、写操作前确认，仍是最重要的边界。"
        }"""


def main() -> None:
    memory = FakeMemory()
    payload = {
        "items": [],
        "profile_subject": "示例用户",
        "profile_markdown": (
            "示例用户现居示例城市，长期关注产品与 AI。"
            "\n\n### 协作方式\n- 写操作先确认 (atom-123456-abc)"
        ),
    }
    with tempfile.TemporaryDirectory() as directory:
        cache_dir = Path(directory)
        llm = FakeLLM()
        generated = terminal_about_me_with_narrative(
            memory,
            llm,
            {"limit": 6},
            base_payload=dict(payload),
            cache_dir=cache_dir,
        )
        assert generated["profile_narrative_version"] == PROFILE_VERSION
        assert generated["profile_narrative"].startswith("我认识你")
        assert generated["profile_brief"].startswith("最近你的主线")
        assert "atom-" not in generated["profile_narrative"]
        assert llm.calls == 1

        cached = terminal_about_me_with_narrative(
            memory,
            None,
            {"limit": 6},
            base_payload=dict(payload),
            cache_dir=cache_dir,
        )
        assert cached["profile_narrative"] == generated["profile_narrative"]
        assert cached["profile_brief"] == generated["profile_brief"]
        assert llm.calls == 1

        refreshed = terminal_about_me_with_narrative(
            memory,
            llm,
            {"limit": 6, "refresh": True},
            base_payload=dict(payload),
            cache_dir=cache_dir,
        )
        assert refreshed["profile_narrative_refreshed"] is True
        assert llm.calls == 2

    print("mobile profile tests passed")


if __name__ == "__main__":
    main()
