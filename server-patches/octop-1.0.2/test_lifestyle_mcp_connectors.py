"""Focused tests for lifestyle MCP connector installation and behavior."""

from __future__ import annotations

import inspect
import unittest
from typing import Any, cast
from urllib.parse import quote

from china_holidays_mcp import HOLIDAYS, holiday_payload, workday_status
from install_lifestyle_mcp_connectors import (
    ANTV_CHART_TOOLS,
    LIFESTYLE_MCP_ENTRIES,
    LIFESTYLE_MCP_KINDS,
    RAILWAY_12306_TOOLS,
    STOCK_MARKET_TOOLS,
    merge_catalog_payload,
    patch_builder_source,
    patch_catalog_source,
    patch_probe_source,
)
from octop.infra.connectors import builder as connector_builder
from octop.infra.connectors import catalog as connector_catalog
from octop.infra.connectors import probe as connector_probe
from octop.infra.connectors.builder import build_http_mcp_spec
from octop.infra.connectors.catalog import get_catalog_entry


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
            ["openalex", *LIFESTYLE_MCP_KINDS, "dida365"],
        )
        self.assertEqual(original["connectors"][0]["kind"], "openalex")

    def test_source_patches_are_idempotent(self) -> None:
        catalog_source = inspect.getsource(connector_catalog)
        builder_source = inspect.getsource(connector_builder)
        probe_source = inspect.getsource(connector_probe)
        self.assertEqual(patch_catalog_source(catalog_source), catalog_source)
        self.assertEqual(patch_builder_source(builder_source), builder_source)
        self.assertEqual(patch_probe_source(probe_source), probe_source)


class InstalledCatalogTests(unittest.TestCase):
    def test_entries_are_available(self) -> None:
        for raw in LIFESTYLE_MCP_ENTRIES:
            kind = str(raw["kind"])
            entry = get_catalog_entry(kind)
            self.assertIsNotNone(entry, kind)
            assert entry is not None
            self.assertEqual(entry.phase, "available")
            self.assertEqual(entry.auth_kind, raw["auth_kind"])
            self.assertEqual(entry.mcp_mode, raw["mcp_mode"])
            self.assertEqual(entry.allowed_tools, tuple(raw.get("allowed_tools") or ()) or None)

    def test_url_template_and_header_authentication(self) -> None:
        config = cast(Any, object())
        cases = {
            "amap": {
                "creds": {"api_key": "amap/key+value"},
                "url": "https://mcp.amap.com/mcp?key=amap%2Fkey%2Bvalue",
                "header": None,
            },
            "variflight": {
                "creds": {"api_key": "vf-key"},
                "url": "https://ai.variflight.com/servers/aviation/mcp",
                "header": ("X-API-Key", "vf-key"),
            },
            "mcdonalds": {
                "creds": {"token": "mcd-token"},
                "url": "https://mcp.mcd.cn",
                "header": ("Authorization", "Bearer mcd-token"),
            },
            "kuaidi100": {
                "creds": {"api_key": "kd/key+value"},
                "url": (
                    "https://api.kuaidi100.com/mcp/streamable"
                    f"?key={quote('kd/key+value', safe='')}"
                ),
                "header": None,
            },
            "caiyun-weather": {
                "creds": {"api_key": "cy-key"},
                "url": "https://mcp-weather.caiyunapp.com/mcp",
                "header": ("X-Caiyun-API-Key", "cy-key"),
            },
        }
        for kind, expected in cases.items():
            entry = get_catalog_entry(kind)
            assert entry is not None
            spec = build_http_mcp_spec(
                entry=entry,
                instance_id="test",
                creds=expected["creds"],
                config=config,
            )
            self.assertEqual(spec["transport"], "http")
            self.assertEqual(spec["url"], expected["url"])
            header = expected["header"]
            if header is None:
                self.assertNotIn("X-API-Key", spec["headers"])
                self.assertNotIn("X-Caiyun-API-Key", spec["headers"])
            else:
                self.assertEqual(spec["headers"][header[0]], header[1])

    def test_stdio_specs_are_pinned_and_limited(self) -> None:
        config = cast(Any, object())
        expected = {
            "railway-12306": ["-y", "12306-mcp@0.3.10"],
            "stock-market": ["-y", "stock-sdk@2.4.6", "mcp"],
            "antv-chart": ["-y", "@antv/mcp-server-chart@0.9.10"],
        }
        for kind, args in expected.items():
            entry = get_catalog_entry(kind)
            assert entry is not None
            spec = build_http_mcp_spec(
                entry=entry,
                instance_id="test",
                creds={"anonymous": True},
                config=config,
            )
            self.assertEqual(spec["transport"], "stdio")
            self.assertEqual(spec["command"], "npx")
            self.assertEqual(spec["args"], args)
            self.assertEqual(spec["allowed_tools"], list(entry.allowed_tools or ()))

        stock = get_catalog_entry("stock-market")
        assert stock is not None
        stock_spec = build_http_mcp_spec(
            entry=stock,
            instance_id="test",
            creds={"anonymous": True},
            config=config,
        )
        self.assertEqual(stock_spec["env"], {"STOCK_SDK_MCP_TOOLS": "full"})
        self.assertEqual(set(stock_spec["allowed_tools"]), set(STOCK_MARKET_TOOLS))

        holidays = get_catalog_entry("china-holidays")
        assert holidays is not None
        holiday_spec = build_http_mcp_spec(
            entry=holidays,
            instance_id="test",
            creds={"anonymous": True},
            config=config,
        )
        self.assertEqual(holiday_spec["transport"], "stdio")
        self.assertTrue(holiday_spec["command"].endswith("/python"))
        self.assertEqual(
            holiday_spec["args"],
            ["-m", "octop.infra.connectors.china_holidays_mcp"],
        )

    def test_selected_tool_lists(self) -> None:
        self.assertEqual(
            RAILWAY_12306_TOOLS,
            [
                "get-current-date",
                "get-stations-code-in-city",
                "get-station-code-of-citys",
                "get-station-code-by-names",
                "get-station-by-telecode",
                "get-tickets",
                "get-interline-tickets",
                "get-train-route-stations",
            ],
        )
        self.assertEqual(len(STOCK_MARKET_TOOLS), 13)
        self.assertEqual(len(ANTV_CHART_TOOLS), 27)


