#!/usr/bin/env python3
"""Mount the custom mobile ingest router after community upgrades."""

from __future__ import annotations

import shutil
import time

from patch_paths import backups_dir, octop_site


def patch_source(source: str) -> str:
    if "mobile_ingest import router as mobile_ingest_router" in source:
        return source

    import_anchor = "    from octop.api.routers.decision import router as decision_router\n"
    if import_anchor not in source:
        raise RuntimeError("decision router import anchor not found")
    source = source.replace(
        import_anchor,
        "    from octop.api.routers.mobile_ingest import router as mobile_ingest_router\n"
        + import_anchor,
        1,
    )

    mount_anchor = '            _RouterMount(update.router, "/api", ["update"]),\n'
    if mount_anchor not in source:
        raise RuntimeError("update router mount anchor not found")
    return source.replace(
        mount_anchor,
        mount_anchor
        + '            _RouterMount(mobile_ingest_router, "/api", ["mobile"]),\n',
        1,
    )


def main() -> None:
    path = octop_site() / "api/app.py"
    source = path.read_text(encoding="utf-8")
    patched = patch_source(source)
    if patched == source:
        print(path)
        return

    backup = backups_dir() / f"mobile-ingest-router-{time.strftime('%Y%m%d-%H%M%S')}"
    backup.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, backup / "app.py")
    path.write_text(patched, encoding="utf-8")
    print(path)


if __name__ == "__main__":
    main()
