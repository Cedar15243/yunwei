import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import {
  createSkillKnowledgeManagementGateway,
  routeSkillKnowledgeManagement,
  SkillKnowledgeManagementError,
  type SkillKnowledgeManagementGateway,
} from "./skill-knowledge-management.ts";
import { sha256Hex } from "./knowledge-attachment-domain.ts";
import {
  KnowledgeDocumentParserClientError,
  type KnowledgeDocumentParser,
} from "./knowledge-document-parser-client.ts";

const admin = {
  id: "admin-a",
  organizationId: "org-a",
  role: "ops_admin" as const,
  displayName: "管理员",
};
const viewer = { ...admin, id: "viewer-a", role: "viewer" as const };

function gateway(
  overrides: Partial<SkillKnowledgeManagementGateway> = {},
): SkillKnowledgeManagementGateway {
  return {
    authenticate: async (token) => {
      if (token === "admin-token") return admin;
      if (token === "viewer-token") return viewer;
      return null;
    },
    listSkills: async () => [{ id: "skill-a", skill_key: "hvac_ddc_repair", name: "DDC 维修" }],
    createSkillDraft: async (_identity, command) => ({ id: "skill-version-a", status: "draft", ...command }),
    listSkillVersions: async (_identity, skillId) => skillId === "missing" ? null : [{ id: "skill-version-a", version: "1.0.0" }],
    transitionSkillVersion: async (_identity, versionId, command) => ({ id: versionId, status: command.newStatus }),
    assignSkillVersion: async (_identity, versionId, command) => ({ id: "assignment-a", skill_version_id: versionId, ...command }),
    revokeSkillAssignment: async (_identity, assignmentId) => ({ id: assignmentId, status: "revoked" }),
    listKnowledge: async () => [{ id: "knowledge-a", knowledge_key: "hf_ddc_guide", title: "DDC 指南" }],
    createKnowledgeDraft: async (_identity, command) => ({ id: "knowledge-version-a", status: "uploaded", ...command }),
    createKnowledgeCaseDraft: async (_identity, taskId, command) => ({
      status: "created",
      item: { id: "knowledge-case-version-a", taskId, status: "uploaded", ...command },
    }),
    listKnowledgeVersions: async (_identity, knowledgeId) => knowledgeId === "missing" ? null : [{ id: "knowledge-version-a", version: 1 }],
    uploadKnowledgeAttachment: async (_identity, versionId, command) => ({
      version: { id: versionId, status: "parsed" },
      attachment: {
        id: "attachment-a",
        status: "parsed",
        original_file_name: command.fileName,
        content_type: command.contentType,
        byte_size: command.bytes.byteLength,
        file_sha256: command.claimedSha256,
      },
    }),
    retryKnowledgeAttachment: async (_identity, attachmentId) => ({
      version: { id: "knowledge-version-a", status: "parsed" },
      attachment: { id: attachmentId, status: "parsed" },
    }),
    transitionKnowledgeVersion: async (_identity, versionId, command) => ({ id: versionId, status: command.newStatus }),
    grantKnowledgeVersion: async (_identity, versionId, command) => ({ id: "grant-a", knowledge_version_id: versionId, ...command }),
    revokeKnowledgeGrant: async (_identity, grantId) => ({ id: grantId, status: "revoked" }),
    ...overrides,
  };
}

Deno.test("requires bearer authentication and an organization administrator", async () => {
  const missing = await routeSkillKnowledgeManagement(request("GET", "/management/skills"), gateway());
  const invalid = await routeSkillKnowledgeManagement(request("GET", "/management/skills", "bad-token"), gateway());
  const denied = await routeSkillKnowledgeManagement(request("GET", "/management/skills", "viewer-token"), gateway());
  const allowed = await routeSkillKnowledgeManagement(request("GET", "/management/skills", "admin-token"), gateway());

  assertEquals(missing.status, 401);
  assertEquals(invalid.status, 401);
  assertEquals(denied.status, 403);
  assertEquals(allowed.status, 200);
});

Deno.test("accepts the deployed Edge Function path and lists Skill definitions", async () => {
  const response = await routeSkillKnowledgeManagement(
    request("GET", "/functions/v1/ops-glasses/management/skills", "admin-token"),
    gateway(),
  );
  assertEquals(response.status, 200);
  assertEquals(await response.json(), {
    items: [{ id: "skill-a", skill_key: "hvac_ddc_repair", name: "DDC 维修" }],
  });
});

Deno.test("creates a strict Skill draft without accepting forged organization or author identity", async () => {
  let received: unknown = null;
  const response = await routeSkillKnowledgeManagement(jsonRequest("POST", "/management/skills", {
    skillKey: "hvac_ddc_repair",
    version: "1.0.0",
    name: "DDC 维修",
    description: "控制器报警处置",
    rules: skillRules(),
    testCases: [],
    reason: "创建首个受控版本",
    idempotencyKey: "skill-create-a",
    organizationId: "forged-org",
    createdBy: "forged-user",
  }), gateway({
    createSkillDraft: async (_identity, command) => {
      received = command;
      return { id: "skill-version-a", status: "draft" };
    },
  }));

  assertEquals(response.status, 201);
  assertEquals(received, {
    skillKey: "hvac_ddc_repair",
    version: "1.0.0",
    name: "DDC 维修",
    description: "控制器报警处置",
    rules: skillRules(),
    testCases: [],
    reason: "创建首个受控版本",
    idempotencyKey: "skill-create-a",
  });
});

