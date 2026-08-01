package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class WorkflowStateMachineTest {
    @Test
    public void enforcesEvidenceBranchingAndCompletionGatesWithoutSkippingNodes() throws Exception {
        WorkflowPackage workflowPackage = WorkflowPackage.parseVerified(envelope(), executionPackage());
        WorkflowStateMachine machine = new WorkflowStateMachine(workflowPackage);
        WorkflowRuntimeState state = machine.start();

        assertEquals(WorkflowRuntimeState.Status.ACTIVE, state.status());
        assertEquals("photo", state.currentNodeId());

        WorkflowAdvanceResult missingEvidence = machine.advance(state, new WorkflowStepContext());
        assertFalse(missingEvidence.advanced());
        assertEquals("evidence_count_required", missingEvidence.code());
        assertEquals("photo", missingEvidence.state().currentNodeId());

        WorkflowStepContext onePhoto = new WorkflowStepContext()
                .addEvidence("device_photo", WorkflowStepContext.EvidenceType.PHOTO, 0)
                .setConfirmed(true);
        WorkflowAdvanceResult stillMissing = machine.advance(missingEvidence.state(), onePhoto);
        assertFalse(stillMissing.advanced());
        assertEquals("evidence_count_required", stillMissing.code());

        WorkflowStepContext twoPhotos = new WorkflowStepContext()
                .addEvidence("device_photo", WorkflowStepContext.EvidenceType.PHOTO, 0)
                .addEvidence("device_photo", WorkflowStepContext.EvidenceType.PHOTO, 0)
                .setConfirmed(true);
        WorkflowAdvanceResult atChoice = machine.advance(stillMissing.state(), twoPhotos);
        assertTrue(atChoice.advanced());
        assertEquals("result", atChoice.state().currentNodeId());

        WorkflowAdvanceResult missingChoice = machine.advance(atChoice.state(), new WorkflowStepContext());
        assertFalse(missingChoice.advanced());
        assertEquals("field_required", missingChoice.code());

        WorkflowAdvanceResult abnormalPath = machine.advance(
                missingChoice.state(),
                new WorkflowStepContext().putField("inspection_result", "abnormal"));
        assertTrue(abnormalPath.advanced());
        assertEquals("abnormal_instruction", abnormalPath.state().currentNodeId());

        WorkflowAdvanceResult atCompletion = machine.advance(
                abnormalPath.state(),
                new WorkflowStepContext());
        assertEquals("complete", atCompletion.state().currentNodeId());

        WorkflowAdvanceResult unsynchronized = machine.advance(
                atCompletion.state(),
                new WorkflowStepContext().setConfirmed(true));
        assertFalse(unsynchronized.advanced());
        assertEquals("sync_required", unsynchronized.code());

        WorkflowAdvanceResult completed = machine.advance(
                unsynchronized.state(),
                new WorkflowStepContext().setConfirmed(true).setSynchronized(true));
        assertTrue(completed.advanced());
        assertEquals(WorkflowRuntimeState.Status.COMPLETED, completed.state().status());
        assertEquals("abnormal", completed.state().variables().getString("inspection_result"));
    }

    @Test
    public void entersAnExplicitNetworkWaitForServerNodesAndRoundTripsTheSnapshot() throws Exception {
        WorkflowPackage workflowPackage = WorkflowPackage.parseVerified(networkEnvelope(), networkPackage());
        WorkflowStateMachine machine = new WorkflowStateMachine(workflowPackage);
        WorkflowRuntimeState state = machine.start();

        assertEquals("ai", state.currentNodeId());
        WorkflowAdvanceResult offline = machine.advance(state, new WorkflowStepContext().setOnline(false));
        assertFalse(offline.advanced());
        assertEquals("network_required", offline.code());
        assertEquals(WorkflowRuntimeState.Status.WAITING_NETWORK, offline.state().status());

        WorkflowRuntimeState restored = WorkflowRuntimeState.fromJson(offline.state().toJson());
        assertEquals("ai", restored.currentNodeId());
        assertEquals(WorkflowRuntimeState.Status.WAITING_NETWORK, restored.status());

        WorkflowAdvanceResult online = machine.advance(
                restored,
                new WorkflowStepContext().setOnline(true).setServerAcknowledged(true));
        assertTrue(online.advanced());
        assertEquals("complete", online.state().currentNodeId());
        assertEquals(WorkflowRuntimeState.Status.ACTIVE, online.state().status());
    }

    private JSONObject envelope() throws Exception {
        return envelopeFor(executionPackage());
    }

    private JSONObject networkEnvelope() throws Exception {
        return envelopeFor(networkPackage());
    }

    private JSONObject envelopeFor(JSONObject executionPackage) throws Exception {
        return new JSONObject()
                .put("assignmentId", "11111111-1111-4111-8111-111111111111")
                .put("workflowVersionId", "33333333-3333-4333-8333-333333333333")
                .put("signatureKeyId", "test-key")
                .put("minAppVersionCode", 9000);
    }

    private JSONObject executionPackage() throws Exception {
        JSONArray nodes = new JSONArray()
                .put(node("start", "start", new JSONObject()))
                .put(node("photo", "photo_capture", new JSONObject()
                        .put("evidenceKey", "device_photo")
                        .put("minCount", 2)
                        .put("confirmationRequired", true)))
                .put(node("result", "choice", new JSONObject()
                        .put("fieldKey", "inspection_result")
                        .put("options", new JSONArray()
                                .put(new JSONObject().put("value", "normal").put("label", "正常"))
                                .put(new JSONObject().put("value", "abnormal").put("label", "异常")))))
                .put(node("abnormal_instruction", "instruction", new JSONObject()))
                .put(node("complete", "complete", new JSONObject()
                        .put("requireSync", true)
                        .put("requireConfirmation", true)));
        JSONArray transitions = new JSONArray()
                .put(transition("start-photo", "start", "photo", null))
                .put(transition("photo-result", "photo", "result", null))
                .put(transition("result-normal", "result", "complete", condition("eq", "inspection_result", "normal")))
                .put(transition("result-abnormal", "result", "abnormal_instruction", condition("eq", "inspection_result", "abnormal")))
                .put(transition("abnormal-complete", "abnormal_instruction", "complete", null));
        return packageWith(nodes, transitions, new JSONArray().put("camera.photo").put("workflow.runtime.v1"));
    }

    private JSONObject networkPackage() throws Exception {
        JSONArray nodes = new JSONArray()
                .put(node("start", "start", new JSONObject()))
                .put(node("ai", "ai_assist", new JSONObject().put("skillVersionId", "skill-v1")))
                .put(node("complete", "complete", new JSONObject()));
        JSONArray transitions = new JSONArray()
                .put(transition("start-ai", "start", "ai", null))
                .put(transition("ai-complete", "ai", "complete", null));
        return packageWith(nodes, transitions, new JSONArray().put("ai.execution_context").put("workflow.runtime.v1"));
    }

    private JSONObject packageWith(JSONArray nodes, JSONArray transitions, JSONArray capabilities) throws Exception {
        return new JSONObject()
                .put("workflowId", "22222222-2222-4222-8222-222222222222")
                .put("schemaVersion", 1)
                .put("title", "受控现场流程")
                .put("nodes", nodes)
                .put("transitions", transitions)
                .put("requiredCapabilities", capabilities)
                .put("contentSha256", new String(new char[64]).replace('\0', 'a'));
    }

    private JSONObject node(String id, String type, JSONObject config) throws Exception {
        return new JSONObject().put("nodeId", id).put("type", type).put("config", config);
    }

    private JSONObject transition(String id, String from, String to, JSONObject condition) throws Exception {
        JSONObject value = new JSONObject()
                .put("transitionId", id)
                .put("fromNodeId", from)
                .put("toNodeId", to);
        if (condition != null) value.put("condition", condition);
        return value;
    }

    private JSONObject condition(String operator, String field, Object value) throws Exception {
        return new JSONObject().put("operator", operator).put("field", field).put("value", value);
    }
}
