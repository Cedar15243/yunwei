import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ManagementApiError } from "../../api/management-api";
import type { WorkOrder, WorkOrderWorkflowResolution } from "../../api/workflow-types";
import {
  WorkOrderBindingPanel,
  type WorkOrderBindingApi,
} from "./WorkOrderBindingPanel";

const order: WorkOrder = {
  id: "55555555-5555-4555-8555-555555555555",
  sourceSystem: "mvs",
  externalWorkOrderId: "MVS-42",
  externalWorkflowCode: "receive-controller",
  projectId: "66666666-6666-4666-8666-666666666666",
  assignedProfileId: "77777777-7777-4777-8777-777777777777",
  title: "设备收货与配置检查",
  customerId: "customer-a",
  workOrderType: "receiving",
  assetId: "asset-a",
  assetCategory: "controller",
  assetBrand: "Honeywell",
  assetModel: "DDC-01",
  faultType: null,
  priority: "normal",
  riskLevel: "low",
  tags: ["pilot"],
  status: "received",
  bindingMode: null,
  bindingStatus: "unresolved",
  bindingSource: null,
  boundWorkflowVersionId: null,
  dueAt: null,
  receivedAt: "2026-08-01T01:00:00.000Z",
  updatedAt: "2026-08-01T01:00:00.000Z",
};

const assigned: WorkOrderWorkflowResolution = {
  kind: "assigned",
  workOrderId: order.id!,
  assignmentId: "88888888-8888-4888-8888-888888888888",
  mode: "required",
  workflowVersionId: "99999999-9999-4999-8999-999999999999",
  bindingStatus: "resolved",
  resolutionSource: "project",
  matchedRuleId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  conflictRuleIds: [],
};

function bindingApi(overrides: Partial<WorkOrderBindingApi> = {}): WorkOrderBindingApi {
  return {
    getWorkOrders: vi.fn().mockResolvedValue([order]),
    resolveWorkOrderWorkflow: vi.fn().mockResolvedValue(assigned),
    ...overrides,
  };
}

describe("WorkOrderBindingPanel", () => {
  it("requires an explicit reason and confirmation then shows the server resolution", async () => {
    const user = userEvent.setup();
    const api = bindingApi();
    render(<WorkOrderBindingPanel api={api} />);

    const row = await screen.findByRole("row", { name: /设备收货与配置检查/ });
    expect(within(row).getByText("待解析")).toBeVisible();
    await user.click(within(row).getByRole("button", { name: "解析工作流" }));

    expect(screen.getByRole("button", { name: "确认解析" })).toBeDisabled();
    await user.type(screen.getByRole("textbox", { name: "解析理由" }), "按当前发布规则重新解析");
    await user.type(screen.getByRole("textbox", { name: "确认词" }), "RESOLVE_WORKFLOW");
    await user.click(screen.getByRole("button", { name: "确认解析" }));

    await waitFor(() => expect(api.resolveWorkOrderWorkflow).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("必须执行 · 项目规则")).toBeVisible();
    expect(screen.getByText("已生成眼镜分配")).toBeVisible();
  });

  it("surfaces deterministic conflict candidates and refuses started work orders", async () => {
    const user = userEvent.setup();
    const started = { ...order, id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", title: "运行中工单", status: "in_progress" as const };
    const conflict: WorkOrderWorkflowResolution = {
      kind: "conflict",
      workOrderId: order.id!,
      assignmentId: null,
      mode: "none",
      workflowVersionId: null,
      bindingStatus: "conflict",
      resolutionSource: null,
      matchedRuleId: null,
      conflictRuleIds: ["rule-a", "rule-b"],
    };
    const api = bindingApi({
      getWorkOrders: vi.fn().mockResolvedValue([order, started]),
      resolveWorkOrderWorkflow: vi.fn().mockResolvedValue(conflict),
    });
    render(<WorkOrderBindingPanel api={api} />);

    const startedRow = await screen.findByRole("row", { name: /运行中工单/ });
    expect(within(startedRow).queryByRole("button", { name: "解析工作流" })).toBeNull();
    expect(within(startedRow).getByText("已开始，禁止换版")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "解析工作流" }));
    await user.type(screen.getByRole("textbox", { name: "解析理由" }), "检查冲突规则");
    await user.type(screen.getByRole("textbox", { name: "确认词" }), "RESOLVE_WORKFLOW");
    await user.click(screen.getByRole("button", { name: "确认解析" }));

    expect(await screen.findByText("规则冲突：rule-a、rule-b")).toBeVisible();
  });

  it("keeps one idempotency key across a recoverable retry", async () => {
    const user = userEvent.setup();
    const resolveWorkOrderWorkflow = vi.fn()
      .mockRejectedValueOnce(new ManagementApiError(503, "request_failed", {}))
      .mockResolvedValueOnce(assigned);
    render(<WorkOrderBindingPanel api={bindingApi({ resolveWorkOrderWorkflow })} />);

    await user.click(await screen.findByRole("button", { name: "解析工作流" }));
    await user.type(screen.getByRole("textbox", { name: "解析理由" }), "重试同一解析操作");
    await user.type(screen.getByRole("textbox", { name: "确认词" }), "RESOLVE_WORKFLOW");
    await user.click(screen.getByRole("button", { name: "确认解析" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("工单解析暂时失败");
    await user.click(screen.getByRole("button", { name: "确认解析" }));

    await waitFor(() => expect(resolveWorkOrderWorkflow).toHaveBeenCalledTimes(2));
    const first = vi.mocked(resolveWorkOrderWorkflow).mock.calls[0][1];
    const second = vi.mocked(resolveWorkOrderWorkflow).mock.calls[1][1];
    expect(first.idempotencyKey).toBeTruthy();
    expect(second.idempotencyKey).toBe(first.idempotencyKey);
  });
});