Deno.test("requires explicit confirmation for Skill review publication assignment and revocation", async () => {
  const publish = await routeSkillKnowledgeManagement(jsonRequest(
    "POST",
    "/management/skill-versions/version-a/transition",
    { expectedStatus: "review_pending", newStatus: "published", reason: "审核通过", idempotencyKey: "publish-a" },
  ), gateway());
  const assign = await routeSkillKnowledgeManagement(jsonRequest(
    "POST",
    "/management/skill-versions/version-a/assignments",
    { scopeType: "device", scopeId: "device-a", reason: "分配试点设备", idempotencyKey: "assign-a" },
  ), gateway());
  const revoke = await routeSkillKnowledgeManagement(jsonRequest(
    "POST",
    "/management/skill-assignments/assignment-a/revoke",
    { reason: "结束授权", idempotencyKey: "revoke-a" },
  ), gateway());

  assertEquals(publish.status, 400);
  assertEquals(assign.status, 400);
  assertEquals(revoke.status, 400);
  assertEquals(await publish.json(), { ok: false, error: "confirmation_required" });
});

Deno.test("forwards only confirmed Skill lifecycle commands", async () => {
  let transition: unknown = null;
  let assignment: unknown = null;
  const publish = await routeSkillKnowledgeManagement(jsonRequest(
    "POST",
    "/management/skill-versions/version-a/transition",
    {
      expectedStatus: "review_pending",
      newStatus: "published",
      testResult: { passed: 12, failed: 0 },
      reason: "测试集全部通过",
      idempotencyKey: "publish-a",
      confirmation: "PUBLISH_SKILL",
    },
  ), gateway({
    transitionSkillVersion: async (_identity, versionId, command) => {
      transition = { versionId, command };
      return { id: versionId, status: command.newStatus };
    },
  }));
  const assign = await routeSkillKnowledgeManagement(jsonRequest(
    "POST",
    "/management/skill-versions/version-a/assignments",
    {
      scopeType: "device",
      scopeId: "device-a",
      activeFrom: null,
      expiresAt: null,
      reason: "分配试点设备",
      idempotencyKey: "assign-a",
      confirmation: "ASSIGN_SKILL",
    },
  ), gateway({
    assignSkillVersion: async (_identity, versionId, command) => {
      assignment = { versionId, command };
      return { id: "assignment-a" };
    },
  }));

  assertEquals(publish.status, 200);
  assertEquals(assign.status, 201);
  assertEquals(transition, {
    versionId: "version-a",
    command: {
      expectedStatus: "review_pending",
      newStatus: "published",
      testResult: { passed: 12, failed: 0 },
      reason: "测试集全部通过",
      idempotencyKey: "publish-a",
    },
  });
  assertEquals(assignment, {
    versionId: "version-a",
    command: {
      scopeType: "device",
      scopeId: "device-a",
      activeFrom: null,
      expiresAt: null,
      reason: "分配试点设备",
      idempotencyKey: "assign-a",
    },
  });
});

Deno.test("creates strict knowledge drafts and does not return client-controlled identity", async () => {
  let received: unknown = null;
  const response = await routeSkillKnowledgeManagement(jsonRequest("POST", "/management/knowledge", {
    knowledgeKey: "hf_ddc_guide",
    version: 1,
    ...knowledgeDraft(),
    reason: "导入厂商手册",
    idempotencyKey: "knowledge-create-a",
    organizationId: "forged-org",
    authorProfileId: "forged-user",
  }), gateway({
    createKnowledgeDraft: async (_identity, command) => {
      received = command;
      return { id: "knowledge-version-a", status: "uploaded" };
    },
  }));

  assertEquals(response.status, 201);
  assertEquals(received, {
    knowledgeKey: "hf_ddc_guide",
    version: 1,
    ...knowledgeDraft(),
    reason: "导入厂商手册",
    idempotencyKey: "knowledge-create-a",
  });
});

Deno.test("creates a knowledge case draft only from a server-derived completed task snapshot", async () => {
  let received: unknown = null;
  const response = await routeSkillKnowledgeManagement(jsonRequest(
    "POST",
    "/functions/v1/ops-glasses/management/tasks/task-a/knowledge-drafts",
    {
      knowledgeKey: "case_task_a",
      version: 1,
      title: "控制器离线维修案例",
      sensitivity: "internal",
      license: "华方内部授权",
      knowledgeScopes: ["hvac/ddc"],
      reason: "人工确认后沉淀现场案例",
      idempotencyKey: "case-task-a",
      confirmation: "CREATE_KNOWLEDGE_CASE_DRAFT",
    },
  ), gateway({
    createKnowledgeCaseDraft: async (identity, taskId, command) => {
      received = { identity, taskId, command };
      return { status: "created", item: { id: "knowledge-case-version-a", status: "uploaded" } };
    },
  }));

  assertEquals(response.status, 201);
  assertEquals(received, {
    identity: admin,
    taskId: "task-a",
    command: {
      knowledgeKey: "case_task_a",
      version: 1,
      title: "控制器离线维修案例",
      sensitivity: "internal",
      license: "华方内部授权",
      knowledgeScopes: ["hvac/ddc"],
      reason: "人工确认后沉淀现场案例",
      idempotencyKey: "case-task-a",
    },
  });
  assertEquals(await response.json(), { id: "knowledge-case-version-a", status: "uploaded" });
});

