import {
  createDeviceSyncGateway,
  type DeviceSyncIdentity,
} from "./device-sync.ts";

export type WorkflowAssignmentStatusCommand = {
  status:
    | "delivered"
    | "verified"
    | "ready"
    | "active"
    | "completed"
    | "failed";
  idempotencyKey: string;
  failureStage: string | null;
  failureReason: string | null;
};

export type WorkflowExecutionStartCommand = {
  assignmentId: string;
  projectId: string;
  taskId: string;
  initialNodeId: string;
  runtimeSnapshot: Record<string, unknown>;
  idempotencyKey: string;
};

export type WorkflowStepExecutionCommand = {
  nodeId: string;
  attemptNumber: number;
  status:
    | "pending"
    | "active"
    | "draft_saved"
    | "waiting_upload"
    | "waiting_server"
    | "completed"
    | "skipped"
    | "failed";
  idempotencyKey: string;
  inputData: Record<string, unknown>;
  outputData: Record<string, unknown>;
  evidenceAssetIds: string[];
  transitionResult: Record<string, unknown>;
  failureCode: string | null;
  failureReason: string | null;
  nextNodeId: string | null;
  runtimeSnapshot: Record<string, unknown>;
};

export type WorkflowDeviceGateway = {
  authenticateDevice(token: string): Promise<DeviceSyncIdentity | null>;
  listAssignments(
    identity: DeviceSyncIdentity,
    afterSequence: number,
    limit: number,
  ): Promise<Array<Record<string, unknown>>>;
  claimAssignment(
    identity: DeviceSyncIdentity,
    assignmentId: string,
    idempotencyKey: string,
  ): Promise<Record<string, unknown> | null>;
  getWorkflowPackage(
    identity: DeviceSyncIdentity,
    assignment: Record<string, unknown>,
  ): Promise<Record<string, unknown> | null>;
  reportAssignmentStatus(
    identity: DeviceSyncIdentity,
    assignmentId: string,
    command: WorkflowAssignmentStatusCommand,
  ): Promise<Record<string, unknown> | null>;
  startExecution(
    identity: DeviceSyncIdentity,
    command: WorkflowExecutionStartCommand,
  ): Promise<Record<string, unknown> | null>;
  appendStep(
    identity: DeviceSyncIdentity,
    executionId: string,
    command: WorkflowStepExecutionCommand,
  ): Promise<Record<string, unknown> | null>;
};

type DeviceCapabilities = {
  appVersionCode: number;
  workflowSchemaVersion: number;
  capabilities: Set<string>;
};

type DeviceWorkflowPackage = {
  assignmentId: string;
  workflowVersionId: string;
  schemaVersion: number;
  executionPackage: Record<string, unknown>;
  contentSha256: string;
  packageSignature: string;
  signatureKeyId: string;
  requiredCapabilities: string[];
  minAppVersionCode: number;
};

const reportableStatuses = new Set([
  "delivered",
  "verified",
  "ready",
  "active",
  "completed",
  "failed",
]);

const workflowStepStatuses = new Set([
  "pending",
  "active",
  "draft_saved",
  "waiting_upload",
  "waiting_server",
  "completed",
  "skipped",
  "failed",
]);

const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, content-type, x-app-version-code, x-workflow-schema-version, x-workflow-capabilities",
  "Content-Type": "application/json; charset=utf-8",
};

export class WorkflowDeviceError extends Error {
  constructor(
    public readonly status: 400 | 404 | 409 | 502,
    public readonly code: string,
  ) {
    super(code);
  }
}

