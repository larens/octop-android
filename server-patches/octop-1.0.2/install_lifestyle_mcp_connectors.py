"""Install reviewed lifestyle MCP connectors and their runtime support."""

from __future__ import annotations

import copy
import json
import os
import shutil
import time
from pathlib import Path
from typing import Any

from patch_paths import backups_dir, octop_site


RAILWAY_12306_TOOLS = [
    "get-current-date",
    "get-stations-code-in-city",
    "get-station-code-of-citys",
    "get-station-code-by-names",
    "get-station-by-telecode",
    "get-tickets",
    "get-interline-tickets",
    "get-train-route-stations",
]

STOCK_MARKET_TOOLS = [
    "search",
    "get_a_share_quotes",
    "get_a_share_simple_quotes",
    "get_today_timeline",
    "get_history_kline",
    "get_minute_kline",
    "get_fund_quotes",
    "get_fund_nav_history",
    "get_global_futures_spot",
    "get_global_futures_kline",
    "get_futures_kline",
    "is_trading_day",
    "get_market_status",
]

ANTV_CHART_TOOLS = [
    "generate_area_chart",
    "generate_bar_chart",
    "generate_boxplot_chart",
    "generate_column_chart",
    "generate_district_map",
    "generate_dual_axes_chart",
    "generate_fishbone_diagram",
    "generate_flow_diagram",
    "generate_funnel_chart",
    "generate_histogram_chart",
    "generate_line_chart",
    "generate_liquid_chart",
    "generate_mind_map",
    "generate_network_graph",
    "generate_organization_chart",
    "generate_path_map",
    "generate_pie_chart",
    "generate_pin_map",
    "generate_radar_chart",
    "generate_sankey_chart",
    "generate_scatter_chart",
    "generate_treemap_chart",
    "generate_venn_chart",
    "generate_violin_chart",
    "generate_waterfall_chart",
    "generate_word_cloud_chart",
    "generate_spreadsheet",
]


