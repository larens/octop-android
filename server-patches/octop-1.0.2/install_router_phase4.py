#!/usr/bin/env python3
"""Install task-scoped approvals and idempotent write receipts for Router Phase 4."""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from patch_paths import backups_dir, octop_site


PACK_DIR = Path(__file__).resolve().parent


def main() -> None:
    site = octop_site()
    targets = {
        site / "infra/conversation/service.py": PACK_DIR / "router_phase0_service.py",
        site / "infra/conversation/budget.py": PACK_DIR / "router_phase0_budget.py",
        site / "infra/conversation/orchestrator.py": PACK_DIR
        / "router_phase3_orchestrator.py",
        site / "infra/conversation/dispatch_plans.py": PACK_DIR
        / "router_phase35_plans.py",
        site / "infra/conversation/write_actions.py": PACK_DIR
        / "router_phase4_actions.py",
        site / "api/routers/conversation_router.py": PACK_DIR / "router_phase0_api.py",
    }
    changed = {
        target: source
        for target, source in targets.items()
        if not target.exists() or target.read_bytes() != source.read_bytes()
    }
    if not changed:
        print(site / "infra/conversation/write_actions.py")
        return

    backup = backups_dir() / f"router-phase4-{time.strftime('%Y%m%d-%H%M%S')}"
    backup.mkdir(parents=True, exist_ok=True)
    for target in changed:
        if target.exists():
            shutil.copy2(target, backup / target.name)
    for target, source in changed.items():
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    print(site / "infra/conversation/write_actions.py")


if __name__ == "__main__":
    main()
