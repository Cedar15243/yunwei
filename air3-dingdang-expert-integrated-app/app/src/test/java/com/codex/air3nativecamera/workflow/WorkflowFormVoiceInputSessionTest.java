package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class WorkflowFormVoiceInputSessionTest {
    @Test
    public void bindsOneTranscriptToTheCurrentFormFieldWithoutAdvancing() throws Exception {
        WorkflowFormVoiceInputSession session = WorkflowFormVoiceInputSession.from(
                "assignment-a", "execution-a", formNode(), "siteCode", 30);

        assertTrue(session.matches(
                "assignment-a", "execution-a", "form", "siteCode"));
        assertFalse(session.matches(
                "assignment-a", "execution-a", "form-other", "siteCode"));
        assertEquals("现场编号", session.fieldLabel());
        assertEquals(12, session.maximumDurationSeconds());
        assertEquals("A-01", session.complete("  A-01  "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownFormFieldsBeforeOpeningTheMicrophone() throws Exception {
        WorkflowFormVoiceInputSession.from(
                "assignment-a", "execution-a", formNode(), "unknown", 30);
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsDuplicateFinalTranscripts() throws Exception {
        WorkflowFormVoiceInputSession session = WorkflowFormVoiceInputSession.from(
                "assignment-a", "execution-a", formNode(), "siteCode", 30);
        session.complete("A-01");
        session.complete("A-02");
    }

    private static WorkflowPackage.Node formNode() throws Exception {
        JSONObject envelope = new JSONObject()
                .put("assignmentId", "11111111-1111-4111-8111-111111111111")
                .put("workflowVersionId", "33333333-3333-4333-8333-333333333333")
                .put("signatureKeyId", "test-key")
                .put("minAppVersionCode", 9000);
        JSONObject executionPackage = new JSONObject()
                .put("workflowId", "22222222-2222-4222-8222-222222222222")
                .put("schemaVersion", 1)
                .put("title", "动态表单")
                .put("contentSha256", repeat('a', 64))
                .put("requiredCapabilities", new JSONArray().put("workflow.runtime.v1"))
                .put("nodes", new JSONArray()
                        .put(node("start", "start", new JSONObject()))
                        .put(node("form", "form", new JSONObject()
                                .put("fields", new JSONArray().put(new JSONObject()
                                        .put("key", "siteCode")
                                        .put("label", "现场编号")
                                        .put("type", "text")
                                        .put("required", true)
                                        .put("maxDurationSeconds", 12)))))
                        .put(node("complete", "complete", new JSONObject())))
                .put("transitions", new JSONArray()
                        .put(transition("start-form", "start", "form"))
                        .put(transition("form-complete", "form", "complete")));
        return WorkflowPackage.parseVerified(envelope, executionPackage).node("form");
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
