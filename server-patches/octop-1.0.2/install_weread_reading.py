"""Install the normalized WeRead reading-digest adapter."""

from __future__ import annotations

import shutil
from pathlib import Path

from patch_paths import octop_site


def main() -> None:
    site = octop_site()
    adapters = site / "infra/connectors/gateway/adapters"
    source = Path(__file__).parent
    targets = {
        source / "weread_reading.py": adapters / "weread_reading.py",
        source / "wechat_reading.py": adapters / "wechat_reading.py",
    }
    backup = adapters / "wechat_reading.py.pre-digest"
    current = adapters / "wechat_reading.py"
    if current.exists() and not backup.exists():
        shutil.copyfile(current, backup)
    for src, dst in targets.items():
        shutil.copyfile(src, dst)
        if dst.read_bytes() != src.read_bytes():
            raise RuntimeError(f"WeRead runtime artifact does not match: {dst}")
    print("\n".join(str(path) for path in targets.values()))


if __name__ == "__main__":
    main()
