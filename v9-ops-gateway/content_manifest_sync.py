from __future__ import annotations

import json
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from typing import Callable, Mapping, Optional


_IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}")
_SHA256 = re.compile(r"[0-9a-f]{64}")


class ContentManifestSyncError(RuntimeError):
    def __init__(self, code: str, status: int = 0, retryable: bool = False):
        super().__init__(code)
        self.code = code
        self.status = status
        self.retryable = retryable


@dataclass(frozen=True)
class ContentManifestSyncConfig:
    base_url: str
    sync_token: str
    timeout_seconds: float = 5.0
    max_response_bytes: int = 2_000_000

    def __post_init__(self) -> None:
        parsed = urllib.parse.urlsplit(self.base_url.strip())
        if (
            parsed.scheme != "https"
            or not parsed.netloc
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError("content_sync_url_invalid")
        if not self.sync_token.strip():
            raise ValueError("content_sync_token_missing")
        if self.timeout_seconds <= 0 or self.timeout_seconds > 30:
            raise ValueError("content_sync_timeout_invalid")
        if self.max_response_bytes <= 0 or self.max_response_bytes > 2_000_000:
            raise ValueError("content_sync_response_limit_invalid")

    @property
    def normalized_base_url(self) -> str:
        return self.base_url.strip().rstrip("/")


@dataclass(frozen=True)
class ContentManifestFetchResult:
    status: str
    etag: str
    manifest_version: int
    expires_at: str
    manifest: Optional[dict]


class ContentManifestSyncClient:
    def __init__(
        self,
        config: ContentManifestSyncConfig,
        opener: Callable[..., object] = urllib.request.urlopen,
    ) -> None:
        self.config = config
        self.opener = opener

    def fetch(
        self, local_project_id: str, cached_etag: str = ""
    ) -> ContentManifestFetchResult:
        local_project_id = local_project_id.strip()
        if not _IDENTIFIER.fullmatch(local_project_id):
            raise ContentManifestSyncError("content_sync_project_invalid")
        normalized_cached_etag = cached_etag.strip().lower()
        if normalized_cached_etag and not _SHA256.fullmatch(normalized_cached_etag):
            raise ContentManifestSyncError("content_sync_etag_invalid")
        query = urllib.parse.urlencode({"localProjectId": local_project_id})
        request = urllib.request.Request(
            self.config.normalized_base_url
            + "/internal/v9/content-manifest?"
            + query,
            method="GET",
            headers={
                "Accept": "application/json",
                "Authorization": "Bearer " + self.config.sync_token.strip(),
                "Cache-Control": "no-cache",
            },
        )
        if normalized_cached_etag:
            request.add_header("If-None-Match", '"' + normalized_cached_etag + '"')
        response = None
        try:
            response = self.opener(request, timeout=self.config.timeout_seconds)
        except urllib.error.HTTPError as error:
            response = error
        except (OSError, urllib.error.URLError, TimeoutError) as error:
            raise ContentManifestSyncError(
                "content_sync_network_failed", retryable=True
            ) from error
        try:
            status = int(getattr(response, "status", getattr(response, "code", 0)))
            if status == 304:
                return self._not_modified(response, normalized_cached_etag)
            body = self._read_bounded(response)
            if status != 200:
                code = _error_code(body)
                raise ContentManifestSyncError(
                    code,
                    status=status,
                    retryable=status >= 500 or status in {408, 429},
                )
            return self._updated(response, body)
        finally:
            close = getattr(response, "close", None)
            if callable(close):
                close()

    def _updated(self, response: object, body: bytes) -> ContentManifestFetchResult:
        try:
            manifest = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ContentManifestSyncError("content_sync_response_invalid") from error
        if not isinstance(manifest, dict):
            raise ContentManifestSyncError("content_sync_response_invalid")
        etag = _normalized_etag(_header(response, "ETag"))
        manifest_etag = str(manifest.get("etag") or "").strip().lower()
        if not etag or etag != manifest_etag or not _SHA256.fullmatch(manifest_etag):
            raise ContentManifestSyncError("content_sync_etag_mismatch")
        manifest_version = manifest.get("manifestVersion")
        header_version = _positive_integer(_header(response, "X-Manifest-Version"))
        if (
            not isinstance(manifest_version, int)
            or isinstance(manifest_version, bool)
            or manifest_version <= 0
            or header_version != manifest_version
        ):
            raise ContentManifestSyncError("content_sync_version_mismatch")
        expires_at = str(manifest.get("expiresAt") or "").strip()
        if not _valid_timestamp(expires_at) or _header(
            response, "X-Manifest-Expires-At"
        ).strip() != expires_at:
            raise ContentManifestSyncError("content_sync_expiry_mismatch")
        return ContentManifestFetchResult(
            "updated", manifest_etag, manifest_version, expires_at, manifest
        )

    def _not_modified(
        self, response: object, cached_etag: str
    ) -> ContentManifestFetchResult:
        etag = _normalized_etag(_header(response, "ETag"))
        if not cached_etag or etag != cached_etag:
            raise ContentManifestSyncError("content_sync_etag_mismatch")
        manifest_version = _positive_integer(_header(response, "X-Manifest-Version"))
        expires_at = _header(response, "X-Manifest-Expires-At").strip()
        if manifest_version <= 0:
            raise ContentManifestSyncError("content_sync_version_mismatch")
        if not _valid_timestamp(expires_at):
            raise ContentManifestSyncError("content_sync_expiry_mismatch")
        return ContentManifestFetchResult(
            "not_modified", etag, manifest_version, expires_at, None
        )

    def _read_bounded(self, response: object) -> bytes:
        body = response.read(self.config.max_response_bytes + 1)
        if len(body) > self.config.max_response_bytes:
            raise ContentManifestSyncError("content_sync_response_too_large")
        return body


class ContentManifestSyncWorker:
    def __init__(
        self,
        repository: object,
        client: ContentManifestSyncClient,
        organization_id: str,
        user_id: str,
        device_id: str,
        refresh_interval_seconds: float = 60.0,
        max_backoff_seconds: float = 900.0,
        clock: Callable[[], float] = time.time,
    ) -> None:
        for value, error in (
            (organization_id, "content_sync_organization_invalid"),
            (user_id, "content_sync_user_invalid"),
            (device_id, "content_sync_device_invalid"),
        ):
            if not _IDENTIFIER.fullmatch(value.strip()):
                raise ValueError(error)
        if refresh_interval_seconds <= 0 or max_backoff_seconds < refresh_interval_seconds:
            raise ValueError("content_sync_interval_invalid")
        self.repository = repository
        self.client = client
        self.organization_id = organization_id.strip()
        self.user_id = user_id.strip()
        self.device_id = device_id.strip()
        self.refresh_interval_seconds = float(refresh_interval_seconds)
        self.max_backoff_seconds = float(max_backoff_seconds)
        self.clock = clock
        self._failures: dict[str, int] = {}
        self._next_due: dict[str, float] = {}
        self._pending: set[str] = set()
        self._lock = threading.Lock()
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def start(self) -> None:
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._stop.clear()
            self._thread = threading.Thread(
                target=self._run_loop,
                name="content-manifest-sync",
                daemon=True,
            )
            self._thread.start()

    def stop(self, timeout_seconds: float = 5.0) -> None:
        self._stop.set()
        self._wake.set()
        thread = self._thread
        if thread is not None:
            thread.join(timeout=max(0.0, timeout_seconds))

    def trigger(self, local_project_id: str) -> None:
        local_project_id = local_project_id.strip()
        if not _IDENTIFIER.fullmatch(local_project_id):
            return
        with self._lock:
            self._pending.add(local_project_id)
        self._wake.set()

    def run_once(self, project_ids: Optional[list[str]] = None) -> dict[str, int]:
        if project_ids is None:
            project_ids = list(
                self.repository.list_content_manifest_sync_projects(self.device_id)
            )
        projects = sorted(
            {
                str(value).strip()
                for value in project_ids
                if _IDENTIFIER.fullmatch(str(value).strip())
            }
        )
        summary = {"updated": 0, "unchanged": 0, "failed": 0, "skipped": 0}
        for local_project_id in projects:
            now = float(self.clock())
            try:
                state = self.repository.content_manifest_sync_state(
                    self.organization_id,
                    self.user_id,
                    self.device_id,
                    local_project_id,
                )
                persisted_failures = int(state.get("failureCount") or 0) if state else 0
                persisted_next_due = float(state.get("nextRetryAt") or 0.0) if state else 0.0
                if persisted_failures > self._failures.get(local_project_id, 0):
                    self._failures[local_project_id] = persisted_failures
                next_due = max(
                    self._next_due.get(local_project_id, 0.0),
                    persisted_next_due,
                )
                if next_due > now:
                    self._next_due[local_project_id] = next_due
                    summary["skipped"] += 1
                    continue
                cached_etag = str(state.get("etag") or "") if state else ""
                result = self.client.fetch(local_project_id, cached_etag)
                if result.status == "not_modified":
                    if not self._same_state(state, result):
                        result = self.client.fetch(local_project_id, "")
                    else:
                        self._record_success(local_project_id)
                        summary["unchanged"] += 1
                        continue
                if result.status != "updated" or result.manifest is None:
                    raise ContentManifestSyncError("content_sync_response_invalid")
                self.repository.sync_authoritative_manifest(
                    self.organization_id,
                    self.user_id,
                    self.device_id,
                    local_project_id,
                    result.manifest,
                )
                self._record_success(local_project_id)
                summary["updated"] += 1
            except ContentManifestSyncError as error:
                self._record_failure(local_project_id, error.code, now)
                summary["failed"] += 1
            except Exception:
                self._record_failure(
                    local_project_id, "content_sync_internal_failed", now
                )
                summary["failed"] += 1
        return summary

    def _run_loop(self) -> None:
        while not self._stop.is_set():
            with self._lock:
                pending = sorted(self._pending)
                self._pending.clear()
            self.run_once(pending or None)
            self._wake.wait(self.refresh_interval_seconds)
            self._wake.clear()

    @staticmethod
    def _same_state(
        state: Optional[Mapping[str, object]], result: ContentManifestFetchResult
    ) -> bool:
        return bool(
            state
            and int(state.get("manifestVersion") or 0) == result.manifest_version
            and str(state.get("etag") or "") == result.etag
            and str(state.get("expiresAt") or "") == result.expires_at
        )

    def _record_success(self, local_project_id: str) -> None:
        self._failures.pop(local_project_id, None)
        self._next_due.pop(local_project_id, None)
        self.repository.record_content_manifest_sync_status(
            self.device_id, local_project_id, "success", "", 0, 0.0
        )

    def _record_failure(self, local_project_id: str, error_code: str, now: float) -> None:
        failure_count = self._failures.get(local_project_id, 0) + 1
        self._failures[local_project_id] = failure_count
        delay = min(
            self.refresh_interval_seconds * (2 ** (failure_count - 1)),
            self.max_backoff_seconds,
        )
        next_retry_at = now + delay
        self._next_due[local_project_id] = next_retry_at
        self.repository.record_content_manifest_sync_status(
            self.device_id,
            local_project_id,
            "failed",
            error_code,
            failure_count,
            next_retry_at,
        )


def _header(response: object, name: str) -> str:
    headers = getattr(response, "headers", {})
    value = headers.get(name, "") if isinstance(headers, Mapping) else headers.get(name)
    return "" if value is None else str(value)


def _normalized_etag(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == '"' and value[-1] == '"':
        value = value[1:-1]
    value = value.strip().lower()
    return value if _SHA256.fullmatch(value) else ""


def _positive_integer(value: str) -> int:
    try:
        parsed = int(value.strip())
    except (TypeError, ValueError):
        return 0
    return parsed if parsed > 0 else 0


def _valid_timestamp(value: str) -> bool:
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00"))
        return True
    except (TypeError, ValueError):
        return False


def _error_code(body: bytes) -> str:
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return "content_sync_http_failed"
    if isinstance(payload, dict):
        code = str(payload.get("error") or "").strip()
        if _IDENTIFIER.fullmatch(code):
            return code
    return "content_sync_http_failed"
