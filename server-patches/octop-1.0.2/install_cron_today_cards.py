"""Link scheduled-task results to stable Today cards."""

from __future__ import annotations

import shutil
from pathlib import Path


site = Path("/opt/octop/venv/lib/python3.12/site-packages/octop")
delivery_path = site / "infra/cron/delivery.py"
automatic_path = site / "infra/today/automatic.py"
cards_path = site / "infra/today/cards.py"
mobile_ingest_path = site / "api/routers/mobile_ingest.py"
runtime_source = Path(__file__).with_name("cron_today_cards.py")
runtime_target = site / "infra/today/cron_cards.py"
health_source = Path(__file__).with_name("health_today_cards.py")
health_target = site / "infra/today/health_cards.py"

shutil.copyfile(runtime_source, runtime_target)
shutil.copyfile(health_source, health_target)

source = delivery_path.read_text()
time_anchor = "import logging\n"
if "\nimport time\n" not in source:
    if time_anchor not in source:
        raise SystemExit("Octop 1.0.2 cron delivery time import anchor not found")
    source = source.replace(time_anchor, "import logging\nimport time\n", 1)
card_method = '''    async def _publish_today_card_best_effort(
        self,
        command: CronDeliveryCommand,
        session: SessionRow,
        text: str,
    ) -> None:
        """Mirror a successful run into Today and wake foreground mobile clients."""
        from octop.infra.today.cron_cards import publish_cron_task_card  # noqa: PLC0415

        try:
            result = publish_cron_task_card(
                self._repos.db,
                cron_id=command.cron_id,
                task_name=command.cron_name,
                prompt=command.prompt,
                agent_id=command.agent_id,
                user_id=session.user_id,
                thread_id=session.thread_id,
                text=text,
                last_run_at=int(time.time()),
                status="ok",
                mark_unread=True,
            )
        except Exception:
            logger.warning(
                "failed to publish Today card for cron=%s",
                command.cron_id,
                exc_info=True,
            )
            return
        if not result["notify"]:
            return
        try:
            await self._gateway.ws_hub.push_to_user(
                session.user_id,
                {
                    "type": "dashboard_push",
                    "event": "today.card_changed",
                    "agent_id": command.agent_id,
                    "card_id": result["card_id"],
                    "priority": "normal",
                    "text": f"「{command.cron_name}」已更新到 Today",
                },
            )
        except Exception:
            logger.warning(
                "failed to push Today card update for cron=%s",
                command.cron_id,
                exc_info=True,
            )

'''
method_anchor = '''    async def _notify_best_effort(
'''
if "_publish_today_card_best_effort" not in source:
    if method_anchor not in source:
        raise SystemExit("Octop 1.0.2 cron Today method anchor not found")
    source = source.replace(method_anchor, card_method + method_anchor, 1)

text_anchor = '''        await self._gateway.push_session_text(
            session,
            command.prompt,
            title_source=title_source,
        )
        await self._notify_best_effort(session, command.agent_id, command.prompt)
'''
text_fixed = '''        await self._gateway.push_session_text(
            session,
            command.prompt,
            title_source=title_source,
        )
        await self._publish_today_card_best_effort(command, session, command.prompt)
        await self._notify_best_effort(session, command.agent_id, command.prompt)
'''
if text_fixed not in source:
    if text_anchor not in source:
        raise SystemExit("Octop 1.0.2 cron text delivery anchor not found")
    source = source.replace(text_anchor, text_fixed, 1)

agent_anchor = '''        await self._gateway.push_session_text(
            session,
            outbound,
            title_source=command.prompt,
        )
        await self._notify_best_effort(session, command.agent_id, outbound)
'''
agent_fixed = '''        await self._gateway.push_session_text(
            session,
            outbound,
            title_source=command.prompt,
        )
        await self._publish_today_card_best_effort(command, session, outbound)
        await self._notify_best_effort(session, command.agent_id, outbound)
'''
if agent_fixed not in source:
    if agent_anchor not in source:
        raise SystemExit("Octop 1.0.2 cron agent delivery anchor not found")
    source = source.replace(agent_anchor, agent_fixed, 1)
