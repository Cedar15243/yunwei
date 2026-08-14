import io
import json
import tempfile
import unittest

from control_plane_sync import (
    ControlPlaneDeliveryResult,
    ControlPlaneSyncClient,
    ControlPlaneSyncConfig,
    ControlPlaneSyncError,
    ControlPlaneSyncWorker,
)
from gateway import SqliteStore


class MutableClock:
    def __init__(self, value=1_800_000_000.0):
        self.value = float(value)

    def __call__(self):
        return self.value


class FakeResponse:
    def __init__(self, status, body=None, headers=None):
        self.status = status
        self._body = io.BytesIO(
            b"" if body is None else json.dumps(body).encode("utf-8")
        )
        self.headers = headers or {}
        self.closed = False

    def read(self, amount=-1):
        return self._body.read(amount)

    def close(self):
        self.closed = True


class CapturingOpener:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []

    def __call__(self, request, timeout):
        self.requests.append((request, timeout))
        return self.responses.pop(0)


def event(key="task-local-a:started:1", event_type="task_started"):
    return {
        "localProjectId": "project-local-a",
        "projectTitle": "冷站年度维护",
        "localTaskId": "task-local-a",
        "taskTitle": "冷水机组控制器故障",
        "eventType": event_type,
        "payload": {"source": "air3"},
        "occurredAt": "2026-08-05T01:00:00.000Z",
        "idempotencyKey": key,
    }


class ControlPlaneSyncClientTest(unittest.TestCase):
    def test_requires_https_and_a_server_only_bootstrap_credential(self):
        with self.assertRaisesRegex(ValueError, "control_plane_sync_url_invalid"):
            ControlPlaneSyncConfig("http://ops.example/functions/v1/ops-glasses", "token-a")
        with self.assertRaisesRegex(ValueError, "control_plane_sync_bootstrap_missing"):
            ControlPlaneSyncConfig("https://ops.example/functions/v1/ops-glasses", "")

    def test_exchanges_a_short_session_and_forwards_the_canonical_event(self):
        clock = MutableClock()
        opener = CapturingOpener(
            FakeResponse(201, {
                "ok": True,
                "accessToken": "short-session-a",
                "expiresAt": "2027-01-15T08:15:00.000Z",
                "tokenType": "Bearer",
            }),
            FakeResponse(202, {"ok": True, "duplicate": False}),
            FakeResponse(202, {"ok": True, "duplicate": True}),
        )
        client = ControlPlaneSyncClient(
            ControlPlaneSyncConfig(
                "https://ops.example/functions/v1/ops-glasses",
                "root-only-bootstrap-token",
            ),
            opener=opener,
            clock=clock,
        )

        first = client.send(event())
        duplicate = client.send(event())

        self.assertEqual(ControlPlaneDeliveryResult(False), first)
        self.assertEqual(ControlPlaneDeliveryResult(True), duplicate)
        self.assertEqual(3, len(opener.requests))
        session_request = opener.requests[0][0]
        first_event_request = opener.requests[1][0]
        self.assertTrue(session_request.full_url.endswith("/device-sync/session"))
        self.assertEqual(
            "Bearer root-only-bootstrap-token",
            session_request.get_header("Authorization"),
        )
        self.assertTrue(first_event_request.full_url.endswith("/device-sync/events"))
        self.assertEqual(
            "Bearer short-session-a",
            first_event_request.get_header("Authorization"),
        )
        self.assertEqual(event(), json.loads(first_event_request.data.decode("utf-8")))

    def test_refreshes_the_short_session_once_after_an_unauthorized_event(self):
        opener = CapturingOpener(
            FakeResponse(201, {
                "ok": True,
                "accessToken": "short-session-old",
                "expiresAt": "2027-01-15T08:15:00.000Z",
                "tokenType": "Bearer",
            }),
            FakeResponse(401, {"ok": False, "error": "unauthorized"}),
            FakeResponse(201, {
                "ok": True,
                "accessToken": "short-session-new",
                "expiresAt": "2027-01-15T08:15:00.000Z",
                "tokenType": "Bearer",
            }),
            FakeResponse(202, {"ok": True, "duplicate": False}),
        )
        client = ControlPlaneSyncClient(
            ControlPlaneSyncConfig(
                "https://ops.example/functions/v1/ops-glasses",
                "root-only-bootstrap-token",
            ),
            opener=opener,
            clock=MutableClock(),
        )

        result = client.send(event())

        self.assertFalse(result.duplicate)
        self.assertEqual(4, len(opener.requests))
        self.assertEqual(
            "Bearer short-session-new",
            opener.requests[-1][0].get_header("Authorization"),
        )


