import { resolveProjectScope, type ProjectScope } from "./project-scope.ts";

type Identity = {
  id: string;
  organizationId: string;
  role: "super_admin" | "ops_admin" | "field_engineer" | "remote_expert" | "viewer";
  displayName: string;
};

type TaskListResult = { items: Array<Record<string, unknown>>; nextCursor: string | null };
type DeviceCredential = { token: string; expiresAt: string };
type DeviceBindingCommand = { profileId: string; projectId: string | null; reason: string };
type ReasonedCommand = { reason: string };
type ProfileStatusCommand = { status: "active" | "disabled" | "archived"; reason: string };
type ProjectAccessRole = "manager" | "engineer" | "expert" | "viewer";
type ProjectMembershipCommand = { profileId: string; accessRole: ProjectAccessRole; reason: string };
type TaskDetail = {
  task: Record<string, unknown>;
  events: Array<Record<string, unknown>>;
  messages: Array<Record<string, unknown>>;
  steps: Array<Record<string, unknown>>;
  media: Array<Record<string, unknown>>;
};

export type ManagementGateway = {
  authenticate(token: string): Promise<Identity | null>;
  dashboard(identity: Identity): Promise<Record<string, unknown>>;
  projects(identity: Identity): Promise<Array<Record<string, unknown>>>;
  projectMemberships(identity: Identity, projectId: string): Promise<Array<Record<string, unknown>> | null>;
  people(identity: Identity): Promise<Array<Record<string, unknown>>>;
  devices(identity: Identity): Promise<Array<Record<string, unknown>>>;
  tasks(identity: Identity, filters: URLSearchParams): Promise<TaskListResult>;
  taskDetail(identity: Identity, taskId: string): Promise<TaskDetail | null>;
  retryMedia(identity: Identity, taskId: string, mediaId: string): Promise<boolean>;
  issueDeviceCredential(identity: Identity, deviceId: string, command: ReasonedCommand): Promise<DeviceCredential | null>;
  bindDevice(identity: Identity, deviceId: string, command: DeviceBindingCommand): Promise<boolean>;
  unbindDevice(identity: Identity, deviceId: string, command: ReasonedCommand): Promise<boolean>;
  revokeDevice(identity: Identity, deviceId: string, command: ReasonedCommand): Promise<boolean>;
  setProfileStatus(identity: Identity, profileId: string, command: ProfileStatusCommand): Promise<boolean>;
  grantProjectMembership(identity: Identity, projectId: string, command: ProjectMembershipCommand): Promise<boolean>;
  revokeProjectMembership(identity: Identity, projectId: string, profileId: string, command: ReasonedCommand): Promise<boolean>;
};

const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Content-Type": "application/json; charset=utf-8",
};

