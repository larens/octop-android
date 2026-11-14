"""Focused tests for the normalized WeRead reading facts."""

from __future__ import annotations

import json
import unittest
from datetime import datetime

from weread_reading import (
    LOCAL_TZ,
    build_weekly_digest,
    collect_week_notes,
    merge_week_books,
    normalize_notebook_rows,
    normalize_reading_stats,
    normalize_shelf,
    normalize_weekly_stats,
    week_bounds,
)


def epoch(month: int, day: int, hour: int = 12, year: int = 2026) -> int:
    return int(datetime(year, month, day, hour, tzinfo=LOCAL_TZ).timestamp())


NOW = epoch(11, 14, 22)


def weekly_payload() -> dict:
    return {
        "totalReadTime": 19617,
        "readDays": 6,
        "compare": -0.3847549665313583,
        "rank": {"text": "朋友中排第2名"},
        "readTimes": {
            str(epoch(11, 9, 0)): 897,
            str(epoch(11, 10, 0)): 867,
            str(epoch(11, 11, 0)): 10616,
            str(epoch(11, 12, 0)): 2205,
            str(epoch(11, 13, 0)): 1421,
            str(epoch(11, 14, 0)): 3611,
            str(epoch(10, 1, 0)): 999,
        },
        "readLongest": [
            {"albumInfo": {}, "readTime": 12501, "tags": []},
            {
                "book": {
                    "bookId": "b1",
                    "title": "AI原生组织",
                    "author": "陈春花 李智勇",
                },
                "readTime": 7129,
                "tags": [],
            },
        ],
    }


def shelf_payload() -> dict:
    return {
        "books": [
            {
                "bookId": "b1",
                "title": "AI原生组织",
                "author": "陈春花 李智勇",
                "category": "经济理财-管理",
                "cover": "https://cdn.weread.qq.com/cover/b1.jpg",
                "finishReading": 0,
                "readUpdateTime": epoch(11, 14, 21),
            },
            {
                "bookId": "b2",
                "title": "一个故事的99种讲法",
                "author": "马特·马登",
                "category": "漫画-经典改编",
                "cover": "https://cdn.weread.qq.com/cover/b2.jpg",
                "finishReading": 0,
                "readUpdateTime": epoch(11, 12, 9),
            },
            {
                "bookId": "b3",
                "title": "旧书",
                "author": "某人",
                "category": "文学",
                "finishReading": 1,
                "readUpdateTime": epoch(9, 1, 9),
            },
        ],
        "albums": [{"albumInfo": {"albumId": "a1", "name": "专辑", "finish": 0}}],
    }


def notebook_rows() -> list[dict]:
    return [
        {
            "bookId": "b1",
            "readingProgress": 27,
            "noteCount": 1,
            "reviewCount": 0,
            "sort": epoch(11, 14, 12),
            "markedStatus": 2,
            "book": {
                "bookId": "b1",
                "title": "AI原生组织",
                "author": "陈春花 李智勇",
                "categories": [{"title": "经济理财-管理"}],
                "cover": "https://cdn.weread.qq.com/cover/b1-notebook.jpg",
            },
        },
        {
            "bookId": "b3",
            "readingProgress": 100,
            "noteCount": 2,
            "reviewCount": 1,
            "sort": epoch(9, 1, 9),
            "markedStatus": 4,
            "book": {"bookId": "b3", "title": "旧书"},
        },
    ]


class WeeklyStatsTest(unittest.TestCase):
    def test_week_bounds_are_monday_to_next_monday(self) -> None:
        start, end = week_bounds(NOW)
        self.assertEqual(datetime.fromtimestamp(start, LOCAL_TZ).strftime("%Y-%m-%d %H:%M"), "2026-11-09 00:00")
        self.assertEqual(end - start, 7 * 86400)

    def test_weekly_stats_keep_daily_buckets_and_unnamed_audio(self) -> None:
        stats = normalize_weekly_stats(weekly_payload(), NOW)
        self.assertEqual(stats["total_seconds"], 19617)
        self.assertEqual(stats["total_hours"], 5.4)
        self.assertEqual(stats["read_days"], 6)
        self.assertEqual(len(stats["daily"]), 6)
        self.assertEqual(stats["daily"][0], {"date": "2026-11-09", "seconds": 897})
        self.assertEqual(stats["compare_pct"], -38.5)
        self.assertEqual(stats["rank"], "朋友中排第2名")
        self.assertEqual(stats["listening_seconds"], 12501)
        self.assertIsNone(stats["longest_items"][0]["title"])
        self.assertEqual(stats["longest_items"][1]["book_id"], "b1")


