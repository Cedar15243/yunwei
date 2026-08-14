import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { TaskTimelinePage } from "./TaskTimelinePage";

const api = {
  getDashboard: async () => ({ activeTaskCount: 0, onlineDeviceCount: 0, failedMediaCount: 0, recentTasks: [] }),
  getProjects: async () => [],
  getTasks: async () => ({ items: [], nextCursor: null }),
  getTask: async () => ({
    task: { id: "task-a", title: "温湿度异常", status: "active", current_step: "检查接线" },
    events: [],
    messages: [
      { id: "message-1", event_type: "user_message", payload: { text: "平台有温湿度报警" }, created_at: "2026-07-31T01:00:00Z" },
      { id: "message-2", event_type: "ai_response", payload: { text: "请先检查 DDC 供电。" }, created_at: "2026-07-31T01:01:00Z" },
    ],
    steps: [],
    media: [{ id: "media-1", kind: "video", url: "https://signed.example/video.mp4", upload_status: "synced", captured_at: "2026-07-31T01:00:30Z", canRetryMedia: false }],
  }),
  getDevices: async () => [],
  retryMedia: async () => undefined,
  createKnowledgeCaseDraft: async () => ({ id: "knowledge-case-a", status: "uploaded" }),
};

describe("TaskTimelinePage", () => {
  it("renders field evidence and conversation in chronological order", async () => {
    render(<TaskTimelinePage taskId="task-a" api={api} />);
    expect(await screen.findByText("平台有温湿度报警")).toBeVisible();
    expect(screen.getByText("请先检查 DDC 供电。")).toBeVisible();
    expect(screen.getByLabelText("现场视频证据")).toHaveAttribute("src", "https://signed.example/video.mp4");
  });

  it("creates a server-derived knowledge draft only after a completed task confirmation", async () => {
    const user = userEvent.setup();
    const createKnowledgeCaseDraft = vi.fn().mockResolvedValue({ id: "knowledge-case-a", status: "uploaded" });
    render(<TaskTimelinePage taskId="task-a" api={{
      ...api,
      getTask: async () => ({ ...(await api.getTask()), task: { id: "task-a", title: "温湿度异常", status: "completed", current_step: "复测完成" } }),
      createKnowledgeCaseDraft,
    }} />);

    await user.click(await screen.findByRole("button", { name: "沉淀为知识草稿" }));
    expect(screen.getByRole("dialog", { name: "沉淀为知识草稿" })).toBeVisible();
    expect(screen.queryByLabelText("知识正文")).not.toBeInTheDocument();
    await user.type(screen.getByLabelText("知识范围"), "hvac/ddc");
    await user.type(screen.getByLabelText("创建原因"), "人工确认完成后沉淀现场案例");
    await user.click(screen.getByRole("button", { name: "确认创建草稿" }));

    await waitFor(() => expect(createKnowledgeCaseDraft).toHaveBeenCalledTimes(1));
    expect(createKnowledgeCaseDraft).toHaveBeenCalledWith("task-a", expect.objectContaining({
      knowledgeKey: "case_task-a",
      title: "温湿度异常维修案例",
      knowledgeScopes: ["hvac/ddc"],
      reason: "人工确认完成后沉淀现场案例",
    }));
    expect(await screen.findByText("知识草稿已创建，请到华方知识库完成审核。")) .toBeVisible();
  });
});
