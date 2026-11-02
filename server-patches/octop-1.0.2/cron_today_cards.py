"""Project scheduled-task results into the Today card stream."""

from __future__ import annotations

import hashlib
import json
import logging
import re
import time
from typing import Any
from urllib.parse import urlsplit

from octop.infra.db.pool import DatabasePool
from octop.infra.db.repos.agents import AgentRepo
from octop.infra.today.cards import (
    TodayCardDraft,
    TodayCardPublisher,
    TodayEvidenceInput,
)

logger = logging.getLogger(__name__)

_URL_RE = re.compile(r"https?://[^\s<>\]\)\}]+")
_ITEM_RE = re.compile(r"^(?:[-*+]\s+|\d+[.)、]\s*|\*\*\d+[.、]\s*)")
_PAGE_HEADING_RE = re.compile(r"(?m)^#\s+(.+?)\s*$")
_PAGE_SOURCE_URL_RE = re.compile(
    r"(?m)^\s*(?:来源|source)\s*[：:]\s*(?:\[[^\]]*\]\()?(https?://[^)\s]+)",
    re.IGNORECASE,
)
_HONORIFIC_LEAD_RE = re.compile(
    r"^(?:(?:早上|中午|下午|晚上)?好[，,]\s*)?[\u4e00-\u9fff]{1,4}(?:老师|先生|女士|总)[，,：:]"
)
_MARKDOWN_LINK_RE = re.compile(r"\[([^\]]+)\]\((https?://[^)]+)\)")
_MARKDOWN_IMAGE_RE = re.compile(r"!\[([^\]]*)\]\([^)]*\)")
_MAILTO_LINK_RE = re.compile(r"\[([^\]]+)\]\(mailto:[^)]+\)")
_SENTENCE_SPLIT_RE = re.compile(r"(?<=[。；;])")
_SECTION_HEADING_RE = re.compile(r"(?m)^\s*(?:\*\*)?【([^】]+)】(?:\*\*)?[ \t]*(.*)$")
_CONTACT_LINE_RE = re.compile(
    r"(?m)^\s*(?:\d+[.)、]\s*)?([^（(｜\n]{1,80})[（(]([^）)\s]+@[^）)\s]+)[）)](.*)$"
)
_DATE_TIME_RE = re.compile(r"\d{1,2}-\d{1,2}\s+\d{1,2}:\d{2}")
_QUOTED_RE = re.compile(r'"([^"]+)"')
_PENDING_COUNT_RE = re.compile(r"待处理私人邮件\s*(\d+)\s*封")
_TRAILING_URL_PUNCTUATION = ".,;:!?，。；：！？、）)]}>'\"*`"
_FACT_TABLE_PREFIX_RE = re.compile(r"^\s*\|\s*S?\d+\s*\|\s*", re.IGNORECASE)
_FACT_INLINE_NUMBER_RE = re.compile(r"^\s*S?\d+\s*[|｜]\s*", re.IGNORECASE)
_FACT_SOURCE_LABEL_RE = re.compile(
    r"^\s*[「【\[]?\s*(?:站点直读|连接器原文)\s*[」】\]]?\s*",
    re.IGNORECASE,
)
_FACT_TRAILING_TABLE_RE = re.compile(r"(?:\s*[|｜]\s*)+$")
_SOURCE_TIME_TITLE_RE = re.compile(
    r"^(?:[^：:|｜]{1,24}\s+)?\d{1,2}[-/]\d{1,2}\s+\d{1,2}:\d{2}$"
)
_READING_BOOK_ROW_RE = re.compile(
    r"^\|\s*(\d{1,2}[-/]\d{1,2})\s*\|\s*《([^》]+)》[^|]*"
    r"\|\s*[*_`]*(\d+)\s*条"
)
_READING_LAST_MARK_RE = re.compile(
    r"最近一次划线(?:是|停在)?\s*[*_`]*\s*(\d{4}-\d{2}-\d{2})"
    r"(?:\s+\d{1,2}:\d{2})?\s*[*_`]*[^《\n]{0,24}《([^》]+)》"
)
_READING_SHELF_RE = re.compile(r"(?:书架|全架)\s*(\d+)\s*本")
_READING_LEAD_RE = re.compile(
    r"(?:一句话结论|结论一句话|结论)\s*[：:]\s*[*_`]*\s*([^\n]+)"
)
_MARKDOWN_HEADING_RE = re.compile(r"(?m)^(#{1,6})\s*([^\n]+?)\s*$")
_BOLD_LABEL_RE = re.compile(r"(?m)^\s*\*\*([^*\n]+)\*\*\s*(?:[：:]\s*(.*))?$")
_READING_INLINE_BOOK_RE = re.compile(
    r"(?<![\d-])(\d{1,2}[-/]\d{1,2})\s*《([^》]+)》"
)
_READING_MARK_COUNT_RE = re.compile(
    r"(?:新增划线|划线(?:均|共|总计)?)[^0-9\n]{0,20}(\d+)\s*条"
)
_STRUCTURED_UI_TITLE_RE = re.compile(
    r'^\s*\{.*?"octop_ui"\s*:',
    re.DOTALL,
)
_NON_ARTICLE_PATH_RE = re.compile(
    r"\.(?:avif|bmp|gif|ico|jpe?g|png|svg|tiff?|webp)$",
    re.IGNORECASE,
)
_SECTION_PAGE_PATH_RE = re.compile(
    r"^/(?:gtx|e/static_e)(?:/|$)",
    re.IGNORECASE,
)
_SECTION_PAGE_TITLES = {
    "移动",
    "中国观察",
    "中国日报网评",
    "中国那些事儿",
}
_WEAK_WEB_TITLES = {
    "首页",
    "it圈",
    "最会买",
    "设置",
    "投稿",
    "订阅",
    "软媒应用",
    "寻求报道",
    "站点直读",
    "连接器原文",
    "来源",
    "source",
}
_IMAGE_HOST_PREFIXES = ("img.", "image.", "images.", "pic.", "wpimg-")
_WEATHER_SENTENCE_RE = re.compile(r"[^。；;！？!?\n]+[。；;！？!?]?")
_WEATHER_QUERY_ALIAS_RE = re.compile(
    r"([\u4e00-\u9fff]{2,8})用[\u4e00-\u9fff]{2,8}代查"
)
_WEATHER_QUERY_CITY_RE = re.compile(r"查询城市\s*[：:]\s*([\u4e00-\u9fff]{2,8})")
_WEATHER_CITY_COMPARISON_RE = re.compile(
    r"^(\s*(?:[-*+]\s+)?)([\u4e00-\u9fff]{2,8})(?:对照|参考)"
    r"(?:[（(]([^）)]*)[）)])?[：:]"
)
_WEATHER_CIRCLED_PROCESS_ITEM_RE = re.compile(
    r"[①②③④⑤⑥⑦⑧⑨⑩]\s*[^①②③④⑤⑥⑦⑧⑨⑩。；;\n]*"
    r"(?:飞书日历|连接器|权限|授权|MCP)"
    r"[^①②③④⑤⑥⑦⑧⑨⑩。；;\n]*[；;]?",
    re.IGNORECASE,
)
_WEATHER_DATA_RE = re.compile(r"(?:°\s*[CF]?|℃|降雨概率|降水概率|天气|温度)")
_INTERNAL_WEATHER_MARKERS = (
    "位置判断",
    "查询城市",
    "飞书日程",
    "飞书日历",
    "连接器",
    "机器人身份",
    "权限没",
    "权限未",
    "权限",
    "授权",
    "未接通",
    "mcp",
    "open-meteo",
    "内置源",
    "内置天气",
    "只吃经纬度",
    "我用的是",
    "按你的规则",
    "工具调用",
    "调用过程",
    "接口被限流",
    "限流",
    "实时报文",
    "优先源",
    "来源",
    "no events in this time range",
)
_SOFT_WEATHER_SOURCE_MARKERS = ("彩云", "内置源")
_TODAY_KIND_BY_TASK_KIND = {
    "news": "briefing",
    "weather": "briefing",
    "signal": "signal",
}
_AUTO_KIND_BY_TASK_KIND = {"news": "news", "inbox": "mail"}
_ANNIVERSARY_NAME_MARKERS = ("纪念日", "周年", "生日提醒", "anniversary")
_ANNIVERSARY_PROMPT_MARKERS = ("纪念日", "生日提醒", "anniversary")
_SIGNAL_MARKERS = (
    "行业信号",
    "科技信号",
    "ai 与科技",
    "ai与科技",
    "ai and tech",
    "ai & tech",
    "ai and technology",
    "industry signal",
    "头部科技公司",
    "重量级 ai",
)
_INTERNAL_TITLE_SUFFIX_RE = re.compile(
    r"\s*[（(]\s*(?:统一入口|系统入口|自动入口)\s*[）)]\s*$"
)
_FAILED_RESULT_START_RE = re.compile(
    r"^(?:"
    r"执行失败|任务执行失败|运行失败|请求失败|获取失败|调用失败|模型调用失败|处理失败|"
    r"无法获取|暂时无法|连接失败|"
    r"服务器错误|服务不可用|服务暂时不可用|"
    r"error\b|internal server error|server unavailable|service unavailable|"
    r"unable to|could not|cannot |connection (?:refused|failed)|"
    r"request (?:failed|timed out)|timed out|timeout"
    r")",
    re.IGNORECASE,
)
_NO_NEW_INFORMATION_START_RE = re.compile(
    r"^(?:"
    r"暂无新信息|没有新的信息|没有获取到新信息|没有获取新信息|暂无重要信息|"
    r"no new information|no important updates?"
    r")",
    re.IGNORECASE,
)


