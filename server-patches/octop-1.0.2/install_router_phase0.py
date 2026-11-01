#!/usr/bin/env python3
"""Install the Phase 0 main-conversation APIs, trace, budget, and shadow router."""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from patch_paths import backups_dir, octop_site


PACK_DIR = Path(__file__).resolve().parent
IMPORT_LINE = (
    "    from octop.api.routers.conversation_router import router as conversation_router\n"
)
MOUNT_LINE = '            _RouterMount(conversation_router, "/api", ["conversation"]),\n'
SHADOW_IMPORT = (
    "from octop.infra.conversation.shadow import maybe_schedule_shadow_router\n"
)
SHADOW_CALL = """\
    # Router Phase 0: observe only; do not change the dashboard execution path.
    maybe_schedule_shadow_router(
        server,
        agent_id=agent_id,
        user_id=user.id,
        thread_id=thread_id,
        session_key=session_key,
        text=_turn_plain_text(turn),
        has_attachments=bool(inbound_attachments),
        target_agent_ids=tuple(target_ids or ()),
        knowledge_base_ids=(
            tuple(turn.knowledge_base_ids)
            if turn.knowledge_base_ids is not None
            else None
        ),
    )
"""

CRON_MAKE_JOB_OLD = """\
    def _make_job(self, row: Any) -> CronJob:
        return CronJob.from_row(
            row,
            delivery_service=self._delivery_service,
            cron_repo=self._repos.cron_repo,
            audit_repo=self._repos.audit_repo,
        )
"""
CRON_MAKE_JOB_NEW = """\
    def _make_job(self, row: Any) -> CronJob:
        from octop.infra.conversation.budget import RouterBudgetGate  # noqa: PLC0415

        return CronJob.from_row(
            row,
            delivery_service=self._delivery_service,
            cron_repo=self._repos.cron_repo,
            audit_repo=self._repos.audit_repo,
            budget_gate=RouterBudgetGate(
                self._repos.db,
                self._repos.settings_repo,
                timezone_name=self._timezone,
            ),
        )
"""

CRON_JOB_OLD = """\
    async def run(self, *, raise_on_error: bool = False) -> None:
        from octop.infra.metrics import METRICS  # noqa: PLC0415

        METRICS.inc("cron_runs_total")
        ts = int(time.time())

        try:
            await self._delivery_service.deliver(
                CronDeliveryCommand(
                    cron_id=self._cron_id,
                    cron_name=self._name,
                    agent_id=self._agent_id,
                    user_id=self._user_id,
                    session_key=self._session_key,
                    prompt=self._prompt,
                    fresh_thread=self._fresh_thread,
                    task_type=self._task_type,
                    model=self._model,
                    mcp_servers=tuple(self._mcp_servers),
                )
            )
        except Exception as exc:
            METRICS.inc("cron_errors_total")
            logger.exception("cron job %s failed", self._cron_id)
            err = str(exc)
            self._cron_repo.set_run_status(self._cron_id, ts=ts, status="error", error=err)
            self._audit_repo.write(
                actor=ACTOR_SYSTEM,
                action="cron.run_failed",
                target=self._cron_id,
                payload=err,
            )
            if raise_on_error:
                raise
            return
        self._cron_repo.set_run_status(self._cron_id, ts=ts, status="ok", error=None)
        self._audit_repo.write(
            actor=ACTOR_SYSTEM,
            action="cron.run_ok",
            target=self._cron_id,
        )
"""
CRON_JOB_NEW = """\
    async def run(self, *, raise_on_error: bool = False) -> None:
        from octop.infra.metrics import METRICS  # noqa: PLC0415

        METRICS.inc("cron_runs_total")
        ts = int(time.time())
        budget_lease = None
        if self._budget_gate is not None:
            try:
                budget_lease = self._budget_gate.start(
                    surface="cron",
                    agent_id=self._agent_id,
                    user_id=self._user_id,
                    source_id=self._cron_id,
                )
            except Exception as exc:
                METRICS.inc("cron_errors_total")
                err = str(exc)
                logger.warning("cron job %s blocked by router budget: %s", self._cron_id, err)
                self._cron_repo.set_run_status(
                    self._cron_id, ts=ts, status="error", error=err
                )
                self._audit_repo.write(
                    actor=ACTOR_SYSTEM,
                    action="cron.run_blocked",
                    target=self._cron_id,
                    payload=err,
                )
                if raise_on_error:
                    raise
                return

        try:
            result = await self._delivery_service.deliver(
                CronDeliveryCommand(
                    cron_id=self._cron_id,
                    cron_name=self._name,
                    agent_id=self._agent_id,
                    user_id=self._user_id,
                    session_key=self._session_key,
                    prompt=self._prompt,
                    fresh_thread=self._fresh_thread,
                    task_type=self._task_type,
                    model=self._model,
                    mcp_servers=tuple(self._mcp_servers),
                    budget_lease=budget_lease,
                )
            )
        except Exception as exc:
            METRICS.inc("cron_errors_total")
            logger.exception("cron job %s failed", self._cron_id)
            err = str(exc)
            if budget_lease is not None:
                budget_lease.finish(failed=True)
            self._cron_repo.set_run_status(self._cron_id, ts=ts, status="error", error=err)
            self._audit_repo.write(
                actor=ACTOR_SYSTEM,
                action="cron.run_failed",
                target=self._cron_id,
                payload=err,
            )
            if raise_on_error:
                raise
            return
        if budget_lease is not None:
            budget_lease.finish(
                actual_tokens=int((result or {}).get("total_tokens", 0) or 0),
                failed=False,
            )
        self._cron_repo.set_run_status(self._cron_id, ts=ts, status="ok", error=None)
        self._audit_repo.write(
            actor=ACTOR_SYSTEM,
            action="cron.run_ok",
            target=self._cron_id,
        )
"""


