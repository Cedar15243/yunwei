package com.codex.air3nativecamera.workflow;

import org.json.JSONArray;
import org.json.JSONObject;

final class WorkflowEvidenceGate {
    WorkflowGateResult evaluate(
            WorkflowPackage.Node node,
            WorkflowStepContext context,
            JSONObject variables
    ) {
        JSONObject config = node.config();
        String type = node.type();
        if ("photo_capture".equals(type)) {
            String key = text(config, "evidenceKey", node.nodeId());
            int minimum = positive(config.optInt("minCount", 1), 1);
            if (context.evidenceCount(key, WorkflowStepContext.EvidenceType.PHOTO, 0) < minimum) {
                return WorkflowGateResult.block("evidence_count_required");
            }
            if (config.optBoolean("confirmationRequired", false) && !context.confirmed()) {
                return WorkflowGateResult.block("confirmation_required");
            }
            return WorkflowGateResult.allow();
        }
        if ("video_capture".equals(type)) {
            String key = text(config, "evidenceKey", node.nodeId());
            int minimum = positive(config.optInt("minCount", 1), 1);
            int minimumDuration = positive(config.optInt("minDurationSeconds", 0), 0);
            if (context.evidenceCount(key, WorkflowStepContext.EvidenceType.VIDEO, minimumDuration) < minimum) {
                return WorkflowGateResult.block("evidence_count_required");
            }
            return WorkflowGateResult.allow();
        }
        if ("choice".equals(type)) {
            return requiredField(variables, text(config, "fieldKey", ""));
        }
        if ("form".equals(type)) {
            JSONArray fields = config.optJSONArray("fields");
            for (int index = 0; fields != null && index < fields.length(); index += 1) {
                JSONObject field = fields.optJSONObject(index);
                if (field != null && field.optBoolean("required", false)) {
                    WorkflowGateResult result = requiredField(variables, text(field, "key", ""));
                    if (!result.allowed()) return result;
                }
            }
            return WorkflowGateResult.allow();
        }
        if ("voice_input".equals(type) && config.optBoolean("required", false)) {
            return requiredField(variables, text(config, "fieldKey", ""));
        }
        if ("confirmation".equals(type)) {
            String phrase = text(config, "requiredPhrase", "");
            if (!context.confirmed() || (!phrase.isEmpty() && !phrase.equals(context.confirmationPhrase()))) {
                return WorkflowGateResult.block("confirmation_required");
            }
            return WorkflowGateResult.allow();
        }
        if ("ai_assist".equals(type) || "expert_call".equals(type) || "connector_action".equals(type)) {
            if (!context.online()) return WorkflowGateResult.waitForNetwork();
            if ("connector_action".equals(type) && !context.confirmed()) {
                return WorkflowGateResult.block("confirmation_required");
            }
            return context.serverAcknowledged()
                    ? WorkflowGateResult.allow()
                    : WorkflowGateResult.block("server_result_required");
        }
        if ("complete".equals(type)) {
            if (config.optBoolean("requireSync", false) && !context.synchronizedState()) {
                return WorkflowGateResult.block("sync_required");
            }
            if (config.optBoolean("requireConfirmation", false) && !context.confirmed()) {
                return WorkflowGateResult.block("confirmation_required");
            }
        }
        return WorkflowGateResult.allow();
    }

    private WorkflowGateResult requiredField(JSONObject variables, String key) {
        Object value = key.isEmpty() ? null : variables.opt(key);
        if (value == null || value == JSONObject.NULL || (value instanceof String && ((String) value).trim().isEmpty())) {
            return WorkflowGateResult.block("field_required");
        }
        return WorkflowGateResult.allow();
    }

    private int positive(int value, int fallback) {
        return value >= 0 ? value : fallback;
    }

    private String text(JSONObject value, String key, String fallback) {
        String text = value.optString(key, fallback);
        return text == null || text.trim().isEmpty() ? fallback : text.trim();
    }
}