def _compact(value: Any, limit: int = 280) -> str:
    text = " ".join(str(value or "").split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def classify_cron_kind(name: str, prompt: str) -> str:
    """Choose the Today card presentation without asking another model to classify."""
    name_text = name.lower()
    text = f"{name}\n{prompt}".lower()
    if (
        any(word in name_text for word in _ANNIVERSARY_NAME_MARKERS)
        or any(word in text for word in _ANNIVERSARY_PROMPT_MARKERS)
    ):
        return "calendar"
    if any(word in text for word in _SIGNAL_MARKERS):
        return "signal"
    if any(word in text for word in ("新闻", "资讯", "快讯", "简报", "关注领域")):
        return "news"
    if "get_weather" in text or "天气" in text:
        return "weather"
    if any(word in text for word in ("日程", "会议", "日历")):
        return "calendar"
    if any(word in text for word in ("健康", "运动", "睡眠", "步数", "心率")):
        return "health"
    if any(word in text for word in ("邮件", "收件箱", "消息", "聊天记录")):
        return "inbox"
    if any(word in text for word in ("回顾", "复盘", "总结", "周报", "日报")):
        return "review"
    if any(word in text for word in ("调研", "研究", "追踪", "关注")):
        return "research"
    return "task"


def _clean_inline(value: str) -> str:
    text = _MARKDOWN_LINK_RE.sub(r"\1", value)
    text = _URL_RE.sub("", text)
    text = text.replace("**", "").replace("__", "").replace("`", "")
    text = re.sub(r"^[#>]+\s*", "", text)
    text = _ITEM_RE.sub("", text)
    return _compact(text, 320)


def _has_honorific_lead(text: str) -> bool:
    return _HONORIFIC_LEAD_RE.match(text) is not None


def _summary_candidates(text: str) -> list[str]:
    candidates: list[str] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "|", "```", "---")):
            continue
        if not _ITEM_RE.match(line):
            continue
        cleaned = _clean_inline(line)
        if len(cleaned) < 8:
            continue
        if _has_honorific_lead(cleaned) and ("运行" in cleaned or "简报" in cleaned):
            continue
        candidates.append(_compact(cleaned, 120))
        if len(candidates) >= 3:
            break
    return candidates


def _section_body(text: str, keyword: str) -> str:
    matches = list(_SECTION_HEADING_RE.finditer(text))
    for index, match in enumerate(matches):
        if keyword not in match.group(1):
            continue
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        inline_body = match.group(2).strip()
        block_body = text[match.end() : end].strip()
        return "\n".join(part for part in (inline_body, block_body) if part)
    return ""


def _clean_mail_detail(value: str) -> str:
    text = _MAILTO_LINK_RE.sub(r"\1", value)
    text = _MARKDOWN_LINK_RE.sub(r"\1", text)
    text = _URL_RE.sub("", text)
    text = text.replace("**", "").replace("__", "").replace("`", "")
    text = re.sub(r"(?m)^\s*[#>]+\s*", "", text)
    lines = [" ".join(line.split()) for line in text.splitlines()]
    return "\n".join(line for line in lines if line)