def patch_source(source: str) -> str:
    if "conversation_router import router as conversation_router" not in source:
        import_anchor = (
            "    from octop.api.routers.decision import router as decision_router\n"
        )
        if import_anchor not in source:
            raise RuntimeError("decision router import anchor not found")
        source = source.replace(import_anchor, IMPORT_LINE + import_anchor, 1)

    if "_RouterMount(conversation_router" not in source:
        mount_anchor = '            _RouterMount(update.router, "/api", ["update"]),\n'
        if mount_anchor not in source:
            raise RuntimeError("update router mount anchor not found")
        source = source.replace(mount_anchor, mount_anchor + MOUNT_LINE, 1)
    return source


def patch_chat_turn_source(source: str) -> str:
    if SHADOW_IMPORT.strip() not in source:
        anchor = "from octop.infra.gateway.ws import WS_CHANNEL_ID\n"
        if anchor not in source:
            raise RuntimeError("chat turn import anchor not found")
        source = source.replace(anchor, anchor + SHADOW_IMPORT, 1)
    if "Router Phase 0: observe only" not in source:
        anchor = "    inbound_attachments = inbound_attachments_from_parts(inbound_content)\n"
        if anchor not in source:
            raise RuntimeError("chat turn shadow insertion anchor not found")
        source = source.replace(anchor, anchor + SHADOW_CALL, 1)
    elif "knowledge_base_ids=(" not in source:
        anchor = (
            "        target_agent_ids=tuple(target_ids or ()),\n"
            "    )\n"
        )
        if anchor not in source:
            raise RuntimeError("chat turn shadow knowledge anchor not found")
        source = source.replace(
            anchor,
            "        target_agent_ids=tuple(target_ids or ()),\n"
            "        knowledge_base_ids=(\n"
            "            tuple(turn.knowledge_base_ids)\n"
            "            if turn.knowledge_base_ids is not None\n"
            "            else None\n"
            "        ),\n"
            "    )\n",
            1,
        )
    return source


def patch_cron_manager_source(source: str) -> str:
    if "budget_gate=RouterBudgetGate(" in source:
        return source
    if CRON_MAKE_JOB_OLD not in source:
        raise RuntimeError("cron manager _make_job anchor not found")
    return source.replace(CRON_MAKE_JOB_OLD, CRON_MAKE_JOB_NEW, 1)


