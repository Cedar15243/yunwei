import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import {
  createVoiceprintAdminClient,
  VoiceprintAdminClientError,
} from "./voiceprint-admin-client.ts";

Deno.test("refuses voiceprint administration without an HTTPS server credential", async () => {
  const client = createVoiceprintAdminClient({ baseUrl: "", token: "" });

  const error = await assertRejects(
    () => client.list("org-a"),
    VoiceprintAdminClientError,
  );

  assertEquals(error.status, 503);
  assertEquals(error.code, "voiceprint_admin_not_configured");
});

Deno.test("reads a redacted organization-scoped voiceprint list", async () => {
  const received: Request[] = [];
  const client = createVoiceprintAdminClient(
    { baseUrl: "https://v9.example.test", token: "server-token-value" },
    async (request) => {
      received.push(request);
      return Response.json({
        ok: true,
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
          providerGroupId: "must-not-cross-boundary",
        }],
      });
    },
  );

  const result = await client.list("org-a");

  assertEquals(received[0].url, "https://v9.example.test/admin/voiceprints");
  assertEquals(received[0].headers.get("Authorization"), "Bearer server-token-value");
  assertEquals(result.auditChainValid, true);
  assertEquals(result.items[0].status, "active");
  assertEquals((result.items[0] as unknown as Record<string, unknown>).providerGroupId, undefined);
});

Deno.test("revokes a voiceprint with the gateway confirmation and stable idempotency key", async () => {
  let body: Record<string, unknown> | null = null;
  const client = createVoiceprintAdminClient(
    { baseUrl: "https://v9.example.test/", token: "server-token-value" },
    async (request) => {
      body = await request.json();
      return Response.json({
        ok: true,
        profileId: "voiceprint/a",
        organizationId: "org-a",
        userId: "user-a",
        deviceId: "device-a",
        provider: "iflytek",
        status: "revoked",
        sampleCount: 0,
        requiredSamples: 3,
        verificationFailures: 0,
        consentVersion: "2026-08-02.v1",
        consentedAt: "2026-08-03T08:00:00.000Z",
        lastVerifiedAt: null,
        lockedAt: null,
        createdAt: "2026-08-03T08:00:00.000Z",
        updatedAt: "2026-08-03T09:00:00.000Z",
      });
    },
  );

  const result = await client.revoke("org-a", "voiceprint/a", {
    reason: "设备交回",
    idempotencyKey: "voiceprint-revoke-1",
  });

  assertEquals(result.status, "revoked");
  assertEquals(body, {
    confirmation: "REVOKE VOICEPRINT",
    reason: "设备交回",
    idempotencyKey: "voiceprint-revoke-1",
  });
});
