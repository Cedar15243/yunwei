export type VoiceprintStatus =
  | "new"
  | "enrolling"
  | "pending_verification"
  | "active"
  | "locked"
  | "deleted"
  | "revoked";

export type VoiceprintAdminProfile = {
  profileId: string;
  organizationId: string;
  userId: string;
  deviceId: string;
  provider: "iflytek";
  status: VoiceprintStatus;
  sampleCount: number;
  requiredSamples: number;
  verificationFailures: number;
  consentVersion: string | null;
  consentedAt: string | null;
  lastVerifiedAt: string | null;
  lockedAt: string | null;
  createdAt: string;
  updatedAt: string;
};

export type VoiceprintAdminList = {
  items: VoiceprintAdminProfile[];
  auditChainValid: boolean;
};

export type VoiceprintRevokeCommand = {
  reason: string;
  idempotencyKey: string;
};

export type VoiceprintAdminClient = {
  list(organizationId: string): Promise<VoiceprintAdminList>;
  revoke(
    organizationId: string,
    profileId: string,
    command: VoiceprintRevokeCommand,
  ): Promise<VoiceprintAdminProfile>;
};

export class VoiceprintAdminClientError extends Error {
  constructor(public readonly status: number, public readonly code: string) {
    super(code);
    this.name = "VoiceprintAdminClientError";
  }
}

type FetchLike = (request: Request) => Promise<Response>;
type ClientConfig = { baseUrl: string; token: string; timeoutMs?: number };

const identifierPattern = /^[A-Za-z0-9][A-Za-z0-9._:@/-]{0,199}$/;
const statuses = new Set<VoiceprintStatus>([
  "new",
  "enrolling",
  "pending_verification",
  "active",
  "locked",
  "deleted",
  "revoked",
]);

export function createVoiceprintAdminClient(
  config: ClientConfig,
  fetchImpl: FetchLike = fetch,
): VoiceprintAdminClient {
  const baseUrl = config.baseUrl.trim().replace(/\/+$/, "");
  const token = config.token.trim();
  const timeoutMs = config.timeoutMs ?? 8_000;
  const configured = baseUrl.startsWith("https://") && token.length >= 16 && timeoutMs >= 1_000 && timeoutMs <= 30_000;

  async function request(path: string, init: RequestInit = {}): Promise<Record<string, unknown>> {
    if (!configured) throw new VoiceprintAdminClientError(503, "voiceprint_admin_not_configured");
    let response: Response;
    try {
      response = await fetchImpl(new Request(`${baseUrl}${path}`, {
        ...init,
        headers: {
          ...init.headers,
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        signal: AbortSignal.timeout(timeoutMs),
      }));
    } catch {
      throw new VoiceprintAdminClientError(503, "voiceprint_admin_unavailable");
    }
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      const code = isRecord(payload) && typeof payload.error === "string"
        ? payload.error
        : "voiceprint_admin_unavailable";
      throw new VoiceprintAdminClientError(response.status, code);
    }
    if (!isRecord(payload)) throw new VoiceprintAdminClientError(502, "voiceprint_admin_response_invalid");
    return payload;
  }

  return {
    async list(organizationId) {
      const payload = await request("/admin/voiceprints");
      if (!Array.isArray(payload.items) || typeof payload.auditChainValid !== "boolean") {
        throw new VoiceprintAdminClientError(502, "voiceprint_admin_response_invalid");
      }
      return {
        auditChainValid: payload.auditChainValid,
        items: payload.items.map((item) => profileFromPayload(item, organizationId)),
      };
    },
    async revoke(organizationId, profileId, command) {
      if (!identifierPattern.test(profileId)) {
        throw new VoiceprintAdminClientError(400, "voiceprint_profile_invalid");
      }
      const payload = await request(`/admin/voiceprints/${encodeURIComponent(profileId)}/revoke`, {
        method: "POST",
        body: JSON.stringify({
          confirmation: "REVOKE VOICEPRINT",
          reason: command.reason,
          idempotencyKey: command.idempotencyKey,
        }),
      });
      return profileFromPayload(payload, organizationId);
    },
  };
}

function profileFromPayload(value: unknown, organizationId: string): VoiceprintAdminProfile {
  if (!isRecord(value)) throw new VoiceprintAdminClientError(502, "voiceprint_admin_response_invalid");
  const profileId = requiredIdentifier(value.profileId);
  const receivedOrganizationId = requiredIdentifier(value.organizationId);
  const userId = requiredIdentifier(value.userId);
  const deviceId = requiredIdentifier(value.deviceId);
  const status = typeof value.status === "string" && statuses.has(value.status as VoiceprintStatus)
    ? value.status as VoiceprintStatus
    : null;
  if (
    !profileId || !receivedOrganizationId || receivedOrganizationId !== organizationId ||
    !userId || !deviceId || value.provider !== "iflytek" || !status
  ) throw new VoiceprintAdminClientError(502, "voiceprint_scope_mismatch");
  return {
    profileId,
    organizationId: receivedOrganizationId,
    userId,
    deviceId,
    provider: "iflytek",
    status,
    sampleCount: requiredCount(value.sampleCount),
    requiredSamples: requiredCount(value.requiredSamples),
    verificationFailures: requiredCount(value.verificationFailures),
    consentVersion: optionalText(value.consentVersion),
    consentedAt: optionalText(value.consentedAt),
    lastVerifiedAt: optionalText(value.lastVerifiedAt),
    lockedAt: optionalText(value.lockedAt),
    createdAt: requiredText(value.createdAt),
    updatedAt: requiredText(value.updatedAt),
  };
}

function requiredIdentifier(value: unknown): string | null {
  return typeof value === "string" && identifierPattern.test(value) ? value : null;
}

function requiredText(value: unknown): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new VoiceprintAdminClientError(502, "voiceprint_admin_response_invalid");
  }
  return value.trim();
}

function optionalText(value: unknown): string | null {
  return value === null || value === undefined ? null : requiredText(value);
}

function requiredCount(value: unknown): number {
  if (!Number.isInteger(value) || Number(value) < 0 || Number(value) > 10_000) {
    throw new VoiceprintAdminClientError(502, "voiceprint_admin_response_invalid");
  }
  return Number(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}
