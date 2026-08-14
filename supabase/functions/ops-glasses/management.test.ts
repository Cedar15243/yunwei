import { assertEquals, assertRejects, assertStringIncludes } from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  createManagementGateway,
  ManagementOperationError,
  routeManagement,
  type ManagementGateway,
} from "./management.ts";

const identity = { id: "user-a", organizationId: "org-a", role: "ops_admin" as const, displayName: "王工" };
const superAdmin = { ...identity, id: "super-a", role: "super_admin" as const };
const viewer = { ...identity, id: "viewer-a", role: "viewer" as const };

function gateway(): ManagementGateway {
  return {
    authenticate: async (token) => {
      if (token === "valid-token") return identity;
      if (token === "super-token") return superAdmin;
      if (token === "viewer-token") return viewer;
      return null;
    },
    dashboard: async () => ({ activeTaskCount: 1, onlineDeviceCount: 1, failedMediaCount: 0, recentTasks: [] }),
    auditEvents: async () => ({
      items: [{
        id: "audit-a",
        actor: { id: "user-a", displayName: "王工", role: "ops_admin" },
        action: "media_retry_requested",
        targetType: "media_asset",
        targetId: "media-a",
        metadata: { taskId: "task-a", authorization: "[REDACTED]" },
        createdAt: "2026-08-03T08:00:00.000Z",
      }],
      nextCursor: null,
    }),
    systemStatus: async () => ({
      modelContract: {
        mainAiModel: "qwen3-vl-plus",
        realtimeAsrModel: "fun-asr-realtime",
        wakeEngine: "iflytek-aikit-previous",
        voiceprintService: "iflytek/s1aa729d0",
        locked: true,
      },
      integrations: {
        contentSyncConfigured: true,
        voiceprintAdminConfigured: true,
        deviceActivationBackendConfigured: true,
      },
      contentDistribution: {
        totalDevices: 1,
        healthyDevices: 1,
        issueCount: 0,
        items: [],
      },
      generatedAt: "2026-08-03T08:00:00.000Z",
    }),
    projects: async () => [{ id: "project-a", title: "实训室" }],
    projectRecord: async (_identity, projectId) => projectId === "project-a" ? {
      project: { id: "project-a", title: "实训室", status: "active", summary: "空调控制器维修" },
      tasks: [{ id: "task-a", title: "温湿度异常", status: "completed" }],
      latestMemory: {
        revision: 3,
        summary: "已更换控制器并复测。",
        confirmedFacts: ["24V 供电正常"],
        excludedFacts: ["总线反接"],
        risks: ["观察 24 小时"],
        taskId: "task-a",
        eventId: "event-a",
        updatedAt: "2026-08-05T08:30:00.000Z",
      },
      skillVersions: ["hvac-ddc@3"],
    } : null,
    people: async () => [{ id: "user-a", display_name: "王工", role: "ops_admin", status: "active" }],
    projectMemberships: async () => [{ profile_id: "engineer-a", access_role: "engineer", status: "active" }],
    devices: async () => [{ id: "device-a", display_name: "Air3" }],
    voiceprints: async (authenticatedIdentity) => ({
      auditChainValid: true,
      items: [{
        profileId: "voiceprint-a",
        organizationId: "org-a",
        userId: "user-a",
        deviceId: "device-a",
        provider: "iflytek",
        status: "active",
        sampleCount: 3,
        requiredSamples: 3,
        verificationFailures: 0,
        consentVersion: "2026-08-02.v1",
        consentedAt: "2026-08-03T08:00:00.000Z",
        lastVerifiedAt: "2026-08-03T08:05:00.000Z",
        lockedAt: null,
        createdAt: "2026-08-03T08:00:00.000Z",
        updatedAt: "2026-08-03T08:05:00.000Z",
        canRevoke: authenticatedIdentity.role === "super_admin",
      }],
    }),
    revokeVoiceprint: async (_identity, profileId) => profileId === "voiceprint-a" ? {
      profileId: "voiceprint-a",
      organizationId: "org-a",
      userId: "user-a",
      deviceId: "device-a",
      provider: "iflytek",
      status: "revoked",
      sampleCount: 0,
      requiredSamples: 3,
      verificationFailures: 0,
      consentVersion: "2026-08-02.v1",
      consentedAt: "2026-08-03T08:00:00.000Z",
      lastVerifiedAt: null,
      lockedAt: null,
      createdAt: "2026-08-03T08:00:00.000Z",
      updatedAt: "2026-08-03T09:00:00.000Z",
      canRevoke: false,
    } : null,
    tasks: async () => ({ items: [{ id: "task-a", title: "温湿度异常", status: "active" }], nextCursor: null }),
    taskExport: async () => "\uFEFF任务ID,任务标题\r\ntask-a,温湿度异常\r\n",
    media: async () => ({ items: [], nextCursor: null }),
    taskDetail: async (_identity, taskId) => taskId === "task-a" ? {
      task: { id: "task-a", title: "温湿度异常" },
      events: [],
      messages: [],
      steps: [],
      media: [{ id: "media-a", kind: "photo", url: "https://signed.example/photo.jpg", canRetryMedia: true }],
    } : null,
    retryMedia: async () => true,
    issueDeviceCredential: async () => ({ token: "issued-once", expiresAt: "2026-08-30T00:00:00.000Z" }),
    bindDevice: async () => true,
    unbindDevice: async () => true,
    revokeDevice: async () => true,
    setProfileStatus: async () => true,
    sendAccountRecovery: async () => true,
    invitePerson: async (_identity, command) => ({
      profileId: "invited-a",
      email: command.email,
      status: "invited",
    }),
    setProfileRole: async () => true,
    grantProjectMembership: async () => true,
    revokeProjectMembership: async () => true,
  };
}

Deno.test("rejects task lists without bearer authentication", async () => {
  const response = await routeManagement(new Request("https://ops/management/tasks"), gateway());
  assertEquals(response.status, 401);
});

Deno.test("returns only authenticated organization tasks", async () => {
  const response = await routeManagement(new Request("https://ops/management/tasks", {
    headers: { Authorization: "Bearer valid-token" },
  }), gateway());
  assertEquals(response.status, 200);
  assertEquals(await response.json(), { items: [{ id: "task-a", title: "温湿度异常", status: "active" }], nextCursor: null });
});

Deno.test("validates and normalizes task list filters before database access", async () => {
  let received: Record<string, unknown> | null = null;
  const filtered = {
    ...gateway(),
    tasks: async (_identity: unknown, filters: Record<string, unknown>) => {
      received = filters;
      return { items: [], nextCursor: null };
    },
  } as unknown as ManagementGateway;
  const response = await routeManagement(new Request(
    "https://ops/management/tasks?status=completed&projectId=11111111-1111-4111-8111-111111111111&query=%E6%B0%B4%E6%B3%B5&limit=20",
    { headers: { Authorization: "Bearer valid-token" } },
  ), filtered);

  assertEquals(response.status, 200);
  assertEquals(received, {
    status: "completed",
    projectId: "11111111-1111-4111-8111-111111111111",
    query: "水泵",
    before: null,
    limit: 20,
  });
});

Deno.test("rejects unknown repeated and invalid task list filters", async () => {
  let calls = 0;
  const filtered = {
    ...gateway(),
    tasks: async () => {
      calls += 1;
      return { items: [], nextCursor: null };
    },
  } as unknown as ManagementGateway;
  const urls = [
    "https://ops/management/tasks?admin=true",
    "https://ops/management/tasks?status=active&status=closed",
    "https://ops/management/tasks?projectId=not-a-uuid",
    "https://ops/management/tasks?limit=101",
    "https://ops/management/tasks?query=",
    "https://ops/management/tasks?before=not-a-cursor",
  ];

  for (const url of urls) {
    const response = await routeManagement(new Request(url, {
      headers: { Authorization: "Bearer valid-token" },
    }), filtered);
    assertEquals(response.status, 400);
    assertEquals(await response.json(), { ok: false, error: "invalid_request" });
  }
  assertEquals(calls, 0);
});

Deno.test("exports only authorized task records as a no-store UTF-8 CSV", async () => {
  let received: Record<string, unknown> | null = null;
  const exportGateway = {
    ...gateway(),
    taskExport: async (_identity: unknown, filters: Record<string, unknown>) => {
      received = filters;
      return "\uFEFF任务ID,任务标题\r\ntask-a,温湿度异常\r\n";
    },
  } as unknown as ManagementGateway;
  const response = await routeManagement(new Request(
    "https://ops/management/tasks/export?status=completed&query=%E6%B8%A9%E6%B9%BF%E5%BA%A6",
    { headers: { Authorization: "Bearer valid-token" } },
  ), exportGateway);

  assertEquals(response.status, 200);
  assertEquals(response.headers.get("Content-Type"), "text/csv; charset=utf-8");
  assertEquals(response.headers.get("Cache-Control"), "no-store");
  assertStringIncludes(response.headers.get("Content-Disposition") ?? "", "v9-task-records.csv");
  assertEquals(await response.text(), "\uFEFF任务ID,任务标题\r\ntask-a,温湿度异常\r\n");
  assertEquals(received, {
    status: "completed",
    projectId: null,
    query: "温湿度",
  });
});

