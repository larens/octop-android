"""Install richer daily diaries and the User entity portrait into Octop 1.0.2."""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from patch_paths import backups_dir, site_packages

SITE = site_packages()
MEMORY = SITE / "octop_memory"
OCTOP = SITE / "octop"
BACKUP = backups_dir() / f"memory-diaries-{time.strftime('%Y%m%d-%H%M%S')}"


def replace_once(source: str, old: str, new: str, *, label: str) -> str:
    if new in source:
        return source
    if old not in source:
        raise SystemExit(f"anchor not found: {label}")
    return source.replace(old, new, 1)


def main() -> None:
    BACKUP.mkdir(parents=True, exist_ok=True)
    paths = [
        MEMORY / "application/dashboard_data.py",
        MEMORY / "adapters/bridge/handlers.py",
        OCTOP / "api/routers/memory.py",
        OCTOP / "api/common/memory_client.py",
    ]
    for path in paths:
        shutil.copy2(path, BACKUP / path.name)

    target_module = OCTOP / "infra/memory/mobile_diaries.py"
    target_module.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(Path(__file__).with_name("memory_diaries.py"), target_module)

    profile_module = OCTOP / "infra/memory/mobile_profiles.py"
    if profile_module.exists():
        shutil.copy2(profile_module, BACKUP / profile_module.name)
    shutil.copy2(Path(__file__).with_name("mobile_profiles.py"), profile_module)

    dashboard_path = MEMORY / "application/dashboard_data.py"
    dashboard = dashboard_path.read_text()
    dashboard_start = dashboard.index("def terminal_about_me(")
    dashboard_end = dashboard.index("\n\ndef terminal_current_focus", dashboard_start)
    terminal_about_me = '''def terminal_about_me(memory: Memory, params: dict[str, Any]) -> dict[str, Any]:
    """About-You card backed by the rich User entity page when available."""
    limit = _limit(params, default=5, cap=20)
    items = _terminal_atoms_by_kinds(memory, kinds=_TERMINAL_PREFERENCE_KINDS, limit=limit)

    user_entities = [
        entity
        for entity in memory.list_entities(limit=10_000)
        if str(getattr(entity, "entity_type", "")).lower() == "user"
    ]
    user_entities.sort(key=lambda entity: getattr(entity, "atom_count", 0), reverse=True)

    profile_markdown = ""
    profile_subject = None
    profile_updated_at = None
    if user_entities:
        entity = user_entities[0]
        page = memory.get_entity_page(entity.id)
        if page is not None:
            profile_markdown = str(getattr(page, "summary_markdown", "") or "").strip()
            first_line = profile_markdown.splitlines()[0].strip() if profile_markdown else ""
            subject_end = len(first_line)
            for marker in ("（", "(", "，", ",", "。"):
                position = first_line.find(marker)
                if position > 0:
                    subject_end = min(subject_end, position)
            candidate = first_line[:subject_end].strip()
            if 2 <= len(candidate) <= 20:
                profile_subject = candidate
            updated = getattr(page, "last_regen_at", None) or getattr(page, "updated_at", None)
            profile_updated_at = _to_jsonable(updated)

    return {
        "items": items,
        "profile_markdown": profile_markdown,
        "profile_subject": profile_subject,
        "profile_updated_at": profile_updated_at,
    }'''
    dashboard = dashboard[:dashboard_start] + terminal_about_me + dashboard[dashboard_end:]
    dashboard_path.write_text(dashboard)

    bridge_path = MEMORY / "adapters/bridge/handlers.py"
    bridge = bridge_path.read_text()
    bridge_anchor = "        self._dispatch.update(dashboard_data.build_dispatch(memory))\n"
    bridge_addition = '''        # [mobile-diary-installed]
        from octop.infra.memory.mobile_diaries import list_memory_diaries

        self._dispatch["list_memory_diaries"] = lambda params: list_memory_diaries(
            self._memory,
            self._runtime.llm if self._runtime.llm_configured else None,
            params,
        )
'''
    if "[mobile-diary-installed]" not in bridge:
        bridge = replace_once(
            bridge,
            bridge_anchor,
            bridge_anchor + bridge_addition,
            label="Bridge dispatch",
        )

    profile_anchor = "        self._dispatch.update(dashboard_data.build_dispatch(memory))\n"
    profile_addition = '''        # [mobile-profile-installed]
        from octop.infra.memory.mobile_profiles import terminal_about_me_with_narrative

        self._dispatch["terminal_about_me"] = lambda params: terminal_about_me_with_narrative(
            self._memory,
            self._runtime.llm if self._runtime.llm_configured else None,
            params,
        )
'''
    if "[mobile-profile-installed]" not in bridge:
        bridge = replace_once(
            bridge,
            profile_anchor,
            profile_anchor + profile_addition,
            label="mobile profile dispatch",
        )
    bridge_path.write_text(bridge)

    router_path = OCTOP / "api/routers/memory.py"
    router = router_path.read_text()
    if "import asyncio\n" not in router:
        router = replace_once(
            router,
            "from __future__ import annotations\n\n",
            "from __future__ import annotations\n\nimport asyncio\n",
            label="diary asyncio import",
        )
    body_anchor = '''class _ListJournalBody(BaseModel):
'''
    body_addition = '''class _ListDiariesBody(BaseModel):
    days: int | None = Field(default=7, ge=1, le=30)
    limit: int | None = Field(default=60, ge=1, le=90)
    refresh: bool = False


'''
    if "class _ListDiariesBody" not in router:
        router = replace_once(router, body_anchor, body_addition + body_anchor, label="diary body")

    route_anchor = '@router.post("/agents/{agent_id}/memory/episodes/list")\n'
    route_addition = '''@router.post("/agents/{agent_id}/memory/diaries/list")
async def list_memory_diaries(
    agent_id: str,
    body: _ListDiariesBody = Body(default_factory=_ListDiariesBody),
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    # Generation can take minutes, so keep the server event loop responsive.
    # [mobile-diary-async-installed]
    return cast(
        dict[str, Any],
        await asyncio.to_thread(
            call_memory_rpc,
            agent_id=agent_id,
            method="list_memory_diaries",
            params=_strip_none(body.model_dump()),
            user=user,
            as_user=as_user,
            server=server,
        ),
    )


'''
    old_route = '''@router.post("/agents/{agent_id}/memory/diaries/list")
async def list_memory_diaries(
    agent_id: str,
    body: _ListDiariesBody = Body(default_factory=_ListDiariesBody),
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    return cast(
        dict[str, Any],
        call_memory_rpc(
            agent_id=agent_id,
            method="list_memory_diaries",
            params=_strip_none(body.model_dump()),
            user=user,
            as_user=as_user,
            server=server,
        ),
    )


'''
    if 'method="list_memory_diaries"' not in router:
        router = replace_once(router, route_anchor, route_addition + route_anchor, label="diary route")
    elif old_route in router:
        router = router.replace(old_route, route_addition, 1)
    router_path.write_text(router)

    memory_client_path = OCTOP / "api/common/memory_client.py"
    memory_client = memory_client_path.read_text()
    memory_client = replace_once(
        memory_client,
        '''    def get_or_open(
        self,
        agent_id: str,
        *,
        backend: str,
        backend_config: dict[str, Any] | None,
        fingerprint: str,
    ) -> tuple[Any, Any]:''',
        '''    def get_or_open(
        self,
        agent_id: str,
        *,
        backend: str,
        backend_config: dict[str, Any] | None,
        fingerprint: str,
        llm: Any = None,
    ) -> tuple[Any, Any]:''',
        label="memory cache signature",
    )
    memory_client = replace_once(
        memory_client,
        "            bridge = Bridge(memory)\n",
        "            # [mobile-diary-llm-installed]\n            bridge = Bridge(memory, llm=llm)\n",
        label="memory bridge llm",
    )
    memory_client = replace_once(
        memory_client,
        '''    runtime = getattr(server, "app_runtime", None)
    if runtime is not None:''',
        '''    runtime = getattr(server, "app_runtime", None)
    llm = None
    if runtime is not None:
        manager = getattr(runtime, "agent_registry", None)
        if manager is not None:
            try:
                agent = manager.get_agent(agent_id)
                memory_runtime = getattr(agent, "_memory_runtime", None)
                llm = getattr(memory_runtime, "_llm_client", None)
            except Exception:  # agent may be stopped; read-only memory still works
                logger.debug("memory LLM unavailable for agent %s", agent_id, exc_info=True)
    if llm is not None:
        fingerprint += f":llm:{id(llm)}"
    if runtime is not None:''',
        label="memory agent llm",
    )
    memory_client = replace_once(
        memory_client,
        '''        backend=backend,
        backend_config=backend_config,
        fingerprint=fingerprint,
    )''',
        '''        backend=backend,
        backend_config=backend_config,
        fingerprint=fingerprint,
        llm=llm,
    )''',
        label="memory cache llm",
    )
    memory_client_path.write_text(memory_client)

    print(BACKUP)


if __name__ == "__main__":
    main()
