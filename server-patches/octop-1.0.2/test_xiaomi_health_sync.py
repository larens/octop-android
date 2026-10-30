from __future__ import annotations

import importlib.util
import json
import sys
import types
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path


def _install_module(name: str, **attributes: object) -> None:
    module = types.ModuleType(name)
    module.__dict__.update(attributes)
    sys.modules[name] = module


def _load_module():
    octop = types.ModuleType("octop")
    octop.__path__ = []
    sys.modules["octop"] = octop
    for name in (
        "octop.infra",
        "octop.infra.connectors",
        "octop.infra.db",
        "octop.infra.db.repos",
        "octop.infra.today",
    ):
        package = types.ModuleType(name)
        package.__path__ = []
        sys.modules[name] = package
    _install_module(
        "octop.infra.connectors.crypto",
        decrypt_credentials=lambda value: value,
        encrypt_credentials=lambda value: value,
    )
    _install_module("octop.infra.db.pool", SqlitePool=object)
    _install_module("octop.infra.db.repos.secrets", SecretRepo=object)
    _install_module(
        "octop.infra.today.health_cards",
        publish_health_today_cards=lambda *args, **kwargs: [],
    )
    _install_module(
        "health_trend_memory",
        sync_health_trend_memory=lambda *args, **kwargs: {},
    )
    _install_module(
        "xiaomi_health_cloud",
        MiHealthAuthError=RuntimeError,
        MiHealthCloudClient=object,
        optional_float=lambda value: float(value) if value is not None else None,
        optional_int=lambda value: int(value) if value is not None else None,
        parse_value=lambda item: json.loads(str(item.get("value") or "{}")),
    )

    path = Path(__file__).with_name("xiaomi_health_sync.py")
    spec = importlib.util.spec_from_file_location("xiaomi_health_sync", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


sync = _load_module()
CST = timezone(timedelta(hours=8))


class FakeClient:
    def __init__(
        self,
        records: list[dict[str, object]],
        daily_reports: list[dict[str, object]] | None = None,
    ) -> None:
        self.records = records
        self.daily_reports = daily_reports or []

    def fetch_key(self, *args: object, **kwargs: object) -> list[dict[str, object]]:
        return self.records

    def fetch_daily_reports(
        self,
        *args: object,
        **kwargs: object,
    ) -> list[dict[str, object]]:
        return self.daily_reports


class XiaomiDailyMetricTest(unittest.TestCase):
    def test_resting_heart_rate_uses_bpm_and_daily_average(self) -> None:
        noon = datetime(2026, 10, 28, 12, 0, tzinfo=CST)
        records = [
            {
                "time": int(noon.timestamp()) - 3600,
                "value": json.dumps({"date_time": int(noon.timestamp()), "bpm": 68}),
            },
            {
                "time": int(noon.timestamp()),
                "value": json.dumps({"date_time": int(noon.timestamp()), "bpm": 72}),
            },
        ]

        drafts = sync._daily_metric_events(
            FakeClient(records),
            "resting_heart_rate",
            "health.resting_heart_rate_daily",
            noon.replace(hour=0, minute=0, second=0, microsecond=0),
            noon,
        )

        self.assertEqual(1, len(drafts))
        draft = drafts[0]
        self.assertEqual("health.resting_heart_rate_daily", draft.event_type)
        self.assertEqual(70, draft.payload["average_bpm"])
        self.assertEqual(68, draft.payload["min_bpm"])
        self.assertEqual(72, draft.payload["max_bpm"])
        self.assertEqual(2, draft.payload["measurements"])

    def test_daily_goal_maps_active_minutes_without_mislabelling_steps(self) -> None:
        day_start = datetime(2026, 10, 28, 0, 0, tzinfo=CST)
        report = {
            "time": int(day_start.timestamp()),
            "update_time": int((day_start + timedelta(hours=19)).timestamp() * 1000),
            "value": json.dumps(
                {
                    "goal_items": [
                        {"field": 1, "achieved_value": 7240, "target_value": 8000},
                        {"field": 4, "achieved_value": 29, "target_value": 30},
                    ],
                    "stand_goal": {
                        "field": 3,
                        "achieved_value": 10,
                        "target_value": 12,
                    },
                }
            ),
        }

        drafts = sync._daily_goal_events(
            FakeClient([], daily_reports=[report]),
            day_start,
            day_start + timedelta(hours=20),
        )

        self.assertEqual(1, len(drafts))
        draft = drafts[0]
        self.assertEqual("health.activity_minutes_daily", draft.event_type)
        self.assertEqual(29, draft.payload["activity_minutes"])


if __name__ == "__main__":
    unittest.main()
