from __future__ import annotations

import json
import re
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Callable, Mapping, Optional


MAX_MVS_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_FORM_CONTENT_BYTES = 16 * 1024
MVS_ORDER_VIEWS = {
    "pending": "/prod-api/engineer/order/pending-accept-execute-list",
    "pending_execute": "/prod-api/engineer/order/pending-execute-list",
    "executing": "/prod-api/engineer/order/executing-list",
    "completed": "/prod-api/engineer/order/completed-list",
}
MVS_TASK_OPERATION_LABELS = {
    "skip": "办理并推进",
    "transfer": "转办",
    "reject": "退回",
    "back-to-node": "退回指定节点",
}
MVS_TASK_OPERATION_ALIASES = {
    "back_to_node": "back-to-node",
    "backtonode": "back-to-node",
}


class MvsProviderUnavailable(RuntimeError):
    pass


@dataclass(frozen=True)
class MvsConnectorConfig:
    base_url: str
    authorization: str
    engineer_id: str
    timeout_seconds: int = 8
    max_response_bytes: int = MAX_MVS_RESPONSE_BYTES

    def __post_init__(self) -> None:
        parsed = urllib.parse.urlsplit(self.base_url.strip())
        if (
            parsed.scheme != "https"
            or not parsed.hostname
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError("mvs_base_url_must_be_https")
        if not self.authorization.strip():
            raise ValueError("mvs_authorization_missing")
        if "\r" in self.authorization or "\n" in self.authorization:
            raise ValueError("mvs_authorization_invalid")
        if not _valid_identifier(self.engineer_id, 120):
            raise ValueError("mvs_engineer_id_invalid")
        if self.timeout_seconds < 1 or self.timeout_seconds > 20:
            raise ValueError("mvs_timeout_out_of_range")
        if self.max_response_bytes < 1024 or self.max_response_bytes > MAX_MVS_RESPONSE_BYTES:
            raise ValueError("mvs_response_limit_out_of_range")


class MvsWorkOrderConnector:
    def __init__(
        self,
        config: MvsConnectorConfig,
        opener: Optional[Callable[[urllib.request.Request, int], object]] = None,
    ):
        self.config = config
        self.opener = opener or (
            lambda request, timeout: urllib.request.urlopen(request, timeout=timeout)
        )

    def list_work_orders(self, view: str, limit: int = 50) -> list[dict]:
        path = MVS_ORDER_VIEWS.get(str(view or "").strip())
        if not path:
            raise ValueError("mvs_work_order_view_not_allowed")
        if not isinstance(limit, int) or isinstance(limit, bool) or limit < 1 or limit > 100:
            raise ValueError("mvs_work_order_limit_invalid")
        payload = self._request("GET", path)
        if isinstance(payload, dict):
            payload = payload.get("rows")
        if not isinstance(payload, list):
            raise MvsProviderUnavailable("mvs_response_invalid")
        result: list[dict] = []
        for item in payload:
            if not isinstance(item, dict):
                continue
            if not self._belongs_to_bound_engineer(item):
                continue
            result.append(_sanitize_work_order(item))
            if len(result) >= limit:
                break
        return result

    def get_work_order_detail(self, order_id: str) -> dict:
        order = self._request(
            "GET", f"/prod-api/engineer/order/{_order_id(order_id)}"
        )
        if not isinstance(order, dict):
            raise MvsProviderUnavailable("mvs_response_invalid")
        if not self._belongs_to_bound_engineer(order):
            raise PermissionError("mvs_order_engineer_mismatch")
        return _sanitize_work_order(order)

    def get_node_form(self, order_id: str) -> dict:
        result = self._request(
            "GET",
            f"/prod-api/mvs/order/{_order_id(order_id)}/flow/node-form",
        )
        if not isinstance(result, dict):
            raise MvsProviderUnavailable("mvs_response_invalid")
        sanitized = _bounded_json(result)
        if not isinstance(sanitized, dict):
            raise MvsProviderUnavailable("mvs_response_invalid")
        return sanitized

    def get_task_operations(self, order_id: str) -> dict:
        detail = self.get_work_order_detail(order_id)
        definition_id = detail.get("definitionId")
        node_code = detail.get("nodeCode")
        try:
            accepted_definition_id = _numeric_identifier(
                definition_id, "mvs_definition_id_invalid"
            )
        except ValueError:
            raise MvsProviderUnavailable(
                "mvs_task_operations_context_missing"
            ) from None
        if not _valid_identifier(node_code, 160):
            raise MvsProviderUnavailable("mvs_task_operations_context_missing")
        accepted_node_code = str(node_code).strip()
        result = self._request(
            "GET",
            "/prod-api/mvs/order/task/operations",
            query={
                "definitionId": accepted_definition_id,
                "nodeCode": accepted_node_code,
            },
        )
        return _sanitize_task_operations(
            result, accepted_definition_id, accepted_node_code
        )

    def get_flow_records(self, order_id: str) -> list[dict]:
        result = self._request(
            "GET",
            f"/prod-api/mvs/order/{_order_id(order_id)}/flow/record-detail",
            query={"orderBy": "asc"},
        )
        if not isinstance(result, list):
            raise MvsProviderUnavailable("mvs_response_invalid")
        records = _bounded_json(result)
        if not isinstance(records, list) or not all(isinstance(item, dict) for item in records):
            raise MvsProviderUnavailable("mvs_response_invalid")
        return records

    def get_execution_records(self, order_id: str) -> list[dict]:
        result = self._request(
            "GET",
            f"/prod-api/mvs/order/{_order_id(order_id)}/execution/list",
        )
        if not isinstance(result, list):
            raise MvsProviderUnavailable("mvs_response_invalid")
        records = _bounded_json(result)
        if not isinstance(records, list) or not all(isinstance(item, dict) for item in records):
            raise MvsProviderUnavailable("mvs_response_invalid")
        return records

    def get_sop_tree(self, order_id: str) -> list[dict]:
        result = self._request(
            "GET",
            f"/prod-api/mvs/order/{_order_id(order_id)}/sop/tree",
        )
        return _sanitize_sop_tree(result)

    def list_attachments(self, order_id: str) -> list[dict]:
        result = self._request(
            "GET",
            f"/prod-api/mvs/order/{_order_id(order_id)}/attachment/list",
        )
        return _sanitize_attachments(result)

    def get_checkin_required(self, order_id: str, definition_id: str) -> object:
        result = self._request(
            "GET",
            f"/prod-api/mvs/order/{_order_id(order_id)}/checkin/required",
            query={"definitionId": _numeric_identifier(definition_id, "mvs_definition_id_invalid")},
        )
        return _bounded_json(result)

    def get_checkin_form(self, order_id: str, direction: str) -> dict:
        type_value = {"in": "1", "out": "2"}.get(str(direction or "").strip())
        if not type_value:
            raise ValueError("mvs_checkin_direction_invalid")
        result = self._request(
            "GET",
            f"/prod-api/mvs/order/{_order_id(order_id)}/checkin/flow-form",
            query={"type": type_value},
        )
        if not isinstance(result, dict):
            raise MvsProviderUnavailable("mvs_response_invalid")
        form = _bounded_json(result)
        if not isinstance(form, dict):
            raise MvsProviderUnavailable("mvs_response_invalid")
        return form

    def list_checkins(self, order_id: str) -> list[dict]:
        result = self._request(
            "GET",
            f"/prod-api/mvs/order/{_order_id(order_id)}/checkin/list",
        )
        if not isinstance(result, list):
            raise MvsProviderUnavailable("mvs_response_invalid")
        records = _bounded_json(result)
        if not isinstance(records, list) or not all(isinstance(item, dict) for item in records):
            raise MvsProviderUnavailable("mvs_response_invalid")
        return records

    def submit_checkin(
        self,
        order_id: str,
        direction: str,
        payload: Mapping[str, object],
        *,
        idempotency_key: str,
        trace_id: str,
    ) -> dict:
        normalized_direction = str(direction or "").strip()
        if normalized_direction not in {"in", "out"}:
            raise ValueError("mvs_checkin_direction_invalid")
        if not _valid_identifier(idempotency_key, 200):
            raise ValueError("mvs_idempotency_key_invalid")
        if not _valid_identifier(trace_id, 200):
            raise ValueError("mvs_trace_id_invalid")
        body = _checkin_body(payload)
        result = self._request(
            "POST",
            f"/prod-api/mvs/order/{_order_id(order_id)}/checkin/{normalized_direction}",
            body=body,
            extra_headers={
                "Idempotency-Key": idempotency_key,
                "X-Trace-Id": trace_id,
            },
        )
        if result is None:
            return {"acknowledged": True}
        if not isinstance(result, dict):
            raise MvsProviderUnavailable("mvs_response_invalid")
        sanitized = _bounded_json(result)
        if not isinstance(sanitized, dict):
            raise MvsProviderUnavailable("mvs_response_invalid")
        return sanitized

    def _belongs_to_bound_engineer(self, value: Mapping[str, object]) -> bool:
        engineer_id = value.get("engineerId")
        if engineer_id is None or str(engineer_id).strip() == "":
            return True
        return str(engineer_id).strip() == self.config.engineer_id

    def _request(
        self,
        method: str,
        path: str,
        *,
        query: Optional[Mapping[str, str]] = None,
        body: Optional[Mapping[str, object]] = None,
        extra_headers: Optional[Mapping[str, str]] = None,
    ) -> object:
        if not path.startswith("/prod-api/") or ".." in path or "//" in path:
            raise ValueError("mvs_path_not_allowed")
        url = self.config.base_url.rstrip("/") + path
        if query:
            url += "?" + urllib.parse.urlencode(query)
        encoded_body = None
        headers = {
            "Authorization": self.config.authorization.strip(),
            "Accept": "application/json",
            "X-MVS-Engineer-Id": self.config.engineer_id,
        }
        if body is not None:
            encoded_body = json.dumps(
                body, ensure_ascii=False, separators=(",", ":")
            ).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if extra_headers:
            headers.update(extra_headers)
        request = urllib.request.Request(
            url,
            data=encoded_body,
            headers=headers,
            method=method,
        )
        response = None
        try:
            response = self.opener(request, self.config.timeout_seconds)
            status = int(getattr(response, "status", 200))
            raw = response.read(self.config.max_response_bytes + 1)
            if status < 200 or status >= 300:
                raise MvsProviderUnavailable("mvs_provider_unavailable")
        except urllib.error.HTTPError as error:
            error.read()
            raise MvsProviderUnavailable("mvs_provider_unavailable") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise MvsProviderUnavailable("mvs_provider_unavailable") from None
        finally:
            if response is not None:
                response.close()
        if len(raw) > self.config.max_response_bytes:
            raise MvsProviderUnavailable("mvs_response_too_large")
        try:
            envelope = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            raise MvsProviderUnavailable("mvs_response_invalid") from None
        if not isinstance(envelope, dict):
            raise MvsProviderUnavailable("mvs_response_invalid")
        if envelope.get("code") not in {0, 200, "0", "200"}:
            raise MvsProviderUnavailable("mvs_business_rejected")
        return envelope.get("data")


def _checkin_body(payload: Mapping[str, object]) -> dict:
    if set(payload) - {"lat", "lng", "address", "formId", "formContent"}:
        raise ValueError("mvs_checkin_payload_invalid")
    lat = payload.get("lat")
    lng = payload.get("lng")
    if (
        not isinstance(lat, (int, float))
        or isinstance(lat, bool)
        or not isinstance(lng, (int, float))
        or isinstance(lng, bool)
        or not -90 <= float(lat) <= 90
        or not -180 <= float(lng) <= 180
    ):
        raise ValueError("mvs_checkin_location_invalid")
    result: dict[str, object] = {"lat": float(lat), "lng": float(lng)}
    address = payload.get("address")
    if address is not None:
        if not _valid_text(address, 500):
            raise ValueError("mvs_checkin_address_invalid")
        result["address"] = str(address).strip()
    form_id = payload.get("formId")
    if form_id is not None:
        if not isinstance(form_id, int) or isinstance(form_id, bool) or form_id < 1:
            raise ValueError("mvs_checkin_form_invalid")
        result["formId"] = form_id
    form_content = payload.get("formContent")
    if form_content is not None:
        if not isinstance(form_content, dict):
            raise ValueError("mvs_checkin_form_invalid")
        encoded = json.dumps(
            _bounded_json(form_content),
            ensure_ascii=False,
            separators=(",", ":"),
        )
        if len(encoded.encode("utf-8")) > MAX_FORM_CONTENT_BYTES:
            raise ValueError("mvs_checkin_form_too_large")
        result["formContent"] = encoded
    return result


def _sanitize_work_order(value: Mapping[str, object]) -> dict:
    scalar_keys = (
        "orderId",
        "orderNo",
        "orderStatus",
        "flowStatus",
        "definitionId",
        "instanceId",
        "taskId",
        "nodeCode",
        "nodeName",
        "projectId",
        "projectName",
        "siteId",
        "siteName",
        "customerId",
        "customerName",
        "addressDetail",
        "lat",
        "lng",
        "appointmentTime",
        "deadlineTime",
        "completeTime",
        "remark",
        "expectedWorkHours",
        "actualWorkHours",
        "engineerId",
        "engineerName",
        "assetId",
        "assetName",
        "assetModel",
        "priority",
    )
    result: dict[str, object] = {"sourceSystem": "mvs"}
    for key in scalar_keys:
        item = value.get(key)
        if item is None or isinstance(item, (dict, list, tuple, set)):
            continue
        if isinstance(item, str):
            if not _valid_text(item, 1000):
                continue
            result[key] = item.strip()
        elif isinstance(item, (int, float, bool)):
            result[key] = item
    contact_name = value.get("contactName")
    contact_phone = value.get("contactPhone")
    if _valid_text(contact_name, 120):
        result["contactName"] = _mask_name(str(contact_name).strip())
    if _valid_text(contact_phone, 60):
        result["contactPhone"] = _mask_phone(str(contact_phone).strip())
    return result


def _sanitize_sop_tree(value: object) -> list[dict]:
    if not isinstance(value, list) or len(value) > 200:
        raise MvsProviderUnavailable("mvs_response_invalid")
    node_count = [0]

    def sanitize_node(node: object, depth: int) -> dict:
        if not isinstance(node, dict) or depth > 8:
            raise MvsProviderUnavailable("mvs_response_invalid")
        node_count[0] += 1
        if node_count[0] > 500:
            raise MvsProviderUnavailable("mvs_response_invalid")
        result: dict[str, object] = {}
        identifier = _first_scalar(node, "id", "sopId", "nodeId", "resourceId")
        title = _first_text(node, "title", "name", "nodeName", maximum=500)
        node_type = _first_text(node, "type", "nodeType", maximum=120)
        status = _first_text(node, "status", maximum=120)
        description = _first_text(
            node, "description", "content", "remark", maximum=5_000
        )
        if identifier is not None:
            result["id"] = identifier
        result["title"] = title or "SOP 节点"
        if node_type:
            result["type"] = node_type
        if status:
            result["status"] = status
        if description:
            result["description"] = description
        children = node.get("children")
        if children is not None:
            if not isinstance(children, list) or len(children) > 200:
                raise MvsProviderUnavailable("mvs_response_invalid")
            result["children"] = [
                sanitize_node(child, depth + 1) for child in children
            ]
        return result

    return [sanitize_node(node, 0) for node in value]


def _sanitize_attachments(value: object) -> list[dict]:
    if not isinstance(value, list) or len(value) > 500:
        raise MvsProviderUnavailable("mvs_response_invalid")
    result: list[dict] = []
    for item in value:
        if not isinstance(item, dict):
            raise MvsProviderUnavailable("mvs_response_invalid")
        attachment: dict[str, object] = {}
        identifier = _first_scalar(item, "resourceId", "attachmentId", "id")
        name = _first_text(
            item, "name", "fileName", "originalName", "title", maximum=500
        )
        content_type = _first_text(
            item, "contentType", "mimeType", "fileType", maximum=160
        )
        uploaded_at = _first_text(
            item, "uploadedAt", "createdAt", "createTime", maximum=160
        )
        byte_size = _first_scalar(item, "byteSize", "fileSize", "size")
        if identifier is not None:
            attachment["resourceId"] = identifier
        attachment["name"] = name or "现场附件"
        if content_type:
            attachment["contentType"] = content_type
        if uploaded_at:
            attachment["uploadedAt"] = uploaded_at
        if isinstance(byte_size, (int, float)) and not isinstance(byte_size, bool):
            if 0 <= byte_size <= 10 * 1024 * 1024 * 1024:
                attachment["byteSize"] = byte_size
        result.append(attachment)
    return result


def _sanitize_task_operations(
    value: object, definition_id: str, node_code: str
) -> dict:
    if not isinstance(value, list) or len(value) > 100:
        raise MvsProviderUnavailable("mvs_response_invalid")
    items: list[dict[str, str]] = []
    seen: set[str] = set()
    filtered_count = 0
    for item in value:
        if isinstance(item, str):
            raw_code = item.strip()
            label = ""
            enabled = True
        elif isinstance(item, dict):
            enabled_value = item.get("enabled", True)
            if not isinstance(enabled_value, bool):
                filtered_count += 1
                continue
            enabled = enabled_value
            raw_code = _first_text(
                item, "operationCode", "code", "key", "value", maximum=120
            )
            label = _first_text(
                item,
                "operationName",
                "label",
                "name",
                "title",
                maximum=240,
            )
        else:
            filtered_count += 1
            continue
        normalized_code = str(raw_code or "").strip().lower()
        normalized_code = MVS_TASK_OPERATION_ALIASES.get(
            normalized_code, normalized_code
        )
        if (
            not enabled
            or normalized_code not in MVS_TASK_OPERATION_LABELS
            or normalized_code in seen
        ):
            filtered_count += 1
            continue
        seen.add(normalized_code)
        items.append(
            {
                "code": normalized_code,
                "label": label or MVS_TASK_OPERATION_LABELS[normalized_code],
            }
        )
    return {
        "definitionId": definition_id,
        "nodeCode": node_code,
        "items": items,
        "filteredCount": filtered_count,
    }


def _first_text(
    value: Mapping[str, object], *keys: str, maximum: int
) -> str:
    for key in keys:
        item = value.get(key)
        if _valid_text(item, maximum):
            return str(item).strip()
    return ""


def _first_scalar(value: Mapping[str, object], *keys: str) -> object:
    for key in keys:
        item = value.get(key)
        if isinstance(item, bool) or item is None:
            continue
        if isinstance(item, (int, float)):
            return item
        if _valid_text(item, 200):
            return str(item).strip()
    return None


def _bounded_json(value: object, depth: int = 0) -> object:
    if depth > 8:
        raise MvsProviderUnavailable("mvs_response_invalid")
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value
    if isinstance(value, str):
        if not _valid_text(value, 10_000):
            raise MvsProviderUnavailable("mvs_response_invalid")
        return value.strip()
    if isinstance(value, list):
        if len(value) > 1000:
            raise MvsProviderUnavailable("mvs_response_invalid")
        return [_bounded_json(item, depth + 1) for item in value]
    if isinstance(value, dict):
        if len(value) > 500:
            raise MvsProviderUnavailable("mvs_response_invalid")
        result = {}
        for key, item in value.items():
            if not isinstance(key, str) or not _valid_identifier(key, 160):
                raise MvsProviderUnavailable("mvs_response_invalid")
            result[key] = _bounded_json(item, depth + 1)
        return result
    raise MvsProviderUnavailable("mvs_response_invalid")


def _order_id(value: object) -> str:
    return _numeric_identifier(value, "mvs_order_id_invalid")


def _numeric_identifier(value: object, error: str) -> str:
    normalized = str(value or "").strip()
    if re.fullmatch(r"[1-9][0-9]{0,18}", normalized) is None:
        raise ValueError(error)
    return normalized


def _valid_identifier(value: object, maximum: int) -> bool:
    return (
        isinstance(value, str)
        and 1 <= len(value.strip()) <= maximum
        and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:@-]*", value.strip()) is not None
    )


def _valid_text(value: object, maximum: int) -> bool:
    if not isinstance(value, str):
        return False
    normalized = value.strip()
    if not normalized or len(normalized) > maximum:
        return False
    return all(character >= " " or character in "\t\r\n" for character in normalized)


def _mask_name(value: str) -> str:
    if len(value) <= 1:
        return "*"
    return value[0] + "*" * (len(value) - 1)


def _mask_phone(value: str) -> str:
    digits = re.sub(r"\D", "", value)
    if len(digits) >= 7:
        return digits[:3] + "****" + digits[-4:]
    if len(value) <= 2:
        return "*" * len(value)
    return value[:1] + "*" * (len(value) - 2) + value[-1:]
