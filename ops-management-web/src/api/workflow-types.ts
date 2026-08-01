export type WorkflowNodeType =
  | "start"
  | "instruction"
  | "choice"
  | "form"
  | "photo_capture"
  | "video_capture"
  | "voice_input"
  | "ai_assist"
  | "expert_call"
  | "confirmation"
  | "condition"
  | "repeat_group"
  | "subflow"
  | "connector_action"
  | "complete";

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
  values?: string[];
  errorCode?: string;
};

export type WorkflowNodeCatalogItem = {
  type: WorkflowNodeType;
  label: string;
  category: "flow" | "content" | "evidence" | "input" | "assist" | "integration";
  pageTemplate: WorkflowPageTemplate;
  requiredCapability: string | null;
  fields: WorkflowConfigField[];
};

export type WorkflowCatalog = {
  schemaVersion: number;
  pageTemplates: WorkflowPageTemplate[];
  riskLevels: Array<"low" | "medium" | "high" | "critical">;
  offlinePolicies: Array<"allowed" | "blocked" | "server_required">;
  nodes: WorkflowNodeCatalogItem[];
};

export type FieldAppEntryMode = "independent" | "work_order" | "both";
export type FieldAppStatus = "draft" | "review_pending" | "published" | "deprecated" | "archived";

export type FieldApp = {
  id: string;
  app_key: string;
  name: string;
  description: string;
  icon_key: string | null;
  entry_mode: FieldAppEntryMode;
  status: FieldAppStatus;
  default_workflow_definition_id?: string | null;
  created_at: string;
  updated_at: string;
};

export type FieldAppCreateCommand = {
  appKey: string;
  name: string;
  description: string;
  iconKey: string | null;
  entryMode: FieldAppEntryMode;
};

export type WorkflowDefinitionStatus = "draft" | "validating" | "review_pending" | "published" | "deprecated" | "archived";

export type WorkflowDraftNode = {
  nodeId: string;
  type: WorkflowNodeType;
  config: Record<string, unknown>;
  position?: { x: number; y: number };
};

export type WorkflowDraftTransition = {
  transitionId: string;
  fromNodeId: string;
  toNodeId: string;
  condition?: Record<string, unknown>;
};

export type WorkflowDraft = {
  workflowId: string;
  schemaVersion: number;
  title: string;
  nodes: WorkflowDraftNode[];
  transitions: WorkflowDraftTransition[];
};

export type WorkflowDefinition = {
  id: string;
  field_app_id: string;
  workflow_key: string;
  title: string;
  description: string;
  status: WorkflowDefinitionStatus;
  schema_version: number;
  latest_version_number: number;
  draft_graph?: WorkflowDraft;
  created_at: string;
  updated_at: string;
};

export type WorkflowCreateCommand = {
  workflowKey: string;
  title: string;
  description: string;
  schemaVersion: number;
};

export type WorkflowValidationError = { code: string; path: string };
export type WorkflowValidationResult = {
  valid: boolean;
  validationErrors: WorkflowValidationError[];
};

export type WorkflowVersionStatus = "published" | "deprecated" | "revoked" | "archived";
export type WorkflowVersion = {
  id: string;
  workflow_definition_id: string;
  version_number: number;
  status: WorkflowVersionStatus;
  schema_version: number;
  execution_package?: Record<string, unknown>;
  content_sha256: string;
  package_signature?: string;
  signature_key_id: string;
  required_capabilities: string[];
  min_app_version_code: number;
  published_by: string;
  published_at: string;
  status_changed_at: string;
};

export type WorkflowPublishCommand = {
  reason: string;
  minAppVersionCode: number;
};

export type WorkOrderStatus = "received" | "accepted" | "in_progress" | "completed" | "closed" | "cancelled";
export type WorkflowBindingMode = "required" | "optional" | "none";
export type WorkflowBindingSource = "manual" | "trusted_external" | "project" | "asset_order_type" | "organization_default";

export type WorkOrder = {
  id: string | null;
  sourceSystem: string | null;
  externalWorkOrderId: string | null;
  externalWorkflowCode: string | null;
  projectId: string | null;
  assignedProfileId: string | null;
  title: string | null;
  customerId: string | null;
  workOrderType: string | null;
  assetId: string | null;
  assetCategory: string | null;
  assetBrand: string | null;
  assetModel: string | null;
  faultType: string | null;
  priority: string | null;
  riskLevel: string | null;
  tags: string[];
  status: WorkOrderStatus | null;
  bindingMode: WorkflowBindingMode | null;
  bindingStatus: "unresolved" | "resolved" | "conflict" | "unsupported" | null;
  boundWorkflowVersionId: string | null;
  dueAt: string | null;
  receivedAt: string | null;
  updatedAt: string | null;
};

export type WorkflowBindingCondition = {
  field: string;
  operator: "eq" | "in" | "contains";
  value: string | string[];
};

export type WorkflowBindingRule = {
  id: string | null;
  ruleKey: string | null;
  source: WorkflowBindingSource | null;
  mode: WorkflowBindingMode | null;
  workflowVersionId: string | null;
  matchConditions: WorkflowBindingCondition[];
  enabled: boolean;
  activeFrom: string | null;
  activeUntil: string | null;
  reason: string | null;
  version: number | null;
  workflowVersionStatus: WorkflowVersionStatus | null;
  createdAt: string | null;
  updatedAt: string | null;
};

export type WorkflowBindingRuleCommand = {
  ruleKey: string;
  source: WorkflowBindingSource;
  mode: WorkflowBindingMode;
  workflowVersionId: string | null;
  matchConditions: WorkflowBindingCondition[];
  enabled: boolean;
  activeFrom: string | null;
  activeUntil: string | null;
  reason: string;
  idempotencyKey: string;
};

export type WorkflowBindingRuleUpdateCommand = WorkflowBindingRuleCommand & {
  expectedVersion: number;
};

export type WorkOrderWorkflowResolutionCommand = {
  reason: string;
  idempotencyKey: string;
  assignedProfileId: string | null;
  assignedDeviceId: string | null;
};

export type WorkOrderWorkflowResolution = {
  kind: "assigned" | "none" | "conflict";
  workOrderId: string;
  assignmentId: string | null;
  mode: WorkflowBindingMode;
  workflowVersionId: string | null;
  bindingStatus: "resolved" | "conflict";
};
