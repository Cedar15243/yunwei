import { resolveProjectScope, type ProjectScope } from "./project-scope.ts";
import {
  VoiceprintAdminClientError,
  type VoiceprintAdminClient,
  type VoiceprintAdminProfile,
  type VoiceprintRevokeCommand,
} from "./voiceprint-admin-client.ts";
import {
  IFLYTEK_VOICEPRINT_SERVICE_ID,
  PREVIOUS_STABLE_AI_MODEL,
  PREVIOUS_STABLE_ASR_MODEL,
} from "./model-contract.ts";

type Identity = {
  id: string;
  organizationId: string;
  role: "super_admin" | "ops_admin" | "field_engineer" | "remote_expert" | "viewer";
  displayName: string;
};

type TaskStatus = "active" | "completed" | "closed" | "aborted";
type TaskCursor = { updatedAt: string; id: string };
export type TaskListFilters = {
  status: TaskStatus | null;
  projectId: string | null;
  query: string | null;
  before: TaskCursor | null;
  limit: number;
};
type TaskExportFilters = Omit<TaskListFilters, "before" | "limit">;
type TaskListResult = { items: Array<Record<string, unknown>>; nextCursor: string | null };
type MediaKind = "photo" | "video" | "audio" | "annotation";
type MediaUploadStatus = "local_saved" | "queued" | "uploading" | "synced" | "failed" | "cancelled" | "deleted";
type MediaCursor = { createdAt: string; id: string };
export type MediaListFilters = {
  kind: MediaKind | null;
  uploadStatus: MediaUploadStatus | null;
  taskId: string | null;
  before: MediaCursor | null;
  limit: number;
};
type MediaListResult = { items: Array<Record<string, unknown>>; nextCursor: string | null };
type DeviceCredential = { token: string; expiresAt: string };
type DeviceActivationCode = { activationCode: string; expiresAt: string; deviceId: string };
type DeviceActivationIssueCommand = { reason: string; expiresInSeconds: number };
type DeviceBindingCommand = { profileId: string; projectId: string | null; reason: string };
type ReasonedCommand = { reason: string };
type ProfileStatusCommand = { status: "active" | "disabled" | "archived"; reason: string };
type ProfileRole = Identity["role"];
type InvitePersonCommand = { email: string; displayName: string; role: ProfileRole; reason: string };
type ProfileRoleCommand = { role: ProfileRole; reason: string };
type InvitedPerson = { profileId: string; email: string; status: "invited" };
type VoiceprintManagementProfile = VoiceprintAdminProfile & { canRevoke: boolean };
type VoiceprintListResult = { items: VoiceprintManagementProfile[]; auditChainValid: boolean };
type ProjectAccessRole = "manager" | "engineer" | "expert" | "viewer";
type ProjectMembershipCommand = { profileId: string; accessRole: ProjectAccessRole; reason: string };
type EquipmentStatus = "normal" | "attention" | "maintenance" | "decommissioned";
type EquipmentWriteFields = {
  equipmentKey: string;
  system: string;
  brand: string;
  model: string;
  quantity: number;
  status: EquipmentStatus;
  lastInspectionAt: string | null;
  faultCount: number;
  repairCount: number;
  keyParameter: string;
  projectIds: string[];
  reason: string;
  idempotencyKey: string;
};
type EquipmentCreateCommand = EquipmentWriteFields;
type EquipmentUpdateCommand = EquipmentWriteFields & { expectedUpdatedAt: string };
type TaskDetail = {
  task: Record<string, unknown>;
  events: Array<Record<string, unknown>>;
  messages: Array<Record<string, unknown>>;
  steps: Array<Record<string, unknown>>;
  media: Array<Record<string, unknown>>;
};
type ProjectRecord = {
  project: Record<string, unknown>;
  tasks: Array<Record<string, unknown>>;
  latestMemory: Record<string, unknown> | null;
  skillVersions: string[];
};
type AuditEventResult = {
  items: Array<Record<string, unknown>>;
  nextCursor: string | null;
};
type ManagementRuntimeContract = {
  contentSyncConfigured: boolean;
  voiceprintAdminConfigured: boolean;
  deviceActivationBackendConfigured: boolean;
};
type ManagementGatewayOptions = {
  accountRecoveryRedirectUrl?: string;
};
type SystemStatusResult = Record<string, unknown>;