class MergeTest(unittest.TestCase):
    def test_shelf_and_notebooks_merge_progress_without_inventing_it(self) -> None:
        shelf = normalize_shelf(shelf_payload())
        self.assertEqual(shelf["total"], 3)
        self.assertEqual(shelf["finished"], 1)
        self.assertIsNone(shelf["books"][0]["progress"])
        self.assertEqual(shelf["books"][2]["progress"], 100)

        notebooks = normalize_notebook_rows(notebook_rows())
        weekly = normalize_weekly_stats(weekly_payload(), NOW)
        start, end = week_bounds(NOW)
        books = merge_week_books(shelf, notebooks, weekly, start, end)
        by_id = {book["book_id"]: book for book in books}
        self.assertEqual(set(by_id), {"b1", "b2"})
        self.assertEqual(by_id["b1"]["progress_pct"], 27)
        self.assertEqual(by_id["b1"]["read_seconds"], 7129)
        self.assertEqual(
            "https://cdn.weread.qq.com/cover/b1.jpg",
            by_id["b1"]["cover"],
        )
        self.assertIsNone(by_id["b2"]["progress_pct"])
        self.assertEqual(by_id["b2"]["category"], "漫画-经典改编")
        self.assertEqual(
            "https://cdn.weread.qq.com/cover/b2.jpg",
            by_id["b2"]["cover"],
        )

    def test_collect_week_notes_filters_by_create_time_and_tracks_newest(self) -> None:
        def call(api: str, **params):
            if api == "/book/bookmarklist":
                return {
                    "chapters": [{"chapterUid": 9, "title": "第九章"}],
                    "updated": [
                        {
                            "bookmarkId": "h1",
                            "chapterUid": 9,
                            "type": 1,
                            "markText": "本周划线",
                            "createTime": epoch(11, 14, 12),
                        },
                        {
                            "bookmarkId": "h2",
                            "chapterUid": 9,
                            "type": 1,
                            "markText": "旧划线",
                            "createTime": epoch(8, 7, 23),
                        },
                        {
                            "bookmarkId": "b1",
                            "chapterUid": 9,
                            "type": 0,
                            "markText": "书签",
                            "createTime": epoch(11, 13, 9),
                        },
                    ],
                }
            if api == "/review/list/mine":
                return {
                    "reviews": [
                        {
                            "review": {
                                "reviewId": "r1",
                                "createTime": epoch(11, 13, 9),
                                "chapterName": "第九章",
                                "content": "本周想法",
                            }
                        }
                    ],
                    "hasMore": 0,
                }
            raise AssertionError(api)

        start, end = week_bounds(NOW)
        notebooks = normalize_notebook_rows(notebook_rows())
        notes = collect_week_notes(call, notebooks, start, end)
        self.assertEqual(notes["new_highlights"], 1)
        self.assertEqual(notes["new_thoughts"], 1)
        self.assertEqual(notes["scanned_books"], 1)
        self.assertEqual(notes["failures"], 0)
        self.assertEqual(notes["newest_highlight"]["text"], "本周划线")
        self.assertEqual(notes["newest_highlight"]["chapter"], "第九章")


