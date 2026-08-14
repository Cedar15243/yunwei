import {
  createDeviceSyncGateway,
  type DeviceSyncIdentity,
} from "./device-sync.ts";

export type SkillManifestItem = {
  skillId: string;
  versionId: string;
  version: string;
  name: string;
  description: string;
  contentSha256: string;
  knowledgeScopes: string[];
  authorizationScope: DirectAuthorizationScope;
};

export type KnowledgeManifestItem = {
  knowledgeId: string;
  versionId: string;
  version: number;
  title: string;
  summary: string;
  language: string;
  sensitivity: string;
  sourceType: string;
  sourceReference: string;
  contentSha256: string;
  knowledgeScopes: string[];
  validFrom: string | null;
  expiresAt: string | null;
  authorizationScope: KnowledgeAuthorizationScope;
};

export type ResolvedAuthorizedContent = {
  skills: SkillManifestItem[];
  knowledge: KnowledgeManifestItem[];
};

export type DeviceContentManifest = ResolvedAuthorizedContent & {
  manifestVersion: number;
  etag: string;
  generatedAt: string;
  expiresAt: string;
  projectId: string | null;
};

export type SkillKnowledgeDeviceGateway = {
  authenticateDevice(token: string): Promise<DeviceSyncIdentity | null>;
  getManifest(
    identity: DeviceSyncIdentity,
    projectId: string | null,
  ): Promise<DeviceContentManifest | null>;
};

const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, content-type, if-none-match",
  "Content-Type": "application/json; charset=utf-8",
};
const identifierPattern = /^[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}$/;
const sha256Pattern = /^[0-9a-f]{64}$/;
type DirectAuthorizationScope = "organization" | "project" | "profile" | "device";
type KnowledgeAuthorizationScope = DirectAuthorizationScope | "skill_version";
const scopePriority = {
  organization: 1,
  project: 2,
  profile: 3,
  device: 4,
  skill_version: 5,
} as const;

