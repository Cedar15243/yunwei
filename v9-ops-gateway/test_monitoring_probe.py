from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import monitoring_probe


class MonitoringProbeTest(unittest.TestCase):
    def make_config(self, root: Path) -> monitoring_probe.MonitorConfig:
        backup_root = root / "backups"
        backup_dir = backup_root / "20260813T000000Z"
        backup_dir.mkdir(parents=True)
        (backup_root / "last-good").write_text(str(backup_dir), encoding="utf-8")
        (backup_dir / "manifest.json").write_text(
            json.dumps(
                {
                    "schemaVersion": 2,
                    "createdAt": "2026-08-13T00:00:00Z",
                    "databases": {},
                    "evidence": {"existed": False, "files": []},
                }
            ),
            encoding="utf-8",
        )
        return monitoring_probe.MonitorConfig(
            local_base_url="http://127.0.0.1:8790",
            public_v9_base_url="https://bb.chinacedar.top:2305/v9-ops",
            expert_health_url="https://bb.chinacedar.top:2305/health",
            backup_root=backup_root,
            backup_max_age_seconds=97_200,
            request_timeout_seconds=5.0,
            latency_warning_ms=1_000,
            state_file=root / "state.json",
            webhook_url=None,
        )

    def healthy_http_probe(self, url: str, timeout_seconds: float, require_json_ok: bool):
        return monitoring_probe.CheckResult(
            name=url,
            ok=True,
            severity="critical",
            latency_ms=25,
            detail="ok",
        )

    def test_healthy_run_records_local_audit_mode_without_notification(self):
        with tempfile.TemporaryDirectory() as directory:
            config = self.make_config(Path(directory))
            result = monitoring_probe.run_monitor(
                config,
                now=datetime(2026, 8, 13, 5, 0, tzinfo=timezone.utc),
                http_probe=self.healthy_http_probe,
                timer_probe=lambda: monitoring_probe.CheckResult(
                    name="backup_timer",
                    ok=True,
                    severity="critical",
                    detail="active",
                ),
            )

            self.assertEqual(result.exit_code, 0)
            self.assertEqual(result.report["status"], "ok")
            self.assertEqual(result.report["notificationMode"], "local_audit_only")
            self.assertEqual(result.report["transition"], "initial_ok")
            state = json.loads(config.state_file.read_text(encoding="utf-8"))
            self.assertEqual(state["observedStatus"], "ok")
            self.assertIsNone(state["notifiedStatus"])

    def test_stale_backup_fails_closed_and_preserves_the_reason(self):
        with tempfile.TemporaryDirectory() as directory:
            config = self.make_config(Path(directory))
            result = monitoring_probe.run_monitor(
                config,
                now=datetime(2026, 8, 15, 0, 0, tzinfo=timezone.utc),
                http_probe=self.healthy_http_probe,
                timer_probe=lambda: monitoring_probe.CheckResult(
                    name="backup_timer",
                    ok=True,
                    severity="critical",
                    detail="active",
                ),
            )

            self.assertEqual(result.exit_code, 1)
            self.assertEqual(result.report["status"], "critical")
            backup = next(check for check in result.report["checks"] if check["name"] == "backup_freshness")
            self.assertFalse(backup["ok"])
            self.assertEqual(backup["detail"], "backup_stale")

    def test_recovery_is_reported_after_a_previous_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            config = self.make_config(Path(directory))
            config.state_file.write_text(
                json.dumps({"observedStatus": "critical", "notifiedStatus": "critical"}),
                encoding="utf-8",
            )
            result = monitoring_probe.run_monitor(
                config,
                now=datetime(2026, 8, 13, 5, 0, tzinfo=timezone.utc),
                http_probe=self.healthy_http_probe,
                timer_probe=lambda: monitoring_probe.CheckResult(
                    name="backup_timer",
                    ok=True,
                    severity="critical",
                    detail="active",
                ),
            )

            self.assertEqual(result.exit_code, 0)
            self.assertEqual(result.report["transition"], "recovered")

    def test_webhook_failure_is_retried_without_marking_the_state_notified(self):
        with tempfile.TemporaryDirectory() as directory:
            config = self.make_config(Path(directory))
            config = monitoring_probe.MonitorConfig(
                **{**config.__dict__, "webhook_url": "https://alerts.example.test/v9"}
            )
            failing_timer = lambda: monitoring_probe.CheckResult(
                name="backup_timer",
                ok=False,
                severity="critical",
                detail="inactive",
            )
            with mock.patch.object(
                monitoring_probe,
                "deliver_webhook",
                side_effect=monitoring_probe.NotificationError("webhook_unreachable"),
            ) as deliver:
                result = monitoring_probe.run_monitor(
                    config,
                    now=datetime(2026, 8, 13, 5, 0, tzinfo=timezone.utc),
                    http_probe=self.healthy_http_probe,
                    timer_probe=failing_timer,
                )

            self.assertEqual(result.exit_code, 2)
            self.assertEqual(result.report["notificationMode"], "delivery_failed")
            deliver.assert_called_once()
            state = json.loads(config.state_file.read_text(encoding="utf-8"))
            self.assertEqual(state["observedStatus"], "critical")
            self.assertIsNone(state["notifiedStatus"])

    def test_failed_recovery_notification_is_retried_on_the_next_healthy_run(self):
        with tempfile.TemporaryDirectory() as directory:
            config = self.make_config(Path(directory))
            config = monitoring_probe.MonitorConfig(
                **{**config.__dict__, "webhook_url": "https://alerts.example.test/v9"}
            )
            config.state_file.write_text(
                json.dumps({"observedStatus": "ok", "notifiedStatus": "critical"}),
                encoding="utf-8",
            )
            with mock.patch.object(monitoring_probe, "deliver_webhook") as deliver:
                result = monitoring_probe.run_monitor(
                    config,
                    now=datetime(2026, 8, 13, 5, 0, tzinfo=timezone.utc),
                    http_probe=self.healthy_http_probe,
                    timer_probe=lambda: monitoring_probe.CheckResult(
                        name="backup_timer",
                        ok=True,
                        severity="critical",
                        detail="active",
                    ),
                )

            self.assertEqual(result.exit_code, 0)
            self.assertEqual(result.report["transition"], "unchanged")
            self.assertEqual(result.report["notificationMode"], "delivered")
            deliver.assert_called_once()
            state = json.loads(config.state_file.read_text(encoding="utf-8"))
            self.assertEqual(state["notifiedStatus"], "ok")


if __name__ == "__main__":
    unittest.main()
