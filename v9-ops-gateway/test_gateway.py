import base64
import hashlib
import http.client
import json
import os
import sqlite3
import socket
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from datetime import datetime, timezone
from unittest import mock

from gateway import (
    GatewayConfig,
    GatewayHttpServer,
    GatewayService,
    SqliteStore,
    configuration_from_environment,
    control_plane_sync_worker_from_environment,
    content_manifest_sync_worker_from_environment,
    serve_gateway,
)
from asr_proxy import encode_websocket_frame, read_websocket_frame
from execution_context import ExecutionContext


class MutableClock:
    def __init__(self, now=1_800_000_000.0):
        self.now = now

    def __call__(self):
        return self.now


def iso_timestamp(value):
    return datetime.fromtimestamp(value, tz=timezone.utc).isoformat().replace(
        "+00:00", "Z"
    )


def authoritative_skill_manifest(
    clock,
    organization_id,
    user_id,
    device_id,
    project_id,
    version_id="skill-hvac@1.0.0",
    manifest_version=1,
    expires_at=None,
    rules=None,
    knowledge=None,
):
    skill_id, version = version_id.split("@", 1)
    normalized_rules = rules or {
        "applicableWhen": {"systems": ["hvac"]},
        "excludedWhen": {},
        "requiredInputs": ["description"],
        "evidenceSchema": {"required": []},
        "steps": [
            {
                "id": "read-alarm-code",
                "instruction": "先读取报警代码",
                "risk": "low",
            }
        ],
        "safety": {
            "forbiddenActions": ["close_task_without_confirmation"],
            "expertEscalation": ["live_voltage"],
        },
        "outputConstraints": {"maxStepsPerTurn": 1},
        "knowledgeScopes": ["hvac-manuals"],
    }
    content_sha256 = hashlib.sha256(
        json.dumps(normalized_rules, ensure_ascii=False, sort_keys=True).encode("utf-8")
    ).hexdigest()
    return {
        "manifestVersion": manifest_version,
        "etag": format(manifest_version, "064x"),
        "organizationId": organization_id,
        "userId": user_id,
        "deviceId": device_id,
        "projectId": project_id,
        "generatedAt": iso_timestamp(clock() - 5),
        "expiresAt": iso_timestamp(
            expires_at if expires_at is not None else clock() + 900
        ),
        "skills": [
            {
                "skillId": skill_id,
                "versionId": version_id,
                "version": version,
                "name": "冷站控制器诊断",
                "description": "按受控步骤诊断冷站控制器。",
                "status": "published",
                "contentSha256": content_sha256,
                "rules": normalized_rules,
                "authorizationScope": "project",
            }
        ],
        "knowledge": knowledge or [],
    }


class FakeProvider:
    def __init__(self, chunks=None, failure=None):
        self.chunks = chunks or ["先检查设备电源指示灯。"]
        self.failure = failure
        self.calls = []

    def stream_chat(self, context, image_data_url, trace_id):
        self.calls.append(
            {
                "context": context,
                "prompt": context.user_text if isinstance(context, ExecutionContext) else context,
                "image_data_url": image_data_url,
                "trace_id": trace_id,
            }
        )
        if self.failure:
            raise self.failure
        return iter(self.chunks)


class FakeContentManifestSyncWorker:
    def __init__(self):
        self.trigger_calls = []

    def trigger(self, local_project_id):
        self.trigger_calls.append(local_project_id)


class FakeControlPlaneSyncWorker:
    def __init__(self):
        self.trigger_calls = 0

    def trigger(self):
        self.trigger_calls += 1


class DoneTerminatedProviderResponse:
    def __init__(self):
        self.lines = iter(
            [
                b'data: {"choices":[{"delta":{"content":"ok"}}]}\n',
                b"data: [DONE]\n",
            ]
        )
        self.closed = False

    def __iter__(self):
        return self

    def __next__(self):
        try:
            return next(self.lines)
        except StopIteration:
            raise AssertionError("provider_stream_read_after_done")

    def close(self):
        self.closed = True


class DisconnectTrackingProvider(FakeProvider):
    class Stream:
        def __init__(self):
            self.closed = False
            self._chunks = iter(["first", "second"])

        def __iter__(self):
            return self

        def __next__(self):
            return next(self._chunks)

        def close(self):
            self.closed = True

    def __init__(self):
        super().__init__()
        self.stream = self.Stream()

    def stream_chat(self, context, image_data_url, trace_id):
        self.calls.append(
            {
                "context": context,
                "image_data_url": image_data_url,
                "trace_id": trace_id,
            }
        )
        return self.stream


