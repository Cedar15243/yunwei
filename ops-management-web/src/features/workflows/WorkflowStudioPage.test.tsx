import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { WorkflowCatalog, WorkflowDefinition, WorkflowDraft } from "../../api/workflow-types";
import { WorkflowStudioPage, type WorkflowStudioApi } from "./WorkflowStudioPage";

const workflow: WorkflowDefinition = {
  id: "22222222-2222-4222-8222-222222222222",
  field_app_id: "11111111-1111-4111-8111-111111111111",
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
    {
      type: "instruction",
      label: "操作说明",
      category: "content",
      pageTemplate: "instruction",
      requiredCapability: null,
      fields: [
        { key: "title", label: "页面标题", kind: "text", maxLength: 160 },
        { key: "description", label: "操作说明", kind: "textarea", maxLength: 4000 },
      ],
    },
    { type: "complete", label: "完成", category: "flow", pageTemplate: "completion", requiredCapability: null, fields: [] },
  ],
};

function api(overrides: Partial<WorkflowStudioApi> = {}): WorkflowStudioApi {
  return {
    getWorkflow: vi.fn().mockResolvedValue(workflow),
    getWorkflowCatalog: vi.fn().mockResolvedValue(catalog),
    ...overrides,
  };
}

describe("WorkflowStudioPage", () => {
  it("loads the workflow and catalog and adds a controlled node to the canvas", async () => {
    const user = userEvent.setup();
    const studioApi = api();
    render(
      <WorkflowStudioPage
        api={studioApi}
        onBack={vi.fn()}
        workflowId={workflow.id}
      />,
    );

    expect(screen.getByText("正在读取工作流…")).toBeVisible();
    expect(await screen.findByRole("heading", { name: "设备收货检查" })).toBeVisible();
    expect(studioApi.getWorkflow).toHaveBeenCalledWith(workflow.id);
    expect(studioApi.getWorkflowCatalog).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "添加操作说明节点" }));

    const canvas = screen.getByRole("region", { name: "工作流画布" });
    expect(within(canvas).getByText("操作说明")).toBeVisible();
    const titleInput = screen.getByRole("textbox", { name: "页面标题" });
    await user.clear(titleInput);
    await user.type(titleInput, "拍摄设备铭牌");

    const preview = screen.getByRole("region", { name: "Air3 HUD 预览" });
    expect(within(preview).getByRole("heading", { name: "拍摄设备铭牌" })).toBeVisible();
    expect(screen.getByText("有未保存修改")).toBeVisible();
  });

  it("keeps a failed load recoverable", async () => {
    const user = userEvent.setup();
    const getWorkflow = vi.fn()
      .mockRejectedValueOnce(new Error("工作流暂时不可用"))
      .mockResolvedValueOnce(workflow);
    render(
      <WorkflowStudioPage
        api={api({ getWorkflow })}
        onBack={vi.fn()}
        workflowId={workflow.id}
      />,
    );

    expect(await screen.findByRole("alert")).toHaveTextContent("工作流暂时不可用");
    await user.click(screen.getByRole("button", { name: "重试" }));

    expect(await screen.findByRole("heading", { name: "设备收货检查" })).toBeVisible();
    expect(getWorkflow).toHaveBeenCalledTimes(2);
  });
});
