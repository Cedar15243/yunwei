import type {
  FieldApp,
  FieldAppCreateCommand,
  WorkOrder,
  WorkOrderStatus,
  WorkOrderWorkflowResolution,
  WorkOrderWorkflowResolutionCommand,
  WorkflowBindingRule,
  WorkflowBindingRuleCommand,
  WorkflowBindingRuleUpdateCommand,
  WorkflowCatalog,
  WorkflowCreateCommand,
  WorkflowDefinition,
  WorkflowDraft,
  WorkflowPublishCommand,
  WorkflowValidationResult,
  WorkflowVersion,
} from "./workflow-types";
import type {
  KnowledgeCaseDraftResult,
  KnowledgeAttachmentOperationResult,
  KnowledgeEntry,
  KnowledgeGrant,
  KnowledgeStatus,
  KnowledgeVersion,
  SkillAssignment,
  SkillDefinition,
  SkillKnowledgeApi,
  SkillStatus,
  SkillVersion,
} from "./skill-knowledge-types";

export type TaskStatus = "active" | "completed" | "closed" | "aborted";
export type TaskListFilters = {
  status?: TaskStatus;
  projectId?: string;
  query?: string;
  before?: string;
  limit?: number;
};
export type TaskExportFilters = Pick<TaskListFilters, "status" | "projectId" | "query">;
export type PagedResult<T> = { items: T[]; nextCursor: string | null };

export type Dashboard = {
  activeTaskCount: number;
  onlineDeviceCount: number;
  failedMediaCount: number;
  recentTasks: TaskSummary[];
};

