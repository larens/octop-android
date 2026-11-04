#!/usr/bin/env python3
"""Exercise the mobile-critical API surface against a candidate server."""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


class SmokeFailure(RuntimeError):
    pass


def _request(
    base_url: str,
    path: str,
    *,
    token: str | None = None,
    body: dict[str, Any] | None = None,
    allowed_errors: frozenset[int] = frozenset(),
    timeout: int = 60,
) -> tuple[int, Any]:
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(base_url + path, data=data, headers=headers)
    try:
        # The update-status probe may wait on PyPI on a cold candidate cache.
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read()
            return response.status, json.loads(payload) if payload else None
    except urllib.error.HTTPError as exc:
        payload = exc.read().decode("utf-8", errors="replace")
        if exc.code in allowed_errors:
            try:
                return exc.code, json.loads(payload) if payload else None
            except json.JSONDecodeError:
                return exc.code, payload
        raise SmokeFailure(f"{path}: HTTP {exc.code}: {payload[:500]}") from exc
    except urllib.error.URLError as exc:
        raise SmokeFailure(f"{path}: {exc}") from exc


def _expect_status(
    base_url: str,
    path: str,
    token: str | None = None,
    body: dict[str, Any] | None = None,
    timeout: int = 60,
) -> Any:
    status, payload = _request(base_url, path, token=token, body=body, timeout=timeout)
    if status != 200:
        raise SmokeFailure(f"{path}: expected HTTP 200, got {status}")
    return payload


def _phase4_write_checks(base_url: str, token: str, agent_id: str) -> dict[str, Any]:
    """Exercise the approval/receipt state machine with a deterministic no-op."""
    conversation = _expect_status(
        base_url,
        f"/api/agents/{agent_id}/main-conversation",
        token,
    )
    if not conversation.get("conversation_id"):
        raise SmokeFailure("main conversation is missing its id")

    def propose(key: str, title: str) -> dict[str, Any]:
        return _expect_status(
            base_url,
            f"/api/agents/{agent_id}/tasks/side-effect",
            token,
            {
                "title": title,
                "objective": "Candidate-only no-op write receipt verification",
                "action_name": "candidate.noop",
                "action_payload": {"marker": key},
                "idempotency_key": key,
                "token_limit": 8_000,
            },
        )

    def approve(proposal: dict[str, Any]) -> dict[str, Any]:
        approval = proposal["approval"]
        return _expect_status(
            base_url,
            (
                f"/api/agents/{agent_id}/tasks/{approval['task_id']}"
                f"/approvals/{approval['approval_id']}/approve"
            ),
            token,
            {
                "action_hash": approval["action_hash"],
                "version": approval["version"],
            },
        )

    def claim(proposal: dict[str, Any]) -> dict[str, Any]:
        approval = proposal["approval"]
        return _expect_status(
            base_url,
            f"/api/agents/{agent_id}/tasks/{approval['task_id']}/write/claim",
            token,
            {
                "approval_id": approval["approval_id"],
                "action_hash": approval["action_hash"],
                "idempotency_key": approval["idempotency_key"],
            },
        )

    success_proposal = propose("idem_candidate_success_phase4", "Candidate no-op success")
    approved = approve(success_proposal)
    if approved["approval"]["status"] != "approved":
        raise SmokeFailure(f"write approval did not bind a run: {approved}")
    first_claim = claim(success_proposal)
    second_claim = claim(success_proposal)
    if first_claim.get("should_execute") is not True:
        raise SmokeFailure(f"first write claim was not executable: {first_claim}")
    if (
        second_claim.get("should_execute") is not False
        or second_claim["receipt"]["receipt_id"] != first_claim["receipt"]["receipt_id"]
    ):
        raise SmokeFailure(f"duplicate write claim was not deduplicated: {second_claim}")
    completed = _expect_status(
        base_url,
        (
            f"/api/agents/{agent_id}/tasks/{success_proposal['approval']['task_id']}"
            f"/write/{first_claim['receipt']['receipt_id']}/complete"
        ),
        token,
        {
            "outcome": {"ok": True},
            "outcome_ref": "candidate:noop:success",
            "actual_tokens": 120,
        },
    )
    if completed.get("status") != "succeeded":
        raise SmokeFailure(f"write success receipt was not recorded: {completed}")

    uncertain_proposal = propose(
        "idem_candidate_uncertain_phase4",
        "Candidate no-op uncertainty",
    )
    approve(uncertain_proposal)
    uncertain_claim = claim(uncertain_proposal)
    uncertain = _expect_status(
        base_url,
        (
            f"/api/agents/{agent_id}/tasks/{uncertain_proposal['approval']['task_id']}"
            f"/write/{uncertain_claim['receipt']['receipt_id']}/uncertain"
        ),
        token,
        {
            "error": "candidate simulated lost response",
            "actual_tokens": 80,
        },
    )
    if uncertain.get("status") != "result_uncertain":
        raise SmokeFailure(f"uncertain write receipt was not recorded: {uncertain}")
    duplicate_uncertain = claim(uncertain_proposal)
    if duplicate_uncertain.get("should_execute") is not False:
        raise SmokeFailure("uncertain write was replayed")

    writes = _expect_status(
        base_url,
        f"/api/agents/{agent_id}/tasks/{success_proposal['approval']['task_id']}/writes",
        token,
    )
    return {
        "approval_status": approved["approval"]["status"],
        "first_should_execute": first_claim["should_execute"],
        "duplicate_should_execute": second_claim["should_execute"],
        "success_receipt": completed["status"],
        "uncertain_receipt": uncertain["status"],
        "success_receipts": len(writes["receipts"]),
    }


