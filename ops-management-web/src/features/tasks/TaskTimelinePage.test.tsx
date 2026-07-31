import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
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
};

describe("TaskTimelinePage", () => {
  it("renders field evidence and conversation in chronological order", async () => {
    render(<TaskTimelinePage taskId="task-a" api={api} />);
    expect(await screen.findByText("平台有温湿度报警")).toBeVisible();
    expect(screen.getByText("请先检查 DDC 供电。")).toBeVisible();
    expect(screen.getByLabelText("现场视频证据")).toHaveAttribute("src", "https://signed.example/video.mp4");
  });
});
