export type WorkflowBindingMode = "required" | "optional" | "none";

export type WorkflowBindingSource =
  | "manual"
  | "trusted_external"
  | "project"
  | "asset_order_type"
  | "organization_default";

export type BindingField =
  | "workOrderId"
  | "externalSystem"
  | "externalWorkflowCode"
  | "customerId"
  | "projectId"
  | "workOrderType"
  | "assetCategory"
  | "assetBrand"
  | "assetModel"
  | "faultType"
  | "priority"
  | "riskLevel"
  | "tags";

export type BindingOperator = "eq" | "in" | "contains";

export interface BindingCondition {
  field: BindingField;
  operator: BindingOperator;
  value: string | string[];
}

export interface WorkOrderFacts {
  organizationId: string;
  workOrderId: string;
  externalSystem?: string;
  externalWorkflowCode?: string;
  customerId?: string;
  projectId?: string;
  workOrderType?: string;
  assetCategory?: string;
  assetBrand?: string;
  assetModel?: string;
  faultType?: string;
  priority?: string;
  riskLevel?: string;
  tags?: string[];
}

export interface BindingCandidate {
  candidateId: string;
  organizationId: string;
  source: WorkflowBindingSource;
  mode: WorkflowBindingMode;
  workflowVersionId?: string;
  workflowVersionStatus:
    | "draft"
    | "review_pending"
    | "published"
    | "deprecated"
    | "archived";
  enabled: boolean;
  conditions: BindingCondition[];
  activeFrom?: string;
  activeUntil?: string;
}

export type BindingResolution =
  | {
    kind: "assigned";
    source: WorkflowBindingSource;
    mode: "required" | "optional";
    workflowVersionId: string;
    candidateId: string;
  }
  | {
    kind: "none";
    mode: "none";
    source?: WorkflowBindingSource;
    candidateId?: string;
  }
  | { kind: "conflict"; candidateIds: string[] };

export interface BindingResolutionOptions {
  now?: Date;
}

const sourceRank: Record<WorkflowBindingSource, number> = {
  manual: 500,
  trusted_external: 400,
  project: 300,
  asset_order_type: 200,
  organization_default: 100,
};

const bindingSources = new Set(Object.keys(sourceRank));
const bindingModes = new Set(["required", "optional", "none"]);
const bindingFields = new Set<BindingField>([
  "workOrderId",
  "externalSystem",
  "externalWorkflowCode",
  "customerId",
  "projectId",
  "workOrderType",
  "assetCategory",
  "assetBrand",
  "assetModel",
  "faultType",
  "priority",
  "riskLevel",
  "tags",
]);
const assetOrderFields = new Set<BindingField>([
  "workOrderType",
  "assetCategory",
  "assetBrand",
  "assetModel",
  "faultType",
  "priority",
  "riskLevel",
  "tags",
]);

export function resolveWorkflowBinding(
  order: WorkOrderFacts,
  candidates: BindingCandidate[],
  options: BindingResolutionOptions = {},
): BindingResolution {
  const now = options.now?.getTime() ?? Date.now();
  const matches = candidates.filter((candidate) =>
    candidateIsUsable(order, candidate, now) &&
    candidate.conditions.every((condition) =>
      conditionMatches(order, condition)
    )
  ).sort(compareCandidates);

  if (matches.length === 0) return { kind: "none", mode: "none" };

  const top = matches[0];
  const tied = matches.filter((candidate) =>
    sourceRank[candidate.source] === sourceRank[top.source] &&
    candidate.conditions.length === top.conditions.length
  );
  const outcomes = new Set(tied.map(candidateOutcome));
  if (outcomes.size > 1) {
    return {
      kind: "conflict",
      candidateIds: tied.map((candidate) => candidate.candidateId).sort(),
    };
  }

  const selected =
    tied.toSorted((left, right) =>
      left.candidateId.localeCompare(right.candidateId)
    )[0];
  if (selected.mode === "none") {
    return {
      kind: "none",
      mode: "none",
      source: selected.source,
      candidateId: selected.candidateId,
    };
  }
  return {
    kind: "assigned",
    source: selected.source,
    mode: selected.mode,
    workflowVersionId: selected.workflowVersionId!,
    candidateId: selected.candidateId,
  };
}

