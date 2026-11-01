"""Recover Feishu CLI user authorization from stale or missing metadata."""

from __future__ import annotations

import os
import shutil
import time
from pathlib import Path

from patch_paths import backups_dir, octop_site


USER_AUTH_COMPLETE_ORIGINAL = '''def complete_user_device_login(
    *,
    config_dir: Path,
    app_id: str,
    app_secret: str,
    device_code: str,
) -> dict[str, Any]:
    """Finish device-code login and switch default identity to user."""
    code = str(device_code or "").strip()
    if not code:
        raise ValueError("device_code is required")
    binary, env = _prepare(config_dir, app_id=app_id, app_secret=app_secret, default_as="bot")
    run_cli(
        [binary, "auth", "login", "--device-code", code, "--json"],
        env=env,
        timeout_s=120.0,
    )
    run_cli([binary, "config", "default-as", "user"], env=env, timeout_s=30.0)
    status = read_auth_status(binary=binary, env=env)
    user = (
        status.get("identities", {}).get("user")
        if isinstance(status.get("identities"), dict)
        else None
    )
    user_available = isinstance(user, dict) and bool(user.get("available"))
    identity = str(status.get("identity") or status.get("defaultAs") or "").strip()
    if not user_available:
        raise ValueError("用户授权未完成或 token 无效。请重新点击「登录授权」并打开链接完成授权。")
    missing_search = not _auth_has_scope(binary=binary, env=env, scope="search:docs:read")
    return {
        "ok": True,
        "identity": identity or "user",
        "default_as": "user",
        "user_available": True,
        "bot_available": bool(
            isinstance(status.get("identities"), dict)
            and isinstance(status["identities"].get("bot"), dict)
            and status["identities"]["bot"].get("available")
        ),
        "search_docs_scope": not missing_search,
        "auth_status": status,
        "warning": (
            "已登录，但缺少 search:docs:read。"
            "请在飞书开放平台为应用开通该权限后，再点一次「登录授权」。"
            if missing_search
            else None
        ),
    }
'''


USER_AUTH_COMPLETE_PATCHED = '''def complete_user_device_login(
    *,
    config_dir: Path,
    app_id: str,
    app_secret: str,
    device_code: str,
) -> dict[str, Any]:
    """Finish login, or reuse an already-valid user grant for a stale device code."""
    code = str(device_code or "").strip()
    if not code:
        raise ValueError("device_code is required")
    binary, env = _prepare(config_dir, app_id=app_id, app_secret=app_secret, default_as="bot")

    # The browser can report success while an older device-code exchange fails,
    # or the instance may already hold a valid grant from a previous login.
    status = read_auth_status(binary=binary, env=env)
    if _user_identity_usable(status) and _auth_has_scope(
        binary=binary, env=env, scope="search:docs:read"
    ):
        run_cli([binary, "config", "default-as", "user"], env=env, timeout_s=30.0)
        return _completed_user_auth_payload(binary=binary, env=env, status=status)

    login_error: ValueError | None = None
    try:
        run_cli(
            [binary, "auth", "login", "--device-code", code, "--json"],
            env=env,
            timeout_s=45.0,
        )
    except ValueError as exc:
        login_error = exc

    run_cli([binary, "config", "default-as", "user"], env=env, timeout_s=30.0)
    status = read_auth_status(binary=binary, env=env)
    if not _user_identity_usable(status):
        raise ValueError(
            "设备授权未完成、已过期，或当前用户 token 无效。"
            "请重新点击「登录授权」并完成浏览器授权后再点「我已授权」。"
        ) from login_error
    return _completed_user_auth_payload(binary=binary, env=env, status=status)


def _user_identity_usable(status: dict[str, Any]) -> bool:
    identities = status.get("identities")
    user = identities.get("user") if isinstance(identities, dict) else None
    if not isinstance(user, dict) or not user.get("available"):
        return False
    token_status = str(user.get("tokenStatus") or "").strip().lower()
    return token_status not in {"expired", "invalid", "missing", "revoked"}


def _completed_user_auth_payload(
    *,
    binary: str,
    env: dict[str, str],
    status: dict[str, Any],
) -> dict[str, Any]:
    identity = str(status.get("identity") or status.get("defaultAs") or "").strip()
    missing_search = not _auth_has_scope(binary=binary, env=env, scope="search:docs:read")
    return {
        "ok": True,
        "identity": identity or "user",
        "default_as": "user",
        "user_available": True,
        "bot_available": bool(
            isinstance(status.get("identities"), dict)
            and isinstance(status["identities"].get("bot"), dict)
            and status["identities"]["bot"].get("available")
        ),
        "search_docs_scope": not missing_search,
        "auth_status": status,
        "warning": (
            "已登录，但缺少 search:docs:read。"
            "请在飞书开放平台为应用开通该权限后，再点一次「登录授权」。"
            if missing_search
            else None
        ),
    }
'''


FEISHU_CLI_ORIGINAL = '''        identity = _resolve_identity(domain, tokens[0], creds)
        if (domain, tokens[0]) in _USER_ONLY_SHORTCUTS and str(
            creds.get("default_as") or ""
        ).strip().lower() != "user":
            # Octop has not recorded a completed user OAuth yet.
            raise ValueError(_USER_AUTH_REQUIRED_MSG)
        env = _prepare_env(creds, prefer_identity=identity)
        if (domain, tokens[0]) in _USER_ONLY_SHORTCUTS and not _has_user_scope(
            binary, env, "search:docs:read"
        ):
            raise ValueError(_MISSING_SEARCH_SCOPE_MSG)
'''


