from __future__ import annotations

import importlib.util
import sys
import types
import unittest
from pathlib import Path


def _install_module(name: str, **attributes: object) -> None:
    module = types.ModuleType(name)
    module.__dict__.update(attributes)
    sys.modules[name] = module


def _load_module():
    octop = types.ModuleType("octop")
    octop.__path__ = []
    sys.modules["octop"] = octop
    for name in ("octop.infra", "octop.infra.db", "octop.infra.db.repos", "octop.infra.today"):
        package = types.ModuleType(name)
        package.__path__ = []
        sys.modules[name] = package
    _install_module("octop.infra.db.pool", DatabasePool=object)
    _install_module("octop.infra.db.repos.agents", AgentRepo=object)
    class Evidence:
        def __init__(self, **values: object) -> None:
            self.__dict__.update(values)

    _install_module(
        "octop.infra.today.cards",
        TodayCardDraft=object,
        TodayCardPublisher=object,
        TodayEvidenceInput=Evidence,
    )

    path = Path(__file__).with_name("cron_today_cards.py")
    spec = importlib.util.spec_from_file_location("cron_today_cards", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


cron_today_cards = _load_module()


class MailSummaryTest(unittest.TestCase):
    def test_mail_summary_keeps_full_detail_but_uses_one_line_overview(self) -> None:
        text = """
昨日（2026-10-27 00:00–23:59 CST）共收到 5 封新邮件，其中待处理私人邮件 0 封。

**【私人邮件 · 待处理】**
昨天没有需要你回复或跟进的事项。

**【私人邮件 · 无需处理】**
1. Taylor Reed（taylor@example.org）｜"Project update: Example Corpus Hub"，10-27 03:51；"Re: Project update: Example Corpus Hub"，10-27 17:12
   原因：对方请求收录一个示例功能；你已授权按 MIT 许可收录并保留署名，对方回信确认。

**【广告邮件】**
共 2 封：Luckin Coffee、Dribbble。

**【通知邮件】**
共 1 封：DeepSeek API 收费提醒。

两点说明：
- 另外提一句。虽然不用处理，但如果你想核对对方项目索引里的署名和许可描述是否准确，可以去公开索引查看一次。需要我盯这件事的话，可以告诉我。
- 范围提醒：本次只覆盖了 QQ 邮箱收件箱。Agent Mail 那个连接器今天报授权失败，返回错误 "check authorization and account quotas"，我没有绕过它，所以那边若有邮件不在这份汇总里。
"""

        summary = cron_today_cards.task_result_summary(
            text,
            task_name="每日 9:00 昨日邮件汇总",
            status="ok",
            error=None,
            kind="inbox",
        )

        self.assertIn("【收件概览】", summary)
        self.assertIn("昨日共收到 5 封新邮件，没有需要回复或跟进的邮件。", summary)
        self.assertNotIn("00:00–23:59", summary)
        self.assertNotIn("【待处理邮件】", summary)
        self.assertIn("\n\n【邮件明细】\n", summary)
        self.assertIn("私人邮件 · 无需处理", summary)
        self.assertIn("Taylor Reed", summary)
        self.assertIn("原因：", summary)
        self.assertIn("广告邮件", summary)
        self.assertIn("通知邮件", summary)
        self.assertIn("\n\n【处理建议】\n", summary)
        self.assertIn("没有需要回复或跟进的邮件", summary)
        self.assertIn("署名和许可描述", summary)
        self.assertIn("\n\n【其他风险】\n", summary)
        self.assertIn("Agent Mail 授权失败", summary)
        self.assertIn("本次仅覆盖 QQ 邮箱收件箱", summary)
        self.assertNotIn("check authorization", summary)
        self.assertNotIn("我没有绕过它", summary)
        self.assertNotIn("•", summary)

    def test_pending_mail_gets_a_highlighted_section_and_sender_evidence(self) -> None:
        text = """
昨日（2026-10-27 00:00–23:59 CST）共收到 3 封新邮件，其中待处理私人邮件 1 封。

**【私人邮件 · 待处理】** 1. Morgan Lee（morgan@example.com）｜"Review request"，10-27 09:20
   原因：需要你在今天下班前确认发布清单。

**【私人邮件 · 无需处理】**
1. Taylor Reed（taylor@example.org）｜"Project update"，10-27 03:51

**【广告邮件】**
共 1 封：Dribbble。
"""

        summary = cron_today_cards.task_result_summary(
            text,
            task_name="每日 9:00 昨日邮件汇总",
            status="ok",
            error=None,
            kind="inbox",
        )
        pending_detail = cron_today_cards._mail_pending_detail(text)
        evidence = cron_today_cards._mail_contact_evidence(
            pending_detail,
            thread_id="thread-1",
            occurred_at=123,
        )

        self.assertIn("昨日共收到 3 封新邮件，其中待处理私人邮件 1 封。", summary)
        self.assertIn("【待处理邮件】", summary)
        self.assertIn("Morgan Lee", summary)
        self.assertIn("今天下班前确认发布清单", summary)
        self.assertIn("私人邮件 · 无需处理", summary)
        self.assertEqual(1, len(evidence))
        self.assertIn("Morgan Lee", evidence[0].title)
        self.assertIn("morgan@example.com", evidence[0].excerpt)

    def test_mail_contact_evidence_highlights_sender_and_topic(self) -> None:
        text = (
            '1. Taylor Reed（taylor@example.org）｜"Project update: Example '
            'Corpus Hub"，10-27 03:51；"Re: Project update: Example Corpus Hub"，10-27 17:12'
        )

        evidence = cron_today_cards._mail_contact_evidence(
            text,
            thread_id="thread-1",
            occurred_at=123,
        )

        self.assertEqual(1, len(evidence))
        self.assertEqual("conversation", evidence[0].source_type)
        self.assertEqual("thread-1", evidence[0].reference)
        self.assertIn("Taylor Reed", evidence[0].title)
        self.assertIn("Project update: Example Corpus Hub", evidence[0].title)
        self.assertIn("taylor@example.org", evidence[0].excerpt)
        self.assertIn("10-27 03:51", evidence[0].excerpt)

    def test_mail_contact_evidence_includes_each_sender(self) -> None:
        text = """
1. Taylor Reed（taylor@example.org）｜"Project update"，10-27 03:51
2. Morgan Lee（morgan@example.com）｜"Review request"，10-27 09:20
"""

        evidence = cron_today_cards._mail_contact_evidence(
            text,
            thread_id="thread-1",
            occurred_at=123,
        )

        self.assertEqual(["Taylor Reed", "Morgan Lee"], [item.title.split("：")[0] for item in evidence])
        self.assertEqual(2, len({item.title for item in evidence}))


if __name__ == "__main__":
    unittest.main()