export type Project = { id: string; title: string; status: string; summary?: string; updated_at?: string };
export type ProjectAccessRole = "manager" | "engineer" | "expert" | "viewer";
export type ProjectMembership = {
  profile_id: string;
  access_role: ProjectAccessRole;
  status: "active" | "revoked";
  reason?: string;
  granted_at?: string;
  revoked_at?: string | null;
};
export type TaskSummary = {
  id: string;
  project_id?: string;
  title: string;
  status: TaskStatus;
  current_step?: string;
  skill_version?: string;
  created_at?: string;
  updated_at?: string;
};
export type TaskEvent = { id: string; event_type: string; payload: Record<string, unknown>; created_at: string };
export type MediaAsset = {
  id: string;
  kind: "photo" | "video" | "audio" | "annotation";
  url: string | null;
  upload_status: string;
  failure_reason?: string;
  captured_at?: string;
  created_at?: string;
  canRetryMedia: boolean;
};
export type MediaUploadStatus = "local_saved" | "queued" | "uploading" | "synced" | "failed" | "cancelled" | "deleted";
export type MediaListFilters = {
  kind?: MediaAsset["kind"];
  uploadStatus?: MediaUploadStatus;
  taskId?: string;
  before?: string;
  limit?: number;
};
export type MediaRecord = MediaAsset & {
  task_id: string;
  task_title: string;
  project_id: string;
  content_type: string;
};
export type TaskDetail = {
  task: TaskSummary & { completed_at?: string };
  events: TaskEvent[];
  messages: TaskEvent[];
  steps: TaskEvent[];
  media: MediaAsset[];
};
export type ProjectMemorySnapshot = {
  revision: number;
  summary: string;
  confirmedFacts: string[];
  excludedFacts: string[];
  risks: string[];
  taskId: string;
  eventId: string;
  updatedAt: string;
};
export type ProjectRecord = {
  project: Project;
  tasks: Array<TaskSummary & { completed_at?: string | null }>;
  latestMemory: ProjectMemorySnapshot | null;
  skillVersions: string[];
};
export type PersonRole = "super_admin" | "ops_admin" | "field_engineer" | "remote_expert" | "viewer";
export type PersonStatus = "invited" | "active" | "disabled" | "archived";
export type Person = {
  id: string;
  email?: string | null;
  display_name: string;
  role: PersonRole;
  status: PersonStatus;
  active: boolean;
  status_changed_at?: string | null;
  created_at?: string;
  updated_at?: string;
};
export type Device = {
  id: string;
  device_key: string;
  display_name: string;
  status: string;
  assigned_profile_id?: string | null;
  model?: string | null;
  app_version?: string | null;
  mdm_policy_version?: string | null;
  mdm_compliance_status?: string | null;
  last_seen_at?: string | null;
  revoked_at?: string | null;
  credential_status?: "active" | "missing" | "expired" | "revoked";
  credential_issued_at?: string | null;
  credential_expires_at?: string | null;
  session_status?: "active" | "missing" | "expired" | "revoked";
  session_last_used_at?: string | null;
  session_expires_at?: string | null;
  manifest_version?: number | null;
  manifest_status?: "healthy" | "missing" | "expired" | "revoked" | "superseded";
  manifest_generated_at?: string | null;
  manifest_expires_at?: string | null;
  sync_health?: "healthy" | "attention" | "offline" | "blocked";
  sync_issue?: string;
};
export type EquipmentStatus = "normal" | "attention" | "maintenance" | "decommissioned";
export type EquipmentMemory = {
  id: string;
  equipmentKey: string;
  system: string;
  brand: string;
  model: string;
  quantity: number;
  status: EquipmentStatus | string;
  lastInspectionAt?: string | null;
  faultCount: number;
  repairCount: number;
  keyParameter: string;
  linkedProjects: Array<{
    projectId: string;
    localProjectId: string;
    title: string;
    status: string;
    taskCount: number;
  }>;
  updatedAt?: string | null;
};
export type EquipmentWriteCommand = {
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
};
export type EquipmentCreateCommand = EquipmentWriteCommand;
export type EquipmentUpdateCommand = EquipmentWriteCommand & { expectedUpdatedAt: string };
export type ReasonedCommand = { reason: string };
export type DeviceBindingCommand = ReasonedCommand & { profileId: string; projectId: string | null };
export type ProjectMembershipCommand = ReasonedCommand & { profileId: string; accessRole: ProjectAccessRole };
export type PersonStatusCommand = ReasonedCommand & { status: PersonStatus };
export type PersonRoleCommand = ReasonedCommand & { role: PersonRole };
export type InvitePersonCommand = ReasonedCommand & {
  email: string;
  displayName: string;
  role: PersonRole;
};
export type InvitedPerson = { profileId: string; email: string; status: "invited" };
export type DeviceCredential = { token: string; expiresAt: string };
export type DeviceActivation = { activationCode: string; expiresAt: string; deviceId: string };
export type DeviceActivationCommand = ReasonedCommand & { expiresInSeconds: number };
export type VoiceprintStatus = "new" | "enrolling" | "pending_verification" | "active" | "locked" | "deleted" | "revoked";
export type VoiceprintProfile = {
  profileId: string;
  organizationId: string;
  userId: string;
  deviceId: string;
  provider: "iflytek";
  status: VoiceprintStatus;
  sampleCount: number;
  requiredSamples: number;
  verificationFailures: number;
  consentVersion: string | null;
  consentedAt: string | null;
  lastVerifiedAt: string | null;
  lockedAt: string | null;
  createdAt: string;
  updatedAt: string;
  canRevoke: boolean;
};
export type VoiceprintList = { items: VoiceprintProfile[]; auditChainValid: boolean };
export type VoiceprintRevokeCommand = ReasonedCommand & { idempotencyKey: string };
export type AuditActor = { id: string | null; displayName: string; role: string };
export type AuditEvent = {
  id: string;
  actor: AuditActor;
  action: string;
  targetType: string;
  targetId: string;
  metadata: Record<string, unknown>;
  createdAt: string;
};
export type AuditEventList = { items: AuditEvent[]; nextCursor: string | null };
export type SystemModelContract = {
  mainAiModel: string;
  realtimeAsrModel: string;
  wakeEngine: string;
  voiceprintService: string;
  locked: boolean;
};
export type SystemIntegrationStatus = {
  contentSyncConfigured: boolean;
  voiceprintAdminConfigured: boolean;
  deviceActivationBackendConfigured: boolean;
};
export type ContentDistributionItem = {
  deviceId: string;
  deviceKey: string;
  displayName: string;
  deviceStatus: string;
  appVersion: string | null;
  lastSeenAt: string | null;
  manifestStatus: "healthy" | "missing" | "expired" | "revoked" | "superseded";
  manifestVersion: number | null;
  manifestEtag: string | null;
  projectId: string | null;
  generatedAt: string | null;
  expiresAt: string | null;
};
export type SystemStatus = {
  modelContract: SystemModelContract;
  integrations: SystemIntegrationStatus;
  contentDistribution: {
    totalDevices: number;
    healthyDevices: number;
    issueCount: number;
    items: ContentDistributionItem[];
  };
  generatedAt: string;
};

