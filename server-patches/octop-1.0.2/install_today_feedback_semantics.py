"""Clarify Today feedback outcomes and apply frequency changes to every card type."""

from __future__ import annotations

from pathlib import Path

from patch_paths import octop_site


OLD_BRANCH = '''        elif body.feedback_type in {"too_frequent", "irrelevant"}:
            for monitor in linked_monitors:
                level = int(monitor["feedback_backoff_level"] or 0) + 1
                backoff_until = max(
                    int(monitor["delivery_backoff_until"] or 0),
                    now + feedback_backoff_seconds(body.feedback_type, level=level),
                )
                conn.execute(
                    "UPDATE interest_monitors SET delivery_backoff_until = ?, "
                    "feedback_backoff_reason = ?, feedback_backoff_level = ?, updated_at = ? "
                    "WHERE monitor_id = ?",
                    (backoff_until, body.feedback_type, level, now, monitor["monitor_id"]),
                )
'''

SEMANTICS_ONLY_BRANCH = '''        elif body.feedback_type == "too_frequent":
            for monitor in linked_monitors:
                level = int(monitor["feedback_backoff_level"] or 0) + 1
                backoff_until = max(
                    int(monitor["delivery_backoff_until"] or 0),
                    now + feedback_backoff_seconds(body.feedback_type, level=level),
                )
                conn.execute(
                    "UPDATE interest_monitors SET delivery_backoff_until = ?, "
                    "feedback_backoff_reason = ?, feedback_backoff_level = ?, updated_at = ? "
                    "WHERE monitor_id = ?",
                    (backoff_until, body.feedback_type, level, now, monitor["monitor_id"]),
                )
        elif body.feedback_type == "irrelevant":
            for monitor in linked_monitors:
                level = int(monitor["feedback_backoff_level"] or 0) + 1
                conn.execute(
                    "UPDATE interest_monitors SET enabled = 0, next_run_at = NULL, "
                    "delivery_backoff_until = NULL, feedback_backoff_reason = ?, "
                    "feedback_backoff_level = ?, updated_at = ? "
                    "WHERE monitor_id = ?",
                    ("irrelevant", level, now, monitor["monitor_id"]),
                )
'''

BACKOFF_BRANCH = '''        elif body.feedback_type == "too_frequent":
            for monitor in linked_monitors:
                level = int(monitor["feedback_backoff_level"] or 0) + 1
                backoff_until = max(
                    int(monitor["delivery_backoff_until"] or 0),
                    now + feedback_backoff_seconds(body.feedback_type, level=level),
                )
                conn.execute(
                    "UPDATE interest_monitors SET delivery_backoff_until = ?, "
                    "feedback_backoff_reason = ?, feedback_backoff_level = ?, updated_at = ? "
                    "WHERE monitor_id = ?",
                    (backoff_until, body.feedback_type, level, now, monitor["monitor_id"]),
                )
            feedback_count = conn.execute(
                "SELECT COUNT(*) FROM today_card_feedback "
                "WHERE agent_id = ? AND card_id = ? AND user_id = ? "
                "AND feedback_type = 'too_frequent'",
                (agent_id, card_id, user.id),
            ).fetchone()[0]
            card_backoff_until = now + feedback_backoff_seconds(
                body.feedback_type,
                level=min(max(int(feedback_count), 1), 2),
            )
            conn.execute(
                "UPDATE today_cards SET lifecycle = 'snoozed', snoozed_until = ?, "
                "updated_at = ? WHERE agent_id = ? AND card_id = ?",
                (card_backoff_until, now, agent_id, card_id),
            )
        elif body.feedback_type == "irrelevant":
            for monitor in linked_monitors:
                level = int(monitor["feedback_backoff_level"] or 0) + 1
                conn.execute(
                    "UPDATE interest_monitors SET enabled = 0, next_run_at = NULL, "
                    "delivery_backoff_until = NULL, feedback_backoff_reason = ?, "
                    "feedback_backoff_level = ?, updated_at = ? "
                    "WHERE monitor_id = ?",
                    ("irrelevant", level, now, monitor["monitor_id"]),
                )
'''

RESPONSE_OLD = '''        if body.feedback_type == "irrelevant":
            conn.execute(
                "UPDATE today_cards SET lifecycle = 'dismissed', snoozed_until = NULL, updated_at = ? "
                "WHERE agent_id = ? AND card_id = ?",
                (now, agent_id, card_id),
            )
            row = conn.execute(
                "SELECT * FROM today_cards WHERE agent_id = ? AND card_id = ?",
                (agent_id, card_id),
            ).fetchone()
            assert row is not None
        return _cards_from_rows(conn, agent_id, [row])[0]
'''

