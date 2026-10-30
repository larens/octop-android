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


class CronResultFreshnessTest(unittest.TestCase):
    def test_failed_or_empty_runs_do_not_produce_today_content(self) -> None:
        for text in (
            "",
            "执行失败：上游服务不可用",
            "模型调用失败：file is not a database",
            "Internal Server Error",
            "Unable to fetch new information",
            "暂无新信息",
        ):
            with self.subTest(text=text):
                self.assertFalse(
                    cron_today_cards.cron_result_has_new_information(
                        text,
                        task_kind="weather",
                    )
                )

    def test_zero_result_is_still_new_information(self) -> None:
        self.assertTrue(
            cron_today_cards.cron_result_has_new_information(
                "昨日共收到 0 封新邮件，没有需要回复或跟进的事项。",
                task_kind="inbox",
            )
        )

    def test_failed_run_has_no_fallback_summary(self) -> None:
        self.assertEqual(
            "",
            cron_today_cards.task_result_summary(
                "上一轮的有效结果",
                task_name="晨间天气与日程",
                status="error",
                error="服务器暂时不可用",
                kind="weather",
            ),
        )


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


class WeatherSummaryTest(unittest.TestCase):
    def test_morning_weather_is_subordinate_to_today_arrangements(self) -> None:
        text = """
【今日天气】
北京，晴转小雨，6~15°C，降雨概率 60%。

【日程与准备】
09:30 产品评审，需要准备原型和上一轮结论。

【处理建议】
今天有雨，带伞。早晚偏冷，多穿一件。
"""

        summary = cron_today_cards.task_result_summary(
            text,
            task_name="晨间天气与日程",
            status="ok",
            error=None,
            kind="weather",
        )

        self.assertIn("【今日安排】", summary)
        self.assertIn("【天气提醒】", summary)
        self.assertIn("【行动建议】", summary)
        self.assertIn("北京，晴转小雨", summary)
        self.assertIn("09:30 产品评审", summary)
        self.assertIn("带伞", summary)
        self.assertLess(summary.index("【今日安排】"), summary.index("【天气提醒】"))
        self.assertLess(summary.index("【天气提醒】"), summary.index("【行动建议】"))
        self.assertLessEqual(len(summary), 1000)

    def test_weather_card_title_describes_the_daily_plan_not_weather(self) -> None:
        self.assertEqual(
            "今日安排与建议",
            cron_today_cards.cron_card_title(
                "weather",
                "晨间天气与日程",
                "【今日安排】09:30 产品评审\n【天气提醒】有雨，带伞。",
            ),
        )

    def test_weather_and_schedule_are_classified_as_weather(self) -> None:
        self.assertEqual(
            "weather",
            cron_today_cards.classify_cron_kind(
                "晨间天气与日程",
                "先用 get_weather 查询天气，再整理今天的会议与日程",
            ),
        )

    def test_evening_weather_task_keeps_tomorrow_preparation(self) -> None:
        text = """
【明日天气】
北京，晴，6.8~19.4°C，降雨概率 0%。

【明日准备】
不用放折叠伞；早晚偏冷，备一件保暖衣物。

【处理建议】
明早按 6.8°C 穿着，出门不要只穿单件。
"""

        summary = cron_today_cards.task_result_summary(
            text,
            task_name="夜间天气与明日准备",
            status="ok",
            error=None,
            kind="weather",
        )

        self.assertIn("【明日安排】", summary)
        self.assertIn("【天气与穿着】", summary)
        self.assertIn("【今晚准备】", summary)
        self.assertIn("降雨概率 0%", summary)
        self.assertIn("不用放折叠伞", summary)
        self.assertIn("不要只穿单件", summary)
        self.assertLess(summary.index("【明日安排】"), summary.index("【天气与穿着】"))

    def test_evening_weather_card_title_is_forward_looking(self) -> None:
        self.assertEqual(
            "明日安排与准备",
            cron_today_cards.cron_card_title(
                "weather",
                "夜间天气与明日准备",
                "【明日安排】明早 09:00 评审\n【天气与穿着】有雨。",
            ),
        )

    def test_weather_bullets_are_kept_out_of_the_primary_arrangement(self) -> None:
        text = """
【明日安排】
- 折叠伞：不用放书包，降雨概率 0%。
- 09:30 产品评审，需要准备原型。

【天气与穿着】
北京，晴，6.8~19.4°C。

【今晚准备】
明早多穿一件。
"""

        summary = cron_today_cards.task_result_summary(
            text,
            task_name="夜间天气与明日准备",
            status="ok",
            error=None,
            kind="weather",
        )
        arrangement = summary.split("【天气与穿着】", 1)[0]

        self.assertIn("09:30 产品评审", arrangement)
        self.assertNotIn("折叠伞", arrangement)
        self.assertIn("折叠伞", summary)

    def test_weather_does_not_dismiss_automatic_news_cards(self) -> None:
        self.assertNotIn("weather", cron_today_cards._AUTO_KIND_BY_TASK_KIND)
        self.assertEqual({"news": "news", "inbox": "mail"}, cron_today_cards._AUTO_KIND_BY_TASK_KIND)


