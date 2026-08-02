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

export type WorkflowPageTemplate =
  | "instruction"
  | "evidence_capture"
  | "form"
  | "choice"
  | "conversation"
  | "confirmation"
  | "completion"
  | "none";

export type WorkflowConfigFieldKind =
  | "text"
  | "textarea"
  | "identifier"
  | "boolean"
  | "integer"
  | "select"
  | "select_list"
  | "uuid_list"
  | "options"
  | "fields"
  | "mappings";

export type WorkflowConfigField = {
  key: string;
  label: string;
  kind: WorkflowConfigFieldKind;
  required?: boolean;
  min?: number;
  max?: number;
  maxLength?: number;
  values?: readonly string[];
  errorCode?: WorkflowNodeConfigIssue["code"];
};

export type WorkflowNodeCatalogItem = {
  type: WorkflowNodeType;
  label: string;
  category:
    | "flow"
    | "content"
    | "evidence"
    | "input"
    | "assist"
    | "integration";
  pageTemplate: WorkflowPageTemplate;
  requiredCapability: string | null;
  fields: readonly WorkflowConfigField[];
};

export type WorkflowNodeConfigIssue = {
  code:
    | "node_config_key_invalid"
    | "node_config_value_invalid"
    | "repeat_iterations_invalid";
  path: string;
};

const riskLevels = ["low", "medium", "high", "critical"] as const;
const offlinePolicies = ["allowed", "blocked", "server_required"] as const;
const allowedActions = [
  "next",
  "back",
  "home",
  "capture",
  "retake",
  "confirm",
  "cancel",
  "retry",
  "expert",
  "complete",
  "skip",
] as const;
const formFieldTypes = [
  "text",
  "number",
  "boolean",
  "single_choice",
  "multi_choice",
] as const;

const commonFields: readonly WorkflowConfigField[] = [
  { key: "title", label: "页面标题", kind: "text", maxLength: 160 },
  {
    key: "description",
    label: "操作说明",
    kind: "textarea",
    maxLength: 4000,
  },
  {
    key: "voicePrompt",
    label: "语音提示",
    kind: "text",
    maxLength: 500,
  },
  {
    key: "riskLevel",
    label: "风险等级",
    kind: "select",
    values: riskLevels,
  },
  {
    key: "offlinePolicy",
    label: "离线策略",
    kind: "select",
    values: offlinePolicies,
  },
  {
    key: "allowedActions",
    label: "允许操作",
    kind: "select_list",
    values: allowedActions,
    max: 11,
  },
];

function fields(
  ...specific: readonly WorkflowConfigField[]
): readonly WorkflowConfigField[] {
  return [...commonFields, ...specific];
}

