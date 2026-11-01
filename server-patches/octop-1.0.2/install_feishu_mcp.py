"""Install the official Feishu MCP catalog entry and OAuth metadata fallback."""

from __future__ import annotations

import copy
import json
import os
import shutil
import time
from pathlib import Path
from typing import Any

from patch_paths import backups_dir, octop_site


FEISHU_SCOPES = (
    "mcp-tool:common:fetch-file-v2",
    "mcp-tool:common:get-user",
    "mcp-tool:common:search-user",
    "mcp-tool:docs:search-doc",
    "mcp-tool:docs:create-doc",
    "mcp-tool:docs:fetch-doc",
    "mcp-tool:docs:update-doc",
    "mcp-tool:docs:get-comments",
    "mcp-tool:docs:add-comments",
    "mcp-tool:docs:list-docs-v2",
    "mcp-tool:im:search-groups",
    "mcp-tool:im:get-group-members",
    "mcp-tool:im:get-messages",
    "mcp-tool:im:get-thread-messages",
    "mcp-tool:im:search-messages",
    "mcp-tool:im:get-read-status",
    "offline_access",
)

FEISHU_MCP_ENTRY: dict[str, Any] = {
    "kind": "feishu-mcp",
    "name": {"en": "Feishu MCP", "zh": "飞书 MCP"},
    "description": {
        "en": "Official hosted MCP for Feishu docs and IM; use Feishu CLI for calendar and Base",
        "zh": "飞书官方托管 MCP：云文档与 IM 搜索；日程和多维表格继续使用飞书 CLI",
    },
    "auth_kind": "oauth2",
    "doc_url": (
        "https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/"
        "mcp_integration/mcp_introduction"
    ),
    "icon": "feishu-cli",
    "color": "#3370ff",
    "phase": "coming_soon",
    "mcp_mode": "remote",
    "category": "office",
    "guide_url": (
        "https://open.feishu.cn/document/uAjLw4CM/ukTMukTMukTM/"
        "mcp_integration/mcp_introduction"
    ),
    "manual_url": "https://open.feishu.cn/app",
    "auth_hint": {
        "en": "Authorize your Feishu account; the official MCP server scopes are requested",
        "zh": "点击「登录授权」并完成飞书账号授权；服务端会请求官方 MCP 支持的最小权限集",
    },
    "oauth_issuer": "https://accounts.feishu.cn/mcp",
    "mcp_url": "https://mcp.feishu.cn/mcp",
    "oauth_resource": "https://mcp.feishu.cn/mcp",
    "oauth_scopes": " ".join(FEISHU_SCOPES),
    "remote_transport": "streamable_http",
}


OLD_FETCH_METADATA = '''async def fetch_authorization_metadata(issuer: str) -> dict[str, Any]:
    metadata_url = f"{issuer.rstrip('/')}/.well-known/oauth-authorization-server"
    await _ensure_mcp_oauth_url(metadata_url, issuer=issuer, field="issuer_metadata")
    r = await safe_request("GET", metadata_url, timeout=20.0)
    r.raise_for_status()
    data = r.json()
    if not isinstance(data, dict):
        raise ValueError("invalid oauth metadata")
    if not data.get("authorization_endpoint") or not data.get("token_endpoint"):
        raise ValueError("oauth metadata missing endpoints")
    return await _validate_metadata_endpoints(data, issuer=issuer)
'''

NEW_FETCH_METADATA = '''def _authorization_metadata_urls(issuer: str) -> list[str]:
    """Return RFC 8414 candidates: legacy suffix first, path insertion second."""
    normalized = issuer.rstrip("/")
    suffix_url = f"{normalized}/.well-known/oauth-authorization-server"
    parsed = urlparse(normalized)
    inserted_path = f"/.well-known/oauth-authorization-server{parsed.path.rstrip('/')}"
    inserted_url = f"{parsed.scheme}://{parsed.netloc}{inserted_path}"
    return list(dict.fromkeys((suffix_url, inserted_url)))


async def fetch_authorization_metadata(issuer: str) -> dict[str, Any]:
    urls = _authorization_metadata_urls(issuer)
    for index, metadata_url in enumerate(urls):
        await _ensure_mcp_oauth_url(metadata_url, issuer=issuer, field="issuer_metadata")
        r = await safe_request("GET", metadata_url, timeout=20.0)
        # Feishu publishes its issuer at /mcp and serves metadata using RFC 8414
        # path insertion. Keep the legacy suffix attempt for existing providers.
        if r.status_code == 404 and index < len(urls) - 1:
            continue
        r.raise_for_status()
        data = r.json()
        if not isinstance(data, dict):
            raise ValueError("invalid oauth metadata")
        if not data.get("authorization_endpoint") or not data.get("token_endpoint"):
            raise ValueError("oauth metadata missing endpoints")
        return await _validate_metadata_endpoints(data, issuer=issuer)
    raise ValueError("oauth metadata not found")
'''


def merge_catalog_payload(payload: dict[str, Any]) -> dict[str, Any]:
    """Upsert Feishu MCP without disturbing upstream catalog entries."""
    output = copy.deepcopy(payload)
    rows = output.get("connectors")
    if not isinstance(rows, list):
        raise ValueError("catalog.json must contain a connectors array")
    kinds = [row.get("kind") for row in rows if isinstance(row, dict)]
    if len(kinds) != len(rows) or len(kinds) != len(set(kinds)):
        raise ValueError("catalog.json connector kinds must be unique objects")
    merged = [copy.deepcopy(row) for row in rows]
    if "feishu-mcp" in kinds:
        index = kinds.index("feishu-mcp")
        merged[index] = copy.deepcopy(FEISHU_MCP_ENTRY)
    else:
        merged.append(copy.deepcopy(FEISHU_MCP_ENTRY))
    output["connectors"] = merged
    return output


def patch_authorization_metadata_source(source: str) -> str:
    """Apply the RFC 8414 fallback once, preserving the original code path."""
    if "_authorization_metadata_urls" in source and "inserted_path" in source:
        return source
    if OLD_FETCH_METADATA not in source:
        raise ValueError("Octop 1.0.2 OAuth metadata anchor not found")
    return source.replace(OLD_FETCH_METADATA, NEW_FETCH_METADATA, 1)


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
    catalog_path = site / "infra/connectors/catalog.json"
    oauth_path = site / "infra/connectors/oauth/mcp.py"
    backup = backups_dir() / f"feishu-mcp-{time.strftime('%Y%m%d-%H%M%S')}"
    changed: list[Path] = []

    catalog_raw = json.loads(catalog_path.read_text(encoding="utf-8"))
    catalog_merged = merge_catalog_payload(catalog_raw)
    catalog_text = json.dumps(catalog_merged, ensure_ascii=False, indent=2) + "\n"
    if _write_if_changed(catalog_path, catalog_text, backup):
        changed.append(catalog_path)

    oauth_source = oauth_path.read_text(encoding="utf-8")
    oauth_patched = patch_authorization_metadata_source(oauth_source)
    if _write_if_changed(oauth_path, oauth_patched, backup):
        changed.append(oauth_path)
    return changed


if __name__ == "__main__":
    for path in install():
        print(path)