def patch_cron_job_source(source: str) -> str:
    if "budget_gate: Any | None = None" not in source:
        source = source.replace(
            "        audit_repo: AuditRepo,\n    ) -> None:\n",
            "        audit_repo: AuditRepo,\n        budget_gate: Any | None = None,\n    ) -> None:\n",
            1,
        )
        source = source.replace(
            "        self._audit_repo = audit_repo\n",
            "        self._audit_repo = audit_repo\n        self._budget_gate = budget_gate\n",
            1,
        )
    if "budget_gate: Any | None = None,\n    ) -> CronJob:" not in source:
        source = source.replace(
            "        audit_repo: AuditRepo,\n    ) -> CronJob:\n",
            "        audit_repo: AuditRepo,\n        budget_gate: Any | None = None,\n    ) -> CronJob:\n",
            1,
        )
        source = source.replace(
            "            audit_repo=audit_repo,\n        )\n",
            "            audit_repo=audit_repo,\n            budget_gate=budget_gate,\n        )\n",
            1,
        )
    if "budget_lease.finish(" not in source:
        if CRON_JOB_OLD not in source:
            raise RuntimeError("cron job run anchor not found")
        source = source.replace(CRON_JOB_OLD, CRON_JOB_NEW, 1)
    if (
        "from typing import TYPE_CHECKING\n" not in source
        and "from typing import TYPE_CHECKING, Any\n" not in source
    ):
        raise RuntimeError("cron job typing import anchor not found")
    if "from typing import TYPE_CHECKING, Any\n" not in source:
        source = source.replace(
            "from typing import TYPE_CHECKING\n",
            "from typing import TYPE_CHECKING, Any\n",
            1,
        )
    return source


def patch_cron_delivery_source(source: str) -> str:
    if "budget_lease: Any | None = None" not in source:
        anchor = "    mcp_servers: tuple[str, ...]\n"
        if anchor not in source:
            raise RuntimeError("cron delivery command anchor not found")
        source = source.replace(
            anchor,
            anchor + "    budget_lease: Any | None = None\n",
            1,
        )
    if "nonlocal delivery_result" not in source:
        source = source.replace(
            "    async def deliver(self, command: CronDeliveryCommand) -> None:\n",
            "    async def deliver(self, command: CronDeliveryCommand) -> dict[str, Any]:\n",
            1,
        )
        source = source.replace(
            "        async def _locked() -> None:\n",
            '        delivery_result: dict[str, Any] = {"total_tokens": 0}\n\n'
            "        async def _locked() -> None:\n"
            "            nonlocal delivery_result\n",
            1,
        )
        source = source.replace(
            "                await self._deliver_text(command, session)\n"
            "            else:\n"
            "                await self._deliver_agent(command, session)\n",
            "                delivery_result = await self._deliver_text(command, session)\n"
            "            else:\n"
            "                delivery_result = await self._deliver_agent(command, session)\n",
            1,
        )
        source = source.replace(
            "        await self._gateway.run_in_session(\n"
            "            command.agent_id,\n"
            "            command.session_key,\n"
            "            _locked,\n"
            "        )\n",
            "        await self._gateway.run_in_session(\n"
            "            command.agent_id,\n"
            "            command.session_key,\n"
            "            _locked,\n"
            "        )\n"
            "        return delivery_result\n",
            1,
        )
    if 'return {"total_tokens": 0}' not in source:
        source = source.replace(
            "    async def _deliver_text(\n"
            "        self,\n"
            "        command: CronDeliveryCommand,\n"
            "        session: SessionRow,\n"
            "    ) -> None:\n",
            "    async def _deliver_text(\n"
            "        self,\n"
            "        command: CronDeliveryCommand,\n"
            "        session: SessionRow,\n"
            "    ) -> dict[str, Any]:\n",
            1,
        )
        source = source.replace(
            "        await self._publish_today_card_best_effort(command, session, command.prompt)\n"
            "        await self._notify_best_effort(session, command.agent_id, command.prompt)\n",
            "        await self._publish_today_card_best_effort(command, session, command.prompt)\n"
            "        await self._notify_best_effort(session, command.agent_id, command.prompt)\n"
            '        return {"total_tokens": 0}\n',
            1,
        )
    if "command.budget_lease.observe" not in source:
        source = source.replace(
            "                async for chunk in self._agent_manager.stream(command.agent_id, request):\n"
            "                    tracker.observe(chunk)\n"
            "                    usage.observe(chunk)\n",
            "                async for chunk in self._agent_manager.stream(command.agent_id, request):\n"
            "                    tracker.observe(chunk)\n"
            "                    usage.observe(chunk)\n"
            "                    if command.budget_lease is not None:\n"
            "                        command.budget_lease.observe(usage.usage)\n",
            1,
        )
    if "trace_id=(command.budget_lease.trace_id" not in source:
        source = source.replace(
            "                thread_id=session.thread_id,\n"
            "                usage=usage.usage,\n"
            '                source="cron",\n'
            "            )\n",
            "                thread_id=session.thread_id,\n"
            "                usage=usage.usage,\n"
            '                source="cron",\n'
            "                trace_id=(command.budget_lease.trace_id\n"
            "                          if command.budget_lease is not None else None),\n"
            "            )\n",
            1,
        )
    if "return dict(usage.usage or {})" not in source:
        source = source.replace(
            "        await self._publish_today_card_best_effort(command, session, outbound)\n"
            "        await self._notify_best_effort(session, command.agent_id, outbound)\n",
            "        await self._publish_today_card_best_effort(command, session, outbound)\n"
            "        await self._notify_best_effort(session, command.agent_id, outbound)\n"
            "        return dict(usage.usage or {})\n",
            1,
        )
    if "budget_lease=None," not in source:
        source = source.replace(
            "        mcp_servers=tuple(str(name) for name in row.mcp_servers),\n"
            "    )\n",
            "        mcp_servers=tuple(str(name) for name in row.mcp_servers),\n"
            "        budget_lease=None,\n"
            "    )\n",
            1,
        )
    if "async def _deliver_agent(" in source:
        header = source.split("async def _deliver_agent(", 1)[1].split("\n", 1)[0]
        if "-> dict[str, Any]:" not in header:
            source = source.replace(
                "    async def _deliver_agent(\n"
                "        self,\n"
                "        command: CronDeliveryCommand,\n"
                "        session: SessionRow,\n"
                "    ) -> None:\n",
                "    async def _deliver_agent(\n"
                "        self,\n"
                "        command: CronDeliveryCommand,\n"
                "        session: SessionRow,\n"
                "    ) -> dict[str, Any]:\n",
                1,
            )
    return source


