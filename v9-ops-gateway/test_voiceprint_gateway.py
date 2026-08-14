import hashlib
import http.client
import json
import tempfile
import threading
import unittest

from gateway import (
    GatewayConfig,
    GatewayHttpServer,
    GatewayService,
    SqliteStore,
    voiceprint_runtime_configuration_from_environment,
)
from voiceprint_lifecycle import VoiceprintResponse


class FakeAiProvider:
    def stream_chat(self, context, image_data_url, trace_id):
        return iter(["ok"])


class FakeVoiceprintRuntime:
    def __init__(self):
        self.calls = []

    def profile(self, device_id):
        self.calls.append(("profile", device_id, None))
        return VoiceprintResponse(200, {"ok": True, "status": "active"})

    def consent(self, device_id, payload):
        self.calls.append(("consent", device_id, payload))
        return VoiceprintResponse(201, {"ok": True, "status": "enrolling"})

    def enroll_sample(self, device_id, payload):
        self.calls.append(("enroll_sample", device_id, payload))
        return VoiceprintResponse(201, {"ok": True, "sampleCount": 1})

    def verify(self, device_id, payload):
        self.calls.append(("verify", device_id, payload))
        return VoiceprintResponse(200, {"ok": True, "verified": True})

    def reenroll(self, device_id, payload):
        self.calls.append(("reenroll", device_id, payload))
        return VoiceprintResponse(200, {"ok": True, "status": "enrolling"})

    def delete(self, device_id, payload):
        self.calls.append(("delete", device_id, payload))
        return VoiceprintResponse(200, {"ok": True, "status": "deleted"})

    def admin_revoke(self, authorization, profile_id, payload):
        self.calls.append(("admin_revoke", authorization, profile_id, payload))
        return VoiceprintResponse(200, {"ok": True, "status": "revoked"})

    def admin_list(self, authorization):
        self.calls.append(("admin_list", authorization))
        return VoiceprintResponse(200, {"ok": True, "items": [], "auditChainValid": True})


