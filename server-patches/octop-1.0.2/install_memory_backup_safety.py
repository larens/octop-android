"""Install consistent SQLite snapshots for memory databases in system backups."""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from patch_paths import backups_dir, octop_site


def main() -> None:
    target = octop_site() / "infra/backup/system_archive.py"
    backup = backups_dir() / f"memory-backup-safety-{time.strftime('%Y%m%d-%H%M%S')}"
    backup.mkdir(parents=True, exist_ok=True)
    shutil.copy2(target, backup / target.name)
    shutil.copy2(Path(__file__).with_name("system_archive.py"), target)
    print(target)


if __name__ == "__main__":
    main()