Deno.test("validates and normalizes media record filters before database access", async () => {
  let received: Record<string, unknown> | null = null;
  const filtered = {
    ...gateway(),
    media: async (_identity: unknown, filters: Record<string, unknown>) => {
      received = filters;
      return { items: [], nextCursor: null };
    },
  } as unknown as ManagementGateway;
  const response = await routeManagement(new Request(
    "https://ops/management/media?kind=photo&uploadStatus=failed&taskId=22222222-2222-4222-8222-222222222222&limit=15",
    { headers: { Authorization: "Bearer valid-token" } },
  ), filtered);

  assertEquals(response.status, 200);
  assertEquals(received, {
    kind: "photo",
    uploadStatus: "failed",
    taskId: "22222222-2222-4222-8222-222222222222",
    before: null,
    limit: 15,
  });
});

Deno.test("accepts cancelled media records as a terminal upload filter", async () => {
  let received: Record<string, unknown> | null = null;
  const filtered = {
    ...gateway(),
    media: async (_identity: unknown, filters: Record<string, unknown>) => {
      received = filters;
      return { items: [], nextCursor: null };
    },
  } as unknown as ManagementGateway;
  const response = await routeManagement(new Request(
    "https://ops/management/media?uploadStatus=cancelled",
    { headers: { Authorization: "Bearer valid-token" } },
  ), filtered);

  assertEquals(response.status, 200);
  assertEquals(received, {
    kind: null,
    uploadStatus: "cancelled",
    taskId: null,
    before: null,
    limit: 30,
  });
});

Deno.test("rejects unknown repeated and invalid media record filters", async () => {
  let calls = 0;
  const filtered = {
    ...gateway(),
    media: async () => {
      calls += 1;
      return { items: [], nextCursor: null };
    },
  } as unknown as ManagementGateway;
  const urls = [
    "https://ops/management/media?admin=true",
    "https://ops/management/media?kind=photo&kind=video",
    "https://ops/management/media?kind=document",
    "https://ops/management/media?uploadStatus=unknown",
    "https://ops/management/media?taskId=not-a-uuid",
    "https://ops/management/media?limit=0",
    "https://ops/management/media?before=not-a-cursor",
  ];

  for (const url of urls) {
    const response = await routeManagement(new Request(url, {
      headers: { Authorization: "Bearer valid-token" },
    }), filtered);
    assertEquals(response.status, 400);
    assertEquals(await response.json(), { ok: false, error: "invalid_request" });
  }
  assertEquals(calls, 0);
});

Deno.test("returns a strict paged media DTO without exposing storage paths", async () => {
  const mediaGateway = {
    ...gateway(),
    media: async () => ({
      items: [{
        id: "media-a",
        task_id: "task-a",
        task_title: "温湿度异常",
        project_id: "project-a",
        kind: "photo",
        content_type: "image/jpeg",
        upload_status: "failed",
        failure_reason: "网络中断",
        captured_at: "2026-08-05T08:00:00.000Z",
        created_at: "2026-08-05T08:00:00.000Z",
        url: null,
        canRetryMedia: true,
      }],
      nextCursor: "cursor-a",
    }),
  } as unknown as ManagementGateway;
  const response = await routeManagement(new Request("https://ops/management/media", {
    headers: { Authorization: "Bearer valid-token" },
  }), mediaGateway);
  const body = await response.json();

  assertEquals(response.status, 200);
  assertEquals(body.nextCursor, "cursor-a");
  assertEquals(body.items[0].failure_reason, "网络中断");
  assertEquals("storage_bucket" in body.items[0], false);
  assertEquals("file_path" in body.items[0], false);
});

Deno.test("applies project scope and builds signed paged media DTOs in the real gateway", async () => {
  const calls: Array<{ table: string; operation: string; args: unknown[] }> = [];
  const signedCalls: Array<{ bucket: string; path: string; expiresIn: number }> = [];
  const mediaRows = [
    {
      id: "00000000-0000-4000-8000-000000000002",
      task_id: "task-b",
      kind: "photo",
      content_type: "image/jpeg",
      storage_bucket: "ops-private",
      file_path: "org-a/task-b/photo.jpg",
      upload_status: "failed",
      failure_reason: "network_interrupted",
      captured_at: "2026-08-05T07:54:00.000Z",
      created_at: "2026-08-05T07:54:00.000Z",
    },
    {
      id: "00000000-0000-4000-8000-000000000001",
      task_id: "task-a",
      kind: "photo",
      content_type: "image/jpeg",
      storage_bucket: "ops-private",
      file_path: "org-a/task-a/photo.jpg",
      upload_status: "synced",
      failure_reason: null,
      captured_at: "2026-08-05T07:53:00.000Z",
      created_at: "2026-08-05T07:53:00.000Z",
    },
    {
      id: "00000000-0000-4000-8000-000000000000",
      task_id: "task-a",
      kind: "photo",
      content_type: "image/jpeg",
      storage_bucket: "ops-private",
      file_path: "org-a/task-a/older.jpg",
      upload_status: "synced",
      failure_reason: null,
      captured_at: "2026-08-05T07:52:00.000Z",
      created_at: "2026-08-05T07:52:00.000Z",
    },
  ];
  const tableResult = (table: string, selection: string) => {
    if (table === "ops_project_memberships") {
      return {
        data: [{
          organization_id: "org-a",
          profile_id: "engineer-a",
          project_id: "project-a",
          status: "active",
        }],
        error: null,
      };
    }
    if (table === "maintenance_tasks" && selection === "id") {
      return { data: [{ id: "task-a" }, { id: "task-b" }], error: null };
    }
    if (table === "maintenance_tasks") {
      return {
        data: [
          { id: "task-a", project_id: "project-a", title: "Pump inspection" },
          { id: "task-b", project_id: "project-a", title: "Network repair" },
        ],
        error: null,
      };
    }
    if (table === "media_assets") return { data: mediaRows, error: null };
    throw new Error(`Unexpected table: ${table}`);
  };
  const supabase = {
    from: (table: string) => {
      let selection = "";
      const builder: any = {
        select: (value: string) => {
          selection = value;
          calls.push({ table, operation: "select", args: [value] });
          return builder;
        },
        eq: (...args: unknown[]) => {
          calls.push({ table, operation: "eq", args });
          return builder;
        },
        in: (...args: unknown[]) => {
          calls.push({ table, operation: "in", args });
          return builder;
        },
        order: (...args: unknown[]) => {
          calls.push({ table, operation: "order", args });
          return builder;
        },
        limit: (...args: unknown[]) => {
          calls.push({ table, operation: "limit", args });
          return builder;
        },
        or: (...args: unknown[]) => {
          calls.push({ table, operation: "or", args });
          return builder;
        },
        then: (resolve: (value: unknown) => unknown, reject: (reason: unknown) => unknown) =>
          Promise.resolve(tableResult(table, selection)).then(resolve, reject),
      };
      return builder;
    },
    storage: {
      from: (bucket: string) => ({
        createSignedUrl: async (path: string, expiresIn: number) => {
          signedCalls.push({ bucket, path, expiresIn });
          return { data: { signedUrl: `https://signed.example/${path}` }, error: null };
        },
      }),
    },
  };
  const management = createManagementGateway(
    supabase,
    {} as never,
    undefined,
    { accountRecoveryRedirectUrl: "https://ops.example.com/" },
  );
  const filters = {
    kind: "photo" as const,
    uploadStatus: null,
    taskId: null,
    before: {
      createdAt: "2026-08-05T07:55:00.000Z",
      id: "00000000-0000-4000-8000-000000000999",
    },
    limit: 2,
  };
  const fieldEngineer = {
    id: "engineer-a",
    organizationId: "org-a",
    role: "field_engineer" as const,
    displayName: "Engineer A",
  };

  const scoped = await management.media(fieldEngineer, filters);
  const administrator = await management.media(identity, filters);

  assertEquals(scoped.items, [
    {
      id: "00000000-0000-4000-8000-000000000002",
      task_id: "task-b",
      task_title: "Network repair",
      project_id: "project-a",
      kind: "photo",
      content_type: "image/jpeg",
      upload_status: "failed",
      failure_reason: "network_interrupted",
      captured_at: "2026-08-05T07:54:00.000Z",
      created_at: "2026-08-05T07:54:00.000Z",
      url: null,
      canRetryMedia: false,
    },
    {
      id: "00000000-0000-4000-8000-000000000001",
      task_id: "task-a",
      task_title: "Pump inspection",
      project_id: "project-a",
      kind: "photo",
      content_type: "image/jpeg",
      upload_status: "synced",
      failure_reason: "",
      captured_at: "2026-08-05T07:53:00.000Z",
      created_at: "2026-08-05T07:53:00.000Z",
      url: "https://signed.example/org-a/task-a/photo.jpg",
      canRetryMedia: false,
    },
  ]);
  assertEquals(typeof scoped.nextCursor, "string");
  assertEquals(administrator.items[0].canRetryMedia, true);
  assertEquals("storage_bucket" in scoped.items[1], false);
  assertEquals("file_path" in scoped.items[1], false);
  assertEquals(signedCalls, [
    { bucket: "ops-private", path: "org-a/task-a/photo.jpg", expiresIn: 300 },
    { bucket: "ops-private", path: "org-a/task-a/photo.jpg", expiresIn: 300 },
  ]);
  assertEquals(calls.some((call) =>
    call.table === "media_assets" && call.operation === "in" &&
    call.args[0] === "task_id" && JSON.stringify(call.args[1]) === JSON.stringify(["task-a", "task-b"])
  ), true);
  assertEquals(calls.some((call) =>
    call.table === "maintenance_tasks" && call.operation === "in" &&
    call.args[0] === "project_id" && JSON.stringify(call.args[1]) === JSON.stringify(["project-a"])
  ), true);
  assertEquals(calls.some((call) =>
    call.table === "media_assets" && call.operation === "or" &&
    call.args[0] ===
      "created_at.lt.2026-08-05T07:55:00.000Z,and(created_at.eq.2026-08-05T07:55:00.000Z,id.lt.00000000-0000-4000-8000-000000000999)"
  ), true);
  assertEquals(calls.filter((call) => call.table === "media_assets" && call.operation === "limit")[0].args, [3]);
});