export const WORKFLOW_NODE_CATALOG: readonly WorkflowNodeCatalogItem[] = [
  {
    type: "start",
    label: "开始",
    category: "flow",
    pageTemplate: "none",
    requiredCapability: "workflow.runtime.v1",
    fields: fields(),
  },
  {
    type: "instruction",
    label: "操作说明",
    category: "content",
    pageTemplate: "instruction",
    requiredCapability: null,
    fields: fields(
      {
        key: "riskNotice",
        label: "风险提示",
        kind: "textarea",
        maxLength: 2000,
      },
      {
        key: "referenceAssetIds",
        label: "参考资料",
        kind: "uuid_list",
        max: 20,
      },
    ),
  },
  {
    type: "choice",
    label: "选择",
    category: "input",
    pageTemplate: "choice",
    requiredCapability: null,
    fields: fields(
      { key: "fieldKey", label: "结果字段", kind: "identifier" },
      { key: "multiple", label: "允许多选", kind: "boolean" },
      { key: "options", label: "选项", kind: "options", max: 50 },
    ),
  },
  {
    type: "form",
    label: "表单",
    category: "input",
    pageTemplate: "form",
    requiredCapability: null,
    fields: fields({
      key: "fields",
      label: "表单字段",
      kind: "fields",
      max: 30,
    }),
  },
  {
    type: "photo_capture",
    label: "拍照取证",
    category: "evidence",
    pageTemplate: "evidence_capture",
    requiredCapability: "camera.photo",
    fields: fields(
      { key: "evidenceKey", label: "证据字段", kind: "identifier" },
      {
        key: "minCount",
        label: "最少照片数",
        kind: "integer",
        min: 1,
        max: 20,
      },
      { key: "allowRetake", label: "允许重拍", kind: "boolean" },
      {
        key: "confirmationRequired",
        label: "拍摄后确认",
        kind: "boolean",
      },
      {
        key: "referenceAssetIds",
        label: "参考图片",
        kind: "uuid_list",
        max: 20,
      },
    ),
  },
  {
    type: "video_capture",
    label: "录像取证",
    category: "evidence",
    pageTemplate: "evidence_capture",
    requiredCapability: "camera.video",
    fields: fields(
      { key: "evidenceKey", label: "证据字段", kind: "identifier" },
      {
        key: "minCount",
        label: "最少视频数",
        kind: "integer",
        min: 1,
        max: 10,
      },
      {
        key: "minDurationSeconds",
        label: "最短时长",
        kind: "integer",
        min: 1,
        max: 15,
      },
      {
        key: "maxDurationSeconds",
        label: "最长时长",
        kind: "integer",
        min: 1,
        max: 15,
      },
      { key: "allowRetake", label: "允许重录", kind: "boolean" },
    ),
  },
  {
    type: "voice_input",
    label: "语音填写",
    category: "input",
    pageTemplate: "form",
    requiredCapability: "audio.voice_input",
    fields: fields(
      { key: "fieldKey", label: "结果字段", kind: "identifier" },
      {
        key: "maxDurationSeconds",
        label: "最长时长",
        kind: "integer",
        min: 1,
        max: 300,
      },
      { key: "required", label: "必须填写", kind: "boolean" },
    ),
  },
  {
    type: "ai_assist",
    label: "AI 协助",
    category: "assist",
    pageTemplate: "conversation",
    requiredCapability: "ai.execution_context",
    fields: fields(
      {
        key: "skillVersionId",
        label: "Skill版本",
        kind: "identifier",
      },
      {
        key: "knowledgeRequired",
        label: "知识库必须可用",
        kind: "boolean",
      },
      {
        key: "outputFields",
        label: "输出字段",
        kind: "fields",
        max: 20,
      },
    ),
  },
  {
    type: "expert_call",
    label: "专家协同",
    category: "assist",
    pageTemplate: "conversation",
    requiredCapability: "expert.video",
    fields: fields(
      { key: "reason", label: "呼叫原因", kind: "text", maxLength: 500 },
      {
        key: "timeoutSeconds",
        label: "等待时限",
        kind: "integer",
        min: 10,
        max: 1800,
      },
    ),
  },
  {
    type: "confirmation",
    label: "人工确认",
    category: "input",
    pageTemplate: "confirmation",
    requiredCapability: null,
    fields: fields(
      {
        key: "confirmationText",
        label: "确认内容",
        kind: "textarea",
        maxLength: 2000,
      },
      {
        key: "requiredPhrase",
        label: "确认词",
        kind: "text",
        maxLength: 120,
      },
    ),
  },
  {
    type: "condition",
    label: "条件分支",
    category: "flow",
    pageTemplate: "none",
    requiredCapability: null,
    fields: fields(),
  },
  {
    type: "repeat_group",
    label: "受控重复",
    category: "flow",
    pageTemplate: "none",
    requiredCapability: null,
    fields: fields({
      key: "maxIterations",
      label: "最大次数",
      kind: "integer",
      required: true,
      min: 1,
      max: 100,
      errorCode: "repeat_iterations_invalid",
    }),
  },
  {
    type: "subflow",
    label: "子流程",
    category: "flow",
    pageTemplate: "none",
    requiredCapability: null,
    fields: fields({
      key: "workflowVersionId",
      label: "流程版本",
      kind: "identifier",
    }),
  },
  {
    type: "connector_action",
    label: "连接器操作",
    category: "integration",
    pageTemplate: "confirmation",
    requiredCapability: "connector.gateway",
    fields: fields(
      { key: "connectorId", label: "连接器", kind: "identifier" },
      { key: "actionId", label: "受控操作", kind: "identifier" },
      {
        key: "parameterMappings",
        label: "参数映射",
        kind: "mappings",
        max: 50,
      },
      {
        key: "confirmationText",
        label: "确认内容",
        kind: "textarea",
        maxLength: 2000,
      },
    ),
  },
  {
    type: "complete",
    label: "完成",
    category: "flow",
    pageTemplate: "completion",
    requiredCapability: null,
    fields: fields(
      { key: "requireSync", label: "要求同步完成", kind: "boolean" },
      {
        key: "requireConfirmation",
        label: "要求人工确认",
        kind: "boolean",
      },
      {
        key: "summaryFields",
        label: "摘要字段",
        kind: "select_list",
        max: 30,
      },
    ),
  },
];

const catalogByType = new Map(
  WORKFLOW_NODE_CATALOG.map((item) => [item.type, item]),
);

export const publicWorkflowCatalog = {
  schemaVersion: 1,
  pageTemplates: [
    "instruction",
    "evidence_capture",
    "form",
    "choice",
    "conversation",
    "confirmation",
    "completion",
    "none",
  ] as const,
  riskLevels,
  offlinePolicies,
  nodes: WORKFLOW_NODE_CATALOG,
};

export function validateWorkflowNodeConfig(
  nodeType: unknown,
  config: Record<string, unknown>,
  path: string,
): WorkflowNodeConfigIssue[] {
  if (typeof nodeType !== "string") return [];
  const catalog = catalogByType.get(nodeType as WorkflowNodeType);
  if (!catalog) return [];
  const fieldByKey = new Map(catalog.fields.map((field) => [field.key, field]));
  const issues: WorkflowNodeConfigIssue[] = [];

  for (const key of Object.keys(config)) {
    const field = fieldByKey.get(key);
    if (!field) {
      issues.push({
        code: "node_config_key_invalid",
        path: `${path}.config.${key}`,
      });
      continue;
    }
    if (!validFieldValue(field, config[key])) {
      issues.push({
        code: field.errorCode ?? "node_config_value_invalid",
        path: `${path}.config.${key}`,
      });
    }
  }

  for (const field of catalog.fields) {
    if (field.required && config[field.key] === undefined) {
      issues.push({
        code: field.errorCode ?? "node_config_value_invalid",
        path: `${path}.config.${field.key}`,
      });
    }
  }
  return issues;
}

