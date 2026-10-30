from __future__ import annotations

import asyncio
import sqlite3
import tempfile
import unittest
from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from starlette.requests import Request

import artifact_shares
from install_artifact_shares import patch_app, patch_deps
from artifact_shares import (
    ArtifactShareBody,
    InvalidArtifactShare,
    create_artifact_share,
    decode_share_token,
    find_registered_artifact,
    get_shared_artifact,
    get_short_shared_artifact,
    issue_share_token,
    legacy_workspace_relative_path,
    load_short_share,
    read_artifact_bytes,
    store_short_share,
)

SECRET = b"test-secret-with-enough-length-1234"


class FakeWorkspace:
    def __init__(self, files: dict[str, bytes]) -> None:
        self.files = files

    async def adownload_bytes(self, path: str) -> bytes | None:
        return self.files.get(path)


class PermissionOnAbsoluteWorkspace(FakeWorkspace):
    async def adownload_bytes(self, path: str) -> bytes | None:
        if path.startswith("/"):
            raise PermissionError(path)
        return self.files.get(path)


class FakeRegistry:
    def __init__(self, workspace_dir: Path, workspace: FakeWorkspace) -> None:
        self.workspace_dir = workspace_dir
        self.workspace = workspace

    def resolve_workspace_dir(self, _agent_id: str) -> Path:
        return self.workspace_dir

    def workspace_for_agent(self, _agent_id: str) -> FakeWorkspace:
        return self.workspace


class FakeDatabase:
    def __init__(self) -> None:
        self.conn = sqlite3.connect(":memory:")
        self.conn.row_factory = sqlite3.Row
        self.conn.execute(
            """
            CREATE TABLE artifact_share_links (
              code TEXT PRIMARY KEY,
              user_id INTEGER NOT NULL,
              thread_id TEXT NOT NULL,
              agent_id TEXT NOT NULL,
              path TEXT NOT NULL,
              filename TEXT NOT NULL,
              expires_at INTEGER NOT NULL,
              created_at INTEGER NOT NULL
            )
            """
        )

    @contextmanager
    def connect(self):
        yield self.conn

    @contextmanager
    def transaction(self):
        try:
            yield self.conn
            self.conn.commit()
        except Exception:
            self.conn.rollback()
            raise


def fake_server(
    workspace_dir: Path,
    workspace: FakeWorkspace,
    row: object,
    db: FakeDatabase,
) -> object:
    thread_registry = SimpleNamespace(get_thread=lambda _thread_id: row)
    runtime = SimpleNamespace(
        agent_registry=FakeRegistry(workspace_dir, workspace),
        gateway=SimpleNamespace(thread_registry=thread_registry),
    )
    services = SimpleNamespace(
        secret_repo=SimpleNamespace(get=lambda _name: SECRET),
    )
    return SimpleNamespace(
        app_runtime=runtime,
        services=SimpleNamespace(
            secret_repo=services.secret_repo,
            db=db,
        ),
    )


def request() -> Request:
    return Request(
        {
            "type": "http",
            "scheme": "https",
            "server": ("octop.example", 443),
            "path": "/api/agents/A/threads/T/artifact-shares",
            "headers": [],
        }
    )


