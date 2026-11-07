"""Read-only Feishu group monitoring that feeds Today and phone alerts.

The monitor never posts to a group. It reads whitelisted chats with the
user-scoped lark-cli identity, folds messages into stable events, and only
surfaces the events that need a decision. Delivery is split in two layers:

* Today card: always updated so the item is inspectable later.
* Phone push: ``p0`` immediately, ``p1`` inside digest windows, silent hours
  respected. ``p2`` stays in Today, ``p3`` is dropped.

Rules live in pure functions at the top of this file so they can be tested
without Feishu, SQLite, or a running server.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import shutil
import sqlite3
import subprocess
import time
from collections import defaultdict
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, time as clock_time, timedelta
from pathlib import Path
from typing import Any, Iterable
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

logger = logging.getLogger(__name__)

DEFAULT_TZ = "Asia/Shanghai"
DEFAULT_AGENT_ID = "18CCXQ"
DEFAULT_USER_ID = 2
DEFAULT_CLI_ROOT = Path("/opt/octop/connector-cli/feishu-cli")

# Read-only whitelist. Names are display hints; chat_id is the authority.
DEFAULT_WATCHES: tuple[tuple[str, str], ...] = (
    ("oc_f44785115ccba7930364f636a5a44688", "TRAE 用户交流群 1️⃣3️⃣｜官方"),
    ("oc_f084163bd542236e69cf34f4971823cc", "TRAE 用户交流群 8️⃣｜官方"),
    ("oc_24b073dee065b3cb000f3fb3fc517808", "TRAE SOLO 用户交流群"),
    ("oc_e00d51742507cbba3ec0453d83bebc3c", "MiniMax开放平台用户交流群1️⃣"),
    ("oc_3a98463e8750aa74d26012c5517bc41a", "火山引擎大模型技术交流1群"),
)

SCAN_INTERVAL_SECONDS = 20 * 60
FIRST_SCAN_WINDOW_HOURS = 14
RESCAN_OVERLAP_HOURS = 3
MAX_MESSAGES_PER_CHAT = 200
PUSH_DEDUPE_SECONDS = 24 * 60 * 60
MAX_IMMEDIATE_PUSHES_PER_DAY = 3

QUIET_START = clock_time(22, 30)
QUIET_END = clock_time(7, 30)
DIGEST_WINDOWS = (
    (clock_time(7, 45), clock_time(9, 15)),
    (clock_time(13, 45), clock_time(15, 0)),
    (clock_time(21, 15), clock_time(22, 30)),
)

PRIORITY_RANK = {"p0": 0, "p1": 1, "p2": 2, "p3": 3}
PRIORITY_PREFIX = {
    "p0": "【需处理】",
    "p1": "【关注】",
    "p2": "【仅入 Today】",
    "p3": "",
}

# Ordered: first match wins, so specific topics stay ahead of generic ones.
TOPIC_RULES: tuple[tuple[tuple[str, ...], str], ...] = (
    (("积分", "扣费", "到账", "账单", "退款", "发票"), "积分扣费与到账"),
    (("套餐", "订阅", "续费", "会员"), "套餐与订阅"),
    (("配额", "额度", "限流", "并发", "调用量"), "配额与限流"),
    (("价格", "计费", "收费", "涨价", "降价"), "价格与计费"),
    (("审核", "上架", "驳回", "合规"), "审核与发布"),
    (("故障", "宕机", "不可用", "超时", "报错", "异常", "失败"), "服务异常"),
    (("泄露", "安全", "风控", "封号", "被盗"), "安全与风控"),
    (("发布", "上线", "新版本", "内测", "公测"), "版本发布"),
    (("开源", "模型", "权重", "论文"), "模型与开源"),
)

PAYMENT_WORDS = (
    "积分", "扣费", "退款", "到账", "账单", "权益", "续费", "支付", "价格",
    "套餐", "配额", "额度", "限流", "计费",
)
INCIDENT_WORDS = (
    "故障", "宕机", "不可用", "挂了", "崩了", "泄露", "封号", "被盗", "登录不了",
)
DEADLINE_WORDS = ("今晚", "截止", "最后一天", "马上到期")
COMPLAINT_WORDS = (
    "为什么", "合理吗", "没到账", "没收到", "不对", "投诉", "争议", "不给",
    "拖", "没人管", "不回复", "自己承担",
)
DISPUTE_WORDS = (
    "不合理", "投诉", "争议", "没人管", "不回复", "没有回复", "未回复", "无人回复",
    "无法确认", "无法处理", "没有权限", "不给答复",
)
RESOLVED_WORDS = ("已解决", "已修复", "已恢复", "已到账", "已处理", "已补偿")
UNRESOLVED_MARKERS = ("未解决", "无法确认", "无法处理", "没有权限", "尚未")
OFFICIAL_NAME_HINTS = ("官方", "小助手", "助理", "客服", "Bot", "bot", "机器人")

_TAG_RE = re.compile(r"<[^>]+>")
_LINK_RE = re.compile(r"\[([^\]]*)\]\((https?://[^)]+)\)")
_IMAGE_RE = re.compile(r"!\[[^\]]*\]\([^)]*\)")
_SPACE_RE = re.compile(r"[ \t\u3000]+")
_BOILERPLATE_RE = re.compile(
    r"(更多使用问题|官方社区|已解决\]|更多问题|本条消息由|自动回复|退订)", re.IGNORECASE
)
_NOISE_LINE_RE = re.compile(r"^[·\-—\s]*[0-9a-f]{20,}[·\-\s]*$", re.IGNORECASE)
_MENTION_ME_RE = re.compile(r"@(?:我|武老师|Larens|健|all)\b", re.IGNORECASE)
_ERROR_CODE_RE = re.compile(r"(?:返回|报错|error)[\s:：-]*[-－]?\d{2,5}", re.IGNORECASE)


@dataclass(frozen=True)
class RawMessage:
    message_id: str
    chat_id: str
    thread_id: str | None
    sender_id: str
    sender_name: str
    sender_type: str
    is_official: bool
    created_at: int
    text: str
    link: str | None


@dataclass
class EventDraft:
    event_key: str
    chat_id: str
    chat_name: str
    thread_id: str | None
    topic: str
    priority: str
    state: str
    title: str
    summary: str
    why_now: str
    action: str
    evidence_url: str | None
    first_seen_at: int
    last_message_at: int
    official_reply_at: int | None
    resolved: bool
    reasons: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class PushIntent:
    user_id: int
    agent_id: str
    payload: dict[str, Any]
    dm_text: str


def local_zone(name: str | None = None) -> ZoneInfo:
    try:
        return ZoneInfo(name or os.environ.get("OCTOP_FEISHU_MONITOR_TZ", DEFAULT_TZ))
    except ZoneInfoNotFoundError:
        return ZoneInfo("UTC")


def parse_message_time(value: str, zone: ZoneInfo) -> int:
    text = str(value or "").strip()
    for pattern in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            parsed = datetime.strptime(text, pattern)
            return int(parsed.replace(tzinfo=zone).timestamp())
        except ValueError:
            continue
    return 0


def clean_text(text: str, limit: int = 220) -> str:
    value = str(text or "")
    value = _TAG_RE.sub(" ", value)
    value = _IMAGE_RE.sub(" ", value)
    value = _LINK_RE.sub(r"\1", value)
    value = value.replace("**", "").replace("🔗", " ")
    value = value.replace("\\n", "\n").replace("\r", "\n")
    lines: list[str] = []
    for raw in value.split("\n"):
        line = _SPACE_RE.sub(" ", raw).strip(" -*_`|")
        if not line or _BOILERPLATE_RE.search(line) or _NOISE_LINE_RE.match(line):
            continue
        lines.append(line)
    joined = " ".join(lines)
    joined = _SPACE_RE.sub(" ", joined).strip()
    if len(joined) > limit:
        joined = joined[: limit - 1].rstrip("，。；、 ") + "…"
    return joined


def _post_text(payload: Any) -> str:
    if isinstance(payload, dict):
        body = payload.get("zh_cn") or payload.get("en_us") or payload
        text = body.get("text") if isinstance(body, dict) else None
        if isinstance(text, str) and text.strip():
            return text
        content = body.get("content") if isinstance(body, dict) else None
        if isinstance(content, list):
            parts = []
            for row in content:
                if not isinstance(row, list):
                    continue
                for cell in row:
                    if isinstance(cell, dict) and isinstance(cell.get("text"), str):
                        parts.append(cell["text"])
            return " ".join(parts)
    return ""


def message_text(content: str, msg_type: str) -> str:
    raw = str(content or "")
    if msg_type == "post" and raw.lstrip().startswith("{"):
        try:
            return _post_text(json.loads(raw))
        except (ValueError, TypeError):
            return raw
    if msg_type == "interactive":
        return raw
    return raw


def is_official_sender(sender: dict[str, Any]) -> bool:
    sender_type = str(sender.get("sender_type") or "").lower()
    id_type = str(sender.get("id_type") or "").lower()
    name = str(sender.get("name") or "")
    if sender_type in {"app", "bot"} or id_type == "app_id":
        return True
    return any(hint in name for hint in OFFICIAL_NAME_HINTS)


def flatten_messages(payload: dict[str, Any], chat_id: str, zone: ZoneInfo) -> list[RawMessage]:
    rows: list[RawMessage] = []
    for item in payload.get("messages") or []:
        if not isinstance(item, dict) or item.get("deleted"):
            continue
        base = {
            "chat_id": str(item.get("chat_id") or chat_id),
            "thread_id": str(item.get("thread_id") or "") or None,
            "link": str(item.get("message_app_link") or "") or None,
        }
        rows.append(_raw_message(item, base, zone))
        for reply in item.get("thread_replies") or []:
            if not isinstance(reply, dict) or reply.get("deleted"):
                continue
            rows.append(
                _raw_message(
                    reply,
                    {
                        "chat_id": base["chat_id"],
                        "thread_id": base["thread_id"],
                        "link": str(reply.get("message_app_link") or base["link"] or "") or None,
                    },
                    zone,
                )
            )
    return [row for row in rows if row.message_id and row.text]


def _raw_message(item: dict[str, Any], base: dict[str, Any], zone: ZoneInfo) -> RawMessage:
    sender = item.get("sender") if isinstance(item.get("sender"), dict) else {}
    return RawMessage(
        message_id=str(item.get("message_id") or ""),
        chat_id=str(base["chat_id"]),
        thread_id=base["thread_id"],
        sender_id=str(sender.get("id") or ""),
        sender_name=str(sender.get("name") or "群成员"),
        sender_type=str(sender.get("sender_type") or "user"),
        is_official=is_official_sender(sender),
        created_at=parse_message_time(str(item.get("create_time") or ""), zone),
        text=clean_text(message_text(str(item.get("content") or ""), str(item.get("msg_type") or "text"))),
        link=base["link"],
    )


def short_group_name(name: str) -> str:
    value = str(name or "").strip()
    indexes = [
        value.find(marker)
        for marker in (
        "用户交流群", "开放平台", "大模型技术交流", "技术交流", "交流群", "官方",
        "互助群", "共建群",
        )
    ]
    positive = [index for index in indexes if index > 0]
    if positive:
        value = value[: min(positive)]
    value = re.sub(r"[\d️⃣|｜\s]+$", "", value).strip(" -·")
    return value[:18] or "飞书群"


def topic_of(text: str) -> str | None:
    if _ERROR_CODE_RE.search(text):
        return "服务异常"
    for words, label in TOPIC_RULES:
        if any(word in text for word in words):
            return label
    return None


def event_key_for(chat_id: str, thread_id: str | None, topic: str) -> str:
    anchor = f"topic:{topic}" if topic and topic != "其他" else (thread_id or "topic:其他")
    return f"{chat_id}:{anchor}"


def is_resolved_text(text: str) -> bool:
    if any(marker in text for marker in UNRESOLVED_MARKERS):
        return False
    return any(word in text for word in RESOLVED_WORDS)


def classify(
    messages: Iterable[RawMessage],
    *,
    now: int,
    first_seen_at: int,
    mention_me: bool,
    official_reply_at: int | None,
    resolved: bool,
) -> tuple[str, list[str]]:
    rows = list(messages)
    humans = [row for row in rows if not row.is_official]
    official = [row for row in rows if row.is_official]
    human_text = " ".join(row.text for row in humans)
    age_hours = max(0.0, (now - first_seen_at) / 3600)
    unique_users = {row.sender_id or row.sender_name for row in humans}

    has_payment = any(word in human_text for word in PAYMENT_WORDS)
    has_incident = any(word in human_text for word in INCIDENT_WORDS)
    has_deadline = any(word in human_text for word in DEADLINE_WORDS)
    has_dispute = any(word in human_text for word in DISPUTE_WORDS)
    has_complaint = any(word in human_text for word in COMPLAINT_WORDS)
    has_error_code = bool(_ERROR_CODE_RE.search(human_text))
    official_unhelpful = bool(official) and not resolved
    repeat = len(unique_users) >= 2
    reasons: list[str] = []

    if resolved:
        return "p2", ["官方已给出解决口径"]
    if mention_me:
        return "p0", ["群里直接点到了你"]
    if has_deadline:
        return "p0", ["涉及 24 小时内的时间点"]
    if (has_incident or has_error_code) and not official:
        return "p0", ["服务异常且官方未回应"]
    if has_payment and (has_complaint or has_dispute):
        reasons.append(f"涉及扣费/配额且已持续 {int(age_hours)} 小时")
        if official_unhelpful:
            reasons.append("官方回复未给出可执行口径")
        if repeat:
            reasons.append(f"{len(unique_users)} 名独立用户反馈")
        return "p0", reasons
    if has_payment and repeat and official_unhelpful:
        return "p0", ["多人反馈扣费问题，官方未给出可执行口径"]
    if has_incident or has_error_code:
        return "p1", ["影响在用工具的可用性"]
    if has_payment and repeat:
        return "p1", ["多名独立用户反馈付费或配额问题"]
    if official_unhelpful and has_dispute:
        return "p1", ["官方已回应但问题仍在"]
    if repeat:
        return "p1", ["多名独立用户持续反馈"]
    if official_reply_at:
        return "p1", ["官方已进入该话题"]
    if humans and len(str(humans[0].text)) >= 12:
        return "p2", ["单条使用反馈"]
    return "p3", ["信息量不足"]


def event_state(
    *,
    previous: dict[str, Any] | None,
    official_reply_at: int | None,
    resolved: bool,
    first_seen_at: int,
    now: int,
) -> str:
    if resolved:
        return "resolved"
    if previous is None:
        return "new"
    previous_official = previous.get("official_reply_at")
    if official_reply_at and int(official_reply_at) != int(previous_official or 0):
        return "official_reply"
    if now - first_seen_at >= 24 * 3600 and not official_reply_at:
        return "stale"
    return "updated"


def is_quiet_hours(now: int, zone: ZoneInfo) -> bool:
    current = datetime.fromtimestamp(now, tz=zone).time()
    if QUIET_START <= QUIET_END:
        return QUIET_START <= current < QUIET_END
    return current >= QUIET_START or current < QUIET_END


def in_digest_window(now: int, zone: ZoneInfo) -> bool:
    current = datetime.fromtimestamp(now, tz=zone).time()
    return any(start <= current < end for start, end in DIGEST_WINDOWS)


def should_push(
    *,
    priority: str,
    state: str,
    now: int,
    zone: ZoneInfo,
    last_push_at: int | None,
    immediate_pushes_today: int,
    deadline_or_incident: bool,
) -> tuple[bool, str]:
    if priority in {"p2", "p3"} or state == "resolved":
        return False, "不需要即时提醒"
    if last_push_at and now - int(last_push_at) < PUSH_DEDUPE_SECONDS:
        return False, "24 小时内已提醒过"
    quiet = is_quiet_hours(now, zone)
    if priority == "p0":
        if quiet and not deadline_or_incident:
            return False, "静默时段，等早间汇总"
        if immediate_pushes_today >= MAX_IMMEDIATE_PUSHES_PER_DAY:
            return False, "今日即时提醒已达 3 条"
        return True, "需处理"
    if quiet:
        return False, "静默时段"
    if not in_digest_window(now, zone):
        return False, "等下一个汇总窗口"
    return True, "汇总提醒"


def summary_line(message: RawMessage, *, official: bool, limit: int = 130) -> str:
    prefix = f"官方（{message.sender_name}）" if official else message.sender_name
    text = clean_text(message.text, limit=limit)
    return f"{prefix}：{text}"


def pick_key_message(rows: list[RawMessage]) -> RawMessage:
    """Prefer the message that carries the most actionable signal, not the newest."""

    def score(row: RawMessage) -> tuple[int, int]:
        hits = sum(
            word in row.text
            for word in PAYMENT_WORDS + INCIDENT_WORDS + DISPUTE_WORDS
        )
        return hits, min(len(row.text), 200)

    return max(rows, key=lambda row: (score(row), row.created_at))


def build_card_text(event: EventDraft, *, zone: ZoneInfo) -> tuple[str, str]:
    updated = datetime.fromtimestamp(event.last_message_at, tz=zone).strftime("%m-%d %H:%M")
    summary = f"{event.summary}\n最近更新：{updated}。"
    action = (
        f"查看群内原始消息：{event.evidence_url}\n"
        f"最小一步：{event.action}\n"
        "（只读监测，未在任何群里回复。）"
    )
    return summary, action


class FeishuCliError(RuntimeError):
    pass


class _ClosingConnection(sqlite3.Connection):
    def __exit__(self, exc_type, exc, tb):  # type: ignore[no-untyped-def]
        try:
            return super().__exit__(exc_type, exc, tb)
        finally:
            self.close()


class DatabaseProxy:
    """Minimal DatabasePool stand-in for standalone runs and tests."""

    def __init__(self, path: str | Path) -> None:
        self._path = Path(path)

    def connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self._path, factory=_ClosingConnection)
        conn.row_factory = sqlite3.Row
        return conn

    @contextmanager
    def transaction(self):
        conn = self.connect()
        try:
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()


class FeishuCli:
    """Thin lark-cli wrapper. Only read commands are exposed."""

    def __init__(self, config_dir: Path | None = None, binary: str | None = None) -> None:
        self._config_dir = config_dir
        self._binary = binary or next(
            (
                path
                for path in (
                    shutil.which("lark-cli"),
                    "/usr/bin/lark-cli",
                    "/usr/local/bin/lark-cli",
                )
                if path and Path(path).exists()
            ),
            "lark-cli",
        )

    @property
    def config_dir(self) -> Path:
        if self._config_dir is not None:
            return self._config_dir
        explicit = os.environ.get("OCTOP_FEISHU_CLI_DIR")
        if explicit:
            self._config_dir = Path(explicit)
            return self._config_dir
        root = Path(os.environ.get("OCTOP_FEISHU_CLI_ROOT", str(DEFAULT_CLI_ROOT)))
        candidates = [path for path in sorted(root.glob("*")) if path.is_dir()]
        if not candidates:
            raise FeishuCliError(f"no feishu-cli config under {root}")
        self._config_dir = candidates[0]
        return self._config_dir

    def _run(self, args: list[str], *, timeout: float = 60.0) -> dict[str, Any]:
        env = os.environ.copy()
        env["LARKSUITE_CLI_CONFIG_DIR"] = str(self.config_dir)
        proc = subprocess.run(
            [self._binary, *args],
            capture_output=True,
            text=True,
            timeout=timeout,
            env=env,
            check=False,
        )
        if proc.returncode != 0:
            raise FeishuCliError((proc.stderr or proc.stdout or "lark-cli failed").strip()[:400])
        try:
            payload = json.loads(proc.stdout)
        except ValueError as exc:
            raise FeishuCliError("lark-cli returned non-JSON output") from exc
        if not payload.get("ok", False):
            message = payload.get("error") or payload.get("message") or "lark-cli returned ok=false"
            raise FeishuCliError(str(message)[:400])
        return payload.get("data") or {}

    def messages(self, chat_id: str, *, since: int, zone: ZoneInfo) -> list[dict[str, Any]]:
        start = datetime.fromtimestamp(since, tz=zone).isoformat()
        data = self._run(
            [
                "im", "+chat-messages-list",
                "--as", "user",
                "--chat-id", chat_id,
                "--start", start,
                "--order", "desc",
                "--page-size", "50",
                "--page-all",
                "--page-limit", "4",
                "--no-reactions",
                "--json",
            ],
            timeout=90.0,
        )
        messages = data.get("messages") or []
        return messages[:MAX_MESSAGES_PER_CHAT]

    def direct_message(self, open_id: str, text: str) -> None:
        self._run(
            [
                "im", "+messages-send",
                "--as", "bot",
                "--user-id", open_id,
                "--text", text,
                "--json",
            ],
            timeout=45.0,
        )


def _row_get(row: Any, key: str, default: Any = None) -> Any:
    if row is None:
        return default
    try:
        value = row[key]
    except (KeyError, IndexError):
        return default
    return default if value is None else value


class FeishuGroupMonitor:
    """Collects whitelisted chats, folds them into events, publishes Today cards."""

    def __init__(
        self,
        db: Any,
        *,
        cli: Any | None = None,
        zone: ZoneInfo | None = None,
        dm_open_id: str | None = None,
    ) -> None:
        self._db = db
        self._cli = cli or FeishuCli()
        self._zone = zone or local_zone()
        self._dm_open_id = dm_open_id or os.environ.get(
            "OCTOP_FEISHU_OPEN_ID", "ou_938da0e8a0bb66a501b05ec53fef0370"
        )

    def ensure_default_watches(self) -> None:
        now = int(time.time())
        with self._db.transaction() as conn:
            existing = conn.execute("SELECT COUNT(*) AS total FROM feishu_group_watch").fetchone()
            if int(_row_get(existing, "total", 0) or 0) > 0:
                return
            for chat_id, chat_name in DEFAULT_WATCHES:
                conn.execute(
                    "INSERT INTO feishu_group_watch("
                    "chat_id, chat_name, agent_id, user_id, enabled, min_priority, "
                    "created_at, updated_at"
                    ") VALUES (?, ?, ?, ?, 1, 'p2', ?, ?)",
                    (chat_id, chat_name, DEFAULT_AGENT_ID, DEFAULT_USER_ID, now, now),
                )

    def watches(self, *, enabled_only: bool = True) -> list[Any]:
        query = "SELECT * FROM feishu_group_watch"
        if enabled_only:
            query += " WHERE enabled = 1"
        with self._db.connect() as conn:
            return conn.execute(query).fetchall()

    def collect(self, *, now: int | None = None) -> list[PushIntent]:
        now = int(now or time.time())
        intents: list[PushIntent] = []
        try:
            self.ensure_default_watches()
        except Exception:
            logger.warning("failed to seed feishu group watch list", exc_info=True)
        for watch in self.watches():
            try:
                intents.extend(self._collect_watch(watch, now=now))
            except Exception:
                logger.warning(
                    "feishu group scan failed chat=%s", _row_get(watch, "chat_id"), exc_info=True
                )
        return intents

    def _collect_watch(self, watch: Any, *, now: int) -> list[PushIntent]:
        chat_id = str(_row_get(watch, "chat_id"))
        chat_name = str(_row_get(watch, "chat_name"))
        agent_id = str(_row_get(watch, "agent_id", DEFAULT_AGENT_ID))
        user_id = int(_row_get(watch, "user_id", DEFAULT_USER_ID))
        last_scan = int(_row_get(watch, "last_scan_at", 0) or 0)
        window_start = (
            now - RESCAN_OVERLAP_HOURS * 3600
            if last_scan
            else now - FIRST_SCAN_WINDOW_HOURS * 3600
        )
        payload = {"messages": self._cli.messages(chat_id, since=window_start, zone=self._zone)}
        messages = flatten_messages(payload, chat_id, self._zone)
        if not messages:
            self._mark_scanned(chat_id, now)
            return []

        thread_topics: dict[str, str] = {}
        for message in sorted(messages, key=lambda row: row.created_at or 0):
            if message.thread_id and message.thread_id not in thread_topics:
                found = topic_of(message.text)
                if found:
                    thread_topics[message.thread_id] = found
        grouped: dict[str, list[RawMessage]] = defaultdict(list)
        group_topics: dict[str, str] = {}
        for message in messages:
            topic = (
                thread_topics.get(message.thread_id)
                if message.thread_id
                else topic_of(message.text)
            ) or topic_of(message.text)
            if message.thread_id is None and topic is None:
                continue
            key = event_key_for(chat_id, message.thread_id, topic or "其他")
            group_topics[key] = topic or "其他"
            grouped[key].append(message)

        intents: list[PushIntent] = []
        with self._db.transaction() as conn:
            for key, rows in grouped.items():
                rows.sort(key=lambda row: row.created_at or 0)
                intent = self._upsert_event(
                    conn,
                    event_key=key,
                    chat_id=chat_id,
                    chat_name=chat_name,
                    agent_id=agent_id,
                    user_id=user_id,
                    rows=rows,
                    topic=group_topics.get(key, "其他"),
                    now=now,
                )
                if intent is not None:
                    intents.append(intent)
            conn.execute(
                "UPDATE feishu_group_watch SET last_scan_at = ?, updated_at = ? WHERE chat_id = ?",
                (now, now, chat_id),
            )
        return intents

    def _mark_scanned(self, chat_id: str, now: int) -> None:
        with self._db.transaction() as conn:
            conn.execute(
                "UPDATE feishu_group_watch SET last_scan_at = ?, updated_at = ? WHERE chat_id = ?",
                (now, now, chat_id),
            )

    def _upsert_event(
        self,
        conn: Any,
        *,
        event_key: str,
        chat_id: str,
        chat_name: str,
        agent_id: str,
        user_id: int,
        rows: list[RawMessage],
        topic: str,
        now: int,
    ) -> PushIntent | None:
        previous = conn.execute(
            "SELECT * FROM feishu_group_event WHERE event_key = ?", (event_key,)
        ).fetchone()
        seen_ids = {
            str(item["message_id"])
            for item in conn.execute(
                "SELECT message_id FROM feishu_group_message WHERE event_key = ?", (event_key,)
            ).fetchall()
        }
        fresh = [row for row in rows if row.message_id not in seen_ids]
        for row in fresh:
            conn.execute(
                "INSERT OR IGNORE INTO feishu_group_message("
                "message_id, chat_id, thread_id, event_key, sender_id, sender_name, "
                "sender_type, is_official, created_at, observed_at, digest"
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    row.message_id, row.chat_id, row.thread_id, event_key, row.sender_id,
                    row.sender_name, row.sender_type, int(row.is_official), row.created_at,
                    now, row.text[:500],
                ),
            )

        first_seen_at = int(_row_get(previous, "first_seen_at", 0) or 0) or min(
            row.created_at or now for row in rows
        )
        last_message_at = max(row.created_at or 0 for row in rows) or now
        official_rows = [row for row in rows if row.is_official]
        official_reply_at = max((row.created_at for row in official_rows), default=None)
        resolved = bool(official_rows) and is_resolved_text(official_rows[-1].text)
        mention_me = any(_MENTION_ME_RE.search(row.text) for row in rows)
        priority, reasons = classify(
            rows,
            now=now,
            first_seen_at=first_seen_at,
            mention_me=mention_me,
            official_reply_at=official_reply_at,
            resolved=resolved,
        )
        if topic == "其他" and priority == "p1" and not mention_me:
            priority, reasons = "p3", ["零散讨论，归不进明确话题"]
        if priority == "p3":
            return None
        state = event_state(
            previous=dict(previous) if previous is not None else None,
            official_reply_at=official_reply_at,
            resolved=resolved,
            first_seen_at=first_seen_at,
            now=now,
        )

        humans = [row for row in rows if not row.is_official]
        latest_human = pick_key_message(humans or rows)
        latest_official = official_rows[-1] if official_rows else None
        summary_parts = [f"事实：{summary_line(latest_human, official=False, limit=110)}"]
        if latest_official is not None:
            summary_parts.append(summary_line(latest_official, official=True, limit=170))
        summary = "\n".join(summary_parts)

        age_hours = max(0, int((now - first_seen_at) / 3600))
        why_now = f"该事项已持续 {age_hours} 小时"
        if official_reply_at:
            why_now += "，官方已回应但尚未给出可执行口径"
        why_now += "。" + _related_hint(conn, agent_id=agent_id, topic=topic, now=now)

        short = short_group_name(chat_name)
        title = f"{PRIORITY_PREFIX.get(priority, '')}{short} · {topic}"
        action = _next_step(priority=priority, topic=topic, resolved=resolved)
        evidence = next((row.link for row in reversed(rows) if row.link), None)
        draft = EventDraft(
            event_key=event_key,
            chat_id=chat_id,
            chat_name=chat_name,
            thread_id=next((row.thread_id for row in rows if row.thread_id), None),
            topic=topic,
            priority=priority,
            state=state,
            title=title[:120],
            summary=summary,
            why_now=why_now[:400],
            action=action,
            evidence_url=evidence,
            first_seen_at=first_seen_at,
            last_message_at=last_message_at,
            official_reply_at=official_reply_at,
            resolved=resolved,
            reasons=reasons,
        )
        changed = previous is None or bool(fresh) or any(
            (
                str(_row_get(previous, "summary")) != draft.summary,
                str(_row_get(previous, "priority")) != draft.priority,
                str(_row_get(previous, "title")) != draft.title,
            )
        )
        if not changed:
            conn.execute(
                "UPDATE feishu_group_event SET last_seen_at = ?, last_message_at = ?, "
                "official_reply_at = ? WHERE event_key = ?",
                (now, draft.last_message_at, draft.official_reply_at, event_key),
            )
            return None
        card_id = self._publish_card(conn, draft, agent_id=agent_id, user_id=user_id, now=now)
        if previous is None:
            conn.execute(
                "INSERT INTO feishu_group_event("
                "event_key, chat_id, chat_name, thread_id, agent_id, user_id, card_id, title, "
                "topic, priority, state, summary, why_now, action, evidence_url, first_seen_at, "
                "last_seen_at, last_message_at, official_reply_at, resolved_at, closed, "
                "notified_at, notify_count"
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0)",
                (
                    event_key, chat_id, draft.chat_name, draft.thread_id, agent_id, user_id,
                    card_id, draft.title, draft.topic, draft.priority, draft.state, draft.summary,
                    draft.why_now, draft.action, draft.evidence_url, draft.first_seen_at, now,
                    draft.last_message_at, draft.official_reply_at, now if resolved else None,
                    int(resolved),
                ),
            )
        else:
            conn.execute(
                "UPDATE feishu_group_event SET chat_name = ?, thread_id = ?, card_id = ?, title = ?, "
                "topic = ?, priority = ?, state = ?, summary = ?, why_now = ?, action = ?, "
                "evidence_url = ?, last_seen_at = ?, last_message_at = ?, official_reply_at = ?, "
                "resolved_at = ?, closed = ? WHERE event_key = ?",
                (
                    draft.chat_name, draft.thread_id, card_id, draft.title, draft.topic,
                    draft.priority, draft.state, draft.summary, draft.why_now, draft.action,
                    draft.evidence_url, now, draft.last_message_at, draft.official_reply_at,
                    now if resolved else _row_get(previous, "resolved_at"),
                    int(resolved),
                    event_key,
                ),
            )
        intent = None
        last_push_at = _last_push_at(conn, event_key)
        immediate_today = _immediate_pushes_today(conn, now=now)
        allowed, reason = should_push(
            priority=priority,
            state=state,
            now=now,
            zone=self._zone,
            last_push_at=last_push_at,
            immediate_pushes_today=immediate_today,
            deadline_or_incident=any(
                word in " ".join(row.text for row in rows)
                for word in DEADLINE_WORDS + INCIDENT_WORDS
            ),
        )
        if allowed:
            payload = {
                "type": "dashboard_push",
                "event": "today.card_changed",
                "agent_id": agent_id,
                "card_id": card_id,
                "priority": priority,
                "title": draft.title,
                "text": _push_text(draft),
                "state": state,
                "importance_reason": "；".join(reasons) or reason,
            }
            intent = PushIntent(
                user_id=user_id,
                agent_id=agent_id,
                payload=payload,
                dm_text=_dm_text(draft) if priority == "p0" else "",
            )
            conn.execute(
                "INSERT INTO feishu_group_push(event_key, priority, channel, reason, sent_at) "
                "VALUES (?, ?, ?, ?, ?)",
                (event_key, priority, "ws", "；".join(reasons) or reason, now),
            )
            conn.execute(
                "UPDATE feishu_group_event SET notified_at = ?, notify_count = notify_count + 1 "
                "WHERE event_key = ?",
                (now, event_key),
            )
        return intent

    def _publish_card(
        self, conn: Any, event: EventDraft, *, agent_id: str, user_id: int, now: int
    ) -> str:
        dedupe_key = f"feishu-group:{event.event_key}"
        card_id = "today-feishu-" + hashlib.sha256(
            f"{agent_id}|{dedupe_key}".encode()
        ).hexdigest()[:24]
        summary, action = build_card_text(event, zone=self._zone)
        conn.execute(
            "INSERT INTO today_cards("
            "agent_id, card_id, user_id, dedupe_key, kind, title, summary, why_now, confidence, "
            "lifecycle, occurred_at, snoozed_until, action_prompt, created_at, updated_at, "
            "risk_level, tracking_json"
            ") VALUES (?, ?, ?, ?, 'signal', ?, ?, ?, 0.7, 'unread', ?, NULL, ?, ?, ?, 'A1', NULL) "
            "ON CONFLICT(agent_id, dedupe_key) DO UPDATE SET "
            "title = excluded.title, summary = excluded.summary, why_now = excluded.why_now, "
            "action_prompt = excluded.action_prompt, occurred_at = excluded.occurred_at, "
            "lifecycle = CASE WHEN today_cards.lifecycle = 'dismissed' THEN 'dismissed' "
            "ELSE 'unread' END, updated_at = excluded.updated_at",
            (
                agent_id, card_id, user_id, dedupe_key, event.title, summary, event.why_now,
                event.last_message_at, action, now, now,
            ),
        )
        return card_id


def _push_text(event: EventDraft) -> str:
    first = event.summary.split("\n")[0].removeprefix("事实：")
    return f"{first[:110]}｜{event.action[:60]}"


def _dm_text(event: EventDraft) -> str:
    return f"{event.title}\n{event.summary}\n最小一步：{event.action}"


def _next_step(*, priority: str, topic: str, resolved: bool) -> str:
    if resolved:
        return "已解决，无需动作；需要时再核对一次结果。"
    if priority == "p0" and topic in {"积分扣费与到账", "套餐与订阅", "配额与限流", "价格与计费"}:
        return "今天若要续费或开通，先暂缓，保留订单号与扣费截图，等官方口径。"
    if priority == "p0":
        return "先确认是否影响你的在用手场景，再决定是否等官方回复。"
    if topic in {"审核与发布", "版本发布"}:
        return "收着当参照，等官方通告再动。"
    return "不需要现在做动作，收进 Today 即可。"


def _related_hint(conn: Any, *, agent_id: str, topic: str, now: int) -> str:
    keywords = {
        "积分扣费与到账": ("积分", "扣费"),
        "套餐与订阅": ("套餐", "订阅"),
        "配额与限流": ("配额", "额度"),
        "价格与计费": ("价格", "计费"),
        "审核与发布": ("审核",),
        "服务异常": ("故障", "异常"),
        "版本发布": ("发布", "版本"),
        "模型与开源": ("开源", "模型"),
        "安全与风控": ("安全", "泄露"),
    }.get(topic)
    if not keywords:
        return ""
    clause = " OR ".join("title LIKE ?" for _ in keywords)
    params = [agent_id, now - 36 * 3600, *(f"%{word}%" for word in keywords)]
    row = conn.execute(
        f"SELECT title FROM today_cards WHERE agent_id = ? AND updated_at >= ? AND ({clause}) "
        "AND dedupe_key NOT LIKE 'feishu-group:%' ORDER BY updated_at DESC LIMIT 1",
        params,
    ).fetchone()
    return f"今日其他卡片里也提到了「{row['title']}」。" if row is not None else ""


def _last_push_at(conn: Any, event_key: str) -> int | None:
    row = conn.execute(
        "SELECT MAX(sent_at) AS sent_at FROM feishu_group_push WHERE event_key = ?",
        (event_key,),
    ).fetchone()
    value = _row_get(row, "sent_at")
    return int(value) if value else None


def _immediate_pushes_today(conn: Any, *, now: int) -> int:
    row = conn.execute(
        "SELECT COUNT(*) AS total FROM feishu_group_push WHERE priority = 'p0' AND sent_at >= ?",
        (now - 24 * 3600,),
    ).fetchone()
    return int(_row_get(row, "total", 0) or 0)


def deliver(intents: list[PushIntent], *, cli: Any | None = None) -> None:
    """Best-effort Feishu DM for p0. Never raises into the scheduler loop."""
    sender = cli
    for intent in intents:
        if not intent.dm_text or sender is None:
            continue
        try:
            sender.direct_message(
                os.environ.get("OCTOP_FEISHU_OPEN_ID", "ou_938da0e8a0bb66a501b05ec53fef0370"),
                intent.dm_text,
            )
        except Exception:
            logger.warning("feishu DM delivery failed", exc_info=True)


async def _monitor_loop(server: Any, interval: int) -> None:
    import asyncio

    await asyncio.sleep(90)
    cli = FeishuCli()
    while True:
        try:
            monitor = FeishuGroupMonitor(server.services.repos.db, cli=cli)
            intents = await asyncio.to_thread(monitor.collect)
            hub = server.app_runtime.gateway.ws_hub
            for intent in intents:
                await hub.push_to_user(intent.user_id, intent.payload)
            await asyncio.to_thread(deliver, intents, cli=cli)
        except Exception:
            logger.warning("feishu group monitor tick failed", exc_info=True)
        await asyncio.sleep(interval)


def start_background_monitor(server: Any) -> None:
    """Start the in-process loop. Safe to call once from server startup."""
    import asyncio

    if os.environ.get("OCTOP_FEISHU_MONITOR", "1") == "0":
        return
    interval = int(os.environ.get("OCTOP_FEISHU_MONITOR_INTERVAL", SCAN_INTERVAL_SECONDS))
    asyncio.create_task(_monitor_loop(server, interval), name="feishu-group-monitor")
