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
        with urllib.request.urlopen(request, timeout=60) as response:
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
) -> Any:
    status, payload = _request(base_url, path, token=token, body=body)
    if status != 200:
        raise SmokeFailure(f"{path}: expected HTTP 200, got {status}")
    return payload


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
        ),
        "update": _expect_status(base_url, "/api/update/status", token),
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
