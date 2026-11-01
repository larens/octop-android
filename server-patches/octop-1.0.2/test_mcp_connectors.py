"""Focused tests for reviewed anonymous MCP connectors."""

from __future__ import annotations

import inspect
import unittest
from typing import Any, cast

from install_mcp_connectors import (
    MCP_ENTRIES,
    MCP_KINDS,
    merge_catalog_payload,
    patch_builder_source,
    patch_catalog_source,
)
from octop.infra.connectors import builder as connector_builder
from octop.infra.connectors import catalog as connector_catalog
from octop.infra.connectors.builder import (
    build_http_mcp_spec,
    validate_create_credentials,
)
from octop.infra.connectors.catalog import (
    catalog_entry_to_dict,
    get_catalog_entry,
    is_mcp_oauth_remote,
)
from octop.infra.connectors.probe import prepare_probe_credentials


EXPECTED_URLS = {
    "deepwiki": "https://mcp.deepwiki.com/mcp",
    "context7": "https://mcp.context7.com/mcp",
    "microsoft-learn": "https://learn.microsoft.com/api/mcp",
    "exa": "https://mcp.exa.ai/mcp",
    "cloudflare-docs": "https://docs.mcp.cloudflare.com/mcp",
    "github": "https://api.githubcopilot.com/mcp/",
    "atlassian": "https://mcp.atlassian.com/v1/mcp/authv2",
    "linear": "https://mcp.linear.app/mcp",
    "figma": "https://mcp.figma.com/mcp",
    "canva": "https://mcp.canva.com/mcp",
    "stripe": "https://mcp.stripe.com",
    "sentry": "https://mcp.sentry.dev/mcp",
    "postman": "https://mcp.postman.com/mcp",
}


class CatalogInstallTests(unittest.TestCase):
    def test_catalog_merge_is_additive_and_idempotent(self) -> None:
        original = {
            "version": 1,
            "connectors": [
                {"kind": "openalex", "name": {"en": "OpenAlex", "zh": "OpenAlex"}},
                {"kind": "dida365", "name": {"en": "Dida", "zh": "滴答"}},
            ],
        }
        first = merge_catalog_payload(original)
        second = merge_catalog_payload(first)
        self.assertEqual(first, second)
        self.assertEqual(
            [row["kind"] for row in first["connectors"]],
            ["openalex", *MCP_KINDS, "dida365"],
        )
        self.assertEqual(original["connectors"][0]["kind"], "openalex")

    def test_installers_are_idempotent(self) -> None:
        catalog_source = inspect.getsource(connector_catalog)
        builder_source = inspect.getsource(connector_builder)
        self.assertEqual(patch_catalog_source(catalog_source), catalog_source)
        self.assertEqual(patch_builder_source(builder_source), builder_source)


class InstalledCatalogTests(unittest.TestCase):
    def test_entries_are_available_and_match_reviewed_auth(self) -> None:
        for raw in MCP_ENTRIES:
            kind = str(raw["kind"])
            entry = get_catalog_entry(kind)
            self.assertIsNotNone(entry, kind)
            assert entry is not None
            self.assertEqual(entry.auth_kind, raw["auth_kind"])
            self.assertEqual(entry.phase, "available")
            self.assertEqual(entry.remote_transport, "streamable_http")
            self.assertEqual(entry.mcp_url, EXPECTED_URLS[kind])
            self.assertEqual(is_mcp_oauth_remote(entry), raw["auth_kind"] == "oauth2")
            row = catalog_entry_to_dict(entry, locale="zh")
            self.assertEqual(
                row["supports_quick_auth"],
                raw["auth_kind"] not in {"api_credentials", "anonymous"},
            )

    def test_empty_credentials_are_accepted(self) -> None:
        for raw in MCP_ENTRIES:
            kind = str(raw["kind"])
            if raw["auth_kind"] == "anonymous":
                self.assertEqual(validate_create_credentials(kind, {}), {"anonymous": True})
            else:
                with self.assertRaises(ValueError):
                    validate_create_credentials(kind, {})

    def test_probe_preparation_is_keyless(self) -> None:
        import asyncio

        for raw in MCP_ENTRIES:
            kind = str(raw["kind"])
            if raw["auth_kind"] == "anonymous":
                prepared = asyncio.run(prepare_probe_credentials(kind, {}))
                self.assertEqual(prepared, {"anonymous": True})

    def test_remote_specs_use_reviewed_auth_and_tools(self) -> None:
        config = cast(Any, object())
        for raw in MCP_ENTRIES:
            kind = str(raw["kind"])
            entry = get_catalog_entry(kind)
            assert entry is not None
            creds: dict[str, str]
            if raw["auth_kind"] == "anonymous":
                creds = {"anonymous": "true"}
            elif raw["auth_kind"] == "personal_token":
                creds = {"token": "github-token"}
            else:
                creds = {"access_token": "oauth-token"}
            spec = build_http_mcp_spec(
                entry=entry,
                instance_id="test",
                creds=creds,
                config=config,
            )
            self.assertEqual(spec["transport"], "http")
            self.assertEqual(spec["url"], EXPECTED_URLS[kind])
            self.assertEqual(
                spec["headers"]["Accept"],
                "application/json, text/event-stream",
            )
            if raw["auth_kind"] == "anonymous":
                self.assertNotIn("Authorization", spec["headers"])
            elif raw["auth_kind"] == "personal_token":
                self.assertEqual(spec["headers"]["Authorization"], "Bearer github-token")
            else:
                self.assertEqual(spec["headers"]["Authorization"], "Bearer oauth-token")
            self.assertEqual(spec.get("allowed_tools"), raw.get("allowed_tools"))


if __name__ == "__main__":
    unittest.main()