LIFESTYLE_MCP_ENTRIES: tuple[dict[str, Any], ...] = (
    {
        "kind": "amap",
        "name": {"en": "Amap Maps", "zh": "高德地图 MCP"},
        "description": {
            "en": "Place search, geocoding, routing, nearby search, distance, and weather",
            "zh": "地点搜索、地理编码、路径规划、周边检索、距离测量与天气查询",
        },
        "auth_kind": "api_key",
        "doc_url": "https://lbs.amap.com/api/mcp-server/summary",
        "icon": "mcp",
        "color": "#1677ff",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "travel",
        "guide_url": "https://lbs.amap.com/api/mcp-server/summary",
        "manual_url": "https://console.amap.com/dev/key/app",
        "auth_hint": {
            "en": "Create a Web Service Key in the Amap console and paste it here",
            "zh": "在高德开放平台创建「Web 服务」类型的 Key，然后粘贴到这里",
        },
        "mcp_url": "https://mcp.amap.com/mcp",
        "mcp_url_template": "https://mcp.amap.com/mcp?key={token}",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "variflight",
        "name": {"en": "VariFlight", "zh": "飞常准"},
        "description": {
            "en": "Flight status, schedules, airport traffic, and aviation disruption queries",
            "zh": "航班动态、时刻、机场运行与航空出行异常查询",
        },
        "auth_kind": "api_key",
        "doc_url": "https://ai.variflight.com/servers/aviation/mcp",
        "icon": "mcp",
        "color": "#1d4ed8",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "travel",
        "guide_url": "https://ai.variflight.com/keys",
        "manual_url": "https://ai.variflight.com/keys",
        "auth_hint": {
            "en": "Create a personal API Key at ai.variflight.com and paste it here",
            "zh": "登录飞常准 AI 平台创建个人 API Key，然后粘贴到这里",
        },
        "mcp_url": "https://ai.variflight.com/servers/aviation/mcp",
        "mcp_auth_header": "X-API-Key",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "railway-12306",
        "name": {"en": "China Railway 12306", "zh": "中国铁路 12306"},
        "description": {
            "en": "Train ticket availability, stations, transfer routes, and train stops",
            "zh": "火车票余票、车站编码、中转方案与列车经停站查询",
        },
        "auth_kind": "anonymous",
        "doc_url": "https://github.com/Joooook/12306-mcp",
        "icon": "mcp",
        "color": "#dc2626",
        "phase": "available",
        "mcp_mode": "stdio",
        "category": "travel",
        "guide_url": "https://github.com/Joooook/12306-mcp",
        "auth_hint": {
            "en": "Community stdio MCP; no account or API key required",
            "zh": "社区维护的 stdio MCP，无需账号或 API Key",
        },
        "allowed_tools": RAILWAY_12306_TOOLS,
    },
    {
        "kind": "mcdonalds",
        "name": {"en": "McDonald's China", "zh": "麦当劳"},
        "description": {
            "en": "Official MCP for McDonald's China stores, menu, offers, and ordering context",
            "zh": "麦当劳中国官方 MCP：门店、菜单、优惠与点餐上下文",
        },
        "auth_kind": "personal_token",
        "doc_url": "https://open.mcd.cn/mcp",
        "icon": "mcp",
        "color": "#da291c",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "productivity",
        "guide_url": "https://open.mcd.cn/mcp",
        "manual_url": "https://open.mcd.cn/mcp",
        "auth_hint": {
            "en": "Sign in to McDonald's MCP platform and paste the issued MCP Token",
            "zh": "登录麦当劳 MCP 平台，复制平台签发的 MCP Token 并粘贴到这里",
        },
        "mcp_url": "https://mcp.mcd.cn",
        "mcp_auth_header": "Authorization",
        "mcp_auth_prefix": "Bearer ",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "kuaidi100",
        "name": {"en": "Kuaidi100", "zh": "快递100"},
        "description": {
            "en": "Courier tracking, delivery-time and price estimates, and carrier detection",
            "zh": "快递轨迹、预计送达时间、运费估算与快递公司识别",
        },
        "auth_kind": "api_key",
        "doc_url": "https://api.kuaidi100.com/document/how-to-use-mcp-service",
        "icon": "mcp",
        "color": "#12805c",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "professional",
        "guide_url": "https://api.kuaidi100.com/document/how-to-use-mcp-service",
        "manual_url": "https://api.kuaidi100.com",
        "auth_hint": {
            "en": "Copy the MCP Key from the Kuaidi100 enterprise console",
            "zh": "从快递100企业管理后台复制 MCP Key",
        },
        "allowed_tools": [
            "query_trace",
            "estimate_time",
            "estimate_price",
            "estimate_time_with_logistic",
            "auto_number",
        ],
        "mcp_url": "https://api.kuaidi100.com/mcp/streamable",
        "mcp_url_template": "https://api.kuaidi100.com/mcp/streamable?key={token}",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "caiyun-weather",
        "name": {"en": "Caiyun Weather", "zh": "彩云天气"},
        "description": {
            "en": "Realtime weather, 72-hour hourly forecast, weekly forecast, history, and alerts",
            "zh": "实时天气、未来 72 小时逐小时预报、一周预报、历史天气与预警",
        },
        "auth_kind": "api_key",
        "doc_url": "https://docs.caiyunapp.com/weather-api/mcp.html",
        "icon": "mcp",
        "color": "#0891b2",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "professional",
        "guide_url": "https://docs.caiyunapp.com/weather-api/mcp.html",
        "manual_url": "https://dashboard.caiyunapp.com/user/sign_in",
        "auth_hint": {
            "en": "Create a Caiyun Weather API Token in the developer console",
            "zh": "在彩云天气开发者控制台创建 API Token",
        },
        "allowed_tools": [
            "get_realtime_weather",
            "get_hourly_forecast",
            "get_weekly_forecast",
            "get_historical_weather",
            "get_weather_alerts",
        ],
        "mcp_url": "https://mcp-weather.caiyunapp.com/mcp",
        "mcp_auth_header": "X-Caiyun-API-Key",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "stock-market",
        "name": {"en": "China Market Quotes", "zh": "A 股、基金与黄金行情"},
        "description": {
            "en": "Read-only A-share quotes, fund NAV history, and domestic/global gold futures",
            "zh": "只读查询 A 股行情、基金净值历史、国内黄金与 COMEX 黄金期货",
        },
        "auth_kind": "anonymous",
        "doc_url": "https://github.com/chengzuopeng/stock-sdk",
        "icon": "mcp",
        "color": "#b45309",
        "phase": "available",
        "mcp_mode": "stdio",
        "category": "professional",
        "guide_url": "https://github.com/chengzuopeng/stock-sdk",
        "auth_hint": {
            "en": "Community stdio MCP; public quotes only, with no trading or investment advice",
            "zh": "社区维护的 stdio MCP；仅查询公开行情，不交易、不生成投资建议",
        },
        "allowed_tools": STOCK_MARKET_TOOLS,
    },
    {
        "kind": "china-holidays",
        "name": {"en": "China Holidays", "zh": "中国法定节假日"},
        "description": {
            "en": "State Council holiday periods and make-up workdays, with official notice sources",
            "zh": "国务院公布的法定节假日与调休上班日，并返回官方通知来源",
        },
        "auth_kind": "anonymous",
        "doc_url": "https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm",
        "icon": "mcp",
        "color": "#be123c",
        "phase": "available",
        "mcp_mode": "stdio",
        "category": "productivity",
        "guide_url": "https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm",
        "auth_hint": {
            "en": "Built-in annual dataset; no key required",
            "zh": "内置年度数据，无需 API Key；未公布年份不会自行推测",
        },
        "allowed_tools": ["get_china_holidays", "is_china_workday"],
    },
    {
        "kind": "antv-chart",
        "name": {"en": "AntV Charts", "zh": "AntV 图表"},
        "description": {
            "en": "Generate 27 chart types, including line, bar, pie, map, flow, and organization charts",
            "zh": "生成 27 类图表，包括折线、柱状、饼图、地图、流程图和组织架构图",
        },
        "auth_kind": "anonymous",
        "doc_url": "https://github.com/antvis/mcp-server-chart",
        "icon": "mcp",
        "color": "#7c3aed",
        "phase": "available",
        "mcp_mode": "stdio",
        "category": "productivity",
        "guide_url": "https://github.com/antvis/mcp-server-chart",
        "auth_hint": {
            "en": "No key required; chart data is sent to AntV's rendering service for image generation",
            "zh": "无需 API Key；生成图片时图表数据会发送到 AntV 免费渲染服务",
        },
        "allowed_tools": ANTV_CHART_TOOLS,
    },
)

