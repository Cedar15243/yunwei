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
_ERROR_CODE = re.compile(r"[a-z0-9_]{1,120}")
_EVENT_FIELDS = {
    "localProjectId",
    "projectTitle",
    "localTaskId",
    "taskTitle",
    "eventType",
    "payload",
    "occurredAt",
    "idempotencyKey",
}


class ControlPlaneSyncError(RuntimeError):
    def __init__(self, code: str, status: int = 0, retryable: bool = False):
        super().__init__(code)
        self.code = code
        self.status = status
        self.retryable = retryable


@dataclass(frozen=True)
class ControlPlaneSyncConfig:
    base_url: str
    bootstrap_credential: str
    timeout_seconds: float = 5.0
    max_response_bytes: int = 1_000_000

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
            raise ValueError("control_plane_sync_url_invalid")
        if not self.bootstrap_credential.strip():
            raise ValueError("control_plane_sync_bootstrap_missing")
        if self.timeout_seconds <= 0 or self.timeout_seconds > 30:
            raise ValueError("control_plane_sync_timeout_invalid")
        if self.max_response_bytes <= 0 or self.max_response_bytes > 1_000_000:
            raise ValueError("control_plane_sync_response_limit_invalid")

    @property
    def normalized_base_url(self) -> str:
        return self.base_url.strip().rstrip("/")


@dataclass(frozen=True)
class ControlPlaneDeliveryResult:
    duplicate: bool


