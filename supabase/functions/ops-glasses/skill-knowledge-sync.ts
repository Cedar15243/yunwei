import {
  deviceMayAccessProject,
  type DeviceSyncIdentity,
} from "./device-sync.ts";
import {
  createSkillKnowledgeDeviceGateway,
  type DeviceContentManifest,
  type KnowledgeManifestItem,
} from "./skill-knowledge-device.ts";

export type ExecutionSkillManifestItem = {
  skillId: string;
  versionId: string;
  version: string;
  name: string;
  description: string;
  status: "published";
  contentSha256: string;
  rules: Record<string, unknown>;
  authorizationScope: "organization" | "project" | "profile" | "device";
};

export type ExecutionContentManifest = {
  manifestVersion: number;
  etag: string;
  organizationId: string;
  userId: string;
  deviceId: string;
  projectId: string;
  generatedAt: string;
  expiresAt: string;
  skills: ExecutionSkillManifestItem[];
  knowledge: KnowledgeManifestItem[];
};

export type SkillKnowledgeSyncGateway = {
  configured(): boolean;
  authenticateGateway(token: string): Promise<DeviceSyncIdentity | null>;
  getExecutionManifest(
    identity: DeviceSyncIdentity,
    localProjectId: string,
  ): Promise<ExecutionContentManifest | null>;
};

export type SkillKnowledgeSyncConfig = {
  tokenSha256: string;
  organizationId: string;
  userId: string;
  deviceId: string;
};

const identifierPattern = /^[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}$/;
const sha256Pattern = /^[0-9a-f]{64}$/;
const ruleFields = new Set([
  "applicableWhen",
  "excludedWhen",
  "requiredInputs",
  "evidenceSchema",
  "steps",
  "safety",
  "outputConstraints",
  "knowledgeScopes",
]);
const forbiddenNestedKeys = new Set([
  "apikey",
  "apisecret",
  "credential",
  "endpoint",
  "password",
  "script",
  "secret",
  "secretkey",
  "token",
  "url",
]);
const responseHeaders = {
  "Content-Type": "application/json; charset=utf-8",
  "Cache-Control": "no-store",
};

export function isSkillKnowledgeSyncPath(path: string): boolean {
  return normalizePath(path) === "/internal/v9/content-manifest";
}

export async function routeSkillKnowledgeSync(
  request: Request,
  gateway: SkillKnowledgeSyncGateway,
): Promise<Response> {
  if (request.method !== "GET" || normalizePath(new URL(request.url).pathname) !== "/internal/v9/content-manifest") {
    return json({ ok: false, error: "not_found" }, 404);
  }
  if (!gateway.configured()) {
    return json({ ok: false, error: "content_sync_not_configured" }, 503);
  }
  const token = bearerToken(request);
  if (!token) return json({ ok: false, error: "unauthorized" }, 401);
  const identity = await gateway.authenticateGateway(token);
  if (!identity || !identity.actorProfileId) {
    return json({ ok: false, error: "unauthorized" }, 401);
  }
  const localProjectId = new URL(request.url).searchParams.get("localProjectId")?.trim() ?? "";
  if (!identifierPattern.test(localProjectId)) {
    return json({ ok: false, error: "local_project_id_invalid" }, 400);
  }
  const manifest = await gateway.getExecutionManifest(identity, localProjectId);
  if (!manifest) return json({ ok: false, error: "content_manifest_forbidden" }, 403);
  const error = validateExecutionManifest(manifest, identity, Date.now());
  if (error) {
    return json(
      { ok: false, error },
      error === "content_manifest_expired" ? 503 : 502,
    );
  }
  const etag = `"${manifest.etag}"`;
  const manifestHeaders = {
    ETag: etag,
    "X-Manifest-Version": String(manifest.manifestVersion),
    "X-Manifest-Expires-At": manifest.expiresAt,
  };
  if (request.headers.get("If-None-Match")?.trim() === etag) {
    return new Response(null, {
      status: 304,
      headers: { ...responseHeaders, ...manifestHeaders },
    });
  }
  return json(executionManifestPayload(manifest), 200, manifestHeaders);
}

export function createSkillKnowledgeSyncGateway(
  supabase: any,
  config: SkillKnowledgeSyncConfig,
): SkillKnowledgeSyncGateway {
  const normalized = {
    tokenSha256: config.tokenSha256.trim().toLowerCase(),
    organizationId: config.organizationId.trim(),
    userId: config.userId.trim(),
    deviceId: config.deviceId.trim(),
  };
  const isConfigured = () =>
    sha256Pattern.test(normalized.tokenSha256) &&
    identifierPattern.test(normalized.organizationId) &&
    identifierPattern.test(normalized.userId) &&
    identifierPattern.test(normalized.deviceId);
  const identity: DeviceSyncIdentity = {
    organizationId: normalized.organizationId,
    actorProfileId: normalized.userId,
    deviceId: normalized.deviceId,
  };
  const deviceGateway = createSkillKnowledgeDeviceGateway(supabase);
  return {
    configured: isConfigured,
    async authenticateGateway(token) {
      if (!isConfigured()) return null;
      const candidate = await sha256Hex(token);
      return constantTimeEqual(candidate, normalized.tokenSha256) ? identity : null;
    },
    async getExecutionManifest(authenticatedIdentity, localProjectId) {
      if (
        authenticatedIdentity.organizationId !== identity.organizationId ||
        authenticatedIdentity.actorProfileId !== identity.actorProfileId ||
        authenticatedIdentity.deviceId !== identity.deviceId
      ) return null;
      const { data: project, error: projectError } = await supabase
        .from("ops_projects")
        .select("id, status")
        .eq("organization_id", identity.organizationId)
        .eq("local_project_id", localProjectId)
        .eq("status", "active")
        .maybeSingle();
      if (projectError) throw projectError;
      if (!project || typeof project.id !== "string") return null;
      if (!await deviceMayAccessProject(supabase, identity, project.id)) return null;
      const deviceManifest = await deviceGateway.getManifest(identity, project.id);
      if (!deviceManifest || deviceManifest.projectId !== project.id) return null;
      const versionIds = deviceManifest.skills.map((skill) => skill.versionId);
      let rows: Array<Record<string, unknown>> = [];
      if (versionIds.length > 0) {
        const { data, error } = await supabase
          .from("skill_versions")
          .select("id, organization_id, status, rules")
          .eq("organization_id", identity.organizationId)
          .in("id", versionIds);
        if (error) throw error;
        rows = Array.isArray(data) ? data : [];
      }
      return executionManifestFromDeviceManifest(identity, deviceManifest, rows);
    },
  };
}