LIFESTYLE_MCP_KINDS = tuple(str(entry["kind"]) for entry in LIFESTYLE_MCP_ENTRIES)


CATALOG_FIELD_ORIGINAL = '''    mcp_url: str | None = None
    oauth_resource: str | None = None'''
CATALOG_FIELD_PATCHED = '''    mcp_url: str | None = None
    mcp_url_template: str | None = None
    oauth_resource: str | None = None'''

CATALOG_PARSE_ORIGINAL = '''        mcp_url=_optional_str(raw.get("mcp_url"), f"{kind}.mcp_url"),
        oauth_resource=_optional_str(raw.get("oauth_resource"), f"{kind}.oauth_resource"),'''
CATALOG_PARSE_PATCHED = '''        mcp_url=_optional_str(raw.get("mcp_url"), f"{kind}.mcp_url"),
        mcp_url_template=_optional_str(
            raw.get("mcp_url_template"), f"{kind}.mcp_url_template"
        ),
        oauth_resource=_optional_str(raw.get("oauth_resource"), f"{kind}.oauth_resource"),'''

REMOTE_SPEC_ORIGINAL = '''def _build_remote_spec(entry: ConnectorCatalogEntry, creds: dict[str, Any]) -> dict[str, Any]:
    if entry.auth_kind == "personal_token" and entry.mcp_url and entry.mcp_auth_header:'''
