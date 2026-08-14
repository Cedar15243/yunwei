import {
  compileKnowledgeVersion,
  compileSkillVersion,
  normalizeKnowledgeDraft,
  normalizeSkillDraft,
  type KnowledgeDraft,
  type KnowledgeStatus,
  type SkillDraft,
  type SkillStatus,
} from "./skill-knowledge-domain.ts";
import {
  parseKnowledgeTextAttachment,
  prepareKnowledgeAttachmentUpload,
  sha256Hex,
  type PreparedKnowledgeAttachment,
} from "./knowledge-attachment-domain.ts";
import {
  KnowledgeDocumentParserClientError,
  type KnowledgeDocumentParser,
} from "./knowledge-document-parser-client.ts";

export type KnowledgeAttachmentUploadCommand = {
  fileName: string;
  contentType: string;
  bytes: Uint8Array;
  claimedSha256: string;
  idempotencyKey: string;
};

export type KnowledgeAttachmentRetryCommand = ReasonedIdempotentCommand;

export type KnowledgeAttachmentOperationResult = {
  version: Record<string, unknown>;
  attachment: Record<string, unknown>;
};

export class SkillKnowledgeManagementError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    public readonly recoverableAction?: string,
  ) {
    super(code);
    this.name = "SkillKnowledgeManagementError";
  }
}

export type SkillKnowledgeManagementIdentity = {
  id: string;
  organizationId: string;
  role: "super_admin" | "ops_admin" | "field_engineer" | "remote_expert" | "viewer";
  displayName: string;
};

export type CreateSkillDraftCommand = SkillDraft & {
  skillKey: string;
  version: string;
  reason: string;
  idempotencyKey: string;
};

export type SkillTransitionCommand = {
  expectedStatus: SkillStatus;
  newStatus: SkillStatus;
  testResult: Record<string, unknown>;
  reason: string;
  idempotencyKey: string;
};

export type ContentScopeType =
  | "organization"
  | "project"
  | "profile"
  | "device"
  | "skill_version";

export type ContentAssignmentCommand = {
  scopeType: ContentScopeType;
  scopeId: string | null;
  activeFrom: string | null;
  expiresAt: string | null;
  reason: string;
  idempotencyKey: string;
};

export type ReasonedIdempotentCommand = {
  reason: string;
  idempotencyKey: string;
};

export type CreateKnowledgeDraftCommand = KnowledgeDraft & {
  knowledgeKey: string;
  version: number;
  reason: string;
  idempotencyKey: string;
};

export type CreateKnowledgeCaseDraftCommand = {
  knowledgeKey: string;
  version: number;
  title: string;
  sensitivity: KnowledgeDraft["sensitivity"];
  license: string;
  knowledgeScopes: string[];
  reason: string;
  idempotencyKey: string;
};

export type CreateKnowledgeCaseDraftResult =
  | { status: "created"; item: Record<string, unknown> }
  | { status: "not_found" | "task_not_completed" | "completion_not_confirmed" | "evidence_pending" };

export type KnowledgeTransitionCommand = {
  expectedStatus: KnowledgeStatus;
  newStatus: KnowledgeStatus;
  processingError: string;
  reason: string;
  idempotencyKey: string;
};

export type SkillKnowledgeManagementGateway = {
  authenticate(token: string): Promise<SkillKnowledgeManagementIdentity | null>;
  listSkills(identity: SkillKnowledgeManagementIdentity): Promise<Array<Record<string, unknown>>>;
  createSkillDraft(
    identity: SkillKnowledgeManagementIdentity,
    command: CreateSkillDraftCommand,
  ): Promise<Record<string, unknown> | null>;
  listSkillVersions(
    identity: SkillKnowledgeManagementIdentity,
    skillId: string,
  ): Promise<Array<Record<string, unknown>> | null>;
  transitionSkillVersion(
    identity: SkillKnowledgeManagementIdentity,
    versionId: string,
    command: SkillTransitionCommand,
  ): Promise<Record<string, unknown> | null>;
  assignSkillVersion(
    identity: SkillKnowledgeManagementIdentity,
    versionId: string,
    command: ContentAssignmentCommand,
  ): Promise<Record<string, unknown> | null>;
  revokeSkillAssignment(
    identity: SkillKnowledgeManagementIdentity,
    assignmentId: string,
    command: ReasonedIdempotentCommand,
  ): Promise<Record<string, unknown> | null>;
  listKnowledge(identity: SkillKnowledgeManagementIdentity): Promise<Array<Record<string, unknown>>>;
  createKnowledgeDraft(
    identity: SkillKnowledgeManagementIdentity,
    command: CreateKnowledgeDraftCommand,
  ): Promise<Record<string, unknown> | null>;
  createKnowledgeCaseDraft(
    identity: SkillKnowledgeManagementIdentity,
    taskId: string,
    command: CreateKnowledgeCaseDraftCommand,
  ): Promise<CreateKnowledgeCaseDraftResult>;
  listKnowledgeVersions(
    identity: SkillKnowledgeManagementIdentity,
    knowledgeId: string,
  ): Promise<Array<Record<string, unknown>> | null>;
  uploadKnowledgeAttachment(
    identity: SkillKnowledgeManagementIdentity,
    versionId: string,
    command: KnowledgeAttachmentUploadCommand,
  ): Promise<KnowledgeAttachmentOperationResult | null>;
  retryKnowledgeAttachment(
    identity: SkillKnowledgeManagementIdentity,
    attachmentId: string,
    command: KnowledgeAttachmentRetryCommand,
  ): Promise<KnowledgeAttachmentOperationResult | null>;
  transitionKnowledgeVersion(
    identity: SkillKnowledgeManagementIdentity,
    versionId: string,
    command: KnowledgeTransitionCommand,
  ): Promise<Record<string, unknown> | null>;
  grantKnowledgeVersion(
    identity: SkillKnowledgeManagementIdentity,
    versionId: string,
    command: ContentAssignmentCommand,
  ): Promise<Record<string, unknown> | null>;
  revokeKnowledgeGrant(
    identity: SkillKnowledgeManagementIdentity,
    grantId: string,
    command: ReasonedIdempotentCommand,
  ): Promise<Record<string, unknown> | null>;
};

const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Content-Type": "application/json; charset=utf-8",
};
const identifierPattern = /^[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}$/;
const semverPattern = /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/;
const skillStatuses = new Set<SkillStatus>([
  "draft",
  "review_pending",
  "published",
  "deprecated",
  "archived",
]);
const knowledgeStatuses = new Set<KnowledgeStatus>([
  "uploaded",
  "scanning",
  "parsing",
  "parsed",
  "review_pending",
  "published",
  "expired",
  "deprecated",
  "archived",
]);
const scopeTypes = new Set<ContentScopeType>([
  "organization",
  "project",
  "profile",
  "device",
  "skill_version",
]);
const knowledgeSensitivities = new Set<KnowledgeDraft["sensitivity"]>([
  "public",
  "internal",
  "confidential",
  "restricted",
]);
const knowledgeCaseDraftFields = new Set([
  "knowledgeKey",
  "version",
  "title",
  "sensitivity",
  "license",
  "knowledgeScopes",
  "reason",
  "idempotencyKey",
  "confirmation",
]);
const knowledgeAttachmentBucket = "ops-knowledge-attachments";
const knowledgeAttachmentInternalSelect =
  "id, knowledge_version_id, status, original_file_name, content_type, byte_size, file_sha256, extracted_content_sha256, storage_bucket, storage_path, processing_error, is_current, created_at, updated_at";

