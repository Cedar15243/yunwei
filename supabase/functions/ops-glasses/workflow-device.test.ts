import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  routeWorkflowDevice,
  WorkflowDeviceError,
  type WorkflowDeviceGateway,
} from "./workflow-device.ts";

const identity = {
  deviceId: "device-a",
  organizationId: "org-a",
  actorProfileId: "profile-a",
};

function gateway(
  overrides: Partial<WorkflowDeviceGateway> = {},
): WorkflowDeviceGateway {
  return {
    authenticateDevice: async (token) =>
      token === "access-token" ? identity : null,
    listAssignments: async () => [{
      id: "assignment-a",
      work_order_id: "order-a",
      project_id: "project-a",
      workflow_version_id: "version-a",
      mode: "required",
      status: "queued",
      delivery_sequence: 7,
      assigned_at: "2026-08-01T01:00:00.000Z",
      execution_package: { forbidden: true },
      connector_token: "must-not-leak",
    }],
    claimAssignment: async () => ({
      id: "assignment-a",
      workflow_version_id: "version-a",
      mode: "required",
      status: "queued",
    }),
    getWorkflowPackage: async () => workflowPackage(),
    reportAssignmentStatus: async (_identity, assignmentId, command) => ({
      id: assignmentId,
      status: command.status,
      organization_id: "must-not-leak",
    }),
    ...overrides,
  };
}

