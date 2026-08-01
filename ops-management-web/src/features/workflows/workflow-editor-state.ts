import type {
  WorkflowDefinition,
  WorkflowDraft,
  WorkflowDraftNode,
  WorkflowDraftTransition,
  WorkflowNodeType,
} from "../../api/workflow-types";

export type WorkflowEditorErrorCode =
  | "fixed_node_required"
  | "node_not_found"
  | "transition_not_found"
  | "complete_has_no_outgoing"
  | "start_has_no_incoming"
  | "self_connection_invalid";

export class WorkflowEditorError extends Error {
  constructor(public readonly code: WorkflowEditorErrorCode) {
    super(code);
    this.name = "WorkflowEditorError";
  }
}

export type WorkflowEditorState = {
  draft: WorkflowDraft;
  baseline: WorkflowDraft;
  selectedNodeId: string | null;
  dirty: boolean;
};

export function createWorkflowEditorState(
  definition: WorkflowDefinition,
): WorkflowEditorState {
  const source = isWorkflowDraft(definition.draft_graph)
    ? definition.draft_graph
    : defaultDraft(definition);
  const baseline = cloneDraft(source);
  return {
    draft: cloneDraft(source),
    baseline,
    selectedNodeId: null,
    dirty: false,
  };
}

export function acceptSavedWorkflow(
  state: WorkflowEditorState,
  workflow: WorkflowDefinition,
): WorkflowEditorState {
  const savedDraft = isWorkflowDraft(workflow.draft_graph)
    ? workflow.draft_graph
    : state.draft;
  const selectedNodeId = state.selectedNodeId && savedDraft.nodes.some(
      (node) => node.nodeId === state.selectedNodeId,
    )
    ? state.selectedNodeId
    : null;
  return {
    draft: cloneDraft(savedDraft),
    baseline: cloneDraft(savedDraft),
    selectedNodeId,
    dirty: false,
  };
}

export function addWorkflowNode(
  state: WorkflowEditorState,
  type: WorkflowNodeType,
  position: { x: number; y: number },
  config: Record<string, unknown> = {},
): WorkflowEditorState {
  if (type === "start" || type === "complete") {
    throw new WorkflowEditorError("fixed_node_required");
  }
  const nodeId = nextNodeId(state.draft.nodes, type);
  const node: WorkflowDraftNode = {
    nodeId,
    type,
    config: cloneValue(config),
    position: { ...position },
  };
  return changed(state, {
    ...state.draft,
    nodes: [...state.draft.nodes, node],
  }, nodeId);
}

export function moveWorkflowNode(
  state: WorkflowEditorState,
  nodeId: string,
  position: { x: number; y: number },
): WorkflowEditorState {
  requireNode(state, nodeId);
  return changed(state, {
    ...state.draft,
    nodes: state.draft.nodes.map((node) =>
      node.nodeId === nodeId ? { ...node, position: { ...position } } : node
    ),
  });
}

export function selectWorkflowNode(
  state: WorkflowEditorState,
  nodeId: string | null,
): WorkflowEditorState {
  if (nodeId !== null) requireNode(state, nodeId);
  return { ...state, selectedNodeId: nodeId };
}

export function updateWorkflowNodeConfig(
  state: WorkflowEditorState,
  nodeId: string,
  config: Record<string, unknown>,
): WorkflowEditorState {
  requireNode(state, nodeId);
  return changed(state, {
    ...state.draft,
    nodes: state.draft.nodes.map((node) =>
      node.nodeId === nodeId
        ? { ...node, config: cloneValue(config) }
        : node
    ),
  }, nodeId);
}

export function connectWorkflowNodes(
  state: WorkflowEditorState,
  fromNodeId: string,
  toNodeId: string,
): WorkflowEditorState {
  const source = requireNode(state, fromNodeId);
  const target = requireNode(state, toNodeId);
  if (source.type === "complete") {
    throw new WorkflowEditorError("complete_has_no_outgoing");
  }
  if (target.type === "start") {
    throw new WorkflowEditorError("start_has_no_incoming");
  }
  if (fromNodeId === toNodeId) {
    throw new WorkflowEditorError("self_connection_invalid");
  }
  if (
    state.draft.transitions.some(
      (transition) =>
        transition.fromNodeId === fromNodeId && transition.toNodeId === toNodeId,
    )
  ) {
    return state;
  }
  const transition: WorkflowDraftTransition = {
    transitionId: nextTransitionId(state.draft.transitions),
    fromNodeId,
    toNodeId,
  };
  return changed(state, {
    ...state.draft,
    transitions: [...state.draft.transitions, transition],
  });
}