export type ManagementGateway = {
  authenticate(token: string): Promise<Identity | null>;
  dashboard(identity: Identity): Promise<Record<string, unknown>>;
  auditEvents(identity: Identity, filters: URLSearchParams): Promise<AuditEventResult>;
  systemStatus(identity: Identity): Promise<SystemStatusResult>;
  projects(identity: Identity): Promise<Array<Record<string, unknown>>>;
  projectRecord(identity: Identity, projectId: string): Promise<ProjectRecord | null>;
  projectMemberships(identity: Identity, projectId: string): Promise<Array<Record<string, unknown>> | null>;
  people(identity: Identity): Promise<Array<Record<string, unknown>>>;
  devices(identity: Identity): Promise<Array<Record<string, unknown>>>;
  equipment?(identity: Identity): Promise<Array<Record<string, unknown>>>;
  createEquipment?(
    identity: Identity,
    command: EquipmentCreateCommand,
  ): Promise<Record<string, unknown> | null>;
  updateEquipment?(
    identity: Identity,
    equipmentId: string,
    command: EquipmentUpdateCommand,
  ): Promise<Record<string, unknown> | null>;
  voiceprints(identity: Identity): Promise<VoiceprintListResult>;
  revokeVoiceprint(
    identity: Identity,
    profileId: string,
    command: VoiceprintRevokeCommand,
  ): Promise<VoiceprintManagementProfile | null>;
  tasks(identity: Identity, filters: TaskListFilters): Promise<TaskListResult>;
  taskExport(identity: Identity, filters: TaskExportFilters): Promise<string>;
  media(identity: Identity, filters: MediaListFilters): Promise<MediaListResult>;
  taskDetail(identity: Identity, taskId: string): Promise<TaskDetail | null>;
  retryMedia(identity: Identity, taskId: string, mediaId: string): Promise<boolean>;
  issueDeviceCredential(identity: Identity, deviceId: string, command: ReasonedCommand): Promise<DeviceCredential | null>;
  issueDeviceActivation?(
    identity: Identity,
    deviceId: string,
    command: DeviceActivationIssueCommand,
  ): Promise<DeviceActivationCode | null>;
  bindDevice(identity: Identity, deviceId: string, command: DeviceBindingCommand): Promise<boolean>;
  unbindDevice(identity: Identity, deviceId: string, command: ReasonedCommand): Promise<boolean>;
  revokeDevice(identity: Identity, deviceId: string, command: ReasonedCommand): Promise<boolean>;
  setProfileStatus(identity: Identity, profileId: string, command: ProfileStatusCommand): Promise<boolean>;
  sendAccountRecovery(identity: Identity, profileId: string, command: ReasonedCommand): Promise<boolean>;
  invitePerson(identity: Identity, command: InvitePersonCommand): Promise<InvitedPerson | null>;
  setProfileRole(identity: Identity, profileId: string, command: ProfileRoleCommand): Promise<boolean>;
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
  if (request.method === "GET" && path === "/management/audit-events") {
    if (!canViewOperations(identity)) return response({ ok: false, error: "forbidden" }, 403);
    return response(await gateway.auditEvents(identity, url.searchParams));
  }
  if (request.method === "GET" && path === "/management/system-status") {
    if (!canViewOperations(identity)) return response({ ok: false, error: "forbidden" }, 403);
    return response(await gateway.systemStatus(identity));
  }
  if (request.method === "GET" && path === "/management/projects") {
    return response({ items: await gateway.projects(identity) });
  }
  const projectRecord = path.match(/^\/management\/projects\/([^/]+)\/record$/);
  if (request.method === "GET" && projectRecord) {
    const record = await gateway.projectRecord(identity, projectRecord[1]);
    return record ? response(record) : response({ ok: false, error: "not_found" }, 404);
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
  if (request.method === "GET" && path === "/management/equipment") {
    if (!canManageDevices(identity) || !gateway.equipment) {
      return response({ ok: false, error: "forbidden" }, 403);
    }
    return response({ items: await gateway.equipment(identity) });
  }
  if (request.method === "POST" && path === "/management/equipment") {
    if (!canManageDevices(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const body = await requestObject(request);
    if (body?.confirmation !== "CREATE_EQUIPMENT") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const command = equipmentCreateCommand(body);
    if (!command) return response({ ok: false, error: "invalid_request" }, 400);
    if (!gateway.createEquipment) return response({ ok: false, error: "equipment_management_unavailable" }, 503);
    try {
      const created = await gateway.createEquipment(identity, command);
      return created ? response(created, 201) : response({ ok: false, error: "not_found" }, 404);
    } catch (error) {
      return managementOperationErrorResponse(error);
    }
  }
  const equipmentUpdate = path.match(/^\/management\/equipment\/([^/]+)$/);
  if (request.method === "PUT" && equipmentUpdate) {
    if (!canManageDevices(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const equipmentId = uuidValue(equipmentUpdate[1]);
    if (!equipmentId) return response({ ok: false, error: "invalid_request" }, 400);
    const body = await requestObject(request);
    if (body?.confirmation !== "UPDATE_EQUIPMENT") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const command = equipmentUpdateCommand(body);
    if (!command) return response({ ok: false, error: "invalid_request" }, 400);
    if (!gateway.updateEquipment) return response({ ok: false, error: "equipment_management_unavailable" }, 503);
    try {
      const updated = await gateway.updateEquipment(identity, equipmentId, command);
      return updated ? response(updated) : response({ ok: false, error: "not_found" }, 404);
    } catch (error) {
      return managementOperationErrorResponse(error);
    }
  }
  if (request.method === "GET" && path === "/management/voiceprints") {
    if (!canManageDevices(identity)) return response({ ok: false, error: "forbidden" }, 403);
    try {
      return response(await gateway.voiceprints(identity));
    } catch (error) {
      return voiceprintErrorResponse(error);
    }
  }
  if (request.method === "GET" && path === "/management/tasks") {
    const filters = taskListFilters(url.searchParams);
    if (!filters) return response({ ok: false, error: "invalid_request" }, 400);
    return response(await gateway.tasks(identity, filters));
  }
  if (request.method === "GET" && path === "/management/tasks/export") {
    const filters = parseTaskExportFilters(url.searchParams);
    if (!filters) return response({ ok: false, error: "invalid_request" }, 400);
    try {
      return csvResponse(await gateway.taskExport(identity, filters), "v9-task-records.csv");
    } catch (error) {
      return managementOperationErrorResponse(error);
    }
  }
  if (request.method === "GET" && path === "/management/media") {
    const filters = mediaListFilters(url.searchParams);
    if (!filters) return response({ ok: false, error: "invalid_request" }, 400);
    return response(await gateway.media(identity, filters));
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
  const activationCode = path.match(/^\/management\/devices\/([^/]+)\/activation-codes$/);
  if (request.method === "POST" && activationCode) {
    if (!canManageCredentials(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const body = await requestObject(request);
    if (body?.confirmation !== "ISSUE_DEVICE_ACTIVATION") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const reason = requiredText(body.reason);
    const expiresInSeconds = integerInRange(body.expiresInSeconds, 300, 900);
    if (!reason || expiresInSeconds === null) {
      return response({ ok: false, error: "invalid_request" }, 400);
    }
    if (!gateway.issueDeviceActivation) {
      return response({ ok: false, error: "activation_unavailable" }, 503);
    }
    const issued = await gateway.issueDeviceActivation(identity, activationCode[1], {
      reason,
      expiresInSeconds,
    });
    return issued
      ? response(issued, 201, { "Cache-Control": "no-store" })
      : response({ ok: false, error: "not_found" }, 404);
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
  const profileRecovery = path.match(/^\/management\/people\/([^/]+)\/recovery$/);
  if (request.method === "POST" && profileRecovery) {
    if (!canManageCredentials(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const body = await requestObject(request);
    if (body?.confirmation !== "SEND_ACCOUNT_RECOVERY") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const reason = requiredText(body.reason);
    if (!reason) return response({ ok: false, error: "invalid_request" }, 400);
    try {
      const accepted = await gateway.sendAccountRecovery(identity, profileRecovery[1], { reason });
      return accepted ? response({ ok: true }) : response({ ok: false, error: "not_found" }, 404);
    } catch (error) {
      return managementOperationErrorResponse(error);
    }
  }
  if (request.method === "POST" && path === "/management/people/invitations") {
    if (!canManageCredentials(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const body = await requestObject(request);
    if (body?.confirmation !== "INVITE_ACCOUNT") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const email = normalizedEmail(body.email);
    const displayName = boundedDisplayName(body.displayName);
    const role = profileRoleValue(body.role);
    const reason = requiredText(body.reason);
    if (!email || !displayName || !role || !reason) {
      return response({ ok: false, error: "invalid_request" }, 400);
    }
    try {
      const invited = await gateway.invitePerson(identity, { email, displayName, role, reason });
      return invited
        ? response(invited, 201, { "Cache-Control": "no-store" })
        : response({ ok: false, error: "conflict" }, 409);
    } catch (error) {
      return managementOperationErrorResponse(error);
    }
  }
  const profileRole = path.match(/^\/management\/people\/([^/]+)\/role$/);
  if (request.method === "POST" && profileRole) {
    if (!canManageCredentials(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const body = await requestObject(request);
    if (body?.confirmation !== "CHANGE_ACCOUNT_ROLE") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const role = profileRoleValue(body.role);
    const reason = requiredText(body.reason);
    if (!role || !reason) return response({ ok: false, error: "invalid_request" }, 400);
    try {
      const accepted = await gateway.setProfileRole(identity, profileRole[1], { role, reason });
      return accepted ? response({ ok: true }) : response({ ok: false, error: "not_found" }, 404);
    } catch (error) {
      return managementOperationErrorResponse(error);
    }
  }
  const voiceprintRevoke = path.match(/^\/management\/voiceprints\/([^/]+)\/revoke$/);
  if (request.method === "POST" && voiceprintRevoke) {
    if (!canManageCredentials(identity)) return response({ ok: false, error: "forbidden" }, 403);
    const body = await requestObject(request);
    if (body?.confirmation !== "REVOKE_VOICEPRINT") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const reason = requiredText(body.reason);
    const idempotencyKey = identifierText(body.idempotencyKey);
    if (!reason || !idempotencyKey) return response({ ok: false, error: "invalid_request" }, 400);
    try {
      const revoked = await gateway.revokeVoiceprint(identity, voiceprintRevoke[1], {
        reason,
        idempotencyKey,
      });
      return revoked ? response(revoked) : response({ ok: false, error: "not_found" }, 404);
    } catch (error) {
      return voiceprintErrorResponse(error);
    }
  }
  return response({ ok: false, error: "not_found" }, 404);
}

export function createManagementGateway(
  supabase: any,
  voiceprintAdmin: VoiceprintAdminClient,
  runtimeContract: ManagementRuntimeContract = {
    contentSyncConfigured: false,
    voiceprintAdminConfigured: false,
    deviceActivationBackendConfigured: false,
  },
  options: ManagementGatewayOptions = {},
): ManagementGateway {
  const gateway: ManagementGateway = {
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
    async auditEvents(identity, filters) {
      const limit = boundedLimit(filters.get("limit"));
      let query = supabase.from("audit_events")
        .select("id, actor_profile_id, action, target_type, target_id, metadata, created_at")
        .eq("organization_id", identity.organizationId)
        .order("created_at", { ascending: false })
        .limit(limit + 1);
      const action = boundedFilter(filters.get("action"));
      const targetType = boundedFilter(filters.get("targetType"));
      const before = filters.get("before")?.trim() ?? "";
      if (action) query = query.eq("action", action);
      if (targetType) query = query.eq("target_type", targetType);
      if (before && Number.isFinite(Date.parse(before))) query = query.lt("created_at", before);
      const { data, error } = await query;
      if (error) throw error;
      const rows = Array.isArray(data) ? data : [];
      const hasMore = rows.length > limit;
      const page = rows.slice(0, limit);
      const actorIds = [...new Set(page.map((row: any) => String(row.actor_profile_id ?? "")).filter(Boolean))];
      const actors = new Map<string, Record<string, unknown>>();
      if (actorIds.length > 0) {
        const actorResult = await supabase.from("ops_profiles")
          .select("id, display_name, role")
          .eq("organization_id", identity.organizationId)
          .in("id", actorIds);
        if (actorResult.error) throw actorResult.error;
        for (const actor of actorResult.data ?? []) {
          actors.set(String(actor.id), {
            id: String(actor.id),
            displayName: String(actor.display_name ?? ""),
            role: String(actor.role ?? ""),
          });
        }
      }
      return {
        items: page.map((row: any) => ({
          id: String(row.id),
          actor: actors.get(String(row.actor_profile_id ?? "")) ?? {
            id: null,
            displayName: "系统",
            role: "system",
          },
          action: String(row.action ?? ""),
          targetType: String(row.target_type ?? ""),
          targetId: String(row.target_id ?? ""),
          metadata: redactAuditMetadata(row.metadata),
          createdAt: String(row.created_at ?? ""),
        })),
        nextCursor: hasMore && page.length > 0 ? String(page[page.length - 1].created_at) : null,
      };
    },
    async systemStatus(identity) {
      const [devicesResult, manifestsResult] = await Promise.all([
        supabase.from("glasses_devices")
          .select("id, device_key, display_name, status, app_version, last_seen_at")
          .eq("organization_id", identity.organizationId)
          .order("display_name", { ascending: true }),
        supabase.from("device_content_manifests")
          .select("id, device_id, project_id, manifest_version, etag, status, generated_at, expires_at")
          .eq("organization_id", identity.organizationId)
          .order("manifest_version", { ascending: false })
          .limit(2000),
      ]);
      if (devicesResult.error) throw devicesResult.error;
      if (manifestsResult.error) throw manifestsResult.error;
      const latestManifestByDevice = new Map<string, any>();
      for (const manifest of manifestsResult.data ?? []) {
        const deviceId = String(manifest.device_id ?? "");
        if (deviceId && !latestManifestByDevice.has(deviceId)) latestManifestByDevice.set(deviceId, manifest);
      }
      const now = Date.now();
      const items: Array<Record<string, unknown> & { manifestStatus: string }> = (devicesResult.data ?? []).map((device: any) => {
        const manifest = latestManifestByDevice.get(String(device.id));
        const status = contentDistributionStatus(manifest, now);
        return {
          deviceId: String(device.id),
          deviceKey: String(device.device_key ?? ""),
          displayName: String(device.display_name ?? device.device_key ?? device.id),
          deviceStatus: String(device.status ?? ""),
          appVersion: device.app_version ?? null,
          lastSeenAt: device.last_seen_at ?? null,
          manifestStatus: status,
          manifestVersion: manifest?.manifest_version ?? null,
          manifestEtag: manifest?.etag ?? null,
          projectId: manifest?.project_id ?? null,
          generatedAt: manifest?.generated_at ?? null,
          expiresAt: manifest?.expires_at ?? null,
        };
      });
      return {
        modelContract: {
          mainAiModel: PREVIOUS_STABLE_AI_MODEL,
          realtimeAsrModel: PREVIOUS_STABLE_ASR_MODEL,
          wakeEngine: "iflytek-aikit-previous",
          voiceprintService: `iflytek/${IFLYTEK_VOICEPRINT_SERVICE_ID}`,
          locked: true,
        },
        integrations: runtimeContract,
        contentDistribution: {
          totalDevices: items.length,
          healthyDevices: items.filter((item) => item.manifestStatus === "healthy").length,
          issueCount: items.filter((item) => item.manifestStatus !== "healthy").length,
          items,
        },
        generatedAt: new Date().toISOString(),
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
    async projectRecord(identity, projectId) {
      const scope = await identityProjectScope(supabase, identity);
      if (!scope.allOrganizationProjects && !scope.projectIds.includes(projectId)) return null;
      const { data: project, error: projectError } = await supabase.from("ops_projects")
        .select("id, title, status, summary, updated_at")
        .eq("organization_id", identity.organizationId)
        .eq("id", projectId)
        .maybeSingle();
      if (projectError) throw projectError;
      if (!project) return null;

      const { data: taskData, error: taskError } = await supabase.from("maintenance_tasks")
        .select("id, project_id, title, status, current_step, skill_version, created_at, updated_at, completed_at")
        .eq("organization_id", identity.organizationId)
        .eq("project_id", projectId)
        .order("updated_at", { ascending: false });
      if (taskError) throw taskError;
      const tasks = Array.isArray(taskData) ? taskData : [];
      const taskIds = tasks.map((task: any) => String(task.id)).filter(Boolean);
      let events: Array<Record<string, unknown>> = [];
      if (taskIds.length > 0) {
        const { data, error } = await supabase.from("task_events")
          .select("id, task_id, event_type, payload, occurred_at, created_at")
          .eq("organization_id", identity.organizationId)
          .in("task_id", taskIds)
          .in("event_type", ["task_completed", "task_closed"])
          .order("occurred_at", { ascending: false })
          .order("created_at", { ascending: false })
          .limit(5000);
        if (error) throw error;
        events = Array.isArray(data) ? data : [];
      }
      const taskById = new Map(tasks.map((task: any) => [String(task.id), task]));
      const latestMemory = events
        .map((event) => projectMemorySnapshot(event, taskById.get(String(event.task_id))))
        .find((memory): memory is Record<string, unknown> => memory !== null) ?? null;
      const skillVersions = [...new Set(tasks
        .map((task: any) => String(task.skill_version ?? "").trim())
        .filter(Boolean))];
      return { project, tasks, latestMemory, skillVersions };
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
        .select("id, email, display_name, role, status, active, status_changed_at, created_at, updated_at")
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
      const devices = Array.isArray(data) ? data : [];
      if (devices.length === 0) return [];
      const ids = devices.map((device: any) => String(device.id));
      const [credentialsResult, sessionsResult, manifestsResult] = await Promise.all([
        supabase.from("glasses_device_tokens")
          .select("id, device_id, status, issued_at, expires_at, revoked_at, revoke_reason")
          .in("device_id", ids)
          .order("issued_at", { ascending: false })
          .limit(5000),
        supabase.from("glasses_device_sessions")
          .select("id, device_id, status, issued_at, last_used_at, expires_at, revoked_at, revoke_reason")
          .in("device_id", ids)
          .order("issued_at", { ascending: false })
          .limit(5000),
        supabase.from("device_content_manifests")
          .select("id, device_id, project_id, manifest_version, etag, status, generated_at, expires_at")
          .in("device_id", ids)
          .order("manifest_version", { ascending: false })
          .limit(5000),
      ]);
      if (credentialsResult.error) throw credentialsResult.error;
      if (sessionsResult.error) throw sessionsResult.error;
      if (manifestsResult.error) throw manifestsResult.error;
      const credentialByDevice = latestRowByDevice(credentialsResult.data);
      const sessionByDevice = latestRowByDevice(sessionsResult.data);
      const manifestByDevice = latestRowByDevice(manifestsResult.data);
      const now = Date.now();
      return devices.map((device: any) => {
        const deviceId = String(device.id);
        const credential = credentialByDevice.get(deviceId);
        const session = sessionByDevice.get(deviceId);
        const manifest = manifestByDevice.get(deviceId);
        const credentialStatus = expiringRecordStatus(credential, now);
        const sessionStatus = expiringRecordStatus(session, now);
        const manifestStatus = contentDistributionStatus(manifest, now);
        const sync = deviceSyncHealth(device, credentialStatus, sessionStatus, manifestStatus, now);
        return {
          ...device,
          credential_status: credentialStatus,
          credential_issued_at: credential?.issued_at ?? null,
          credential_expires_at: credential?.expires_at ?? null,
          session_status: sessionStatus,
          session_last_used_at: session?.last_used_at ?? null,
          session_expires_at: session?.expires_at ?? null,
          manifest_version: manifest?.manifest_version ?? null,
          manifest_status: manifestStatus,
          manifest_generated_at: manifest?.generated_at ?? null,
          manifest_expires_at: manifest?.expires_at ?? null,
          sync_health: sync.health,
          sync_issue: sync.issue,
        };
      });
    },
    async equipment(identity) {
      const scope = await identityProjectScope(supabase, identity);
      const [{ data: equipmentRows, error: equipmentError }, { data: linkRows, error: linkError }] =
        await Promise.all([
          supabase.from("ops_equipment")
            .select("id, equipment_key, system, brand, model, quantity, status, last_inspection_at, fault_count, repair_count, key_parameter, updated_at")
            .eq("organization_id", identity.organizationId)
            .order("system", { ascending: true })
            .order("equipment_key", { ascending: true }),
          supabase.from("ops_equipment_projects")
            .select("equipment_id, project_id")
            .eq("organization_id", identity.organizationId),
        ]);
      if (equipmentError) throw equipmentError;
      if (linkError) throw linkError;
      const links = Array.isArray(linkRows) ? linkRows : [];
      const projectIds = [...new Set(links.map((row: any) => String(row.project_id ?? "")).filter(Boolean))]
        .filter((projectId) => scope.allOrganizationProjects || scope.projectIds.includes(projectId));
      const projects = new Map<string, Record<string, unknown>>();
      if (projectIds.length > 0) {
        let projectQuery = supabase.from("ops_projects")
          .select("id, local_project_id, title, status")
          .eq("organization_id", identity.organizationId)
          .in("id", projectIds);
        const { data, error } = await projectQuery;
        if (error) throw error;
        for (const row of data ?? []) {
          projects.set(String(row.id), {
            projectId: String(row.id),
            localProjectId: String(row.local_project_id ?? ""),
            title: String(row.title ?? ""),
            status: String(row.status ?? ""),
          });
        }
      }
      const taskCounts = new Map<string, number>();
      if (projectIds.length > 0) {
        const { data, error } = await supabase.from("maintenance_tasks")
          .select("project_id")
          .eq("organization_id", identity.organizationId)
          .in("project_id", projectIds);
        if (error) throw error;
        for (const row of data ?? []) {
          const projectId = String(row.project_id ?? "");
          if (projectId) taskCounts.set(projectId, (taskCounts.get(projectId) ?? 0) + 1);
        }
      }
      const projectLinks = new Map<string, Array<Record<string, unknown>>>();
      for (const row of links) {
        const equipmentId = String(row.equipment_id ?? "");
        const projectId = String(row.project_id ?? "");
        const project = projects.get(projectId);
        if (!equipmentId || !project) continue;
        const values = projectLinks.get(equipmentId) ?? [];
        values.push({ ...project, taskCount: taskCounts.get(projectId) ?? 0 });
        projectLinks.set(equipmentId, values);
      }
      return (Array.isArray(equipmentRows) ? equipmentRows : []).flatMap((row: any) => {
        const linkedProjects = projectLinks.get(String(row.id)) ?? [];
        if (linkedProjects.length === 0) return [];
        return [{
          id: String(row.id),
          equipmentKey: String(row.equipment_key ?? ""),
          system: String(row.system ?? ""),
          brand: String(row.brand ?? ""),
          model: String(row.model ?? ""),
          quantity: Number(row.quantity ?? 0),
          status: String(row.status ?? ""),
          lastInspectionAt: row.last_inspection_at ?? null,
          faultCount: Number(row.fault_count ?? 0),
          repairCount: Number(row.repair_count ?? 0),
          keyParameter: String(row.key_parameter ?? ""),
          linkedProjects,
          updatedAt: row.updated_at ?? null,
        }];
      });
    },
    async createEquipment(identity, command) {
      const equipmentId = await manageEquipmentTransaction(supabase, identity, "create", null, command);
      const catalog = await gateway.equipment!(identity);
      return catalog.find((item) => item.id === equipmentId) ?? null;
    },
    async updateEquipment(identity, equipmentId, command) {
      const updatedEquipmentId = await manageEquipmentTransaction(
        supabase,
        identity,
        "update",
        equipmentId,
        command,
      );
      const catalog = await gateway.equipment!(identity);
      return catalog.find((item) => item.id === updatedEquipmentId) ?? null;
    },
    async voiceprints(identity) {
      const result = await voiceprintAdmin.list(identity.organizationId);
      return {
        auditChainValid: result.auditChainValid,
        items: result.items.map((profile) => ({
          ...profile,
          canRevoke: identity.role === "super_admin" && !["deleted", "revoked"].includes(profile.status),
        })),
      };
    },
    async revokeVoiceprint(identity, profileId, command) {
      const profile = await voiceprintAdmin.revoke(identity.organizationId, profileId, command);
      const { error } = await supabase.from("audit_events").insert({
        organization_id: identity.organizationId,
        actor_profile_id: identity.id,
        action: "voiceprint_revoked",
        target_type: "voiceprint_profile",
        target_id: profile.profileId,
        metadata: {
          reason: command.reason,
          idempotencyKey: command.idempotencyKey,
          userId: profile.userId,
          deviceId: profile.deviceId,
          provider: profile.provider,
        },
      });
      if (error && error.code !== "23505") throw error;
      return { ...profile, canRevoke: false };
    },
    async tasks(identity, filters) {
      const rows = await selectTaskRecords(supabase, identity, filters, filters.limit + 1);
      const hasMore = rows.length > filters.limit;
      const items = rows.slice(0, filters.limit);
      const last = items[items.length - 1] as Record<string, unknown> | undefined;
      return {
        items,
        nextCursor: hasMore && last ? encodeTaskCursor(last) : null,
      };
    },
    async taskExport(identity, filters) {
      const rows = await selectTaskRecords(supabase, identity, { ...filters, before: null }, 5_001);
      if (rows.length > 5_000) {
        throw new ManagementOperationError(413, "task_export_too_large");
      }
      return taskRecordsCsv(rows);
    },
    async media(identity, filters) {
      const scope = await identityProjectScope(supabase, identity);
      if (!scope.allOrganizationProjects && scope.projectIds.length === 0) {
        return { items: [], nextCursor: null };
      }
      const taskIds = scope.allOrganizationProjects ? null : await scopedTaskIds(supabase, identity, scope);
      if (taskIds !== null && taskIds.length === 0) return { items: [], nextCursor: null };
      let query = supabase.from("media_assets")
        .select("id, task_id, kind, content_type, storage_bucket, file_path, upload_status, failure_reason, captured_at, created_at")
        .eq("organization_id", identity.organizationId)
        .order("created_at", { ascending: false })
        .order("id", { ascending: false })
        .limit(filters.limit + 1);
      if (taskIds !== null) query = query.in("task_id", taskIds);
      if (filters.kind) query = query.eq("kind", filters.kind);
      if (filters.uploadStatus) query = query.eq("upload_status", filters.uploadStatus);
      if (filters.taskId) query = query.eq("task_id", filters.taskId);
      if (filters.before) {
        query = query.or(
          `created_at.lt.${filters.before.createdAt},and(created_at.eq.${filters.before.createdAt},id.lt.${filters.before.id})`,
        );
      }
      const { data, error } = await query;
      if (error) throw error;
      const rows = Array.isArray(data) ? data : [];
      const hasMore = rows.length > filters.limit;
      const page = rows.slice(0, filters.limit);
      const pageTaskIds = Array.from(new Set(page.map((row: any) => String(row.task_id ?? "")).filter(Boolean)));
      const taskById = new Map<string, Record<string, unknown>>();
      if (pageTaskIds.length > 0) {
        let taskQuery = supabase.from("maintenance_tasks")
          .select("id, project_id, title")
          .eq("organization_id", identity.organizationId)
          .in("id", pageTaskIds);
        if (!scope.allOrganizationProjects) taskQuery = taskQuery.in("project_id", scope.projectIds);
        const taskResult = await taskQuery;
        if (taskResult.error) throw taskResult.error;
        for (const task of taskResult.data ?? []) taskById.set(String(task.id), task);
      }
      const items = await Promise.all(page.map(async (asset: any) => {
        const task = taskById.get(String(asset.task_id ?? ""));
        const signed = asset.upload_status === "synced"
          ? await supabase.storage.from(asset.storage_bucket).createSignedUrl(asset.file_path, 300)
          : { data: null };
        return {
          id: String(asset.id),
          task_id: String(asset.task_id),
          task_title: String(task?.title ?? ""),
          project_id: String(task?.project_id ?? ""),
          kind: String(asset.kind),
          content_type: String(asset.content_type ?? ""),
          upload_status: String(asset.upload_status),
          failure_reason: String(asset.failure_reason ?? ""),
          captured_at: asset.captured_at ?? null,
          created_at: asset.created_at ?? null,
          url: signed.data?.signedUrl ?? null,
          canRetryMedia: canManageMedia(identity) && asset.upload_status === "failed",
        };
      }));
      const last = page[page.length - 1] as Record<string, unknown> | undefined;
      return {
        items,
        nextCursor: hasMore && last ? encodeMediaCursor(last) : null,
      };
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
        supabase.from("media_assets").select("id, event_id, kind, content_type, storage_bucket, file_path, duration_seconds, upload_status, failure_reason, captured_at, created_at")
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
    async issueDeviceActivation(identity, deviceId, command) {
      const activationCode = randomActivationCode();
      const expiresAt = new Date(
        Date.now() + command.expiresInSeconds * 1000,
      ).toISOString();
      const { data, error } = await supabase.rpc("issue_device_activation_code", {
        target_device_id: deviceId,
        activation_code_hash: await sha256(activationCode),
        expires_at: expiresAt,
        actor_id: identity.id,
        reason: command.reason,
      });
      if (error) throw error;
      if (data !== true) return null;
      return { activationCode, expiresAt, deviceId };
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
    async sendAccountRecovery(identity, profileId, command) {
      const redirectTo = accountRecoveryRedirectUrl(options.accountRecoveryRedirectUrl);
      const { data: profile, error: profileError } = await supabase.from("ops_profiles")
        .select("id, email, status")
        .eq("id", profileId)
        .eq("organization_id", identity.organizationId)
        .maybeSingle();
      if (profileError) throw profileError;
      if (!profile) return false;
      const email = normalizedEmail(profile.email);
      if (!email || profile.status === "invited" || profile.status === "archived") {
        throw new ManagementOperationError(409, "account_recovery_unavailable");
      }
      const { error: auditError } = await supabase.from("audit_events").insert({
        organization_id: identity.organizationId,
        actor_profile_id: identity.id,
        action: "profile_recovery_requested",
        target_type: "ops_profile",
        target_id: profileId,
        metadata: {
          reason: command.reason,
          delivery: "supabase_auth_email",
          accountStatus: String(profile.status ?? ""),
        },
      });
      if (auditError) {
        throw new ManagementOperationError(503, "account_recovery_audit_failed");
      }
      const { error: recoveryError } = await supabase.auth.resetPasswordForEmail(email, { redirectTo });
      if (recoveryError) {
        throw new ManagementOperationError(503, "account_recovery_delivery_failed");
      }
      return true;
    },
    async invitePerson(identity, command) {
      const { data: existing, error: existingError } = await supabase.from("ops_profiles")
        .select("id")
        .eq("organization_id", identity.organizationId)
        .eq("email", command.email)
        .maybeSingle();
      if (existingError) throw existingError;
      if (existing) return null;

      const { data: invited, error: inviteError } = await supabase.auth.admin.inviteUserByEmail(
        command.email,
        { data: { display_name: command.displayName, organization_id: identity.organizationId } },
      );
      if (inviteError || !invited?.user?.id) {
        throw new ManagementOperationError(409, "account_invite_rejected");
      }
      const profileId = String(invited.user.id);
      const { error: profileError } = await supabase.from("ops_profiles").insert({
        id: profileId,
        organization_id: identity.organizationId,
        email: command.email,
        display_name: command.displayName,
        role: command.role,
        status: "invited",
        active: false,
        lifecycle_reason: command.reason,
      });
      if (profileError) {
        await supabase.auth.admin.deleteUser(profileId).catch(() => undefined);
        throw new ManagementOperationError(409, "account_profile_creation_failed");
      }
      const { error: auditError } = await supabase.from("audit_events").insert({
        organization_id: identity.organizationId,
        actor_profile_id: identity.id,
        action: "profile_invited",
        target_type: "ops_profile",
        target_id: profileId,
        metadata: {
          email: command.email,
          role: command.role,
          reason: command.reason,
        },
      });
      if (auditError) {
        await rollbackInvitedAccount(supabase, profileId);
        throw new ManagementOperationError(503, "account_audit_write_failed");
      }
      return { profileId, email: command.email, status: "invited" as const };
    },
    async setProfileRole(identity, profileId, command) {
      const { data: profile, error: profileError } = await supabase.from("ops_profiles")
        .select("id").eq("id", profileId).eq("organization_id", identity.organizationId).maybeSingle();
      if (profileError) throw profileError;
      if (!profile) return false;
      const { error } = await supabase.rpc("set_ops_profile_role", {
        target_profile_id: profileId,
        new_role: command.role,
        actor_id: identity.id,
        reason: command.reason,
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
  return gateway;
}

async function manageEquipmentTransaction(
  supabase: any,
  identity: Identity,
  operation: "create" | "update",
  equipmentId: string | null,
  command: EquipmentCreateCommand | EquipmentUpdateCommand,
): Promise<string> {
  const expectedUpdatedAt = "expectedUpdatedAt" in command ? command.expectedUpdatedAt : null;
  const commandHash = await sha256(JSON.stringify({
    operation,
    equipmentId,
    equipmentKey: command.equipmentKey,
    system: command.system,
    brand: command.brand,
    model: command.model,
    quantity: command.quantity,
    status: command.status,
    lastInspectionAt: command.lastInspectionAt,
    faultCount: command.faultCount,
    repairCount: command.repairCount,
    keyParameter: command.keyParameter,
    projectIds: [...command.projectIds].sort(),
    expectedUpdatedAt,
    reason: command.reason,
  }));
  const { data, error } = await supabase.rpc("manage_ops_equipment", {
    operation_name: operation,
    target_equipment_id: equipmentId,
    target_equipment_key: command.equipmentKey,
    target_system: command.system,
    target_brand: command.brand,
    target_model: command.model,
    target_quantity: command.quantity,
    target_status: command.status,
    target_last_inspection_at: command.lastInspectionAt,
    target_fault_count: command.faultCount,
    target_repair_count: command.repairCount,
    target_key_parameter: command.keyParameter,
    target_project_ids: command.projectIds,
    expected_updated_at: expectedUpdatedAt,
    actor_id: identity.id,
    reason: command.reason,
    command_idempotency_key: command.idempotencyKey,
    command_hash: commandHash,
  });
  if (error) throw equipmentManagementOperationError(error);
  const resultId = uuidValue(data);
  if (!resultId) throw new ManagementOperationError(503, "equipment_management_response_invalid");
  return resultId;
}

function equipmentManagementOperationError(error: unknown): ManagementOperationError {
  const record = error && typeof error === "object" ? error as Record<string, unknown> : {};
  const message = [record.message, record.details, record.hint, record.code]
    .filter((value) => typeof value === "string")
    .join(" ");
  const controlledCodes = [
    "equipment_version_conflict",
    "equipment_key_conflict",
    "equipment_idempotency_conflict",
    "equipment_project_not_found",
    "equipment_not_found",
    "equipment_management_forbidden",
    "equipment_operation_invalid",
    "equipment_reason_invalid",
    "equipment_idempotency_key_invalid",
    "equipment_command_hash_invalid",
    "equipment_payload_invalid",
    "equipment_projects_invalid",
    "equipment_create_contract_invalid",
    "equipment_update_contract_invalid",
  ] as const;
  const code = controlledCodes.find((candidate) => message.includes(candidate));
  if (code === "equipment_version_conflict" || code === "equipment_key_conflict" ||
      code === "equipment_idempotency_conflict") {
    return new ManagementOperationError(409, code);
  }
  if (code === "equipment_project_not_found" || code === "equipment_not_found") {
    return new ManagementOperationError(404, code);
  }
  if (code === "equipment_management_forbidden") {
    return new ManagementOperationError(403, code);
  }
  if (code) return new ManagementOperationError(400, code);
  if (
    message.includes("manage_ops_equipment") || message.includes("PGRST202") ||
    message.includes("42883") || message.toLowerCase().includes("schema cache")
  ) {
    return new ManagementOperationError(503, "equipment_management_unavailable");
  }
  return new ManagementOperationError(503, "equipment_management_failed");
}

export class ManagementOperationError extends Error {
  constructor(public readonly status: number, public readonly code: string) {
    super(code);
    this.name = "ManagementOperationError";
  }
}

async function rollbackInvitedAccount(supabase: any, profileId: string): Promise<void> {
  try {
    await supabase.from("ops_profiles").delete().eq("id", profileId);
  } catch {
    // Cleanup is best effort; the operation remains failed closed and is auditable by the caller.
  }
  try {
    await supabase.auth.admin.deleteUser(profileId);
  } catch {
    // Auth cleanup is best effort; no credentials are returned to the client.
  }
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

function canViewOperations(identity: Identity): boolean {
  return identity.role === "super_admin" || identity.role === "ops_admin";
}

function canManageCredentials(identity: Identity): boolean {
  return identity.role === "super_admin";
}

function boundedLimit(value: string | null): number {
  const parsed = Number(value ?? "50");
  return Number.isInteger(parsed) ? Math.min(100, Math.max(1, parsed)) : 50;
}

function boundedFilter(value: string | null): string | null {
  const normalized = value?.trim() ?? "";
  return normalized && normalized.length <= 120 ? normalized : null;
}

function taskListFilters(filters: URLSearchParams): TaskListFilters | null {
  if (!hasOnlySingleQueryParams(filters, new Set(["status", "projectId", "query", "before", "limit"]))) {
    return null;
  }
  const statusValue = filters.get("status");
  const status = statusValue === null ? null : taskStatusValue(statusValue);
  const projectValue = filters.get("projectId");
  const projectId = projectValue === null ? null : uuidValue(projectValue);
  const queryValue = filters.get("query");
  const query = queryValue === null ? null : boundedFilter(queryValue);
  const beforeValue = filters.get("before");
  const before = beforeValue === null ? null : decodeTaskCursor(beforeValue);
  const limitValue = filters.get("limit");
  const limit = limitValue === null ? 30 : integerQueryValue(limitValue, 1, 100);
  if (
    (statusValue !== null && status === null) ||
    (projectValue !== null && projectId === null) ||
    (queryValue !== null && query === null) ||
    (beforeValue !== null && before === null) ||
    limit === null
  ) {
    return null;
  }
  return { status, projectId, query, before, limit };
}

function parseTaskExportFilters(filters: URLSearchParams): TaskExportFilters | null {
  if (!hasOnlySingleQueryParams(filters, new Set(["status", "projectId", "query"]))) return null;
  const statusValue = filters.get("status");
  const status = statusValue === null ? null : taskStatusValue(statusValue);
  const projectValue = filters.get("projectId");
  const projectId = projectValue === null ? null : uuidValue(projectValue);
  const queryValue = filters.get("query");
  const query = queryValue === null ? null : boundedFilter(queryValue);
  if (
    (statusValue !== null && status === null) ||
    (projectValue !== null && projectId === null) ||
    (queryValue !== null && query === null)
  ) {
    return null;
  }
  return { status, projectId, query };
}

function hasOnlySingleQueryParams(filters: URLSearchParams, allowed: Set<string>): boolean {
  for (const key of filters.keys()) {
    if (!allowed.has(key) || filters.getAll(key).length !== 1) return false;
  }
  return true;
}

function taskStatusValue(value: string): TaskStatus | null {
  return value === "active" || value === "completed" || value === "closed" || value === "aborted"
    ? value
    : null;
}

function uuidValue(value: string): string | null {
  const normalized = value.trim().toLowerCase();
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(normalized)
    ? normalized
    : null;
}

function integerQueryValue(value: string, minimum: number, maximum: number): number | null {
  if (!/^\d+$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed >= minimum && parsed <= maximum ? parsed : null;
}

function decodeTaskCursor(value: string): TaskCursor | null {
  try {
    const base64 = value.replaceAll("-", "+").replaceAll("_", "/");
    const decoded = JSON.parse(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, "=")));
    if (!decoded || typeof decoded !== "object" || Array.isArray(decoded)) return null;
    const updatedAt = typeof decoded.updatedAt === "string" ? decoded.updatedAt : "";
    const id = typeof decoded.id === "string" ? uuidValue(decoded.id) : null;
    if (!id || !Number.isFinite(Date.parse(updatedAt))) return null;
    return { updatedAt: new Date(updatedAt).toISOString(), id };
  } catch {
    return null;
  }
}

function encodeTaskCursor(row: Record<string, unknown>): string {
  const updatedAt = String(row.updated_at ?? "");
  const id = String(row.id ?? "");
  if (!Number.isFinite(Date.parse(updatedAt)) || !uuidValue(id)) {
    throw new ManagementOperationError(500, "task_cursor_invalid");
  }
  return btoa(JSON.stringify({ updatedAt: new Date(updatedAt).toISOString(), id }))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
}

function mediaListFilters(filters: URLSearchParams): MediaListFilters | null {
  if (!hasOnlySingleQueryParams(filters, new Set(["kind", "uploadStatus", "taskId", "before", "limit"]))) {
    return null;
  }
  const kindValue = filters.get("kind");
  const kind = kindValue === null ? null : mediaKindValue(kindValue);
  const statusValue = filters.get("uploadStatus");
  const uploadStatus = statusValue === null ? null : mediaUploadStatusValue(statusValue);
  const taskValue = filters.get("taskId");
  const taskId = taskValue === null ? null : uuidValue(taskValue);
  const beforeValue = filters.get("before");
  const before = beforeValue === null ? null : decodeMediaCursor(beforeValue);
  const limitValue = filters.get("limit");
  const limit = limitValue === null ? 30 : integerQueryValue(limitValue, 1, 100);
  if (
    (kindValue !== null && kind === null) ||
    (statusValue !== null && uploadStatus === null) ||
    (taskValue !== null && taskId === null) ||
    (beforeValue !== null && before === null) ||
    limit === null
  ) {
    return null;
  }
  return { kind, uploadStatus, taskId, before, limit };
}

function mediaKindValue(value: string): MediaKind | null {
  return value === "photo" || value === "video" || value === "audio" || value === "annotation"
    ? value
    : null;
}

function mediaUploadStatusValue(value: string): MediaUploadStatus | null {
  return value === "local_saved" || value === "queued" || value === "uploading" || value === "synced" ||
      value === "failed" || value === "cancelled" || value === "deleted"
    ? value
    : null;
}

function decodeMediaCursor(value: string): MediaCursor | null {
  try {
    const base64 = value.replaceAll("-", "+").replaceAll("_", "/");
    const decoded = JSON.parse(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, "=")));
    if (!decoded || typeof decoded !== "object" || Array.isArray(decoded)) return null;
    const createdAt = typeof decoded.createdAt === "string" ? decoded.createdAt : "";
    const id = typeof decoded.id === "string" ? uuidValue(decoded.id) : null;
    if (!id || !Number.isFinite(Date.parse(createdAt))) return null;
    return { createdAt: new Date(createdAt).toISOString(), id };
  } catch {
    return null;
  }
}

function encodeMediaCursor(row: Record<string, unknown>): string {
  const createdAt = String(row.created_at ?? "");
  const id = String(row.id ?? "");
  if (!Number.isFinite(Date.parse(createdAt)) || !uuidValue(id)) {
    throw new ManagementOperationError(500, "media_cursor_invalid");
  }
  return btoa(JSON.stringify({ createdAt: new Date(createdAt).toISOString(), id }))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
}

function redactAuditMetadata(value: unknown): unknown {
  if (Array.isArray(value)) return value.map((item) => redactAuditMetadata(item));
  if (!value || typeof value !== "object") return value ?? null;
  const result: Record<string, unknown> = {};
  for (const [key, nested] of Object.entries(value as Record<string, unknown>)) {
    const normalized = key.toLowerCase().replace(/[^a-z0-9]/g, "");
    if (
      normalized.includes("token") || normalized.includes("secret") ||
      normalized.includes("password") || normalized.includes("authorization") ||
      normalized.includes("apikey") || normalized.includes("credential") ||
      normalized.includes("activationcode") || normalized.includes("audiobase64") ||
      normalized === "url" || normalized.includes("privatekey")
    ) {
      result[key] = "[REDACTED]";
    } else {
      result[key] = redactAuditMetadata(nested);
    }
  }
  return result;
}

function contentDistributionStatus(
  manifest: Record<string, unknown> | null | undefined,
  now: number,
): "healthy" | "missing" | "expired" | "revoked" | "superseded" {
  if (!manifest) return "missing";
  const status = String(manifest.status ?? "");
  if (status === "revoked") return "revoked";
  if (status !== "active") return "superseded";
  const expiresAt = Date.parse(String(manifest.expires_at ?? ""));
  return Number.isFinite(expiresAt) && expiresAt > now ? "healthy" : "expired";
}

function latestRowByDevice(rows: unknown): Map<string, Record<string, unknown>> {
  const result = new Map<string, Record<string, unknown>>();
  for (const row of Array.isArray(rows) ? rows : []) {
    const record = row as Record<string, unknown>;
    const deviceId = String(record.device_id ?? "");
    if (deviceId && !result.has(deviceId)) result.set(deviceId, record);
  }
  return result;
}

function expiringRecordStatus(
  row: Record<string, unknown> | undefined,
  now: number,
): "active" | "missing" | "expired" | "revoked" {
  if (!row) return "missing";
  if (String(row.status ?? "") === "revoked" || row.revoked_at) return "revoked";
  const expiresAt = Date.parse(String(row.expires_at ?? ""));
  if (String(row.status ?? "") !== "active" || !Number.isFinite(expiresAt) || expiresAt <= now) return "expired";
  return "active";
}

function deviceSyncHealth(
  device: Record<string, unknown>,
  credentialStatus: "active" | "missing" | "expired" | "revoked",
  sessionStatus: "active" | "missing" | "expired" | "revoked",
  manifestStatus: "healthy" | "missing" | "expired" | "revoked" | "superseded",
  now: number,
): { health: "healthy" | "attention" | "offline" | "blocked"; issue: string } {
  if (device.revoked_at) return { health: "blocked", issue: "device_revoked" };
  if (device.mdm_compliance_status === "noncompliant") return { health: "blocked", issue: "mdm_noncompliant" };
  if (credentialStatus !== "active") return { health: "blocked", issue: `credential_${credentialStatus}` };
  const lastSeenAt = Date.parse(String(device.last_seen_at ?? ""));
  if (device.status !== "online" || !Number.isFinite(lastSeenAt) || now - lastSeenAt > 20 * 60 * 1000) {
    return { health: "offline", issue: Number.isFinite(lastSeenAt) ? "device_offline" : "device_never_seen" };
  }
  if (device.mdm_compliance_status !== "compliant") return { health: "attention", issue: "mdm_unknown" };
  if (sessionStatus !== "active") return { health: "attention", issue: `session_${sessionStatus}` };
  if (manifestStatus !== "healthy") return { health: "attention", issue: `manifest_${manifestStatus}` };
  return { health: "healthy", issue: "" };
}

function projectMemorySnapshot(
  event: Record<string, unknown>,
  task: Record<string, unknown> | undefined,
): Record<string, unknown> | null {
  const payloadValue = event.payload;
  if (!task || !payloadValue || typeof payloadValue !== "object" || Array.isArray(payloadValue)) return null;
  const payload = payloadValue as Record<string, unknown>;
  const taskStatus = String(task.status ?? "");
  const eventType = String(event.event_type ?? "");
  if (
    payload.humanConfirmed !== true || payload.phase !== "COMPLETED" ||
    String(payload.taskStatus ?? "") !== taskStatus ||
    (eventType === "task_completed" && taskStatus !== "completed") ||
    (eventType === "task_closed" && taskStatus !== "closed")
  ) {
    return null;
  }
  const revision = integerInRange(payload.projectMemoryRevision, 0, Number.MAX_SAFE_INTEGER);
  const summary = requiredText(payload.summary);
  const confirmedFacts = projectMemoryList(payload.confirmedFacts);
  const excludedFacts = projectMemoryList(payload.excludedFacts);
  const risks = projectMemoryList(payload.risks);
  if (
    revision === null || !summary || summary.length > 8000 ||
    confirmedFacts === null || excludedFacts === null || risks === null
  ) {
    return null;
  }
  return {
    revision,
    summary,
    confirmedFacts,
    excludedFacts,
    risks,
    taskId: String(event.task_id ?? ""),
    eventId: String(event.id ?? ""),
    updatedAt: String(event.occurred_at ?? event.created_at ?? ""),
  };
}

function projectMemoryList(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.length > 100) return null;
  const items: string[] = [];
  for (const item of value) {
    const text = requiredText(item);
    if (!text || text.length > 1000) return null;
    items.push(text);
  }
  return items;
}

function equipmentCreateCommand(body: Record<string, unknown>): EquipmentCreateCommand | null {
  return equipmentWriteFields(body);
}

function equipmentUpdateCommand(body: Record<string, unknown>): EquipmentUpdateCommand | null {
  const fields = equipmentWriteFields(body);
  const expectedUpdatedAt = isoTimestamp(body.expectedUpdatedAt);
  return fields && expectedUpdatedAt ? { ...fields, expectedUpdatedAt } : null;
}

function equipmentWriteFields(body: Record<string, unknown>): EquipmentWriteFields | null {
  const equipmentKey = identifierText(body.equipmentKey);
  const system = boundedText(body.system, 120);
  const brand = boundedText(body.brand, 120);
  const model = boundedText(body.model, 120);
  const quantity = integerInRange(body.quantity, 0, 100_000);
  const status = equipmentStatusValue(body.status);
  const lastInspectionAt = nullableIsoTimestamp(body.lastInspectionAt);
  const faultCount = integerInRange(body.faultCount, 0, 1_000_000);
  const repairCount = integerInRange(body.repairCount, 0, 1_000_000);
  const keyParameter = optionalBoundedText(body.keyParameter, 1_000);
  const projectIds = uuidList(body.projectIds, 1, 100);
  const reason = boundedText(body.reason, 240, 3);
  const idempotencyKey = identifierText(body.idempotencyKey);
  if (
    !equipmentKey || equipmentKey.length > 120 || !system || !brand || !model || quantity === null || !status ||
    lastInspectionAt === undefined || faultCount === null || repairCount === null || keyParameter === null ||
    !projectIds || !reason || !idempotencyKey || idempotencyKey.length < 8
  ) {
    return null;
  }
  return {
    equipmentKey,
    system,
    brand,
    model,
    quantity,
    status,
    lastInspectionAt,
    faultCount,
    repairCount,
    keyParameter,
    projectIds,
    reason,
    idempotencyKey,
  };
}

function equipmentStatusValue(value: unknown): EquipmentStatus | null {
  return value === "normal" || value === "attention" || value === "maintenance" || value === "decommissioned"
    ? value
    : null;
}

function uuidList(value: unknown, minimum: number, maximum: number): string[] | null {
  if (!Array.isArray(value) || value.length < minimum || value.length > maximum) return null;
  const values: string[] = [];
  for (const item of value) {
    if (typeof item !== "string") return null;
    const id = uuidValue(item);
    if (!id) return null;
    if (!values.includes(id)) values.push(id);
  }
  return values.length >= minimum ? values : null;
}

function isoTimestamp(value: unknown): string | null {
  if (typeof value !== "string" || !Number.isFinite(Date.parse(value))) return null;
  return new Date(value).toISOString();
}

function nullableIsoTimestamp(value: unknown): string | null | undefined {
  if (value === undefined || value === null || value === "") return null;
  return isoTimestamp(value) ?? undefined;
}

function boundedText(value: unknown, maximum: number, minimum = 1): string | null {
  const normalized = requiredText(value);
  return normalized && normalized.length >= minimum && normalized.length <= maximum ? normalized : null;
}

function optionalBoundedText(value: unknown, maximum: number): string | null {
  if (value === undefined || value === null || value === "") return "";
  return typeof value === "string" && value.trim().length <= maximum ? value.trim() : null;
}

function accountRecoveryRedirectUrl(value: string | undefined): string {
  const normalized = value?.trim() ?? "";
  if (!normalized) {
    throw new ManagementOperationError(503, "account_recovery_redirect_unconfigured");
  }
  try {
    const url = new URL(normalized);
    if (url.protocol !== "https:" || url.username || url.password) throw new Error("unsafe redirect");
    return url.toString();
  } catch {
    throw new ManagementOperationError(503, "account_recovery_redirect_invalid");
  }
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

function integerInRange(value: unknown, minimum: number, maximum: number): number | null {
  return typeof value === "number" && Number.isInteger(value)
      && value >= minimum && value <= maximum
    ? value
    : null;
}

function identifierText(value: unknown): string | null {
  const normalized = requiredText(value);
  return normalized && /^[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}$/.test(normalized) ? normalized : null;
}

function voiceprintErrorResponse(error: unknown): Response {
  if (error instanceof VoiceprintAdminClientError) {
    const status = [400, 401, 404, 409, 423, 502, 503].includes(error.status) ? error.status : 502;
    return response({ ok: false, error: error.code }, status);
  }
  throw error;
}

function profileStatusValue(value: unknown): ProfileStatusCommand["status"] | null {
  return value === "active" || value === "disabled" || value === "archived" ? value : null;
}

function profileRoleValue(value: unknown): ProfileRole | null {
  return value === "super_admin" || value === "ops_admin" || value === "field_engineer" ||
      value === "remote_expert" || value === "viewer" ? value : null;
}

function normalizedEmail(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const email = value.trim().toLowerCase();
  return email.length <= 320 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ? email : null;
}

function boundedDisplayName(value: unknown): string | null {
  const name = requiredText(value);
  return name && name.length <= 120 ? name : null;
}

function managementOperationErrorResponse(error: unknown): Response {
  if (error instanceof ManagementOperationError) return response({ ok: false, error: error.code }, error.status);
  throw error;
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

async function selectTaskRecords(
  supabase: any,
  identity: Identity,
  filters: Omit<TaskListFilters, "limit">,
  rowLimit: number,
): Promise<Array<Record<string, unknown>>> {
  const scope = await identityProjectScope(supabase, identity);
  if (!scope.allOrganizationProjects && scope.projectIds.length === 0) return [];
  let query = supabase.from("maintenance_tasks")
    .select("id, project_id, title, status, current_step, skill_version, created_at, updated_at")
    .eq("organization_id", identity.organizationId)
    .order("updated_at", { ascending: false })
    .order("id", { ascending: false })
    .limit(rowLimit);
  if (!scope.allOrganizationProjects) query = query.in("project_id", scope.projectIds);
  if (filters.status) query = query.eq("status", filters.status);
  if (filters.projectId) query = query.eq("project_id", filters.projectId);
  if (filters.query) query = query.ilike("title", `%${escapeLikePattern(filters.query)}%`);
  if (filters.before) {
    query = query.or(
      `updated_at.lt.${filters.before.updatedAt},and(updated_at.eq.${filters.before.updatedAt},id.lt.${filters.before.id})`,
    );
  }
  const { data, error } = await query;
  if (error) throw error;
  return Array.isArray(data) ? data : [];
}

function escapeLikePattern(value: string): string {
  return value.replace(/[\\%_]/g, (match) => `\\${match}`);
}

function taskRecordsCsv(rows: Array<Record<string, unknown>>): string {
  const header = ["任务ID", "项目ID", "任务标题", "状态", "当前步骤", "技能版本", "创建时间", "更新时间"];
  const lines = rows.map((row) => [
    row.id,
    row.project_id,
    row.title,
    row.status,
    row.current_step,
    row.skill_version,
    row.created_at,
    row.updated_at,
  ].map(csvCell).join(","));
  return `\uFEFF${header.join(",")}\r\n${lines.length ? `${lines.join("\r\n")}\r\n` : ""}`;
}

function csvCell(value: unknown): string {
  let text = value === null || value === undefined ? "" : String(value);
  if (/^[=+\-@]/.test(text)) text = `'${text}`;
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
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

function randomActivationCode(): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = crypto.getRandomValues(new Uint8Array(12));
  let payload = "";
  for (const byte of bytes) payload += alphabet[byte % alphabet.length];
  return `HF9-${payload.slice(0, 4)}-${payload.slice(4, 8)}-${payload.slice(8, 12)}`;
}

async function sha256(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value);
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return Array.from(hash, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function response(body: unknown, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...headers, ...extraHeaders } });
}

function csvResponse(body: string, filename: string): Response {
  return new Response(body, {
    status: 200,
    headers: {
      ...headers,
      "Content-Type": "text/csv; charset=utf-8",
      "Content-Disposition": `attachment; filename="${filename}"`,
      "Cache-Control": "no-store",
    },
  });
}
