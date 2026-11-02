"""Install Didi ride status publication into Today."""

from __future__ import annotations

import shutil
from pathlib import Path

from patch_paths import octop_site

IMPORT_ANCHOR = (
    "        from octop.infra.agents.middleware.thread_artifacts import "
    "ThreadArtifactsMiddleware\n"
)
IMPORT_LINE = (
    "        from octop.infra.agents.middleware.didi_ride_today import "
    "DidiRideTodayMiddleware\n"
)
MIDDLEWARE_ANCHOR = '''            ThreadArtifactsMiddleware(
                thread_repo=self._repos.thread_repo,
                workspace_dir=harness_workspace,
                agent_id=row.agent_id,
            ),
'''
MIDDLEWARE_ADDITION = '''            DidiRideTodayMiddleware(
                db=self._repos.db,
                agent_id=row.agent_id,
                user_id=row.user_id,
            ),
'''


def patch_manager_source(source: str) -> str:
    if IMPORT_LINE not in source:
        if IMPORT_ANCHOR not in source:
            raise RuntimeError("Octop 1.0.2 Didi middleware import anchor not found")
        source = source.replace(IMPORT_ANCHOR, IMPORT_LINE + IMPORT_ANCHOR, 1)
    if "DidiRideTodayMiddleware(" not in source:
        if MIDDLEWARE_ANCHOR not in source:
            raise RuntimeError("Octop 1.0.2 Didi middleware list anchor not found")
        source = source.replace(
            MIDDLEWARE_ANCHOR,
            MIDDLEWARE_ANCHOR + MIDDLEWARE_ADDITION,
            1,
        )
    return source


def main() -> None:
    site = octop_site()
    manager_path = site / "infra/agents/manager.py"
    runtime_target = site / "infra/today/didi_ride_cards.py"
    middleware_target = site / "infra/agents/middleware/didi_ride_today.py"

    shutil.copyfile(Path(__file__).with_name("didi_ride_today.py"), runtime_target)
    shutil.copyfile(
        Path(__file__).with_name("didi_ride_today_middleware.py"),
        middleware_target,
    )

    source = manager_path.read_text(encoding="utf-8")
    manager_path.write_text(patch_manager_source(source), encoding="utf-8")
    print(manager_path)


if __name__ == "__main__":
    main()
