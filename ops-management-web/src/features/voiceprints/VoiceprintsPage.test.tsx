import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { ManagementApi } from "../../api/management-api";
import { VoiceprintsPage } from "./VoiceprintsPage";

function managementApi(overrides: Partial<ManagementApi> = {}): ManagementApi {
  return {
    getPeople: vi.fn().mockResolvedValue([{
      id: "user-a",
      display_name: "张工",
      role: "field_engineer",
      status: "active",
      active: true,
    }]),
    getDevices: vi.fn().mockResolvedValue([{
      id: "device-a",
      device_key: "AIR3-001",
      display_name: "Air3 一号机",
      status: "online",
    }]),
    getVoiceprints: vi.fn().mockResolvedValue({
      auditChainValid: true,
      items: [{
        profileId: "voiceprint-a",
        organizationId: "org-a",
        userId: "user-a",
        deviceId: "device-a",
        provider: "iflytek",
        status: "active",
        sampleCount: 3,
        requiredSamples: 3,
        verificationFailures: 0,
        consentVersion: "2026-08-02.v1",
        consentedAt: "2026-08-03T08:00:00.000Z",
        lastVerifiedAt: "2026-08-03T08:05:00.000Z",
        lockedAt: null,
        createdAt: "2026-08-03T08:00:00.000Z",
        updatedAt: "2026-08-03T08:05:00.000Z",
        canRevoke: true,
      }],
    }),
    revokeVoiceprint: vi.fn().mockResolvedValue({ status: "revoked" }),
    ...overrides,
  } as unknown as ManagementApi;
}

describe("VoiceprintsPage", () => {
  it("shows the redacted profile with its person, device and audit health", async () => {
    render(<VoiceprintsPage api={managementApi()} />);

    expect(await screen.findByText("张工")).toBeVisible();
    expect(screen.getByText("Air3 一号机")).toBeVisible();
    expect(screen.getByText("已激活")).toBeVisible();
    expect(screen.getByText("讯飞声纹（新）")).toBeVisible();
    expect(screen.getByText("审计链正常")).toBeVisible();
    expect(screen.queryByText(/providerGroup|providerFeature/i)).not.toBeInTheDocument();
  });

  it("revokes a profile only after a reasoned second confirmation", async () => {
    const user = userEvent.setup();
    const api = managementApi();
    render(<VoiceprintsPage api={api} />);

    await user.click(await screen.findByRole("button", { name: "撤销 张工 的声纹" }));
    await user.type(screen.getByLabelText("操作原因"), "设备交回并解除人员绑定");
    await user.click(screen.getByRole("button", { name: "确认撤销声纹" }));

    expect(api.revokeVoiceprint).toHaveBeenCalledWith("voiceprint-a", {
      reason: "设备交回并解除人员绑定",
      idempotencyKey: expect.any(String),
    });
    expect(api.getVoiceprints).toHaveBeenCalledTimes(2);
  });

  it("keeps the reason and dialog open when the server refuses revocation", async () => {
    const user = userEvent.setup();
    const api = managementApi({
      revokeVoiceprint: vi.fn().mockRejectedValue(new Error("声纹网关暂不可用")),
    });
    render(<VoiceprintsPage api={api} />);

    await user.click(await screen.findByRole("button", { name: "撤销 张工 的声纹" }));
    const reason = screen.getByLabelText("操作原因");
    await user.type(reason, "设备遗失需要撤销");
    await user.click(screen.getByRole("button", { name: "确认撤销声纹" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("声纹网关暂不可用");
    expect(reason).toHaveValue("设备遗失需要撤销");
  });
});