class HolidayDataTests(unittest.TestCase):
    def test_published_years_have_official_sources(self) -> None:
        self.assertEqual(sorted(HOLIDAYS), [2025, 2026])
        for year, data in HOLIDAYS.items():
            self.assertTrue(data["source_url"].startswith("https://www.gov.cn/"))
            self.assertRegex(data["published_at"], r"^\d{4}-\d{2}-\d{2}$")
            self.assertEqual(
                sum(period["days"] for period in data["periods"]),
                sum(
                    (
                        __import__("datetime").date.fromisoformat(period["end"])
                        - __import__("datetime").date.fromisoformat(period["start"])
                    ).days
                    + 1
                    for period in data["periods"]
                ),
                year,
            )

    def test_2026_holiday_and_makeup_workday(self) -> None:
        holiday = workday_status("2026-02-17")
        self.assertFalse(holiday["is_workday"])
        self.assertEqual(holiday["holiday"], "春节")
        self.assertEqual(holiday["reason"], "statutory_holiday")

        makeup = workday_status("2026-02-28")
        self.assertTrue(makeup["is_workday"])
        self.assertTrue(makeup["makeup_workday"])
        self.assertEqual(makeup["reason"], "makeup_workday")

    def test_weekday_weekend_and_missing_year(self) -> None:
        self.assertTrue(workday_status("2026-03-02")["is_workday"])
        self.assertFalse(workday_status("2026-03-01")["is_workday"])
        missing = holiday_payload(2027)
        self.assertFalse(missing["available"])
        self.assertEqual(missing["available_years"], [2025, 2026])


if __name__ == "__main__":
    unittest.main()
