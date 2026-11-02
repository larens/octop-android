from __future__ import annotations

import importlib.util
import json
import sqlite3
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

    def test_weather_summary_removes_process_text_and_keeps_both_cities(self) -> None:
        text = """
【明日安排】
明日上午在滨海，晚上返回北京。
位置判断：按你的规则今天在天津滨海，明天回北京。
飞书日程：连接器权限没接上，返回 No events in this time range。

| 时间 | 安排 | 备注 |
|---|---|---|
| 上午 | 在家陪孩子 | 待确认课程 |
| 20:00 之后 | 滨海 → 北京南 | 订 20:00 后车次 |

【天气与穿着】
查询城市：天津（滨海用天津市区代查）——来源：彩云天气 MCP（实时 + 逐小时 + 3 日预报）。
- 滨海：明天多云转晴，降雨概率 0%，最低 8.4°C / 最高 17.4°C。
- 北京：明天多云，降雨概率 0%，最低 5.3°C / 最高 18.2°C。
穿着：早晚偏凉，带薄外套。

【今晚准备】
把折叠伞放进书包。
"""

        summary = cron_today_cards.task_result_summary(
            text,
            task_name="夜间天气与明日准备",
            status="ok",
            error=None,
            kind="weather",
        )

        self.assertIn("明日上午在滨海，晚上返回北京", summary)
        self.assertIn("滨海：明天多云转晴", summary)
        self.assertIn("北京：明天多云", summary)
        self.assertIn("把折叠伞放进书包", summary)
        for internal in ("位置判断", "查询城市", "飞书日程", "连接器", "权限没", "MCP"):
            self.assertNotIn(internal, summary)

    def test_weather_summary_labels_the_unlabelled_primary_city(self) -> None:
        text = """
【明日安排】
明天白天在滨海，晚上回北京。
位置判断：按你的规则明天在天津滨海。

【天气与穿着】
查询城市：天津（滨海用天津市区代查）——来源：彩云天气 MCP（实时 + 逐小时 + 3 日预报）。
- 明天 11-01：天气多云转晴，降雨概率 0%，最低 8.4°C / 最高 17.4°C。
- 北京对照（你明晚到京）：明天 5.3~18.2°C，多云，降雨概率 0%。
- 实时报文里 33 mm/hr 与逐小时不一致，我按逐小时的量级理解。
- 预警接口连续返回 429 限流，我没有拿到预警数据。

【今晚准备】
1. 把折叠伞放进书包。彩云说明天降雨概率 0%，但两边打架，带把伞最省心。
"""

        summary = cron_today_cards.task_result_summary(
            text,
            task_name="夜间天气与明日准备",
            status="ok",
            error=None,
            kind="weather",
        )

        self.assertIn("滨海：明天 11-01", summary)
        self.assertIn("北京：你明晚到京；明天 5.3~18.2°C", summary)
        self.assertIn("把折叠伞放进书包", summary)
        self.assertNotIn("彩云", summary)
        self.assertNotIn("实时报文", summary)
        self.assertNotIn("限流", summary)
        self.assertNotIn("位置判断", summary)


