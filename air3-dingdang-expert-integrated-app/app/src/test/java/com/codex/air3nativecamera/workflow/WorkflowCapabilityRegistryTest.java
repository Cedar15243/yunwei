package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class WorkflowCapabilityRegistryTest {
    @Test
    public void exposesOnlyTheFixedHudAndNativeCapabilityBindings() {
        assertEquals(WorkflowCapabilityRegistry.PageTemplate.EVIDENCE_CAPTURE,
                WorkflowCapabilityRegistry.bindingForNodeType("photo_capture").pageTemplate());
        assertEquals("camera.photo",
                WorkflowCapabilityRegistry.bindingForNodeType("photo_capture").capabilityId());
        assertEquals(WorkflowCapabilityRegistry.ExecutionKind.UI_ONLY,
                WorkflowCapabilityRegistry.bindingForNodeType("instruction").executionKind());
        assertEquals(WorkflowCapabilityRegistry.ExecutionKind.INTERNAL,
                WorkflowCapabilityRegistry.bindingForNodeType("condition").executionKind());
        assertEquals(WorkflowCapabilityRegistry.PageTemplate.CONVERSATION,
                WorkflowCapabilityRegistry.bindingForNodeType("expert_call").pageTemplate());

        assertEquals(15, WorkflowCapabilityRegistry.nodeTypes().size());
        assertFalse(WorkflowCapabilityRegistry.nodeTypes().contains("http_request"));
    }

    @Test
    public void dispatchesThroughARegisteredNativeAdapterAndCompletesOnlyOnce() throws Exception {
        AtomicInteger handlerCalls = new AtomicInteger();
        AtomicInteger callbackCalls = new AtomicInteger();
        AtomicReference<WorkflowCapabilityRegistry.Result> result = new AtomicReference<>();
        Map<String, WorkflowCapabilityRegistry.Handler> handlers = new LinkedHashMap<>();
        handlers.put("camera.photo", (request, callback) -> {
            handlerCalls.incrementAndGet();
            assertEquals("photo", request.node().nodeId());
            callback.complete(WorkflowCapabilityRegistry.Result.completed(
                    resultPayload("localEvidenceId", "photo-1")));
            callback.complete(WorkflowCapabilityRegistry.Result.failed("duplicate_callback"));
        });
        WorkflowCapabilityRegistry registry = new WorkflowCapabilityRegistry(handlers);

        WorkflowCapabilityRegistry.Dispatch dispatch = registry.dispatch(
                photoNode(),
                new WorkflowCapabilityRegistry.Request(
                        "assignment-a", "execution-a", 1, new JSONObject()),
                value -> {
                    callbackCalls.incrementAndGet();
                    result.set(value);
                });

        assertEquals(WorkflowCapabilityRegistry.Dispatch.STARTED, dispatch);
        assertEquals(1, handlerCalls.get());
        assertEquals(1, callbackCalls.get());
        assertEquals(WorkflowCapabilityRegistry.Result.Status.COMPLETED, result.get().status());
        assertTrue(registry.supportedCapabilities().contains("workflow.runtime.v1"));
        assertTrue(registry.supportedCapabilities().contains("camera.photo"));
    }

    @Test
    public void failsClosedWhenTheNativeCapabilityIsMissingOrThrows() throws Exception {
        AtomicReference<WorkflowCapabilityRegistry.Result> missing = new AtomicReference<>();
        WorkflowCapabilityRegistry empty = new WorkflowCapabilityRegistry(
                new LinkedHashMap<String, WorkflowCapabilityRegistry.Handler>());
        assertEquals(WorkflowCapabilityRegistry.Dispatch.UNAVAILABLE,
                empty.dispatch(
                        photoNode(),
                        new WorkflowCapabilityRegistry.Request(
                                "assignment-a", "execution-a", 1, new JSONObject()),
                        missing::set));
        assertEquals("capability_unavailable", missing.get().errorCode());

        Map<String, WorkflowCapabilityRegistry.Handler> handlers = new LinkedHashMap<>();
        handlers.put("camera.photo", (request, callback) -> {
            throw new IllegalStateException("camera busy");
        });
        AtomicReference<WorkflowCapabilityRegistry.Result> failed = new AtomicReference<>();
        WorkflowCapabilityRegistry throwing = new WorkflowCapabilityRegistry(handlers);
        assertEquals(WorkflowCapabilityRegistry.Dispatch.FAILED,
                throwing.dispatch(
                        photoNode(),
                        new WorkflowCapabilityRegistry.Request(
                                "assignment-a", "execution-a", 1, new JSONObject()),
                        failed::set));
        assertEquals("capability_start_failed", failed.get().errorCode());
    }

    private WorkflowPackage.Node photoNode() throws Exception {
        JSONObject envelope = new JSONObject()
                .put("assignmentId", "11111111-1111-4111-8111-111111111111")
                .put("workflowVersionId", "33333333-3333-4333-8333-333333333333")
                .put("signatureKeyId", "test-key")
                .put("minAppVersionCode", 9000);
        JSONObject executionPackage = new JSONObject()
                .put("workflowId", "22222222-2222-4222-8222-222222222222")
                .put("schemaVersion", 1)
                .put("title", "拍照流程")
                .put("contentSha256", new String(new char[64]).replace('\0', 'a'))
                .put("requiredCapabilities", new JSONArray()
                        .put("workflow.runtime.v1").put("camera.photo"))
                .put("nodes", new JSONArray()
                        .put(node("start", "start"))
                        .put(node("photo", "photo_capture"))
                        .put(node("complete", "complete")))
                .put("transitions", new JSONArray()
                        .put(transition("start-photo", "start", "photo"))
                        .put(transition("photo-complete", "photo", "complete")));
        return WorkflowPackage.parseVerified(envelope, executionPackage).node("photo");
    }

    private JSONObject node(String id, String type) throws Exception {
        return new JSONObject().put("nodeId", id).put("type", type).put("config", new JSONObject());
    }

    private JSONObject transition(String id, String from, String to) throws Exception {
        return new JSONObject()
                .put("transitionId", id)
                .put("fromNodeId", from)
                .put("toNodeId", to);
    }

    private static JSONObject resultPayload(String key, Object value) {
        try {
            return new JSONObject().put(key, value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
