package com.codex.air3nativecamera.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/** Builds the bounded snapshot emitted only after the V9 gateway accepts task completion. */
public final class TaskCompletionSyncPayload {
    private static final int MAX_SUMMARY_LENGTH = 8000;
    private static final int MAX_ITEMS = 100;
    private static final int MAX_ITEM_LENGTH = 1000;

    private TaskCompletionSyncPayload() {
    }

    public static JSONObject create(
            String taskStatus,
            String summary,
            long projectMemoryRevision,
            List<String> confirmedFacts,
            List<String> excludedFacts,
            List<String> risks
    ) {
        String acceptedStatus = text(taskStatus);
        if (!("completed".equals(acceptedStatus) || "closed".equals(acceptedStatus))) {
            throw invalid("status");
        }
        String acceptedSummary = boundedText(summary, MAX_SUMMARY_LENGTH, "summary");
        if (projectMemoryRevision < 0L) throw invalid("revision");
        try {
            return new JSONObject()
                    .put("humanConfirmed", true)
                    .put("taskStatus", acceptedStatus)
                    .put("phase", "COMPLETED")
                    .put("projectMemoryRevision", projectMemoryRevision)
                    .put("summary", acceptedSummary)
                    .put("confirmedFacts", stringArray(confirmedFacts, "confirmed_facts"))
                    .put("excludedFacts", stringArray(excludedFacts, "excluded_facts"))
                    .put("risks", stringArray(risks, "risks"));
        } catch (JSONException exception) {
            throw new IllegalStateException("task completion payload serialization failed", exception);
        }
    }

    private static JSONArray stringArray(List<String> values, String field) {
        List<String> source = values == null ? Collections.<String>emptyList() : values;
        if (source.size() > MAX_ITEMS) throw invalid(field);
        JSONArray result = new JSONArray();
        for (String value : source) {
            result.put(boundedText(value, MAX_ITEM_LENGTH, field));
        }
        return result;
    }

    private static String boundedText(String value, int maximum, String field) {
        String accepted = text(value);
        if (accepted.isEmpty() || accepted.length() > maximum || containsControlCharacter(accepted)) {
            throw invalid(field);
        }
        return accepted;
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 32 && character != '\n') return true;
        }
        return false;
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("task_completion_" + field + "_invalid");
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
