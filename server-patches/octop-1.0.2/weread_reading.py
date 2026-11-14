"""Normalize WeRead Agent Gateway payloads into compact reading facts.

The gateway returns large, vendor-shaped payloads: notebooks carry both a nested
``book`` object and flat counters, shelf rows use a different progress shape, and
read statistics change keys per mode. Feeding those payloads straight into an
agent loses the weekly facts and wastes context.

This module keeps the normalization in one place, mirroring the data organization
of the dsh-weread plugin: explicit week bounds, stable IDs, local-time ISO dates,
counts that say what they cover, and an explicit ``limitations`` list.
"""

from __future__ import annotations

import hashlib
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timedelta, timezone
from typing import Any, Callable

LOCAL_TZ = timezone(timedelta(hours=8), "Asia/Shanghai")
CACHE_TTL_SECONDS = 10 * 60
MAX_NOTE_BOOKS = 8
MAX_NOTE_ITEMS = 12
MAX_BOOKS = 15
MAX_TEXT = 240

_CACHE: dict[str, tuple[float, dict[str, Any]]] = {}
_CACHE_LOCK = threading.Lock()

GatewayCall = Callable[..., dict[str, Any]]


def _obj(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _rows(value: Any) -> list[dict[str, Any]]:
    if not isinstance(value, list):
        return []
    return [row for row in value if isinstance(row, dict)]


def _num(value: Any) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 0.0
    if number != number or number in (float("inf"), float("-inf")):
        return 0.0
    return number


def _text(value: Any) -> str:
    if isinstance(value, str):
        return value.strip()
    if value is None:
        return ""
    return str(value).strip()


def _timestamp(value: Any) -> int:
    number = _num(value)
    if number <= 0:
        return 0
    if number > 1e12:
        number /= 1000
    return int(number)


def _iso(timestamp: int) -> str | None:
    if timestamp <= 0:
        return None
    return datetime.fromtimestamp(timestamp, LOCAL_TZ).isoformat(timespec="seconds")


def _date(timestamp: int) -> str:
    return datetime.fromtimestamp(timestamp, LOCAL_TZ).strftime("%Y-%m-%d")


def _week_start(now: int) -> int:
    local = datetime.fromtimestamp(now, LOCAL_TZ)
    monday = (local - timedelta(days=local.weekday())).replace(
        hour=0, minute=0, second=0, microsecond=0
    )
    return int(monday.timestamp())


def week_bounds(now: int | None = None) -> tuple[int, int]:
    """Return Monday 00:00 and next Monday 00:00 in Asia/Shanghai."""

    current = int(now if now is not None else time.time())
    start = _week_start(current)
    return start, start + 7 * 86400


def _clamp_progress(value: Any) -> int | None:
    if value is None or value == "":
        return None
    number = _num(value)
    return max(0, min(100, int(round(number))))


def _book_category(book: dict[str, Any]) -> str | None:
    categories = _rows(book.get("categories"))
    if categories:
        title = _text(categories[0].get("title"))
        if title:
            return title
    scalar = _text(book.get("category"))
    return scalar or None


def _strip_html(value: Any) -> str:
    text = _text(value)
    if "<" not in text:
        return text
    for needle, replacement in (
        ("<br>", "\n"),
        ("<br/>", "\n"),
        ("<br />", "\n"),
        ("</p>", "\n"),
        ("</div>", "\n"),
    ):
        text = text.replace(needle, replacement)
    while "<" in text and ">" in text:
        start = text.find("<")
        end = text.find(">", start)
        if end < 0:
            break
        text = text[:start] + text[end + 1 :]
    for entity, char in (
        ("&nbsp;", " "),
        ("&amp;", "&"),
        ("&lt;", "<"),
        ("&gt;", ">"),
        ("&quot;", '"'),
        ("&#39;", "'"),
    ):
        text = text.replace(entity, char)
    return text.strip()


def _truncate(value: str, limit: int = MAX_TEXT) -> str:
    return value if len(value) <= limit else value[: limit - 1].rstrip() + "…"


def normalize_weekly_stats(raw: dict[str, Any], now: int | None = None) -> dict[str, Any]:
    """Turn ``/readdata/detail?mode=weekly`` into week-scoped facts."""

    current = int(now if now is not None else time.time())
    start, end = week_bounds(current)
    daily: list[dict[str, Any]] = []
    for key, value in _obj(raw.get("readTimes")).items():
        moment = _timestamp(key)
        seconds = int(_num(value))
        if start <= moment < end and seconds > 0:
            daily.append({"date": _date(moment), "seconds": seconds})
    daily.sort(key=lambda item: item["date"])

    longest: list[dict[str, Any]] = []
    for item in _rows(raw.get("readLongest")):
        seconds = int(_num(item.get("readTime")))
        if seconds <= 0:
            continue
        book = _obj(item.get("book"))
        album = _obj(item.get("albumInfo"))
        has_album = "albumInfo" in item
        longest.append(
            {
                "kind": "book" if _text(book.get("bookId")) else "audio" if has_album else "unknown",
                "book_id": _text(book.get("bookId")) or None,
                "title": _text(book.get("title")) or _text(album.get("name")) or None,
                "author": _text(book.get("author")) or _text(album.get("authorName")) or None,
                "seconds": seconds,
            }
        )

    total = int(_num(raw.get("totalReadTime"))) or sum(item["seconds"] for item in daily)
    total_minutes = round(total / 60)
    read_days = int(_num(raw.get("readDays"))) or sum(1 for item in daily if item["seconds"] > 0)
    compare = raw.get("compare")
    compare_pct = (
        round(float(compare) * 100, 1)
        if isinstance(compare, (int, float)) and compare == compare
        else None
    )
    rank = _text(_obj(raw.get("rank")).get("text")) or None
    return {
        "total_seconds": total,
        "total_hours": round(total / 3600, 1),
        "total_minutes": total_minutes,
        "duration_label": (
            f"{total_minutes // 60} 小时 {total_minutes % 60} 分钟"
            if total_minutes >= 60
            else f"{total_minutes} 分钟"
        ),
        "read_days": read_days,
        "daily": daily,
        "compare_pct": compare_pct,
        "rank": rank,
        "longest_items": longest,
        "listening_seconds": sum(item["seconds"] for item in longest if item["kind"] == "audio"),
        "longest_books_seconds": sum(item["seconds"] for item in longest if item["kind"] == "book"),
    }


def normalize_shelf(raw: dict[str, Any]) -> dict[str, Any]:
    """Normalize ``/shelf/sync`` books and albums without inventing progress."""

    books: list[dict[str, Any]] = []
    for row in _rows(raw.get("books")):
        book_id = _text(row.get("bookId"))
        if book_id == "":
            continue
        finished = _num(row.get("finishReading")) == 1
        books.append(
            {
                "book_id": book_id,
                "title": _text(row.get("title")),
                "author": _text(row.get("author")),
                "category": _text(row.get("category")) or None,
                "cover": _text(row.get("cover")) or None,
                "last_read_at": _timestamp(row.get("readUpdateTime")),
                "finished": finished,
                "progress": 100 if finished else None,
            }
        )
    albums: list[dict[str, Any]] = []
    for row in _rows(raw.get("albums")):
        info = _obj(row.get("albumInfo"))
        album_id = _text(info.get("albumId"))
        if album_id == "":
            continue
        albums.append(
            {
                "album_id": album_id,
                "title": _text(info.get("name")),
                "author": _text(info.get("authorName")),
                "finished": _num(info.get("finish")) == 1,
                "last_listened_at": _timestamp(_obj(row.get("albumInfoExtra")).get("lectureReadUpdateTime")),
            }
        )
    return {
        "books": books,
        "albums": albums,
        "total": len(books),
        "finished": sum(1 for book in books if book["finished"]),
    }


def normalize_notebook_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Merge notebook pages, keeping one row per book and notebook-only counters."""

    books: dict[str, dict[str, Any]] = {}
    for row in rows:
        book = _obj(row.get("book"))
        book_id = _text(row.get("bookId")) or _text(book.get("bookId"))
        if book_id == "":
            continue
        books[book_id] = {
            "book_id": book_id,
            "title": _text(book.get("title")) or _text(row.get("title")),
            "author": _text(book.get("author")) or _text(row.get("author")),
            "category": _book_category(book),
            "cover": _text(book.get("cover")) or None,
            "progress": _clamp_progress(row.get("readingProgress")),
            "note_count": int(_num(row.get("noteCount"))),
            "review_count": int(_num(row.get("reviewCount"))),
            "last_note_at": _timestamp(row.get("sort")),
            "marked_status": int(_num(row.get("markedStatus"))),
        }
    return list(books.values())


def fetch_notebooks(
    call: GatewayCall, max_pages: int = 5
) -> tuple[list[dict[str, Any]], bool, dict[str, int]]:
    """Walk every notebook page; return rows, completion and upstream counters."""

    rows: list[dict[str, Any]] = []
    meta = {"total_books": 0, "total_notes": 0, "books_without_reviews": 0}
    last_sort: int | None = None
    for page_index in range(max_pages):
        params: dict[str, Any] = {"count": 300}
        if last_sort is not None:
            params["lastSort"] = last_sort
        raw = call("/user/notebooks", **params)
        if page_index == 0:
            meta = {
                "total_books": int(_num(raw.get("totalBookCount"))),
                "total_notes": int(_num(raw.get("totalNoteCount"))),
                "books_without_reviews": int(_num(raw.get("noBookReviewCount"))),
            }
        page = _rows(raw.get("books"))
        rows.extend(page)
        has_more = raw.get("hasMore") in (1, True, "1")
        if not has_more:
            return rows, True, meta
        if not page:
            return rows, False, meta
        cursor = page[-1].get("sort")
        if not isinstance(cursor, int) or cursor <= 0 or cursor == last_sort:
            return rows, False, meta
        last_sort = cursor
    return rows, False, meta


def _fetch_mine_reviews(call: GatewayCall, book_id: str, max_pages: int = 2) -> list[dict[str, Any]]:
    reviews: list[dict[str, Any]] = []
    synckey = 0
    seen: set[int] = set()
    for _ in range(max_pages):
        raw = call("/review/list/mine", bookid=book_id, synckey=synckey, count=100)
        for wrapper in _rows(raw.get("reviews")):
            review = _obj(wrapper.get("review") or wrapper)
            content = _strip_html(review.get("htmlContent") or review.get("content"))
            if content == "":
                continue
            reviews.append(
                {
                    "review_id": _text(review.get("reviewId")) or _text(wrapper.get("reviewId")),
                    "text": content,
                    "chapter": _text(review.get("chapterTitle")) or _text(review.get("chapterName")),
                    "created_at": _timestamp(review.get("createTime")),
                }
            )
        if raw.get("hasMore") not in (1, True, "1"):
            break
        cursor = raw.get("synckey")
        if not isinstance(cursor, int) or cursor <= 0 or cursor in seen:
            break
        seen.add(cursor)
        synckey = cursor
    return reviews


def _book_notes(
    call: GatewayCall, book: dict[str, Any], start: int, end: int
) -> dict[str, Any]:
    book_id = book["book_id"]
    raw = call("/book/bookmarklist", bookId=book_id)
    chapters = {
        _text(chapter.get("chapterUid")): _text(chapter.get("title"))
        for chapter in _rows(raw.get("chapters"))
    }
    highlights: list[dict[str, Any]] = []
    newest: dict[str, Any] | None = None
    for row in _rows(raw.get("updated")):
        if row.get("type") in (0, "0"):
            continue
        text = _text(row.get("markText"))
        created = _timestamp(row.get("createTime"))
        if text == "" or created <= 0:
            continue
        item = {
            "kind": "highlight",
            "book_id": book_id,
            "book_title": book["title"],
            "chapter": chapters.get(_text(row.get("chapterUid")))
            or _text(row.get("chapterName"))
            or None,
            "text": _truncate(text),
            "created_at": _iso(created),
            "create_time": created,
        }
        if newest is None or created > newest["create_time"]:
            newest = item
        if start <= created < end:
            highlights.append(item)

    thoughts: list[dict[str, Any]] = []
    newest_thought: dict[str, Any] | None = None
    for review in _fetch_mine_reviews(call, book_id):
        created = review["created_at"]
        if created <= 0:
            continue
        item = {
            "kind": "thought",
            "book_id": book_id,
            "book_title": book["title"],
            "chapter": review["chapter"] or None,
            "text": _truncate(review["text"]),
            "created_at": _iso(created),
            "create_time": created,
        }
        if newest_thought is None or created > newest_thought["create_time"]:
            newest_thought = item
        if start <= created < end:
            thoughts.append(item)
    return {"highlights": highlights, "thoughts": thoughts, "newest": newest, "newest_thought": newest_thought}


def collect_week_notes(
    call: GatewayCall,
    books: list[dict[str, Any]],
    start: int,
    end: int,
) -> dict[str, Any]:
    """Scan only notebooks updated this week, then filter notes by create time."""

    candidates = sorted(
        (book for book in books if book["last_note_at"] >= start),
        key=lambda book: book["last_note_at"],
        reverse=True,
    )[:MAX_NOTE_BOOKS]
    items: list[dict[str, Any]] = []
    by_book: dict[str, dict[str, int]] = {}
    newest: dict[str, Any] | None = None
    newest_thought: dict[str, Any] | None = None
    failures = 0

    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = {pool.submit(_book_notes, call, book, start, end): book for book in candidates}
        for future in as_completed(futures):
            book = futures[future]
            try:
                result = future.result()
            except Exception:
                failures += 1
                continue
            by_book[book["book_id"]] = {
                "highlights": len(result["highlights"]),
                "thoughts": len(result["thoughts"]),
            }
            items.extend(result["highlights"])
            items.extend(result["thoughts"])
            if result["newest"] is not None and (
                newest is None or result["newest"]["create_time"] > newest["create_time"]
            ):
                newest = result["newest"]
            if result["newest_thought"] is not None and (
                newest_thought is None
                or result["newest_thought"]["create_time"] > newest_thought["create_time"]
            ):
                newest_thought = result["newest_thought"]

    items.sort(key=lambda item: item["create_time"], reverse=True)
    for item in items:
        item.pop("create_time", None)
    if newest is not None:
        newest.pop("create_time", None)
    if newest_thought is not None:
        newest_thought.pop("create_time", None)
    return {
        "items": items[:MAX_NOTE_ITEMS],
        "new_highlights": sum(count["highlights"] for count in by_book.values()),
        "new_thoughts": sum(count["thoughts"] for count in by_book.values()),
        "by_book": by_book,
        "scanned_books": len(candidates),
        "failures": failures,
        "newest_highlight": newest,
        "newest_thought": newest_thought,
    }


def merge_week_books(
    shelf: dict[str, Any],
    notebooks: list[dict[str, Any]],
    weekly: dict[str, Any],
    start: int,
    end: int,
) -> list[dict[str, Any]]:
    """Merge shelf, notebook and longest-reading rows by stable book ID."""

    merged: dict[str, dict[str, Any]] = {}

    def entry(book_id: str) -> dict[str, Any]:
        return merged.setdefault(
            book_id,
            {
                "book_id": book_id,
                "title": "",
                "author": "",
                "category": None,
                "cover": None,
                "last_read_at": 0,
                "last_note_at": 0,
                "progress_pct": None,
                "finished": False,
                "note_count": 0,
                "review_count": 0,
                "read_seconds": 0,
            },
        )

    for book in shelf["books"]:
        row = entry(book["book_id"])
        row.update(
            {
                "title": book["title"] or row["title"],
                "author": book["author"] or row["author"],
                "category": book["category"] or row["category"],
                "cover": book["cover"] or row["cover"],
                "last_read_at": book["last_read_at"],
                "progress_pct": book["progress"],
                "finished": book["finished"],
            }
        )

    for book in notebooks:
        row = entry(book["book_id"])
        row.update(
            {
                "title": book["title"] or row["title"],
                "author": book["author"] or row["author"],
                "category": book["category"] or row["category"],
                "cover": row["cover"] or book["cover"],
                "last_note_at": book["last_note_at"],
                "progress_pct": book["progress"] if book["progress"] is not None else row["progress_pct"],
                "finished": row["finished"] or book["progress"] == 100,
                "note_count": book["note_count"],
                "review_count": book["review_count"],
            }
        )

    for item in weekly["longest_items"]:
        book_id = item["book_id"]
        if not book_id:
            continue
        row = entry(book_id)
        row.update(
            {
                "title": item["title"] or row["title"],
                "author": item["author"] or row["author"],
                "read_seconds": row["read_seconds"] + item["seconds"],
            }
        )

    selected = [
        row
        for row in merged.values()
        if row["last_read_at"] >= start
        or row["last_note_at"] >= start
        or row["read_seconds"] > 0
    ]
    selected.sort(
        key=lambda row: (row["read_seconds"], row["last_note_at"], row["last_read_at"]),
        reverse=True,
    )
    return selected[:MAX_BOOKS]


def _cache_get(key: str) -> dict[str, Any] | None:
    with _CACHE_LOCK:
        entry = _CACHE.get(key)
        if entry is None:
            return None
        expires, value = entry
        if expires <= time.time():
            _CACHE.pop(key, None)
            return None
        return value


def _cache_put(key: str, value: dict[str, Any]) -> None:
    with _CACHE_LOCK:
        if len(_CACHE) >= 8:
            _CACHE.pop(next(iter(_CACHE)))
        _CACHE[key] = (time.time() + CACHE_TTL_SECONDS, value)


def _cache_key(api_key: str, start: int) -> str:
    digest = hashlib.sha256(api_key.encode("utf-8")).hexdigest()[:16]
    return f"{digest}:{start}"


def build_weekly_digest(
    call: GatewayCall,
    *,
    api_key: str,
    now: int | None = None,
) -> dict[str, Any]:
    """Fetch and normalize one week of reading facts for the weekly card."""

    current = int(now if now is not None else time.time())
    start, end = week_bounds(current)
    key = _cache_key(api_key, start)
    cached = _cache_get(key)
    if cached is not None:
        return cached

    raw_stats = call("/readdata/detail", mode="weekly", baseTime=current)
    weekly = normalize_weekly_stats(raw_stats, current)
    shelf = normalize_shelf(call("/shelf/sync"))

    notebooks_complete = True
    notebook_meta = {"total_books": 0, "total_notes": 0, "books_without_reviews": 0}
    notebooks: list[dict[str, Any]] = []
    try:
        rows, notebooks_complete, notebook_meta = fetch_notebooks(call)
        notebooks = normalize_notebook_rows(rows)
    except Exception:
        notebooks_complete = False

    books = merge_week_books(shelf, notebooks, weekly, start, end)
    notes = collect_week_notes(call, notebooks, start, end)
    notes_by_book = notes.pop("by_book")
    for book in books:
        count = notes_by_book.get(book["book_id"], {})
        book["new_highlights"] = count.get("highlights", 0)
        book["new_thoughts"] = count.get("thoughts", 0)

    for book in books[:5]:
        if book["progress_pct"] is not None or book["finished"]:
            continue
        try:
            progress = _obj(call("/book/getprogress", bookId=book["book_id"]).get("book"))
            book["progress_pct"] = _clamp_progress(progress.get("progress"))
            book["finished"] = _num(progress.get("finishTime")) > 0 or book["progress_pct"] == 100
        except Exception:
            continue

    limitations = [
        "微信读书周统计只返回总时长和每日分桶；readLongest 仅覆盖时长最长的少量条目，不能据此计算每本书的完整时长。",
        "本周在读按书架 readUpdateTime 与笔记更新时间合并，表示有翻书或笔记动作，不等于读完。",
    ]
    if weekly["listening_seconds"] > 0:
        unnamed = sum(
            1 for item in weekly["longest_items"] if item["kind"] == "audio" and not item["title"]
        )
        limitations.append(
            "听书时长计入总时长"
            + (f"；其中 {unnamed} 条长条目未返回专辑名，无法拆分到具体专辑。" if unnamed else "。")
        )
    if notes["scanned_books"] > 0:
        limitations.append(
            f"新增划线/想法只扫描本周有笔记更新的 {notes['scanned_books']} 本书；未更新书籍不会重复扫描。"
        )
    if notes["failures"] > 0:
        limitations.append(f"{notes['failures']} 本书的笔记拉取失败，新增划线可能少计。")
    if notes["new_highlights"] == 0 and notes["new_thoughts"] == 0:
        limitations.append("本周没有新增划线或想法。")
    if not notebooks_complete:
        limitations.append("笔记本列表未完整获取，本周在读与笔记统计可能不完整。")

    shelf_total = shelf["total"]
    digest = {
        "ok": True,
        "generated_at": _iso(current),
        "timezone": "Asia/Shanghai",
        "period": {
            "start": _date(start),
            "end": _date(current),
            "week_end": _date(end - 1),
            "label": f"{datetime.fromtimestamp(start, LOCAL_TZ):%m-%d} ~ {datetime.fromtimestamp(current, LOCAL_TZ):%m-%d}",
        },
        "reading": {
            "total_seconds": weekly["total_seconds"],
            "total_hours": weekly["total_hours"],
            "total_minutes": weekly["total_minutes"],
            "duration_label": weekly["duration_label"],
            "read_days": weekly["read_days"],
            "daily": weekly["daily"],
            "compare_pct": weekly["compare_pct"],
            "rank": weekly["rank"],
            "listening_seconds": weekly["listening_seconds"],
            "listening_hours": round(weekly["listening_seconds"] / 3600, 1),
            "longest_items": weekly["longest_items"],
        },
        "books": books,
        "notes": {
            "new_highlights": notes["new_highlights"],
            "new_thoughts": notes["new_thoughts"],
            "items": notes["items"],
            "newest_highlight": notes["newest_highlight"],
            "newest_thought": notes["newest_thought"],
            "scanned_books": notes["scanned_books"],
            "failures": notes["failures"],
        },
        "shelf": {
            "total": shelf_total,
            "finished_marked": shelf["finished"],
            "finished_pct": round(shelf["finished"] * 100 / shelf_total, 1) if shelf_total else 0,
            "notebooks": len(notebooks),
            "notebook_notes_reported": notebook_meta["total_notes"],
            "sum_note_counts": sum(book["note_count"] for book in notebooks),
            "sum_review_counts": sum(book["review_count"] for book in notebooks),
            "albums": len(shelf["albums"]),
        },
        "coverage": {
            "notebooks_complete": notebooks_complete,
            "notes_books_scanned": notes["scanned_books"],
            "notes_books_failed": notes["failures"],
        },
        "limitations": limitations,
    }
    _cache_put(key, digest)
    return digest


def normalize_reading_stats(raw: dict[str, Any], mode: str) -> dict[str, Any]:
    """Normalize ``/readdata/detail`` for weekly, monthly and overall views."""

    if mode not in {"weekly", "monthly", "overall"}:
        raise ValueError("mode 必须是 weekly、monthly 或 overall")

    total = int(_num(raw.get("totalReadTime")))
    read_days = int(_num(raw.get("readDays")))
    compare = raw.get("compare")
    compare_pct = (
        round(float(compare) * 100, 1)
        if isinstance(compare, (int, float)) and compare == compare
        else None
    )
    read_stat = [
        {"label": _text(item.get("stat")), "value": _text(item.get("counts"))}
        for item in _rows(raw.get("readStat"))
        if _text(item.get("stat"))
    ]
    categories = [
        {
            "name": _text(item.get("categoryTitle")),
            "parent": _text(item.get("parentCategoryTitle")) or None,
            "minutes": round(_num(item.get("readingTime")) / 60, 1),
            "books": int(_num(item.get("readingCount"))),
        }
        for item in _rows(raw.get("preferCategory"))
        if _text(item.get("categoryTitle"))
    ]
    authors = [
        {"name": _text(item.get("name")), "books": int(_num(item.get("count")))}
        for item in _rows(raw.get("preferAuthor"))
        if _text(item.get("name"))
    ]
    longest = [
        {
            "kind": "book" if _text(_obj(item.get("book")).get("bookId")) else "audio",
            "book_id": _text(_obj(item.get("book")).get("bookId")) or None,
            "title": _text(_obj(item.get("book")).get("title"))
            or _text(_obj(item.get("albumInfo")).get("name"))
            or None,
            "author": _text(_obj(item.get("book")).get("author"))
            or _text(_obj(item.get("albumInfo")).get("authorName"))
            or None,
            "seconds": int(_num(item.get("readTime"))),
        }
        for item in _rows(raw.get("readLongest"))
        if int(_num(item.get("readTime"))) > 0
    ]

    daily: list[dict[str, Any]] = []
    years: list[dict[str, Any]] = []
    for key, value in _obj(raw.get("readTimes")).items():
        moment = _timestamp(key)
        seconds = int(_num(value))
        if moment <= 0 or seconds <= 0:
            continue
        bucket = _date(moment) if mode != "overall" else str(datetime.fromtimestamp(moment, LOCAL_TZ).year)
        target = daily if mode != "overall" else years
        existing = next((item for item in target if item["date" if mode != "overall" else "year"] == bucket), None)
        if existing is None:
            if mode == "overall":
                target.append({"year": bucket, "seconds": seconds})
            else:
                target.append({"date": bucket, "seconds": seconds})
        else:
            existing["seconds"] += seconds
    daily.sort(key=lambda item: item["date"])
    years.sort(key=lambda item: item["year"])

    limitations: list[str] = []
    if mode == "weekly":
        limitations.append("周统计不返回分类/作者偏好，只能给出总时长与每日分桶。")
    if mode in {"weekly", "monthly"}:
        limitations.append("readLongest 只覆盖阅读时长最长的少量条目，不能据此还原每本书的完整时长。")
    return {
        "mode": mode,
        "total_seconds": total,
        "total_hours": round(total / 3600, 1),
        "read_days": read_days,
        "day_average_seconds": int(_num(raw.get("dayAverageReadTime"))),
        "compare_pct": compare_pct,
        "rank": _text(_obj(raw.get("rank")).get("text")) or None,
        "read_stat": read_stat,
        "daily": daily,
        "years": years,
        "prefer_categories": categories[:8],
        "prefer_authors": authors[:8],
        "prefer_time": [
            int(_num(item))
            for item in (raw.get("preferTime") if isinstance(raw.get("preferTime"), list) else [])
        ],
        "prefer_category_word": _text(raw.get("preferCategoryWord")) or None,
        "prefer_time_word": _text(raw.get("preferTimeWord")) or None,
        "longest_items": longest,
        "limitations": limitations,
    }


def build_reading_stats(
    call: GatewayCall,
    mode: str = "weekly",
    base_time: int | None = None,
) -> dict[str, Any]:
    """Fetch and normalize one reading-statistics view."""

    if mode not in {"weekly", "monthly", "overall"}:
        raise ValueError("mode 必须是 weekly、monthly 或 overall")
    current = int(base_time or time.time())
    raw = call("/readdata/detail", mode=mode, baseTime=current)
    result = normalize_reading_stats(raw, mode)
    result["base_time"] = _iso(current)
    return result


def dumps(value: dict[str, Any]) -> str:
    """Serialize compactly; the MCP layer hands this string to the agent."""

    return json.dumps(value, ensure_ascii=False, indent=2)