def _weather_block(value: str, limit: int) -> str:
    text = _public_weather_text(value)
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def _public_weather_text(value: str) -> str:
    """Remove tool, permission, location-selection and fallback chatter from a result."""
    lines: list[str] = []
    weather_place: str | None = None
    needs_primary_place = False
    for raw in _clean_mail_detail(value).splitlines():
        line = raw.strip()
        if not line:
            continue
        if "位置判断" in line:
            route = re.search(r"(?:明天|明日)[^。；;\n]*", line)
            if route:
                lines.append(route.group(0).strip())
            continue
        if "查询城市" in line:
            alias = _WEATHER_QUERY_ALIAS_RE.search(line)
            city = _WEATHER_QUERY_CITY_RE.search(line)
            weather_place = (
                alias.group(1) if alias else city.group(1) if city else None
            )
            needs_primary_place = weather_place is not None
            continue
        line = _WEATHER_CITY_COMPARISON_RE.sub(
            lambda match: (
                f"{match.group(1)}{match.group(2)}："
                f"{match.group(3) + '；' if match.group(3) else ''}"
            ),
            line,
        )
        line = _WEATHER_CIRCLED_PROCESS_ITEM_RE.sub("", line).strip()
        if not line or line in {"待确认事项：", "待确认事项:"}:
            continue
        kept: list[str] = []
        for sentence in _WEATHER_SENTENCE_RE.findall(line):
            sentence = sentence.strip()
            if not sentence:
                continue
            if any(marker.lower() in sentence.lower() for marker in _INTERNAL_WEATHER_MARKERS):
                continue
            if any(marker in sentence for marker in _SOFT_WEATHER_SOURCE_MARKERS):
                if any(word in sentence for word in ("打架", "为准", "分歧", "不编造")):
                    continue
                sentence = re.sub(r"[（(]\s*彩云\s*[：:]\s*", "（", sentence)
                sentence = re.sub(
                    r"(?:彩云天气|彩云|内置源|内置天气)"
                    r"(?:MCP|说|报|显示|给出)?\s*[：:]?",
                    "",
                    sentence,
                    flags=re.IGNORECASE,
                )
            sentence = re.sub(
                r"(?:这点我不含糊|不做夸张描述|我不编造)",
                "",
                sentence,
            )
            sentence = " ".join(sentence.split())
            sentence = re.sub(r"[，,：:]\s*$", "。", sentence)
            if sentence:
                kept.append(sentence)
        if not kept:
            continue
        cleaned = "".join(kept)
        if (
            needs_primary_place
            and weather_place
            and _WEATHER_DATA_RE.search(cleaned)
            and not re.match(
                r"^\s*(?:[-*+]\s+)?[\u4e00-\u9fff]{2,10}[：:]",
                cleaned,
            )
        ):
            bullet = re.match(r"^(\s*[-*+]\s+)", cleaned)
            prefix = bullet.group(1) if bullet else ""
            body = cleaned[len(prefix) :]
            cleaned = f"{prefix}{weather_place}：{body}"
            needs_primary_place = False
        lines.append(cleaned)
    return "\n".join(lines)


def _split_weather_from_schedule(value: str) -> tuple[str, str]:
    """Keep weather-dependent bullets out of the primary plan section."""
    weather_hints = (
        "伞",
        "降雨",
        "降水",
        "有雨",
        "无雨",
        "气温",
        "温度",
        "°C",
        "℃",
        "降温",
        "保暖",
        "衣物",
        "穿着",
        "湿度",
        "晴天",
        "刮风",
        "大风",
    )
    schedule_lines: list[str] = []
    weather_lines: list[str] = []
    for line in _clean_mail_detail(value).splitlines():
        target = (
            schedule_lines
            if line.lstrip().startswith("|")
            else weather_lines
            if any(hint in line for hint in weather_hints)
            else schedule_lines
        )
        target.append(line)
    return "\n".join(schedule_lines), "\n".join(weather_lines)


def _first_summary_section(text: str, headings: tuple[str, ...]) -> str:
    for heading in headings:
        body = _section_body(text, heading)
        if body:
            return body
    return ""


def _markdown_section(text: str, keyword: str) -> str:
    headings = list(_MARKDOWN_HEADING_RE.finditer(text))
    for index, match in enumerate(headings):
        level = len(match.group(1))
        title = re.sub(r"^[一二三四五六七八九十\d]+[、.．]\s*", "", match.group(2))
        if keyword not in title:
            continue
        end = next(
            (
                heading.start()
                for heading in headings[index + 1 :]
                if len(heading.group(1)) <= level
            ),
            len(text),
        )
        return text[match.end() : end].strip()
    return ""


def _reading_section(text: str, keyword: str) -> str:
    markdown = _markdown_section(text, keyword)
    if markdown:
        return markdown
    labels = list(_BOLD_LABEL_RE.finditer(text))
    for index, match in enumerate(labels):
        title = match.group(1).strip()
        if keyword not in title:
            continue
        inline = (match.group(2) or "").strip()
        if inline:
            return inline
        end = labels[index + 1].start() if index + 1 < len(labels) else len(text)
        return text[match.end() : end].strip()
    return ""


def _reading_items(value: str, limit: int) -> list[str]:
    items: list[str] = []
    for raw in value.splitlines():
        line = raw.strip()
        if not line or not _ITEM_RE.match(line):
            continue
        cleaned = _clean_inline(line)
        if len(cleaned) < 8:
            continue
        items.append(_compact(cleaned, 260))
        if len(items) >= limit:
            break
    return items


def _reading_result_summary(text: str) -> str:
    """Keep reading counts and book-level evidence that generic bullet summaries drop."""
    if "阅读" not in text and "划线" not in text:
        return ""

    books: list[tuple[str, str, int | None]] = []
    seen_books: set[tuple[str, str]] = set()
    for raw in text.splitlines():
        match = _READING_BOOK_ROW_RE.match(raw.strip())
        if match:
            item = (
                match.group(1),
                _clean_inline(match.group(2)).strip("《》 "),
                int(match.group(3)),
            )
            books.append(item)
            seen_books.add(item[:2])

    if not books:
        for raw in text.splitlines():
            line = raw.strip()
            if "划线" not in line or "最近一次划线" in line:
                continue
            head, _, tail = line.partition("——")
            tail_marks = _READING_MARK_COUNT_RE.search(tail)
            for segment in re.split(r"[、；;]", head):
                match = _READING_INLINE_BOOK_RE.search(segment)
                if not match:
                    continue
                date = match.group(1)
                title = _clean_inline(match.group(2)).strip("《》 ")
                if (date, title) in seen_books:
                    continue
                mark_match = _READING_MARK_COUNT_RE.search(segment)
                marks = int(mark_match.group(1)) if mark_match else None
                if marks is None and tail_marks and int(tail_marks.group(1)) == 0:
                    marks = 0
                books.append((date, title, marks))
                seen_books.add((date, title))

    shelf = _READING_SHELF_RE.search(text)
    last_mark = _READING_LAST_MARK_RE.search(text)
    lead = _READING_LEAD_RE.search(text)
    if not books and not shelf and not last_mark:
        return ""

    read_count = len(books)
    known_marks = [item[2] for item in books if item[2] is not None]
    total_mark = _READING_MARK_COUNT_RE.search(text)
    marks_count = (
        sum(known_marks)
        if len(known_marks) == len(books)
        else int(total_mark.group(1)) if total_mark else None
    )
    overview_parts = [
        f"本周阅读 {read_count} 本" if read_count else "",
        f"新增划线 {marks_count} 条" if marks_count is not None else "",
        f"书架 {shelf.group(1)} 本" if shelf else "",
    ]
    overview_lines = [" · ".join(part for part in overview_parts if part)]
    if last_mark:
        overview_lines.append(
            f"最近划线：{last_mark.group(1)}《{_clean_inline(last_mark.group(2))}》"
        )
    if lead:
        conclusion = _clean_inline(lead.group(1)).strip("*_` ")
        if conclusion:
            overview_lines.append(f"结论：{conclusion}")

    sections = [f"【阅读概览】\n" + "\n".join(line for line in overview_lines if line)]
    if books:
        book_lines = [
            f"{date}《{title}》· {marks} 条" if marks is not None
            else f"{date}《{title}》· 划线数见总览"
            for date, title, marks in books[:8]
        ]
        sections.append("【本周在读】\n" + "\n".join(book_lines))

    core = _reading_section(text, "核心观点")
    quoted = [
        item
        for item in _reading_items(core, limit=12)
        if '"' in item or "“" in item or "”" in item
    ]
    core_items = (quoted or _reading_items(core, limit=3))[:3]
    if core_items:
        sections.append("【核心观点】\n" + "\n".join(f"- {item}" for item in core_items))

    actions = _reading_items(_reading_section(text, "可行动"), limit=3)
    if actions:
        sections.append("【可行动】\n" + "\n".join(f"- {item}" for item in actions))

    next_reading = _reading_section(text, "下一步读")
    next_titles: list[str] = []
    for match in re.finditer(r"《[^》]+》", next_reading):
        title = match.group(0)
        if title not in next_titles:
            next_titles.append(title)
    if next_titles:
        sections.append("【下一步阅读】\n" + "\n".join(f"- {item}" for item in next_titles))

    summary = "\n\n".join(sections)
    return summary if len(summary) <= 5200 else summary[:5199].rstrip() + "…"


