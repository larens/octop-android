"""Install reviewed official MCP connectors into the Octop catalog."""

from __future__ import annotations

import copy
import json
import os
import shutil
import time
from pathlib import Path
from typing import Any

from patch_paths import backups_dir, octop_site


MCP_ENTRIES: tuple[dict[str, Any], ...] = (
    {
        "kind": "deepwiki",
        "name": {"en": "DeepWiki", "zh": "DeepWiki"},
        "description": {
            "en": "Ask questions and read architecture docs for GitHub repositories",
            "zh": "检索并阅读 GitHub 开源仓库的架构文档，支持 Wiki 问答",
        },
        "auth_kind": "anonymous",
        "doc_url": "https://docs.devin.ai/work-with-devin/deepwiki-mcp",
        "icon": "mcp",
        "color": "#2563eb",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "knowledge",
        "guide_url": "https://docs.devin.ai/work-with-devin/deepwiki-mcp",
        "manual_url": "https://docs.devin.ai/work-with-devin/deepwiki-mcp",
        "auth_hint": {
            "en": "No API key required; probe and save to enable",
            "zh": "无需 API Key，探测通过后保存即可启用",
        },
        "allowed_tools": [
            "ask_wiki_question",
            "read_wiki_contents",
            "read_wiki_structure",
        ],
        "mcp_url": "https://mcp.deepwiki.com/mcp",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "context7",
        "name": {"en": "Context7", "zh": "Context7"},
        "description": {
            "en": "Get current library, framework, and API documentation with code examples",
            "zh": "获取最新版本的库、框架与 API 文档和代码示例",
        },
        "auth_kind": "anonymous",
        "doc_url": "https://github.com/upstash/context7",
        "icon": "mcp",
        "color": "#0f766e",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "knowledge",
        "guide_url": "https://github.com/upstash/context7",
        "manual_url": "https://context7.com",
        "auth_hint": {
            "en": "No API key required; probe and save to enable",
            "zh": "无需 API Key，探测通过后保存即可启用",
        },
        "allowed_tools": ["resolve-library-id", "query-docs"],
        "mcp_url": "https://mcp.context7.com/mcp",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "microsoft-learn",
        "name": {"en": "Microsoft Learn", "zh": "Microsoft Learn"},
        "description": {
            "en": "Search official Microsoft and Azure docs, tutorials, and code samples",
            "zh": "检索官方 Microsoft 与 Azure 文档、教程和代码示例",
        },
        "auth_kind": "anonymous",
        "doc_url": "https://learn.microsoft.com/en-us/training/support/mcp",
        "icon": "mcp",
        "color": "#0078d4",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "knowledge",
        "guide_url": "https://learn.microsoft.com/en-us/training/support/mcp",
        "manual_url": "https://learn.microsoft.com",
        "auth_hint": {
            "en": "No API key required; probe and save to enable",
            "zh": "无需 API Key，探测通过后保存即可启用",
        },
        "allowed_tools": [
            "microsoft_docs_search",
            "microsoft_code_sample_search",
            "microsoft_docs_fetch",
        ],
        "mcp_url": "https://learn.microsoft.com/api/mcp",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "exa",
        "name": {"en": "Exa", "zh": "Exa"},
        "description": {
            "en": "Official MCP for web search, page extraction, and research",
            "zh": "官方 MCP：网页搜索、正文提取与资料研究",
        },
        "auth_kind": "anonymous",
        "doc_url": "https://docs.exa.ai/reference/exa-mcp",
        "icon": "mcp",
        "color": "#1f2937",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "knowledge",
        "guide_url": "https://docs.exa.ai/reference/exa-mcp",
        "manual_url": "https://exa.ai",
        "auth_hint": {
            "en": "No API key required for the public endpoint; usage limits may apply",
            "zh": "公共端点无需 API Key，可能有匿名用量限制",
        },
        "allowed_tools": ["web_search_exa", "web_fetch_exa"],
        "mcp_url": "https://mcp.exa.ai/mcp",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "cloudflare-docs",
        "name": {"en": "Cloudflare Docs", "zh": "Cloudflare 文档"},
        "description": {
            "en": "Official MCP for searching Cloudflare product and developer documentation",
            "zh": "官方 MCP：检索 Cloudflare 产品与开发者文档",
        },
        "auth_kind": "anonymous",
        "doc_url": "https://developers.cloudflare.com/agents/model-context-protocol/mcp-server-portal/",
        "icon": "mcp",
        "color": "#f38020",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "knowledge",
        "guide_url": "https://developers.cloudflare.com/agents/model-context-protocol/mcp-server-portal/",
        "manual_url": "https://developers.cloudflare.com",
        "auth_hint": {
            "en": "No API key required; probe and save to enable",
            "zh": "无需 API Key，探测通过后保存即可启用",
        },
        "allowed_tools": [
            "search_cloudflare_documentation",
            "migrate_pages_to_workers_guide",
        ],
        "mcp_url": "https://docs.mcp.cloudflare.com/mcp",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "github",
        "name": {"en": "GitHub", "zh": "GitHub"},
        "description": {
            "en": "Official remote MCP for repositories, code, issues, pull requests, and projects",
            "zh": "官方远程 MCP：仓库、代码、Issue、Pull Request 与项目协作",
        },
        "auth_kind": "personal_token",
        "doc_url": "https://docs.github.com/en/copilot/how-tos/provide-context/use-mcp/use-the-github-mcp-server",
        "icon": "mcp",
        "color": "#181717",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "productivity",
        "guide_url": "https://docs.github.com/en/copilot/how-tos/provide-context/use-mcp/use-the-github-mcp-server",
        "manual_url": "https://github.com/settings/personal-access-tokens/new",
        "auth_hint": {
            "en": "Create a fine-grained PAT with only the repository, organization, and project permissions you need",
            "zh": "创建 Fine-grained PAT，只授予实际需要的仓库、组织与项目权限",
        },
        "mcp_url": "https://api.githubcopilot.com/mcp/",
        "mcp_auth_header": "Authorization",
        "mcp_auth_prefix": "Bearer ",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "atlassian",
        "name": {"en": "Atlassian", "zh": "Atlassian"},
        "description": {
            "en": "Official remote MCP for Jira, Confluence, Compass, and team work search",
            "zh": "官方远程 MCP：Jira、Confluence、Compass 与团队工作检索",
        },
        "auth_kind": "oauth2",
        "doc_url": "https://www.atlassian.com/platform/remote-mcp-server",
        "icon": "mcp",
        "color": "#0052cc",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "productivity",
        "guide_url": "https://www.atlassian.com/platform/remote-mcp-server",
        "auth_hint": {
            "en": "Click Authorize to sign in with your Atlassian account",
            "zh": "点击「一键授权」使用 Atlassian 账号登录",
        },
        "oauth_issuer": "https://auth.atlassian.com/VCeDsk8ZHncYF1g234fKtc4lNipbBhu3",
        "mcp_url": "https://mcp.atlassian.com/v1/mcp/authv2",
        "oauth_resource": "https://mcp.atlassian.com/v1/mcp/authv2",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "linear",
        "name": {"en": "Linear", "zh": "Linear"},
        "description": {
            "en": "Official MCP for issues, projects, cycles, documents, and team planning",
            "zh": "官方 MCP：Issue、项目、周期、文档与团队规划",
        },
        "auth_kind": "oauth2",
        "doc_url": "https://linear.app/docs/mcp",
        "icon": "mcp",
        "color": "#5e6ad2",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "productivity",
        "guide_url": "https://linear.app/docs/mcp",
        "auth_hint": {
            "en": "Click Authorize to sign in to Linear and choose the workspace",
            "zh": "点击「一键授权」登录 Linear 并选择工作区",
        },
        "oauth_issuer": "https://mcp.linear.app",
        "mcp_url": "https://mcp.linear.app/mcp",
        "oauth_resource": "https://mcp.linear.app/mcp",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "figma",
        "name": {"en": "Figma", "zh": "Figma"},
        "description": {
            "en": "Official MCP for Figma design context, components, and developer handoff",
            "zh": "官方 MCP：Figma 设计上下文、组件与开发交付信息",
        },
        "auth_kind": "oauth2",
        "doc_url": "https://developers.figma.com/docs/figma-mcp-server/",
        "icon": "mcp",
        "color": "#f24e1e",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "productivity",
        "guide_url": "https://developers.figma.com/docs/figma-mcp-server/",
        "auth_hint": {
            "en": "Click Authorize to connect Figma; the server requests the mcp:connect scope",
            "zh": "点击「一键授权」连接 Figma，服务端会申请 mcp:connect 权限",
        },
        "oauth_issuer": "https://api.figma.com",
        "mcp_url": "https://mcp.figma.com/mcp",
        "oauth_resource": "https://mcp.figma.com/mcp",
        "oauth_scopes": "mcp:connect",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "canva",
        "name": {"en": "Canva", "zh": "Canva"},
        "description": {
            "en": "Official MCP for Canva designs, folders, assets, comments, and brand templates",
            "zh": "官方 MCP：Canva 设计、文件夹、素材、评论与品牌模板",
        },
        "auth_kind": "oauth2",
        "doc_url": "https://www.canva.dev/docs/connect/canva-mcp-server-setup/",
        "icon": "mcp",
        "color": "#00c4cc",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "productivity",
        "guide_url": "https://www.canva.dev/docs/connect/canva-mcp-server-setup/",
        "auth_hint": {
            "en": "Click Authorize to sign in to Canva and approve the requested design permissions",
            "zh": "点击「一键授权」登录 Canva 并批准所需的设计权限",
        },
        "oauth_issuer": "https://mcp.canva.com",
        "mcp_url": "https://mcp.canva.com/mcp",
        "oauth_resource": "https://mcp.canva.com/mcp",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "stripe",
        "name": {"en": "Stripe", "zh": "Stripe"},
        "description": {
            "en": "Official MCP for Stripe documentation, account data, and payment operations",
            "zh": "官方 MCP：Stripe 文档、账户数据与支付操作",
        },
        "auth_kind": "oauth2",
        "doc_url": "https://docs.stripe.com/mcp",
        "icon": "mcp",
        "color": "#635bff",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "professional",
        "guide_url": "https://docs.stripe.com/mcp",
        "auth_hint": {
            "en": "Click Authorize to connect Stripe; use a restricted account where possible",
            "zh": "点击「一键授权」连接 Stripe；建议使用权限受限的账户",
        },
        "oauth_issuer": "https://access.stripe.com/mcp",
        "mcp_url": "https://mcp.stripe.com",
        "oauth_resource": "https://mcp.stripe.com",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "sentry",
        "name": {"en": "Sentry", "zh": "Sentry"},
        "description": {
            "en": "Official MCP for Sentry issues, events, projects, teams, and alerts",
            "zh": "官方 MCP：Sentry Issue、事件、项目、团队与告警",
        },
        "auth_kind": "oauth2",
        "doc_url": "https://docs.sentry.io/product/sentry-mcp/",
        "icon": "mcp",
        "color": "#362d59",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "professional",
        "guide_url": "https://docs.sentry.io/product/sentry-mcp/",
        "auth_hint": {
            "en": "Click Authorize to sign in to Sentry and choose the organizations to expose",
            "zh": "点击「一键授权」登录 Sentry 并选择允许访问的组织",
        },
        "oauth_issuer": "https://mcp.sentry.dev",
        "mcp_url": "https://mcp.sentry.dev/mcp",
        "oauth_resource": "https://mcp.sentry.dev/mcp",
        "remote_transport": "streamable_http",
    },
    {
        "kind": "postman",
        "name": {"en": "Postman", "zh": "Postman"},
        "description": {
            "en": "Official MCP for Postman workspaces, collections, APIs, and mock servers",
            "zh": "官方 MCP：Postman 工作区、集合、API 与 Mock Server",
        },
        "auth_kind": "oauth2",
        "doc_url": "https://www.postman.com/product/mcp-server",
        "icon": "mcp",
        "color": "#ff6c37",
        "phase": "available",
        "mcp_mode": "remote",
        "category": "professional",
        "guide_url": "https://www.postman.com/product/mcp-server",
        "auth_hint": {
            "en": "Click Authorize to sign in to Postman and choose a workspace",
            "zh": "点击「一键授权」登录 Postman 并选择工作区",
        },
        "oauth_issuer": "https://mcp.postman.com",
        "mcp_url": "https://mcp.postman.com/mcp",
        "oauth_resource": "https://mcp.postman.com/mcp",
        "remote_transport": "streamable_http",
    },
)