def run(base_url: str, credentials_path: Path) -> dict[str, Any]:
    credentials = json.loads(credentials_path.read_text(encoding="utf-8"))
    health = _expect_status(base_url, "/api/health")
    if health.get("ok") is not True or health.get("db") is not True:
        raise SmokeFailure(f"health check failed: {health}")

    status, login = _request(
        base_url,
        "/api/auth/login",
        body={
            "username": credentials["username"],
            "password": credentials["password"],
        },
    )
    if status != 200 or not login.get("access_token"):
        raise SmokeFailure(f"login failed: HTTP {status} {login}")
    token = str(login["access_token"])

    agents = _expect_status(base_url, "/api/agents", token)
    if not isinstance(agents, list):
        raise SmokeFailure("agents endpoint did not return a list")
    agent_id = str(credentials.get("agent_id") or "")
    if not agent_id:
        raise SmokeFailure("candidate credentials did not include an agent id")

    checks = {
        "providers": _expect_status(base_url, "/api/providers/resolved", token),
        "threads": _expect_status(base_url, f"/api/agents/{agent_id}/threads", token),
        "cron": _expect_status(base_url, f"/api/agents/{agent_id}/cron", token),
        "today": _expect_status(base_url, f"/api/agents/{agent_id}/today/cards", token),
        "interests": _expect_status(
            base_url,
            f"/api/agents/{agent_id}/interest-monitors",
            token,
        ),
        "diaries": _expect_status(
            base_url,
            f"/api/agents/{agent_id}/memory/diaries/list",
            token,
            {"days": 7, "limit": 1, "refresh": False},
            timeout=180,
        ),
        "update": _expect_status(base_url, "/api/update/status", token),
        "worker_status": _expect_status(
            base_url,
            f"/api/agents/{agent_id}/tasks/worker-status",
            token,
        ),
    }
    managed = checks["update"]
    if not isinstance(checks["interests"], list):
        raise SmokeFailure("interest monitors endpoint did not return a list")
    if managed.get("managed_update") is not True or managed.get("has_update") is not False:
        raise SmokeFailure(f"managed updater is not active: {managed}")
    blocked_status, blocked = _request(
        base_url,
        "/api/update/upgrade",
        token=token,
        body={},
        allowed_errors=frozenset({403}),
    )
    if blocked_status != 403:
        raise SmokeFailure(f"direct upgrade was not blocked: HTTP {blocked_status} {blocked}")
    write_status = checks["worker_status"].get("side_effect_writes")
    if not isinstance(write_status, dict) or write_status.get("ready") is not True:
        raise SmokeFailure(f"candidate write controls are not ready: {write_status}")
    phase4_writes = _phase4_write_checks(base_url, token, agent_id)

    return {
        "health": health,
        "agent_id": agent_id,
        "provider_count": len(checks["providers"])
        if isinstance(checks["providers"], list)
        else None,
        "thread_count": len(checks["threads"]) if isinstance(checks["threads"], list) else None,
        "cron_count": len(checks["cron"]) if isinstance(checks["cron"], list) else None,
        "today_type": type(checks["today"]).__name__,
        "interest_count": len(checks["interests"]),
        "diaries_type": type(checks["diaries"]).__name__,
        "managed_update": managed.get("managed_update"),
        "direct_upgrade_status": blocked_status,
        "phase4_writes": phase4_writes,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18088")
    parser.add_argument("--credentials", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run(args.base_url.rstrip("/"), args.credentials)
    except SmokeFailure as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
