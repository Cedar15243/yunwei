import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  routeWorkflowManagement,
  type WorkflowManagementGateway,
} from "./workflow-management.ts";

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
