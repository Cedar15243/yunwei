import { assertEquals, assertThrows } from "jsr:@std/assert@1";
import {
  createSkillKnowledgeSyncGateway,
  executionManifestFromDeviceManifest,
  isSkillKnowledgeSyncPath,
  routeSkillKnowledgeSync,
  type ExecutionContentManifest,
  type SkillKnowledgeSyncGateway,
} from "./skill-knowledge-sync.ts";

const identity = {
  deviceId: "device-a",
  organizationId: "org-a",
  actorProfileId: "profile-a",
};

function skillRules(): Record<string, unknown> {
  return {
    applicableWhen: { systems: ["hvac"] },
    excludedWhen: { systems: [] },
    requiredInputs: ["现场照片"],
    evidenceSchema: { required: ["photo"] },
    steps: [{ id: "inspect", title: "检查供电", instruction: "确认电源指示灯" }],
    safety: ["断电前确认影响范围"],
    outputConstraints: { requiredKnowledgeScopes: ["hvac/ddc"], optionalKnowledgeScopes: [] },
    knowledgeScopes: ["hvac/ddc"],
  };
}

function gateway(
  overrides: Partial<SkillKnowledgeSyncGateway> = {},
): SkillKnowledgeSyncGateway {
  const now = Date.now();
  return {
    configured: () => true,
    authenticateGateway: async (token) => token === "sync-token" ? identity : null,
    getExecutionManifest: async (_identity, localProjectId) => ({
      manifestVersion: 9,
      etag: "a".repeat(64),
      organizationId: "org-a",
      userId: "profile-a",
      deviceId: "device-a",
      projectId: "project-cloud-a",
      generatedAt: new Date(now - 60_000).toISOString(),
      expiresAt: new Date(now + 15 * 60_000).toISOString(),
      skills: [{
        skillId: "hvac_ddc_repair",
        versionId: "skill-version-a",
        version: "1.0.0",
        name: "DDC 维修",
        description: "控制器报警处置",
        status: "published",
        contentSha256: "b".repeat(64),
        rules: skillRules(),
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

Deno.test("recognizes only the server execution manifest path", () => {
  assertEquals(isSkillKnowledgeSyncPath("/internal/v9/content-manifest"), true);
  assertEquals(isSkillKnowledgeSyncPath("/ops-glasses/internal/v9/content-manifest"), true);
  assertEquals(
    isSkillKnowledgeSyncPath("/functions/v1/ops-glasses/internal/v9/content-manifest"),
    true,
  );
  assertEquals(isSkillKnowledgeSyncPath("/device-sync/content-manifest"), false);
});

Deno.test("builds the execution manifest only from selected immutable Skill versions", () => {
  const manifest = executionManifestFromDeviceManifest(
    identity,
    {
      manifestVersion: 9,
      etag: "a".repeat(64),
      projectId: "project-cloud-a",
      generatedAt: "2026-08-03T07:59:00.000Z",
      expiresAt: "2026-08-03T08:15:00.000Z",
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
      knowledge: [],
    },
    [{
      id: "skill-version-a",
      organization_id: "org-a",
      status: "published",
      rules: skillRules(),
    }],
  );

  assertEquals(manifest.organizationId, "org-a");
  assertEquals(manifest.userId, "profile-a");
  assertEquals(manifest.deviceId, "device-a");
  assertEquals(manifest.skills[0].status, "published");
});

Deno.test("refuses executable or credential-shaped fields in server Skill rules", () => {
  assertThrows(
    () => executionManifestFromDeviceManifest(
      identity,
      {
        manifestVersion: 9,
        etag: "a".repeat(64),
        projectId: "project-cloud-a",
        generatedAt: "2026-08-03T07:59:00.000Z",
        expiresAt: "2026-08-03T08:15:00.000Z",
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
        knowledge: [],
      },
      [{
        id: "skill-version-a",
        organization_id: "org-a",
        status: "published",
        rules: { ...skillRules(), endpoint: "https://forbidden.example" },
      }],
    ),
    Error,
    "skill_rules_invalid",
  );
});

Deno.test("requires a configured independent gateway sync credential", async () => {
  const disabled = await routeSkillKnowledgeSync(request("sync-token"), gateway({
    configured: () => false,
  }));
  const missing = await routeSkillKnowledgeSync(request(), gateway());
  const invalid = await routeSkillKnowledgeSync(request("bad-token"), gateway());

  assertEquals(disabled.status, 503);
  assertEquals(missing.status, 401);
  assertEquals(invalid.status, 401);
});

Deno.test("returns a strict server execution manifest without supplier or transport secrets", async () => {
  let received: unknown = null;
  const response = await routeSkillKnowledgeSync(request("sync-token"), gateway({
    getExecutionManifest: async (receivedIdentity, localProjectId) => {
      received = { receivedIdentity, localProjectId };
      return await gateway().getExecutionManifest(receivedIdentity, localProjectId);
    },
  }));

  assertEquals(response.status, 200);
  assertEquals(response.headers.get("ETag"), `"${"a".repeat(64)}"`);
  assertEquals(response.headers.get("X-Manifest-Version"), "9");
  assertEquals(received, { receivedIdentity: identity, localProjectId: "project-local-a" });
  const body = await response.json() as ExecutionContentManifest;
  assertEquals((body as unknown as Record<string, unknown>).localProjectId, undefined);
  assertEquals(body.skills[0].rules.knowledgeScopes, ["hvac/ddc"]);
  const serialized = JSON.stringify(body).toLowerCase();
  assertEquals(serialized.includes('"apikey":'), false);
  assertEquals(serialized.includes('"secret":'), false);
  assertEquals(serialized.includes('"token":'), false);
  assertEquals(serialized.includes('"url":'), false);
  assertEquals(serialized.includes('"script":'), false);
});

Deno.test("returns 304 with renewal headers for an unchanged execution manifest", async () => {
  const response = await routeSkillKnowledgeSync(
    request("sync-token", `"${"a".repeat(64)}"`),
    gateway(),
  );

  assertEquals(response.status, 304);
  assertEquals(response.headers.get("ETag"), `"${"a".repeat(64)}"`);
  assertEquals(response.headers.get("X-Manifest-Version"), "9");
  assertEquals(response.headers.get("X-Manifest-Expires-At")?.length! > 0, true);
  assertEquals(await response.text(), "");
});

Deno.test("rejects missing or malformed local project identifiers before loading data", async () => {
  let calls = 0;
  const missing = await routeSkillKnowledgeSync(
    new Request("https://ops.example/internal/v9/content-manifest", {
      headers: { Authorization: "Bearer sync-token" },
    }),
    gateway({ getExecutionManifest: async () => { calls += 1; return null; } }),
  );
  const malformed = await routeSkillKnowledgeSync(
    new Request("https://ops.example/internal/v9/content-manifest?localProjectId=bad/project", {
      headers: { Authorization: "Bearer sync-token" },
    }),
    gateway({ getExecutionManifest: async () => { calls += 1; return null; } }),
  );

  assertEquals(missing.status, 400);
  assertEquals(malformed.status, 400);
  assertEquals(calls, 0);
});

Deno.test("does not resolve an execution manifest for a closed authoritative project", async () => {
  const database = executionManifestSupabase({ projectStatus: "closed" });
  const manifest = await createSkillKnowledgeSyncGateway(database, syncConfig())
    .getExecutionManifest(identity, "project-local-a");

  assertEquals(manifest, null);
});

Deno.test("does not resolve an execution manifest after project membership is revoked", async () => {
  const database = executionManifestSupabase({ membershipStatus: "revoked" });
  const manifest = await createSkillKnowledgeSyncGateway(database, syncConfig())
    .getExecutionManifest(identity, "project-local-a");

  assertEquals(manifest, null);
});

Deno.test("does not resolve an execution manifest for an incompatible device binding", async () => {
  const database = executionManifestSupabase({ bindingProjectId: "project-other" });
  const manifest = await createSkillKnowledgeSyncGateway(database, syncConfig())
    .getExecutionManifest(identity, "project-local-a");

  assertEquals(manifest, null);
});

Deno.test("resolves the active authoritative project UUID for an authorized profile and device", async () => {
  const manifest = await createSkillKnowledgeSyncGateway(
    executionManifestSupabase(),
    syncConfig(),
  ).getExecutionManifest(identity, "project-local-a");

  assertEquals(manifest?.projectId, "project-cloud-a");
});

function request(token?: string, etag?: string): Request {
  const headers = new Headers();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (etag) headers.set("If-None-Match", etag);
  return new Request(
    "https://ops.example/functions/v1/ops-glasses/internal/v9/content-manifest?localProjectId=project-local-a",
    { headers },
  );
}

function syncConfig() {
  return {
    tokenSha256: "a".repeat(64),
    organizationId: "org-a",
    userId: "profile-a",
    deviceId: "device-a",
  };
}

function executionManifestSupabase(options: {
  projectStatus?: "active" | "closed" | "archived";
  membershipStatus?: "active" | "revoked";
  bindingProjectId?: string | null;
} = {}) {
  const rows: Record<string, Array<Record<string, unknown>>> = {
    ops_projects: [{
      id: "project-cloud-a",
      organization_id: "org-a",
      local_project_id: "project-local-a",
      status: options.projectStatus ?? "active",
    }],
    ops_project_memberships: options.membershipStatus === "revoked"
      ? []
      : [{
        id: "membership-a",
        organization_id: "org-a",
        project_id: "project-cloud-a",
        profile_id: "profile-a",
        status: "active",
      }],
    device_bindings: [{
      id: "binding-a",
      organization_id: "org-a",
      device_id: "device-a",
      profile_id: "profile-a",
      project_id: options.bindingProjectId ?? null,
      status: "active",
    }],
    skill_assignments: [],
    knowledge_grants: [],
    skill_versions: [],
    device_content_manifests: [],
  };

  return {
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
        in() {
          return builder;
        },
        update(value: Record<string, unknown>) {
          mutation = { kind: "update", value };
          return builder;
        },
        insert(value: Record<string, unknown>) {
          mutation = { kind: "insert", value };
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
            data: [{
              ...mutation.value,
              manifest_version: 1,
            }],
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
}
