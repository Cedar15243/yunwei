import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  createManagementApi,
  ManagementApiError,
} from "./management-api";

const baseUrl = "https://ops.example.test/functions/v1/ops-glasses/";
const token = "session-token";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function requestAt(index = 0): [string, RequestInit] {
  const call = vi.mocked(fetch).mock.calls[index];
  return [String(call[0]), (call[1] ?? {}) as RequestInit];
}

describe("workflow management API", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => jsonResponse({ items: [] })));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("reads the workflow catalog, field apps, workflows and immutable versions", async () => {
    const api = createManagementApi(baseUrl, async () => token);

    await api.getWorkflowCatalog();
    await api.getFieldApps();
    await api.getWorkflows("field app/a");
    await api.getWorkflow("workflow/a");
    await api.getWorkflowVersions("workflow/a");
    await api.getWorkflowVersion("version/a");

    expect(vi.mocked(fetch).mock.calls.map((_, index) => requestAt(index)[0])).toEqual([
      "https://ops.example.test/functions/v1/ops-glasses/management/workflow-catalog",
      "https://ops.example.test/functions/v1/ops-glasses/management/field-apps",
      "https://ops.example.test/functions/v1/ops-glasses/management/field-apps/field%20app%2Fa/workflows",
      "https://ops.example.test/functions/v1/ops-glasses/management/workflows/workflow%2Fa",
      "https://ops.example.test/functions/v1/ops-glasses/management/workflows/workflow%2Fa/versions",
      "https://ops.example.test/functions/v1/ops-glasses/management/workflow-versions/version%2Fa",
    ]);
  });

  it("creates field apps and workflows with JSON request bodies", async () => {
    const api = createManagementApi(baseUrl, async () => token);

    await api.createFieldApp({
      appKey: "receiving",
      name: "设备收货",
      description: "采集收货与配置证据",
      iconKey: "package-check",
      entryMode: "both",
    });
    await api.createWorkflow("field/app", {
      workflowKey: "receive_device",
      title: "设备收货检查",
      description: "按现场要求完成证据采集",
      schemaVersion: 1,
    });

    const [, fieldAppInit] = requestAt(0);
    const [workflowUrl, workflowInit] = requestAt(1);
    expect(fieldAppInit).toMatchObject({
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
    });
    expect(JSON.parse(String(fieldAppInit.body))).toMatchObject({ appKey: "receiving" });
    expect(workflowUrl).toContain("/management/field-apps/field%2Fapp/workflows");
    expect(workflowInit.method).toBe("POST");
    expect(JSON.parse(String(workflowInit.body))).toMatchObject({ workflowKey: "receive_device" });
  });

  it("saves and validates a workflow draft through the server contract", async () => {
    const api = createManagementApi(baseUrl, async () => token);
    const draft = {
      workflowId: "workflow/a",
      schemaVersion: 1,
      title: "收货检查",
      nodes: [],
      transitions: [],
    };

    await api.saveWorkflowDraft("workflow/a", draft);
    await api.validateWorkflow("workflow/a");

    const [saveUrl, saveInit] = requestAt(0);
    const [validationUrl, validationInit] = requestAt(1);
    expect(saveUrl).toContain("/management/workflows/workflow%2Fa/draft");
    expect(saveInit.method).toBe("PUT");
    expect(saveInit.headers).toMatchObject({ "Content-Type": "application/json" });
    expect(JSON.parse(String(saveInit.body))).toEqual({ draft });
    expect(validationUrl).toContain("/management/workflows/workflow%2Fa/validate");
    expect(validationInit.method).toBe("POST");
  });

  it("adds explicit confirmation payloads for high-impact workflow commands", async () => {
    const api = createManagementApi(baseUrl, async () => token);
    const rule = {
      ruleKey: "project_receiving",
      source: "project" as const,
      mode: "required" as const,
      workflowVersionId: "44444444-4444-4444-8444-444444444444",
      matchConditions: [{ field: "projectId", operator: "eq" as const, value: "project-a" }],
      enabled: true,
      activeFrom: null,
      activeUntil: null,
      reason: "启用项目收货流程",
      idempotencyKey: "rule-create-1",
    };

    await api.publishWorkflow("workflow/a", {
      reason: "通过样例与异常路径验证",
      minAppVersionCode: 9000,
      confirmation: "FORGED_CONFIRMATION",
    } as Parameters<typeof api.publishWorkflow>[1]);
    await api.createWorkflowBindingRule(rule);
    await api.updateWorkflowBindingRule("rule/a", { ...rule, expectedVersion: 3 });
    await api.resolveWorkOrderWorkflow("order/a", {
      reason: "按已发布规则重新解析",
      idempotencyKey: "resolve-order-1",
      assignedProfileId: null,
      assignedDeviceId: null,
    });

    expect(JSON.parse(String(requestAt(0)[1].body))).toEqual({
      confirmation: "PUBLISH_WORKFLOW",
      reason: "通过样例与异常路径验证",
      minAppVersionCode: 9000,
    });
    expect(JSON.parse(String(requestAt(1)[1].body))).toMatchObject({
      confirmation: "CREATE_WORKFLOW_BINDING_RULE",
      ruleKey: "project_receiving",
    });
    expect(requestAt(2)[0]).toContain("/workflow-binding-rules/rule%2Fa");
    expect(JSON.parse(String(requestAt(2)[1].body))).toMatchObject({
      confirmation: "UPDATE_WORKFLOW_BINDING_RULE",
      expectedVersion: 3,
    });
    expect(requestAt(3)[0]).toContain("/work-orders/order%2Fa/resolve-workflow");
    expect(JSON.parse(String(requestAt(3)[1].body))).toMatchObject({
      confirmation: "RESOLVE_WORKFLOW",
      idempotencyKey: "resolve-order-1",
    });
  });

  it("serializes work order filters and reads binding rules", async () => {
    const api = createManagementApi(baseUrl, async () => token);

    await api.getWorkOrders({ status: "received", limit: 50 });
    await api.getWorkflowBindingRules();

    expect(requestAt(0)[0]).toBe(
      "https://ops.example.test/functions/v1/ops-glasses/management/work-orders?status=received&limit=50",
    );
    expect(requestAt(1)[0]).toContain("/management/workflow-binding-rules");
  });

  it("does not parse an empty 204 response", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 204 }));
    const api = createManagementApi(baseUrl, async () => token);

    await expect(api.retryMedia("task/a", "media/a")).resolves.toBeUndefined();
  });

  it.each([
    [422, { error: "workflow_invalid", validationErrors: [{ code: "complete_required", path: "$.nodes" }] }],
    [503, { error: "signing_unavailable" }],
    [409, { error: "workflow_already_started" }],
    [409, { error: "conflict", candidateIds: ["rule-a", "rule-b"] }],
  ])("preserves structured management errors for status %s", async (status, payload) => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(payload, status));
    const api = createManagementApi(baseUrl, async () => token);

    const failure = await api.publishWorkflow("workflow-a", {
      reason: "验证错误合同",
      minAppVersionCode: 9000,
    }).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ManagementApiError);
    expect(failure).toMatchObject({ status, code: payload.error, payload });
  });
});