def _weather_plan_is_tomorrow(task_name: str, text: str) -> bool:
    heading_markers = ("【明日安排】", "【明日日程】", "【明日天气】", "【天气与穿着】", "【今晚准备】")
    return (
        any(word in task_name for word in ("明日", "夜间"))
        or any(marker in text for marker in heading_markers)
    )


def cron_card_title(task_kind: str, task_name: str, text: str) -> str:
    """Use a broad daily-plan title so weather stays a supporting detail."""
    if task_kind == "signal":
        return "AI 与科技行业信号"
    if task_kind == "weather":
        return "明日安排与准备" if _weather_plan_is_tomorrow(task_name, text) else "今日安排与建议"
    public_name = _INTERNAL_TITLE_SUFFIX_RE.sub("", task_name).strip()
    return _compact(public_name or "定时任务", 200)


def cron_result_has_new_information(text: str, *, task_kind: str) -> bool:
    """Return whether a successful run produced content worth putting in Today."""
    normalized = re.sub(r"^[\s*_`#>]+", "", str(text or "")).strip()
    if not normalized:
        return False
    if task_kind == "signal" and _signal_no_update(normalized):
        return False
    if _FAILED_RESULT_START_RE.match(normalized):
        return False
    if _NO_NEW_INFORMATION_START_RE.match(normalized):
        return False
    return True


def _signal_no_update(text: str) -> bool:
    normalized = re.sub(r"[*_`#>]", "", text).strip()
    if normalized.upper().startswith("NO_SIGNAL"):
        return True
    return normalized in {
        "今日无重要信号",
        "今天没有值得推荐的变化",
        "无值得推荐的变化",
        "暂无重要信号",
    }


def _clean_signal_block(value: str, limit: int = 900) -> str:
    lines: list[str] = []
    for raw in value.splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "|", "```", "---")):
            continue
        line = _MARKDOWN_LINK_RE.sub(r"\1", line)
        line = _URL_RE.sub("", line)
        line = line.replace("**", "").replace("__", "").replace("`", "")
        line = re.sub(r"^[#>]+\s*", "", line)
        line = " ".join(line.split())
        if line:
            lines.append(_compact(line, 420))
    cleaned = "\n".join(lines)
    return cleaned if len(cleaned) <= limit else cleaned[: limit - 1].rstrip() + "…"


def _signal_result_summary(text: str) -> str:
    if _signal_no_update(text):
        return ""
    judgment = _clean_signal_block(
        _first_summary_section(text, ("总判断", "核心判断", "今日判断")),
        420,
    )
    signals = _clean_signal_block(
        _first_summary_section(text, ("关键信号", "行业信号", "值得关注的变化")),
        4200,
    )
    advice = _clean_signal_block(
        _first_summary_section(text, ("行动建议", "今天可以做什么", "建议")),
        420,
    )
    if not signals:
        return ""
    sections = []
    if judgment:
        sections.append(f"【总判断】\n{judgment}")
    sections.append(f"【关键信号】\n{signals}")
    if advice:
        sections.append(f"【行动建议】\n{advice}")
    summary = "\n\n".join(sections)
    return summary if len(summary) <= 5200 else summary[:5199].rstrip() + "…"


def _weather_result_summary(text: str, *, task_name: str) -> str:
    is_tomorrow = _weather_plan_is_tomorrow(task_name, text)
    weather_parts = [
        _weather_block(_section_body(text, heading), 1000)
        for heading in ("天气提醒", "天气与穿着", "天气", "今日天气", "明日天气", "天气概览")
    ]
    weather_parts = list(dict.fromkeys(part for part in weather_parts if part))
    schedule = _weather_block(
        _first_summary_section(
            text,
            (
                "今日安排",
                "明日安排",
                "今日重点",
                "明日重点",
                "日程与出行",
                "日程与准备",
                "明日准备",
                "今日日程",
                "明日日程",
            ),
        ),
        1400,
    )
    schedule, schedule_weather = _split_weather_from_schedule(schedule)
    advice = _weather_block(
        _first_summary_section(
            text,
            ("行动建议", "今晚准备", "处理建议", "晨间建议", "夜间建议", "出行建议"),
        ),
        900,
    )

    sections: list[str] = []
    if schedule:
        sections.append(f"【{'明日安排' if is_tomorrow else '今日安排'}】\n{schedule}")
    elif not weather_parts and not advice:
        candidates = _summary_candidates(text)
        if candidates:
            return "\n".join(f"• {item}" for item in candidates)
        return _compact(text, 320) or f"「{task_name}」已执行完成，查看完整结果与来源。"
    else:
        sections.append(f"【{'明日安排' if is_tomorrow else '今日安排'}】\n暂无已同步的日程安排。")
    if schedule_weather:
        weather_parts.insert(0, schedule_weather)
    if weather_parts:
        heading = "天气与穿着" if is_tomorrow else "天气提醒"
        sections.append(f"【{heading}】\n" + "\n".join(weather_parts))
    if advice:
        heading = "今晚准备" if is_tomorrow else "行动建议"
        sections.append(f"【{heading}】\n{advice}")

    summary = "\n\n".join(sections)
    return summary if len(summary) <= 3000 else summary[:2999].rstrip() + "…"


def _mail_pending_detail(text: str) -> str:
    body = _section_body(text, "私人邮件 · 待处理") or _section_body(text, "待处理")
    detail = _clean_mail_detail(body)
    if not detail:
        return ""
    if re.search(r"(?:没有|暂无|无).{0,10}(?:需要|待处理|待回复|需处理)", detail):
        return ""
    if "无需处理" in detail:
        return ""
    return detail


def _mail_pending_count(text: str) -> int:
    match = _PENDING_COUNT_RE.search(text)
    return int(match.group(1)) if match else 0


def _mail_contacts(text: str) -> list[tuple[str, str, list[str], list[str]]]:
    contacts: list[tuple[str, str, list[str], list[str]]] = []
    seen: set[tuple[str, str]] = set()
    for match in _CONTACT_LINE_RE.finditer(text):
        name = _compact(match.group(1).strip(" -—*"), 60)
        email = match.group(2).strip()
        if not name or (name.lower(), email.lower()) in seen:
            continue
        seen.add((name.lower(), email.lower()))
        detail = match.group(3)
        times = _DATE_TIME_RE.findall(detail)
        subjects = [
            _compact(subject, 100)
            for subject in _QUOTED_RE.findall(detail)
            if subject.strip()
        ]
        contacts.append((name, email, times, subjects))
        if len(contacts) >= 5:
            break
    return contacts


