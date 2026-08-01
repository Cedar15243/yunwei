import { assertEquals } from "jsr:@std/assert@1";
import { authorizeGlassesRequest } from "./device-auth.ts";

const identity = {
  deviceId: "device-a",
  organizationId: "organization-a",
  actorProfileId: "operator-a",
};

Deno.test("keeps the existing server key authorization path", async () => {
  let deviceAuthCalls = 0;
  const result = await authorizeGlassesRequest(
    request({ "x-ops-glasses-key": "legacy-key" }),
    "legacy-key",
    async () => {
      deviceAuthCalls++;
      return null;
    },
  );

  assertEquals(result, { ok: true, mode: "legacy" });
  assertEquals(deviceAuthCalls, 0);
});

Deno.test("never treats a missing server key as anonymous legacy authorization", async () => {
  const result = await authorizeGlassesRequest(
    request({}),
    "",
    async () => null,
  );

  assertEquals(result, { ok: false, status: 401, error: "unauthorized" });
});

Deno.test("accepts a bound short-lived device credential", async () => {
  const result = await authorizeGlassesRequest(
    request({ Authorization: "Bearer device-token" }),
    "legacy-key",
    async (token) => token === "device-token" ? identity : null,
  );

  assertEquals(result, { ok: true, mode: "device", identity });
});

Deno.test("rejects invalid and unbound device credentials", async () => {
  const invalid = await authorizeGlassesRequest(
    request({ Authorization: "Bearer invalid" }),
    "legacy-key",
    async () => null,
  );
  const unbound = await authorizeGlassesRequest(
    request({ Authorization: "Bearer unbound" }),
    "legacy-key",
    async () => ({ ...identity, actorProfileId: "" }),
  );

  assertEquals(invalid, { ok: false, status: 401, error: "unauthorized" });
  assertEquals(unbound, { ok: false, status: 403, error: "device_not_bound" });
});

function request(headers: Record<string, string>): Request {
  return new Request("https://project.supabase.co/functions/v1/ops-glasses/sessions/session-a/diagnose/stream", {
    headers,
  });
}
