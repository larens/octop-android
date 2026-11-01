"""Built-in MCP for Chinese statutory holidays and make-up workdays.

The dataset only contains arrangements published by the State Council.  It is
intentionally conservative: missing years are reported as unavailable instead
of being inferred from weekends or recurring holiday names.
"""

from __future__ import annotations

from copy import deepcopy
from datetime import date, datetime
from typing import Any
from zoneinfo import ZoneInfo

from mcp.server.fastmcp import FastMCP


SHANGHAI = ZoneInfo("Asia/Shanghai")

HOLIDAYS: dict[int, dict[str, Any]] = {
    2025: {
        "source_url": "https://www.gov.cn/zhengce/content/202411/content_6986382.htm",
        "published_at": "2024-11-12",
        "periods": [
            {"name": "元旦", "start": "2025-01-01", "end": "2025-01-01", "days": 1},
            {"name": "春节", "start": "2025-01-28", "end": "2025-02-04", "days": 8},
            {"name": "清明节", "start": "2025-04-04", "end": "2025-04-06", "days": 3},
            {"name": "劳动节", "start": "2025-05-01", "end": "2025-05-05", "days": 5},
            {"name": "端午节", "start": "2025-05-31", "end": "2025-06-02", "days": 3},
            {
                "name": "国庆节、中秋节",
                "start": "2025-10-01",
                "end": "2025-10-08",
                "days": 8,
            },
        ],
        "makeup_workdays": ["2025-01-26", "2025-02-08", "2025-04-27", "2025-09-28", "2025-10-11"],
    },
    2026: {
        "source_url": "https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm",
        "published_at": "2025-11-04",
        "periods": [
            {"name": "元旦", "start": "2026-01-01", "end": "2026-01-03", "days": 3},
            {"name": "春节", "start": "2026-02-15", "end": "2026-02-23", "days": 9},
            {"name": "清明节", "start": "2026-04-04", "end": "2026-04-06", "days": 3},
            {"name": "劳动节", "start": "2026-05-01", "end": "2026-05-05", "days": 5},
            {"name": "端午节", "start": "2026-06-19", "end": "2026-06-21", "days": 3},
            {"name": "中秋节", "start": "2026-09-25", "end": "2026-09-27", "days": 3},
            {"name": "国庆节", "start": "2026-10-01", "end": "2026-10-07", "days": 7},
        ],
        "makeup_workdays": ["2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10"],
    },
}


def _available_years() -> list[int]:
    return sorted(HOLIDAYS)


def _missing_year(year: int) -> dict[str, Any]:
    return {
        "year": year,
        "available": False,
        "available_years": _available_years(),
        "message": "该年度国务院放假通知尚未纳入；不根据周末或往年在售规则推测。",
    }


def holiday_payload(year: int | None = None) -> dict[str, Any]:
    """Return the published holiday periods and make-up workdays for a year."""
    selected = year or datetime.now(SHANGHAI).year
    data = HOLIDAYS.get(selected)
    if data is None:
        return _missing_year(selected)
    return {
        "year": selected,
        "available": True,
        **deepcopy(data),
    }


def workday_status(value: str | None = None) -> dict[str, Any]:
    """Classify one Shanghai-local ISO date as a workday, holiday, or weekend."""
    raw = value or datetime.now(SHANGHAI).date().isoformat()
    try:
        selected = date.fromisoformat(raw)
    except ValueError as exc:
        raise ValueError("date must use YYYY-MM-DD format") from exc

    data = HOLIDAYS.get(selected.year)
    if data is None:
        return {
            "date": selected.isoformat(),
            "available": False,
            **_missing_year(selected.year),
        }

    iso = selected.isoformat()
    for period in data["periods"]:
        if period["start"] <= iso <= period["end"]:
            return {
                "date": iso,
                "available": True,
                "is_workday": False,
                "reason": "statutory_holiday",
                "holiday": period["name"],
                "makeup_workday": False,
                "source_url": data["source_url"],
            }

    if iso in data["makeup_workdays"]:
        return {
            "date": iso,
            "available": True,
            "is_workday": True,
            "reason": "makeup_workday",
            "holiday": None,
            "makeup_workday": True,
            "source_url": data["source_url"],
        }

    is_weekday = selected.weekday() < 5
    return {
        "date": iso,
        "available": True,
        "is_workday": is_weekday,
        "reason": "weekday" if is_weekday else "weekend",
        "holiday": None,
        "makeup_workday": False,
        "source_url": data["source_url"],
    }


mcp = FastMCP("china-holidays")


@mcp.tool()
def get_china_holidays(year: int | None = None) -> dict[str, Any]:
    """查询中国法定节假日、调休上班日及国务院通知来源。"""
    return holiday_payload(year)


@mcp.tool()
def is_china_workday(day: str | None = None) -> dict[str, Any]:
    """判断 YYYY-MM-DD 是否为中国的法定工作日，并说明节假日或调休原因。"""
    return workday_status(day)


if __name__ == "__main__":
    mcp.run()