Deno.test("rejects forged case content and maps incomplete task evidence without creating knowledge", async () => {
  let calls = 0;
  const forged = await routeSkillKnowledgeManagement(jsonRequest(
    "POST",
    "/management/tasks/task-a/knowledge-drafts",
    {
      knowledgeKey: "case_task_a",
      version: 1,
      title: "伪造案例",
      sensitivity: "internal",
      license: "华方内部授权",
      knowledgeScopes: ["hvac/ddc"],
      reason: "尝试伪造案例正文",
      idempotencyKey: "case-task-forged",
      confirmation: "CREATE_KNOWLEDGE_CASE_DRAFT",
      content: "客户端伪造正文",
      sourceReference: "task:other-task",
    },
  ), gateway({
    createKnowledgeCaseDraft: async () => {
      calls += 1;
      return { status: "created", item: {} };
    },
  }));
  const evidencePending = await routeSkillKnowledgeManagement(jsonRequest(
    "POST",
    "/management/tasks/task-a/knowledge-drafts",
    {
      knowledgeKey: "case_task_a",
      version: 1,
      title: "控制器离线维修案例",
      sensitivity: "internal",
      license: "华方内部授权",
      knowledgeScopes: ["hvac/ddc"],
      reason: "人工确认后沉淀现场案例",
      idempotencyKey: "case-task-pending",
      confirmation: "CREATE_KNOWLEDGE_CASE_DRAFT",
    },
  ), gateway({
    createKnowledgeCaseDraft: async () => ({ status: "evidence_pending" }),
  }));

  assertEquals(forged.status, 400);
  assertEquals(calls, 0);
  assertEquals(evidencePending.status, 409);
  assertEquals(await evidencePending.json(), {
    ok: false,
    error: "task_evidence_not_synced",
    recoverableAction: "retry_media_sync",
  });
});

Deno.test("maps case draft metadata to the server-derived task RPC", async () => {
  let rpcName = "";
  let rpcArgs: Record<string, unknown> = {};
  const supabase = {
    async rpc(name: string, args: Record<string, unknown>) {
      rpcName = name;
      rpcArgs = args;
      return {
        data: {
          status: "created",
          item: { id: "knowledge-case-version-a", status: "uploaded" },
        },
        error: null,
      };
    },
  };

  const result = await createSkillKnowledgeManagementGateway(supabase)
    .createKnowledgeCaseDraft(admin, "task-a", {
      knowledgeKey: "case_task_a",
      version: 1,
      title: "控制器离线维修案例",
      sensitivity: "internal",
      license: "华方内部授权",
      knowledgeScopes: ["hvac/ddc"],
      reason: "人工确认后沉淀现场案例",
      idempotencyKey: "case-task-a",
    });

  assertEquals(rpcName, "create_knowledge_case_draft_from_task");
  assertEquals(rpcArgs, {
    target_task_id: "task-a",
    draft_knowledge_key: "case_task_a",
    draft_version: 1,
    draft_title: "控制器离线维修案例",
    draft_sensitivity: "internal",
    draft_license: "华方内部授权",
    draft_knowledge_scopes: ["hvac/ddc"],
    actor_id: "admin-a",
    command_reason: "人工确认后沉淀现场案例",
    idempotency_key: "case-task-a",
  });
  assertEquals(result, {
    status: "created",
    item: { id: "knowledge-case-version-a", status: "uploaded" },
  });
});

Deno.test("stores a parsed text attachment at a server-derived private path", async () => {
  const bytes = new TextEncoder().encode("第一步：检查供电。\n第二步：检查总线。");
  const sha256 = await sha256Hex(bytes);
  let attachmentReads = 0;
  let versionReads = 0;
  let insertedAttachment: Record<string, unknown> | null = null;
  let storageUpload: Record<string, unknown> | null = null;
  let completion: Record<string, unknown> | null = null;
  const finalVersion = {
    id: "11111111-1111-4111-8111-111111111111",
    status: "parsed",
    source_type: "uploaded_file",
    source_reference: "ddc-guide.md",
  };
  const supabase = {
    from(table: string) {
      if (table === "knowledge_versions") {
        versionReads += 1;
        return maybeSingleQuery(versionReads === 1
          ? { ...finalVersion, status: "uploaded", source_reference: "ddc-guide.md" }
          : finalVersion);
      }
      if (table === "knowledge_version_attachments") {
        attachmentReads += 1;
        if (attachmentReads === 1) return maybeSingleQuery(null);
        if (attachmentReads === 2) {
          return {
            insert(value: Record<string, unknown>) {
              insertedAttachment = value;
              return {
                select() {
                  return { single: async () => ({ data: value, error: null }) };
                },
              };
            },
          };
        }
        return maybeSingleQuery({
          ...insertedAttachment,
          status: "parsed",
          is_current: true,
          extracted_content_sha256: "c".repeat(64),
          processing_error: "",
          created_at: "2026-08-05T10:00:00.000Z",
          updated_at: "2026-08-05T10:00:01.000Z",
        });
      }
      throw new Error(`unexpected table ${table}`);
    },
    storage: {
      from(bucket: string) {
        return {
          async upload(path: string, uploaded: Uint8Array, options: Record<string, unknown>) {
            storageUpload = { bucket, path, bytes: Array.from(uploaded), options };
            return { data: { path }, error: null };
          },
          async createSignedUrl(path: string, expiresIn: number) {
            return { data: { signedUrl: `https://signed.example/${path}`, expiresIn }, error: null };
          },
        };
      },
    },
    async rpc(name: string, args: Record<string, unknown>) {
      completion = { name, args };
      return { data: { id: insertedAttachment?.id, status: "parsed" }, error: null };
    },
  };

  const result = await createSkillKnowledgeManagementGateway(supabase)
    .uploadKnowledgeAttachment(admin, finalVersion.id, {
      fileName: "ddc-guide.md",
      contentType: "text/markdown",
      bytes,
      claimedSha256: sha256,
      idempotencyKey: "attachment-upload-real-a",
    });

  const recordedStorageUpload = storageUpload as Record<string, unknown> | null;
  const recordedCompletion = completion as Record<string, unknown> | null;
  const recordedAttachment = insertedAttachment as Record<string, unknown> | null;
  const path = String(recordedStorageUpload?.path ?? "");
  assertEquals(recordedStorageUpload?.bucket, "ops-knowledge-attachments");
  assertEquals(path.startsWith(`knowledge/org-a/${finalVersion.id}/`), true);
  assertEquals(path.endsWith("/source.md"), true);
  assertEquals(path.includes("ddc-guide"), false);
  assertEquals(recordedCompletion?.name, "complete_knowledge_attachment_processing");
  assertEquals(recordedCompletion?.args, {
    target_attachment_id: recordedAttachment?.id,
    expected_status: "uploaded",
    new_status: "parsed",
    extracted_content: "第一步：检查供电。\n第二步：检查总线。",
    extracted_content_sha256: await sha256Hex(bytes),
    new_processing_error: "",
    actor_id: "admin-a",
    command_reason: "知识附件完整性校验与文本解析完成",
    idempotency_key: "attachment-upload-real-a:complete",
  });
  assertEquals(result?.version, finalVersion);
  assertEquals("storage_bucket" in (result?.attachment ?? {}), false);
  assertEquals("storage_path" in (result?.attachment ?? {}), false);
  assertEquals(String(result?.attachment.download_url).startsWith("https://signed.example/knowledge/org-a/"), true);
});