Deno.test("returns organization audit events only to administrators", async () => {
  const allowed = await routeManagement(new Request("https://ops/management/audit-events", {
    headers: { Authorization: "Bearer valid-token" },
  }), gateway());
  const denied = await routeManagement(new Request("https://ops/management/audit-events", {
    headers: { Authorization: "Bearer viewer-token" },
  }), gateway());

  assertEquals(allowed.status, 200);
  assertEquals((await allowed.json()).items[0].action, "media_retry_requested");
  assertEquals(denied.status, 403);
});

Deno.test("passes audit filters to the authorized management gateway", async () => {
  const received: { value: URLSearchParams | null } = { value: null };
  const filtered = {
    ...gateway(),
    auditEvents: async (_identity: unknown, filters: URLSearchParams) => {
      received.value = filters;
      return { items: [], nextCursor: null };
    },
  } as ManagementGateway;
  const response = await routeManagement(new Request(
    "https://ops/management/audit-events?action=voiceprint_revoked&targetType=voiceprint_profile&limit=25&before=2026-08-03T08:00:00.000Z",
    { headers: { Authorization: "Bearer super-token" } },
  ), filtered);

  assertEquals(response.status, 200);
  assertEquals(received.value?.get("action"), "voiceprint_revoked");
  assertEquals(received.value?.get("targetType"), "voiceprint_profile");
  assertEquals(received.value?.get("limit"), "25");
  assertEquals(received.value?.get("before"), "2026-08-03T08:00:00.000Z");
});

Deno.test("returns system runtime and content distribution status only to administrators", async () => {
  const allowed = await routeManagement(new Request("https://ops/management/system-status", {
    headers: { Authorization: "Bearer valid-token" },
  }), gateway());
  const denied = await routeManagement(new Request("https://ops/management/system-status", {
    headers: { Authorization: "Bearer viewer-token" },
  }), gateway());

  assertEquals(allowed.status, 200);
  assertEquals((await allowed.json()).modelContract.locked, true);
  assertEquals(denied.status, 403);
});

Deno.test("accepts the deployed Supabase management path", async () => {
  const response = await routeManagement(new Request(
    "https://project.supabase.co/functions/v1/ops-glasses/management/tasks",
    { headers: { Authorization: "Bearer valid-token" } },
  ), gateway());

  assertEquals(response.status, 200);
});

Deno.test("returns an authorized cloud project record and rejects an unknown project", async () => {
  const allowed = await routeManagement(new Request("https://ops/management/projects/project-a/record", {
    headers: { Authorization: "Bearer valid-token" },
  }), gateway());
  const missing = await routeManagement(new Request("https://ops/management/projects/missing/record", {
    headers: { Authorization: "Bearer valid-token" },
  }), gateway());

  assertEquals(allowed.status, 200);
  assertEquals((await allowed.json()).latestMemory.revision, 3);
  assertEquals(missing.status, 404);
});

Deno.test("aggregates project tasks and the latest human-confirmed memory without per-task queries", async () => {
  const calls: string[] = [];
  const rows: Record<string, Array<Record<string, unknown>>> = {
    ops_projects: [{
      id: "11111111-1111-4111-8111-111111111111",
      title: "一号机房",
      status: "active",
      summary: "DDC 控制器维修记录",
      updated_at: "2026-08-05T09:00:00.000Z",
    }],
    maintenance_tasks: [
      {
        id: "22222222-2222-4222-8222-222222222222",
        project_id: "11111111-1111-4111-8111-111111111111",
        title: "控制器离线",
        status: "completed",
        current_step: "复测通信",
        skill_version: "hvac-ddc@3",
        created_at: "2026-08-05T08:00:00.000Z",
        updated_at: "2026-08-05T08:30:00.000Z",
        completed_at: "2026-08-05T08:30:00.000Z",
      },
      {
        id: "33333333-3333-4333-8333-333333333333",
        project_id: "11111111-1111-4111-8111-111111111111",
        title: "观察任务",
        status: "active",
        current_step: "等待复检",
        skill_version: "hvac-ddc@3",
        created_at: "2026-08-05T08:40:00.000Z",
        updated_at: "2026-08-05T09:00:00.000Z",
        completed_at: null,
      },
    ],
    task_events: [
      {
        id: "event-invalid",
        task_id: "33333333-3333-4333-8333-333333333333",
        event_type: "task_completed",
        payload: { humanConfirmed: false, summary: "不能作为项目记忆" },
        occurred_at: "2026-08-05T09:00:00.000Z",
        created_at: "2026-08-05T09:00:00.000Z",
      },
      {
        id: "event-a",
        task_id: "22222222-2222-4222-8222-222222222222",
        event_type: "task_completed",
        payload: {
          humanConfirmed: true,
          phase: "COMPLETED",
          taskStatus: "completed",
          projectMemoryRevision: 4,
          summary: "已更换控制器并复测通信。",
          confirmedFacts: ["24V 供电正常", "新控制器在线"],
          excludedFacts: ["总线反接"],
          risks: ["观察 24 小时"],
        },
        occurred_at: "2026-08-05T08:30:00.000Z",
        created_at: "2026-08-05T08:30:00.000Z",
      },
    ],
  };
  const supabase = {
    from: (table: string) => {
      calls.push(table);
      const builder: any = {
        select: () => builder,
        eq: () => builder,
        in: () => builder,
        order: () => builder,
        limit: () => builder,
        maybeSingle: async () => ({ data: rows[table]?.[0] ?? null, error: null }),
        then: (resolve: (value: unknown) => unknown) =>
          Promise.resolve({ data: rows[table] ?? [], error: null }).then(resolve),
      };
      return builder;
    },
  };
  const management = createManagementGateway(supabase, {} as never);

  const record = await management.projectRecord(
    superAdmin,
    "11111111-1111-4111-8111-111111111111",
  );

  assertEquals(calls, ["ops_projects", "maintenance_tasks", "task_events"]);
  assertEquals(record, {
    project: rows.ops_projects[0],
    tasks: rows.maintenance_tasks,
    latestMemory: {
      revision: 4,
      summary: "已更换控制器并复测通信。",
      confirmedFacts: ["24V 供电正常", "新控制器在线"],
      excludedFacts: ["总线反接"],
      risks: ["观察 24 小时"],
      taskId: "22222222-2222-4222-8222-222222222222",
      eventId: "event-a",
      updatedAt: "2026-08-05T08:30:00.000Z",
    },
    skillVersions: ["hvac-ddc@3"],
  });
});

Deno.test("returns devices only after bearer authentication", async () => {
  const response = await routeManagement(new Request("https://ops/management/devices", {
    headers: { Authorization: "Bearer valid-token" },
  }), gateway());
  assertEquals(response.status, 200);
  assertEquals(await response.json(), { items: [{ id: "device-a", display_name: "Air3" }] });
});

