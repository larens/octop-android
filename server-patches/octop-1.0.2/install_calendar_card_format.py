"""Format calendar cards as time ranges plus a labeled location."""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from patch_paths import backups_dir, octop_site

site = octop_site()
path = site / "api/routers/mobile_ingest.py"
source = path.read_text()
marker = "def _calendar_time_range_label("

if marker not in source:
    backup = backups_dir() / f"calendar-card-format-{time.strftime('%Y%m%d-%H%M%S')}"
    backup.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, backup / "mobile_ingest.py")

    function_anchor = "def _calendar_card_copy(\n"
    helper = '''def _calendar_time_range_label(starts_ms: int, ends_ms: int | None) -> str:
    starts_at = datetime.fromtimestamp(starts_ms / 1000, tz=timezone.utc).astimezone()
    start_label = starts_at.strftime("%H:%M")
    if ends_ms is None or ends_ms <= starts_ms:
        return start_label
    ends_at = datetime.fromtimestamp(ends_ms / 1000, tz=timezone.utc).astimezone()
    if ends_at.date() != starts_at.date():
        return start_label
    return f"{start_label}-{ends_at.strftime('%H:%M')}"


'''
    if function_anchor not in source:
        raise SystemExit("Octop 1.0.2 calendar copy anchor not found")
    source = source.replace(function_anchor, helper + function_anchor, 1)

    time_old = '''    time_label = starts_at.strftime("%H:%M")
    date_label = f"{starts_at.month}月{starts_at.day}日 {time_label}"
'''
    time_new = '''    time_label = _calendar_time_range_label(starts_ms, ends_ms)
    date_label = f"{starts_at.month}月{starts_at.day}日 {time_label}"
'''
    if time_old not in source:
        raise SystemExit("Octop 1.0.2 calendar time label anchor not found")
    source = source.replace(time_old, time_new, 1)

    summary_old = '''    location = _payload_text(payload, "location")
    summary = f"{day_label} {time_label} · {event_label}"
    if location:
        summary += f" · {location}"
'''
    summary_new = '''    location = _payload_text(payload, "location")
    schedule_label = time_label if day_delta == 0 else f"{day_label} {time_label}"
    summary = f"{schedule_label} · {event_label}"
    if location:
        summary += f" · 地点：{location}"
'''
    if summary_old not in source:
        raise SystemExit("Octop 1.0.2 calendar summary anchor not found")
    source = source.replace(summary_old, summary_new, 1)
    path.write_text(source)
