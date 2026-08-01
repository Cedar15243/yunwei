package com.codex.air3nativecamera.sync;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkflowAssignmentRepository {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_ASSIGNMENTS = 500;
    private static final int MAX_STORAGE_BYTES = 1024 * 1024;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Set<String> MODES = set("required", "optional", "none");
    private static final Set<String> STATUSES = set(
            "queued", "notified", "delivered", "verified", "ready",
            "active", "completed", "failed", "revoked");

    public enum ApplyResult {
        APPLIED,
        NO_CHANGE,
        REJECTED,
        PERSIST_FAILED
    }

    public interface Storage {
        byte[] read() throws IOException;
        void writeAtomically(byte[] value) throws IOException;
    }

    public static final class CachedAssignment {
        private final String assignmentId;
        private final String workOrderId;
        private final String projectId;
        private final String workflowVersionId;
        private final String mode;
        private final String status;
        private final long deliverySequence;
        private final String assignedAt;
        private final boolean packageCached;

        private CachedAssignment(
                String assignmentId,
                String workOrderId,
                String projectId,
                String workflowVersionId,
                String mode,
                String status,
                long deliverySequence,
                String assignedAt,
                boolean packageCached
        ) {
            this.assignmentId = clean(assignmentId);
            this.workOrderId = clean(workOrderId);
            this.projectId = clean(projectId);
            this.workflowVersionId = clean(workflowVersionId);
            this.mode = clean(mode);
            this.status = clean(status);
            this.deliverySequence = deliverySequence;
            this.assignedAt = clean(assignedAt);
            this.packageCached = packageCached;
            if (!validIdentifier(this.assignmentId)
                    || !validIdentifier(this.workOrderId)
                    || (!this.projectId.isEmpty() && !validIdentifier(this.projectId))
                    || (!this.workflowVersionId.isEmpty() && !validIdentifier(this.workflowVersionId))
                    || !MODES.contains(this.mode)
                    || !STATUSES.contains(this.status)
                    || deliverySequence < 1L || deliverySequence > MAX_SAFE_INTEGER
                    || this.assignedAt.isEmpty() || this.assignedAt.length() > 100
                    || ("none".equals(this.mode) && !this.workflowVersionId.isEmpty())
                    || (!"none".equals(this.mode) && this.workflowVersionId.isEmpty())
                    || (packageCached && invalidatesPackage(this.mode, this.status))) {
                throw new IllegalArgumentException("workflow assignment cache entry is invalid");
            }
        }

        private static CachedAssignment from(WorkflowDeviceHttpClient.Assignment value) {
            return new CachedAssignment(
                    value.assignmentId(),
                    value.workOrderId(),
                    value.projectId(),
                    value.workflowVersionId(),
                    value.mode(),
                    value.status(),
                    value.deliverySequence(),
                    value.assignedAt(),
                    false);
        }

        private CachedAssignment update(WorkflowDeviceHttpClient.Assignment value) {
            if (!assignmentId.equals(value.assignmentId())
                    || !workOrderId.equals(value.workOrderId())
                    || !projectId.equals(value.projectId())
                    || !workflowVersionId.equals(value.workflowVersionId())
                    || !mode.equals(value.mode())
                    || !assignedAt.equals(value.assignedAt())
                    || value.deliverySequence() <= deliverySequence
                    || !forwardStatus(status, value.status())) {
                return null;
            }
            return new CachedAssignment(
                    assignmentId,
                    workOrderId,
                    projectId,
                    workflowVersionId,
                    mode,
                    value.status(),
                    value.deliverySequence(),
                    assignedAt,
                    packageCached && !invalidatesPackage(mode, value.status()));
        }

        private CachedAssignment withPackageCached() {
            return new CachedAssignment(
                    assignmentId, workOrderId, projectId, workflowVersionId, mode,
                    status, deliverySequence, assignedAt, true);
        }

        private CachedAssignment withPackageUnavailable() {
            return new CachedAssignment(
                    assignmentId, workOrderId, projectId, workflowVersionId, mode,
                    status, deliverySequence, assignedAt, false);
        }

        public String assignmentId() { return assignmentId; }
        public String workOrderId() { return workOrderId; }
        public String projectId() { return projectId; }
        public String workflowVersionId() { return workflowVersionId; }
        public String mode() { return mode; }
        public String status() { return status; }
        public long deliverySequence() { return deliverySequence; }
        public String assignedAt() { return assignedAt; }
        public boolean packageCached() { return packageCached; }

        private JSONObject toJson() {
            try {
                return new JSONObject()
                        .put("assignment_id", assignmentId)
                        .put("work_order_id", workOrderId)
                        .put("project_id", projectId.isEmpty() ? JSONObject.NULL : projectId)
                        .put("workflow_version_id", workflowVersionId.isEmpty()
                                ? JSONObject.NULL : workflowVersionId)
                        .put("mode", mode)
                        .put("status", status)
                        .put("delivery_sequence", deliverySequence)
                        .put("assigned_at", assignedAt)
                        .put("package_cached", packageCached);
            } catch (JSONException exception) {
                throw new IllegalStateException("unable to serialize workflow assignment", exception);
            }
        }

        private static CachedAssignment fromJson(JSONObject value) {
            if (value == null
                    || !(value.opt("delivery_sequence") instanceof Number)
                    || !(value.opt("package_cached") instanceof Boolean)) {
                throw new IllegalArgumentException("workflow assignment cache entry is invalid");
            }
            long sequence = exactNonNegativeLong(value.opt("delivery_sequence"));
            return new CachedAssignment(
                    value.optString("assignment_id", ""),
                    value.optString("work_order_id", ""),
                    nullableText(value, "project_id"),
                    nullableText(value, "workflow_version_id"),
                    value.optString("mode", ""),
                    value.optString("status", ""),
                    sequence,
                    value.optString("assigned_at", ""),
                    value.optBoolean("package_cached", false));
        }
    }

    private final Storage storage;
    private long cursor;
    private LinkedHashMap<String, CachedAssignment> values;

    public WorkflowAssignmentRepository(Storage storage) {
        if (storage == null) throw new IllegalArgumentException("workflow assignment storage is required");
        this.storage = storage;
        this.values = new LinkedHashMap<>();
        restore();
    }

    public synchronized long cursor() {
        return cursor;
    }

    public synchronized CachedAssignment find(String assignmentId) {
        return values.get(clean(assignmentId));
    }

    public synchronized List<CachedAssignment> assignments() {
        List<CachedAssignment> result = new ArrayList<>(values.values());
        Collections.sort(result, new Comparator<CachedAssignment>() {
            @Override
            public int compare(CachedAssignment left, CachedAssignment right) {
                return Long.compare(right.deliverySequence(), left.deliverySequence());
            }
        });
        return Collections.unmodifiableList(result);
    }

    public synchronized ApplyResult apply(WorkflowDeviceHttpClient.AssignmentPage page) {
        if (page == null || page.nextSequence() < cursor || page.nextSequence() > MAX_SAFE_INTEGER) {
            return ApplyResult.REJECTED;
        }
        List<WorkflowDeviceHttpClient.Assignment> items = page.items();
        if (items.isEmpty()) {
            return page.nextSequence() == cursor ? ApplyResult.NO_CHANGE : ApplyResult.REJECTED;
        }

        LinkedHashMap<String, CachedAssignment> next = new LinkedHashMap<>(values);
        long previousSequence = cursor;
        try {
            for (WorkflowDeviceHttpClient.Assignment item : items) {
                if (item == null
                        || item.deliverySequence() <= previousSequence
                        || item.deliverySequence() > page.nextSequence()) {
                    return ApplyResult.REJECTED;
                }
                CachedAssignment existing = next.get(item.assignmentId());
                CachedAssignment merged = existing == null
                        ? CachedAssignment.from(item)
                        : existing.update(item);
                if (merged == null) return ApplyResult.REJECTED;
                next.put(merged.assignmentId(), merged);
                previousSequence = item.deliverySequence();
            }
        } catch (IllegalArgumentException exception) {
            return ApplyResult.REJECTED;
        }
        if (previousSequence != page.nextSequence() || !prune(next)) {
            return ApplyResult.REJECTED;
        }
        return persist(page.nextSequence(), next);
    }

    public synchronized ApplyResult markPackageCached(String assignmentId, String workflowVersionId) {
        String id = clean(assignmentId);
        CachedAssignment current = values.get(id);
        if (current == null
                || !current.workflowVersionId().equals(clean(workflowVersionId))
                || invalidatesPackage(current.mode(), current.status())) {
            return ApplyResult.REJECTED;
        }
        if (current.packageCached()) return ApplyResult.NO_CHANGE;
        LinkedHashMap<String, CachedAssignment> next = new LinkedHashMap<>(values);
        next.put(id, current.withPackageCached());
        return persist(cursor, next);
    }

    public synchronized ApplyResult markPackageUnavailable(
            String assignmentId,
            String workflowVersionId
    ) {
        String id = clean(assignmentId);
        CachedAssignment current = values.get(id);
        if (current == null || !current.workflowVersionId().equals(clean(workflowVersionId))) {
            return ApplyResult.REJECTED;
        }
        if (!current.packageCached()) return ApplyResult.NO_CHANGE;
        LinkedHashMap<String, CachedAssignment> next = new LinkedHashMap<>(values);
        next.put(id, current.withPackageUnavailable());
        return persist(cursor, next);
    }

    private ApplyResult persist(long nextCursor, LinkedHashMap<String, CachedAssignment> next) {
        byte[] bytes = serialize(nextCursor, next);
        if (bytes.length > MAX_STORAGE_BYTES) return ApplyResult.REJECTED;
        try {
            storage.writeAtomically(bytes);
        } catch (IOException exception) {
            return ApplyResult.PERSIST_FAILED;
        }
        cursor = nextCursor;
        values = next;
        return ApplyResult.APPLIED;
    }

    private void restore() {
        byte[] bytes;
        try {
            bytes = storage.read();
        } catch (IOException exception) {
            throw new IllegalStateException("workflow assignment cache read failed", exception);
        }
        if (bytes == null || bytes.length == 0) return;
        if (bytes.length > MAX_STORAGE_BYTES) {
            throw new IllegalStateException("workflow assignment cache is too large");
        }
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (exactNonNegativeLong(root.opt("schema_version")) != SCHEMA_VERSION) {
                throw new IllegalArgumentException("workflow assignment cache schema is invalid");
            }
            long restoredCursor = exactNonNegativeLong(root.opt("cursor"));
            JSONArray assignments = root.optJSONArray("assignments");
            if (restoredCursor < 0L || assignments == null || assignments.length() > MAX_ASSIGNMENTS) {
                throw new IllegalArgumentException("workflow assignment cache is invalid");
            }
            LinkedHashMap<String, CachedAssignment> restored = new LinkedHashMap<>();
            long highestSequence = 0L;
            for (int index = 0; index < assignments.length(); index += 1) {
                CachedAssignment item = CachedAssignment.fromJson(assignments.optJSONObject(index));
                if (restored.put(item.assignmentId(), item) != null) {
                    throw new IllegalArgumentException("workflow assignment cache is duplicated");
                }
                highestSequence = Math.max(highestSequence, item.deliverySequence());
            }
            if (highestSequence > restoredCursor) {
                throw new IllegalArgumentException("workflow assignment cache cursor is invalid");
            }
            cursor = restoredCursor;
            values = restored;
        } catch (JSONException | IllegalArgumentException exception) {
            throw new IllegalStateException("workflow assignment cache is corrupt", exception);
        }
    }

    private static byte[] serialize(long cursor, Map<String, CachedAssignment> values) {
        try {
            JSONArray assignments = new JSONArray();
            for (CachedAssignment item : values.values()) assignments.put(item.toJson());
            return new JSONObject()
                    .put("schema_version", SCHEMA_VERSION)
                    .put("cursor", cursor)
                    .put("assignments", assignments)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JSONException exception) {
            throw new IllegalStateException("unable to serialize workflow assignment cache", exception);
        }
    }

    private static boolean prune(LinkedHashMap<String, CachedAssignment> values) {
        if (values.size() <= MAX_ASSIGNMENTS) return true;
        List<CachedAssignment> removable = new ArrayList<>();
        for (CachedAssignment item : values.values()) {
            if (terminal(item.status())) removable.add(item);
        }
        Collections.sort(removable, new Comparator<CachedAssignment>() {
            @Override
            public int compare(CachedAssignment left, CachedAssignment right) {
                return Long.compare(left.deliverySequence(), right.deliverySequence());
            }
        });
        Iterator<CachedAssignment> iterator = removable.iterator();
        while (values.size() > MAX_ASSIGNMENTS && iterator.hasNext()) {
            values.remove(iterator.next().assignmentId());
        }
        return values.size() <= MAX_ASSIGNMENTS;
    }

    private static boolean forwardStatus(String current, String next) {
        if (current.equals(next)) return true;
        if (terminal(current)) return false;
        if ("failed".equals(next) || "revoked".equals(next)) return true;
        return statusRank(next) > statusRank(current);
    }

    private static int statusRank(String status) {
        if ("queued".equals(status)) return 0;
        if ("notified".equals(status)) return 1;
        if ("delivered".equals(status)) return 2;
        if ("verified".equals(status)) return 3;
        if ("ready".equals(status)) return 4;
        if ("active".equals(status)) return 5;
        if ("completed".equals(status)) return 6;
        return -1;
    }

    private static boolean terminal(String status) {
        return "completed".equals(status) || "failed".equals(status) || "revoked".equals(status);
    }

    private static boolean invalidatesPackage(String mode, String status) {
        return "none".equals(mode) || "failed".equals(status) || "revoked".equals(status);
    }

    private static long exactNonNegativeLong(Object raw) {
        if (!(raw instanceof Number)) return -1L;
        Number number = (Number) raw;
        double decimal = number.doubleValue();
        long integer = number.longValue();
        return Double.isFinite(decimal)
                && decimal == (double) integer
                && integer >= 0L
                && integer <= MAX_SAFE_INTEGER
                ? integer : -1L;
    }

    private static String nullableText(JSONObject value, String key) {
        Object raw = value.opt(key);
        return raw == null || raw == JSONObject.NULL ? "" : clean(String.valueOf(raw));
    }

    private static boolean validIdentifier(String value) {
        return value != null && value.matches("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}$");
    }

    private static Set<String> set(String... values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