delivery_path.write_text(source)

source = automatic_path.read_text()
import_anchor = "from octop.infra.db.repos.secrets import SecretRepo\n"
import_new = (
    import_anchor
    + "from octop.infra.today.cron_cards import has_cron_card_kind\n"
)
if "from octop.infra.today.cron_cards import has_cron_card_kind" not in source:
    source = source.replace(import_anchor, import_new, 1)

helper_anchor = '''def _dismiss_previous(
'''
dismiss_helper = '''def _dismiss_all_automatic(
    conn: Any,
    *,
    agent_id: str,
    user_id: int,
    kind: str,
    now: int,
) -> None:
    conn.execute(
        "UPDATE today_cards SET lifecycle = 'dismissed', snoozed_until = NULL, updated_at = ? "
        "WHERE agent_id = ? AND user_id = ? AND dedupe_key LIKE ? "
        "AND lifecycle != 'dismissed'",
        (now, agent_id, user_id, f"auto:{kind}:%"),
    )


'''
if "_dismiss_all_automatic" not in source:
    if helper_anchor not in source:
        raise SystemExit("Octop 1.0.2 automatic dismiss helper anchor not found")
    source = source.replace(helper_anchor, dismiss_helper + helper_anchor, 1)

existing_anchor = '''        existing = _existing_auto_cards(db, agent_id=agent_id, user_id=user_id)
        conversation = _conversation_card(
'''
existing_fixed = '''        existing = _existing_auto_cards(db, agent_id=agent_id, user_id=user_id)
        mail_owned = has_cron_card_kind(
            db,
            agent_id=agent_id,
            user_id=user_id,
            kind="inbox",
        )
        news_owned = has_cron_card_kind(
            db,
            agent_id=agent_id,
            user_id=user_id,
            kind="briefing",
        )
        conversation = _conversation_card(
'''
if "mail_owned = has_cron_card_kind(" not in source:
    if existing_anchor not in source:
        raise SystemExit("Octop 1.0.2 automatic existing-card anchor not found")
    source = source.replace(existing_anchor, existing_fixed, 1)

mail_old = '''            _SourceResult(cards=(), loaded=False)
            if _is_fresh(existing, kind="mail", now=now)
            else await _load_mail(db, user_id=user_id, now=now)
'''
mail_new = '''            _SourceResult(cards=(), loaded=False)
            if mail_owned or _is_fresh(existing, kind="mail", now=now)
            else await _load_mail(db, user_id=user_id, now=now)
'''
if mail_new not in source:
    if mail_old not in source:
        raise SystemExit("Octop 1.0.2 automatic mail anchor not found")
    source = source.replace(mail_old, mail_new, 1)

news_old = '''            _SourceResult(cards=(), loaded=False)
            if _is_fresh(existing, kind="news", now=now)
            else await _load_news(
'''
news_new = '''            _SourceResult(cards=(), loaded=False)
            if news_owned or _is_fresh(existing, kind="news", now=now)
            else await _load_news(
'''
if news_new not in source:
    if news_old not in source:
        raise SystemExit("Octop 1.0.2 automatic news anchor not found")
    source = source.replace(news_old, news_new, 1)

transaction_anchor = '''        with db.transaction() as conn:
            for kind, result in results.items():
'''
transaction_fixed = '''        with db.transaction() as conn:
            if mail_owned:
                _dismiss_all_automatic(
                    conn,
                    agent_id=agent_id,
                    user_id=user_id,
                    kind="mail",
                    now=now,
                )
            if news_owned:
                _dismiss_all_automatic(
                    conn,
                    agent_id=agent_id,
                    user_id=user_id,
                    kind="news",
                    now=now,
                )
            for kind, result in results.items():
'''
if "if mail_owned:\n                _dismiss_all_automatic(" not in source:
    if transaction_anchor not in source:
        raise SystemExit("Octop 1.0.2 automatic transaction anchor not found")
    source = source.replace(transaction_anchor, transaction_fixed, 1)
