type Identity = {
  id: string;
  organizationId: string;
  role: "super_admin" | "ops_admin" | "field_engineer" | "remote_expert" | "viewer";
  displayName: string;
};

type TaskListResult = { items: Array<Record<string, unknown>>; nextCursor: string | null };
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
  tasks(identity: Identity, filters: URLSearchParams): Promise<TaskListResult>;
  taskDetail(identity: Identity, taskId: string): Promise<TaskDetail | null>;
  retryMedia(identity: Identity, taskId: string, mediaId: string): Promise<boolean>;
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
  const path = url.pathname;
  if (request.method === "GET" && path === "/management/dashboard") {
    return response(await gateway.dashboard(identity));
  }
  if (request.method === "GET" && path === "/management/projects") {
    return response({ items: await gateway.projects(identity) });
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
  return response({ ok: false, error: "not_found" }, 404);
}

export function createManagementGateway(supabase: any): ManagementGateway {
  return {
    async authenticate(token) {
      const { data, error } = await supabase.auth.getUser(token);
      if (error || !data?.user?.id) return null;
      const { data: profile, error: profileError } = await supabase
        .from("ops_profiles")
        .select("id, organization_id, role, display_name, active")
        .eq("id", data.user.id)
        .eq("active", true)
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
      const [tasks, devices, failedMedia, recentTasks] = await Promise.all([
        supabase.from("maintenance_tasks").select("id", { count: "exact", head: true }).eq("organization_id", identity.organizationId).eq("status", "active"),
        supabase.from("glasses_devices").select("id", { count: "exact", head: true }).eq("organization_id", identity.organizationId).eq("status", "online"),
        supabase.from("media_assets").select("id", { count: "exact", head: true }).eq("organization_id", identity.organizationId).eq("upload_status", "failed"),
        supabase.from("maintenance_tasks").select("id, title, status, updated_at").eq("organization_id", identity.organizationId).order("updated_at", { ascending: false }).limit(8),
      ]);
      return {
        activeTaskCount: tasks.count ?? 0,
        onlineDeviceCount: devices.count ?? 0,
        failedMediaCount: failedMedia.count ?? 0,
        recentTasks: recentTasks.data ?? [],
      };
    },
    async projects(identity) {
      const { data, error } = await supabase.from("ops_projects")
        .select("id, title, status, summary, updated_at")
        .eq("organization_id", identity.organizationId)
        .order("updated_at", { ascending: false });
      if (error) throw error;
      return data ?? [];
    },
    async tasks(identity, filters) {
      let query = supabase.from("maintenance_tasks")
        .select("id, project_id, title, status, current_step, skill_version, created_at, updated_at")
        .eq("organization_id", identity.organizationId)
        .order("updated_at", { ascending: false })
        .limit(30);
      const status = filters.get("status");
      const projectId = filters.get("projectId");
      if (status) query = query.eq("status", status);
      if (projectId) query = query.eq("project_id", projectId);
      const { data, error } = await query;
      if (error) throw error;
      return { items: data ?? [], nextCursor: null };
    },
    async taskDetail(identity, taskId) {
      const { data: task, error } = await supabase.from("maintenance_tasks")
        .select("id, project_id, title, status, current_step, skill_version, created_at, updated_at, completed_at")
        .eq("id", taskId)
        .eq("organization_id", identity.organizationId)
        .maybeSingle();
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
  };
}

function bearerToken(request: Request): string | null {
  const value = request.headers.get("Authorization") ?? "";
  return value.startsWith("Bearer ") && value.length > 7 ? value.slice(7) : null;
}

function canManageMedia(identity: Identity): boolean {
  return identity.role === "super_admin" || identity.role === "ops_admin";
}

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers });
}