export function isSkillKnowledgeDevicePath(path: string): boolean {
  return (path
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/") === "/device-sync/content-manifest";
}

export async function routeSkillKnowledgeDevice(
  request: Request,
  gateway: SkillKnowledgeDeviceGateway,
): Promise<Response> {
  const path = routePath(request);
  if (request.method !== "GET" || path !== "/device-sync/content-manifest") {
    return response({ ok: false, error: "not_found" }, 404);
  }
  const token = bearerToken(request);
  if (!token) return response({ ok: false, error: "unauthorized" }, 401);
  const identity = await gateway.authenticateDevice(token);
  if (!identity) return response({ ok: false, error: "unauthorized" }, 401);
  if (!identity.actorProfileId) return response({ ok: false, error: "device_not_bound" }, 403);

  const projectValue = new URL(request.url).searchParams.get("projectId");
  const projectId = projectValue?.trim() || null;
  if (projectId !== null && !identifierPattern.test(projectId)) {
    return response({ ok: false, error: "project_id_invalid" }, 400);
  }
  const manifest = await gateway.getManifest(identity, projectId);
  if (!manifest) return response({ ok: false, error: "content_manifest_forbidden" }, 403);
  const manifestError = validateManifest(manifest, projectId, Date.now());
  if (manifestError) {
    return response({ ok: false, error: manifestError }, manifestError === "content_manifest_expired" ? 503 : 502, {
      "Cache-Control": "no-store",
    });
  }
  const etag = `"${manifest.etag}"`;
  const responseHeaders = {
    ETag: etag,
    "Cache-Control": "private, max-age=60, must-revalidate",
  };
  if (request.headers.get("If-None-Match")?.trim() === etag) {
    return new Response(null, { status: 304, headers: { ...headers, ...responseHeaders } });
  }
  return response(manifest, 200, responseHeaders);
}

export function createSkillKnowledgeDeviceGateway(
  supabase: any,
): SkillKnowledgeDeviceGateway {
  const deviceGateway = createDeviceSyncGateway(supabase);
  return {
    authenticateDevice: deviceGateway.authenticateDevice,
    async getManifest(identity, projectId) {
      if (!await deviceMayUseProject(supabase, identity, projectId)) return null;
      const now = new Date();
      const nowIso = now.toISOString();
      const [{ data: assignments, error: assignmentError }, { data: grants, error: grantError }] = await Promise.all([
        supabase
          .from("skill_assignments")
          .select(
            "id, organization_id, status, scope_type, project_id, profile_id, device_id, active_from, expires_at, assigned_at, skill_versions!inner(id, organization_id, version, status, content_sha256, rules, skill_definitions!inner(id, skill_key, name, description))",
          )
          .eq("organization_id", identity.organizationId)
          .eq("status", "active")
          .lte("active_from", nowIso),
        supabase
          .from("knowledge_grants")
          .select(
            "id, organization_id, status, scope_type, project_id, profile_id, device_id, skill_version_id, active_from, expires_at, granted_at, knowledge_versions!inner(id, organization_id, version, status, title, summary, language, sensitivity, source_type, source_reference, content_sha256, knowledge_scopes, valid_from, expires_at, knowledge_entries!inner(knowledge_key))",
          )
          .eq("organization_id", identity.organizationId)
          .eq("status", "active")
          .lte("active_from", nowIso),
      ]);
      if (assignmentError) throw assignmentError;
      if (grantError) throw grantError;

      const resolved = resolveAuthorizedContent(
        identity,
        projectId,
        assignments ?? [],
        grants ?? [],
        now,
      );
      const payload = {
        schemaVersion: 1,
        projectId,
        skills: resolved.skills,
        knowledge: resolved.knowledge,
      };
      const etag = await sha256Hex(canonicalJson(payload));
      const existing = await findReusableManifest(
        supabase,
        identity,
        projectId,
        etag,
        nowIso,
      );
      if (existing) return manifestRecord(existing);

      let supersede = supabase
        .from("device_content_manifests")
        .update({ status: "superseded" })
        .eq("organization_id", identity.organizationId)
        .eq("device_id", identity.deviceId)
        .eq("status", "active");
      supersede = projectId === null ? supersede.is("project_id", null) : supersede.eq("project_id", projectId);
      const { error: supersedeError } = await supersede;
      if (supersedeError) throw supersedeError;

      const expiresAt = new Date(now.getTime() + 15 * 60 * 1000).toISOString();
      const { data: inserted, error: insertError } = await supabase
        .from("device_content_manifests")
        .insert({
          organization_id: identity.organizationId,
          device_id: identity.deviceId,
          profile_id: identity.actorProfileId,
          project_id: projectId,
          etag,
          payload,
          status: "active",
          generated_at: nowIso,
          expires_at: expiresAt,
        })
        .select("manifest_version, etag, payload, generated_at, expires_at, project_id")
        .single();
      if (!insertError && inserted) return manifestRecord(inserted);
      if (String(insertError?.code ?? "") === "23505") {
        const concurrent = await findReusableManifest(
          supabase,
          identity,
          projectId,
          etag,
          nowIso,
        );
        if (concurrent) return manifestRecord(concurrent);
      }
      throw insertError;
    },
  };
}

export function resolveAuthorizedContent(
  identity: DeviceSyncIdentity,
  projectId: string | null,
  assignmentRows: Array<Record<string, unknown>>,
  grantRows: Array<Record<string, unknown>>,
  now: Date,
): ResolvedAuthorizedContent {
  const selectedSkills = new Map<string, { priority: number; time: number; item: SkillManifestItem }>();
  for (const assignment of assignmentRows) {
    if (!isActiveOwnedRecord(assignment, identity.organizationId, now)) continue;
    const scope = matchingDirectScope(assignment, identity, projectId);
    if (!scope) continue;
    const version = nestedRecord(assignment.skill_versions);
    const definition = nestedRecord(version.skill_definitions);
    if (
      version.organization_id !== identity.organizationId ||
      version.status !== "published" ||
      typeof version.id !== "string" ||
      typeof version.version !== "string" ||
      typeof version.content_sha256 !== "string" ||
      !sha256Pattern.test(version.content_sha256) ||
      typeof definition.skill_key !== "string" ||
      typeof definition.name !== "string" ||
      typeof definition.description !== "string"
    ) continue;
    const definitionId = typeof definition.id === "string" ? definition.id : definition.skill_key;
    const item: SkillManifestItem = {
      skillId: definition.skill_key,
      versionId: version.id,
      version: version.version,
      name: definition.name,
      description: definition.description,
      contentSha256: version.content_sha256,
      knowledgeScopes: safeStringArray(nestedRecord(version.rules).knowledgeScopes, 100, 200),
      authorizationScope: scope,
    };
    const candidate = {
      priority: scopePriority[scope],
      time: timestampValue(assignment.assigned_at),
      item,
    };
    const existing = selectedSkills.get(definitionId);
    if (!existing || compareCandidate(candidate, existing) > 0) {
      selectedSkills.set(definitionId, candidate);
    }
  }
  const skills = Array.from(selectedSkills.values()).map((entry) => entry.item)
    .sort((left, right) => left.name.localeCompare(right.name));
  const selectedSkillVersions = new Set(skills.map((item) => item.versionId));
  const selectedSkillKnowledgeScopes = new Map(
    skills.map((item) => [item.versionId, new Set(item.knowledgeScopes)]),
  );

  const selectedKnowledge = new Map<string, { priority: number; time: number; version: number; item: KnowledgeManifestItem }>();
  for (const grant of grantRows) {
    if (!isActiveOwnedRecord(grant, identity.organizationId, now)) continue;
    const scope = matchingKnowledgeScope(grant, identity, projectId, selectedSkillVersions);
    if (!scope) continue;
    const version = nestedRecord(grant.knowledge_versions);
    const entry = nestedRecord(version.knowledge_entries);
    if (
      scope === "skill_version" &&
      !knowledgeMatchesSkillScopes(
        version.knowledge_scopes,
        selectedSkillKnowledgeScopes.get(String(grant.skill_version_id)),
      )
    ) continue;
    if (
      version.organization_id !== identity.organizationId ||
      version.status !== "published" ||
      typeof version.id !== "string" ||
      !Number.isInteger(version.version) ||
      typeof version.title !== "string" ||
      typeof version.summary !== "string" ||
      typeof version.language !== "string" ||
      typeof version.sensitivity !== "string" ||
      typeof version.source_type !== "string" ||
      typeof version.source_reference !== "string" ||
      typeof version.content_sha256 !== "string" ||
      !sha256Pattern.test(version.content_sha256) ||
      typeof entry.knowledge_key !== "string" ||
      !versionWindowIsActive(version, now)
    ) continue;
    const item: KnowledgeManifestItem = {
      knowledgeId: entry.knowledge_key,
      versionId: version.id,
      version: Number(version.version),
      title: version.title,
      summary: version.summary,
      language: version.language,
      sensitivity: version.sensitivity,
      sourceType: version.source_type,
      sourceReference: version.source_reference,
      contentSha256: version.content_sha256,
      knowledgeScopes: safeStringArray(version.knowledge_scopes, 100, 200),
      validFrom: nullableString(version.valid_from),
      expiresAt: nullableString(version.expires_at),
      authorizationScope: scope,
    };
    const candidate = {
      priority: scopePriority[scope],
      time: timestampValue(grant.granted_at),
      version: item.version,
      item,
    };
    const existing = selectedKnowledge.get(item.knowledgeId);
    if (
      !existing ||
      candidate.priority > existing.priority ||
      candidate.priority === existing.priority && candidate.version > existing.version ||
      candidate.priority === existing.priority && candidate.version === existing.version && candidate.time > existing.time
    ) {
      selectedKnowledge.set(item.knowledgeId, candidate);
    }
  }
  const knowledge = Array.from(selectedKnowledge.values()).map((entry) => entry.item)
    .sort((left, right) => left.knowledgeId.localeCompare(right.knowledgeId));
  return { skills, knowledge };
}

async function deviceMayUseProject(
  supabase: any,
  identity: DeviceSyncIdentity,
  projectId: string | null,
): Promise<boolean> {
  let bindingQuery = supabase
    .from("device_bindings")
    .select("id, project_id")
    .eq("organization_id", identity.organizationId)
    .eq("device_id", identity.deviceId)
    .eq("profile_id", identity.actorProfileId)
    .eq("status", "active");
  const { data: bindings, error: bindingError } = await bindingQuery;
  if (bindingError) throw bindingError;
  if (!Array.isArray(bindings) || bindings.length === 0) return false;
  if (projectId === null) return true;
  const [
    { data: project, error: projectError },
    { data: membership, error: membershipError },
  ] = await Promise.all([
    supabase
      .from("ops_projects")
      .select("id, status")
      .eq("organization_id", identity.organizationId)
      .eq("id", projectId)
      .eq("status", "active")
      .maybeSingle(),
    supabase
      .from("ops_project_memberships")
      .select("id")
      .eq("organization_id", identity.organizationId)
      .eq("project_id", projectId)
      .eq("profile_id", identity.actorProfileId)
      .eq("status", "active")
      .maybeSingle(),
  ]);
  if (projectError) throw projectError;
  if (membershipError) throw membershipError;
  if (!project || !membership) return false;
  return bindings.some((binding: Record<string, unknown>) =>
    binding.project_id === null || binding.project_id === projectId
  );
}

async function findReusableManifest(
  supabase: any,
  identity: DeviceSyncIdentity,
  projectId: string | null,
  etag: string,
  nowIso: string,
): Promise<Record<string, unknown> | null> {
  let query = supabase
    .from("device_content_manifests")
    .select("manifest_version, etag, payload, generated_at, expires_at, project_id")
    .eq("organization_id", identity.organizationId)
    .eq("device_id", identity.deviceId)
    .eq("profile_id", identity.actorProfileId)
    .eq("etag", etag)
    .eq("status", "active")
    .gt("expires_at", nowIso);
  query = projectId === null ? query.is("project_id", null) : query.eq("project_id", projectId);
  const { data, error } = await query.order("manifest_version", { ascending: false }).limit(1).maybeSingle();
  if (error) throw error;
  return isRecord(data) ? data : null;
}

function manifestRecord(record: Record<string, unknown>): DeviceContentManifest {
  const payload = nestedRecord(record.payload);
  return {
    manifestVersion: Number(record.manifest_version),
    etag: String(record.etag),
    generatedAt: String(record.generated_at),
    expiresAt: String(record.expires_at),
    projectId: nullableString(record.project_id),
    skills: Array.isArray(payload.skills) ? payload.skills.filter(isSkillManifestItem) : [],
    knowledge: Array.isArray(payload.knowledge) ? payload.knowledge.filter(isKnowledgeManifestItem) : [],
  };
}

function matchingDirectScope(
  record: Record<string, unknown>,
  identity: DeviceSyncIdentity,
  projectId: string | null,
): DirectAuthorizationScope | null {
  const scope = record.scope_type;
  if (scope === "organization") return "organization";
  if (scope === "project" && projectId !== null && record.project_id === projectId) return "project";
  if (scope === "profile" && record.profile_id === identity.actorProfileId) return "profile";
  if (scope === "device" && record.device_id === identity.deviceId) return "device";
  return null;
}

function matchingKnowledgeScope(
  record: Record<string, unknown>,
  identity: DeviceSyncIdentity,
  projectId: string | null,
  selectedSkillVersions: Set<string>,
): KnowledgeAuthorizationScope | null {
  const directScope = matchingDirectScope(record, identity, projectId);
  if (directScope) return directScope;
  const scope = record.scope_type;
  if (
    scope === "skill_version" &&
    typeof record.skill_version_id === "string" &&
    selectedSkillVersions.has(record.skill_version_id)
  ) return "skill_version";
  return null;
}

function knowledgeMatchesSkillScopes(
  value: unknown,
  allowedScopes: Set<string> | undefined,
): boolean {
  if (!allowedScopes || allowedScopes.size === 0) return false;
  return safeStringArray(value, 100, 200).some((scope) => allowedScopes.has(scope));
}

function isActiveOwnedRecord(
  record: Record<string, unknown>,
  organizationId: string,
  now: Date,
): boolean {
  if (record.organization_id !== organizationId || record.status !== "active") return false;
  const activeFrom = timestampValue(record.active_from);
  const expiresAt = nullableTimestamp(record.expires_at);
  return activeFrom <= now.getTime() && (expiresAt === null || expiresAt > now.getTime());
}

function versionWindowIsActive(version: Record<string, unknown>, now: Date): boolean {
  const validFrom = nullableTimestamp(version.valid_from);
  const expiresAt = nullableTimestamp(version.expires_at);
  return (validFrom === null || validFrom <= now.getTime()) && (expiresAt === null || expiresAt > now.getTime());
}

function compareCandidate(
  left: { priority: number; time: number },
  right: { priority: number; time: number },
): number {
  return left.priority === right.priority ? left.time - right.time : left.priority - right.priority;
}

function timestampValue(value: unknown): number {
  if (typeof value !== "string") return Number.NEGATIVE_INFINITY;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : Number.NEGATIVE_INFINITY;
}

function nullableTimestamp(value: unknown): number | null {
  if (value === null || value === undefined || value === "") return null;
  const parsed = timestampValue(value);
  return Number.isFinite(parsed) ? parsed : Number.NEGATIVE_INFINITY;
}

function nullableString(value: unknown): string | null {
  return typeof value === "string" && value ? value : null;
}

function safeStringArray(value: unknown, maximumItems: number, maximumLength: number): string[] {
  if (!Array.isArray(value) || value.length > maximumItems) return [];
  const result: string[] = [];
  const seen = new Set<string>();
  for (const item of value) {
    if (typeof item !== "string") continue;
    const text = item.trim();
    if (!text || text.length > maximumLength || seen.has(text)) continue;
    seen.add(text);
    result.push(text);
  }
  return result;
}

function isSkillManifestItem(value: unknown): value is SkillManifestItem {
  return isRecord(value) &&
    typeof value.skillId === "string" &&
    typeof value.versionId === "string" &&
    typeof value.version === "string" &&
    typeof value.name === "string" &&
    typeof value.description === "string" &&
    typeof value.contentSha256 === "string" &&
    sha256Pattern.test(value.contentSha256) &&
    Array.isArray(value.knowledgeScopes) &&
    value.knowledgeScopes.every((scope) => typeof scope === "string") &&
    ["organization", "project", "profile", "device"].includes(String(value.authorizationScope));
}

function isKnowledgeManifestItem(value: unknown): value is KnowledgeManifestItem {
  return isRecord(value) &&
    typeof value.knowledgeId === "string" &&
    typeof value.versionId === "string" &&
    Number.isInteger(value.version) &&
    typeof value.title === "string" &&
    typeof value.summary === "string" &&
    typeof value.language === "string" &&
    typeof value.sensitivity === "string" &&
    typeof value.sourceType === "string" &&
    typeof value.sourceReference === "string" &&
    typeof value.contentSha256 === "string" &&
    sha256Pattern.test(value.contentSha256) &&
    Array.isArray(value.knowledgeScopes) &&
    value.knowledgeScopes.every((scope) => typeof scope === "string") &&
    (value.validFrom === null || typeof value.validFrom === "string") &&
    (value.expiresAt === null || typeof value.expiresAt === "string") &&
    ["organization", "project", "profile", "device", "skill_version"].includes(
      String(value.authorizationScope),
    );
}

function validateManifest(
  manifest: DeviceContentManifest,
  requestedProjectId: string | null,
  now: number,
): "content_manifest_invalid" | "content_manifest_expired" | null {
  const generatedAt = Date.parse(manifest.generatedAt);
  const expiresAt = Date.parse(manifest.expiresAt);
  if (
    !Number.isInteger(manifest.manifestVersion) || manifest.manifestVersion <= 0 ||
    !sha256Pattern.test(manifest.etag) ||
    manifest.projectId !== requestedProjectId ||
    !Number.isFinite(generatedAt) || !Number.isFinite(expiresAt) || generatedAt >= expiresAt ||
    !Array.isArray(manifest.skills) || !manifest.skills.every(isSkillManifestItem) ||
    !Array.isArray(manifest.knowledge) || !manifest.knowledge.every(isKnowledgeManifestItem)
  ) return "content_manifest_invalid";
  return expiresAt <= now ? "content_manifest_expired" : null;
}

function bearerToken(request: Request): string | null {
  const value = request.headers.get("Authorization") ?? "";
  return value.startsWith("Bearer ") && value.slice(7).trim() ? value.slice(7).trim() : null;
}

function routePath(request: Request): string {
  return new URL(request.url).pathname
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/";
}

function nestedRecord(value: unknown): Record<string, unknown> {
  if (Array.isArray(value)) return isRecord(value[0]) ? value[0] : {};
  return isRecord(value) ? value : {};
}

function canonicalJson(value: unknown): string {
  return JSON.stringify(normalizeValue(value));
}

function normalizeValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(normalizeValue);
  if (isRecord(value)) {
    return Object.fromEntries(
      Object.entries(value).sort(([left], [right]) => left.localeCompare(right)).map(
        ([key, child]) => [key, normalizeValue(child)],
      ),
    );
  }
  return value;
}

async function sha256Hex(value: string): Promise<string> {
  const digest = new Uint8Array(
    await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)),
  );
  return Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function response(
  body: unknown,
  status = 200,
  extraHeaders: Record<string, string> = {},
): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...headers, ...extraHeaders } });
}
