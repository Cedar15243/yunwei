import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { ManagementApi } from "./api/management-api";
import type { FieldApp, WorkflowCatalog, WorkflowDefinition, WorkflowDraft } from "./api/workflow-types";
import { App } from "./App";

const fieldApp: FieldApp = {
  id: "11111111-1111-4111-8111-111111111111",
  app_key: "equipment_receiving",
  name: "设备收货",
  description: "采集收货证据",
  icon_key: "workflow",
  entry_mode: "both",
  status: "draft",
  created_at: "2026-08-01T01:00:00.000Z",
  updated_at: "2026-08-01T02:00:00.000Z",
};

const workflow: WorkflowDefinition = {
  id: "22222222-2222-4222-8222-222222222222",
  field_app_id: fieldApp.id,
  workflow_key: "receive_device",
  title: "设备收货检查",
  description: "按步骤采集现场证据",
  status: "draft",
  schema_version: 1,
  latest_version_number: 0,
  draft_graph: {} as WorkflowDraft,
  created_at: "2026-08-01T01:30:00.000Z",
  updated_at: "2026-08-01T02:30:00.000Z",
};

const catalog: WorkflowCatalog = {
  schemaVersion: 1,
  pageTemplates: ["none", "instruction", "completion"],
  riskLevels: ["low", "medium", "high", "critical"],
  offlinePolicies: ["allowed", "blocked", "server_required"],
  nodes: [
    { type: "start", label: "开始", category: "flow", pageTemplate: "none", requiredCapability: null, fields: [] },
    { type: "instruction", label: "操作说明", category: "content", pageTemplate: "instruction", requiredCapability: null, fields: [] },
    { type: "complete", label: "完成", category: "flow", pageTemplate: "completion", requiredCapability: null, fields: [] },
  ],
};

function workflowApi(): ManagementApi {
  return {
    getDashboard: vi.fn().mockResolvedValue({
      activeTaskCount: 0,
      onlineDeviceCount: 0,
      failedMediaCount: 0,
      recentTasks: [],
    }),
    getFieldApps: vi.fn().mockResolvedValue([fieldApp]),
    getWorkflows: vi.fn().mockResolvedValue([workflow]),
    getWorkflow: vi.fn().mockResolvedValue(workflow),
    getWorkflowCatalog: vi.fn().mockResolvedValue(catalog),
    saveWorkflowDraft: vi.fn().mockResolvedValue(workflow),
    validateWorkflow: vi.fn().mockResolvedValue({ valid: true, validationErrors: [] }),
    publishWorkflow: vi.fn(),
    getWorkflowVersions: vi.fn().mockResolvedValue([]),
    getWorkOrders: vi.fn().mockResolvedValue([]),
    resolveWorkOrderWorkflow: vi.fn(),
  } as unknown as ManagementApi;
}