MCP_KINDS = tuple(str(entry["kind"]) for entry in MCP_ENTRIES)

AUTH_KIND_END = '''    "api_credentials",
    "custom_fields",
]'''
AUTH_KIND_END_PATCHED = '''    "api_credentials",
    "custom_fields",
    "anonymous",
]'''

QUICK_AUTH_ORIGINAL = (
    '        "supports_quick_auth": entry.phase == "available" and '
    'entry.auth_kind != "api_credentials",'
)
QUICK_AUTH_PATCHED = (
    '        "supports_quick_auth": entry.phase == "available"\n'
    '        and entry.auth_kind not in {"api_credentials", "anonymous"},'
)

VALIDATE_ORIGINAL = '''    out: dict[str, Any] = {}  # pre-declare to avoid mypy no-redef errors

    if entry.auth_kind == "personal_token":'''
VALIDATE_PATCHED = '''    out: dict[str, Any] = {}  # pre-declare to avoid mypy no-redef errors

    if entry.auth_kind == "anonymous":
        return {"anonymous": True}

    if entry.auth_kind == "personal_token":'''

MCP_FIELDS_ORIGINAL = '''    mcp_user_agent: str | None = None
    credential_fields: tuple[ConnectorCredentialField, ...] = ()'''
MCP_FIELDS_PATCHED = '''    mcp_user_agent: str | None = None
    mcp_auth_header: str | None = None
    mcp_auth_prefix: str | None = None
    credential_fields: tuple[ConnectorCredentialField, ...] = ()'''