export function executionManifestFromDeviceManifest(
  identity: DeviceSyncIdentity,
  manifest: DeviceContentManifest,
  skillVersionRows: Array<Record<string, unknown>>,
): ExecutionContentManifest {
  if (!manifest.projectId || !identifierPattern.test(manifest.projectId)) {
    throw new Error("content_manifest_project_invalid");
  }
  const rows = new Map(skillVersionRows.map((row) => [String(row.id ?? ""), row]));
  const skills = manifest.skills.map((skill): ExecutionSkillManifestItem => {
    const row = rows.get(skill.versionId);
    if (
      !row || row.organization_id !== identity.organizationId ||
      row.status !== "published" || !isRecord(row.rules)
    ) throw new Error("content_manifest_skill_unavailable");
    const rules = strictSkillRules(row.rules);
    return {
      skillId: skill.skillId,
      versionId: skill.versionId,
      version: skill.version,
      name: skill.name,
      description: skill.description,
      status: "published",
      contentSha256: skill.contentSha256,
      rules,
      authorizationScope: skill.authorizationScope,
    };
  });
  return {
    manifestVersion: manifest.manifestVersion,
    etag: manifest.etag,
    organizationId: identity.organizationId,
    userId: identity.actorProfileId,
    deviceId: identity.deviceId,
    projectId: manifest.projectId,
    generatedAt: manifest.generatedAt,
    expiresAt: manifest.expiresAt,
    skills,
    knowledge: manifest.knowledge,
  };
}

function executionManifestPayload(manifest: ExecutionContentManifest): ExecutionContentManifest {
  return {
    manifestVersion: manifest.manifestVersion,
    etag: manifest.etag,
    organizationId: manifest.organizationId,
    userId: manifest.userId,
    deviceId: manifest.deviceId,
    projectId: manifest.projectId,
    generatedAt: manifest.generatedAt,
    expiresAt: manifest.expiresAt,
    skills: manifest.skills,
    knowledge: manifest.knowledge,
  };
}

function validateExecutionManifest(
  manifest: ExecutionContentManifest,
  identity: DeviceSyncIdentity,
  now: number,
): "content_manifest_invalid" | "content_manifest_expired" | null {
  const generatedAt = Date.parse(manifest.generatedAt);
  const expiresAt = Date.parse(manifest.expiresAt);
  if (
    !Number.isInteger(manifest.manifestVersion) || manifest.manifestVersion <= 0 ||
    !sha256Pattern.test(manifest.etag) ||
    manifest.organizationId !== identity.organizationId ||
    manifest.userId !== identity.actorProfileId ||
    manifest.deviceId !== identity.deviceId ||
    !identifierPattern.test(manifest.projectId) ||
    !Number.isFinite(generatedAt) || !Number.isFinite(expiresAt) || generatedAt >= expiresAt ||
    !Array.isArray(manifest.skills) || !Array.isArray(manifest.knowledge)
  ) return "content_manifest_invalid";
  return expiresAt <= now ? "content_manifest_expired" : null;
}

function strictSkillRules(value: Record<string, unknown>): Record<string, unknown> {
  if (Object.keys(value).some((key) => !ruleFields.has(key)) || containsForbiddenKey(value)) {
    throw new Error("skill_rules_invalid");
  }
  const serialized = JSON.stringify(value);
  if (serialized.length > 128_000) throw new Error("skill_rules_invalid");
  return JSON.parse(serialized) as Record<string, unknown>;
}

function containsForbiddenKey(value: unknown): boolean {
  if (Array.isArray(value)) return value.some(containsForbiddenKey);
  if (!isRecord(value)) return false;
  for (const [key, nested] of Object.entries(value)) {
    if (forbiddenNestedKeys.has(key.toLowerCase().replace(/[^a-z0-9]/g, ""))) return true;
    if (containsForbiddenKey(nested)) return true;
  }
  return false;
}

function bearerToken(request: Request): string | null {
  const value = request.headers.get("Authorization") ?? "";
  return value.startsWith("Bearer ") && value.slice(7).trim() ? value.slice(7).trim() : null;
}

function normalizePath(path: string): string {
  return (path
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/");
}

async function sha256Hex(value: string): Promise<string> {
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
  return Array.from(hash, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function constantTimeEqual(left: string, right: string): boolean {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let index = 0; index < left.length; index += 1) {
    difference |= left.charCodeAt(index) ^ right.charCodeAt(index);
  }
  return difference === 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function json(body: unknown, status: number, extraHeaders: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...responseHeaders, ...extraHeaders },
  });
}
