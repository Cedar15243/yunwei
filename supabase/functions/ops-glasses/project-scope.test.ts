import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { resolveProjectScope } from "./project-scope.ts";

Deno.test("organization administrators receive organization scope without loading memberships", async () => {
  let loads = 0;
  const scope = await resolveProjectScope(
    { id: "admin-a", organizationId: "org-a", role: "ops_admin" },
    async () => {
      loads += 1;
      return [];
    },
  );

  assertEquals(scope, { allOrganizationProjects: true, projectIds: [] });
  assertEquals(loads, 0);
});

Deno.test("field identities receive only their active same-organization memberships", async () => {
  const scope = await resolveProjectScope(
    { id: "engineer-a", organizationId: "org-a", role: "field_engineer" },
    async () => [
      { organizationId: "org-a", profileId: "engineer-a", projectId: "project-a", status: "active" },
      { organizationId: "org-a", profileId: "engineer-a", projectId: "project-a", status: "active" },
      { organizationId: "org-a", profileId: "engineer-a", projectId: "project-revoked", status: "revoked" },
      { organizationId: "org-b", profileId: "engineer-a", projectId: "project-b", status: "active" },
      { organizationId: "org-a", profileId: "other-user", projectId: "project-other", status: "active" },
    ],
  );

  assertEquals(scope, { allOrganizationProjects: false, projectIds: ["project-a"] });
});

Deno.test("an identity without active project membership receives an empty scope", async () => {
  const scope = await resolveProjectScope(
    { id: "viewer-a", organizationId: "org-a", role: "viewer" },
    async () => [],
  );

  assertEquals(scope, { allOrganizationProjects: false, projectIds: [] });
});