export async function routeWorkflowDevice(
  request: Request,
  gateway: WorkflowDeviceGateway,
): Promise<Response> {
  const path = routePath(request);
  const token = bearerToken(request);
  if (!token) return response({ ok: false, error: "unauthorized" }, 401);
  const identity = await gateway.authenticateDevice(token);
  if (!identity) return response({ ok: false, error: "unauthorized" }, 401);
  if (!identity.actorProfileId) {
    return response({ ok: false, error: "device_not_bound" }, 403);
  }

  try {
    if (
      request.method === "GET" && path === "/device-sync/workflows/assignments"
    ) {
      const url = new URL(request.url);
      const afterSequence = integerQuery(
        url.searchParams.get("afterSequence"),
        0,
      );
      const requestedLimit = integerQuery(url.searchParams.get("limit"), 50);
      if (
        afterSequence === null || requestedLimit === null || requestedLimit < 1
      ) {
        return response({ ok: false, error: "invalid_cursor" }, 400);
      }
      const limit = Math.min(requestedLimit, 100);
      const records = await gateway.listAssignments(
        identity,
        afterSequence,
        limit,
      );
      const items = records.map(assignmentMetadata).filter(isRecord);
      const nextSequence = items.reduce(
        (highest, item) => Math.max(highest, Number(item.deliverySequence)),
        afterSequence,
      );
      return response({ items, nextSequence });
    }

    const packageRoute = path.match(
      /^\/device-sync\/workflows\/assignments\/([^/]+)\/package$/,
    );
    if (request.method === "GET" && packageRoute) {
      const deviceCapabilities = capabilityDeclaration(request);
      if (!deviceCapabilities) {
        return response(
          { ok: false, error: "device_capabilities_required" },
          400,
        );
      }
      const assignment = await gateway.claimAssignment(
        identity,
        packageRoute[1],
        `package:${packageRoute[1]}:${identity.deviceId}`,
      );
      if (!assignment) return response({ ok: false, error: "not_found" }, 404);
      if (
        assignment.mode === "none" ||
        !requiredText(assignment.workflow_version_id)
      ) {
        return response({ ok: false, error: "workflow_not_required" }, 409);
      }
      const record = await gateway.getWorkflowPackage(identity, assignment);
      if (!record) {
        return response(
          { ok: false, error: "workflow_package_unavailable" },
          404,
        );
      }
      const workflowPackage = packageResponse(record);
      if (!workflowPackage) {
        throw new WorkflowDeviceError(502, "workflow_package_invalid");
      }
      if (!packageIsCompatible(workflowPackage, deviceCapabilities)) {
        return response({
          ok: false,
          error: "workflow_incompatible",
          required: {
            schemaVersion: workflowPackage.schemaVersion,
            minAppVersionCode: workflowPackage.minAppVersionCode,
            capabilities: workflowPackage.requiredCapabilities,
          },
        }, 409);
      }
      return response(workflowPackage, 200, { "Cache-Control": "no-store" });
    }

    const statusRoute = path.match(
      /^\/device-sync\/workflows\/assignments\/([^/]+)\/status$/,
    );
    if (request.method === "POST" && statusRoute) {
      const body = await requestObject(request);
      const command = assignmentStatusCommand(body);
      if (!command) {
        return response({ ok: false, error: "invalid_status_report" }, 400);
      }
      const assignment = await gateway.claimAssignment(
        identity,
        statusRoute[1],
        `status:${statusRoute[1]}:${identity.deviceId}`,
      );
      if (!assignment) return response({ ok: false, error: "not_found" }, 404);
      const item = await gateway.reportAssignmentStatus(
        identity,
        statusRoute[1],
        command,
      );
      if (!item) return response({ ok: false, error: "not_found" }, 404);
      return response({
        assignmentId: requiredText(item.id) ?? statusRoute[1],
        status: requiredText(item.status) ?? command.status,
      });
    }

    if (
      request.method === "POST" &&
      path === "/device-sync/workflows/executions"
    ) {
      const command = executionStartCommand(await requestObject(request));
      if (!command) {
        return response({ ok: false, error: "invalid_execution_start" }, 400);
      }
      const item = await gateway.startExecution(identity, command);
      if (!item) return response({ ok: false, error: "not_found" }, 404);
      const execution = executionResponse(item);
      if (!execution) {
        throw new WorkflowDeviceError(502, "workflow_execution_invalid");
      }
      return response(execution, 201);
    }

    const stepRoute = path.match(
      /^\/device-sync\/workflows\/executions\/([^/]+)\/steps$/,
    );
    if (request.method === "POST" && stepRoute) {
      const command = stepExecutionCommand(await requestObject(request));
      if (!command) {
        return response({ ok: false, error: "invalid_step_report" }, 400);
      }
      const item = await gateway.appendStep(
        identity,
        stepRoute[1],
        command,
      );
      if (!item) return response({ ok: false, error: "not_found" }, 404);
      const step = stepExecutionResponse(item);
      if (!step) {
        throw new WorkflowDeviceError(502, "workflow_step_invalid_response");
      }
      return response(step);
    }
  } catch (error) {
    if (error instanceof WorkflowDeviceError) {
      return response({ ok: false, error: error.code }, error.status);
    }
    throw error;
  }

  return response({ ok: false, error: "not_found" }, 404);
}

