import type { DeviceSyncIdentity } from "./device-sync.ts";

export type GlassesAuthorization =
  | { ok: true; mode: "legacy" }
  | { ok: true; mode: "device"; identity: DeviceSyncIdentity }
  | { ok: false; status: 401 | 403; error: "unauthorized" | "device_not_bound" };

export async function authorizeGlassesRequest(
  request: Request,
  legacyKey: string,
  authenticateDevice: (token: string) => Promise<DeviceSyncIdentity | null>,
): Promise<GlassesAuthorization> {
  const directKey = request.headers.get("x-ops-glasses-key") ?? "";
  const authorization = request.headers.get("authorization") ?? "";
  if (legacyKey.length > 0 && (directKey === legacyKey || authorization === `Bearer ${legacyKey}`)) {
    return { ok: true, mode: "legacy" };
  }
  const token = bearerToken(authorization);
  if (!token) return { ok: false, status: 401, error: "unauthorized" };
  const identity = await authenticateDevice(token);
  if (!identity) return { ok: false, status: 401, error: "unauthorized" };
  if (!identity.actorProfileId) return { ok: false, status: 403, error: "device_not_bound" };
  return { ok: true, mode: "device", identity };
}

function bearerToken(value: string): string | null {
  return value.startsWith("Bearer ") && value.length > 7 ? value.slice(7) : null;
}