class VoiceprintGatewayTest(unittest.TestCase):
    def setUp(self):
        self.bootstrap = "voiceprint-http-bootstrap"
        self.temp = tempfile.TemporaryDirectory()
        self.store = SqliteStore(
            f"{self.temp.name}/gateway.db", f"{self.temp.name}/evidence"
        )
        self.voiceprint = FakeVoiceprintRuntime()
        self.service = GatewayService(
            GatewayConfig(
                bootstrap_token_hash=hashlib.sha256(self.bootstrap.encode()).hexdigest(),
                device_id="air3-YM00FCF3NW0031",
                organization_id="org-huafang",
                user_id="user-field-engineer",
                provider_base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
                provider_api_key="server-only-ai-key",
                provider_model="qwen3-vl-plus",
            ),
            self.store,
            FakeAiProvider(),
            voiceprint_service=self.voiceprint,
        )
        self.server = GatewayHttpServer(("127.0.0.1", 0), self.service)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.store.close()
        self.temp.cleanup()

    def request(self, method, path, payload=None, token=""):
        connection = http.client.HTTPConnection(
            "127.0.0.1", self.server.server_port, timeout=3
        )
        body = json.dumps(payload or {}).encode("utf-8") if method == "POST" else None
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        parsed = json.loads(response.read())
        connection.close()
        return response.status, parsed

    def issue_session(self):
        status, body = self.request(
            "POST", "/device-sync/session", {}, self.bootstrap
        )
        self.assertEqual(201, status)
        return body["accessToken"]

    def test_voiceprint_routes_require_short_session_and_forward_bound_device(self):
        unauthorized_status, _ = self.request(
            "GET", "/device-sync/voiceprint/profile"
        )
        token = self.issue_session()
        routes = (
            ("GET", "/device-sync/voiceprint/profile", None, "profile"),
            ("POST", "/device-sync/voiceprint/consent", {"probe": 1}, "consent"),
            (
                "POST",
                "/device-sync/voiceprint/enrollment/samples",
                {"probe": 2},
                "enroll_sample",
            ),
            ("POST", "/device-sync/voiceprint/verify", {"probe": 3}, "verify"),
            ("POST", "/device-sync/voiceprint/reenroll", {"probe": 4}, "reenroll"),
            ("POST", "/device-sync/voiceprint/delete", {"probe": 5}, "delete"),
        )

        statuses = []
        for method, path, payload, _name in routes:
            statuses.append(self.request(method, path, payload, token)[0])

        self.assertEqual(401, unauthorized_status)
        self.assertEqual([200, 201, 201, 200, 200, 200], statuses)
        self.assertEqual(
            [item[3] for item in routes], [call[0] for call in self.voiceprint.calls]
        )
        self.assertTrue(
            all(call[1] == "air3-YM00FCF3NW0031" for call in self.voiceprint.calls)
        )

    def test_voiceprint_routes_fail_closed_when_runtime_is_not_configured(self):
        self.service.voiceprint_service = None
        token = self.issue_session()

        status, body = self.request(
            "GET", "/device-sync/voiceprint/profile", token=token
        )

        self.assertEqual(503, status)
        self.assertEqual("voiceprint_unavailable", body["error"])

    def test_admin_revoke_uses_separate_server_credential_path(self):
        status, body = self.request(
            "POST",
            "/admin/voiceprints/profile-a/revoke",
            {
                "confirmation": "REVOKE VOICEPRINT",
                "reason": "device returned",
                "idempotencyKey": "revoke-a",
            },
            "admin-token",
        )

        self.assertEqual(200, status)
        self.assertEqual("revoked", body["status"])
        self.assertEqual("admin_revoke", self.voiceprint.calls[0][0])
        self.assertEqual("Bearer admin-token", self.voiceprint.calls[0][1])

    def test_admin_list_uses_separate_server_credential_path(self):
        status, body = self.request(
            "GET",
            "/admin/voiceprints",
            token="admin-token",
        )

        self.assertEqual(200, status)
        self.assertEqual([], body["items"])
        self.assertEqual("admin_list", self.voiceprint.calls[0][0])
        self.assertEqual("Bearer admin-token", self.voiceprint.calls[0][1])

    def test_loads_complete_iflytek_new_runtime_and_rejects_partial_configuration(self):
        environment = {
            "IFLYTEK_APP_ID": "server-app-id",
            "IFLYTEK_API_KEY": "server-api-key",
            "IFLYTEK_API_SECRET": "server-api-secret",
            "V9_VOICEPRINT_ORGANIZATION_ID": "org-huafang",
            "V9_VOICEPRINT_USER_ID": "user-field-engineer",
            "V9_VOICEPRINT_ADMIN_TOKEN_SHA256": "a" * 64,
            "V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256": "b" * 64,
            "V9_VOICEPRINT_REPLAY_HMAC_KEY": "server-replay-key",
            "V9_VOICEPRINT_URL": "https://api.xf-yun.com/v1/private/s1aa729d0",
            "V9_VOICEPRINT_THRESHOLD": "0.70",
        }

        provider_config, lifecycle_config = (
            voiceprint_runtime_configuration_from_environment(
                environment, "air3-YM00FCF3NW0031"
            )
        )

        self.assertEqual("server-app-id", provider_config.app_id)
        self.assertEqual(0.70, provider_config.threshold)
        self.assertEqual("org-huafang", lifecycle_config.organization_id)
        self.assertEqual("air3-YM00FCF3NW0031", lifecycle_config.device_id)
        self.assertEqual("b" * 64, lifecycle_config.admin_previous_token_hash)
        with self.assertRaisesRegex(ValueError, "missing_voiceprint_environment"):
            voiceprint_runtime_configuration_from_environment(
                {"IFLYTEK_APP_ID": "server-app-id"},
                "air3-YM00FCF3NW0031",
            )
        self.assertIsNone(
            voiceprint_runtime_configuration_from_environment(
                {}, "air3-YM00FCF3NW0031"
            )
        )
        with self.assertRaisesRegex(ValueError, "voiceprint_admin_previous_token_hash_invalid"):
            voiceprint_runtime_configuration_from_environment(
                {**environment, "V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256": "invalid"},
                "air3-YM00FCF3NW0031",
            )


if __name__ == "__main__":
    unittest.main()