export function createWorkflowDeviceGateway(
  supabase: any,
): WorkflowDeviceGateway {
  const deviceSync = createDeviceSyncGateway(supabase);
  return {
    authenticateDevice: deviceSync.authenticateDevice,
    async listAssignments(identity, afterSequence, limit) {
      const { data, error } = await supabase.from("workflow_assignments")
        .select(
          "id, work_order_id, project_id, workflow_version_id, mode, status, delivery_sequence, assigned_at",
        )
        .eq("organization_id", identity.organizationId)
        .eq("assigned_profile_id", identity.actorProfileId)
        .or(
          `assigned_device_id.is.null,assigned_device_id.eq.${identity.deviceId}`,
        )
        .gt("delivery_sequence", afterSequence)
        .order("delivery_sequence", { ascending: true })
        .limit(limit);
      if (error) throw error;
      return data ?? [];
    },
    async claimAssignment(identity, assignmentId, idempotencyKey) {
      const { data, error } = await supabase.rpc("claim_workflow_assignment", {
        target_assignment_id: assignmentId,
        target_profile_id: identity.actorProfileId,
        target_device_id: identity.deviceId,
        claim_idempotency_key: idempotencyKey,
      });
      if (error) throw workflowDatabaseError(error);
      return firstRow(data);
    },
    async getWorkflowPackage(identity, assignment) {
      const workflowVersionId = requiredText(assignment.workflow_version_id);
      if (!workflowVersionId) return null;
      const { data, error } = await supabase.from("workflow_versions")
        .select(
          "id, schema_version, execution_package, content_sha256, package_signature, signature_key_id, required_capabilities, min_app_version_code, status",
        )
        .eq("organization_id", identity.organizationId)
        .eq("id", workflowVersionId)
        .in("status", ["published", "deprecated"])
        .maybeSingle();
      if (error) throw error;
      if (!data) return null;
      return {
        assignment_id: assignment.id,
        workflow_version_id: data.id,
        schema_version: data.schema_version,
        execution_package: data.execution_package,
        content_sha256: data.content_sha256,
        package_signature: data.package_signature,
        signature_key_id: data.signature_key_id,
        required_capabilities: data.required_capabilities,
        min_app_version_code: data.min_app_version_code,
      };
    },
    async reportAssignmentStatus(identity, assignmentId, command) {
      const { data, error } = await supabase.rpc(
        "report_workflow_assignment_status",
        {
          target_assignment_id: assignmentId,
          target_profile_id: identity.actorProfileId,
          target_device_id: identity.deviceId,
          new_status: command.status,
          report_idempotency_key: command.idempotencyKey,
          reported_failure_stage: command.failureStage,
          reported_failure_reason: command.failureReason,
        },
      );
      if (error) throw workflowDatabaseError(error);
      return firstRow(data);
    },
    async startExecution(identity, command) {
      const { data, error } = await supabase.rpc(
        "start_workflow_execution",
        {
          target_assignment_id: command.assignmentId,
          target_project_id: command.projectId,
          target_task_id: command.taskId,
          initial_node_id: command.initialNodeId,
          execution_snapshot: command.runtimeSnapshot,
          start_idempotency_key: command.idempotencyKey,
          target_profile_id: identity.actorProfileId,
          target_device_id: identity.deviceId,
        },
      );
      if (error) throw workflowDatabaseError(error);
      return firstRow(data);
    },
    async appendStep(identity, executionId, command) {
      const { data, error } = await supabase.rpc(
        "append_workflow_step_execution",
        {
          target_execution_id: executionId,
          step_node_id: command.nodeId,
          step_attempt_number: command.attemptNumber,
          new_step_status: command.status,
          step_idempotency_key: command.idempotencyKey,
          step_input_data: command.inputData,
          step_output_data: command.outputData,
          step_evidence_asset_ids: command.evidenceAssetIds,
          step_transition_result: command.transitionResult,
          step_failure_code: command.failureCode,
          step_failure_reason: command.failureReason,
          next_node_id: command.nextNodeId,
          execution_runtime_snapshot: command.runtimeSnapshot,
          target_profile_id: identity.actorProfileId,
          target_device_id: identity.deviceId,
        },
      );
      if (error) throw workflowDatabaseError(error);
      return firstRow(data);
    },
  };
}