export interface ManagementApi extends SkillKnowledgeApi {
  getDashboard(): Promise<Dashboard>;
  getAuditEvents(filters?: { action?: string; targetType?: string; limit?: number; before?: string }): Promise<AuditEventList>;
  getSystemStatus(): Promise<SystemStatus>;
  getProjects(): Promise<Project[]>;
  getProjectRecord(projectId: string): Promise<ProjectRecord>;
  getProjectMemberships(projectId: string): Promise<ProjectMembership[]>;
  grantProjectMembership(projectId: string, command: ProjectMembershipCommand): Promise<void>;
  revokeProjectMembership(projectId: string, profileId: string, command: ReasonedCommand): Promise<void>;
  getTasks(filters?: TaskListFilters): Promise<PagedResult<TaskSummary>>;
  exportTasks(filters?: TaskExportFilters): Promise<Blob>;
  getMedia(filters?: MediaListFilters): Promise<PagedResult<MediaRecord>>;
  getTask(taskId: string): Promise<TaskDetail>;
  getPeople(): Promise<Person[]>;
  getDevices(): Promise<Device[]>;
  getEquipment?: () => Promise<EquipmentMemory[]>;
  createEquipment(command: EquipmentCreateCommand): Promise<EquipmentMemory>;
  updateEquipment(equipmentId: string, command: EquipmentUpdateCommand): Promise<EquipmentMemory>;
  getVoiceprints(): Promise<VoiceprintList>;
  revokeVoiceprint(profileId: string, command: VoiceprintRevokeCommand): Promise<VoiceprintProfile>;
  setPersonStatus(personId: string, command: PersonStatusCommand): Promise<void>;
  sendPersonRecovery(personId: string, command: ReasonedCommand): Promise<void>;
  bindDevice(deviceId: string, command: DeviceBindingCommand): Promise<void>;
  unbindDevice(deviceId: string, command: ReasonedCommand): Promise<void>;
  revokeDevice(deviceId: string, command: ReasonedCommand): Promise<void>;
  issueDeviceCredential(deviceId: string, command: ReasonedCommand): Promise<DeviceCredential>;
  issueDeviceActivation(deviceId: string, command: DeviceActivationCommand): Promise<DeviceActivation>;
  invitePerson(command: InvitePersonCommand): Promise<InvitedPerson>;
  setPersonRole(personId: string, command: PersonRoleCommand): Promise<void>;
  retryMedia(taskId: string, mediaId: string): Promise<void>;
  getWorkflowCatalog(): Promise<WorkflowCatalog>;
  getFieldApps(): Promise<FieldApp[]>;
  createFieldApp(command: FieldAppCreateCommand): Promise<FieldApp>;
  getWorkflows(fieldAppId: string): Promise<WorkflowDefinition[]>;
  createWorkflow(fieldAppId: string, command: WorkflowCreateCommand): Promise<WorkflowDefinition>;
  getWorkflow(workflowId: string): Promise<WorkflowDefinition>;
  saveWorkflowDraft(workflowId: string, draft: WorkflowDraft): Promise<WorkflowDefinition>;
  validateWorkflow(workflowId: string): Promise<WorkflowValidationResult>;
  getWorkflowVersions(workflowId: string): Promise<WorkflowVersion[]>;
  getWorkflowVersion(versionId: string): Promise<WorkflowVersion>;
  publishWorkflow(workflowId: string, command: WorkflowPublishCommand): Promise<WorkflowVersion>;
  getWorkOrders(filters?: { status?: WorkOrderStatus; limit?: number }): Promise<WorkOrder[]>;
  getWorkflowBindingRules(): Promise<WorkflowBindingRule[]>;
  createWorkflowBindingRule(command: WorkflowBindingRuleCommand): Promise<WorkflowBindingRule>;
  updateWorkflowBindingRule(ruleId: string, command: WorkflowBindingRuleUpdateCommand): Promise<WorkflowBindingRule>;
  resolveWorkOrderWorkflow(workOrderId: string, command: WorkOrderWorkflowResolutionCommand): Promise<WorkOrderWorkflowResolution>;
}