FEISHU_CLI_PATCHED = '''        identity = _resolve_identity(domain, tokens[0], creds)
        user_only = (domain, tokens[0]) in _USER_ONLY_SHORTCUTS
        if user_only:
            # The saved metadata may predate a successful device login. Trust the
            # live CLI grant instead of requiring default_as=user in the database.
            env = _prepare_env(creds, prefer_identity="user")
            scope_error = _user_scope_error(binary, env, "search:docs:read")
            if scope_error:
                raise ValueError(scope_error)
        else:
            env = _prepare_env(creds, prefer_identity=identity)
'''


FEISHU_SCOPE_HELPER_ORIGINAL = '''def _has_user_scope(binary: str, env: dict[str, str], scope: str) -> bool:
    try:
        run_cli([binary, "auth", "check", "--scope", scope], env=env, timeout_s=30.0)
        return True
    except ValueError:
        return False
'''


FEISHU_SCOPE_HELPER_PATCHED = '''def _user_scope_error(binary: str, env: dict[str, str], scope: str) -> str | None:
    try:
        run_cli([binary, "auth", "check", "--scope", scope], env=env, timeout_s=30.0)
        return None
    except ValueError:
        if _user_identity_usable(binary, env):
            return _MISSING_SEARCH_SCOPE_MSG
        return _USER_AUTH_REQUIRED_MSG


def _user_identity_usable(binary: str, env: dict[str, str]) -> bool:
    try:
        raw = run_cli([binary, "auth", "status", "--json"], env=env, timeout_s=30.0)
        payload = json.loads(raw) if raw.lstrip().startswith("{") else {}
        if not payload and "{" in raw:
            payload = json.loads(raw[raw.find("{") :])
    except (ValueError, json.JSONDecodeError):
        return False
    identities = payload.get("identities") if isinstance(payload, dict) else None
    user = identities.get("user") if isinstance(identities, dict) else None
    if not isinstance(user, dict) or not user.get("available"):
        return False
    token_status = str(user.get("tokenStatus") or "").strip().lower()
    return token_status not in {"expired", "invalid", "missing", "revoked"}
'''


INSTANCE_PREVIEW_ORIGINAL = '''        if inst.kind == "feishu-cli" and data["credentials_preview"].get("user_auth_configured"):
            live = await asyncio.to_thread(live_user_auth_preview, creds)
            data["credentials_preview"].update(live)
'''


INSTANCE_PREVIEW_PATCHED = '''        if inst.kind == "feishu-cli":
            live = await asyncio.to_thread(live_user_auth_preview, creds)
            data["credentials_preview"].update(live)
            if live.get("user_auth_valid") is True:
                # Older instances can have a valid CLI grant without the
                # default_as flag. Report the live state so the drawer does not
                # keep asking the user to authorize an already-authorized account.
                data["credentials_preview"]["user_auth_configured"] = True
'''


def _replace_required(source: str, original: str, patched: str, *, marker: str) -> str:
    if marker in source:
        return source
    if original not in source:
        raise ValueError("Octop 1.0.2 Feishu authorization source anchor not found")
    return source.replace(original, patched, 1)


def patch_user_auth_source(source: str) -> str:
    return _replace_required(
        source,
        USER_AUTH_COMPLETE_ORIGINAL,
        USER_AUTH_COMPLETE_PATCHED,
        marker="if _user_identity_usable(status) and _auth_has_scope(",
    )


def patch_feishu_cli_source(source: str) -> str:
    patched = _replace_required(
        source,
        FEISHU_CLI_ORIGINAL,
        FEISHU_CLI_PATCHED,
        marker="scope_error = _user_scope_error(binary, env, \"search:docs:read\")",
    )
    return _replace_required(
        patched,
        FEISHU_SCOPE_HELPER_ORIGINAL,
        FEISHU_SCOPE_HELPER_PATCHED,
        marker="def _user_scope_error(binary: str, env: dict[str, str], scope: str) -> str | None:",
    )


def patch_connectors_router_source(source: str) -> str:
    return _replace_required(
        source,
        INSTANCE_PREVIEW_ORIGINAL,
        INSTANCE_PREVIEW_PATCHED,
        marker='if live.get("user_auth_valid") is True:',
    )


def _write_if_changed(path: Path, content: str, backup_dir: Path) -> bool:
    current = path.read_text(encoding="utf-8")
    if current == content:
        return False
    backup_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, backup_dir / path.name)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    temporary.write_text(content, encoding="utf-8")
    shutil.copymode(path, temporary)
    os.replace(temporary, path)
    return True


def install() -> list[Path]:
    site = octop_site()
    backup = backups_dir() / f"feishu-user-auth-{time.strftime('%Y%m%d-%H%M%S')}"
    targets = (
        (
            site / "infra/connectors/gateway/feishu_user_auth.py",
            patch_user_auth_source,
        ),
        (
            site / "infra/connectors/gateway/adapters/feishu_cli.py",
            patch_feishu_cli_source,
        ),
        (
            site / "api/routers/connectors.py",
            patch_connectors_router_source,
        ),
    )
    changed: list[Path] = []
    for path, patcher in targets:
        source = path.read_text(encoding="utf-8")
        patched = patcher(source)
        if _write_if_changed(path, patched, backup):
            changed.append(path)
    return changed


if __name__ == "__main__":
    for changed_path in install():
        print(changed_path)
