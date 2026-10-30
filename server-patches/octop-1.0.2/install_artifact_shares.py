"""Install the guarded public HTML artifact-share route for Octop 1.0.2."""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from patch_paths import backups_dir, octop_site


def patch_app(source: str) -> str:
    if "artifact_shares import router as artifact_share_router" in source:
        return source

    import_anchor = "    from octop.api.routers.mobile_ingest import router as mobile_ingest_router\n"
    if import_anchor not in source:
        raise RuntimeError("mobile ingest router import anchor not found")
    source = source.replace(
        import_anchor,
        "    from octop.api.routers.artifact_shares import router as artifact_share_router\n"
        + import_anchor,
        1,
    )

    mount_anchor = '            _RouterMount(plugins.router, "/api", ["plugins"]),\n'
    if mount_anchor not in source:
        raise RuntimeError("plugins router mount anchor not found")
    return source.replace(
        mount_anchor,
        mount_anchor
        + '            _RouterMount(artifact_share_router, "/api", ["artifacts"]),\n',
        1,
    )


def patch_deps(source: str) -> str:
    if '"/api/s/",' in source:
        return source
    anchor = '    "/api/downloads/link/",\n'
    if anchor not in source:
        raise RuntimeError("JWT exemption anchor not found")
    additions = (
        "    # Public capability links bind one registered HTML artifact.\n"
        + '    "/api/s/",\n'
    )
    if '"/api/artifact-shares/",' not in source:
        additions += '    "/api/artifact-shares/",\n'
    return source.replace(
        anchor,
        anchor + additions,
        1,
    )


def main() -> None:
    site = octop_site()
    backup = backups_dir() / f"artifact-shares-{time.strftime('%Y%m%d-%H%M%S')}"
    backup.mkdir(parents=True, exist_ok=True)

    target_module = site / "api/routers/artifact_shares.py"
    if target_module.exists():
        shutil.copy2(target_module, backup / "artifact_shares.py")
    target_module.write_text(
        Path(__file__).with_name("artifact_shares.py").read_text(
            encoding="utf-8"
        ),
        encoding="utf-8",
    )

    for name, patcher in (("app.py", patch_app), ("deps.py", patch_deps)):
        path = site / "api" / name
        source = path.read_text(encoding="utf-8")
        shutil.copy2(path, backup / name)
        path.write_text(patcher(source), encoding="utf-8")
    print(target_module)


if __name__ == "__main__":
    main()
