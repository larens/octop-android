"""WeChat Reading (WeRead) gateway adapter.

The raw gateway is convenient but its payloads are vendor-shaped. This adapter
keeps the two original raw tools for compatibility and adds normalized
``get_weekly_reading`` / ``get_reading_stats`` tools so scheduled cards receive a
small, complete fact set instead of a 1,200-book JSON dump.
"""

from __future__ import annotations

import json
from typing import Any

import httpx

from octop.infra.connectors.gateway.adapters import weread_reading

WEREAD_GATEWAY_URL = "https://i.weread.qq.com/api/agent/gateway"
WEREAD_SKILL_VERSION = "1.0.3"

_CLIENT: httpx.Client | None = None

TOOLS: list[dict[str, Any]] = [
    {
        "name": "list_bookshelf",
        "description": "List books on WeRead shelf (raw gateway payload)",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "get_book_notes",
        "description": "Get raw highlights/notes for one book by bookId",
        "inputSchema": {
            "type": "object",
            "properties": {
                "book_id": {"type": "string"},
            },
            "required": ["book_id"],
        },
    },
    {
        "name": "get_weekly_reading",
        "description": (
            "Get this week's normalized reading digest: total time, read days, "
            "daily buckets, books with progress, new highlights/thoughts, shelf "
            "coverage, and explicit data limitations. Prefer this over scanning "
            "the raw shelf."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "base_time": {
                    "type": "integer",
                    "description": "Optional Unix seconds inside the target week.",
                }
            },
        },
    },
    {
        "name": "get_reading_stats",
        "description": (
            "Get normalized reading statistics for weekly, monthly or overall "
            "mode: total time, read days, daily/year buckets, read counters, "
            "preferred categories/authors, and limitations."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "mode": {
                    "type": "string",
                    "enum": ["weekly", "monthly", "overall"],
                    "description": "Statistics window; defaults to weekly.",
                },
                "base_time": {
                    "type": "integer",
                    "description": "Optional Unix seconds anchoring the window.",
                },
            },
        },
    },
]


def list_tools() -> list[dict[str, Any]]:
    return TOOLS


def call_tool(creds: dict[str, Any], name: str, args: dict[str, Any]) -> str:
    def call(api_name: str, **params: Any) -> dict[str, Any]:
        return _api_json(creds, api_name, **params)

    if name == "list_bookshelf":
        return shelf(creds)
    if name == "get_book_notes":
        return notes(creds, args)
    if name == "get_weekly_reading":
        base_time = args.get("base_time")
        return weread_reading.dumps(
            weread_reading.build_weekly_digest(
                call,
                api_key=_api_key(creds),
                now=int(base_time) if base_time not in (None, "") else None,
            )
        )
    if name == "get_reading_stats":
        base_time = args.get("base_time")
        return weread_reading.dumps(
            weread_reading.build_reading_stats(
                call,
                mode=str(args.get("mode") or "weekly"),
                base_time=int(base_time) if base_time not in (None, "") else None,
            )
        )
    raise ValueError(f"unknown tool: {name}")


def shelf(creds: dict[str, Any]) -> str:
    return _api(creds, "/shelf/sync")


def notes(creds: dict[str, Any], args: dict[str, Any]) -> str:
    book_id = str(args.get("book_id") or "").strip()
    if not book_id:
        raise ValueError("book_id is required")
    return _api(creds, "/book/bookmarklist", bookId=book_id)


def probe_credentials(creds: dict[str, Any]) -> None:
    """Validate WeRead API key by syncing the bookshelf."""
    shelf(creds)


def _api_key(creds: dict[str, Any]) -> str:
    api_key = str(creds.get("api_key") or "").strip()
    if not api_key:
        raise ValueError("missing wechat-reading API key")
    if not api_key.startswith("wrk-"):
        raise ValueError(
            "微信读书需使用 wrk- 开头的 API Key（非浏览器 Cookie）。"
            "请打开 https://weread.qq.com/r/weread-skills 登录后复制 API Key 并更新连接器配置"
        )
    return api_key


def _client() -> httpx.Client:
    global _CLIENT
    if _CLIENT is None:
        _CLIENT = httpx.Client(timeout=25.0)
    return _CLIENT


def _api_json(creds: dict[str, Any], api_name: str, **params: Any) -> dict[str, Any]:
    body: dict[str, Any] = {"api_name": api_name, "skill_version": WEREAD_SKILL_VERSION}
    body.update({key: value for key, value in params.items() if value is not None})
    headers = {
        "Authorization": f"Bearer {_api_key(creds)}",
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (octop-connector/0.1)",
    }
    try:
        response = _client().post(WEREAD_GATEWAY_URL, headers=headers, json=body)
    except Exception as exc:  # network errors must not expose the request headers
        raise ValueError(f"微信读书请求失败：{exc}") from exc
    if response.status_code == 401:
        raise ValueError("微信读书 API Key 无效或已过期")
    if response.status_code == 403:
        raise ValueError(
            "微信读书拒绝了当前网络出口（风控 403）。API Key 可能有效，"
            "但服务器出口 IP 被限制，请改用可正常访问微信读书的代理。"
        )
    if response.status_code >= 400:
        raise ValueError(f"微信读书接口返回 HTTP {response.status_code}")
    try:
        data = response.json()
    except Exception as exc:
        raise ValueError(f"微信读书返回非 JSON：{exc}") from exc
    if isinstance(data, dict):
        errcode = data.get("errcode", data.get("errCode"))
        if errcode not in (0, None):
            message = str(data.get("errmsg") or data.get("errMsg") or "weread api error")
            raise ValueError(f"[{errcode}] {message}")
    return data if isinstance(data, dict) else {}


def _api(creds: dict[str, Any], api_name: str, **params: Any) -> str:
    return json.dumps(_api_json(creds, api_name, **params), ensure_ascii=False, indent=2)
