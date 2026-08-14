package com.codex.air3nativecamera.workflow;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** App-private, order-bound photo drafts waiting for a confirmed MVS upload contract. */
public final class MvsWorkOrderEvidenceDraftStore {
    private static final int VERSION = 1;
    private static final int MAX_DRAFTS = 200;
    private static final int MAX_PHOTO_BYTES = 5 * 1024 * 1024;

    interface Clock {
        long now();
    }

    public static final class Draft {
        private final String id;
        private final String orderId;
        private final String localReference;
        private final long byteSize;
        private final String sha256;
        private final long capturedAt;
        private final String state;

        private Draft(
                String id,
                String orderId,
                String localReference,
                long byteSize,
                String sha256,
                long capturedAt,
                String state
        ) {
            this.id = id;
            this.orderId = orderId;
            this.localReference = localReference;
            this.byteSize = byteSize;
            this.sha256 = sha256;
            this.capturedAt = capturedAt;
            this.state = state;
        }

        public String id() { return id; }
        public String orderId() { return orderId; }
        public String localReference() { return localReference; }
        public long byteSize() { return byteSize; }
        public String sha256() { return sha256; }
        public long capturedAt() { return capturedAt; }
        public String state() { return state; }

        private JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id)
                    .put("orderId", orderId)
                    .put("localReference", localReference)
                    .put("byteSize", byteSize)
                    .put("sha256", sha256)
                    .put("capturedAt", capturedAt)
                    .put("state", state);
        }
    }

    private final WorkflowSnapshotStorage storage;
    private final Clock clock;
    private final List<Draft> drafts = new ArrayList<>();

    public MvsWorkOrderEvidenceDraftStore(WorkflowSnapshotStorage storage) throws IOException {
        this(storage, System::currentTimeMillis);
    }

    MvsWorkOrderEvidenceDraftStore(WorkflowSnapshotStorage storage, Clock clock)
            throws IOException {
        if (storage == null || clock == null) {
            throw new IllegalArgumentException("mvs evidence draft storage is invalid");
        }
        this.storage = storage;
        this.clock = clock;
        restore();
    }

    public synchronized Draft recordPhoto(
            String orderId,
            String localReference,
            long byteSize,
            String sha256
    ) throws IOException {
        String acceptedOrderId = numericOrderId(orderId);
        String acceptedReference = reference(localReference, acceptedOrderId);
        if (byteSize < 1 || byteSize > MAX_PHOTO_BYTES) {
            throw new IllegalArgumentException("mvs evidence photo size is invalid");
        }
        String acceptedSha = sha256(sha256);
        for (Draft existing : drafts) {
            if (existing.orderId.equals(acceptedOrderId)
                    && existing.localReference.equals(acceptedReference)) {
                if (existing.byteSize != byteSize || !existing.sha256.equals(acceptedSha)) {
                    throw new IllegalArgumentException("mvs evidence draft conflict");
                }
                return existing;
            }
        }
        if (drafts.size() >= MAX_DRAFTS) {
            throw new IllegalStateException("mvs evidence draft limit reached");
        }
        Draft next = new Draft(
                "mvs-evidence-draft-" + UUID.randomUUID(),
                acceptedOrderId,
                acceptedReference,
                byteSize,
                acceptedSha,
                Math.max(0L, clock.now()),
                "draft");
        drafts.add(next);
        try {
            persist();
        } catch (IOException exception) {
            drafts.remove(next);
            throw exception;
        }
        return next;
    }

    public synchronized List<Draft> draftsForOrder(String orderId) {
        String acceptedOrderId = numericOrderId(orderId);
        List<Draft> result = new ArrayList<>();
        for (Draft draft : drafts) {
            if (draft.orderId.equals(acceptedOrderId)) result.add(draft);
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized int countForOrder(String orderId) {
        return draftsForOrder(orderId).size();
    }

    public synchronized Draft discard(String orderId, String draftId) throws IOException {
        String acceptedOrderId = numericOrderId(orderId);
        String acceptedId = identifier(draftId, "mvs evidence draft id");
        for (int index = 0; index < drafts.size(); index++) {
            Draft draft = drafts.get(index);
            if (!draft.orderId.equals(acceptedOrderId) || !draft.id.equals(acceptedId)) continue;
            drafts.remove(index);
            try {
                persist();
            } catch (IOException exception) {
                drafts.add(index, draft);
                throw exception;
            }
            return draft;
        }
        return null;
    }

    public static String sha256Hex(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("mvs evidence bytes are required");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha256_unavailable", exception);
        }
    }

    private void restore() throws IOException {
        byte[] bytes = storage.read();
        if (bytes == null || bytes.length == 0) return;
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (root.optInt("version", -1) != VERSION) throw invalidSnapshot();
            JSONArray values = root.optJSONArray("drafts");
            if (values == null || values.length() > MAX_DRAFTS) throw invalidSnapshot();
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) throw invalidSnapshot();
                Draft draft = fromJson(value);
                for (Draft existing : drafts) {
                    if (existing.id.equals(draft.id)
                            || (existing.orderId.equals(draft.orderId)
                            && existing.localReference.equals(draft.localReference))) {
                        throw invalidSnapshot();
                    }
                }
                drafts.add(draft);
            }
        } catch (JSONException | IllegalArgumentException exception) {
            throw new IOException("mvs evidence draft snapshot invalid", exception);
        }
    }

    private void persist() throws IOException {
        try {
            JSONArray values = new JSONArray();
            for (Draft draft : drafts) values.put(draft.toJson());
            byte[] bytes = new JSONObject()
                    .put("version", VERSION)
                    .put("drafts", values)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            storage.writeAtomically(bytes);
        } catch (JSONException exception) {
            throw new IOException("mvs evidence draft snapshot write failed", exception);
        }
    }

    private static Draft fromJson(JSONObject value) {
        String orderId = numericOrderId(value.optString("orderId", ""));
        String id = identifier(value.optString("id", ""), "mvs evidence draft id");
        String localReference = reference(value.optString("localReference", ""), orderId);
        long byteSize = value.optLong("byteSize", -1L);
        if (byteSize < 1 || byteSize > MAX_PHOTO_BYTES) {
            throw new IllegalArgumentException("mvs evidence photo size is invalid");
        }
        String sha = sha256(value.optString("sha256", ""));
        long capturedAt = value.optLong("capturedAt", -1L);
        if (capturedAt < 0 || !"draft".equals(value.optString("state", ""))) {
            throw new IllegalArgumentException("mvs evidence draft state is invalid");
        }
        return new Draft(id, orderId, localReference, byteSize, sha, capturedAt, "draft");
    }

    private static IllegalArgumentException invalidSnapshot() {
        return new IllegalArgumentException("mvs evidence draft snapshot invalid");
    }

    private static String numericOrderId(String value) {
        String accepted = value == null ? "" : value.trim();
        if (!accepted.matches("^[1-9][0-9]{0,18}$")) {
            throw new IllegalArgumentException("mvs order id is invalid");
        }
        return accepted;
    }

    private static String reference(String value, String orderId) {
        String accepted = value == null ? "" : value.trim();
        String prefix = "mvs-work-order-evidence/" + orderId + "/";
        if (!accepted.startsWith(prefix)
                || accepted.length() > prefix.length() + 160
                || accepted.contains("..")
                || !accepted.substring(prefix.length()).matches("^[A-Za-z0-9_.-]+\\.jpg$")) {
            throw new IllegalArgumentException("mvs evidence reference is invalid");
        }
        return accepted;
    }

    private static String identifier(String value, String label) {
        String accepted = value == null ? "" : value.trim();
        if (!accepted.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return accepted;
    }

    private static String sha256(String value) {
        String accepted = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!accepted.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("mvs evidence sha256 is invalid");
        }
        return accepted;
    }
}
