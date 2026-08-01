package com.codex.air3nativecamera.workflow;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class WorkflowConditionEvaluatorTest {
    @Test
    public void rejectsCrossTypeOrderingAndEvaluatesNestedBooleanConditions() throws Exception {
        WorkflowConditionEvaluator evaluator = new WorkflowConditionEvaluator();
        JSONObject variables = new JSONObject()
                .put("temperature", 27.5)
                .put("status", "alarm")
                .put("device", new JSONObject().put("online", true));

        assertFalse(evaluator.evaluate(leaf("lt", "status", 10), variables));
        assertFalse(evaluator.evaluate(leaf("gte", "missing", 1), variables));

        JSONObject nested = new JSONObject()
                .put("operator", "all")
                .put("conditions", new JSONArray()
                        .put(leaf("gt", "temperature", 25))
                        .put(new JSONObject()
                                .put("operator", "not")
                                .put("condition", leaf("eq", "status", "normal")))
                        .put(new JSONObject()
                                .put("operator", "exists")
                                .put("field", "device.online")));
        assertTrue(evaluator.evaluate(nested, variables));
    }

    private JSONObject leaf(String operator, String field, Object value) throws Exception {
        return new JSONObject()
                .put("operator", operator)
                .put("field", field)
                .put("value", value);
    }
}
