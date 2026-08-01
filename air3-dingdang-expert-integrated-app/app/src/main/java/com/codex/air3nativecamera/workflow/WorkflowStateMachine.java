package com.codex.air3nativecamera.workflow;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class WorkflowStateMachine {
    private static final class TransitionSelection {
        private final WorkflowPackage.Transition transition;
        private final String error;

        private TransitionSelection(WorkflowPackage.Transition transition, String error) {
            this.transition = transition;
            this.error = error;
        }
    }

    private final WorkflowPackage workflowPackage;
    private final WorkflowEvidenceGate evidenceGate = new WorkflowEvidenceGate();
    private final WorkflowConditionEvaluator conditions = new WorkflowConditionEvaluator();

    public WorkflowStateMachine(WorkflowPackage workflowPackage) {
        if (workflowPackage == null) throw new IllegalArgumentException("workflow package is required");
        this.workflowPackage = workflowPackage;
    }

    public WorkflowRuntimeState start() {
        WorkflowRuntimeState state = new WorkflowRuntimeState(
                workflowPackage.workflowVersionId(),
                workflowPackage.startNodeId(),
                WorkflowRuntimeState.Status.ACTIVE,
                0,
                new JSONObject());
        TransitionSelection selection = selectTransition(state.currentNodeId(), state.variables());
        if (selection.transition == null) {
            throw new IllegalStateException(selection.error);
        }
        return state.moveTo(
                selection.transition.toNodeId(),
                WorkflowRuntimeState.Status.ACTIVE,
                state.variables());
    }

    public WorkflowAdvanceResult advance(WorkflowRuntimeState state, WorkflowStepContext context) {
        if (state == null || context == null || !workflowPackage.workflowVersionId().equals(state.workflowVersionId())) {
            throw new IllegalArgumentException("workflow runtime input is invalid");
        }
        if (state.status() == WorkflowRuntimeState.Status.COMPLETED
                || state.status() == WorkflowRuntimeState.Status.CANCELLED) {
            return WorkflowAdvanceResult.blocked(state, "execution_terminal");
        }
        WorkflowPackage.Node node = workflowPackage.node(state.currentNodeId());
        if (node == null) return WorkflowAdvanceResult.blocked(state, "node_not_found");

        JSONObject variables = merge(state.variables(), context.fields());
        WorkflowGateResult gate = evidenceGate.evaluate(node, context, variables);
        if (!gate.allowed()) {
            WorkflowRuntimeState.Status status = gate.waitingNetwork()
                    ? WorkflowRuntimeState.Status.WAITING_NETWORK
                    : WorkflowRuntimeState.Status.ACTIVE;
            return WorkflowAdvanceResult.blocked(state.retain(status, variables), gate.code());
        }
        if ("complete".equals(node.type())) {
            return WorkflowAdvanceResult.advanced(
                    state.moveTo(node.nodeId(), WorkflowRuntimeState.Status.COMPLETED, variables));
        }

        TransitionSelection selection = selectTransition(node.nodeId(), variables);
        if (selection.transition == null) {
            return WorkflowAdvanceResult.blocked(
                    state.retain(WorkflowRuntimeState.Status.ACTIVE, variables),
                    selection.error);
        }
        WorkflowRuntimeState next = state.moveTo(
                selection.transition.toNodeId(),
                WorkflowRuntimeState.Status.ACTIVE,
                variables);
        return WorkflowAdvanceResult.advanced(next);
    }

    private TransitionSelection selectTransition(String nodeId, JSONObject variables) {
        List<WorkflowPackage.Transition> matched = new ArrayList<>();
        List<WorkflowPackage.Transition> fallback = new ArrayList<>();
        for (WorkflowPackage.Transition transition : workflowPackage.transitions()) {
            if (!transition.fromNodeId().equals(nodeId)) continue;
            JSONObject condition = transition.condition();
            if (condition == null) fallback.add(transition);
            else if (conditions.evaluate(condition, variables)) matched.add(transition);
        }
        if (matched.size() == 1) return new TransitionSelection(matched.get(0), "");
        if (matched.size() > 1) return new TransitionSelection(null, "transition_ambiguous");
        if (fallback.size() == 1) return new TransitionSelection(fallback.get(0), "");
        return new TransitionSelection(null, fallback.isEmpty()
                ? "transition_not_matched"
                : "transition_ambiguous");
    }

    private JSONObject merge(JSONObject current, JSONObject additions) {
        JSONObject result;
        try {
            result = new JSONObject(current.toString());
            Iterator<String> keys = additions.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                result.put(key, additions.opt(key));
            }
            return result;
        } catch (JSONException exception) {
            throw new IllegalArgumentException("workflow variables are invalid", exception);
        }
    }
}