Deno.test("returns only service-managed equipment memory to organization administrators", async () => {
  const equipmentGateway = {
    ...gateway(),
    equipment: async () => [{
      id: "equipment-a",
      equipmentKey: "HVAC-001",
      system: "暖通",
      brand: "华方",
      model: "HF-DDC-100",
      quantity: 4,
      status: "normal",
      faultCount: 1,
      repairCount: 2,
      keyParameter: "送风 17 C",
      linkedProjects: [{
        projectId: "project-a",
        localProjectId: "local-a",
        title: "实训室",
        status: "active",
        taskCount: 2,
      }],
    }],
  } as unknown as ManagementGateway;
  const allowed = await routeManagement(new Request("https://ops/management/equipment", {
    headers: { Authorization: "Bearer valid-token" },
  }), equipmentGateway);
  assertEquals(allowed.status, 200);
  assertEquals((await allowed.json()).items[0].equipmentKey, "HVAC-001");

  const denied = await routeManagement(new Request("https://ops/management/equipment", {
    headers: { Authorization: "Bearer viewer-token" },
  }), equipmentGateway);
  assertEquals(denied.status, 403);
});

Deno.test("creates equipment only with administrator confirmation idempotency and project bindings", async () => {
  let received: Record<string, unknown> | null = null;
  const createdEquipment = {
    id: "22222222-2222-4222-8222-222222222222",
    equipmentKey: "UPS-01",
    system: "供配电系统",
    brand: "华方",
    model: "HF-UPS-20K",
    quantity: 2,
    status: "attention",
    lastInspectionAt: "2026-08-08T02:00:00.000Z",
    faultCount: 3,
    repairCount: 2,
    keyParameter: "20 kVA / 380 V",
    linkedProjects: [],
    updatedAt: "2026-08-08T03:00:00.000Z",
  };
  const equipmentGateway = {
    ...gateway(),
    createEquipment: async (_identity: unknown, command: Record<string, unknown>) => {
      received = command;
      return createdEquipment;
    },
  } as unknown as ManagementGateway;
  const body = {
    equipmentKey: " UPS-01 ",
    system: " 供配电系统 ",
    brand: " 华方 ",
    model: " HF-UPS-20K ",
    quantity: 2,
    status: "attention",
    lastInspectionAt: "2026-08-08T10:00:00+08:00",
    faultCount: 3,
    repairCount: 2,
    keyParameter: " 20 kVA / 380 V ",
    projectIds: ["11111111-1111-4111-8111-111111111111"],
    reason: "登记项目现场设备",
    idempotencyKey: "equipment-create-001",
    confirmation: "CREATE_EQUIPMENT",
  };

  const response = await routeManagement(new Request("https://ops/management/equipment", {
    method: "POST",
    headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
    body: JSON.stringify(body),
  }), equipmentGateway);

  assertEquals(response.status, 201);
  assertEquals(await response.json(), createdEquipment);
  assertEquals(received, {
    equipmentKey: "UPS-01",
    system: "供配电系统",
    brand: "华方",
    model: "HF-UPS-20K",
    quantity: 2,
    status: "attention",
    lastInspectionAt: "2026-08-08T02:00:00.000Z",
    faultCount: 3,
    repairCount: 2,
    keyParameter: "20 kVA / 380 V",
    projectIds: ["11111111-1111-4111-8111-111111111111"],
    reason: "登记项目现场设备",
    idempotencyKey: "equipment-create-001",
  });
});

Deno.test("rejects equipment creation without confirmation projects or administrator access", async () => {
  const validBody = {
    equipmentKey: "UPS-01",
    system: "供配电系统",
    brand: "华方",
    model: "HF-UPS-20K",
    quantity: 2,
    status: "normal",
    faultCount: 0,
    repairCount: 0,
    keyParameter: "",
    projectIds: ["11111111-1111-4111-8111-111111111111"],
    reason: "登记项目现场设备",
    idempotencyKey: "equipment-create-002",
  };
  const missingConfirmation = await routeManagement(new Request("https://ops/management/equipment", {
    method: "POST",
    headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
    body: JSON.stringify(validBody),
  }), gateway());
  const missingProjects = await routeManagement(new Request("https://ops/management/equipment", {
    method: "POST",
    headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
    body: JSON.stringify({ ...validBody, confirmation: "CREATE_EQUIPMENT", projectIds: [] }),
  }), gateway());
  const forbidden = await routeManagement(new Request("https://ops/management/equipment", {
    method: "POST",
    headers: { Authorization: "Bearer viewer-token", "Content-Type": "application/json" },
    body: JSON.stringify({ ...validBody, confirmation: "CREATE_EQUIPMENT" }),
  }), gateway());

  assertEquals(missingConfirmation.status, 400);
  assertEquals(await missingConfirmation.json(), { ok: false, error: "confirmation_required" });
  assertEquals(missingProjects.status, 400);
  assertEquals(forbidden.status, 403);
});

Deno.test("updates equipment with optimistic concurrency and atomically replaces project bindings", async () => {
  let equipmentId = "";
  let received: Record<string, unknown> | null = null;
  const updatedEquipment = {
    id: "22222222-2222-4222-8222-222222222222",
    equipmentKey: "UPS-01",
    system: "供配电系统",
    brand: "华方",
    model: "HF-UPS-20K-R2",
    quantity: 1,
    status: "maintenance",
    lastInspectionAt: null,
    faultCount: 4,
    repairCount: 2,
    keyParameter: "更换旁路板",
    linkedProjects: [],
    updatedAt: "2026-08-08T04:00:00.000Z",
  };
  const equipmentGateway = {
    ...gateway(),
    updateEquipment: async (_identity: unknown, id: string, command: Record<string, unknown>) => {
      equipmentId = id;
      received = command;
      return updatedEquipment;
    },
  } as unknown as ManagementGateway;
  const response = await routeManagement(new Request(
    "https://ops/management/equipment/22222222-2222-4222-8222-222222222222",
    {
      method: "PUT",
      headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
      body: JSON.stringify({
        equipmentKey: "UPS-01",
        system: "供配电系统",
        brand: "华方",
        model: "HF-UPS-20K-R2",
        quantity: 1,
        status: "maintenance",
        lastInspectionAt: null,
        faultCount: 4,
        repairCount: 2,
        keyParameter: "更换旁路板",
        projectIds: [
          "11111111-1111-4111-8111-111111111111",
          "33333333-3333-4333-8333-333333333333",
          "11111111-1111-4111-8111-111111111111",
        ],
        expectedUpdatedAt: "2026-08-08T03:00:00.000Z",
        reason: "设备维修后更新资料",
        idempotencyKey: "equipment-update-001",
        confirmation: "UPDATE_EQUIPMENT",
      }),
    },
  ), equipmentGateway);

  assertEquals(response.status, 200);
  assertEquals(equipmentId, "22222222-2222-4222-8222-222222222222");
  const receivedCommand = received as unknown as Record<string, unknown>;
  assertEquals((receivedCommand.projectIds as string[]).length, 2);
  assertEquals(receivedCommand.expectedUpdatedAt, "2026-08-08T03:00:00.000Z");
});

Deno.test("persists equipment through the audited transaction RPC and reloads the authorized catalog", async () => {
  const rpcCalls: Array<{ name: string; args: Record<string, unknown> }> = [];
  const rows: Record<string, Array<Record<string, unknown>>> = {
    ops_equipment: [{
      id: "22222222-2222-4222-8222-222222222222",
      equipment_key: "UPS-01",
      system: "供配电系统",
      brand: "华方",
      model: "HF-UPS-20K",
      quantity: 2,
      status: "attention",
      last_inspection_at: "2026-08-08T02:00:00.000Z",
      fault_count: 3,
      repair_count: 2,
      key_parameter: "20 kVA / 380 V",
      updated_at: "2026-08-08T03:00:00.000Z",
    }],
    ops_equipment_projects: [{
      equipment_id: "22222222-2222-4222-8222-222222222222",
      project_id: "11111111-1111-4111-8111-111111111111",
    }],
    ops_projects: [{
      id: "11111111-1111-4111-8111-111111111111",
      local_project_id: "local-project-a",
      title: "一号机房",
      status: "active",
    }],
    maintenance_tasks: [{ project_id: "11111111-1111-4111-8111-111111111111" }],
  };
  const supabase = {
    rpc: async (name: string, args: Record<string, unknown>) => {
      rpcCalls.push({ name, args });
      return { data: "22222222-2222-4222-8222-222222222222", error: null };
    },
    from: (table: string) => {
      const builder: any = {
        select: () => builder,
        eq: () => builder,
        in: () => builder,
        order: () => builder,
        then: (resolve: (value: unknown) => unknown, reject: (reason: unknown) => unknown) =>
          Promise.resolve({ data: rows[table] ?? [], error: null }).then(resolve, reject),
      };
      return builder;
    },
  };
  const management = createManagementGateway(supabase, {} as never);

  const created = await management.createEquipment?.(identity, {
    equipmentKey: "UPS-01",
    system: "供配电系统",
    brand: "华方",
    model: "HF-UPS-20K",
    quantity: 2,
    status: "attention",
    lastInspectionAt: "2026-08-08T02:00:00.000Z",
    faultCount: 3,
    repairCount: 2,
    keyParameter: "20 kVA / 380 V",
    projectIds: ["11111111-1111-4111-8111-111111111111"],
    reason: "登记项目现场设备",
    idempotencyKey: "equipment-create-003",
  });

  assertEquals(created?.equipmentKey, "UPS-01");
  assertEquals(created?.linkedProjects, [{
    projectId: "11111111-1111-4111-8111-111111111111",
    localProjectId: "local-project-a",
    title: "一号机房",
    status: "active",
    taskCount: 1,
  }]);
  assertEquals(rpcCalls[0].name, "manage_ops_equipment");
  assertEquals(rpcCalls[0].args.operation_name, "create");
  assertEquals(rpcCalls[0].args.target_equipment_id, null);
  assertEquals(rpcCalls[0].args.actor_id, "user-a");
  assertEquals((rpcCalls[0].args.command_hash as string).length, 64);
});