function assignmentMetadata(value: unknown): Record<string, unknown> | null {
  const item = recordValue(value);
  const assignmentId = requiredText(item?.id);
  const workOrderId = requiredText(item?.work_order_id);
  const mode = requiredText(item?.mode);
  const status = requiredText(item?.status);
  const deliverySequence = finiteInteger(item?.delivery_sequence);
  const assignedAt = requiredText(item?.assigned_at);
  if (
    !assignmentId || !workOrderId || !mode || !status ||
    deliverySequence === null || !assignedAt
  ) return null;
  return {
    assignmentId,
    workOrderId,
    projectId: nullableText(item?.project_id),
    workflowVersionId: nullableText(item?.workflow_version_id),
    mode,
    status,
    deliverySequence,
    assignedAt,
  };
}

function packageResponse(value: unknown): DeviceWorkflowPackage | null {
  const item = recordValue(value);
  const assignmentId = requiredText(item?.assignment_id);
  const workflowVersionId = requiredText(item?.workflow_version_id);
  const schemaVersion = finiteInteger(item?.schema_version);
  const executionPackage = recordValue(item?.execution_package);
  const contentSha256 = requiredText(item?.content_sha256);
  const packageSignature = requiredText(item?.package_signature);
  const signatureKeyId = requiredText(item?.signature_key_id);
  const minAppVersionCode = finiteInteger(item?.min_app_version_code);
  const requiredCapabilities = stringArray(item?.required_capabilities);
  const packageSchemaVersion = finiteInteger(executionPackage?.schemaVersion);
  const packageContentSha256 = requiredText(executionPackage?.contentSha256);
  if (
    !assignmentId || !workflowVersionId || schemaVersion === null ||
    schemaVersion < 1 || !executionPackage ||
    !contentSha256 || !/^[0-9a-f]{64}$/.test(contentSha256) ||
    packageSchemaVersion !== schemaVersion ||
    packageContentSha256 !== contentSha256 ||
    !packageSignature || !signatureKeyId || minAppVersionCode === null ||
    minAppVersionCode < 1 || requiredCapabilities === null
  ) return null;
  return {
    assignmentId,
    workflowVersionId,
    schemaVersion,
    executionPackage,
    contentSha256,
    packageSignature,
    signatureKeyId,
    requiredCapabilities,
    minAppVersionCode,
  };
}

function executionStartCommand(
  body: Record<string, unknown> | null,
): WorkflowExecutionStartCommand | null {
  const assignmentId = boundedText(body?.assignmentId, 200);
  const projectId = boundedText(body?.projectId, 200);
  const taskId = boundedText(body?.taskId, 200);
  const initialNodeId = boundedText(body?.initialNodeId, 160);
  const runtimeSnapshot = recordValue(body?.runtimeSnapshot);
  const idempotencyKey = boundedText(body?.idempotencyKey, 200);
  if (
    !assignmentId || !projectId || !taskId || !initialNodeId ||
    !runtimeSnapshot || !idempotencyKey
  ) return null;
  return {
    assignmentId,
    projectId,
    taskId,
    initialNodeId,
    runtimeSnapshot,
    idempotencyKey,
  };
}

function executionResponse(
  value: unknown,
): Record<string, unknown> | null {
  const item = recordValue(value);
  const executionId = requiredText(item?.id);
  const assignmentId = requiredText(item?.assignment_id);
  const taskId = requiredText(item?.task_id);
  const status = requiredText(item?.status);
  const currentNodeId = requiredText(item?.current_node_id);
  const startedAt = requiredText(item?.started_at);
  if (
    !executionId || !assignmentId || !taskId || !status || !currentNodeId ||
    !startedAt
  ) return null;
  return {
    executionId,
    assignmentId,
    taskId,
    status,
    currentNodeId,
    startedAt,
  };
}

