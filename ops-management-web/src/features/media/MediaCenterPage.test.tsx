import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { ManagementApi, MediaRecord } from "../../api/management-api";
import { MediaCenterPage } from "./MediaCenterPage";

const failedPhoto: MediaRecord = {
  id: "media-a",
  task_id: "task-a",
  task_title: "Pump inspection",
  project_id: "project-a",
  kind: "photo",
  content_type: "image/jpeg",
  url: null,
  upload_status: "failed",
  failure_reason: "network_interrupted",
  captured_at: "2026-08-05T08:00:00.000Z",
  created_at: "2026-08-05T08:00:00.000Z",
  canRetryMedia: true,
};
const syncedVideo: MediaRecord = {
  id: "media-b",
  task_id: "task-b",
  task_title: "Network repair",
  project_id: "project-a",
  kind: "video",
  content_type: "video/mp4",
  url: "https://signed.example/video.mp4",
  upload_status: "synced",
  failure_reason: "",
  captured_at: "2026-08-05T07:00:00.000Z",
  created_at: "2026-08-05T07:00:00.000Z",
  canRetryMedia: false,
};
const cancelledPhoto: MediaRecord = {
  ...failedPhoto,
  id: "media-c",
  task_id: "task-c",
  task_title: "Cancelled upload",
  upload_status: "cancelled",
  failure_reason: "workflow_evidence_upload_cancelled",
  canRetryMedia: false,
};

function managementApi(overrides: Partial<ManagementApi> = {}): ManagementApi {
  return {
    getMedia: vi.fn().mockResolvedValue({ items: [failedPhoto], nextCursor: null }),
    retryMedia: vi.fn().mockResolvedValue(undefined),
    getTasks: vi.fn(),
    getTask: vi.fn(),
    ...overrides,
  } as unknown as ManagementApi;
}

describe("MediaCenterPage", () => {
  it("loads media directly without task-list or task-detail fan-out", async () => {
    const api = managementApi();
    render(<MediaCenterPage api={api} />);

    expect(await screen.findByText("Pump inspection")).toBeVisible();
    expect(api.getMedia).toHaveBeenCalledWith({ limit: 24 });
    expect(api.getTasks).not.toHaveBeenCalled();
    expect(api.getTask).not.toHaveBeenCalled();
  });

  it("filters server-side and appends the next media page", async () => {
    const user = userEvent.setup();
    const getMedia = vi.fn().mockImplementation(async (filters: Record<string, unknown>) => {
      if (filters.before) return { items: [failedPhoto, syncedVideo], nextCursor: null };
      return { items: [failedPhoto], nextCursor: "cursor-a" };
    });
    render(<MediaCenterPage api={managementApi({ getMedia })} />);

    await user.selectOptions(await screen.findByLabelText("证据类型"), "photo");
    await user.selectOptions(screen.getByLabelText("同步状态"), "failed");
    await waitFor(() => expect(getMedia).toHaveBeenLastCalledWith({
      kind: "photo",
      uploadStatus: "failed",
      limit: 24,
    }));

    await user.click(screen.getByRole("button", { name: "加载下一页媒体" }));
    expect(await screen.findByText("Network repair")).toBeVisible();
    expect(screen.getAllByText("Pump inspection")).toHaveLength(1);
    expect(getMedia).toHaveBeenLastCalledWith({
      kind: "photo",
      uploadStatus: "failed",
      before: "cursor-a",
      limit: 24,
    });
  });

  it("shows the precise failure reason and retries only the failed asset", async () => {
    const user = userEvent.setup();
    const retryMedia = vi.fn()
      .mockRejectedValueOnce(new Error("retry_gateway_unavailable"))
      .mockResolvedValueOnce(undefined);
    render(<MediaCenterPage api={managementApi({ retryMedia })} />);

    expect(await screen.findByText("network_interrupted")).toBeVisible();
    const retryButton = screen.getByRole("button", { name: "重试 Pump inspection 的照片" });
    await user.click(retryButton);

    expect(await screen.findByRole("alert")).toHaveTextContent("retry_gateway_unavailable");
    expect(screen.getByText("Pump inspection")).toBeVisible();
    expect(screen.getByText("network_interrupted")).toBeVisible();
    expect(retryMedia).toHaveBeenLastCalledWith("task-a", "media-a");

    await user.click(screen.getByRole("button", { name: "重试 Pump inspection 的照片" }));
    expect(await screen.findByText("等待同步", { selector: ".status" })).toBeVisible();
    expect(retryMedia).toHaveBeenCalledTimes(2);
  });

  it("keeps an empty or failed first page recoverable", async () => {
    const user = userEvent.setup();
    const getMedia = vi.fn()
      .mockRejectedValueOnce(new Error("media_service_unavailable"))
      .mockResolvedValueOnce({ items: [], nextCursor: null });
    render(<MediaCenterPage api={managementApi({ getMedia })} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("media_service_unavailable");
    await user.click(screen.getByRole("button", { name: "重试" }));

    expect(await screen.findByText("暂无已授权的现场媒体。")) .toBeVisible();
    expect(getMedia).toHaveBeenCalledTimes(2);
  });

  it("shows cancelled evidence separately without offering a failure retry", async () => {
    const user = userEvent.setup();
    const getMedia = vi.fn().mockResolvedValue({ items: [cancelledPhoto], nextCursor: null });
    render(<MediaCenterPage api={managementApi({ getMedia })} />);

    expect(await screen.findByText("已取消", { selector: ".status" })).toBeVisible();
    expect(screen.queryByRole("button", { name: /重试 Cancelled upload/ })).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("同步状态"), "cancelled");
    await waitFor(() => expect(getMedia).toHaveBeenLastCalledWith({
      uploadStatus: "cancelled",
      limit: 24,
    }));
  });
});