MCP_PARSE_ORIGINAL = '''        mcp_user_agent=_optional_str(raw.get("mcp_user_agent"), f"{kind}.mcp_user_agent"),
        credential_fields=tuple('''
MCP_PARSE_PATCHED = '''        mcp_user_agent=_optional_str(raw.get("mcp_user_agent"), f"{kind}.mcp_user_agent"),
        mcp_auth_header=_optional_str(raw.get("mcp_auth_header"), f"{kind}.mcp_auth_header"),
        mcp_auth_prefix=_optional_str(raw.get("mcp_auth_prefix"), f"{kind}.mcp_auth_prefix"),
        credential_fields=tuple('''

REMOTE_SPEC_ORIGINAL = '''def _build_remote_spec(entry: ConnectorCatalogEntry, creds: dict[str, Any]) -> dict[str, Any]:
    if entry.kind == "didi":'''
REMOTE_SPEC_PATCHED = '''def _build_remote_spec(entry: ConnectorCatalogEntry, creds: dict[str, Any]) -> dict[str, Any]:
    if entry.auth_kind == "anonymous":
        mcp_url = (entry.mcp_url or "").strip()
        if not mcp_url:
            raise ValueError(f"connector {entry.kind} is missing mcp_url")
        spec: dict[str, Any] = {
            "transport": "http",
            "url": mcp_url,
            "headers": _mcp_http_headers(),
        }
        if entry.allowed_tools is not None:
            spec["allowed_tools"] = list(entry.allowed_tools)
        return spec
    if entry.kind == "didi":'''