Deno.test("maps an equipment optimistic concurrency failure to a stable management error", async () => {
  const supabase = {
    rpc: async () => ({ data: null, error: { message: "equipment_version_conflict" } }),
  };
  const management = createManagementGateway(supabase, {} as never);

  await assertRejects(
    () => management.updateEquipment!(identity, "22222222-2222-4222-8222-222222222222", {
      equipmentKey: "UPS-01",
      system: "供配电系统",
      brand: "华方",
      model: "HF-UPS-20K-R2",
      quantity: 1,
      status: "maintenance",
      lastInspectionAt: null,
      faultCount: 4,
      repairCount: 2,
      keyParameter: "更换旁路板",
      projectIds: ["11111111-1111-4111-8111-111111111111"],
      expectedUpdatedAt: "2026-08-08T03:00:00.000Z",
      reason: "设备维修后更新资料",
      idempotencyKey: "equipment-update-002",
    }),
    Error,
    "equipment_version_conflict",
  );
});

Deno.test("maps controlled equipment transaction failures to stable management errors", async () => {
  const cases = [
    ["equipment_key_conflict", 409],
    ["equipment_idempotency_conflict", 409],
    ["equipment_project_not_found", 404],
    ["equipment_not_found", 404],
    ["equipment_management_forbidden", 403],
    ["equipment_payload_invalid", 400],
    ["Could not find the function public.manage_ops_equipment", 503],
  ] as const;

  for (const [message, expectedStatus] of cases) {
    const supabase = {
      rpc: async () => ({ data: null, error: { message } }),
    };
    const management = createManagementGateway(supabase, {} as never);

    await assertRejects(
      () => management.createEquipment!(identity, {
        equipmentKey: "UPS-01",
        system: "供配电系统",
        brand: "华方",
        model: "HF-UPS-20K",
        quantity: 1,
        status: "normal",
        lastInspectionAt: null,
        faultCount: 0,
        repairCount: 0,
        keyParameter: "20 kVA / 380 V",
        projectIds: ["11111111-1111-4111-8111-111111111111"],
        reason: "登记项目现场设备",
        idempotencyKey: "equipment-create-errors",
      }),
      ManagementOperationError,
      message.startsWith("Could not find") ? "equipment_management_unavailable" : message,
    ).then((error) => assertEquals(error.status, expectedStatus));
  }
});

Deno.test("joins device credential session manifest and sync health without per-device queries", async () => {
  const now = new Date();
  const future = new Date(now.getTime() + 60 * 60 * 1000).toISOString();
  const recent = new Date(now.getTime() - 60 * 1000).toISOString();
  const calls: string[] = [];
  const rows: Record<string, Array<Record<string, unknown>>> = {
    glasses_devices: [{
      id: "device-a",
      device_key: "AIR3-001",
      display_name: "Air3 一号机",
      status: "online",
      assigned_profile_id: "person-a",
      model: "Air3",
      app_version: "9.0.0",
      mdm_policy_version: "policy-3",
      mdm_compliance_status: "compliant",
      last_seen_at: recent,
      revoked_at: null,
    }],
    glasses_device_tokens: [{
      id: "credential-a",
      device_id: "device-a",
      status: "active",
      issued_at: recent,
      expires_at: future,
      revoked_at: null,
      revoke_reason: "",
    }],
    glasses_device_sessions: [{
      id: "session-a",
      device_id: "device-a",
      status: "active",
      issued_at: recent,
      last_used_at: recent,
      expires_at: future,
      revoked_at: null,
      revoke_reason: "",
    }],
    device_content_manifests: [{
      id: "manifest-a",
      device_id: "device-a",
      project_id: "project-a",
      manifest_version: 7,
      etag: "a".repeat(64),
      status: "active",
      generated_at: recent,
      expires_at: future,
    }],
  };
  const supabase = {
    from: (table: string) => {
      calls.push(table);
      const builder: any = {
        select: () => builder,
        eq: () => builder,
        in: () => builder,
        order: () => builder,
        limit: () => builder,
        then: (resolve: (value: unknown) => unknown) => Promise.resolve({ data: rows[table] ?? [], error: null }).then(resolve),
      };
      return builder;
    },
  };
  const management = createManagementGateway(supabase, {} as never);

  const devices = await management.devices(superAdmin);

  assertEquals(calls, [
    "glasses_devices",
    "glasses_device_tokens",
    "glasses_device_sessions",
    "device_content_manifests",
  ]);
  assertEquals(devices[0], {
    ...rows.glasses_devices[0],
    credential_status: "active",
    credential_issued_at: recent,
    credential_expires_at: future,
    session_status: "active",
    session_last_used_at: recent,
    session_expires_at: future,
    manifest_version: 7,
    manifest_status: "healthy",
    manifest_generated_at: recent,
    manifest_expires_at: future,
    sync_health: "healthy",
    sync_issue: "",
  });
});

Deno.test("classifies blocked attention and offline device sync failures", async () => {
  const now = new Date();
  const future = new Date(now.getTime() + 60 * 60 * 1000).toISOString();
  const past = new Date(now.getTime() - 60 * 1000).toISOString();
  const recent = new Date(now.getTime() - 60 * 1000).toISOString();
  const stale = new Date(now.getTime() - 21 * 60 * 1000).toISOString();
  const rows: Record<string, Array<Record<string, unknown>>> = {
    glasses_devices: [
      {
        id: "missing-credential",
        device_key: "AIR3-001",
        display_name: "Missing credential",
        status: "online",
        mdm_compliance_status: "compliant",
        last_seen_at: recent,
        revoked_at: null,
      },
      {
        id: "expired-manifest",
        device_key: "AIR3-002",
        display_name: "Expired manifest",
        status: "online",
        mdm_compliance_status: "compliant",
        last_seen_at: recent,
        revoked_at: null,
      },
      {
        id: "stale-device",
        device_key: "AIR3-003",
        display_name: "Stale device",
        status: "online",
        mdm_compliance_status: "compliant",
        last_seen_at: stale,
        revoked_at: null,
      },
    ],
    glasses_device_tokens: [
      { device_id: "expired-manifest", status: "active", issued_at: recent, expires_at: future },
      { device_id: "stale-device", status: "active", issued_at: recent, expires_at: future },
    ],
    glasses_device_sessions: [
      { device_id: "missing-credential", status: "active", issued_at: recent, expires_at: future },
      { device_id: "expired-manifest", status: "active", issued_at: recent, expires_at: future },
      { device_id: "stale-device", status: "active", issued_at: recent, expires_at: future },
    ],
    device_content_manifests: [
      { device_id: "missing-credential", status: "active", manifest_version: 1, expires_at: future },
      { device_id: "expired-manifest", status: "active", manifest_version: 2, expires_at: past },
      { device_id: "stale-device", status: "active", manifest_version: 3, expires_at: future },
    ],
  };
  const supabase = {
    from: (table: string) => {
      const builder: any = {
        select: () => builder,
        eq: () => builder,
        in: () => builder,
        order: () => builder,
        limit: () => builder,
        then: (resolve: (value: unknown) => unknown) =>
          Promise.resolve({ data: rows[table] ?? [], error: null }).then(resolve),
      };
      return builder;
    },
  };
  const management = createManagementGateway(supabase, {} as never);

  const devices = await management.devices(superAdmin) as Array<Record<string, unknown>>;

  assertEquals(devices.map(({ id, sync_health, sync_issue }) => ({ id, sync_health, sync_issue })), [
    { id: "missing-credential", sync_health: "blocked", sync_issue: "credential_missing" },
    { id: "expired-manifest", sync_health: "attention", sync_issue: "manifest_expired" },
    { id: "stale-device", sync_health: "offline", sync_issue: "device_offline" },
  ]);
});

