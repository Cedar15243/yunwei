export const WORKFLOW_NODE_TYPES = [
  "start",
  "instruction",
  "choice",
  "form",
  "photo_capture",
  "video_capture",
  "voice_input",
  "ai_assist",
  "expert_call",
  "confirmation",
  "condition",
  "repeat_group",
  "subflow",
  "connector_action",
  "complete",
] as const;

export type WorkflowNodeType = typeof WORKFLOW_NODE_TYPES[number];

export type WorkflowValidationErrorCode =
  | "invalid_draft"
  | "workflow_id_required"
  | "schema_version_required"
  | "title_required"
  | "nodes_required"
  | "invalid_node"
  | "node_id_required"
  | "node_type_required"
  | "unsupported_node_type"
  | "invalid_node_config"
  | "forbidden_config_key"
  | "duplicate_node_id"
  | "transitions_required"
  | "invalid_transition"
  | "transition_id_required"
  | "duplicate_transition_id"
  | "transition_endpoint_required"
  | "transition_node_not_found"
  | "condition_invalid"
  | "condition_operator_invalid"
  | "condition_key_invalid"
  | "condition_field_invalid"
  | "condition_children_invalid"
  | "single_start_required"
  | "complete_required"
  | "start_has_incoming"
  | "complete_has_outgoing"
  | "repeat_iterations_invalid"
  | "subflow_version_required"
  | "connector_reference_required"
  | "unreachable_node"
  | "node_cannot_reach_complete"
  | "cycle_not_allowed";

export interface WorkflowValidationError {
  code: WorkflowValidationErrorCode;
  path: string;
}

export interface WorkflowValidationResult {
  errors: WorkflowValidationError[];
}

export interface WorkflowExecutionNode {
  nodeId: string;
  type: WorkflowNodeType;
  config: Record<string, unknown>;
}

export interface WorkflowExecutionTransition {
  transitionId: string;
  fromNodeId: string;
  toNodeId: string;
  condition?: Record<string, unknown>;
}

export interface WorkflowExecutionPackage {
  workflowId: string;
  schemaVersion: number;
  title: string;
  nodes: WorkflowExecutionNode[];
  transitions: WorkflowExecutionTransition[];
  requiredCapabilities: string[];
  contentSha256: string;
}

export class WorkflowValidationException extends Error {
  constructor(public readonly errors: WorkflowValidationError[]) {
    super("workflow_validation_failed");
    this.name = "WorkflowValidationException";
  }
}

const workflowNodeTypeSet = new Set<string>(WORKFLOW_NODE_TYPES);
const forbiddenConfigKeys = new Set([
  "url",
  "uri",
  "endpoint",
  "baseurl",
  "script",
  "javascript",
  "password",
  "passwd",
  "token",
  "accesstoken",
  "refreshtoken",
  "apikey",
  "apisecret",
  "clientsecret",
  "authorization",
  "credential",
  "credentials",
  "bearer",
]);
const leafConditionOperators = new Set([
  "eq",
  "neq",
  "in",
  "contains",
  "exists",
  "gt",
  "gte",
  "lt",
  "lte",
]);
const groupConditionOperators = new Set(["all", "any"]);
const forbiddenConditionFieldSegments = new Set([
  "__proto__",
  "prototype",
  "constructor",
]);

export function validateWorkflowDraft(
  value: unknown,
): WorkflowValidationResult {
  if (!isRecord(value)) {
    return { errors: [{ code: "invalid_draft", path: "$" }] };
  }

  const errors: WorkflowValidationError[] = [];
  if (typeof value.workflowId !== "string" || value.workflowId.trim() === "") {
    errors.push({ code: "workflow_id_required", path: "$.workflowId" });
  }
  if (
    !Number.isInteger(value.schemaVersion) || Number(value.schemaVersion) <= 0
  ) {
    errors.push({ code: "schema_version_required", path: "$.schemaVersion" });
  }
  if (typeof value.title !== "string" || value.title.trim() === "") {
    errors.push({ code: "title_required", path: "$.title" });
  }

  if (!Array.isArray(value.nodes)) {
    errors.push({ code: "nodes_required", path: "$.nodes" });
    return { errors };
  }

  value.nodes.forEach((node, index) => {
    validateNode(node, `$.nodes[${index}]`, errors);
  });

  if (errors.length > 0) return { errors };
  validateGraph(value, errors);
  return { errors };
}

