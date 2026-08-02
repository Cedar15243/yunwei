package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class WorkflowVoiceInputPlanTest {
    @Test
    public void acceptsOnlyTheAir3BoundVoiceInputContract() throws Exception {
        WorkflowVoiceInputPlan required = WorkflowVoiceInputPlan.from(
                voiceNode(new JSONObject()
                        .put("fieldKey", "delivery_note")
                        .put("maxDurationSeconds", 30)
                        .put("required", true)),
                30);

        assertEquals("voice", required.nodeId());
        assertEquals("delivery_note", required.fieldKey());
        assertEquals(30, required.maximumDurationSeconds());
        assertEquals(30_000L, required.maximumDurationMillis());
        assertTrue(required.required());

        WorkflowVoiceInputPlan optional = WorkflowVoiceInputPlan.from(
                voiceNode(new JSONObject()
                        .put("fieldKey", "optional_note")
                        .put("maxDurationSeconds", 12)
                        .put("required", false)),
                30);
        assertFalse(optional.required());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAWorkflowThatExceedsTheVerifiedDeviceLimit() throws Exception {
        WorkflowVoiceInputPlan.from(
                voiceNode(new JSONObject()
                        .put("fieldKey", "delivery_note")
                        .put("maxDurationSeconds", 31)
                        .put("required", true)),
                30);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingFieldKeys() throws Exception {
        WorkflowVoiceInputPlan.from(
                voiceNode(new JSONObject()
                        .put("maxDurationSeconds", 10)
                        .put("required", true)),
                30);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonBooleanRequiredFlags() throws Exception {
        WorkflowVoiceInputPlan.from(
                voiceNode(new JSONObject()
                        .put("fieldKey", "delivery_note")
                        .put("maxDurationSeconds", 10)
                        .put("required", "true")),
                30);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsFractionalDurations() throws Exception {
        WorkflowVoiceInputPlan.from(
                voiceNode(new JSONObject()
                        .put("fieldKey", "delivery_note")
                        .put("maxDurationSeconds", 10.5)
                        .put("required", true)),
                30);
    }

    private static WorkflowPackage.Node voiceNode(JSONObject config) throws Exception {
        JSONObject envelope = new JSONObject()
                .put("assignmentId", "11111111-1111-4111-8111-111111111111")
                .put("workflowVersionId", "33333333-3333-4333-8333-333333333333")
                .put("signatureKeyId", "test-key")
                .put("minAppVersionCode", 9000);
        JSONObject executionPackage = new JSONObject()
                .put("workflowId", "22222222-2222-4222-8222-222222222222")
                .put("schemaVersion", 1)
                .put("title", "语音填写流程")
                .put("contentSha256", repeat('a', 64))
                .put("requiredCapabilities", new JSONArray()
                        .put("workflow.runtime.v1").put("audio.voice_input"))
                .put("nodes", new JSONArray()
                        .put(node("start", "start", new JSONObject()))
                        .put(node("voice", "voice_input", config))
                        .put(node("complete", "complete", new JSONObject())))
                .put("transitions", new JSONArray()
                        .put(transition("start-voice", "start", "voice"))
                        .put(transition("voice-complete", "voice", "complete")));
        return WorkflowPackage.parseVerified(envelope, executionPackage).node("voice");
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