function validFieldValue(field: WorkflowConfigField, value: unknown): boolean {
  if (field.kind === "boolean") return typeof value === "boolean";
  if (field.kind === "integer") {
    return Number.isInteger(value) &&
      (field.min === undefined || Number(value) >= field.min) &&
      (field.max === undefined || Number(value) <= field.max);
  }
  if (field.kind === "select") {
    return typeof value === "string" && field.values?.includes(value) === true;
  }
  if (field.kind === "select_list") {
    return validStringList(value, field.max, field.values);
  }
  if (field.kind === "uuid_list") {
    return Array.isArray(value) &&
      value.length <= (field.max ?? 100) &&
      value.every((item) => typeof item === "string" && isUuid(item)) &&
      new Set(value).size === value.length;
  }
  if (field.kind === "options") return validOptions(value, field.max);
  if (field.kind === "fields") return validFields(value, field.max);
  if (field.kind === "mappings") return validMappings(value, field.max);
  if (typeof value !== "string") return false;
  const text = value.trim();
  if (!text || text.length > (field.maxLength ?? 160)) return false;
  if (containsUnsafeText(text)) return false;
  return field.kind !== "identifier" ||
    /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$/.test(text);
}

function validStringList(
  value: unknown,
  max = 100,
  allowed?: readonly string[],
): boolean {
  return Array.isArray(value) && value.length <= max &&
    value.every((item) =>
      typeof item === "string" && item.trim().length > 0 &&
      item.length <= 160 && !containsUnsafeText(item) &&
      (!allowed || allowed.includes(item))
    ) && new Set(value).size === value.length;
}

function validOptions(value: unknown, max = 50): boolean {
  if (!Array.isArray(value) || value.length < 1 || value.length > max) {
    return false;
  }
  const values = new Set<string>();
  return value.every((item) => {
    if (!isRecord(item) || !onlyKeys(item, ["value", "label"])) return false;
    const optionValue = boundedPlainText(item.value, 120);
    const label = boundedPlainText(item.label, 240);
    if (!optionValue || !label || values.has(optionValue)) return false;
    values.add(optionValue);
    return true;
  });
}

function validFields(value: unknown, max = 30): boolean {
  if (!Array.isArray(value) || value.length < 1 || value.length > max) {
    return false;
  }
  const keys = new Set<string>();
  return value.every((item) => {
    if (
      !isRecord(item) ||
      !onlyKeys(item, [
        "key",
        "label",
        "type",
        "required",
        "options",
        "min",
        "max",
        "unit",
      ])
    ) return false;
    const key = boundedIdentifier(item.key);
    const label = boundedPlainText(item.label, 240);
    if (
      !key || !label || keys.has(key) ||
      typeof item.type !== "string" || !formFieldTypes.includes(
        item.type as typeof formFieldTypes[number],
      ) ||
      (item.required !== undefined && typeof item.required !== "boolean") ||
      (item.unit !== undefined && !boundedPlainText(item.unit, 40)) ||
      !optionalFiniteNumber(item.min) || !optionalFiniteNumber(item.max) ||
      (typeof item.min === "number" && typeof item.max === "number" &&
        item.min > item.max)
    ) return false;
    if (
      ["single_choice", "multi_choice"].includes(item.type) &&
      !validOptions(item.options, 50)
    ) return false;
    if (
      !["single_choice", "multi_choice"].includes(item.type) &&
      item.options !== undefined
    ) return false;
    keys.add(key);
    return true;
  });
}

function validMappings(value: unknown, max = 50): boolean {
  return Array.isArray(value) && value.length <= max &&
    value.every((item) =>
      isRecord(item) && onlyKeys(item, ["targetField", "sourceField"]) &&
      Boolean(boundedIdentifier(item.targetField)) &&
      Boolean(boundedIdentifier(item.sourceField))
    );
}

function onlyKeys(
  value: Record<string, unknown>,
  allowed: readonly string[],
): boolean {
  return Object.keys(value).every((key) => allowed.includes(key));
}

function optionalFiniteNumber(value: unknown): boolean {
  return value === undefined ||
    (typeof value === "number" && Number.isFinite(value));
}

function boundedIdentifier(value: unknown): string | null {
  return typeof value === "string" &&
      /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$/.test(value.trim())
    ? value.trim()
    : null;
}

function boundedPlainText(value: unknown, maxLength: number): string | null {
  if (typeof value !== "string") return null;
  const text = value.trim();
  return text && text.length <= maxLength && !containsUnsafeText(text)
    ? text
    : null;
}

function containsUnsafeText(value: string): boolean {
  return /<\s*\/?\s*[a-z][^>]*>/i.test(value) ||
    /(?:https?|wss?|file):\/\//i.test(value) ||
    /(?:data|javascript):/i.test(value);
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    .test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