export class ManagementApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly payload: Record<string, unknown>,
  ) {
    super(`管理服务请求失败（${status}：${code}）`);
    this.name = "ManagementApiError";
  }
}

export function createManagementApi(baseUrl: string, getAccessToken: () => Promise<string | null>): ManagementApi {
  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const token = await getAccessToken();
    if (!token) throw new Error("登录已失效，请重新登录。");
    const response = await fetch(`${baseUrl.replace(/\/$/, "")}${path}`, {
      ...init,
      headers: { ...init?.headers, Authorization: `Bearer ${token}` },
    });
    const payload = await responsePayload(response);
    if (!response.ok) {
      const errorPayload = isRecord(payload) ? payload : {};
      const code = typeof errorPayload.error === "string" ? errorPayload.error : "request_failed";
      throw new ManagementApiError(response.status, code, errorPayload);
    }
    return payload as T;
  }

  async function download(path: string): Promise<Blob> {
    const token = await getAccessToken();
    if (!token) throw new Error("登录已失效，请重新登录。");
    const response = await fetch(`${baseUrl.replace(/\/$/, "")}${path}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) {
      const payload = await responsePayload(response);
      const errorPayload = isRecord(payload) ? payload : {};
      const code = typeof errorPayload.error === "string" ? errorPayload.error : "request_failed";
      throw new ManagementApiError(response.status, code, errorPayload);
    }
    return await response.blob();
  }

  const encoded = (value: string) => encodeURIComponent(value);
  const json = (method: "POST" | "PUT", body: unknown): RequestInit => ({
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  return {
    getDashboard: () => request<Dashboard>("/management/dashboard"),
    getAuditEvents: (filters = {}) => {
      const query = new URLSearchParams();
      if (filters.action) query.set("action", filters.action);
      if (filters.targetType) query.set("targetType", filters.targetType);
      if (filters.limit !== undefined) query.set("limit", String(filters.limit));
      if (filters.before) query.set("before", filters.before);
      return request<AuditEventList>(`/management/audit-events${query.size ? `?${query}` : ""}`);
    },
    getSystemStatus: () => request<SystemStatus>("/management/system-status"),
    getProjects: async () => (await request<{ items: Project[] }>("/management/projects")).items,
    getProjectRecord: (projectId) => request<ProjectRecord>(`/management/projects/${encoded(projectId)}/record`),
    getProjectMemberships: async (projectId) =>
      (await request<{ items: ProjectMembership[] }>(`/management/projects/${encoded(projectId)}/memberships`)).items,
    grantProjectMembership: async (projectId, command) => {
      await request(`/management/projects/${encoded(projectId)}/memberships`, json("POST", command));
    },
    revokeProjectMembership: async (projectId, profileId, command) => {
      await request(`/management/projects/${encoded(projectId)}/memberships/${encoded(profileId)}/revoke`, json("POST", {
        ...command,
        confirmation: "REVOKE_PROJECT_ACCESS",
      }));
    },
    getTasks: (filters = {}) => {
      const query = new URLSearchParams();
      if (filters.status) query.set("status", filters.status);
      if (filters.projectId) query.set("projectId", filters.projectId);
      if (filters.query) query.set("query", filters.query);
      if (filters.before) query.set("before", filters.before);
      if (filters.limit !== undefined) query.set("limit", String(filters.limit));
      return request<PagedResult<TaskSummary>>(`/management/tasks${query.size ? `?${query}` : ""}`);
    },
    exportTasks: (filters = {}) => {
      const query = new URLSearchParams();
      if (filters.status) query.set("status", filters.status);
      if (filters.projectId) query.set("projectId", filters.projectId);
      if (filters.query) query.set("query", filters.query);
      return download(`/management/tasks/export${query.size ? `?${query}` : ""}`);
    },
    getMedia: (filters = {}) => {
      const query = new URLSearchParams();
      if (filters.kind) query.set("kind", filters.kind);
      if (filters.uploadStatus) query.set("uploadStatus", filters.uploadStatus);
      if (filters.taskId) query.set("taskId", filters.taskId);
      if (filters.before) query.set("before", filters.before);
      if (filters.limit !== undefined) query.set("limit", String(filters.limit));
      return request<PagedResult<MediaRecord>>(`/management/media${query.size ? `?${query}` : ""}`);
    },
    getTask: (taskId) => request<TaskDetail>(`/management/tasks/${encodeURIComponent(taskId)}`),
    getPeople: async () => (await request<{ items: Person[] }>("/management/people")).items,
    getDevices: async () => (await request<{ items: Device[] }>("/management/devices")).items,
    getEquipment: async () => (await request<{ items: EquipmentMemory[] }>("/management/equipment")).items,
    createEquipment: (command) => request<EquipmentMemory>("/management/equipment", json("POST", {
      ...command,
      idempotencyKey: equipmentIdempotencyKey("create"),
      confirmation: "CREATE_EQUIPMENT",
    })),
    updateEquipment: (equipmentId, command) => request<EquipmentMemory>(
      `/management/equipment/${encoded(equipmentId)}`,
      json("PUT", {
        ...command,
        idempotencyKey: equipmentIdempotencyKey("update"),
        confirmation: "UPDATE_EQUIPMENT",
      }),
    ),
    getVoiceprints: () => request<VoiceprintList>("/management/voiceprints"),
    revokeVoiceprint: (profileId, command) =>
      request<VoiceprintProfile>(`/management/voiceprints/${encoded(profileId)}/revoke`, json("POST", {
        ...command,
        confirmation: "REVOKE_VOICEPRINT",
      })),
    setPersonStatus: async (personId, command) => {
      await request(`/management/people/${encoded(personId)}/status`, json("POST", {
        ...command,
        confirmation: "CHANGE_ACCOUNT_STATUS",
      }));
    },
    sendPersonRecovery: async (personId, command) => {
      await request(`/management/people/${encoded(personId)}/recovery`, json("POST", {
        ...command,
        confirmation: "SEND_ACCOUNT_RECOVERY",
      }));
    },
    invitePerson: (command) => request<InvitedPerson>("/management/people/invitations", json("POST", {
      ...command,
      confirmation: "INVITE_ACCOUNT",
    })),
    setPersonRole: async (personId, command) => {
      await request(`/management/people/${encoded(personId)}/role`, json("POST", {
        ...command,
        confirmation: "CHANGE_ACCOUNT_ROLE",
      }));
    },
    bindDevice: async (deviceId, command) => {
      await request(`/management/devices/${encoded(deviceId)}/bindings`, json("POST", command));
    },
    unbindDevice: async (deviceId, command) => {
      await request(`/management/devices/${encoded(deviceId)}/unbind`, json("POST", {
        ...command,
        confirmation: "UNBIND_DEVICE",
      }));
    },
    revokeDevice: async (deviceId, command) => {
      await request(`/management/devices/${encoded(deviceId)}/revoke`, json("POST", {
        ...command,
        confirmation: "REVOKE_DEVICE",
      }));
    },
    issueDeviceCredential: (deviceId, command) =>
      request<DeviceCredential>(`/management/devices/${encoded(deviceId)}/credentials`, json("POST", {
        ...command,
        confirmation: "ISSUE_DEVICE_CREDENTIAL",
      })),
    issueDeviceActivation: (deviceId, command) =>
      request<DeviceActivation>(`/management/devices/${encoded(deviceId)}/activation-codes`, json("POST", {
        ...command,
        confirmation: "ISSUE_DEVICE_ACTIVATION",
      })),
    retryMedia: async (taskId, mediaId) => { await request<void>(`/management/tasks/${encoded(taskId)}/media/${encoded(mediaId)}/retry`, { method: "POST" }); },
    getSkills: async () => (await request<{ items: SkillDefinition[] }>("/management/skills")).items,
    createSkillDraft: (command) => request<SkillVersion>("/management/skills", json("POST", command)),
    getSkillVersions: async (skillId) =>
      (await request<{ items: SkillVersion[] }>(`/management/skills/${encoded(skillId)}/versions`)).items,
    transitionSkillVersion: (versionId, command) =>
      request<SkillVersion>(`/management/skill-versions/${encoded(versionId)}/transition`, json("POST", {
        ...command,
        confirmation: skillConfirmation(command.newStatus),
      })),
    assignSkillVersion: (versionId, command) =>
      request<SkillAssignment>(`/management/skill-versions/${encoded(versionId)}/assignments`, json("POST", {
        ...command,
        confirmation: "ASSIGN_SKILL",
      })),
    revokeSkillAssignment: (assignmentId, command) =>
      request<SkillAssignment>(`/management/skill-assignments/${encoded(assignmentId)}/revoke`, json("POST", {
        ...command,
        confirmation: "REVOKE_SKILL_ASSIGNMENT",
      })),
    getKnowledge: async () => (await request<{ items: KnowledgeEntry[] }>("/management/knowledge")).items,
    createKnowledgeDraft: (command) => request<KnowledgeVersion>("/management/knowledge", json("POST", command)),
    createKnowledgeCaseDraft: (taskId, command) =>
      request<KnowledgeCaseDraftResult>(`/management/tasks/${encoded(taskId)}/knowledge-drafts`, json("POST", {
        ...command,
        confirmation: "CREATE_KNOWLEDGE_CASE_DRAFT",
      })),
    getKnowledgeVersions: async (knowledgeId) =>
      (await request<{ items: KnowledgeVersion[] }>(`/management/knowledge/${encoded(knowledgeId)}/versions`)).items,
    uploadKnowledgeAttachment: async (versionId, file, idempotencyKey) => {
      const body = new FormData();
      body.set("file", file);
      body.set("sha256", await sha256File(file));
      body.set("idempotencyKey", idempotencyKey);
      body.set("confirmation", "UPLOAD_KNOWLEDGE_ATTACHMENT");
      return request<KnowledgeAttachmentOperationResult>(
        `/management/knowledge-versions/${encoded(versionId)}/attachments`,
        { method: "POST", body },
      );
    },
    retryKnowledgeAttachment: (attachmentId, command) =>
      request<KnowledgeAttachmentOperationResult>(`/management/knowledge-attachments/${encoded(attachmentId)}/retry`, json("POST", {
        ...command,
        confirmation: "RETRY_KNOWLEDGE_ATTACHMENT",
      })),
    transitionKnowledgeVersion: (versionId, command) =>
      request<KnowledgeVersion>(`/management/knowledge-versions/${encoded(versionId)}/transition`, json("POST", {
        ...command,
        confirmation: knowledgeConfirmation(command.newStatus),
      })),
    grantKnowledgeVersion: (versionId, command) =>
      request<KnowledgeGrant>(`/management/knowledge-versions/${encoded(versionId)}/grants`, json("POST", {
        ...command,
        confirmation: "GRANT_KNOWLEDGE",
      })),
    revokeKnowledgeGrant: (grantId, command) =>
      request<KnowledgeGrant>(`/management/knowledge-grants/${encoded(grantId)}/revoke`, json("POST", {
        ...command,
        confirmation: "REVOKE_KNOWLEDGE_GRANT",
      })),
    getWorkflowCatalog: () => request<WorkflowCatalog>("/management/workflow-catalog"),
    getFieldApps: async () => (await request<{ items: FieldApp[] }>("/management/field-apps")).items,
    createFieldApp: (command) => request<FieldApp>("/management/field-apps", json("POST", command)),
    getWorkflows: async (fieldAppId) =>
      (await request<{ items: WorkflowDefinition[] }>(`/management/field-apps/${encoded(fieldAppId)}/workflows`)).items,
    createWorkflow: (fieldAppId, command) =>
      request<WorkflowDefinition>(`/management/field-apps/${encoded(fieldAppId)}/workflows`, json("POST", command)),
    getWorkflow: (workflowId) => request<WorkflowDefinition>(`/management/workflows/${encoded(workflowId)}`),
    saveWorkflowDraft: (workflowId, draft) =>
      request<WorkflowDefinition>(`/management/workflows/${encoded(workflowId)}/draft`, json("PUT", { draft })),
    validateWorkflow: (workflowId) =>
      request<WorkflowValidationResult>(`/management/workflows/${encoded(workflowId)}/validate`, json("POST", {})),
    getWorkflowVersions: async (workflowId) =>
      (await request<{ items: WorkflowVersion[] }>(`/management/workflows/${encoded(workflowId)}/versions`)).items,
    getWorkflowVersion: (versionId) =>
      request<WorkflowVersion>(`/management/workflow-versions/${encoded(versionId)}`),
    publishWorkflow: (workflowId, command) =>
      request<WorkflowVersion>(`/management/workflows/${encoded(workflowId)}/publish`, json("POST", {
        ...command,
        confirmation: "PUBLISH_WORKFLOW",
      })),
    getWorkOrders: async (filters = {}) => {
      const query = new URLSearchParams();
      if (filters.status) query.set("status", filters.status);
      if (filters.limit !== undefined) query.set("limit", String(filters.limit));
      return (await request<{ items: WorkOrder[] }>(`/management/work-orders${query.size ? `?${query}` : ""}`)).items;
    },
    getWorkflowBindingRules: async () =>
      (await request<{ items: WorkflowBindingRule[] }>("/management/workflow-binding-rules")).items,
    createWorkflowBindingRule: (command) =>
      request<WorkflowBindingRule>("/management/workflow-binding-rules", json("POST", {
        ...command,
        confirmation: "CREATE_WORKFLOW_BINDING_RULE",
      })),
    updateWorkflowBindingRule: (ruleId, command) =>
      request<WorkflowBindingRule>(`/management/workflow-binding-rules/${encoded(ruleId)}`, json("PUT", {
        ...command,
        confirmation: "UPDATE_WORKFLOW_BINDING_RULE",
      })),
    resolveWorkOrderWorkflow: (workOrderId, command) =>
      request<WorkOrderWorkflowResolution>(`/management/work-orders/${encoded(workOrderId)}/resolve-workflow`, json("POST", {
        ...command,
        confirmation: "RESOLVE_WORKFLOW",
      })),
  };
}

function equipmentIdempotencyKey(operation: "create" | "update"): string {
  return `equipment-${operation}-${crypto.randomUUID()}`;
}

function skillConfirmation(status: SkillStatus): string {
  return {
    draft: "REJECT_SKILL_REVIEW",
    review_pending: "SUBMIT_SKILL_REVIEW",
    published: "PUBLISH_SKILL",
    deprecated: "DEPRECATE_SKILL",
    archived: "ARCHIVE_SKILL",
  }[status];
}

function knowledgeConfirmation(status: KnowledgeStatus): string {
  return {
    uploaded: "RESET_KNOWLEDGE_UPLOAD",
    scanning: "START_KNOWLEDGE_SCAN",
    parsing: "START_KNOWLEDGE_PARSE",
    parsed: "MARK_KNOWLEDGE_PARSED",
    review_pending: "SUBMIT_KNOWLEDGE_REVIEW",
    published: "PUBLISH_KNOWLEDGE",
    expired: "EXPIRE_KNOWLEDGE",
    deprecated: "DEPRECATE_KNOWLEDGE",
    archived: "ARCHIVE_KNOWLEDGE",
  }[status];
}

async function sha256File(file: File): Promise<string> {
  const bytes = await readFileBytes(file);
  const copy = Uint8Array.from(bytes);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", copy.buffer));
  return Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function readFileBytes(file: File): Promise<Uint8Array> {
  if (typeof file.arrayBuffer === "function") {
    return new Uint8Array(await file.arrayBuffer());
  }
  return await new Promise<Uint8Array>((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(reader.error ?? new Error("文件读取失败"));
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer));
    reader.readAsArrayBuffer(file);
  });
}

async function responsePayload(response: Response): Promise<unknown> {
  if (response.status === 204) return undefined;
  const text = await response.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function eventText(event: TaskEvent): string {
  const value = event.payload.text ?? event.payload.transcript ?? event.payload.content ?? event.payload.message;
  return typeof value === "string" ? value : "已记录现场事件";
}
