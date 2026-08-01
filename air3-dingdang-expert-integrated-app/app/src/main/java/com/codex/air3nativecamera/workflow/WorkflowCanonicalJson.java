package com.codex.air3nativecamera.workflow;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

final class WorkflowCanonicalJson {
    private WorkflowCanonicalJson() {
    }

    static String unsignedExecutionPackage(JSONObject executionPackage) {
        try {
            JSONObject unsigned = new JSONObject(executionPackage.toString());
            unsigned.remove("contentSha256");
            return encode(unsigned);
        } catch (JSONException exception) {
            throw new IllegalArgumentException("invalid_execution_package", exception);
        }
    }

    private static String encode(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) return encodeObject((JSONObject) value);
        if (value instanceof JSONArray) return encodeArray((JSONArray) value);
        if (value instanceof String) return JSONObject.quote((String) value);
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) {
            try {
                return JSONObject.numberToString((Number) value);
            } catch (JSONException exception) {
                throw new IllegalArgumentException("invalid_json_number", exception);
            }
        }
        throw new IllegalArgumentException("unsupported_json_value");
    }

    private static String encodeObject(JSONObject value) {
        List<String> keys = new ArrayList<>();
        Iterator<String> iterator = value.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        Collections.sort(keys);
        StringBuilder output = new StringBuilder("{");
        for (int index = 0; index < keys.size(); index += 1) {
            if (index > 0) output.append(',');
            String key = keys.get(index);
            output.append(JSONObject.quote(key)).append(':').append(encode(value.opt(key)));
        }
        return output.append('}').toString();
    }

    private static String encodeArray(JSONArray value) {
        StringBuilder output = new StringBuilder("[");
        for (int index = 0; index < value.length(); index += 1) {
            if (index > 0) output.append(',');
            output.append(encode(value.opt(index)));
        }
        return output.append(']').toString();
    }
}