REMOTE_TOKEN_ORIGINAL = '''def _build_remote_spec(entry: ConnectorCatalogEntry, creds: dict[str, Any]) -> dict[str, Any]:
    if entry.auth_kind == "anonymous":'''
REMOTE_TOKEN_PATCHED = '''def _build_remote_spec(entry: ConnectorCatalogEntry, creds: dict[str, Any]) -> dict[str, Any]:
    if entry.auth_kind == "personal_token" and entry.mcp_url and entry.mcp_auth_header:
        token = str(creds.get("token") or creds.get("access_token") or "").strip()
        if not token:
            raise ValueError("token is required")
        headers = _mcp_http_headers()
        headers[entry.mcp_auth_header] = f"{entry.mcp_auth_prefix or ''}{token}"
        spec: dict[str, Any] = {
            "transport": "http",
            "url": entry.mcp_url,
            "headers": headers,
        }
        if entry.allowed_tools is not None:
            spec["allowed_tools"] = list(entry.allowed_tools)
        return spec
    if entry.auth_kind == "anonymous":'''


def _replace_once(source: str, original: str, patched: str, *, marker: str) -> str:
    if marker in source:
        return source
    if original not in source:
        raise ValueError("Octop 1.0.2 connector source anchor not found")
    return source.replace(original, patched, 1)