export async function compileWorkflowDraft(
  value: unknown,
): Promise<WorkflowExecutionPackage> {
  const validation = validateWorkflowDraft(value);
  if (validation.errors.length > 0) {
    throw new WorkflowValidationException(validation.errors);
  }

  const draft = value as Record<string, unknown>;
  const nodes = (draft.nodes as Array<Record<string, unknown>>).map((node) => ({
    nodeId: String(node.nodeId).trim(),
    type: node.type as WorkflowNodeType,
    config: normalizeRecord(isRecord(node.config) ? node.config : {}),
  })).sort((left, right) => left.nodeId.localeCompare(right.nodeId));
  const transitions = (draft.transitions as Array<Record<string, unknown>>)
    .map((transition) => {
      const normalized: WorkflowExecutionTransition = {
        transitionId: String(transition.transitionId).trim(),
        fromNodeId: String(transition.fromNodeId).trim(),
        toNodeId: String(transition.toNodeId).trim(),
      };
      if (isRecord(transition.condition)) {
        normalized.condition = normalizeRecord(transition.condition);
      }
      return normalized;
    })
    .sort(compareTransitions);
  const requiredCapabilities = requiredCapabilitiesFor(nodes);
  const unsigned = {
    workflowId: String(draft.workflowId).trim(),
    schemaVersion: Number(draft.schemaVersion),
    title: String(draft.title).trim(),
    nodes,
    transitions,
    requiredCapabilities,
  };

  return {
    ...unsigned,
    contentSha256: await sha256Hex(canonicalJson(unsigned)),
  };
}

function validateNode(
  value: unknown,
  path: string,
  errors: WorkflowValidationError[],
): void {
  if (!isRecord(value)) {
    errors.push({ code: "invalid_node", path });
    return;
  }

  if (typeof value.nodeId !== "string" || value.nodeId.trim() === "") {
    errors.push({ code: "node_id_required", path: `${path}.nodeId` });
  }

  if (typeof value.type !== "string" || value.type.trim() === "") {
    errors.push({ code: "node_type_required", path: `${path}.type` });
  } else if (!workflowNodeTypeSet.has(value.type)) {
    errors.push({ code: "unsupported_node_type", path: `${path}.type` });
  }

  if (value.config === undefined) {
    validateNodeTypeConfig(value.type, {}, path, errors);
    return;
  }
  if (!isRecord(value.config)) {
    errors.push({ code: "invalid_node_config", path: `${path}.config` });
    return;
  }

  const forbiddenPath = findForbiddenConfigPath(
    value.config,
    `${path}.config`,
  );
  if (forbiddenPath !== null) {
    errors.push({ code: "forbidden_config_key", path: forbiddenPath });
  }

  validateNodeTypeConfig(value.type, value.config, path, errors);
}

function validateNodeTypeConfig(
  nodeType: unknown,
  config: Record<string, unknown>,
  path: string,
  errors: WorkflowValidationError[],
): void {
  if (nodeType === "repeat_group") {
    const maxIterations = config.maxIterations;
    if (
      !Number.isInteger(maxIterations) || Number(maxIterations) < 1 ||
      Number(maxIterations) > 100
    ) {
      errors.push({
        code: "repeat_iterations_invalid",
        path: `${path}.config.maxIterations`,
      });
    }
  }
  if (
    nodeType === "subflow" && !requiredText(config.workflowVersionId)
  ) {
    errors.push({
      code: "subflow_version_required",
      path: `${path}.config.workflowVersionId`,
    });
  }
  if (
    nodeType === "connector_action" &&
    (!requiredText(config.connectorId) ||
      !requiredText(config.actionId))
  ) {
    errors.push({
      code: "connector_reference_required",
      path: `${path}.config`,
    });
  }
}

