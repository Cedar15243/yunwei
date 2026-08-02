package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class WorkflowVoiceInputSessionTest {
    @Test
    public void convertsTheFinalTranscriptIntoOnlyTheCurrentWorkflowField() throws Exception {
        WorkflowCapabilityRegistry.Request request = request(voiceNode());
        WorkflowVoiceInputSession session = WorkflowVoiceInputSession.from(request, 30);

        assertTrue(session.matches(
                "11111111-1111-4111-8111-111111111111",
                "execution-1",
                "voice"));
        assertFalse(session.matches(
                "11111111-1111-4111-8111-111111111111",
                "execution-2",
                "voice"));

        WorkflowVoiceInputSession.Completion completion =
                session.complete("  设备已经断电并挂牌  ");

        assertEquals("设备已经断电并挂牌",
                completion.context().fields().getString("safety_note"));
        assertEquals("safety_note", completion.outputData().getString("fieldKey"));
        assertEquals("设备已经断电并挂牌",
                completion.outputData().getString("transcript"));
        assertEquals("voice", completion.outputData().getString("nodeId"));
        assertEquals(1, completion.inputData().getInt("attemptNumber"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankFinalTranscriptsWithoutCompletingTheStep() throws Exception {
        WorkflowVoiceInputSession.from(request(voiceNode()), 30).complete("   ");
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsDuplicateFinalTranscripts() throws Exception {
        WorkflowVoiceInputSession session = WorkflowVoiceInputSession.from(request(voiceNode()), 30);
        session.complete("第一次结果");
        session.complete("第二次结果");
    }

    private static WorkflowCapabilityRegistry.Request request(
            WorkflowPackage.Node node
    ) throws Exception {
        java.util.Map<String, WorkflowCapabilityRegistry.Handler> handlers =
                new java.util.HashMap<>();
        final WorkflowCapabilityRegistry.Request[] bound = new WorkflowCapabilityRegistry.Request[1];
        handlers.put("audio.voice_input", (request, callback) -> bound[0] = request);
        WorkflowCapabilityRegistry registry = new WorkflowCapabilityRegistry(handlers);
        WorkflowCapabilityRegistry.Request request = new WorkflowCapabilityRegistry.Request(
                "11111111-1111-4111-8111-111111111111",
                "execution-1",
                1,
                new JSONObject());
        registry.dispatch(node, request, result -> { });
        return bound[0];
    }

    private static WorkflowPackage.Node voiceNode() throws Exception {
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
                        .put(node("voice", "voice_input", new JSONObject()
                                .put("fieldKey", "safety_note")
                                .put("maxDurationSeconds", 30)
                                .put("required", true)))
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
