#!/usr/bin/env python3
"""Validate Router Phase 0 against a running, isolated candidate server."""

from __future__ import annotations

import argparse
import asyncio
import json
import sqlite3
import sys
import time
import urllib.error
import urllib.request
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any

import websockets

from octop.infra.conversation.service import ConversationService, NotFoundError


class ValidationFailure(RuntimeError):
    pass


class SqliteDB:
    dialect = "sqlite"

    def __init__(self, path: Path) -> None:
        self.path = path

    @contextmanager
    def connect(self):
        connection = sqlite3.connect(self.path)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        try:
            yield connection
        finally:
            connection.close()

    @contextmanager
    def transaction(self):
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                yield connection
                connection.execute("COMMIT")
            except Exception:
                connection.execute("ROLLBACK")
                raise


def request(
    base_url: str,
    path: str,
    *,
    token: str | None = None,
    body: dict[str, Any] | None = None,
    timeout: int = 60,
) -> Any:
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(base_url + path, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            payload = response.read()
            if not payload:
                return None
            return json.loads(payload)
    except urllib.error.HTTPError as exc:
        payload = exc.read().decode("utf-8", errors="replace")
        raise ValidationFailure(f"{path}: HTTP {exc.code}: {payload[:500]}") from exc
    except urllib.error.URLError as exc:
        raise ValidationFailure(f"{path}: {exc}") from exc


def login(base_url: str, credentials: dict[str, str]) -> tuple[str, dict[str, Any]]:
    payload = request(
        base_url,
        "/api/auth/login",
        body={"username": credentials["username"], "password": credentials["password"]},
    )
    token = str(payload.get("access_token") or "")
    if not token:
        raise ValidationFailure("login did not return an access token")
    return token, payload


def find_active_dashboard(threads: list[dict[str, Any]]) -> dict[str, Any]:
    dashboard = [
        item
        for item in threads
        if item.get("channel_type") == "dashboard"
    ]
    active = [item for item in dashboard if item.get("is_active")]
    if not active:
        raise ValidationFailure("candidate has no active dashboard thread")
    return max(active, key=lambda item: int(item.get("last_active", 0) or 0))


async def run_legacy_chat(
    base_url: str,
    token: str,
    agent_id: str,
    thread_id: str,
    *,
    timeout: int,
) -> dict[str, Any]:
    ws_url = (
        base_url.replace("http://", "ws://").replace("https://", "wss://")
        + f"/api/agents/{agent_id}/chat/ws?token={token}"
    )
    client_pending_id = f"router-phase0-{uuid.uuid4().hex}"
    started = False
    async with websockets.connect(ws_url, open_timeout=30, close_timeout=5) as socket:
        await socket.send(json.dumps({"type": "subscribe", "thread_id": thread_id}))
        await socket.send(
            json.dumps(
                {
                    "type": "user_turn",
                    "text": "请只回复 OK，不要调用任何工具。",
                    "thread_id": thread_id,
                    "conversation_mode": "ask",
                    "delivery": "auto",
                    "client_pending_id": client_pending_id,
                }
            )
        )
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            raw = await asyncio.wait_for(socket.recv(), timeout=max(1.0, deadline - time.monotonic()))
            event = json.loads(raw)
            kind = event.get("type")
            if kind == "turn.started":
                started = True
            if kind == "error":
                raise ValidationFailure(f"legacy chat error: {event}")
            if kind == "turn.ended":
                return {
                    "started": started,
                    "failed": bool(event.get("failed")),
                    "hitl_paused": bool(event.get("hitl_paused")),
                    "thread_id": event.get("thread_id") or thread_id,
                }
    raise ValidationFailure("legacy chat did not finish before timeout")


def verify_usage_columns(db_path: Path, *, agent_id: str, user_id: int, trace_id: str) -> dict[str, Any]:
    with sqlite3.connect(db_path) as connection:
        columns = {
            str(row[1])
            for row in connection.execute("PRAGMA table_info(usage_log)")
        }
        missing = {"trace_id", "task_id", "run_id"} - columns
        if missing:
            raise ValidationFailure(f"usage_log missing Router columns: {sorted(missing)}")
        cursor = connection.execute(
            "INSERT INTO usage_log("
            "ts,agent_id,user_id,thread_id,model,input_tokens,output_tokens,"
            "total_tokens,source,trace_id,task_id,run_id"
            ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            (
                int(time.time()),
                agent_id,
                user_id,
                "",
                "phase0-validation",
                1,
                1,
                2,
                "validation",
                trace_id,
                "task-phase0-validation",
                "run-phase0-validation",
            ),
        )
        row_id = int(cursor.lastrowid)
        row = connection.execute(
            "SELECT trace_id,task_id,run_id,total_tokens FROM usage_log WHERE id=?",
            (row_id,),
        ).fetchone()
        connection.execute("DELETE FROM usage_log WHERE id=?", (row_id,))
        connection.commit()
    if row is None:
        raise ValidationFailure("synthetic usage row was not persisted")
    return {
        "trace_id_round_trip": row[0] == trace_id,
        "task_id_round_trip": row[1] == "task-phase0-validation",
        "run_id_round_trip": row[2] == "run-phase0-validation",
        "total_tokens": int(row[3]),
    }


def verify_trace_ownership(
    db_path: Path,
    *,
    agent_id: str,
    user_id: int,
    trace_id: str,
) -> bool:
    service = ConversationService(SqliteDB(db_path))
    try:
        service.get_trace(agent_id=agent_id, user_id=user_id + 1, trace_id=trace_id)
    except NotFoundError:
        return True
    return False


def run(base_url: str, credentials_path: Path, db_path: Path) -> dict[str, Any]:
    credentials = json.loads(credentials_path.read_text(encoding="utf-8"))
    token, _login_payload = login(base_url, credentials)
    agent_id = str(credentials["agent_id"])

    agents = request(base_url, "/api/agents", token=token)
    if not any(item.get("agent_id") == agent_id for item in agents):
        raise ValidationFailure("candidate credentials agent is not visible")

    threads = request(base_url, f"/api/agents/{agent_id}/threads?limit=50", token=token)
    active = find_active_dashboard(threads)
    active_thread_id = str(active["thread_id"])
    history_before = request(
        base_url,
        f"/api/agents/{agent_id}/threads/{active_thread_id}/history?limit=20",
        token=token,
    )

    conversation = request(
        base_url,
        f"/api/agents/{agent_id}/main-conversation",
        token=token,
    )
    if conversation.get("router_thread_id") != active_thread_id:
        raise ValidationFailure(
            "main conversation did not bind to the active dashboard thread: "
            f"{conversation.get('router_thread_id')} != {active_thread_id}"
        )
    conversation_id = str(conversation["conversation_id"])

    tasks = request(base_url, f"/api/agents/{agent_id}/tasks?limit=20", token=token)
    if not isinstance(tasks, list):
        raise ValidationFailure("tasks endpoint did not return a list")
    events = request(
        base_url,
        f"/api/agents/{agent_id}/main-conversation/events?after_seq=0&limit=100",
        token=token,
    )
    if not isinstance(events, list):
        raise ValidationFailure("main conversation events did not return a list")

    chat = asyncio.run(
        run_legacy_chat(
            base_url,
            token,
            agent_id,
            active_thread_id,
            timeout=180,
        )
    )
    if not chat["started"] or chat["failed"]:
        raise ValidationFailure(f"legacy chat failed: {chat}")

    history_after = request(
        base_url,
        f"/api/agents/{agent_id}/threads/{active_thread_id}/history?limit=20",
        token=token,
    )
    if len(history_after.get("messages", [])) <= len(history_before.get("messages", [])):
        raise ValidationFailure("legacy thread history did not grow after chat")

    shadow_event: dict[str, Any] | None = None
    for _ in range(20):
        time.sleep(1)
        events = request(
            base_url,
            f"/api/agents/{agent_id}/main-conversation/events?after_seq=0&limit=200",
            token=token,
        )
        candidates = [
            event
            for event in events
            if event.get("event_type") == "router.shadow.decision"
            and event.get("conversation_id") == conversation_id
        ]
        if candidates:
            shadow_event = candidates[-1]
            break
    if shadow_event is None:
        raise ValidationFailure("shadow router decision was not recorded")

    trace_id = str(shadow_event["trace_id"])
    trace = request(
        base_url,
        f"/api/agents/{agent_id}/traces/{trace_id}?include_payload=false&limit=100",
        token=token,
    )
    event_types = {str(item.get("event_type")) for item in trace.get("events", [])}
    required_events = {
        "router.received",
        "context.pack.compiled",
        "router.shadow.decision",
    }
    if not required_events.issubset(event_types):
        raise ValidationFailure(f"trace is missing shadow events: {sorted(required_events - event_types)}")
    if not trace.get("context_packs"):
        raise ValidationFailure("trace did not include a Context Pack manifest")

    with sqlite3.connect(db_path) as connection:
        user_id = connection.execute(
            "SELECT id FROM users WHERE username=?",
            (credentials["username"],),
        ).fetchone()
    if user_id is None:
        raise ValidationFailure("candidate test user was not found")

    return {
        "agent_id": agent_id,
        "conversation_id": conversation_id,
        "active_dashboard_thread_id": active_thread_id,
        "main_conversation_bound": True,
        "legacy_history_ok": True,
        "legacy_chat_ok": True,
        "tasks_api_ok": True,
        "events_api_ok": True,
        "shadow_decision": shadow_event.get("status"),
        "trace_event_types": sorted(event_types),
        "context_pack_count": len(trace["context_packs"]),
        "trace_payload_hidden": all(
            item.get("payload") is None for item in trace.get("events", [])
        ),
        "trace_ownership_enforced": verify_trace_ownership(
            db_path,
            agent_id=agent_id,
            user_id=int(user_id[0]),
            trace_id=trace_id,
        ),
        "usage_columns": verify_usage_columns(
            db_path,
            agent_id=agent_id,
            user_id=int(user_id[0]),
            trace_id=trace_id,
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18088")
    parser.add_argument("--credentials", type=Path, required=True)
    parser.add_argument("--db", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run(args.base_url.rstrip("/"), args.credentials, args.db)
    except (ValidationFailure, OSError, sqlite3.Error) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
