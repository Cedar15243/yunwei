package com.codex.air3nativecamera.workflow;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkflowPackage {
    private static final Set<String> NODE_TYPES;

    static {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Collections.addAll(values,
                "start", "instruction", "choice", "form", "photo_capture",
                "video_capture", "voice_input", "ai_assist", "expert_call",
                "confirmation", "condition", "repeat_group", "subflow",
                "connector_action", "complete");
        NODE_TYPES = Collections.unmodifiableSet(values);
    }

    public static final class Node {
        private final String nodeId;
        private final String type;
        private final JSONObject config;

        private Node(String nodeId, String type, JSONObject config) {
            this.nodeId = nodeId;
            this.type = type;
            this.config = config;
        }

        public String nodeId() {
            return nodeId;
        }

        public String type() {
            return type;
        }

        public JSONObject config() {
            return copy(config);
        }
    }

    public static final class Transition {
        private final String transitionId;
        private final String fromNodeId;
        private final String toNodeId;
        private final JSONObject condition;

        private Transition(String transitionId, String fromNodeId, String toNodeId, JSONObject condition) {
            this.transitionId = transitionId;
            this.fromNodeId = fromNodeId;
            this.toNodeId = toNodeId;
            this.condition = condition;
        }

        public String transitionId() {
            return transitionId;
        }

        public String fromNodeId() {
            return fromNodeId;
        }

        public String toNodeId() {
            return toNodeId;
        }

        public JSONObject condition() {
            return condition == null ? null : copy(condition);
        }
    }

    private final String assignmentId;
    private final String workflowVersionId;
    private final String workflowId;
    private final int schemaVersion;
    private final String title;
    private final String contentSha256;
    private final String signatureKeyId;
    private final int minAppVersionCode;
    private final Set<String> requiredCapabilities;
    private final Map<String, Node> nodes;
    private final List<Transition> transitions;
    private final String startNodeId;

    private WorkflowPackage(
            String assignmentId,
            String workflowVersionId,
            String workflowId,
            int schemaVersion,
            String title,
            String contentSha256,
            String signatureKeyId,
            int minAppVersionCode,
            Set<String> requiredCapabilities,
            Map<String, Node> nodes,
            List<Transition> transitions,
            String startNodeId
    ) {
        this.assignmentId = assignmentId;
        this.workflowVersionId = workflowVersionId;
        this.workflowId = workflowId;
        this.schemaVersion = schemaVersion;
        this.title = title;
        this.contentSha256 = contentSha256;
        this.signatureKeyId = signatureKeyId;
        this.minAppVersionCode = minAppVersionCode;
        this.requiredCapabilities = Collections.unmodifiableSet(new LinkedHashSet<>(requiredCapabilities));
        this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        this.transitions = Collections.unmodifiableList(new ArrayList<>(transitions));
        this.startNodeId = startNodeId;
    }

    static WorkflowPackage parseVerified(JSONObject envelope, JSONObject executionPackage) {
        String assignmentId = requiredText(envelope, "assignmentId", 200);
        String workflowVersionId = requiredText(envelope, "workflowVersionId", 200);
        String workflowId = requiredText(executionPackage, "workflowId", 200);
        String title = requiredText(executionPackage, "title", 400);
        String contentSha256 = requiredText(executionPackage, "contentSha256", 64);
        String signatureKeyId = requiredText(envelope, "signatureKeyId", 200);
        int schemaVersion = positiveInt(executionPackage, "schemaVersion");
        int minAppVersionCode = positiveInt(envelope, "minAppVersionCode");
        if (assignmentId == null || workflowVersionId == null || workflowId == null || title == null
                || contentSha256 == null || signatureKeyId == null || schemaVersion < 1
                || minAppVersionCode < 1) {
            throw new IllegalArgumentException("package_identity_invalid");
        }

        Set<String> capabilities = stringSet(executionPackage.optJSONArray("requiredCapabilities"), 100);
        JSONArray nodeArray = executionPackage.optJSONArray("nodes");
        JSONArray transitionArray = executionPackage.optJSONArray("transitions");
        if (capabilities == null || nodeArray == null || nodeArray.length() < 2 || nodeArray.length() > 500
                || transitionArray == null || transitionArray.length() > 2000) {
            throw new IllegalArgumentException("package_shape_invalid");
        }

        Map<String, Node> nodes = new LinkedHashMap<>();
        String startNodeId = null;
        int completeCount = 0;
        for (int index = 0; index < nodeArray.length(); index += 1) {
            JSONObject item = nodeArray.optJSONObject(index);
            if (item == null) throw new IllegalArgumentException("node_invalid");
            String nodeId = requiredText(item, "nodeId", 160);
            String type = requiredText(item, "type", 80);
            JSONObject config = item.optJSONObject("config");
            if (nodeId == null || type == null || config == null || !NODE_TYPES.contains(type)
                    || nodes.containsKey(nodeId)) {
                throw new IllegalArgumentException("node_invalid");
            }
            if ("start".equals(type)) {
                if (startNodeId != null) throw new IllegalArgumentException("start_invalid");
                startNodeId = nodeId;
            }
            if ("complete".equals(type)) completeCount += 1;
            nodes.put(nodeId, new Node(nodeId, type, copy(config)));
        }
        if (startNodeId == null || completeCount < 1) throw new IllegalArgumentException("terminal_nodes_invalid");

        List<Transition> transitions = new ArrayList<>();
        Set<String> transitionIds = new HashSet<>();
        Map<String, List<String>> adjacency = adjacency(nodes.keySet());
        Map<String, List<String>> reverse = adjacency(nodes.keySet());
        Map<String, Integer> indegree = new HashMap<>();
        for (String nodeId : nodes.keySet()) indegree.put(nodeId, 0);
        for (int index = 0; index < transitionArray.length(); index += 1) {
            JSONObject item = transitionArray.optJSONObject(index);
            if (item == null) throw new IllegalArgumentException("transition_invalid");
            String transitionId = requiredText(item, "transitionId", 160);
            String fromNodeId = requiredText(item, "fromNodeId", 160);
            String toNodeId = requiredText(item, "toNodeId", 160);
            JSONObject condition = item.optJSONObject("condition");
            if (transitionId == null || fromNodeId == null || toNodeId == null
                    || !transitionIds.add(transitionId) || !nodes.containsKey(fromNodeId)
                    || !nodes.containsKey(toNodeId) || "complete".equals(nodes.get(fromNodeId).type())
                    || "start".equals(nodes.get(toNodeId).type())) {
                throw new IllegalArgumentException("transition_invalid");
            }
            transitions.add(new Transition(
                    transitionId,
                    fromNodeId,
                    toNodeId,
                    condition == null ? null : copy(condition)));
            adjacency.get(fromNodeId).add(toNodeId);
            reverse.get(toNodeId).add(fromNodeId);
            indegree.put(toNodeId, indegree.get(toNodeId) + 1);
        }
        validateGraph(nodes, startNodeId, adjacency, reverse, indegree);
        return new WorkflowPackage(
                assignmentId,
                workflowVersionId,
                workflowId,
                schemaVersion,
                title,
                contentSha256,
                signatureKeyId,
                minAppVersionCode,
                capabilities,
                nodes,
                transitions,
                startNodeId);
    }

    private static void validateGraph(
            Map<String, Node> nodes,
            String startNodeId,
            Map<String, List<String>> adjacency,
            Map<String, List<String>> reverse,
            Map<String, Integer> indegree
    ) {
        Set<String> reachable = visit(Collections.singleton(startNodeId), adjacency);
        if (reachable.size() != nodes.size()) throw new IllegalArgumentException("unreachable_node");

        List<String> completeIds = new ArrayList<>();
        for (Node node : nodes.values()) if ("complete".equals(node.type())) completeIds.add(node.nodeId());
        Set<String> canComplete = visit(completeIds, reverse);
        if (canComplete.size() != nodes.size()) throw new IllegalArgumentException("completion_unreachable");

        Deque<String> pending = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) pending.add(entry.getKey());
        }
        int visited = 0;
        while (!pending.isEmpty()) {
            String nodeId = pending.removeFirst();
            visited += 1;
            for (String target : adjacency.get(nodeId)) {
                int next = indegree.get(target) - 1;
                indegree.put(target, next);
                if (next == 0) pending.addLast(target);
            }
        }
        if (visited != nodes.size()) throw new IllegalArgumentException("cycle_not_allowed");
    }

    private static Map<String, List<String>> adjacency(Set<String> nodeIds) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String nodeId : nodeIds) result.put(nodeId, new ArrayList<>());
        return result;
    }

    private static Set<String> visit(Iterable<String> starts, Map<String, List<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        for (String start : starts) pending.add(start);
        while (!pending.isEmpty()) {
            String nodeId = pending.removeLast();
            if (!visited.add(nodeId)) continue;
            for (String target : adjacency.get(nodeId)) pending.add(target);
        }
        return visited;
    }

    private static Set<String> stringSet(JSONArray values, int limit) {
        if (values == null || values.length() < 1 || values.length() > limit) return null;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (int index = 0; index < values.length(); index += 1) {
            String value = clean(values.optString(index, ""));
            if (value.isEmpty() || value.length() > 160 || !result.add(value)) return null;
        }
        return result;
    }

    private static String requiredText(JSONObject value, String key, int maxLength) {
        String text = clean(value.optString(key, ""));
        return text.isEmpty() || text.length() > maxLength ? null : text;
    }

    private static int positiveInt(JSONObject value, String key) {
        Object raw = value.opt(key);
        return raw instanceof Number ? ((Number) raw).intValue() : -1;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static JSONObject copy(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException exception) {
            throw new IllegalArgumentException("invalid_json_object", exception);
        }
    }

    public String assignmentId() {
        return assignmentId;
    }

    public String workflowVersionId() {
        return workflowVersionId;
    }

    public String workflowId() {
        return workflowId;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String title() {
        return title;
    }

    public String contentSha256() {
        return contentSha256;
    }

    public String signatureKeyId() {
        return signatureKeyId;
    }

    public int minAppVersionCode() {
        return minAppVersionCode;
    }

    public Set<String> requiredCapabilities() {
        return requiredCapabilities;
    }

    public String startNodeId() {
        return startNodeId;
    }

    public Node node(String nodeId) {
        return nodes.get(nodeId);
    }

    public List<Transition> transitions() {
        return transitions;
    }

    public int nodeCount() {
        return nodes.size();
    }
}