function stepExecutionCommand(
  body: Record<string, unknown> | null,
): WorkflowStepExecutionCommand | null {
  const nodeId = boundedText(body?.nodeId, 160);
  const attemptNumber = finiteInteger(body?.attemptNumber);
  const status = requiredText(body?.status);
  const idempotencyKey = boundedText(body?.idempotencyKey, 200);
  const inputData = recordValue(body?.inputData);
  const outputData = recordValue(body?.outputData);
  const evidenceAssetIds = evidenceIdentifiers(body?.evidenceAssetIds);
  const transitionResult = recordValue(body?.transitionResult);
  const failureCode = nullableBoundedText(body?.failureCode, 160);
  const failureReason = nullableBoundedText(body?.failureReason, 1000);
  const nextNodeId = nullableBoundedText(body?.nextNodeId, 160);
  const runtimeSnapshot = recordValue(body?.runtimeSnapshot);
  if (
    !nodeId || attemptNumber === null || attemptNumber < 1 ||
    attemptNumber > 2_147_483_647 || !status ||
    !workflowStepStatuses.has(status) || !idempotencyKey || !inputData ||
    !outputData || evidenceAssetIds === null || !transitionResult ||
    failureCode === undefined || failureReason === undefined ||
    nextNodeId === undefined || !runtimeSnapshot ||
    (status === "failed" && !failureReason)
  ) return null;
  return {
    nodeId,
    attemptNumber,
    status: status as WorkflowStepExecutionCommand["status"],
    idempotencyKey,
    inputData,
    outputData,
    evidenceAssetIds,
    transitionResult,
    failureCode,
    failureReason,
    nextNodeId,
    runtimeSnapshot,
  };
}

function stepExecutionResponse(
  value: unknown,
): Record<string, unknown> | null {
  const item = recordValue(value);
  const stepExecutionId = requiredText(item?.id);
  const executionId = requiredText(item?.execution_id);
  const nodeId = requiredText(item?.node_id);
  const attemptNumber = finiteInteger(item?.attempt_number);
  const status = requiredText(item?.status);
  const evidenceAssetIds = evidenceIdentifiers(item?.evidence_asset_ids);
  const updatedAt = requiredText(item?.updated_at);
  if (
    !stepExecutionId || !executionId || !nodeId || attemptNumber === null ||
    attemptNumber < 1 || !status || !workflowStepStatuses.has(status) ||
    evidenceAssetIds === null || !updatedAt
  ) return null;
  return {
    stepExecutionId,
    executionId,
    nodeId,
    attemptNumber,
    status,
    evidenceAssetIds,
    updatedAt,
  };
}

function evidenceIdentifiers(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.length > 1000) return null;
  const identifiers = value.map((item) => requiredText(item));
  if (
    identifiers.some((item) => !item || !isUuid(item)) ||
    new Set(identifiers).size !== identifiers.length
  ) return null;
  return identifiers as string[];
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    .test(value);
}

function capabilityDeclaration(request: Request): DeviceCapabilities | null {
  const appVersionCode = finiteInteger(
    request.headers.get("X-App-Version-Code"),
  );
  const workflowSchemaVersion = finiteInteger(
    request.headers.get("X-Workflow-Schema-Version"),
  );
  const capabilityHeader = request.headers.get("X-Workflow-Capabilities");
  if (
    appVersionCode === null || appVersionCode < 9000 ||
    workflowSchemaVersion === null || workflowSchemaVersion < 1 ||
    capabilityHeader === null || capabilityHeader.length > 4000
  ) return null;
  const capabilities = capabilityHeader.split(",").map((item) => item.trim())
    .filter(Boolean);
  if (capabilities.length === 0 || capabilities.length > 100) return null;
  return {
    appVersionCode,
    workflowSchemaVersion,
    capabilities: new Set(capabilities),
  };
}

function packageIsCompatible(
  workflowPackage: DeviceWorkflowPackage,
  device: DeviceCapabilities,
): boolean {
  return workflowPackage.schemaVersion <= device.workflowSchemaVersion &&
    workflowPackage.minAppVersionCode <= device.appVersionCode &&
    workflowPackage.requiredCapabilities.every((capability) =>
      device.capabilities.has(capability)
    );
}

