"""Time-limited public links for HTML artifacts produced in a chat thread.

The link is an unguessable capability, not a generic workspace-file endpoint. A user can
only create one for an HTML path already recorded in that thread's artifacts, and
the public response is isolated with a CSP sandbox so generated pages cannot act
with the Octop origin's privileges.

New links use a short, random database-backed capability code. Existing JWT links
remain readable so links already shared before this change do not break.
"""

from __future__ import annotations

import secrets
import string
import time
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Any, Sequence

import jwt
from fastapi import APIRouter, Depends, Request
from fastapi.responses import Response
from pydantic import BaseModel

from octop.api.common.agent import require_agent_row
from octop.api.common.agent_workspace import resolve_agent_workspace_dir
from octop.api.common.content_disposition import content_disposition
from octop.api.common.public_base import resolve_public_base
from octop.api.deps import current_user, get_server
from octop.infra.agents.threads.artifact import artifacts_for_response
from octop.infra.errors import ErrorCode, OctopError
from octop.infra.utils.thread_artifact import ThreadArtifact

router = APIRouter()

SHARE_SCOPE = "octop-html-artifact-share"
SHARE_TTL_SECONDS = 7 * 24 * 60 * 60
MAX_SHARE_BYTES = 24 * 1024 * 1024
SHORT_CODE_LENGTH = 10
SHORT_CODE_ALPHABET = string.ascii_uppercase + string.ascii_lowercase + string.digits


class ArtifactShareBody(BaseModel):
    path: str
    agent_id: str | None = None


@dataclass(frozen=True)
class ShareClaims:
    user_id: int
    thread_id: str
    agent_id: str
    path: str
    filename: str
    expires_at: int


class InvalidArtifactShare(ValueError):
    pass


def is_shareable_html(path: str) -> bool:
    return PurePosixPath(path.replace("\\", "/")).suffix.lower() in {".html", ".htm"}


def _filename(path: str) -> str:
    name = PurePosixPath(path.replace("\\", "/")).name.strip()
    return name or "artifact.html"


def legacy_workspace_relative_path(path: str, agent_id: str) -> str | None:
    """Map artifacts recorded under an older workspace root to the current one."""
    normalized = path.replace("\\", "/")
    marker = f"/.octop/workspaces/{agent_id.strip()}/"
    marker_at = normalized.find(marker)
    if marker_at < 0:
        return None
    relative = normalized[marker_at + len(marker) :]
    candidate = PurePosixPath(relative)
    if (
        not relative
        or candidate.is_absolute()
        or any(part in {"", ".", ".."} for part in candidate.parts)
    ):
        return None
    return relative


async def read_artifact_bytes(
    workspace: Any,
    *,
    agent_id: str,
    path: str,
) -> bytes | None:
    async def download(candidate: str) -> bytes | None:
        try:
            return await workspace.adownload_bytes(candidate)
        except PermissionError:
            # BackendWorkspace rejects host paths outside its virtual workspace.
            return None

    blob = await download(path)
    if blob is not None:
        return blob
    relative = legacy_workspace_relative_path(path, agent_id)
    if relative is None or relative == path:
        return None
    return await download(relative)


def _secret(server: Any) -> bytes:
    services = getattr(server, "services", None)
    secret_repo = getattr(services, "secret_repo", None)
    secret = secret_repo.get("jwt") if secret_repo is not None else None
    if not secret:
        raise OctopError(ErrorCode.INTERNAL_ERROR, "jwt secret missing")
    return secret


def issue_share_token(
    secret: bytes,
    *,
    user_id: int,
    thread_id: str,
    agent_id: str,
    path: str,
    ttl_seconds: int = SHARE_TTL_SECONDS,
) -> tuple[str, int]:
    now = int(time.time())
    expires_at = now + ttl_seconds
    payload = {
        "scope": SHARE_SCOPE,
        "sub": str(user_id),
        "thread_id": thread_id,
        "agent_id": agent_id,
        "path": path,
        "filename": _filename(path),
        "iat": now,
        "exp": expires_at,
    }
    return jwt.encode(payload, secret, algorithm="HS256"), expires_at


