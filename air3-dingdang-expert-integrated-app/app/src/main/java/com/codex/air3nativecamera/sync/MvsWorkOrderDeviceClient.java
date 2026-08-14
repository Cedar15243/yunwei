package com.codex.air3nativecamera.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Short-session client for the server-isolated MVS work-order whitelist. */
public final class MvsWorkOrderDeviceClient {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_FORM_CONTENT_BYTES = 16 * 1024;
    private static final Set<String> VIEWS = immutableSet(
            "pending", "pending_execute", "executing", "completed");
    private static final Set<String> RESOURCES = immutableSet(
            "detail", "node_form", "sop_tree", "attachments",
            "flow_records", "execution_records",
            "checkins", "checkin_form", "checkin_required", "task_operations");
    private static final Set<String> TASK_OPERATION_CODES = immutableSet(
            "skip", "transfer", "reject", "back-to-node");

    public static final class WorkOrder {
        private final String orderId;
        private final String orderNo;
        private final String orderStatus;
        private final String flowStatus;
        private final String definitionId;
        private final String nodeCode;
        private final String nodeName;
        private final String projectName;
        private final String siteName;
        private final String addressDetail;
        private final String assetName;
        private final String assetModel;
        private final String priority;
        private final String contactName;
        private final String contactPhone;

        private WorkOrder(JSONObject value) throws IOException {
            if (!"mvs".equals(value.optString("sourceSystem", ""))) {
                throw invalidResponse();
            }
            orderId = numericIdentifier(value.opt("orderId"));
            orderNo = requiredText(value.opt("orderNo"), 240);
            orderStatus = optionalText(value.opt("orderStatus"), 120);
            flowStatus = optionalText(value.opt("flowStatus"), 120);
            definitionId = optionalNumericIdentifier(value.opt("definitionId"));
            nodeCode = optionalIdentifier(value.opt("nodeCode"));
            nodeName = optionalText(value.opt("nodeName"), 240);
            projectName = optionalText(value.opt("projectName"), 240);
            siteName = optionalText(value.opt("siteName"), 240);
            addressDetail = optionalText(value.opt("addressDetail"), 1_000);
            assetName = optionalText(value.opt("assetName"), 240);
            assetModel = optionalText(value.opt("assetModel"), 240);
            priority = optionalText(value.opt("priority"), 80);
            contactName = optionalText(value.opt("contactName"), 120);
            contactPhone = optionalText(value.opt("contactPhone"), 60);
        }

        public String orderId() { return orderId; }
        public String orderNo() { return orderNo; }
        public String orderStatus() { return orderStatus; }
        public String flowStatus() { return flowStatus; }
        public String definitionId() { return definitionId; }
        public String nodeCode() { return nodeCode; }
        public String nodeName() { return nodeName; }
        public String projectName() { return projectName; }
        public String siteName() { return siteName; }
        public String addressDetail() { return addressDetail; }
        public String assetName() { return assetName; }
        public String assetModel() { return assetModel; }
        public String priority() { return priority; }
        public String contactName() { return contactName; }
        public String contactPhone() { return contactPhone; }
    }

    public static final class WorkOrderPage {
        private final List<WorkOrder> items;
        private final String view;

        private WorkOrderPage(List<WorkOrder> items, String view) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.view = view;
        }