function validateGraph(
  draft: Record<string, unknown>,
  errors: WorkflowValidationError[],
): void {
  const nodes = draft.nodes as Array<Record<string, unknown>>;
  const nodeIndexes = new Map<string, number>();
  for (let index = 0; index < nodes.length; index++) {
    const nodeId = String(nodes[index].nodeId).trim();
    if (nodeIndexes.has(nodeId)) {
      errors.push({
        code: "duplicate_node_id",
        path: `$.nodes[${index}].nodeId`,
      });
    } else {
      nodeIndexes.set(nodeId, index);
    }
  }
  if (errors.length > 0) return;

  if (!Array.isArray(draft.transitions)) {
    errors.push({ code: "transitions_required", path: "$.transitions" });
    return;
  }
  const transitions = draft.transitions;
  const transitionIds = new Set<string>();
  const validTransitions: Array<Record<string, unknown>> = [];
  transitions.forEach((transition, index) => {
    const path = `$.transitions[${index}]`;
    if (!isRecord(transition)) {
      errors.push({ code: "invalid_transition", path });
      return;
    }
    const transitionId = requiredText(transition.transitionId);
    if (!transitionId) {
      errors.push({
        code: "transition_id_required",
        path: `${path}.transitionId`,
      });
    } else if (transitionIds.has(transitionId)) {
      errors.push({
        code: "duplicate_transition_id",
        path: `${path}.transitionId`,
      });
    } else {
      transitionIds.add(transitionId);
    }
    for (const endpoint of ["fromNodeId", "toNodeId"] as const) {
      const nodeId = requiredText(transition[endpoint]);
      if (!nodeId) {
        errors.push({
          code: "transition_endpoint_required",
          path: `${path}.${endpoint}`,
        });
      } else if (!nodeIndexes.has(nodeId)) {
        errors.push({
          code: "transition_node_not_found",
          path: `${path}.${endpoint}`,
        });
      }
    }
    if (transition.condition !== undefined) {
      validateCondition(transition.condition, `${path}.condition`, errors);
    }
    validTransitions.push(transition);
  });
  if (errors.length > 0) return;

  const startNodes = nodes.filter((node) => node.type === "start");
  if (startNodes.length !== 1) {
    errors.push({ code: "single_start_required", path: "$.nodes" });
  }
  const completeNodes = nodes.filter((node) => node.type === "complete");
  if (completeNodes.length === 0) {
    errors.push({ code: "complete_required", path: "$.nodes" });
  }
  if (errors.length > 0) return;

  validTransitions.forEach((transition, index) => {
    const fromNodeId = String(transition.fromNodeId);
    const toNodeId = String(transition.toNodeId);
    if (nodes[nodeIndexes.get(fromNodeId)!].type === "complete") {
      errors.push({
        code: "complete_has_outgoing",
        path: `$.transitions[${index}].fromNodeId`,
      });
    }
    if (nodes[nodeIndexes.get(toNodeId)!].type === "start") {
      errors.push({
        code: "start_has_incoming",
        path: `$.transitions[${index}].toNodeId`,
      });
    }
  });
  if (errors.length > 0) return;

  const adjacency = adjacencyFor(nodeIndexes.keys(), validTransitions, false);
  const reverse = adjacencyFor(nodeIndexes.keys(), validTransitions, true);
  const reachable = visitFrom([String(startNodes[0].nodeId)], adjacency);
  const canReachComplete = visitFrom(
    completeNodes.map((node) => String(node.nodeId)),
    reverse,
  );
  nodes.forEach((node, index) => {
    const nodeId = String(node.nodeId);
    if (!reachable.has(nodeId)) {
      errors.push({ code: "unreachable_node", path: `$.nodes[${index}]` });
    }
  });
  nodes.forEach((node, index) => {
    const nodeId = String(node.nodeId);
    if (node.type !== "complete" && !canReachComplete.has(nodeId)) {
      errors.push({
        code: "node_cannot_reach_complete",
        path: `$.nodes[${index}]`,
      });
    }
  });
  if (containsCycle(adjacency)) {
    errors.push({ code: "cycle_not_allowed", path: "$.transitions" });
  }
}

