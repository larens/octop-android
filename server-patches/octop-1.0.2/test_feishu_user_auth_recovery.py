"""Focused tests for recovering stale Feishu CLI user-auth state."""

from __future__ import annotations

import json
import unittest
from pathlib import Path
from unittest.mock import patch

from install_feishu_user_auth_recovery import (
    patch_connectors_router_source,
    patch_feishu_cli_source,
    patch_user_auth_source,
)
from octop.infra.connectors.gateway import feishu_user_auth
from octop.infra.connectors.gateway.adapters import feishu_cli


def valid_status() -> dict[str, object]:
    return {
        "identity": "user",
        "identities": {
            "bot": {"available": True},
            "user": {
                "available": True,
                "tokenStatus": "valid",
                "expiresAt": "2026-10-31T08:31:43+08:00",
            },
        },
    }


def invalid_status() -> dict[str, object]:
    return {
        "identity": "bot",
        "identities": {
            "bot": {"available": True},
            "user": {"available": False, "tokenStatus": "missing"},
        },
    }


class UserAuthCompletionTests(unittest.TestCase):
    def test_valid_existing_grant_skips_stale_device_code(self) -> None:
        with (
            patch.object(feishu_user_auth, "_prepare", return_value=("lark-cli", {})),
            patch.object(feishu_user_auth, "read_auth_status", return_value=valid_status()),
            patch.object(feishu_user_auth, "_auth_has_scope", return_value=True),
            patch.object(feishu_user_auth, "run_cli") as run_cli,
        ):
            result = feishu_user_auth.complete_user_device_login(
                config_dir=Path("/tmp/feishu"),
                app_id="cli_test",
                app_secret="secret",
                device_code="already-used-device-code",
            )

        commands = [call.args[0] for call in run_cli.call_args_list]
        self.assertFalse(any("--device-code" in command for command in commands))
        self.assertIn(["lark-cli", "config", "default-as", "user"], commands)
        self.assertTrue(result["ok"])
        self.assertTrue(result["search_docs_scope"])
        self.assertEqual(result["default_as"], "user")

    def test_successful_browser_grant_recovers_after_device_exchange_error(self) -> None:
        def fake_run(argv: list[str], **_kwargs: object) -> str:
            if "--device-code" in argv:
                raise ValueError("device token 400")
            return "{}"

        with (
            patch.object(feishu_user_auth, "_prepare", return_value=("lark-cli", {})),
            patch.object(
                feishu_user_auth,
                "read_auth_status",
                side_effect=[invalid_status(), valid_status()],
            ),
            patch.object(feishu_user_auth, "_auth_has_scope", return_value=True),
            patch.object(feishu_user_auth, "run_cli", side_effect=fake_run),
        ):
            result = feishu_user_auth.complete_user_device_login(
                config_dir=Path("/tmp/feishu"),
                app_id="cli_test",
                app_secret="secret",
                device_code="stale-code",
            )

        self.assertTrue(result["ok"])
        self.assertEqual(result["identity"], "user")

    def test_invalid_grant_returns_actionable_error(self) -> None:
        def fake_run(argv: list[str], **_kwargs: object) -> str:
            if "--device-code" in argv:
                raise ValueError("device token 400")
            return "{}"

        with (
            patch.object(feishu_user_auth, "_prepare", return_value=("lark-cli", {})),
            patch.object(feishu_user_auth, "read_auth_status", return_value=invalid_status()),
            patch.object(feishu_user_auth, "_auth_has_scope", return_value=False),
            patch.object(feishu_user_auth, "run_cli", side_effect=fake_run),
        ):
            with self.assertRaisesRegex(ValueError, "重新点击「登录授权」"):
                feishu_user_auth.complete_user_device_login(
                    config_dir=Path("/tmp/feishu"),
                    app_id="cli_test",
                    app_secret="secret",
                    device_code="expired-code",
                )


class FeishuSearchIdentityTests(unittest.TestCase):
    def test_docs_search_uses_live_user_grant_without_stored_default_as(self) -> None:
        creds = {
            "app_id": "cli_test",
            "app_secret": "secret",
            "cli_config_key": "config-key",
        }

        def fake_run(argv: list[str], **_kwargs: object) -> str:
            if "check" in argv:
                return '{"ok": true, "missing": null}'
            return '{"data": {"items": []}}'

        with (
            patch.object(feishu_cli, "resolve_binary", return_value="lark-cli"),
            patch.object(feishu_cli, "_prepare_env", return_value={}) as prepare_env,
            patch.object(feishu_cli, "run_cli", side_effect=fake_run) as run_cli,
        ):
            result = feishu_cli.call_tool(
                creds,
                "doc",
                {"method": "+search", "args": {"query": "项目"}},
            )

        self.assertIn("items", result)
        prepare_env.assert_called_once_with(creds, prefer_identity="user")
        final_argv = run_cli.call_args_list[-1].args[0]
        self.assertEqual(final_argv[:3], ["lark-cli", "docs", "+search"])
        self.assertIn("--as", final_argv)
        self.assertEqual(final_argv[final_argv.index("--as") + 1], "user")

    def test_docs_search_reports_missing_user_identity(self) -> None:
        creds = {
            "app_id": "cli_test",
            "app_secret": "secret",
            "cli_config_key": "config-key",
        }

        def fake_run(argv: list[str], **_kwargs: object) -> str:
            if "check" in argv:
                raise ValueError("missing user grant")
            return json.dumps(invalid_status())

        with (
            patch.object(feishu_cli, "resolve_binary", return_value="lark-cli"),
            patch.object(feishu_cli, "_prepare_env", return_value={}),
            patch.object(feishu_cli, "run_cli", side_effect=fake_run),
        ):
            with self.assertRaisesRegex(ValueError, "文档搜索需要先完成飞书账号授权"):
                feishu_cli.call_tool(
                    creds,
                    "doc",
                    {"method": "+search", "args": {"query": "项目"}},
                )


class InstallerTests(unittest.TestCase):
    def test_patchers_are_idempotent_after_install(self) -> None:
        user_auth_path = Path(feishu_user_auth.__file__)
        targets = (
            (
                user_auth_path,
                patch_user_auth_source,
            ),
            (
                Path(feishu_cli.__file__),
                patch_feishu_cli_source,
            ),
            (
                user_auth_path.parents[3] / "api/routers/connectors.py",
                patch_connectors_router_source,
            ),
        )
        for path, patcher in targets:
            source = path.read_text(encoding="utf-8")
            self.assertEqual(patcher(source), source)

    def test_router_reports_live_user_grant_without_stored_flag(self) -> None:
        router_path = Path(feishu_user_auth.__file__).parents[3] / "api/routers/connectors.py"
        source = router_path.read_text(encoding="utf-8")
        self.assertIn('if live.get("user_auth_valid") is True:', source)


if __name__ == "__main__":
    unittest.main()
