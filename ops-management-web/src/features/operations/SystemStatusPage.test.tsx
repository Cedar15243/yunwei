import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { ManagementApi } from "../../api/management-api";
import { SystemStatusPage } from "./SystemStatusPage";

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
      contentDistribution: {
        totalDevices: 2,
        healthyDevices: 1,
        issueCount: 1,
        items: [{
          deviceId: "device-a",
          deviceKey: "AIR3-001",
          displayName: "Air3 一号机",
          deviceStatus: "online",
          appVersion: "9.0.0",
          lastSeenAt: "2026-08-03T08:00:00.000Z",
          manifestStatus: "expired",
          manifestVersion: 4,
          manifestEtag: "etag-a",
          projectId: "project-a",
          generatedAt: "2026-08-02T08:00:00.000Z",
          expiresAt: "2026-08-03T07:00:00.000Z",
        }],
      },
      generatedAt: "2026-08-03T08:00:00.000Z",
    }),
    ...overrides,
  } as unknown as ManagementApi;
}

describe("SystemStatusPage", () => {
  it("shows the locked model contract and real content distribution issue", async () => {
    render(<SystemStatusPage api={managementApi()} />);

    expect(await screen.findByText("qwen3-vl-plus")).toBeVisible();
    expect(screen.getByText("fun-asr-realtime")).toBeVisible();
    expect(screen.getByText("讯飞 s1aa729d0")).toBeVisible();
    expect(screen.getByText("已锁定")).toBeVisible();
    expect(screen.getByText("内容清单已过期")).toBeVisible();
    expect(screen.getByText("声纹管理代理未配置")).toBeVisible();
  });

  it("keeps server failures visible with a retry action", async () => {
    const getSystemStatus = vi.fn().mockRejectedValue(new Error("云端状态请求失败"));
    render(<SystemStatusPage api={managementApi({ getSystemStatus })} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("云端状态请求失败");
    expect(screen.getByRole("button", { name: "重试系统状态" })).toBeVisible();
  });
});
