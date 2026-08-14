import { assertEquals } from "jsr:@std/assert@1";
import {
  createSkillKnowledgeDeviceGateway,
  isSkillKnowledgeDevicePath,
  resolveAuthorizedContent,
  routeSkillKnowledgeDevice,
  type DeviceContentManifest,
  type SkillKnowledgeDeviceGateway,
} from "./skill-knowledge-device.ts";

const identity = {
  deviceId: "device-a",
  organizationId: "org-a",
  actorProfileId: "profile-a",
};

function gateway(
  overrides: Partial<SkillKnowledgeDeviceGateway> = {},
): SkillKnowledgeDeviceGateway {
  const now = Date.now();
  return {
    authenticateDevice: async (token) => token === "access-token" ? identity : null,
    getManifest: async (_identity, projectId) => ({
      manifestVersion: 7,
      etag: "a".repeat(64),
      generatedAt: new Date(now - 60_000).toISOString(),
      expiresAt: new Date(now + 15 * 60_000).toISOString(),
      projectId,
      skills: [{
        skillId: "hvac_ddc_repair",
        versionId: "skill-version-a",
        version: "1.0.0",
        name: "DDC 维修",
        description: "控制器报警处置",
        contentSha256: "b".repeat(64),
        knowledgeScopes: ["hvac/ddc"],
        authorizationScope: "device",
      }],
      knowledge: [{
        knowledgeId: "hf_ddc_guide",
        versionId: "knowledge-version-a",
        version: 1,
        title: "DDC 指南",
        summary: "确认供电和总线",
        language: "zh-CN",
        sensitivity: "internal",
        sourceType: "manual",
        sourceReference: "HF-DDC-100-R2",
        contentSha256: "c".repeat(64),
        knowledgeScopes: ["hvac/ddc"],
        validFrom: "2026-08-03T00:00:00.000Z",
        expiresAt: null,
        authorizationScope: "skill_version",
      }],
    }),
    ...overrides,
  };
}

Deno.test("requires a bound short-lived device session for the content manifest", async () => {
  const missing = await routeSkillKnowledgeDevice(request(), gateway());
  const invalid = await routeSkillKnowledgeDevice(request("bad-token"), gateway());
  const unbound = await routeSkillKnowledgeDevice(request("access-token"), gateway({
    authenticateDevice: async () => ({ ...identity, actorProfileId: "" }),
  }));

  assertEquals(missing.status, 401);
  assertEquals(invalid.status, 401);
  assertEquals(unbound.status, 403);
});

Deno.test("recognizes only the device content manifest route across Edge Function prefixes", () => {
  assertEquals(isSkillKnowledgeDevicePath("/device-sync/content-manifest"), true);
  assertEquals(isSkillKnowledgeDevicePath("/ops-glasses/device-sync/content-manifest"), true);
  assertEquals(
    isSkillKnowledgeDevicePath("/functions/v1/ops-glasses/device-sync/content-manifest"),
    true,
  );
  assertEquals(isSkillKnowledgeDevicePath("/device-sync/events"), false);
});

Deno.test("returns the deployed device manifest with a strong ETag and no secret fields", async () => {
  let received: unknown = null;
  const response = await routeSkillKnowledgeDevice(
    request("access-token", "/functions/v1/ops-glasses/device-sync/content-manifest?projectId=project-a"),
    gateway({
      getManifest: async (receivedIdentity, projectId) => {
        received = { receivedIdentity, projectId };
        return await gateway().getManifest(receivedIdentity, projectId);
      },
    }),
  );

  assertEquals(response.status, 200);
  assertEquals(response.headers.get("ETag"), `"${"a".repeat(64)}"`);
  assertEquals(response.headers.get("Cache-Control"), "private, max-age=60, must-revalidate");
  assertEquals(received, { receivedIdentity: identity, projectId: "project-a" });
  const serialized = JSON.stringify(await response.json()).toLowerCase();
  assertEquals(serialized.includes("apikey"), false);
  assertEquals(serialized.includes("secret"), false);
  assertEquals(serialized.includes("token"), false);
  assertEquals(serialized.includes("rules"), false);
  assertEquals(serialized.includes("content\":"), false);
});

Deno.test("returns 304 when the verified device cache already has the current ETag", async () => {
  const response = await routeSkillKnowledgeDevice(
    request("access-token", "/device-sync/content-manifest", `"${"a".repeat(64)}"`),
    gateway(),
  );
  assertEquals(response.status, 304);
  assertEquals(await response.text(), "");
});

Deno.test("rejects an expired manifest instead of marking stale authorization successful", async () => {
  const response = await routeSkillKnowledgeDevice(
    request("access-token"),
    gateway({
      getManifest: async (receivedIdentity, projectId) => ({
        ...await gateway().getManifest(receivedIdentity, projectId) as DeviceContentManifest,
        generatedAt: "1999-12-31T23:45:00.000Z",
        expiresAt: "2000-01-01T00:00:00.000Z",
      }),
    }),
  );

  assertEquals(response.status, 503);
  assertEquals(await response.json(), { ok: false, error: "content_manifest_expired" });
});

