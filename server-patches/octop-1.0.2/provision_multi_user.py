"""Create an isolated Octop user and first agent for multi-user deployments.

Run with the Octop virtualenv and feed JSON on stdin:

    /opt/octop/venv/bin/python provision_multi_user.py < new-user.json

The password is read from stdin, never from argv, so it does not show up in
process listings. The script only creates identity/agent records; binding the
user's own Xiaomi credentials is a separate explicit step:

    /opt/octop/venv/bin/python configure_xiaomi_health.py < xiaomi-binding.json
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

from octop.cli.support.offline_ops import create_user_offline

OCTOP_BIN = Path(os.environ.get("OCTOP_BIN", "/opt/octop/venv/bin/octop"))
OCTOP_HOME = Path(os.environ.get("OCTOP_HOME", "/opt/octop"))


def _required(payload: dict[str, object], key: str) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"missing field: {key}")
    return value.strip()


def main() -> int:
    try:
        payload = json.load(sys.stdin)
    except json.JSONDecodeError:
        print("expected JSON on stdin", file=sys.stderr)
        return 2
    if not isinstance(payload, dict):
        print("expected a JSON object", file=sys.stderr)
        return 2
    try:
        username = _required(payload, "username")
        password = _required(payload, "password")
        agent_name = _required(payload, "agent_name")
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2

    try:
        user = create_user_offline(
            username=username,
            password=password,
            role="user",
            display_name=str(payload.get("display_name") or username),
            email=str(payload["email"]) if payload.get("email") else None,
            home=OCTOP_HOME,
        )
    except Exception as error:
        print(f"user creation failed: {error}", file=sys.stderr)
        return 1

    command = [str(OCTOP_BIN), "agent", "create", agent_name, "--user", username]
    template = payload.get("template")
    if isinstance(template, str) and template.strip():
        command.extend(["--template", template.strip()])
    try:
        completed = subprocess.run(
            command,
            check=True,
            capture_output=True,
            text=True,
            timeout=180,
            env={**os.environ, "OCTOP_HOME": str(OCTOP_HOME)},
        )
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(f"agent creation failed: {error}", file=sys.stderr)
        return 1
    try:
        agent = json.loads(completed.stdout)
    except json.JSONDecodeError:
        print(f"unexpected agent output: {completed.stdout}", file=sys.stderr)
        return 1

    print(
        json.dumps(
            {
                "user_id": user.get("id"),
                "username": username,
                "agent_id": agent.get("agent_id"),
                "agent_name": agent.get("name"),
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
