import {
  compileWorkflowDraft,
  validateWorkflowDraft,
  type WorkflowExecutionPackage,
} from "./workflow-domain.ts";
import {
  type BindingCandidate,
  type BindingResolution,
  resolveWorkflowBinding,
  type WorkOrderFacts,
} from "./workflow-binding.ts";
import type { WorkflowPackageSigner } from "./workflow-signing.ts";
import { publicWorkflowCatalog } from "./workflow-catalog.ts";

export type WorkflowManagementIdentity = {
  id: string;
  organizationId: string;
  role:
    | "super_admin"
    | "ops_admin"
    | "field_engineer"
    | "remote_expert"
    | "viewer";
  displayName: string;
};

export type FieldAppCommand = {
  appKey: string;
  name: string;
  description: string;
  iconKey: string | null;
  entryMode: "independent" | "work_order" | "both";
};

export type WorkflowDefinitionCommand = {
  workflowKey: string;
  title: string;
  description: string;
  schemaVersion: number;
};

export type WorkflowPublicationCommand = {
  executionPackage: WorkflowExecutionPackage;
  contentSha256: string;
  packageSignature: string;
  signatureKeyId: string;
  requiredCapabilities: string[];
  minAppVersionCode: number;
  reason: string;
};

export type WorkOrderWorkflowResolutionCommand = {
  reason: string;
  idempotencyKey: string;
  assignedProfileId: string | null;
  assignedDeviceId: string | null;
};

export type WorkOrderStatus =
  | "received"
  | "accepted"
  | "in_progress"
  | "completed"
  | "closed"
  | "cancelled";

export type WorkflowBindingRuleCommand = {
  ruleKey: string;
  source:
    | "manual"
    | "trusted_external"
    | "project"
    | "asset_order_type"
    | "organization_default";
  mode: "required" | "optional" | "none";
  workflowVersionId: string | null;
  matchConditions: Array<{
    field: string;
    operator: "eq" | "in" | "contains";
    value: string | string[];
  }>;
  enabled: boolean;
  activeFrom: string | null;
  activeUntil: string | null;
  reason: string;
  idempotencyKey: string;
};

export type WorkflowBindingRuleUpdateCommand = WorkflowBindingRuleCommand & {
  expectedVersion: number;
};

export class WorkflowBindingRuleVersionConflictError extends Error {
  constructor() {
    super("workflow_binding_rule_version_conflict");
    this.name = "WorkflowBindingRuleVersionConflictError";
  }
}

export type WorkflowManagementGateway = {
  authenticate(token: string): Promise<WorkflowManagementIdentity | null>;
  listFieldApps(
    identity: WorkflowManagementIdentity,
  ): Promise<Array<Record<string, unknown>>>;
  createFieldApp(
    identity: WorkflowManagementIdentity,
    command: FieldAppCommand,
  ): Promise<Record<string, unknown> | null>;
  listWorkflows(
    identity: WorkflowManagementIdentity,
    fieldAppId: string,
  ): Promise<Array<Record<string, unknown>> | null>;
  createWorkflow(
    identity: WorkflowManagementIdentity,
    fieldAppId: string,
    command: WorkflowDefinitionCommand,
  ): Promise<Record<string, unknown> | null>;
  getWorkflow(
    identity: WorkflowManagementIdentity,
    workflowId: string,
  ): Promise<Record<string, unknown> | null>;
  saveWorkflowDraft(
    identity: WorkflowManagementIdentity,
    workflowId: string,
    draft: Record<string, unknown>,
  ): Promise<Record<string, unknown> | null>;
  listWorkflowVersions(
    identity: WorkflowManagementIdentity,
    workflowId: string,
  ): Promise<Array<Record<string, unknown>> | null>;
  getWorkflowVersion(
    identity: WorkflowManagementIdentity,
    versionId: string,
  ): Promise<Record<string, unknown> | null>;
  publishWorkflowVersion(
    identity: WorkflowManagementIdentity,
    workflowId: string,
    command: WorkflowPublicationCommand,
  ): Promise<Record<string, unknown> | null>;
  getWorkOrder(
    identity: WorkflowManagementIdentity,
    workOrderId: string,
  ): Promise<Record<string, unknown> | null>;
  listWorkOrders(
    identity: WorkflowManagementIdentity,
    status: WorkOrderStatus | null,
    limit: number,
  ): Promise<Array<Record<string, unknown>>>;
  listWorkflowBindingRules(
    identity: WorkflowManagementIdentity,
  ): Promise<Array<Record<string, unknown>>>;
  createWorkflowBindingRule(
    identity: WorkflowManagementIdentity,
    command: WorkflowBindingRuleCommand,
  ): Promise<Record<string, unknown> | null>;
  updateWorkflowBindingRule(
    identity: WorkflowManagementIdentity,
    ruleId: string,
    command: WorkflowBindingRuleUpdateCommand,
  ): Promise<Record<string, unknown> | null>;
  applyWorkOrderWorkflowResolution(
    identity: WorkflowManagementIdentity,
    order: Record<string, unknown>,
    resolution: BindingResolution,
    command: WorkOrderWorkflowResolutionCommand,
  ): Promise<Record<string, unknown> | null>;
};

