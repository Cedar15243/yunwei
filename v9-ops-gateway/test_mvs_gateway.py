import hashlib
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

from gateway import (
    GatewayConfig,
    GatewayHttpServer,
    GatewayService,
    MvsWorkOrderRetryWorker,
    SqliteStore,
    mvs_connector_from_environment,
    mvs_write_enabled_from_environment,
    mvs_retry_worker_from_environment,
)
from mvs_work_order import MvsProviderUnavailable


class FakeProvider:
    def stream_chat(self, context, image_data_url, trace_id):
        return iter(["ok"])


class FakeMvsConnector:
    def __init__(self):
        self.calls = []
        self.failure = None

    def list_work_orders(self, view, limit=50):
        self.calls.append(("list", view, limit))
        return [{"orderId": 42, "orderNo": "MVS-42", "sourceSystem": "mvs"}]

    def get_work_order_detail(self, order_id):
        self.calls.append(("detail", order_id))
        return {"orderId": 42, "orderNo": "MVS-42", "sourceSystem": "mvs"}

    def get_node_form(self, order_id):
        self.calls.append(("node_form", order_id))
        return {"formId": 12, "fields": []}

    def get_flow_records(self, order_id):
        self.calls.append(("flow_records", order_id))
        return [{"nodeName": "现场处理"}]

    def get_execution_records(self, order_id):
        self.calls.append(("execution_records", order_id))
        return [{"status": "executing"}]

    def get_sop_tree(self, order_id):
        self.calls.append(("sop_tree", order_id))
        return [{"title": "停机确认", "children": []}]

    def list_attachments(self, order_id):
        self.calls.append(("attachments", order_id))
        return [{"name": "铭牌照片.jpg", "resourceId": 88}]

    def list_checkins(self, order_id):
        self.calls.append(("checkins", order_id))
        return [{"type": "in"}]

    def get_checkin_form(self, order_id, direction):
        self.calls.append(("checkin_form", order_id, direction))
        return {"formId": 13, "direction": direction}

    def get_checkin_required(self, order_id, definition_id):
        self.calls.append(("checkin_required", order_id, definition_id))
        return True

    def get_task_operations(self, order_id):
        self.calls.append(("task_operations", order_id))
        return {
            "definitionId": "99",
            "nodeCode": "onsite-repair",
            "items": [{"code": "skip", "label": "提交并推进"}],
            "filteredCount": 1,
        }

    def submit_checkin(
        self,
        order_id,
        direction,
        payload,
        *,
        idempotency_key,
        trace_id,
    ):
        self.calls.append(
            (
                "submit_checkin",
                order_id,
                direction,
                dict(payload),
                idempotency_key,
                trace_id,
            )
        )
        if self.failure:
            raise self.failure
        return {"recordId": 88}