class IndustrySignalSummaryTest(unittest.TestCase):
    def test_signal_is_classified_before_news_and_keeps_a_stable_title(self) -> None:
        self.assertEqual(
            "signal",
            cron_today_cards.classify_cron_kind(
                "AI 与科技行业信号",
                "整理今天值得关注的 AI 新闻与头部科技公司动态",
            ),
        )
        self.assertEqual(
            "signal",
            cron_today_cards.classify_cron_kind(
                "Afternoon AI and tech signals",
                "Review leading AI and technology companies",
            ),
        )
        self.assertEqual(
            "AI 与科技行业信号",
            cron_today_cards.cron_card_title(
                "signal",
                "AI 与科技行业信号",
                "【关键信号】1. OpenAI｜GPT-6 Astra Pro",
            ),
        )

    def test_signal_summary_keeps_the_cross_event_judgment_and_comparison(self) -> None:
        text = """
## 今日判断

**【总判断】**
形态已经清楚了，数字还只有发布方口径。

**【关键信号】**
1. OpenAI｜GPT-6 Astra Pro
成熟度：可验证进展
证据强度：3/3
核心变化：同一任务拆给多个 agent 并行推进，过程实时可见。
不确定性：Pro 档仍在灰度。
对我的意义：今天就能对照现有交付流程试一次。
来源：https://example.com/openai

2. Anthropic｜企业交易 agent
成熟度：仅发布方口径
证据强度：1/3
核心变化：agent 从聊天走向替企业做具体交易决策。
不确定性：没有第三方验证、上线时间和收费标准。
对我的意义：先观望，不纳入近期方案。

**【行动建议】**
先验证已经开放的能力，交易类进展等第三方证据。
"""

        summary = cron_today_cards.task_result_summary(
            text,
            task_name="AI 与科技行业信号",
            status="ok",
            error=None,
            kind="signal",
        )

        self.assertIn("【总判断】", summary)
        self.assertIn("形态已经清楚了", summary)
        self.assertIn("【关键信号】", summary)
        self.assertIn("OpenAI｜GPT-6 Astra Pro", summary)
        self.assertIn("成熟度：可验证进展", summary)
        self.assertIn("证据强度：3/3", summary)
        self.assertIn("Anthropic｜企业交易 agent", summary)
        self.assertIn("仅发布方口径", summary)
        self.assertIn("【行动建议】", summary)
        self.assertNotIn("https://example.com", summary)
        self.assertLess(summary.index("【总判断】"), summary.index("【关键信号】"))
        self.assertLess(summary.index("【关键信号】"), summary.index("【行动建议】"))

    def test_signal_summary_is_silent_when_nothing_important_happened(self) -> None:
        self.assertEqual(
            "",
            cron_today_cards.task_result_summary(
                "NO_SIGNAL",
                task_name="AI 与科技行业信号",
                status="ok",
                error=None,
                kind="signal",
            ),
        )
        self.assertEqual(
            "",
            cron_today_cards.task_result_summary(
                "今天没有值得推荐的变化，只有重复发布会通稿。",
                task_name="AI 与科技行业信号",
                status="ok",
                error=None,
                kind="signal",
            ),
        )

    def test_signal_summary_keeps_the_third_event_and_action_for_detail_view(self) -> None:
        text = """
【总判断】
三个事件指向同一条边界：能力增长快于验证速度。

【关键信号】
1. OpenAI｜Computer use
成熟度：已有第三方验证
证据强度：3/3
核心变化：这是第一条足够长的能力变化说明，用于确认摘要不会在第二个事件之前提前截断。
不确定性：仅覆盖部分任务。
对我的意义：先在低风险任务验证。
来源：https://example.com/1
2. Anthropic｜Safety disclosure
成熟度：可验证进展
证据强度：2/3
核心变化：这是第二条足够长的能力变化说明，用于确认摘要会完整保留比较信息。
不确定性：仍缺独立基准。
对我的意义：先观望发布细节。
来源：https://example.com/2
3. NVIDIA｜Runtime guard
成熟度：仅发布方口径
证据强度：1/3
核心变化：这是第三条足够长的能力变化说明，用于确认多事件对比不会丢失末尾信号。
不确定性：尚未提供定价和上线时间。
对我的意义：等第三方复现。
来源：https://example.com/3

【行动建议】
先验证第一条，另外两条保持观察。
"""

        summary = cron_today_cards.task_result_summary(
            text,
            task_name="AI 与科技行业信号",
            status="ok",
            error=None,
            kind="signal",
        )

        self.assertIn("3. NVIDIA｜Runtime guard", summary)
        self.assertIn("【行动建议】", summary)
        self.assertIn("先验证第一条", summary)
        self.assertLessEqual(len(summary), 5200)

    def test_signal_does_not_replace_automatic_news_cards(self) -> None:
        self.assertEqual({"news": "news", "inbox": "mail"}, cron_today_cards._AUTO_KIND_BY_TASK_KIND)

    def test_source_only_web_label_falls_back_to_the_article_host(self) -> None:
        items = cron_today_cards._web_evidence(
            "\n".join(
                (
                    "来源：；https://view.inews.qq.com/a/20261030A04MS300",
                    "来源：；https://view.inews.qq.com/a/20261030A01FDS00",
                )
            ),
            occurred_at=1,
        )

        self.assertEqual(2, len(items))
        self.assertEqual(
            ["view.inews.qq.com", "view.inews.qq.com"],
            [item.title for item in items],
        )
        self.assertEqual(
            [
                "https://view.inews.qq.com/a/20261030A04MS300",
                "https://view.inews.qq.com/a/20261030A01FDS00",
            ],
            [item.url for item in items],
        )

    def test_connector_json_keeps_titled_article_links_and_drops_images(self) -> None:
        payload = {
            "news_list": [
                {
                    "title": "Google DeepMind 发布新一代世界模型 Genie 4",
                    "url": "https://view.inews.qq.com/a/example",
                    "source": "每日经济新闻",
                    "image": "https://inews.gtimg.com/example.jpg",
                },
                {
                    "title": "示例图片",
                    "url": "https://inews.gtimg.com/example.jpg",
                },
            ]
        }

        items = cron_today_cards._embedded_web_items(payload)

        self.assertEqual(1, len(items))
        self.assertIn("每日经济新闻", items[0][0])
        self.assertEqual("https://view.inews.qq.com/a/example", items[0][1])


class AnniversaryCardTest(unittest.TestCase):
    def test_anniversary_is_schedule_related_before_inbox_matching(self) -> None:
        self.assertEqual(
            "calendar",
            cron_today_cards.classify_cron_kind(
                "纪念日提醒（统一入口）",
                "每天发送纪念日和生日提醒消息",
            ),
        )

    def test_card_title_hides_internal_entry_suffix(self) -> None:
        self.assertEqual(
            "纪念日提醒",
            cron_today_cards.cron_card_title(
                "calendar",
                "纪念日提醒（统一入口）",
                "今天是纪念日。",
            ),
        )

    def test_anniversary_words_in_a_signal_prompt_do_not_change_its_kind(self) -> None:
        self.assertEqual(
            "signal",
            cron_today_cards.classify_cron_kind(
                "AI 与科技行业信号",
                "整理 OpenAI 新模型与 Anthropic 周年发布动态",
            ),
        )


if __name__ == "__main__":
    unittest.main()