function validateCondition(
  value: unknown,
  path: string,
  errors: WorkflowValidationError[],
): void {
  if (!isRecord(value)) {
    errors.push({ code: "condition_invalid", path });
    return;
  }

  const operator = requiredText(value.operator);
  if (operator === null) {
    errors.push({
      code: "condition_operator_invalid",
      path: `${path}.operator`,
    });
    validateConditionKeys(value, new Set(["operator"]), path, errors);
    return;
  }

  if (leafConditionOperators.has(operator)) {
    const allowedKeys = operator === "exists"
      ? new Set(["operator", "field"])
      : new Set(["operator", "field", "value"]);
    validateConditionKeys(value, allowedKeys, path, errors);
    validateConditionField(value.field, `${path}.field`, errors);
    if (operator !== "exists") {
      if (!Object.prototype.hasOwnProperty.call(value, "value")) {
        errors.push({ code: "condition_invalid", path: `${path}.value` });
      } else if (!isConditionValue(value.value)) {
        errors.push({ code: "condition_invalid", path: `${path}.value` });
      }
    }
    return;
  }

  if (groupConditionOperators.has(operator)) {
    validateConditionKeys(
      value,
      new Set(["operator", "conditions"]),
      path,
      errors,
    );
    if (!Array.isArray(value.conditions) || value.conditions.length === 0) {
      errors.push({
        code: "condition_children_invalid",
        path: `${path}.conditions`,
      });
      return;
    }
    value.conditions.forEach((condition, index) => {
      validateCondition(condition, `${path}.conditions[${index}]`, errors);
    });
    return;
  }

  if (operator === "not") {
    validateConditionKeys(
      value,
      new Set(["operator", "condition"]),
      path,
      errors,
    );
    if (!isRecord(value.condition)) {
      errors.push({
        code: "condition_children_invalid",
        path: `${path}.condition`,
      });
      return;
    }
    validateCondition(value.condition, `${path}.condition`, errors);
    return;
  }

  errors.push({
    code: "condition_operator_invalid",
    path: `${path}.operator`,
  });
  validateConditionKeys(value, new Set(["operator"]), path, errors);
}

function validateConditionKeys(
  value: Record<string, unknown>,
  allowedKeys: Set<string>,
  path: string,
  errors: WorkflowValidationError[],
): void {
  Object.keys(value).sort().forEach((key) => {
    if (!allowedKeys.has(key)) {
      errors.push({
        code: "condition_key_invalid",
        path: appendPath(path, key),
      });
    }
  });
}

function validateConditionField(
  value: unknown,
  path: string,
  errors: WorkflowValidationError[],
): void {
  const field = requiredText(value);
  if (
    field === null || field.length > 128 ||
    !/^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$/.test(field) ||
    field.split(".").some((segment) =>
      forbiddenConditionFieldSegments.has(segment)
    )
  ) {
    errors.push({ code: "condition_field_invalid", path });
  }
}

function isConditionValue(value: unknown): boolean {
  if (
    value === null || typeof value === "string" || typeof value === "boolean"
  ) {
    return true;
  }
  if (typeof value === "number") return Number.isFinite(value);
  return Array.isArray(value) &&
    value.every((item) =>
      item === null || typeof item === "string" || typeof item === "boolean" ||
      (typeof item === "number" && Number.isFinite(item))
    );
}

function adjacencyFor(
  nodeIds: Iterable<string>,
  transitions: Array<Record<string, unknown>>,
  reverse: boolean,
): Map<string, string[]> {
  const adjacency = new Map<string, string[]>();
  for (const nodeId of nodeIds) adjacency.set(nodeId, []);
  for (const transition of transitions) {
    const from = String(reverse ? transition.toNodeId : transition.fromNodeId);
    const to = String(reverse ? transition.fromNodeId : transition.toNodeId);
    adjacency.get(from)?.push(to);
  }
  return adjacency;
}

