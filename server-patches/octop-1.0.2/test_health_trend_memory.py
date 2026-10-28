from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path


def _load_module():
    path = Path(__file__).with_name("health_trend_memory.py")
    spec = importlib.util.spec_from_file_location("health_trend_memory", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


trends = _load_module()
CST = timezone(timedelta(hours=8))
NOW = datetime(2026, 10, 28, 21, 0, tzinfo=CST)


def _event(
    event_type: str,
    occurred_at: datetime,
    payload: dict[str, object],
    *,
    observed_at: datetime | None = None,
) -> object:
    return trends.TrendEvent(
        event_id=f"{event_type}:{occurred_at.isoformat()}",
        event_type=event_type,
        occurred_at=int(occurred_at.timestamp()),
        observed_at=int((observed_at or occurred_at).timestamp()),
        payload={
            "timezone_offset_minutes": 480,
            **payload,
        },
    )


def _daily_events(days: int, *, end: date) -> list[object]:
    events: list[object] = []
    for offset in range(days):
        day = end - timedelta(days=offset)
        start = datetime.combine(day, time.min, tzinfo=CST)
        events.extend(
            [
                _event(
                    "health.steps_30d",
                    start,
                    {"date": day.isoformat(), "steps": 7000 + offset * 100},
                ),
                _event(
                    "health.standing_daily",
                    start,
                    {
                        "date": day.isoformat(),
                        "stand_hours": 9,
                        "longest_sedentary_minutes": 65,
                    },
                ),
                _event(
                    "health.heart_rate_daily",
                    start,
                    {"date": day.isoformat(), "average_bpm": 70 + offset % 3},
                ),
                _event(
                    "health.oxygen_saturation_daily",
                    start,
                    {
                        "date": day.isoformat(),
                        "average_percent": 97,
                        "min_percent": 95,
                    },
                ),
                _event(
                    "health.stress_daily",
                    start,
                    {"date": day.isoformat(), "average_score": 42},
                ),
            ]
        )
    return events


def _sleep_events(nights: int, *, end: date) -> list[object]:
    events: list[object] = []
    for offset in range(nights):
        day = end - timedelta(days=offset)
        starts_at = datetime.combine(
            day - timedelta(days=1),
            time(23, 30),
            tzinfo=CST,
        )
        ends_at = datetime.combine(day, time(7, 0), tzinfo=CST)
        events.append(
            _event(
                "health.sleep_session",
                starts_at,
                {
                    "starts_at": int(starts_at.timestamp()),
                    "ends_at": int(ends_at.timestamp()),
                    "duration_minutes": 450,
                    "stages_minutes": {"deep": 90, "awake": 20},
                },
                observed_at=ends_at,
            )
        )
    return events


@dataclass
class FakeEntity:
    id: str
    canonical_name: str


@dataclass
class FakeAtom:
    id: str
    entity_id: str
    assertion: str


@dataclass
class FakeMemory:
    atoms: dict[str, FakeAtom] = field(default_factory=dict)
    entity: FakeEntity | None = None
    create_calls: int = 0
    replace_calls: int = 0

    def find_entity_by_name(self, name: str, *, entity_type: str | None = None):
        if self.entity and self.entity.canonical_name == name:
            return self.entity
        return None

    def find_entity_by_alias(self, alias: str):
        return self.entity if self.entity and self.entity.canonical_name == alias else None

    def list_atoms(self, *, entity_id: str, limit: int = 100):
        return [
            atom for atom in self.atoms.values() if atom.entity_id == entity_id
        ][:limit]

    def create_atom(self, assertion: str, **kwargs: object):
        self.create_calls += 1
        if self.entity is None:
            self.entity = FakeEntity("entity-health", trends.ENTITY_NAME)
        atom = FakeAtom(
            id=f"atom-{len(self.atoms) + 1}",
            entity_id=self.entity.id,
            assertion=assertion,
        )
        self.atoms[atom.id] = atom
        return atom, self.entity, False

    def replace_atom(self, atom_id: str, *, assertion: str, **kwargs: object):
        self.replace_calls += 1
        self.atoms[atom_id].assertion = assertion
        return self.atoms[atom_id]


class FakeConnection:
    def __init__(self, row: object) -> None:
        self.row = row

    def __enter__(self):
        return self

    def __exit__(self, *args: object) -> None:
        return None

    def execute(self, *args: object, **kwargs: object):
        return self

    def fetchone(self):
        return self.row


class FakePool:
    def __init__(self, row: object) -> None:
        self.row = row

    def connect(self):
        return FakeConnection(self.row)


class HealthTrendBuildTest(unittest.TestCase):
    def test_six_days_are_not_enough_for_long_term_memory(self) -> None:
        events = _daily_events(6, end=date(2026, 10, 27))
        events += _sleep_events(6, end=date(2026, 10, 28))

        self.assertEqual([], trends.build_health_trends(events, now=NOW))

    def test_builds_stable_activity_sleep_and_recovery_summaries(self) -> None:
        events = _daily_events(7, end=date(2026, 10, 27))
        events += _sleep_events(7, end=date(2026, 10, 28))
        events.append(
            _event(
                "health.exercise_session",
                datetime(2026, 10, 27, 18, 0, tzinfo=CST),
                {
                    "duration_minutes": 30,
                    "exercise_type_name": "walking",
                },
            )
        )

        observations = trends.build_health_trends(events, now=NOW)

        self.assertEqual(
            ["activity", "sleep", "recovery"],
            [item.key for item in observations],
        )
        combined = "\n".join(item.assertion for item in observations)
        self.assertIn("近 7 天日均", combined)
        self.assertIn("平均 7小时30分", combined)
        self.assertIn("日均全天心率", combined)
        self.assertIn("主要为步行", combined)
        for forbidden in (
            "pass_token",
            "serviceToken",
            "cookie",
            "xiaomi_user_id",
            "ssecurity",
        ):
            self.assertNotIn(forbidden, combined)

    def test_publish_is_idempotent_and_updates_existing_atoms(self) -> None:
        observations = trends.build_health_trends(
            _daily_events(7, end=date(2026, 10, 27))
            + _sleep_events(7, end=date(2026, 10, 28)),
            now=NOW,
        )
        memory = FakeMemory()

        first = trends.publish_health_trends(observations, memory)
        second = trends.publish_health_trends(observations, memory)
        self.assertEqual(3, first["created"])
        self.assertEqual(0, second["created"])
        self.assertEqual(3, second["unchanged"])
        self.assertEqual(3, len(memory.atoms))

        updated = [
            trends.TrendObservation(
                key=item.key,
                assertion=item.assertion.replace("截至", "更新截至"),
                confidence=item.confidence,
            )
            for item in observations
        ]
        third = trends.publish_health_trends(updated, memory)

        self.assertEqual(0, third["created"])
        self.assertEqual(3, third["replaced"])
        self.assertEqual(3, len(memory.atoms))
        self.assertTrue(all("更新截至" in atom.assertion for atom in memory.atoms.values()))

    def test_resolves_memory_database_from_agent_workspace(self) -> None:
        row = {
            "config_json": json.dumps(
                {
                    "backend": {
                        "type": "local_shell",
                        "root_dir": "/data/agent-workspace",
                    },
                    "workspace_dir": "/.octop/workspaces/18CCXQ",
                    "system_files_path": ".octop",
                }
            )
        }

        backend = trends._memory_backend(FakePool(row), "18CCXQ")

        self.assertEqual("sqlite", backend[0])
        self.assertEqual(
            "/data/agent-workspace/.octop/workspaces/18CCXQ/.octop/memory.sqlite",
            backend[1]["db_path"],
        )

    def test_parser_ignores_non_health_events(self) -> None:
        rows = [
            {
                "event_id": "health-1",
                "event_type": "health.steps_30d",
                "occurred_at": int(NOW.timestamp()),
                "observed_at": int(NOW.timestamp()),
                "payload_json": '{"steps": 7000, "data_source": "xiaomi_health_cloud"}',
            },
            {
                "event_id": "calendar-1",
                "event_type": "calendar",
                "occurred_at": int(NOW.timestamp()),
                "observed_at": int(NOW.timestamp()),
                "payload_json": "{}",
            },
        ]

        parsed = trends.parse_trend_events(rows)

        self.assertEqual(["health.steps_30d"], [event.event_type for event in parsed])
