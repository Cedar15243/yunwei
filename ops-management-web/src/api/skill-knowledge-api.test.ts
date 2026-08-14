import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createManagementApi } from "./management-api";

const baseUrl = "https://ops.example.test/functions/v1/ops-glasses/";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function call(index: number): [string, RequestInit] {
  const [url, init] = vi.mocked(fetch).mock.calls[index];
  return [String(url), (init ?? {}) as RequestInit];
}

describe("Skill and knowledge management API", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => jsonResponse({ items: [] })));
  });

  afterEach(() => vi.unstubAllGlobals());

  it("uses the deployed directory and immutable version routes", async () => {
    const api = createManagementApi(baseUrl, async () => "session-token");

    await api.getSkills();
    await api.getSkillVersions("skill/a");
    await api.getKnowledge();
    await api.getKnowledgeVersions("knowledge/a");

    expect(vi.mocked(fetch).mock.calls.map((_, index) => call(index)[0])).toEqual([
      "https://ops.example.test/functions/v1/ops-glasses/management/skills",
      "https://ops.example.test/functions/v1/ops-glasses/management/skills/skill%2Fa/versions",
      "https://ops.example.test/functions/v1/ops-glasses/management/knowledge",
      "https://ops.example.test/functions/v1/ops-glasses/management/knowledge/knowledge%2Fa/versions",
    ]);
  });

  it("adds server confirmation constants to every high-impact command", async () => {
    const api = createManagementApi(baseUrl, async () => "session-token");
    const reason = "通过审核并投放试点设备";
    const idempotencyKey = "command-a";

    await api.transitionSkillVersion("skill-version/a", {
      expectedStatus: "review_pending",
      newStatus: "published",
      testResult: { passed: 12, failed: 0 },
      reason,
      idempotencyKey,
    });
    await api.assignSkillVersion("skill-version/a", {
      scopeType: "device",
      scopeId: "device-a",
      activeFrom: null,
      expiresAt: null,
      reason,
      idempotencyKey: "assign-a",
    });
    await api.revokeSkillAssignment("assignment/a", { reason, idempotencyKey: "revoke-a" });
    await api.transitionKnowledgeVersion("knowledge-version/a", {
      expectedStatus: "review_pending",
      newStatus: "published",
      processingError: "",
      reason,
      idempotencyKey: "publish-knowledge-a",
    });
    await api.grantKnowledgeVersion("knowledge-version/a", {
      scopeType: "skill_version",
      scopeId: "skill-version-a",
      activeFrom: null,
      expiresAt: null,
      reason,
      idempotencyKey: "grant-a",
    });
    await api.revokeKnowledgeGrant("grant/a", { reason, idempotencyKey: "revoke-grant-a" });
    await api.createKnowledgeCaseDraft("task/a", {
      knowledgeKey: "case_task_a",
      version: 1,
      title: "控制器离线维修案例",
      sensitivity: "internal",
      license: "华方内部授权",
      knowledgeScopes: ["hvac/ddc"],
      reason,
      idempotencyKey: "case-task-a",
    });

    expect(JSON.parse(String(call(0)[1].body))).toMatchObject({ confirmation: "PUBLISH_SKILL" });
    expect(JSON.parse(String(call(1)[1].body))).toMatchObject({ confirmation: "ASSIGN_SKILL" });
    expect(JSON.parse(String(call(2)[1].body))).toMatchObject({ confirmation: "REVOKE_SKILL_ASSIGNMENT" });
    expect(JSON.parse(String(call(3)[1].body))).toMatchObject({ confirmation: "PUBLISH_KNOWLEDGE" });
    expect(JSON.parse(String(call(4)[1].body))).toMatchObject({ confirmation: "GRANT_KNOWLEDGE" });
    expect(JSON.parse(String(call(5)[1].body))).toMatchObject({ confirmation: "REVOKE_KNOWLEDGE_GRANT" });
    expect(call(6)[0]).toBe("https://ops.example.test/functions/v1/ops-glasses/management/tasks/task%2Fa/knowledge-drafts");
    expect(JSON.parse(String(call(6)[1].body))).toEqual({
      knowledgeKey: "case_task_a",
      version: 1,
      title: "控制器离线维修案例",
      sensitivity: "internal",
      license: "华方内部授权",
      knowledgeScopes: ["hvac/ddc"],
      reason,
      idempotencyKey: "case-task-a",
      confirmation: "CREATE_KNOWLEDGE_CASE_DRAFT",
    });
  });

  it("uploads knowledge attachments with Web Crypto and browser-managed multipart boundaries", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({
      version: { id: "knowledge-version-a", status: "parsed" },
      attachment: { id: "attachment-a", status: "parsed" },
    }, 201));
    const api = createManagementApi(baseUrl, async () => "session-token");
    const file = new File(["第一步：检查供电。"], "ddc-guide.md", { type: "text/markdown" });

    await api.uploadKnowledgeAttachment("knowledge-version/a", file, "attachment-upload-a");

    const [url, init] = call(0);
    expect(url).toBe("https://ops.example.test/functions/v1/ops-glasses/management/knowledge-versions/knowledge-version%2Fa/attachments");
    expect(init.method).toBe("POST");
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer session-token");
    expect(new Headers(init.headers).has("Content-Type")).toBe(false);
    expect(init.body).toBeInstanceOf(FormData);
    const body = init.body as FormData;
    expect((body.get("file") as File).name).toBe("ddc-guide.md");
    expect(body.get("sha256")).toMatch(/^[0-9a-f]{64}$/);
    expect(body.get("idempotencyKey")).toBe("attachment-upload-a");
    expect(body.get("confirmation")).toBe("UPLOAD_KNOWLEDGE_ATTACHMENT");
  });

  it("retries attachment processing with an explicit server confirmation", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({
      version: { id: "knowledge-version-a", status: "parsed" },
      attachment: { id: "attachment-a", status: "parsed" },
    }));
    const api = createManagementApi(baseUrl, async () => "session-token");

    await api.retryKnowledgeAttachment("attachment/a", {
      reason: "重新执行文本解析",
      idempotencyKey: "attachment-retry-a",
    });

    expect(call(0)[0]).toBe("https://ops.example.test/functions/v1/ops-glasses/management/knowledge-attachments/attachment%2Fa/retry");
    expect(JSON.parse(String(call(0)[1].body))).toEqual({
      reason: "重新执行文本解析",
      idempotencyKey: "attachment-retry-a",
      confirmation: "RETRY_KNOWLEDGE_ATTACHMENT",
    });
  });
});