function visitFrom(
  starts: string[],
  adjacency: Map<string, string[]>,
): Set<string> {
  const visited = new Set<string>();
  const pending = [...starts];
  while (pending.length > 0) {
    const nodeId = pending.pop()!;
    if (visited.has(nodeId)) continue;
    visited.add(nodeId);
    for (const next of adjacency.get(nodeId) ?? []) pending.push(next);
  }
  return visited;
}

function containsCycle(adjacency: Map<string, string[]>): boolean {
  const indegree = new Map<string, number>();
  for (const nodeId of adjacency.keys()) indegree.set(nodeId, 0);
  for (const targets of adjacency.values()) {
    for (const target of targets) {
      indegree.set(target, (indegree.get(target) ?? 0) + 1);
    }
  }
  const pending = [...indegree.entries()].filter(([, count]) => count === 0)
    .map(([nodeId]) => nodeId);
  let visited = 0;
  while (pending.length > 0) {
    const nodeId = pending.pop()!;
    visited++;
    for (const target of adjacency.get(nodeId) ?? []) {
      const next = (indegree.get(target) ?? 0) - 1;
      indegree.set(target, next);
      if (next === 0) pending.push(target);
    }
  }
  return visited !== adjacency.size;
}

function findForbiddenConfigPath(value: unknown, path: string): string | null {
  if (Array.isArray(value)) {
    for (let index = 0; index < value.length; index++) {
      const result = findForbiddenConfigPath(value[index], `${path}[${index}]`);
      if (result !== null) return result;
    }
    return null;
  }
  if (!isRecord(value)) return null;

  for (const key of Object.keys(value).sort()) {
    const keyPath = appendPath(path, key);
    if (forbiddenConfigKeys.has(normalizeConfigKey(key))) return keyPath;
    const result = findForbiddenConfigPath(value[key], keyPath);
    if (result !== null) return result;
  }
  return null;
}

function normalizeConfigKey(key: string): string {
  return key.replace(/[_\-.\s]/g, "").toLowerCase();
}

function normalizeRecord(
  value: Record<string, unknown>,
): Record<string, unknown> {
  return Object.fromEntries(
    Object.keys(value).sort().map((key) => [key, normalizeValue(value[key])]),
  );
}

function normalizeValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(normalizeValue);
  return isRecord(value) ? normalizeRecord(value) : value;
}

function canonicalJson(value: unknown): string {
  return JSON.stringify(normalizeValue(value));
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return [...new Uint8Array(digest)].map((byte) =>
    byte.toString(16).padStart(2, "0")
  ).join("");
}

function requiredCapabilitiesFor(nodes: WorkflowExecutionNode[]): string[] {
  const capabilities = new Set<string>(["workflow.runtime.v1"]);
  const byType: Partial<Record<WorkflowNodeType, string>> = {
    photo_capture: "camera.photo",
    video_capture: "camera.video",
    voice_input: "audio.voice_input",
    ai_assist: "ai.execution_context",
    expert_call: "expert.video",
    connector_action: "connector.gateway",
  };
  nodes.forEach((node) => {
    const capability = byType[node.type];
    if (capability) capabilities.add(capability);
  });
  return [...capabilities].sort();
}

function compareTransitions(
  left: WorkflowExecutionTransition,
  right: WorkflowExecutionTransition,
): number {
  return left.fromNodeId.localeCompare(right.fromNodeId) ||
    left.toNodeId.localeCompare(right.toNodeId) ||
    left.transitionId.localeCompare(right.transitionId);
}

function requiredText(value: unknown): string | null {
  return typeof value === "string" && value.trim() !== "" ? value.trim() : null;
}

function appendPath(path: string, key: string): string {
  return /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(key)
    ? `${path}.${key}`
    : `${path}[${JSON.stringify(key)}]`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
