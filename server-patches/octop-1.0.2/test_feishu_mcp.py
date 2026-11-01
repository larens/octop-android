"""Focused tests for the Feishu MCP catalog entry and OAuth discovery fallback."""

from __future__ import annotations

import unittest
from unittest.mock import patch

import httpx

import octop.infra.connectors.oauth.mcp as oauth_mcp
from install_feishu_mcp import (
    FEISHU_MCP_ENTRY,
    FEISHU_SCOPES,
    merge_catalog_payload,
)
from octop.infra.connectors.catalog import get_catalog_entry, is_mcp_oauth_remote
from octop.infra.connectors.oauth.registry import _scopes_for_kind
from octop.infra.utils.ssrf_guard import host_allowed_for_issuer


class CatalogInstallTests(unittest.TestCase):
    def test_merge_is_additive_and_idempotent(self) -> None:
        original = {
            "version": 1,
            "connectors": [
                {"kind": "feishu-cli", "name": {"en": "CLI", "zh": "CLI"}},
                {"kind": "didi", "name": {"en": "Didi", "zh": "滴滴"}},
            ],
        }
        first = merge_catalog_payload(original)
        second = merge_catalog_payload(first)
        self.assertEqual(first, second)
        self.assertEqual(
            [row["kind"] for row in first["connectors"]],
            ["feishu-cli", "didi", "feishu-mcp"],
        )
        self.assertEqual(original["connectors"][0]["kind"], "feishu-cli")

    def test_installed_catalog_entry(self) -> None:
        entry = get_catalog_entry("feishu-mcp")
        self.assertIsNotNone(entry)
        assert entry is not None
        self.assertEqual(entry.phase, "coming_soon")
        self.assertTrue(is_mcp_oauth_remote(entry))
        self.assertEqual(entry.mcp_url, "https://mcp.feishu.cn/mcp")
        self.assertEqual(entry.oauth_issuer, "https://accounts.feishu.cn/mcp")
        self.assertEqual(set((entry.oauth_scopes or "").split()), set(FEISHU_SCOPES))
        self.assertEqual(_scopes_for_kind("feishu-mcp", {}), FEISHU_MCP_ENTRY["oauth_scopes"])

    def test_feishu_cross_subdomain_registration_is_allowed(self) -> None:
        issuer = FEISHU_MCP_ENTRY["oauth_issuer"]
        self.assertTrue(host_allowed_for_issuer("open.feishu.cn", issuer))
        self.assertTrue(host_allowed_for_issuer("accounts.feishu.cn", issuer))


class OAuthMetadataTests(unittest.IsolatedAsyncioTestCase):
    async def test_path_insertion_fallback_on_404(self) -> None:
        requested: list[str] = []
        success = {
            "authorization_endpoint": "https://accounts.feishu.cn/oauth/v2/mcp/authorize",
            "token_endpoint": "https://accounts.feishu.cn/oauth/v3/token",
            "registration_endpoint": "https://open.feishu.cn/open-apis/app/v1/dcr",
            "token_endpoint_auth_methods_supported": ["none"],
        }

        async def fake_request(method: str, url: str, **_kwargs: object) -> httpx.Response:
            requested.append(url)
            if len(requested) == 1:
                return httpx.Response(404, request=httpx.Request(method, url))
            return httpx.Response(200, json=success, request=httpx.Request(method, url))

        async def allow(url: str, **_kwargs: object) -> str:
            return url

        with (
            patch.object(oauth_mcp, "_ensure_mcp_oauth_url", allow),
            patch.object(oauth_mcp, "safe_request", fake_request),
        ):
            metadata = await oauth_mcp.fetch_authorization_metadata(
                "https://accounts.feishu.cn/mcp"
            )

        self.assertEqual(
            requested,
            [
                "https://accounts.feishu.cn/mcp/.well-known/oauth-authorization-server",
                "https://accounts.feishu.cn/.well-known/oauth-authorization-server/mcp",
            ],
        )
        self.assertEqual(metadata["token_endpoint"], success["token_endpoint"])

    async def test_non_404_error_does_not_fallback(self) -> None:
        requested: list[str] = []

        async def fake_request(method: str, url: str, **_kwargs: object) -> httpx.Response:
            requested.append(url)
            return httpx.Response(500, request=httpx.Request(method, url))

        async def allow(url: str, **_kwargs: object) -> str:
            return url

        with (
            patch.object(oauth_mcp, "_ensure_mcp_oauth_url", allow),
            patch.object(oauth_mcp, "safe_request", fake_request),
        ):
            with self.assertRaises(httpx.HTTPStatusError):
                await oauth_mcp.fetch_authorization_metadata(
                    "https://accounts.feishu.cn/mcp"
                )
        self.assertEqual(len(requested), 1)


if __name__ == "__main__":
    unittest.main()
