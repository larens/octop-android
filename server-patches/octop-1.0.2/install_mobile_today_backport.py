#!/usr/bin/env python3
"""Install custom mobile and Today modules that are absent from Octop 1.0.2."""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from patch_paths import backups_dir, octop_site


BACKPORT_DIR = Path(__file__).with_name("backport")
REQUIRED_PATHS = (
    Path("octop/api/routers/interest_monitors.py"),
    Path("octop/api/routers/mobile_ingest.py"),
    Path("octop/infra/interest_monitor.py"),
    Path("octop/infra/today/__init__.py"),
    Path("octop/infra/today/automatic.py"),
    Path("octop/infra/today/cards.py"),
    Path("octop/infra/today/cron_cards.py"),
    Path("octop/infra/today/health_cards.py"),
)
INTEREST_IMPORT = (
    "    from octop.api.routers.interest_monitors import router as "
    "interest_monitors_router\n"
)
INTEREST_MOUNT = (
    '            _RouterMount(interest_monitors_router, "/api", ["interests"]),\n'
)


def patch_app_source(source: str) -> str:
    """Mount the interest monitor router without disturbing other app routes."""
    if INTEREST_IMPORT in source and INTEREST_MOUNT in source:
        return source

    if INTEREST_IMPORT not in source:
        import_anchor = (
            "    from octop.api.routers.decision import router as decision_router\n"
        )
        if import_anchor not in source:
            raise RuntimeError("Octop 1.0.2 decision router import anchor not found")
        source = source.replace(
            import_anchor,
            import_anchor + INTEREST_IMPORT,
            1,
        )

    if INTEREST_MOUNT not in source:
        mount_anchor = (
            '            _RouterMount(decision_router, "/api", ["settings"]),\n'
        )
        if mount_anchor not in source:
            raise RuntimeError("Octop 1.0.2 decision router mount anchor not found")
        source = source.replace(
            mount_anchor,
            mount_anchor + INTEREST_MOUNT,
            1,
        )
    return source


def install_backport_files(
    *,
    source_root: Path,
    target_site: Path,
    backup_root: Path,
    timestamp: str | None = None,
) -> list[Path]:
    """Copy the backport tree, validating every required file and backing up replacements."""
    missing = [str(path) for path in REQUIRED_PATHS if not (source_root / path).is_file()]
    if missing:
        raise RuntimeError("mobile/Today backport is incomplete: " + ", ".join(missing))

    stamp = timestamp or time.strftime("%Y%m%d-%H%M%S")
    backup_dir = backup_root / f"mobile-today-backport-{stamp}"
    changed: list[Path] = []
    for source in sorted(source_root.rglob("*.py")):
        relative = source.relative_to(source_root)
        target = target_site / relative
        content = source.read_bytes()
        if target.is_file() and target.read_bytes() == content:
            continue
        if target.exists():
            backup = backup_dir / relative
            backup.parent.mkdir(parents=True, exist_ok=True)
            if target.is_dir():
                raise RuntimeError(f"backport target is a directory: {target}")
            shutil.copy2(target, backup)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        changed.append(target)
    return changed


def install_app_router(
    *,
    target_site: Path,
    backup_root: Path,
    timestamp: str | None = None,
) -> Path:
    path = target_site / "octop/api/app.py"
    source = path.read_text(encoding="utf-8")
    patched = patch_app_source(source)
    if patched == source:
        return path

    stamp = timestamp or time.strftime("%Y%m%d-%H%M%S")
    backup = backup_root / f"mobile-today-backport-{stamp}" / "octop/api/app.py"
    backup.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, backup)
    path.write_text(patched, encoding="utf-8")
    return path


def main() -> None:
    target_site = octop_site().parent
    stamp = time.strftime("%Y%m%d-%H%M%S")
    changed = install_backport_files(
        source_root=BACKPORT_DIR,
        target_site=target_site,
        backup_root=backups_dir(),
        timestamp=stamp,
    )
    app_path = install_app_router(
        target_site=target_site,
        backup_root=backups_dir(),
        timestamp=stamp,
    )
    print(f"installed_files={len(changed)}")
    print(f"app={app_path}")


if __name__ == "__main__":
    main()