Deno.test("parses a PDF through the configured private parser before marking it parsed", async () => {
  const bytes = new TextEncoder().encode("%PDF-1.7\nprivate parser fixture");
  const fileSha256 = await sha256Hex(bytes);
  const content = "设备收货检查\n确认包装无破损";
  const contentSha256 = await sha256Hex(new TextEncoder().encode(content));
  let attachmentReads = 0;
  let versionReads = 0;
  let insertedAttachment: Record<string, unknown> | null = null;
  let completion: Record<string, unknown> | null = null;
  let parserInput: Record<string, unknown> | null = null;
  const finalVersion = {
    id: "22222222-2222-4222-8222-222222222222",
    status: "parsed",
    source_type: "uploaded_file",
    source_reference: "receipt.pdf",
  };
  const parser: KnowledgeDocumentParser = {
    async parse(input) {
      parserInput = input;
      return { content, contentSha256 };
    },
  };
  const supabase = {
    from(table: string) {
      if (table === "knowledge_versions") {
        versionReads += 1;
        return maybeSingleQuery(versionReads === 1
          ? { ...finalVersion, status: "uploaded" }
          : finalVersion);
      }
      if (table === "knowledge_version_attachments") {
        attachmentReads += 1;
        if (attachmentReads === 1) return maybeSingleQuery(null);
        if (attachmentReads === 2) {
          return {
            insert(value: Record<string, unknown>) {
              insertedAttachment = value;
              return {
                select() {
                  return { single: async () => ({ data: value, error: null }) };
                },
              };
            },
          };
        }
        return maybeSingleQuery({
          ...insertedAttachment,
          status: "parsed",
          is_current: true,
          extracted_content_sha256: contentSha256,
          processing_error: "",
          created_at: "2026-08-05T10:00:00.000Z",
          updated_at: "2026-08-05T10:00:01.000Z",
        });
      }
      throw new Error(`unexpected table ${table}`);
    },
    storage: {
      from() {
        return {
          async upload(path: string) {
            return { data: { path }, error: null };
          },
          async createSignedUrl(path: string) {
            return { data: { signedUrl: `https://signed.example/${path}` }, error: null };
          },
        };
      },
    },
    async rpc(name: string, args: Record<string, unknown>) {
      completion = { name, args };
      return { data: { id: insertedAttachment?.id, status: "parsed" }, error: null };
    },
  };

  const result = await createSkillKnowledgeManagementGateway(supabase, parser)
    .uploadKnowledgeAttachment(admin, finalVersion.id, {
      fileName: "receipt.pdf",
      contentType: "application/pdf",
      bytes,
      claimedSha256: fileSha256,
      idempotencyKey: "attachment-upload-pdf-a",
    });

  const recordedCompletion = completion as Record<string, unknown> | null;
  const recordedAttachment = insertedAttachment as Record<string, unknown> | null;
  assertEquals(parserInput, {
    fileName: "receipt.pdf",
    contentType: "application/pdf",
    bytes,
    fileSha256,
  });
  assertEquals(recordedCompletion?.name, "complete_knowledge_attachment_processing");
  assertEquals(recordedCompletion?.args, {
    target_attachment_id: recordedAttachment?.id,
    expected_status: "uploaded",
    new_status: "parsed",
    extracted_content: content,
    extracted_content_sha256: contentSha256,
    new_processing_error: "",
    actor_id: "admin-a",
    command_reason: "知识附件完整性校验与文档解析完成",
    idempotency_key: "attachment-upload-pdf-a:complete",
  });
  assertEquals(result?.version, finalVersion);
  assertEquals(result?.attachment.status, "parsed");
});

