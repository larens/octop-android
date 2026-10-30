#!/usr/bin/env python3
"""Disable Octop's in-place updater when the host is managed by blue-green."""

from __future__ import annotations

import os
import shutil
import time
from pathlib import Path


def _site_packages() -> Path:
    raw = os.environ.get("OCTOP_SITE_PACKAGES")
    if raw:
        return Path(raw)
    raise RuntimeError("OCTOP_SITE_PACKAGES is required")


def _replace_once(source: str, old: str, new: str, label: str) -> str:
    if new in source:
        return source
    if old not in source:
        raise RuntimeError(f"update router anchor not found: {label}")
    return source.replace(old, new, 1)


def install() -> Path:
    path = _site_packages() / "octop/api/routers/update.py"
    source = path.read_text(encoding="utf-8")
    if "OCTOP_MANAGED_UPDATE" in source:
        return path

    backup_root = Path(os.environ.get("OCTOP_BACKUPS_DIR", "/opt/octop/backups"))
    backup = backup_root / f"managed-update-{time.strftime('%Y%m%d-%H%M%S')}"
    backup.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, backup / "update.py")

    helper = '''
def _managed_by_bluegreen() -> bool:
    raw = (os.environ.get("OCTOP_MANAGED_UPDATE") or "").strip().lower()
    return raw in {"1", "true", "yes", "on"}

'''
    source = _replace_once(
        source,
        "logger = logging.getLogger(__name__)\n\n",
        "logger = logging.getLogger(__name__)\n" + helper,
        "managed helper",
    )
    source = _replace_once(
        source,
        "    has_update = bool(latest and is_newer(latest, current))\n",
        "    managed_update = _managed_by_bluegreen()\n"
        "    has_update = bool(latest and is_newer(latest, current) and not managed_update)\n",
        "status suppression",
    )
    source = _replace_once(
        source,
        '        "has_update": has_update,\n',
        '        "has_update": has_update,\n'
        '        "managed_update": managed_update,\n',
        "managed status field",
    )
    source = _replace_once(
        source,
        "    if get_editable_path() is not None:\n",
        "    if _managed_by_bluegreen():\n"
        "        raise OctopError(\n"
        "            ErrorCode.FORBIDDEN,\n"
        '            "this instance is managed by octop-bluegreen; use the staged upgrade flow",\n'
        "        )\n"
        "    if get_editable_path() is not None:\n",
        "upgrade block",
    )
    path.write_text(source, encoding="utf-8")
    return path


if __name__ == "__main__":
    print(install())
