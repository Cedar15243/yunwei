export type ScopeIdentity = {
  id: string;
  organizationId: string;
  role: "super_admin" | "ops_admin" | "field_engineer" | "remote_expert" | "viewer";
};

export type ProjectMembership = {
  organizationId: string;
  profileId: string;
  projectId: string;
  status: string;
};

export type ProjectScope = {
  allOrganizationProjects: boolean;
  projectIds: string[];
};

export async function resolveProjectScope(
  identity: ScopeIdentity,
  loadMemberships: () => Promise<ProjectMembership[]>,
): Promise<ProjectScope> {
  if (identity.role === "super_admin" || identity.role === "ops_admin") {
    return { allOrganizationProjects: true, projectIds: [] };
  }

  const memberships = await loadMemberships();
  const projectIds = memberships
    .filter((membership) =>
      membership.organizationId === identity.organizationId &&
      membership.profileId === identity.id &&
      membership.status === "active" &&
      membership.projectId
    )
    .map((membership) => membership.projectId);

  return {
    allOrganizationProjects: false,
    projectIds: [...new Set(projectIds)].sort(),
  };
}