Deno.test("returns people only to organization administrators", async () => {
  const allowed = await routeManagement(new Request("https://ops/management/people", {
    headers: { Authorization: "Bearer valid-token" },
  }), gateway());
  assertEquals(allowed.status, 200);
  assertEquals(await allowed.json(), {
    items: [{ id: "user-a", display_name: "王工", role: "ops_admin", status: "active" }],
  });

  const denied = await routeManagement(new Request("https://ops/management/people", {
    headers: { Authorization: "Bearer viewer-token" },
  }), gateway());
  assertEquals(denied.status, 403);
});

Deno.test("requires super administrator confirmation before inviting an account", async () => {
  const forbidden = await routeManagement(new Request("https://ops/management/people/invitations", {
    method: "POST",
    headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
    body: JSON.stringify({
      email: "engineer@example.com",
      displayName: "李工",
      role: "field_engineer",
      reason: "新增现场工程师",
      confirmation: "INVITE_ACCOUNT",
    }),
  }), gateway());
  const missingConfirmation = await routeManagement(new Request("https://ops/management/people/invitations", {
    method: "POST",
    headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
    body: JSON.stringify({
      email: "engineer@example.com",
      displayName: "李工",
      role: "field_engineer",
      reason: "新增现场工程师",
    }),
  }), gateway());

  assertEquals(forbidden.status, 403);
  assertEquals(missingConfirmation.status, 400);
  assertEquals(await missingConfirmation.json(), { ok: false, error: "confirmation_required" });
});

Deno.test("invites an account without accepting or returning a password", async () => {
  let received: Record<string, unknown> | null = null;
  const inviteGateway = {
    ...gateway(),
    invitePerson: async (_identity, command) => {
      received = command;
      return { profileId: "invited-a", email: command.email, status: "invited" as const };
    },
  } as ManagementGateway;
  const response = await routeManagement(new Request("https://ops/management/people/invitations", {
    method: "POST",
    headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
    body: JSON.stringify({
      email: "engineer@example.com",
      displayName: "李工",
      role: "field_engineer",
      reason: "新增现场工程师",
      confirmation: "INVITE_ACCOUNT",
      password: "must-not-be-accepted",
    }),
  }), inviteGateway);

  assertEquals(response.status, 201);
  assertEquals(received, {
    email: "engineer@example.com",
    displayName: "李工",
    role: "field_engineer",
    reason: "新增现场工程师",
  });
  assertEquals(await response.json(), {
    profileId: "invited-a",
    email: "engineer@example.com",
    status: "invited",
  });
});

Deno.test("changes a profile role only with explicit super administrator confirmation", async () => {
  let received: Record<string, unknown> | null = null;
  const roleGateway = {
    ...gateway(),
    setProfileRole: async (_identity, profileId, command) => {
      received = { profileId, ...command };
      return true;
    },
  } as ManagementGateway;
  const missingConfirmation = await routeManagement(new Request("https://ops/management/people/user-a/role", {
    method: "POST",
    headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
    body: JSON.stringify({ role: "remote_expert", reason: "转为远程专家" }),
  }), roleGateway);
  const allowed = await routeManagement(new Request("https://ops/management/people/user-a/role", {
    method: "POST",
    headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
    body: JSON.stringify({ role: "remote_expert", reason: "转为远程专家", confirmation: "CHANGE_ACCOUNT_ROLE" }),
  }), roleGateway);

  assertEquals(missingConfirmation.status, 400);
  assertEquals(allowed.status, 200);
  assertEquals(received, { profileId: "user-a", role: "remote_expert", reason: "转为远程专家" });
});

Deno.test("sends account recovery only for a super administrator with explicit confirmation", async () => {
  let received: Record<string, unknown> | null = null;
  const recoveryGateway = {
    ...gateway(),
    sendAccountRecovery: async (_identity: unknown, profileId: string, command: Record<string, unknown>) => {
      received = { profileId, ...command };
      return true;
    },
  } as ManagementGateway;
  const denied = await routeManagement(new Request("https://ops/management/people/user-a/recovery", {
    method: "POST",
    headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
    body: JSON.stringify({ reason: "账号本人无法登录", confirmation: "SEND_ACCOUNT_RECOVERY" }),
  }), recoveryGateway);
  const missingConfirmation = await routeManagement(new Request("https://ops/management/people/user-a/recovery", {
    method: "POST",
    headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
    body: JSON.stringify({ reason: "账号本人无法登录" }),
  }), recoveryGateway);
  const allowed = await routeManagement(new Request("https://ops/management/people/user-a/recovery", {
    method: "POST",
    headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
    body: JSON.stringify({ reason: "账号本人无法登录", confirmation: "SEND_ACCOUNT_RECOVERY" }),
  }), recoveryGateway);

  assertEquals(denied.status, 403);
  assertEquals(missingConfirmation.status, 400);
  assertEquals(allowed.status, 200);
  assertEquals(received, { profileId: "user-a", reason: "账号本人无法登录" });
});

Deno.test("returns redacted voiceprint profiles to organization administrators", async () => {
  const operationsAdmin = await routeManagement(new Request("https://ops/management/voiceprints", {
    headers: { Authorization: "Bearer valid-token" },
  }), gateway());
  const superAdministrator = await routeManagement(new Request("https://ops/management/voiceprints", {
    headers: { Authorization: "Bearer super-token" },
  }), gateway());
  const denied = await routeManagement(new Request("https://ops/management/voiceprints", {
    headers: { Authorization: "Bearer viewer-token" },
  }), gateway());

  assertEquals(operationsAdmin.status, 200);
  assertEquals((await operationsAdmin.json()).items[0].canRevoke, false);
  assertEquals((await superAdministrator.json()).items[0].canRevoke, true);
  assertEquals(denied.status, 403);
});

Deno.test("allows only a super administrator to revoke a voiceprint with confirmation", async () => {
  let received: Record<string, unknown> | null = null;
  const voiceprintGateway = {
    ...gateway(),
    revokeVoiceprint: async (_identity, profileId, command) => {
      received = { profileId, ...command };
      return await gateway().revokeVoiceprint(superAdmin, profileId, command);
    },
  } as ManagementGateway;
  const denied = await routeManagement(new Request("https://ops/management/voiceprints/voiceprint-a/revoke", {
    method: "POST",
    headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
    body: JSON.stringify({ reason: "设备交回", idempotencyKey: "voiceprint-revoke-1", confirmation: "REVOKE_VOICEPRINT" }),
  }), voiceprintGateway);
  const missingConfirmation = await routeManagement(new Request("https://ops/management/voiceprints/voiceprint-a/revoke", {
    method: "POST",
    headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
    body: JSON.stringify({ reason: "设备交回", idempotencyKey: "voiceprint-revoke-1" }),
  }), voiceprintGateway);
  const allowed = await routeManagement(new Request("https://ops/management/voiceprints/voiceprint-a/revoke", {
    method: "POST",
    headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
    body: JSON.stringify({ reason: "设备交回", idempotencyKey: "voiceprint-revoke-1", confirmation: "REVOKE_VOICEPRINT" }),
  }), voiceprintGateway);

  assertEquals(denied.status, 403);
  assertEquals(missingConfirmation.status, 400);
  assertEquals(allowed.status, 200);
  assertEquals(received, {
    profileId: "voiceprint-a",
    reason: "设备交回",
    idempotencyKey: "voiceprint-revoke-1",
  });
});

Deno.test("returns project memberships only to organization administrators", async () => {
  const allowed = await routeManagement(new Request("https://ops/management/projects/project-a/memberships", {
    headers: { Authorization: "Bearer valid-token" },
  }), gateway());
  assertEquals(allowed.status, 200);
  assertEquals(await allowed.json(), {
    items: [{ profile_id: "engineer-a", access_role: "engineer", status: "active" }],
  });

  const denied = await routeManagement(new Request("https://ops/management/projects/project-a/memberships", {
    headers: { Authorization: "Bearer viewer-token" },
  }), gateway());
  assertEquals(denied.status, 403);
});

Deno.test("grants project membership with an explicit role and reason", async () => {
  let received: Record<string, unknown> | null = null;
  const membershipGateway = {
    ...gateway(),
    grantProjectMembership: async (_identity, projectId, command) => {
      received = { projectId, ...command };
      return true;
    },
  } as ManagementGateway;
  const response = await routeManagement(new Request("https://ops/management/projects/project-a/memberships", {
    method: "POST",
    headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
    body: JSON.stringify({ profileId: "engineer-a", accessRole: "engineer", reason: "加入现场项目" }),
  }), membershipGateway);

  assertEquals(response.status, 200);
  assertEquals(received, {
    projectId: "project-a",
    profileId: "engineer-a",
    accessRole: "engineer",
    reason: "加入现场项目",
  });
});

