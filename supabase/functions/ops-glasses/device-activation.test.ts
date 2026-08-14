import { assertEquals, assertThrows } from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  createDeviceActivationGateway,
  deviceActivationConfigurationError,
  routeDeviceActivation,
  type DeviceActivationGateway,
} from "./device-activation.ts";

const validRequest = {
  activationCode: "HF9-ABCD-EFGH-JKLM",
  deviceInstanceId: "11111111-2222-4333-8444-555555555555",
  packageName: "com.codex.air3nativecamera.dingdangexpert.v9",
  appVersion: "9.0.0",
  deviceModel: "IMA301",
};

function request(body: unknown = validRequest): Request {
  return new Request("https://ops.example.com/device-activation/redeem", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

Deno.test("redeems a one-time activation without returning supplier keys", async () => {
  let received: Record<string, unknown> | null = null;
  const gateway: DeviceActivationGateway = {
    redeem: async (command) => {
      received = command;
      return {
        status: "activated",
        backendBaseUrl: "https://bb.chinacedar.top:2305/v9-ops",
        bootstrapCredential: "abcdefghijklmnopqrstuvwxyz_1234567890-ABCDE",
        credentialExpiresAt: "2026-11-01T12:00:00.000Z",
        deviceId: "device-a",
        organizationId: "organization-a",
        policyVersion: "v9-production-1",
      };
    },
  };

  const response = await routeDeviceActivation(request(), gateway);
  const body = await response.json();

  assertEquals(response.status, 201);
  assertEquals(response.headers.get("Cache-Control"), "no-store");
  assertEquals(received, validRequest);
  assertEquals(body, {
    ok: true,
    backendBaseUrl: "https://bb.chinacedar.top:2305/v9-ops",
    bootstrapCredential: "abcdefghijklmnopqrstuvwxyz_1234567890-ABCDE",
    credentialExpiresAt: "2026-11-01T12:00:00.000Z",
    deviceId: "device-a",
    organizationId: "organization-a",
    policyVersion: "v9-production-1",
  });
  assertEquals("iflytekApiSecret" in body, false);
  assertEquals("aiApiKey" in body, false);
  assertEquals("asrApiKey" in body, false);
  assertEquals("accessToken" in body, false);
});

Deno.test("rejects malformed activation requests before calling the gateway", async () => {
  let calls = 0;
  const gateway: DeviceActivationGateway = {
    redeem: async () => {
      calls += 1;
      return { status: "invalid" };
    },
  };

  const response = await routeDeviceActivation(request({
    ...validRequest,
    activationCode: "short",
    packageName: "com.example.fake",
  }), gateway);

  assertEquals(response.status, 400);
  assertEquals(await response.json(), { ok: false, error: "invalid_request" });
  assertEquals(calls, 0);
});

Deno.test("maps expired used mismatched and revoked activations to stable errors", async () => {
  const cases = [
    ["expired", 410, "activation_expired"],
    ["used", 409, "activation_already_used"],
    ["device_mismatch", 403, "activation_device_mismatch"],
    ["revoked", 403, "device_revoked"],
    ["binding_revoked", 403, "device_binding_revoked"],
    ["invalid", 401, "activation_invalid"],
  ] as const;

  for (const [status, httpStatus, error] of cases) {
    const gateway: DeviceActivationGateway = {
      redeem: async () => ({ status }),
    };
    const response = await routeDeviceActivation(request(), gateway);
    assertEquals(response.status, httpStatus);
    assertEquals(response.headers.get("Cache-Control"), "no-store");
    assertEquals(await response.json(), { ok: false, error });
  }
});

Deno.test("accepts the deployed Supabase activation path only for POST", async () => {
  const gateway: DeviceActivationGateway = {
    redeem: async () => ({ status: "invalid" }),
  };
  const deployed = new Request(
    "https://project.supabase.co/functions/v1/ops-glasses/device-activation/redeem",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(validRequest),
    },
  );
  const wrongMethod = new Request(
    "https://project.supabase.co/functions/v1/ops-glasses/device-activation/redeem",
  );

  assertEquals((await routeDeviceActivation(deployed, gateway)).status, 401);
  assertEquals((await routeDeviceActivation(wrongMethod, gateway)).status, 404);
});

Deno.test("hashes activation and bootstrap secrets before atomic redemption", async () => {
  let rpcName = "";
  let rpcArgs: Record<string, unknown> = {};
  const supabase = {
    async rpc(name: string, args: Record<string, unknown>) {
      rpcName = name;
      rpcArgs = args;
      return {
        data: [{
          activation_status: "activated",
          device_id: "device-a",
          organization_id: "organization-a",
          bootstrap_expires_at: "2026-11-01T12:00:00.000Z",
        }],
        error: null,
      };
    },
  };
  const gateway = createDeviceActivationGateway(supabase, {
    backendBaseUrl: "https://bb.chinacedar.top:2305/v9-ops/",
    policyVersion: "v9-production-1",
  });

  const result = await gateway.redeem(validRequest);

  assertEquals(rpcName, "redeem_device_activation_code");
  assertEquals(String(rpcArgs.candidate_code_hash).length, 64);
  assertEquals(rpcArgs.candidate_code_hash === validRequest.activationCode, false);
  assertEquals(String(rpcArgs.claimed_device_instance_hash).length, 64);
  assertEquals(rpcArgs.claimed_device_instance_hash === validRequest.deviceInstanceId, false);
  assertEquals(String(rpcArgs.new_bootstrap_token_hash).length, 64);
  assertEquals(result.status, "activated");
  if (result.status === "activated") {
    assertEquals(result.backendBaseUrl, "https://bb.chinacedar.top:2305/v9-ops");
    assertEquals(result.bootstrapCredential.length > 32, true);
    assertEquals(rpcArgs.new_bootstrap_token_hash === result.bootstrapCredential, false);
    assertEquals(result.policyVersion, "v9-production-1");
  }
});

Deno.test("returns the database redemption status without issuing a false credential", async () => {
  const supabase = {
    async rpc() {
      return { data: [{ activation_status: "used" }], error: null };
    },
  };
  const gateway = createDeviceActivationGateway(supabase, {
    backendBaseUrl: "https://bb.chinacedar.top:2305/v9-ops",
    policyVersion: "v9-production-1",
  });

  assertEquals(await gateway.redeem(validRequest), { status: "used" });
});

Deno.test("fails closed when the activation backend or policy is not explicitly configured", () => {
  const missingBackend = assertThrows(
    () => createDeviceActivationGateway({}, {
      backendBaseUrl: "",
      policyVersion: "v9-production-1",
    }),
    Error,
    "device_activation_backend_https_required",
  );
  const missingPolicy = assertThrows(
    () => createDeviceActivationGateway({}, {
      backendBaseUrl: "https://gateway.example.com/v9-ops",
      policyVersion: "",
    }),
    Error,
    "device_activation_policy_invalid",
  );

  assertEquals(
    deviceActivationConfigurationError(missingBackend),
    "device_activation_backend_https_required",
  );
  assertEquals(
    deviceActivationConfigurationError(missingPolicy),
    "device_activation_policy_invalid",
  );
  assertEquals(deviceActivationConfigurationError(new Error("database_unavailable")), null);
});
