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

  it("opens an existing workflow in the real studio route", async () => {
    const user = userEvent.setup();
    render(<App api={workflowApi()} initialAuthenticated />);

    await user.click(screen.getByRole("link", { name: "现场应用" }));
    await user.click(await screen.findByRole("button", { name: /设备收货检查/ }));

    expect(await screen.findByRole("heading", { name: "工作流编排", level: 1 })).toBeVisible();
    expect(await screen.findByRole("heading", { name: "设备收货检查" })).toBeVisible();
  });
});