Deno.test("requires confirmation before revoking project membership", async () => {
  const response = await routeManagement(new Request(
    "https://ops/management/projects/project-a/memberships/engineer-a/revoke",
    {
      method: "POST",
      headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
      body: JSON.stringify({ reason: "人员退出项目" }),
    },
  ), gateway());
  assertEquals(response.status, 400);
  assertEquals(await response.json(), { ok: false, error: "confirmation_required" });
});

Deno.test("revokes confirmed project membership through the authorized gateway", async () => {
  const response = await routeManagement(new Request(
    "https://ops/management/projects/project-a/memberships/engineer-a/revoke",
    {
      method: "POST",
      headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
      body: JSON.stringify({
        reason: "人员退出项目",
        confirmation: "REVOKE_PROJECT_ACCESS",
      }),
    },
  ), gateway());
  assertEquals(response.status, 200);
  assertEquals(await response.json(), { ok: true });
});

Deno.test("returns signed media only for an authorized task", async () => {
  const response = await routeManagement(new Request("https://ops/management/tasks/task-a", {
    headers: { Authorization: "Bearer valid-token" },
  }), gateway());
  const body = await response.json();
  assertStringIncludes(body.media[0].url, "signed.example");
});

Deno.test("issues a device credential only through an authenticated management request", async () => {
  const credentialGateway = {
    ...gateway(),
    issueDeviceCredential: async () => ({ token: "issued-once", expiresAt: "2026-08-30T00:00:00.000Z" }),
  } as ManagementGateway;
  const response = await routeManagement(new Request("https://ops/management/devices/device-a/credentials", {
    method: "POST",
    headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
    body: JSON.stringify({ reason: "设备首次受管激活", confirmation: "ISSUE_DEVICE_CREDENTIAL" }),
  }), credentialGateway);
  assertEquals(response.status, 201);
  assertEquals(await response.json(), { token: "issued-once", expiresAt: "2026-08-30T00:00:00.000Z" });
});

Deno.test("requires explicit confirmation before issuing a device credential", async () => {
  const response = await routeManagement(new Request("https://ops/management/devices/device-a/credentials", {
    method: "POST",
    headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
    body: JSON.stringify({ reason: "设备首次受管激活" }),
  }), gateway());
  assertEquals(response.status, 400);
  assertEquals(await response.json(), { ok: false, error: "confirmation_required" });
});

Deno.test("rejects device credential issuance by an operations administrator", async () => {
  const response = await routeManagement(new Request("https://ops/management/devices/device-a/credentials", {
    method: "POST",
    headers: { Authorization: "Bearer valid-token" },
  }), gateway());
  assertEquals(response.status, 403);
});

Deno.test("issues a short-lived one-time device activation only to a super administrator", async () => {
  let received: Record<string, unknown> | null = null;
  const activationGateway = {
    ...gateway(),
    issueDeviceActivation: async (_identity: unknown, deviceId: string, command: Record<string, unknown>) => {
      received = { deviceId, ...command };
      return {
        activationCode: "HF9-ABCD-EFGH-JKLM",
        expiresAt: "2026-08-03T12:10:00.000Z",
        deviceId,
      };
    },
  } as ManagementGateway;
  const response = await routeManagement(new Request(
    "https://ops/management/devices/device-a/activation-codes",
    {
      method: "POST",
      headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
      body: JSON.stringify({
        reason: "设备首次部署",
        confirmation: "ISSUE_DEVICE_ACTIVATION",
        expiresInSeconds: 600,
      }),
    },
  ), activationGateway);

  assertEquals(response.status, 201);
  assertEquals(response.headers.get("Cache-Control"), "no-store");
  assertEquals(received, {
    deviceId: "device-a",
    reason: "设备首次部署",
    expiresInSeconds: 600,
  });
  assertEquals(await response.json(), {
    activationCode: "HF9-ABCD-EFGH-JKLM",
    expiresAt: "2026-08-03T12:10:00.000Z",
    deviceId: "device-a",
  });
});

Deno.test("rejects activation issuance without confirmation or a five-to-fifteen-minute expiry", async () => {
  let calls = 0;
  const activationGateway = {
    ...gateway(),
    issueDeviceActivation: async () => {
      calls += 1;
      return null;
    },
  } as ManagementGateway;
  const missingConfirmation = await routeManagement(new Request(
    "https://ops/management/devices/device-a/activation-codes",
    {
      method: "POST",
      headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
      body: JSON.stringify({ reason: "设备首次部署", expiresInSeconds: 600 }),
    },
  ), activationGateway);
  const invalidExpiry = await routeManagement(new Request(
    "https://ops/management/devices/device-a/activation-codes",
    {
      method: "POST",
      headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
      body: JSON.stringify({
        reason: "设备首次部署",
        confirmation: "ISSUE_DEVICE_ACTIVATION",
        expiresInSeconds: 60,
      }),
    },
  ), activationGateway);
  const forbidden = await routeManagement(new Request(
    "https://ops/management/devices/device-a/activation-codes",
    {
      method: "POST",
      headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
      body: JSON.stringify({
        reason: "设备首次部署",
        confirmation: "ISSUE_DEVICE_ACTIVATION",
        expiresInSeconds: 600,
      }),
    },
  ), activationGateway);

  assertEquals(missingConfirmation.status, 400);
  assertEquals(invalidExpiry.status, 400);
  assertEquals(forbidden.status, 403);
  assertEquals(calls, 0);
});

Deno.test("hashes a generated activation code before persisting it", async () => {
  let rpcName = "";
  let rpcArgs: Record<string, unknown> = {};
  const supabase = {
    rpc: async (name: string, args: Record<string, unknown>) => {
      rpcName = name;
      rpcArgs = args;
      return { data: true, error: null };
    },
  };
  const management = createManagementGateway(
    supabase,
    {} as never,
    undefined,
    { accountRecoveryRedirectUrl: "https://ops.example.com/" },
  );
  const before = Date.now();

  const issued = await management.issueDeviceActivation?.(
    superAdmin,
    "device-a",
    { reason: "设备首次部署", expiresInSeconds: 600 },
  );

  assertEquals(rpcName, "issue_device_activation_code");
  assertEquals(String(rpcArgs.activation_code_hash).length, 64);
  assertEquals(rpcArgs.activation_code_hash === issued?.activationCode, false);
  assertEquals(rpcArgs.target_device_id, "device-a");
  assertEquals(rpcArgs.actor_id, "super-a");
  assertEquals(rpcArgs.reason, "设备首次部署");
  assertEquals(/^HF9-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(
    issued?.activationCode ?? "",
  ), true);
  const lifetime = Date.parse(issued?.expiresAt ?? "") - before;
  assertEquals(lifetime >= 599_000 && lifetime <= 601_000, true);
});

Deno.test("provisions an invited profile through Supabase Auth Admin without a password", async () => {
  const inserts: Array<{ table: string; value: unknown }> = [];
  const supabase = {
    from: (table: string) => {
      const builder: any = {
        select: () => builder,
        eq: () => builder,
        maybeSingle: async () => ({ data: null, error: null }),
        insert: async (value: unknown) => {
          inserts.push({ table, value });
          return { data: null, error: null };
        },
      };
      return builder;
    },
    auth: {
      admin: {
        inviteUserByEmail: async (email: string, options: Record<string, unknown>) => ({
          data: { user: { id: "auth-invited-a", email, user_metadata: options.data } },
          error: null,
        }),
        deleteUser: async () => ({ error: null }),
      },
    },
  };
  const management = createManagementGateway(supabase, {} as never);

  const invited = await management.invitePerson(superAdmin, {
    email: "li@example.com",
    displayName: "李工",
    role: "field_engineer",
    reason: "新增现场工程师",
  });

  assertEquals(invited, { profileId: "auth-invited-a", email: "li@example.com", status: "invited" });
  assertEquals(inserts[0], {
    table: "ops_profiles",
    value: {
      id: "auth-invited-a",
      organization_id: "org-a",
      email: "li@example.com",
      display_name: "李工",
      role: "field_engineer",
      status: "invited",
      active: false,
      lifecycle_reason: "新增现场工程师",
    },
  });
  assertEquals(inserts[1].table, "audit_events");
  assertEquals((inserts[1].value as Record<string, unknown>).metadata, {
    email: "li@example.com",
    role: "field_engineer",
    reason: "新增现场工程师",
  });
});

