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

export type WorkflowManagementGateway = {
  authenticate(token: string): Promise<WorkflowManagementIdentity | null>;
  listFieldApps(
    identity: WorkflowManagementIdentity,
  ): Promise<Array<Record<string, unknown>>>;
};

const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Content-Type": "application/json; charset=utf-8",
};

export async function routeWorkflowManagement(
  request: Request,
  gateway: WorkflowManagementGateway,
): Promise<Response> {
  const token = bearerToken(request);
  if (!token) return response({ ok: false, error: "unauthorized" }, 401);
  const identity = await gateway.authenticate(token);
  if (!identity) return response({ ok: false, error: "unauthorized" }, 401);
  if (!canManageWorkflows(identity)) {
    return response({ ok: false, error: "forbidden" }, 403);
  }

  const path = routePath(request);
  if (request.method === "GET" && path === "/management/field-apps") {
    return response({ items: await gateway.listFieldApps(identity) });
  }

  return response({ ok: false, error: "not_found" }, 404);
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

function routePath(request: Request): string {
  return new URL(request.url).pathname
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/";
}

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers });
}