        public List<WorkOrder> items() { return items; }
        public String view() { return view; }
    }

    public static final class CheckinCommand {
        private final String direction;
        private final String confirmation;
        private final String idempotencyKey;
        private final String traceId;
        private final double lat;
        private final double lng;
        private final String address;
        private final Integer formId;
        private final JSONObject formContent;

        public CheckinCommand(
                String direction,
                String confirmation,
                String idempotencyKey,
                String traceId,
                double lat,
                double lng,
                String address,
                Integer formId,
                JSONObject formContent
        ) {
            this.direction = clean(direction);
            String expected = "in".equals(this.direction)
                    ? "CONFIRM_MVS_CHECKIN"
                    : "out".equals(this.direction) ? "CONFIRM_MVS_CHECKOUT" : "";
            if (expected.isEmpty()) throw new IllegalArgumentException("direction is invalid");
            this.confirmation = clean(confirmation);
            if (!expected.equals(this.confirmation)) {
                throw new IllegalArgumentException("confirmation is invalid");
            }
            this.idempotencyKey = requiredIdentifier(idempotencyKey, "idempotency key");
            this.traceId = requiredIdentifier(traceId, "trace id");
            if (!Double.isFinite(lat) || lat < -90d || lat > 90d
                    || !Double.isFinite(lng) || lng < -180d || lng > 180d) {
                throw new IllegalArgumentException("location is invalid");
            }
            this.lat = lat;
            this.lng = lng;
            this.address = optionalInputText(address, 1_000, "address");
            if (formId != null && formId <= 0) {
                throw new IllegalArgumentException("form id is invalid");
            }
            this.formId = formId;
            this.formContent = copyFormContent(formContent);
        }

        private JSONObject toJson() throws IOException {
            try {
                JSONObject value = new JSONObject()
                        .put("direction", direction)
                        .put("confirmation", confirmation)
                        .put("idempotencyKey", idempotencyKey)
                        .put("traceId", traceId)
                        .put("lat", lat)
                        .put("lng", lng);
                if (!address.isEmpty()) value.put("address", address);
                if (formId != null) value.put("formId", formId);
                if (formContent != null) value.put("formContent", new JSONObject(formContent.toString()));
                return value;
            } catch (JSONException exception) {
                throw new IOException("mvs_checkin_request_invalid", exception);
            }
        }
    }

    /** Server-authoritative operation descriptor. It intentionally has no write method. */
    public static final class TaskOperation {
        private final String code;
        private final String label;

        private TaskOperation(JSONObject value) throws IOException {
            if (value == null) throw invalidResponse();
            code = requiredIdentifierResponse(value.opt("code"), "task operation code");
            if (!TASK_OPERATION_CODES.contains(code)) throw invalidResponse();
            label = requiredText(value.opt("label"), 240);
        }

        public String code() { return code; }
        public String label() { return label; }
    }

    /** Read-only operation list bound to the active MVS process node. */
    public static final class TaskOperations {
        private final String definitionId;
        private final String nodeCode;
        private final List<TaskOperation> items;
        private final int filteredCount;

        private TaskOperations(JSONObject value) throws IOException {
            this(
                    numericIdentifier(value == null ? null : value.opt("definitionId")),
                    requiredIdentifierResponse(
                            value == null ? null : value.opt("nodeCode"), "node code"),
                    parseTaskOperations(value == null ? null : value.optJSONArray("items")),
                    parseFilteredCount(value == null ? null : value.opt("filteredCount")));
        }

        private TaskOperations(
                String definitionId,
                String nodeCode,
                List<TaskOperation> items,
                int filteredCount
        ) throws IOException {
            this.definitionId = numericIdentifier(definitionId);
            this.nodeCode = requiredIdentifierResponse(nodeCode, "node code");
            if (items == null || items.size() > TASK_OPERATION_CODES.size()
                    || filteredCount < 0 || filteredCount > 100) {
                throw invalidResponse();
            }
            Set<String> codes = new HashSet<>();
            for (TaskOperation item : items) {
                if (item == null || !codes.add(item.code())) throw invalidResponse();
            }
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.filteredCount = filteredCount;
        }

        public String definitionId() { return definitionId; }
        public String nodeCode() { return nodeCode; }
        public List<TaskOperation> items() { return items; }
        public int filteredCount() { return filteredCount; }
    }

    public static final class CheckinReceipt {
        private final String operationId;
        private final boolean duplicate;
        private final boolean retryable;
        private final JSONObject result;

        private CheckinReceipt(JSONObject value) throws IOException {
            if (!value.optBoolean("ok", false)) throw invalidResponse();
            operationId = requiredIdentifier(value.optString("operationId", ""), "operation id");
            duplicate = value.optBoolean("duplicate", false);
            retryable = value.optBoolean("retryable", false);
            JSONObject resultValue = value.optJSONObject("result");
            if (resultValue == null) throw invalidResponse();
            try {
                result = new JSONObject(resultValue.toString());
            } catch (JSONException exception) {
                throw invalidResponse();
            }
        }

        public String operationId() { return operationId; }
        public boolean duplicate() { return duplicate; }
        public boolean retryable() { return retryable; }
        public JSONObject result() {
            try {
                return new JSONObject(result.toString());
            } catch (JSONException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    public static final class MvsRequestException extends IOException {
        private final int status;
        private final String errorCode;
        private final String operationId;
        private final boolean retryable;
        private final boolean duplicate;

        private MvsRequestException(int status, JSONObject response) {
            super("mvs_http_" + status + ":" + safeErrorCode(
                    response.optString("error", "request_failed")));
            this.status = status;
            this.errorCode = safeErrorCode(response.optString("error", "request_failed"));
            this.operationId = safeIdentifier(response.optString("operationId", ""));
            this.retryable = response.optBoolean("retryable", false);
            this.duplicate = response.optBoolean("duplicate", false);
        }

        public int status() { return status; }
        public String errorCode() { return errorCode; }
        public String operationId() { return operationId; }
        public boolean retryable() { return retryable; }
        public boolean duplicate() { return duplicate; }
    }

    private final DeviceSyncConfiguration configuration;
    private final DeviceAccessTokenProvider tokenProvider;
    private final HttpConnectionFactory connectionFactory;

    public MvsWorkOrderDeviceClient(
            DeviceSyncConfiguration configuration,
            DeviceAccessTokenProvider tokenProvider
    ) {
        this(configuration, tokenProvider, HttpConnectionFactory.DEFAULT);
    }

    MvsWorkOrderDeviceClient(
            DeviceSyncConfiguration configuration,
            DeviceAccessTokenProvider tokenProvider,
            HttpConnectionFactory connectionFactory
    ) {
        if (configuration == null || tokenProvider == null || connectionFactory == null) {
            throw new IllegalArgumentException("mvs_work_order_client_configuration_missing");
        }
        this.configuration = configuration;
        this.tokenProvider = tokenProvider;
        this.connectionFactory = connectionFactory;
    }

    public WorkOrderPage listWorkOrders(String view, int limit) throws IOException {
        String acceptedView = clean(view);
        if (!VIEWS.contains(acceptedView)) {
            throw new IllegalArgumentException("work order view is invalid");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("work order limit is invalid");
        }
        JSONObject response = request("GET", configuration.workOrderEndpoint()
                + "?view=" + encode(acceptedView) + "&limit=" + limit, null);
        if (!acceptedView.equals(response.optString("view", ""))) throw invalidResponse();
        JSONArray values = response.optJSONArray("items");
        if (values == null || values.length() > limit) throw invalidResponse();
        List<WorkOrder> items = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) throw invalidResponse();
            items.add(new WorkOrder(value));
        }
        return new WorkOrderPage(items, acceptedView);
    }

    public WorkOrder getDetail(String orderId) throws IOException {
        return new WorkOrder(objectResource(orderId, "detail", "", ""));
    }

    public JSONObject getNodeForm(String orderId) throws IOException {
        return objectResource(orderId, "node_form", "", "");
    }

    public JSONArray getFlowRecords(String orderId) throws IOException {
        return arrayResource(orderId, "flow_records", "", "");
    }

    public JSONArray getSopTree(String orderId) throws IOException {
        return arrayResource(orderId, "sop_tree", "", "");
    }

    public JSONArray getAttachments(String orderId) throws IOException {
        return arrayResource(orderId, "attachments", "", "");
    }

    public JSONArray getExecutionRecords(String orderId) throws IOException {
        return arrayResource(orderId, "execution_records", "", "");
    }

    public JSONArray getCheckins(String orderId) throws IOException {
        return arrayResource(orderId, "checkins", "", "");
    }

    public JSONObject getCheckinForm(String orderId, String direction) throws IOException {
        String acceptedDirection = checkinDirection(direction);
        return objectResource(orderId, "checkin_form", "direction", acceptedDirection);
    }

    public boolean isCheckinRequired(String orderId, String definitionId) throws IOException {
        Object value = resource(orderId, "checkin_required", "definitionId",
                numericIdentifier(definitionId));
        if (!(value instanceof Boolean)) throw invalidResponse();
        return (Boolean) value;
    }

    public TaskOperations getTaskOperations(String orderId) throws IOException {
        return new TaskOperations(objectResource(orderId, "task_operations", "", ""));
    }

    public CheckinReceipt submitCheckin(String orderId, CheckinCommand command) throws IOException {
        if (command == null) throw new IllegalArgumentException("checkin command is missing");
        JSONObject response = request("POST", configuration.workOrderEndpoint()
                + "/" + encode(numericIdentifier(orderId)) + "/checkin", command.toJson());
        return new CheckinReceipt(response);
    }

    private JSONObject objectResource(
            String orderId,
            String resource,
            String queryName,
            String queryValue
    ) throws IOException {
        Object value = resource(orderId, resource, queryName, queryValue);
        if (!(value instanceof JSONObject)) throw invalidResponse();
        try {
            return new JSONObject(value.toString());
        } catch (JSONException exception) {
            throw invalidResponse();
        }
    }

    private JSONArray arrayResource(
            String orderId,
            String resource,
            String queryName,
            String queryValue
    ) throws IOException {
        Object value = resource(orderId, resource, queryName, queryValue);
        if (!(value instanceof JSONArray)) throw invalidResponse();
        try {
            return new JSONArray(value.toString());
        } catch (JSONException exception) {
            throw invalidResponse();
        }
    }

    private Object resource(
            String orderId,
            String resource,
            String queryName,
            String queryValue
    ) throws IOException {
        String acceptedOrderId = numericIdentifier(orderId);
        if (!RESOURCES.contains(resource)) throw new IllegalArgumentException("resource is invalid");
        String endpoint = configuration.workOrderEndpoint() + "/" + encode(acceptedOrderId)
                + "?resource=" + encode(resource);
        if (!queryName.isEmpty()) endpoint += "&" + queryName + "=" + encode(queryValue);
        JSONObject response = request("GET", endpoint, null);
        if (!acceptedOrderId.equals(numericIdentifier(response.opt("orderId")))
                || !resource.equals(response.optString("resourceType", ""))
                || !response.has("resource") || response.isNull("resource")) {
            throw invalidResponse();
        }
        return response.opt("resource");
    }

    private JSONObject request(String method, String endpoint, JSONObject body) throws IOException {
        HttpURLConnection connection = connectionFactory.open(endpoint);
        OutputStream output = null;
        try {
            String accessToken = clean(tokenProvider.accessToken());
            if (accessToken.isEmpty()) throw new IOException("device_access_token_missing");
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(12_000);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-store");
            if (body != null) {
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(payload.length);
                output = connection.getOutputStream();
                output.write(payload);
                output.close();
                output = null;
            }
            int status = connection.getResponseCode();
            JSONObject response = readObject(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (status < 200 || status >= 300) throw new MvsRequestException(status, response);
            return response;
        } finally {
            if (output != null) output.close();
            connection.disconnect();
        }
    }

    private static JSONObject readObject(InputStream input) throws IOException {
        if (input == null) throw new IOException("mvs_work_order_response_missing");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4_096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > MAX_RESPONSE_BYTES) {
                    throw new IOException("mvs_work_order_response_too_large");
                }
                if (read > 0) output.write(buffer, 0, read);
            }
            try {
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            } catch (JSONException exception) {
                throw invalidResponse();
            }
        } finally {
            input.close();
        }
    }

    private static JSONObject copyFormContent(JSONObject value) {
        if (value == null) return null;
        byte[] encoded = value.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_FORM_CONTENT_BYTES) {
            throw new IllegalArgumentException("form content is too large");
        }
        try {
            return new JSONObject(value.toString());
        } catch (JSONException exception) {
            throw new IllegalArgumentException("form content is invalid", exception);
        }
    }

    private static String numericIdentifier(Object value) throws IOException {
        String result = value == null || value == JSONObject.NULL ? "" : clean(String.valueOf(value));
        if (!result.matches("^[0-9]{1,32}$")) throw invalidResponse();
        return result;
    }

    private static String numericIdentifier(String value) {
        String result = clean(value);
        if (!result.matches("^[0-9]{1,32}$")) {
            throw new IllegalArgumentException("numeric identifier is invalid");
        }
        return result;
    }

    private static String optionalNumericIdentifier(Object value) throws IOException {
        if (value == null || value == JSONObject.NULL || clean(String.valueOf(value)).isEmpty()) return "";
        return numericIdentifier(value);
    }

    private static String optionalIdentifier(Object value) throws IOException {
        if (value == null || value == JSONObject.NULL || clean(String.valueOf(value)).isEmpty()) {
            return "";
        }
        return requiredIdentifierResponse(value, "identifier");
    }

    private static String requiredText(Object value, int maximum) throws IOException {
        String result = optionalText(value, maximum);
        if (result.isEmpty()) throw invalidResponse();
        return result;
    }

    private static String optionalText(Object value, int maximum) throws IOException {
        if (value == null || value == JSONObject.NULL) return "";
        if (!(value instanceof String)) throw invalidResponse();
        String result = clean((String) value);
        if (result.length() > maximum) throw invalidResponse();
        return result;
    }

    private static String optionalInputText(String value, int maximum, String label) {
        String result = clean(value);
        if (result.length() > maximum) throw new IllegalArgumentException(label + " is invalid");
        return result;
    }

    private static String checkinDirection(String value) {
        String result = clean(value);
        if (!"in".equals(result) && !"out".equals(result)) {
            throw new IllegalArgumentException("direction is invalid");
        }
        return result;
    }

    private static String requiredIdentifier(String value, String label) {
        String result = clean(value);
        if (!result.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return result;
    }

    private static String requiredIdentifierResponse(Object value, String label) throws IOException {
        if (!(value instanceof String)) throw invalidResponse();
        String result = clean((String) value);
        if (!result.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")) {
            throw invalidResponse();
        }
        return result;
    }

    private static List<TaskOperation> parseTaskOperations(JSONArray values) throws IOException {
        if (values == null || values.length() > TASK_OPERATION_CODES.size()) {
            throw invalidResponse();
        }
        List<TaskOperation> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) throw invalidResponse();
            result.add(new TaskOperation(value));
        }
        return result;
    }

    private static int parseFilteredCount(Object value) throws IOException {
        if (!(value instanceof Number) || value instanceof Float || value instanceof Double) {
            throw invalidResponse();
        }
        long count = ((Number) value).longValue();
        if (count < 0 || count > 100) throw invalidResponse();
        return (int) count;
    }

    private static String safeIdentifier(String value) {
        String result = clean(value);
        return result.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$") ? result : "";
    }

    private static String safeErrorCode(String value) {
        String result = clean(value);
        return result.matches("^[a-z0-9_]{1,120}$") ? result : "request_failed";
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static IOException invalidResponse() {
        return new IOException("mvs_work_order_response_invalid");
    }

    private static Set<String> immutableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