def patch_usage_repo_source(source: str) -> str:
    if "trace_id: str | None = None" in source and "trace_id, task_id, run_id" in source:
        return source
    source = source.replace(
        '        source: str = "chat",\n'
        "        ts: int | None = None,\n",
        '        source: str = "chat",\n'
        "        trace_id: str | None = None,\n"
        "        task_id: str | None = None,\n"
        "        run_id: str | None = None,\n"
        "        ts: int | None = None,\n",
        1,
    )
    source = source.replace(
        "                    output_tokens, reasoning_tokens, total_tokens, model_calls, source\n"
        "                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\n",
        "                    output_tokens, reasoning_tokens, total_tokens, model_calls, source,\n"
        "                    trace_id, task_id, run_id\n"
        "                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)\n",
        1,
    )
    source = source.replace(
        "                    int(model_calls),\n"
        "                    source,\n"
        "                ),\n",
        "                    int(model_calls),\n"
        "                    source,\n"
        "                    trace_id,\n"
        "                    task_id,\n"
        "                    run_id,\n"
        "                ),\n",
        1,
    )
    return source


def patch_usage_record_source(source: str) -> str:
    if 'source: str = "chat",\n    trace_id: str | None = None,' not in source:
        source = source.replace(
            '    source: str = "chat",\n) -> None:\n',
            '    source: str = "chat",\n'
            "    trace_id: str | None = None,\n"
            "    task_id: str | None = None,\n"
            "    run_id: str | None = None,\n"
            ") -> None:\n",
            1,
        )
        source = source.replace(
            "            model_calls=max(1, _token_int(usage.get(\"model_calls\"))),\n"
            "            source=source,\n"
            "        )\n",
            "            model_calls=max(1, _token_int(usage.get(\"model_calls\"))),\n"
            "            source=source,\n"
            "            trace_id=trace_id,\n"
            "            task_id=task_id,\n"
            "            run_id=run_id,\n"
            "        )\n",
            1,
        )
    return source


def patch_proactive_scheduler_source(source: str) -> str:
    old = "                await self._care_service.run(agent_id, target_session.session_key)\n"
    new = (
        "                await self._care_service.run(\n"
        "                    agent_id,\n"
        "                    target_session.session_key,\n"
        "                    user_id=target_session.user_id,\n"
        "                )\n"
    )
    if new in source:
        return source
    if old not in source:
        raise RuntimeError("proactive scheduler run call anchor not found")
    return source.replace(old, new, 1)


