package com.codex.air3nativecamera.features.inspection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InspectionRun {
    public enum Outcome { UNCONFIRMED, NORMAL, ABNORMAL }

    public static final class PointRecord {
        private String photoReference = "";
        private String aiObservation = "";
        private String previousValue = "";
        private String currentValue = "";
        private Outcome outcome = Outcome.UNCONFIRMED;
        private String operatorNote = "";
        private boolean completed;

        public String photoReference() { return photoReference; }
        public String aiObservation() { return aiObservation; }
        public String previousValue() { return previousValue; }
        public String currentValue() { return currentValue; }
        public Outcome outcome() { return outcome; }
        public String operatorNote() { return operatorNote; }
        public boolean completed() { return completed; }
    }

    private final InspectionTaskDefinition definition;
    private final List<PointRecord> records = new ArrayList<>();
    private int currentPointIndex;

    private InspectionRun(InspectionTaskDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("inspection definition is required");
        this.definition = definition;
        for (int i = 0; i < definition.points().size(); i++) records.add(new PointRecord());
    }

    public static InspectionRun start(InspectionTaskDefinition definition) { return new InspectionRun(definition); }
    public InspectionTaskDefinition definition() { return definition; }
    public List<PointRecord> records() { return Collections.unmodifiableList(records); }
    public int currentPointNumber() { return records.isEmpty() ? 0 : currentPointIndex + 1; }
    public int completedPointCount() {
        int count = 0;
        for (PointRecord record : records) if (record.completed) count++;
        return count;
    }
    public boolean isCompleted() {
        return !records.isEmpty() && completedPointCount() == records.size();
    }
    public InspectionTaskDefinition.Point currentPoint() {
        return definition.points().isEmpty() ? null : definition.points().get(currentPointIndex);
    }
    public PointRecord currentRecord() { return records.isEmpty() ? null : current(); }

    public void attachPhoto(String reference) { current().photoReference = text(reference); }
    public void recordAiObservation(String observation, String previousValue, String currentValue) {
        PointRecord record = current();
        record.aiObservation = text(observation);
        record.previousValue = text(previousValue);
        record.currentValue = text(currentValue);
    }
    public void confirmCurrentPoint(Outcome outcome, String note) {
        PointRecord record = current();
        record.outcome = outcome == null ? Outcome.UNCONFIRMED : outcome;
        record.operatorNote = text(note);
    }
    public boolean completeCurrentPoint() {
        PointRecord record = current();
        InspectionTaskDefinition.Point point = currentPoint();
        if ((point.photoRequired() && record.photoReference.length() == 0)
                || record.aiObservation.length() == 0 || record.outcome == Outcome.UNCONFIRMED) return false;
        record.completed = true;
        if (currentPointIndex + 1 < records.size()) currentPointIndex++;
        return true;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            for (PointRecord record : records) {
                array.put(new JSONObject()
                        .put("photo", record.photoReference)
                        .put("ai_observation", record.aiObservation)
                        .put("previous_value", record.previousValue)
                        .put("current_value", record.currentValue)
                        .put("outcome", record.outcome.name())
                        .put("operator_note", record.operatorNote)
                        .put("completed", record.completed));
            }
            json.put("definition_id", definition.id());
            json.put("current_point_index", currentPointIndex);
            json.put("records", array);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize inspection run", exception);
        }
        return json;
    }

    public static InspectionRun fromJson(JSONObject json, InspectionCatalog catalog) {
        InspectionTaskDefinition definition = catalog.find(json.optString("definition_id", ""));
        InspectionRun run = start(definition);
        JSONArray array = json.optJSONArray("records");
        for (int i = 0; array != null && i < array.length() && i < run.records.size(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            PointRecord record = run.records.get(i);
            record.photoReference = item.optString("photo", "");
            record.aiObservation = item.optString("ai_observation", "");
            record.previousValue = item.optString("previous_value", "");
            record.currentValue = item.optString("current_value", "");
            try { record.outcome = Outcome.valueOf(item.optString("outcome", "UNCONFIRMED")); }
            catch (IllegalArgumentException ignored) { record.outcome = Outcome.UNCONFIRMED; }
            record.operatorNote = item.optString("operator_note", "");
            record.completed = item.optBoolean("completed", false);
        }
        run.currentPointIndex = Math.max(0, Math.min(json.optInt("current_point_index", 0),
                Math.max(0, run.records.size() - 1)));
        return run;
    }

    private PointRecord current() {
        if (records.isEmpty()) throw new IllegalStateException("inspection has no points");
        return records.get(currentPointIndex);
    }
    private static String text(String value) { return value == null ? "" : value.trim(); }
}