REMOTE_SPEC_PATCHED = '''def _build_remote_spec(entry: ConnectorCatalogEntry, creds: dict[str, Any]) -> dict[str, Any]:
    token = str(
        creds.get("token") or creds.get("api_key") or creds.get("access_token") or ""
    ).strip()
    if entry.auth_kind in {"personal_token", "api_key"} and entry.mcp_url_template:
        if not token:
            raise ValueError("token is required")
        if "{token}" not in entry.mcp_url_template:
            raise ValueError(f"connector {entry.kind} mcp_url_template is missing {{token}}")
        spec: dict[str, Any] = {
            "transport": "http",
            "url": entry.mcp_url_template.replace("{token}", quote(token, safe="")),
            "headers": _mcp_http_headers(),
        }
        if entry.allowed_tools is not None:
            spec["allowed_tools"] = list(entry.allowed_tools)
        return spec
    if entry.auth_kind == "api_key" and entry.mcp_url and entry.mcp_auth_header:
        if not token:
            raise ValueError("api_key is required")
        headers = _mcp_http_headers()
        headers[entry.mcp_auth_header] = f"{entry.mcp_auth_prefix or ''}{token}"
        spec = {
            "transport": "http",
            "url": entry.mcp_url,
            "headers": headers,
        }
        if entry.allowed_tools is not None:
            spec["allowed_tools"] = list(entry.allowed_tools)
        return spec
    if entry.auth_kind == "personal_token" and entry.mcp_url and entry.mcp_auth_header:'''

STDIO_RAISE = '    raise ValueError(f"unsupported stdio connector kind: {entry.kind}")'
STDIO_PATCHED = '''    if entry.kind == "railway-12306":
        spec = {
            "transport": "stdio",
            "command": "npx",
            "args": ["-y", "12306-mcp@0.3.10"],
        }
        if entry.allowed_tools is not None:
            spec["allowed_tools"] = list(entry.allowed_tools)
        return spec
    if entry.kind == "stock-market":
        spec = {
            "transport": "stdio",
            "command": "npx",
            "args": ["-y", "stock-sdk@2.4.6", "mcp"],
            "env": {"STOCK_SDK_MCP_TOOLS": "full"},
        }
        if entry.allowed_tools is not None:
            spec["allowed_tools"] = list(entry.allowed_tools)
        return spec
    if entry.kind == "china-holidays":
        import sys

        spec = {
            "transport": "stdio",
            "command": sys.executable,
            "args": ["-m", "octop.infra.connectors.china_holidays_mcp"],
        }
        if entry.allowed_tools is not None:
            spec["allowed_tools"] = list(entry.allowed_tools)
        return spec
    if entry.kind == "antv-chart":
        spec = {
            "transport": "stdio",
            "command": "npx",
            "args": ["-y", "@antv/mcp-server-chart@0.9.10"],
        }
        if entry.allowed_tools is not None:
            spec["allowed_tools"] = list(entry.allowed_tools)
        return spec
    raise ValueError(f"unsupported stdio connector kind: {entry.kind}")'''

REDACTION_ORIGINAL = '''            for key in ("Authorization", "authorization"):
                if key in redacted:
                    redacted[key] = "***"'''
REDACTION_PATCHED = '''            for key in redacted:
                lowered = key.lower()
                if (
                    lowered == "authorization"
                    or lowered.endswith("-api-key")
                    or lowered.endswith("-key")
                    or "token" in lowered
                ):
                    redacted[key] = "***"'''

STDIO_TIMEOUT_ORIGINAL = "        async with asyncio.timeout(25):"
STDIO_TIMEOUT_PATCHED = "        async with asyncio.timeout(60):"


def _replace_once(source: str, original: str, patched: str, *, marker: str) -> str:
    if marker in source:
        return source
    if original not in source:
        raise ValueError("Octop 1.0.2 lifestyle MCP source anchor not found")
    return source.replace(original, patched, 1)


def patch_catalog_source(source: str) -> str:
    """Add the URL-template field used by key-in-query MCP providers."""
    patched = _replace_once(
        source,
        CATALOG_FIELD_ORIGINAL,
        CATALOG_FIELD_PATCHED,
        marker="mcp_url_template: str | None = None",
    )
    return _replace_once(
        patched,
        CATALOG_PARSE_ORIGINAL,
        CATALOG_PARSE_PATCHED,
        marker='mcp_url_template=_optional_str(',
    )


