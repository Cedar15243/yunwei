import json
import unittest
import urllib.request

from mvs_work_order import (
    MvsConnectorConfig,
    MvsProviderUnavailable,
    MvsWorkOrderConnector,
)


class FakeResponse:
    def __init__(self, body, status=200):
        self.body = body
        self.status = status
        self.closed = False

    def read(self, maximum=-1):
        if maximum < 0:
            return self.body
        return self.body[:maximum]

    def close(self):
        self.closed = True


class RecordingOpener:
    def __init__(self, payload):
        self.payload = payload
        self.calls = []

    def __call__(self, request, timeout):
        self.calls.append((request, timeout))
        return FakeResponse(json.dumps(self.payload, ensure_ascii=False).encode("utf-8"))


class QueueRecordingOpener:
    def __init__(self, *payloads):
        self.payloads = list(payloads)
        self.calls = []

    def __call__(self, request, timeout):
        self.calls.append((request, timeout))
        if not self.payloads:
            raise AssertionError("unexpected MVS request")
        payload = self.payloads.pop(0)
        return FakeResponse(json.dumps(payload, ensure_ascii=False).encode("utf-8"))


class MvsWorkOrderConnectorTest(unittest.TestCase):
    def config(self, **overrides):
        values = {
            "base_url": "https://mvs-gateway.example.test",
            "authorization": "engineer-delegated-token",
            "engineer_id": "1001",
        }
        values.update(overrides)
        return MvsConnectorConfig(**values)

    def test_requires_https_and_a_server_only_engineer_credential(self):
        with self.assertRaisesRegex(ValueError, "mvs_base_url_must_be_https"):
            self.config(base_url="http://192.168.20.246")
        with self.assertRaisesRegex(ValueError, "mvs_authorization_missing"):
            self.config(authorization="")
        with self.assertRaisesRegex(ValueError, "mvs_authorization_invalid"):
            self.config(authorization="token\nInjected: value")

    def test_lists_only_the_bound_engineers_orders_from_the_allowlisted_endpoint(self):
        opener = RecordingOpener(
            {
                "code": 200,
                "msg": "success",
                "data": [
                    {
                        "orderId": 42,
                        "orderNo": "MVS-42",
                        "orderStatus": "EXECUTING",
                        "projectId": 7,
                        "projectName": "冷站年度维护",
                        "siteName": "一号冷站",
                        "engineerId": 1001,
                        "engineerName": "现场工程师",
                        "contactName": "张经理",
                        "contactPhone": "13800138000",
                        "addressDetail": "园区一号楼",
                        "unexpectedAdminField": "must-not-leak",
                    },
                    {
                        "orderId": 43,
                        "orderNo": "MVS-43",
                        "engineerId": 2002,
                    },
                ],
            }
        )
        connector = MvsWorkOrderConnector(self.config(), opener=opener)

        result = connector.list_work_orders("executing", limit=20)

        self.assertEqual(1, len(result))
        self.assertEqual("MVS-42", result[0]["orderNo"])
        self.assertEqual("张**", result[0]["contactName"])
        self.assertEqual("138****8000", result[0]["contactPhone"])
        self.assertNotIn("unexpectedAdminField", result[0])
        request, timeout = opener.calls[0]
        self.assertEqual(
            "https://mvs-gateway.example.test/prod-api/engineer/order/executing-list",
            request.full_url,
        )
        self.assertEqual("engineer-delegated-token", request.headers["Authorization"])
        self.assertEqual("1001", request.headers["X-mvs-engineer-id"])
        self.assertEqual(8, timeout)

    def test_reads_detail_node_form_and_flow_records_without_arbitrary_paths(self):
        detail_opener = RecordingOpener(
            {
                "code": 200,
                "data": {
                    "orderId": 42,
                    "orderNo": "MVS-42",
                    "engineerId": "1001",
                    "nodeCode": "onsite",
                    "nodeName": "现场处理",
                    "definitionId": 99,
                    "instanceId": 501,
                    "taskId": 502,
                    "contactName": "李工",
                    "contactPhone": "13912345678",
                },
            }
        )
        connector = MvsWorkOrderConnector(self.config(), opener=detail_opener)

        detail = connector.get_work_order_detail("42")

        self.assertEqual("MVS-42", detail["orderNo"])
        self.assertEqual("李*", detail["contactName"])
        self.assertEqual("139****5678", detail["contactPhone"])
        self.assertEqual(
            "https://mvs-gateway.example.test/prod-api/engineer/order/42",
            detail_opener.calls[0][0].full_url,
        )

        form_opener = RecordingOpener({"code": 200, "data": {"formId": 12, "fields": []}})
        form = MvsWorkOrderConnector(self.config(), opener=form_opener).get_node_form("42")
        self.assertEqual(12, form["formId"])
        self.assertEqual(
            "https://mvs-gateway.example.test/prod-api/mvs/order/42/flow/node-form",
            form_opener.calls[0][0].full_url,
        )

        records_opener = RecordingOpener({"code": 200, "data": [{"nodeName": "已签到"}]})
        records = MvsWorkOrderConnector(self.config(), opener=records_opener).get_flow_records("42")
        self.assertEqual("已签到", records[0]["nodeName"])
        self.assertEqual(
            "https://mvs-gateway.example.test/prod-api/mvs/order/42/flow/record-detail?orderBy=asc",
            records_opener.calls[0][0].full_url,
        )

    def test_reads_task_operations_from_authoritative_order_context_and_filters_unknown_items(self):
        opener = QueueRecordingOpener(
            {
                "code": 200,
                "data": {
                    "orderId": 42,
                    "orderNo": "MVS-42",
                    "engineerId": "1001",
                    "definitionId": 99,
                    "nodeCode": "onsite-repair",
                },
            },
            {
                "code": 200,
                "data": [
                    {
                        "operationCode": "skip",
                        "operationName": "提交并推进",
                        "enabled": True,
                        "targetUserId": "must-not-leak",
                    },
                    {"code": "transfer", "label": "转办"},
                    {"key": "reject", "name": "退回"},
                    {"code": "reject", "label": "重复退回"},
                    {"code": "terminate", "label": "终止流程"},
                    {"code": "back-to-node", "label": "退回指定节点", "enabled": False},
                ],
            },
        )
        connector = MvsWorkOrderConnector(self.config(), opener=opener)

        result = connector.get_task_operations("42")

        self.assertEqual("99", result["definitionId"])
        self.assertEqual("onsite-repair", result["nodeCode"])
        self.assertEqual(
            [
                {"code": "skip", "label": "提交并推进"},
                {"code": "transfer", "label": "转办"},
                {"code": "reject", "label": "退回"},
            ],
            result["items"],
        )
        self.assertEqual(3, result["filteredCount"])
        self.assertEqual(
            "https://mvs-gateway.example.test/prod-api/engineer/order/42",
            opener.calls[0][0].full_url,
        )
        self.assertEqual(
            "https://mvs-gateway.example.test/prod-api/mvs/order/task/operations"
            "?definitionId=99&nodeCode=onsite-repair",
            opener.calls[1][0].full_url,
        )
        self.assertNotIn("targetUserId", json.dumps(result))

    def test_task_operations_fail_closed_when_order_context_or_response_is_invalid(self):
        missing_context = QueueRecordingOpener(
            {
                "code": 200,
                "data": {
                    "orderId": 42,
                    "orderNo": "MVS-42",
                    "engineerId": "1001",
                },
            }
        )
        with self.assertRaisesRegex(
            MvsProviderUnavailable, "mvs_task_operations_context_missing"
        ):
            MvsWorkOrderConnector(
                self.config(), opener=missing_context
            ).get_task_operations("42")

        invalid_response = QueueRecordingOpener(
            {
                "code": 200,
                "data": {
                    "orderId": 42,
                    "orderNo": "MVS-42",
                    "engineerId": "1001",
                    "definitionId": 99,
                    "nodeCode": "onsite-repair",
                },
            },
            {"code": 200, "data": {"unexpected": "shape"}},
        )
        with self.assertRaisesRegex(MvsProviderUnavailable, "mvs_response_invalid"):
            MvsWorkOrderConnector(
                self.config(), opener=invalid_response
            ).get_task_operations("42")

    def test_submits_only_confirmed_checkin_fields_with_idempotency_and_trace_headers(self):
        opener = RecordingOpener({"code": 200, "data": {"recordId": 88}})
        connector = MvsWorkOrderConnector(self.config(), opener=opener)

        result = connector.submit_checkin(
            "42",
            "in",
            {
                "lat": 31.2304,
                "lng": 121.4737,
                "address": "园区一号楼",
                "formId": 12,
                "formContent": {"photo": "asset-a"},
            },
            idempotency_key="checkin-42-in-a",
            trace_id="trace-checkin-a",
        )

        self.assertEqual(88, result["recordId"])
        request, _timeout = opener.calls[0]
        self.assertEqual("POST", request.method)
        self.assertEqual(
            "https://mvs-gateway.example.test/prod-api/mvs/order/42/checkin/in",
            request.full_url,
        )
        self.assertEqual("checkin-42-in-a", request.headers["Idempotency-key"])
        self.assertEqual("trace-checkin-a", request.headers["X-trace-id"])
        self.assertEqual(
            {
                "lat": 31.2304,
                "lng": 121.4737,
                "address": "园区一号楼",
                "formId": 12,
                "formContent": '{"photo":"asset-a"}',
            },
            json.loads(request.data),
        )

    def test_rejects_unapproved_views_invalid_location_and_mvs_business_failure(self):
        connector = MvsWorkOrderConnector(self.config(), opener=RecordingOpener({"code": 200, "data": []}))
        with self.assertRaisesRegex(ValueError, "mvs_work_order_view_not_allowed"):
            connector.list_work_orders("all-admin")
        with self.assertRaisesRegex(ValueError, "mvs_checkin_location_invalid"):
            connector.submit_checkin(
                "42",
                "out",
                {"lat": 999, "lng": 121.4},
                idempotency_key="checkin-42-out-a",
                trace_id="trace-checkout-a",
            )

        rejected = MvsWorkOrderConnector(
            self.config(),
            opener=RecordingOpener({"code": 500, "msg": "database details", "data": None}),
        )
        with self.assertRaisesRegex(MvsProviderUnavailable, "mvs_business_rejected"):
            rejected.get_work_order_detail("42")

    def test_loads_allowlisted_sop_tree_and_attachment_list_without_exposing_urls(self):
        sop_opener = RecordingOpener(
            {"code": 200, "data": [{"title": "停机确认", "children": []}]}
        )
        attachment_opener = RecordingOpener(
            {"code": 200, "data": [{"name": "铭牌照片.jpg", "resourceId": 88}]}
        )
        sop_connector = MvsWorkOrderConnector(self.config(), opener=sop_opener)
        attachment_connector = MvsWorkOrderConnector(
            self.config(), opener=attachment_opener
        )

        sop = sop_connector.get_sop_tree("42")
        attachments = attachment_connector.list_attachments("42")

        self.assertEqual("停机确认", sop[0]["title"])
        self.assertEqual("铭牌照片.jpg", attachments[0]["name"])
        self.assertEqual(
            "https://mvs-gateway.example.test/prod-api/mvs/order/42/sop/tree",
            sop_opener.calls[0][0].full_url,
        )
        self.assertEqual(
            "https://mvs-gateway.example.test/prod-api/mvs/order/42/attachment/list",
            attachment_opener.calls[0][0].full_url,
        )
        self.assertNotIn("downloadUrl", str(attachments))

    def test_rejects_oversized_or_malformed_provider_responses(self):
        class OversizedOpener:
            def __call__(self, request: urllib.request.Request, timeout: int):
                return FakeResponse(b"{" + b"x" * (2 * 1024 * 1024) + b"}")

        connector = MvsWorkOrderConnector(self.config(), opener=OversizedOpener())
        with self.assertRaisesRegex(MvsProviderUnavailable, "mvs_response_too_large"):
            connector.get_work_order_detail("42")

        malformed = MvsWorkOrderConnector(
            self.config(), opener=lambda request, timeout: FakeResponse(b"not-json")
        )
        with self.assertRaisesRegex(MvsProviderUnavailable, "mvs_response_invalid"):
            malformed.get_work_order_detail("42")


if __name__ == "__main__":
    unittest.main()