class DigestTest(unittest.TestCase):
    def test_weekly_digest_is_compact_and_reports_coverage(self) -> None:
        calls: list[str] = []

        def call(api: str, **params):
            calls.append(api)
            if api == "/readdata/detail":
                return weekly_payload()
            if api == "/shelf/sync":
                return shelf_payload()
            if api == "/user/notebooks":
                return {"books": notebook_rows(), "hasMore": 0}
            if api == "/book/bookmarklist":
                return {
                    "chapters": [{"chapterUid": 9, "title": "第九章"}],
                    "updated": [
                        {
                            "bookmarkId": "h1",
                            "chapterUid": 9,
                            "type": 1,
                            "markText": "本周划线",
                            "createTime": epoch(11, 14, 12),
                        }
                    ],
                }
            if api == "/review/list/mine":
                return {"reviews": [], "hasMore": 0}
            if api == "/book/getprogress":
                return {"book": {"progress": 40}}
            raise AssertionError(api)

        digest = build_weekly_digest(call, api_key="wrk-test-digest", now=NOW)
        self.assertTrue(digest["ok"])
        self.assertEqual(digest["period"]["start"], "2026-11-09")
        self.assertEqual(digest["period"]["end"], "2026-11-14")
        self.assertEqual(digest["reading"]["total_hours"], 5.4)
        self.assertEqual(digest["reading"]["listening_hours"], 3.5)
        self.assertEqual(digest["shelf"]["total"], 3)
        self.assertEqual(digest["shelf"]["finished_pct"], 33.3)
        by_id = {book["book_id"]: book for book in digest["books"]}
        self.assertEqual(by_id["b1"]["new_highlights"], 1)
        self.assertEqual(by_id["b2"]["progress_pct"], 40)
        self.assertEqual(
            "https://cdn.weread.qq.com/cover/b1.jpg",
            by_id["b1"]["cover"],
        )
        self.assertEqual(digest["notes"]["new_highlights"], 1)
        self.assertEqual(digest["coverage"]["notes_books_scanned"], 1)
        self.assertTrue(any("readLongest" in item for item in digest["limitations"]))
        self.assertTrue(any("听书" in item for item in digest["limitations"]))
        self.assertLess(len(json.dumps(digest, ensure_ascii=False)), 15000)

    def test_weekly_digest_is_cached_per_account_and_week(self) -> None:
        calls: list[str] = []

        def call(api: str, **params):
            calls.append(api)
            if api == "/readdata/detail":
                return weekly_payload()
            if api == "/shelf/sync":
                return shelf_payload()
            if api == "/user/notebooks":
                return {"books": [], "hasMore": 0}
            raise AssertionError(api)

        first = build_weekly_digest(call, api_key="wrk-cache-test", now=NOW)
        count = len(calls)
        second = build_weekly_digest(call, api_key="wrk-cache-test", now=NOW)
        self.assertEqual(len(calls), count)
        self.assertEqual(first["period"], second["period"])


class ReadingStatsTest(unittest.TestCase):
    def test_overall_stats_normalize_years_categories_and_authors(self) -> None:
        raw = {
            "totalReadTime": 14659694,
            "readDays": 2967,
            "readStat": [
                {"stat": "读过", "counts": "395本"},
                {"stat": "读完", "counts": "40本"},
            ],
            "readTimes": {str(epoch(1, 1, 0, 2025)): 1000, str(epoch(1, 1, 0, 2026)): 2000},
            "preferCategory": [
                {"categoryTitle": "经济理财", "parentCategoryTitle": "经济", "readingTime": 600, "readingCount": 4}
            ],
            "preferAuthor": [{"name": "某人", "count": 3}],
            "preferTime": [1, 2, 3],
            "preferCategoryWord": "偏好阅读经济理财",
            "preferTimeWord": "偏好上午与夜晚阅读",
            "readLongest": [{"book": {"bookId": "b1", "title": "书"}, "readTime": 60}],
        }
        stats = normalize_reading_stats(raw, "overall")
        self.assertEqual(stats["total_hours"], 4072.1)
        self.assertEqual(stats["read_stat"][0], {"label": "读过", "value": "395本"})
        self.assertEqual(stats["years"][0], {"year": "2025", "seconds": 1000})
        self.assertEqual(stats["prefer_categories"][0]["minutes"], 10.0)
        self.assertEqual(stats["prefer_authors"][0]["books"], 3)
        self.assertEqual(stats["prefer_time"], [1, 2, 3])
        self.assertEqual(stats["prefer_time_word"], "偏好上午与夜晚阅读")

    def test_weekly_stats_state_missing_category_breakdown(self) -> None:
        stats = normalize_reading_stats(weekly_payload(), "weekly")
        self.assertEqual(stats["mode"], "weekly")
        self.assertTrue(any("分类" in item for item in stats["limitations"]))
        with self.assertRaises(ValueError):
            normalize_reading_stats({}, "daily")


if __name__ == "__main__":
    unittest.main()
