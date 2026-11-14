"""Install the evening token-usage card into the Today feed."""

from __future__ import annotations

import re
import shutil
from pathlib import Path

from patch_paths import octop_site

HEALTH_IMPORT = "from octop.infra.today.health_cards import publish_health_today_cards\n"
USAGE_IMPORT = "from octop.infra.today.usage_cards import publish_usage_today_card\n"
HEALTH_CALL = """    publish_health_today_cards(
        server.services.db,
        agent_id=agent_id,
        user_id=user.id,
        now=now,
    )
"""
USAGE_CALL = """    publish_usage_today_card(
        server.services.db,
        agent_id=agent_id,
        user_id=user.id,
        now=now,
        timezone_name=str(
            getattr(server.config, "default_timezone", None) or "Asia/Shanghai"
        ),
    )
"""
_CARD_KIND_RE = re.compile(r"TodayCardKind = Literal\[([^\]]*)\]")


def patch_card_kind_source(source: str) -> str:
    """Allow the new ``usage`` kind without dropping later custom kinds."""
    match = _CARD_KIND_RE.search(source)
    if match is None:
        raise RuntimeError("Octop 1.0.2 Today card kind anchor not found")
    values = {value.strip().strip("\"'") for value in match.group(1).split(",")}
    if "usage" in values:
        return source
    replacement = f'TodayCardKind = Literal[{match.group(1)}, "usage"]'
    return source[: match.start()] + replacement + source[match.end() :]


def patch_mobile_ingest_source(source: str) -> str:
    """Publish the usage card whenever the mobile Today feed is refreshed."""
    if USAGE_IMPORT not in source:
        if HEALTH_IMPORT not in source:
            raise RuntimeError("Octop 1.0.2 health card import anchor not found")
        source = source.replace(HEALTH_IMPORT, HEALTH_IMPORT + USAGE_IMPORT, 1)
    if "publish_usage_today_card(" not in source:
        if HEALTH_CALL not in source:
            raise RuntimeError("Octop 1.0.2 health card call anchor not found")
        source = source.replace(HEALTH_CALL, HEALTH_CALL + USAGE_CALL, 1)
    return source


def main() -> None:
    site = octop_site()
    runtime_source = Path(__file__).with_name("usage_today_cards.py")
    runtime_target = site / "infra/today/usage_cards.py"
    cards_path = site / "infra/today/cards.py"
    mobile_ingest_path = site / "api/routers/mobile_ingest.py"

    shutil.copyfile(runtime_source, runtime_target)
    if runtime_target.read_bytes() != runtime_source.read_bytes():
        raise RuntimeError("usage Today runtime artifact does not match the patch")

    cards_path.write_text(
        patch_card_kind_source(cards_path.read_text(encoding="utf-8")),
        encoding="utf-8",
    )
    mobile_ingest_path.write_text(
        patch_mobile_ingest_source(mobile_ingest_path.read_text(encoding="utf-8")),
        encoding="utf-8",
    )
    print(cards_path)
    print(mobile_ingest_path)
    print(runtime_target)


if __name__ == "__main__":
    main()
