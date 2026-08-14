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
    | "failed";
  idempotencyKey: string;
  failureStage: string | null;
  failureReason: string | null;
};

export type WorkflowExecutionStartCommand = {
  executionId: string;
  assignmentId: string;
  projectId: string;
  localTaskId: string;
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

export type WorkflowEvidenceUploadCommand = {
  assignmentId: string;
  executionId: string;
  localEvidenceId: string;
  nodeId: string;
  evidenceKey: string;
  kind: "photo" | "video";
  contentType: "image/jpeg" | "video/mp4";
  byteSize: number;
  durationSeconds?: number;
  sha256: string;
  bytes: Uint8Array;
  capturedAt: string;
};

export type WorkflowEvidenceUploadSessionCommand = Omit<
  WorkflowEvidenceUploadCommand,
  "bytes"
> & {
  chunkSize: number;
  chunkCount: number;
};

export type WorkflowEvidenceChunkCommand = {
  uploadId: string;
  chunkIndex: number;
  chunkCount: number;
  chunkByteSize: number;
  chunkSha256: string;
  bytes: Uint8Array;
};

export type WorkflowEvidenceCompleteCommand = {
  uploadId: string;
  chunkCount: number;
  sha256: string;
};

export type WorkflowEvidenceCancelCommand = {
  uploadId: string;
  assetId: string;
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
  storeEvidence(
    identity: DeviceSyncIdentity,
    command: WorkflowEvidenceUploadCommand,
  ): Promise<Record<string, unknown> | null>;
  createEvidenceUploadSession?(
    identity: DeviceSyncIdentity,
    command: WorkflowEvidenceUploadSessionCommand,
  ): Promise<Record<string, unknown> | null>;
  storeEvidenceChunk?(
    identity: DeviceSyncIdentity,
    command: WorkflowEvidenceChunkCommand,
  ): Promise<Record<string, unknown> | null>;
  completeEvidenceUpload?(
    identity: DeviceSyncIdentity,
    command: WorkflowEvidenceCompleteCommand,
  ): Promise<Record<string, unknown> | null>;
  cancelEvidenceUpload?(
    identity: DeviceSyncIdentity,
    command: WorkflowEvidenceCancelCommand,
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
  "failed",
]);

const workOrderStatuses = new Set([
  "received",
  "accepted",
  "in_progress",
  "completed",
  "closed",
  "cancelled",
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

const maxWorkflowPhotoBytes = 5 * 1024 * 1024;
const maxWorkflowVideoBytes = 8 * 1024 * 1024;
const maxWorkflowVideoDurationSeconds = 15;
const maxWorkflowEvidenceChunkBytes = 1024 * 1024;
const maxWorkflowEvidenceChunks = 10000;
const workflowEvidenceBucket = "ops-glasses-captures";

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
      if (!execution || execution.executionId !== command.executionId) {
        throw new WorkflowDeviceError(502, "workflow_execution_invalid");
      }
      return response(execution, 201);
    }

    if (
      request.method === "POST" &&
      path === "/device-sync/workflows/evidence"
    ) {
      const command = await workflowEvidenceUploadCommand(
        await requestObject(request),
      );
      if (!command) {
        return response(
          { ok: false, error: "invalid_workflow_evidence" },
          400,
        );
      }
      const item = await gateway.storeEvidence(identity, command);
      if (!item) return response({ ok: false, error: "not_found" }, 404);
      const evidence = workflowEvidenceResponse(item, command);
      if (!evidence) {
        throw new WorkflowDeviceError(
          502,
          "workflow_evidence_invalid_response",
        );
      }
      return response(evidence, 201);
    }

    if (
      request.method === "POST" &&
      path === "/device-sync/workflows/evidence/session"
    ) {
      if (!gateway.createEvidenceUploadSession) {
        return response({ ok: false, error: "workflow_evidence_resumable_unavailable" }, 503);
      }
      const command = workflowEvidenceUploadSessionCommand(
        await requestObject(request),
      );
      if (!command) {
        return response(
          { ok: false, error: "invalid_workflow_evidence_session" },
          400,
        );
      }
      const item = await gateway.createEvidenceUploadSession(identity, command);
      if (!item) return response({ ok: false, error: "not_found" }, 404);
      const evidence = workflowEvidenceUploadSessionResponse(item);
      if (!evidence) {
        throw new WorkflowDeviceError(502, "workflow_evidence_invalid_response");
      }
      return response(evidence, item.created === true ? 201 : 200);
    }

    if (
      request.method === "POST" &&
      path === "/device-sync/workflows/evidence/chunk"
    ) {
      if (!gateway.storeEvidenceChunk) {
        return response({ ok: false, error: "workflow_evidence_resumable_unavailable" }, 503);
      }
      const command = await workflowEvidenceChunkCommand(await requestObject(request));
      if (!command) {
        return response(
          { ok: false, error: "invalid_workflow_evidence_chunk" },
          400,
        );
      }
      const item = await gateway.storeEvidenceChunk(identity, command);
      if (!item) return response({ ok: false, error: "not_found" }, 404);
      const evidence = workflowEvidenceUploadSessionResponse(item);
      if (!evidence) {
        throw new WorkflowDeviceError(502, "workflow_evidence_invalid_response");
      }
      return response(evidence, 200);
    }

    if (
      request.method === "POST" &&
      path === "/device-sync/workflows/evidence/complete"
    ) {
      if (!gateway.completeEvidenceUpload) {
        return response({ ok: false, error: "workflow_evidence_resumable_unavailable" }, 503);
      }
      const command = workflowEvidenceCompleteCommand(await requestObject(request));
      if (!command) {
        return response(
          { ok: false, error: "invalid_workflow_evidence_complete" },
          400,
        );
      }
      const item = await gateway.completeEvidenceUpload(identity, command);
      if (!item) return response({ ok: false, error: "not_found" }, 404);
      const evidence = workflowEvidenceResponseFromUpload(item);
      if (!evidence) {
        throw new WorkflowDeviceError(502, "workflow_evidence_invalid_response");
      }
      return response(evidence, item.finalized === true ? 201 : 200);
    }

    if (
      request.method === "POST" &&
      path === "/device-sync/workflows/evidence/cancel"
    ) {
      if (!gateway.cancelEvidenceUpload) {
        return response({ ok: false, error: "workflow_evidence_resumable_unavailable" }, 503);
      }
      const command = workflowEvidenceCancelCommand(await requestObject(request));
      if (!command) {
        return response(
          { ok: false, error: "invalid_workflow_evidence_cancel" },
          400,
        );
      }
      const item = await gateway.cancelEvidenceUpload(identity, command);
      if (!item) return response({ ok: false, error: "not_found" }, 404);
      const evidence = workflowEvidenceCancelResponse(item);
      if (!evidence) {
        throw new WorkflowDeviceError(502, "workflow_evidence_invalid_response");
      }
      return response(evidence, 200);
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
          "id, work_order_id, project_id, workflow_version_id, mode, status, delivery_sequence, assigned_at, work_orders!workflow_assignments_work_order_fk(external_work_order_id, title, description, customer_id, work_order_type, asset_id, asset_category, asset_brand, asset_model, priority, risk_level, status, due_at, received_at)",
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
          requested_execution_id: command.executionId,
          target_assignment_id: command.assignmentId,
          target_project_id: command.projectId,
          target_local_task_id: command.localTaskId,
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
    async storeEvidence(identity, command) {
      const { data: execution, error: executionError } = await supabase
        .from("workflow_executions")
        .select("id, assignment_id, task_id")
        .eq("organization_id", identity.organizationId)
        .eq("operator_profile_id", identity.actorProfileId)
        .eq("device_id", identity.deviceId)
        .eq("id", command.executionId)
        .eq("assignment_id", command.assignmentId)
        .maybeSingle();
      if (executionError) throw executionError;
      const taskId = uuidValue(execution?.task_id);
      if (!execution || !taskId) return null;

      const filePath = [
        "workflow",
        identity.organizationId,
        taskId,
        command.executionId,
        `${command.localEvidenceId}.${
          command.kind === "video" ? "mp4" : "jpg"
        }`,
      ].join("/");
      const existing = await existingEvidence(supabase, filePath);
      if (existing) {
        const recoverable = recoverableEvidence(existing, command);
        if (recoverable.upload_status === "synced") return recoverable;
        return await uploadReservedEvidence(
          supabase,
          identity,
          filePath,
          recoverable,
          command,
        );
      }

      const { data: reserved, error: reserveError } = await supabase
        .from("media_assets").insert({
          organization_id: identity.organizationId,
          task_id: taskId,
          kind: command.kind,
          content_type: command.contentType,
          storage_bucket: workflowEvidenceBucket,
          file_path: filePath,
          sha256: command.sha256,
          byte_size: command.byteSize,
          duration_seconds: command.durationSeconds ?? 0,
          upload_status: "uploading",
          failure_reason: "",
          captured_at: command.capturedAt,
        }).select("id, upload_status, byte_size, duration_seconds, sha256")
        .single();
      if (reserveError || !reserved) {
        const raced = await existingEvidence(supabase, filePath);
        if (!raced) {
          throw reserveError ??
            new Error("workflow evidence reservation failed");
        }
        const recoverable = recoverableEvidence(raced, command);
        if (recoverable.upload_status === "synced") return recoverable;
        return await uploadReservedEvidence(
          supabase,
          identity,
          filePath,
          recoverable,
          command,
        );
      }
      return await uploadReservedEvidence(
        supabase,
        identity,
        filePath,
        recoverableEvidence(reserved, command),
        command,
      );
    },
    async createEvidenceUploadSession(identity, command) {
      const execution = await workflowEvidenceExecution(
        supabase,
        identity,
        command.executionId,
        command.assignmentId,
      );
      if (!execution) return null;
      const filePath = workflowEvidenceFilePath(
        identity,
        execution.task_id as string,
        command.executionId,
        command.localEvidenceId,
        command.kind,
      );
      let asset = await existingEvidence(supabase, filePath);
      let created = false;
      if (!asset) {
        const { data, error } = await supabase.from("media_assets").insert({
          organization_id: identity.organizationId,
          task_id: execution.task_id,
          kind: command.kind,
          content_type: command.contentType,
          storage_bucket: workflowEvidenceBucket,
          file_path: filePath,
          sha256: command.sha256,
          byte_size: command.byteSize,
          duration_seconds: command.durationSeconds ?? 0,
          upload_status: "uploading",
          failure_reason: "",
          captured_at: command.capturedAt,
        }).select("id, kind, content_type, upload_status, byte_size, duration_seconds, sha256")
          .single();
        if (error || !data) {
          asset = await existingEvidence(supabase, filePath);
          if (!asset) throw error ?? new Error("workflow evidence reservation failed");
        } else {
          asset = data;
          created = true;
        }
      }
      if (!asset) throw new Error("workflow evidence reservation failed");
      let recoverable = recoverableEvidenceMetadata(asset, command);
      let upload = await existingEvidenceUpload(supabase, identity, recoverable.id as string);
      if (!upload) {
        const { data, error } = await supabase.from("workflow_evidence_uploads").insert({
          asset_id: recoverable.id,
          organization_id: identity.organizationId,
          device_id: identity.deviceId,
          chunk_size: command.chunkSize,
          chunk_count: command.chunkCount,
          completed_at: recoverable.upload_status === "synced"
            ? new Date().toISOString()
            : null,
          cancelled_at: null,
        }).select("upload_id, asset_id, device_id, chunk_size, chunk_count").single();
        if (error || !data) {
          upload = await existingEvidenceUpload(supabase, identity, recoverable.id as string);
          if (!upload) throw error ?? new Error("workflow evidence upload session failed");
        } else {
          upload = data;
          created = true;
        }
      }
      if (!upload) throw new Error("workflow evidence upload session failed");
      if (upload.cancelled_at || recoverable.upload_status === "cancelled") {
        await cleanupWorkflowEvidenceParts(supabase, upload.upload_id as string);
        const now = new Date().toISOString();
        const { data: restartedAsset, error: assetResetError } = await supabase
          .from("media_assets")
          .update({ upload_status: "uploading", failure_reason: "" })
          .eq("organization_id", identity.organizationId)
          .eq("id", recoverable.id)
          .select("id, kind, content_type, upload_status, byte_size, duration_seconds, sha256")
          .single();
        if (assetResetError || !restartedAsset) {
          throw assetResetError ?? new Error("workflow evidence restart failed");
        }
        const { data: restartedUpload, error: uploadResetError } = await supabase
          .from("workflow_evidence_uploads")
          .update({ updated_at: now, completed_at: null, cancelled_at: null })
          .eq("upload_id", upload.upload_id)
          .eq("organization_id", identity.organizationId)
          .eq("device_id", identity.deviceId)
          .select("upload_id, asset_id, device_id, chunk_size, chunk_count, completed_at, cancelled_at")
          .single();
        if (uploadResetError || !restartedUpload) {
          throw uploadResetError ?? new Error("workflow evidence restart failed");
        }
        recoverable = restartedAsset;
        upload = restartedUpload;
      }
      const activeUpload = upload;
      if (!activeUpload) throw new Error("workflow evidence upload session failed");
      if (
        finiteInteger(activeUpload.chunk_size) !== command.chunkSize ||
        finiteInteger(activeUpload.chunk_count) !== command.chunkCount
      ) {
        throw new WorkflowDeviceError(409, "workflow_evidence_upload_conflict");
      }
      const receivedChunks = await receivedChunkIndexes(
        supabase,
        activeUpload.upload_id as string,
      );
      return {
        ...recoverable,
        ...activeUpload,
        created,
        received_chunks: receivedChunks,
        next_chunk_index: nextMissingChunk(receivedChunks, command.chunkCount),
      };
    },
    async storeEvidenceChunk(identity, command) {
      const { data: upload, error: uploadError } = await supabase
        .from("workflow_evidence_uploads")
        .select("upload_id, asset_id, organization_id, device_id, chunk_size, chunk_count, completed_at, cancelled_at")
        .eq("upload_id", command.uploadId)
        .eq("organization_id", identity.organizationId)
        .eq("device_id", identity.deviceId)
        .maybeSingle();
      if (uploadError) throw uploadError;
      if (!upload) return null;
      const { data: asset, error: assetError } = await supabase.from("media_assets")
        .select("id, upload_status, byte_size, duration_seconds, sha256")
        .eq("id", upload.asset_id)
        .eq("organization_id", identity.organizationId)
        .maybeSingle();
      if (assetError) throw assetError;
      if (!asset) return null;
      if (upload.cancelled_at || asset.upload_status === "cancelled") {
        throw new WorkflowDeviceError(409, "workflow_evidence_upload_cancelled");
      }
      if (asset.upload_status === "synced") {
        throw new WorkflowDeviceError(409, "workflow_evidence_upload_completed");
      }
      if (finiteInteger(upload.chunk_count) !== command.chunkCount) {
        throw new WorkflowDeviceError(409, "workflow_evidence_chunk_conflict");
      }
      const expectedSize = expectedWorkflowChunkSize(
        finiteInteger(asset.byte_size) ?? -1,
        finiteInteger(upload.chunk_size) ?? -1,
        command.chunkCount,
        command.chunkIndex,
      );
      if (expectedSize !== command.chunkByteSize) {
        throw new WorkflowDeviceError(409, "workflow_evidence_chunk_size_mismatch");
      }
      const { data: existing, error: existingError } = await supabase
        .from("workflow_evidence_parts")
        .select("chunk_index, byte_size, sha256, storage_path")
        .eq("upload_id", command.uploadId)
        .eq("chunk_index", command.chunkIndex)
        .maybeSingle();
      if (existingError) throw existingError;
      if (existing && (existing.byte_size !== command.chunkByteSize || existing.sha256 !== command.chunkSha256)) {
        throw new WorkflowDeviceError(409, "workflow_evidence_chunk_conflict");
      }
      const storagePath = `workflow/${identity.organizationId}/.parts/${command.uploadId}/${String(command.chunkIndex).padStart(8, "0")}.part`;
      const { error: storageError } = await supabase.storage.from(workflowEvidenceBucket)
        .upload(storagePath, command.bytes, {
          contentType: "application/octet-stream",
          cacheControl: "3600",
          upsert: true,
        });
      if (storageError) throw new WorkflowDeviceError(502, "workflow_evidence_storage_failed");
      if (!existing) {
        const { error } = await supabase.from("workflow_evidence_parts").insert({
          upload_id: command.uploadId,
          asset_id: upload.asset_id,
          organization_id: identity.organizationId,
          chunk_index: command.chunkIndex,
          byte_size: command.chunkByteSize,
          sha256: command.chunkSha256,
          storage_path: storagePath,
        });
        if (error && !String(error.message ?? "").includes("duplicate")) throw error;
      }
      const received = await receivedChunkIndexes(supabase, command.uploadId);
      const nextChunkIndex = nextMissingChunk(received, command.chunkCount);
      return {
        id: upload.asset_id,
        asset_id: upload.asset_id,
        upload_id: command.uploadId,
        upload_status: "uploading",
        byte_size: asset.byte_size,
        duration_seconds: asset.duration_seconds,
        sha256: asset.sha256,
        chunk_size: upload.chunk_size,
        chunk_count: command.chunkCount,
        received_chunks: received,
        next_chunk_index: nextChunkIndex,
      };
    },
    async completeEvidenceUpload(identity, command) {
      const { data: upload, error: uploadError } = await supabase
        .from("workflow_evidence_uploads")
        .select("upload_id, asset_id, organization_id, device_id, chunk_size, chunk_count, completed_at, cancelled_at")
        .eq("upload_id", command.uploadId)
        .eq("organization_id", identity.organizationId)
        .eq("device_id", identity.deviceId)
        .maybeSingle();
      if (uploadError) throw uploadError;
      if (!upload) return null;
      const { data: asset, error: assetError } = await supabase.from("media_assets")
        .select("id, kind, upload_status, byte_size, duration_seconds, sha256, file_path, content_type")
        .eq("id", upload.asset_id)
        .eq("organization_id", identity.organizationId)
        .maybeSingle();
      if (assetError) throw assetError;
      if (!asset) return null;
      if (upload.cancelled_at || asset.upload_status === "cancelled") {
        throw new WorkflowDeviceError(409, "workflow_evidence_upload_cancelled");
      }
      if (finiteInteger(upload.chunk_count) !== command.chunkCount || asset.sha256 !== command.sha256) {
        throw new WorkflowDeviceError(409, "workflow_evidence_upload_conflict");
      }
      if (asset.upload_status === "synced") {
        await cleanupWorkflowEvidenceParts(supabase, command.uploadId);
        return {
          ...asset,
          upload_id: command.uploadId,
          chunk_size: upload.chunk_size,
          chunk_count: upload.chunk_count,
          finalized: false,
        };
      }
      const { data: parts, error: partsError } = await supabase.from("workflow_evidence_parts")
        .select("chunk_index, byte_size, sha256, storage_path")
        .eq("upload_id", command.uploadId)
        .order("chunk_index", { ascending: true });
      if (partsError) throw partsError;
      if (!parts || parts.length !== command.chunkCount || parts.some((part: any, index: number) => part.chunk_index !== index)) {
        throw new WorkflowDeviceError(409, "workflow_evidence_chunks_missing");
      }
      const merged = new Uint8Array(finiteInteger(asset.byte_size) ?? 0);
      let offset = 0;
      for (const part of parts as any[]) {
        const { data: blob, error } = await supabase.storage.from(workflowEvidenceBucket).download(part.storage_path);
        if (error || !blob) throw new WorkflowDeviceError(502, "workflow_evidence_storage_failed");
        const bytes = new Uint8Array(await blob.arrayBuffer());
        if (bytes.length !== part.byte_size || await sha256Hex(bytes) !== part.sha256) {
          throw new WorkflowDeviceError(409, "workflow_evidence_chunk_corrupt");
        }
        merged.set(bytes, offset);
        offset += bytes.length;
      }
      if (offset !== merged.length || await sha256Hex(merged) !== command.sha256 || !validWorkflowMediaBytes(asset.kind, merged)) {
        throw new WorkflowDeviceError(409, "workflow_evidence_digest_mismatch");
      }
      const { error: finalUploadError } = await supabase.storage.from(workflowEvidenceBucket)
        .upload(asset.file_path, merged, {
          contentType: asset.content_type,
          cacheControl: "3600",
          upsert: true,
        });
      if (finalUploadError) throw new WorkflowDeviceError(502, "workflow_evidence_storage_failed");
      const { data: updated, error: updateError } = await supabase.from("media_assets")
        .update({ upload_status: "synced", failure_reason: "" })
        .eq("organization_id", identity.organizationId)
        .eq("id", asset.id)
        .select("id, upload_status, byte_size, duration_seconds, sha256")
        .single();
      if (updateError || !updated) throw updateError ?? new Error("workflow evidence completion failed");
      await supabase.from("workflow_evidence_uploads")
        .update({ updated_at: new Date().toISOString(), completed_at: new Date().toISOString() })
        .eq("upload_id", command.uploadId);
      await cleanupWorkflowEvidenceParts(supabase, command.uploadId, parts as any[]);
      return {
        ...updated,
        upload_id: command.uploadId,
        chunk_size: upload.chunk_size,
        chunk_count: upload.chunk_count,
        finalized: true,
      };
    },
    async cancelEvidenceUpload(identity, command) {
      const { data: upload, error: uploadError } = await supabase
        .from("workflow_evidence_uploads")
        .select("upload_id, asset_id, organization_id, device_id, chunk_size, chunk_count, completed_at, cancelled_at")
        .eq("upload_id", command.uploadId)
        .eq("asset_id", command.assetId)
        .eq("organization_id", identity.organizationId)
        .eq("device_id", identity.deviceId)
        .maybeSingle();
      if (uploadError) throw uploadError;
      if (!upload) return null;
      const { data: asset, error: assetError } = await supabase.from("media_assets")
        .select("id, upload_status, byte_size, duration_seconds, sha256")
        .eq("id", command.assetId)
        .eq("organization_id", identity.organizationId)
        .maybeSingle();
      if (assetError) throw assetError;
      if (!asset) return null;
      if (upload.completed_at || asset.upload_status === "synced") {
        throw new WorkflowDeviceError(409, "workflow_evidence_upload_completed");
      }
      await cleanupWorkflowEvidenceParts(supabase, command.uploadId);
      const cancelledAt = typeof upload.cancelled_at === "string" && upload.cancelled_at
        ? upload.cancelled_at
        : new Date().toISOString();
      const { data: cancelledAsset, error: cancelAssetError } = await supabase
        .from("media_assets")
        .update({
          upload_status: "cancelled",
          failure_reason: "workflow_evidence_upload_cancelled",
        })
        .eq("organization_id", identity.organizationId)
        .eq("id", command.assetId)
        .select("id, upload_status, byte_size, duration_seconds, sha256")
        .single();
      if (cancelAssetError || !cancelledAsset) {
        throw cancelAssetError ?? new Error("workflow evidence cancellation failed");
      }
      const { data: cancelledUpload, error: cancelUploadError } = await supabase
        .from("workflow_evidence_uploads")
        .update({
          updated_at: cancelledAt,
          completed_at: null,
          cancelled_at: cancelledAt,
        })
        .eq("upload_id", command.uploadId)
        .eq("organization_id", identity.organizationId)
        .eq("device_id", identity.deviceId)
        .select("upload_id, asset_id, chunk_size, chunk_count, completed_at, cancelled_at")
        .single();
      if (cancelUploadError || !cancelledUpload) {
        throw cancelUploadError ?? new Error("workflow evidence cancellation failed");
      }
      return {
        ...cancelledAsset,
        ...cancelledUpload,
        received_chunks: [],
        next_chunk_index: 0,
      };
    },
  };
}

async function existingEvidence(
  supabase: any,
  filePath: string,
): Promise<Record<string, unknown> | null> {
  const { data, error } = await supabase.from("media_assets")
    .select("id, kind, content_type, upload_status, byte_size, duration_seconds, sha256, file_path")
    .eq("file_path", filePath)
    .maybeSingle();
  if (error) throw error;
  return isRecord(data) ? data : null;
}

async function workflowEvidenceExecution(
  supabase: any,
  identity: DeviceSyncIdentity,
  executionId: string,
  assignmentId: string,
): Promise<Record<string, unknown> | null> {
  const { data, error } = await supabase.from("workflow_executions")
    .select("id, assignment_id, task_id")
    .eq("organization_id", identity.organizationId)
    .eq("operator_profile_id", identity.actorProfileId)
    .eq("device_id", identity.deviceId)
    .eq("id", executionId)
    .eq("assignment_id", assignmentId)
    .maybeSingle();
  if (error) throw error;
  return isRecord(data) && uuidValue(data.task_id) ? data : null;
}

function workflowEvidenceFilePath(
  identity: DeviceSyncIdentity,
  taskId: string,
  executionId: string,
  localEvidenceId: string,
  kind: unknown,
): string {
  return [
    "workflow",
    identity.organizationId,
    taskId,
    executionId,
    `${localEvidenceId}.${kind === "video" ? "mp4" : "jpg"}`,
  ].join("/");
}

async function existingEvidenceUpload(
  supabase: any,
  identity: DeviceSyncIdentity,
  assetId: string,
): Promise<Record<string, unknown> | null> {
  const { data, error } = await supabase.from("workflow_evidence_uploads")
    .select("upload_id, asset_id, organization_id, device_id, chunk_size, chunk_count, completed_at, cancelled_at")
    .eq("asset_id", assetId)
    .eq("organization_id", identity.organizationId)
    .eq("device_id", identity.deviceId)
    .maybeSingle();
  if (error) throw error;
  return isRecord(data) ? data : null;
}

function recoverableEvidenceMetadata(
  item: Record<string, unknown>,
  command: WorkflowEvidenceUploadSessionCommand,
): Record<string, unknown> {
  if (
    !uuidValue(item.id) ||
    !new Set(["uploading", "failed", "synced", "cancelled"]).has(String(item.upload_status)) ||
    item.kind !== command.kind ||
    item.content_type !== command.contentType ||
    finiteInteger(item.byte_size) !== command.byteSize ||
    evidenceDuration(item, command) !== (command.durationSeconds ?? 0) ||
    item.sha256 !== command.sha256
  ) {
    throw new WorkflowDeviceError(409, "workflow_evidence_conflict");
  }
  return item;
}

async function receivedChunkIndexes(supabase: any, uploadId: string): Promise<number[]> {
  const { data, error } = await supabase.from("workflow_evidence_parts")
    .select("chunk_index")
    .eq("upload_id", uploadId)
    .order("chunk_index", { ascending: true });
  if (error) throw error;
  return (data ?? [])
    .map((item: any) => finiteInteger(item.chunk_index))
    .filter((value: number | null): value is number => value !== null);
}

async function cleanupWorkflowEvidenceParts(
  supabase: any,
  uploadId: string,
  knownParts?: Array<Record<string, unknown>>,
): Promise<void> {
  let parts = knownParts;
  if (!parts) {
    const { data, error } = await supabase.from("workflow_evidence_parts")
      .select("storage_path")
      .eq("upload_id", uploadId);
    if (error) throw error;
    parts = Array.isArray(data) ? data : [];
  }
  const paths = parts
    .map((part) => requiredText(part.storage_path))
    .filter((path): path is string => path !== null);
  if (paths.length > 0) {
    const { error } = await supabase.storage.from(workflowEvidenceBucket).remove(paths);
    if (error) throw new WorkflowDeviceError(502, "workflow_evidence_storage_failed");
  }
  const { error } = await supabase.from("workflow_evidence_parts")
    .delete()
    .eq("upload_id", uploadId);
  if (error) throw error;
}

function nextMissingChunk(received: number[], chunkCount: number): number {
  const values = new Set(received);
  for (let index = 0; index < chunkCount; index += 1) {
    if (!values.has(index)) return index;
  }
  return chunkCount;
}

function expectedWorkflowChunkSize(
  byteSize: number,
  chunkSize: number,
  chunkCount: number,
  chunkIndex: number,
): number {
  return chunkIndex < chunkCount - 1
    ? chunkSize
    : byteSize - chunkSize * (chunkCount - 1);
}

function workflowEvidenceUploadSessionResponse(
  item: Record<string, unknown>,
): Record<string, unknown> | null {
  const assetId = uuidValue(item.asset_id ?? item.id);
  const uploadId = uuidValue(item.upload_id);
  const uploadStatus = requiredText(item.upload_status);
  const byteSize = finiteInteger(item.byte_size);
  const sha256 = typeof item.sha256 === "string" ? item.sha256.trim().toLowerCase() : "";
  const chunkSize = finiteInteger(item.chunk_size);
  const chunkCount = finiteInteger(item.chunk_count);
  const received = Array.isArray(item.received_chunks)
    ? item.received_chunks.filter((value): value is number => Number.isInteger(value))
    : [];
  const nextChunkIndex = finiteInteger(item.next_chunk_index);
  if (
    !assetId || !uploadId || !uploadStatus || byteSize === null ||
    !/^[0-9a-f]{64}$/.test(sha256) || chunkSize === null || chunkCount === null ||
    nextChunkIndex === null
  ) return null;
  const result: Record<string, unknown> = {
    assetId,
    uploadId,
    uploadStatus,
    byteSize,
    sha256,
    chunkSize,
    chunkCount,
    receivedChunks: received,
    nextChunkIndex,
  };
  const durationSeconds = finiteInteger(item.duration_seconds);
  if (durationSeconds !== null && durationSeconds > 0) result.durationSeconds = durationSeconds;
  return result;
}

function workflowEvidenceResponseFromUpload(
  item: Record<string, unknown>,
): Record<string, unknown> | null {
  const base = workflowEvidenceUploadSessionResponse({
    ...item,
    received_chunks: [],
    next_chunk_index: item.chunk_count,
  });
  if (!base || base.uploadStatus !== "synced") return null;
  return {
    assetId: base.assetId,
    uploadStatus: base.uploadStatus,
    byteSize: base.byteSize,
    ...(base.durationSeconds === undefined ? {} : { durationSeconds: base.durationSeconds }),
    sha256: base.sha256,
  };
}

function workflowEvidenceCancelResponse(
  item: Record<string, unknown>,
): Record<string, unknown> | null {
  const assetId = uuidValue(item.asset_id ?? item.id);
  const uploadId = uuidValue(item.upload_id);
  const cancelledAt = requiredText(item.cancelled_at);
  if (!assetId || !uploadId || !cancelledAt) return null;
  return {
    assetId,
    uploadId,
    uploadStatus: "cancelled",
    receivedChunks: [],
    nextChunkIndex: 0,
  };
}

function validWorkflowMediaBytes(kind: unknown, bytes: Uint8Array): boolean {
  if (kind === "photo") {
    return bytes.length >= 4 && bytes[0] === 0xff && bytes[1] === 0xd8 &&
      bytes[bytes.length - 2] === 0xff && bytes[bytes.length - 1] === 0xd9;
  }
  if (kind === "video") {
    return bytes.length >= 12 && new TextDecoder().decode(bytes.slice(4, 8)) === "ftyp";
  }
  return false;
}

function matchingEvidence(
  item: Record<string, unknown>,
  command: WorkflowEvidenceUploadCommand,
): Record<string, unknown> {
  if (
    item.upload_status !== "synced" ||
    finiteInteger(item.byte_size) !== command.byteSize ||
    evidenceDuration(item, command) !== (command.durationSeconds ?? 0) ||
    item.sha256 !== command.sha256
  ) {
    throw new WorkflowDeviceError(409, "workflow_evidence_conflict");
  }
  return item;
}

function recoverableEvidence(
  item: Record<string, unknown>,
  command: WorkflowEvidenceUploadCommand,
): Record<string, unknown> {
  const status = item.upload_status;
  if (
    !uuidValue(item.id) ||
    !new Set(["uploading", "failed", "synced"]).has(String(status)) ||
    finiteInteger(item.byte_size) !== command.byteSize ||
    evidenceDuration(item, command) !== (command.durationSeconds ?? 0) ||
    item.sha256 !== command.sha256
  ) {
    throw new WorkflowDeviceError(409, "workflow_evidence_conflict");
  }
  return item;
}

async function uploadReservedEvidence(
  supabase: any,
  identity: DeviceSyncIdentity,
  filePath: string,
  reserved: Record<string, unknown>,
  command: WorkflowEvidenceUploadCommand,
): Promise<Record<string, unknown>> {
  const assetId = uuidValue(reserved.id);
  if (!assetId) {
    throw new WorkflowDeviceError(502, "workflow_evidence_invalid_response");
  }
  const { error: uploadError } = await supabase.storage
    .from(workflowEvidenceBucket)
    .upload(filePath, command.bytes, {
      contentType: command.contentType,
      cacheControl: "3600",
      upsert: true,
    });
  if (uploadError) {
    throw new WorkflowDeviceError(502, "workflow_evidence_storage_failed");
  }
  const { data, error } = await supabase.from("media_assets")
    .update({ upload_status: "synced", failure_reason: "" })
    .eq("organization_id", identity.organizationId)
    .eq("id", assetId)
    .eq("file_path", filePath)
    .select("id, upload_status, byte_size, duration_seconds, sha256")
    .single();
  if (error || !data) {
    throw error ?? new Error("workflow evidence completion failed");
  }
  return matchingEvidence(data, command);
}

function assignmentMetadata(value: unknown): Record<string, unknown> | null {
  const item = recordValue(value);
  const assignmentId = requiredText(item?.id);
  const workOrderId = requiredText(item?.work_order_id);
  const mode = requiredText(item?.mode);
  const status = requiredText(item?.status);
  const deliverySequence = finiteInteger(item?.delivery_sequence);
  const assignedAt = requiredText(item?.assigned_at);
  const workOrder = assignmentWorkOrder(item?.work_orders);
  if (
    !assignmentId || !workOrderId || !mode || !status ||
    deliverySequence === null || !assignedAt || !workOrder
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
    workOrder,
  };
}

function assignmentWorkOrder(value: unknown): Record<string, unknown> | null {
  const item = recordValue(value);
  const title = boundedText(item?.title, 240);
  const status = boundedText(item?.status, 40);
  const receivedAt = boundedText(item?.received_at, 100);
  const optional = {
    externalWorkOrderId: nullableBoundedText(
      item?.external_work_order_id,
      200,
    ),
    description: nullableBoundedText(item?.description, 2000),
    customerId: nullableBoundedText(item?.customer_id, 160),
    workOrderType: nullableBoundedText(item?.work_order_type, 160),
    assetId: nullableBoundedText(item?.asset_id, 160),
    assetCategory: nullableBoundedText(item?.asset_category, 160),
    assetBrand: nullableBoundedText(item?.asset_brand, 160),
    assetModel: nullableBoundedText(item?.asset_model, 160),
    priority: nullableBoundedText(item?.priority, 80),
    riskLevel: nullableBoundedText(item?.risk_level, 80),
    dueAt: nullableBoundedText(item?.due_at, 100),
  };
  if (
    !title || !status || !workOrderStatuses.has(status) || !receivedAt ||
    Object.values(optional).some((item) => item === undefined)
  ) return null;
  return { ...optional, title, status, receivedAt };
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
  const executionId = uuidValue(body?.executionId);
  const assignmentId = boundedText(body?.assignmentId, 200);
  const projectId = boundedText(body?.projectId, 200);
  const localTaskId = boundedText(body?.localTaskId, 200);
  const initialNodeId = boundedText(body?.initialNodeId, 160);
  const runtimeSnapshot = recordValue(body?.runtimeSnapshot);
  const idempotencyKey = boundedText(body?.idempotencyKey, 200);
  if (
    !executionId || !assignmentId || !projectId || !localTaskId ||
    !initialNodeId ||
    !runtimeSnapshot || !idempotencyKey
  ) return null;
  return {
    executionId,
    assignmentId,
    projectId,
    localTaskId,
    initialNodeId,
    runtimeSnapshot,
    idempotencyKey,
  };
}

function uuidValue(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.trim().toLowerCase();
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
      .test(normalized)
    ? normalized
    : null;
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

async function workflowEvidenceUploadCommand(
  body: Record<string, unknown> | null,
): Promise<WorkflowEvidenceUploadCommand | null> {
  const allowedKeys = new Set([
    "assignmentId",
    "executionId",
    "localEvidenceId",
    "nodeId",
    "evidenceKey",
    "kind",
    "contentType",
    "byteSize",
    "durationSeconds",
    "sha256",
    "dataBase64",
    "capturedAt",
  ]);
  if (!body || Object.keys(body).some((key) => !allowedKeys.has(key))) {
    return null;
  }
  const assignmentId = uuidValue(body?.assignmentId);
  const executionId = uuidValue(body?.executionId);
  const localEvidenceId = boundedIdentifier(body?.localEvidenceId);
  const nodeId = boundedIdentifier(body?.nodeId);
  const evidenceKey = boundedIdentifier(body?.evidenceKey);
  const kind = body?.kind;
  const contentType = body?.contentType;
  const byteSize = finiteInteger(body?.byteSize);
  const durationSeconds = body?.durationSeconds === undefined
    ? 0
    : finiteInteger(body.durationSeconds);
  const sha256 = typeof body?.sha256 === "string"
    ? body.sha256.trim().toLowerCase()
    : "";
  const dataBase64 = typeof body?.dataBase64 === "string"
    ? body.dataBase64
    : "";
  const capturedAt = boundedText(body?.capturedAt, 100);
  if (
    !assignmentId || !executionId || !localEvidenceId || !nodeId ||
    !evidenceKey ||
    !validWorkflowMedia(kind, contentType, durationSeconds) ||
    byteSize === null || byteSize < 1 ||
    byteSize > workflowMediaMaximumBytes(kind) ||
    !/^[0-9a-f]{64}$/.test(sha256) || !capturedAt ||
    !Number.isFinite(Date.parse(capturedAt)) ||
    dataBase64.length < 4 ||
    dataBase64.length >
      Math.ceil(workflowMediaMaximumBytes(kind) / 3) * 4 + 4 ||
    !/^[A-Za-z0-9+/]+={0,2}$/.test(dataBase64)
  ) return null;

  let bytes: Uint8Array;
  try {
    const decoded = atob(dataBase64);
    bytes = new Uint8Array(decoded.length);
    for (let index = 0; index < decoded.length; index += 1) {
      bytes[index] = decoded.charCodeAt(index);
    }
  } catch {
    return null;
  }
  if (bytes.length !== byteSize || await sha256Hex(bytes) !== sha256) {
    return null;
  }
  return {
    assignmentId,
    executionId,
    localEvidenceId,
    nodeId,
    evidenceKey,
    kind,
    contentType: contentType as WorkflowEvidenceUploadCommand["contentType"],
    byteSize,
    durationSeconds: durationSeconds ?? 0,
    sha256,
    bytes,
    capturedAt,
  };
}

function workflowEvidenceUploadSessionCommand(
  body: Record<string, unknown> | null,
): WorkflowEvidenceUploadSessionCommand | null {
  const allowedKeys = new Set([
    "assignmentId",
    "executionId",
    "localEvidenceId",
    "nodeId",
    "evidenceKey",
    "kind",
    "contentType",
    "byteSize",
    "durationSeconds",
    "sha256",
    "capturedAt",
    "chunkSize",
    "chunkCount",
  ]);
  if (!body || Object.keys(body).some((key) => !allowedKeys.has(key)) ||
    Object.keys(body).length !== allowedKeys.size) return null;
  const assignmentId = uuidValue(body.assignmentId);
  const executionId = uuidValue(body.executionId);
  const localEvidenceId = boundedIdentifier(body.localEvidenceId);
  const nodeId = boundedIdentifier(body.nodeId);
  const evidenceKey = boundedIdentifier(body.evidenceKey);
  const kind = body.kind;
  const contentType = body.contentType;
  const byteSize = finiteInteger(body.byteSize);
  const durationSeconds = finiteInteger(body.durationSeconds);
  const chunkSize = finiteInteger(body.chunkSize);
  const chunkCount = finiteInteger(body.chunkCount);
  const sha256 = typeof body.sha256 === "string" ? body.sha256.trim().toLowerCase() : "";
  const capturedAt = boundedText(body.capturedAt, 100);
  if (
    !assignmentId || !executionId || !localEvidenceId || !nodeId || !evidenceKey ||
    !validWorkflowMedia(kind, contentType, durationSeconds) || byteSize === null ||
    byteSize < 1 || byteSize > workflowMediaMaximumBytes(kind) ||
    !/^[0-9a-f]{64}$/.test(sha256) || !capturedAt || !Number.isFinite(Date.parse(capturedAt)) ||
    chunkSize === null || chunkSize < 1 || chunkSize > maxWorkflowEvidenceChunkBytes ||
    chunkCount === null || chunkCount < 1 || chunkCount > maxWorkflowEvidenceChunks ||
    chunkCount !== Math.ceil(byteSize / chunkSize)
  ) return null;
  return {
    assignmentId,
    executionId,
    localEvidenceId,
    nodeId,
    evidenceKey,
    kind: kind as WorkflowEvidenceUploadSessionCommand["kind"],
    contentType: contentType as WorkflowEvidenceUploadSessionCommand["contentType"],
    byteSize,
    durationSeconds: durationSeconds ?? undefined,
    sha256,
    capturedAt,
    chunkSize,
    chunkCount,
  };
}

async function workflowEvidenceChunkCommand(
  body: Record<string, unknown> | null,
): Promise<WorkflowEvidenceChunkCommand | null> {
  const allowedKeys = new Set([
    "uploadId",
    "chunkIndex",
    "chunkCount",
    "chunkByteSize",
    "chunkSha256",
    "dataBase64",
  ]);
  if (!body || Object.keys(body).some((key) => !allowedKeys.has(key)) ||
    Object.keys(body).length !== allowedKeys.size) return null;
  const uploadId = uuidValue(body.uploadId);
  const chunkIndex = finiteInteger(body.chunkIndex);
  const chunkCount = finiteInteger(body.chunkCount);
  const chunkByteSize = finiteInteger(body.chunkByteSize);
  const chunkSha256 = typeof body.chunkSha256 === "string"
    ? body.chunkSha256.trim().toLowerCase()
    : "";
  const dataBase64 = typeof body.dataBase64 === "string" ? body.dataBase64 : "";
  if (
    !uploadId || chunkIndex === null || chunkIndex < 0 || chunkCount === null ||
    chunkCount < 1 || chunkCount > maxWorkflowEvidenceChunks || chunkIndex >= chunkCount ||
    chunkByteSize === null || chunkByteSize < 1 || chunkByteSize > maxWorkflowEvidenceChunkBytes ||
    !/^[0-9a-f]{64}$/.test(chunkSha256) ||
    dataBase64.length < 4 || dataBase64.length > Math.ceil(maxWorkflowEvidenceChunkBytes / 3) * 4 + 4 ||
    !/^[A-Za-z0-9+/]+={0,2}$/.test(dataBase64)
  ) return null;
  let bytes: Uint8Array;
  try {
    const decoded = atob(dataBase64);
    bytes = new Uint8Array(decoded.length);
    for (let index = 0; index < decoded.length; index += 1) bytes[index] = decoded.charCodeAt(index);
  } catch {
    return null;
  }
  if (bytes.length !== chunkByteSize || await sha256Hex(bytes) !== chunkSha256) return null;
  return { uploadId, chunkIndex, chunkCount, chunkByteSize, chunkSha256, bytes };
}

function workflowEvidenceCompleteCommand(
  body: Record<string, unknown> | null,
): WorkflowEvidenceCompleteCommand | null {
  const uploadId = uuidValue(body?.uploadId);
  const chunkCount = finiteInteger(body?.chunkCount);
  const sha256 = typeof body?.sha256 === "string" ? body.sha256.trim().toLowerCase() : "";
  if (
    !uploadId || chunkCount === null || chunkCount < 1 || chunkCount > maxWorkflowEvidenceChunks ||
    !/^[0-9a-f]{64}$/.test(sha256)
  ) return null;
  return { uploadId, chunkCount, sha256 };
}

function workflowEvidenceCancelCommand(
  body: Record<string, unknown> | null,
): WorkflowEvidenceCancelCommand | null {
  if (!body || Object.keys(body).length !== 2) return null;
  const uploadId = uuidValue(body.uploadId);
  const assetId = uuidValue(body.assetId);
  return uploadId && assetId ? { uploadId, assetId } : null;
}

function workflowEvidenceResponse(
  value: unknown,
  command: WorkflowEvidenceUploadCommand,
): Record<string, unknown> | null {
  const item = recordValue(value);
  const assetId = uuidValue(item?.id);
  const uploadStatus = item?.upload_status;
  const byteSize = finiteInteger(item?.byte_size);
  const durationSeconds = finiteInteger(item?.duration_seconds) ?? 0;
  const sha256 = typeof item?.sha256 === "string"
    ? item.sha256.trim().toLowerCase()
    : "";
  if (
    !assetId || uploadStatus !== "synced" ||
    byteSize !== command.byteSize || sha256 !== command.sha256 ||
    (command.kind === "video" &&
      durationSeconds !== command.durationSeconds)
  ) return null;
  return command.kind === "video"
    ? { assetId, uploadStatus, byteSize, durationSeconds, sha256 }
    : { assetId, uploadStatus, byteSize, sha256 };
}

function validWorkflowMedia(
  kind: unknown,
  contentType: unknown,
  durationSeconds: number | null,
): kind is WorkflowEvidenceUploadCommand["kind"] {
  if (kind === "photo") {
    return contentType === "image/jpeg" && durationSeconds === 0;
  }
  return kind === "video" && contentType === "video/mp4" &&
    durationSeconds !== null && durationSeconds >= 1 &&
    durationSeconds <= maxWorkflowVideoDurationSeconds;
}

function workflowMediaMaximumBytes(kind: unknown): number {
  return kind === "video" ? maxWorkflowVideoBytes : maxWorkflowPhotoBytes;
}

function evidenceDuration(
  item: Record<string, unknown>,
  command: Pick<WorkflowEvidenceUploadCommand, "kind" | "durationSeconds">,
): number | null {
  const duration = finiteInteger(item.duration_seconds);
  return duration ?? (command.kind === "photo" ? 0 : null);
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const copy = new Uint8Array(bytes.length);
  copy.set(bytes);
  const digest = new Uint8Array(
    await crypto.subtle.digest("SHA-256", copy.buffer),
  );
  return Array.from(digest)
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
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
  if (message.includes("workflow assignment completion requires a completed execution")) {
    return new WorkflowDeviceError(409, "workflow_assignment_completion_requires_execution");
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

function boundedIdentifier(value: unknown): string | null {
  const text = boundedText(value, 160);
  return text && /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$/.test(text) ? text : null;
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