function candidateIsUsable(
  order: WorkOrderFacts,
  candidate: BindingCandidate,
  now: number,
): boolean {
  if (!isRecord(candidate)) return false;
  if (
    !requiredText(order.organizationId) || !requiredText(order.workOrderId) ||
    !requiredText(candidate.candidateId) ||
    candidate.organizationId !== order.organizationId ||
    !bindingSources.has(candidate.source) ||
    !bindingModes.has(candidate.mode) ||
    candidate.enabled !== true ||
    candidate.workflowVersionStatus !== "published" ||
    !Array.isArray(candidate.conditions) ||
    !candidate.conditions.every(conditionIsValid) ||
    hasDuplicateConditions(candidate.conditions) ||
    !sourcePredicatesAreValid(candidate.source, candidate.conditions) ||
    !isActiveAt(candidate, now)
  ) {
    return false;
  }
  if (candidate.mode === "none") {
    return candidate.workflowVersionId === undefined;
  }
  return requiredText(candidate.workflowVersionId) !== null;
}

function conditionIsValid(condition: unknown): condition is BindingCondition {
  if (
    !isRecord(condition) ||
    !bindingFields.has(condition.field as BindingField)
  ) {
    return false;
  }
  if (condition.operator === "contains") {
    return condition.field === "tags" && requiredText(condition.value) !== null;
  }
  if (condition.operator === "eq") {
    return condition.field !== "tags" && requiredText(condition.value) !== null;
  }
  if (condition.operator === "in") {
    return condition.field !== "tags" && Array.isArray(condition.value) &&
      condition.value.length > 0 && condition.value.every((value) =>
        requiredText(value) !== null
      );
  }
  return false;
}

function sourcePredicatesAreValid(
  source: WorkflowBindingSource,
  conditions: BindingCondition[],
): boolean {
  const fields = new Set(conditions.map((condition) => condition.field));
  if (source === "manual") {
    return conditions.some((condition) =>
      condition.field === "workOrderId" && condition.operator === "eq"
    );
  }
  if (source === "trusted_external") {
    return conditions.some((condition) =>
      condition.field === "externalSystem" && condition.operator === "eq"
    ) && conditions.some((condition) =>
      condition.field === "externalWorkflowCode" &&
      condition.operator === "eq"
    );
  }
  if (source === "project") {
    return fields.has("customerId") || fields.has("projectId");
  }
  if (source === "asset_order_type") {
    return conditions.length > 0 &&
      conditions.every((condition) => assetOrderFields.has(condition.field));
  }
  return conditions.length === 0;
}

function hasDuplicateConditions(conditions: BindingCondition[]): boolean {
  const identities = new Set<string>();
  for (const condition of conditions) {
    const identity = JSON.stringify([
      condition.field,
      condition.operator,
      condition.value,
    ]);
    if (identities.has(identity)) return true;
    identities.add(identity);
  }
  return false;
}

function isActiveAt(candidate: BindingCandidate, now: number): boolean {
  if (!Number.isFinite(now)) return false;
  if (candidate.activeFrom !== undefined) {
    const activeFrom = Date.parse(candidate.activeFrom);
    if (!Number.isFinite(activeFrom) || activeFrom > now) return false;
  }
  if (candidate.activeUntil !== undefined) {
    const activeUntil = Date.parse(candidate.activeUntil);
    if (!Number.isFinite(activeUntil) || activeUntil <= now) return false;
  }
  return true;
}

function conditionMatches(
  order: WorkOrderFacts,
  condition: BindingCondition,
): boolean {
  if (condition.field === "tags") {
    return condition.operator === "contains" &&
      (order.tags ?? []).includes(condition.value as string);
  }
  const fact = order[condition.field];
  if (typeof fact !== "string") return false;
  if (condition.operator === "eq") return fact === condition.value;
  if (condition.operator === "in") {
    return (condition.value as string[]).includes(fact);
  }
  return false;
}

function compareCandidates(
  left: BindingCandidate,
  right: BindingCandidate,
): number {
  return sourceRank[right.source] - sourceRank[left.source] ||
    right.conditions.length - left.conditions.length ||
    left.candidateId.localeCompare(right.candidateId);
}

function candidateOutcome(candidate: BindingCandidate): string {
  return `${candidate.mode}:${candidate.workflowVersionId ?? ""}`;
}

function requiredText(value: unknown): string | null {
  return typeof value === "string" && value.trim() !== "" ? value.trim() : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
