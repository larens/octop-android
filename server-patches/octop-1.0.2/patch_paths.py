"""Resolve patch targets without assuming one production site-packages path."""

from __future__ import annotations

import os
from pathlib import Path


DEFAULT_SITE_PACKAGES = Path("/opt/octop/venv/lib/python3.12/site-packages")
DEFAULT_BACKUPS_DIR = Path("/opt/octop/backups")
DEFAULT_DB_PATH = Path("/opt/octop/octop.db")


def site_packages() -> Path:
    return Path(os.environ.get("OCTOP_SITE_PACKAGES", str(DEFAULT_SITE_PACKAGES)))


def octop_site() -> Path:
    return site_packages() / "octop"


def backups_dir() -> Path:
    return Path(os.environ.get("OCTOP_BACKUPS_DIR", str(DEFAULT_BACKUPS_DIR)))


def db_path() -> Path:
    return Path(os.environ.get("OCTOP_DB_PATH", str(DEFAULT_DB_PATH)))
