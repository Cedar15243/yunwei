export type DeviceSyncIdentity = {
  deviceId: string;
  organizationId: string;
  actorProfileId: string;
};

export type DeviceBootstrapIdentity = DeviceSyncIdentity & {
  bootstrapTokenId: string;
};

export type DeviceAccessSession = {
  accessToken: string;
  expiresAt: string;
};

export type DeviceSyncEvent = {
  localProjectId: string;
  projectTitle: string;
  localTaskId: string;
  taskTitle: string;
  eventType: string;
  payload: Record<string, unknown>;
  occurredAt: string;
  idempotencyKey: string;
};

export type DeviceSyncGateway = {
  authenticateBootstrap(token: string): Promise<DeviceBootstrapIdentity | null>;
  issueSession(identity: DeviceBootstrapIdentity): Promise<DeviceAccessSession>;
  authenticateDevice(token: string): Promise<DeviceSyncIdentity | null>;
  appendEvent(identity: DeviceSyncIdentity, event: DeviceSyncEvent): Promise<{ duplicate: boolean }>;
};

class DeviceSyncHttpError extends Error {
  constructor(
    readonly code: string,
    readonly status: 403 | 409,
  ) {
    super(code);
  }
}

const eventTypes = new Set([
  "task_started", "user_message", "voice_transcript", "photo_captured", "video_recorded",
  "ai_response", "step_changed", "task_completed", "task_closed", "media_upload_failed",
]);

const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, content-type",
  "Content-Type": "application/json; charset=utf-8",
};

export async function routeDeviceSync(request: Request, gateway: DeviceSyncGateway): Promise<Response> {
  const path = routePath(request);
  if (request.method === "POST" && path === "/device-sync/session") {
    const token = bearerToken(request);
    if (!token) return response({ ok: false, error: "unauthorized" }, 401);
    const identity = await gateway.authenticateBootstrap(token);
    if (!identity) return response({ ok: false, error: "unauthorized" }, 401);
    if (!identity.actorProfileId) return response({ ok: false, error: "device_not_bound" }, 403);
    const session = await gateway.issueSession(identity);
    return response({
      ok: true,
      accessToken: session.accessToken,
      expiresAt: session.expiresAt,
      tokenType: "Bearer",
    }, 201, { "Cache-Control": "no-store" });
  }
  if (request.method !== "POST" || path !== "/device-sync/events") {
    return response({ ok: false, error: "not_found" }, 404);
  }
  const token = bearerToken(request);
  if (!token) return response({ ok: false, error: "unauthorized" }, 401);
  const identity = await gateway.authenticateDevice(token);
  if (!identity) return response({ ok: false, error: "unauthorized" }, 401);
  if (!identity.actorProfileId) return response({ ok: false, error: "device_not_bound" }, 403);

  const event = await request.json().catch(() => null);
  if (!isDeviceSyncEvent(event)) return response({ ok: false, error: "invalid_event" }, 400);
  try {
    const result = await gateway.appendEvent(identity, event);
    return response({ ok: true, duplicate: result.duplicate }, 202);
  } catch (error) {
    if (error instanceof DeviceSyncHttpError) {
      return response({ ok: false, error: error.code }, error.status);
    }
    throw error;
  }
}

export function createDeviceSyncGateway(supabase: any): DeviceSyncGateway {
  return {
    async authenticateBootstrap(token) {
      const { data, error } = await supabase.rpc("resolve_glasses_bootstrap_credential", {
        candidate_token_hash: await sha256(token),
      });
      if (error) return null;
      const credential = firstRow(data);
      if (!credential) return null;
      return {
        bootstrapTokenId: String(credential.bootstrap_token_id),
        deviceId: String(credential.device_id),
        organizationId: String(credential.organization_id),
        actorProfileId: String(credential.actor_profile_id ?? ""),
      };
    },
    async issueSession(identity) {
      const accessToken = randomToken();
      const expiresAt = new Date(Date.now() + 15 * 60 * 1000).toISOString();
      const { error } = await supabase.rpc("issue_glasses_device_session", {
        source_bootstrap_token_id: identity.bootstrapTokenId,
        new_token_hash: await sha256(accessToken),
        new_expires_at: expiresAt,
      });
      if (error) throw error;
      return { accessToken, expiresAt };
    },
    async authenticateDevice(token) {
      const { data, error } = await supabase.rpc("resolve_glasses_device_session", {
        candidate_token_hash: await sha256(token),
      });
      if (error) return null;
      const device = firstRow(data);
      if (!device) return null;
      return {
        deviceId: String(device.device_id),
        organizationId: String(device.organization_id),
        actorProfileId: String(device.actor_profile_id ?? ""),
      };
    },
    async appendEvent(identity, event) {
      const project = await resolveProject(supabase, identity, event);
      const task = await upsertTask(supabase, identity, event, project.id);
      const { data: existing, error: existingError } = await supabase
        .from("task_events")
        .select("id")
        .eq("task_id", task.id)
        .eq("idempotency_key", event.idempotencyKey)
        .maybeSingle();
      if (existingError) throw existingError;
      if (existing) return { duplicate: true };
      const { error } = await supabase.from("task_events").insert({
        organization_id: identity.organizationId,
        task_id: task.id,
        actor_profile_id: identity.actorProfileId,
        device_id: identity.deviceId,
        idempotency_key: event.idempotencyKey,
        event_type: event.eventType,
        payload: event.payload,
        occurred_at: event.occurredAt,
      });
      if (!error) return { duplicate: false };
      if (String(error.code ?? "") === "23505") return { duplicate: true };
      throw error;
    },
  };
}

