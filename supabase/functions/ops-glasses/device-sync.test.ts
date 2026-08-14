import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { createDeviceSyncGateway, routeDeviceSync, type DeviceSyncGateway } from "./device-sync.ts";

const identity = {
  deviceId: "device-a",
  organizationId: "org-a",
  actorProfileId: "profile-a",
};

type SessionGateway = DeviceSyncGateway & {
  authenticateBootstrap(token: string): Promise<typeof identity & { bootstrapTokenId: string } | null>;
  issueSession(bootstrapIdentity: typeof identity & { bootstrapTokenId: string }): Promise<{
    accessToken: string;
    expiresAt: string;
  }>;
};

function gateway(overrides: Partial<SessionGateway> = {}): SessionGateway {
  return {
    authenticateBootstrap: async (token) => token === "bootstrap-token"
      ? { ...identity, bootstrapTokenId: "bootstrap-a" }
      : null,
    issueSession: async () => ({
      accessToken: "access-token",
      expiresAt: "2026-07-31T04:15:00.000Z",
    }),
    authenticateDevice: async (token) => token === "access-token" ? identity : null,
    appendEvent: async () => ({ duplicate: false }),
    ...overrides,
  };
}

function eventRequest(
  token = "access-token",
  overrides: Record<string, unknown> = {},
): Request {
  return new Request("https://ops/device-sync/events", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      localProjectId: "project-local-a",
      projectTitle: "机房巡检",
      localTaskId: "task-local-a",
      taskTitle: "温湿度异常",
      eventType: "user_message",
      payload: { text: "开始检查" },
      occurredAt: "2026-07-31T04:00:00.000Z",
      idempotencyKey: "event-a",
      ...overrides,
    }),
  });
}