class ArtifactSharesTest(unittest.TestCase):
    def test_installer_patches_are_idempotent(self) -> None:
        app_source = (
            "    from octop.api.routers.mobile_ingest import router as mobile_ingest_router\n"
            '            _RouterMount(plugins.router, "/api", ["plugins"]),\n'
        )
        deps_source = '    "/api/downloads/link/",\n)'

        app_patched = patch_app(app_source)
        deps_patched = patch_deps(deps_source)

        self.assertIn("artifact_share_router", app_patched)
        self.assertIn('"/api/s/"', deps_patched)
        self.assertIn('"/api/artifact-shares/"', deps_patched)
        self.assertEqual(app_patched, patch_app(app_patched))
        self.assertEqual(deps_patched, patch_deps(deps_patched))

    def test_installer_adds_short_path_without_duplicating_legacy_exemption(self) -> None:
        deps_source = (
            '    "/api/downloads/link/",\n'
            '    "/api/artifact-shares/",\n'
            ")"
        )

        patched = patch_deps(deps_source)

        self.assertEqual(1, patched.count('"/api/s/"'))
        self.assertEqual(1, patched.count('"/api/artifact-shares/"'))

    def test_token_round_trip_and_tamper_rejection(self) -> None:
        token, expires_at = issue_share_token(
            SECRET,
            user_id=7,
            thread_id="thread-1",
            agent_id="agent-1",
            path="outbound/report.html",
        )
        claims = decode_share_token(SECRET, token)

        self.assertEqual("thread-1", claims.thread_id)
        self.assertEqual("outbound/report.html", claims.path)
        self.assertEqual("report.html", claims.filename)
        self.assertEqual(expires_at, claims.expires_at)
        with self.assertRaises(InvalidArtifactShare):
            decode_share_token(SECRET + b"-wrong", token)

    def test_expired_token_is_rejected(self) -> None:
        token, _ = issue_share_token(
            SECRET,
            user_id=7,
            thread_id="thread-1",
            agent_id="agent-1",
            path="outbound/report.html",
            ttl_seconds=-1,
        )

        with self.assertRaises(InvalidArtifactShare):
            decode_share_token(SECRET, token)

    def test_legacy_workspace_path_maps_only_for_same_agent(self) -> None:
        paths = (
            "/.octop/workspaces/agent-1/generated/report.html",
            "/data/agent-workspace/.octop/workspaces/agent-1/generated/report.html",
            "/Users/octop/.octop/workspaces/agent-1/generated/report.html",
        )

        for path in paths:
            with self.subTest(path=path):
                self.assertEqual(
                    "generated/report.html",
                    legacy_workspace_relative_path(path, "agent-1"),
                )

        self.assertIsNone(
            legacy_workspace_relative_path(
                "/data/agent-workspace/.octop/workspaces/agent-2/generated/report.html",
                "agent-1",
            )
        )
        self.assertIsNone(
            legacy_workspace_relative_path("/tmp/generated/report.html", "agent-1")
        )
        self.assertIsNone(
            legacy_workspace_relative_path(
                "/.octop/workspaces/agent-1/../private.html",
                "agent-1",
            )
        )

    def test_legacy_workspace_path_is_retried_after_original_miss(self) -> None:
        workspace = FakeWorkspace({"generated/report.html": b"<!doctype html>"})
        old_path = (
            "/data/agent-workspace/.octop/workspaces/agent-1/generated/report.html"
        )

        blob = asyncio.run(
            read_artifact_bytes(
                workspace,
                agent_id="agent-1",
                path=old_path,
            )
        )

        self.assertEqual(b"<!doctype html>", blob)

    def test_legacy_workspace_path_is_retried_after_absolute_permission_error(
        self,
    ) -> None:
        workspace = PermissionOnAbsoluteWorkspace(
            {"generated/report.html": b"<!doctype html><h1>fallback</h1>"}
        )
        old_path = (
            "/data/agent-workspace/.octop/workspaces/agent-1/generated/report.html"
        )

        blob = asyncio.run(
            read_artifact_bytes(
                workspace,
                agent_id="agent-1",
                path=old_path,
            )
        )

        self.assertEqual(b"<!doctype html><h1>fallback</h1>", blob)

    def test_only_registered_artifact_is_resolved(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            workspace = Path(tmp)
            registered = workspace / "outbound/report.html"

            found = find_registered_artifact(
                [str(registered)],
                workspace,
                default_agent_id="agent-1",
                requested_path=str(registered),
                requested_agent_id="agent-1",
            )
            not_found = find_registered_artifact(
                [str(registered)],
                workspace,
                default_agent_id="agent-1",
                requested_path=str(workspace / "outbound/private.html"),
                requested_agent_id="agent-1",
            )

            self.assertIsNotNone(found)
            self.assertIsNone(not_found)

    def test_short_code_is_mixed_alphanumeric_and_lookup_is_expiring(self) -> None:
        db = FakeDatabase()
        server = SimpleNamespace(
            services=SimpleNamespace(db=db),
        )
        claims = artifact_shares.ShareClaims(
            user_id=7,
            thread_id="thread-1",
            agent_id="agent-1",
            path="outbound/report.html",
            filename="report.html",
            expires_at=200,
        )

        code = store_short_share(server, claims, now=100)

        self.assertEqual(10, len(code))
        self.assertTrue(any(character.islower() for character in code))
        self.assertTrue(any(character.isupper() for character in code))
        self.assertTrue(any(character.isdigit() for character in code))
        self.assertEqual(claims, load_short_share(server, code, now=150))
        self.assertIsNone(load_short_share(server, code, now=200))

    def test_unknown_short_code_is_rejected(self) -> None:
        db = FakeDatabase()
        row = SimpleNamespace(agent_id="agent-1", user_id=7, artifacts=[])
        with tempfile.TemporaryDirectory() as tmp:
            workspace = FakeWorkspace({})
            server = fake_server(Path(tmp), workspace, row, db)
            with self.assertRaises(artifact_shares.OctopError):
                asyncio.run(get_short_shared_artifact("NotAReal01", server))

    def test_private_route_and_public_response_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            workspace_dir = Path(tmp)
            html_path = str(workspace_dir / "outbound/report.html")
            workspace = FakeWorkspace({html_path: b"<!doctype html><h1>ok</h1>"})
            row = SimpleNamespace(agent_id="agent-1", user_id=7, artifacts=[html_path])
            server = fake_server(workspace_dir, workspace, row, FakeDatabase())
            user = SimpleNamespace(id=7)

            with patch.object(artifact_shares, "require_agent_row"):
                result = asyncio.run(
                    create_artifact_share(
                        "agent-1",
                        "thread-1",
                        ArtifactShareBody(path=html_path),
                        request(),
                        None,
                        user,
                        server,
                    )
                )

            self.assertTrue(result["url"].startswith("https://octop.example/api/s/"))
            self.assertLess(len(result["url"]), 60)
            code = result["url"].rsplit("/", 1)[-1]
            response = asyncio.run(get_short_shared_artifact(code, server))

            self.assertEqual(b"<!doctype html><h1>ok</h1>", response.body)
            self.assertEqual("text/html; charset=utf-8", response.headers["content-type"])
            self.assertIn("sandbox", response.headers["content-security-policy"])
            self.assertEqual("nosniff", response.headers["x-content-type-options"])

    def test_legacy_signed_link_still_opens(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            workspace_dir = Path(tmp)
            html_path = str(workspace_dir / "outbound/report.html")
            workspace = FakeWorkspace({html_path: b"<!doctype html><h1>legacy</h1>"})
            row = SimpleNamespace(agent_id="agent-1", user_id=7, artifacts=[html_path])
            server = fake_server(workspace_dir, workspace, row, FakeDatabase())
            token, _ = issue_share_token(
                SECRET,
                user_id=7,
                thread_id="thread-1",
                agent_id="agent-1",
                path=html_path,
            )

            response = asyncio.run(get_shared_artifact(token, server))

            self.assertEqual(b"<!doctype html><h1>legacy</h1>", response.body)

    def test_legacy_absolute_artifact_path_is_served_from_relative_workspace_path(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            workspace_dir = Path(tmp)
            old_path = (
                "/data/agent-workspace/.octop/workspaces/agent-1/"
                "generated/report.html"
            )
            workspace = FakeWorkspace(
                {"generated/report.html": b"<!doctype html><h1>legacy</h1>"}
            )
            row = SimpleNamespace(agent_id="agent-1", user_id=7, artifacts=[old_path])
            server = fake_server(workspace_dir, workspace, row, FakeDatabase())
            user = SimpleNamespace(id=7)

            with patch.object(artifact_shares, "require_agent_row"):
                result = asyncio.run(
                    create_artifact_share(
                        "agent-1",
                        "thread-1",
                        ArtifactShareBody(path=old_path),
                        request(),
                        None,
                        user,
                        server,
                    )
                )

            code = result["url"].rsplit("/", 1)[-1]
            response = asyncio.run(get_short_shared_artifact(code, server))
            self.assertEqual(b"<!doctype html><h1>legacy</h1>", response.body)


if __name__ == "__main__":
    unittest.main()