RESPONSE_NEW = '''        if body.feedback_type in {"too_frequent", "irrelevant"}:
            if body.feedback_type == "irrelevant":
                conn.execute(
                    "UPDATE today_cards SET lifecycle = 'dismissed', snoozed_until = NULL, "
                    "updated_at = ? WHERE agent_id = ? AND card_id = ?",
                    (now, agent_id, card_id),
                )
            row = conn.execute(
                "SELECT * FROM today_cards WHERE agent_id = ? AND card_id = ?",
                (agent_id, card_id),
            ).fetchone()
            assert row is not None
        return _cards_from_rows(conn, agent_id, [row])[0]
'''

PUBLISHER_OLD = '''                lifecycle = (
                    "dismissed"
                    if _text(existing["lifecycle"]) == "dismissed"
                    else "unread"
                )
                conn.execute(
                    "UPDATE today_cards SET kind = ?, title = ?, summary = ?, why_now = ?, "
                    "confidence = ?, lifecycle = ?, occurred_at = ?, snoozed_until = NULL, "
                    "action_prompt = ?, updated_at = ?, goal_id = ?, risk_level = ? "
                    "WHERE agent_id = ? AND card_id = ?",
                    (
                        draft.kind,
                        draft.title,
                        draft.summary,
                        draft.why_now,
                        draft.confidence,
                        lifecycle,
                        occurred_at,
                        action_prompt,
                        now,
                        draft.goal_id,
                        draft.risk_level,
                        agent_id,
                        card_id,
                    ),
                )
'''

PUBLISHER_NEW = '''                existing_lifecycle = _text(existing["lifecycle"])
                existing_snoozed_until = int(existing["snoozed_until"] or 0)
                preserve_snooze = (
                    existing_lifecycle == "snoozed" and existing_snoozed_until > now
                )
                lifecycle = (
                    "dismissed"
                    if existing_lifecycle == "dismissed"
                    else "snoozed" if preserve_snooze else "unread"
                )
                snoozed_until = existing_snoozed_until if preserve_snooze else None
                conn.execute(
                    "UPDATE today_cards SET kind = ?, title = ?, summary = ?, why_now = ?, "
                    "confidence = ?, lifecycle = ?, occurred_at = ?, snoozed_until = ?, "
                    "action_prompt = ?, updated_at = ?, goal_id = ?, risk_level = ? "
                    "WHERE agent_id = ? AND card_id = ?",
                    (
                        draft.kind,
                        draft.title,
                        draft.summary,
                        draft.why_now,
                        draft.confidence,
                        lifecycle,
                        occurred_at,
                        snoozed_until,
                        action_prompt,
                        now,
                        draft.goal_id,
                        draft.risk_level,
                        agent_id,
                        card_id,
                    ),
                )
'''

NEW_BRANCH = BACKOFF_BRANCH


def patch_source(source: str) -> str:
    if OLD_BRANCH in source:
        source = source.replace(OLD_BRANCH, SEMANTICS_ONLY_BRANCH, 1)
    if SEMANTICS_ONLY_BRANCH in source:
        source = source.replace(SEMANTICS_ONLY_BRANCH, BACKOFF_BRANCH, 1)
    if BACKOFF_BRANCH not in source:
        raise SystemExit("Octop 1.0.2 Today feedback branch anchor not found")
    if RESPONSE_OLD in source:
        source = source.replace(RESPONSE_OLD, RESPONSE_NEW, 1)
    if RESPONSE_NEW not in source:
        raise SystemExit("Octop 1.0.2 Today feedback response anchor not found")
    return source


def patch_publisher(source: str) -> str:
    if PUBLISHER_NEW in source:
        return source
    if PUBLISHER_OLD not in source:
        raise SystemExit("Octop 1.0.2 Today card publisher anchor not found")
    return source.replace(PUBLISHER_OLD, PUBLISHER_NEW, 1)


def main() -> None:
    site = octop_site()
    ingest_path = site / "api/routers/mobile_ingest.py"
    publisher_path = site / "infra/today/cards.py"

    ingest_source = ingest_path.read_text()
    ingest_patched = patch_source(ingest_source)
    if ingest_patched != ingest_source:
        ingest_path.write_text(ingest_patched)

    publisher_source = publisher_path.read_text()
    publisher_patched = patch_publisher(publisher_source)
    if publisher_patched != publisher_source:
        publisher_path.write_text(publisher_patched)


if __name__ == "__main__":
    main()