Deno.test("returns attachment history with batch signed URLs and no storage internals", async () => {
  const rawAttachment = {
    id: "attachment-a",
    knowledge_version_id: "knowledge-version-a",
    status: "parsed",
    original_file_name: "ddc-guide.md",
    content_type: "text/markdown",
    byte_size: 120,
    file_sha256: "a".repeat(64),
    extracted_content_sha256: "b".repeat(64),
    storage_bucket: "ops-knowledge-attachments",
    storage_path: "knowledge/org-a/knowledge-version-a/attachment-a/source.md",
    processing_error: "",
    is_current: true,
    created_at: "2026-08-05T10:00:00.000Z",
    updated_at: "2026-08-05T10:00:01.000Z",
  };
  const version = {
    id: "knowledge-version-a",
    knowledge_entry_id: "knowledge-a",
    version: 1,
    status: "parsed",
    knowledge_grants: [],
    knowledge_version_attachments: [rawAttachment],
  };
  const supabase = {
    from(table: string) {
      if (table === "knowledge_entries") return maybeSingleQuery({ id: "knowledge-a" });
      if (table === "knowledge_versions") return orderedQuery([version]);
      throw new Error(`unexpected table ${table}`);
    },
    storage: {
      from(bucket: string) {
        assertEquals(bucket, "ops-knowledge-attachments");
        return {
          async createSignedUrls(paths: string[], expiresIn: number) {
            assertEquals(paths, [rawAttachment.storage_path]);
            assertEquals(expiresIn, 300);
            return {
              data: paths.map((path) => ({ path, signedUrl: `https://signed.example/${path}` })),
              error: null,
            };
          },
        };
      },
    },
  };

  const result = await createSkillKnowledgeManagementGateway(supabase)
    .listKnowledgeVersions(admin, "knowledge-a");
  const attachment = result?.[0]?.knowledge_attachments as Record<string, unknown>[];

  assertEquals(Array.isArray(attachment), true);
  assertEquals(attachment[0].download_url, `https://signed.example/${rawAttachment.storage_path}`);
  assertEquals("storage_bucket" in attachment[0], false);
  assertEquals("storage_path" in attachment[0], false);
  assertEquals("knowledge_version_attachments" in (result?.[0] ?? {}), false);
});

Deno.test("retries a failed text attachment from private storage without accepting client content", async () => {
  const bytes = new TextEncoder().encode("重试后解析的受控正文");
  const sha256 = await sha256Hex(bytes);
  let attachmentReads = 0;
  let completion: Record<string, unknown> | null = null;
  const failedAttachment = {
    id: "attachment-retry-a",
    knowledge_version_id: "knowledge-version-a",
    status: "failed",
    original_file_name: "retry.txt",
    content_type: "text/plain",
    byte_size: bytes.byteLength,
    file_sha256: sha256,
    extracted_content_sha256: "",
    storage_bucket: "ops-knowledge-attachments",
    storage_path: "knowledge/org-a/knowledge-version-a/attachment-retry-a/source.txt",
    processing_error: "knowledge_attachment_text_invalid",
    is_current: false,
    created_at: "2026-08-05T10:00:00.000Z",
    updated_at: "2026-08-05T10:00:01.000Z",
  };
  const finalAttachment = {
    ...failedAttachment,
    status: "parsed",
    extracted_content_sha256: sha256,
    processing_error: "",
    is_current: true,
  };
  const finalVersion = {
    id: "knowledge-version-a",
    status: "parsed",
    source_type: "uploaded_file",
    source_reference: "retry.txt",
  };
  const supabase = {
    from(table: string) {
      if (table === "knowledge_version_attachments") {
        attachmentReads += 1;
        return maybeSingleQuery(attachmentReads === 1 ? failedAttachment : finalAttachment);
      }
      if (table === "knowledge_versions") return maybeSingleQuery(finalVersion);
      throw new Error(`unexpected table ${table}`);
    },
    storage: {
      from(bucket: string) {
        assertEquals(bucket, "ops-knowledge-attachments");
        return {
          async download(path: string) {
            assertEquals(path, failedAttachment.storage_path);
            return { data: new Blob([bytes], { type: "text/plain" }), error: null };
          },
          async createSignedUrl(path: string) {
            return { data: { signedUrl: `https://signed.example/${path}` }, error: null };
          },
        };
      },
    },
    async rpc(name: string, args: Record<string, unknown>) {
      completion = { name, args };
      return { data: finalAttachment, error: null };
    },
  };

  const result = await createSkillKnowledgeManagementGateway(supabase)
    .retryKnowledgeAttachment(admin, failedAttachment.id, {
      reason: "重新执行文本解析",
      idempotencyKey: "attachment-retry-real-a",
    });

  const recordedCompletion = completion as Record<string, unknown> | null;
  assertEquals(recordedCompletion?.name, "complete_knowledge_attachment_processing");
  assertEquals(recordedCompletion?.args, {
    target_attachment_id: failedAttachment.id,
    expected_status: "failed",
    new_status: "parsed",
    extracted_content: "重试后解析的受控正文",
    extracted_content_sha256: sha256,
    new_processing_error: "",
    actor_id: "admin-a",
    command_reason: "重新执行文本解析",
    idempotency_key: "attachment-retry-real-a:complete",
  });
  assertEquals(result?.version, finalVersion);
  assertEquals(result?.attachment.status, "parsed");
});

