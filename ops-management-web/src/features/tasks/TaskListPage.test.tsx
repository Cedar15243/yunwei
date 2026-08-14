import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ManagementApi, TaskSummary } from "../../api/management-api";
import { TaskListPage } from "./TaskListPage";

const appCss = readFileSync(resolve(process.cwd(), "src/styles/app.css"), "utf8");

const taskA: TaskSummary = {
  id: "task-a",
  project_id: "project-a",
  title: "Pump inspection",
  status: "active",
  current_step: "Check power",
  updated_at: "2026-08-05T08:00:00.000Z",
};
const taskB: TaskSummary = {
  id: "task-b",
  project_id: "project-a",
  title: "Network repair",
  status: "completed",
  current_step: "Completed",
  updated_at: "2026-08-05T07:00:00.000Z",
};

function managementApi(overrides: Partial<ManagementApi> = {}): ManagementApi {
  return {
    getProjects: vi.fn().mockResolvedValue([
      { id: "project-a", title: "Lab A", status: "active" },
    ]),
    getTasks: vi.fn().mockResolvedValue({ items: [taskA], nextCursor: null }),
    exportTasks: vi.fn().mockResolvedValue(new Blob(["task_id,title\r\ntask-a,Pump inspection\r\n"], {
      type: "text/csv",
    })),
    ...overrides,
  } as unknown as ManagementApi;
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("TaskListPage", () => {
  it("releases the desktop body minimum width for both record-center workbenches", () => {
    expect(appCss).toContain(
      "body:has(.task-record-workbench),body:has(.media-center-workbench){min-width:0}",
    );
  });

  it("applies an explicit title search with status and project filters then resets them", async () => {
    const user = userEvent.setup();
    const api = managementApi();
    render(<TaskListPage api={api} onOpenTask={vi.fn()} />);

    expect(await screen.findByText("Pump inspection")).toBeVisible();
    await user.selectOptions(screen.getByLabelText("任务状态"), "completed");
    await user.selectOptions(screen.getByLabelText("所属项目"), "project-a");
    await user.type(screen.getByLabelText("任务标题"), "pump & valve");
    await user.click(screen.getByRole("button", { name: "搜索任务" }));

    await waitFor(() => expect(api.getTasks).toHaveBeenLastCalledWith({
      status: "completed",
      projectId: "project-a",
      query: "pump & valve",
      limit: 50,
    }));

    await user.click(screen.getByRole("button", { name: "重置筛选" }));
    await waitFor(() => expect(api.getTasks).toHaveBeenLastCalledWith({ limit: 50 }));
    expect(screen.getByLabelText("任务标题")).toHaveValue("");
  });

  it("appends the next page without duplicating existing tasks", async () => {
    const user = userEvent.setup();
    const getTasks = vi.fn()
      .mockResolvedValueOnce({ items: [taskA], nextCursor: "cursor-a" })
      .mockResolvedValueOnce({ items: [taskA, taskB], nextCursor: null });
    render(<TaskListPage api={managementApi({ getTasks })} onOpenTask={vi.fn()} />);

    await user.click(await screen.findByRole("button", { name: "加载下一页" }));

    expect(await screen.findByText("Network repair")).toBeVisible();
    expect(screen.getAllByText("Pump inspection")).toHaveLength(1);
    expect(getTasks).toHaveBeenLastCalledWith({ before: "cursor-a", limit: 50 });
    expect(screen.queryByRole("button", { name: "加载下一页" })).not.toBeInTheDocument();
  });

  it("preserves loaded tasks when pagination fails and retries the same page", async () => {
    const user = userEvent.setup();
    const getTasks = vi.fn()
      .mockResolvedValueOnce({ items: [taskA], nextCursor: "cursor-a" })
      .mockRejectedValueOnce(new Error("媒体网关暂时不可用"))
      .mockResolvedValueOnce({ items: [taskB], nextCursor: null });
    render(<TaskListPage api={managementApi({ getTasks })} onOpenTask={vi.fn()} />);

    await user.click(await screen.findByRole("button", { name: "加载下一页" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("媒体网关暂时不可用");
    expect(screen.getByText("Pump inspection")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "重试" }));

    expect(await screen.findByText("Network repair")).toBeVisible();
    expect(getTasks).toHaveBeenLastCalledWith({ before: "cursor-a", limit: 50 });
  });

  it("disables export while generating the authorized CSV download", async () => {
    const user = userEvent.setup();
    let resolveExport!: (value: Blob) => void;
    const exportTasks = vi.fn().mockReturnValue(new Promise<Blob>((resolve) => {
      resolveExport = resolve;
    }));
    const createObjectURL = vi.fn().mockReturnValue("blob:task-export");
    const revokeObjectURL = vi.fn();
    Object.defineProperty(URL, "createObjectURL", { configurable: true, value: createObjectURL });
    Object.defineProperty(URL, "revokeObjectURL", { configurable: true, value: revokeObjectURL });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => undefined);
    render(<TaskListPage api={managementApi({ exportTasks })} onOpenTask={vi.fn()} />);

    const exportButton = await screen.findByRole("button", { name: "导出任务记录" });
    await user.click(exportButton);

    expect(exportButton).toBeDisabled();
    resolveExport(new Blob(["task_id,title"], { type: "text/csv" }));
    await waitFor(() => expect(exportButton).not.toBeDisabled());
    expect(exportTasks).toHaveBeenCalledWith({});
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:task-export");
  });
});