def _mail_contact_evidence(
    text: str,
    *,
    thread_id: str,
    occurred_at: int,
) -> list[TodayEvidenceInput]:
    contacts = _mail_contacts(text)
    evidence: list[TodayEvidenceInput] = []
    for name, email, times, subjects in contacts:
        topic = subjects[0] if subjects else ""
        title = f"{name}：{topic}" if topic else f"{name} 联系你"
        details = [email]
        if times:
            details.append(f"{times[0]} 联系你")
        if len(times) > 1:
            details.append(f"{times[-1]} 有回信")
        evidence.append(
            TodayEvidenceInput(
                source_type="conversation",
                reference=thread_id,
                title=title,
                excerpt=" · ".join(details),
                occurred_at=occurred_at,
            )
        )
    return evidence


def _mail_risk_lines(text: str) -> list[str]:
    lines: list[str] = []
    coverage = re.search(r"本次(?:只|仅)覆盖(?:了)?\s*([^。；\n]+)", text)
    if coverage:
        lines.append(f"本次仅覆盖 {coverage.group(1).strip()}，其他邮件可能遗漏。")
    connector = re.search(
        r"([A-Za-z][A-Za-z0-9 ._-]{1,40}?)\s*那个连接器[^。\n]*"
        r"(?:授权失败|check authorization)",
        text,
        re.IGNORECASE,
    )
    if connector:
        lines.append(
            f"{connector.group(1).strip()} 授权失败，可能有邮件遗漏；重新授权后可补查。"
        )
    if lines:
        return list(dict.fromkeys(lines))
    risk_words = ("失败", "未覆盖", "不在这份", "遗漏", "范围提醒", "风险")
    return list(
        dict.fromkeys(
            _compact(_clean_inline(line), 260)
            for line in text.splitlines()
            if any(word in line for word in risk_words) and _clean_inline(line)
        )
    ) or ["未发现额外风险。"]


def _mail_result_summary(text: str) -> str:
    raw_overview = next(
        (
            _clean_inline(line)
            for line in text.splitlines()
            if "共收到" in line and "封" in line
        ),
        "",
    )
    raw_overview = re.sub(r"^昨日\s*[（(][^）)]*[）)]\s*", "昨日", raw_overview)
    pending_detail = _mail_pending_detail(text)
    pending_count = _mail_pending_count(text)
    has_pending = bool(pending_detail) or pending_count > 0
    total_match = re.search(r"共收到\s*(\d+)\s*封", raw_overview)
    total_count = int(total_match.group(1)) if total_match else 0
    if total_count and has_pending:
        overview = (
            f"昨日共收到 {total_count} 封新邮件，其中待处理私人邮件 "
            f"{pending_count if pending_count else '若干'} 封。"
        )
    elif total_count:
        overview = f"昨日共收到 {total_count} 封新邮件，没有需要回复或跟进的邮件。"
    else:
        overview = raw_overview or "昨日没有需要回复或跟进的邮件。"

    advice: list[str] = []
    if has_pending:
        advice.append("先处理待处理邮件，其余邮件归档即可。")
    else:
        advice.append("没有需要回复或跟进的邮件。")

    risk_words = ("失败", "未覆盖", "不在这份", "遗漏", "范围提醒", "风险")
    note_lines = [
        _clean_inline(line)
        for line in text.splitlines()
        if line.strip().startswith(("-", "*", "•"))
    ]
    for line in note_lines:
        if any(word in line for word in risk_words):
            continue
        check = re.search(r"想核对(.+?)，比如", line)
        if check:
            advice.append(f"如需核对{check.group(1)}，可查看公开来源后再决定是否跟进。")
        elif any(word in line for word in ("可以", "建议", "需要我")):
            advice.append(_compact(line, 220))
    if not advice:
        advice.append("按联系人和紧急程度处理待办，其余邮件归档即可。")

    risk_lines = _mail_risk_lines(text)
    detail_blocks: list[str] = []
    for heading in ("私人邮件 · 无需处理", "广告邮件", "通知邮件"):
        raw_body = _section_body(text, heading)
        raw_body = re.split(r"(?m)^\s*两点说明[：:]\s*$", raw_body, maxsplit=1)[0]
        body = _clean_mail_detail(raw_body)
        if body:
            detail_blocks.append(f"{heading}\n{body}")

    sections = [f"【收件概览】\n{overview}"]
    if pending_detail:
        sections.append(f"【待处理邮件】\n{pending_detail}")
    if detail_blocks:
        sections.append("【邮件明细】\n" + "\n\n".join(detail_blocks))
    sections.append("【处理建议】\n" + "\n".join(advice[:3]))
    sections.append("【其他风险】\n" + "\n".join(risk_lines[:3]))
    return "\n\n".join(sections)


def task_result_summary(
    text: str,
    *,
    task_name: str,
    status: str,
    error: str | None,
    kind: str | None = None,
) -> str:
    if status == "error":
        return ""
    if kind == "inbox":
        return _mail_result_summary(text)
    if kind == "weather":
        return _weather_result_summary(text, task_name=task_name)
    if kind == "signal":
        return _signal_result_summary(text)
    if kind == "review":
        reading = _reading_result_summary(text)
        if reading:
            return reading
    candidates = _summary_candidates(text)
    if candidates:
        return "\n".join(f"• {item}" for item in candidates)
    first_paragraph = next(
        (
            cleaned
            for raw in text.splitlines()
            if (cleaned := _clean_inline(raw))
            and len(cleaned) >= 16
            and not _has_honorific_lead(cleaned)
        ),
        "",
    )
    return first_paragraph or f"「{task_name}」已执行完成，查看完整结果与来源。"


def _message_content(message_json: str) -> str:
    try:
        payload = json.loads(message_json)
    except (TypeError, json.JSONDecodeError):
        return ""
    if not isinstance(payload, dict):
        return ""
    data = payload.get("data")
    content = data.get("content") if isinstance(data, dict) else payload.get("content")
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        return "".join(
            str(block.get("text") or "")
            if isinstance(block, dict)
            else str(block)
            for block in content
        ).strip()
    return ""


def last_ai_text(db: DatabasePool, thread_id: str) -> str:
    with db.connect() as conn:
        rows = conn.execute(
            "SELECT message_json FROM thread_messages "
            "WHERE thread_id = ? AND role IN ('ai', 'assistant') "
            "ORDER BY seq DESC LIMIT 30",
            (thread_id,),
        ).fetchall()
    for row in rows:
        text = _message_content(str(row["message_json"]))
        if text:
            return text
    return ""


def _clean_url(value: str) -> str:
    return value.rstrip(_TRAILING_URL_PUNCTUATION)


def _web_title(raw_line: str, segment: str) -> str:
    common = _clean_inline(raw_line).split("（", 1)[0].strip(" -—:：")
    label = _clean_inline(segment).split("（", 1)[0].strip(" -—:：")
    label = re.sub(r"[\s→\-—:：;；,，.。()（）]+$", "", label)
    if len(label) < 8:
        label = common
    return _compact(label, 120)


