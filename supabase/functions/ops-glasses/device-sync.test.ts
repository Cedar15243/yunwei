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

function eventRequest(token = "access-token"): Request {
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

async function persistedTaskStatus(eventType: string): Promise<string> {
  let taskStatus = "";
  const emptySelection = {
    eq() {
      return this;
    },
    maybeSingle: async () => ({ data: null, error: null }),
  };
  const supabase = {
    from(table: string) {
      if (table === "ops_projects") {
        return {
          upsert: () => ({
            select: () => ({ single: async () => ({ data: { id: "project-a" }, error: null }) }),
          }),
        };
      }
      if (table === "maintenance_tasks") {
        return {
          upsert: (record: Record<string, unknown>) => {
            taskStatus = String(record.status ?? "");
            return {
              select: () => ({ single: async () => ({ data: { id: "task-a" }, error: null }) }),
            };
          },
        };
      }
      if (table === "task_events") {
        return {
          select: () => emptySelection,
          insert: async () => ({ error: null }),
        };
      }
      throw new Error(`Unexpected table ${table}`);
    },
  };

  await createDeviceSyncGateway(supabase).appendEvent(identity, {
    localProjectId: "project-local-a",
    projectTitle: "机房巡检",
    localTaskId: "task-local-a",
    taskTitle: "温湿度异常",
    eventType,
    payload: {},
    occurredAt: "2026-07-31T04:00:00.000Z",
    idempotencyKey: `event-${eventType}`,
  });
  return taskStatus;
}
