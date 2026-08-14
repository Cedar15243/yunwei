package com.codex.air3nativecamera.workflow;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Persists locally validated MVS node-form drafts until the provider write contract is approved. */
public final class MvsWorkOrderFormDraftStore {
    private static final int VERSION = 1;
    private static final int MAX_DRAFTS = 100;
    private static final int MAX_VALUES_BYTES = 16 * 1024;

    interface Clock {
        long now();
    }

    private static final class Draft {
        private final String orderId;
        private final int formId;
        private final String nodeCode;
        private final String schemaFingerprint;
        private final JSONObject values;
        private final long updatedAt;

        private Draft(
                String orderId,
                int formId,
                String nodeCode,
                String schemaFingerprint,
                JSONObject values,
                long updatedAt
        ) {
            this.orderId = orderId;
            this.formId = formId;
            this.nodeCode = nodeCode;
            this.schemaFingerprint = schemaFingerprint;
            this.values = copy(values);
            this.updatedAt = updatedAt;
        }

        private boolean matches(MvsWorkOrderNodeForm form) {
            return orderId.equals(form.orderId()) && formId == form.formId()
                    && nodeCode.equals(form.nodeCode())
                    && schemaFingerprint.equals(form.schemaFingerprint());
        }

        private JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("orderId", orderId)
                    .put("formId", formId)
                    .put("nodeCode", nodeCode)
                    .put("schemaFingerprint", schemaFingerprint)
                    .put("values", copy(values))
                    .put("updatedAt", updatedAt);
        }
    }

    private final WorkflowSnapshotStorage storage;
    private final Clock clock;
    private final List<Draft> drafts = new ArrayList<>();

    public MvsWorkOrderFormDraftStore(WorkflowSnapshotStorage storage) throws IOException {
        this(storage, System::currentTimeMillis);
    }

    MvsWorkOrderFormDraftStore(WorkflowSnapshotStorage storage, Clock clock) throws IOException {
        if (storage == null || clock == null) {
            throw new IllegalArgumentException("mvs form draft storage is invalid");
        }
        this.storage = storage;
        this.clock = clock;
        restore();
    }

    public synchronized void saveField(
            MvsWorkOrderNodeForm form,
            String fieldKey,
            Object value
    ) throws IOException {
        if (form == null) throw new IllegalArgumentException("mvs node form is required");
        MvsWorkOrderNodeForm.Field field = form.field(fieldKey);
        if (field == null || !field.editable()) {
            throw new IllegalArgumentException("mvs form field is unsupported");
        }
        Object normalized = field.normalizeValue(value, form.orderId());
        JSONObject nextValues = valuesFor(form);
        try {
            nextValues.put(field.key(), normalized);
        } catch (JSONException exception) {
            throw new IOException("mvs form draft is invalid", exception);
        }
        requireBoundedValues(nextValues);
        int existing = indexOf(form);
        Draft next = new Draft(
                form.orderId(), form.formId(), form.nodeCode(), form.schemaFingerprint(),
                nextValues, Math.max(0L, clock.now()));
        Draft previous = existing >= 0 ? drafts.set(existing, next) : null;
        if (existing < 0) {
            if (drafts.size() >= MAX_DRAFTS) throw new IllegalStateException("mvs form draft limit reached");
            drafts.add(next);
        }
        try {
            persist();
        } catch (IOException exception) {
            if (existing >= 0) drafts.set(existing, previous);
            else drafts.remove(next);
            throw exception;
        }
    }

    public synchronized JSONObject valuesFor(MvsWorkOrderNodeForm form) {
        if (form == null) return new JSONObject();
        Draft draft = matchingDraft(form);
        if (draft == null) return new JSONObject();
        try {
            JSONObject result = new JSONObject();
            Iterator<String> keys = draft.values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                MvsWorkOrderNodeForm.Field field = form.field(key);
                if (field == null || !field.editable()) return new JSONObject();
                result.put(key, field.normalizeValue(draft.values.opt(key), form.orderId()));
            }
            return result;
        } catch (JSONException | IllegalArgumentException exception) {
            return new JSONObject();
        }
    }

    public synchronized boolean discard(MvsWorkOrderNodeForm form) throws IOException {
        int index = indexOf(form);
        if (index < 0) return false;
        Draft removed = drafts.remove(index);
        try {
            persist();
            return true;
        } catch (IOException exception) {
            drafts.add(index, removed);
            throw exception;
        }
    }

    private Draft matchingDraft(MvsWorkOrderNodeForm form) {
        int index = indexOf(form);
        return index < 0 ? null : drafts.get(index);
    }

    private int indexOf(MvsWorkOrderNodeForm form) {
        if (form == null) return -1;
        for (int index = 0; index < drafts.size(); index += 1) {
            if (drafts.get(index).matches(form)) return index;
        }
        return -1;
    }

    private void restore() throws IOException {
        byte[] bytes = storage.read();
        if (bytes == null || bytes.length == 0) return;
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (root.optInt("version", -1) != VERSION) throw invalidSnapshot();
            JSONArray values = root.optJSONArray("drafts");
            if (values == null || values.length() > MAX_DRAFTS) throw invalidSnapshot();
            for (int index = 0; index < values.length(); index += 1) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) throw invalidSnapshot();
                Draft draft = fromJson(value);
                for (Draft existing : drafts) {
                    if (sameDraftKey(existing, draft)) throw invalidSnapshot();
                }
                drafts.add(draft);
            }
        } catch (JSONException | IllegalArgumentException exception) {
            throw new IOException("mvs form draft snapshot invalid", exception);
        }
    }

    private void persist() throws IOException {
        try {
            JSONArray values = new JSONArray();
            for (Draft draft : drafts) values.put(draft.toJson());
            storage.writeAtomically(new JSONObject()
                    .put("version", VERSION)
                    .put("drafts", values)
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException exception) {
            throw new IOException("mvs form draft snapshot write failed", exception);
        }
    }

    private static Draft fromJson(JSONObject value) {
        String orderId = numericOrderId(value.optString("orderId", ""));
        int formId = positiveInteger(value.opt("formId"));
        String nodeCode = nodeCode(value.optString("nodeCode", ""));
        String fingerprint = value.optString("schemaFingerprint", "").trim();
        if (!fingerprint.matches("^[0-9a-f]{64}$")) throw invalidSnapshot();
        JSONObject values = value.optJSONObject("values");
        if (values == null) throw invalidSnapshot();
        requireBoundedValues(values);
        long updatedAt = value.optLong("updatedAt", -1L);
        if (updatedAt < 0L) throw invalidSnapshot();
        return new Draft(orderId, formId, nodeCode, fingerprint, values, updatedAt);
    }

    private static boolean sameDraftKey(Draft first, Draft second) {
        return first.orderId.equals(second.orderId) && first.formId == second.formId
                && first.nodeCode.equals(second.nodeCode)
                && first.schemaFingerprint.equals(second.schemaFingerprint);
    }

    private static void requireBoundedValues(JSONObject value) {
        if (value == null || value.length() > MAX_DRAFTS
                || value.toString().getBytes(StandardCharsets.UTF_8).length > MAX_VALUES_BYTES) {
            throw new IllegalArgumentException("mvs form draft values are invalid");
        }
    }

    private static JSONObject copy(JSONObject value) {
        try {
            return value == null ? new JSONObject() : new JSONObject(value.toString());
        } catch (JSONException exception) {
            throw new IllegalArgumentException("mvs form draft values are invalid", exception);
        }
    }

    private static String numericOrderId(String value) {
        String accepted = value == null ? "" : value.trim();
        if (!accepted.matches("^[1-9][0-9]{0,18}$")) throw invalidSnapshot();
        return accepted;
    }

    private static int positiveInteger(Object value) {
        if (!(value instanceof Number)) throw invalidSnapshot();
        long accepted = ((Number) value).longValue();
        if (accepted < 1 || accepted > Integer.MAX_VALUE) throw invalidSnapshot();
        return (int) accepted;
    }

    private static String nodeCode(String value) {
        String accepted = value == null ? "" : value.trim();
        if (!accepted.matches("^[A-Za-z0-9_.:-]{0,199}$")) throw invalidSnapshot();
        return accepted;
    }

    private static IllegalArgumentException invalidSnapshot() {
        return new IllegalArgumentException("mvs form draft snapshot invalid");
    }
}
