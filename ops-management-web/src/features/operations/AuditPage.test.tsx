import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { ManagementApi } from "../../api/management-api";
import { AuditPage } from "./AuditPage";

function managementApi(overrides: Partial<ManagementApi> = {}): ManagementApi {
  return {
    getAuditEvents: vi.fn().mockResolvedValue({
      items: [{
        id: "audit-a",
        actor: { id: "user-a", displayName: "王工", role: "ops_admin" },
        action: "media_retry_requested",
        targetType: "media_asset",
        targetId: "media-a",
        metadata: { taskId: "task-a" },
        createdAt: "2026-08-03T08:00:00.000Z",
      }],
      nextCursor: "2026-08-03T08:00:00.000Z",
    }),
    ...overrides,
  } as unknown as ManagementApi;
}

describe("AuditPage", () => {
  it("shows organization audit facts and redacted metadata without provider secrets", async () => {
    render(<AuditPage api={managementApi()} />);

    expect(await screen.findByText("媒体重试请求")).toBeVisible();
    expect(screen.getByText("王工")).toBeVisible();
    expect(screen.getByText("媒体 media-a")).toBeVisible();
    expect(screen.getByText(/taskId/)).toBeVisible();
    expect(screen.queryByText(/token|secret|password/i)).not.toBeInTheDocument();
  });

  it("sends explicit filters and cursor when loading earlier events", async () => {
    const user = userEvent.setup();
    const getAuditEvents = vi.fn()
      .mockResolvedValueOnce({ items: [], nextCursor: null })
      .mockResolvedValueOnce({ items: [], nextCursor: null });
    render(<AuditPage api={managementApi({ getAuditEvents })} />);

    await user.type(screen.getByLabelText("动作筛选"), "voiceprint_revoked");
    await user.type(screen.getByLabelText("对象类型筛选"), "voiceprint_profile");
    await user.click(screen.getByRole("button", { name: "查询审计" }));

    expect(getAuditEvents).toHaveBeenLastCalledWith({
      action: "voiceprint_revoked",
      targetType: "voiceprint_profile",
      limit: 50,
    });
  });
});
