package com.codex.air3nativecamera.workflow;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A completed local step waiting for its evidence to receive remote asset identifiers. */
public final class WorkflowDeferredStep {
    private static final String IDENTIFIER_PATTERN = "^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$";

    private final String nodeId;
    private final int attemptNumber;
    private final JSONObject inputData;
    private final JSONObject outputData;
    private final List<String> localEvidenceIds;
    private final JSONObject transitionResult;
    private final String nextNodeId;
    private final JSONObject runtimeSnapshot;
    private final long createdAt;
    private final String idempotencyKey;

    public WorkflowDeferredStep(
            String nodeId,
            int attemptNumber,
            JSONObject inputData,
            JSONObject outputData,
            List<String> localEvidenceIds,
            JSONObject transitionResult,
            String nextNodeId,
            JSONObject runtimeSnapshot,
            long createdAt,
            String idempotencyKey
    ) {
        this.nodeId = clean(nodeId);
        this.attemptNumber = attemptNumber;
        this.inputData = copy(inputData, "workflow deferred input is invalid");
        this.outputData = copy(outputData, "workflow deferred output is invalid");
        this.localEvidenceIds = immutableEvidenceIds(localEvidenceIds);
        this.transitionResult = copy(
                transitionResult, "workflow deferred transition is invalid");
        this.nextNodeId = clean(nextNodeId);
        this.runtimeSnapshot = snapshot(runtimeSnapshot);
        this.createdAt = createdAt;
        this.idempotencyKey = clean(idempotencyKey);
        if (!this.nodeId.matches(IDENTIFIER_PATTERN)
                || attemptNumber < 1
                || attemptNumber > 1_000_000
                || (!this.nextNodeId.isEmpty()
                && !this.nextNodeId.matches(IDENTIFIER_PATTERN))
                || createdAt < 0L
                || this.idempotencyKey.isEmpty()
                || this.idempotencyKey.length() > 200
                || containsControl(this.idempotencyKey)) {
            throw new IllegalArgumentException("workflow deferred step is invalid");
        }
    }

    public String nodeId() {
        return nodeId;
    }

    public int attemptNumber() {
        return attemptNumber;
    }

    public JSONObject inputData() {
        return copy(inputData, "workflow deferred input is invalid");
    }

    public JSONObject outputData() {
        return copy(outputData, "workflow deferred output is invalid");
    }

    public List<String> localEvidenceIds() {
        return localEvidenceIds;
    }

    public JSONObject transitionResult() {
        return copy(transitionResult, "workflow deferred transition is invalid");
    }

    public String nextNodeId() {
        return nextNodeId;
    }

    public JSONObject runtimeSnapshot() {
        return copy(runtimeSnapshot, "workflow deferred runtime snapshot is invalid");
    }

    public long createdAt() {
        return createdAt;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    JSONObject toJson() {
        try {
            JSONArray evidenceIds = new JSONArray();
            for (String value : localEvidenceIds) evidenceIds.put(value);
            return new JSONObject()
                    .put("node_id", nodeId)
                    .put("attempt_number", attemptNumber)
                    .put("input_data", inputData())
                    .put("output_data", outputData())
                    .put("local_evidence_ids", evidenceIds)
                    .put("transition_result", transitionResult())
                    .put("next_node_id", nextNodeId.isEmpty() ? JSONObject.NULL : nextNodeId)
                    .put("runtime_snapshot", runtimeSnapshot())
                    .put("created_at", createdAt)
                    .put("idempotency_key", idempotencyKey);
        } catch (JSONException exception) {
            throw new IllegalStateException("unable to serialize workflow deferred step", exception);
        }
    }

    static WorkflowDeferredStep fromJson(JSONObject value) {
        if (value == null
                || value.optJSONObject("input_data") == null
                || value.optJSONObject("output_data") == null
                || value.optJSONArray("local_evidence_ids") == null
                || value.optJSONObject("transition_result") == null
                || value.optJSONObject("runtime_snapshot") == null) {
            throw new IllegalArgumentException("workflow deferred snapshot shape is invalid");
        }
        JSONArray values = value.optJSONArray("local_evidence_ids");
        List<String> evidenceIds = new ArrayList<>();
        for (int index = 0; index < values.length(); index += 1) {
            Object item = values.opt(index);
            if (!(item instanceof String)) {
                throw new IllegalArgumentException("workflow deferred evidence is invalid");
            }
            evidenceIds.add((String) item);
        }
        return new WorkflowDeferredStep(
                value.optString("node_id", ""),
                value.optInt("attempt_number", -1),
                value.optJSONObject("input_data"),
                value.optJSONObject("output_data"),
                evidenceIds,
                value.optJSONObject("transition_result"),
                nullableText(value, "next_node_id"),
                value.optJSONObject("runtime_snapshot"),
                value.optLong("created_at", -1L),
                value.optString("idempotency_key", ""));
    }

    boolean sameAs(WorkflowDeferredStep other) {
        return other != null && toJson().toString().equals(other.toJson().toString());
    }

    private static List<String> immutableEvidenceIds(List<String> values) {
        if (values == null || values.size() > 2000) {
            throw new IllegalArgumentException("workflow deferred evidence is invalid");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            String accepted = clean(value);
            if (!accepted.matches(IDENTIFIER_PATTERN) || !unique.add(accepted)) {
                throw new IllegalArgumentException("workflow deferred evidence is invalid");
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }

    private static JSONObject snapshot(JSONObject value) {
        JSONObject accepted = copy(value, "workflow deferred runtime snapshot is invalid");
        if (accepted.length() == 0) {
            throw new IllegalArgumentException("workflow deferred runtime snapshot is invalid");
        }
        accepted.remove("deferred_steps");
        return accepted;
    }

    private static JSONObject copy(JSONObject value, String message) {
        if (value == null) throw new IllegalArgumentException(message);
        try {
            return new JSONObject(value.toString());
        } catch (JSONException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    private static String nullableText(JSONObject value, String key) {
        Object raw = value.opt(key);
        return raw == null || raw == JSONObject.NULL ? "" : clean(String.valueOf(raw));
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index += 1) {
            if (Character.isISOControl(value.charAt(index))) return true;
        }
        return false;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
