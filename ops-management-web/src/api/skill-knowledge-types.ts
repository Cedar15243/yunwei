export type SkillStatus = "draft" | "review_pending" | "published" | "deprecated" | "archived";
export type KnowledgeStatus =
  | "uploaded"
  | "scanning"
  | "parsing"
  | "parsed"
  | "review_pending"
  | "published"
  | "expired"
  | "deprecated"
  | "archived";
export type KnowledgeAttachmentStatus = "uploaded" | "parsing" | "parsed" | "processing_unavailable" | "failed" | "replaced";
export type ContentScopeType = "organization" | "project" | "profile" | "device" | "skill_version";
export type AssignmentStatus = "active" | "revoked" | "expired";

export type SkillAssignment = {
  id: string;
  scope_type: Exclude<ContentScopeType, "skill_version">;
  project_id: string | null;
  profile_id: string | null;
  device_id: string | null;
  status: AssignmentStatus;
  active_from: string;
  expires_at: string | null;
  assigned_at: string;
  revoke_reason?: string;
};

export type SkillVersion = {
  id: string;
  skill_definition_id: string;
  version: string;
  status: SkillStatus;
  rules: Record<string, unknown>;
  test_cases: Array<Record<string, unknown>>;
  test_result: Record<string, unknown>;
  content_sha256: string;
  lifecycle_reason: string;
  submitted_at?: string | null;
  reviewed_at?: string | null;
  published_at?: string | null;
  created_at: string;
  updated_at: string;
  skill_assignments: SkillAssignment[];
};

export type SkillDefinition = {
  id: string;
  skill_key: string;
  name: string;
  description: string;
  created_at: string;
  updated_at: string;
  skill_versions: SkillVersion[];
};

export type KnowledgeGrant = {
  id: string;
  scope_type: ContentScopeType;
  project_id: string | null;
  profile_id: string | null;
  device_id: string | null;
  skill_version_id: string | null;
  status: AssignmentStatus;
  active_from: string;
  expires_at: string | null;
  granted_at: string;
  revoke_reason?: string;
};

export type KnowledgeVersion = {
  id: string;
  knowledge_entry_id: string;
  version: number;
  status: KnowledgeStatus;
  title: string;
  summary: string;
  source_type: "manual" | "uploaded_file" | "case" | "external_link";
  source_reference: string;
  language: string;
  sensitivity: "public" | "internal" | "confidential" | "restricted";
  license: string;
  project_ids: string[];
  device_models: string[];
  skill_ids: string[];
  knowledge_scopes: string[];
  content: string;
  content_sha256: string;
  processing_error: string;
  lifecycle_reason: string;
  valid_from: string | null;
  expires_at: string | null;
  submitted_at?: string | null;
  reviewed_at?: string | null;
  published_at?: string | null;
  created_at: string;
  updated_at: string;
  knowledge_attachments: KnowledgeAttachment[];
  knowledge_grants: KnowledgeGrant[];
};

export type KnowledgeAttachment = {
  id: string;
  knowledge_version_id: string;
  status: KnowledgeAttachmentStatus;
  original_file_name: string;
  content_type: string;
  byte_size: number;
  file_sha256: string;
  extracted_content_sha256: string;
  processing_error: string;
  is_current: boolean;
  created_at: string;
  updated_at: string;
  download_url: string | null;
  download_expires_at: string | null;
};

export type KnowledgeAttachmentOperationResult = {
  version: Pick<KnowledgeVersion, "id" | "status"> & Partial<KnowledgeVersion>;
  attachment: KnowledgeAttachment;
};

export type KnowledgeEntry = {
  id: string;
  knowledge_key: string;
  title: string;
  created_at: string;
  updated_at: string;
  knowledge_versions: KnowledgeVersion[];
};

export type SkillDraftCommand = {
  skillKey: string;
  version: string;
  name: string;
  description: string;
  rules: Record<string, unknown>;
  testCases: Array<Record<string, unknown>>;
  reason: string;
  idempotencyKey: string;
};

export type KnowledgeDraftCommand = {
  knowledgeKey: string;
  version: number;
  title: string;
  summary: string;
  sourceType: KnowledgeVersion["source_type"];
  sourceReference: string;
  language: string;
  sensitivity: KnowledgeVersion["sensitivity"];
  license: string;
  projectIds: string[];
  deviceModels: string[];
  skillIds: string[];
  knowledgeScopes: string[];
  content: string;
  reason: string;
  idempotencyKey: string;
};

export type KnowledgeCaseDraftCommand = {
  knowledgeKey: string;
  version: number;
  title: string;
  sensitivity: KnowledgeVersion["sensitivity"];
  license: string;
  knowledgeScopes: string[];
  reason: string;
  idempotencyKey: string;
};

export type KnowledgeCaseDraftResult = {
  id: string;
  status: KnowledgeStatus;
  knowledge_entry_id?: string;
  version?: number;
  title?: string;
  source_type?: "case";
  redaction_status?: "pending" | "approved" | "rejected";
};

export type SkillTransitionCommand = {
  expectedStatus: SkillStatus;
  newStatus: SkillStatus;
  testResult: Record<string, unknown>;
  reason: string;
  idempotencyKey: string;
};

export type KnowledgeTransitionCommand = {
  expectedStatus: KnowledgeStatus;
  newStatus: KnowledgeStatus;
  processingError: string;
  reason: string;
  idempotencyKey: string;
};

export type AssignmentCommand = {
  scopeType: ContentScopeType;
  scopeId: string | null;
  activeFrom: string | null;
  expiresAt: string | null;
  reason: string;
  idempotencyKey: string;
};

export type ReasonedCommand = { reason: string; idempotencyKey: string };

export interface SkillKnowledgeApi {
  getSkills(): Promise<SkillDefinition[]>;
  createSkillDraft(command: SkillDraftCommand): Promise<SkillVersion>;
  getSkillVersions(skillId: string): Promise<SkillVersion[]>;
  transitionSkillVersion(versionId: string, command: SkillTransitionCommand): Promise<SkillVersion>;
  assignSkillVersion(versionId: string, command: AssignmentCommand): Promise<SkillAssignment>;
  revokeSkillAssignment(assignmentId: string, command: ReasonedCommand): Promise<SkillAssignment>;
  getKnowledge(): Promise<KnowledgeEntry[]>;
  createKnowledgeDraft(command: KnowledgeDraftCommand): Promise<KnowledgeVersion>;
  createKnowledgeCaseDraft(taskId: string, command: KnowledgeCaseDraftCommand): Promise<KnowledgeCaseDraftResult>;
  getKnowledgeVersions(knowledgeId: string): Promise<KnowledgeVersion[]>;
  uploadKnowledgeAttachment(versionId: string, file: File, idempotencyKey: string): Promise<KnowledgeAttachmentOperationResult>;
  retryKnowledgeAttachment(attachmentId: string, command: ReasonedCommand): Promise<KnowledgeAttachmentOperationResult>;
  transitionKnowledgeVersion(versionId: string, command: KnowledgeTransitionCommand): Promise<KnowledgeVersion>;
  grantKnowledgeVersion(versionId: string, command: AssignmentCommand): Promise<KnowledgeGrant>;
  revokeKnowledgeGrant(grantId: string, command: ReasonedCommand): Promise<KnowledgeGrant>;
}
