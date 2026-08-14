import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { ManagementApi } from "../../api/management-api";
import { ProjectRecordsPage } from "./ProjectRecordsPage";

function managementApi(overrides: Partial<ManagementApi> = {}): ManagementApi {
  return {
    getProjects: vi.fn().mockResolvedValue([{
      id: "project-a",
      title: "一号机房",
      status: "active",
      summary: "DDC 控制器维修记录",
      updated_at: "2026-08-05T09:00:00.000Z",
    }]),
    getProjectRecord: vi.fn().mockResolvedValue({
      project: {
        id: "project-a",
        title: "一号机房",
        status: "active",
        summary: "DDC 控制器维修记录",
        updated_at: "2026-08-05T09:00:00.000Z",
      },
      tasks: [{
        id: "task-a",
        project_id: "project-a",
        title: "控制器离线",
        status: "completed",
        current_step: "复测通信",
        skill_version: "hvac-ddc@3",
        updated_at: "2026-08-05T08:30:00.000Z",
      }],
      latestMemory: {
        revision: 4,
        summary: "已更换控制器并复测通信。",
        confirmedFacts: ["24V 供电正常", "新控制器在线"],
        excludedFacts: ["总线反接"],
        risks: ["观察 24 小时"],
        taskId: "task-a",
        eventId: "event-a",
        updatedAt: "2026-08-05T08:30:00.000Z",
      },
      skillVersions: ["hvac-ddc@3"],
    }),
    ...overrides,
  } as unknown as ManagementApi;
}

describe("ProjectRecordsPage", () => {
  it("shows synchronized project memory facts risks skills and tasks", async () => {
    const onOpenTask = vi.fn();
    const user = userEvent.setup();
    render(<ProjectRecordsPage api={managementApi()} onOpenTask={onOpenTask} />);

    expect(await screen.findByRole("heading", { name: "一号机房", level: 3 })).toBeVisible();
    expect(screen.getByText("已更换控制器并复测通信。")).toBeVisible();
    expect(screen.getByText("项目记忆 v4")).toBeVisible();
    expect(screen.getByText("24V 供电正常")).toBeVisible();
    expect(screen.getByText("总线反接")).toBeVisible();
    expect(screen.getByText("观察 24 小时")).toBeVisible();
    expect(screen.getByText("hvac-ddc@3")).toBeVisible();

    const task = screen.getByRole("article", { name: "控制器离线" });
    await user.click(within(task).getByRole("button", { name: "查看任务" }));
    expect(onOpenTask).toHaveBeenCalledWith("task-a");
  });

  it("keeps an absent human-confirmed memory explicit and recoverable", async () => {
    const getProjectRecord = vi.fn()
      .mockRejectedValueOnce(new Error("项目记录请求失败"))
      .mockResolvedValueOnce({
        project: { id: "project-a", title: "一号机房", status: "active", summary: "" },
        tasks: [],
        latestMemory: null,
        skillVersions: [],
      });
    const user = userEvent.setup();
    render(<ProjectRecordsPage api={managementApi({ getProjectRecord })} onOpenTask={vi.fn()} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("项目记录请求失败");
    await user.click(screen.getByRole("button", { name: "重试项目记录" }));

    await waitFor(() => expect(screen.getByText("尚无人工确认并同步的项目结束摘要。")).toBeVisible());
  });
});