def patch_proactive_service_source(source: str) -> str:
    if "budget_gate: Any | None = None" not in source:
        source = source.replace(
            "        agent_manager: AgentManager,\n"
            "        timezone: str = \"Asia/Shanghai\",\n"
            "    ) -> None:\n",
            "        agent_manager: AgentManager,\n"
            "        timezone: str = \"Asia/Shanghai\",\n"
            "        budget_gate: Any | None = None,\n"
            "        usage_repo: Any | None = None,\n"
            "    ) -> None:\n",
            1,
        )
        source = source.replace(
            "        self._timezone = timezone or \"Asia/Shanghai\"\n",
            "        self._timezone = timezone or \"Asia/Shanghai\"\n"
            "        self._budget_gate = budget_gate\n"
            "        self._usage_repo = usage_repo\n",
            1,
        )
    if "async def run(self, agent_id: str, session_key: str) -> None:" in source:
        source = source.replace(
            "    async def run(self, agent_id: str, session_key: str) -> None:\n",
            "    async def run(\n"
            "        self,\n"
            "        agent_id: str,\n"
            "        session_key: str,\n"
            "        *,\n"
            "        user_id: int | None = None,\n"
            "    ) -> None:\n",
            1,
        )
        source = source.replace(
            "        logger.info(\n"
            "            \"ProactiveCareService: starting push agent=%s session=%s\", agent_id, session_key\n"
            "        )\n",
            "        logger.info(\n"
            "            \"ProactiveCareService: starting push agent=%s session=%s\", agent_id, session_key\n"
            "        )\n"
            "        if user_id is None:\n"
            "            parts = session_key.split(\":\", 3)\n"
            "            user_id = (\n"
            "                int(parts[2])\n"
            "                if len(parts) > 2 and parts[2].isdigit()\n"
            "                else 0\n"
            "            )\n",
            1,
        )
    old_llm = """\
        # 6. Call the LLM to generate the care message
        episode_context = _format_episodes_for_prompt(pick_result.episodes)
        try:
            llm = agent.model_factory.get(agent.config.pick_default_model_ref())
            messages = [
                SystemMessage(content=system_prompt),
                HumanMessage(content=f"User's recent life events:\\n\\n{episode_context}"),
            ]
            care_text = await ainvoke_text(llm, messages, timeout=30.0)
            # Truncate to the max length
            if len(care_text) > _MAX_CARE_TEXT_LEN:
                care_text = care_text[:_MAX_CARE_TEXT_LEN]
            logger.info(
                "ProactiveCareService: LLM generated care message (%d chars) agent=%s: %s",
                len(care_text),
                agent_id,
                care_text[:50] + "..." if len(care_text) > 50 else care_text,
            )
        except Exception as exc:
            logger.warning("ProactiveCareService: LLM call failed agent=%s: %s", agent_id, exc)
            return  # do not push, do not write a record

        # 7. Push the message directly without invoking the agent.
        try:
            await self._gateway.push_text_from_session(agent_id, session_key, care_text)
        except Exception as exc:
            logger.warning(
                "ProactiveCareService: push failed agent=%s session=%s: %s",
                agent_id,
                session_key,
                exc,
            )
            return  # do not write a record
"""
    new_llm = """\
        # 6. Call the LLM to generate the care message.
        episode_context = _format_episodes_for_prompt(pick_result.episodes)
        from octop.infra.conversation.budget import estimate_tokens  # noqa: PLC0415

        estimated_input = estimate_tokens(system_prompt) + estimate_tokens(episode_context)
        budget_lease = None
        if self._budget_gate is not None and int(user_id or 0) > 0:
            try:
                budget_lease = self._budget_gate.start(
                    surface="proactive",
                    agent_id=agent_id,
                    user_id=int(user_id),
                    source_id=f"proactive:{agent_id}",
                )
            except Exception as exc:
                logger.warning(
                    "ProactiveCareService: router budget blocked agent=%s: %s",
                    agent_id,
                    exc,
                )
                return
        model_ref = agent.config.pick_default_model_ref()
        try:
            llm = agent.model_factory.get(model_ref)
            messages = [
                SystemMessage(content=system_prompt),
                HumanMessage(content=f"User's recent life events:\\n\\n{episode_context}"),
            ]
            care_text = await ainvoke_text(llm, messages, timeout=30.0)
            # Truncate to the max length
            if len(care_text) > _MAX_CARE_TEXT_LEN:
                care_text = care_text[:_MAX_CARE_TEXT_LEN]
            logger.info(
                "ProactiveCareService: LLM generated care message (%d chars) agent=%s: %s",
                len(care_text),
                agent_id,
                care_text[:50] + "..." if len(care_text) > 50 else care_text,
            )
        except Exception as exc:
            if budget_lease is not None:
                budget_lease.finish(actual_tokens=estimated_input, failed=True)
            logger.warning("ProactiveCareService: LLM call failed agent=%s: %s", agent_id, exc)
            return  # do not push, do not write a record

        actual_tokens = estimated_input + estimate_tokens(care_text)
        if self._usage_repo is not None and int(user_id or 0) > 0:
            try:
                thread_id = (
                    self._gateway.thread_registry.get_bound_thread_id(session_key) or ""
                )
                self._usage_repo.record(
                    agent_id=agent_id,
                    user_id=int(user_id),
                    thread_id=thread_id,
                    model=str(model_ref or ""),
                    input_tokens=estimated_input,
                    output_tokens=max(0, actual_tokens - estimated_input),
                    source="proactive",
                    trace_id=(budget_lease.trace_id if budget_lease is not None else None),
                )
            except Exception:
                logger.warning(
                    "ProactiveCareService: usage record failed agent=%s",
                    agent_id,
                    exc_info=True,
                )

        # 7. Push the message directly without invoking the agent.
        try:
            await self._gateway.push_text_from_session(agent_id, session_key, care_text)
        except Exception as exc:
            if budget_lease is not None:
                budget_lease.finish(actual_tokens=actual_tokens, failed=True)
            logger.warning(
                "ProactiveCareService: push failed agent=%s session=%s: %s",
                agent_id,
                session_key,
                exc,
            )
            return  # do not write a record
        if budget_lease is not None:
            budget_lease.finish(actual_tokens=actual_tokens, failed=False)
"""
    if old_llm not in source:
        if "router budget blocked" in source:
            return source
        raise RuntimeError("proactive service LLM block anchor not found")
    return source.replace(old_llm, new_llm, 1)


