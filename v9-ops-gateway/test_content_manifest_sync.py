import io
import json
import unittest

from content_manifest_sync import (
    ContentManifestFetchResult,
    ContentManifestSyncWorker,
    ContentManifestSyncClient,
    ContentManifestSyncConfig,
    ContentManifestSyncError,
)


def manifest(version=9, etag="a" * 64):
    return {
        "manifestVersion": version,
        "etag": etag,
        "organizationId": "org-a",
        "userId": "profile-a",
        "deviceId": "device-a",
        "projectId": "project-cloud-a",
        "generatedAt": "2026-08-03T07:59:00.000Z",
        "expiresAt": "2026-08-03T08:15:00.000Z",
        "skills": [],
        "knowledge": [],
    }


class FakeResponse:
    def __init__(self, status, body=b"", headers=None):
        self.status = status
        self._body = io.BytesIO(body)
        self.headers = headers or {}

    def read(self, amount=-1):
        return self._body.read(amount)

    def __enter__(self):
        return self

    def __exit__(self, _type, _value, _traceback):
        return False


class CapturingOpener:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []

    def __call__(self, request, timeout):
        self.requests.append((request, timeout))
        return self.responses.pop(0)


class ContentManifestSyncClientTest(unittest.TestCase):
    def test_requires_https_and_a_server_only_sync_token(self):
        with self.assertRaisesRegex(ValueError, "content_sync_url_invalid"):
            ContentManifestSyncConfig("http://ops.example/functions/v1/ops-glasses", "token-a")
        with self.assertRaisesRegex(ValueError, "content_sync_token_missing"):
            ContentManifestSyncConfig("https://ops.example/functions/v1/ops-glasses", "")

    def test_fetches_a_strict_manifest_with_authorization_and_etag(self):
        payload = manifest()
        opener = CapturingOpener(FakeResponse(
            200,
            json.dumps(payload).encode("utf-8"),
            {
                "ETag": '"' + payload["etag"] + '"',
                "X-Manifest-Version": "9",
                "X-Manifest-Expires-At": payload["expiresAt"],
            },
        ))
        client = ContentManifestSyncClient(
            ContentManifestSyncConfig(
                "https://ops.example/functions/v1/ops-glasses",
                "root-only-sync-token",
            ),
            opener=opener,
        )

        result = client.fetch("project-local-a", "b" * 64)

        self.assertEqual("updated", result.status)
        self.assertEqual(payload, result.manifest)
        request, timeout = opener.requests[0]
        self.assertEqual(5.0, timeout)
        self.assertEqual("Bearer root-only-sync-token", request.get_header("Authorization"))
        self.assertEqual('"' + "b" * 64 + '"', request.get_header("If-none-match"))
        self.assertIn("localProjectId=project-local-a", request.full_url)

    def test_accepts_304_only_when_all_renewal_headers_match(self):
        etag = "c" * 64
        opener = CapturingOpener(FakeResponse(
            304,
            b"",
            {
                "ETag": '"' + etag + '"',
                "X-Manifest-Version": "11",
                "X-Manifest-Expires-At": "2026-08-03T08:30:00.000Z",
            },
        ))
        client = ContentManifestSyncClient(
            ContentManifestSyncConfig(
                "https://ops.example/functions/v1/ops-glasses",
                "root-only-sync-token",
            ),
            opener=opener,
        )

        result = client.fetch("project-local-a", etag)

        self.assertEqual("not_modified", result.status)
        self.assertIsNone(result.manifest)
        self.assertEqual(11, result.manifest_version)
        self.assertEqual("2026-08-03T08:30:00.000Z", result.expires_at)

    def test_rejects_mismatched_or_oversized_responses_without_leaking_the_token(self):
        payload = manifest()
        mismatched = CapturingOpener(FakeResponse(
            200,
            json.dumps(payload).encode("utf-8"),
            {
                "ETag": '"' + "d" * 64 + '"',
                "X-Manifest-Version": "9",
                "X-Manifest-Expires-At": payload["expiresAt"],
            },
        ))
        client = ContentManifestSyncClient(
            ContentManifestSyncConfig(
                "https://ops.example/functions/v1/ops-glasses",
                "root-only-sync-token",
            ),
            opener=mismatched,
        )
        with self.assertRaises(ContentManifestSyncError) as mismatch:
            client.fetch("project-local-a")
        self.assertEqual("content_sync_etag_mismatch", mismatch.exception.code)
        self.assertNotIn("root-only-sync-token", str(mismatch.exception))

        oversized = CapturingOpener(FakeResponse(
            200,
            b"x" * (2_000_001),
            {"ETag": '"' + "e" * 64 + '"'},
        ))
        oversized_client = ContentManifestSyncClient(
            ContentManifestSyncConfig(
                "https://ops.example/functions/v1/ops-glasses",
                "root-only-sync-token",
            ),
            opener=oversized,
        )
        with self.assertRaises(ContentManifestSyncError) as too_large:
            oversized_client.fetch("project-local-a")
        self.assertEqual("content_sync_response_too_large", too_large.exception.code)


