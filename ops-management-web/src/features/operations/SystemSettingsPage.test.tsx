import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { ManagementApi } from "../../api/management-api";
import { SystemSettingsPage } from "./SystemSettingsPage";

function managementApi(overrides: Partial<ManagementApi> = {}): ManagementApi {
  return {
    getSystemStatus: vi.fn().mockResolvedValue({
      modelContract: {
        mainAiModel: "qwen3-vl-plus",
        realtimeAsrModel: "fun-asr-realtime",
        wakeEngine: "iflytek-aikit-previous",
        voiceprintService: "iflytek/s1aa729d0",
        locked: true,
      },
      integrations: {
        contentSyncConfigured: true,
        voiceprintAdminConfigured: false,
        deviceActivationBackendConfigured: true,
      },
      contentDistribution: { totalDevices: 0, healthyDevices: 0, issueCount: 0, items: [] },
      generatedAt: "2026-08-05T04:00:00.000Z",
    }),
    ...overrides,
  } as unknown as ManagementApi;
}

describe("SystemSettingsPage", () => {
  it("shows the controlled settings without exposing write controls or secrets", async () => {
    render(<SystemSettingsPage api={managementApi()} />);

    expect(await screen.findByRole("heading", { name: "系统设置", level: 2 })).toBeVisible();
    expect(screen.getByText("模型与语音策略")).toBeVisible();
    expect(screen.getByText("短期会话，最长 15 分钟")).toBeVisible();
    expect(screen.getByText("签名后推送")).toBeVisible();
    expect(screen.getByText("声纹管理代理")).toBeVisible();
    expect(screen.getByText("未配置")).toBeVisible();
    expect(screen.queryByRole("button", { name: /保存|修改|编辑/ })).not.toBeInTheDocument();
  });

  it("keeps a real server failure recoverable", async () => {
    const getSystemStatus = vi.fn().mockRejectedValue(new Error("系统状态请求失败"));
    render(<SystemSettingsPage api={managementApi({ getSystemStatus })} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("系统状态请求失败");
    expect(screen.getByRole("button", { name: "重试设置状态" })).toBeVisible();
  });
});
