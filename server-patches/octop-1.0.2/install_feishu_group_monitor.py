#!/usr/bin/env python3
"""Install the read-only Feishu group monitor and its startup hook."""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from patch_paths import backups_dir, octop_site

PACK_DIR = Path(__file__).resolve().parent
RUNTIME_SOURCE = PACK_DIR / "feishu_group_monitor.py"

IMPORT_BLOCK = (
    "        from octop.infra.today.feishu_group_monitor import "
    "start_background_monitor  # noqa: PLC0415\n"
    "\n"
    "        start_background_monitor(self)\n"
)
ANCHOR = '        asyncio.create_task(_resume_bridges(), name="bridge-auto-resume")\n'


def main() -> None:
    site = octop_site()
    runtime = site / "infra/today/feishu_group_monitor.py"
    server = site / "infra/server.py"
    if not server.exists():
        raise SystemExit(f"server.py not found: {server}")

    backup = backups_dir() / f"feishu-group-monitor-{time.strftime('%Y%m%d-%H%M%S')}"
    backup.mkdir(parents=True, exist_ok=True)
    if runtime.exists():
        shutil.copy2(runtime, backup / runtime.name)

    runtime.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(RUNTIME_SOURCE, runtime)

    source = server.read_text(encoding="utf-8")
    if "start_background_monitor(self)" not in source:
        if ANCHOR not in source:
            raise SystemExit("Octop server.py bridge-resume anchor not found")
        shutil.copy2(server, backup / server.name)
        source = source.replace(ANCHOR, ANCHOR + "\n" + IMPORT_BLOCK, 1)
        server.write_text(source, encoding="utf-8")

    print(runtime)


if __name__ == "__main__":
    main()