Deno.test("requests a real Supabase recovery email after writing an audit event", async () => {
  const calls: string[] = [];
  let recoveryEmail = "";
  let recoveryOptions: Record<string, unknown> | undefined;
  const supabase = {
    from: (table: string) => {
      const builder: any = {
        select: () => builder,
        eq: () => builder,
        maybeSingle: async () => ({
          data: table === "ops_profiles"
            ? { id: "person-a", email: "zhang@example.com", status: "active" }
            : null,
          error: null,
        }),
        insert: async (value: Record<string, unknown>) => {
          calls.push(`audit:${String(value.action)}`);
          return { data: null, error: null };
        },
      };
      return builder;
    },
    auth: {
      resetPasswordForEmail: async (email: string, options?: Record<string, unknown>) => {
        calls.push("recovery-email");
        recoveryEmail = email;
        recoveryOptions = options;
        return { data: {}, error: null };
      },
    },
  };
  const management = createManagementGateway(
    supabase,
    {} as never,
    undefined,
    { accountRecoveryRedirectUrl: "https://ops.example.com/" },
  );

  const accepted = await (management as any).sendAccountRecovery(superAdmin, "person-a", {
    reason: "账号本人无法登录",
  });

  assertEquals(accepted, true);
  assertEquals(recoveryEmail, "zhang@example.com");
  assertEquals(recoveryOptions, { redirectTo: "https://ops.example.com/" });
  assertEquals(calls, ["audit:profile_recovery_requested", "recovery-email"]);
});

Deno.test("does not send account recovery when the audit event cannot be written", async () => {
  let recoveryCalls = 0;
  const supabase = {
    from: (table: string) => {
      const builder: any = {
        select: () => builder,
        eq: () => builder,
        maybeSingle: async () => ({
          data: table === "ops_profiles"
            ? { id: "person-a", email: "zhang@example.com", status: "active" }
            : null,
          error: null,
        }),
        insert: async () => ({ data: null, error: { message: "audit unavailable" } }),
      };
      return builder;
    },
    auth: {
      resetPasswordForEmail: async () => {
        recoveryCalls += 1;
        return { data: {}, error: null };
      },
    },
  };
  const management = createManagementGateway(
    supabase,
    {} as never,
    undefined,
    { accountRecoveryRedirectUrl: "https://ops.example.com/" },
  );

  await assertRejects(
    () => (management as any).sendAccountRecovery(superAdmin, "person-a", {
      reason: "账号本人无法登录",
    }),
    Error,
    "account_recovery_audit_failed",
  );
  assertEquals(recoveryCalls, 0);
});

Deno.test("fails account recovery closed when the HTTPS management redirect is not configured", async () => {
  let recoveryCalls = 0;
  const supabase = {
    from: () => {
      const builder: any = {
        select: () => builder,
        eq: () => builder,
        maybeSingle: async () => ({
          data: { id: "person-a", email: "zhang@example.com", status: "active" },
          error: null,
        }),
        insert: async () => ({ data: null, error: null }),
      };
      return builder;
    },
    auth: {
      resetPasswordForEmail: async () => {
        recoveryCalls += 1;
        return { data: {}, error: null };
      },
    },
  };
  const management = createManagementGateway(supabase, {} as never);

  await assertRejects(
    () => management.sendAccountRecovery(superAdmin, "person-a", {
      reason: "账号本人无法登录",
    }),
    Error,
    "account_recovery_redirect_unconfigured",
  );
  assertEquals(recoveryCalls, 0);
});

Deno.test("rolls back an invited account when the audit event cannot be written", async () => {
  const deletedProfiles: string[] = [];
  const deletedAuthUsers: string[] = [];
  const supabase = {
    from: (table: string) => {
      const builder: any = {
        select: () => builder,
        eq: () => builder,
        maybeSingle: async () => ({ data: null, error: null }),
        insert: async () => table === "audit_events"
          ? { data: null, error: { message: "audit unavailable" } }
          : { data: null, error: null },
        delete: () => ({
          eq: async (_column: string, value: string) => {
            deletedProfiles.push(value);
            return { data: null, error: null };
          },
        }),
      };
      return builder;
    },
    auth: {
      admin: {
        inviteUserByEmail: async (email: string) => ({
          data: { user: { id: "auth-invited-b", email } },
          error: null,
        }),
        deleteUser: async (profileId: string) => {
          deletedAuthUsers.push(profileId);
          return { error: null };
        },
      },
    },
  };
  const management = createManagementGateway(supabase, {} as never);

  await assertRejects(
    () => management.invitePerson(superAdmin, {
      email: "audit-failure@example.com",
      displayName: "审计失败账号",
      role: "field_engineer",
      reason: "验证审计失败回滚",
    }),
    Error,
    "account_audit_write_failed",
  );
  assertEquals(deletedProfiles, ["auth-invited-b"]);
  assertEquals(deletedAuthUsers, ["auth-invited-b"]);
});

Deno.test("routes profile role changes through the service-role RPC", async () => {
  let rpcName = "";
  let rpcArgs: Record<string, unknown> = {};
  const supabase = {
    from: () => {
      const builder: any = {
        select: () => builder,
        eq: () => builder,
        maybeSingle: async () => ({ data: { id: "person-a" }, error: null }),
      };
      return builder;
    },
    rpc: async (name: string, args: Record<string, unknown>) => {
      rpcName = name;
      rpcArgs = args;
      return { data: null, error: null };
    },
  };
  const management = createManagementGateway(supabase, {} as never);
  const accepted = await management.setProfileRole(superAdmin, "person-a", {
    role: "remote_expert",
    reason: "转为远程专家",
  });

  assertEquals(accepted, true);
  assertEquals(rpcName, "set_ops_profile_role");
  assertEquals(rpcArgs, {
    target_profile_id: "person-a",
    new_role: "remote_expert",
    actor_id: "super-a",
    reason: "转为远程专家",
  });
});

Deno.test("binds a device only with a target profile and reason", async () => {
  let received: Record<string, unknown> | null = null;
  const bindingGateway = {
    ...gateway(),
    bindDevice: async (_identity, deviceId, command) => {
      received = { deviceId, ...command };
      return true;
    },
  } as ManagementGateway;
  const response = await routeManagement(new Request("https://ops/management/devices/device-a/bindings", {
    method: "POST",
    headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
    body: JSON.stringify({ profileId: "engineer-a", projectId: "project-a", reason: "现场设备领用" }),
  }), bindingGateway);

  assertEquals(response.status, 200);
  assertEquals(received, {
    deviceId: "device-a",
    profileId: "engineer-a",
    projectId: "project-a",
    reason: "现场设备领用",
  });
});

Deno.test("requires explicit confirmation before unbinding a device", async () => {
  const response = await routeManagement(new Request("https://ops/management/devices/device-a/unbind", {
    method: "POST",
    headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
    body: JSON.stringify({ reason: "人员调离" }),
  }), gateway());
  assertEquals(response.status, 400);
  assertEquals(await response.json(), { ok: false, error: "confirmation_required" });
});

Deno.test("allows a confirmed device unbind by an operations administrator", async () => {
  const response = await routeManagement(new Request("https://ops/management/devices/device-a/unbind", {
    method: "POST",
    headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
    body: JSON.stringify({ reason: "人员调离", confirmation: "UNBIND_DEVICE" }),
  }), gateway());
  assertEquals(response.status, 200);
  assertEquals(await response.json(), { ok: true });
});

Deno.test("allows only a super administrator to revoke a device", async () => {
  const denied = await routeManagement(new Request("https://ops/management/devices/device-a/revoke", {
    method: "POST",
    headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
    body: JSON.stringify({ reason: "设备遗失", confirmation: "REVOKE_DEVICE" }),
  }), gateway());
  assertEquals(denied.status, 403);

  const allowed = await routeManagement(new Request("https://ops/management/devices/device-a/revoke", {
    method: "POST",
    headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
    body: JSON.stringify({ reason: "设备遗失", confirmation: "REVOKE_DEVICE" }),
  }), gateway());
  assertEquals(allowed.status, 200);
});

Deno.test("allows only a super administrator to change account status", async () => {
  const denied = await routeManagement(new Request("https://ops/management/people/user-a/status", {
    method: "POST",
    headers: { Authorization: "Bearer valid-token", "Content-Type": "application/json" },
    body: JSON.stringify({ status: "disabled", reason: "离职", confirmation: "CHANGE_ACCOUNT_STATUS" }),
  }), gateway());
  assertEquals(denied.status, 403);

  const allowed = await routeManagement(new Request("https://ops/management/people/user-a/status", {
    method: "POST",
    headers: { Authorization: "Bearer super-token", "Content-Type": "application/json" },
    body: JSON.stringify({ status: "disabled", reason: "离职", confirmation: "CHANGE_ACCOUNT_STATUS" }),
  }), gateway());
  assertEquals(allowed.status, 200);
});