const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Content-Type": "application/json; charset=utf-8",
};
const workOrderStatuses = new Set<WorkOrderStatus>([
  "received",
  "accepted",
  "in_progress",
  "completed",
  "closed",
  "cancelled",
]);
const bindingRuleSources = new Set<WorkflowBindingRuleCommand["source"]>([
  "manual",
  "trusted_external",
  "project",
  "asset_order_type",
  "organization_default",
]);
const bindingRuleModes = new Set<WorkflowBindingRuleCommand["mode"]>([
  "required",
  "optional",
  "none",
]);
const bindingConditionFields = new Set([
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
const assetOrderConditionFields = new Set([
  "workOrderType",
  "assetCategory",
  "assetBrand",
  "assetModel",
  "faultType",
  "priority",
  "riskLevel",
  "tags",
]);

export async function routeWorkflowManagement(
  request: Request,
  gateway: WorkflowManagementGateway,
  signer: WorkflowPackageSigner | null = null,
): Promise<Response> {
  const token = bearerToken(request);
  if (!token) return response({ ok: false, error: "unauthorized" }, 401);
  const identity = await gateway.authenticate(token);
  if (!identity) return response({ ok: false, error: "unauthorized" }, 401);
  if (!canManageWorkflows(identity)) {
    return response({ ok: false, error: "forbidden" }, 403);
  }

  const path = routePath(request);
  if (request.method === "GET" && path === "/management/workflow-catalog") {
    return response(publicWorkflowCatalog);
  }

  if (request.method === "GET" && path === "/management/work-orders") {
    const filters = workOrderListFilters(request);
    if (!filters) return invalidRequest();
    const items = await gateway.listWorkOrders(
      identity,
      filters.status,
      filters.limit,
    );
    return response({ items: items.map(workOrderManagementDto) });
  }

  if (
    request.method === "GET" &&
    path === "/management/workflow-binding-rules"
  ) {
    const items = await gateway.listWorkflowBindingRules(identity);
    return response({ items: items.map(workflowBindingRuleDto) });
  }

  if (
    request.method === "POST" &&
    path === "/management/workflow-binding-rules"
  ) {
    const body = await requestObject(request);
    if (body?.confirmation !== "CREATE_WORKFLOW_BINDING_RULE") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const command = workflowBindingRuleCommand(body);
    if (!command) return invalidRequest();
    const item = await gateway.createWorkflowBindingRule(identity, command);
    return item
      ? response(workflowBindingRuleDto(item), 201)
      : response({ ok: false, error: "not_found" }, 404);
  }

  const workflowBindingRuleUpdate = path.match(
    /^\/management\/workflow-binding-rules\/([^/]+)$/,
  );
  if (request.method === "PUT" && workflowBindingRuleUpdate) {
    const ruleId = uuidValue(workflowBindingRuleUpdate[1]);
    if (!ruleId) return invalidRequest();
    const body = await requestObject(request);
    if (body?.confirmation !== "UPDATE_WORKFLOW_BINDING_RULE") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const command = workflowBindingRuleUpdateCommand(body);
    if (!command) return invalidRequest();
    try {
      const item = await gateway.updateWorkflowBindingRule(
        identity,
        ruleId,
        command,
      );
      return item
        ? response(workflowBindingRuleDto(item))
        : response({ ok: false, error: "not_found" }, 404);
    } catch (error) {
      if (error instanceof WorkflowBindingRuleVersionConflictError) {
        return response({
          ok: false,
          error: "binding_rule_version_conflict",
        }, 409);
      }
      throw error;
    }
  }

  const workOrderResolution = path.match(
    /^\/management\/work-orders\/([^/]+)\/resolve-workflow$/,
  );
  if (request.method === "POST" && workOrderResolution) {
    const body = await requestObject(request);
    if (body?.confirmation !== "RESOLVE_WORKFLOW") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const reason = textValue(body.reason, 1000);
    const idempotencyKey = textValue(body.idempotencyKey, 200);
    const assignedDeviceId = optionalUuidValue(body.assignedDeviceId);
    if (!reason || !idempotencyKey || assignedDeviceId === undefined) {
      return invalidRequest();
    }

    const order = await gateway.getWorkOrder(
      identity,
      workOrderResolution[1],
    );
    if (!order) return response({ ok: false, error: "not_found" }, 404);
    const facts = workOrderFacts(identity, order);
    if (!facts) {
      return response({ ok: false, error: "work_order_invalid" }, 409);
    }
    if (["in_progress", "completed", "closed"].includes(String(order.status))) {
      return response({ ok: false, error: "workflow_already_started" }, 409);
    }
    if (String(order.status) === "cancelled") {
      return response({ ok: false, error: "work_order_not_resolvable" }, 409);
    }

    const candidates = (await gateway.listWorkflowBindingRules(identity)).map(
      bindingCandidate,
    );
    const resolution = resolveWorkflowBinding(facts, candidates);
    const assignedProfileId = nullableIdentifier(order.assigned_profile_id);
    if (resolution.kind === "assigned" && !assignedProfileId) {
      return response({ ok: false, error: "work_order_unassigned" }, 409);
    }
    if (resolution.kind === "assigned" && !facts.projectId) {
      return response({ ok: false, error: "work_order_project_required" }, 409);
    }

    const item = await gateway.applyWorkOrderWorkflowResolution(
      identity,
      order,
      resolution,
      { reason, idempotencyKey, assignedProfileId, assignedDeviceId },
    );
    return item
      ? response(item)
      : response({ ok: false, error: "not_found" }, 404);
  }

  if (request.method === "GET" && path === "/management/field-apps") {
    return response({ items: await gateway.listFieldApps(identity) });
  }
  if (request.method === "POST" && path === "/management/field-apps") {
    const body = await requestObject(request);
    const command = fieldAppCommand(body);
    if (!command) return invalidRequest();
    const item = await gateway.createFieldApp(identity, command);
    return item
      ? response(item, 201)
      : response({ ok: false, error: "not_found" }, 404);
  }

  const fieldAppWorkflows = path.match(
    /^\/management\/field-apps\/([^/]+)\/workflows$/,
  );
  if (request.method === "GET" && fieldAppWorkflows) {
    const items = await gateway.listWorkflows(identity, fieldAppWorkflows[1]);
    return items
      ? response({ items })
      : response({ ok: false, error: "not_found" }, 404);
  }
  if (request.method === "POST" && fieldAppWorkflows) {
    const body = await requestObject(request);
    const command = workflowDefinitionCommand(body);
    if (!command) return invalidRequest();
    const item = await gateway.createWorkflow(
      identity,
      fieldAppWorkflows[1],
      command,
    );
    return item
      ? response(item, 201)
      : response({ ok: false, error: "not_found" }, 404);
  }

  const workflowDraft = path.match(
    /^\/management\/workflows\/([^/]+)\/draft$/,
  );
  if (request.method === "PUT" && workflowDraft) {
    const body = await requestObject(request);
    const draft = recordValue(body?.draft);
    if (!draft) return invalidRequest();
    if (draft.workflowId !== workflowDraft[1]) {
      return response({ ok: false, error: "workflow_id_mismatch" }, 400);
    }
    const validation = validateWorkflowDraft(draft);
    if (validation.errors.length > 0) {
      return response({
        ok: false,
        error: "workflow_invalid",
        validationErrors: validation.errors,
      }, 422);
    }
    const item = await gateway.saveWorkflowDraft(
      identity,
      workflowDraft[1],
      draft,
    );
    return item
      ? response(item)
      : response({ ok: false, error: "not_found" }, 404);
  }

  const workflowValidation = path.match(
    /^\/management\/workflows\/([^/]+)\/validate$/,
  );
  if (request.method === "POST" && workflowValidation) {
    const item = await gateway.getWorkflow(identity, workflowValidation[1]);
    if (!item) return response({ ok: false, error: "not_found" }, 404);
    const validation = validateWorkflowDraft(item.draft_graph);
    const valid = validation.errors.length === 0;
    return response(
      { valid, validationErrors: validation.errors },
      valid ? 200 : 422,
    );
  }

  const workflowPublication = path.match(
    /^\/management\/workflows\/([^/]+)\/publish$/,
  );
  if (request.method === "POST" && workflowPublication) {
    const body = await requestObject(request);
    if (body?.confirmation !== "PUBLISH_WORKFLOW") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const reason = textValue(body.reason, 1000);
    const minAppVersionCode = body.minAppVersionCode;
    if (
      !reason || !Number.isInteger(minAppVersionCode) ||
      Number(minAppVersionCode) < 9000
    ) return invalidRequest();
    if (!signer) {
      return response({ ok: false, error: "signing_unavailable" }, 503);
    }
    const workflow = await gateway.getWorkflow(
      identity,
      workflowPublication[1],
    );
    if (!workflow) return response({ ok: false, error: "not_found" }, 404);
    const validation = validateWorkflowDraft(workflow.draft_graph);
    if (validation.errors.length > 0) {
      return response({
        ok: false,
        error: "workflow_invalid",
        validationErrors: validation.errors,
      }, 422);
    }
    const executionPackage = await compileWorkflowDraft(workflow.draft_graph);
    let packageSignature: string;
    try {
      packageSignature = await signer.sign(executionPackage.contentSha256);
    } catch {
      return response({ ok: false, error: "signing_unavailable" }, 503);
    }
    const item = await gateway.publishWorkflowVersion(
      identity,
      workflowPublication[1],
      {
        executionPackage,
        contentSha256: executionPackage.contentSha256,
        packageSignature,
        signatureKeyId: signer.keyId,
        requiredCapabilities: executionPackage.requiredCapabilities,
        minAppVersionCode: Number(minAppVersionCode),
        reason,
      },
    );
    return item
      ? response(item, 201)
      : response({ ok: false, error: "not_found" }, 404);
  }

  const workflowVersions = path.match(
    /^\/management\/workflows\/([^/]+)\/versions$/,
  );
  if (request.method === "GET" && workflowVersions) {
    const items = await gateway.listWorkflowVersions(
      identity,
      workflowVersions[1],
    );
    return items
      ? response({ items })
      : response({ ok: false, error: "not_found" }, 404);
  }

  const workflowVersionDetail = path.match(
    /^\/management\/workflow-versions\/([^/]+)$/,
  );
  if (request.method === "GET" && workflowVersionDetail) {
    const item = await gateway.getWorkflowVersion(
      identity,
      workflowVersionDetail[1],
    );
    return item
      ? response(item)
      : response({ ok: false, error: "not_found" }, 404);
  }

  const workflowDetail = path.match(/^\/management\/workflows\/([^/]+)$/);
  if (request.method === "GET" && workflowDetail) {
    const item = await gateway.getWorkflow(identity, workflowDetail[1]);
    return item
      ? response(item)
      : response({ ok: false, error: "not_found" }, 404);
  }

  return response({ ok: false, error: "not_found" }, 404);
}

export function createWorkflowManagementGateway(
  supabase: any,
): WorkflowManagementGateway {
  return {
    async authenticate(token) {
      const { data, error } = await supabase.auth.getUser(token);
      if (error || !data?.user?.id) return null;
      const { data: profile, error: profileError } = await supabase
        .from("ops_profiles")
        .select("id, organization_id, role, display_name, active, status")
        .eq("id", data.user.id)
        .eq("active", true)
        .eq("status", "active")
        .maybeSingle();
      if (profileError || !profile) return null;
      return {
        id: String(profile.id),
        organizationId: String(profile.organization_id),
        role: profile.role,
        displayName: String(profile.display_name ?? ""),
      } as WorkflowManagementIdentity;
    },
    async listFieldApps(identity) {
      const { data, error } = await supabase.from("field_apps")
        .select(
          "id, app_key, name, description, icon_key, entry_mode, status, default_workflow_definition_id, created_at, updated_at",
        )
        .eq("organization_id", identity.organizationId)
        .order("updated_at", { ascending: false });
      if (error) throw error;
      return data ?? [];
    },
    async createFieldApp(identity, command) {
      const { data, error } = await supabase.from("field_apps").insert({
        organization_id: identity.organizationId,
        app_key: command.appKey,
        name: command.name,
        description: command.description,
        icon_key: command.iconKey,
        entry_mode: command.entryMode,
        status: "draft",
        created_by: identity.id,
        updated_by: identity.id,
      }).select(
        "id, app_key, name, description, icon_key, entry_mode, status, created_at, updated_at",
      ).single();
      if (error) throw error;
      return data ?? null;
    },
    async listWorkflows(identity, fieldAppId) {
      if (
        !await organizationFieldAppExists(
          supabase,
          identity.organizationId,
          fieldAppId,
        )
      ) return null;
      const { data, error } = await supabase.from("workflow_definitions")
        .select(
          "id, field_app_id, workflow_key, title, description, status, schema_version, latest_version_number, created_at, updated_at",
        )
        .eq("organization_id", identity.organizationId)
        .eq("field_app_id", fieldAppId)
        .order("updated_at", { ascending: false });
      if (error) throw error;
      return data ?? [];
    },
    async createWorkflow(identity, fieldAppId, command) {
      if (
        !await organizationFieldAppExists(
          supabase,
          identity.organizationId,
          fieldAppId,
        )
      ) return null;
      const { data, error } = await supabase.from("workflow_definitions")
        .insert({
          organization_id: identity.organizationId,
          field_app_id: fieldAppId,
          workflow_key: command.workflowKey,
          title: command.title,
          description: command.description,
          status: "draft",
          schema_version: command.schemaVersion,
          draft_graph: {},
          created_by: identity.id,
          updated_by: identity.id,
        }).select(
          "id, field_app_id, workflow_key, title, description, status, schema_version, latest_version_number, draft_graph, created_at, updated_at",
        ).single();
      if (error) throw error;
      return data ?? null;
    },
    async getWorkflow(identity, workflowId) {
      const { data, error } = await supabase.from("workflow_definitions")
        .select(
          "id, field_app_id, workflow_key, title, description, status, schema_version, latest_version_number, draft_graph, created_at, updated_at",
        )
        .eq("organization_id", identity.organizationId)
        .eq("id", workflowId)
        .maybeSingle();
      if (error) throw error;
      return data ?? null;
    },
    async saveWorkflowDraft(identity, workflowId, draft) {
      const now = new Date().toISOString();
      const { data, error } = await supabase.from("workflow_definitions")
        .update({
          draft_graph: draft,
          schema_version: draft.schemaVersion,
          status: "draft",
          updated_by: identity.id,
          updated_at: now,
        })
        .eq("organization_id", identity.organizationId)
        .eq("id", workflowId)
        .select(
          "id, field_app_id, workflow_key, title, description, status, schema_version, latest_version_number, draft_graph, created_at, updated_at",
        )
        .maybeSingle();
      if (error) throw error;
      return data ?? null;
    },
    async listWorkflowVersions(identity, workflowId) {
      const workflow = await organizationWorkflowExists(
        supabase,
        identity.organizationId,
        workflowId,
      );
      if (!workflow) return null;
      const { data, error } = await supabase.from("workflow_versions")
        .select(
          "id, workflow_definition_id, version_number, status, schema_version, content_sha256, signature_key_id, required_capabilities, min_app_version_code, published_by, published_at, status_changed_at",
        )
        .eq("organization_id", identity.organizationId)
        .eq("workflow_definition_id", workflowId)
        .order("version_number", { ascending: false });
      if (error) throw error;
      return data ?? [];
    },
    async getWorkflowVersion(identity, versionId) {
      const { data, error } = await supabase.from("workflow_versions")
        .select(
          "id, workflow_definition_id, version_number, status, schema_version, execution_package, content_sha256, package_signature, signature_key_id, required_capabilities, min_app_version_code, published_by, published_at, status_changed_at",
        )
        .eq("organization_id", identity.organizationId)
        .eq("id", versionId)
        .maybeSingle();
      if (error) throw error;
      return data ?? null;
    },
    async publishWorkflowVersion(identity, workflowId, command) {
      const { data, error } = await supabase.rpc("publish_workflow_version", {
        target_workflow_definition_id: workflowId,
        compiled_execution_package: command.executionPackage,
        compiled_content_sha256: command.contentSha256,
        compiled_package_signature: command.packageSignature,
        compiled_signature_key_id: command.signatureKeyId,
        compiled_required_capabilities: command.requiredCapabilities,
        required_min_app_version_code: command.minAppVersionCode,
        actor_id: identity.id,
        publication_reason: command.reason,
      });
      if (error) throw error;
      return firstRow(data);
    },
    async getWorkOrder(identity, workOrderId) {
      const { data, error } = await supabase.from("work_orders")
        .select(
          "id, organization_id, source_system, external_work_order_id, external_workflow_code, project_id, assigned_profile_id, customer_id, work_order_type, asset_category, asset_brand, asset_model, fault_type, priority, risk_level, tags, status, binding_status",
        )
        .eq("organization_id", identity.organizationId)
        .eq("id", workOrderId)
        .maybeSingle();
      if (error) throw error;
      return data ?? null;
    },
    async listWorkOrders(identity, status, limit) {
      let query = supabase.from("work_orders")
        .select(
          "id, source_system, external_work_order_id, external_workflow_code, project_id, assigned_profile_id, title, customer_id, work_order_type, asset_id, asset_category, asset_brand, asset_model, fault_type, priority, risk_level, tags, status, binding_mode, binding_status, bound_workflow_version_id, due_at, received_at, updated_at",
        )
        .eq("organization_id", identity.organizationId)
        .order("received_at", { ascending: false })
        .limit(limit);
      if (status) query = query.eq("status", status);
      const { data, error } = await query;
      if (error) throw error;
      return data ?? [];
    },
    async listWorkflowBindingRules(identity) {
      const { data, error } = await supabase.from("workflow_binding_rules")
        .select(
          "id, organization_id, rule_key, source, mode, workflow_version_id, match_conditions, enabled, active_from, active_until, reason, version, created_at, updated_at, workflow_versions(status)",
        )
        .eq("organization_id", identity.organizationId)
        .order("updated_at", { ascending: false });
      if (error) throw error;
      return data ?? [];
    },
    async createWorkflowBindingRule(identity, command) {
      const { data, error } = await supabase.rpc(
        "create_workflow_binding_rule",
        workflowBindingRuleRpcArguments(identity, command),
      );
      if (error) throw error;
      return firstRow(data);
    },
    async updateWorkflowBindingRule(identity, ruleId, command) {
      const { data, error } = await supabase.rpc(
        "update_workflow_binding_rule",
        {
          target_rule_id: ruleId,
          ...workflowBindingRuleRpcArguments(identity, command),
          expected_version: command.expectedVersion,
        },
      );
      if (error) {
        if (isWorkflowBindingRuleVersionConflict(error)) {
          throw new WorkflowBindingRuleVersionConflictError();
        }
        throw error;
      }
      return firstRow(data);
    },
    async applyWorkOrderWorkflowResolution(
      identity,
      order,
      resolution,
      command,
    ) {
      const source = resolution.kind === "conflict"
        ? null
        : resolution.source ?? null;
      const candidateId = resolution.kind === "conflict"
        ? null
        : resolution.candidateId ?? null;
      const workflowVersionId = resolution.kind === "assigned"
        ? resolution.workflowVersionId
        : null;
      const mode = resolution.kind === "assigned" ? resolution.mode : "none";
      const evidence = resolution.kind === "conflict"
        ? { candidateIds: resolution.candidateIds, resolver: "v9-binding-v1" }
        : {
          candidateId,
          resolver: "v9-binding-v1",
          workflowVersionId,
        };
      const { data, error } = await supabase.rpc(
        "apply_work_order_workflow_resolution",
        {
          target_work_order_id: order.id,
          resolution_kind: resolution.kind,
          resolved_mode: mode,
          resolved_workflow_version_id: workflowVersionId,
          resolved_rule_id: candidateId,
          resolved_source: source,
          resolved_evidence: evidence,
          target_assigned_profile_id: command.assignedProfileId,
          target_assigned_device_id: command.assignedDeviceId,
          resolution_idempotency_key: command.idempotencyKey,
          actor_id: identity.id,
          resolution_reason: command.reason,
        },
      );
      if (error) throw error;
      return firstRow(data);
    },
  };
}

function bearerToken(request: Request): string | null {
  const value = request.headers.get("Authorization") ?? "";
  return value.startsWith("Bearer ") && value.length > 7
    ? value.slice(7)
    : null;
}

function canManageWorkflows(identity: WorkflowManagementIdentity): boolean {
  return identity.role === "super_admin" || identity.role === "ops_admin";
}

async function organizationFieldAppExists(
  supabase: any,
  organizationId: string,
  fieldAppId: string,
): Promise<boolean> {
  const { data, error } = await supabase.from("field_apps")
    .select("id")
    .eq("organization_id", organizationId)
    .eq("id", fieldAppId)
    .maybeSingle();
  if (error) throw error;
  return Boolean(data);
}

async function organizationWorkflowExists(
  supabase: any,
  organizationId: string,
  workflowId: string,
): Promise<boolean> {
  const { data, error } = await supabase.from("workflow_definitions")
    .select("id")
    .eq("organization_id", organizationId)
    .eq("id", workflowId)
    .maybeSingle();
  if (error) throw error;
  return Boolean(data);
}

async function requestObject(
  request: Request,
): Promise<Record<string, unknown> | null> {
  try {
    return recordValue(await request.json());
  } catch {
    return null;
  }
}

function fieldAppCommand(
  body: Record<string, unknown> | null,
): FieldAppCommand | null {
  const appKey = keyValue(body?.appKey);
  const name = textValue(body?.name, 120);
  const description = optionalTextValue(body?.description, 4000);
  const iconKey = nullableTextValue(body?.iconKey, 160);
  const entryMode = body?.entryMode;
  if (
    !appKey || !name || description === null || iconKey === undefined ||
    (entryMode !== "independent" && entryMode !== "work_order" &&
      entryMode !== "both")
  ) return null;
  return { appKey, name, description, iconKey, entryMode };
}

function workflowDefinitionCommand(
  body: Record<string, unknown> | null,
): WorkflowDefinitionCommand | null {
  const workflowKey = keyValue(body?.workflowKey);
  const title = textValue(body?.title, 160);
  const description = optionalTextValue(body?.description, 4000);
  const schemaVersion = body?.schemaVersion ?? 1;
  if (
    !workflowKey || !title || description === null ||
    !Number.isInteger(schemaVersion) || Number(schemaVersion) < 1 ||
    Number(schemaVersion) > 100
  ) return null;
  return {
    workflowKey,
    title,
    description,
    schemaVersion: Number(schemaVersion),
  };
}

function workOrderListFilters(
  request: Request,
): { status: WorkOrderStatus | null; limit: number } | null {
  const searchParams = new URL(request.url).searchParams;
  for (const key of searchParams.keys()) {
    if (key !== "status" && key !== "limit") return null;
  }
  if (
    searchParams.getAll("status").length > 1 ||
    searchParams.getAll("limit").length > 1
  ) return null;

  const statusValue = searchParams.get("status");
  const status = statusValue === null || statusValue === ""
    ? null
    : workOrderStatuses.has(statusValue as WorkOrderStatus)
    ? statusValue as WorkOrderStatus
    : undefined;
  const limitValue = searchParams.get("limit");
  const limit = limitValue === null || limitValue === ""
    ? 50
    : /^\d{1,3}$/.test(limitValue)
    ? Number(limitValue)
    : 0;
  return status !== undefined && limit >= 1 && limit <= 100
    ? { status, limit }
    : null;
}

function workflowBindingRuleCommand(
  body: Record<string, unknown> | null,
): WorkflowBindingRuleCommand | null {
  if (!body) return null;
  const ruleKey = keyValue(body.ruleKey);
  const source = body.source;
  const mode = body.mode;
  const workflowVersionId = optionalUuidValue(body.workflowVersionId);
  const matchConditions = bindingConditionsValue(body.matchConditions);
  const activeFrom = nullableTimestampValue(body.activeFrom);
  const activeUntil = nullableTimestampValue(body.activeUntil);
  const reason = textValue(body.reason, 1000);
  const idempotencyKey = textValue(body.idempotencyKey, 200);
  if (
    !ruleKey || !bindingRuleSources.has(
      source as WorkflowBindingRuleCommand["source"],
    ) || !bindingRuleModes.has(mode as WorkflowBindingRuleCommand["mode"]) ||
    workflowVersionId === undefined || matchConditions === null ||
    typeof body.enabled !== "boolean" || activeFrom === undefined ||
    activeUntil === undefined || !reason || !idempotencyKey
  ) return null;
  if (
    (mode === "none" && workflowVersionId !== null) ||
    ((mode === "required" || mode === "optional") &&
      workflowVersionId === null) ||
    (activeFrom && activeUntil &&
      Date.parse(activeUntil) <= Date.parse(activeFrom)) ||
    !bindingSourceConditionsAreValid(
      source as WorkflowBindingRuleCommand["source"],
      matchConditions,
    )
  ) return null;
  return {
    ruleKey,
    source: source as WorkflowBindingRuleCommand["source"],
    mode: mode as WorkflowBindingRuleCommand["mode"],
    workflowVersionId,
    matchConditions,
    enabled: body.enabled,
    activeFrom,
    activeUntil,
    reason,
    idempotencyKey,
  };
}

function workflowBindingRuleUpdateCommand(
  body: Record<string, unknown> | null,
): WorkflowBindingRuleUpdateCommand | null {
  const command = workflowBindingRuleCommand(body);
  const expectedVersion = body?.expectedVersion;
  return command && Number.isInteger(expectedVersion) &&
      Number(expectedVersion) > 0 && Number(expectedVersion) <= 2147483647
    ? { ...command, expectedVersion: Number(expectedVersion) }
    : null;
}

function bindingConditionsValue(
  value: unknown,
): WorkflowBindingRuleCommand["matchConditions"] | null {
  if (!Array.isArray(value) || value.length > 20) return null;
  const conditions: WorkflowBindingRuleCommand["matchConditions"] = [];
  const identities = new Set<string>();
  for (const item of value) {
    if (
      !isRecord(item) ||
      Object.keys(item).some((key) =>
        key !== "field" && key !== "operator" && key !== "value"
      ) ||
      typeof item.field !== "string" ||
      !bindingConditionFields.has(item.field) ||
      (item.operator !== "eq" && item.operator !== "in" &&
        item.operator !== "contains")
    ) return null;

    let conditionValue: string | string[];
    if (item.operator === "in") {
      if (
        item.field === "tags" || !Array.isArray(item.value) ||
        item.value.length < 1 || item.value.length > 50
      ) return null;
      const values = item.value.map(bindingConditionText);
      if (
        values.some((entry) => entry === null) ||
        new Set(values).size !== values.length
      ) return null;
      conditionValue = values as string[];
    } else {
      const text = bindingConditionText(item.value);
      if (
        !text ||
        (item.operator === "contains" && item.field !== "tags") ||
        (item.operator === "eq" && item.field === "tags")
      ) return null;
      conditionValue = text;
    }
    const condition = {
      field: item.field,
      operator: item.operator,
      value: conditionValue,
    } as WorkflowBindingRuleCommand["matchConditions"][number];
    const identity = JSON.stringify(condition);
    if (identities.has(identity)) return null;
    identities.add(identity);
    conditions.push(condition);
  }
  return conditions;
}

function bindingSourceConditionsAreValid(
  source: WorkflowBindingRuleCommand["source"],
  conditions: WorkflowBindingRuleCommand["matchConditions"],
): boolean {
  const has = (field: string, operator?: string) =>
    conditions.some((condition) =>
      condition.field === field &&
      (operator === undefined || condition.operator === operator)
    );
  if (source === "manual") return has("workOrderId", "eq");
  if (source === "trusted_external") {
    return has("externalSystem", "eq") &&
      has("externalWorkflowCode", "eq");
  }
  if (source === "project") {
    return has("customerId") || has("projectId");
  }
  if (source === "asset_order_type") {
    return conditions.length > 0 &&
      conditions.every((condition) =>
        assetOrderConditionFields.has(condition.field)
      );
  }
  return conditions.length === 0;
}

function bindingConditionText(value: unknown): string | null {
  const text = textValue(value, 240);
  if (!text) return null;
  return /<\s*\/?\s*[a-z][^>]*>/i.test(text) ||
      /(?:https?|wss?|file):\/\//i.test(text) ||
      /(?:data|javascript):/i.test(text)
    ? null
    : text;
}

function nullableTimestampValue(
  value: unknown,
): string | null | undefined {
  if (value === undefined || value === null || value === "") return null;
  if (typeof value !== "string") return undefined;
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp)
    ? new Date(timestamp).toISOString()
    : undefined;
}

function uuidValue(value: unknown): string | null {
  return optionalUuidValue(value) ?? null;
}

function keyValue(value: unknown): string | null {
  const text = textValue(value, 64);
  return text && /^[a-z][a-z0-9_-]{2,63}$/.test(text) ? text : null;
}

function textValue(value: unknown, maxLength: number): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.trim();
  return normalized && normalized.length <= maxLength ? normalized : null;
}