function request(
  method: string,
  path: string,
  options: {
    token?: string;
    body?: Record<string, unknown>;
    packageHeaders?: boolean;
  } = {},
): Request {
  const headers = new Headers();
  if (options.token !== undefined) {
    headers.set("Authorization", `Bearer ${options.token}`);
  }
  if (options.body) headers.set("Content-Type", "application/json");
  if (options.packageHeaders) {
    headers.set("X-App-Version-Code", "9002");
    headers.set("X-Workflow-Schema-Version", "1");
    headers.set(
      "X-Workflow-Capabilities",
      "workflow.runtime.v1,camera.photo",
    );
  }
  return new Request(`https://ops.example${path}`, {
    method,
    headers,
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
}

function workflowPackage(): Record<string, unknown> {
  return {
    assignment_id: "assignment-a",
    workflow_version_id: "version-a",
    schema_version: 1,
    execution_package: {
      workflowId: "workflow-a",
      schemaVersion: 1,
      nodes: [],
      transitions: [],
      contentSha256: "a".repeat(64),
    },
    content_sha256: "a".repeat(64),
    package_signature: "signed-package",
    signature_key_id: "workflow-key-a",
    required_capabilities: ["workflow.runtime.v1", "camera.photo"],
    min_app_version_code: 9000,
    database_secret: "must-not-leak",
  };
}

Deno.test("rejects missing bootstrap and expired workflow device credentials", async () => {
  const missing = await routeWorkflowDevice(
    request("GET", "/device-sync/workflows/assignments"),
    gateway(),
  );
  const bootstrap = await routeWorkflowDevice(
    request("GET", "/device-sync/workflows/assignments", {
      token: "bootstrap-token",
    }),
    gateway(),
  );
  const expired = await routeWorkflowDevice(
    request("GET", "/device-sync/workflows/assignments", {
      token: "expired-token",
    }),
    gateway(),
  );
  const unbound = await routeWorkflowDevice(
    request("GET", "/device-sync/workflows/assignments", {
      token: "access-token",
    }),
    gateway({
      authenticateDevice: async () => ({ ...identity, actorProfileId: "" }),
    }),
  );

  assertEquals(missing.status, 401);
  assertEquals(bootstrap.status, 401);
  assertEquals(expired.status, 401);
  assertEquals(unbound.status, 403);
});

Deno.test("validates assignment cursors and returns metadata only", async () => {
  let received: Record<string, unknown> | null = null;
  const invalid = await routeWorkflowDevice(
    request("GET", "/device-sync/workflows/assignments?afterSequence=-1", {
      token: "access-token",
    }),
    gateway(),
  );
  const response = await routeWorkflowDevice(
    request(
      "GET",
      "/functions/v1/ops-glasses/device-sync/workflows/assignments?afterSequence=4&limit=500",
      { token: "access-token" },
    ),
    gateway({
      listAssignments: async (receivedIdentity, afterSequence, limit) => {
        received = { receivedIdentity, afterSequence, limit };
        return (await gateway().listAssignments(identity, 0, 1));
      },
    }),
  );

  assertEquals(invalid.status, 400);
  assertEquals(response.status, 200);
  assertEquals(received, {
    receivedIdentity: identity,
    afterSequence: 4,
    limit: 100,
  });
  assertEquals(await response.json(), {
    items: [{
      assignmentId: "assignment-a",
      workOrderId: "order-a",
      projectId: "project-a",
      workflowVersionId: "version-a",
      mode: "required",
      status: "queued",
      deliverySequence: 7,
      assignedAt: "2026-08-01T01:00:00.000Z",
    }],
    nextSequence: 7,
  });
});

Deno.test("claims an owned assignment and returns only its signed execution package", async () => {
  const calls: string[] = [];
  const response = await routeWorkflowDevice(
    request(
      "GET",
      "/device-sync/workflows/assignments/assignment-a/package",
      { token: "access-token", packageHeaders: true },
    ),
    gateway({
      claimAssignment: async (
        receivedIdentity,
        assignmentId,
        idempotencyKey,
      ) => {
        calls.push(
          `claim:${receivedIdentity.deviceId}:${assignmentId}:${idempotencyKey}`,
        );
        return {
          id: assignmentId,
          workflow_version_id: "version-a",
          mode: "required",
          status: "queued",
        };
      },
      getWorkflowPackage: async (_identity, assignment) => {
        calls.push(`package:${assignment.id}`);
        return workflowPackage();
      },
    }),
  );

  assertEquals(response.status, 200);
  assertEquals(calls, [
    "claim:device-a:assignment-a:package:assignment-a:device-a",
    "package:assignment-a",
  ]);
  assertEquals(await response.json(), {
    assignmentId: "assignment-a",
    workflowVersionId: "version-a",
    schemaVersion: 1,
    executionPackage: {
      workflowId: "workflow-a",
      schemaVersion: 1,
      nodes: [],
      transitions: [],
      contentSha256: "a".repeat(64),
    },
    contentSha256: "a".repeat(64),
    packageSignature: "signed-package",
    signatureKeyId: "workflow-key-a",
    requiredCapabilities: ["workflow.runtime.v1", "camera.photo"],
    minAppVersionCode: 9000,
  });
});

Deno.test("rejects unsupported workflow schema capabilities and app versions", async () => {
  const unsupportedSchema = await routeWorkflowDevice(
    request(
      "GET",
      "/device-sync/workflows/assignments/assignment-a/package",
      { token: "access-token", packageHeaders: true },
    ),
    gateway({
      getWorkflowPackage: async () => ({
        ...workflowPackage(),
        schema_version: 2,
        execution_package: {
          ...(workflowPackage().execution_package as Record<string, unknown>),
          schemaVersion: 2,
        },
      }),
    }),
  );
  const missingCapability = await routeWorkflowDevice(
    new Request(
      "https://ops.example/device-sync/workflows/assignments/assignment-a/package",
      {
        headers: {
          Authorization: "Bearer access-token",
          "X-App-Version-Code": "9002",
          "X-Workflow-Schema-Version": "1",
          "X-Workflow-Capabilities": "workflow.runtime.v1",
        },
      },
    ),
    gateway(),
  );
  const oldApp = await routeWorkflowDevice(
    request(
      "GET",
      "/device-sync/workflows/assignments/assignment-a/package",
      { token: "access-token", packageHeaders: true },
    ),
    gateway({
      getWorkflowPackage: async () => ({
        ...workflowPackage(),
        min_app_version_code: 9003,
      }),
    }),
  );

  assertEquals(unsupportedSchema.status, 409);
  assertEquals(missingCapability.status, 409);
  assertEquals(oldApp.status, 409);
  assertEquals(
    (await missingCapability.json()).error,
    "workflow_incompatible",
  );
});

Deno.test("rejects a package whose signed metadata disagrees with its payload", async () => {
  const response = await routeWorkflowDevice(
    request(
      "GET",
      "/device-sync/workflows/assignments/assignment-a/package",
      { token: "access-token", packageHeaders: true },
    ),
    gateway({
      getWorkflowPackage: async () => ({
        ...workflowPackage(),
        content_sha256: "b".repeat(64),
      }),
    }),
  );

  assertEquals(response.status, 502);
  assertEquals((await response.json()).error, "workflow_package_invalid");
});

Deno.test("requires assignment ownership and rejects workflow-free package retrieval", async () => {
  const notOwned = await routeWorkflowDevice(
    request(
      "GET",
      "/device-sync/workflows/assignments/other-assignment/package",
      { token: "access-token", packageHeaders: true },
    ),
    gateway({ claimAssignment: async () => null }),
  );
  const noPackage = await routeWorkflowDevice(
    request(
      "GET",
      "/device-sync/workflows/assignments/assignment-none/package",
      { token: "access-token", packageHeaders: true },
    ),
    gateway({
      claimAssignment: async () => ({
        id: "assignment-none",
        workflow_version_id: null,
        mode: "none",
        status: "ready",
      }),
    }),
  );

  assertEquals(notOwned.status, 404);
  assertEquals(noPackage.status, 409);
  assertEquals((await noPackage.json()).error, "workflow_not_required");
});

Deno.test("reports forward delivery state from authenticated identity only", async () => {
  let received: Record<string, unknown> | null = null;
  const response = await routeWorkflowDevice(
    request(
      "POST",
      "/device-sync/workflows/assignments/assignment-a/status",
      {
        token: "access-token",
        body: {
          status: "delivered",
          idempotencyKey: "delivery-a",
          organizationId: "forged-org",
          profileId: "forged-profile",
          deviceId: "forged-device",
        },
      },
    ),
    gateway({
      reportAssignmentStatus: async (
        receivedIdentity,
        assignmentId,
        command,
      ) => {
        received = { receivedIdentity, assignmentId, command };
        return { id: assignmentId, status: command.status };
      },
    }),
  );

  assertEquals(response.status, 200);
  assertEquals(received, {
    receivedIdentity: identity,
    assignmentId: "assignment-a",
    command: {
      status: "delivered",
      idempotencyKey: "delivery-a",
      failureStage: null,
      failureReason: null,
    },
  });
  assertEquals(await response.json(), {
    assignmentId: "assignment-a",
    status: "delivered",
  });
});

Deno.test("rejects direct revocation malformed reports and backward transitions", async () => {
  const revoked = await routeWorkflowDevice(
    request(
      "POST",
      "/device-sync/workflows/assignments/assignment-a/status",
      {
        token: "access-token",
        body: { status: "revoked", idempotencyKey: "revoked-a" },
      },
    ),
    gateway(),
  );
  const failedWithoutReason = await routeWorkflowDevice(
    request(
      "POST",
      "/device-sync/workflows/assignments/assignment-a/status",
      {
        token: "access-token",
        body: { status: "failed", idempotencyKey: "failed-a" },
      },
    ),
    gateway(),
  );
  const backward = await routeWorkflowDevice(
    request(
      "POST",
      "/device-sync/workflows/assignments/assignment-a/status",
      {
        token: "access-token",
        body: { status: "delivered", idempotencyKey: "backward-a" },
      },
    ),
    gateway({
      reportAssignmentStatus: async () => {
        throw new WorkflowDeviceError(
          409,
          "workflow_transition_invalid",
        );
      },
    }),
  );

  assertEquals(revoked.status, 400);
  assertEquals(failedWithoutReason.status, 400);
  assertEquals(backward.status, 409);
  assertEquals(
    (await backward.json()).error,
    "workflow_transition_invalid",
  );
});
