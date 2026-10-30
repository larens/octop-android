"""Minimal Xiaomi Mi Health cloud client for personal data sync.

The protocol follows the MIT-licensed community projects:
- https://github.com/Aaron-gx/mihealth-api
- https://github.com/This-weiyuqing/mi-fitness-health

It exchanges a Xiaomi passToken for a short-lived Mi Health service token and
reads the private app API. The API is unofficial and may change without notice.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import random
import struct
import time
from datetime import UTC, datetime
from urllib.parse import urlencode

import httpx

LOGIN_PREFIX = b"&&&START&&&"
LOGIN_URL = "https://account.xiaomi.com/pass/serviceLogin"
AUTH_ERROR_MARKERS = (
    "auth",
    "login required",
    "not logged in",
    "session expired",
    "unauthorized",
)


class MiHealthAuthError(RuntimeError):
    pass


def _parse_login_payload(text: str) -> dict:
    raw = text.encode()
    if raw.startswith(LOGIN_PREFIX):
        raw = raw[len(LOGIN_PREFIX) :]
    parsed = json.loads(raw)
    if not isinstance(parsed, dict):
        raise ValueError("unexpected Xiaomi login response")
    return parsed


def _rc4_crypt(key: bytes, payload: bytes) -> bytes:
    state = list(range(256))
    j = 0
    key_len = len(key)
    for i in range(256):
        j = (j + state[i] + key[i % key_len]) % 256
        state[i], state[j] = state[j], state[i]

    i = 0
    j = 0

    def next_byte() -> int:
        nonlocal i, j
        i = (i + 1) % 256
        j = (j + state[i]) % 256
        state[i], state[j] = state[j], state[i]
        return state[(state[i] + state[j]) % 256]

    for _ in range(1024):
        next_byte()
    return bytes(value ^ next_byte() for value in payload)


def _gen_nonce() -> bytes:
    raw = bytearray(os.urandom(8))
    raw.extend(struct.pack(">I", int(datetime.now(tz=UTC).timestamp() // 60)))
    return bytes(raw)


def _gen_signature(
    method: str,
    path: str,
    values: dict[str, str],
    signed_nonce: bytes,
) -> str:
    base = f"{method}&{path}&data={values['data']}"
    if "rc4_hash__" in values:
        base += f"&rc4_hash__={values['rc4_hash__']}"
    base += f"&{base64.b64encode(signed_nonce).decode()}"
    return base64.b64encode(hashlib.sha1(base.encode()).digest()).decode()


def _service_token_from_headers(headers: httpx.Headers) -> str | None:
    for value in headers.get_list("set-cookie"):
        for part in value.split(";"):
            name, separator, cookie_value = part.strip().partition("=")
            if separator and name == "serviceToken" and cookie_value:
                return cookie_value
    return None


class MiHealthCloudClient:
    """Read-only client for the Xiaomi Mi Health app v1 API."""

    def __init__(
        self,
        *,
        xiaomi_user_id: str,
        pass_token: str,
        region: str = "cn",
        timeout: float = 25.0,
        max_pages: int = 300,
    ) -> None:
        self.xiaomi_user_id = str(xiaomi_user_id)
        self.pass_token = pass_token
        self.region = region or "cn"
        self.base_url = (
            "https://hlth.io.mi.com"
            if self.region in ("", "cn")
            else f"https://{self.region}.hlth.io.mi.com"
        )
        self.timeout = timeout
        self.max_pages = max_pages
        self._ssecurity = b""
        self._cookies = ""
        self._http = httpx.Client(
            timeout=timeout,
            follow_redirects=False,
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/131.0.0.0 Safari/537.36"
                )
            },
        )

    def __enter__(self) -> "MiHealthCloudClient":
        self.connect()
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def close(self) -> None:
        self._http.close()

    def connect(self) -> None:
        response = self._http.get(
            LOGIN_URL,
            params={"_json": "true", "sid": "miothealth"},
            headers={
                "Cookie": (
                    f"userId={self.xiaomi_user_id}; "
                    f"passToken={self.pass_token}"
                )
            },
        )
        response.raise_for_status()
        payload = _parse_login_payload(response.text)
        if payload.get("code") != 0 or not payload.get("location"):
            raise MiHealthAuthError(
                "Xiaomi login expired; scan the Mi Health login QR again"
            )
        security = payload.get("ssecurity")
        if not isinstance(security, str) or not security:
            raise MiHealthAuthError("Xiaomi login did not return ssecurity")
        self._ssecurity = base64.b64decode(security)
        self.xiaomi_user_id = str(payload.get("userId") or self.xiaomi_user_id)
        updated_pass_token = payload.get("passToken")
        if isinstance(updated_pass_token, str) and updated_pass_token:
            self.pass_token = updated_pass_token

        redirect = self._http.get(
            payload["location"],
            headers={
                "Cookie": (
                    f"userId={self.xiaomi_user_id}; "
                    f"passToken={self.pass_token}"
                )
            },
        )
        redirect.raise_for_status()
        parts = [
            value.split(";", 1)[0]
            for value in redirect.headers.get_list("set-cookie")
        ]
        service_token = _service_token_from_headers(redirect.headers)
        if not service_token:
            raise MiHealthAuthError("Xiaomi STS did not return a serviceToken")
        c_user_id = payload.get("cUserId")
        if not c_user_id:
            for part in parts:
                name, separator, value = part.partition("=")
                if separator and name == "cUserId":
                    c_user_id = value
                    break
        if not c_user_id:
            raise MiHealthAuthError("Xiaomi login did not return cUserId")
        self._cookies = (
            f"cUserId={c_user_id}; "
            f"serviceToken={service_token}; "
            "locale=zh_cn"
        )

    def _request(self, api_path: str, payload: dict) -> dict:
        if not self._ssecurity or not self._cookies:
            self.connect()
        form = {"data": json.dumps(payload, separators=(",", ":"))}
        nonce = _gen_nonce()
        signed_nonce = hashlib.sha256(self._ssecurity + nonce).digest()
        form["rc4_hash__"] = _gen_signature("POST", api_path, form, signed_nonce)
        encrypted = {
            key: base64.b64encode(
                _rc4_crypt(signed_nonce, value.encode())
            ).decode()
            for key, value in form.items()
        }
        encrypted["signature"] = _gen_signature(
            "POST", api_path, encrypted, signed_nonce
        )
        encrypted["_nonce"] = base64.b64encode(nonce).decode()

        response = self._http.post(
            self.base_url + api_path,
            headers={
                "Cookie": self._cookies,
                "Content-Type": "application/x-www-form-urlencoded",
            },
            content=urlencode(encrypted),
        )
        if response.status_code in (401, 403):
            raise MiHealthAuthError("Xiaomi Mi Health service token expired")
        response.raise_for_status()
        try:
            plaintext = _rc4_crypt(
                signed_nonce, base64.b64decode(response.text)
            )
            body = json.loads(plaintext)
        except Exception:
            body = json.loads(response.text)
        if body.get("code") != 0:
            message = str(body.get("message") or "unknown Mi Health error")
            if any(marker in message.casefold() for marker in AUTH_ERROR_MARKERS):
                raise MiHealthAuthError(message)
            raise RuntimeError(f"Mi Health API error: {message}")
        result = body.get("result")
        if not isinstance(result, dict):
            raise ValueError("Mi Health API returned an invalid result")
        return result

    def fetch_key(
        self,
        key: str,
        start_ms: int,
        end_ms: int,
    ) -> list[dict]:
        items: list[dict] = []
        next_key: str | None = None
        seen: set[str] = set()
        for _ in range(self.max_pages):
            payload = {
                "key": key,
                "start_time": start_ms // 1000,
                "end_time": end_ms // 1000,
                "reverse": True,
            }
            if next_key:
                payload["next_key"] = next_key
            result = self._request(
                "/app/v1/data/get_fitness_data_by_time", payload
            )
            batch = result.get("data_list")
            if not isinstance(batch, list):
                raise ValueError(f"invalid {key} response")
            items.extend(item for item in batch if isinstance(item, dict))
            if not result.get("has_more"):
                return items
            candidate = result.get("next_key")
            if (
                not isinstance(candidate, str)
                or not candidate
                or candidate in seen
            ):
                raise RuntimeError(f"invalid {key} pagination cursor")
            seen.add(candidate)
            next_key = candidate
        raise RuntimeError(f"{key} pagination exceeded safety limit")

    def fetch_daily_reports(
        self,
        key: str,
        start_seconds: int,
        end_seconds: int,
        *,
        tag: str = "daily_report",
    ) -> list[dict]:
        items: list[dict] = []
        next_key: str | None = None
        seen: set[str] = set()
        for _ in range(self.max_pages):
            payload = {
                "key": key,
                "tag": tag,
                "start_time": start_seconds,
                "end_time": end_seconds,
                "limit": 100,
            }
            if next_key:
                payload["next_key"] = next_key
            result = self._request(
                "/app/v1/data/get_aggregated_fitness_data_by_time",
                payload,
            )
            batch = result.get("data_list")
            if not isinstance(batch, list):
                raise ValueError(f"invalid daily {key} response")
            items.extend(item for item in batch if isinstance(item, dict))
            if not result.get("has_more"):
                return items
            candidate = result.get("next_key")
            if (
                not isinstance(candidate, str)
                or not candidate
                or candidate in seen
            ):
                raise RuntimeError(f"invalid daily {key} pagination cursor")
            seen.add(candidate)
            next_key = candidate
        raise RuntimeError(f"daily {key} pagination exceeded safety limit")

    def fetch_sport_records(
        self,
        start_seconds: int,
        end_seconds: int,
    ) -> list[dict]:
        items: list[dict] = []
        next_key: str | None = None
        seen: set[str] = set()
        for _ in range(self.max_pages):
            payload = {
                "start_time": start_seconds,
                "end_time": end_seconds,
                "limit": 50,
            }
            if next_key:
                payload["next_key"] = next_key
            result = self._request(
                "/app/v1/data/get_sport_records_by_time", payload
            )
            batch = result.get("sport_records")
            if not isinstance(batch, list):
                raise ValueError("invalid sport record response")
            items.extend(item for item in batch if isinstance(item, dict))
            if not result.get("has_more"):
                return items
            candidate = result.get("next_key")
            if (
                not isinstance(candidate, str)
                or not candidate
                or candidate in seen
            ):
                raise RuntimeError("invalid sport pagination cursor")
            seen.add(candidate)
            next_key = candidate
        raise RuntimeError("sport pagination exceeded safety limit")


def parse_value(item: dict) -> dict:
    raw = item.get("value", "{}")
    if isinstance(raw, dict):
        return raw
    try:
        parsed = json.loads(raw)
    except (TypeError, json.JSONDecodeError):
        return {}
    return parsed if isinstance(parsed, dict) else {}


def optional_int(value: object) -> int | None:
    try:
        parsed = int(float(value))  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


def optional_float(value: object) -> float | None:
    try:
        parsed = float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


def current_epoch_ms() -> int:
    return time.time_ns() // 1_000_000