class ReadingSummaryTest(unittest.TestCase):
    def test_live_weekly_reading_format_keeps_inline_books_and_counts(self) -> None:
        text = """
**结论一句话：这周你翻了 2 本书，但一条划线都没留。阅读动作合格，沉淀不及格。**

**本周事实（10/26–11/01）**
- 10-31《Token经济》白惠天/袁晓辉/司晓、10-29《故事》麦基 —— 两本划线均 **0 条**，都未标记读完。
- 全账号最近一次划线停在 **2026-08-07 23:43**（《AI未来已来》）。全架 1201 本，标记读完的只有 28 本（2.3%）。
- 10 周活跃度曲线看，本周 2 本属于第 3 次「单周 2 本以上」，读的动作没断。

**核心观点（两条线，来源分开标）**
- **线 A 成本与护城河**：Token 只是 AI 投入中最小的一部分。
- **线 B 叙事结构**：故事靠鸿沟推进。

**可行动（P1–P3）**
1. 给一个在做的 AI 场景算「Token 之外」六项账。
2. 用 DRI 规矩重写一件在拖的事。

**下一步读**：《本体驱动的AI数据管理》→《做对判断》→《大模型时代的基础架构》
"""

        summary = cron_today_cards.task_result_summary(
            text,
            task_name="每周阅读总结",
            status="ok",
            error=None,
            kind="review",
        )

        self.assertIn("本周阅读 2 本", summary)
        self.assertIn("新增划线 0 条", summary)
        self.assertIn("书架 1201 本", summary)
        self.assertIn("最近划线：2026-08-07《AI未来已来》", summary)
        self.assertIn("10-31《Token经济》· 0 条", summary)
        self.assertIn("10-29《故事》· 0 条", summary)
        self.assertIn("【核心观点】", summary)
        self.assertIn("【可行动】", summary)
        self.assertIn("《本体驱动的AI数据管理》", summary)

    def test_weekly_reading_summary_keeps_counts_books_and_actions(self) -> None:
        text = """
**一句话结论：这周你翻了 2 本书，但一条划线都没留下——阅读在继续，但要沉淀下来的东西断了。**

## 一、本周数据（口径先说清）

书架 1201 本。按 `readUpdateTime` 落在 2026-10-26 ~ 11-01 的只有两本：

| 日期 | 书名 | 划线/想法 |
|---|---|---|
| 10-31 | 《Token经济》白惠天、袁晓辉、司晓 | **0 条** |
| 10-29 | 《故事：材质、结构、风格和银幕剧作的原理》罗伯特·麦基 | **0 条** |

另外也核对了一遍：整个账号最近一次划线是 **2026-08-07 23:43**，在《AI未来已来》上。

## 二、核心观点

### 线 A｜成本与护城河

- **"Token 只是 AI 投入中最小的一部分，打通数据、校验输出、流程改造、人员配套才是真正要花钱的地方。"**
- **"Token 越便宜，围绕它搭建的那套系统就越值钱。"**

## 三、可行动的启发（就这周）

1. **恢复划线，但要挑地方划。** 只在"划了会改变一个决定"的句子上下手。
2. **给一个 AI 场景算一次 Token 之外的账。**

## 四、下一步读什么

- **《本体驱动的AI数据管理》**——把"数据才是差异"变成工程。
- **《大模型时代的基础架构》**——补齐成本结构。
"""

        summary = cron_today_cards.task_result_summary(
            text,
            task_name="每周阅读总结",
            status="ok",
            error=None,
            kind="review",
        )

        self.assertIn("【阅读概览】", summary)
        self.assertIn("本周阅读 2 本", summary)
        self.assertIn("新增划线 0 条", summary)
        self.assertIn("书架 1201 本", summary)
        self.assertIn("最近划线：2026-08-07《AI未来已来》", summary)
        self.assertIn("【本周在读】", summary)
        self.assertIn("10-31《Token经济》· 0 条", summary)
        self.assertIn("10-29《故事：材质、结构、风格和银幕剧作的原理》· 0 条", summary)
        self.assertIn("【核心观点】", summary)
        self.assertIn("Token 只是 AI 投入中最小的一部分", summary)
        self.assertIn("【可行动】", summary)
        self.assertIn("【下一步阅读】", summary)
        self.assertIn("《本体驱动的AI数据管理》", summary)
        self.assertLess(summary.index("【阅读概览】"), summary.index("【核心观点】"))


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

    def test_github_trending_uses_repository_name_instead_of_the_ui_json(self) -> None:
        payload = {
            "octop_ui": {"renderer": "github_trending_list", "version": 1},
            "data": {
                "items": [
                    {
                        "name": "unslop",
                        "full_name": "heydamir/unslop",
                        "url": "https://github.com/heydamir/unslop",
                        "description": "Turns AI-looking UI into a designed interface",
                        "language": "JavaScript",
                    }
                ]
            },
            "text": "GitHub 趋势",
        }

        items = cron_today_cards._embedded_web_items(payload)

        self.assertEqual([("heydamir/unslop", "https://github.com/heydamir/unslop")], items)
        self.assertEqual(
            "",
            cron_today_cards._clean_fact_title(
                '{"octop_ui": {"renderer": "github_trending_list", "version": 1}, '
                '"data": {"items": ['
            ),
        )

    def test_connector_article_title_replaces_a_source_time_placeholder(self) -> None:
        url = "https://www.ithome.com/1/020/471.htm"
        primary = [
            cron_today_cards.TodayEvidenceInput(
                source_type="web_search",
                reference=url,
                url=url,
                title="「站点直读」10-31 14:26",
                excerpt="",
                occurred_at=1,
            )
        ]
        discovered = [
            cron_today_cards.TodayEvidenceInput(
                source_type="web_search",
                reference=url,
                url=url,
                title="Anthropic 在 Claude Code 里给 Opus 5.5 加了禁止复刻现有游戏的规则",
                excerpt="",
                occurred_at=2,
            ),
        ]

        merged = cron_today_cards._merge_web_evidence(primary, discovered)

        self.assertEqual(
            "Anthropic 在 Claude Code 里给 Opus 5.5 加了禁止复刻现有游戏的规则",
            merged[0].title,
        )

    def test_article_snapshot_uses_heading_and_rejects_navigation_or_images(self) -> None:
        content = """
# Anthropic 出手：Claude Opus 5.5 不再帮你“AI 复刻游戏”

2026/10/31 14:26:18
来源：[IT之家](https://www.ithome.com/1/020/471.htm)

[首页](https://www.ithome.com)
![配图](https://img.ithome.com/example.png)
"""

        article = cron_today_cards._article_snapshot_item(content)

        self.assertEqual(
            (
                "Anthropic 出手：Claude Opus 5.5 不再帮你“AI 复刻游戏”",
                "https://www.ithome.com/1/020/471.htm",
            ),
            article,
        )
        self.assertTrue(
            cron_today_cards._is_non_article_evidence(
                "https://www.ithome.com",
                "首页",
            )
        )
        self.assertTrue(
            cron_today_cards._is_non_article_evidence(
                "https://img.ithome.com/example.png",
                "配图",
            )
        )
        self.assertTrue(
            cron_today_cards._is_non_article_evidence(
                "https://wpimg-wscn.awtmt.com/avatar?imageView2/1/w/160/h/160",
                "头像",
            )
        )
        for url, title in (
            ("https://www.chinadaily.com.cn/e/static_e/newmedia", "移动"),
            (
                "https://cn.chinadaily.com.cn/gtx/5d63917ba31099ab995dbb29/ChinaWatch",
                "中国观察",
            ),
            (
                "https://cn.chinadaily.com.cn/gtx/5d63917ba31099ab995dbb29/wangping",
                "中国日报网评",
            ),
            (
                "https://cn.chinadaily.com.cn/gtx/5d63917ba31099ab995dbb29/Teahouse",
                "中国那些事儿",
            ),
        ):
            self.assertTrue(
                cron_today_cards._is_non_article_evidence(url, title),
                f"{title} should be treated as section navigation, not a news article",
            )
        self.assertEqual(
            "英特尔、Reddit 双双暴跌近三成",
            cron_today_cards._clean_fact_title(
                "[![英特尔、Reddit 双双暴跌近三成]()]"
            ),
        )

    def test_thread_evidence_uses_article_snapshot_title_instead_of_navigation(self) -> None:
        conn = sqlite3.connect(":memory:")
        conn.row_factory = sqlite3.Row
        conn.execute(
            "CREATE TABLE thread_messages ("
            "seq INTEGER PRIMARY KEY, thread_id TEXT, role TEXT, "
            "message_json TEXT, created_at INTEGER)"
        )
        content = """
# 真实文章标题

2026/10/31 14:26:18
来源：[IT之家](https://www.ithome.com/1/020/471.htm)

[首页](https://www.ithome.com)
![]()(https://static.example.com/cover.png)
"""
        conn.executemany(
            "INSERT INTO thread_messages VALUES (?, ?, ?, ?, ?)",
            [
                (1, "thread", "human", _message_text("本期提示"), 10),
                (2, "thread", "tool", _message_text(content), 11),
            ],
        )

        evidence = cron_today_cards._thread_web_evidence(
            _ConnectionDb(conn),
            thread_id="thread",
            occurred_at=12,
            since=12,
        )

        self.assertEqual(["真实文章标题"], [item.title for item in evidence])
        self.assertEqual(
            ["https://www.ithome.com/1/020/471.htm"],
            [item.url for item in evidence],
        )

    def test_connector_evidence_missing_from_answer_is_appended(self) -> None:
        primary_url = "https://www.ithome.com/1/020/471.htm"
        discovered_url = "https://www.news.cn/20261031/example.htm"
        primary = [
            cron_today_cards.TodayEvidenceInput(
                source_type="web_search",
                reference=primary_url,
                url=primary_url,
                title="已有原文标题",
                excerpt="",
                occurred_at=1,
            )
        ]
        discovered = [
            cron_today_cards.TodayEvidenceInput(
                source_type="web_search",
                reference=primary_url,
                url=primary_url,
                title="已有原文标题",
                excerpt="",
                occurred_at=1,
            ),
            cron_today_cards.TodayEvidenceInput(
                source_type="web_search",
                reference=discovered_url,
                url=discovered_url,
                title="| S2 | 新华网·五中全会公报 | |",
                excerpt="",
                occurred_at=2,
            ),
        ]

        merged = cron_today_cards._merge_web_evidence(primary, discovered)

        self.assertEqual(
            ["已有原文标题", "新华网·五中全会公报"],
            [item.title for item in merged],
        )
        self.assertEqual([primary_url, discovered_url], [item.url for item in merged])

    def test_weekly_table_source_number_is_removed_from_the_article_title(self) -> None:
        self.assertEqual(
            "新华网·五中全会公报",
            cron_today_cards._clean_fact_title("| S1 | 新华网·五中全会公报 | |"),
        )
        self.assertEqual(
            "央广网·10/30 中共中央新闻发布会",
            cron_today_cards._clean_fact_title("|S2| 央广网·10/30 中共中央新闻发布会 |"),
        )

    def test_thread_evidence_uses_tools_after_the_latest_human_prompt(self) -> None:
        conn = sqlite3.connect(":memory:")
        conn.row_factory = sqlite3.Row
        conn.execute(
            "CREATE TABLE thread_messages ("
            "seq INTEGER PRIMARY KEY, thread_id TEXT, role TEXT, "
            "message_json TEXT, created_at INTEGER)"
        )
        old_payload = {
            "title": "旧文章",
            "url": "https://example.com/old",
        }
        new_payload = {
            "title": "本期真实文章标题",
            "url": "https://example.com/new",
        }
        rows = [
            (1, "thread", "human", _message_text("旧提示"), 10),
            (2, "thread", "tool", _message_text(json.dumps(old_payload)), 11),
            (3, "thread", "human", _message_text("本期提示"), 999),
            (4, "thread", "tool", _message_text(json.dumps(new_payload)), 1000),
        ]
        conn.executemany(
            "INSERT INTO thread_messages VALUES (?, ?, ?, ?, ?)",
            rows,
        )

        evidence = cron_today_cards._thread_web_evidence(
            _ConnectionDb(conn),
            thread_id="thread",
            occurred_at=1001,
            since=1001,
        )

        self.assertEqual(["本期真实文章标题"], [item.title for item in evidence])
        self.assertEqual(["https://example.com/new"], [item.url for item in evidence])


def _message_text(content: str) -> str:
    return json.dumps({"data": {"content": content}}, ensure_ascii=False)


class _ConnectionDb:
    def __init__(self, conn: sqlite3.Connection) -> None:
        self.conn = conn

    def connect(self) -> sqlite3.Connection:
        return self.conn


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