Deno.test("retries a stored PDF after the private parser becomes available", async () => {
  const bytes = new TextEncoder().encode("%PDF-1.7\nretry parser fixture");
  const fileSha256 = await sha256Hex(bytes);
  const content = "重试后解析的 PDF 正文";
  const contentSha256 = await sha256Hex(new TextEncoder().encode(content));
  let attachmentReads = 0;
  let completion: Record<string, unknown> | null = null;
  let parserCalls = 0;
  const pendingAttachment = {
    id: "attachment-retry-pdf-a",
    knowledge_version_id: "knowledge-version-pdf-a",
    status: "processing_unavailable",
    original_file_name: "manual.pdf",
    content_type: "application/pdf",
    byte_size: bytes.byteLength,
    file_sha256: fileSha256,
    extracted_content_sha256: "",
    storage_bucket: "ops-knowledge-attachments",
    storage_path: "knowledge/org-a/knowledge-version-pdf-a/attachment-retry-pdf-a/source.pdf",
    processing_error: "knowledge_attachment_processor_unavailable",
    is_current: false,
    created_at: "2026-08-05T10:00:00.000Z",
    updated_at: "2026-08-05T10:00:01.000Z",
  };
  const finalAttachment = {
    ...pendingAttachment,
    status: "parsed",
    extracted_content_sha256: contentSha256,
    processing_error: "",
    is_current: true,
  };
  const finalVersion = {
    id: "knowledge-version-pdf-a",
    status: "parsed",
    source_type: "uploaded_file",
    source_reference: "manual.pdf",
  };
  const parser: KnowledgeDocumentParser = {
    async parse(input) {
      parserCalls += 1;
      assertEquals(input.fileSha256, fileSha256);
      return { content, contentSha256 };
    },
  };
  const supabase = {
    from(table: string) {
      if (table === "knowledge_version_attachments") {
        attachmentReads += 1;
        return maybeSingleQuery(attachmentReads === 1 ? pendingAttachment : finalAttachment);
      }
      if (table === "knowledge_versions") return maybeSingleQuery(finalVersion);
      throw new Error(`unexpected table ${table}`);
    },
    storage: {
      from() {
        return {
          async download() {
            return { data: new Blob([bytes], { type: "application/pdf" }), error: null };
          },
          async createSignedUrl(path: string) {
            return { data: { signedUrl: `https://signed.example/${path}` }, error: null };
          },
        };
      },
    },
    async rpc(name: string, args: Record<string, unknown>) {
      completion = { name, args };
      return { data: finalAttachment, error: null };
    },
  };

  const result = await createSkillKnowledgeManagementGateway(supabase, parser)
    .retryKnowledgeAttachment(admin, pendingAttachment.id, {
      reason: "重新执行 PDF 解析",
      idempotencyKey: "attachment-retry-pdf-a",
    });

  const recordedCompletion = completion as Record<string, unknown> | null;
  assertEquals(parserCalls, 1);
  assertEquals(recordedCompletion?.args, {
    target_attachment_id: pendingAttachment.id,
    expected_status: "processing_unavailable",
    new_status: "parsed",
    extracted_content: content,
    extracted_content_sha256: contentSha256,
    new_processing_error: "",
    actor_id: "admin-a",
    command_reason: "重新执行 PDF 解析",
    idempotency_key: "attachment-retry-pdf-a:complete",
  });
  assertEquals(result?.attachment.status, "parsed");
});

Deno.test("keeps a PDF processing-unavailable when the private parser returns 503", async () => {
  const fixture = await parserFailureUploadFixture(503, "knowledge_document_processor_unavailable");

  const error = await assertRejects(
    () => createSkillKnowledgeManagementGateway(fixture.supabase, fixture.parser)
      .uploadKnowledgeAttachment(admin, fixture.versionId, fixture.command),
    SkillKnowledgeManagementError,
    "knowledge_document_processor_unavailable",
  );

  assertEquals(error.status, 503);
  assertEquals(error.recoverableAction, "retry_attachment_processing");
  assertEquals(fixture.completion()?.args.new_status, "processing_unavailable");
  assertEquals(fixture.completion()?.args.new_processing_error, "knowledge_document_processor_unavailable");
});

Deno.test("marks a PDF failed when the private parser rejects an invalid document", async () => {
  const fixture = await parserFailureUploadFixture(422, "knowledge_document_pdf_invalid");

  const error = await assertRejects(
    () => createSkillKnowledgeManagementGateway(fixture.supabase, fixture.parser)
      .uploadKnowledgeAttachment(admin, fixture.versionId, fixture.command),
    SkillKnowledgeManagementError,
    "knowledge_document_pdf_invalid",
  );

  assertEquals(error.status, 422);
  assertEquals(error.recoverableAction, "replace_attachment");
  assertEquals(fixture.completion()?.args.new_status, "failed");
  assertEquals(fixture.completion()?.args.new_processing_error, "knowledge_document_pdf_invalid");
});

Deno.test("maps controlled attachment gateway failures to recoverable HTTP errors", async () => {
  const bytes = new TextEncoder().encode("受控正文");
  const response = await routeSkillKnowledgeManagement(
    attachmentRequest("/management/knowledge-versions/version-a/attachments", {
      file: new File([bytes], "guide.txt", { type: "text/plain" }),
      sha256: await sha256Hex(bytes),
      idempotencyKey: "attachment-error-a",
      confirmation: "UPLOAD_KNOWLEDGE_ATTACHMENT",
    }),
    gateway({
      uploadKnowledgeAttachment: async () => {
        throw new SkillKnowledgeManagementError(
          409,
          "knowledge_version_does_not_accept_attachment",
          "create_uploaded_file_draft",
        );
      },
    }),
  );

  assertEquals(response.status, 409);
  assertEquals(await response.json(), {
    ok: false,
    error: "knowledge_version_does_not_accept_attachment",
    recoverableAction: "create_uploaded_file_draft",
  });
});

