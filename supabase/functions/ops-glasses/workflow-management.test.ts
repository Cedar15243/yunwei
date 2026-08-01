import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  routeWorkflowManagement,
  type WorkflowManagementGateway,
} from "./workflow-management.ts";
import type { WorkflowPackageSigner } from "./workflow-signing.ts";

const adminIdentity = {
  id: "admin-a",
  organizationId: "org-a",
  role: "ops_admin" as const,
  displayName: "Admin A",
};

function gateway(
  overrides: Partial<WorkflowManagementGateway> = {},
): WorkflowManagementGateway {
  return {
    authenticate: async (token) => {
      if (token === "admin-token") return adminIdentity;
      if (token === "field-token") {
        return {
          ...adminIdentity,
          id: "field-a",
          role: "field_engineer",
        };
      }
      return null;
    },
    listFieldApps: async () => [{ id: "app-a", name: "Receiving" }],
    createFieldApp: async (_identity, command) => ({
      id: "app-created",
      ...command,
    }),
    listWorkflows: async (_identity, appId) =>
      appId === "missing"
        ? null
        : [{ id: "workflow-a", field_app_id: appId, title: "Receive" }],
    createWorkflow: async (_identity, appId, command) =>
      appId === "missing"
        ? null
        : { id: "workflow-created", field_app_id: appId, ...command },
    getWorkflow: async (_identity, workflowId) =>
      workflowId === "missing" ? null : {
        id: workflowId,
        organization_id: "org-a",
        draft_graph: validDraft(workflowId),
      },
    saveWorkflowDraft: async (_identity, workflowId, draft) => ({
      id: workflowId,
      draft_graph: draft,
    }),
    listWorkflowVersions: async (_identity, workflowId) =>
      workflowId === "missing"
        ? null
        : [{ id: "version-a", workflow_definition_id: workflowId }],
    getWorkflowVersion: async (_identity, versionId) =>
      versionId === "missing"
        ? null
        : { id: versionId, workflow_definition_id: "workflow-a" },
    publishWorkflowVersion: async (_identity, workflowId, command) => ({
      id: "version-published",
      workflow_definition_id: workflowId,
      ...command,
    }),
    getWorkOrder: async () => workOrder(),
    listWorkflowBindingRules: async () => [],
    applyWorkOrderWorkflowResolution: async (
      _identity,
      _order,
      resolution,
    ) => ({ kind: resolution.kind }),
    ...overrides,
  };
}

function request(
  method: string,
  path: string,
  token?: string,
): Request {
  const headers = new Headers();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return new Request(`https://ops.example${path}`, { method, headers });
}