class ControlPlaneSyncClient:
    def __init__(
        self,
        config: ControlPlaneSyncConfig,
        opener: Callable[..., object] = urllib.request.urlopen,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.config = config
        self.opener = opener
        self.clock = clock
        self._access_token = ""
        self._access_expires_at = 0.0
        self._lock = threading.Lock()

    def send(self, event: Mapping[str, object]) -> ControlPlaneDeliveryResult:
        normalized_event = _normalized_event(event)
        with self._lock:
            token = self._access_session()
            response = self._send_event(token, normalized_event)
            if response.status == 401:
                self._access_token = ""
                self._access_expires_at = 0.0
                response = self._send_event(
                    self._access_session(), normalized_event
                )
            return self._delivery_result(response)

    def _access_session(self) -> str:
        if self._access_token and self._access_expires_at - 30.0 > self.clock():
            return self._access_token
        response = self._request(
            "/device-sync/session",
            {},
            self.config.bootstrap_credential.strip(),
        )
        if response.status != 201:
            raise ControlPlaneSyncError(
                response.error_code,
                status=response.status,
                retryable=_retryable_status(response.status),
            )
        body = response.body
        token = str(body.get("accessToken") or "").strip()
        expires_at = _timestamp_epoch(body.get("expiresAt"))
        if (
            body.get("ok") is not True
            or str(body.get("tokenType") or "") != "Bearer"
            or not token
            or expires_at <= self.clock() + 30.0
        ):
            raise ControlPlaneSyncError("control_plane_sync_session_invalid")
        self._access_token = token
        self._access_expires_at = expires_at
        return token

    def _send_event(
        self, token: str, event: dict[str, object]
    ) -> "_JsonResponse":
        return self._request("/device-sync/events", event, token)

    def _delivery_result(
        self, response: "_JsonResponse"
    ) -> ControlPlaneDeliveryResult:
        if response.status != 202:
            raise ControlPlaneSyncError(
                response.error_code,
                status=response.status,
                retryable=_retryable_status(response.status),
            )
        duplicate = response.body.get("duplicate")
        if response.body.get("ok") is not True or not isinstance(duplicate, bool):
            raise ControlPlaneSyncError("control_plane_sync_response_invalid")
        return ControlPlaneDeliveryResult(duplicate)

    def _request(
        self, path: str, payload: Mapping[str, object], token: str
    ) -> "_JsonResponse":
        request = urllib.request.Request(
            self.config.normalized_base_url + path,
            data=json.dumps(
                payload,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8"),
            method="POST",
            headers={
                "Accept": "application/json",
                "Authorization": "Bearer " + token,
                "Cache-Control": "no-store",
                "Content-Type": "application/json; charset=utf-8",
            },
        )
        response = None
        try:
            response = self.opener(request, timeout=self.config.timeout_seconds)
        except urllib.error.HTTPError as error:
            response = error
        except (OSError, urllib.error.URLError, TimeoutError) as error:
            raise ControlPlaneSyncError(
                "control_plane_sync_network_failed", retryable=True
            ) from error
        try:
            status = int(getattr(response, "status", getattr(response, "code", 0)))
            body_bytes = response.read(self.config.max_response_bytes + 1)
            if len(body_bytes) > self.config.max_response_bytes:
                raise ControlPlaneSyncError("control_plane_sync_response_too_large")
            try:
                body = json.loads(body_bytes.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise ControlPlaneSyncError(
                    "control_plane_sync_response_invalid"
                ) from error
            if not isinstance(body, dict):
                raise ControlPlaneSyncError("control_plane_sync_response_invalid")
            return _JsonResponse(status, body, _error_code(body))
        finally:
            close = getattr(response, "close", None)
            if callable(close):
                close()


@dataclass(frozen=True)
class _JsonResponse:
    status: int
    body: dict
    error_code: str


class ControlPlaneSyncWorker:
    def __init__(
        self,
        repository: object,
        client: ControlPlaneSyncClient,
        retry_base_seconds: float = 5.0,
        max_backoff_seconds: float = 900.0,
        batch_size: int = 25,
        poll_interval_seconds: float = 1.0,
        clock: Callable[[], float] = time.time,
        manifest_trigger: Optional[Callable[[str], None]] = None,
    ) -> None:
        if retry_base_seconds <= 0 or max_backoff_seconds < retry_base_seconds:
            raise ValueError("control_plane_sync_retry_invalid")
        if batch_size <= 0 or batch_size > 100:
            raise ValueError("control_plane_sync_batch_invalid")
        if poll_interval_seconds <= 0 or poll_interval_seconds > 60:
            raise ValueError("control_plane_sync_poll_invalid")
        self.repository = repository
        self.client = client
        self.retry_base_seconds = float(retry_base_seconds)
        self.max_backoff_seconds = float(max_backoff_seconds)
        self.batch_size = int(batch_size)
        self.poll_interval_seconds = float(poll_interval_seconds)
        self.clock = clock
        self.manifest_trigger = manifest_trigger
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._thread: Optional[threading.Thread] = None

    def start(self) -> None:
        with self._lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._stop.clear()
            self._thread = threading.Thread(
                target=self._run_loop,
                name="control-plane-sync",
                daemon=True,
            )
            self._thread.start()

    def stop(self, timeout_seconds: float = 5.0) -> None:
        self._stop.set()
        self._wake.set()
        thread = self._thread
        if thread is not None:
            thread.join(timeout=max(0.0, timeout_seconds))

    def trigger(self) -> None:
        self._wake.set()

    def run_once(self) -> dict[str, int]:
        summary = {"delivered": 0, "failed": 0, "skipped": 0}
        now = float(self.clock())
        items = self.repository.pending_control_plane_events(now, self.batch_size)
        for item in items:
            try:
                result = self.client.send(item["event"])
            except ControlPlaneSyncError as error:
                attempt_count = int(item["attemptCount"]) + 1
                delay = min(
                    self.retry_base_seconds * (2 ** (attempt_count - 1)),
                    self.max_backoff_seconds,
                )
                self.repository.mark_control_plane_event_failed(
                    item["outboxId"],
                    error.code,
                    attempt_count,
                    now + delay,
                    now,
                )
                summary["failed"] += 1
                continue
            self.repository.mark_control_plane_event_succeeded(
                item["outboxId"], result.duplicate, now
            )
            if item["eventType"] == "task_started" and self.manifest_trigger:
                self.manifest_trigger(item["localProjectId"])
            summary["delivered"] += 1
        return summary

    def _run_loop(self) -> None:
        while not self._stop.is_set():
            self.run_once()
            self._wake.wait(self.poll_interval_seconds)
            self._wake.clear()


def _normalized_event(value: Mapping[str, object]) -> dict[str, object]:
    if not isinstance(value, Mapping) or set(value) != _EVENT_FIELDS:
        raise ControlPlaneSyncError("control_plane_sync_event_invalid")
    result = dict(value)
    for key in ("localProjectId", "localTaskId", "eventType", "idempotencyKey"):
        text = str(result.get(key) or "").strip()
        if not _IDENTIFIER.fullmatch(text):
            raise ControlPlaneSyncError("control_plane_sync_event_invalid")
        result[key] = text
    for key in ("projectTitle", "taskTitle"):
        text = str(result.get(key) or "").strip()
        if len(text) > 500:
            raise ControlPlaneSyncError("control_plane_sync_event_invalid")
        result[key] = text
    if not isinstance(result.get("payload"), Mapping):
        raise ControlPlaneSyncError("control_plane_sync_event_invalid")
    result["payload"] = dict(result["payload"])
    occurred_at = str(result.get("occurredAt") or "").strip()
    if _timestamp_epoch(occurred_at) <= 0:
        raise ControlPlaneSyncError("control_plane_sync_event_invalid")
    result["occurredAt"] = occurred_at
    return result


def _timestamp_epoch(value: object) -> float:
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00")).timestamp()
    except (TypeError, ValueError):
        return 0.0


def _retryable_status(status: int) -> bool:
    return status >= 500 or status in {408, 429}


def _error_code(body: Mapping[str, object]) -> str:
    code = str(body.get("error") or "").strip()
    return code if _ERROR_CODE.fullmatch(code) else "control_plane_sync_http_failed"