def _clean_fact_title(value: Any) -> str:
    label = _clean_inline(str(value or ""))
    if _STRUCTURED_UI_TITLE_RE.match(label):
        return ""
    label = _MARKDOWN_IMAGE_RE.sub(r"\1", label)
    label = _FACT_TABLE_PREFIX_RE.sub("", label)
    label = _FACT_INLINE_NUMBER_RE.sub("", label)
    label = _FACT_SOURCE_LABEL_RE.sub("", label)
    label = _FACT_TRAILING_TABLE_RE.sub("", label)
    return label.strip(" -—:：|｜[]")


def _is_weak_web_title(value: str, url: str) -> bool:
    label = _clean_fact_title(value)
    if not label:
        return True
    if label.lower() in _WEAK_WEB_TITLES:
        return True
    if re.fullmatch(r"(?:来源|source)\s*[：:]\s*[^：:]{1,30}", label, re.IGNORECASE):
        return True
    if _SOURCE_TIME_TITLE_RE.match(label):
        return True
    host = _web_host(url)
    residual = label.replace(host, "", 1) if host else label
    residual = re.sub(
        r"(?:站点直读|连接器原文|来源|source|\d{1,2}[-/]\d{1,2}|\d{1,2}:\d{2})",
        "",
        residual,
        flags=re.IGNORECASE,
    )
    return not re.search(r"[A-Za-z\u4e00-\u9fff]", residual)


def _is_non_article_evidence(url: str, title: str) -> bool:
    parsed = urlsplit(url)
    path = parsed.path
    host = parsed.netloc.lower()
    if not path or path == "/":
        return True
    if _NON_ARTICLE_PATH_RE.search(path):
        return True
    if (
        _SECTION_PAGE_PATH_RE.search(path)
        and _clean_fact_title(title).casefold() in _SECTION_PAGE_TITLES
    ):
        return True
    if host.startswith(_IMAGE_HOST_PREFIXES):
        return True
    if re.search(r"(?:imageview|x-oss-process|thumbnail|resize)", parsed.query, re.IGNORECASE):
        return True
    return _clean_fact_title(title).lower() in _WEAK_WEB_TITLES


def _web_title_score(value: str, url: str) -> int:
    if _is_weak_web_title(value, url):
        return 1
    return len(_clean_fact_title(value))


def _merge_web_evidence(
    primary: list[TodayEvidenceInput],
    discovered: list[TodayEvidenceInput],
) -> list[TodayEvidenceInput]:
    """Prefer connector-returned article titles while keeping the model's URL order."""
    discovered_by_url = {
        str(item.url or item.reference): item
        for item in discovered
        if item.url or item.reference
    }
    merged: list[TodayEvidenceInput] = []
    seen_urls: set[str] = set()
    for item in primary:
        url = str(item.url or item.reference)
        seen_urls.add(url)
        title = str(item.title or "")
        excerpt = item.excerpt
        candidate = discovered_by_url.get(url)
        if candidate is not None:
            candidate_title = str(candidate.title or "")
            if _web_title_score(candidate_title, url) > _web_title_score(title, url):
                title = candidate_title
                excerpt = excerpt or candidate.excerpt
            elif _is_weak_web_title(title, url):
                title = candidate_title
        title = _clean_fact_title(title)
        if _is_non_article_evidence(url, title) or _is_weak_web_title(title, url):
            continue
        merged.append(
            TodayEvidenceInput(
                source_type=item.source_type,
                reference=item.reference,
                url=item.url,
                title=title,
                excerpt=excerpt,
                occurred_at=item.occurred_at,
            )
        )
    for item in discovered:
        url = str(item.url or item.reference)
        if not url or url in seen_urls:
            continue
        seen_urls.add(url)
        title = _clean_fact_title(item.title)
        if _is_weak_web_title(title, url):
            title = _web_host(url) or url
        if _is_non_article_evidence(url, title) or _is_weak_web_title(title, url):
            continue
        merged.append(
            TodayEvidenceInput(
                source_type=item.source_type,
                reference=item.reference,
                url=item.url,
                title=title,
                excerpt=item.excerpt,
                occurred_at=item.occurred_at,
            )
        )
    return merged


def _web_host(url: str) -> str:
    return urlsplit(url).netloc.removeprefix("www.")


def _meaningful_web_title(value: str, url: str) -> bool:
    label = re.sub(r"^(?:来源|source)\s*[:：]?", "", value, flags=re.IGNORECASE)
    host = _web_host(url)
    if host:
        label = re.sub(re.escape(host), "", label, flags=re.IGNORECASE)
    label = re.sub(r"[\s·|｜:：;；,，.。!?！？()（）\[\]【】<>《》\-—–]+", "", label)
    return bool(label)


def _article_snapshot_item(content: str) -> tuple[str, str] | None:
    """Read one article page's visible heading and canonical source URL."""
    source = _PAGE_SOURCE_URL_RE.search(content)
    if source is None:
        return None
    heading = _PAGE_HEADING_RE.search(content[: source.start()])
    if heading is None:
        return None
    title = _clean_fact_title(
        _MARKDOWN_LINK_RE.sub(r"\1", heading.group(1)).strip(" #*_")
    )
    url = _clean_url(source.group(1))
    if (
        not title
        or _is_weak_web_title(title, url)
        or _is_non_article_evidence(url, title)
    ):
        return None
    return title, url


def _web_evidence(text: str, *, occurred_at: int, limit: int = 99) -> list[TodayEvidenceInput]:
    items: list[TodayEvidenceInput] = []
    seen: set[str] = set()
    title_counts: dict[str, int] = {}
    for raw_line in text.splitlines():
        for segment in _SENTENCE_SPLIT_RE.split(raw_line):
            for match in _URL_RE.finditer(segment):
                url = _clean_url(match.group(0))
                if not url or url in seen:
                    continue
                seen.add(url)
                label = _web_title(raw_line, segment)
                if not _meaningful_web_title(label, url):
                    label = _web_host(url) or url
                title_key = label.lower()
                if title_counts.get(title_key, 0):
                    host = _web_host(url) or "来源"
                    if label.lower() != host.lower():
                        label = _compact(f"{label} · {host}", 120)
                title_counts[title_key] = title_counts.get(title_key, 0) + 1
                items.append(
                    TodayEvidenceInput(
                        source_type="web",
                        reference=url,
                        url=url,
                        title=label,
                        occurred_at=occurred_at - len(items),
                    )
                )
                if len(items) >= limit:
                    return items
    return items


def _embedded_web_items(value: Any, *, limit: int = 12) -> list[tuple[str, str]]:
    """Extract titled article links from connector JSON without treating image URLs as sources."""
    items: list[tuple[str, str]] = []
    seen: set[str] = set()

    def visit(node: Any) -> bool:
        if isinstance(node, dict):
            title = str(
                node.get("title")
                or node.get("full_name")
                or ""
            ).strip()
            raw_url = node.get("url") or node.get("link")
            if title and isinstance(raw_url, str):
                url = _clean_url(raw_url)
                host = urlsplit(url).netloc.lower()
                if (
                    url.startswith(("http://", "https://"))
                    and "gtimg.com" not in host
                    and not _is_non_article_evidence(url, title)
                    and url not in seen
                ):
                    seen.add(url)
                    source = _compact(node.get("source") or "", 60)
                    label = f"{source}：{title}" if source else title
                    items.append((_compact(label, 120), url))
                    if len(items) >= limit:
                        return True
            return any(visit(child) for child in node.values())
        if isinstance(node, list):
            return any(visit(child) for child in node)
        return False

    visit(value)
    return items


