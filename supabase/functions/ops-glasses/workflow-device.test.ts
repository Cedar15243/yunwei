import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  createWorkflowDeviceGateway,
  routeWorkflowDevice,
  WorkflowDeviceError,
  type WorkflowDeviceGateway,
} from "./workflow-device.ts";

const identity = {
  deviceId: "device-a",
  organizationId: "org-a",
  actorProfileId: "profile-a",
};
type ExecutionGateway = WorkflowDeviceGateway;

function gateway(
  overrides: Partial<ExecutionGateway> = {},
): ExecutionGateway {
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
      work_orders: {
        external_work_order_id: "MVS-20260801-001",
        title: "冷水机组控制器故障",
        description: "控制器报警，现场需要采集铭牌和配置页面。",
        customer_id: "customer-a",
        work_order_type: "repair",
        asset_id: "asset-a",
        asset_category: "hvac",
        asset_brand: "Huafang",
        asset_model: "HF-CH-01",
        priority: "high",
        risk_level: "medium",
        status: "received",
        due_at: "2026-08-02T01:00:00.000Z",
        received_at: "2026-08-01T00:30:00.000Z",
        binding_evidence: { mustNotLeak: true },
      },
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
    startExecution: async () => ({
      id: "execution-a",
      assignment_id: "assignment-a",
      task_id: "task-a",
      status: "active",
      current_node_id: "start-a",
      started_at: "2026-08-01T02:00:00.000Z",
      organization_id: "must-not-leak",
      operator_profile_id: "must-not-leak",
      device_id: "must-not-leak",
    }),
    appendStep: async (_identity, executionId, command) => ({
      id: "step-a",
      execution_id: executionId,
      node_id: command.nodeId,
      attempt_number: command.attemptNumber,
      status: command.status,
      evidence_asset_ids: command.evidenceAssetIds,
      updated_at: "2026-08-01T02:01:00.000Z",
      organization_id: "must-not-leak",
      operator_profile_id: "must-not-leak",
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
      workOrder: {
        externalWorkOrderId: "MVS-20260801-001",
        title: "冷水机组控制器故障",
        description: "控制器报警，现场需要采集铭牌和配置页面。",
        customerId: "customer-a",
        workOrderType: "repair",
        assetId: "asset-a",
        assetCategory: "hvac",
        assetBrand: "Huafang",
        assetModel: "HF-CH-01",
        priority: "high",
        riskLevel: "medium",
        status: "received",
        dueAt: "2026-08-02T01:00:00.000Z",
        receivedAt: "2026-08-01T00:30:00.000Z",
      },
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

Deno.test("starts an owned workflow execution from authenticated identity only", async () => {
  const executionId = "11111111-1111-4111-8111-111111111111";
  let received: Record<string, unknown> | null = null;
  const response = await routeWorkflowDevice(
    request("POST", "/device-sync/workflows/executions", {
      token: "access-token",
      body: {
        executionId,
        assignmentId: "assignment-a",
        projectId: "project-a",
        localTaskId: "workflow-task-a",
        initialNodeId: "start-a",
        runtimeSnapshot: { currentNodeId: "start-a", localRevision: 1 },
        idempotencyKey: "start-execution-a",
        organizationId: "forged-org",
        operatorProfileId: "forged-profile",
        deviceId: "forged-device",
      },
    }),
    gateway({
      startExecution: async (receivedIdentity, command) => {
        received = { receivedIdentity, command };
        const started = await gateway().startExecution(
          receivedIdentity,
          command,
        );
        return { ...started, id: executionId };
      },
    }),
  );

  assertEquals(response.status, 201);
  assertEquals(received, {
    receivedIdentity: identity,
    command: {
      executionId,
      assignmentId: "assignment-a",
      projectId: "project-a",
      localTaskId: "workflow-task-a",
      initialNodeId: "start-a",
      runtimeSnapshot: { currentNodeId: "start-a", localRevision: 1 },
      idempotencyKey: "start-execution-a",
    },
  });
  assertEquals(await response.json(), {
    executionId,
    assignmentId: "assignment-a",
    taskId: "task-a",
    status: "active",
    currentNodeId: "start-a",
    startedAt: "2026-08-01T02:00:00.000Z",
  });
});

Deno.test("rejects malformed workflow execution start commands", async () => {
  const missingTask = await routeWorkflowDevice(
    request("POST", "/device-sync/workflows/executions", {
      token: "access-token",
      body: {
        assignmentId: "assignment-a",
        projectId: "project-a",
        initialNodeId: "start-a",
        runtimeSnapshot: {},
        idempotencyKey: "start-execution-a",
      },
    }),
    gateway(),
  );
  const invalidSnapshot = await routeWorkflowDevice(
    request("POST", "/device-sync/workflows/executions", {
      token: "access-token",
      body: {
        assignmentId: "assignment-a",
        projectId: "project-a",
        localTaskId: "workflow-task-a",
        initialNodeId: "start-a",
        runtimeSnapshot: ["not-an-object"],
        idempotencyKey: "start-execution-a",
      } as unknown as Record<string, unknown>,
    }),
    gateway(),
  );
  const invalidExecutionId = await routeWorkflowDevice(
    request("POST", "/device-sync/workflows/executions", {
      token: "access-token",
      body: {
        executionId: "not-a-uuid",
        assignmentId: "assignment-a",
        projectId: "project-a",
        localTaskId: "workflow-task-a",
        initialNodeId: "start-a",
        runtimeSnapshot: {},
        idempotencyKey: "start-execution-a",
      },
    }),
    gateway(),
  );

  assertEquals(missingTask.status, 400);
  assertEquals(invalidSnapshot.status, 400);
  assertEquals(invalidExecutionId.status, 400);
  assertEquals(
    (await missingTask.json()).error,
    "invalid_execution_start",
  );
});

Deno.test("appends an audited workflow step from authenticated identity only", async () => {
  const evidenceId = "11111111-1111-4111-8111-111111111111";
  let received: Record<string, unknown> | null = null;
  const response = await routeWorkflowDevice(
    request(
      "POST",
      "/device-sync/workflows/executions/execution-a/steps",
      {
        token: "access-token",
        body: {
          nodeId: "photo-a",
          attemptNumber: 1,
          status: "completed",
          idempotencyKey: "step-photo-a-completed",
          inputData: { requiredCount: 1 },
          outputData: { capturedCount: 1 },
          evidenceAssetIds: [evidenceId],
          transitionResult: { matchedTransitionId: "photo-to-form" },
          failureCode: null,
          failureReason: null,
          nextNodeId: "form-a",
          runtimeSnapshot: { currentNodeId: "form-a", localRevision: 2 },
          organizationId: "forged-org",
          operatorProfileId: "forged-profile",
          deviceId: "forged-device",
        },
      },
    ),
    gateway({
      appendStep: async (receivedIdentity, executionId, command) => {
        received = { receivedIdentity, executionId, command };
        return await gateway().appendStep(
          receivedIdentity,
          executionId,
          command,
        );
      },
    }),
  );

  assertEquals(response.status, 200);
  assertEquals(received, {
    receivedIdentity: identity,
    executionId: "execution-a",
    command: {
      nodeId: "photo-a",
      attemptNumber: 1,
      status: "completed",
      idempotencyKey: "step-photo-a-completed",
      inputData: { requiredCount: 1 },
      outputData: { capturedCount: 1 },
      evidenceAssetIds: [evidenceId],
      transitionResult: { matchedTransitionId: "photo-to-form" },
      failureCode: null,
      failureReason: null,
      nextNodeId: "form-a",
      runtimeSnapshot: { currentNodeId: "form-a", localRevision: 2 },
    },
  });
  assertEquals(await response.json(), {
    stepExecutionId: "step-a",
    executionId: "execution-a",
    nodeId: "photo-a",
    attemptNumber: 1,
    status: "completed",
    evidenceAssetIds: [evidenceId],
    updatedAt: "2026-08-01T02:01:00.000Z",
  });
});

Deno.test("rejects invalid workflow step status payloads and evidence identifiers", async () => {
  const baseBody = {
    nodeId: "photo-a",
    attemptNumber: 1,
    status: "completed",
    idempotencyKey: "step-photo-a-completed",
    inputData: {},
    outputData: {},
    evidenceAssetIds: ["11111111-1111-4111-8111-111111111111"],
    transitionResult: {},
    runtimeSnapshot: {},
    nextNodeId: "form-a",
  };
  const invalidStatus = await routeWorkflowDevice(
    request("POST", "/device-sync/workflows/executions/execution-a/steps", {
      token: "access-token",
      body: { ...baseBody, status: "cancelled" },
    }),
    gateway(),
  );
  const invalidPayload = await routeWorkflowDevice(
    request("POST", "/device-sync/workflows/executions/execution-a/steps", {
      token: "access-token",
      body: {
        ...baseBody,
        inputData: ["not-an-object"],
      } as unknown as Record<string, unknown>,
    }),
    gateway(),
  );
  const invalidEvidence = await routeWorkflowDevice(
    request("POST", "/device-sync/workflows/executions/execution-a/steps", {
      token: "access-token",
      body: { ...baseBody, evidenceAssetIds: ["not-a-uuid"] },
    }),
    gateway(),
  );

  assertEquals(invalidStatus.status, 400);
  assertEquals(invalidPayload.status, 400);
  assertEquals(invalidEvidence.status, 400);
  assertEquals(
    (await invalidEvidence.json()).error,
    "invalid_step_report",
  );
});

Deno.test("does not append steps to completed or cancelled executions", async () => {
  const actualGateway = createWorkflowDeviceGateway({
    rpc: (name: string) => {
      if (name === "resolve_glasses_device_session") {
        return Promise.resolve({
          data: [{
            device_id: "device-a",
            organization_id: "org-a",
            actor_profile_id: "profile-a",
          }],
          error: null,
        });
      }
      if (name === "append_workflow_step_execution") {
        return Promise.resolve({
          data: null,
          error: {
            message:
              "completed or cancelled workflow execution cannot accept steps",
          },
        });
      }
      return Promise.resolve({
        data: null,
        error: { message: "unexpected rpc" },
      });
    },
  });
  const response = await routeWorkflowDevice(
    request("POST", "/device-sync/workflows/executions/execution-a/steps", {
      token: "access-token",
      body: {
        nodeId: "photo-a",
        attemptNumber: 1,
        status: "active",
        idempotencyKey: "step-after-completion",
        inputData: {},
        outputData: {},
        evidenceAssetIds: [],
        transitionResult: {},
        runtimeSnapshot: {},
      },
    }),
    actualGateway,
  );

  assertEquals(response.status, 409);
  assertEquals(
    (await response.json()).error,
    "workflow_execution_terminal",
  );
});

Deno.test("maps workflow execution commands to server-only transaction RPCs", async () => {
  const calls: Array<Record<string, unknown>> = [];
  const supabase = {
    rpc: async (name: string, args: Record<string, unknown>) => {
      calls.push({ name, args });
      if (name === "start_workflow_execution") {
        return { data: [{ id: "execution-a", status: "active" }], error: null };
      }
      if (name === "append_workflow_step_execution") {
        return { data: [{ id: "step-a", status: "completed" }], error: null };
      }
      return { data: null, error: { message: "unexpected rpc" } };
    },
  };
  const actual = createWorkflowDeviceGateway(
    supabase,
  ) as unknown as ExecutionGateway;
  const evidenceId = "11111111-1111-4111-8111-111111111111";

  await actual.startExecution(identity, {
    executionId: evidenceId,
    assignmentId: "assignment-a",
    projectId: "project-a",
    localTaskId: "workflow-task-a",
    initialNodeId: "start-a",
    runtimeSnapshot: { currentNodeId: "start-a" },
    idempotencyKey: "start-execution-a",
  });
  await actual.appendStep(identity, "execution-a", {
    nodeId: "photo-a",
    attemptNumber: 1,
    status: "completed",
    idempotencyKey: "step-photo-a-completed",
    inputData: { requiredCount: 1 },
    outputData: { capturedCount: 1 },
    evidenceAssetIds: [evidenceId],
    transitionResult: { matchedTransitionId: "photo-to-form" },
    failureCode: null,
    failureReason: null,
    nextNodeId: "form-a",
    runtimeSnapshot: { currentNodeId: "form-a" },
  });

  assertEquals(calls, [{
    name: "start_workflow_execution",
    args: {
      requested_execution_id: evidenceId,
      target_assignment_id: "assignment-a",
      target_project_id: "project-a",
      target_local_task_id: "workflow-task-a",
      initial_node_id: "start-a",
      execution_snapshot: { currentNodeId: "start-a" },
      start_idempotency_key: "start-execution-a",
      target_profile_id: "profile-a",
      target_device_id: "device-a",
    },
  }, {
    name: "append_workflow_step_execution",
    args: {
      target_execution_id: "execution-a",
      step_node_id: "photo-a",
      step_attempt_number: 1,
      new_step_status: "completed",
      step_idempotency_key: "step-photo-a-completed",
      step_input_data: { requiredCount: 1 },
      step_output_data: { capturedCount: 1 },
      step_evidence_asset_ids: [evidenceId],
      step_transition_result: { matchedTransitionId: "photo-to-form" },
      step_failure_code: null,
      step_failure_reason: null,
      next_node_id: "form-a",
      execution_runtime_snapshot: { currentNodeId: "form-a" },
      target_profile_id: "profile-a",
      target_device_id: "device-a",
    },
  }]);
});