Deno.test("rejects invalid project identifiers before querying the manifest", async () => {
  let calls = 0;
  const response = await routeSkillKnowledgeDevice(
    request("access-token", "/device-sync/content-manifest?projectId=bad/project"),
    gateway({ getManifest: async () => { calls += 1; return null; } }),
  );
  assertEquals(response.status, 400);
  assertEquals(calls, 0);
});

Deno.test("direct device manifest rejects a closed project", async () => {
  const database = manifestAuthorizationSupabase({ projectStatus: "closed" });
  const manifest = await createSkillKnowledgeDeviceGateway(database.client)
    .getManifest(identity, "project-a");

  assertEquals(manifest, null);
  assertEquals(database.manifestInserts, 0);
});

Deno.test("direct device manifest rejects a revoked project membership", async () => {
  const database = manifestAuthorizationSupabase({ membershipStatus: "revoked" });
  const manifest = await createSkillKnowledgeDeviceGateway(database.client)
    .getManifest(identity, "project-a");

  assertEquals(manifest, null);
  assertEquals(database.manifestInserts, 0);
});

Deno.test("chooses one authorized published Skill per definition by scope specificity", () => {
  const resolved = resolveAuthorizedContent(
    identity,
    "project-a",
    [
      skillAssignment("assignment-org", "org-a", "organization", null, "skill-v1", "1.0.0", "published"),
      skillAssignment("assignment-project", "org-a", "project", "project-a", "skill-v2", "2.0.0", "published"),
      skillAssignment("assignment-device", "org-a", "device", "device-a", "skill-v3", "3.0.0", "published"),
      skillAssignment("assignment-other", "org-b", "device", "device-a", "skill-v4", "4.0.0", "published"),
      skillAssignment("assignment-draft", "org-a", "device", "device-a", "skill-v5", "5.0.0", "draft"),
    ],
    [],
    new Date("2026-08-03T05:00:00.000Z"),
  );

  assertEquals(resolved.skills.length, 1);
  assertEquals(resolved.skills[0].versionId, "skill-v3");
  assertEquals(resolved.skills[0].authorizationScope, "device");
});

Deno.test("returns only published unexpired knowledge granted to the device or selected Skill", () => {
  const skills = [skillAssignment(
    "assignment-device",
    "org-a",
    "device",
    "device-a",
    "skill-v3",
    "3.0.0",
    "published",
  )];
  const resolved = resolveAuthorizedContent(
    identity,
    "project-a",
    skills,
    [
      knowledgeGrant("grant-skill", "org-a", "skill_version", "skill-v3", "knowledge-v1", "published", null),
      knowledgeGrant(
        "grant-outside-scope",
        "org-a",
        "skill_version",
        "skill-v3",
        "knowledge-outside-scope",
        "published",
        null,
        "hf_electrical_guide",
        ["electrical/low-voltage"],
      ),
      knowledgeGrant("grant-device", "org-a", "device", "device-a", "knowledge-v2", "published", null, "hf_wiring"),
      knowledgeGrant("grant-expired", "org-a", "device", "device-a", "knowledge-v3", "published", "2026-08-03T04:00:00.000Z", "hf_expired"),
      knowledgeGrant("grant-draft", "org-a", "device", "device-a", "knowledge-v4", "parsed", null, "hf_draft"),
      knowledgeGrant("grant-other", "org-b", "device", "device-a", "knowledge-v5", "published", null, "hf_other"),
    ],
    new Date("2026-08-03T05:00:00.000Z"),
  );

  assertEquals(resolved.knowledge.map((item) => item.versionId), ["knowledge-v1", "knowledge-v2"]);
  assertEquals(resolved.knowledge.every((item) => item.contentSha256.length === 64), true);
  assertEquals(resolved.knowledge[0].knowledgeScopes, ["hvac/ddc"]);
});

function request(
  token?: string,
  path = "/device-sync/content-manifest",
  etag?: string,
): Request {
  const headers = new Headers();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (etag) headers.set("If-None-Match", etag);
  return new Request(`https://ops.example${path}`, { headers });
}