def _thread_web_evidence(
    db: DatabasePool,
    *,
    thread_id: str,
    occurred_at: int,
    since: int,
    limit: int = 12,
) -> list[TodayEvidenceInput]:
    """Use the connector output as source evidence when the model omits links from its answer."""
    with db.connect() as conn:
        last_human = conn.execute(
            "SELECT seq FROM thread_messages "
            "WHERE thread_id = ? AND role = 'human' "
            "ORDER BY seq DESC LIMIT 1",
            (thread_id,),
        ).fetchone()
        if last_human is not None:
            rows = conn.execute(
                "SELECT message_json FROM thread_messages "
                "WHERE thread_id = ? AND role = 'tool' AND seq > ? "
                "ORDER BY seq DESC LIMIT 80",
                (thread_id, last_human["seq"]),
            ).fetchall()
        else:
            rows = conn.execute(
                "SELECT message_json FROM thread_messages "
                "WHERE thread_id = ? AND role = 'tool' AND created_at >= ? "
                "ORDER BY seq DESC LIMIT 40",
                (thread_id, since),
            ).fetchall()

    evidence: list[TodayEvidenceInput] = []
    seen: set[str] = set()
    for row in rows:
        content = _message_content(str(row["message_json"]))
        if not content:
            continue
        items: list[tuple[str, str]] = []
        try:
            items = _embedded_web_items(json.loads(content), limit=limit)
        except (TypeError, json.JSONDecodeError):
            pass
        if not items:
            article = _article_snapshot_item(content)
            if article is not None:
                items.append(article)
        if not items:
            items.extend(
                (str(item.title or item.url or item.reference), str(item.url or item.reference))
                for item in _web_evidence(content, occurred_at=occurred_at, limit=limit)
                if not _is_non_article_evidence(
                    str(item.url or item.reference),
                    str(item.title or ""),
                )
            )
        for title, url in items:
            if (
                not url
                or url in seen
                or _is_non_article_evidence(url, title)
                or _is_weak_web_title(title, url)
            ):
                continue
            seen.add(url)
            evidence.append(
                TodayEvidenceInput(
                    source_type="web",
                    reference=url,
                    url=url,
                    title=title,
                    excerpt="",
                    occurred_at=occurred_at - len(evidence),
                )
            )
            if len(evidence) >= limit:
                return evidence
    return evidence


def _resolved_evidence_rows(
    items: list[TodayEvidenceInput],
    *,
    occurred_at: int,
) -> list[tuple[Any, ...]]:
    rows: list[tuple[Any, ...]] = []
    for item in items:
        if item.source_type == "conversation":
            source_type = "conversation"
            source_record_id = item.reference
            event_type = "conversation.context"
            privacy_class = "private_user_content"
            url = None
        else:
            url = str(item.url or item.reference)
            source_type = "web_search"
            source_record_id = url
            event_type = "web.search_result"
            privacy_class = "public_web"
        evidence_id = hashlib.sha256(
            f"{source_type}|{item.reference}|{url or ''}".encode()
        ).hexdigest()
        rows.append(
            (
                evidence_id,
                source_type,
                source_record_id,
                event_type,
                int(item.occurred_at) if item.occurred_at else occurred_at,
                privacy_class,
                url,
                item.title,
                item.excerpt,
            )
        )
    return rows


def _dismiss_automatic_kind(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    task_kind: str,
    now: int,
) -> None:
    auto_kind = _AUTO_KIND_BY_TASK_KIND.get(task_kind)
    if auto_kind is None:
        return
    with db.transaction() as conn:
        conn.execute(
            "UPDATE today_cards SET lifecycle = 'dismissed', snoozed_until = NULL, updated_at = ? "
            "WHERE agent_id = ? AND user_id = ? AND dedupe_key LIKE ? "
            "AND lifecycle != 'dismissed'",
            (now, agent_id, user_id, f"auto:{auto_kind}:%"),
        )


def has_cron_card_kind(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
    kind: str,
) -> bool:
    """Whether an enabled scheduled task owns this category in Today."""
    with db.connect() as conn:
        row = conn.execute(
            "SELECT 1 FROM today_cards c "
            "JOIN cron_jobs j ON j.agent_id = c.agent_id AND j.user_id = c.user_id "
            "AND (c.dedupe_key = 'cron:' || j.cron_id "
            "OR c.dedupe_key = 'agent:cron:' || j.cron_id) "
            "WHERE c.agent_id = ? AND c.user_id = ? AND c.kind = ? AND j.enabled = 1 LIMIT 1",
            (agent_id, user_id, kind),
        ).fetchone()
    return row is not None


