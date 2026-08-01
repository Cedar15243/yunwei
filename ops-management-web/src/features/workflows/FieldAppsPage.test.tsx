import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { FieldApp, WorkflowDefinition } from "../../api/workflow-types";
import { FieldAppsPage, type WorkflowDirectoryApi } from "./FieldAppsPage";

const fieldApp: FieldApp = {
  id: "11111111-1111-4111-8111-111111111111",
  app_key: "equipment_receiving",
  name: "设备收货",
  description: "采集收货、铭牌和配置证据",
  icon_key: "package-check",
  entry_mode: "both",
  status: "draft",
  default_workflow_definition_id: null,
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
  created_at: "2026-08-01T01:30:00.000Z",
  updated_at: "2026-08-01T02:30:00.000Z",
};

function api(overrides: Partial<WorkflowDirectoryApi> = {}): WorkflowDirectoryApi {
  return {
    getFieldApps: vi.fn().mockResolvedValue([fieldApp]),
    createFieldApp: vi.fn().mockResolvedValue(fieldApp),
    getWorkflows: vi.fn().mockResolvedValue([workflow]),
    createWorkflow: vi.fn().mockResolvedValue(workflow),
    ...overrides,
  };
}

async function findFieldAppRow() {
  const appList = await screen.findByRole("complementary", { name: "现场应用列表" });
  return within(appList).getByRole("button", { name: /设备收货/ });
}

describe("FieldAppsPage", () => {
  it("loads real field apps and their workflow list", async () => {
    const directoryApi = api();
    render(<FieldAppsPage api={directoryApi} onOpenWorkflow={vi.fn()} />);

    expect(screen.getByText("正在读取现场应用…")).toBeVisible();
    expect(await findFieldAppRow()).toBeVisible();
    expect(await screen.findByRole("button", { name: /设备收货检查/ })).toBeVisible();
    expect(directoryApi.getWorkflows).toHaveBeenCalledWith(fieldApp.id);
    expect(screen.getByText("草稿")).toBeVisible();
  });

  it("shows a real empty state when no field apps exist", async () => {
    render(<FieldAppsPage api={api({ getFieldApps: vi.fn().mockResolvedValue([]) })} onOpenWorkflow={vi.fn()} />);

    expect(await screen.findByText("尚未创建现场应用。")).toBeVisible();
    expect(screen.getByRole("button", { name: "新建现场应用" })).toBeEnabled();
  });

  it("retries a failed directory request", async () => {
    const user = userEvent.setup();
    const getFieldApps = vi.fn()
      .mockRejectedValueOnce(new Error("管理服务暂时不可用"))
      .mockResolvedValueOnce([fieldApp]);
    render(<FieldAppsPage api={api({ getFieldApps })} onOpenWorkflow={vi.fn()} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("管理服务暂时不可用");
    await user.click(screen.getByRole("button", { name: "重试" }));

    expect(await findFieldAppRow()).toBeVisible();
    expect(getFieldApps).toHaveBeenCalledTimes(2);
  });

  it("keeps field app input after a server failure and closes only after success", async () => {
    const user = userEvent.setup();
    const createFieldApp = vi.fn()
      .mockRejectedValueOnce(new Error("应用标识已存在"))
      .mockResolvedValueOnce(fieldApp);
    render(
      <FieldAppsPage
        api={api({ getFieldApps: vi.fn().mockResolvedValue([]), createFieldApp })}
        onOpenWorkflow={vi.fn()}
      />,
    );
    await screen.findByText("尚未创建现场应用。");

    await user.click(screen.getByRole("button", { name: "新建现场应用" }));
    await user.type(screen.getByLabelText("应用名称"), "设备收货");
    await user.type(screen.getByLabelText("应用标识"), "equipment_receiving");
    await user.type(screen.getByLabelText("应用说明"), "采集收货、铭牌和配置证据");
    await user.selectOptions(screen.getByLabelText("入口方式"), "both");
    await user.click(screen.getByRole("button", { name: "创建应用" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("应用标识已存在");
    expect(screen.getByLabelText("应用名称")).toHaveValue("设备收货");
    await user.click(screen.getByRole("button", { name: "创建应用" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(await findFieldAppRow()).toBeVisible();
    expect(createFieldApp).toHaveBeenCalledTimes(2);
  });

  it("opens the editor only after the workflow is created by the server", async () => {
    const user = userEvent.setup();
    const onOpenWorkflow = vi.fn();
    const createWorkflow = vi.fn()
      .mockRejectedValueOnce(new Error("工作流标识已存在"))
      .mockResolvedValueOnce(workflow);
    render(
      <FieldAppsPage
        api={api({ getWorkflows: vi.fn().mockResolvedValue([]), createWorkflow })}
        onOpenWorkflow={onOpenWorkflow}
      />,
    );
    await findFieldAppRow();
    await screen.findByText("当前应用尚未创建工作流。");

    await user.click(screen.getByRole("button", { name: "新建工作流" }));
    await user.type(screen.getByLabelText("工作流名称"), "设备收货检查");
    await user.type(screen.getByLabelText("工作流标识"), "receive_device");
    await user.type(screen.getByLabelText("工作流说明"), "按步骤采集现场证据");
    await user.click(screen.getByRole("button", { name: "创建并进入编排" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("工作流标识已存在");
    expect(onOpenWorkflow).not.toHaveBeenCalled();
    expect(screen.getByLabelText("工作流名称")).toHaveValue("设备收货检查");
    await user.click(screen.getByRole("button", { name: "创建并进入编排" }));

    await waitFor(() => expect(onOpenWorkflow).toHaveBeenCalledWith(workflow.id));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
