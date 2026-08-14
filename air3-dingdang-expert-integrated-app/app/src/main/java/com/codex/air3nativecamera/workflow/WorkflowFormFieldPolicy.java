package com.codex.air3nativecamera.workflow;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Validates server-defined form values before they enter the durable workflow snapshot. */
final class WorkflowFormFieldPolicy {
    private static final int MAX_VALUE_CHARS = 8_192;

    private WorkflowFormFieldPolicy() { }

    static String normalize(WorkflowPackage.Node node, String fieldKey, String rawValue) {
        JSONObject field = field(node, fieldKey);
        if (field == null) throw new IllegalArgumentException("workflow_form_field_unknown");
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty() || value.length() > MAX_VALUE_CHARS) {
            throw new IllegalArgumentException("workflow_form_value_invalid");
        }
        String type = field.optString("type", "text").trim().toLowerCase();
        if (type.isEmpty() || "text".equals(type) || "textarea".equals(type)
                || "string".equals(type)) {
            return value;
        }
        if ("number".equals(type) || "decimal".equals(type) || "integer".equals(type)) {
            double parsed;
            try {
                parsed = Double.parseDouble(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("workflow_form_number_invalid", exception);
            }
            if ("integer".equals(type) && parsed != Math.rint(parsed)) {
                throw new IllegalArgumentException("workflow_form_integer_invalid");
            }
            if (field.has("min") && parsed < field.optDouble("min")) {
                throw new IllegalArgumentException("workflow_form_number_below_min");
            }
            if (field.has("max") && parsed > field.optDouble("max")) {
                throw new IllegalArgumentException("workflow_form_number_above_max");
            }
            return value;
        }
        if ("boolean".equals(type) || "bool".equals(type)) {
            if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value)
                    || "是".equals(value) || "正常".equals(value)) return "true";
            if ("false".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value)
                    || "否".equals(value) || "异常".equals(value)) return "false";
            throw new IllegalArgumentException("workflow_form_boolean_invalid");
        }
        if ("radio".equals(type) || "single_choice".equals(type)
                || "select".equals(type) || "choice".equals(type)) {
            JSONArray options = field.optJSONArray("options");
            for (int index = 0; options != null && index < options.length(); index += 1) {
                JSONObject option = options.optJSONObject(index);
                if (option == null) continue;
                String optionValue = option.optString("value", "").trim();
                String optionLabel = option.optString("label", "").trim();
                if (value.equals(optionValue)) return optionValue;
                if (!optionValue.isEmpty() && value.equals(optionLabel)) return optionValue;
            }
            throw new IllegalArgumentException("workflow_form_choice_invalid");
        }
        throw new IllegalArgumentException("workflow_form_type_unsupported");
    }

    static JSONObject fieldsFor(WorkflowPackage.Node node, JSONObject variables) {
        JSONObject result = new JSONObject();
        JSONArray fields = node == null ? null : node.config().optJSONArray("fields");
        for (int index = 0; fields != null && index < fields.length(); index += 1) {
            JSONObject field = fields.optJSONObject(index);
            if (field == null) continue;
            String key = fieldKey(field);
            if (key.isEmpty() || variables == null || !variables.has(key)) continue;
            Object value = variables.opt(key);
            if (value == null || value == JSONObject.NULL) continue;
            try {
                result.put(key, value);
            } catch (JSONException exception) {
                throw new IllegalArgumentException("workflow_form_fields_invalid", exception);
            }
        }
        return result;
    }

    static WorkflowStepContext contextFor(WorkflowPackage.Node node, JSONObject variables) {
        WorkflowStepContext context = new WorkflowStepContext();
        JSONObject fields = fieldsFor(node, variables);
        java.util.Iterator<String> keys = fields.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            context.putField(key, fields.opt(key));
        }
        return context;
    }

    static JSONObject inputDataFor(WorkflowPackage.Node node, JSONObject variables) {
        try {
            return new JSONObject()
                    .put("nodeId", node.nodeId())
                    .put("fields", fieldsFor(node, variables));
        } catch (JSONException exception) {
            throw new IllegalArgumentException("workflow_form_input_invalid", exception);
        }
    }

    static JSONObject outputDataFor(WorkflowPackage.Node node, JSONObject variables) {
        try {
            return new JSONObject()
                    .put("nodeId", node.nodeId())
                    .put("fields", fieldsFor(node, variables));
        } catch (JSONException exception) {
            throw new IllegalArgumentException("workflow_form_output_invalid", exception);
        }
    }

    static String displayValue(JSONObject variables, String fieldKey) {
        if (variables == null || fieldKey == null || !variables.has(fieldKey)) return "";
        String value = variables.optString(fieldKey, "").trim();
        if (value.length() > 120) return value.substring(0, 120) + "...";
        return value;
    }

    private static JSONObject field(WorkflowPackage.Node node, String fieldKey) {
        if (node == null || !"form".equals(node.type())) return null;
        String requested = fieldKey == null ? "" : fieldKey.trim();
        JSONArray fields = node.config().optJSONArray("fields");
        for (int index = 0; fields != null && index < fields.length(); index += 1) {
            JSONObject field = fields.optJSONObject(index);
            if (field != null && requested.equals(fieldKey(field))) return field;
        }
        return null;
    }

    private static String fieldKey(JSONObject field) {
        String key = field.optString("key", "").trim();
        return key.isEmpty() ? field.optString("fieldKey", "").trim() : key;
    }
}