def patch_server_source(source: str) -> str:
    if "RouterBudgetGate(" not in source:
        anchor = (
            "        from octop.infra.cron.delivery import CronDeliveryService  # noqa: PLC0415\n"
        )
        if anchor not in source:
            raise RuntimeError("server cron delivery import anchor not found")
        source = source.replace(
            anchor,
            anchor
            + "        from octop.infra.conversation.budget import RouterBudgetGate  # noqa: PLC0415\n",
            1,
        )
        old = """\
        care_service = ProactiveCareService(
            gateway=gateway,
            care_push_repo=self.services.repos.care_push_repo,
            agent_manager=registry,
            timezone=config.default_timezone,
        )
"""
        new = """\
        router_budget = RouterBudgetGate(
            self.services.db,
            self.services.settings_repo,
            timezone_name=config.default_timezone,
        )
        care_service = ProactiveCareService(
            gateway=gateway,
            care_push_repo=self.services.repos.care_push_repo,
            agent_manager=registry,
            timezone=config.default_timezone,
            budget_gate=router_budget,
            usage_repo=self.services.repos.usage_repo,
        )
"""
        if old not in source:
            raise RuntimeError("server proactive service anchor not found")
        source = source.replace(old, new, 1)
    return source


def _write_if_changed(source: Path, target: Path) -> bool:
    if target.exists() and target.read_bytes() == source.read_bytes():
        return False
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    return True


