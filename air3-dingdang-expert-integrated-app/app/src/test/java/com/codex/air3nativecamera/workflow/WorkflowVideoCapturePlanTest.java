package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class WorkflowVideoCapturePlanTest {
    @Test
    public void acceptsOnlyTheAir3BoundShortVideoContract() throws Exception {
        WorkflowVideoCapturePlan plan = WorkflowVideoCapturePlan.from(
                videoNode(new JSONObject()
                        .put("evidenceKey", "control-panel")
                        .put("minCount", 2)
                        .put("minDurationSeconds", 3)
                        .put("maxDurationSeconds", 15)),
                15);

        assertEquals("video", plan.nodeId());
        assertEquals("control-panel", plan.evidenceKey());
        assertEquals(2, plan.minimumCount());
        assertEquals(3, plan.minimumDurationSeconds());
        assertEquals(15, plan.maximumDurationSeconds());
        assertEquals(15_000L, plan.maximumDurationMillis());
        assertEquals(12, plan.completedDurationSeconds(1_000L, 13_999L));
        assertEquals(15, plan.completedDurationSeconds(1_000L, 20_000L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAWorkflowThatExceedsTheVerifiedDeviceLimit() throws Exception {
        WorkflowVideoCapturePlan.from(
                videoNode(new JSONObject()
                        .put("evidenceKey", "panel")
                        .put("minCount", 1)
                        .put("minDurationSeconds", 1)
                        .put("maxDurationSeconds", 16)),
                15);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsContradictoryVideoDurations() throws Exception {
        WorkflowVideoCapturePlan.from(
                videoNode(new JSONObject()
                        .put("evidenceKey", "panel")
                        .put("minCount", 1)
                        .put("minDurationSeconds", 8)
                        .put("maxDurationSeconds", 7)),
                15);
    }

    private static WorkflowPackage.Node videoNode(JSONObject config) throws Exception {
        JSONObject envelope = new JSONObject()
                .put("assignmentId", "11111111-1111-4111-8111-111111111111")
                .put("workflowVersionId", "33333333-3333-4333-8333-333333333333")
                .put("signatureKeyId", "test-key")
                .put("minAppVersionCode", 9000);
        JSONObject executionPackage = new JSONObject()
                .put("workflowId", "22222222-2222-4222-8222-222222222222")
                .put("schemaVersion", 1)
                .put("title", "录像流程")
                .put("contentSha256", repeat('a', 64))
                .put("requiredCapabilities", new JSONArray()
                        .put("workflow.runtime.v1").put("camera.video"))
                .put("nodes", new JSONArray()
                        .put(node("start", "start", new JSONObject()))
                        .put(node("video", "video_capture", config))
                        .put(node("complete", "complete", new JSONObject())))
                .put("transitions", new JSONArray()
                        .put(transition("start-video", "start", "video"))
                        .put(transition("video-complete", "video", "complete")));
        return WorkflowPackage.parseVerified(envelope, executionPackage).node("video");
    }

    private static JSONObject node(String id, String type, JSONObject config) throws Exception {
        return new JSONObject().put("nodeId", id).put("type", type).put("config", config);
    }

    private static JSONObject transition(String id, String from, String to) throws Exception {
        return new JSONObject().put("transitionId", id)
                .put("fromNodeId", from).put("toNodeId", to);
    }

    private static String repeat(char value, int count) {
        char[] values = new char[count];
        java.util.Arrays.fill(values, value);
        return new String(values);
    }
}
