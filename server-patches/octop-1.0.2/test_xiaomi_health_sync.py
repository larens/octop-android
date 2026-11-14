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


class _FakeConnection:
    def __init__(self, keys: list[str], owners: dict[str, int]) -> None:
        self._keys = keys
        self._owners = owners
        self._sql = ""

    def execute(self, sql: str, _params: object = None) -> _FakeConnection:
        self._sql = sql
        return self

    def fetchall(self) -> list[dict[str, object]]:
        if "FROM agents" in self._sql:
            return [
                {"agent_id": agent_id, "user_id": user_id}
                for agent_id, user_id in self._owners.items()
            ]
        return [{"k": key} for key in self._keys]


class _FakePool:
    def __init__(self, keys: list[str], owners: dict[str, int] | None = None) -> None:
        self._connection = _FakeConnection(keys, owners or {})

    def connect(self) -> _FakePool:
        return self

    def __enter__(self) -> _FakeConnection:
        return self._connection

    def __exit__(self, *_args: object) -> bool:
        return False


class XiaomiBindingTest(unittest.TestCase):
    def test_credentials_key_is_scoped_per_agent(self) -> None:
        self.assertEqual(
            "xiaomi_health_cloud:18CCXQ",
            sync._credentials_key("18CCXQ"),
        )
        self.assertNotEqual(
            sync._credentials_key("agent-a"),
            sync._credentials_key("agent-b"),
        )

    def test_load_bindings_prefers_agent_scoped_and_deduplicates_agents(self) -> None:
        keys = [
            "xiaomi_health_cloud",
            "xiaomi_health_cloud:18CCXQ",
            "xiaomi_health_cloud:NEW001",
        ]
        credentials = {
            "xiaomi_health_cloud": {
                "agent_id": "18CCXQ",
                "user_id": 2,
                "pass_token": "legacy",
            },
            "xiaomi_health_cloud:18CCXQ": {
                "agent_id": "18CCXQ",
                "user_id": 2,
                "pass_token": "scoped",
            },
            "xiaomi_health_cloud:NEW001": {
                "agent_id": "NEW001",
                "user_id": 3,
                "pass_token": "friend",
            },
        }
        original = sync._load_credentials
        sync._load_credentials = lambda _pool, key=sync.CREDENTIALS_KEY: (
            credentials[key],
            b"",
        )
        try:
            bindings = sync._load_bindings(
                _FakePool(keys, {"18CCXQ": 2, "NEW001": 3})
            )
        finally:
            sync._load_credentials = original

        self.assertEqual(
            ["xiaomi_health_cloud:18CCXQ", "xiaomi_health_cloud:NEW001"],
            [key for key, _ in bindings],
        )
        self.assertEqual(["18CCXQ", "NEW001"], [row["agent_id"] for _, row in bindings])
        self.assertEqual(["scoped", "friend"], [row["pass_token"] for _, row in bindings])

    def test_load_bindings_skips_incomplete_binding(self) -> None:
        original = sync._load_credentials
        sync._load_credentials = lambda _pool, key=sync.CREDENTIALS_KEY: (
            {"agent_id": "", "user_id": 2},
            b"",
        )
        try:
            bindings = sync._load_bindings(_FakePool(["xiaomi_health_cloud:EMPTY"]))
        finally:
            sync._load_credentials = original

        self.assertEqual([], bindings)

    def test_load_bindings_skips_agent_owner_mismatch(self) -> None:
        original = sync._load_credentials
        sync._load_credentials = lambda _pool, key=sync.CREDENTIALS_KEY: (
            {"agent_id": "STOLEN", "user_id": 3},
            b"",
        )
        try:
            bindings = sync._load_bindings(
                _FakePool(["xiaomi_health_cloud:STOLEN"], {"STOLEN": 2})
            )
        finally:
            sync._load_credentials = original

        self.assertEqual([], bindings)