def patch_catalog_source(source: str) -> str:
    """Add anonymous auth, catalog token-header fields, and auth UI handling."""
    if AUTH_KIND_END_PATCHED in source:
        catalog_patched = source
    elif AUTH_KIND_END in source:
        catalog_patched = source.replace(AUTH_KIND_END, AUTH_KIND_END_PATCHED, 1)
    else:
        raise ValueError("Octop 1.0.2 AuthKind anchor not found")
    catalog_patched = _replace_once(
        catalog_patched,
        QUICK_AUTH_ORIGINAL,
        QUICK_AUTH_PATCHED,
        marker='entry.auth_kind not in {"api_credentials", "anonymous"}',
    )
    catalog_patched = _replace_once(
        catalog_patched,
        MCP_FIELDS_ORIGINAL,
        MCP_FIELDS_PATCHED,
        marker="mcp_auth_header: str | None = None",
    )
    return _replace_once(
        catalog_patched,
        MCP_PARSE_ORIGINAL,
        MCP_PARSE_PATCHED,
        marker='mcp_auth_header=_optional_str(raw.get("mcp_auth_header")',
    )


def patch_builder_source(source: str) -> str:
    """Allow anonymous credentials and catalog-driven remote authentication."""
    credential_patched = _replace_once(
        source,
        VALIDATE_ORIGINAL,
        VALIDATE_PATCHED,
        marker='if entry.auth_kind == "anonymous":\n        return {"anonymous": True}',
    )
    token_patched = _replace_once(
        credential_patched,
        REMOTE_SPEC_ORIGINAL,
        REMOTE_SPEC_PATCHED,
        marker='if entry.auth_kind == "anonymous":\n        mcp_url = (entry.mcp_url or "").strip()',
    )
    return _replace_once(
        token_patched,
        REMOTE_TOKEN_ORIGINAL,
        REMOTE_TOKEN_PATCHED,
        marker='if entry.auth_kind == "personal_token" and entry.mcp_url and entry.mcp_auth_header:',
    )


def merge_catalog_payload(payload: dict[str, Any]) -> dict[str, Any]:
    """Upsert the reviewed keyless connectors without disturbing other entries."""
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
        if isinstance(row, dict) and row.get("kind") not in MCP_KINDS
    ]
    insert_at = next(
        (index + 1 for index, row in enumerate(retained) if row.get("kind") == "openalex"),
        len(retained),
    )
    output["connectors"] = (
        retained[:insert_at]
        + [copy.deepcopy(entry) for entry in MCP_ENTRIES]
        + retained[insert_at:]
    )
    return output


def _write_if_changed(path: Path, content: str, backup_dir: Path) -> bool:
    current = path.read_text(encoding="utf-8")
    if current == content:
        return False
    backup_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, backup_dir / path.name)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    temporary.write_text(content, encoding="utf-8")
    shutil.copymode(path, temporary)
    os.replace(temporary, path)
    return True


def install() -> list[Path]:
    site = octop_site()
    catalog_json_path = site / "infra/connectors/catalog.json"
    catalog_py_path = site / "infra/connectors/catalog.py"
    builder_py_path = site / "infra/connectors/builder.py"
    backup = backups_dir() / f"mcp-connectors-{time.strftime('%Y%m%d-%H%M%S')}"
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
    ):
        source = path.read_text(encoding="utf-8")
        patched = patcher(source)
        if _write_if_changed(path, patched, backup):
            changed.append(path)
    return changed


if __name__ == "__main__":
    for changed_path in install():
        print(changed_path)