export async function routeManagement(request: Request, gateway: ManagementGateway): Promise<Response> {
  const token = bearerToken(request);
  if (!token) return response({ ok: false, error: "unauthorized" }, 401);
  const identity = await gateway.authenticate(token);
  if (!identity) return response({ ok: false, error: "unauthorized" }, 401);

  const url = new URL(request.url);
  const path = routePath(request);
  if (request.method === "GET" && path === "/management/dashboard") {
    return response(await gateway.dashboard(identity));
  }
  if (request.method === "GET" && path === "/management/projects") {
    return response({ items: await gateway.projects(identity) });
  }
  const projectMemberships = path.match(/^\/management\/projects\/([^/]+)\/memberships$/);
  if (request.method === "GET" && projectMemberships) {
    if (!canManageDevices(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const items = await gateway.projectMemberships(identity, projectMemberships[1]);
    return items ? response({ items }) : response({ ok: false, error: "not_found" }, 404);
  }
  if (request.method === "POST" && projectMemberships) {
    if (!canManageDevices(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const body = await requestObject(request);
    const profileId = requiredText(body?.profileId);
    const accessRole = projectAccessRole(body?.accessRole);
    const reason = requiredText(body?.reason);
    if (!profileId || !accessRole || !reason) return response({ ok: false, error: "invalid_request" }, 400);
    const accepted = await gateway.grantProjectMembership(identity, projectMemberships[1], {
      profileId,
      accessRole,
      reason,
    });
    return accepted ? response({ ok: true }) : response({ ok: false, error: "not_found" }, 404);
  }
  const revokeMembership = path.match(/^\/management\/projects\/([^/]+)\/memberships\/([^/]+)\/revoke$/);
  if (request.method === "POST" && revokeMembership) {
    if (!canManageDevices(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const body = await requestObject(request);
    if (body?.confirmation !== "REVOKE_PROJECT_ACCESS") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const reason = requiredText(body.reason);
    if (!reason) return response({ ok: false, error: "invalid_request" }, 400);
    const accepted = await gateway.revokeProjectMembership(
      identity,
      revokeMembership[1],
      revokeMembership[2],
      { reason },
    );
    return accepted ? response({ ok: true }) : response({ ok: false, error: "not_found" }, 404);
  }
  if (request.method === "GET" && path === "/management/people") {
    if (!canManageDevices(identity)) return response({ ok: false, error: "forbidden" }, 403);
    return response({ items: await gateway.people(identity) });
  }
  if (request.method === "GET" && path === "/management/devices") {
    return response({ items: await gateway.devices(identity) });
  }
  if (request.method === "GET" && path === "/management/tasks") {
    return response(await gateway.tasks(identity, url.searchParams));
  }

  const detail = path.match(/^\/management\/tasks\/([^/]+)$/);
  if (request.method === "GET" && detail) {
    const task = await gateway.taskDetail(identity, detail[1]);
    return task ? response(task) : response({ ok: false, error: "not_found" }, 404);
  }

  const retry = path.match(/^\/management\/tasks\/([^/]+)\/media\/([^/]+)\/retry$/);
  if (request.method === "POST" && retry) {
    if (!canManageMedia(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const accepted = await gateway.retryMedia(identity, retry[1], retry[2]);
    return accepted ? response({ ok: true }) : response({ ok: false, error: "not_found" }, 404);
  }
  const credential = path.match(/^\/management\/devices\/([^/]+)\/credentials$/);
  if (request.method === "POST" && credential) {
    if (!canManageCredentials(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const body = await requestObject(request);
    if (body?.confirmation !== "ISSUE_DEVICE_CREDENTIAL") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const reason = requiredText(body.reason);
    if (!reason) return response({ ok: false, error: "invalid_request" }, 400);
    const issued = await gateway.issueDeviceCredential(identity, credential[1], { reason });
    return issued ? response(issued, 201) : response({ ok: false, error: "not_found" }, 404);
  }
  const binding = path.match(/^\/management\/devices\/([^/]+)\/bindings$/);
  if (request.method === "POST" && binding) {
    if (!canManageDevices(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const body = await requestObject(request);
    const profileId = requiredText(body?.profileId);
    const reason = requiredText(body?.reason);
    const projectId = optionalText(body?.projectId);
    if (!profileId || !reason || projectId === undefined) {
      return response({ ok: false, error: "invalid_request" }, 400);
    }
    const accepted = await gateway.bindDevice(identity, binding[1], { profileId, projectId, reason });
    return accepted ? response({ ok: true }) : response({ ok: false, error: "not_found" }, 404);
  }
  const unbind = path.match(/^\/management\/devices\/([^/]+)\/unbind$/);
  if (request.method === "POST" && unbind) {
    if (!canManageDevices(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const body = await requestObject(request);
    if (body?.confirmation !== "UNBIND_DEVICE") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const reason = requiredText(body.reason);
    if (!reason) return response({ ok: false, error: "invalid_request" }, 400);
    const accepted = await gateway.unbindDevice(identity, unbind[1], { reason });
    return accepted ? response({ ok: true }) : response({ ok: false, error: "not_found" }, 404);
  }
  const revoke = path.match(/^\/management\/devices\/([^/]+)\/revoke$/);
  if (request.method === "POST" && revoke) {
    if (!canManageCredentials(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const body = await requestObject(request);
    if (body?.confirmation !== "REVOKE_DEVICE") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const reason = requiredText(body.reason);
    if (!reason) return response({ ok: false, error: "invalid_request" }, 400);
    const accepted = await gateway.revokeDevice(identity, revoke[1], { reason });
    return accepted ? response({ ok: true }) : response({ ok: false, error: "not_found" }, 404);
  }
  const profileStatus = path.match(/^\/management\/people\/([^/]+)\/status$/);
  if (request.method === "POST" && profileStatus) {
    if (!canManageCredentials(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const body = await requestObject(request);
    if (body?.confirmation !== "CHANGE_ACCOUNT_STATUS") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const status = profileStatusValue(body.status);
    const reason = requiredText(body.reason);
    if (!status || !reason) return response({ ok: false, error: "invalid_request" }, 400);
    const accepted = await gateway.setProfileStatus(identity, profileStatus[1], { status, reason });
    return accepted ? response({ ok: true }) : response({ ok: false, error: "not_found" }, 404);
  }
  return response({ ok: false, error: "not_found" }, 404);
}

export function createManagementGateway(supabase: any): ManagementGateway {
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
      } as Identity;
    },
    async dashboard(identity) {
      const scope = await identityProjectScope(supabase, identity);
      if (!scope.allOrganizationProjects && scope.projectIds.length === 0) {
        return { activeTaskCount: 0, onlineDeviceCount: 0, failedMediaCount: 0, recentTasks: [] };
      }
      const taskIds = scope.allOrganizationProjects ? null : await scopedTaskIds(supabase, identity, scope);
      const deviceIds = scope.allOrganizationProjects ? null : await scopedDeviceIds(supabase, identity, scope);
      let activeTasksQuery = supabase.from("maintenance_tasks").select("id", { count: "exact", head: true })
        .eq("organization_id", identity.organizationId).eq("status", "active");
      let recentTasksQuery = supabase.from("maintenance_tasks").select("id, title, status, updated_at")
        .eq("organization_id", identity.organizationId).order("updated_at", { ascending: false }).limit(8);
      if (!scope.allOrganizationProjects) {
        activeTasksQuery = activeTasksQuery.in("project_id", scope.projectIds);
        recentTasksQuery = recentTasksQuery.in("project_id", scope.projectIds);
      }
      let onlineDevicesQuery = supabase.from("glasses_devices").select("id", { count: "exact", head: true })
        .eq("organization_id", identity.organizationId).eq("status", "online");
      if (deviceIds !== null && deviceIds.length > 0) onlineDevicesQuery = onlineDevicesQuery.in("id", deviceIds);
      const onlineDevices = deviceIds !== null && deviceIds.length === 0
        ? Promise.resolve({ count: 0 })
        : onlineDevicesQuery;
      let failedMediaQuery = supabase.from("media_assets").select("id", { count: "exact", head: true })
        .eq("organization_id", identity.organizationId).eq("upload_status", "failed");
      if (taskIds !== null && taskIds.length > 0) failedMediaQuery = failedMediaQuery.in("task_id", taskIds);
      const failedMediaResult = taskIds !== null && taskIds.length === 0
        ? Promise.resolve({ count: 0 })
        : failedMediaQuery;
      const [tasks, devices, failedMedia, recentTasks] = await Promise.all([
        activeTasksQuery,
        onlineDevices,
        failedMediaResult,
        recentTasksQuery,
      ]);
      return {
        activeTaskCount: tasks.count ?? 0,
        onlineDeviceCount: devices.count ?? 0,
        failedMediaCount: failedMedia.count ?? 0,
        recentTasks: recentTasks.data ?? [],
      };
    },
    async projects(identity) {
      const scope = await identityProjectScope(supabase, identity);
      if (!scope.allOrganizationProjects && scope.projectIds.length === 0) return [];
      let query = supabase.from("ops_projects")
        .select("id, title, status, summary, updated_at")
        .eq("organization_id", identity.organizationId)
        .order("updated_at", { ascending: false });
      if (!scope.allOrganizationProjects) query = query.in("id", scope.projectIds);
      const { data, error } = await query;
      if (error) throw error;
      return data ?? [];
    },
    async projectMemberships(identity, projectId) {
      if (!await organizationProjectExists(supabase, identity.organizationId, projectId)) return null;
      const { data, error } = await supabase.from("ops_project_memberships")
        .select("profile_id, access_role, status, reason, granted_by, granted_at, revoked_by, revoked_at, revoke_reason")
        .eq("organization_id", identity.organizationId)
        .eq("project_id", projectId)
        .order("granted_at", { ascending: false });
      if (error) throw error;
      return data ?? [];
    },
    async people(identity) {
      const { data, error } = await supabase.from("ops_profiles")
        .select("id, display_name, role, status, active, status_changed_at, created_at, updated_at")
        .eq("organization_id", identity.organizationId)
        .order("display_name", { ascending: true });
      if (error) throw error;
      return data ?? [];
    },
    async devices(identity) {
      const scope = await identityProjectScope(supabase, identity);
      const deviceIds = scope.allOrganizationProjects ? null : await scopedDeviceIds(supabase, identity, scope);
      if (deviceIds !== null && deviceIds.length === 0) return [];
      let query = supabase.from("glasses_devices")
        .select("id, device_key, display_name, status, assigned_profile_id, model, app_version, mdm_policy_version, mdm_compliance_status, last_seen_at, revoked_at")
        .eq("organization_id", identity.organizationId)
        .order("last_seen_at", { ascending: false, nullsFirst: false });
      if (deviceIds !== null) query = query.in("id", deviceIds);
      const { data, error } = await query;
      if (error) throw error;
      return data ?? [];
    },
    async tasks(identity, filters) {
      const scope = await identityProjectScope(supabase, identity);
      if (!scope.allOrganizationProjects && scope.projectIds.length === 0) {
        return { items: [], nextCursor: null };
      }
      let query = supabase.from("maintenance_tasks")
        .select("id, project_id, title, status, current_step, skill_version, created_at, updated_at")
        .eq("organization_id", identity.organizationId)
        .order("updated_at", { ascending: false })
        .limit(30);
      if (!scope.allOrganizationProjects) query = query.in("project_id", scope.projectIds);
      const status = filters.get("status");
      const projectId = filters.get("projectId");
      if (status) query = query.eq("status", status);
      if (projectId) query = query.eq("project_id", projectId);
      const { data, error } = await query;
      if (error) throw error;
      return { items: data ?? [], nextCursor: null };
    },
    async taskDetail(identity, taskId) {
      const scope = await identityProjectScope(supabase, identity);
      if (!scope.allOrganizationProjects && scope.projectIds.length === 0) return null;
      let taskQuery = supabase.from("maintenance_tasks")
        .select("id, project_id, title, status, current_step, skill_version, created_at, updated_at, completed_at")
        .eq("id", taskId)
        .eq("organization_id", identity.organizationId);
      if (!scope.allOrganizationProjects) taskQuery = taskQuery.in("project_id", scope.projectIds);
      const { data: task, error } = await taskQuery.maybeSingle();
      if (error) throw error;
      if (!task) return null;
      const [eventsResult, mediaResult] = await Promise.all([
        supabase.from("task_events").select("id, event_type, payload, created_at")
          .eq("task_id", taskId).eq("organization_id", identity.organizationId).order("created_at", { ascending: true }),
        supabase.from("media_assets").select("id, event_id, kind, content_type, storage_bucket, file_path, upload_status, failure_reason, captured_at, created_at")
          .eq("task_id", taskId).eq("organization_id", identity.organizationId).order("created_at", { ascending: true }),
      ]);
      if (eventsResult.error) throw eventsResult.error;
      if (mediaResult.error) throw mediaResult.error;
      const media = await Promise.all((mediaResult.data ?? []).map(async (asset: any) => {
        const signed = asset.upload_status === "synced"
          ? await supabase.storage.from(asset.storage_bucket).createSignedUrl(asset.file_path, 300)
          : { data: null };
        return {
          ...asset,
          url: signed.data?.signedUrl ?? null,
          canRetryMedia: canManageMedia(identity) && asset.upload_status === "failed",
        };
      }));
      const events = eventsResult.data ?? [];
      return {
        task,
        events,
        messages: events.filter((event: any) => ["user_message", "voice_transcript", "ai_response"].includes(event.event_type)),
        steps: events.filter((event: any) => event.event_type === "step_changed"),
        media,
      };
    },
    async retryMedia(identity, taskId, mediaId) {
      const { data: asset, error } = await supabase.from("media_assets")
        .select("id").eq("id", mediaId).eq("task_id", taskId).eq("organization_id", identity.organizationId).maybeSingle();
      if (error) throw error;
      if (!asset) return false;
      const { error: updateError } = await supabase.from("media_assets")
        .update({ upload_status: "queued", failure_reason: "", updated_at: new Date().toISOString() }).eq("id", mediaId);
      if (updateError) throw updateError;
      await supabase.from("audit_events").insert({
        organization_id: identity.organizationId,
        actor_profile_id: identity.id,
        action: "media_retry_requested",
        target_type: "media_asset",
        target_id: mediaId,
        metadata: { taskId },
      });
      return true;
    },
    async issueDeviceCredential(identity, deviceId, command) {
      const { data: device, error: deviceError } = await supabase.from("glasses_devices")
        .select("id").eq("id", deviceId).eq("organization_id", identity.organizationId).is("revoked_at", null).maybeSingle();
      if (deviceError) throw deviceError;
      if (!device) return null;
      const token = randomToken();
      const expiresAt = new Date(Date.now() + 90 * 24 * 60 * 60 * 1000).toISOString();
      const { error } = await supabase.rpc("rotate_glasses_device_token", {
        target_device_id: deviceId,
        new_token_hash: await sha256(token),
        new_expires_at: expiresAt,
        actor_id: identity.id,
        reason: command.reason,
      });
      if (error) throw error;
      return { token, expiresAt };
    },
    async bindDevice(identity, deviceId, command) {
      if (!await organizationDeviceExists(supabase, identity.organizationId, deviceId)) return false;
      const { error } = await supabase.rpc("bind_glasses_device", {
        target_device_id: deviceId,
        target_profile_id: command.profileId,
        target_project_id: command.projectId,
        actor_id: identity.id,
        reason: command.reason,
      });
      if (error) throw error;
      return true;
    },
    async unbindDevice(identity, deviceId, command) {
      if (!await organizationDeviceExists(supabase, identity.organizationId, deviceId)) return false;
      const { error } = await supabase.rpc("unbind_glasses_device", {
        target_device_id: deviceId,
        actor_id: identity.id,
        reason: command.reason,
      });
      if (error) throw error;
      return true;
    },
    async revokeDevice(identity, deviceId, command) {
      if (!await organizationDeviceExists(supabase, identity.organizationId, deviceId)) return false;
      const { error } = await supabase.rpc("revoke_glasses_device", {
        target_device_id: deviceId,
        actor_id: identity.id,
        reason: command.reason,
      });
      if (error) throw error;
      return true;
    },
    async setProfileStatus(identity, profileId, command) {
      const { data: profile, error: profileError } = await supabase.from("ops_profiles")
        .select("id").eq("id", profileId).eq("organization_id", identity.organizationId).maybeSingle();
      if (profileError) throw profileError;
      if (!profile) return false;
      const { error } = await supabase.rpc("set_ops_profile_status", {
        target_profile_id: profileId,
        new_status: command.status,
        reason: command.reason,
        actor_id: identity.id,
      });
      if (error) throw error;
      return true;
    },
    async grantProjectMembership(identity, projectId, command) {
      if (!await organizationProjectExists(supabase, identity.organizationId, projectId)) return false;
      const { error } = await supabase.rpc("set_ops_project_membership", {
        target_project_id: projectId,
        target_profile_id: command.profileId,
        new_access_role: command.accessRole,
        actor_id: identity.id,
        reason: command.reason,
      });
      if (error) throw error;
      return true;
    },
    async revokeProjectMembership(identity, projectId, profileId, command) {
      if (!await organizationProjectExists(supabase, identity.organizationId, projectId)) return false;
      const { error } = await supabase.rpc("revoke_ops_project_membership", {
        target_project_id: projectId,
        target_profile_id: profileId,
        actor_id: identity.id,
        reason: command.reason,
      });
      if (error) throw error;
      return true;
    },
  };
}

function bearerToken(request: Request): string | null {
  const value = request.headers.get("Authorization") ?? "";
  return value.startsWith("Bearer ") && value.length > 7 ? value.slice(7) : null;
}

function canManageMedia(identity: Identity): boolean {
  return identity.role === "super_admin" || identity.role === "ops_admin";
}

function canManageDevices(identity: Identity): boolean {
  return identity.role === "super_admin" || identity.role === "ops_admin";
}

function canManageCredentials(identity: Identity): boolean {
  return identity.role === "super_admin";
}

async function requestObject(request: Request): Promise<Record<string, unknown> | null> {
  try {
    const value = await request.json();
    return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

function requiredText(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.trim();
  return normalized ? normalized : null;
}

function optionalText(value: unknown): string | null | undefined {
  if (value === undefined || value === null || value === "") return null;
  return requiredText(value) ?? undefined;
}

function profileStatusValue(value: unknown): ProfileStatusCommand["status"] | null {
  return value === "active" || value === "disabled" || value === "archived" ? value : null;
}

function projectAccessRole(value: unknown): ProjectAccessRole | null {
  return value === "manager" || value === "engineer" || value === "expert" || value === "viewer" ? value : null;
}

async function organizationDeviceExists(supabase: any, organizationId: string, deviceId: string): Promise<boolean> {
  const { data, error } = await supabase.from("glasses_devices")
    .select("id").eq("id", deviceId).eq("organization_id", organizationId).maybeSingle();
  if (error) throw error;
  return Boolean(data);
}

async function organizationProjectExists(supabase: any, organizationId: string, projectId: string): Promise<boolean> {
  const { data, error } = await supabase.from("ops_projects")
    .select("id").eq("id", projectId).eq("organization_id", organizationId).maybeSingle();
  if (error) throw error;
  return Boolean(data);
}

async function identityProjectScope(supabase: any, identity: Identity): Promise<ProjectScope> {
  return await resolveProjectScope(identity, async () => {
    const { data, error } = await supabase.from("ops_project_memberships")
      .select("organization_id, profile_id, project_id, status")
      .eq("organization_id", identity.organizationId)
      .eq("profile_id", identity.id)
      .eq("status", "active");
    if (error) throw error;
    return (data ?? []).map((membership: any) => ({
      organizationId: String(membership.organization_id),
      profileId: String(membership.profile_id),
      projectId: String(membership.project_id),
      status: String(membership.status),
    }));
  });
}

async function scopedTaskIds(supabase: any, identity: Identity, scope: ProjectScope): Promise<string[]> {
  if (scope.allOrganizationProjects || scope.projectIds.length === 0) return [];
  const { data, error } = await supabase.from("maintenance_tasks")
    .select("id")
    .eq("organization_id", identity.organizationId)
    .in("project_id", scope.projectIds);
  if (error) throw error;
  return (data ?? []).map((task: any) => String(task.id));
}

async function scopedDeviceIds(supabase: any, identity: Identity, scope: ProjectScope): Promise<string[]> {
  if (scope.allOrganizationProjects) return [];
  let query = supabase.from("device_bindings")
    .select("device_id")
    .eq("organization_id", identity.organizationId)
    .eq("status", "active");
  if (identity.role === "field_engineer") {
    query = query.eq("profile_id", identity.id);
  } else {
    if (scope.projectIds.length === 0) return [];
    query = query.in("project_id", scope.projectIds);
  }
  const { data, error } = await query;
  if (error) throw error;
  const deviceIds: string[] = (data ?? []).map((binding: any) => String(binding.device_id));
  return Array.from(new Set<string>(deviceIds));
}

function routePath(request: Request): string {
  return new URL(request.url).pathname
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/";
}

function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

async function sha256(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value);
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return Array.from(hash, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers });
}