function sessionRequest(token = "bootstrap-token"): Request {
  return new Request("https://ops/device-sync/session", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
}

Deno.test("exchanges a bootstrap credential for a no-store short-lived session", async () => {
  const response = await routeDeviceSync(sessionRequest(), gateway());

  assertEquals(response.status, 201);
  assertEquals(response.headers.get("Cache-Control"), "no-store");
  assertEquals(await response.json(), {
    ok: true,
    accessToken: "access-token",
    expiresAt: "2026-07-31T04:15:00.000Z",
    tokenType: "Bearer",
  });
});

Deno.test("rejects invalid or unbound bootstrap credentials during session exchange", async () => {
  const invalid = await routeDeviceSync(sessionRequest("invalid"), gateway());
  const unbound = await routeDeviceSync(sessionRequest(), gateway({
    authenticateBootstrap: async () => ({
      ...identity,
      actorProfileId: "",
      bootstrapTokenId: "bootstrap-a",
    }),
  }));

  assertEquals(invalid.status, 401);
  assertEquals(unbound.status, 403);
});

Deno.test("does not accept a bootstrap credential as an event access token", async () => {
  const response = await routeDeviceSync(eventRequest("bootstrap-token"), gateway());
  assertEquals(response.status, 401);
});

Deno.test("rejects device events without a bearer token", async () => {
  const response = await routeDeviceSync(new Request("https://ops/device-sync/events", { method: "POST" }), gateway());
  assertEquals(response.status, 401);
});

Deno.test("rejects a device token without a bound operator", async () => {
  const response = await routeDeviceSync(eventRequest(), gateway({
    authenticateDevice: async () => ({ ...identity, actorProfileId: "" }),
  }));
  assertEquals(response.status, 403);
});

Deno.test("uses the authenticated device identity and reports an idempotent duplicate", async () => {
  let receivedOrganizationId = "";
  let receivedDeviceId = "";
  const response = await routeDeviceSync(eventRequest(), gateway({
    appendEvent: async (receivedIdentity) => {
      receivedOrganizationId = receivedIdentity.organizationId;
      receivedDeviceId = receivedIdentity.deviceId;
      return { duplicate: true };
    },
  }));

  assertEquals(response.status, 202);
  assertEquals(await response.json(), { ok: true, duplicate: true });
  assertEquals(receivedOrganizationId, "org-a");
  assertEquals(receivedDeviceId, "device-a");
});

Deno.test("accepts the deployed Supabase function path", async () => {
  const request = new Request("https://project.supabase.co/functions/v1/ops-glasses/device-sync/events", {
    method: "POST",
    headers: eventRequest().headers,
    body: await eventRequest().text(),
  });
  const response = await routeDeviceSync(request, gateway());
  assertEquals(response.status, 202);
});

Deno.test("accepts the deployed session exchange path", async () => {
  const request = new Request("https://project.supabase.co/functions/v1/ops-glasses/device-sync/session", {
    method: "POST",
    headers: sessionRequest().headers,
  });
  const response = await routeDeviceSync(request, gateway());
  assertEquals(response.status, 201);
});

Deno.test("hashes bootstrap credentials before database resolution", async () => {
  let rpcName = "";
  let rpcArgs: Record<string, unknown> = {};
  const supabase = {
    async rpc(name: string, args: Record<string, unknown>) {
      rpcName = name;
      rpcArgs = args;
      return {
        data: [{
          bootstrap_token_id: "bootstrap-a",
          device_id: "device-a",
          organization_id: "org-a",
          actor_profile_id: "profile-a",
        }],
        error: null,
      };
    },
  };

  const resolved = await (createDeviceSyncGateway(supabase) as SessionGateway)
    .authenticateBootstrap("bootstrap-token");

  assertEquals(rpcName, "resolve_glasses_bootstrap_credential");
  assertEquals(String(rpcArgs.candidate_token_hash).length, 64);
  assertEquals(rpcArgs.candidate_token_hash === "bootstrap-token", false);
  assertEquals(resolved, { ...identity, bootstrapTokenId: "bootstrap-a" });
});

Deno.test("issues a fifteen minute session without persisting its plaintext token", async () => {
  let rpcName = "";
  let rpcArgs: Record<string, unknown> = {};
  const supabase = {
    async rpc(name: string, args: Record<string, unknown>) {
      rpcName = name;
      rpcArgs = args;
      return { data: null, error: null };
    },
  };
  const before = Date.now();

  const session = await (createDeviceSyncGateway(supabase) as SessionGateway).issueSession({
    ...identity,
    bootstrapTokenId: "bootstrap-a",
  });

  const expiresInMs = Date.parse(session.expiresAt) - before;
  assertEquals(rpcName, "issue_glasses_device_session");
  assertEquals(rpcArgs.source_bootstrap_token_id, "bootstrap-a");
  assertEquals(String(rpcArgs.new_token_hash).length, 64);
  assertEquals(rpcArgs.new_token_hash === session.accessToken, false);
  assertEquals(session.accessToken.length > 32, true);
  assertEquals(expiresInMs >= 14 * 60 * 1000 && expiresInMs <= 15 * 60 * 1000 + 1000, true);
});

Deno.test("resolves only database-approved access sessions", async () => {
  const approvedSupabase = {
    async rpc() {
      return {
        data: [{ device_id: "device-a", organization_id: "org-a", actor_profile_id: "profile-a" }],
        error: null,
      };
    },
  };
  const rejectedSupabase = { async rpc() { return { data: [], error: null }; } };

  assertEquals(
    await createDeviceSyncGateway(approvedSupabase).authenticateDevice("access-token"),
    identity,
  );
  assertEquals(
    await createDeviceSyncGateway(rejectedSupabase).authenticateDevice("expired-revoked-or-disabled"),
    null,
  );
});

Deno.test("persists completed task events with completed task status", async () => {
  assertEquals(await persistedTaskStatus("task_completed"), "completed");
});

Deno.test("persists closed task events with closed task status", async () => {
  assertEquals(await persistedTaskStatus("task_closed"), "closed");
});

Deno.test("refuses to reactivate a closed authoritative project from a device event", async () => {
  const database = authorizationSupabase({ projectStatus: "closed" });
  const persistedGateway = createDeviceSyncGateway(database.client);
  const response = await routeDeviceSync(eventRequest(), gateway({
    appendEvent: persistedGateway.appendEvent,
  }));

  assertEquals(response.status, 409);
  assertEquals(await response.json(), { ok: false, error: "project_inactive" });
  assertEquals(database.projectUpserts, 0);
});

Deno.test("rejects device events after the project membership is revoked", async () => {
  const database = authorizationSupabase({ membershipStatus: "revoked" });
  const persistedGateway = createDeviceSyncGateway(database.client);
  const response = await routeDeviceSync(eventRequest(), gateway({
    appendEvent: persistedGateway.appendEvent,
  }));

  assertEquals(response.status, 403);
  assertEquals(await response.json(), { ok: false, error: "project_access_forbidden" });
});

Deno.test("rejects device events when the active binding targets another project", async () => {
  const database = authorizationSupabase({ bindingProjectId: "project-other" });
  const persistedGateway = createDeviceSyncGateway(database.client);
  const response = await routeDeviceSync(eventRequest(), gateway({
    appendEvent: persistedGateway.appendEvent,
  }));

  assertEquals(response.status, 403);
  assertEquals(await response.json(), { ok: false, error: "project_access_forbidden" });
});

Deno.test("creates a new project and engineer membership through one atomic registration RPC", async () => {
  const database = authorizationSupabase({ existingProject: false });
  const persistedGateway = createDeviceSyncGateway(database.client);
  const response = await routeDeviceSync(eventRequest("access-token", {
    eventType: "task_started",
    idempotencyKey: "event-start-a",
  }), gateway({ appendEvent: persistedGateway.appendEvent }));

  assertEquals(response.status, 202);
  assertEquals(database.projectRegistrationCalls, 1);
  assertEquals(database.projectUpserts, 0);
  assertEquals(database.membershipInserts, 0);
  assertEquals(database.registrationArgs, {
    target_organization_id: "org-a",
    target_actor_profile_id: "profile-a",
    target_device_id: "device-a",
    target_local_project_id: "project-local-a",
    target_project_title: "机房巡检",
  });
});

Deno.test("does not create a missing project from a non-start event", async () => {
  const database = authorizationSupabase({ existingProject: false });
  const persistedGateway = createDeviceSyncGateway(database.client);
  const response = await routeDeviceSync(
    eventRequest(),
    gateway({ appendEvent: persistedGateway.appendEvent }),
  );

  assertEquals(response.status, 409);
  assertEquals(await response.json(), { ok: false, error: "project_not_registered" });
  assertEquals(database.projectRegistrationCalls, 0);
  assertEquals(database.projectUpserts, 0);
  assertEquals(database.membershipInserts, 0);
});

Deno.test("does not resolve a same-name local project from another organization", async () => {
  const database = authorizationSupabase({ existingProject: false, includeForeignProject: true });
  const persistedGateway = createDeviceSyncGateway(database.client);
  const response = await routeDeviceSync(eventRequest("access-token", {
    eventType: "task_started",
    idempotencyKey: "event-cross-org-start",
  }), gateway({ appendEvent: persistedGateway.appendEvent }));

  assertEquals(response.status, 202);
  assertEquals(database.projectRegistrationCalls, 1);
  assertEquals(database.registrationArgs.target_organization_id, "org-a");
});

Deno.test("does not bootstrap a new project from a project-scoped device binding", async () => {
  const database = authorizationSupabase({
    existingProject: false,
    bindingProjectId: "project-other",
  });
  const persistedGateway = createDeviceSyncGateway(database.client);
  const response = await routeDeviceSync(eventRequest("access-token", {
    eventType: "task_started",
    idempotencyKey: "event-project-bound-start",
  }), gateway({ appendEvent: persistedGateway.appendEvent }));

  assertEquals(response.status, 403);
  assertEquals(await response.json(), { ok: false, error: "project_access_forbidden" });
  assertEquals(database.projectRegistrationCalls, 0);
  assertEquals(database.projectUpserts, 0);
});

async function persistedTaskStatus(eventType: string): Promise<string> {
  const database = authorizationSupabase();

  await createDeviceSyncGateway(database.client).appendEvent(identity, {
    localProjectId: "project-local-a",
    projectTitle: "机房巡检",
    localTaskId: "task-local-a",
    taskTitle: "温湿度异常",
    eventType,
    payload: {},
    occurredAt: "2026-07-31T04:00:00.000Z",
    idempotencyKey: `event-${eventType}`,
  });
  return database.taskStatus;
}

function authorizationSupabase(options: {
  projectStatus?: "active" | "closed" | "archived";
  membershipStatus?: "active" | "revoked";
  bindingProjectId?: string | null;
  existingProject?: boolean;
  includeForeignProject?: boolean;
} = {}) {
  let projectStatus = options.projectStatus ?? "active";
  let projectUpserts = 0;
  let membershipInserts = 0;
  let projectRegistrationCalls = 0;
  let registrationArgs: Record<string, unknown> = {};
  let persistedTaskStatus = "";
  const rows: Record<string, Array<Record<string, unknown>>> = {
    ops_projects: [
      ...(options.existingProject === false ? [] : [{
        id: "project-a",
        organization_id: "org-a",
        local_project_id: "project-local-a",
        status: projectStatus,
      }]),
      ...(options.includeForeignProject ? [{
        id: "project-foreign",
        organization_id: "org-b",
        local_project_id: "project-local-a",
        status: "active",
      }] : []),
    ],
    ops_project_memberships: options.membershipStatus === "revoked" || options.existingProject === false
      ? []
      : [{
        id: "membership-a",
        organization_id: "org-a",
        project_id: "project-a",
        profile_id: "profile-a",
        status: "active",
      }],
    device_bindings: [{
      id: "binding-a",
      organization_id: "org-a",
      device_id: "device-a",
      profile_id: "profile-a",
      project_id: options.bindingProjectId ?? null,
      status: "active",
    }],
    task_events: [],
  };

  const client = {
    async rpc(name: string, args: Record<string, unknown>) {
      if (name !== "register_device_project_from_task_start") {
        return { data: null, error: { message: `unexpected rpc ${name}` } };
      }
      projectRegistrationCalls += 1;
      registrationArgs = args;
      rows.ops_projects.push({
        id: "project-a",
        organization_id: "org-a",
        local_project_id: "project-local-a",
        status: "active",
      });
      rows.ops_project_memberships.push({
        id: "membership-a",
        organization_id: "org-a",
        project_id: "project-a",
        profile_id: "profile-a",
        status: "active",
      });
      return {
        data: [{ project_id: "project-a", project_status: "active" }],
        error: null,
      };
    },
    from(table: string) {
      const filters = new Map<string, unknown>();
      let mutation: { kind: "insert" | "upsert"; value: Record<string, unknown> } | null = null;
      const builder: any = {
        select() {
          return builder;
        },
        eq(field: string, value: unknown) {
          filters.set(field, value);
          return builder;
        },
        upsert(value: Record<string, unknown>) {
          mutation = { kind: "upsert", value };
          if (table === "ops_projects") {
            projectUpserts += 1;
            if (typeof value.status === "string") projectStatus = value.status as typeof projectStatus;
          }
          if (table === "maintenance_tasks") {
            persistedTaskStatus = String(value.status ?? "");
          }
          return builder;
        },
        insert(value: Record<string, unknown>) {
          mutation = { kind: "insert", value };
          if (table === "ops_project_memberships") membershipInserts += 1;
          return builder;
        },
        async maybeSingle() {
          const result = execute();
          return { data: result.data[0] ?? null, error: result.error };
        },
        async single() {
          const result = execute();
          return { data: result.data[0] ?? null, error: result.error };
        },
        then(resolve: (value: unknown) => unknown, reject: (reason: unknown) => unknown) {
          return Promise.resolve(execute()).then(resolve, reject);
        },
      };

      function execute(): { data: Array<Record<string, unknown>>; error: null } {
        if (mutation) {
          if (table === "ops_projects") {
            return { data: [{ id: "project-a", status: projectStatus }], error: null };
          }
          if (table === "maintenance_tasks") return { data: [{ id: "task-a" }], error: null };
          return { data: [], error: null };
        }
        const filtered = (rows[table] ?? []).filter((row) =>
          Array.from(filters.entries()).every(([field, value]) => row[field] === value)
        );
        return { data: filtered, error: null };
      }

      return builder;
    },
  };

  return {
    client,
    get projectUpserts() {
      return projectUpserts;
    },
    get taskStatus() {
      return persistedTaskStatus;
    },
    get membershipInserts() {
      return membershipInserts;
    },
    get projectRegistrationCalls() {
      return projectRegistrationCalls;
    },
    get registrationArgs() {
      return registrationArgs;
    },
  };
}