Deno.test("requires confirmation for knowledge publication grants and revocation", async () => {
  const publish = await routeSkillKnowledgeManagement(jsonRequest(
    "POST",
    "/management/knowledge-versions/version-a/transition",
    { expectedStatus: "review_pending", newStatus: "published", reason: "审核通过", idempotencyKey: "kp-a" },
  ), gateway());
  const grant = await routeSkillKnowledgeManagement(jsonRequest(
    "POST",
    "/management/knowledge-versions/version-a/grants",
    { scopeType: "skill_version", scopeId: "skill-version-a", reason: "授权当前技能", idempotencyKey: "kg-a" },
  ), gateway());
  const revoke = await routeSkillKnowledgeManagement(jsonRequest(
    "POST",
    "/management/knowledge-grants/grant-a/revoke",
    { reason: "撤销错误资料", idempotencyKey: "kr-a" },
  ), gateway());

  assertEquals(publish.status, 400);
  assertEquals(grant.status, 400);
  assertEquals(revoke.status, 400);
});

Deno.test("rejects invalid DTOs before any management write", async () => {
  let calls = 0;
  const invalidSkill = await routeSkillKnowledgeManagement(jsonRequest("POST", "/management/skills", {
    skillKey: "BAD KEY",
    version: "latest",
    name: "",
    description: "x",
    rules: { endpoint: "https://unsafe.example" },
    testCases: [],
    reason: "x",
    idempotencyKey: "",
  }), gateway({ createSkillDraft: async () => { calls += 1; return {}; } }));
  const invalidKnowledge = await routeSkillKnowledgeManagement(jsonRequest("POST", "/management/knowledge", {
    knowledgeKey: "hf_ddc_guide",
    version: 1,
    ...knowledgeDraft(),
    sourceReference: "https://unsafe.example/manual",
    reason: "导入厂商手册",
    idempotencyKey: "knowledge-create-a",
  }), gateway({ createKnowledgeDraft: async () => { calls += 1; return {}; } }));

  assertEquals(invalidSkill.status, 400);
  assertEquals(invalidKnowledge.status, 400);
  assertEquals(calls, 0);
});

Deno.test("returns not found for unknown Skill and knowledge resources", async () => {
  const skill = await routeSkillKnowledgeManagement(
    request("GET", "/management/skills/missing/versions", "admin-token"),
    gateway(),
  );
  const knowledge = await routeSkillKnowledgeManagement(
    request("GET", "/management/knowledge/missing/versions", "admin-token"),
    gateway(),
  );
  assertEquals(skill.status, 404);
  assertEquals(knowledge.status, 404);
});

Deno.test("accepts a strict confirmed multipart knowledge attachment upload", async () => {
  const bytes = new TextEncoder().encode("第一步：检查供电。\n第二步：检查总线。");
  const sha256 = await sha256Hex(bytes);
  let received: unknown = null;
  const response = await routeSkillKnowledgeManagement(
    attachmentRequest("/management/knowledge-versions/version-a/attachments", {
      file: new File([bytes], "ddc-guide.md", { type: "text/markdown" }),
      sha256,
      idempotencyKey: "attachment-upload-a",
      confirmation: "UPLOAD_KNOWLEDGE_ATTACHMENT",
    }),
    gateway({
      uploadKnowledgeAttachment: async (identity, versionId, command) => {
        received = { identity, versionId, command: { ...command, bytes: Array.from(command.bytes) } };
        return {
          version: { id: versionId, status: "parsed" },
          attachment: { id: "attachment-a", status: "parsed" },
        };
      },
    }),
  );

  assertEquals(response.status, 201);
  assertEquals(received, {
    identity: admin,
    versionId: "version-a",
    command: {
      fileName: "ddc-guide.md",
      contentType: "text/markdown",
      bytes: Array.from(bytes),
      claimedSha256: sha256,
      idempotencyKey: "attachment-upload-a",
    },
  });
});

Deno.test("rejects unconfirmed or forged multipart attachment fields before storage", async () => {
  const bytes = new TextEncoder().encode("受控知识正文");
  const sha256 = await sha256Hex(bytes);
  let calls = 0;
  const unconfirmed = await routeSkillKnowledgeManagement(
    attachmentRequest("/management/knowledge-versions/version-a/attachments", {
      file: new File([bytes], "guide.txt", { type: "text/plain" }),
      sha256,
      idempotencyKey: "attachment-unconfirmed-a",
    }),
    gateway({ uploadKnowledgeAttachment: async () => { calls += 1; return null; } }),
  );
  const forged = await routeSkillKnowledgeManagement(
    attachmentRequest("/management/knowledge-versions/version-a/attachments", {
      file: new File([bytes], "guide.txt", { type: "text/plain" }),
      sha256,
      idempotencyKey: "attachment-forged-a",
      confirmation: "UPLOAD_KNOWLEDGE_ATTACHMENT",
      storagePath: "forged/path.txt",
    }),
    gateway({ uploadKnowledgeAttachment: async () => { calls += 1; return null; } }),
  );

  assertEquals(unconfirmed.status, 400);
  assertEquals(await unconfirmed.json(), { ok: false, error: "confirmation_required" });
  assertEquals(forged.status, 400);
  assertEquals(await forged.json(), { ok: false, error: "invalid_request" });
  assertEquals(calls, 0);
});

Deno.test("returns an explicit accepted state when a real document processor is unavailable", async () => {
  const bytes = new TextEncoder().encode("%PDF-1.7\nfixture");
  const response = await routeSkillKnowledgeManagement(
    attachmentRequest("/management/knowledge-versions/version-a/attachments", {
      file: new File([bytes], "manual.pdf", { type: "application/pdf" }),
      sha256: await sha256Hex(bytes),
      idempotencyKey: "attachment-pdf-a",
      confirmation: "UPLOAD_KNOWLEDGE_ATTACHMENT",
    }),
    gateway({
      uploadKnowledgeAttachment: async () => ({
        version: { id: "version-a", status: "uploaded" },
        attachment: { id: "attachment-pdf-a", status: "processing_unavailable" },
      }),
    }),
  );

  assertEquals(response.status, 202);
  assertEquals(await response.json(), {
    version: { id: "version-a", status: "uploaded" },
    attachment: { id: "attachment-pdf-a", status: "processing_unavailable" },
    recoverableAction: "retry_attachment_processing",
  });
});

