import { assertEquals, assertStringIncludes } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { routeManagement, type ManagementGateway } from "./management.ts";

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
    projects: async () => [{ id: "project-a", title: "实训室" }],
    people: async () => [{ id: "user-a", display_name: "王工", role: "ops_admin", status: "active" }],
    projectMemberships: async () => [{ profile_id: "engineer-a", access_role: "engineer", status: "active" }],
    devices: async () => [{ id: "device-a", display_name: "Air3" }],
    tasks: async () => ({ items: [{ id: "task-a", title: "温湿度异常", status: "active" }], nextCursor: null }),
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

Deno.test("accepts the deployed Supabase management path", async () => {
  const response = await routeManagement(new Request(
    "https://project.supabase.co/functions/v1/ops-glasses/management/tasks",
    { headers: { Authorization: "Bearer valid-token" } },
  ), gateway());

  assertEquals(response.status, 200);
});

Deno.test("returns devices only after bearer authentication", async () => {
  const response = await routeManagement(new Request("https://ops/management/devices", {
    headers: { Authorization: "Bearer valid-token" },
  }), gateway());
  assertEquals(response.status, 200);
  assertEquals(await response.json(), { items: [{ id: "device-a", display_name: "Air3" }] });
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
