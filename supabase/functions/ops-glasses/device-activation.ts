export type DeviceActivationCommand = {
  activationCode: string;
  deviceInstanceId: string;
  packageName: string;
  appVersion: string;
  deviceModel: string;
};

export type DeviceActivationFailureStatus =
  | "invalid"
  | "expired"
  | "used"
  | "device_mismatch"
  | "revoked"
  | "binding_revoked";

export type DeviceActivationResult =
  | { status: DeviceActivationFailureStatus }
  | {
    status: "activated";
    backendBaseUrl: string;
    bootstrapCredential: string;
    credentialExpiresAt: string;
    deviceId: string;
    organizationId: string;
    policyVersion: string;
  };

export type DeviceActivationGateway = {
  redeem(command: DeviceActivationCommand): Promise<DeviceActivationResult>;
};

export type DeviceActivationRuntimeConfiguration = {
  backendBaseUrl: string;
  policyVersion: string;
};

const FORMAL_PACKAGE = "com.codex.air3nativecamera.dingdangexpert.v9";
const configurationErrors = new Set([
  "device_activation_backend_https_required",
  "device_activation_policy_invalid",
]);
const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "content-type",
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
};

export async function routeDeviceActivation(
  request: Request,
  gateway: DeviceActivationGateway,
): Promise<Response> {
  const path = routePath(request);
  if (request.method !== "POST" || path !== "/device-activation/redeem") {
    return response({ ok: false, error: "not_found" }, 404);
  }
  const body = await request.json().catch(() => null);
  const command = parseCommand(body);
  if (!command) return response({ ok: false, error: "invalid_request" }, 400);

  const result = await gateway.redeem(command);
  if (result.status === "activated") {
    if (!validGrant(result)) {
      return response({ ok: false, error: "activation_response_invalid" }, 503);
    }
    return response({
      ok: true,
      backendBaseUrl: trimTrailingSlash(result.backendBaseUrl),
      bootstrapCredential: result.bootstrapCredential.trim(),
      credentialExpiresAt: result.credentialExpiresAt,
      deviceId: result.deviceId,
      organizationId: result.organizationId,
      policyVersion: result.policyVersion,
    }, 201);
  }
  const failure = failureResponse(result.status);
  return response({ ok: false, error: failure.error }, failure.status);
}

export function createDeviceActivationGateway(
  supabase: any,
  configuration: DeviceActivationRuntimeConfiguration,
): DeviceActivationGateway {
  const backendBaseUrl = trimTrailingSlash(configuration.backendBaseUrl);
  const policyVersion = text(configuration.policyVersion);
  try {
    const parsed = new URL(backendBaseUrl);
    if (parsed.protocol !== "https:" || !parsed.hostname) {
      throw new Error("device_activation_backend_https_required");
    }
  } catch (error) {
    if (error instanceof Error && error.message === "device_activation_backend_https_required") {
      throw error;
    }
    throw new Error("device_activation_backend_https_required");
  }
  if (!validIdentifier(policyVersion, 200)) {
    throw new Error("device_activation_policy_invalid");
  }
  return {
    async redeem(command) {
      const bootstrapCredential = randomToken();
      const bootstrapExpiresAt = new Date(
        Date.now() + 90 * 24 * 60 * 60 * 1000,
      ).toISOString();
      const { data, error } = await supabase.rpc("redeem_device_activation_code", {
        candidate_code_hash: await sha256(command.activationCode),
        claimed_device_instance_hash: await sha256(command.deviceInstanceId),
        claimed_package_name: command.packageName,
        claimed_app_version: command.appVersion,
        claimed_device_model: command.deviceModel,
        new_bootstrap_token_hash: await sha256(bootstrapCredential),
        new_bootstrap_expires_at: bootstrapExpiresAt,
      });
      if (error) throw error;
      const result = firstRow(data);
      const status = String(result?.activation_status ?? "invalid");
      if (status !== "activated") {
        return { status: activationFailureStatus(status) };
      }
      return {
        status: "activated",
        backendBaseUrl,
        bootstrapCredential,
        credentialExpiresAt: String(
          result?.bootstrap_expires_at ?? bootstrapExpiresAt,
        ),
        deviceId: String(result?.device_id ?? ""),
        organizationId: String(result?.organization_id ?? ""),
        policyVersion,
      };
    },
  };
}