export function deleteWorkflowNode(
  state: WorkflowEditorState,
  nodeId: string,
): WorkflowEditorState {
  const node = requireNode(state, nodeId);
  if (node.type === "start" || node.type === "complete") {
    throw new WorkflowEditorError("fixed_node_required");
  }
  return changed(
    state,
    {
      ...state.draft,
      nodes: state.draft.nodes.filter((item) => item.nodeId !== nodeId),
      transitions: state.draft.transitions.filter(
        (transition) =>
          transition.fromNodeId !== nodeId && transition.toNodeId !== nodeId,
      ),
    },
    state.selectedNodeId === nodeId ? null : state.selectedNodeId,
  );
}

export function deleteWorkflowTransition(
  state: WorkflowEditorState,
  transitionId: string,
): WorkflowEditorState {
  if (!state.draft.transitions.some((item) => item.transitionId === transitionId)) {
    throw new WorkflowEditorError("transition_not_found");
  }
  return changed(state, {
    ...state.draft,
    transitions: state.draft.transitions.filter(
      (transition) => transition.transitionId !== transitionId,
    ),
  });
}

export function discardWorkflowChanges(
  state: WorkflowEditorState,
): WorkflowEditorState {
  return {
    draft: cloneDraft(state.baseline),
    baseline: cloneDraft(state.baseline),
    selectedNodeId: null,
    dirty: false,
  };
}

function changed(
  state: WorkflowEditorState,
  draft: WorkflowDraft,
  selectedNodeId = state.selectedNodeId,
): WorkflowEditorState {
  return { ...state, draft, selectedNodeId, dirty: true };
}

function requireNode(
  state: WorkflowEditorState,
  nodeId: string,
): WorkflowDraftNode {
  const node = state.draft.nodes.find((item) => item.nodeId === nodeId);
  if (!node) throw new WorkflowEditorError("node_not_found");
  return node;
}

function defaultDraft(definition: WorkflowDefinition): WorkflowDraft {
  return {
    workflowId: definition.id,
    schemaVersion: definition.schema_version,
    title: definition.title,
    nodes: [
      { nodeId: "start", type: "start", config: {}, position: { x: 80, y: 180 } },
      { nodeId: "complete", type: "complete", config: {}, position: { x: 520, y: 180 } },
    ],
    transitions: [
      {
        transitionId: "transition-1",
        fromNodeId: "start",
        toNodeId: "complete",
      },
    ],
  };
}

function nextNodeId(
  nodes: WorkflowDraftNode[],
  type: WorkflowNodeType,
): string {
  const existing = new Set(nodes.map((node) => node.nodeId));
  let sequence = 1;
  while (existing.has(`${type}-${sequence}`)) sequence += 1;
  return `${type}-${sequence}`;
}

function nextTransitionId(transitions: WorkflowDraftTransition[]): string {
  const existing = new Set(
    transitions.map((transition) => transition.transitionId),
  );
  let sequence = 1;
  while (existing.has(`transition-${sequence}`)) sequence += 1;
  return `transition-${sequence}`;
}

function cloneDraft(draft: WorkflowDraft): WorkflowDraft {
  return {
    workflowId: draft.workflowId,
    schemaVersion: draft.schemaVersion,
    title: draft.title,
    nodes: draft.nodes.map((node) => ({
      ...node,
      config: cloneValue(node.config),
      position: node.position ? { ...node.position } : undefined,
    })),
    transitions: draft.transitions.map((transition) => ({
      ...transition,
      condition: transition.condition
        ? cloneValue(transition.condition)
        : undefined,
    })),
  };
}

function cloneValue<T>(value: T): T {
  return structuredClone(value);
}

function isWorkflowDraft(value: unknown): value is WorkflowDraft {
  if (!isRecord(value)) return false;
  if (
    typeof value.workflowId !== "string" ||
    typeof value.schemaVersion !== "number" ||
    typeof value.title !== "string" ||
    !Array.isArray(value.nodes) ||
    !Array.isArray(value.transitions)
  ) {
    return false;
  }
  return value.nodes.every(isDraftNode) &&
    value.transitions.every(isDraftTransition);
}

function isDraftNode(value: unknown): value is WorkflowDraftNode {
  return isRecord(value) &&
    typeof value.nodeId === "string" &&
    typeof value.type === "string" &&
    isRecord(value.config);
}

function isDraftTransition(value: unknown): value is WorkflowDraftTransition {
  return isRecord(value) &&
    typeof value.transitionId === "string" &&
    typeof value.fromNodeId === "string" &&
    typeof value.toNodeId === "string";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
