package com.codex.air3nativecamera.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;

public final class MvsWorkOrderDeviceClientTest {
    @Test
    public void listsBoundEngineerOrdersWithShortSessionAndStrictDto() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, "{\"items\":[{\"sourceSystem\":\"mvs\"," +
                "\"orderId\":42,\"orderNo\":\"MVS-42\"," +
                "\"orderStatus\":\"EXECUTING\",\"flowStatus\":\"ACTIVE\"," +
                "\"nodeName\":\"现场处理\",\"projectName\":\"冷站年度维护\"," +
                "\"siteName\":\"一号冷站\",\"addressDetail\":\"园区一号楼\"," +
                "\"assetName\":\"冷水机组控制器\",\"assetModel\":\"HF-CH-01\"," +
                "\"priority\":\"high\",\"contactName\":\"张*\"," +
                "\"contactPhone\":\"138****8000\"," +
                "\"unexpectedAdminField\":\"must-not-enter-dto\"}]," +
                "\"view\":\"executing\"}");
        MvsWorkOrderDeviceClient client = client(connections);

        MvsWorkOrderDeviceClient.WorkOrderPage page =
                client.listWorkOrders("executing", 20);

        assertEquals(1, page.items().size());
        MvsWorkOrderDeviceClient.WorkOrder order = page.items().get(0);
        assertEquals("42", order.orderId());
        assertEquals("MVS-42", order.orderNo());
        assertEquals("冷水机组控制器", order.assetName());
        assertEquals("HF-CH-01", order.assetModel());
        assertEquals("张*", order.contactName());
        assertEquals("executing", page.view());
        assertEquals(configuration().workOrderEndpoint()
                + "?view=executing&limit=20", connections.opened.get(0));
        assertEquals("Bearer access-a",
                connections.used.get(0).getRequestProperty("Authorization"));
        assertFalse(connections.used.get(0).getRequestProperty("Authorization")
                .contains("bootstrap-a"));
    }

    @Test
    public void lazilyLoadsOnlyTheRequestedDetailResources() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, resource("detail",
                "{\"sourceSystem\":\"mvs\",\"orderId\":42," +
                        "\"orderNo\":\"MVS-42\",\"definitionId\":99," +
                        "\"nodeCode\":\"onsite-repair\",\"nodeName\":\"现场处理\"}"));
        connections.enqueue(200, resource("node_form",
                "{\"formId\":12,\"fields\":[{\"key\":\"photo\"}]}"));
        connections.enqueue(200, resource("sop_tree",
                "[{\"title\":\"停机确认\",\"children\":[]}]"));
        connections.enqueue(200, resource("attachments",
                "[{\"name\":\"铭牌照片.jpg\",\"resourceId\":88}]"));
        connections.enqueue(200, resource("flow_records",
                "[{\"nodeName\":\"已签到\"}]"));
        connections.enqueue(200, resource("task_operations",
                "{\"definitionId\":\"99\",\"nodeCode\":\"onsite-repair\"," +
                        "\"items\":[{\"code\":\"skip\",\"label\":\"提交并推进\"}," +
                        "{\"code\":\"reject\",\"label\":\"退回\"}],\"filteredCount\":1}"));
        connections.enqueue(200, resource("checkin_form",
                "{\"formId\":13,\"direction\":\"in\"}"));
        connections.enqueue(200, resource("checkin_required", "true"));
        MvsWorkOrderDeviceClient client = client(connections);

        MvsWorkOrderDeviceClient.WorkOrder detail = client.getDetail("42");
        JSONObject nodeForm = client.getNodeForm("42");
        JSONArray sop = client.getSopTree("42");
        JSONArray attachments = client.getAttachments("42");
        JSONArray records = client.getFlowRecords("42");
        MvsWorkOrderDeviceClient.TaskOperations operations =
                client.getTaskOperations("42");
        JSONObject checkinForm = client.getCheckinForm("42", "in");
        boolean required = client.isCheckinRequired("42", "99");

        assertEquals("现场处理", detail.nodeName());
        assertEquals("99", detail.definitionId());
        assertEquals("onsite-repair", detail.nodeCode());
        assertEquals(12, nodeForm.getInt("formId"));
        assertEquals("停机确认", sop.getJSONObject(0).getString("title"));
        assertEquals("铭牌照片.jpg", attachments.getJSONObject(0).getString("name"));
        assertEquals("已签到", records.getJSONObject(0).getString("nodeName"));
        assertEquals("99", operations.definitionId());
        assertEquals("onsite-repair", operations.nodeCode());
        assertEquals(2, operations.items().size());
        assertEquals("skip", operations.items().get(0).code());
        assertEquals("提交并推进", operations.items().get(0).label());
        assertEquals(1, operations.filteredCount());
        assertEquals(13, checkinForm.getInt("formId"));
        assertTrue(required);
        assertTrue(connections.opened.get(1).endsWith(
                "/device-sync/work-orders/42?resource=node_form"));
        assertTrue(connections.opened.get(2).endsWith(
                "/device-sync/work-orders/42?resource=sop_tree"));
        assertTrue(connections.opened.get(3).endsWith(
                "/device-sync/work-orders/42?resource=attachments"));
        assertTrue(connections.opened.get(4).endsWith(
                "/device-sync/work-orders/42?resource=flow_records"));
        assertTrue(connections.opened.get(5).endsWith(
                "/device-sync/work-orders/42?resource=task_operations"));
        assertTrue(connections.opened.get(6).endsWith(
                "/device-sync/work-orders/42?resource=checkin_form&direction=in"));
        assertTrue(connections.opened.get(7).endsWith(
                "/device-sync/work-orders/42?resource=checkin_required&definitionId=99"));
    }

    @Test
    public void submitsOnlyExplicitlyConfirmedCheckinAndParsesIdempotentReceipt()
            throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(200, "{\"ok\":true,\"operationId\":\"operation-a\"," +
                "\"duplicate\":false,\"retryable\":false," +
                "\"result\":{\"recordId\":88}}");
        MvsWorkOrderDeviceClient client = client(connections);
        MvsWorkOrderDeviceClient.CheckinCommand command =
                new MvsWorkOrderDeviceClient.CheckinCommand(
                        "in",
                        "CONFIRM_MVS_CHECKIN",
                        "mvs-checkin-42-a",
                        "trace-mvs-checkin-42-a",
                        31.2304,
                        121.4737,
                        "园区一号楼",
                        12,
                        new JSONObject().put("photoAssetId", "asset-a"));

        MvsWorkOrderDeviceClient.CheckinReceipt receipt =
                client.submitCheckin("42", command);

        assertEquals("operation-a", receipt.operationId());
        assertFalse(receipt.duplicate());
        assertFalse(receipt.retryable());
        assertEquals(88, receipt.result().getInt("recordId"));
        CapturingConnection connection = connections.used.get(0);
        assertEquals("POST", connection.method);
        JSONObject body = new JSONObject(connection.requestText());
        assertEquals("in", body.getString("direction"));
        assertEquals("CONFIRM_MVS_CHECKIN", body.getString("confirmation"));
        assertEquals("mvs-checkin-42-a", body.getString("idempotencyKey"));
        assertEquals("asset-a",
                body.getJSONObject("formContent").getString("photoAssetId"));
    }

    @Test
    public void exposesRetryableFailureWithoutTurningItIntoSuccess() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        connections.enqueue(503, "{\"ok\":false,\"operationId\":\"operation-b\"," +
                "\"duplicate\":true,\"retryable\":true," +
                "\"error\":\"mvs_provider_unavailable\"}");
        MvsWorkOrderDeviceClient client = client(connections);

        try {
            client.submitCheckin("42", new MvsWorkOrderDeviceClient.CheckinCommand(
                    "out",
                    "CONFIRM_MVS_CHECKOUT",
                    "mvs-checkout-42-a",
                    "trace-mvs-checkout-42-a",
                    31.2304,
                    121.4737,
                    "",
                    null,
                    null));
            fail("retryable MVS failure must not return success");
        } catch (MvsWorkOrderDeviceClient.MvsRequestException exception) {
            assertEquals(503, exception.status());
            assertEquals("mvs_provider_unavailable", exception.errorCode());
            assertEquals("operation-b", exception.operationId());
            assertTrue(exception.retryable());
            assertTrue(exception.duplicate());
        }
    }

    @Test
    public void rejectsWrongConfirmationAndMalformedProviderDataBeforeUse() throws Exception {
        QueueConnectionFactory connections = new QueueConnectionFactory();
        MvsWorkOrderDeviceClient client = client(connections);
        try {
            client.submitCheckin("42", new MvsWorkOrderDeviceClient.CheckinCommand(
                    "in", "CONFIRM_MVS_CHECKOUT", "key-a", "trace-a",
                    31.2, 121.4, "", null, null));
            fail("wrong confirmation must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("confirmation"));
        }

        connections.enqueue(200, "{\"items\":[{\"sourceSystem\":\"other\"," +
                "\"orderId\":42}],\"view\":\"executing\"}");
        try {
            client.listWorkOrders("executing", 20);
            fail("non-MVS response must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("mvs_work_order_response_invalid"));
        }
    }

    private MvsWorkOrderDeviceClient client(QueueConnectionFactory connections) {
        return new MvsWorkOrderDeviceClient(
                configuration(),
                new DeviceAccessTokenProvider() {
                    @Override
                    public String accessToken() {
                        return "access-a";
                    }
                },
                connections);
    }

    private DeviceSyncConfiguration configuration() {
        return DeviceSyncConfiguration.fromManagedValues(
                "https://ops.example.com/v9-ops/device-sync/events",
                "bootstrap-a");
    }

    private static String resource(String type, String value) {
        return "{\"orderId\":\"42\",\"resourceType\":\"" + type
                + "\",\"resource\":" + value + "}";
    }

    private static final class QueueConnectionFactory implements HttpConnectionFactory {
        private final Queue<Response> responses = new ArrayDeque<>();
        private final java.util.List<String> opened = new java.util.ArrayList<>();
        private final java.util.List<CapturingConnection> used = new java.util.ArrayList<>();

        void enqueue(int status, String body) {
            responses.add(new Response(status, body));
        }

        @Override
        public HttpURLConnection open(String endpoint) throws IOException {
            Response response = responses.remove();
            CapturingConnection connection = new CapturingConnection(
                    new URL(endpoint), response.status, response.body);
            opened.add(endpoint);
            used.add(connection);
            return connection;
        }
    }

    private static final class Response {
        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class CapturingConnection extends HttpURLConnection {
        private final int status;
        private final byte[] response;
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();
        private String method = "";

        private CapturingConnection(URL url, int status, String body) {
            super(url);
            this.status = status;
            this.response = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override public void setRequestMethod(String method) { this.method = method; }
        @Override public OutputStream getOutputStream() { return request; }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(response); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }

        private String requestText() {
            return new String(request.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