def decode_share_token(secret: bytes, token: str) -> ShareClaims:
    if not token or len(token) > 4096:
        raise InvalidArtifactShare("invalid token")
    try:
        payload = jwt.decode(token, secret, algorithms=["HS256"])
    except jwt.InvalidTokenError as exc:
        raise InvalidArtifactShare("invalid token") from exc
    if payload.get("scope") != SHARE_SCOPE:
        raise InvalidArtifactShare("wrong token scope")
    try:
        claims = ShareClaims(
            user_id=int(payload["sub"]),
            thread_id=str(payload["thread_id"]),
            agent_id=str(payload["agent_id"]),
            path=str(payload["path"]),
            filename=str(payload["filename"]),
            expires_at=int(payload["exp"]),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise InvalidArtifactShare("incomplete token") from exc
    if (
        not claims.thread_id
        or not claims.agent_id
        or not claims.path
        or not is_shareable_html(claims.path)
    ):
        raise InvalidArtifactShare("invalid artifact claim")
    return claims


def generate_short_code() -> str:
    """Return an unguessable URL-safe code containing letters and a digit."""
    while True:
        code = "".join(
            secrets.choice(SHORT_CODE_ALPHABET) for _ in range(SHORT_CODE_LENGTH)
        )
        if (
            any(character.islower() for character in code)
            and any(character.isupper() for character in code)
            and any(character.isdigit() for character in code)
        ):
            return code


def store_short_share(server: Any, claims: ShareClaims, *, now: int) -> str:
    db = server.services.db
    with db.transaction() as conn:
        conn.execute(
            "DELETE FROM artifact_share_links WHERE expires_at <= ?",
            (now,),
        )
        for _ in range(12):
            code = generate_short_code()
            if conn.execute(
                "SELECT 1 FROM artifact_share_links WHERE code = ?",
                (code,),
            ).fetchone():
                continue
            conn.execute(
                "INSERT INTO artifact_share_links("
                "code, user_id, thread_id, agent_id, path, filename, expires_at, created_at"
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    code,
                    claims.user_id,
                    claims.thread_id,
                    claims.agent_id,
                    claims.path,
                    claims.filename,
                    claims.expires_at,
                    now,
                ),
            )
            return code
    raise OctopError(ErrorCode.INTERNAL_ERROR, "unable to allocate artifact share code")


def load_short_share(server: Any, code: str, *, now: int) -> ShareClaims | None:
    if not code or len(code) > 32:
        return None
    with server.services.db.connect() as conn:
        row = conn.execute(
            "SELECT user_id, thread_id, agent_id, path, filename, expires_at "
            "FROM artifact_share_links WHERE code = ?",
            (code,),
        ).fetchone()
    if row is None:
        return None
    claims = ShareClaims(
        user_id=int(row["user_id"]),
        thread_id=str(row["thread_id"]),
        agent_id=str(row["agent_id"]),
        path=str(row["path"]),
        filename=str(row["filename"]),
        expires_at=int(row["expires_at"]),
    )
    if claims.expires_at <= now:
        with server.services.db.transaction() as conn:
            conn.execute("DELETE FROM artifact_share_links WHERE code = ?", (code,))
        return None
    return claims


def find_registered_artifact(
    artifacts: Sequence[object],
    workspace_dir: Any,
    *,
    default_agent_id: str,
    requested_path: str,
    requested_agent_id: str,
) -> ThreadArtifact | None:
    path = requested_path.strip()
    agent_id = requested_agent_id.strip()
    for artifact in artifacts_for_response(
        artifacts,
        workspace_dir,
        default_agent_id=default_agent_id,
    ):
        if artifact.path == path and artifact.agent_id == agent_id:
            return artifact
    return None


def share_response_headers(filename: str) -> dict[str, str]:
    return {
        "Content-Disposition": content_disposition(filename, disposition="inline"),
        "Cache-Control": "private, max-age=60",
        "X-Content-Type-Options": "nosniff",
        "X-Frame-Options": "DENY",
        "Referrer-Policy": "no-referrer",
        "Content-Security-Policy": (
            "sandbox allow-scripts allow-forms allow-modals allow-popups allow-downloads"
        ),
    }


