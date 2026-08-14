import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { ManagementApi } from "../../api/management-api";
import { ProjectTaskWorkspace } from "./ProjectTaskWorkspace";

describe("ProjectTaskWorkspace", () => {
  it("keeps task records as the default and opens project memory without changing navigation", async () => {
    const api = {
      getTasks: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
      getProjects: vi.fn().mockResolvedValue([{ id: "project-a", title: "一号机房", status: "active" }]),
      exportTasks: vi.fn(),
      getProjectRecord: vi.fn().mockResolvedValue({
        project: { id: "project-a", title: "一号机房", status: "active" },
        tasks: [],
        latestMemory: null,
        skillVersions: [],
      }),
    } as unknown as ManagementApi;
    const user = userEvent.setup();
    render(<ProjectTaskWorkspace api={api} onOpenTask={vi.fn()} />);

    expect(screen.getByRole("tab", { name: "任务记录" })).toHaveAttribute("aria-selected", "true");
    expect(await screen.findByText("当前筛选条件下暂无任务。")).toBeVisible();

    await user.click(screen.getByRole("tab", { name: "项目记忆" }));

    expect(screen.getByRole("tab", { name: "项目记忆" })).toHaveAttribute("aria-selected", "true");
    expect(await screen.findByRole("heading", { name: "一号机房", level: 3 })).toBeVisible();
  });
});
