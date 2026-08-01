import { validateWorkflowDraft } from "./workflow-domain.ts";

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

function recordValue(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function invalidRequest(): Response {
  return response({ ok: false, error: "invalid_request" }, 400);
}

function routePath(request: Request): string {
  return new URL(request.url).pathname
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/";
}

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers });
}