function assignmentStatusCommand(
  body: Record<string, unknown> | null,
): WorkflowAssignmentStatusCommand | null {
  const status = requiredText(body?.status);
  const idempotencyKey = boundedText(body?.idempotencyKey, 200);
  const failureStage = nullableBoundedText(body?.failureStage, 160);
  const failureReason = nullableBoundedText(body?.failureReason, 1000);
  if (
    !status || !reportableStatuses.has(status) || !idempotencyKey ||
    failureStage === undefined || failureReason === undefined ||
    (status === "failed" && !failureReason)
  ) return null;
  return {
    status: status as WorkflowAssignmentStatusCommand["status"],
    idempotencyKey,
    failureStage,
    failureReason,
  };
}

function workflowDatabaseError(error: unknown): Error {
  const message = String(recordValue(error)?.message ?? error).toLowerCase();
  if (message.includes("invalid workflow assignment status transition")) {
    return new WorkflowDeviceError(409, "workflow_transition_invalid");
  }
  if (
    message.includes("completed or cancelled workflow execution") ||
    message.includes("terminal workflow execution")
  ) {
    return new WorkflowDeviceError(409, "workflow_execution_terminal");
  }
  if (message.includes("invalid workflow step status transition")) {
    return new WorkflowDeviceError(409, "workflow_step_transition_invalid");
  }
  if (message.includes("workflow step evidence does not belong")) {
    return new WorkflowDeviceError(409, "workflow_evidence_invalid");
  }
  if (
    message.includes("initial node is not in signed package") ||
    message.includes("step node is not in signed package") ||
    message.includes("step transition is not in signed package") ||
    message.includes("step does not match current node")
  ) {
    return new WorkflowDeviceError(409, "workflow_package_state_invalid");
  }
  if (message.includes("workflow execution package is unavailable")) {
    return new WorkflowDeviceError(409, "workflow_package_unavailable");
  }
  if (
    message.includes("idempotency key was already used") ||
    message.includes("assignment already has an execution")
  ) {
    return new WorkflowDeviceError(409, "workflow_idempotency_conflict");
  }
  if (
    message.includes("terminal workflow assignment") ||
    message.includes("not ready for execution") ||
    message.includes("workflow-free assignment")
  ) {
    return new WorkflowDeviceError(409, "workflow_assignment_unavailable");
  }
  if (
    message.includes("not found") || message.includes("ownership") ||
    message.includes("not bound") || message.includes("not authorized")
  ) {
    return new WorkflowDeviceError(404, "not_found");
  }
  return error instanceof Error ? error : new Error(String(error));
}

function integerQuery(value: string | null, fallback: number): number | null {
  if (value === null || value === "") return fallback;
  if (!/^\d+$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) ? parsed : null;
}

function finiteInteger(value: unknown): number | null {
  if (typeof value === "string" && /^\d+$/.test(value)) value = Number(value);
  return typeof value === "number" && Number.isSafeInteger(value)
    ? value
    : null;
}

function stringArray(value: unknown): string[] | null {
  return Array.isArray(value) &&
      value.every((item) => requiredText(item) !== null)
    ? value.map((item) => String(item).trim())
    : null;
}

function nullableText(value: unknown): string | null {
  return requiredText(value);
}

function requiredText(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function boundedText(value: unknown, maxLength: number): string | null {
  const text = requiredText(value);
  return text && text.length <= maxLength ? text : null;
}

function nullableBoundedText(
  value: unknown,
  maxLength: number,
): string | null | undefined {
  if (value === undefined || value === null || value === "") return null;
  return boundedText(value, maxLength) ?? undefined;
}

async function requestObject(
  request: Request,
): Promise<Record<string, unknown> | null> {
  try {
    return recordValue(await request.json());
  } catch {
    return null;
  }
}

function bearerToken(request: Request): string | null {
  const value = request.headers.get("Authorization") ?? "";
  return value.startsWith("Bearer ") && value.length > 7
    ? value.slice(7)
    : null;
}

function routePath(request: Request): string {
  const path = new URL(request.url).pathname
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/";
  return path === "/" ? path : path.replace(/\/$/, "");
}

function firstRow(data: unknown): Record<string, unknown> | null {
  if (Array.isArray(data)) return recordValue(data[0]);
  return recordValue(data);
}

function recordValue(value: unknown): Record<string, unknown> | null {
  return isRecord(value) ? value : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function response(
  body: unknown,
  status = 200,
  extraHeaders: Record<string, string> = {},
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...headers, ...extraHeaders },
  });
}
