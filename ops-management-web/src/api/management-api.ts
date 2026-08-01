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

export type TaskStatus = "active" | "completed" | "closed" | "aborted";

export type Dashboard = {
  activeTaskCount: number;
  onlineDeviceCount: number;
  failedMediaCount: number;
  recentTasks: TaskSummary[];
};

export type Project = { id: string; title: string; status: string; summary?: string; updated_at?: string };
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
export type TaskDetail = {
  task: TaskSummary & { completed_at?: string };
  events: TaskEvent[];
  messages: TaskEvent[];
  steps: TaskEvent[];
  media: MediaAsset[];
};
export type Device = { id: string; device_key: string; display_name: string; status: string; last_seen_at?: string | null };

export interface ManagementApi {
  getDashboard(): Promise<Dashboard>;
  getProjects(): Promise<Project[]>;
  getTasks(filters?: { status?: TaskStatus; projectId?: string }): Promise<{ items: TaskSummary[]; nextCursor: string | null }>;
  getTask(taskId: string): Promise<TaskDetail>;
  getDevices(): Promise<Device[]>;
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

  const encoded = (value: string) => encodeURIComponent(value);
  const json = (method: "POST" | "PUT", body: unknown): RequestInit => ({
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  return {
    getDashboard: () => request<Dashboard>("/management/dashboard"),
    getProjects: async () => (await request<{ items: Project[] }>("/management/projects")).items,
    getTasks: (filters = {}) => {
      const query = new URLSearchParams();
      if (filters.status) query.set("status", filters.status);
      if (filters.projectId) query.set("projectId", filters.projectId);
      return request<{ items: TaskSummary[]; nextCursor: string | null }>(`/management/tasks${query.size ? `?${query}` : ""}`);
    },
    getTask: (taskId) => request<TaskDetail>(`/management/tasks/${encodeURIComponent(taskId)}`),
    getDevices: async () => (await request<{ items: Device[] }>("/management/devices")).items,
    retryMedia: async (taskId, mediaId) => { await request<void>(`/management/tasks/${encoded(taskId)}/media/${encoded(mediaId)}/retry`, { method: "POST" }); },
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
