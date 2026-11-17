"""Feishu DM alerts for Xiaomi Health sync authentication failures.

Only Larens receives these alerts. The collector runs every 30 minutes, so
the alert state lives in the ``settings`` table: send once when an account
starts failing, remind at most once per day, and send one recovery message
when the account syncs again.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import shutil
import subprocess
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable, Iterable, Mapping

LOG = logging.getLogger("xiaomi_health_alert")

DEFAULT_OPEN_ID = "ou_938da0e8a0bb66a501b05ec53fef0370"
DEFAULT_CLI_CONFIG_DIR = (
    "/opt/octop/connector-cli/feishu-cli/01M642XQHNJKYF63C383AS6DWW"
)
WEBHOOK_ENV = "OCTOP_HEALTH_ALERT_WEBHOOK"
WEBHOOK_FILE = Path("/opt/octop/xiaomi_health/feishu_webhook")
ALERT_STATE_PREFIX = "xiaomi_health.alert:"
REMINDER_SECONDS = 24 * 60 * 60
CST = timezone(timedelta(hours=8))


def _state_key(agent_id: str) -> str:
    return f"{ALERT_STATE_PREFIX}{agent_id}"


def _read_state(pool, agent_id: str) -> dict | None:
    with pool.connect() as conn:
        row = conn.execute(
            "SELECT value FROM settings WHERE key = ?",
            (_state_key(agent_id),),
        ).fetchone()
    if row is None:
        return None
    try:
        state = json.loads(str(row["value"]))
    except (KeyError, TypeError, ValueError):
        return None
    return state if isinstance(state, dict) else None


def _write_state(pool, agent_id: str, state: Mapping[str, object]) -> None:
    with pool.transaction() as conn:
        conn.execute(
            "INSERT INTO settings(key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (
                _state_key(agent_id),
                json.dumps(state, ensure_ascii=False, separators=(",", ":")),
            ),
        )


def _delete_state(pool, agent_id: str) -> None:
    with pool.transaction() as conn:
        conn.execute(
            "DELETE FROM settings WHERE key = ?",
            (_state_key(agent_id),),
        )


def _agent_labels(pool, agent_ids: Iterable[str]) -> dict[str, str]:
    ids = list(dict.fromkeys(agent_ids))
    if not ids:
        return {}
    placeholders = ",".join("?" for _ in ids)
    with pool.connect() as conn:
        rows = conn.execute(
            "SELECT a.agent_id, u.username FROM agents a "
            "LEFT JOIN users u ON u.id = a.user_id "
            f"WHERE a.agent_id IN ({placeholders})",
            tuple(ids),
        ).fetchall()
    labels: dict[str, str] = {}
    for row in rows:
        agent_id = str(row["agent_id"])
        username = row["username"]
        labels[agent_id] = (
            f"{agent_id}（{username}）" if username else agent_id
        )
    for agent_id in ids:
        labels.setdefault(agent_id, agent_id)
    return labels


def _format_message(
    *,
    new_failures: list[str],
    reminders: list[str],
    recoveries: list[str],
    labels: Mapping[str, str],
    now: int,
) -> str:
    lines: list[str] = []
    if new_failures or reminders:
        lines.append("【Octop 健康同步告警】")
        lines.append("小米运动健康云端登录失效，健康数据已停止更新：")
        for agent_id in new_failures:
            lines.append(f"• {labels[agent_id]} — 登录失效（首次）")
        for agent_id in reminders:
            lines.append(f"• {labels[agent_id]} — 登录失效（仍在失败）")
        lines.append("")
        lines.append("处理：在服务器重新绑定 passToken 后执行 --full 回填。")
    if recoveries:
        if lines:
            lines.append("")
        lines.append("【Octop 健康同步恢复】")
        for agent_id in recoveries:
            lines.append(f"• {labels[agent_id]} 已恢复同步。")
    lines.append("")
    lines.append(f"时间：{datetime.fromtimestamp(now, CST):%Y-%m-%d %H:%M}")
    return "\n".join(lines)


def _webhook_url() -> str:
    value = os.environ.get(WEBHOOK_ENV, "").strip()
    if value:
        return value
    try:
        return WEBHOOK_FILE.read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def _send_webhook(url: str, text: str) -> bool:
    body = json.dumps(
        {"msg_type": "text", "content": {"text": text}},
        ensure_ascii=False,
    ).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = json.loads(response.read().decode("utf-8") or "{}")
    except Exception:
        LOG.warning("Feishu webhook delivery failed", exc_info=True)
        return False
    code = payload.get("code", payload.get("StatusCode", 0))
    if code not in (0, "0", None):
        LOG.warning("Feishu webhook rejected: %s", str(payload)[:300])
        return False
    LOG.info("Feishu health alert delivered via webhook")
    return True


def send_feishu_text(text: str, *, open_id: str | None = None) -> bool:
    webhook = _webhook_url()
    if webhook:
        return _send_webhook(webhook, text)
    recipient = (
        open_id
        or os.environ.get("OCTOP_HEALTH_ALERT_OPEN_ID")
        or DEFAULT_OPEN_ID
    )
    binary = (
        os.environ.get("OCTOP_FEISHU_CLI_BIN")
        or shutil.which("lark-cli")
        or "/usr/bin/lark-cli"
    )
    config_dir = (
        os.environ.get("OCTOP_FEISHU_CLI_DIR")
        or DEFAULT_CLI_CONFIG_DIR
    )
    env = os.environ.copy()
    env["LARKSUITE_CLI_CONFIG_DIR"] = config_dir
    # The timer unit runs with ProtectHome=true; keep the CLI state readable.
    env["HOME"] = env.get("OCTOP_ALERT_HOME", "/opt/octop/lark-cli-home")
    try:
        proc = subprocess.run(
            [
                binary,
                "im", "+messages-send",
                "--as", "bot",
                "--user-id", recipient,
                "--text", text,
                "--json",
            ],
            capture_output=True,
            text=True,
            timeout=45,
            env=env,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        LOG.warning("Feishu alert delivery failed", exc_info=True)
        return False
    if proc.returncode != 0:
        LOG.warning(
            "Feishu alert failed rc=%s: %s",
            proc.returncode,
            (proc.stderr or proc.stdout or "")[:300],
        )
        return False
    try:
        payload = json.loads(proc.stdout)
    except ValueError:
        LOG.warning(
            "Feishu alert returned non-JSON output: %s",
            (proc.stdout or "")[:200],
        )
        return False
    if not payload.get("ok", False):
        LOG.warning(
            "Feishu alert rejected: %s",
            str(payload.get("error") or payload.get("message") or payload)[:300],
        )
        return False
    data = payload.get("data")
    message_id = data.get("message_id") if isinstance(data, dict) else None
    LOG.info("Feishu health alert delivered message_id=%s", message_id or "-")
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--test",
        action="store_true",
        help="Send a test alert to the configured recipient",
    )
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    if not args.test:
        parser.print_help()
        return 2
    ok = send_feishu_text(
        "【Octop】健康同步告警通道测试，收到即可忽略。"
    )
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())


def notify_sync_outcome(
    pool,
    *,
    auth_failures: Iterable[str] = (),
    succeeded: Iterable[str] = (),
    now: int | None = None,
    sender: Callable[[str], bool] | None = None,
) -> bool:
    """Send at most one Feishu DM for the current sync outcome.

    State is only advanced after a successful delivery, so a transient CLI
    failure is retried on the next 30-minute run.
    """

    now_ts = int(now if now is not None else time.time())
    failed = list(dict.fromkeys(str(value) for value in auth_failures))
    succeeded_ids = list(dict.fromkeys(str(value) for value in succeeded))
    failed_set = set(failed)

    new_failures: list[str] = []
    reminders: list[str] = []
    for agent_id in failed:
        state = _read_state(pool, agent_id)
        if state is None:
            new_failures.append(agent_id)
        elif now_ts - int(state.get("last_notified_at") or 0) >= REMINDER_SECONDS:
            reminders.append(agent_id)

    recoveries: list[str] = []
    for agent_id in succeeded_ids:
        if agent_id in failed_set:
            continue
        if _read_state(pool, agent_id) is not None:
            recoveries.append(agent_id)

    if not (new_failures or reminders or recoveries):
        return False

    labels = _agent_labels(
        pool, [*new_failures, *reminders, *recoveries]
    )
    text = _format_message(
        new_failures=new_failures,
        reminders=reminders,
        recoveries=recoveries,
        labels=labels,
        now=now_ts,
    )
    deliver = sender or send_feishu_text
    if not deliver(text):
        return False

    for agent_id in new_failures:
        _write_state(
            pool,
            agent_id,
            {
                "status": "failing",
                "since": now_ts,
                "last_notified_at": now_ts,
            },
        )
    for agent_id in reminders:
        state = _read_state(pool, agent_id) or {}
        state["last_notified_at"] = now_ts
        _write_state(pool, agent_id, state)
    for agent_id in recoveries:
        _delete_state(pool, agent_id)
    LOG.info(
        "Feishu health alert sent new=%s reminders=%s recovered=%s",
        new_failures,
        reminders,
        recoveries,
    )
    return True
