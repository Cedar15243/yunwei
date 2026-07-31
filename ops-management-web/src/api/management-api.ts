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
}

export function createManagementApi(baseUrl: string, getAccessToken: () => Promise<string | null>): ManagementApi {
  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    const token = await getAccessToken();
    if (!token) throw new Error("登录已失效，请重新登录。");
    const response = await fetch(`${baseUrl.replace(/\/$/, "")}${path}`, {
      ...init,
      headers: { ...init?.headers, Authorization: `Bearer ${token}` },
    });
    if (!response.ok) throw new Error(`管理服务请求失败（${response.status}）`);
    return response.json() as Promise<T>;
  }
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
    retryMedia: async (taskId, mediaId) => { await request(`/management/tasks/${encodeURIComponent(taskId)}/media/${encodeURIComponent(mediaId)}/retry`, { method: "POST" }); },
  };
}

export function eventText(event: TaskEvent): string {
  const value = event.payload.text ?? event.payload.transcript ?? event.payload.content ?? event.payload.message;
  return typeof value === "string" ? value : "已记录现场事件";
}
