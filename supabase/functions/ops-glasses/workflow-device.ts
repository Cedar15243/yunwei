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
    message.includes("terminal workflow assignment") ||
    message.includes("not ready for execution")
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
