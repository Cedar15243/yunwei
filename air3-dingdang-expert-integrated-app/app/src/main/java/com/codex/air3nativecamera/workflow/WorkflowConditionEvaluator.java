package com.codex.air3nativecamera.workflow;

import org.json.JSONArray;
import org.json.JSONObject;

final class WorkflowConditionEvaluator {
    boolean evaluate(JSONObject condition, JSONObject variables) {
        if (condition == null) return true;
        String operator = condition.optString("operator", "");
        if ("all".equals(operator) || "any".equals(operator)) {
            JSONArray children = condition.optJSONArray("conditions");
            if (children == null || children.length() == 0) return false;
            boolean result = "all".equals(operator);
            for (int index = 0; index < children.length(); index += 1) {
                JSONObject child = children.optJSONObject(index);
                boolean childResult = child != null && evaluate(child, variables);
                if ("all".equals(operator) && !childResult) return false;
                if ("any".equals(operator) && childResult) return true;
            }
            return result;
        }
        if ("not".equals(operator)) {
            JSONObject child = condition.optJSONObject("condition");
            return child != null && !evaluate(child, variables);
        }
        Object actual = resolve(variables, condition.optString("field", ""));
        if ("exists".equals(operator)) return actual != null && actual != JSONObject.NULL;
        Object expected = condition.opt("value");
        if ("eq".equals(operator)) return equal(actual, expected);
        if ("neq".equals(operator)) return !equal(actual, expected);
        if ("in".equals(operator) && expected instanceof JSONArray) {
            return contains((JSONArray) expected, actual);
        }
        if ("contains".equals(operator)) {
            if (actual instanceof JSONArray) return contains((JSONArray) actual, expected);
            return actual instanceof String && expected instanceof String
                    && ((String) actual).contains((String) expected);
        }
        Integer comparison = compare(actual, expected);
        if (comparison == null) return false;
        if ("gt".equals(operator)) return comparison > 0;
        if ("gte".equals(operator)) return comparison >= 0;
        if ("lt".equals(operator)) return comparison < 0;
        if ("lte".equals(operator)) return comparison <= 0;
        return false;
    }

    private Object resolve(JSONObject variables, String path) {
        if (path == null || path.trim().isEmpty()) return null;
        Object current = variables;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof JSONObject)) return null;
            current = ((JSONObject) current).opt(segment);
            if (current == null || current == JSONObject.NULL) return current;
        }
        return current;
    }

    private boolean contains(JSONArray values, Object expected) {
        for (int index = 0; index < values.length(); index += 1) {
            if (equal(values.opt(index), expected)) return true;
        }
        return false;
    }

    private boolean equal(Object left, Object right) {
        if (left == null || left == JSONObject.NULL) return right == null || right == JSONObject.NULL;
        if (right == null || right == JSONObject.NULL) return false;
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue()) == 0;
        }
        return left.equals(right);
    }

    private Integer compare(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(((Number) left).doubleValue(), ((Number) right).doubleValue());
        }
        if (left instanceof String && right instanceof String) {
            return ((String) left).compareTo((String) right);
        }
        return null;
    }
}