function jsonRequest(
  method: string,
  path: string,
  body: Record<string, unknown>,
  token = "admin-token",
): Request {
  return new Request(`https://ops.example${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

function validDraft(workflowId = "workflow-a"): Record<string, unknown> {
  return {
    workflowId,
    schemaVersion: 1,
    title: "Receive equipment",
    nodes: [
      { nodeId: "start", type: "start", config: {} },
      { nodeId: "done", type: "complete", config: {} },
    ],
    transitions: [{
      transitionId: "finish",
      fromNodeId: "start",
      toNodeId: "done",
    }],
  };
}

Deno.test("rejects workflow management requests without valid bearer authentication", async () => {
  const missing = await routeWorkflowManagement(
    request("GET", "/management/field-apps"),
    gateway(),
  );
  const invalid = await routeWorkflowManagement(
    request("GET", "/management/field-apps", "invalid-token"),
    gateway(),
  );

  assertEquals(missing.status, 401);
  assertEquals(invalid.status, 401);
  assertEquals(await missing.json(), { ok: false, error: "unauthorized" });
});

Deno.test("allows only organization administrators to list field apps", async () => {
  const denied = await routeWorkflowManagement(
    request("GET", "/management/field-apps", "field-token"),
    gateway(),
  );
  const allowed = await routeWorkflowManagement(
    request("GET", "/management/field-apps", "admin-token"),
    gateway(),
  );

  assertEquals(denied.status, 403);
  assertEquals(await denied.json(), { ok: false, error: "forbidden" });
  assertEquals(allowed.status, 200);
  assertEquals(await allowed.json(), {
    items: [{ id: "app-a", name: "Receiving" }],
  });
});

Deno.test("accepts the deployed Supabase workflow management path", async () => {
  const response = await routeWorkflowManagement(
    request(
      "GET",
      "/functions/v1/ops-glasses/management/field-apps",
      "admin-token",
    ),
    gateway(),
  );

  assertEquals(response.status, 200);
});

Deno.test("returns not found for unknown workflow management routes", async () => {
  const response = await routeWorkflowManagement(
    request("GET", "/management/workflow-unknown", "admin-token"),
    gateway(),
  );

  assertEquals(response.status, 404);
  assertEquals(await response.json(), { ok: false, error: "not_found" });
});

Deno.test("creates a field app from a strict organization-independent command", async () => {
  let received: Record<string, unknown> | null = null;
  const response = await routeWorkflowManagement(
    jsonRequest("POST", "/management/field-apps", {
      appKey: "receiving_app",
      name: "Receiving",
      description: "Controlled receiving flow",
      entryMode: "both",
      organizationId: "forged-org",
      createdBy: "forged-user",
    }),
    gateway({
      createFieldApp: async (_identity, command) => {
        received = command;
        return { id: "app-created", ...command };
      },
    }),
  );

  assertEquals(response.status, 201);
  assertEquals(received, {
    appKey: "receiving_app",
    name: "Receiving",
    description: "Controlled receiving flow",
    iconKey: null,
    entryMode: "both",
  });
});

Deno.test("rejects invalid field app and workflow definition commands", async () => {
  const invalidApp = await routeWorkflowManagement(
    jsonRequest("POST", "/management/field-apps", {
      appKey: "BAD KEY",
      name: "Receiving",
      entryMode: "arbitrary",
    }),
    gateway(),
  );
  const invalidWorkflow = await routeWorkflowManagement(
    jsonRequest("POST", "/management/field-apps/app-a/workflows", {
      workflowKey: "x",
      title: "",
    }),
    gateway(),
  );

  assertEquals(invalidApp.status, 400);
  assertEquals(invalidWorkflow.status, 400);
});

Deno.test("lists and creates workflow definitions only below an owned field app", async () => {
  const listed = await routeWorkflowManagement(
    request(
      "GET",
      "/management/field-apps/app-a/workflows",
      "admin-token",
    ),
    gateway(),
  );
  const created = await routeWorkflowManagement(
    jsonRequest("POST", "/management/field-apps/app-a/workflows", {
      workflowKey: "receive_flow",
      title: "Receive flow",
      description: "Capture required evidence",
      schemaVersion: 1,
    }),
    gateway(),
  );
  const missing = await routeWorkflowManagement(
    request(
      "GET",
      "/management/field-apps/missing/workflows",
      "admin-token",
    ),
    gateway(),
  );

  assertEquals(listed.status, 200);
  assertEquals(created.status, 201);
  assertEquals(missing.status, 404);
});

Deno.test("returns an organization-scoped workflow definition", async () => {
  const found = await routeWorkflowManagement(
    request("GET", "/management/workflows/workflow-a", "admin-token"),
    gateway(),
  );
  const missing = await routeWorkflowManagement(
    request("GET", "/management/workflows/missing", "admin-token"),
    gateway(),
  );

  assertEquals(found.status, 200);
  assertEquals(missing.status, 404);
});

Deno.test("persists only a valid draft matching the target workflow", async () => {
  let saved = false;
  const valid = await routeWorkflowManagement(
    jsonRequest("PUT", "/management/workflows/workflow-a/draft", {
      draft: validDraft(),
    }),
    gateway({
      saveWorkflowDraft: async (_identity, workflowId, draft) => {
        saved = true;
        return { id: workflowId, draft_graph: draft };
      },
    }),
  );
  const mismatched = await routeWorkflowManagement(
    jsonRequest("PUT", "/management/workflows/workflow-a/draft", {
      draft: validDraft("another-workflow"),
    }),
    gateway({
      saveWorkflowDraft: async () => {
        throw new Error("invalid draft must not be persisted");
      },
    }),
  );

  assertEquals(valid.status, 200);
  assertEquals(saved, true);
  assertEquals(mismatched.status, 400);
  assertEquals((await mismatched.json()).error, "workflow_id_mismatch");
});

Deno.test("returns stable compiler errors without persisting an invalid draft", async () => {
  const invalidDraft = {
    ...validDraft(),
    nodes: [{ nodeId: "start", type: "http", config: {} }],
    transitions: [],
  };
  const save = await routeWorkflowManagement(
    jsonRequest("PUT", "/management/workflows/workflow-a/draft", {
      draft: invalidDraft,
    }),
    gateway({
      saveWorkflowDraft: async () => {
        throw new Error("invalid draft must not be persisted");
      },
    }),
  );

  assertEquals(save.status, 422);
  const body = await save.json();
  assertEquals(body.ok, false);
  assertEquals(body.error, "workflow_invalid");
  assertEquals(body.validationErrors[0], {
    code: "unsupported_node_type",
    path: "$.nodes[0].type",
  });
});

Deno.test("validates the currently saved workflow draft on the server", async () => {
  const valid = await routeWorkflowManagement(
    request(
      "POST",
      "/management/workflows/workflow-a/validate",
      "admin-token",
    ),
    gateway(),
  );
  const invalid = await routeWorkflowManagement(
    request(
      "POST",
      "/management/workflows/workflow-a/validate",
      "admin-token",
    ),
    gateway({
      getWorkflow: async () => ({
        id: "workflow-a",
        draft_graph: { workflowId: "workflow-a" },
      }),
    }),
  );

  assertEquals(valid.status, 200);
  assertEquals(await valid.json(), { valid: true, validationErrors: [] });
  assertEquals(invalid.status, 422);
  assertEquals((await invalid.json()).valid, false);
});

Deno.test("requires explicit confirmation and a reason before publishing", async () => {
  const signer = testSigner();
  const missingConfirmation = await routeWorkflowManagement(
    jsonRequest("POST", "/management/workflows/workflow-a/publish", {
      reason: "Approved for pilot",
      minAppVersionCode: 9000,
    }),
    gateway(),
    signer,
  );
  const missingReason = await routeWorkflowManagement(
    jsonRequest("POST", "/management/workflows/workflow-a/publish", {
      confirmation: "PUBLISH_WORKFLOW",
      minAppVersionCode: 9000,
    }),
    gateway(),
    signer,
  );

  assertEquals(missingConfirmation.status, 400);
  assertEquals(
    (await missingConfirmation.json()).error,
    "confirmation_required",
  );
  assertEquals(missingReason.status, 400);
});

Deno.test("refuses publication when the server signing key is unavailable", async () => {
  let persisted = false;
  const response = await routeWorkflowManagement(
    jsonRequest("POST", "/management/workflows/workflow-a/publish", {
      confirmation: "PUBLISH_WORKFLOW",
      reason: "Approved for pilot",
      minAppVersionCode: 9000,
    }),
    gateway({
      publishWorkflowVersion: async () => {
        persisted = true;
        return {};
      },
    }),
    null,
  );

  assertEquals(response.status, 503);
  assertEquals(await response.json(), {
    ok: false,
    error: "signing_unavailable",
  });
  assertEquals(persisted, false);
});

Deno.test("compiles signs and publishes one immutable workflow version", async () => {
  let received: Record<string, unknown> | null = null;
  const response = await routeWorkflowManagement(
    jsonRequest("POST", "/management/workflows/workflow-a/publish", {
      confirmation: "PUBLISH_WORKFLOW",
      reason: "Approved for pilot",
      minAppVersionCode: 9002,
    }),
    gateway({
      publishWorkflowVersion: async (_identity, _workflowId, command) => {
        received = command;
        return { id: "version-a", ...command };
      },
    }),
    testSigner(),
  );

  assertEquals(response.status, 201);
  assertEquals(received!.signatureKeyId, "workflow-key-a");
  assertEquals(received!.minAppVersionCode, 9002);
  assertEquals(received!.reason, "Approved for pilot");
  assertEquals(
    String(received!.packageSignature).startsWith("signed:"),
    true,
  );
  assertEquals(String(received!.contentSha256).length, 64);
  assertEquals(Array.isArray(received!.requiredCapabilities), true);
});

Deno.test("does not publish an invalid saved draft", async () => {
  let persisted = false;
  const response = await routeWorkflowManagement(
    jsonRequest("POST", "/management/workflows/workflow-a/publish", {
      confirmation: "PUBLISH_WORKFLOW",
      reason: "Approved for pilot",
      minAppVersionCode: 9000,
    }),
    gateway({
      getWorkflow: async () => ({
        id: "workflow-a",
        draft_graph: { workflowId: "workflow-a" },
      }),
      publishWorkflowVersion: async () => {
        persisted = true;
        return {};
      },
    }),
    testSigner(),
  );

  assertEquals(response.status, 422);
  assertEquals((await response.json()).error, "workflow_invalid");
  assertEquals(persisted, false);
});

Deno.test("lists immutable versions and returns a version detail", async () => {
  const list = await routeWorkflowManagement(
    request(
      "GET",
      "/management/workflows/workflow-a/versions",
      "admin-token",
    ),
    gateway(),
  );
  const detail = await routeWorkflowManagement(
    request(
      "GET",
      "/management/workflow-versions/version-a",
      "admin-token",
    ),
    gateway(),
  );

  assertEquals(list.status, 200);
  assertEquals(detail.status, 200);
});

Deno.test("requires confirmation reason and idempotency before resolving a work order workflow", async () => {
  const missingConfirmation = await routeWorkflowManagement(
    jsonRequest("POST", "/management/work-orders/order-a/resolve-workflow", {
      reason: "Assign the approved receiving flow",
      idempotencyKey: "resolution-a",
    }),
    resolutionGateway(),
  );
  const missingReason = await routeWorkflowManagement(
    jsonRequest("POST", "/management/work-orders/order-a/resolve-workflow", {
      confirmation: "RESOLVE_WORKFLOW",
      idempotencyKey: "resolution-a",
    }),
    resolutionGateway(),
  );
  const missingIdempotency = await routeWorkflowManagement(
    jsonRequest("POST", "/management/work-orders/order-a/resolve-workflow", {
      confirmation: "RESOLVE_WORKFLOW",
      reason: "Assign the approved receiving flow",
    }),
    resolutionGateway(),
  );

  assertEquals(missingConfirmation.status, 400);
  assertEquals(
    (await missingConfirmation.json()).error,
    "confirmation_required",
  );
  assertEquals(missingReason.status, 400);
  assertEquals(missingIdempotency.status, 400);
});

Deno.test("resolves an organization work order from published server rules and persists the selected version", async () => {
  let persisted: Record<string, unknown> | null = null;
  const response = await routeWorkflowManagement(
    jsonRequest("POST", "/management/work-orders/order-a/resolve-workflow", {
      confirmation: "RESOLVE_WORKFLOW",
      reason: "Assign the approved receiving flow",
      idempotencyKey: "resolution-a",
      assignedDeviceId: "44444444-4444-4444-8444-444444444444",
      assignedProfileId: "forged-client-profile",
      workflowVersionId: "forged-client-version",
      organizationId: "forged-client-organization",
    }),
    resolutionGateway({
      listWorkflowBindingRules: async () => [{
        id: "rule-project",
        organization_id: "org-a",
        source: "project",
        mode: "required",
        workflow_version_id: "version-approved",
        match_conditions: [{
          field: "projectId",
          operator: "eq",
          value: "22222222-2222-4222-8222-222222222222",
        }],
        enabled: true,
        active_from: null,
        active_until: null,
        workflow_versions: { status: "published" },
      }],
      applyWorkOrderWorkflowResolution: async (
        _identity: unknown,
        _order: unknown,
        resolution: Record<string, unknown>,
        command: Record<string, unknown>,
      ) => {
        persisted = { resolution, command };
        return { kind: "assigned", assignmentId: "assignment-a" };
      },
    }),
  );

  assertEquals(response.status, 200);
  assertEquals(await response.json(), {
    kind: "assigned",
    assignmentId: "assignment-a",
  });
  assertEquals(persisted, {
    resolution: {
      kind: "assigned",
      source: "project",
      mode: "required",
      workflowVersionId: "version-approved",
      candidateId: "rule-project",
    },
    command: {
      reason: "Assign the approved receiving flow",
      idempotencyKey: "resolution-a",
      assignedProfileId: "33333333-3333-4333-8333-333333333333",
      assignedDeviceId: "44444444-4444-4444-8444-444444444444",
    },
  });
});

Deno.test("persists explicit none and deterministic conflict outcomes", async () => {
  const outcomes: string[] = [];
  const apply = async (
    _identity: unknown,
    _order: unknown,
    resolution: Record<string, unknown>,
  ) => {
    outcomes.push(String(resolution.kind));
    return { kind: resolution.kind };
  };
  const explicitNone = await routeWorkflowManagement(
    jsonRequest("POST", "/management/work-orders/order-a/resolve-workflow", {
      confirmation: "RESOLVE_WORKFLOW",
      reason: "Use the ordinary V9 task path",
      idempotencyKey: "resolution-none",
    }),
    resolutionGateway({
      listWorkflowBindingRules: async () => [{
        id: "rule-none",
        organization_id: "org-a",
        source: "organization_default",
        mode: "none",
        workflow_version_id: null,
        match_conditions: [],
        enabled: true,
        workflow_versions: null,
      }],
      applyWorkOrderWorkflowResolution: apply,
    }),
  );
  const conflict = await routeWorkflowManagement(
    jsonRequest("POST", "/management/work-orders/order-a/resolve-workflow", {
      confirmation: "RESOLVE_WORKFLOW",
      reason: "Record conflicting customer rules for review",
      idempotencyKey: "resolution-conflict",
    }),
    resolutionGateway({
      listWorkflowBindingRules: async () => [
        bindingRule("rule-a", "required", "version-a"),
        bindingRule("rule-b", "optional", "version-b"),
      ],
      applyWorkOrderWorkflowResolution: apply,
    }),
  );

  assertEquals(explicitNone.status, 200);
  assertEquals(conflict.status, 200);
  assertEquals(outcomes, ["none", "conflict"]);
});

Deno.test("ignores malformed cross-organization and unpublished workflow rules", async () => {
  let kind = "";
  const response = await routeWorkflowManagement(
    jsonRequest("POST", "/management/work-orders/order-a/resolve-workflow", {
      confirmation: "RESOLVE_WORKFLOW",
      reason: "Resolve only from eligible published rules",
      idempotencyKey: "resolution-filtered",
    }),
    resolutionGateway({
      listWorkflowBindingRules: async () => [
        {
          ...bindingRule("cross-org", "required", "version-a"),
          organization_id: "org-b",
        },
        {
          ...bindingRule("unpublished", "required", "version-b"),
          workflow_versions: { status: "deprecated" },
        },
        {
          id: "malformed",
          organization_id: "org-a",
          match_conditions: "not-an-array",
        },
      ],
      applyWorkOrderWorkflowResolution: async (
        _identity: unknown,
        _order: unknown,
        resolution: Record<string, unknown>,
      ) => {
        kind = String(resolution.kind);
        return { kind };
      },
    }),
  );

  assertEquals(response.status, 200);
  assertEquals(kind, "none");
});

Deno.test("refuses to rebind a work order that has already started", async () => {
  let persisted = false;
  const response = await routeWorkflowManagement(
    jsonRequest("POST", "/management/work-orders/order-a/resolve-workflow", {
      confirmation: "RESOLVE_WORKFLOW",
      reason: "Attempt to replace an active workflow",
      idempotencyKey: "resolution-started",
    }),
    resolutionGateway({
      getWorkOrder: async () => ({ ...workOrder(), status: "in_progress" }),
      applyWorkOrderWorkflowResolution: async () => {
        persisted = true;
        return {};
      },
    }),
  );

  assertEquals(response.status, 409);
  assertEquals((await response.json()).error, "workflow_already_started");
  assertEquals(persisted, false);
});

function resolutionGateway(
  overrides: Record<string, unknown> = {},
): WorkflowManagementGateway {
  return Object.assign(gateway(), {
    getWorkOrder: async () => workOrder(),
    listWorkflowBindingRules: async () => [],
    applyWorkOrderWorkflowResolution: async (
      _identity: unknown,
      _order: unknown,
      resolution: Record<string, unknown>,
    ) => ({ kind: resolution.kind }),
  }, overrides) as WorkflowManagementGateway;
}

function workOrder(): Record<string, unknown> {
  return {
    id: "order-a",
    organization_id: "org-a",
    source_system: "mvs",
    external_workflow_code: null,
    customer_id: "customer-a",
    project_id: "22222222-2222-4222-8222-222222222222",
    assigned_profile_id: "33333333-3333-4333-8333-333333333333",
    work_order_type: "receiving",
    asset_category: "controller",
    asset_brand: "Honeywell",
    asset_model: "DDC-01",
    fault_type: null,
    priority: "normal",
    risk_level: "low",
    tags: ["pilot"],
    status: "received",
  };
}

function bindingRule(
  id: string,
  mode: "required" | "optional",
  versionId: string,
): Record<string, unknown> {
  return {
    id,
    organization_id: "org-a",
    source: "project",
    mode,
    workflow_version_id: versionId,
    match_conditions: [{
      field: "projectId",
      operator: "eq",
      value: "22222222-2222-4222-8222-222222222222",
    }],
    enabled: true,
    active_from: null,
    active_until: null,
    workflow_versions: { status: "published" },
  };
}

function testSigner(): WorkflowPackageSigner {
  return {
    keyId: "workflow-key-a",
    sign: async (contentHash) => `signed:${contentHash}`,
  };
}
