package com.codex.air3nativecamera.workflow;

import org.json.JSONException;
import org.json.JSONObject;

/** Keeps one ASR result bound to the workflow node that requested it. */
public final class WorkflowVoiceInputSession {
    private static final int MAX_TRANSCRIPT_CHARS = 8_192;

    public static final class Completion {
        private final WorkflowStepContext context;
        private final JSONObject inputData;
        private final JSONObject outputData;

        private Completion(
                WorkflowStepContext context,
                JSONObject inputData,
                JSONObject outputData
        ) {
            this.context = context;
            this.inputData = copy(inputData);
            this.outputData = copy(outputData);
        }

        public WorkflowStepContext context() { return context; }
        public JSONObject inputData() { return copy(inputData); }
        public JSONObject outputData() { return copy(outputData); }
    }

    private final String assignmentId;
    private final String executionId;
    private final int attemptNumber;
    private final WorkflowVoiceInputPlan plan;
    private boolean completed;

    private WorkflowVoiceInputSession(
            String assignmentId,
            String executionId,
            int attemptNumber,
            WorkflowVoiceInputPlan plan
    ) {
        this.assignmentId = assignmentId;
        this.executionId = executionId;
        this.attemptNumber = attemptNumber;
        this.plan = plan;
    }

    public static WorkflowVoiceInputSession from(
            WorkflowCapabilityRegistry.Request request,
            int deviceMaximumDurationSeconds
    ) {
        if (request == null || request.node() == null) {
            throw new IllegalArgumentException("workflow voice request is invalid");
        }
        return new WorkflowVoiceInputSession(
                request.assignmentId(),
                request.executionId(),
                request.attemptNumber(),
                WorkflowVoiceInputPlan.from(request.node(), deviceMaximumDurationSeconds));
    }

    public WorkflowVoiceInputPlan plan() { return plan; }

    public boolean matches(String assignmentId, String executionId, String nodeId) {
        return this.assignmentId.equals(clean(assignmentId))
                && this.executionId.equals(clean(executionId))
                && plan.nodeId().equals(clean(nodeId));
    }

    public Completion complete(String transcript) {
        if (completed) {
            throw new IllegalStateException("workflow voice session is already complete");
        }
        String value = clean(transcript);
        if (value.isEmpty() || value.length() > MAX_TRANSCRIPT_CHARS) {
            throw new IllegalArgumentException("workflow voice transcript is invalid");
        }
        try {
            JSONObject input = new JSONObject()
                    .put("nodeId", plan.nodeId())
                    .put("fieldKey", plan.fieldKey())
                    .put("attemptNumber", attemptNumber);
            JSONObject output = new JSONObject()
                    .put("nodeId", plan.nodeId())
                    .put("fieldKey", plan.fieldKey())
                    .put("transcript", value);
            WorkflowStepContext context =
                    new WorkflowStepContext().putField(plan.fieldKey(), value);
            completed = true;
            return new Completion(context, input, output);
        } catch (JSONException exception) {
            throw new IllegalArgumentException("workflow voice transcript is invalid", exception);
        }
    }

    private static JSONObject copy(JSONObject value) {
        try {
            return value == null ? new JSONObject() : new JSONObject(value.toString());
        } catch (JSONException exception) {
            throw new IllegalStateException("workflow voice data is invalid", exception);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
