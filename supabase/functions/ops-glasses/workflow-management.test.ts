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
