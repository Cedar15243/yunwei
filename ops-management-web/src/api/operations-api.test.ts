import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createManagementApi } from "./management-api";

const baseUrl = "https://ops.example.test/functions/v1/ops-glasses/";

describe("operations management API", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [], nextCursor: null }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })));
  });

  afterEach(() => { vi.unstubAllGlobals(); });

  it("reads audit events with encoded filters through the authenticated management route", async () => {
    const api = createManagementApi(baseUrl, async () => "session-token");

    await api.getAuditEvents({
      action: "voiceprint_revoked",
      targetType: "voiceprint_profile",
      limit: 25,
      before: "2026-08-03T08:00:00.000Z",
    });

    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(String(url)).toContain("/management/audit-events?");
    expect(String(url)).toContain("action=voiceprint_revoked");
    expect(String(url)).toContain("targetType=voiceprint_profile");
    expect(String(url)).toContain("limit=25");
    expect(String(url)).toContain("before=2026-08-03T08%3A00%3A00.000Z");
    expect(init?.headers).toMatchObject({ Authorization: "Bearer session-token" });
  });

  it("reads the system status without sending a write request", async () => {
    const api = createManagementApi(baseUrl, async () => "session-token");

    await api.getSystemStatus();

    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(String(url)).toBe("https://ops.example.test/functions/v1/ops-glasses/management/system-status");
    expect(init?.method).toBeUndefined();
  });
});