def publish_cron_task_card(
    db: DatabasePool,
    *,
    cron_id: str,
    task_name: str,
    prompt: str,
    agent_id: str,
    user_id: int,
    thread_id: str,
    text: str,
    last_run_at: int,
    status: str = "ok",
    error: str | None = None,
    mark_unread: bool = False,
) -> dict[str, Any]:
    """Publish one stable card per task and link it to the canonical run thread."""
    task_kind = classify_cron_kind(task_name, prompt)
    kind = _TODAY_KIND_BY_TASK_KIND.get(task_kind, task_kind)
    if status != "ok":
        return {
            "card_id": None,
            "changed": False,
            "notify": False,
            "kind": kind,
        }

    with db.connect() as conn:
        thread = conn.execute(
            "SELECT title FROM threads "
            "WHERE thread_id = ? AND agent_id = ? AND user_id = ?",
            (thread_id, agent_id, user_id),
        ).fetchone()
    if thread is None:
        raise ValueError("cron result thread does not belong to this agent and user")

    if not cron_result_has_new_information(text, task_kind=task_kind):
        return {
            "card_id": None,
            "changed": False,
            "notify": False,
            "kind": kind,
        }
    title = cron_card_title(
        task_kind,
        task_name or str(thread["title"] or ""),
        text,
    )
    summary = task_result_summary(
        text,
        task_name=title,
        status=status,
        error=error,
        kind=task_kind,
    )
    web_evidence = _web_evidence(text, occurred_at=last_run_at)
    if task_kind in {"news", "signal"}:
        web_evidence = _merge_web_evidence(
            web_evidence,
            _thread_web_evidence(
                db,
                thread_id=thread_id,
                occurred_at=last_run_at,
                since=last_run_at,
            ),
        )
    if task_kind == "signal" and status == "ok" and (not summary or not web_evidence):
        return {
            "card_id": None,
            "changed": False,
            "notify": False,
            "kind": kind,
        }
    if task_kind == "inbox":
        pending_detail = _mail_pending_detail(text)
        evidence = _mail_contact_evidence(
            pending_detail,
            thread_id=thread_id,
            occurred_at=last_run_at,
        )
        if not evidence:
            evidence = [
                TodayEvidenceInput(
                    source_type="conversation",
                    reference=thread_id,
                    title="邮件汇总结果",
                    excerpt="完整邮件明细已整理到卡片详情。",
                    occurred_at=last_run_at,
                )
            ]
    else:
        evidence = [
            *web_evidence,
            TodayEvidenceInput(
                source_type="conversation",
                reference=thread_id,
                title="定时任务结果",
                excerpt="完整执行记录、过程与来源保留在会话中。",
                occurred_at=last_run_at,
            ),
        ]
    draft = TodayCardDraft(
        dedupe_key=f"cron:{cron_id}",
        kind=kind,
        title=title,
        summary=summary,
        why_now=(
            "这条日间信号只在出现值得比较的新进展时生成，用来补充早间新闻简报。"
            if task_kind == "signal"
            else "这是该定时任务最近一次运行的结果，已同步到 Today。"
        ),
        action_prompt=(
            f"查看「{title}」的本次执行结果；如需继续处理，请基于现有结果推进，并保留来源。"
        ),
        risk_level="A1",
        evidence=evidence,
        confidence=0.98,
        occurred_at=last_run_at,
    )
    publisher = TodayCardPublisher(db, agent_repo=AgentRepo(db))
    published = publisher.publish(draft, agent_id=agent_id, user_id=user_id)
    card_id = str(published["card_id"])
    action_state = "completed" if status == "ok" else "failed"

    with db.transaction() as conn:
        current = conn.execute(
            "SELECT lifecycle, snoozed_until, execution_thread_id, action_state, action_error, "
            "action_updated_at "
            "FROM today_cards WHERE agent_id = ? AND card_id = ?",
            (agent_id, card_id),
        ).fetchone()
        assert current is not None
        now = int(time.time())
        should_link = (
            str(current["execution_thread_id"] or "") != thread_id
            or str(current["action_state"] or "") != action_state
            or (str(current["action_error"] or "") or None) != (error or None)
            or int(current["action_updated_at"] or 0) != int(last_run_at)
        )
        should_reopen = (
            mark_unread
            and int(current["snoozed_until"] or 0) <= now
            and (
                str(current["lifecycle"]) != "unread"
                or int(current["action_updated_at"] or 0) != int(last_run_at)
            )
        )
        if should_link or should_reopen:
            conn.execute(
                "UPDATE today_cards SET "
                "execution_thread_id = ?, execution_turn_id = NULL, action_state = ?, "
                "action_error = ?, action_updated_at = ?, "
                "lifecycle = CASE WHEN ? THEN 'unread' ELSE lifecycle END, "
                "snoozed_until = CASE WHEN ? THEN NULL ELSE snoozed_until END, "
                "updated_at = ? WHERE agent_id = ? AND card_id = ?",
                (
                    thread_id,
                    action_state,
                    error,
                    last_run_at,
                    1 if should_reopen else 0,
                    1 if should_reopen else 0,
                    now if should_reopen else int(published["updated_at"]),
                    agent_id,
                    card_id,
                ),
            )
        desired_evidence = _resolved_evidence_rows(evidence, occurred_at=last_run_at)
        existing_evidence = [
            (
                str(row["evidence_id"]),
                str(row["source_type"]),
                str(row["source_record_id"] or ""),
                str(row["event_type"]),
                int(row["occurred_at"]),
                str(row["privacy_class"]),
                str(row["url"] or ""),
                str(row["title"] or ""),
                str(row["excerpt"] or ""),
            )
            for row in conn.execute(
                "SELECT evidence_id, source_type, source_record_id, event_type, occurred_at, "
                "privacy_class, url, title, excerpt FROM today_card_evidence "
                "WHERE agent_id = ? AND card_id = ? ORDER BY evidence_id",
                (agent_id, card_id),
            ).fetchall()
        ]
        comparable_existing = sorted([
            (item[0], item[1], item[2], item[3], item[4], item[5], item[6], item[7], item[8] or "")
            for item in existing_evidence
        ])
        comparable_desired = sorted([
            (item[0], item[1], item[2], item[3], item[4], item[5], item[6], item[7] or "", item[8] or "")
            for item in desired_evidence
        ])
        if comparable_existing != comparable_desired:
            conn.execute(
                "DELETE FROM today_card_evidence WHERE agent_id = ? AND card_id = ?",
                (agent_id, card_id),
            )
            created_at = int(time.time())
            for item in desired_evidence:
                conn.execute(
                    "INSERT INTO today_card_evidence("
                    "agent_id, card_id, evidence_id, source_type, source_record_id, "
                    "event_type, occurred_at, privacy_class, url, title, excerpt, created_at"
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (agent_id, card_id, *item, created_at),
                )

    _dismiss_automatic_kind(
        db,
        agent_id=agent_id,
        user_id=user_id,
        task_kind=task_kind,
        now=max(int(time.time()), int(last_run_at)),
    )
    return {
        "card_id": card_id,
        "changed": bool(published["changed"]),
        "notify": bool(published["changed"] or should_reopen),
        "kind": kind,
    }


def sync_completed_cron_today_cards(
    db: DatabasePool,
    *,
    agent_id: str,
    user_id: int,
) -> list[str]:
    """Backfill cards for completed task runs, including runs from before this patch."""
    with db.connect() as conn:
        rows = conn.execute(
            "SELECT j.cron_id, j.name, j.prompt, j.last_run_at, j.last_status, "
            "t.thread_id FROM cron_jobs j JOIN threads t "
            "ON t.agent_id = j.agent_id AND t.user_id = j.user_id "
            "AND t.session_key = j.session_key "
            "WHERE j.agent_id = ? AND j.user_id = ? AND j.last_run_at IS NOT NULL "
            "AND j.last_status = 'ok' ORDER BY j.last_run_at DESC",
            (agent_id, user_id),
        ).fetchall()

    changed: list[str] = []
    for row in rows:
        try:
            thread_id = str(row["thread_id"])
            status = str(row["last_status"])
            text = last_ai_text(db, thread_id)
            if not text:
                logger.warning(
                    "skip Today card for cron=%s: result thread has no assistant text",
                    row["cron_id"],
                )
                continue
            result = publish_cron_task_card(
                db,
                cron_id=str(row["cron_id"]),
                task_name=str(row["name"]),
                prompt=str(row["prompt"]),
                agent_id=agent_id,
                user_id=user_id,
                thread_id=thread_id,
                text=text,
                last_run_at=int(row["last_run_at"]),
                status=status,
                mark_unread=False,
            )
            if result["changed"]:
                changed.append(str(result["card_id"]))
        except Exception:
            logger.warning(
                "failed to sync Today card for cron=%s",
                row["cron_id"],
                exc_info=True,
            )
    return changed


__all__ = [
    "classify_cron_kind",
    "cron_card_title",
    "cron_result_has_new_information",
    "has_cron_card_kind",
    "last_ai_text",
    "publish_cron_task_card",
    "sync_completed_cron_today_cards",
    "task_result_summary",
]