Deno.test("retries stored attachment processing only after explicit confirmation", async () => {
  let received: unknown = null;
  const missingConfirmation = await routeSkillKnowledgeManagement(jsonRequest(
    "POST",
    "/management/knowledge-attachments/attachment-a/retry",
    { reason: "重新执行文本解析", idempotencyKey: "retry-a" },
  ), gateway());
  const response = await routeSkillKnowledgeManagement(jsonRequest(
    "POST",
    "/management/knowledge-attachments/attachment-a/retry",
    {
      reason: "重新执行文本解析",
      idempotencyKey: "retry-b",
      confirmation: "RETRY_KNOWLEDGE_ATTACHMENT",
    },
  ), gateway({
    retryKnowledgeAttachment: async (identity, attachmentId, command) => {
      received = { identity, attachmentId, command };
      return {
        version: { id: "knowledge-version-a", status: "parsed" },
        attachment: { id: attachmentId, status: "parsed" },
      };
    },
  }));

  assertEquals(missingConfirmation.status, 400);
  assertEquals(response.status, 200);
  assertEquals(received, {
    identity: admin,
    attachmentId: "attachment-a",
    command: { reason: "重新执行文本解析", idempotencyKey: "retry-b" },
  });
});

function request(method: string, path: string, token?: string): Request {
  const headers = new Headers();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return new Request(`https://ops.example${path}`, { method, headers });
}

function jsonRequest(method: string, path: string, body: Record<string, unknown>): Request {
  return new Request(`https://ops.example${path}`, {
    method,
    headers: { Authorization: "Bearer admin-token", "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

function attachmentRequest(path: string, fields: Record<string, string | File>): Request {
  const body = new FormData();
  for (const [key, value] of Object.entries(fields)) body.set(key, value);
  return new Request(`https://ops.example${path}`, {
    method: "POST",
    headers: { Authorization: "Bearer admin-token" },
    body,
  });
}

function maybeSingleQuery(data: Record<string, unknown> | null) {
  const query = {
    select() { return query; },
    eq() { return query; },
    maybeSingle: async () => ({ data, error: null }),
  };
  return query;
}

function orderedQuery(data: Array<Record<string, unknown>>) {
  const query = {
    select() { return query; },
    eq() { return query; },
    order: async () => ({ data, error: null }),
  };
  return query;
}

async function parserFailureUploadFixture(status: number, code: string) {
  const bytes = new TextEncoder().encode("%PDF-1.7\nparser failure fixture");
  const claimedSha256 = await sha256Hex(bytes);
  const versionId = "33333333-3333-4333-8333-333333333333";
  let attachmentReads = 0;
  let insertedAttachment: Record<string, unknown> | null = null;
  let completion: Record<string, unknown> | null = null;
  const parser: KnowledgeDocumentParser = {
    async parse() {
      throw new KnowledgeDocumentParserClientError(code, status);
    },
  };
  const supabase = {
    from(table: string) {
      if (table === "knowledge_versions") {
        return maybeSingleQuery({
          id: versionId,
          status: "uploaded",
          source_type: "uploaded_file",
          source_reference: "manual.pdf",
        });
      }
      if (table === "knowledge_version_attachments") {
        attachmentReads += 1;
        if (attachmentReads === 1) return maybeSingleQuery(null);
        return {
          insert(value: Record<string, unknown>) {
            insertedAttachment = value;
            return {
              select() {
                return { single: async () => ({ data: value, error: null }) };
              },
            };
          },
        };
      }
      throw new Error(`unexpected table ${table}`);
    },
    storage: {
      from() {
        return {
          async upload(path: string) {
            return { data: { path }, error: null };
          },
        };
      },
    },
    async rpc(name: string, args: Record<string, unknown>) {
      completion = { name, args };
      return { data: insertedAttachment, error: null };
    },
  };
  return {
    supabase,
    parser,
    versionId,
    command: {
      fileName: "manual.pdf",
      contentType: "application/pdf",
      bytes,
      claimedSha256,
      idempotencyKey: `attachment-parser-failure-${status}`,
    },
    completion: () => completion as { name: string; args: Record<string, unknown> } | null,
  };
}

function skillRules(): Record<string, unknown> {
  return {
    applicableWhen: {},
    excludedWhen: {},
    requiredInputs: ["alarm_code"],
    evidenceSchema: {},
    steps: [{ id: "record_alarm", instruction: "先记录故障码", risk: "low" }],
    safety: {},
    outputConstraints: {},
    knowledgeScopes: ["hvac/ddc"],
  };
}

function knowledgeDraft(): Record<string, unknown> {
  return {
    title: "DDC 离线排查",
    summary: "确认供电、总线和地址配置。",
    sourceType: "manual",
    sourceReference: "HF-DDC-100-2026-R2",
    language: "zh-CN",
    sensitivity: "internal",
    license: "华方内部授权",
    projectIds: [],
    deviceModels: ["HF-DDC-100"],
    skillIds: ["hvac_ddc_repair"],
    knowledgeScopes: ["hvac/ddc"],
    content: "先确认 24V 供电，再检查总线极性。",
  };
}
