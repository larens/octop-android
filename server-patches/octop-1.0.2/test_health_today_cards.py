from __future__ import annotations

import importlib.util
import json
import sys
import types
import unittest
from datetime import date, datetime, timedelta, timezone
from pathlib import Path


def _install_module(name: str, **attributes: object) -> None:
    module = types.ModuleType(name)
    module.__dict__.update(attributes)
    sys.modules[name] = module


def _load_module():
    octop = types.ModuleType("octop")
    octop.__path__ = []
    sys.modules["octop"] = octop
    for name in ("octop.infra", "octop.infra.db", "octop.infra.db.repos", "octop.infra.today"):
        package = types.ModuleType(name)
        package.__path__ = []
        sys.modules[name] = package
    _install_module("octop.infra.db.pool", DatabasePool=object)
    _install_module("octop.infra.db.repos.agents", AgentRepo=object)

    class Evidence:
        def __init__(self, **values: object) -> None:
            self.__dict__.update(values)

    _install_module(
        "octop.infra.today.cards",
        TodayCardDraft=object,
        TodayCardPublisher=object,
        TodayEvidenceInput=Evidence,
    )

    path = Path(__file__).with_name("health_today_cards.py")
    spec = importlib.util.spec_from_file_location("health_today_cards", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


health_cards = _load_module()
CST = timezone(timedelta(hours=8))


def _epoch(value: datetime) -> int:
    return int(value.timestamp())


def _row(
    event_id: str,
    event_type: str,
    occurred_at: datetime,
    payload: dict[str, object],
    *,
    observed_at: datetime | None = None,
    source_type: str = "xiaomi_health",
    data_source: str = "xiaomi_health_cloud",
) -> dict[str, object]:
    return {
        "event_id": event_id,
        "source_record_id": event_id,
        "source_type": source_type,
        "event_type": event_type,
        "occurred_at": _epoch(occurred_at),
        "observed_at": _epoch(observed_at or occurred_at),
        "payload": json.dumps(
            {
                "data_source": data_source,
                "timezone_offset_minutes": 480,
                **payload,
            }
        ),
    }


def _sleep_rows(target_date: date = date(2026, 10, 28)) -> list[dict[str, object]]:
    starts_at = datetime.combine(
        target_date - timedelta(days=1),
        datetime.min.time(),
        tzinfo=CST,
    ) + timedelta(hours=23, minutes=30)
    ends_at = datetime.combine(target_date, datetime.min.time(), tzinfo=CST) + timedelta(
        hours=7
    )
    return [
        _row(
            f"sleep-{target_date.isoformat()}",
            "health.sleep_session",
            starts_at,
            {
                "starts_at": _epoch(starts_at),
                "ends_at": _epoch(ends_at),
                "duration_minutes": 450,
                "stages_minutes": {"deep": 90, "rem": 80, "awake": 20},
                "sleep_score": 82,
            },
            observed_at=ends_at,
        )
    ]


def _sleep_history_rows(
    end_date: date = date(2026, 10, 28),
    nights: int = 14,
) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    for offset in range(nights):
        target_date = end_date - timedelta(days=offset)
        duration = 450 if offset == 0 else 420 - min(offset, 6) * 5
        starts_at = datetime.combine(
            target_date - timedelta(days=1),
            datetime.min.time(),
            tzinfo=CST,
        ) + timedelta(hours=23, minutes=30 + (offset % 4) * 5)
        ends_at = starts_at + timedelta(minutes=duration)
        rows.append(
            _row(
                f"sleep-history-{target_date.isoformat()}",
                "health.sleep_session",
                starts_at,
                {
                    "starts_at": _epoch(starts_at),
                    "ends_at": _epoch(ends_at),
                    "duration_minutes": duration,
                    "stages_minutes": {
                        "deep": 90 if offset == 0 else 68 + (offset % 3) * 4,
                        "rem": 80 if offset == 0 else 62,
                        "awake": 20 if offset == 0 else 25 + (offset % 3) * 5,
                    },
                    "sleep_score": 82 if offset == 0 else 76,
                },
                observed_at=ends_at,
            )
        )
    return rows


def _daily_metric_rows(target_date: date = date(2026, 10, 28)) -> list[dict[str, object]]:
    day_start = datetime.combine(target_date, datetime.min.time(), tzinfo=CST)
    rows = [
        _row(
            "steps",
            "health.steps_30d",
            day_start,
            {"date": target_date.isoformat(), "steps": 7240},
            observed_at=day_start + timedelta(hours=19),
        ),
        _row(
            "standing",
            "health.standing_daily",
            day_start,
            {
                "date": target_date.isoformat(),
                "stand_hours": 10,
                "stand_events": 14,
                "longest_sedentary_minutes": 75,
            },
            observed_at=day_start + timedelta(hours=19),
        ),
        _row(
            "heart",
            "health.heart_rate_daily",
            day_start,
            {
                "date": target_date.isoformat(),
                "average_bpm": 72,
                "min_bpm": 52,
                "max_bpm": 148,
                "measurements": 72,
            },
            observed_at=day_start + timedelta(hours=19),
        ),
        _row(
            "resting-heart",
            "health.resting_heart_rate_daily",
            day_start,
            {
                "date": target_date.isoformat(),
                "average_bpm": 71,
                "min_bpm": 66,
                "max_bpm": 77,
                "measurements": 18,
            },
            observed_at=day_start + timedelta(hours=19),
        ),
        _row(
            "oxygen",
            "health.oxygen_saturation_daily",
            day_start,
            {
                "date": target_date.isoformat(),
                "average_percent": 96.5,
                "min_percent": 94,
                "measurements": 12,
            },
            observed_at=day_start + timedelta(hours=19),
        ),
        _row(
            "stress",
            "health.stress_daily",
            day_start,
            {
                "date": target_date.isoformat(),
                "average_score": 54,
                "min_score": 30,
                "max_score": 82,
            },
            observed_at=day_start + timedelta(hours=19),
        ),
        _row(
            "exercise",
            "health.exercise_session",
            day_start + timedelta(hours=18),
            {
                "starts_at": _epoch(day_start + timedelta(hours=18)),
                "ends_at": _epoch(day_start + timedelta(hours=18, minutes=25)),
                "duration_minutes": 25,
                "exercise_type_name": "walking",
            },
            observed_at=day_start + timedelta(hours=18, minutes=25),
        ),
        _row(
            "calories",
            "health.active_calories_daily",
            day_start,
            {"date": target_date.isoformat(), "active_calories_kcal": 280},
            observed_at=day_start + timedelta(hours=19),
        ),
        _row(
            "activity-minutes",
            "health.activity_minutes_daily",
            day_start,
            {"date": target_date.isoformat(), "activity_minutes": 29},
            observed_at=day_start + timedelta(hours=19),
        ),
        _row(
            "distance",
            "health.distance_daily",
            day_start,
            {"date": target_date.isoformat(), "distance_meters": 5450},
            observed_at=day_start + timedelta(hours=19),
        ),
        _row(
            "floors",
            "health.floors_daily",
            day_start,
            {"date": target_date.isoformat(), "floors": 4},
            observed_at=day_start + timedelta(hours=19),
        ),
    ]
    for days_ago in range(1, 15):
        baseline_date = target_date - timedelta(days=days_ago)
        baseline_start = datetime.combine(
            baseline_date,
            datetime.min.time(),
            tzinfo=CST,
        )
        rows.extend(
            [
                _row(
                    f"steps-{days_ago}",
                    "health.steps_30d",
                    baseline_start,
                    {"date": baseline_date.isoformat(), "steps": 6200 + days_ago * 200},
                ),
                _row(
                    f"standing-{days_ago}",
                    "health.standing_daily",
                    baseline_start,
                    {
                        "date": baseline_date.isoformat(),
                        "stand_hours": 9,
                        "longest_sedentary_minutes": 55,
                    },
                ),
                _row(
                    f"heart-{days_ago}",
                    "health.heart_rate_daily",
                    baseline_start,
                    {
                        "date": baseline_date.isoformat(),
                        "average_bpm": 70,
                        "min_bpm": 52,
                        "measurements": 60,
                    },
                ),
                _row(
                    f"stress-{days_ago}",
                    "health.stress_daily",
                    baseline_start,
                    {
                        "date": baseline_date.isoformat(),
                        "average_score": 40,
                    },
                ),
                _row(
                    f"calories-{days_ago}",
                    "health.active_calories_daily",
                    baseline_start,
                    {
                        "date": baseline_date.isoformat(),
                        "active_calories_kcal": 500 + days_ago * 4,
                    },
                ),
                _row(
                    f"activity-minutes-{days_ago}",
                    "health.activity_minutes_daily",
                    baseline_start,
                    {
                        "date": baseline_date.isoformat(),
                        "activity_minutes": 20 + days_ago % 6,
                    },
                ),
                _row(
                    f"resting-heart-{days_ago}",
                    "health.resting_heart_rate_daily",
                    baseline_start,
                    {
                        "date": baseline_date.isoformat(),
                        "average_bpm": 70,
                        "min_bpm": 65,
                        "max_bpm": 76,
                        "measurements": 16,
                    },
                ),
            ]
        )
    return rows


def _visual_payload(card: object) -> dict[str, object]:
    summary = str(getattr(card, "summary"))
    marker = "【健康可视化】\n"
    assert marker in summary
    return json.loads(summary.split(marker, 1)[1])


class MorningHealthCardTest(unittest.TestCase):
    def test_morning_card_combines_sleep_activity_and_visuals(self) -> None:
        target_date = date(2026, 10, 28)
        events = health_cards.parse_health_events(
            [
                *_sleep_rows(target_date),
                _row(
                    "early-steps",
                    "health.steps_30d",
                    datetime(2026, 10, 28, 0, 1, tzinfo=CST),
                    {"date": target_date.isoformat(), "steps": 800},
                    observed_at=datetime(2026, 10, 28, 7, 15, tzinfo=CST),
                ),
            ]
        )

        card = health_cards.build_morning_card(
            events,
            datetime(2026, 10, 28, 7, 30, tzinfo=CST),
        )

        self.assertIsNotNone(card)
        assert card is not None
        self.assertEqual("auto:health:morning:2026-10-28", card.dedupe_key)
        self.assertIn("昨晚睡眠：7 小时 30 分", card.title)
        self.assertIn("【睡眠概览】", card.summary)
        self.assertIn("今日活动：800 步", card.summary)
        self.assertIn("来自小米运动健康云端", card.summary)
        self.assertIn("【健康可视化】", card.summary)
        self.assertNotIn("health.steps_30d", card.summary)
        self.assertLessEqual(len(card.summary), 1200)
        visual = _visual_payload(card)
        self.assertEqual("morning", visual["w"])
        self.assertEqual("sleep", visual["m"][0]["k"])
        self.assertEqual("ratio", visual["m"][0]["c"])

    def test_morning_card_is_not_available_after_noon(self) -> None:
        self.assertIsNone(
            health_cards.build_morning_card(
                [],
                datetime(2026, 10, 28, 12, 0, tzinfo=CST),
            )
        )

    def test_morning_card_adds_sleep_regularity_and_resting_heart_context(self) -> None:
        target_date = date(2026, 10, 28)
        events = health_cards.parse_health_events(
            [
                *_sleep_history_rows(target_date),
                *_daily_metric_rows(target_date),
            ]
        )

        card = health_cards.build_morning_card(
            events,
            datetime(2026, 10, 28, 7, 30, tzinfo=CST),
        )

        self.assertIsNotNone(card)
        assert card is not None
        self.assertIn("近 7 晚平均", card.summary)
        self.assertIn("醒来时间", card.summary)
        self.assertIn("静息心率近 7 天平均", card.summary)
        visual = _visual_payload(card)
        keys = [item["k"] for item in visual["m"]]
        self.assertEqual("morning", visual["w"])
        self.assertEqual("sleep", keys[0])
        self.assertIn("deep_ratio", keys)
        self.assertIn("wake_time", keys)
        self.assertIn("resting_heart", keys)
        self.assertLessEqual(len(card.summary), 1200)


class EveningHealthCardTest(unittest.TestCase):
    def test_evening_card_combines_last_sleep_and_today_activity(self) -> None:
        target = datetime(2026, 10, 28, 19, 30, tzinfo=CST)
        events = health_cards.parse_health_events(
            [*_sleep_rows(target.date()), *_daily_metric_rows(target.date())]
        )

        card = health_cards.build_daily_health_card(events, target)

        self.assertIsNotNone(card)
        assert card is not None
        self.assertEqual("auto:health:daily:2026-10-28", card.dedupe_key)
        self.assertEqual("晚间健康 · 7,240 步", card.title)
        self.assertIn("【昨晚睡眠】", card.summary)
        self.assertIn("7 小时 30 分", card.summary)
        self.assertIn("步数：7,240 步", card.summary)
        self.assertIn("有效站立：10 个有效小时", card.summary)
        self.assertIn("心率：平均 72 bpm", card.summary)
        self.assertIn("血氧：平均 96%", card.summary)
        self.assertIn("静息心率：71 bpm", card.summary)
        self.assertIn("压力：54，高于 14 日基线 35%", card.summary)
        self.assertIn("【近 7 天】", card.summary)
        self.assertIn("近 7 天日均 6,949 步", card.summary)
        self.assertIn("近 7 天平均 70 bpm", card.summary)
        self.assertIn("中高强度活动：近 7 天日均", card.summary)
        self.assertNotIn("最长连续未站立", card.summary)
        self.assertIn("步行 25 分钟", card.summary)
        self.assertIn("数据来自小米运动健康云端", card.summary)
        self.assertIn("【健康可视化】", card.summary)
        self.assertNotIn("health.steps_30d", card.summary)
        self.assertLessEqual(len(card.summary), 1200)
        visual = _visual_payload(card)
        self.assertEqual("evening", visual["w"])
        self.assertEqual(
            ["steps", "calories", "activity_minutes", "resting_heart", "sleep"],
            [item["k"] for item in visual["m"]],
        )
        self.assertEqual(
            ["line", "columns", "columns", "columns", "ratio"],
            [item["c"] for item in visual["m"]],
        )

    def test_no_generic_summary_between_noon_and_evening(self) -> None:
        events = health_cards.parse_health_events(_daily_metric_rows())
        self.assertIsNone(
            health_cards.build_daily_health_card(
                events,
                datetime(2026, 10, 28, 15, 0, tzinfo=CST),
            )
        )

    def test_cloud_steps_win_over_later_phone_system_steps(self) -> None:
        target = datetime(2026, 10, 28, 19, 30, tzinfo=CST)
        rows = [
            _row(
                "cloud-steps",
                "health.steps_30d",
                datetime(2026, 10, 28, 0, 1, tzinfo=CST),
                {"date": "2026-10-28", "steps": 7240},
                observed_at=datetime(2026, 10, 28, 19, 0, tzinfo=CST),
            ),
            _row(
                "phone-steps",
                "health.steps_30d",
                datetime(2026, 10, 28, 0, 1, tzinfo=CST),
                {"date": "2026-10-28", "steps": 9999},
                observed_at=datetime(2026, 10, 28, 19, 20, tzinfo=CST),
                source_type="xiaomi_health",
                data_source="xiaomi_system_steps",
            ),
        ]
        events = health_cards.parse_health_events(rows)

        card = health_cards.build_daily_health_card(events, target)

        self.assertIsNotNone(card)
        assert card is not None
        self.assertIn("步数：7,240 步", card.summary)
        self.assertNotIn("9,999", card.summary)
        self.assertNotIn("系统步数", card.summary)

    def test_missing_activity_goal_falls_back_to_recorded_exercise(self) -> None:
        target = datetime(2026, 10, 28, 19, 30, tzinfo=CST)
        rows = [
            row
            for row in _daily_metric_rows(target.date())
            if row["event_type"] != "health.activity_minutes_daily"
        ]
        events = health_cards.parse_health_events(
            [*_sleep_rows(target.date()), *rows]
        )

        card = health_cards.build_daily_health_card(events, target)

        self.assertIsNotNone(card)
        assert card is not None
        self.assertIn("• 运动：近 7 天", card.summary)
        self.assertNotIn("中高强度活动：近 7 天日均", card.summary)
        keys = [item["k"] for item in _visual_payload(card)["m"]]
        self.assertIn("exercise_minutes", keys)

    def test_missing_activity_goal_day_is_not_filled_with_exercise(self) -> None:
        target_date = date(2026, 10, 28)
        rows = [
            row
            for row in _daily_metric_rows(target_date)
            if row["event_type"] != "health.activity_minutes_daily"
        ]
        rows.append(
            _row(
                "activity-yesterday",
                "health.activity_minutes_daily",
                datetime(2026, 10, 27, 0, 1, tzinfo=CST),
                {
                    "date": "2026-10-27",
                    "activity_minutes": 20,
                },
            )
        )
        events = health_cards.parse_health_events(rows)

        self.assertIsNone(
            health_cards._activity_minutes_for_date(events, target_date)
        )
        self.assertEqual(
            20,
            health_cards._activity_minutes_for_date(
                events,
                target_date - timedelta(days=1),
            ),
        )


class WeeklyHealthCardTest(unittest.TestCase):
    def test_weekly_card_compares_completed_seven_day_periods(self) -> None:
        completed_through = date(2026, 11, 1)
        events = health_cards.parse_health_events(
            [
                *_sleep_history_rows(completed_through),
                *_daily_metric_rows(completed_through),
            ]
        )

        card = health_cards.build_weekly_health_card(
            events,
            datetime(2026, 11, 2, 10, 30, tzinfo=CST),
        )

        self.assertIsNotNone(card)
        assert card is not None
        self.assertEqual("auto:health:weekly:2026-11-02", card.dedupe_key)
        self.assertTrue(card.title.startswith("健康周报"))
        self.assertIn("【趋势摘要】", card.summary)
        self.assertIn("• 睡眠：近 7 晚平均", card.summary)
        self.assertIn("• 活动：日均", card.summary)
        self.assertIn("• 中高强度活动：日均", card.summary)
        self.assertIn("• 恢复：静息心率平均", card.summary)
        self.assertIn("【下周建议】", card.summary)
        self.assertLessEqual(len(card.summary), 1200)
        visual = _visual_payload(card)
        self.assertEqual("weekly", visual["w"])
        keys = [item["k"] for item in visual["m"]]
        self.assertEqual("steps", keys[0])
        self.assertIn("sleep", keys)
        self.assertIn("calories", keys)
        self.assertIn("activity_minutes", keys)
        self.assertIn("resting_heart", keys)

    def test_weekly_card_only_appears_on_monday_after_morning(self) -> None:
        events = health_cards.parse_health_events(_daily_metric_rows())

        self.assertIsNone(
            health_cards.build_weekly_health_card(
                events,
                datetime(2026, 11, 2, 9, 30, tzinfo=CST),
            )
        )
        self.assertIsNone(
            health_cards.build_weekly_health_card(
                events,
                datetime(2026, 10, 28, 10, 30, tzinfo=CST),
            )
        )


class DaytimeAlertTest(unittest.TestCase):
    def test_normal_daytime_metrics_do_not_generate_cards(self) -> None:
        events = health_cards.parse_health_events(_daily_metric_rows())
        stress_event = next(
            event
            for event in events
            if event.event_type == "health.stress_daily"
            and event.payload.get("date") == "2026-10-28"
        )
        stress_event.payload["average_score"] = 44
        cards = health_cards.build_daytime_alert_cards(
            events,
            datetime(2026, 10, 28, 15, 0, tzinfo=CST),
        )
        self.assertEqual([], cards)

    def test_abnormal_metrics_generate_independent_alert_cards(self) -> None:
        target_date = date(2026, 10, 28)
        day_start = datetime(2026, 10, 28, 0, 1, tzinfo=CST)
        rows = [
            _row(
                "standing",
                "health.standing_daily",
                day_start,
                {
                    "date": target_date.isoformat(),
                    "stand_hours": 3,
                    "longest_sedentary_minutes": 125,
                },
                observed_at=datetime(2026, 10, 28, 15, 0, tzinfo=CST),
            ),
            _row(
                "stress",
                "health.stress_daily",
                day_start,
                {"date": target_date.isoformat(), "average_score": 82},
                observed_at=datetime(2026, 10, 28, 15, 0, tzinfo=CST),
            ),
            _row(
                "heart",
                "health.heart_rate_daily",
                day_start,
                {
                    "date": target_date.isoformat(),
                    "average_bpm": 106,
                    "min_bpm": 76,
                    "max_bpm": 132,
                    "measurements": 36,
                },
                observed_at=datetime(2026, 10, 28, 15, 0, tzinfo=CST),
            ),
            _row(
                "oxygen",
                "health.oxygen_saturation_daily",
                day_start,
                {
                    "date": target_date.isoformat(),
                    "average_percent": 92,
                    "min_percent": 88,
                    "measurements": 6,
                },
                observed_at=datetime(2026, 10, 28, 15, 0, tzinfo=CST),
            ),
        ]
        for days_ago in range(1, 5):
            baseline = target_date - timedelta(days=days_ago)
            baseline_start = datetime.combine(baseline, datetime.min.time(), tzinfo=CST)
            rows.extend(
                [
                    _row(
                        f"stress-{days_ago}",
                        "health.stress_daily",
                        baseline_start,
                        {"date": baseline.isoformat(), "average_score": 40},
                    ),
                    _row(
                        f"heart-{days_ago}",
                        "health.heart_rate_daily",
                        baseline_start,
                        {
                            "date": baseline.isoformat(),
                            "average_bpm": 70,
                            "min_bpm": 52,
                            "measurements": 60,
                        },
                    ),
                ]
            )
        events = health_cards.parse_health_events(rows)

        cards = health_cards.build_daytime_alert_cards(
            events,
            datetime(2026, 10, 28, 15, 0, tzinfo=CST),
        )

        self.assertEqual(
            {
                "auto:health:alert:oxygen:2026-10-28",
                "auto:health:alert:heart:2026-10-28",
                "auto:health:alert:stress:2026-10-28",
                "auto:health:alert:sedentary:2026-10-28",
            },
            {card.dedupe_key for card in cards},
        )
        for card in cards:
            self.assertIn("【需要留意】", card.summary)
            self.assertIn("【现在可以做】", card.summary)
            self.assertIn("【健康可视化】", card.summary)
            self.assertNotIn("health.", card.summary)
            self.assertLessEqual(len(card.summary), 1200)

    def test_high_workout_peak_does_not_alert_when_average_is_normal(self) -> None:
        target_date = date(2026, 10, 28)
        rows = [
            _row(
                "heart",
                "health.heart_rate_daily",
                datetime(2026, 10, 28, 0, 1, tzinfo=CST),
                {
                    "date": target_date.isoformat(),
                    "average_bpm": 74,
                    "min_bpm": 54,
                    "max_bpm": 178,
                    "measurements": 80,
                },
                observed_at=datetime(2026, 10, 28, 15, 0, tzinfo=CST),
            )
        ]
        events = health_cards.parse_health_events(rows)
        self.assertEqual(
            [],
            health_cards.build_daytime_alert_cards(
                events,
                datetime(2026, 10, 28, 15, 0, tzinfo=CST),
            ),
        )

    def test_inactive_alerts_are_dismissed_without_touching_active_alert(self) -> None:
        statements: list[tuple[str, tuple[object, ...]]] = []

        class Connection:
            def execute(self, sql: str, params: tuple[object, ...]) -> None:
                statements.append((sql, params))

        class Transaction:
            def __enter__(self) -> Connection:
                return Connection()

            def __exit__(self, *args: object) -> None:
                return None

        class Db:
            def transaction(self) -> Transaction:
                return Transaction()

        health_cards._dismiss_inactive_alerts(
            Db(),
            agent_id="agent-1",
            user_id=7,
            target_date=date(2026, 10, 28),
            active_keys={"agent:auto:health:alert:stress:2026-10-28"},
        )

        self.assertEqual(1, len(statements))
        sql, params = statements[0]
        stale = set(params[3:])
        self.assertEqual(
            {
                "agent:auto:health:alert:sedentary:2026-10-28",
                "agent:auto:health:alert:heart:2026-10-28",
                "agent:auto:health:alert:oxygen:2026-10-28",
            },
            stale,
        )
        self.assertIn("lifecycle = 'dismissed'", sql)


class PublishHealthCardTest(unittest.TestCase):
    def test_publish_uses_evening_dedupe_key_and_human_evidence(self) -> None:
        rows = _daily_metric_rows()
        published_drafts = []

        class Draft:
            def __init__(self, **values: object) -> None:
                self.__dict__.update(values)

        class Publisher:
            def __init__(self, db: object, *, agent_repo: object) -> None:
                self.db = db
                self.agent_repo = agent_repo

            def publish(self, draft: object, *, agent_id: str, user_id: int) -> dict[str, object]:
                published_drafts.append((draft, agent_id, user_id))
                return {"card_id": "card-health-1"}

        originals = (
            health_cards._fetch_health_rows,
            health_cards.TodayCardDraft,
            health_cards.TodayCardPublisher,
            health_cards.AgentRepo,
            health_cards._dismiss_inactive_alerts,
        )
        health_cards._fetch_health_rows = lambda *args, **kwargs: rows
        health_cards.TodayCardDraft = Draft
        health_cards.TodayCardPublisher = Publisher
        health_cards.AgentRepo = lambda db: object()
        health_cards._dismiss_inactive_alerts = lambda *args, **kwargs: None
        try:
            ids = health_cards.publish_health_today_cards(
                object(),
                agent_id="agent-1",
                user_id=7,
                now=datetime(2026, 10, 28, 19, 30, tzinfo=CST),
            )
        finally:
            (
                health_cards._fetch_health_rows,
                health_cards.TodayCardDraft,
                health_cards.TodayCardPublisher,
                health_cards.AgentRepo,
                health_cards._dismiss_inactive_alerts,
            ) = originals

        self.assertEqual(["card-health-1"], ids)
        self.assertEqual(1, len(published_drafts))
        draft, agent_id, user_id = published_drafts[0]
        self.assertEqual("agent-1", agent_id)
        self.assertEqual(7, user_id)
        self.assertEqual("health", draft.kind)
        self.assertEqual("auto:health:daily:2026-10-28", draft.dedupe_key)
        self.assertTrue(all("health." not in item.title for item in draft.evidence))


if __name__ == "__main__":
    unittest.main()