def main() -> None:
    site = octop_site()
    app_path = site / "api/app.py"
    turn_path = site / "api/routers/chat/turn.py"
    cron_manager_path = site / "infra/cron/manager.py"
    cron_job_path = site / "infra/cron/job.py"
    cron_delivery_path = site / "infra/cron/delivery.py"
    usage_repo_path = site / "infra/db/repos/usage.py"
    usage_record_path = site / "infra/gateway/process/usage_record.py"
    proactive_scheduler_path = site / "infra/proactive/scheduler.py"
    proactive_service_path = site / "infra/proactive/service.py"
    server_path = site / "infra/server.py"

    source_updates = {
        app_path: patch_source(app_path.read_text(encoding="utf-8")),
        turn_path: patch_chat_turn_source(turn_path.read_text(encoding="utf-8")),
        cron_manager_path: patch_cron_manager_source(
            cron_manager_path.read_text(encoding="utf-8")
        ),
        cron_job_path: patch_cron_job_source(cron_job_path.read_text(encoding="utf-8")),
        cron_delivery_path: patch_cron_delivery_source(
            cron_delivery_path.read_text(encoding="utf-8")
        ),
        usage_repo_path: patch_usage_repo_source(
            usage_repo_path.read_text(encoding="utf-8")
        ),
        usage_record_path: patch_usage_record_source(
            usage_record_path.read_text(encoding="utf-8")
        ),
        proactive_scheduler_path: patch_proactive_scheduler_source(
            proactive_scheduler_path.read_text(encoding="utf-8")
        ),
        proactive_service_path: patch_proactive_service_source(
            proactive_service_path.read_text(encoding="utf-8")
        ),
        server_path: patch_server_source(server_path.read_text(encoding="utf-8")),
    }

    service_target = site / "infra/conversation/service.py"
    context_target = site / "infra/conversation/context.py"
    budget_target = site / "infra/conversation/budget.py"
    shadow_target = site / "infra/conversation/shadow.py"
    router_target = site / "api/routers/conversation_router.py"
    init_target = site / "infra/conversation/__init__.py"

    service_source = PACK_DIR / "router_phase0_service.py"
    context_source = PACK_DIR / "router_phase0_context.py"
    budget_source = PACK_DIR / "router_phase0_budget.py"
    shadow_source = PACK_DIR / "router_phase0_shadow.py"
    router_source = PACK_DIR / "router_phase0_api.py"
    service_changed = not service_target.exists() or (
        service_target.read_bytes() != service_source.read_bytes()
    )
    router_changed = not router_target.exists() or (
        router_target.read_bytes() != router_source.read_bytes()
    )
    context_changed = not context_target.exists() or (
        context_target.read_bytes() != context_source.read_bytes()
    )
    budget_changed = not budget_target.exists() or (
        budget_target.read_bytes() != budget_source.read_bytes()
    )
    shadow_changed = not shadow_target.exists() or (
        shadow_target.read_bytes() != shadow_source.read_bytes()
    )
    changed = (
        any(patched != original for patched, original in (
            (source_updates[path], path.read_text(encoding="utf-8"))
            for path in source_updates
        ))
        or service_changed
        or context_changed
        or budget_changed
        or shadow_changed
        or router_changed
        or not init_target.exists()
    )

    if changed:
        backup = backups_dir() / f"router-phase0-{time.strftime('%Y%m%d-%H%M%S')}"
        backup.mkdir(parents=True, exist_ok=True)
        for path, patched in source_updates.items():
            if patched != path.read_text(encoding="utf-8"):
                shutil.copy2(path, backup / path.name)
        if service_target.exists() and service_changed:
            shutil.copy2(service_target, backup / "conversation-service.py")
        if context_target.exists() and context_changed:
            shutil.copy2(context_target, backup / "conversation-context.py")
        if budget_target.exists() and budget_changed:
            shutil.copy2(budget_target, backup / "conversation-budget.py")
        if shadow_target.exists() and shadow_changed:
            shutil.copy2(shadow_target, backup / "conversation-shadow.py")
        if router_target.exists() and router_changed:
            shutil.copy2(router_target, backup / "conversation-router.py")

    if service_changed:
        service_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(service_source, service_target)
    if not init_target.exists():
        init_target.parent.mkdir(parents=True, exist_ok=True)
        init_target.write_text('"""Main-conversation domain services."""\n', encoding="utf-8")
    if context_changed:
        context_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(context_source, context_target)
    if budget_changed:
        budget_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(budget_source, budget_target)
    if shadow_changed:
        shadow_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(shadow_source, shadow_target)
    if router_changed:
        router_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(router_source, router_target)
    for path, patched in source_updates.items():
        if patched != path.read_text(encoding="utf-8"):
            path.write_text(patched, encoding="utf-8")
    print(router_target)


if __name__ == "__main__":
    main()