def _require_thread(
    server: Any,
    agent_id: str,
    thread_id: str,
    user: Any,
    as_user: int | None,
) -> Any:
    require_agent_row(agent_id, user=user, as_user=as_user, server=server)
    row = server.app_runtime.gateway.thread_registry.get_thread(thread_id)
    if row is None or row.agent_id != agent_id:
        raise OctopError(ErrorCode.AGENT_NOT_FOUND, f"thread {thread_id!r} not found")
    effective_uid = as_user if as_user is not None else user.id
    if row.user_id != effective_uid:
        raise OctopError(ErrorCode.FORBIDDEN, "thread not owned by user")
    return row


def _workspace(server: Any, agent_id: str) -> Any:
    registry = getattr(getattr(server, "app_runtime", None), "agent_registry", None)
    workspace = registry.workspace_for_agent(agent_id) if registry is not None else None
    if workspace is None:
        raise OctopError(ErrorCode.AGENT_NOT_FOUND, f"agent {agent_id!r} not found")
    return workspace


@router.post("/agents/{agent_id}/threads/{thread_id}/artifact-shares")
async def create_artifact_share(
    agent_id: str,
    thread_id: str,
    body: ArtifactShareBody,
    request: Request,
    as_user: int | None = None,
    user: Any = Depends(current_user),
    server: Any = Depends(get_server),
) -> dict[str, Any]:
    row = _require_thread(server, agent_id, thread_id, user, as_user)
    target_agent_id = (body.agent_id or agent_id).strip()
    require_agent_row(target_agent_id, user=user, as_user=as_user, server=server)
    workspace_dir = resolve_agent_workspace_dir(server, target_agent_id)
    artifact = find_registered_artifact(
        row.artifacts,
        workspace_dir,
        default_agent_id=agent_id,
        requested_path=body.path,
        requested_agent_id=target_agent_id,
    )
    if artifact is None:
        raise OctopError(ErrorCode.NOT_FOUND, "artifact is not registered to this thread")
    if not is_shareable_html(artifact.path):
        raise OctopError(ErrorCode.SLASH_BAD_ARGS, "only HTML artifacts can be shared")

    workspace = _workspace(server, target_agent_id)
    blob = await read_artifact_bytes(
        workspace,
        agent_id=target_agent_id,
        path=artifact.path,
    )
    if blob is None:
        raise OctopError(
            ErrorCode.NOT_FOUND,
            "conversation artifact no longer exists; ask the agent to regenerate it",
        )
    if len(blob) > MAX_SHARE_BYTES:
        raise OctopError(ErrorCode.SLASH_BAD_ARGS, "HTML artifact is too large to share")

    now = int(time.time())
    effective_uid = as_user if as_user is not None else user.id
    claims = ShareClaims(
        user_id=effective_uid,
        thread_id=thread_id,
        agent_id=target_agent_id,
        path=artifact.path,
        filename=_filename(artifact.path),
        expires_at=now + SHARE_TTL_SECONDS,
    )
    code = store_short_share(server, claims, now=now)
    base = resolve_public_base(request).rstrip("/")
    return {
        "url": f"{base}/api/s/{code}",
        "expires_at": claims.expires_at,
        "filename": claims.filename,
    }


async def _serve_share(server: Any, claims: ShareClaims) -> Response:
    blob = await read_artifact_bytes(
        _workspace(server, claims.agent_id),
        agent_id=claims.agent_id,
        path=claims.path,
    )
    if blob is None:
        raise OctopError(ErrorCode.NOT_FOUND, "shared artifact not found")
    if len(blob) > MAX_SHARE_BYTES:
        raise OctopError(ErrorCode.NOT_FOUND, "shared artifact not found")
    return Response(
        content=blob,
        media_type="text/html; charset=utf-8",
        headers=share_response_headers(claims.filename),
    )


@router.get("/s/{code}", include_in_schema=False)
async def get_short_shared_artifact(
    code: str,
    server: Any = Depends(get_server),
) -> Response:
    claims = load_short_share(server, code, now=int(time.time()))
    if claims is None:
        raise OctopError(ErrorCode.NOT_FOUND, "shared artifact not found or expired")
    return await _serve_share(server, claims)


@router.get("/artifact-shares/{token}", include_in_schema=False)
async def get_shared_artifact(
    token: str,
    server: Any = Depends(get_server),
) -> Response:
    try:
        claims = decode_share_token(_secret(server), token)
    except InvalidArtifactShare as exc:
        raise OctopError(ErrorCode.NOT_FOUND, "shared artifact not found or expired") from exc

    return await _serve_share(server, claims)