describe("App", () => {
  it("shows sign in without a session", () => {
    render(<App initialAuthenticated={false} />);
    expect(screen.getByRole("heading", { name: "叮当 AI 运维管理平台" })).toBeVisible();
    expect(screen.getByLabelText("账号邮箱")).toBeVisible();
  });

  it("completes a recovery session by setting a new password and returning to sign in", async () => {
    const user = userEvent.setup();
    const completePasswordRecovery = vi.fn().mockResolvedValue(undefined);
    const auth = {
      restoreSession: vi.fn().mockResolvedValue({ authenticated: true, passwordRecovery: true }),
      signIn: vi.fn(),
      completePasswordRecovery,
    };
    render(<App auth={auth as any} />);

    expect(await screen.findByRole("heading", { name: "设置新密码" })).toBeVisible();
    await user.type(screen.getByLabelText("新密码"), "SecurePass123!");
    await user.type(screen.getByLabelText("确认新密码"), "SecurePass123!");
    await user.click(screen.getByRole("button", { name: "确认更新密码" }));

    expect(completePasswordRecovery).toHaveBeenCalledWith("SecurePass123!");
    expect(await screen.findByText("密码已更新，请使用新密码登录。")).toBeVisible();
    expect(screen.getByRole("heading", { name: "叮当 AI 运维管理平台" })).toBeVisible();
  });

  it("keeps password recovery local when the two new passwords do not match", async () => {
    const user = userEvent.setup();
    const completePasswordRecovery = vi.fn();
    const auth = {
      restoreSession: vi.fn().mockResolvedValue({ authenticated: true, passwordRecovery: true }),
      signIn: vi.fn(),
      completePasswordRecovery,
    };
    render(<App auth={auth as any} />);

    await user.type(await screen.findByLabelText("新密码"), "SecurePass123!");
    await user.type(screen.getByLabelText("确认新密码"), "DifferentPass123!");
    await user.click(screen.getByRole("button", { name: "确认更新密码" }));

    expect(screen.getByRole("alert")).toHaveTextContent("两次输入的新密码不一致");
    expect(completePasswordRecovery).not.toHaveBeenCalled();
  });

  it("keeps desktop navigation after selecting tasks", async () => {
    const user = userEvent.setup();
    render(<App initialAuthenticated />);
    await user.click(screen.getByRole("link", { name: "项目与任务" }));
    expect(screen.getByRole("navigation", { name: "主导航" })).toBeVisible();
    expect(screen.getByRole("heading", { name: "项目与任务", level: 1 })).toBeVisible();
  });

  it("exposes the field application workspace in the existing navigation", async () => {
    const user = userEvent.setup();
    render(<App initialAuthenticated />);

    await user.click(screen.getByRole("link", { name: "现场应用" }));

    expect(screen.getByRole("heading", { name: "现场应用", level: 1 })).toBeVisible();
    expect(screen.getByRole("navigation", { name: "主导航" })).toBeVisible();
  });

  it("exposes AI Skill and Huafang knowledge workspaces in the existing navigation", async () => {
    render(<App initialAuthenticated />);

    expect(screen.getByRole("link", { name: "AI 运维技能" })).toBeVisible();
    expect(screen.getByRole("link", { name: "华方知识库" })).toBeVisible();
  });

  it("opens the independent voiceprint management workspace", async () => {
    const user = userEvent.setup();
    const api = {
      ...workflowApi(),
      getPeople: vi.fn().mockResolvedValue([]),
      getDevices: vi.fn().mockResolvedValue([]),
      getVoiceprints: vi.fn().mockResolvedValue({ items: [], auditChainValid: true }),
      revokeVoiceprint: vi.fn(),
    } as unknown as ManagementApi;
    render(<App api={api} initialAuthenticated />);

    await user.click(screen.getByRole("link", { name: "声纹管理" }));

    expect(screen.getByRole("heading", { name: "声纹管理", level: 1 })).toBeVisible();
    expect(await screen.findByText("当前组织暂无声纹档案。")).toBeVisible();
  }, 10_000);

  it("opens the audit and system operations workspaces from the existing navigation", async () => {
    const user = userEvent.setup();
    const api = {
      ...workflowApi(),
      getAuditEvents: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
      getSystemStatus: vi.fn().mockResolvedValue({
        modelContract: {
          mainAiModel: "qwen3-vl-plus",
          realtimeAsrModel: "fun-asr-realtime",
          wakeEngine: "iflytek-aikit-previous",
          voiceprintService: "iflytek/s1aa729d0",
          locked: true,
        },
        integrations: {
          contentSyncConfigured: true,
          voiceprintAdminConfigured: true,
          deviceActivationBackendConfigured: true,
        },
        contentDistribution: { totalDevices: 0, healthyDevices: 0, issueCount: 0, items: [] },
        generatedAt: "2026-08-03T08:00:00.000Z",
      }),
    } as unknown as ManagementApi;
    render(<App api={api} initialAuthenticated />);

    await user.click(screen.getByRole("link", { name: "统一审计" }));
    expect(screen.getByRole("heading", { name: "统一审计", level: 1 })).toBeVisible();
    expect(await screen.findByText("当前筛选条件没有审计记录。")).toBeVisible();

    await user.click(screen.getByRole("link", { name: "系统运行" }));
    expect(screen.getByRole("heading", { name: "系统运行", level: 1 })).toBeVisible();
    expect(await screen.findByText("模型与安全合同")).toBeVisible();

    await user.click(screen.getByRole("link", { name: "系统设置" }));
    expect(screen.getByRole("heading", { name: "系统设置", level: 1 })).toBeVisible();
    expect(await screen.findByText("身份与密钥边界")).toBeVisible();
  }, 10_000);

  it("opens an existing workflow in the real studio route", async () => {
    const user = userEvent.setup();
    render(<App api={workflowApi()} initialAuthenticated />);

    await user.click(screen.getByRole("link", { name: "现场应用" }));
    await user.click(await screen.findByRole("button", { name: /设备收货检查/ }));

    expect(await screen.findByRole("heading", { name: "工作流编排", level: 1 })).toBeVisible();
    expect(await screen.findByRole("heading", { name: "设备收货检查" })).toBeVisible();
  });
});
