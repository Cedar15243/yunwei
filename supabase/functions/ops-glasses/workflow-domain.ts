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
  | "forbidden_config_key";

export interface WorkflowValidationError {
  code: WorkflowValidationErrorCode;
  path: string;
}

export interface WorkflowValidationResult {
  errors: WorkflowValidationError[];
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
  return { errors };
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

  if (value.config === undefined) return;
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

function appendPath(path: string, key: string): string {
  return /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(key)
    ? `${path}.${key}`
    : `${path}[${JSON.stringify(key)}]`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