class FakeRepository:
    def __init__(self):
        self.projects = ["project-local-a"]
        self.state = None
        self.synced = []
        self.statuses = []

    def list_content_manifest_sync_projects(self, device_id):
        self.listed_device_id = device_id
        return list(self.projects)

    def content_manifest_sync_state(self, organization_id, user_id, device_id, local_project_id):
        self.state_identity = (organization_id, user_id, device_id, local_project_id)
        return self.state

    def sync_authoritative_manifest(self, organization_id, user_id, device_id, local_project_id, payload):
        self.synced.append((organization_id, user_id, device_id, local_project_id, payload))
        return {"manifestVersion": payload["manifestVersion"], "etag": payload["etag"]}

    def record_content_manifest_sync_status(self, device_id, local_project_id, status, error_code, failure_count, next_retry_at):
        self.statuses.append((device_id, local_project_id, status, error_code, failure_count, next_retry_at))


class FakeSyncClient:
    def __init__(self, *results):
        self.results = list(results)
        self.calls = []

    def fetch(self, local_project_id, etag=""):
        self.calls.append((local_project_id, etag))
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result


class MutableClock:
    def __init__(self, value=1_000.0):
        self.value = value

    def __call__(self):
        return self.value


class ContentManifestSyncWorkerTest(unittest.TestCase):
    def test_imports_an_updated_manifest_without_entering_the_request_path(self):
        payload = manifest()
        repository = FakeRepository()
        repository.state = {"manifestVersion": 8, "etag": "b" * 64, "expiresAt": "old"}
        client = FakeSyncClient(ContentManifestFetchResult(
            "updated", payload["etag"], 9, payload["expiresAt"], payload
        ))
        worker = ContentManifestSyncWorker(
            repository, client, "org-a", "profile-a", "device-a", clock=MutableClock()
        )

        summary = worker.run_once()

        self.assertEqual({"updated": 1, "unchanged": 0, "failed": 0, "skipped": 0}, summary)
        self.assertEqual([("project-local-a", "b" * 64)], client.calls)
        self.assertEqual(payload, repository.synced[0][4])
        self.assertEqual("success", repository.statuses[-1][2])

    def test_refetches_without_etag_when_304_reports_a_new_manifest_version(self):
        payload = manifest(version=10, etag="c" * 64)
        repository = FakeRepository()
        repository.state = {
            "manifestVersion": 9,
            "etag": "c" * 64,
            "expiresAt": "2026-08-03T08:15:00.000Z",
        }
        client = FakeSyncClient(
            ContentManifestFetchResult(
                "not_modified", "c" * 64, 10, "2026-08-03T08:30:00.000Z", None
            ),
            ContentManifestFetchResult(
                "updated", "c" * 64, 10, payload["expiresAt"], payload
            ),
        )
        worker = ContentManifestSyncWorker(
            repository, client, "org-a", "profile-a", "device-a", clock=MutableClock()
        )

        summary = worker.run_once()

        self.assertEqual(1, summary["updated"])
        self.assertEqual(
            [("project-local-a", "c" * 64), ("project-local-a", "")],
            client.calls,
        )
        self.assertEqual(10, repository.synced[0][4]["manifestVersion"])

    def test_keeps_the_last_manifest_and_records_sanitized_backoff_on_failure(self):
        repository = FakeRepository()
        repository.state = {"manifestVersion": 9, "etag": "c" * 64, "expiresAt": "old"}
        client = FakeSyncClient(ContentManifestSyncError(
            "content_sync_network_failed", retryable=True
        ))
        clock = MutableClock()
        worker = ContentManifestSyncWorker(
            repository,
            client,
            "org-a",
            "profile-a",
            "device-a",
            refresh_interval_seconds=60,
            max_backoff_seconds=300,
            clock=clock,
        )

        first = worker.run_once()
        second = worker.run_once()

        self.assertEqual(1, first["failed"])
        self.assertEqual(1, second["skipped"])
        self.assertEqual([], repository.synced)
        self.assertEqual("content_sync_network_failed", repository.statuses[-1][3])
        self.assertEqual(1, repository.statuses[-1][4])
        self.assertEqual(1_060.0, repository.statuses[-1][5])

    def test_respects_persisted_backoff_after_worker_restart(self):
        repository = FakeRepository()
        repository.state = {
            "manifestVersion": 9,
            "etag": "c" * 64,
            "expiresAt": "old",
            "syncStatus": "failed",
            "errorCode": "content_sync_network_failed",
            "failureCount": 2,
            "nextRetryAt": 1_120.0,
        }
        client = FakeSyncClient()
        worker = ContentManifestSyncWorker(
            repository,
            client,
            "org-a",
            "profile-a",
            "device-a",
            refresh_interval_seconds=60,
            max_backoff_seconds=300,
            clock=MutableClock(1_000.0),
        )

        summary = worker.run_once()

        self.assertEqual(1, summary["skipped"])
        self.assertEqual([], client.calls)


if __name__ == "__main__":
    unittest.main()