def patch_builder_source(source: str) -> str:
    """Add URL/header API-key auth, local stdio specs, and complete redaction."""
    patched = _replace_once(
        source,
        REMOTE_SPEC_ORIGINAL,
        REMOTE_SPEC_PATCHED,
        marker='if entry.auth_kind in {"personal_token", "api_key"} and entry.mcp_url_template:',
    )
    patched = _replace_once(
        patched,
        STDIO_RAISE,
        STDIO_PATCHED,
        marker='if entry.kind == "railway-12306":',
    )
    patched = _replace_once(
        patched,
        REDACTION_ORIGINAL,
        REDACTION_PATCHED,
        marker='lowered.endswith("-api-key")',
    )
    return patched


def patch_probe_source(source: str) -> str:
    """Allow cold npx startup for the larger 12306 package."""
    if STDIO_TIMEOUT_PATCHED in source:
        return source
    if STDIO_TIMEOUT_ORIGINAL not in source:
        raise ValueError("Octop 1.0.2 stdio probe timeout anchor not found")
    return source.replace(STDIO_TIMEOUT_ORIGINAL, STDIO_TIMEOUT_PATCHED, 1)


def merge_catalog_payload(payload: dict[str, Any]) -> dict[str, Any]:
    """Upsert lifestyle connectors without disturbing other catalog entries."""
    output = copy.deepcopy(payload)
    rows = output.get("connectors")
    if not isinstance(rows, list):
        raise ValueError("catalog.json must contain a connectors array")
    kinds = [row.get("kind") for row in rows if isinstance(row, dict)]
    if len(kinds) != len(rows) or len(kinds) != len(set(kinds)):
        raise ValueError("catalog.json connector kinds must be unique objects")

    retained = [
        copy.deepcopy(row)
        for row in rows
        if isinstance(row, dict) and row.get("kind") not in LIFESTYLE_MCP_KINDS
    ]
    insert_at = next(
        (index for index, row in enumerate(retained) if row.get("kind") == "dida365"),
        len(retained),
    )
    output["connectors"] = (
        retained[:insert_at]
        + [copy.deepcopy(entry) for entry in LIFESTYLE_MCP_ENTRIES]
        + retained[insert_at:]
    )
    return output


def _write_if_changed(path: Path, content: str, backup_dir: Path) -> bool:
    current = path.read_text(encoding="utf-8") if path.exists() else None
    if current == content:
        return False
    backup_dir.mkdir(parents=True, exist_ok=True)
    if path.exists():
        shutil.copy2(path, backup_dir / path.name)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    temporary.write_text(content, encoding="utf-8")
    if path.exists():
        shutil.copymode(path, temporary)
    else:
        temporary.chmod(0o644)
    os.replace(temporary, path)
    return True


def install() -> list[Path]:
    site = octop_site()
    catalog_json_path = site / "infra/connectors/catalog.json"
    catalog_py_path = site / "infra/connectors/catalog.py"
    builder_py_path = site / "infra/connectors/builder.py"
    probe_py_path = site / "infra/connectors/probe.py"
    holidays_source = Path(__file__).with_name("china_holidays_mcp.py")
    holidays_target = site / "infra/connectors/china_holidays_mcp.py"
    backup = backups_dir() / f"lifestyle-mcp-{time.strftime('%Y%m%d-%H%M%S')}"
    changed: list[Path] = []

    catalog_payload = json.loads(catalog_json_path.read_text(encoding="utf-8"))
    catalog_text = json.dumps(
        merge_catalog_payload(catalog_payload),
        ensure_ascii=False,
        indent=2,
    ) + "\n"
    if _write_if_changed(catalog_json_path, catalog_text, backup):
        changed.append(catalog_json_path)

    for path, patcher in (
        (catalog_py_path, patch_catalog_source),
        (builder_py_path, patch_builder_source),
        (probe_py_path, patch_probe_source),
    ):
        source = path.read_text(encoding="utf-8")
        patched = patcher(source)
        if _write_if_changed(path, patched, backup):
            changed.append(path)

    if _write_if_changed(
        holidays_target,
        holidays_source.read_text(encoding="utf-8"),
        backup,
    ):
        changed.append(holidays_target)
    return changed


if __name__ == "__main__":
    for changed_path in install():
        print(changed_path)