automatic_path.write_text(source)

source = mobile_ingest_path.read_text()
source_type_old = (
    '    source_type: Literal["share_inbox", "system_calendar", "health_connect"]\n'
)
source_type_new = (
    '    source_type: Literal[\n'
    '        "share_inbox", "system_calendar", "health_connect", "xiaomi_health"\n'
    '    ]\n'
)
if source_type_new not in source:
    if source_type_old not in source:
        raise SystemExit("Octop 1.0.2 mobile ingest source type anchor not found")
    source = source.replace(source_type_old, source_type_new, 1)

import_anchor = "from octop.infra.today.automatic import refresh_automatic_today_cards\n"
import_new = (
    import_anchor
    + "from octop.infra.today.cron_cards import sync_completed_cron_today_cards\n"
)
if "from octop.infra.today.cron_cards import sync_completed_cron_today_cards" not in source:
    if import_anchor not in source:
        raise SystemExit("Octop 1.0.2 mobile ingest import anchor not found")
    source = source.replace(import_anchor, import_new, 1)
health_import = "from octop.infra.today.health_cards import publish_health_today_cards\n"
if health_import not in source:
    if import_anchor not in source:
        raise SystemExit("Octop 1.0.2 mobile ingest import anchor not found")
    source = source.replace(import_anchor, import_anchor + health_import, 1)

sync_anchor = '''    await refresh_automatic_today_cards(
        server.services.db,
        agent_id=agent_id,
        user_id=user.id,
        now=now,
    )
'''
sync_without_health = '''    sync_completed_cron_today_cards(
        server.services.db,
        agent_id=agent_id,
        user_id=user.id,
    )
    await refresh_automatic_today_cards(
        server.services.db,
        agent_id=agent_id,
        user_id=user.id,
        now=now,
    )
'''
if sync_without_health not in source:
    if sync_anchor not in source:
        raise SystemExit("Octop 1.0.2 mobile ingest Today anchor not found")
    source = source.replace(sync_anchor, sync_without_health, 1)

health_call = '''    publish_health_today_cards(
        server.services.db,
        agent_id=agent_id,
        user_id=user.id,
        now=now,
    )
'''
if health_call not in source:
    if sync_without_health not in source:
        raise SystemExit("Octop 1.0.2 mobile ingest health card anchor not found")
    source = source.replace(sync_without_health, sync_without_health + health_call, 1)

evidence_order_old = '''        "ORDER BY created_at DESC",
'''
evidence_order_new = '''        "ORDER BY created_at DESC, occurred_at DESC",
'''
if evidence_order_new not in source:
    if evidence_order_old not in source:
        raise SystemExit("Octop 1.0.2 mobile ingest evidence order anchor not found")
    source = source.replace(evidence_order_old, evidence_order_new, 1)
mobile_ingest_path.write_text(source)

source = cards_path.read_text()
kind_old = (
    'TodayCardKind = Literal["briefing", "research", "task", "calendar", '
    '"health", "inbox", "review"]'
)
kind_new = (
    'TodayCardKind = Literal["briefing", "research", "task", "calendar", '
    '"health", "inbox", "review", "signal"]'
)
if kind_new not in source:
    if kind_old not in source:
        raise SystemExit("Octop 1.0.2 Today card kind anchor not found")
    source = source.replace(kind_old, kind_new, 1)

summary_old = "summary: str = Field(min_length=1, max_length=1000)"
summary_new = "summary: str = Field(min_length=1, max_length=5200)"
if summary_new not in source:
    if summary_old not in source:
        raise SystemExit("Octop 1.0.2 Today summary limit anchor not found")
    source = source.replace(summary_old, summary_new, 1)

old_limit = "_MAX_EVIDENCE = 20"
new_limit = "_MAX_EVIDENCE = 100"
if new_limit not in source:
    if old_limit not in source:
        raise SystemExit("Octop 1.0.2 Today evidence limit anchor not found")
    source = source.replace(old_limit, new_limit, 1)
cards_path.write_text(source)