class GatewayServiceTest(unittest.TestCase):
    def setUp(self):
        self.bootstrap = "bootstrap-secret-for-air3"
        self.clock = MutableClock()
        self.temp = tempfile.TemporaryDirectory()
        self.store = SqliteStore(
            f"{self.temp.name}/gateway.db",
            f"{self.temp.name}/evidence",
            clock=self.clock,
        )
        self.local_project_id = "project-local-a"
        self.local_task_id = "task-local-a"
        self.store.append_device_event(
            "air3-YM00FCF3NW0031",
            {
                "localProjectId": self.local_project_id,
                "projectTitle": "冷站年度维护",
                "localTaskId": self.local_task_id,
                "taskTitle": "冷水机组控制器故障",
                "eventType": "task_started",
                "payload": {},
                "occurredAt": "2026-08-03T01:00:00.000Z",
                "idempotencyKey": "task-local-a:started:1",
            },
        )
        self.provider = FakeProvider(["先检查", "设备电源指示灯。"])
        self.service = GatewayService(
            GatewayConfig(
                bootstrap_token_hash=hashlib.sha256(self.bootstrap.encode()).hexdigest(),
                device_id="air3-YM00FCF3NW0031",
                organization_id="org-huafang",
                user_id="user-field-engineer",
                provider_base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
                provider_api_key="server-only-provider-key",
                provider_model="qwen3-vl-plus",
                session_ttl_seconds=900,
                max_image_bytes=1024 * 1024,
            ),
            self.store,
            self.provider,
            clock=self.clock,
        )

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def issue_session(self):
        response = self.service.exchange_device_session(f"Bearer {self.bootstrap}")
        self.assertEqual(201, response.status)
        return response.json_body["accessToken"]

    def ai_payload(self, text, **extra):
        payload = {
            "final_text": text,
            "localProjectId": self.local_project_id,
            "localTaskId": self.local_task_id,
        }
        payload.update(extra)
        return payload

    def sync_authoritative_skill(
        self,
        version_id="skill-hvac@1.0.0",
        manifest_version=1,
        expires_at=None,
        rules=None,
        knowledge=None,
    ):
        manifest = authoritative_skill_manifest(
            self.clock,
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            "project-cloud-a",
            version_id=version_id,
            manifest_version=manifest_version,
            expires_at=expires_at,
            rules=rules,
            knowledge=knowledge,
        )
        self.store.execution_context.sync_authoritative_manifest(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_project_id,
            manifest,
        )
        return version_id

    def test_exchanges_bootstrap_for_no_store_fifteen_minute_session(self):
        response = self.service.exchange_device_session(f"Bearer {self.bootstrap}")

        self.assertEqual(201, response.status)
        self.assertEqual("no-store", response.headers["Cache-Control"])
        self.assertNotEqual(self.bootstrap, response.json_body["accessToken"])
        self.assertEqual("Bearer", response.json_body["tokenType"])
        self.assertLessEqual(
            response.json_body["expiresAtEpochSeconds"] - self.clock.now,
            900,
        )

    def test_rejects_bootstrap_and_expired_sessions_on_ai_route(self):
        bootstrap_response = self.service.diagnose(
            f"Bearer {self.bootstrap}",
            "_",
            self.ai_payload("设备无法启动"),
        )
        self.assertEqual(401, bootstrap_response.status)

        access_token = self.issue_session()
        self.clock.now += 901
        expired_response = self.service.diagnose(
            f"Bearer {access_token}",
            "_",
            self.ai_payload("设备无法启动"),
        )
        self.assertEqual(401, expired_response.status)
        self.assertEqual([], self.provider.calls)

    def test_streams_real_text_only_ai_response_without_fake_image(self):
        self.sync_authoritative_skill()
        access_token = self.issue_session()

        response = self.service.diagnose(
            f"Bearer {access_token}",
            "_",
            self.ai_payload("设备无法启动", contextAttributes={"system": "hvac"}),
        )

        self.assertEqual(200, response.status)
        self.assertEqual("text/event-stream; charset=utf-8", response.headers["Content-Type"])
        body = b"".join(response.body).decode("utf-8")
        self.assertIn('event: delta\ndata: {"text": "先检查"}', body)
        self.assertIn("event: done", body)
        self.assertEqual("", self.provider.calls[0]["image_data_url"])
        self.assertIn("设备无法启动", self.provider.calls[0]["prompt"])

    def test_uploads_valid_jpeg_and_binds_it_to_the_ai_request(self):
        self.sync_authoritative_skill()
        access_token = self.issue_session()
        jpeg = b"\xff\xd8\xff\xe0" + b"field-evidence" + b"\xff\xd9"

        uploaded = self.service.upload_image(
            f"Bearer {access_token}",
            self.local_task_id,
            {
                "image_base64": base64.b64encode(jpeg).decode("ascii"),
                "image_kind": "field_photo",
            },
        )
        self.assertEqual(201, uploaded.status)

        response = self.service.diagnose(
            f"Bearer {access_token}",
            uploaded.json_body["session_id"],
            {
                "image_id": uploaded.json_body["image_id"],
                "final_text": "看一下这个接线端子",
                "localProjectId": self.local_project_id,
                "localTaskId": self.local_task_id,
            },
        )

        self.assertEqual(200, response.status)
        list(response.body)
        image_data_url = self.provider.calls[0]["image_data_url"]
        self.assertTrue(image_data_url.startswith("data:image/jpeg;base64,"))
        self.assertEqual(jpeg, base64.b64decode(image_data_url.split(",", 1)[1]))

    def test_rejects_non_jpeg_and_oversized_evidence(self):
        access_token = self.issue_session()
        not_jpeg = base64.b64encode(b"not-a-jpeg").decode("ascii")
        invalid = self.service.upload_image(
            f"Bearer {access_token}",
            "_",
            {"image_base64": not_jpeg},
        )
        self.assertEqual(400, invalid.status)
        self.assertEqual("invalid_jpeg", invalid.json_body["error"])

        oversized_jpeg = b"\xff\xd8" + (b"x" * (1024 * 1024)) + b"\xff\xd9"
        oversized = self.service.upload_image(
            f"Bearer {access_token}",
            "_",
            {"image_base64": base64.b64encode(oversized_jpeg).decode("ascii")},
        )
        self.assertEqual(413, oversized.status)

    def test_provider_failure_is_explicit_and_never_returns_fake_success(self):
        self.sync_authoritative_skill()
        access_token = self.issue_session()
        self.service.provider = FakeProvider(failure=RuntimeError("provider_http_401"))

        response = self.service.diagnose(
            f"Bearer {access_token}",
            "_",
            self.ai_payload("设备无法启动"),
        )

        self.assertEqual(502, response.status)
        self.assertEqual("provider_unavailable", response.json_body["error"])
        self.assertNotIn("text", json.dumps(response.json_body))

    def test_health_response_does_not_disclose_credentials_or_provider(self):
        response = self.service.health()

        self.assertEqual(200, response.status)
        serialized = json.dumps(response.json_body)
        self.assertNotIn(self.bootstrap, serialized)
        self.assertNotIn("server-only-provider-key", serialized)
        self.assertNotIn("dashscope", serialized.lower())

    def test_readiness_checks_local_storage_without_calling_providers(self):
        response = self.service.readiness()

        self.assertEqual(200, response.status)
        self.assertEqual(
            {"ok": True, "service": "dingdang-v9-gateway", "storage": "ready"},
            response.json_body,
        )
        self.assertEqual([], self.provider.calls)

    def test_readiness_fails_closed_when_local_storage_is_unavailable(self):
        with mock.patch.object(
            self.store, "readiness", side_effect=sqlite3.DatabaseError("corrupt")
        ):
            response = self.service.readiness()

        self.assertEqual(503, response.status)
        self.assertEqual("storage_unavailable", response.json_body["error"])
        self.assertNotIn("corrupt", json.dumps(response.json_body))

    def test_rejects_models_other_than_the_previous_stable_model(self):
        with self.assertRaisesRegex(ValueError, "provider_model_not_previous_stable"):
            GatewayConfig(
                bootstrap_token_hash=hashlib.sha256(self.bootstrap.encode()).hexdigest(),
                device_id="air3-YM00FCF3NW0031",
                organization_id="org-huafang",
                user_id="user-field-engineer",
                provider_base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
                provider_api_key="server-only-provider-key",
                provider_model="newly-added-model",
            )

    def test_configuration_fails_closed_when_server_secret_is_missing(self):
        with self.assertRaisesRegex(ValueError, "missing_environment:V9_AI_API_KEY"):
            configuration_from_environment(
                {
                    "V9_BOOTSTRAP_TOKEN_SHA256": hashlib.sha256(
                        self.bootstrap.encode()
                    ).hexdigest(),
                    "V9_DEVICE_ID": "air3-YM00FCF3NW0031",
                    "V9_ORGANIZATION_ID": "org-huafang",
                    "V9_USER_ID": "user-field-engineer",
                    "V9_AI_BASE_URL": "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "V9_AI_MODEL": "qwen3-vl-plus",
                }
            )

    def test_configuration_requires_managed_organization_and_user_identity(self):
        base_environment = {
            "V9_BOOTSTRAP_TOKEN_SHA256": hashlib.sha256(
                self.bootstrap.encode()
            ).hexdigest(),
            "V9_DEVICE_ID": "air3-YM00FCF3NW0031",
            "V9_AI_BASE_URL": "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "V9_AI_API_KEY": "server-only-provider-key",
            "V9_AI_MODEL": "qwen3-vl-plus",
        }
        with self.assertRaisesRegex(
            ValueError,
            "missing_environment:V9_ORGANIZATION_ID,V9_USER_ID",
        ):
            configuration_from_environment(base_environment)

    def test_content_manifest_sync_configuration_is_optional_but_fails_closed_when_partial(self):
        self.assertIsNone(
            content_manifest_sync_worker_from_environment(
                {}, self.service.config, self.store.execution_context
            )
        )

        with self.assertRaisesRegex(
            ValueError,
            "missing_content_manifest_sync_environment:V9_CONTENT_MANIFEST_SYNC_TOKEN",
        ):
            content_manifest_sync_worker_from_environment(
                {
                    "V9_CONTENT_MANIFEST_SYNC_BASE_URL": (
                        "https://project.supabase.co/functions/v1/ops-glasses"
                    ),
                },
                self.service.config,
                self.store.execution_context,
            )

    def test_content_manifest_sync_configuration_uses_separate_server_credentials(self):
        worker = content_manifest_sync_worker_from_environment(
            {
                "V9_CONTENT_MANIFEST_SYNC_BASE_URL": (
                    "https://project.supabase.co/functions/v1/ops-glasses"
                ),
                "V9_CONTENT_MANIFEST_SYNC_TOKEN": "root-only-sync-token",
                "V9_CONTENT_MANIFEST_REFRESH_SECONDS": "30",
                "V9_CONTENT_MANIFEST_MAX_BACKOFF_SECONDS": "300",
            },
            self.service.config,
            self.store.execution_context,
        )

        self.assertIsNotNone(worker)
        self.assertEqual("root-only-sync-token", worker.client.config.sync_token)
        self.assertEqual(30.0, worker.refresh_interval_seconds)
        self.assertEqual(300.0, worker.max_backoff_seconds)
        self.assertEqual(self.service.config.organization_id, worker.organization_id)
        self.assertEqual(self.service.config.user_id, worker.user_id)
        self.assertEqual(self.service.config.device_id, worker.device_id)

    def test_control_plane_sync_configuration_is_optional_but_fails_closed_when_partial(self):
        self.assertIsNone(
            control_plane_sync_worker_from_environment({}, self.store)
        )
        self.assertIsNone(
            control_plane_sync_worker_from_environment(
                {
                    "V9_CONTROL_PLANE_SYNC_RETRY_BASE_SECONDS": "5",
                    "V9_CONTROL_PLANE_SYNC_MAX_BACKOFF_SECONDS": "900",
                    "V9_CONTROL_PLANE_SYNC_POLL_SECONDS": "1",
                    "V9_CONTROL_PLANE_SYNC_BATCH_SIZE": "25",
                },
                self.store,
            )
        )
        with self.assertRaisesRegex(
            ValueError,
            "missing_control_plane_sync_environment:V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN",
        ):
            control_plane_sync_worker_from_environment(
                {
                    "V9_CONTROL_PLANE_SYNC_BASE_URL": (
                        "https://project.supabase.co/functions/v1/ops-glasses"
                    ),
                },
                self.store,
            )

    def test_control_plane_sync_configuration_uses_a_server_only_bootstrap(self):
        manifest_worker = FakeContentManifestSyncWorker()
        worker = control_plane_sync_worker_from_environment(
            {
                "V9_CONTROL_PLANE_SYNC_BASE_URL": (
                    "https://project.supabase.co/functions/v1/ops-glasses"
                ),
                "V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN": "root-only-bootstrap-token",
                "V9_CONTROL_PLANE_SYNC_RETRY_BASE_SECONDS": "3",
                "V9_CONTROL_PLANE_SYNC_MAX_BACKOFF_SECONDS": "120",
                "V9_CONTROL_PLANE_SYNC_POLL_SECONDS": "2",
            },
            self.store,
            manifest_worker,
        )

        self.assertIsNotNone(worker)
        self.assertEqual(
            "root-only-bootstrap-token",
            worker.client.config.bootstrap_credential,
        )
        self.assertEqual(3.0, worker.retry_base_seconds)
        self.assertEqual(120.0, worker.max_backoff_seconds)
        self.assertEqual(2.0, worker.poll_interval_seconds)
        worker.manifest_trigger("project-local-a")
        self.assertEqual(["project-local-a"], manifest_worker.trigger_calls)

    def test_gateway_runtime_starts_and_stops_manifest_worker_around_server(self):
        events = []

        class RuntimeWorker:
            def start(self):
                events.append("worker.start")

            def stop(self):
                events.append("worker.stop")

        class RuntimeMvsWorker:
            def start(self):
                events.append("mvs-worker.start")

            def stop(self):
                events.append("mvs-worker.stop")

        class RuntimeControlPlaneWorker:
            def start(self):
                events.append("control-worker.start")

            def stop(self):
                events.append("control-worker.stop")

        class RuntimeServer:
            def serve_forever(self, poll_interval):
                events.append(("server.serve_forever", poll_interval))
                raise RuntimeError("server_stopped")

            def server_close(self):
                events.append("server.close")

        class RuntimeStore:
            def close(self):
                events.append("store.close")

        class RuntimeVoiceprintStore:
            def close(self):
                events.append("voiceprint.close")

        with self.assertRaisesRegex(RuntimeError, "server_stopped"):
            serve_gateway(
                RuntimeServer(),
                RuntimeStore(),
                RuntimeVoiceprintStore(),
                RuntimeWorker(),
                RuntimeMvsWorker(),
                RuntimeControlPlaneWorker(),
            )

        self.assertEqual(
            [
                "worker.start",
                "control-worker.start",
                "mvs-worker.start",
                ("server.serve_forever", 0.2),
                "mvs-worker.stop",
                "control-worker.stop",
                "worker.stop",
                "server.close",
                "store.close",
                "voiceprint.close",
            ],
            events,
        )

    def test_ai_requires_real_project_and_task_identity(self):
        access_token = self.issue_session()

        response = self.service.diagnose(
            f"Bearer {access_token}",
            "_",
            {"final_text": "设备无法启动"},
        )

        self.assertEqual(400, response.status)
        self.assertEqual("project_task_required", response.json_body["error"])
        self.assertEqual([], self.provider.calls)

    def test_resolves_authoritative_skill_memory_and_instructions_for_provider(self):
        repository = self.store.execution_context
        rules = {
            "applicableWhen": {"systems": ["hvac"]},
            "excludedWhen": {"assetStates": ["decommissioned"]},
            "requiredInputs": ["description"],
            "evidenceSchema": {"required": []},
            "steps": [
                {
                    "id": "read-alarm-code",
                    "instruction": "先读取报警代码",
                    "risk": "low",
                }
            ],
            "safety": {
                "forbiddenActions": ["close_task_without_confirmation"],
                "expertEscalation": ["live_voltage"],
            },
            "outputConstraints": {"maxStepsPerTurn": 1},
            "knowledgeScopes": ["hvac-manuals"],
        }
        self.sync_authoritative_skill(rules=rules)
        repository.activate_task_skill(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_task_id,
            "skill-hvac@1.0.0",
        )
        repository.update_project_memory(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_project_id,
            0,
            "控制器上电后仍报警。",
            ["电源指示灯常亮"],
            ["不是断电故障"],
            ["带电操作风险"],
        )
        repository.put_project_instruction(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_project_id,
            "instruction-controller-alarm",
            0,
            "active",
            {"containsAny": ["报警"], "systems": ["hvac"]},
            "以后先读取报警代码，再检查接线。",
            [],
            "trace-memory-confirmed",
        )
        access_token = self.issue_session()

        response = self.service.diagnose(
            f"Bearer {access_token}",
            "_",
            self.ai_payload("控制器仍然报警", contextAttributes={"system": "hvac"}),
        )

        self.assertEqual(200, response.status)
        list(response.body)
        context = self.provider.calls[0]["context"]
        self.assertIsInstance(context, ExecutionContext)
        self.assertEqual("skill-hvac@1.0.0", context.skill.version_id)
        self.assertEqual(1, context.memory_revision)
        self.assertEqual(
            ["instruction-controller-alarm"],
            [item.instruction_id for item in context.instructions],
        )
        self.assertEqual("控制器仍然报警", context.user_text)
        with self.store.lock:
            run = self.store.connection.execute(
                "SELECT * FROM execution_context_runs"
            ).fetchone()
        self.assertEqual("success", run["status"])
        self.assertEqual("skill-hvac@1.0.0", run["skill_version_id"])
        self.assertEqual(1, run["manifest_version"])
        self.assertEqual(format(1, "064x"), run["manifest_etag"])
        self.assertEqual(1, run["memory_revision"])

    def test_rejects_unknown_task_without_calling_provider(self):
        access_token = self.issue_session()

        response = self.service.diagnose(
            f"Bearer {access_token}",
            "_",
            {
                "finalText": "继续检查",
                "localProjectId": self.local_project_id,
                "localTaskId": "task-not-owned",
            },
        )

        self.assertEqual(404, response.status)
        self.assertEqual("task_not_found", response.json_body["error"])
        self.assertEqual([], self.provider.calls)

    def test_expired_content_manifest_is_explicitly_rejected(self):
        repository = self.store.execution_context
        self.sync_authoritative_skill(
            version_id="skill-expiring@1.0.0",
            expires_at=self.clock.now + 1,
            rules={
                "applicableWhen": {},
                "excludedWhen": {},
                "requiredInputs": ["description"],
                "evidenceSchema": {"required": []},
                "steps": [
                    {
                        "id": "confirm-asset-id",
                        "instruction": "先确认设备编号",
                        "risk": "low",
                    }
                ],
                "safety": {"forbiddenActions": [], "expertEscalation": []},
                "outputConstraints": {"maxStepsPerTurn": 1},
                "knowledgeScopes": [],
            },
        )
        repository.activate_task_skill(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_task_id,
            "skill-expiring@1.0.0",
        )
        self.clock.now += 2
        access_token = self.issue_session()

        response = self.service.diagnose(
            f"Bearer {access_token}",
            "_",
            self.ai_payload("继续检查"),
        )

        self.assertEqual(503, response.status)
        self.assertEqual("content_manifest_expired", response.json_body["error"])
        self.assertEqual([], self.provider.calls)

    def test_device_content_manifest_is_minimal_project_scoped_and_etagged(self):
        access_token = self.issue_session()
        self.sync_authoritative_skill(
            manifest_version=7,
            knowledge=[
                {
                    "knowledgeId": "knowledge-hvac",
                    "versionId": "knowledge-hvac@3",
                    "version": 3,
                    "title": "HVAC service manual",
                    "summary": "Authorized reference",
                    "language": "zh-CN",
                    "sensitivity": "internal",
                    "sourceType": "document",
                    "sourceReference": "kb://hvac/manual-3",
                    "contentSha256": "b" * 64,
                    "knowledgeScopes": ["hvac-manuals"],
                    "validFrom": None,
                    "expiresAt": None,
                    "authorizationScope": "skill_version",
                }
            ],
        )

        response = self.service.get_device_content_manifest(
            f"Bearer {access_token}", self.local_project_id, ""
        )

        self.assertEqual(200, response.status)
        self.assertEqual('"' + format(7, "064x") + '"', response.headers["ETag"])
        self.assertEqual("7", response.headers["X-Manifest-Version"])
        self.assertEqual(self.local_project_id, response.json_body["localProjectId"])
        self.assertEqual("project-cloud-a", response.json_body["projectId"])
        self.assertEqual("skill-hvac@1.0.0", response.json_body["skills"][0]["versionId"])
        self.assertEqual("knowledge-hvac@3", response.json_body["knowledge"][0]["versionId"])
        serialized = json.dumps(response.json_body)
        self.assertNotIn("rules", serialized)
        self.assertNotIn("organizationId", serialized)
        self.assertNotIn("userId", serialized)
        self.assertNotIn("deviceId", serialized)

        not_modified = self.service.get_device_content_manifest(
            f"Bearer {access_token}",
            self.local_project_id,
            response.headers["ETag"],
        )
        self.assertEqual(304, not_modified.status)
        self.assertIsNone(not_modified.json_body)
        self.assertEqual("7", not_modified.headers["X-Manifest-Version"])

    def test_device_content_manifest_replacement_removes_revoked_content(self):
        access_token = self.issue_session()
        self.sync_authoritative_skill(manifest_version=1)
        revoked = authoritative_skill_manifest(
            self.clock,
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            "project-cloud-a",
            manifest_version=2,
        )
        revoked["skills"] = []
        revoked["knowledge"] = []
        self.store.execution_context.sync_authoritative_manifest(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_project_id,
            revoked,
        )

        response = self.service.get_device_content_manifest(
            f"Bearer {access_token}", self.local_project_id, ""
        )

        self.assertEqual(200, response.status)
        self.assertEqual([], response.json_body["skills"])
        self.assertEqual([], response.json_body["knowledge"])
        self.assertEqual(2, response.json_body["manifestVersion"])

    def test_large_history_context_stays_bounded_fast_and_uses_one_provider_call(self):
        repository = self.store.execution_context
        self.sync_authoritative_skill()
        repository.activate_task_skill(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_task_id,
            "skill-hvac@1.0.0",
            self.local_project_id,
        )
        repository.update_project_memory(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_project_id,
            0,
            "冷站控制器长期维护记录。",
            [f"已确认事实 {index}" for index in range(100)],
            [],
            [],
        )
        for index in range(50):
            repository.put_project_instruction(
                "org-huafang",
                "user-field-engineer",
                "air3-YM00FCF3NW0031",
                self.local_project_id,
                f"instruction-benchmark-{index}",
                0,
                "active",
                {},
                f"按项目规则 {index} 执行。",
                [],
                f"trace-benchmark-{index}",
            )
        session_id = self.store.ensure_chat_session(
            self.local_task_id, "air3-YM00FCF3NW0031"
        )
        with self.store.lock:
            self.store.connection.executemany(
                """
                INSERT INTO messages(
                    id, session_id, device_id, role, content,
                    image_id, trace_id, created_at
                ) VALUES (?, ?, ?, ?, ?, NULL, ?, ?)
                """,
                [
                    (
                        f"benchmark-message-{index}",
                        session_id,
                        "air3-YM00FCF3NW0031",
                        "user" if index % 2 == 0 else "assistant",
                        f"历史消息 {index}",
                        f"benchmark-trace-{index}",
                        float(index),
                    )
                    for index in range(1000)
                ],
            )
            self.store.connection.commit()
        access_token = self.issue_session()
        durations_ms = []

        for index in range(20):
            started = time.perf_counter()
            response = self.service.diagnose(
                f"Bearer {access_token}",
                session_id,
                self.ai_payload(f"第 {index} 轮继续检查"),
            )
            self.assertEqual(200, response.status)
            list(response.body)
            durations_ms.append((time.perf_counter() - started) * 1000)

        p95 = sorted(durations_ms)[18]
        self.assertLess(p95, 50.0)
        self.assertEqual(20, len(self.provider.calls))
        self.assertEqual(12, len(self.provider.calls[-1]["context"].recent_messages))

    def test_dashscope_stream_stops_immediately_at_done_event(self):
        from gateway import DashscopeProvider

        response = DoneTerminatedProviderResponse()
        provider = DashscopeProvider(
            self.service.config,
            opener=lambda _request, _timeout: response,
        )
        context = ExecutionContext(
            organization_id="org-huafang",
            user_id="user-field-engineer",
            device_id="air3-YM00FCF3NW0031",
            project_id="project-server-a",
            task_id="task-server-a",
            local_project_id=self.local_project_id,
            local_task_id=self.local_task_id,
            skill=None,
            project_summary="",
            confirmed_facts=(),
            excluded_facts=(),
            risks=(),
            memory_revision=0,
            instructions=(),
            recent_messages=(),
            user_text="probe",
        )
        chunks = list(provider.stream_chat(context, "", "trace-probe"))

        self.assertEqual(["ok"], chunks)
        self.assertTrue(response.closed)

    def test_dashscope_payload_disables_thinking_and_bounds_generation(self):
        from gateway import DashscopeProvider

        captured = {}

        def opener(request, _timeout):
            captured["payload"] = json.loads(request.data.decode("utf-8"))
            return DoneTerminatedProviderResponse()

        provider = DashscopeProvider(self.service.config, opener=opener)
        context = ExecutionContext(
            organization_id="org-huafang",
            user_id="user-field-engineer",
            device_id="air3-YM00FCF3NW0031",
            project_id="project-server-a",
            task_id="task-server-a",
            local_project_id=self.local_project_id,
            local_task_id=self.local_task_id,
            skill=None,
            project_summary="",
            confirmed_facts=(),
            excluded_facts=(),
            risks=(),
            memory_revision=0,
            instructions=(),
            recent_messages=(),
            user_text="probe",
        )

        self.assertEqual(["ok"], list(provider.stream_chat(context, "", "trace-bounded")))
        self.assertIs(False, captured["payload"]["enable_thinking"])
        self.assertEqual(600, captured["payload"]["max_tokens"])

    def test_client_disconnect_closes_provider_and_finalizes_execution(self):
        self.sync_authoritative_skill()
        access_token = self.issue_session()
        provider = DisconnectTrackingProvider()
        self.service.provider = provider

        response = self.service.diagnose(
            f"Bearer {access_token}",
            self.local_task_id,
            self.ai_payload("设备无法启动", contextAttributes={"system": "hvac"}),
        )
        stream = iter(response.body)
        first_chunk = next(stream)
        self.assertIn(b"event: delta", first_chunk)
        stream.close()

        trace_id = provider.calls[0]["trace_id"]
        ai_request = self.store.connection.execute(
            "SELECT status, error_code FROM ai_requests WHERE trace_id = ?",
            (trace_id,),
        ).fetchone()
        execution_run = self.store.connection.execute(
            "SELECT status, error_code FROM execution_context_runs WHERE trace_id = ?",
            (trace_id,),
        ).fetchone()
        self.assertTrue(provider.stream.closed)
        self.assertEqual(("failed", "client_disconnected"), tuple(ai_request))
        self.assertEqual(("failed", "client_disconnected"), tuple(execution_run))

    def test_closing_completed_stream_keeps_success_status(self):
        self.sync_authoritative_skill()
        access_token = self.issue_session()

        response = self.service.diagnose(
            f"Bearer {access_token}",
            self.local_task_id,
            self.ai_payload("设备无法启动", contextAttributes={"system": "hvac"}),
        )
        stream = iter(response.body)
        chunks = list(stream)
        stream.close()

        trace_id = self.provider.calls[0]["trace_id"]
        ai_request = self.store.connection.execute(
            "SELECT status, error_code FROM ai_requests WHERE trace_id = ?",
            (trace_id,),
        ).fetchone()
        execution_run = self.store.connection.execute(
            "SELECT status, error_code FROM execution_context_runs WHERE trace_id = ?",
            (trace_id,),
        ).fetchone()
        self.assertTrue(any(b"event: done" in chunk for chunk in chunks))
        self.assertEqual(("success", None), tuple(ai_request))
        self.assertEqual(("success", None), tuple(execution_run))

    def test_store_startup_recovers_interrupted_ai_and_execution_runs(self):
        with tempfile.TemporaryDirectory() as root:
            database_path = os.path.join(root, "gateway.db")
            evidence_path = os.path.join(root, "evidence")
            store = SqliteStore(database_path, evidence_path, clock=self.clock)
            context = ExecutionContext(
                organization_id="org-huafang",
                user_id="user-field-engineer",
                device_id="air3-YM00FCF3NW0031",
                project_id="project-server-a",
                task_id="task-server-a",
                local_project_id="project-local-a",
                local_task_id="task-local-a",
                skill=None,
                project_summary="",
                confirmed_facts=(),
                excluded_facts=(),
                risks=(),
                memory_revision=0,
                instructions=(),
                recent_messages=(),
                user_text="probe",
            )
            store.start_ai_request(
                "trace-interrupted", "task-local-a", "air3-YM00FCF3NW0031", "qwen3-vl-plus"
            )
            store.execution_context.start_execution_run(
                "trace-interrupted", context, "qwen3-vl-plus"
            )
            store.close()

            recovered = SqliteStore(database_path, evidence_path, clock=self.clock)
            try:
                ai_request = recovered.connection.execute(
                    "SELECT status, error_code, completed_at FROM ai_requests WHERE trace_id = ?",
                    ("trace-interrupted",),
                ).fetchone()
                execution_run = recovered.connection.execute(
                    "SELECT status, error_code, completed_at FROM execution_context_runs WHERE trace_id = ?",
                    ("trace-interrupted",),
                ).fetchone()
                self.assertEqual(("failed", "gateway_restarted"), tuple(ai_request[:2]))
                self.assertEqual(("failed", "gateway_restarted"), tuple(execution_run[:2]))
                self.assertEqual(self.clock.now, ai_request["completed_at"])
                self.assertEqual(self.clock.now, execution_run["completed_at"])
            finally:
                recovered.close()

    def test_unresolved_client_session_reuses_the_same_task_history(self):
        self.sync_authoritative_skill()
        access_token = self.issue_session()

        first = self.service.diagnose(
            f"Bearer {access_token}",
            "_",
            self.ai_payload("服务器无法启动，设备型号为 H3C UniServer R4900 G5。"),
        )
        self.assertEqual(200, first.status)
        list(first.body)

        second = self.service.diagnose(
            f"Bearer {access_token}",
            "_",
            self.ai_payload("刚才的设备型号和故障是什么？"),
        )
        self.assertEqual(200, second.status)
        list(second.body)

        self.assertEqual(
            (
                {
                    "role": "user",
                    "content": "服务器无法启动，设备型号为 H3C UniServer R4900 G5。",
                },
                {"role": "assistant", "content": "先检查设备电源指示灯。"},
            ),
            self.provider.calls[-1]["context"].recent_messages,
        )

    def test_new_task_keeps_project_memory_without_reusing_closed_task_messages(self):
        self.sync_authoritative_skill()
        access_token = self.issue_session()

        first = self.service.diagnose(
            f"Bearer {access_token}",
            "_",
            self.ai_payload("控制器报警，设备型号为 HF-100。"),
        )
        self.assertEqual(200, first.status)
        list(first.body)
        self.store.execution_context.end_task(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_project_id,
            self.local_task_id,
            "closed",
            "旧任务已人工确认关闭。",
            0,
            ["设备型号为 HF-100"],
            [],
            [],
        )
        new_task_id = "task-local-b"
        self.store.append_device_event(
            "air3-YM00FCF3NW0031",
            {
                "localProjectId": self.local_project_id,
                "projectTitle": "冷站年度维护",
                "localTaskId": new_task_id,
                "taskTitle": "控制器复查",
                "eventType": "task_started",
                "payload": {},
                "occurredAt": "2026-08-05T05:00:00.000Z",
                "idempotencyKey": "task-local-b:started:1",
            },
        )

        second = self.service.diagnose(
            f"Bearer {access_token}",
            "_",
            {
                "finalText": "开始新的复查任务。",
                "localProjectId": self.local_project_id,
                "localTaskId": new_task_id,
            },
        )
        self.assertEqual(200, second.status)
        list(second.body)

        context = self.provider.calls[-1]["context"]
        self.assertEqual(new_task_id, context.local_task_id)
        self.assertEqual((), context.recent_messages)
        self.assertEqual(1, context.memory_revision)
        self.assertEqual(("设备型号为 HF-100",), context.confirmed_facts)

    def test_rejects_a_chat_session_id_from_a_different_task(self):
        self.sync_authoritative_skill()
        access_token = self.issue_session()

        first = self.service.diagnose(
            f"Bearer {access_token}",
            "_",
            self.ai_payload("旧任务现场描述。"),
        )
        self.assertEqual(200, first.status)
        list(first.body)
        new_task_id = "task-local-b"
        self.store.append_device_event(
            "air3-YM00FCF3NW0031",
            {
                "localProjectId": self.local_project_id,
                "projectTitle": "冷站年度维护",
                "localTaskId": new_task_id,
                "taskTitle": "新的独立任务",
                "eventType": "task_started",
                "payload": {},
                "occurredAt": "2026-08-05T05:10:00.000Z",
                "idempotencyKey": "task-local-b:started:mismatch",
            },
        )

        response = self.service.diagnose(
            f"Bearer {access_token}",
            self.local_task_id,
            {
                "finalText": "新任务不能继承旧对话。",
                "localProjectId": self.local_project_id,
                "localTaskId": new_task_id,
            },
        )

        self.assertEqual(409, response.status)
        self.assertEqual("task_session_mismatch", response.json_body["error"])
        self.assertEqual(1, len(self.provider.calls))

    def test_dashscope_model_payload_excludes_control_plane_identity(self):
        from gateway import DashscopeProvider

        captured = {}

        def opener(request, _timeout):
            captured["payload"] = json.loads(request.data.decode("utf-8"))
            return DoneTerminatedProviderResponse()

        provider = DashscopeProvider(self.service.config, opener=opener)
        context = ExecutionContext(
            organization_id="org-huafang",
            user_id="user-field-engineer",
            device_id="air3-YM00FCF3NW0031",
            project_id="project-server-a",
            task_id="task-server-a",
            local_project_id=self.local_project_id,
            local_task_id=self.local_task_id,
            skill=None,
            project_summary="服务器无法启动，设备型号为 H3C UniServer R4900 G5。",
            confirmed_facts=("现场设备型号是 R4900 G5",),
            excluded_facts=(),
            risks=(),
            memory_revision=1,
            instructions=(),
            recent_messages=(
                {"role": "user", "content": "服务器无法启动。"},
            ),
            user_text="刚才的设备型号和故障是什么？",
        )

        self.assertEqual(["ok"], list(provider.stream_chat(context, "", "trace-model")))

        system_prompt = captured["payload"]["messages"][0]["content"]
        self.assertIn("执行当前 executionContext 中已匹配且已授权的 Skill 和项目指令", system_prompt)
        self.assertIn("不得套用未出现在当前 executionContext 中的规则", system_prompt)
        model_text = captured["payload"]["messages"][1]["content"][0]["text"]
        model_context = json.loads(model_text)["executionContext"]
        self.assertNotIn("identity", model_context)
        self.assertNotIn("air3-YM00FCF3NW0031", model_text)
        self.assertEqual(
            [{"role": "user", "content": "服务器无法启动。"}],
            model_context["recentMessages"],
        )

    def test_provider_connection_resolves_ipv4_only(self):
        from gateway import _create_ipv4_connection

        connected = mock.Mock()
        address = ("8.152.159.24", 443)
        with mock.patch(
            "gateway.socket.getaddrinfo",
            return_value=[
                (socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", address)
            ],
        ) as resolver, mock.patch("gateway.socket.socket", return_value=connected):
            result = _create_ipv4_connection(("dashscope.aliyuncs.com", 443), 5)

        self.assertIs(connected, result)
        resolver.assert_called_once_with(
            "dashscope.aliyuncs.com", 443, socket.AF_INET, socket.SOCK_STREAM
        )
        connected.settimeout.assert_called_once_with(5)
        connected.connect.assert_called_once_with(address)

    def test_persists_device_events_idempotently_and_updates_task_status(self):
        access_token = self.issue_session()
        event = {
            "localProjectId": "project-local-a",
            "projectTitle": "冷站年度维护",
            "localTaskId": "task-local-a",
            "taskTitle": "冷水机组控制器故障",
            "eventType": "task_completed",
            "payload": {"summary": "现场人工确认完成"},
            "occurredAt": "2026-08-02T04:05:06.000Z",
            "idempotencyKey": "task-local-a:completed:1",
        }

        accepted = self.service.append_device_event(
            f"Bearer {access_token}", event
        )
        duplicate = self.service.append_device_event(
            f"Bearer {access_token}", event
        )

        self.assertEqual(202, accepted.status)
        self.assertFalse(accepted.json_body["duplicate"])
        self.assertEqual(202, duplicate.status)
        self.assertTrue(duplicate.json_body["duplicate"])
        with self.store.lock:
            task = self.store.connection.execute(
                "SELECT status FROM maintenance_tasks WHERE local_task_id = ?",
                ("task-local-a",),
            ).fetchone()
            count = self.store.connection.execute(
                """
                SELECT COUNT(*) AS count FROM task_events
                WHERE idempotency_key = 'task-local-a:completed:1'
                """
            ).fetchone()["count"]
        self.assertEqual("completed", task["status"])
        self.assertEqual(1, count)

    def test_rejects_reused_device_event_idempotency_key_with_changed_content(self):
        access_token = self.issue_session()
        event = {
            "localProjectId": "project-local-a",
            "projectTitle": "冷站年度维护",
            "localTaskId": "task-local-a",
            "taskTitle": "冷水机组控制器故障",
            "eventType": "user_message",
            "payload": {"text": "先检查电源"},
            "occurredAt": "2026-08-05T04:05:06.000Z",
            "idempotencyKey": "task-local-a:user-message:1",
        }
        conflicting = dict(event)
        conflicting["payload"] = {"text": "改为直接复位"}

        accepted = self.service.append_device_event(
            f"Bearer {access_token}", event
        )
        rejected = self.service.append_device_event(
            f"Bearer {access_token}", conflicting
        )

        self.assertEqual(202, accepted.status)
        self.assertEqual(409, rejected.status)
        self.assertEqual("idempotency_conflict", rejected.json_body["error"])

    def test_rejects_invalid_or_unauthorized_device_events(self):
        event = {
            "localProjectId": "project-local-a",
            "localTaskId": "task-local-a",
            "eventType": "invented_success",
            "payload": {},
            "occurredAt": "not-a-timestamp",
            "idempotencyKey": "event-a",
        }

        unauthorized = self.service.append_device_event("Bearer invalid", event)
        access_token = self.issue_session()
        invalid = self.service.append_device_event(
            f"Bearer {access_token}", event
        )

        self.assertEqual(401, unauthorized.status)
        self.assertEqual(400, invalid.status)
        self.assertEqual("invalid_event", invalid.json_body["error"])

    def test_new_task_started_event_only_queues_background_manifest_sync_once(self):
        worker = FakeContentManifestSyncWorker()
        service = GatewayService(
            self.service.config,
            self.store,
            self.provider,
            clock=self.clock,
            content_manifest_sync_worker=worker,
        )
        access_token = service.exchange_device_session(
            f"Bearer {self.bootstrap}"
        ).json_body["accessToken"]
        event = {
            "localProjectId": "project-local-sync",
            "projectTitle": "同步项目",
            "localTaskId": "task-local-sync",
            "taskTitle": "同步任务",
            "eventType": "task_started",
            "payload": {},
            "occurredAt": "2026-08-03T03:00:00.000Z",
            "idempotencyKey": "task-local-sync:started:1",
        }

        first = service.append_device_event(f"Bearer {access_token}", event)
        duplicate = service.append_device_event(f"Bearer {access_token}", event)

        self.assertEqual(202, first.status)
        self.assertFalse(first.json_body["duplicate"])
        self.assertTrue(duplicate.json_body["duplicate"])
        self.assertEqual(["project-local-sync"], worker.trigger_calls)

    def test_control_plane_worker_owns_cloud_delivery_before_manifest_refresh(self):
        manifest_worker = FakeContentManifestSyncWorker()
        control_worker = FakeControlPlaneSyncWorker()
        service = GatewayService(
            self.service.config,
            self.store,
            self.provider,
            clock=self.clock,
            content_manifest_sync_worker=manifest_worker,
            control_plane_sync_worker=control_worker,
        )
        access_token = service.exchange_device_session(
            f"Bearer {self.bootstrap}"
        ).json_body["accessToken"]
        payload = {
            "localProjectId": "project-local-cloud-sync",
            "projectTitle": "云端同步项目",
            "localTaskId": "task-local-cloud-sync",
            "taskTitle": "云端同步任务",
            "eventType": "task_started",
            "payload": {},
            "occurredAt": "2026-08-05T03:00:00.000Z",
            "idempotencyKey": "task-local-cloud-sync:started:1",
        }

        first = service.append_device_event(f"Bearer {access_token}", payload)
        duplicate = service.append_device_event(f"Bearer {access_token}", payload)

        self.assertFalse(first.json_body["duplicate"])
        self.assertTrue(duplicate.json_body["duplicate"])
        self.assertEqual(1, control_worker.trigger_calls)
        self.assertEqual([], manifest_worker.trigger_calls)


class GatewayHttpTest(unittest.TestCase):
    def setUp(self):
        self.bootstrap = "http-bootstrap-secret"
        self.temp = tempfile.TemporaryDirectory()
        self.store = SqliteStore(
            f"{self.temp.name}/gateway.db",
            f"{self.temp.name}/evidence",
        )
        self.local_project_id = "project-local-http"
        self.local_task_id = "task-local-http"
        self.store.append_device_event(
            "air3-YM00FCF3NW0031",
            {
                "localProjectId": self.local_project_id,
                "projectTitle": "HTTP 项目",
                "localTaskId": self.local_task_id,
                "taskTitle": "HTTP 任务",
                "eventType": "task_started",
                "payload": {},
                "occurredAt": "2026-08-03T01:00:00.000Z",
                "idempotencyKey": "task-local-http:started:1",
            },
        )
        self.provider = FakeProvider(["检查电源", "和急停状态。"])
        self.service = GatewayService(
            GatewayConfig(
                bootstrap_token_hash=hashlib.sha256(self.bootstrap.encode()).hexdigest(),
                device_id="air3-YM00FCF3NW0031",
                organization_id="org-huafang",
                user_id="user-field-engineer",
                provider_base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
                provider_api_key="server-only-provider-key",
                provider_model="qwen3-vl-plus",
            ),
            self.store,
            self.provider,
        )
        self.server = GatewayHttpServer(("127.0.0.1", 0), self.service)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.store.close()
        self.temp.cleanup()

    def request(self, path, payload=None, token="", extra_headers=None):
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        headers.update(extra_headers or {})
        return urllib.request.urlopen(
            urllib.request.Request(
                self.base_url + path,
                data=body,
                headers=headers,
                method="GET" if payload is None else "POST",
            ),
            timeout=3,
        )

    def issue_session(self):
        with self.request("/device-sync/session", {}, self.bootstrap) as response:
            self.assertEqual(201, response.status)
            self.assertEqual("no-store", response.headers["Cache-Control"])
            return json.loads(response.read())["accessToken"]

    def publish_authorized_skill(self, version_id="skill-hvac@1.0.0"):
        repository = self.store.execution_context
        repository.sync_authoritative_manifest(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_project_id,
            authoritative_skill_manifest(
                self.store.clock,
                "org-huafang",
                "user-field-engineer",
                "air3-YM00FCF3NW0031",
                "project-cloud-http",
                version_id=version_id,
            ),
        )
        return version_id

    def test_http_exposes_the_etagged_content_manifest_through_the_gateway(self):
        self.publish_authorized_skill()
        access_token = self.issue_session()

        with self.request(
            "/device-sync/content-manifest?localProjectId=" + self.local_project_id,
            token=access_token,
        ) as response:
            payload = json.loads(response.read())

        self.assertEqual(200, response.status)
        self.assertEqual('"' + format(1, "064x") + '"', response.headers["ETag"])
        self.assertEqual(self.local_project_id, payload["localProjectId"])
        self.assertNotIn("rules", json.dumps(payload))

    def provision_assignment(self):
        assignment_id = "33333333-3333-4333-8333-333333333333"
        workflow_version_id = "44444444-4444-4444-8444-444444444444"
        digest = "a" * 64
        self.store.provision_workflow_assignment(
            self.service.config.device_id,
            {
                "assignmentId": assignment_id,
                "workOrderId": "work-order-a",
                "projectId": "project-a",
                "workflowVersionId": workflow_version_id,
                "mode": "required",
                "status": "queued",
                "deliverySequence": 7,
                "assignedAt": "2026-08-02T04:00:00Z",
                "workOrder": {
                    "externalWorkOrderId": "MVS-20260802-001",
                    "title": "冷水机组控制器故障",
                    "description": "控制器报警",
                    "customerId": "customer-a",
                    "workOrderType": "repair",
                    "assetId": "asset-a",
                    "assetCategory": "hvac",
                    "assetBrand": "Huafang",
                    "assetModel": "HF-CH-01",
                    "priority": "high",
                    "riskLevel": "medium",
                    "status": "received",
                    "dueAt": "2026-08-03T04:00:00Z",
                    "receivedAt": "2026-08-02T03:00:00Z",
                },
            },
            {
                "workflowVersionId": workflow_version_id,
                "schemaVersion": 1,
                "executionPackage": {
                    "schemaVersion": 1,
                    "contentSha256": digest,
                    "startNodeId": "photo-a",
                    "nodes": [],
                },
                "contentSha256": digest,
                "packageSignature": "signed-package-a",
                "signatureKeyId": "workflow-key-a",
                "requiredCapabilities": ["workflow.runtime.v1", "camera.photo"],
                "minAppVersionCode": 9000,
            },
        )
        return assignment_id

    def test_http_rejects_an_invalid_bootstrap(self):
        with self.assertRaises(urllib.error.HTTPError) as captured:
            self.request("/device-sync/session", {}, "wrong-bootstrap")

        self.assertEqual(401, captured.exception.code)
        self.assertEqual("unauthorized", json.loads(captured.exception.read())["error"])

    def test_asr_websocket_requires_a_short_session_and_relays_to_provider(self):
        unauthorized = http.client.HTTPConnection(
            "127.0.0.1", self.server.server_port, timeout=2
        )
        try:
            unauthorized.request(
                "GET",
                "/sessions/session-http/asr",
                headers={
                    "Upgrade": "websocket",
                    "Connection": "Upgrade",
                    "Sec-WebSocket-Version": "13",
                    "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ==",
                },
            )
            response = unauthorized.getresponse()
            response.read()
            self.assertEqual(401, response.status)
        finally:
            unauthorized.close()

        access_token = self.issue_session()
        gateway_provider, provider = socket.socketpair()
        gateway_provider.settimeout(2)
        provider.settimeout(2)
        self.service.asr_connector = lambda: gateway_provider
        glasses = socket.create_connection(
            ("127.0.0.1", self.server.server_port), timeout=2
        )
        glasses_reader = glasses.makefile("rb")
        provider_reader = provider.makefile("rb")
        try:
            glasses.sendall(
                (
                    "GET /sessions/session-http/asr HTTP/1.1\r\n"
                    "Host: 127.0.0.1\r\n"
                    "Upgrade: websocket\r\n"
                    "Connection: Upgrade\r\n"
                    "Sec-WebSocket-Version: 13\r\n"
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    f"Authorization: Bearer {access_token}\r\n\r\n"
                ).encode()
            )
            status = glasses_reader.readline().decode()
            headers = {}
            while True:
                line = glasses_reader.readline().decode().strip()
                if not line:
                    break
                key, value = line.split(":", 1)
                headers[key.lower()] = value.strip()

            self.assertIn(" 101 ", status)
            self.assertEqual("websocket", headers["upgrade"].lower())
            self.assertEqual(
                "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", headers["sec-websocket-accept"]
            )

            glasses.sendall(
                encode_websocket_frame(
                    b'{"type":"start","sample_rate":16000,"format":"pcm"}',
                    opcode=0x1,
                    masked=True,
                    mask_key=b"\x01\x02\x03\x04",
                )
            )
            run_task = json.loads(
                read_websocket_frame(provider_reader, expect_masked=True).payload
            )
            provider.sendall(
                encode_websocket_frame(
                    b'{"header":{"event":"task-started"},"payload":{}}',
                    opcode=0x1,
                    masked=False,
                )
            )
            ready = json.loads(
                read_websocket_frame(glasses_reader, expect_masked=False).payload
            )

            self.assertEqual("fun-asr-realtime", run_task["payload"]["model"])
            self.assertEqual("ready", ready["type"])
        finally:
            for stream in (glasses_reader, provider_reader):
                stream.close()
            for active_socket in (glasses, provider, gateway_provider):
                try:
                    active_socket.close()
                except OSError:
                    pass

    def test_session_exchange_consumes_body_before_reusing_http_connection(self):
        connection = http.client.HTTPConnection(
            "127.0.0.1", self.server.server_port, timeout=3
        )
        try:
            connection.request(
                "POST",
                "/device-sync/session",
                body=b"{}",
                headers={
                    "Authorization": f"Bearer {self.bootstrap}",
                    "Content-Type": "application/json",
                },
            )
            session_response = connection.getresponse()
            session_response.read()
            self.assertEqual(201, session_response.status)

            connection.request("GET", "/health")
            health_response = connection.getresponse()
            body = health_response.read()
        finally:
            connection.close()

        self.assertEqual(200, health_response.status)
        self.assertTrue(json.loads(body)["ok"])

    def test_unknown_post_consumes_body_before_reusing_http_connection(self):
        connection = http.client.HTTPConnection(
            "127.0.0.1", self.server.server_port, timeout=3
        )
        try:
            connection.request(
                "POST",
                "/device-sync/unknown",
                body=json.dumps({"eventType": "task_started"}).encode("utf-8"),
                headers={"Content-Type": "application/json"},
            )
            unknown_response = connection.getresponse()
            unknown_response.read()
            self.assertEqual(404, unknown_response.status)

            connection.request("GET", "/health")
            health_response = connection.getresponse()
            body = health_response.read()
        finally:
            connection.close()

        self.assertEqual(200, health_response.status)
        self.assertTrue(json.loads(body)["ok"])

    def test_http_exchanges_session_and_streams_ai_events(self):
        self.publish_authorized_skill()
        access_token = self.issue_session()

        with self.request(
            "/sessions/_/diagnose/stream",
            {
                "final_text": "设备无法启动",
                "localProjectId": self.local_project_id,
                "localTaskId": self.local_task_id,
            },
            access_token,
        ) as response:
            body = response.read().decode("utf-8")

        self.assertEqual("text/event-stream; charset=utf-8", response.headers["Content-Type"])
        self.assertIn('event: delta\ndata: {"text": "检查电源"}', body)
        self.assertIn("event: done", body)
        self.assertEqual("设备无法启动", self.provider.calls[0]["prompt"])

    def test_device_skill_routes_list_activate_and_deactivate_with_confirmation(self):
        version_id = self.publish_authorized_skill()
        access_token = self.issue_session()
        query = urllib.parse.urlencode(
            {
                "localProjectId": self.local_project_id,
                "localTaskId": self.local_task_id,
            }
        )

        with self.request(
            "/device-sync/skills?" + query,
            token=access_token,
        ) as listed_response:
            listed = json.loads(listed_response.read())

        self.assertEqual("no-store", listed_response.headers["Cache-Control"])
        self.assertEqual([version_id], [item["versionId"] for item in listed["items"]])
        self.assertFalse(listed["items"][0]["activeForTask"])
        self.assertNotIn("rules", listed["items"][0])
        self.assertNotIn("先读取报警代码", json.dumps(listed, ensure_ascii=False))

        with self.assertRaises(urllib.error.HTTPError) as missing_confirmation:
            self.request(
                f"/device-sync/tasks/{self.local_task_id}/skill",
                {
                    "action": "activate",
                    "localProjectId": self.local_project_id,
                    "skillVersionId": version_id,
                    "idempotencyKey": "activate-skill-http-a",
                },
                access_token,
            )
        self.assertEqual(400, missing_confirmation.exception.code)
        self.assertEqual(
            "confirmation_required",
            json.loads(missing_confirmation.exception.read())["error"],
        )

        activate_payload = {
            "action": "activate",
            "localProjectId": self.local_project_id,
            "skillVersionId": version_id,
            "confirmation": "ACTIVATE_SKILL",
            "idempotencyKey": "activate-skill-http-a",
        }
        with self.request(
            f"/device-sync/tasks/{self.local_task_id}/skill",
            activate_payload,
            access_token,
        ) as activated_response:
            activated = json.loads(activated_response.read())
        with self.request(
            f"/device-sync/tasks/{self.local_task_id}/skill",
            activate_payload,
            access_token,
        ) as duplicate_response:
            duplicate = json.loads(duplicate_response.read())

        self.assertEqual(version_id, activated["skill"]["versionId"])
        self.assertNotIn("rules", activated["skill"])
        self.assertFalse(activated["duplicate"])
        self.assertTrue(duplicate["duplicate"])
        self.assertEqual("no-store", activated_response.headers["Cache-Control"])

        deactivate_payload = {
            "action": "deactivate",
            "localProjectId": self.local_project_id,
            "confirmation": "DEACTIVATE_SKILL",
            "idempotencyKey": "deactivate-skill-http-a",
        }
        with self.request(
            f"/device-sync/tasks/{self.local_task_id}/skill",
            deactivate_payload,
            access_token,
        ) as deactivated_response:
            deactivated = json.loads(deactivated_response.read())

        self.assertFalse(deactivated["active"])
        with self.request(
            "/device-sync/skills?" + query,
            token=access_token,
        ) as relisted_response:
            relisted = json.loads(relisted_response.read())
        self.assertFalse(relisted["items"][0]["activeForTask"])

        reactivate_payload = dict(
            activate_payload,
            idempotencyKey="reactivate-skill-http-a",
        )
        with self.request(
            f"/device-sync/tasks/{self.local_task_id}/skill",
            reactivate_payload,
            access_token,
        ) as reactivated_response:
            reactivated = json.loads(reactivated_response.read())
        self.assertTrue(reactivated["active"])

    def test_task_skill_activation_rejects_project_mismatch_and_cross_device_project(self):
        version_id = self.publish_authorized_skill()
        self.store.append_device_event(
            "air3-YM00FCF3NW0031",
            {
                "localProjectId": "project-local-http-b",
                "projectTitle": "另一个项目",
                "localTaskId": "task-local-http-b",
                "taskTitle": "另一个任务",
                "eventType": "task_started",
                "payload": {},
                "occurredAt": "2026-08-03T01:10:00.000Z",
                "idempotencyKey": "task-local-http-b:started:1",
            },
        )
        self.store.append_device_event(
            "air3-other-device",
            {
                "localProjectId": "project-other-device",
                "projectTitle": "其他设备项目",
                "localTaskId": "task-other-device",
                "taskTitle": "其他设备任务",
                "eventType": "task_started",
                "payload": {},
                "occurredAt": "2026-08-03T01:10:00.000Z",
                "idempotencyKey": "task-other-device:started:1",
            },
        )
        access_token = self.issue_session()

        with self.assertRaises(urllib.error.HTTPError) as mismatch:
            self.request(
                "/device-sync/tasks/task-local-http-b/skill",
                {
                    "action": "activate",
                    "localProjectId": self.local_project_id,
                    "skillVersionId": version_id,
                    "confirmation": "ACTIVATE_SKILL",
                    "idempotencyKey": "activate-skill-project-mismatch",
                },
                access_token,
            )
        self.assertEqual(404, mismatch.exception.code)
        self.assertEqual("task_not_found", json.loads(mismatch.exception.read())["error"])

        with self.assertRaises(urllib.error.HTTPError) as cross_device:
            self.request(
                "/device-sync/projects/project-other-device",
                token=access_token,
            )
        self.assertEqual(404, cross_device.exception.code)
        self.assertEqual(
            "project_not_found",
            json.loads(cross_device.exception.read())["error"],
        )

    def test_device_project_routes_return_memory_tasks_and_instructions(self):
        repository = self.store.execution_context
        repository.sync_authoritative_manifest(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_project_id,
            authoritative_skill_manifest(
                self.store.clock,
                "org-huafang",
                "user-field-engineer",
                "air3-YM00FCF3NW0031",
                "project-cloud-http",
                manifest_version=7,
                knowledge=[
                    {
                        "knowledgeId": "knowledge-hvac",
                        "versionId": "knowledge-hvac@3",
                        "version": 3,
                        "title": "HVAC service manual",
                        "summary": "Authorized reference",
                        "language": "zh-CN",
                        "sensitivity": "internal",
                        "sourceType": "document",
                        "sourceReference": "kb://hvac/manual-3",
                        "contentSha256": "b" * 64,
                        "knowledgeScopes": ["hvac-manuals"],
                        "validFrom": None,
                        "expiresAt": None,
                        "authorizationScope": "project",
                    }
                ],
            ),
        )
        repository.activate_task_skill(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_task_id,
            "skill-hvac@1.0.0",
        )
        repository.update_project_memory(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_project_id,
            0,
            "控制器已恢复供电，仍需观察报警。",
            ["电源指示灯常亮"],
            ["不是完全断电"],
            ["带电操作风险"],
        )
        repository.put_project_instruction(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_project_id,
            "instruction-http-a",
            0,
            "active",
            {"systems": ["hvac"]},
            "以后先记录报警代码。",
            [],
            "trace-http-memory-a",
        )
        access_token = self.issue_session()

        with self.request("/device-sync/projects", token=access_token) as list_response:
            projects = json.loads(list_response.read())
        with self.request(
            f"/device-sync/projects/{self.local_project_id}",
            token=access_token,
        ) as detail_response:
            detail = json.loads(detail_response.read())

        self.assertEqual("no-store", list_response.headers["Cache-Control"])
        self.assertEqual("no-store", detail_response.headers["Cache-Control"])
        self.assertEqual(
            [self.local_project_id],
            [item["localProjectId"] for item in projects["items"]],
        )
        self.assertEqual(1, detail["projectMemory"]["revision"])
        self.assertEqual(
            [self.local_task_id],
            [item["localTaskId"] for item in detail["tasks"]],
        )
        self.assertEqual("skill-hvac@1.0.0", detail["tasks"][0]["skillVersionId"])
        self.assertEqual(
            ["knowledge-hvac@3"], detail["tasks"][0]["knowledgeVersionIds"]
        )
        self.assertEqual(7, detail["tasks"][0]["contentManifestVersion"])
        self.assertTrue(detail["tasks"][0]["contentSnapshotActive"])
        self.assertEqual(
            ["instruction-http-a"],
            [item["instructionId"] for item in detail["projectInstructions"]],
        )

    def test_task_end_summary_requires_confirmation_is_idempotent_and_closes_context(self):
        self.publish_authorized_skill()
        self.store.execution_context.activate_task_skill(
            "org-huafang",
            "user-field-engineer",
            "air3-YM00FCF3NW0031",
            self.local_task_id,
            "skill-hvac@1.0.0",
        )
        access_token = self.issue_session()
        path = f"/device-sync/tasks/{self.local_task_id}/end-summary"
        base_payload = {
            "localProjectId": self.local_project_id,
            "taskStatus": "completed",
            "summary": "现场问题：控制器报警\n处理结果：人工确认恢复正常。",
            "confirmedFacts": ["报警已清除"],
            "excludedFacts": ["不是电源故障"],
            "risks": [],
            "expectedMemoryRevision": 0,
            "idempotencyKey": "end-task-http-a",
        }

        with self.assertRaises(urllib.error.HTTPError) as missing_confirmation:
            self.request(path, base_payload, access_token)
        self.assertEqual(400, missing_confirmation.exception.code)
        self.assertEqual(
            "confirmation_required",
            json.loads(missing_confirmation.exception.read())["error"],
        )

        invalid_control = dict(
            base_payload,
            summary="现场问题：控制器报警\u0001处理结果：人工确认恢复正常。",
            confirmation="END_TASK",
            idempotencyKey="end-task-http-control",
        )
        with self.assertRaises(urllib.error.HTTPError) as invalid_summary:
            self.request(path, invalid_control, access_token)
        self.assertEqual(400, invalid_summary.exception.code)
        self.assertEqual(
            "task_summary_invalid",
            json.loads(invalid_summary.exception.read())["error"],
        )

        payload = dict(base_payload, confirmation="END_TASK")
        with self.request(path, payload, access_token) as ended_response:
            ended = json.loads(ended_response.read())
        with self.request(path, payload, access_token) as duplicate_response:
            duplicate = json.loads(duplicate_response.read())

        self.assertEqual("completed", ended["taskStatus"])
        self.assertEqual(1, ended["memoryRevision"])
        self.assertFalse(ended["duplicate"])
        self.assertTrue(duplicate["duplicate"])
        self.assertEqual("no-store", ended_response.headers["Cache-Control"])

        with self.request(
            f"/device-sync/projects/{self.local_project_id}", token=access_token
        ) as project_response:
            completed_project = json.loads(project_response.read())
        completed_task = completed_project["tasks"][0]
        self.assertEqual("skill-hvac@1.0.0", completed_task["skillVersionId"])
        self.assertFalse(completed_task["contentSnapshotActive"])
        self.assertEqual(
            "现场问题：控制器报警\n处理结果：人工确认恢复正常。",
            completed_task["endSummary"],
        )

        changed_key = dict(payload, idempotencyKey="end-task-http-b")
        with self.assertRaises(urllib.error.HTTPError) as closed_task:
            self.request(path, changed_key, access_token)
        self.assertEqual(409, closed_task.exception.code)
        self.assertEqual(
            "task_not_active",
            json.loads(closed_task.exception.read())["error"],
        )

    def test_project_instruction_requires_confirmation_version_and_idempotency(self):
        access_token = self.issue_session()
        path = f"/device-sync/projects/{self.local_project_id}/instructions"
        base_payload = {
            "instructionId": "instruction-http-confirmed",
            "expectedVersion": 0,
            "status": "active",
            "condition": {"containsAny": ["报警"]},
            "action": "以后先读取报警代码再检查接线。",
            "exceptions": ["设备完全断电时先检查供电"],
            "sourceTraceId": "trace-http-confirmed",
            "idempotencyKey": "instruction-http-a",
        }

        with self.assertRaises(urllib.error.HTTPError) as missing_confirmation:
            self.request(path, base_payload, access_token)
        self.assertEqual(400, missing_confirmation.exception.code)

        payload = dict(
            base_payload,
            confirmation="CONFIRM_PROJECT_INSTRUCTION",
        )
        with self.request(path, payload, access_token) as created_response:
            created = json.loads(created_response.read())
        with self.request(path, payload, access_token) as duplicate_response:
            duplicate = json.loads(duplicate_response.read())

        self.assertEqual(1, created["version"])
        self.assertFalse(created["duplicate"])
        self.assertTrue(duplicate["duplicate"])
        self.assertEqual("no-store", created_response.headers["Cache-Control"])

        edited_payload = dict(
            payload,
            expectedVersion=1,
            action="以后先读取报警代码，再检查控制器供电。",
            sourceTraceId="trace-http-edited",
            idempotencyKey="instruction-http-edit",
        )
        with self.request(path, edited_payload, access_token) as edited_response:
            edited = json.loads(edited_response.read())
        self.assertEqual(2, edited["version"])
        self.assertEqual("active", edited["status"])

        no_change = dict(
            edited_payload,
            expectedVersion=2,
            sourceTraceId="trace-http-noop",
            idempotencyKey="instruction-http-noop",
        )
        with self.assertRaises(urllib.error.HTTPError) as no_change_response:
            self.request(path, no_change, access_token)
        self.assertEqual(409, no_change_response.exception.code)
        self.assertEqual(
            "project_instruction_no_change",
            json.loads(no_change_response.exception.read())["error"],
        )

        version = 2
        for target_status in ("disabled", "active", "deleted"):
            version += 1
            lifecycle_payload = dict(
                edited_payload,
                expectedVersion=version - 1,
                status=target_status,
                sourceTraceId=f"trace-http-{target_status}",
                idempotencyKey=f"instruction-http-{target_status}",
            )
            with self.request(path, lifecycle_payload, access_token) as lifecycle_response:
                lifecycle = json.loads(lifecycle_response.read())
            self.assertEqual(version, lifecycle["version"])
            self.assertEqual(target_status, lifecycle["status"])

        resurrect = dict(
            edited_payload,
            expectedVersion=5,
            status="active",
            sourceTraceId="trace-http-resurrect",
            idempotencyKey="instruction-http-resurrect",
        )
        with self.assertRaises(urllib.error.HTTPError) as resurrect_response:
            self.request(path, resurrect, access_token)
        self.assertEqual(409, resurrect_response.exception.code)
        self.assertEqual(
            "project_instruction_transition_invalid",
            json.loads(resurrect_response.exception.read())["error"],
        )

        stale = dict(payload, idempotencyKey="instruction-http-b")
        with self.assertRaises(urllib.error.HTTPError) as version_conflict:
            self.request(path, stale, access_token)
        self.assertEqual(409, version_conflict.exception.code)
        self.assertEqual(
            "project_instruction_version_conflict",
            json.loads(version_conflict.exception.read())["error"],
        )

    def test_http_accepts_idempotent_device_events_with_short_session(self):
        access_token = self.issue_session()
        payload = {
            "localProjectId": "project-local-a",
            "projectTitle": "冷站年度维护",
            "localTaskId": "task-local-a",
            "taskTitle": "冷水机组控制器故障",
            "eventType": "task_started",
            "payload": {"source": "air3"},
            "occurredAt": "2026-08-02T04:05:06.000Z",
            "idempotencyKey": "task-local-a:started:1",
        }

        with self.request("/device-sync/events", payload, access_token) as response:
            body = json.loads(response.read())

        self.assertEqual(202, response.status)
        self.assertFalse(body["duplicate"])

    def test_workflow_delivery_is_empty_until_a_real_assignment_exists(self):
        access_token = self.issue_session()

        with self.request(
            "/device-sync/workflows/assignments?afterSequence=0&limit=50",
            token=access_token,
        ) as response:
            body = json.loads(response.read())

        self.assertEqual(200, response.status)
        self.assertEqual([], body["items"])
        self.assertEqual(0, body["nextSequence"])

    def test_workflow_delivery_returns_a_bound_signed_package_with_capability_gate(self):
        access_token = self.issue_session()
        assignment_id = self.provision_assignment()

        with self.request(
            "/device-sync/workflows/assignments?afterSequence=0&limit=50",
            token=access_token,
        ) as list_response:
            listed = json.loads(list_response.read())
        with self.request(
            f"/device-sync/workflows/assignments/{assignment_id}/package",
            token=access_token,
            extra_headers={
                "X-App-Version-Code": "9010",
                "X-Workflow-Schema-Version": "1",
                "X-Workflow-Capabilities": "workflow.runtime.v1,camera.photo",
            },
        ) as package_response:
            package = json.loads(package_response.read())

        self.assertEqual(200, list_response.status)
        self.assertEqual(7, listed["nextSequence"])
        self.assertEqual("冷水机组控制器故障", listed["items"][0]["workOrder"]["title"])
        self.assertEqual(200, package_response.status)
        self.assertEqual(assignment_id, package["assignmentId"])
        self.assertEqual("signed-package-a", package["packageSignature"])
        self.assertEqual("no-store", package_response.headers["Cache-Control"])

        with self.assertRaises(urllib.error.HTTPError) as incompatible:
            self.request(
                f"/device-sync/workflows/assignments/{assignment_id}/package",
                token=access_token,
                extra_headers={
                    "X-App-Version-Code": "9010",
                    "X-Workflow-Schema-Version": "1",
                    "X-Workflow-Capabilities": "workflow.runtime.v1",
                },
            )
        self.assertEqual(409, incompatible.exception.code)
        self.assertEqual(
            "workflow_incompatible",
            json.loads(incompatible.exception.read())["error"],
        )

    def test_workflow_status_execution_and_steps_are_idempotently_persisted(self):
        access_token = self.issue_session()
        assignment_id = self.provision_assignment()
        execution_id = "11111111-1111-4111-8111-111111111111"

        status_payload = {
            "status": "verified",
            "idempotencyKey": f"{assignment_id}:verified",
            "failureStage": None,
            "failureReason": None,
        }
        with self.request(
            f"/device-sync/workflows/assignments/{assignment_id}/status",
            status_payload,
            access_token,
        ) as status_response:
            status_body = json.loads(status_response.read())

        execution_payload = {
            "executionId": execution_id,
            "assignmentId": assignment_id,
            "projectId": "project-a",
            "localTaskId": "workflow-task-a",
            "initialNodeId": "photo-a",
            "runtimeSnapshot": {"nodeId": "photo-a"},
            "idempotencyKey": f"{assignment_id}:start",
        }
        with self.request(
            "/device-sync/workflows/executions", execution_payload, access_token
        ) as execution_response:
            execution_body = json.loads(execution_response.read())
        with self.request(
            "/device-sync/workflows/executions", execution_payload, access_token
        ) as duplicate_execution_response:
            duplicate_execution_body = json.loads(duplicate_execution_response.read())

        step_payload = {
            "nodeId": "photo-a",
            "attemptNumber": 1,
            "status": "completed",
            "idempotencyKey": f"{execution_id}:photo-a:1:completed",
            "inputData": {},
            "outputData": {"confirmed": True},
            "evidenceAssetIds": [],
            "transitionResult": {"matched": True},
            "failureCode": None,
            "failureReason": None,
            "nextNodeId": "complete-a",
            "runtimeSnapshot": {"nodeId": "complete-a"},
        }
        with self.request(
            f"/device-sync/workflows/executions/{execution_id}/steps",
            step_payload,
            access_token,
        ) as step_response:
            step_body = json.loads(step_response.read())
        with self.request(
            f"/device-sync/workflows/executions/{execution_id}/steps",
            step_payload,
            access_token,
        ) as duplicate_step_response:
            duplicate_step_body = json.loads(duplicate_step_response.read())

        self.assertEqual(200, status_response.status)
        self.assertEqual("verified", status_body["status"])
        self.assertEqual(201, execution_response.status)
        self.assertEqual(execution_id, execution_body["executionId"])
        self.assertEqual(execution_body, duplicate_execution_body)
        self.assertEqual(200, step_response.status)
        self.assertEqual("complete-a", step_body["nextNodeId"])
        self.assertEqual(step_body, duplicate_step_body)
        with self.store.lock:
            execution_count = self.store.connection.execute(
                "SELECT COUNT(*) AS count FROM workflow_executions"
            ).fetchone()["count"]
            step_count = self.store.connection.execute(
                "SELECT COUNT(*) AS count FROM workflow_step_events"
            ).fetchone()["count"]
        self.assertEqual(1, execution_count)
        self.assertEqual(1, step_count)

    def test_workflow_execution_rejects_a_project_mismatch(self):
        access_token = self.issue_session()
        assignment_id = self.provision_assignment()

        with self.assertRaises(urllib.error.HTTPError) as mismatch:
            self.request(
                "/device-sync/workflows/executions",
                {
                    "executionId": "11111111-1111-4111-8111-111111111111",
                    "assignmentId": assignment_id,
                    "projectId": "other-project",
                    "localTaskId": "workflow-task-a",
                    "initialNodeId": "photo-a",
                    "runtimeSnapshot": {},
                    "idempotencyKey": f"{assignment_id}:start",
                },
                access_token,
            )

        self.assertEqual(404, mismatch.exception.code)
        self.assertEqual("not_found", json.loads(mismatch.exception.read())["error"])
        with self.store.lock:
            count = self.store.connection.execute(
                "SELECT COUNT(*) AS count FROM workflow_executions"
            ).fetchone()["count"]
        self.assertEqual(0, count)

    def test_workflow_evidence_is_verified_stored_and_idempotent(self):
        access_token = self.issue_session()
        assignment_id = self.provision_assignment()
        execution_id = "11111111-1111-4111-8111-111111111111"
        with self.request(
            "/device-sync/workflows/executions",
            {
                "executionId": execution_id,
                "assignmentId": assignment_id,
                "projectId": "project-a",
                "localTaskId": "workflow-task-a",
                "initialNodeId": "photo-a",
                "runtimeSnapshot": {},
                "idempotencyKey": f"{assignment_id}:start",
            },
            access_token,
        ) as started:
            started.read()

        jpeg = b"\xff\xd8\xff\xe0field-evidence\xff\xd9"
        digest = hashlib.sha256(jpeg).hexdigest()
        evidence = {
            "assignmentId": assignment_id,
            "executionId": execution_id,
            "localEvidenceId": "workflow-photo-local-1",
            "nodeId": "photo-a",
            "evidenceKey": "nameplate",
            "kind": "photo",
            "contentType": "image/jpeg",
            "durationSeconds": 0,
            "byteSize": len(jpeg),
            "sha256": digest,
            "dataBase64": base64.b64encode(jpeg).decode("ascii"),
            "capturedAt": "2026-08-02T04:05:06.000Z",
        }
        with self.request(
            "/device-sync/workflows/evidence", evidence, access_token
        ) as uploaded:
            first = json.loads(uploaded.read())
        with self.request(
            "/device-sync/workflows/evidence", evidence, access_token
        ) as retried:
            second = json.loads(retried.read())

        self.assertEqual(201, uploaded.status)
        self.assertEqual(first, second)
        self.assertEqual("synced", first["uploadStatus"])
        self.assertEqual(digest, first["sha256"])
        self.assertEqual(len(jpeg), first["byteSize"])
        self.assertRegex(
            first["assetId"],
            r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
        with self.store.lock:
            row = self.store.connection.execute(
                "SELECT file_path FROM workflow_evidence"
            ).fetchone()
            count = self.store.connection.execute(
                "SELECT COUNT(*) AS count FROM workflow_evidence"
            ).fetchone()["count"]
        self.assertEqual(1, count)
        with open(row["file_path"], "rb") as stored:
            self.assertEqual(jpeg, stored.read())

        conflict = dict(evidence)
        conflict_bytes = b"\xff\xd8\xff\xe0different\xff\xd9"
        conflict["byteSize"] = len(conflict_bytes)
        conflict["sha256"] = hashlib.sha256(conflict_bytes).hexdigest()
        conflict["dataBase64"] = base64.b64encode(conflict_bytes).decode("ascii")
        with self.assertRaises(urllib.error.HTTPError) as mismatch:
            self.request("/device-sync/workflows/evidence", conflict, access_token)
        self.assertEqual(409, mismatch.exception.code)
        self.assertEqual(
            "workflow_evidence_conflict",
            json.loads(mismatch.exception.read())["error"],
        )

    def test_workflow_evidence_rejects_media_magic_or_digest_mismatch(self):
        access_token = self.issue_session()
        assignment_id = self.provision_assignment()
        execution_id = "11111111-1111-4111-8111-111111111111"
        with self.request(
            "/device-sync/workflows/executions",
            {
                "executionId": execution_id,
                "assignmentId": assignment_id,
                "projectId": "project-a",
                "localTaskId": "workflow-task-a",
                "initialNodeId": "photo-a",
                "runtimeSnapshot": {},
                "idempotencyKey": f"{assignment_id}:start",
            },
            access_token,
        ) as started:
            started.read()
        invalid = b"not-a-jpeg"

        with self.assertRaises(urllib.error.HTTPError) as rejected:
            self.request(
                "/device-sync/workflows/evidence",
                {
                    "assignmentId": assignment_id,
                    "executionId": execution_id,
                    "localEvidenceId": "workflow-photo-local-1",
                    "nodeId": "photo-a",
                    "evidenceKey": "nameplate",
                    "kind": "photo",
                    "contentType": "image/jpeg",
                    "durationSeconds": 0,
                    "byteSize": len(invalid),
                    "sha256": "0" * 64,
                    "dataBase64": base64.b64encode(invalid).decode("ascii"),
                    "capturedAt": "2026-08-02T04:05:06.000Z",
                },
                access_token,
            )

        self.assertEqual(400, rejected.exception.code)
        self.assertEqual(
            "invalid_workflow_evidence",
            json.loads(rejected.exception.read())["error"],
        )

    def test_workflow_evidence_resumable_upload_resumes_missing_chunks_and_finalizes(self):
        access_token = self.issue_session()
        assignment_id = self.provision_assignment()
        execution_id = "11111111-1111-4111-8111-111111111111"
        with self.request(
            "/device-sync/workflows/executions",
            {
                "executionId": execution_id,
                "assignmentId": assignment_id,
                "projectId": "project-a",
                "localTaskId": "workflow-task-a",
                "initialNodeId": "photo-a",
                "runtimeSnapshot": {},
                "idempotencyKey": f"{assignment_id}:start",
            },
            access_token,
        ) as started:
            started.read()

        jpeg = b"\xff\xd8\xff\xe0" + b"resumable-evidence-" * 2 + b"\xff\xd9"
        chunk_size = 16
        chunks = [jpeg[index:index + chunk_size] for index in range(0, len(jpeg), chunk_size)]
        digest = hashlib.sha256(jpeg).hexdigest()
        session_payload = {
            "assignmentId": assignment_id,
            "executionId": execution_id,
            "localEvidenceId": "workflow-photo-resumable-1",
            "nodeId": "photo-a",
            "evidenceKey": "nameplate",
            "kind": "photo",
            "contentType": "image/jpeg",
            "durationSeconds": 0,
            "byteSize": len(jpeg),
            "sha256": digest,
            "capturedAt": "2026-08-02T04:05:06.000Z",
            "chunkSize": chunk_size,
            "chunkCount": len(chunks),
        }
        with self.request(
            "/device-sync/workflows/evidence/session", session_payload, access_token
        ) as session_response:
            session = json.loads(session_response.read())
        self.assertEqual(201, session_response.status)
        self.assertEqual("uploading", session["uploadStatus"])
        self.assertEqual(0, session["nextChunkIndex"])

        def chunk_payload(index):
            value = chunks[index]
            return {
                "uploadId": session["uploadId"],
                "chunkIndex": index,
                "chunkCount": len(chunks),
                "chunkByteSize": len(value),
                "chunkSha256": hashlib.sha256(value).hexdigest(),
                "dataBase64": base64.b64encode(value).decode("ascii"),
            }

        with self.request(
            "/device-sync/workflows/evidence/chunk", chunk_payload(0), access_token
        ) as first_chunk:
            first_chunk_body = json.loads(first_chunk.read())
        with self.request(
            "/device-sync/workflows/evidence/chunk", chunk_payload(len(chunks) - 1), access_token
        ) as last_chunk:
            last_chunk_body = json.loads(last_chunk.read())
        self.assertEqual(200, first_chunk.status)
        self.assertEqual(200, last_chunk.status)
        self.assertEqual(1, first_chunk_body["nextChunkIndex"])
        self.assertEqual(1, last_chunk_body["nextChunkIndex"])

        with self.request(
            "/device-sync/workflows/evidence/session", session_payload, access_token
        ) as resumed_session_response:
            resumed_session = json.loads(resumed_session_response.read())
        self.assertEqual(200, resumed_session_response.status)
        self.assertEqual(session["uploadId"], resumed_session["uploadId"])
        self.assertEqual(1, resumed_session["nextChunkIndex"])
        self.assertEqual([0, len(chunks) - 1], resumed_session["receivedChunks"])

        with self.request(
            "/device-sync/workflows/evidence/chunk", chunk_payload(1), access_token
        ) as middle_chunk:
            middle_chunk.read()
        with self.request(
            "/device-sync/workflows/evidence/complete",
            {
                "uploadId": session["uploadId"],
                "chunkCount": len(chunks),
                "sha256": digest,
            },
            access_token,
        ) as completed:
            completed_body = json.loads(completed.read())
        self.assertEqual(201, completed.status)
        self.assertEqual("synced", completed_body["uploadStatus"])
        self.assertEqual(digest, completed_body["sha256"])

        with self.store.lock:
            row = self.store.connection.execute(
                "SELECT file_path FROM workflow_evidence WHERE asset_id = ?",
                (session["assetId"],),
            ).fetchone()
            part_count = self.store.connection.execute(
                "SELECT COUNT(*) AS count FROM workflow_evidence_parts WHERE asset_id = ?",
                (session["assetId"],),
            ).fetchone()["count"]
        self.assertEqual(0, part_count)
        with open(row["file_path"], "rb") as stored:
            self.assertEqual(jpeg, stored.read())

        with self.request(
            "/device-sync/workflows/evidence/complete",
            {
                "uploadId": session["uploadId"],
                "chunkCount": len(chunks),
                "sha256": digest,
            },
            access_token,
        ) as replayed:
            replayed_body = json.loads(replayed.read())
        self.assertEqual(200, replayed.status)
        self.assertEqual(completed_body, replayed_body)

    def test_workflow_evidence_resumable_upload_can_be_cancelled_and_restarted(self):
        access_token = self.issue_session()
        assignment_id = self.provision_assignment()
        execution_id = "11111111-1111-4111-8111-111111111111"
        with self.request(
            "/device-sync/workflows/executions",
            {
                "executionId": execution_id,
                "assignmentId": assignment_id,
                "projectId": "project-a",
                "localTaskId": "workflow-task-a",
                "initialNodeId": "photo-a",
                "runtimeSnapshot": {},
                "idempotencyKey": f"{assignment_id}:start",
            },
            access_token,
        ) as started:
            started.read()

        jpeg = b"\xff\xd8\xff\xe0cancelled-resumable-evidence\xff\xd9"
        chunk_size = 16
        chunks = [jpeg[index:index + chunk_size] for index in range(0, len(jpeg), chunk_size)]
        session_payload = {
            "assignmentId": assignment_id,
            "executionId": execution_id,
            "localEvidenceId": "workflow-photo-cancel-1",
            "nodeId": "photo-a",
            "evidenceKey": "nameplate",
            "kind": "photo",
            "contentType": "image/jpeg",
            "durationSeconds": 0,
            "byteSize": len(jpeg),
            "sha256": hashlib.sha256(jpeg).hexdigest(),
            "capturedAt": "2026-08-05T15:00:00.000Z",
            "chunkSize": chunk_size,
            "chunkCount": len(chunks),
        }
        with self.request(
            "/device-sync/workflows/evidence/session", session_payload, access_token
        ) as session_response:
            session = json.loads(session_response.read())

        first = chunks[0]
        chunk_payload = {
            "uploadId": session["uploadId"],
            "chunkIndex": 0,
            "chunkCount": len(chunks),
            "chunkByteSize": len(first),
            "chunkSha256": hashlib.sha256(first).hexdigest(),
            "dataBase64": base64.b64encode(first).decode("ascii"),
        }
        with self.request(
            "/device-sync/workflows/evidence/chunk", chunk_payload, access_token
        ) as uploaded:
            uploaded.read()
        with self.store.lock:
            part_path = self.store.connection.execute(
                "SELECT file_path FROM workflow_evidence_parts WHERE upload_id = ?",
                (session["uploadId"],),
            ).fetchone()["file_path"]
        self.assertTrue(os.path.isfile(part_path))

        cancel_payload = {
            "uploadId": session["uploadId"],
            "assetId": session["assetId"],
        }
        with self.request(
            "/device-sync/workflows/evidence/cancel", cancel_payload, access_token
        ) as cancelled_response:
            cancelled = json.loads(cancelled_response.read())
        self.assertEqual(200, cancelled_response.status)
        self.assertEqual("cancelled", cancelled["uploadStatus"])
        self.assertEqual([], cancelled["receivedChunks"])
        self.assertFalse(os.path.exists(part_path))
        with self.store.lock:
            part_count = self.store.connection.execute(
                "SELECT COUNT(*) AS count FROM workflow_evidence_parts WHERE upload_id = ?",
                (session["uploadId"],),
            ).fetchone()["count"]
        self.assertEqual(0, part_count)

        with self.request(
            "/device-sync/workflows/evidence/cancel", cancel_payload, access_token
        ) as replayed_cancel_response:
            replayed_cancel = json.loads(replayed_cancel_response.read())
        self.assertEqual(200, replayed_cancel_response.status)
        self.assertEqual(cancelled, replayed_cancel)

        with self.assertRaises(urllib.error.HTTPError) as rejected_chunk:
            self.request(
                "/device-sync/workflows/evidence/chunk", chunk_payload, access_token
            )
        self.assertEqual(409, rejected_chunk.exception.code)
        self.assertEqual(
            "workflow_evidence_upload_cancelled",
            json.loads(rejected_chunk.exception.read())["error"],
        )

        with self.request(
            "/device-sync/workflows/evidence/session", session_payload, access_token
        ) as restarted_response:
            restarted = json.loads(restarted_response.read())
        self.assertEqual(200, restarted_response.status)
        self.assertEqual(session["uploadId"], restarted["uploadId"])
        self.assertEqual("uploading", restarted["uploadStatus"])
        self.assertEqual([], restarted["receivedChunks"])
        self.assertEqual(0, restarted["nextChunkIndex"])


if __name__ == "__main__":
    unittest.main()