class _FakeCloudClient:
    def __init__(self, **kwargs: object) -> None:
        self.pass_token = str(kwargs.get("pass_token") or "")
        self.fetched: list[tuple[object, ...]] = []

    def __enter__(self) -> _FakeCloudClient:
        return self

    def __exit__(self, *_args: object) -> bool:
        return False

    def fetch_key(self, *args: object, **kwargs: object) -> list[object]:
        self.fetched.append(("key", *args))
        return []

    def fetch_daily_reports(self, *args: object, **kwargs: object) -> list[object]:
        self.fetched.append(("daily", *args))
        return []

    def fetch_sport_records(self, *args: object, **kwargs: object) -> list[object]:
        self.fetched.append(("sport", *args))
        return []


class XiaomiMultiBindingSyncTest(unittest.TestCase):
    def setUp(self) -> None:
        self.originals = {
            name: getattr(sync, name)
            for name in (
                "_read_last_sync",
                "_write_last_sync",
                "_upsert_events",
                "publish_health_today_cards",
                "sync_health_trend_memory",
                "MiHealthCloudClient",
                "SqlitePool",
                "_load_bindings",
                "_sync_binding",
            )
        }
        self.calls: list[tuple[object, ...]] = []
        sync._read_last_sync = lambda *_args, **_kwargs: None
        sync._write_last_sync = (
            lambda *_args, key=sync.LAST_SYNC_KEY, **_kwargs: self.calls.append(
                ("last_sync", key)
            )
        )
        sync._upsert_events = (
            lambda _pool, *, agent_id, user_id, drafts: self.calls.append(
                ("upsert", agent_id, user_id)
            )
            or len(list(drafts))
        )
        sync.publish_health_today_cards = (
            lambda _pool, *, agent_id, user_id, now: self.calls.append(
                ("cards", agent_id, user_id)
            )
            or []
        )
        sync.sync_health_trend_memory = (
            lambda _pool, *, agent_id, user_id, now: self.calls.append(
                ("memory", agent_id, user_id)
            )
        )
        sync.MiHealthCloudClient = _FakeCloudClient

    def tearDown(self) -> None:
        for name, value in self.originals.items():
            setattr(sync, name, value)

    def test_each_binding_writes_only_to_its_agent_and_user(self) -> None:
        result = sync._sync_binding(
            object(),
            key="xiaomi_health_cloud:NEW001",
            credentials={
                "agent_id": "NEW001",
                "user_id": 3,
                "xiaomi_user_id": "friend",
                "pass_token": "friend-token",
            },
            force_full=True,
            publish_cards=True,
        )

        self.assertEqual(1, result["upserted"])
        self.assertIn(("upsert", "NEW001", 3), self.calls)
        self.assertIn(("cards", "NEW001", 3), self.calls)
        self.assertIn(("memory", "NEW001", 3), self.calls)
        self.assertIn(("last_sync", "xiaomi_health.last_sync:NEW001"), self.calls)
        self.assertNotIn(("cards", "18CCXQ", 2), self.calls)

    def test_one_expired_binding_does_not_block_other_users(self) -> None:
        class _ClosablePool:
            def close(self) -> None:
                pass

        sync.SqlitePool = lambda _path: _ClosablePool()
        sync._load_bindings = lambda _pool: [
            ("xiaomi_health_cloud:OLD", {"agent_id": "OLD", "user_id": 2}),
            ("xiaomi_health_cloud:NEW", {"agent_id": "NEW", "user_id": 3}),
        ]

        def fake_sync(_pool: object, *, key: str, **_kwargs: object) -> dict[str, int]:
            self.calls.append(("binding", key))
            if key.endswith(":OLD"):
                raise sync.MiHealthAuthError("expired")
            return {"upserted": 1}

        sync._sync_binding = fake_sync
        with self.assertRaises(sync.MiHealthAuthError):
            sync.sync(force_full=True)

        self.assertIn(("binding", "xiaomi_health_cloud:OLD"), self.calls)
        self.assertIn(("binding", "xiaomi_health_cloud:NEW"), self.calls)


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
