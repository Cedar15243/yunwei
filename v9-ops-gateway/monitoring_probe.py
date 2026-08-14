"""Independent availability, backup freshness, and notification probe for V9."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
from typing import Any, Callable
from urllib import error, request
from urllib.parse import urlparse


SERVICE_NAME = "dingdang-v9-gateway"
BACKUP_TIMER = "dingdang-v9-backup.timer"


class NotificationError(RuntimeError):
    """Raised when a configured notification endpoint cannot accept an event."""


@dataclass(frozen=True)
class CheckResult:
    name: str
    ok: bool
    severity: str
    detail: str
    latency_ms: int | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        result: dict[str, Any] = {
            "name": self.name,
            "ok": self.ok,
            "severity": self.severity,
            "detail": self.detail,
        }
        if self.latency_ms is not None:
            result["latencyMs"] = self.latency_ms
        result.update(self.metadata)
        return result


@dataclass(frozen=True)
class MonitorConfig:
    local_base_url: str
    public_v9_base_url: str
    expert_health_url: str
    backup_root: Path
    backup_max_age_seconds: int
    request_timeout_seconds: float
    latency_warning_ms: int
    state_file: Path
    webhook_url: str | None
    webhook_bearer_token: str | None = None


@dataclass(frozen=True)
class MonitorRunResult:
    exit_code: int
    report: dict[str, Any]


HttpProbe = Callable[[str, float, bool], CheckResult]
TimerProbe = Callable[[], CheckResult]


def _parse_positive_number(name: str, raw: str, *, integer: bool) -> int | float:
    try:
        value = int(raw) if integer else float(raw)
    except ValueError as exc:
        raise ValueError(f"{name}_invalid") from exc
    if value <= 0:
        raise ValueError(f"{name}_invalid")
    return value


def _validate_url(name: str, value: str, *, local: bool = False) -> str:
    parsed = urlparse(value)
    if local:
        if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"}:
            raise ValueError(f"{name}_invalid")
    elif parsed.scheme != "https" or not parsed.hostname:
        raise ValueError(f"{name}_invalid")
    return value.rstrip("/")


def load_config(environ: dict[str, str] | None = None) -> MonitorConfig:
    values = os.environ if environ is None else environ
    webhook_url = values.get("V9_MONITOR_WEBHOOK_URL", "").strip() or None
    if webhook_url is not None:
        webhook_url = _validate_url("webhook_url", webhook_url)
    return MonitorConfig(
        local_base_url=_validate_url(
            "local_base_url",
            values.get("V9_MONITOR_LOCAL_BASE_URL", "http://127.0.0.1:8790"),
            local=True,
        ),
        public_v9_base_url=_validate_url(
            "public_v9_base_url",
            values.get(
                "V9_MONITOR_PUBLIC_V9_BASE_URL",
                "https://bb.chinacedar.top:2305/v9-ops",
            ),
        ),
        expert_health_url=_validate_url(
            "expert_health_url",
            values.get(
                "V9_MONITOR_EXPERT_HEALTH_URL",
                "https://bb.chinacedar.top:2305/health",
            ),
        ),
        backup_root=Path(
            values.get(
                "V9_MONITOR_BACKUP_ROOT",
                "/var/backups/dingdang-v9-gateway",
            )
        ),
        backup_max_age_seconds=int(
            _parse_positive_number(
                "backup_max_age_seconds",
                values.get("V9_MONITOR_BACKUP_MAX_AGE_SECONDS", "97200"),
                integer=True,
            )
        ),
        request_timeout_seconds=float(
            _parse_positive_number(
                "request_timeout_seconds",
                values.get("V9_MONITOR_REQUEST_TIMEOUT_SECONDS", "5"),
                integer=False,
            )
        ),
        latency_warning_ms=int(
            _parse_positive_number(
                "latency_warning_ms",
                values.get("V9_MONITOR_LATENCY_WARNING_MS", "1000"),
                integer=True,
            )
        ),
        state_file=Path(
            values.get(
                "V9_MONITOR_STATE_FILE",
                "/var/lib/dingdang-v9-monitor/state.json",
            )
        ),
        webhook_url=webhook_url,
        webhook_bearer_token=(
            values.get("V9_MONITOR_WEBHOOK_BEARER_TOKEN", "").strip() or None
        ),
    )


def default_http_probe(
    url: str,
    timeout_seconds: float,
    require_json_ok: bool,
) -> CheckResult:
    started = datetime.now(timezone.utc)
    try:
        http_request = request.Request(
            url,
            headers={"Accept": "application/json", "User-Agent": "dingdang-v9-monitor/1"},
            method="GET",
        )
        with request.urlopen(http_request, timeout=timeout_seconds) as response:
            payload = response.read(65_537)
            if response.read(1):
                return CheckResult(url, False, "critical", "response_too_large")
            if response.status < 200 or response.status >= 300:
                return CheckResult(url, False, "critical", "http_status_invalid")
        if require_json_ok:
            try:
                body = json.loads(payload.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                return CheckResult(url, False, "critical", "response_json_invalid")
            if body.get("ok") is not True:
                return CheckResult(url, False, "critical", "response_not_ok")
    except (error.URLError, TimeoutError, OSError):
        return CheckResult(url, False, "critical", "request_failed")
    latency_ms = max(
        0,
        int((datetime.now(timezone.utc) - started).total_seconds() * 1000),
    )
    return CheckResult(url, True, "critical", "ok", latency_ms=latency_ms)


def default_timer_probe() -> CheckResult:
    try:
        completed = subprocess.run(
            ["systemctl", "is-active", BACKUP_TIMER],
            check=False,
            capture_output=True,
            text=True,
            timeout=5,
        )
    except (OSError, subprocess.TimeoutExpired):
        return CheckResult("backup_timer", False, "critical", "probe_failed")
    active = completed.returncode == 0 and completed.stdout.strip() == "active"
    return CheckResult(
        "backup_timer",
        active,
        "critical",
        "active" if active else "inactive",
    )


def _parse_utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("timestamp_timezone_missing")
    return parsed.astimezone(timezone.utc)


def check_backup_freshness(
    config: MonitorConfig,
    now: datetime,
) -> CheckResult:
    try:
        backup_root = config.backup_root.resolve(strict=True)
        last_good = backup_root / "last-good"
        backup_dir = Path(last_good.read_text(encoding="utf-8").strip()).resolve(strict=True)
        backup_dir.relative_to(backup_root)
        manifest_path = backup_dir / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("schemaVersion") != 2:
            return CheckResult("backup_freshness", False, "critical", "manifest_schema_invalid")
        created_at = _parse_utc(str(manifest["createdAt"]))
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError):
        return CheckResult("backup_freshness", False, "critical", "backup_metadata_invalid")

    age_seconds = int((now.astimezone(timezone.utc) - created_at).total_seconds())
    metadata = {
        "ageSeconds": age_seconds,
        "backupId": backup_dir.name,
        "createdAt": created_at.isoformat().replace("+00:00", "Z"),
    }
    if age_seconds < -300:
        return CheckResult(
            "backup_freshness",
            False,
            "critical",
            "backup_timestamp_in_future",
            metadata=metadata,
        )
    if age_seconds > config.backup_max_age_seconds:
        return CheckResult(
            "backup_freshness",
            False,
            "critical",
            "backup_stale",
            metadata=metadata,
        )
    return CheckResult(
        "backup_freshness",
        True,
        "critical",
        "ok",
        metadata=metadata,
    )


def _named_http_check(
    name: str,
    url: str,
    config: MonitorConfig,
    http_probe: HttpProbe,
    *,
    require_json_ok: bool,
) -> CheckResult:
    result = http_probe(url, config.request_timeout_seconds, require_json_ok)
    return CheckResult(
        name=name,
        ok=result.ok,
        severity=result.severity,
        detail=result.detail,
        latency_ms=result.latency_ms,
        metadata=result.metadata,
    )


def _latency_checks(checks: list[CheckResult], threshold_ms: int) -> list[CheckResult]:
    warnings = []
    for check in checks:
        if check.ok and check.latency_ms is not None and check.latency_ms > threshold_ms:
            warnings.append(
                CheckResult(
                    name=f"{check.name}_latency",
                    ok=False,
                    severity="warning",
                    detail="latency_high",
                    latency_ms=check.latency_ms,
                    metadata={"thresholdMs": threshold_ms},
                )
            )
    return warnings


def _read_state(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}
    except (OSError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def _write_state(path: Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary_name = tempfile.mkstemp(prefix=".state-", dir=path.parent)
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as temporary:
            json.dump(state, temporary, ensure_ascii=False, sort_keys=True)
            temporary.write("\n")
            temporary.flush()
            os.fsync(temporary.fileno())
        os.chmod(temporary_name, 0o600)
        os.replace(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def _transition(previous_status: str | None, current_status: str) -> str:
    if previous_status is None:
        return "initial_ok" if current_status == "ok" else "initial_failure"
    if previous_status == current_status:
        return "unchanged"
    if current_status == "ok":
        return "recovered"
    if previous_status == "ok":
        return "degraded"
    return "changed"


def _status_for(checks: list[CheckResult]) -> str:
    if any(not check.ok and check.severity == "critical" for check in checks):
        return "critical"
    if any(not check.ok for check in checks):
        return "warning"
    return "ok"


def _notification_payload(report: dict[str, Any]) -> dict[str, Any]:
    failed = [
        {"name": check["name"], "severity": check["severity"], "detail": check["detail"]}
        for check in report["checks"]
        if check["ok"] is False
    ]
    return {
        "schemaVersion": 1,
        "eventType": "monitor_state_changed",
        "service": SERVICE_NAME,
        "status": report["status"],
        "transition": report["transition"],
        "checkedAt": report["checkedAt"],
        "failedChecks": failed,
    }


def deliver_webhook(
    webhook_url: str,
    payload: dict[str, Any],
    timeout_seconds: float,
    bearer_token: str | None = None,
) -> None:
    headers = {
        "Content-Type": "application/json",
        "User-Agent": "dingdang-v9-monitor/1",
    }
    if bearer_token:
        headers["Authorization"] = f"Bearer {bearer_token}"
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    webhook_request = request.Request(webhook_url, data=encoded, headers=headers, method="POST")
    try:
        with request.urlopen(webhook_request, timeout=timeout_seconds) as response:
            response.read(65_537)
            if response.status < 200 or response.status >= 300:
                raise NotificationError("webhook_status_invalid")
    except NotificationError:
        raise
    except (error.URLError, TimeoutError, OSError) as exc:
        raise NotificationError("webhook_unreachable") from exc


def run_monitor(
    config: MonitorConfig,
    *,
    now: datetime | None = None,
    http_probe: HttpProbe = default_http_probe,
    timer_probe: TimerProbe = default_timer_probe,
) -> MonitorRunResult:
    checked_at = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    http_checks = [
        _named_http_check(
            "local_health",
            f"{config.local_base_url}/health",
            config,
            http_probe,
            require_json_ok=True,
        ),
        _named_http_check(
            "local_ready",
            f"{config.local_base_url}/ready",
            config,
            http_probe,
            require_json_ok=True,
        ),
        _named_http_check(
            "public_v9_health",
            f"{config.public_v9_base_url}/health",
            config,
            http_probe,
            require_json_ok=True,
        ),
        _named_http_check(
            "public_v9_ready",
            f"{config.public_v9_base_url}/ready",
            config,
            http_probe,
            require_json_ok=True,
        ),
        _named_http_check(
            "expert_health",
            config.expert_health_url,
            config,
            http_probe,
            require_json_ok=False,
        ),
    ]
    checks = [
        *http_checks,
        check_backup_freshness(config, checked_at),
        timer_probe(),
        *_latency_checks(http_checks, config.latency_warning_ms),
    ]
    status = _status_for(checks)
    previous = _read_state(config.state_file)
    previous_observed = previous.get("observedStatus")
    previous_notified = previous.get("notifiedStatus")
    transition = _transition(
        previous_observed if isinstance(previous_observed, str) else None,
        status,
    )
    report = {
        "schemaVersion": 1,
        "service": SERVICE_NAME,
        "checkedAt": checked_at.isoformat().replace("+00:00", "Z"),
        "status": status,
        "transition": transition,
        "notificationMode": "configured" if config.webhook_url else "local_audit_only",
        "checks": [check.as_dict() for check in checks],
    }

    should_notify = previous_notified != status and (
        status != "ok" or previous_notified not in {None, "ok"}
    )
    notified_status = previous_notified if isinstance(previous_notified, str) else None
    delivery_failed = False
    if should_notify and config.webhook_url:
        try:
            deliver_webhook(
                config.webhook_url,
                _notification_payload(report),
                config.request_timeout_seconds,
                config.webhook_bearer_token,
            )
            report["notificationMode"] = "delivered"
            notified_status = status
        except NotificationError:
            report["notificationMode"] = "delivery_failed"
            delivery_failed = True

    _write_state(
        config.state_file,
        {
            "schemaVersion": 1,
            "observedStatus": status,
            "notifiedStatus": notified_status,
            "checkedAt": report["checkedAt"],
        },
    )
    if delivery_failed:
        exit_code = 2
    elif status == "critical":
        exit_code = 1
    else:
        exit_code = 0
    return MonitorRunResult(exit_code=exit_code, report=report)


def run_notification_drill(config: MonitorConfig) -> MonitorRunResult:
    checked_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    report = {
        "schemaVersion": 1,
        "service": SERVICE_NAME,
        "checkedAt": checked_at,
        "status": "drill",
        "transition": "notification_drill",
        "notificationMode": "not_configured",
        "checks": [],
    }
    if not config.webhook_url:
        return MonitorRunResult(exit_code=2, report=report)
    payload = {
        "schemaVersion": 1,
        "eventType": "notification_drill",
        "service": SERVICE_NAME,
        "status": "drill",
        "checkedAt": checked_at,
    }
    try:
        deliver_webhook(
            config.webhook_url,
            payload,
            config.request_timeout_seconds,
            config.webhook_bearer_token,
        )
        report["notificationMode"] = "delivered"
        return MonitorRunResult(exit_code=0, report=report)
    except NotificationError:
        report["notificationMode"] = "delivery_failed"
        return MonitorRunResult(exit_code=2, report=report)


def main() -> int:
    parser = argparse.ArgumentParser(description="Dingdang V9 independent monitor")
    parser.add_argument(
        "--notification-drill",
        action="store_true",
        help="send a labeled test event without changing monitor state",
    )
    arguments = parser.parse_args()
    try:
        config = load_config()
        result = (
            run_notification_drill(config)
            if arguments.notification_drill
            else run_monitor(config)
        )
    except ValueError as exc:
        print(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "service": SERVICE_NAME,
                    "status": "configuration_error",
                    "error": str(exc),
                },
                sort_keys=True,
            )
        )
        return 2
    print(json.dumps(result.report, ensure_ascii=False, sort_keys=True))
    return result.exit_code


if __name__ == "__main__":
    raise SystemExit(main())