export function isSkillKnowledgeManagementPath(path: string): boolean {
  const normalized = path
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/";
  return normalized === "/management/skills" ||
    normalized.startsWith("/management/skills/") ||
    normalized.startsWith("/management/skill-versions/") ||
    normalized.startsWith("/management/skill-assignments/") ||
    normalized === "/management/knowledge" ||
    normalized.startsWith("/management/knowledge/") ||
    normalized.startsWith("/management/knowledge-versions/") ||
    normalized.startsWith("/management/knowledge-attachments/") ||
    normalized.startsWith("/management/knowledge-grants/") ||
    normalized.startsWith("/management/tasks/");
}

export async function routeSkillKnowledgeManagement(
  request: Request,
  gateway: SkillKnowledgeManagementGateway,
): Promise<Response> {
  const token = bearerToken(request);
  if (!token) return response({ ok: false, error: "unauthorized" }, 401);
  const identity = await gateway.authenticate(token);
  if (!identity) return response({ ok: false, error: "unauthorized" }, 401);
  if (!canManage(identity)) return response({ ok: false, error: "forbidden" }, 403);

  const path = routePath(request);
  if (request.method === "GET" && path === "/management/skills") {
    return response({ items: await gateway.listSkills(identity) });
  }
  if (request.method === "POST" && path === "/management/skills") {
    const body = await requestObject(request);
    const command = skillDraftCommand(body);
    if (!command) return response({ ok: false, error: "invalid_request" }, 400);
    const created = await gateway.createSkillDraft(identity, command);
    return created ? response(created, 201) : response({ ok: false, error: "not_found" }, 404);
  }
  const skillVersions = path.match(/^\/management\/skills\/([^/]+)\/versions$/);
  if (request.method === "GET" && skillVersions) {
    const items = await gateway.listSkillVersions(identity, skillVersions[1]);
    return items ? response({ items }) : response({ ok: false, error: "not_found" }, 404);
  }
  const skillTransition = path.match(/^\/management\/skill-versions\/([^/]+)\/transition$/);
  if (request.method === "POST" && skillTransition) {
    const body = await requestObject(request);
    const command = skillTransitionCommand(body);
    if (!command) return response({ ok: false, error: "invalid_request" }, 400);
    if (body?.confirmation !== skillTransitionConfirmation(command.newStatus)) {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const transitioned = await gateway.transitionSkillVersion(identity, skillTransition[1], command);
    return transitioned ? response(transitioned) : response({ ok: false, error: "not_found" }, 404);
  }
  const skillAssignments = path.match(/^\/management\/skill-versions\/([^/]+)\/assignments$/);
  if (request.method === "POST" && skillAssignments) {
    const body = await requestObject(request);
    const command = assignmentCommand(body, false);
    if (!command) return response({ ok: false, error: "invalid_request" }, 400);
    if (body?.confirmation !== "ASSIGN_SKILL") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const assigned = await gateway.assignSkillVersion(identity, skillAssignments[1], command);
    return assigned ? response(assigned, 201) : response({ ok: false, error: "not_found" }, 404);
  }
  const revokeAssignment = path.match(/^\/management\/skill-assignments\/([^/]+)\/revoke$/);
  if (request.method === "POST" && revokeAssignment) {
    const body = await requestObject(request);
    const command = reasonedCommand(body);
    if (!command) return response({ ok: false, error: "invalid_request" }, 400);
    if (body?.confirmation !== "REVOKE_SKILL_ASSIGNMENT") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const revoked = await gateway.revokeSkillAssignment(identity, revokeAssignment[1], command);
    return revoked ? response(revoked) : response({ ok: false, error: "not_found" }, 404);
  }

  if (request.method === "GET" && path === "/management/knowledge") {
    return response({ items: await gateway.listKnowledge(identity) });
  }
  if (request.method === "POST" && path === "/management/knowledge") {
    const body = await requestObject(request);
    const command = knowledgeDraftCommand(body);
    if (!command) return response({ ok: false, error: "invalid_request" }, 400);
    const created = await gateway.createKnowledgeDraft(identity, command);
    return created ? response(created, 201) : response({ ok: false, error: "not_found" }, 404);
  }
  const taskKnowledgeDraft = path.match(/^\/management\/tasks\/([^/]+)\/knowledge-drafts$/);
  if (request.method === "POST" && taskKnowledgeDraft) {
    const body = await requestObject(request);
    const command = knowledgeCaseDraftCommand(body);
    if (!command) return response({ ok: false, error: "invalid_request" }, 400);
    if (body?.confirmation !== "CREATE_KNOWLEDGE_CASE_DRAFT") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const result = await gateway.createKnowledgeCaseDraft(
      identity,
      taskKnowledgeDraft[1],
      command,
    );
    if (result.status === "created") return response(result.item, 201);
    if (result.status === "not_found") {
      return response({ ok: false, error: "not_found" }, 404);
    }
    if (result.status === "evidence_pending") {
      return response({
        ok: false,
        error: "task_evidence_not_synced",
        recoverableAction: "retry_media_sync",
      }, 409);
    }
    return response({
      ok: false,
      error: result.status === "task_not_completed"
        ? "task_not_completed"
        : "task_completion_not_confirmed",
    }, 409);
  }
  const knowledgeVersions = path.match(/^\/management\/knowledge\/([^/]+)\/versions$/);
  if (request.method === "GET" && knowledgeVersions) {
    const items = await gateway.listKnowledgeVersions(identity, knowledgeVersions[1]);
    return items ? response({ items }) : response({ ok: false, error: "not_found" }, 404);
  }
  const knowledgeAttachmentUpload = path.match(/^\/management\/knowledge-versions\/([^/]+)\/attachments$/);
  if (request.method === "POST" && knowledgeAttachmentUpload) {
    const upload = await knowledgeAttachmentUploadCommand(request);
    if (!upload) return response({ ok: false, error: "invalid_request" }, 400);
    if (upload.confirmation !== "UPLOAD_KNOWLEDGE_ATTACHMENT") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    let result: KnowledgeAttachmentOperationResult | null;
    try {
      result = await gateway.uploadKnowledgeAttachment(
        identity,
        knowledgeAttachmentUpload[1],
        upload.command,
      );
    } catch (cause) {
      const controlled = skillKnowledgeManagementErrorResponse(cause);
      if (controlled) return controlled;
      throw cause;
    }
    if (!result) return response({ ok: false, error: "not_found" }, 404);
    if (result.attachment.status === "processing_unavailable") {
      return response({ ...result, recoverableAction: "retry_attachment_processing" }, 202);
    }
    return response(result, 201);
  }
  const knowledgeAttachmentRetry = path.match(/^\/management\/knowledge-attachments\/([^/]+)\/retry$/);
  if (request.method === "POST" && knowledgeAttachmentRetry) {
    const body = await requestObject(request);
    const command = knowledgeAttachmentRetryCommand(body);
    if (!command) return response({ ok: false, error: "invalid_request" }, 400);
    if (body?.confirmation !== "RETRY_KNOWLEDGE_ATTACHMENT") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    let result: KnowledgeAttachmentOperationResult | null;
    try {
      result = await gateway.retryKnowledgeAttachment(
        identity,
        knowledgeAttachmentRetry[1],
        command,
      );
    } catch (cause) {
      const controlled = skillKnowledgeManagementErrorResponse(cause);
      if (controlled) return controlled;
      throw cause;
    }
    return result ? response(result) : response({ ok: false, error: "not_found" }, 404);
  }
  const knowledgeTransition = path.match(/^\/management\/knowledge-versions\/([^/]+)\/transition$/);
  if (request.method === "POST" && knowledgeTransition) {
    const body = await requestObject(request);
    const command = knowledgeTransitionCommand(body);
    if (!command) return response({ ok: false, error: "invalid_request" }, 400);
    if (body?.confirmation !== knowledgeTransitionConfirmation(command.newStatus)) {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const transitioned = await gateway.transitionKnowledgeVersion(
      identity,
      knowledgeTransition[1],
      command,
    );
    return transitioned ? response(transitioned) : response({ ok: false, error: "not_found" }, 404);
  }
  const knowledgeGrants = path.match(/^\/management\/knowledge-versions\/([^/]+)\/grants$/);
  if (request.method === "POST" && knowledgeGrants) {
    const body = await requestObject(request);
    const command = assignmentCommand(body, true);
    if (!command) return response({ ok: false, error: "invalid_request" }, 400);
    if (body?.confirmation !== "GRANT_KNOWLEDGE") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const granted = await gateway.grantKnowledgeVersion(identity, knowledgeGrants[1], command);
    return granted ? response(granted, 201) : response({ ok: false, error: "not_found" }, 404);
  }
  const revokeGrant = path.match(/^\/management\/knowledge-grants\/([^/]+)\/revoke$/);
  if (request.method === "POST" && revokeGrant) {
    const body = await requestObject(request);
    const command = reasonedCommand(body);
    if (!command) return response({ ok: false, error: "invalid_request" }, 400);
    if (body?.confirmation !== "REVOKE_KNOWLEDGE_GRANT") {
      return response({ ok: false, error: "confirmation_required" }, 400);
    }
    const revoked = await gateway.revokeKnowledgeGrant(identity, revokeGrant[1], command);
    return revoked ? response(revoked) : response({ ok: false, error: "not_found" }, 404);
  }
  return response({ ok: false, error: "not_found" }, 404);
}

export function createSkillKnowledgeManagementGateway(
  supabase: any,
  documentParser: KnowledgeDocumentParser | null = null,
): SkillKnowledgeManagementGateway {
  return {
    async authenticate(token) {
      const { data, error } = await supabase.auth.getUser(token);
      if (error || !data?.user?.id) return null;
      const { data: profile, error: profileError } = await supabase
        .from("ops_profiles")
        .select("id, organization_id, role, display_name, active, status")
        .eq("id", data.user.id)
        .eq("active", true)
        .eq("status", "active")
        .maybeSingle();
      if (profileError || !profile) return null;
      return {
        id: String(profile.id),
        organizationId: String(profile.organization_id),
        role: profile.role,
        displayName: String(profile.display_name ?? ""),
      } as SkillKnowledgeManagementIdentity;
    },
    async listSkills(identity) {
      const { data, error } = await supabase
        .from("skill_definitions")
        .select(
          "id, skill_key, name, description, created_at, updated_at, skill_versions(id, skill_definition_id, version, status, rules, test_cases, test_result, content_sha256, submitted_at, reviewed_at, published_at, lifecycle_reason, created_at, updated_at, skill_assignments(id, scope_type, project_id, profile_id, device_id, status, active_from, expires_at, assigned_at, revoke_reason))",
        )
        .eq("organization_id", identity.organizationId)
        .order("updated_at", { ascending: false });
      if (error) throw error;
      return data ?? [];
    },
    async createSkillDraft(identity, command) {
      const { data, error } = await supabase.rpc("create_skill_draft", {
        draft_skill_key: command.skillKey,
        draft_name: command.name,
        draft_description: command.description,
        draft_version: command.version,
        draft_rules: command.rules,
        draft_test_cases: command.testCases,
        actor_id: identity.id,
        command_reason: command.reason,
        idempotency_key: command.idempotencyKey,
      });
      if (error) throw error;
      return singleRecord(data);
    },
    async listSkillVersions(identity, skillId) {
      const { data: definition, error: definitionError } = await supabase
        .from("skill_definitions")
        .select("id")
        .eq("organization_id", identity.organizationId)
        .eq("id", skillId)
        .maybeSingle();
      if (definitionError) throw definitionError;
      if (!definition) return null;
      const { data, error } = await supabase
        .from("skill_versions")
        .select(
          "id, skill_definition_id, version, status, rules, test_cases, test_result, content_sha256, submitted_by, submitted_at, reviewed_by, reviewed_at, published_by, published_at, lifecycle_reason, created_at, updated_at, skill_assignments(id, scope_type, project_id, profile_id, device_id, status, active_from, expires_at, assigned_at, revoke_reason)",
        )
        .eq("organization_id", identity.organizationId)
        .eq("skill_definition_id", skillId)
        .order("created_at", { ascending: false });
      if (error) throw error;
      return data ?? [];
    },
    async transitionSkillVersion(identity, versionId, command) {
      let contentSha256 = "";
      if (command.newStatus === "published") {
        const { data: version, error: versionError } = await supabase
          .from("skill_versions")
          .select(
            "id, version, status, rules, test_cases, skill_definitions!inner(skill_key, name, description)",
          )
          .eq("organization_id", identity.organizationId)
          .eq("id", versionId)
          .maybeSingle();
        if (versionError) throw versionError;
        if (!version) return null;
        const definition = nestedRecord(version.skill_definitions);
        const compiled = await compileSkillVersion({
          skillId: definition.skill_key,
          version: version.version,
          name: definition.name,
          description: definition.description,
          rules: version.rules,
          testCases: version.test_cases,
        });
        contentSha256 = compiled.contentSha256;
      }
      const { data, error } = await supabase.rpc("transition_skill_version", {
        target_skill_version_id: versionId,
        expected_status: command.expectedStatus,
        new_status: command.newStatus,
        published_content_sha256: contentSha256,
        published_test_result: command.testResult,
        actor_id: identity.id,
        command_reason: command.reason,
        idempotency_key: command.idempotencyKey,
      });
      if (error) throw error;
      return singleRecord(data);
    },
    async assignSkillVersion(identity, versionId, command) {
      const { data, error } = await supabase.rpc("assign_skill_version", {
        target_skill_version_id: versionId,
        assignment_scope: command.scopeType,
        scope_id: command.scopeId,
        assigned_active_from: command.activeFrom,
        assigned_expires_at: command.expiresAt,
        actor_id: identity.id,
        command_reason: command.reason,
        idempotency_key: command.idempotencyKey,
      });
      if (error) throw error;
      return singleRecord(data);
    },
    async revokeSkillAssignment(identity, assignmentId, command) {
      const { data, error } = await supabase.rpc("revoke_skill_assignment", {
        target_assignment_id: assignmentId,
        actor_id: identity.id,
        command_reason: command.reason,
        idempotency_key: command.idempotencyKey,
      });
      if (error) throw error;
      return singleRecord(data);
    },
    async listKnowledge(identity) {
      const { data, error } = await supabase
        .from("knowledge_entries")
        .select(
          "id, knowledge_key, title, created_at, updated_at, knowledge_versions(id, knowledge_entry_id, version, status, title, summary, source_type, source_reference, language, sensitivity, license, project_ids, device_models, skill_ids, knowledge_scopes, content, content_sha256, processing_error, submitted_at, reviewed_at, published_at, valid_from, expires_at, lifecycle_reason, created_at, updated_at, knowledge_grants(id, scope_type, project_id, profile_id, device_id, skill_version_id, status, active_from, expires_at, granted_at, revoke_reason))",
        )
        .eq("organization_id", identity.organizationId)
        .order("updated_at", { ascending: false });
      if (error) throw error;
      return data ?? [];
    },
    async createKnowledgeDraft(identity, command) {
      const { data, error } = await supabase.rpc("create_knowledge_draft", {
        draft_knowledge_key: command.knowledgeKey,
        draft_title: command.title,
        draft_summary: command.summary,
        draft_source_type: command.sourceType,
        draft_source_reference: command.sourceReference,
        draft_language: command.language,
        draft_sensitivity: command.sensitivity,
        draft_license: command.license,
        draft_project_ids: command.projectIds,
        draft_device_models: command.deviceModels,
        draft_skill_ids: command.skillIds,
        draft_knowledge_scopes: command.knowledgeScopes,
        draft_content: command.content,
        draft_version: command.version,
        actor_id: identity.id,
        command_reason: command.reason,
        idempotency_key: command.idempotencyKey,
      });
      if (error) throw error;
      return singleRecord(data);
    },
    async createKnowledgeCaseDraft(identity, taskId, command) {
      const { data, error } = await supabase.rpc("create_knowledge_case_draft_from_task", {
        target_task_id: taskId,
        draft_knowledge_key: command.knowledgeKey,
        draft_version: command.version,
        draft_title: command.title,
        draft_sensitivity: command.sensitivity,
        draft_license: command.license,
        draft_knowledge_scopes: command.knowledgeScopes,
        actor_id: identity.id,
        command_reason: command.reason,
        idempotency_key: command.idempotencyKey,
      });
      if (error) throw error;
      return knowledgeCaseDraftResult(data);
    },
    async listKnowledgeVersions(identity, knowledgeId) {
      const { data: entry, error: entryError } = await supabase
        .from("knowledge_entries")
        .select("id")
        .eq("organization_id", identity.organizationId)
        .eq("id", knowledgeId)
        .maybeSingle();
      if (entryError) throw entryError;
      if (!entry) return null;
      const { data, error } = await supabase
        .from("knowledge_versions")
        .select(
          `id, knowledge_entry_id, version, status, title, summary, source_type, source_reference, language, sensitivity, license, project_ids, device_models, skill_ids, knowledge_scopes, content, content_sha256, processing_error, submitted_by, submitted_at, reviewed_by, reviewed_at, published_by, published_at, valid_from, expires_at, lifecycle_reason, created_at, updated_at, knowledge_grants(id, scope_type, project_id, profile_id, device_id, skill_version_id, status, active_from, expires_at, granted_at, revoke_reason), knowledge_version_attachments(${knowledgeAttachmentInternalSelect})`,
        )
        .eq("organization_id", identity.organizationId)
        .eq("knowledge_entry_id", knowledgeId)
        .order("version", { ascending: false });
      if (error) throw error;
      return await knowledgeVersionsWithAttachments(supabase, data ?? []);
    },
    async uploadKnowledgeAttachment(identity, versionId, command) {
      let prepared: PreparedKnowledgeAttachment;
      try {
        prepared = await prepareKnowledgeAttachmentUpload({
          fileName: command.fileName,
          contentType: command.contentType,
          bytes: command.bytes,
          claimedSha256: command.claimedSha256,
        });
      } catch (cause) {
        throw knowledgeAttachmentDomainError(cause);
      }
      const { data: existing, error: existingError } = await supabase
        .from("knowledge_version_attachments")
        .select(knowledgeAttachmentInternalSelect)
        .eq("organization_id", identity.organizationId)
        .eq("idempotency_key", command.idempotencyKey)
        .maybeSingle();
      if (existingError) throw existingError;
      if (existing) {
        if (String(existing.knowledge_version_id) !== versionId ||
          String(existing.file_sha256) !== prepared.fileSha256 ||
          String(existing.original_file_name) !== prepared.originalFileName) {
          throw new SkillKnowledgeManagementError(
            409,
            "knowledge_attachment_idempotency_conflict",
            "use_new_idempotency_key",
          );
        }
        return await knowledgeAttachmentOperationResult(
          supabase,
          identity.organizationId,
          versionId,
          String(existing.id),
        );
      }

      const { data: version, error: versionError } = await supabase
        .from("knowledge_versions")
        .select("id, status, source_type, source_reference")
        .eq("organization_id", identity.organizationId)
        .eq("id", versionId)
        .maybeSingle();
      if (versionError) throw versionError;
      if (!version) return null;
      if (version.source_type !== "uploaded_file" || !["uploaded", "parsed"].includes(String(version.status))) {
        throw new SkillKnowledgeManagementError(
          409,
          "knowledge_version_does_not_accept_attachment",
          "create_uploaded_file_draft",
        );
      }

      const attachmentId = crypto.randomUUID();
      const storagePath = knowledgeAttachmentStoragePath(
        identity.organizationId,
        versionId,
        attachmentId,
        prepared,
      );
      const attachmentRecord = {
        id: attachmentId,
        organization_id: identity.organizationId,
        knowledge_version_id: versionId,
        status: "uploaded",
        original_file_name: prepared.originalFileName,
        content_type: prepared.contentType,
        byte_size: prepared.byteSize,
        file_sha256: prepared.fileSha256,
        extracted_content_sha256: "",
        storage_bucket: knowledgeAttachmentBucket,
        storage_path: storagePath,
        processing_error: "",
        is_current: false,
        idempotency_key: command.idempotencyKey,
        created_by: identity.id,
      };
      const { data: inserted, error: insertError } = await supabase
        .from("knowledge_version_attachments")
        .insert(attachmentRecord)
        .select(knowledgeAttachmentInternalSelect)
        .single();
      if (insertError) throw insertError;

      const { error: uploadError } = await supabase.storage
        .from(knowledgeAttachmentBucket)
        .upload(storagePath, command.bytes, {
          contentType: prepared.contentType,
          upsert: false,
          cacheControl: "3600",
        });
      if (uploadError) {
        await completeKnowledgeAttachmentProcessing(supabase, identity, command.idempotencyKey, {
          attachmentId: String(inserted.id),
          expectedStatus: "uploaded",
          newStatus: "failed",
          content: "",
          contentSha256: "",
          processingError: "知识附件写入私有存储失败",
          reason: "知识附件私有存储失败",
        });
        throw new SkillKnowledgeManagementError(
          502,
          "knowledge_attachment_storage_failed",
          "retry_upload",
        );
      }

      if (!prepared.processorAvailable && !documentParser) {
        await completeKnowledgeAttachmentProcessing(supabase, identity, command.idempotencyKey, {
          attachmentId: String(inserted.id),
          expectedStatus: "uploaded",
          newStatus: "processing_unavailable",
          content: "",
          contentSha256: "",
          processingError: "当前部署未配置 PDF/DOCX 真实解析器，请替换为 TXT/Markdown 或配置受控处理器",
          reason: "知识附件处理器未配置",
        });
      } else {
        let parsed: { content: string; contentSha256: string };
        try {
          parsed = prepared.processorAvailable
            ? await parseKnowledgeTextAttachment(prepared, command.bytes)
            : await documentParser!.parse({
              fileName: prepared.originalFileName,
              contentType: prepared.contentType,
              bytes: command.bytes,
              fileSha256: prepared.fileSha256,
            });
        } catch (cause) {
          const parserFailure = prepared.processorAvailable
            ? null
            : knowledgeDocumentParserFailure(cause);
          await completeKnowledgeAttachmentProcessing(supabase, identity, command.idempotencyKey, {
            attachmentId: String(inserted.id),
            expectedStatus: "uploaded",
            newStatus: parserFailure?.attachmentStatus ?? "failed",
            content: "",
            contentSha256: "",
            processingError: parserFailure?.processingError ??
              (cause instanceof Error ? cause.message : "知识附件文本解析失败"),
            reason: parserFailure?.reason ?? "知识附件文本解析失败",
          });
          throw parserFailure?.error ?? knowledgeAttachmentDomainError(cause);
        }
        await completeKnowledgeAttachmentProcessing(supabase, identity, command.idempotencyKey, {
          attachmentId: String(inserted.id),
          expectedStatus: "uploaded",
          newStatus: "parsed",
          content: parsed.content,
          contentSha256: parsed.contentSha256,
          processingError: "",
          reason: prepared.processorAvailable
            ? "知识附件完整性校验与文本解析完成"
            : "知识附件完整性校验与文档解析完成",
        });
      }

      return await knowledgeAttachmentOperationResult(
        supabase,
        identity.organizationId,
        versionId,
        String(inserted.id),
      );
    },
    async retryKnowledgeAttachment(identity, attachmentId, command) {
      const { data: attachment, error: attachmentError } = await supabase
        .from("knowledge_version_attachments")
        .select(knowledgeAttachmentInternalSelect)
        .eq("organization_id", identity.organizationId)
        .eq("id", attachmentId)
        .maybeSingle();
      if (attachmentError) throw attachmentError;
      if (!attachment) return null;
      const attachmentStatus = String(attachment.status ?? "");
      if (!["failed", "processing_unavailable"].includes(attachmentStatus)) {
        throw new SkillKnowledgeManagementError(
          409,
          "knowledge_attachment_retry_not_allowed",
          "refresh_knowledge_version",
        );
      }
      if (attachment.storage_bucket !== knowledgeAttachmentBucket || !attachment.storage_path) {
        throw new SkillKnowledgeManagementError(
          409,
          "knowledge_attachment_storage_reference_invalid",
          "replace_attachment",
        );
      }
      const { data: storedFile, error: downloadError } = await supabase.storage
        .from(knowledgeAttachmentBucket)
        .download(String(attachment.storage_path));
      if (downloadError || !storedFile) {
        throw new SkillKnowledgeManagementError(
          502,
          "knowledge_attachment_download_failed",
          "replace_attachment",
        );
      }
      const bytes = new Uint8Array(await storedFile.arrayBuffer());
      let prepared: PreparedKnowledgeAttachment;
      try {
        prepared = await prepareKnowledgeAttachmentUpload({
          fileName: String(attachment.original_file_name ?? ""),
          contentType: String(attachment.content_type ?? ""),
          bytes,
          claimedSha256: String(attachment.file_sha256 ?? ""),
        });
      } catch (cause) {
        throw knowledgeAttachmentDomainError(cause);
      }
      if (!prepared.processorAvailable && !documentParser) {
        throw new SkillKnowledgeManagementError(
          409,
          "knowledge_attachment_processor_unavailable",
          "replace_attachment",
        );
      }
      let parsed: { content: string; contentSha256: string };
      try {
        parsed = prepared.processorAvailable
          ? await parseKnowledgeTextAttachment(prepared, bytes)
          : await documentParser!.parse({
            fileName: prepared.originalFileName,
            contentType: prepared.contentType,
            bytes,
            fileSha256: prepared.fileSha256,
          });
      } catch (cause) {
        const parserFailure = prepared.processorAvailable
          ? null
          : knowledgeDocumentParserFailure(cause);
        await completeKnowledgeAttachmentProcessing(supabase, identity, command.idempotencyKey, {
          attachmentId,
          expectedStatus: attachmentStatus,
          newStatus: parserFailure?.attachmentStatus ?? "failed",
          content: "",
          contentSha256: "",
          processingError: parserFailure?.processingError ??
            (cause instanceof Error ? cause.message : "知识附件文本解析失败"),
          reason: command.reason,
        });
        throw parserFailure?.error ?? knowledgeAttachmentDomainError(cause);
      }
      await completeKnowledgeAttachmentProcessing(supabase, identity, command.idempotencyKey, {
        attachmentId,
        expectedStatus: attachmentStatus,
        newStatus: "parsed",
        content: parsed.content,
        contentSha256: parsed.contentSha256,
        processingError: "",
        reason: command.reason,
      });
      return await knowledgeAttachmentOperationResult(
        supabase,
        identity.organizationId,
        String(attachment.knowledge_version_id),
        attachmentId,
      );
    },
    async transitionKnowledgeVersion(identity, versionId, command) {
      let contentSha256 = "";
      if (command.newStatus === "published") {
        const { data: version, error: versionError } = await supabase
          .from("knowledge_versions")
          .select(
            "id, version, title, summary, source_type, source_reference, language, sensitivity, license, project_ids, device_models, skill_ids, knowledge_scopes, content, knowledge_entries!inner(knowledge_key)",
          )
          .eq("organization_id", identity.organizationId)
          .eq("id", versionId)
          .maybeSingle();
        if (versionError) throw versionError;
        if (!version) return null;
        const entry = nestedRecord(version.knowledge_entries);
        const compiled = await compileKnowledgeVersion({
          knowledgeId: entry.knowledge_key,
          version: version.version,
          title: version.title,
          summary: version.summary,
          sourceType: version.source_type,
          sourceReference: version.source_reference,
          language: version.language,
          sensitivity: version.sensitivity,
          license: version.license,
          projectIds: version.project_ids,
          deviceModels: version.device_models,
          skillIds: version.skill_ids,
          knowledgeScopes: version.knowledge_scopes,
          content: version.content,
        });
        contentSha256 = compiled.contentSha256;
      }
      const { data, error } = await supabase.rpc("transition_knowledge_version", {
        target_knowledge_version_id: versionId,
        expected_status: command.expectedStatus,
        new_status: command.newStatus,
        published_content_sha256: contentSha256,
        new_processing_error: command.processingError,
        actor_id: identity.id,
        command_reason: command.reason,
        idempotency_key: command.idempotencyKey,
      });
      if (error) throw error;
      return singleRecord(data);
    },
    async grantKnowledgeVersion(identity, versionId, command) {
      const { data, error } = await supabase.rpc("grant_knowledge_version", {
        target_knowledge_version_id: versionId,
        grant_scope: command.scopeType,
        scope_id: command.scopeId,
        granted_active_from: command.activeFrom,
        granted_expires_at: command.expiresAt,
        actor_id: identity.id,
        command_reason: command.reason,
        idempotency_key: command.idempotencyKey,
      });
      if (error) throw error;
      return singleRecord(data);
    },
    async revokeKnowledgeGrant(identity, grantId, command) {
      const { data, error } = await supabase.rpc("revoke_knowledge_grant", {
        target_grant_id: grantId,
        actor_id: identity.id,
        command_reason: command.reason,
        idempotency_key: command.idempotencyKey,
      });
      if (error) throw error;
      return singleRecord(data);
    },
  };
}

function skillDraftCommand(body: Record<string, unknown> | null): CreateSkillDraftCommand | null {
  if (!body) return null;
  try {
    const draft = normalizeSkillDraft({
      name: body.name,
      description: body.description,
      rules: body.rules,
      testCases: body.testCases,
    });
    const skillKey = identifier(body.skillKey);
    const version = requiredText(body.version, 40);
    const reason = reasonText(body.reason);
    const idempotencyKey = idempotencyText(body.idempotencyKey);
    if (!skillKey || !version || !semverPattern.test(version) || !reason || !idempotencyKey) return null;
    return { skillKey, version, ...draft, reason, idempotencyKey };
  } catch {
    return null;
  }
}

function knowledgeDraftCommand(
  body: Record<string, unknown> | null,
): CreateKnowledgeDraftCommand | null {
  if (!body || !Number.isInteger(body.version) || Number(body.version) <= 0) return null;
  try {
    const draft = normalizeKnowledgeDraft({
      title: body.title,
      summary: body.summary,
      sourceType: body.sourceType,
      sourceReference: body.sourceReference,
      language: body.language,
      sensitivity: body.sensitivity,
      license: body.license,
      projectIds: body.projectIds,
      deviceModels: body.deviceModels,
      skillIds: body.skillIds,
      knowledgeScopes: body.knowledgeScopes,
      content: body.content,
    });
    const knowledgeKey = identifier(body.knowledgeKey);
    const reason = reasonText(body.reason);
    const idempotencyKey = idempotencyText(body.idempotencyKey);
    if (!knowledgeKey || !reason || !idempotencyKey) return null;
    return {
      knowledgeKey,
      version: Number(body.version),
      ...draft,
      reason,
      idempotencyKey,
    };
  } catch {
    return null;
  }
}

function knowledgeCaseDraftCommand(
  body: Record<string, unknown> | null,
): CreateKnowledgeCaseDraftCommand | null {
  if (!body || !hasExactFields(body, knowledgeCaseDraftFields)
    || !Number.isInteger(body.version) || Number(body.version) <= 0) {
    return null;
  }
  const knowledgeKey = identifier(body.knowledgeKey);
  const title = requiredText(body.title, 300);
  const sensitivity = body.sensitivity as KnowledgeDraft["sensitivity"];
  const license = requiredText(body.license, 300);
  const knowledgeScopes = strictStringList(body.knowledgeScopes, 100, 200);
  const reason = reasonText(body.reason);
  const idempotencyKey = idempotencyText(body.idempotencyKey);
  if (!knowledgeKey || !title || !knowledgeSensitivities.has(sensitivity)
    || !license || !knowledgeScopes || !reason || !idempotencyKey) {
    return null;
  }
  return {
    knowledgeKey,
    version: Number(body.version),
    title,
    sensitivity,
    license,
    knowledgeScopes,
    reason,
    idempotencyKey,
  };
}

function skillTransitionCommand(
  body: Record<string, unknown> | null,
): SkillTransitionCommand | null {
  if (!body || !skillStatuses.has(body.expectedStatus as SkillStatus) || !skillStatuses.has(body.newStatus as SkillStatus)) {
    return null;
  }
  const reason = reasonText(body.reason);
  const idempotencyKey = idempotencyText(body.idempotencyKey);
  const testResult = body.testResult === undefined ? {} : body.testResult;
  if (!reason || !idempotencyKey || !isRecord(testResult)) return null;
  return {
    expectedStatus: body.expectedStatus as SkillStatus,
    newStatus: body.newStatus as SkillStatus,
    testResult,
    reason,
    idempotencyKey,
  };
}

function knowledgeTransitionCommand(
  body: Record<string, unknown> | null,
): KnowledgeTransitionCommand | null {
  if (!body || !knowledgeStatuses.has(body.expectedStatus as KnowledgeStatus) || !knowledgeStatuses.has(body.newStatus as KnowledgeStatus)) {
    return null;
  }
  const reason = reasonText(body.reason);
  const idempotencyKey = idempotencyText(body.idempotencyKey);
  const processingError = body.processingError === undefined ? "" : optionalText(body.processingError, 4_000);
  if (!reason || !idempotencyKey || processingError === null) return null;
  return {
    expectedStatus: body.expectedStatus as KnowledgeStatus,
    newStatus: body.newStatus as KnowledgeStatus,
    processingError,
    reason,
    idempotencyKey,
  };
}

async function knowledgeAttachmentUploadCommand(
  request: Request,
): Promise<{ command: KnowledgeAttachmentUploadCommand; confirmation: string } | null> {
  const contentType = request.headers.get("Content-Type") ?? "";
  if (!contentType.toLowerCase().startsWith("multipart/form-data")) return null;
  let body: FormData;
  try {
    body = await request.formData();
  } catch {
    return null;
  }
  const allowedFields = new Set(["file", "sha256", "idempotencyKey", "confirmation"]);
  const keys = Array.from(body.keys());
  if (keys.some((key) => !allowedFields.has(key))) return null;
  for (const field of ["file", "sha256", "idempotencyKey"]) {
    if (body.getAll(field).length !== 1) return null;
  }
  if (body.getAll("confirmation").length > 1) return null;
  const file = body.get("file");
  const claimedSha256 = body.get("sha256");
  const idempotencyKey = body.get("idempotencyKey");
  const confirmation = body.get("confirmation");
  if (!(file instanceof File) || typeof claimedSha256 !== "string" ||
    typeof idempotencyKey !== "string" ||
    (confirmation !== null && typeof confirmation !== "string")) {
    return null;
  }
  const normalizedSha256 = claimedSha256.trim().toLowerCase();
  const normalizedIdempotencyKey = idempotencyText(idempotencyKey);
  if (!/^[0-9a-f]{64}$/.test(normalizedSha256) || !normalizedIdempotencyKey) return null;
  return {
    command: {
      fileName: file.name,
      contentType: file.type,
      bytes: new Uint8Array(await file.arrayBuffer()),
      claimedSha256: normalizedSha256,
      idempotencyKey: normalizedIdempotencyKey,
    },
    confirmation: typeof confirmation === "string" ? confirmation : "",
  };
}

function knowledgeAttachmentRetryCommand(
  body: Record<string, unknown> | null,
): KnowledgeAttachmentRetryCommand | null {
  if (!body || Object.keys(body).some((key) => !["reason", "idempotencyKey", "confirmation"].includes(key))) {
    return null;
  }
  return reasonedCommand(body);
}

function assignmentCommand(
  body: Record<string, unknown> | null,
  allowSkillVersion: boolean,
): ContentAssignmentCommand | null {
  if (!body || !scopeTypes.has(body.scopeType as ContentScopeType)) return null;
  const scopeType = body.scopeType as ContentScopeType;
  if (!allowSkillVersion && scopeType === "skill_version") return null;
  const scopeId = body.scopeId === null || body.scopeId === undefined ? null : identifier(body.scopeId);
  if ((scopeType === "organization" && scopeId !== null) || (scopeType !== "organization" && !scopeId)) return null;
  const activeFrom = optionalTimestamp(body.activeFrom);
  const expiresAt = optionalTimestamp(body.expiresAt);
  const reason = reasonText(body.reason);
  const idempotencyKey = idempotencyText(body.idempotencyKey);
  if (activeFrom === undefined || expiresAt === undefined || !reason || !idempotencyKey) return null;
  if (activeFrom && expiresAt && new Date(expiresAt).getTime() <= new Date(activeFrom).getTime()) return null;
  return { scopeType, scopeId, activeFrom, expiresAt, reason, idempotencyKey };
}

function reasonedCommand(body: Record<string, unknown> | null): ReasonedIdempotentCommand | null {
  if (!body) return null;
  const reason = reasonText(body.reason);
  const idempotencyKey = idempotencyText(body.idempotencyKey);
  return reason && idempotencyKey ? { reason, idempotencyKey } : null;
}

function skillTransitionConfirmation(status: SkillStatus): string {
  return {
    draft: "REJECT_SKILL_REVIEW",
    review_pending: "SUBMIT_SKILL_REVIEW",
    published: "PUBLISH_SKILL",
    deprecated: "DEPRECATE_SKILL",
    archived: "ARCHIVE_SKILL",
  }[status];
}

function knowledgeTransitionConfirmation(status: KnowledgeStatus): string {
  return {
    uploaded: "RESET_KNOWLEDGE_UPLOAD",
    scanning: "START_KNOWLEDGE_SCAN",
    parsing: "START_KNOWLEDGE_PARSE",
    parsed: "MARK_KNOWLEDGE_PARSED",
    review_pending: "SUBMIT_KNOWLEDGE_REVIEW",
    published: "PUBLISH_KNOWLEDGE",
    expired: "EXPIRE_KNOWLEDGE",
    deprecated: "DEPRECATE_KNOWLEDGE",
    archived: "ARCHIVE_KNOWLEDGE",
  }[status];
}

function canManage(identity: SkillKnowledgeManagementIdentity): boolean {
  return identity.role === "super_admin" || identity.role === "ops_admin";
}

function bearerToken(request: Request): string | null {
  const value = request.headers.get("Authorization") ?? "";
  return value.startsWith("Bearer ") && value.slice(7).trim() ? value.slice(7).trim() : null;
}

async function requestObject(request: Request): Promise<Record<string, unknown> | null> {
  try {
    const value = await request.json();
    return isRecord(value) ? value : null;
  } catch {
    return null;
  }
}

function routePath(request: Request): string {
  return new URL(request.url).pathname
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/";
}

function identifier(value: unknown): string | null {
  const text = requiredText(value, 200);
  return text && identifierPattern.test(text) ? text : null;
}

function reasonText(value: unknown): string | null {
  const text = requiredText(value, 1_000);
  return text && text.length >= 3 ? text : null;
}

function idempotencyText(value: unknown): string | null {
  return requiredText(value, 200);
}

function requiredText(value: unknown, maximum: number): string | null {
  if (typeof value !== "string") return null;
  const text = value.trim();
  return text && text.length <= maximum && !/[\u0000-\u001f]/.test(text) ? text : null;
}

function optionalText(value: unknown, maximum: number): string | null {
  if (value === null || value === undefined || value === "") return "";
  return requiredText(value, maximum);
}

function optionalTimestamp(value: unknown): string | null | undefined {
  if (value === null || value === undefined || value === "") return null;
  if (typeof value !== "string" || !Number.isFinite(Date.parse(value))) return undefined;
  return value;
}

async function completeKnowledgeAttachmentProcessing(
  supabase: any,
  identity: SkillKnowledgeManagementIdentity,
  uploadIdempotencyKey: string,
  command: {
    attachmentId: string;
    expectedStatus: string;
    newStatus: string;
    content: string;
    contentSha256: string;
    processingError: string;
    reason: string;
  },
): Promise<void> {
  const rawCompletionKey = `${uploadIdempotencyKey}:complete`;
  const completionKey = rawCompletionKey.length <= 200
    ? rawCompletionKey
    : `attachment-complete:${await sha256Hex(new TextEncoder().encode(uploadIdempotencyKey))}`;
  const { error } = await supabase.rpc("complete_knowledge_attachment_processing", {
    target_attachment_id: command.attachmentId,
    expected_status: command.expectedStatus,
    new_status: command.newStatus,
    extracted_content: command.content,
    extracted_content_sha256: command.contentSha256,
    new_processing_error: command.processingError,
    actor_id: identity.id,
    command_reason: command.reason,
    idempotency_key: completionKey,
  });
  if (error) throw error;
}

function knowledgeAttachmentStoragePath(
  organizationId: string,
  versionId: string,
  attachmentId: string,
  attachment: PreparedKnowledgeAttachment,
): string {
  const extension = ({ text: "txt", markdown: "md", pdf: "pdf", docx: "docx" })[
    attachment.fileKind
  ];
  return `knowledge/${organizationId}/${versionId}/${attachmentId}/source.${extension}`;
}

async function knowledgeAttachmentOperationResult(
  supabase: any,
  organizationId: string,
  versionId: string,
  attachmentId: string,
): Promise<KnowledgeAttachmentOperationResult | null> {
  const { data: version, error: versionError } = await supabase
    .from("knowledge_versions")
    .select("id, status, source_type, source_reference")
    .eq("organization_id", organizationId)
    .eq("id", versionId)
    .maybeSingle();
  if (versionError) throw versionError;
  const { data: attachment, error: attachmentError } = await supabase
    .from("knowledge_version_attachments")
    .select(knowledgeAttachmentInternalSelect)
    .eq("organization_id", organizationId)
    .eq("id", attachmentId)
    .maybeSingle();
  if (attachmentError) throw attachmentError;
  if (!version || !attachment) return null;
  const { data: signed, error: signedError } = await supabase.storage
    .from(knowledgeAttachmentBucket)
    .createSignedUrl(String(attachment.storage_path), 300);
  if (signedError) throw signedError;
  return {
    version,
    attachment: sanitizeKnowledgeAttachment(
      attachment,
      typeof signed?.signedUrl === "string" ? signed.signedUrl : null,
      new Date(Date.now() + 300_000).toISOString(),
    ),
  };
}

async function knowledgeVersionsWithAttachments(
  supabase: any,
  versions: unknown[],
): Promise<Array<Record<string, unknown>>> {
  const records = versions.filter(isRecord);
  const attachments = records.flatMap((version) =>
    Array.isArray(version.knowledge_version_attachments)
      ? version.knowledge_version_attachments.filter(isRecord)
      : []
  );
  const paths = Array.from(new Set(attachments.map((item) => String(item.storage_path ?? "")).filter(Boolean)));
  const signedByPath = new Map<string, string>();
  if (paths.length) {
    const { data, error } = await supabase.storage
      .from(knowledgeAttachmentBucket)
      .createSignedUrls(paths, 300);
    if (error) throw error;
    for (const item of Array.isArray(data) ? data : []) {
      if (isRecord(item) && typeof item.path === "string" && typeof item.signedUrl === "string") {
        signedByPath.set(item.path, item.signedUrl);
      }
    }
  }
  const expiresAt = new Date(Date.now() + 300_000).toISOString();
  return records.map((version) => {
    const { knowledge_version_attachments: rawAttachments, ...publicVersion } = version;
    const knowledgeAttachments = Array.isArray(rawAttachments)
      ? rawAttachments.filter(isRecord).map((attachment) => {
        const path = String(attachment.storage_path ?? "");
        return sanitizeKnowledgeAttachment(attachment, signedByPath.get(path) ?? null, expiresAt);
      })
      : [];
    return { ...publicVersion, knowledge_attachments: knowledgeAttachments };
  });
}

function sanitizeKnowledgeAttachment(
  attachment: Record<string, unknown>,
  downloadUrl: string | null,
  downloadExpiresAt: string,
): Record<string, unknown> {
  return {
    id: attachment.id,
    knowledge_version_id: attachment.knowledge_version_id,
    status: attachment.status,
    original_file_name: attachment.original_file_name,
    content_type: attachment.content_type,
    byte_size: attachment.byte_size,
    file_sha256: attachment.file_sha256,
    extracted_content_sha256: attachment.extracted_content_sha256,
    processing_error: attachment.processing_error,
    is_current: attachment.is_current,
    created_at: attachment.created_at,
    updated_at: attachment.updated_at,
    download_url: downloadUrl,
    download_expires_at: downloadUrl ? downloadExpiresAt : null,
  };
}

function nestedRecord(value: unknown): Record<string, unknown> {
  if (Array.isArray(value)) return isRecord(value[0]) ? value[0] : {};
  return isRecord(value) ? value : {};
}

function singleRecord(value: unknown): Record<string, unknown> | null {
  if (Array.isArray(value)) return isRecord(value[0]) ? value[0] : null;
  return isRecord(value) ? value : null;
}

function knowledgeCaseDraftResult(value: unknown): CreateKnowledgeCaseDraftResult {
  const result = Array.isArray(value) ? value[0] : value;
  if (!isRecord(result)) throw new Error("knowledge_case_draft_response_invalid");
  const status = result.status;
  if (status === "created" && isRecord(result.item)) {
    return { status, item: result.item };
  }
  if (status === "not_found" || status === "task_not_completed"
    || status === "completion_not_confirmed" || status === "evidence_pending") {
    return { status };
  }
  throw new Error("knowledge_case_draft_response_invalid");
}

function strictStringList(value: unknown, maximumItems: number, maximumLength: number): string[] | null {
  if (!Array.isArray(value) || value.length > maximumItems) return null;
  const seen = new Set<string>();
  const result: string[] = [];
  for (const item of value) {
    const text = requiredText(item, maximumLength);
    if (!text || seen.has(text)) return null;
    seen.add(text);
    result.push(text);
  }
  return result;
}

function hasExactFields(value: Record<string, unknown>, fields: Set<string>): boolean {
  const keys = Object.keys(value);
  return keys.length === fields.size && keys.every((key) => fields.has(key));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function knowledgeAttachmentDomainError(cause: unknown): SkillKnowledgeManagementError {
  const code = cause instanceof Error ? cause.message : "knowledge_attachment_invalid";
  if (code === "knowledge_attachment_too_large") {
    return new SkillKnowledgeManagementError(413, code, "select_smaller_file");
  }
  if (code === "knowledge_attachment_type_not_allowed" || code === "knowledge_attachment_type_mismatch") {
    return new SkillKnowledgeManagementError(415, code, "select_supported_file");
  }
  if (code === "knowledge_attachment_processor_unavailable") {
    return new SkillKnowledgeManagementError(409, code, "replace_attachment");
  }
  return new SkillKnowledgeManagementError(422, code, "replace_attachment");
}

function knowledgeDocumentParserFailure(cause: unknown): {
  attachmentStatus: "processing_unavailable" | "failed";
  processingError: string;
  reason: string;
  error: SkillKnowledgeManagementError;
} {
  if (!(cause instanceof KnowledgeDocumentParserClientError)) {
    return {
      attachmentStatus: "processing_unavailable",
      processingError: "knowledge_attachment_processor_unavailable",
      reason: "知识附件处理器暂不可用",
      error: new SkillKnowledgeManagementError(
        503,
        "knowledge_attachment_processor_unavailable",
        "retry_attachment_processing",
      ),
    };
  }
  const documentRejected = cause.status === 400 || cause.status === 413 ||
    cause.status === 415 || cause.status === 422;
  return documentRejected
    ? {
      attachmentStatus: "failed",
      processingError: cause.code,
      reason: "知识附件文档解析失败",
      error: new SkillKnowledgeManagementError(
        cause.status,
        cause.code,
        "replace_attachment",
      ),
    }
    : {
      attachmentStatus: "processing_unavailable",
      processingError: cause.code,
      reason: "知识附件处理器暂不可用",
      error: new SkillKnowledgeManagementError(
        503,
        cause.code,
        "retry_attachment_processing",
      ),
    };
}

function skillKnowledgeManagementErrorResponse(cause: unknown): Response | null {
  if (!(cause instanceof SkillKnowledgeManagementError)) return null;
  return response({
    ok: false,
    error: cause.code,
    ...(cause.recoverableAction ? { recoverableAction: cause.recoverableAction } : {}),
  }, cause.status);
}

function response(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers });
}
