package com.codex.air3nativecamera.workflow;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorkflowCapabilityRegistry {
    public enum PageTemplate {
        INSTRUCTION,
        EVIDENCE_CAPTURE,
        FORM,
        CHOICE,
        CONVERSATION,
        CONFIRMATION,
        COMPLETION,
        NONE
    }

    public enum ExecutionKind {
        UI_ONLY,
        INTERNAL,
        NATIVE_CAPABILITY
    }

    public enum Dispatch {
        STARTED,
        UI_ONLY,
        INTERNAL,
        UNAVAILABLE,
        FAILED
    }

    public interface Handler {
        void execute(Request request, Callback callback);
    }

    public interface Callback {
        void complete(Result result);
    }

    public static final class Binding {
        private final String nodeType;
        private final PageTemplate pageTemplate;
        private final ExecutionKind executionKind;
        private final String capabilityId;

        private Binding(
                String nodeType,
                PageTemplate pageTemplate,
                ExecutionKind executionKind,
                String capabilityId
        ) {
            this.nodeType = nodeType;
            this.pageTemplate = pageTemplate;
            this.executionKind = executionKind;
            this.capabilityId = capabilityId;
        }

        public String nodeType() {
            return nodeType;
        }

        public PageTemplate pageTemplate() {
            return pageTemplate;
        }

        public ExecutionKind executionKind() {
            return executionKind;
        }

        public String capabilityId() {
            return capabilityId;
        }
    }

    public static final class Request {
        private final String assignmentId;
        private final String executionId;
        private final int attemptNumber;
        private final JSONObject input;
        private WorkflowPackage.Node node;

        public Request(String assignmentId, String executionId, int attemptNumber, JSONObject input) {
            this.assignmentId = clean(assignmentId);
            this.executionId = clean(executionId);
            this.attemptNumber = attemptNumber;
            this.input = copy(input);
            if (this.assignmentId.isEmpty()
                    || this.executionId.isEmpty()
                    || attemptNumber < 1
                    || input == null
                    || this.input.toString().length() > 262_144) {
                throw new IllegalArgumentException("workflow capability request is invalid");
            }
        }

        private Request withNode(WorkflowPackage.Node nextNode) {
            Request bound = new Request(assignmentId, executionId, attemptNumber, input);
            bound.node = nextNode;
            return bound;
        }

        public String assignmentId() {
            return assignmentId;
        }

        public String executionId() {
            return executionId;
        }

        public int attemptNumber() {
            return attemptNumber;
        }

        public JSONObject input() {
            return copy(input);
        }

        public WorkflowPackage.Node node() {
            return node;
        }
    }

    public static final class Result {
        public enum Status {
            COMPLETED,
            CANCELLED,
            WAITING_NETWORK,
            FAILED
        }

        private final Status status;
        private final JSONObject output;
        private final String errorCode;

        private Result(Status status, JSONObject output, String errorCode) {
            this.status = status;
            this.output = copy(output);
            this.errorCode = clean(errorCode);
            if (status == null
                    || output == null
                    || this.output.toString().length() > 262_144
                    || (status == Status.FAILED && this.errorCode.isEmpty())) {
                throw new IllegalArgumentException("workflow capability result is invalid");
            }
        }

        public static Result completed(JSONObject output) {
            return new Result(Status.COMPLETED, output, "");
        }

        public static Result cancelled() {
            return new Result(Status.CANCELLED, new JSONObject(), "");
        }

        public static Result waitingNetwork(String errorCode) {
            String code = clean(errorCode);
            return new Result(Status.WAITING_NETWORK, new JSONObject(),
                    code.isEmpty() ? "network_required" : code);
        }

        public static Result failed(String errorCode) {
            return new Result(Status.FAILED, new JSONObject(), errorCode);
        }

        public Status status() {
            return status;
        }

        public JSONObject output() {
            return copy(output);
        }

        public String errorCode() {
            return errorCode;
        }
    }

    private static final Map<String, Binding> BINDINGS;
    private static final Set<String> NATIVE_CAPABILITIES;

    static {
        LinkedHashMap<String, Binding> bindings = new LinkedHashMap<>();
        add(bindings, "start", PageTemplate.NONE, ExecutionKind.INTERNAL, "");
        add(bindings, "instruction", PageTemplate.INSTRUCTION, ExecutionKind.UI_ONLY, "");
        add(bindings, "choice", PageTemplate.CHOICE, ExecutionKind.UI_ONLY, "");
        add(bindings, "form", PageTemplate.FORM, ExecutionKind.UI_ONLY, "");
        add(bindings, "photo_capture", PageTemplate.EVIDENCE_CAPTURE,
                ExecutionKind.NATIVE_CAPABILITY, "camera.photo");
        add(bindings, "video_capture", PageTemplate.EVIDENCE_CAPTURE,
                ExecutionKind.NATIVE_CAPABILITY, "camera.video");
        add(bindings, "voice_input", PageTemplate.FORM,
                ExecutionKind.NATIVE_CAPABILITY, "audio.voice_input");
        add(bindings, "ai_assist", PageTemplate.CONVERSATION,
                ExecutionKind.NATIVE_CAPABILITY, "ai.execution_context");
        add(bindings, "expert_call", PageTemplate.CONVERSATION,
                ExecutionKind.NATIVE_CAPABILITY, "expert.video");
        add(bindings, "confirmation", PageTemplate.CONFIRMATION, ExecutionKind.UI_ONLY, "");
        add(bindings, "condition", PageTemplate.NONE, ExecutionKind.INTERNAL, "");
        add(bindings, "repeat_group", PageTemplate.NONE, ExecutionKind.INTERNAL, "");
        add(bindings, "subflow", PageTemplate.NONE, ExecutionKind.INTERNAL, "");
        add(bindings, "connector_action", PageTemplate.CONFIRMATION,
                ExecutionKind.NATIVE_CAPABILITY, "connector.gateway");
        add(bindings, "complete", PageTemplate.COMPLETION, ExecutionKind.UI_ONLY, "");
        BINDINGS = Collections.unmodifiableMap(bindings);

        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        for (Binding binding : bindings.values()) {
            if (!binding.capabilityId().isEmpty()) capabilities.add(binding.capabilityId());
        }
        NATIVE_CAPABILITIES = Collections.unmodifiableSet(capabilities);
    }

    private final Map<String, Handler> handlers;
    private final Set<String> supportedCapabilities;

    public WorkflowCapabilityRegistry(Map<String, Handler> handlers) {
        if (handlers == null) throw new IllegalArgumentException("workflow capability handlers are required");
        LinkedHashMap<String, Handler> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, Handler> item : handlers.entrySet()) {
            String capabilityId = clean(item.getKey());
            if (!NATIVE_CAPABILITIES.contains(capabilityId) || item.getValue() == null) {
                throw new IllegalArgumentException("workflow capability handler is invalid");
            }
            accepted.put(capabilityId, item.getValue());
        }
        this.handlers = Collections.unmodifiableMap(accepted);
        LinkedHashSet<String> capabilities = new LinkedHashSet<>();
        capabilities.add("workflow.runtime.v1");
        capabilities.addAll(accepted.keySet());
        this.supportedCapabilities = Collections.unmodifiableSet(capabilities);
    }

    public static Binding bindingForNodeType(String nodeType) {
        Binding binding = BINDINGS.get(clean(nodeType));
        if (binding == null) throw new IllegalArgumentException("workflow node type is unsupported");
        return binding;
    }

    public static List<String> nodeTypes() {
        return Collections.unmodifiableList(new ArrayList<>(BINDINGS.keySet()));
    }

    public Set<String> supportedCapabilities() {
        return supportedCapabilities;
    }

    public Dispatch dispatch(WorkflowPackage.Node node, Request request, Callback callback) {
        if (node == null || request == null || callback == null) {
            throw new IllegalArgumentException("workflow capability dispatch is invalid");
        }
        Binding binding = bindingForNodeType(node.type());
        if (binding.executionKind() == ExecutionKind.UI_ONLY) return Dispatch.UI_ONLY;
        if (binding.executionKind() == ExecutionKind.INTERNAL) return Dispatch.INTERNAL;

        Handler handler = handlers.get(binding.capabilityId());
        OneShotCallback guarded = new OneShotCallback(callback);
        if (handler == null) {
            guarded.complete(Result.failed("capability_unavailable"));
            return Dispatch.UNAVAILABLE;
        }
        try {
            handler.execute(request.withNode(node), guarded);
            return Dispatch.STARTED;
        } catch (RuntimeException exception) {
            guarded.complete(Result.failed("capability_start_failed"));
            return Dispatch.FAILED;
        }
    }

    private static void add(
            Map<String, Binding> bindings,
            String nodeType,
            PageTemplate pageTemplate,
            ExecutionKind executionKind,
            String capabilityId
    ) {
        bindings.put(nodeType, new Binding(nodeType, pageTemplate, executionKind, capabilityId));
    }

    private static final class OneShotCallback implements Callback {
        private final Callback delegate;
        private final AtomicBoolean completed = new AtomicBoolean();

        private OneShotCallback(Callback delegate) {
            this.delegate = delegate;
        }

        @Override
        public void complete(Result result) {
            if (result == null || !completed.compareAndSet(false, true)) return;
            delegate.complete(result);
        }
    }

    private static JSONObject copy(JSONObject value) {
        if (value == null) return new JSONObject();
        try {
            return new JSONObject(value.toString());
        } catch (JSONException exception) {
            throw new IllegalArgumentException("workflow capability JSON is invalid", exception);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