class MvsGatewayServiceTest(unittest.TestCase):
    def setUp(self):
        self.bootstrap = "bootstrap-secret-for-air3"
        self.temp = tempfile.TemporaryDirectory()
        self.now = [1_700_000_000.0]
        self.store = SqliteStore(
            f"{self.temp.name}/gateway.db",
            f"{self.temp.name}/evidence",
            clock=lambda: self.now[0],
        )
        self.connector = FakeMvsConnector()
        self.service = GatewayService(
            GatewayConfig(
                bootstrap_token_hash=hashlib.sha256(
                    self.bootstrap.encode("utf-8")
                ).hexdigest(),
                device_id="air3-YM00FCF3NW0031",
                organization_id="org-huafang",
                user_id="user-field-engineer",
                provider_base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
                provider_api_key="server-only-provider-key",
                provider_model="qwen3-vl-plus",
            ),
            self.store,
            FakeProvider(),
            mvs_connector=self.connector,
            mvs_write_enabled=True,
        )

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def issue_session(self):
        response = self.service.exchange_device_session(f"Bearer {self.bootstrap}")
        self.assertEqual(201, response.status)
        return response.json_body["accessToken"]

    def test_mvs_configuration_is_optional_but_partial_configuration_fails_closed(self):
        self.assertIsNone(mvs_connector_from_environment({}))
        self.assertFalse(mvs_write_enabled_from_environment({}))
        self.assertTrue(mvs_write_enabled_from_environment({"V9_MVS_WRITE_ENABLED": "true"}))

        with self.assertRaisesRegex(
            ValueError,
            "missing_mvs_environment:V9_MVS_AUTHORIZATION,V9_MVS_ENGINEER_ID",
        ):
            mvs_connector_from_environment(
                {"V9_MVS_BASE_URL": "https://mvs.example.test"}
            )

        connector = mvs_connector_from_environment(
            {
                "V9_MVS_BASE_URL": "https://mvs.example.test",
                "V9_MVS_AUTHORIZATION": "server-only-engineer-token",
                "V9_MVS_ENGINEER_ID": "1001",
            }
        )
        self.assertEqual("https://mvs.example.test", connector.config.base_url)
        self.assertEqual("1001", connector.config.engineer_id)

        worker = mvs_retry_worker_from_environment(
            {
                "V9_MVS_RETRY_INTERVAL_SECONDS": "2",
                "V9_MVS_RETRY_BASE_SECONDS": "3",
                "V9_MVS_RETRY_STALE_SECONDS": "15",
                "V9_MVS_RETRY_MAX_ATTEMPTS": "6",
                "V9_MVS_RETRY_BATCH_SIZE": "8",
            },
            self.store,
            connector,
            True,
        )
        self.assertIsInstance(worker, MvsWorkOrderRetryWorker)
        self.assertEqual(6, worker.max_attempts)
        self.assertEqual(8, worker.batch_size)

    def test_mvs_writes_fail_closed_without_explicit_write_enablement(self):
        self.service.mvs_write_enabled = False
        token = self.issue_session()
        response = self.service.submit_mvs_checkin(
            f"Bearer {token}",
            "42",
            {
                "direction": "in",
                "confirmation": "CONFIRM_MVS_CHECKIN",
                "idempotencyKey": "mvs-checkin-disabled",
                "traceId": "trace-mvs-checkin-disabled",
            },
        )
        self.assertEqual(503, response.status)
        self.assertEqual("mvs_write_not_enabled", response.json_body["error"])
        self.assertFalse(self.connector.calls)
        self.assertIsNone(
            self.store.load_mvs_work_order_operation(
                self.service.config.device_id, "mvs-checkin-disabled"
            )
        )
        self.assertIsNone(mvs_retry_worker_from_environment({}, self.store, None))

    def test_lists_only_through_a_short_device_session_and_fails_closed_when_unconfigured(self):
        unauthorized = self.service.list_mvs_work_orders("", "executing", 20)
        self.assertEqual(401, unauthorized.status)

        token = self.issue_session()
        response = self.service.list_mvs_work_orders(
            f"Bearer {token}", "executing", 20
        )

        self.assertEqual(200, response.status)
        self.assertEqual("no-store", response.headers["Cache-Control"])
        self.assertEqual("MVS-42", response.json_body["items"][0]["orderNo"])
        self.assertEqual([("list", "executing", 20)], self.connector.calls)

        self.service.mvs_connector = None
        unavailable = self.service.list_mvs_work_orders(
            f"Bearer {token}", "executing", 20
        )
        self.assertEqual(503, unavailable.status)
        self.assertEqual("mvs_unavailable", unavailable.json_body["error"])

    def test_loads_one_allowlisted_detail_resource_at_a_time(self):
        token = self.issue_session()
        authorization = f"Bearer {token}"

        detail = self.service.get_mvs_work_order_resource(
            authorization, "42", "detail", {}
        )
        form = self.service.get_mvs_work_order_resource(
            authorization, "42", "node_form", {}
        )
        sop = self.service.get_mvs_work_order_resource(
            authorization, "42", "sop_tree", {}
        )
        attachments = self.service.get_mvs_work_order_resource(
            authorization, "42", "attachments", {}
        )
        checkin_form = self.service.get_mvs_work_order_resource(
            authorization, "42", "checkin_form", {"direction": "in"}
        )
        required = self.service.get_mvs_work_order_resource(
            authorization,
            "42",
            "checkin_required",
            {"definitionId": "99"},
        )
        operations = self.service.get_mvs_work_order_resource(
            authorization, "42", "task_operations", {
                "definitionId": "client-must-not-control",
                "nodeCode": "client-must-not-control",
            }
        )
        rejected = self.service.get_mvs_work_order_resource(
            authorization, "42", "arbitrary_url", {}
        )

        self.assertEqual(200, detail.status)
        self.assertEqual(12, form.json_body["resource"]["formId"])
        self.assertEqual("停机确认", sop.json_body["resource"][0]["title"])
        self.assertEqual("铭牌照片.jpg", attachments.json_body["resource"][0]["name"])
        self.assertEqual("in", checkin_form.json_body["resource"]["direction"])
        self.assertTrue(required.json_body["resource"])
        self.assertEqual("99", operations.json_body["resource"]["definitionId"])
        self.assertEqual(
            "skip", operations.json_body["resource"]["items"][0]["code"]
        )
        self.assertIn(("task_operations", "42"), self.connector.calls)
        self.assertNotIn("client-must-not-control", json.dumps(self.connector.calls))
        audit_events = self.store.list_mvs_work_order_resource_audit_events(
            "air3-YM00FCF3NW0031", "42"
        )
        self.assertEqual(1, len(audit_events))
        self.assertEqual("task_operations", audit_events[0]["resourceType"])
        self.assertEqual("allowed", audit_events[0]["outcome"])
        self.assertEqual(1, audit_events[0]["filteredCount"])
        self.assertEqual(400, rejected.status)
        self.assertEqual("mvs_resource_not_allowed", rejected.json_body["error"])
        self.assertNotIn("arbitrary_url", json.dumps(self.connector.calls))

    def test_checkin_requires_confirmation_and_is_idempotent_and_audited(self):
        token = self.issue_session()
        authorization = f"Bearer {token}"
        payload = {
            "direction": "in",
            "confirmation": "CONFIRM_MVS_CHECKIN",
            "idempotencyKey": "mvs-checkin-42-a",
            "traceId": "trace-mvs-checkin-42-a",
            "lat": 31.2304,
            "lng": 121.4737,
            "address": "园区一号楼",
            "formId": 12,
            "formContent": {"photoAssetId": "asset-a"},
        }

        missing_confirmation = self.service.submit_mvs_checkin(
            authorization,
            "42",
            dict(payload, confirmation=""),
        )
        first = self.service.submit_mvs_checkin(authorization, "42", payload)
        duplicate = self.service.submit_mvs_checkin(authorization, "42", payload)
        conflict = self.service.submit_mvs_checkin(
            authorization,
            "42",
            dict(payload, lat=30.0),
        )

        self.assertEqual(400, missing_confirmation.status)
        self.assertEqual("confirmation_required", missing_confirmation.json_body["error"])
        self.assertEqual(200, first.status)
        self.assertFalse(first.json_body["duplicate"])
        self.assertEqual(88, first.json_body["result"]["recordId"])
        self.assertEqual(200, duplicate.status)
        self.assertTrue(duplicate.json_body["duplicate"])
        self.assertEqual(409, conflict.status)
        self.assertEqual("idempotency_conflict", conflict.json_body["error"])
        submit_calls = [call for call in self.connector.calls if call[0] == "submit_checkin"]
        self.assertEqual(1, len(submit_calls))

        operation = self.store.load_mvs_work_order_operation(
            self.service.config.device_id,
            "mvs-checkin-42-a",
        )
        self.assertEqual("succeeded", operation["status"])
        self.assertEqual(2, len(self.store.list_mvs_work_order_audit_events(operation["id"])))

    def test_provider_failure_is_recorded_as_retryable_and_never_as_success(self):
        token = self.issue_session()
        self.connector.failure = MvsProviderUnavailable("mvs_provider_unavailable")
        payload = {
            "direction": "out",
            "confirmation": "CONFIRM_MVS_CHECKOUT",
            "idempotencyKey": "mvs-checkout-42-a",
            "traceId": "trace-mvs-checkout-42-a",
            "lat": 31.2304,
            "lng": 121.4737,
        }

        failed = self.service.submit_mvs_checkin(
            f"Bearer {token}", "42", payload
        )
        duplicate = self.service.submit_mvs_checkin(
            f"Bearer {token}", "42", payload
        )

        self.assertEqual(503, failed.status)
        self.assertTrue(failed.json_body["retryable"])
        self.assertNotIn("result", failed.json_body)
        self.assertEqual(503, duplicate.status)
        self.assertTrue(duplicate.json_body["duplicate"])
        submit_calls = [call for call in self.connector.calls if call[0] == "submit_checkin"]
        self.assertEqual(1, len(submit_calls))

        operation = self.store.load_mvs_work_order_operation(
            self.service.config.device_id,
            "mvs-checkout-42-a",
        )
        self.assertEqual("retryable", operation["status"])
        self.assertEqual("mvs_provider_unavailable", operation["errorCode"])

    def test_retry_worker_replays_the_same_idempotent_checkin_until_authoritative_success(self):
        token = self.issue_session()
        self.connector.failure = MvsProviderUnavailable("mvs_provider_unavailable")
        payload = {
            "direction": "out",
            "confirmation": "CONFIRM_MVS_CHECKOUT",
            "idempotencyKey": "mvs-checkout-42-retry",
            "traceId": "trace-mvs-checkout-42-retry",
            "lat": 31.2304,
            "lng": 121.4737,
        }
        failed = self.service.submit_mvs_checkin(
            f"Bearer {token}", "42", payload
        )
        self.assertEqual(503, failed.status)

        self.connector.failure = None
        worker = MvsWorkOrderRetryWorker(
            self.store,
            self.connector,
            retry_base_seconds=0,
            retry_stale_seconds=0,
        )

        self.assertEqual(1, worker.run_once())

        operation = self.store.load_mvs_work_order_operation(
            self.service.config.device_id,
            "mvs-checkout-42-retry",
        )
        self.assertEqual("succeeded", operation["status"])
        self.assertEqual(2, operation["attemptCount"])
        submit_calls = [call for call in self.connector.calls if call[0] == "submit_checkin"]
        self.assertEqual(2, len(submit_calls))
        self.assertEqual("mvs-checkout-42-retry", submit_calls[1][4])
        self.assertEqual("trace-mvs-checkout-42-retry", submit_calls[1][5])

        duplicate = self.service.submit_mvs_checkin(
            f"Bearer {token}", "42", payload
        )
        self.assertEqual(200, duplicate.status)
        self.assertTrue(duplicate.json_body["duplicate"])
        self.assertEqual(88, duplicate.json_body["result"]["recordId"])

    def test_retry_worker_marks_the_operation_failed_after_the_configured_attempt_limit(self):
        token = self.issue_session()
        self.connector.failure = MvsProviderUnavailable("mvs_provider_unavailable")
        payload = {
            "direction": "in",
            "confirmation": "CONFIRM_MVS_CHECKIN",
            "idempotencyKey": "mvs-checkin-42-exhaust",
            "traceId": "trace-mvs-checkin-42-exhaust",
            "lat": 31.2304,
            "lng": 121.4737,
        }
        failed = self.service.submit_mvs_checkin(
            f"Bearer {token}", "42", payload
        )
        self.assertEqual(503, failed.status)
        worker = MvsWorkOrderRetryWorker(
            self.store,
            self.connector,
            retry_base_seconds=0,
            retry_stale_seconds=0,
            max_attempts=2,
        )

        self.assertEqual(1, worker.run_once())
        self.assertEqual(0, worker.run_once())

        operation = self.store.load_mvs_work_order_operation(
            self.service.config.device_id,
            "mvs-checkin-42-exhaust",
        )
        self.assertEqual("failed", operation["status"])
        self.assertEqual(2, operation["attemptCount"])
        self.assertEqual("mvs_retry_exhausted", operation["errorCode"])
        event_types = [
            event["eventType"]
            for event in self.store.list_mvs_work_order_audit_events(operation["id"])
        ]
        self.assertIn("retry_exhausted", event_types)

    def test_retry_worker_recovers_a_stale_pending_operation_after_restart(self):
        operation = self.store.begin_mvs_work_order_operation(
            self.service.config.device_id,
            "mvs-checkin-42-pending",
            "mvs_checkin_in",
            "42",
            {
                "direction": "in",
                "confirmation": "CONFIRM_MVS_CHECKIN",
                "idempotencyKey": "mvs-checkin-42-pending",
                "traceId": "trace-mvs-checkin-42-pending",
                "lat": 31.2304,
                "lng": 121.4737,
            },
            "trace-mvs-checkin-42-pending",
        )
        worker = MvsWorkOrderRetryWorker(
            self.store,
            self.connector,
            retry_base_seconds=0,
            retry_stale_seconds=30,
        )

        self.assertEqual(0, worker.run_once())
        self.now[0] += 31
        self.assertEqual(1, worker.run_once())

        recovered = self.store.load_mvs_work_order_operation(
            self.service.config.device_id,
            "mvs-checkin-42-pending",
        )
        self.assertEqual(operation["id"], recovered["id"])
        self.assertEqual("succeeded", recovered["status"])
        self.assertEqual(2, recovered["attemptCount"])
        submit_call = [
            call for call in self.connector.calls if call[0] == "submit_checkin"
        ][0]
        self.assertEqual("mvs-checkin-42-pending", submit_call[4])
        self.assertEqual("trace-mvs-checkin-42-pending", submit_call[5])

    def test_http_routes_reject_unknown_queries_and_expose_only_the_gateway_contract(self):
        server = GatewayHttpServer(("127.0.0.1", 0), self.service)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        try:
            token = self.issue_session()
            headers = {"Authorization": f"Bearer {token}"}
            with self.assertRaises(urllib.error.HTTPError) as rejected:
                urllib.request.urlopen(
                    urllib.request.Request(
                        base_url
                        + "/device-sync/work-orders?view=executing&admin=true",
                        headers=headers,
                    ),
                    timeout=3,
                )
            self.assertEqual(400, rejected.exception.code)
            self.assertEqual(
                "invalid_work_order_query",
                json.loads(rejected.exception.read())["error"],
            )

            with urllib.request.urlopen(
                urllib.request.Request(
                    base_url + "/device-sync/work-orders?view=executing&limit=20",
                    headers=headers,
                ),
                timeout=3,
            ) as response:
                listed = json.loads(response.read())
                self.assertEqual("no-store", response.headers["Cache-Control"])
            self.assertEqual("MVS-42", listed["items"][0]["orderNo"])

            request = urllib.request.Request(
                base_url + "/device-sync/work-orders/42/checkin",
                data=json.dumps(
                    {
                        "direction": "in",
                        "confirmation": "CONFIRM_MVS_CHECKIN",
                        "idempotencyKey": "mvs-http-checkin-42-a",
                        "traceId": "trace-mvs-http-checkin-42-a",
                        "lat": 31.2304,
                        "lng": 121.4737,
                    }
                ).encode("utf-8"),
                headers={**headers, "Content-Type": "application/json"},
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=3) as response:
                checked_in = json.loads(response.read())
            self.assertEqual(88, checked_in["result"]["recordId"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