export function deviceActivationConfigurationError(error: unknown): string | null {
  const code = error instanceof Error ? error.message : "";
  return configurationErrors.has(code) ? code : null;
}

function parseCommand(value: unknown): DeviceActivationCommand | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const body = value as Record<string, unknown>;
  const allowed = new Set([
    "activationCode",
    "deviceInstanceId",
    "packageName",
    "appVersion",
    "deviceModel",
  ]);
  if (Object.keys(body).some((field) => !allowed.has(field))) return null;
  const activationCode = text(body.activationCode);
  const deviceInstanceId = text(body.deviceInstanceId);
  const packageName = text(body.packageName);
  const appVersion = text(body.appVersion);
  const deviceModel = text(body.deviceModel);
  if (!/^HF9-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$/.test(activationCode)) return null;
  if (!validIdentifier(deviceInstanceId, 200)) return null;
  if (packageName !== FORMAL_PACKAGE) return null;
  if (!/^9\.\d+\.\d+(?:[-+][A-Za-z0-9._-]+)?$/.test(appVersion)) return null;
  if (!validIdentifier(deviceModel, 100)) return null;
  return { activationCode, deviceInstanceId, packageName, appVersion, deviceModel };
}

function validGrant(result: Extract<DeviceActivationResult, { status: "activated" }>): boolean {
  const url = trimTrailingSlash(result.backendBaseUrl);
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== "https:" || !parsed.hostname) return false;
  } catch {
    return false;
  }
  const bootstrap = result.bootstrapCredential.trim();
  return bootstrap.length >= 24
    && bootstrap.length <= 1024
    && !/[\s\x00-\x1f\x7f]/.test(bootstrap)
    && !Number.isNaN(Date.parse(result.credentialExpiresAt))
    && validIdentifier(result.deviceId, 200)
    && validIdentifier(result.organizationId, 200)
    && validIdentifier(result.policyVersion, 200);
}

function failureResponse(status: DeviceActivationFailureStatus): { status: number; error: string } {
  if (status === "expired") return { status: 410, error: "activation_expired" };
  if (status === "used") return { status: 409, error: "activation_already_used" };
  if (status === "device_mismatch") return { status: 403, error: "activation_device_mismatch" };
  if (status === "revoked") return { status: 403, error: "device_revoked" };
  if (status === "binding_revoked") return { status: 403, error: "device_binding_revoked" };
  return { status: 401, error: "activation_invalid" };
}

function activationFailureStatus(value: string): DeviceActivationFailureStatus {
  if (value === "expired" || value === "used" || value === "device_mismatch"
    || value === "revoked" || value === "binding_revoked") {
    return value;
  }
  return "invalid";
}

function validIdentifier(value: string, maxLength: number): boolean {
  return value.length > 0 && value.length <= maxLength && !/[\x00-\x1f\x7f]/.test(value);
}

function text(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function trimTrailingSlash(value: string): string {
  return value.trim().replace(/\/+$/, "");
}

function routePath(request: Request): string {
  return new URL(request.url).pathname
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/";
}

function response(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), { status, headers });
}

async function sha256(value: string): Promise<string> {
  const bytes = new TextEncoder().encode(value);
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return Array.from(hash, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function randomToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
}

function firstRow(value: unknown): Record<string, unknown> | null {
  if (Array.isArray(value)) {
    return value.length > 0 && value[0] && typeof value[0] === "object"
      ? value[0] as Record<string, unknown>
      : null;
  }
  return value && typeof value === "object" ? value as Record<string, unknown> : null;
}