function optionalTextValue(value: unknown, maxLength: number): string | null {
  if (value === undefined || value === null || value === "") return "";
  return textValue(value, maxLength);
}

function nullableTextValue(
  value: unknown,
  maxLength: number,
): string | null | undefined {
  if (value === undefined || value === null || value === "") return null;
  return textValue(value, maxLength) ?? undefined;
}

function optionalUuidValue(value: unknown): string | null | undefined {
  if (value === undefined || value === null || value === "") return null;
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().toLowerCase();
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
      .test(
        normalized,
      )
    ? normalized
    : undefined;
}

function nullableIdentifier(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function workOrderFacts(
  identity: WorkflowManagementIdentity,
  row: Record<string, unknown>,
): WorkOrderFacts | null {
  const organizationId = nullableIdentifier(row.organization_id);
  const workOrderId = nullableIdentifier(row.id);
  if (
    !organizationId || organizationId !== identity.organizationId ||
    !workOrderId
  ) {
    return null;
  }
  const tags =
    Array.isArray(row.tags) && row.tags.every((tag) => typeof tag === "string")
      ? row.tags as string[]
      : undefined;
  return {
    organizationId,
    workOrderId,
    externalSystem: nullableIdentifier(row.source_system) ?? undefined,
    externalWorkflowCode: nullableIdentifier(row.external_workflow_code) ??
      undefined,
    customerId: nullableIdentifier(row.customer_id) ?? undefined,
    projectId: nullableIdentifier(row.project_id) ?? undefined,
    workOrderType: nullableIdentifier(row.work_order_type) ?? undefined,
    assetCategory: nullableIdentifier(row.asset_category) ?? undefined,
    assetBrand: nullableIdentifier(row.asset_brand) ?? undefined,
    assetModel: nullableIdentifier(row.asset_model) ?? undefined,
    faultType: nullableIdentifier(row.fault_type) ?? undefined,
    priority: nullableIdentifier(row.priority) ?? undefined,
    riskLevel: nullableIdentifier(row.risk_level) ?? undefined,
    tags,
  };
}

function bindingCandidate(row: Record<string, unknown>): BindingCandidate {
  const joinedVersion = Array.isArray(row.workflow_versions)
    ? recordValue(row.workflow_versions[0])
    : recordValue(row.workflow_versions);
  const mode = row.mode;
  const versionStatus = mode === "none" && row.workflow_version_id == null
    ? "published"
    : joinedVersion?.status;
  return {
    candidateId: nullableIdentifier(row.id) ?? "",
    organizationId: nullableIdentifier(row.organization_id) ?? "",
    source: row.source as BindingCandidate["source"],
    mode: mode as BindingCandidate["mode"],
    workflowVersionId: nullableIdentifier(row.workflow_version_id) ?? undefined,
    workflowVersionStatus:
      versionStatus as BindingCandidate["workflowVersionStatus"],
    enabled: row.enabled === true,
    conditions: row.match_conditions as BindingCandidate["conditions"],
    activeFrom: nullableIdentifier(row.active_from) ?? undefined,
    activeUntil: nullableIdentifier(row.active_until) ?? undefined,
  };
}

function workOrderManagementDto(
  row: Record<string, unknown>,
): Record<string, unknown> {
  return {
    id: outputText(row.id),
    sourceSystem: outputText(row.source_system),
    externalWorkOrderId: outputText(row.external_work_order_id),
    externalWorkflowCode: outputText(row.external_workflow_code),
    projectId: outputText(row.project_id),
    assignedProfileId: outputText(row.assigned_profile_id),
    title: outputText(row.title),
    customerId: outputText(row.customer_id),
    workOrderType: outputText(row.work_order_type),
    assetId: outputText(row.asset_id),
    assetCategory: outputText(row.asset_category),
    assetBrand: outputText(row.asset_brand),
    assetModel: outputText(row.asset_model),
    faultType: outputText(row.fault_type),
    priority: outputText(row.priority),
    riskLevel: outputText(row.risk_level),
    tags: outputStringArray(row.tags),
    status: outputText(row.status),
    bindingMode: outputText(row.binding_mode),
    bindingStatus: outputText(row.binding_status),
    boundWorkflowVersionId: outputText(row.bound_workflow_version_id),
    dueAt: outputText(row.due_at),
    receivedAt: outputText(row.received_at),
    updatedAt: outputText(row.updated_at),
  };
}

function workflowBindingRuleDto(
  row: Record<string, unknown>,
): Record<string, unknown> {
  const joinedVersion = Array.isArray(row.workflow_versions)
    ? recordValue(row.workflow_versions[0])
    : recordValue(row.workflow_versions);
  const version = Number(row.version);
  return {
    id: outputText(row.id),
    ruleKey: outputText(row.rule_key),
    source: outputText(row.source),
    mode: outputText(row.mode),
    workflowVersionId: outputText(row.workflow_version_id),
    matchConditions: bindingConditionsValue(row.match_conditions) ?? [],
    enabled: row.enabled === true,
    activeFrom: outputText(row.active_from),
    activeUntil: outputText(row.active_until),
    reason: outputText(row.reason),
    version: Number.isInteger(version) && version > 0 ? version : null,
    workflowVersionStatus: outputText(joinedVersion?.status),
    createdAt: outputText(row.created_at),
    updatedAt: outputText(row.updated_at),
  };
}

function workflowBindingRuleRpcArguments(
  identity: WorkflowManagementIdentity,
  command: WorkflowBindingRuleCommand,
): Record<string, unknown> {
  return {
    rule_key: command.ruleKey,
    binding_source: command.source,
    binding_mode: command.mode,
    target_workflow_version_id: command.workflowVersionId,
    binding_match_conditions: command.matchConditions,
    binding_enabled: command.enabled,
    binding_active_from: command.activeFrom,
    binding_active_until: command.activeUntil,
    binding_idempotency_key: command.idempotencyKey,
    actor_id: identity.id,
    command_reason: command.reason,
  };
}

function isWorkflowBindingRuleVersionConflict(error: unknown): boolean {
  const record = recordValue(error);
  const code = outputText(record?.code);
  const message = outputText(record?.message)?.toLowerCase() ?? "";
  return code === "40001" ||
    message.includes("workflow binding rule version conflict");
}

function outputText(value: unknown): string | null {
  return typeof value === "string" ? value : null;
}

function outputStringArray(value: unknown): string[] {
  return Array.isArray(value) && value.every((item) => typeof item === "string")
    ? value
    : [];
}

function recordValue(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return recordValue(value) !== null;
}

function invalidRequest(): Response {
  return response({ ok: false, error: "invalid_request" }, 400);
}

function firstRow(value: unknown): Record<string, unknown> | null {
  if (Array.isArray(value)) return recordValue(value[0]);
  return recordValue(value);
}

function routePath(request: Request): string {
  return new URL(request.url).pathname
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/";
}

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers });
}
