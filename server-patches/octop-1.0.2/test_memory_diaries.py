"""Focused tests for the mobile daily diary adapter."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from octop.infra.memory.mobile_diaries import DIARY_VERSION, list_memory_diaries


@dataclass
class Event:
    id: str
    session_id: str
    timestamp: datetime
    event_type: str
    content: str


class FakeMemory:
    def __init__(self) -> None:
        self.events = [
            Event(
                id="u1",
                session_id="thread-memory-diary",
                timestamp=datetime(2026, 10, 29, 2, 0, tzinfo=UTC),
                event_type="user_message",
                content="把记忆页的日记改成一天的工作、学习和智能体成长总结。",
            ),
            Event(
                id="a1",
                session_id="thread-memory-diary",
                timestamp=datetime(2026, 10, 29, 2, 1, tzinfo=UTC),
                event_type="assistant_message",
                content="已检查服务端 Episode 规则，并补上按自然日汇总完整会话的实现。",
            ),
        ]
        self.digests = {}

    def list_raw(self, **kwargs):
        return list(reversed(self.events))

    def list_digests(self, *, period_kind=None, limit=50):
        return sorted(self.digests.values(), key=lambda item: item.period_start, reverse=True)[:limit]

    def upsert_digest(self, digest):
        self.digests[digest.period_key] = digest


class FakeLLM:
    def call_llm(self, prompt, **_kwargs):
        assert "Technical work" in prompt
        return """{
          "diaries": [{
            "date": "2026-10-29",
            "title": "把日记改成真正的每日复盘",
            "summary": "今天的主线是把记忆页从零散 Episode 改成按自然日复盘。\\n\\n你明确了内容应覆盖工作、学习和智能体成长，而不是只保留情绪片段。\\n\\n我检查了生成链路，发现原规则主动排除了技术任务，于是补上完整会话汇总，并保留了旧接口的兜底。",
            "growth": "我学会了先核对生成规则，再判断展示层为何显得单薄。",
            "emotion": "reflective",
            "source_count": 2
          }]
        }"""


def main() -> None:
    memory = FakeMemory()
    result = list_memory_diaries(memory, FakeLLM(), {"days": 7, "refresh": True})
    assert result["generated"] is True
    assert len(result["items"]) == 1
    item = result["items"][0]
    assert item["period_key"] == "2026-10-29"
    assert item["growth"].startswith("我学会了")
    assert memory.digests["2026-10-29"].llm_version == DIARY_VERSION

    cached = list_memory_diaries(memory, None, {"days": 7, "refresh": False})
    assert cached["generated"] is False
    assert cached["items"][0]["title"] == "把日记改成真正的每日复盘"
    print("memory diary tests passed")


if __name__ == "__main__":
    main()