function skillAssignment(
  id: string,
  organizationId: string,
  scopeType: string,
  scopeId: string | null,
  versionId: string,
  version: string,
  status: string,
): Record<string, unknown> {
  return {
    id,
    organization_id: organizationId,
    status: "active",
    scope_type: scopeType,
    project_id: scopeType === "project" ? scopeId : null,
    profile_id: scopeType === "profile" ? scopeId : null,
    device_id: scopeType === "device" ? scopeId : null,
    active_from: "2026-08-03T00:00:00.000Z",
    expires_at: null,
    assigned_at: "2026-08-03T01:00:00.000Z",
    skill_versions: {
      id: versionId,
      organization_id: organizationId,
      version,
      status,
      content_sha256: "b".repeat(64),
      rules: { knowledgeScopes: ["hvac/ddc"] },
      skill_definitions: {
        id: "skill-definition-a",
        skill_key: "hvac_ddc_repair",
        name: "DDC 维修",
        description: "控制器报警处置",
      },
    },
  };
}

function knowledgeGrant(
  id: string,
  organizationId: string,
  scopeType: string,
  scopeId: string,
  versionId: string,
  status: string,
  expiresAt: string | null,
  knowledgeKey = "hf_ddc_guide",
  knowledgeScopes: string[] = ["hvac/ddc"],
): Record<string, unknown> {
  return {
    id,
    organization_id: organizationId,
    status: "active",
    scope_type: scopeType,
    project_id: scopeType === "project" ? scopeId : null,
    profile_id: scopeType === "profile" ? scopeId : null,
    device_id: scopeType === "device" ? scopeId : null,
    skill_version_id: scopeType === "skill_version" ? scopeId : null,
    active_from: "2026-08-03T00:00:00.000Z",
    expires_at: expiresAt,
    granted_at: "2026-08-03T01:00:00.000Z",
    knowledge_versions: {
      id: versionId,
      organization_id: organizationId,
      version: 1,
      status,
      title: "DDC 指南",
      summary: "确认供电和总线",
      language: "zh-CN",
      sensitivity: "internal",
      source_type: "manual",
      source_reference: "HF-DDC-100-R2",
      content_sha256: "c".repeat(64),
      knowledge_scopes: knowledgeScopes,
      valid_from: "2026-08-03T00:00:00.000Z",
      expires_at: expiresAt,
      knowledge_entries: { knowledge_key: knowledgeKey },
    },
  };
}

function manifestAuthorizationSupabase(options: {
  projectStatus?: "active" | "closed" | "archived";
  membershipStatus?: "active" | "revoked";
} = {}) {
  let manifestInserts = 0;
  const rows: Record<string, Array<Record<string, unknown>>> = {
    device_bindings: [{
      id: "binding-a",
      organization_id: "org-a",
      device_id: "device-a",
      profile_id: "profile-a",
      project_id: null,
      status: "active",
    }],
    ops_projects: [{
      id: "project-a",
      organization_id: "org-a",
      status: options.projectStatus ?? "active",
    }],
    ops_project_memberships: options.membershipStatus === "revoked" ? [] : [{
      id: "membership-a",
      organization_id: "org-a",
      project_id: "project-a",
      profile_id: "profile-a",
      status: "active",
    }],
    skill_assignments: [],
    knowledge_grants: [],
    device_content_manifests: [],
  };

  const client = {
    from(table: string) {
      const filters = new Map<string, unknown>();
      let nullFilter: { field: string; value: unknown } | null = null;
      let mutation: { kind: "insert" | "update"; value: Record<string, unknown> } | null = null;
      const builder: any = {
        select() {
          return builder;
        },
        eq(field: string, value: unknown) {
          filters.set(field, value);
          return builder;
        },
        is(field: string, value: unknown) {
          nullFilter = { field, value };
          return builder;
        },
        lte() {
          return builder;
        },
        gt() {
          return builder;
        },
        order() {
          return builder;
        },
        limit() {
          return builder;
        },
        update(value: Record<string, unknown>) {
          mutation = { kind: "update", value };
          return builder;
        },
        insert(value: Record<string, unknown>) {
          mutation = { kind: "insert", value };
          if (table === "device_content_manifests") manifestInserts += 1;
          return builder;
        },
        async maybeSingle() {
          const result = execute();
          return { data: result.data[0] ?? null, error: result.error };
        },
        async single() {
          const result = execute();
          return { data: result.data[0] ?? null, error: result.error };
        },
        then(resolve: (value: unknown) => unknown, reject: (reason: unknown) => unknown) {
          return Promise.resolve(execute()).then(resolve, reject);
        },
      };

      function execute(): { data: Array<Record<string, unknown>>; error: null } {
        if (mutation?.kind === "update") return { data: [], error: null };
        if (mutation?.kind === "insert" && table === "device_content_manifests") {
          return {
            data: [{ ...mutation.value, manifest_version: 1 }],
            error: null,
          };
        }
        const filtered = (rows[table] ?? []).filter((row) => {
          if (!Array.from(filters.entries()).every(([field, value]) => row[field] === value)) return false;
          return !nullFilter || row[nullFilter.field] === nullFilter.value;
        });
        return { data: filtered, error: null };
      }

      return builder;
    },
  };

  return {
    client,
    get manifestInserts() {
      return manifestInserts;
    },
  };
}