class ControlPlaneSyncOutboxTest(unittest.TestCase):
    def setUp(self):
        self.clock = MutableClock()
        self.temp = tempfile.TemporaryDirectory()
        self.database_path = f"{self.temp.name}/gateway.db"
        self.evidence_path = f"{self.temp.name}/evidence"
        self.store = SqliteStore(
            self.database_path,
            self.evidence_path,
            clock=self.clock,
        )

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def test_event_and_outbox_are_atomic_idempotent_and_survive_restart(self):
        duplicate_first = self.store.append_device_event("air3-a", event())
        duplicate_second = self.store.append_device_event("air3-a", event())

        self.assertFalse(duplicate_first)
        self.assertTrue(duplicate_second)
        pending = self.store.pending_control_plane_events(self.clock.value, 10)
        self.assertEqual(1, len(pending))
        self.assertEqual(event(), pending[0]["event"])
        self.assertEqual(0, pending[0]["attemptCount"])
        serialized = json.dumps(pending, ensure_ascii=False).lower()
        self.assertNotIn("bootstrap", serialized)
        self.assertNotIn("accesstoken", serialized)

        self.store.close()
        self.store = SqliteStore(
            self.database_path,
            self.evidence_path,
            clock=self.clock,
        )
        self.assertEqual(
            [event()],
            [item["event"] for item in self.store.pending_control_plane_events(self.clock.value, 10)],
        )

    def test_same_device_idempotency_key_rejects_different_event_content(self):
        original = event()
        conflicting = event()
        conflicting["payload"] = {"source": "different"}

        self.store.append_device_event("air3-a", original)
        with self.assertRaisesRegex(ValueError, "idempotency_conflict"):
            self.store.append_device_event("air3-a", conflicting)

        pending = self.store.pending_control_plane_events(self.clock.value, 10)
        self.assertEqual([original], [item["event"] for item in pending])

    def test_worker_retries_without_blocking_and_triggers_manifest_after_cloud_acceptance(self):
        self.store.append_device_event("air3-a", event())
        calls = []

        class FlakyClient:
            def __init__(self):
                self.attempts = 0

            def send(self, payload):
                self.attempts += 1
                calls.append(payload)
                if self.attempts == 1:
                    raise ControlPlaneSyncError(
                        "control_plane_sync_network_failed", retryable=True
                    )
                return ControlPlaneDeliveryResult(False)

        triggered = []
        worker = ControlPlaneSyncWorker(
            self.store,
            FlakyClient(),
            retry_base_seconds=5,
            max_backoff_seconds=60,
            clock=self.clock,
            manifest_trigger=triggered.append,
        )

        first = worker.run_once()
        self.assertEqual({"delivered": 0, "failed": 1, "skipped": 0}, first)
        self.assertEqual([], triggered)
        self.assertEqual([], self.store.pending_control_plane_events(self.clock.value + 4, 10))

        self.clock.value += 5
        second = worker.run_once()
        self.assertEqual({"delivered": 1, "failed": 0, "skipped": 0}, second)
        self.assertEqual(["project-local-a"], triggered)
        self.assertEqual([], self.store.pending_control_plane_events(self.clock.value, 10))
        self.assertEqual(2, len(calls))


if __name__ == "__main__":
    unittest.main()