async function resolveProject(
  supabase: any,
  identity: DeviceSyncIdentity,
  event: DeviceSyncEvent,
): Promise<{ id: string }> {
  const { data: existing, error: existingError } = await supabase
    .from("ops_projects")
    .select("id, status")
    .eq("organization_id", identity.organizationId)
    .eq("local_project_id", event.localProjectId)
    .maybeSingle();
  if (existingError) throw existingError;
  if (existing) {
    if (existing.status !== "active") {
      throw new DeviceSyncHttpError("project_inactive", 409);
    }
    if (!await deviceMayAccessProject(supabase, identity, String(existing.id))) {
      throw new DeviceSyncHttpError("project_access_forbidden", 403);
    }
    return { id: String(existing.id) };
  }

  if (event.eventType !== "task_started") {
    throw new DeviceSyncHttpError("project_not_registered", 409);
  }
  if (!await hasCompatibleDeviceBinding(supabase, identity, null)) {
    throw new DeviceSyncHttpError("project_access_forbidden", 403);
  }

  const { data, error } = await supabase.rpc("register_device_project_from_task_start", {
    target_organization_id: identity.organizationId,
    target_actor_profile_id: identity.actorProfileId,
    target_device_id: identity.deviceId,
    target_local_project_id: event.localProjectId,
    target_project_title: event.projectTitle,
  });
  if (error) throw projectRegistrationHttpError(error) ?? error;
  const registered = firstRow(data);
  if (!registered || registered.project_status !== "active") {
    throw new DeviceSyncHttpError("project_inactive", 409);
  }
  const projectId = String(registered.project_id ?? "");
  if (!projectId || !await deviceMayAccessProject(supabase, identity, projectId)) {
    throw new DeviceSyncHttpError("project_access_forbidden", 403);
  }
  return { id: projectId };
}

export async function deviceMayAccessProject(
  supabase: any,
  identity: DeviceSyncIdentity,
  projectId: string,
): Promise<boolean> {
  const [{ data: membership, error: membershipError }, bindingAllowed] = await Promise.all([
    supabase
      .from("ops_project_memberships")
      .select("id")
      .eq("organization_id", identity.organizationId)
      .eq("project_id", projectId)
      .eq("profile_id", identity.actorProfileId)
      .eq("status", "active")
      .maybeSingle(),
    hasCompatibleDeviceBinding(supabase, identity, projectId),
  ]);
  if (membershipError) throw membershipError;
  return Boolean(membership) && bindingAllowed;
}

async function hasCompatibleDeviceBinding(
  supabase: any,
  identity: DeviceSyncIdentity,
  projectId: string | null,
): Promise<boolean> {
  const { data: bindings, error } = await supabase
    .from("device_bindings")
    .select("id, project_id")
    .eq("organization_id", identity.organizationId)
    .eq("device_id", identity.deviceId)
    .eq("profile_id", identity.actorProfileId)
    .eq("status", "active");
  if (error) throw error;
  if (!Array.isArray(bindings)) return false;
  return bindings.some((binding: Record<string, unknown>) =>
    projectId === null ? binding.project_id === null : binding.project_id === null || binding.project_id === projectId
  );
}

async function upsertTask(
  supabase: any,
  identity: DeviceSyncIdentity,
  event: DeviceSyncEvent,
  projectId: string,
): Promise<{ id: string }> {
  const { data, error } = await supabase.from("maintenance_tasks").upsert({
    organization_id: identity.organizationId,
    project_id: projectId,
    created_by: identity.actorProfileId,
    local_task_id: event.localTaskId,
    title: event.taskTitle,
    status: taskStatus(event.eventType),
  }, { onConflict: "organization_id,local_task_id" }).select("id").single();
  if (error) throw error;
  return data;
}

function taskStatus(eventType: string): "active" | "completed" | "closed" {
  if (eventType === "task_completed") return "completed";
  if (eventType === "task_closed") return "closed";
  return "active";
}

function projectRegistrationHttpError(error: unknown): DeviceSyncHttpError | null {
  const message = String((error as Record<string, unknown> | null)?.message ?? error).toLowerCase();
  if (message.includes("project_access_forbidden")) {
    return new DeviceSyncHttpError("project_access_forbidden", 403);
  }
  if (message.includes("project_inactive")) {
    return new DeviceSyncHttpError("project_inactive", 409);
  }
  if (message.includes("project_not_registered")) {
    return new DeviceSyncHttpError("project_not_registered", 409);
  }
  return null;
}

function isDeviceSyncEvent(value: unknown): value is DeviceSyncEvent {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const event = value as Record<string, unknown>;
  return required(event.localProjectId)
    && required(event.localTaskId)
    && required(event.eventType)
    && eventTypes.has(String(event.eventType))
    && required(event.idempotencyKey)
    && typeof event.payload === "object"
    && event.payload !== null
    && !Array.isArray(event.payload)
    && !Number.isNaN(Date.parse(String(event.occurredAt)));
}

function required(value: unknown): boolean {
  return typeof value === "string" && value.trim().length > 0;
}

function bearerToken(request: Request): string | null {
  const value = request.headers.get("Authorization") ?? "";
  return value.startsWith("Bearer ") && value.length > 7 ? value.slice(7) : null;
}

function routePath(request: Request): string {
  return new URL(request.url).pathname
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/";
}

async function sha256(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value);
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return Array.from(hash, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function firstRow(data: unknown): Record<string, unknown> | null {
  if (Array.isArray(data)) return data.length > 0 && data[0] ? data[0] : null;
  return data && typeof data === "object" ? data as Record<string, unknown> : null;
}

function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  const binary = Array.from(bytes, (byte) => String.fromCharCode(byte)).join("");
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function response(body: unknown, status: number, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...headers, ...extraHeaders } });
}
