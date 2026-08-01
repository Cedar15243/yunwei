import { assertEquals } from "jsr:@std/assert@1";
import {
  BindingCandidate,
  resolveWorkflowBinding,
  WorkOrderFacts,
} from "./workflow-binding.ts";

const now = new Date("2026-08-01T08:00:00.000Z");

Deno.test("resolves manual binding before trusted external and policy rules", () => {
  const result = resolveWorkflowBinding(workOrder(), [
    candidate("organization-default", "organization_default", "optional", []),
    candidate("project", "project", "optional", [
      { field: "projectId", operator: "eq", value: "project-a" },
    ]),
    candidate("external", "trusted_external", "required", [
      { field: "externalSystem", operator: "eq", value: "mvs" },
      {
        field: "externalWorkflowCode",
        operator: "eq",
        value: "receiving-v3",
      },
    ]),
    candidate("manual", "manual", "required", [
      { field: "workOrderId", operator: "eq", value: "order-1" },
    ]),
  ], { now });

  assertEquals(result, {
    kind: "assigned",
    source: "manual",
    mode: "required",
    workflowVersionId: "version-manual",
    candidateId: "manual",
  });
});

Deno.test("resolves trusted external mapping before project rules", () => {
  const result = resolveWorkflowBinding(workOrder(), [
    candidate("project", "project", "required", [
      { field: "projectId", operator: "eq", value: "project-a" },
    ]),
    candidate("external", "trusted_external", "optional", [
      { field: "externalSystem", operator: "eq", value: "mvs" },
      {
        field: "externalWorkflowCode",
        operator: "eq",
        value: "receiving-v3",
      },
    ]),
  ], { now });

  assertEquals(result, {
    kind: "assigned",
    source: "trusted_external",
    mode: "optional",
    workflowVersionId: "version-external",
    candidateId: "external",
  });
});

Deno.test("prefers the most specific matching rule within one source", () => {
  const result = resolveWorkflowBinding(workOrder(), [
    candidate("asset-generic", "asset_order_type", "optional", [
      { field: "workOrderType", operator: "eq", value: "repair" },
    ]),
    candidate("asset-specific", "asset_order_type", "required", [
      { field: "workOrderType", operator: "eq", value: "repair" },
      { field: "assetBrand", operator: "eq", value: "honeywell" },
      { field: "tags", operator: "contains", value: "receiving" },
    ]),
  ], { now });

  assertEquals(result, {
    kind: "assigned",
    source: "asset_order_type",
    mode: "required",
    workflowVersionId: "version-asset-specific",
    candidateId: "asset-specific",
  });
});

Deno.test("returns conflict for equally specific rules with different outcomes", () => {
  const result = resolveWorkflowBinding(workOrder(), [
    candidate("project-a", "project", "required", [
      { field: "projectId", operator: "eq", value: "project-a" },
    ]),
    candidate("project-b", "project", "optional", [
      { field: "projectId", operator: "eq", value: "project-a" },
    ]),
    candidate("organization-default", "organization_default", "required", []),
  ], { now });

  assertEquals(result, {
    kind: "conflict",
    candidateIds: ["project-a", "project-b"],
  });
});

Deno.test("returns explicit none with its audit source and implicit none without a match", () => {
  const explicit = resolveWorkflowBinding(workOrder(), [
    candidate("manual-none", "manual", "none", [
      { field: "workOrderId", operator: "eq", value: "order-1" },
    ]),
  ], { now });
  assertEquals(explicit, {
    kind: "none",
    mode: "none",
    source: "manual",
    candidateId: "manual-none",
  });

  assertEquals(resolveWorkflowBinding(workOrder(), [], { now }), {
    kind: "none",
    mode: "none",
  });
});

Deno.test("ignores cross organization expired unpublished and malformed candidates", () => {
  const crossOrganization = candidate(
    "cross-organization",
    "manual",
    "required",
    [{ field: "workOrderId", operator: "eq", value: "order-1" }],
  );
  crossOrganization.organizationId = "organization-b";

  const expired = candidate("expired", "manual", "required", [
    { field: "workOrderId", operator: "eq", value: "order-1" },
  ]);
  expired.activeUntil = "2026-07-31T00:00:00.000Z";

  const unpublished = candidate("unpublished", "manual", "required", [
    { field: "workOrderId", operator: "eq", value: "order-1" },
  ]);
  unpublished.workflowVersionStatus = "draft";

  const unknownField = candidate(
    "unknown-field",
    "manual",
    "required",
    [
      { field: "adminToken", operator: "eq", value: "secret" },
    ] as unknown as BindingCandidate["conditions"],
  );

  const malformedMode = candidate("malformed-mode", "manual", "required", [
    { field: "workOrderId", operator: "eq", value: "order-1" },
  ]);
  malformedMode.mode = "automatic" as BindingCandidate["mode"];

  const result = resolveWorkflowBinding(workOrder(), [
    crossOrganization,
    expired,
    unpublished,
    unknownField,
    malformedMode,
    candidate("organization-default", "organization_default", "optional", []),
  ], { now });

  assertEquals(result, {
    kind: "assigned",
    source: "organization_default",
    mode: "optional",
    workflowVersionId: "version-organization-default",
    candidateId: "organization-default",
  });
});

Deno.test("requires source appropriate predicates and deterministic duplicate selection", () => {
  const invalidManual = candidate("invalid-manual", "manual", "required", [
    { field: "projectId", operator: "eq", value: "project-a" },
  ]);
  const invalidDefault = candidate(
    "invalid-default",
    "organization_default",
    "required",
    [{ field: "workOrderType", operator: "eq", value: "repair" }],
  );
  const broadManual = candidate("broad-manual", "manual", "required", [
    { field: "workOrderId", operator: "in", value: ["order-1"] },
  ]);
  const duplicateB = candidate("same-b", "project", "required", [
    { field: "projectId", operator: "eq", value: "project-a" },
  ]);
  duplicateB.workflowVersionId = "version-same";
  const duplicateA = candidate("same-a", "project", "required", [
    { field: "projectId", operator: "eq", value: "project-a" },
  ]);
  duplicateA.workflowVersionId = "version-same";

  const result = resolveWorkflowBinding(workOrder(), [
    invalidManual,
    invalidDefault,
    broadManual,
    duplicateB,
    duplicateA,
  ], { now });

  assertEquals(result, {
    kind: "assigned",
    source: "project",
    mode: "required",
    workflowVersionId: "version-same",
    candidateId: "same-a",
  });
});

Deno.test("safely ignores malformed runtime values and duplicate predicates", () => {
  const malformedConditions = candidate(
    "malformed-conditions",
    "manual",
    "required",
    [{ field: "workOrderId", operator: "eq", value: "order-1" }],
  );
  malformedConditions.conditions = [
    null as unknown as BindingCandidate["conditions"][number],
  ];

  const repeated = candidate("repeated", "asset_order_type", "required", [
    { field: "workOrderType", operator: "eq", value: "repair" },
    { field: "workOrderType", operator: "eq", value: "repair" },
    { field: "workOrderType", operator: "eq", value: "repair" },
  ]);
  const legitimate = candidate("legitimate", "asset_order_type", "optional", [
    { field: "workOrderType", operator: "eq", value: "repair" },
    { field: "assetBrand", operator: "eq", value: "honeywell" },
  ]);

  const result = resolveWorkflowBinding(workOrder(), [
    null as unknown as BindingCandidate,
    malformedConditions,
    repeated,
    legitimate,
  ], { now });

  assertEquals(result, {
    kind: "assigned",
    source: "asset_order_type",
    mode: "optional",
    workflowVersionId: "version-legitimate",
    candidateId: "legitimate",
  });
});

function workOrder(): WorkOrderFacts {
  return {
    organizationId: "organization-a",
    workOrderId: "order-1",
    externalSystem: "mvs",
    externalWorkflowCode: "receiving-v3",
    customerId: "customer-a",
    projectId: "project-a",
    workOrderType: "repair",
    assetCategory: "ddc",
    assetBrand: "honeywell",
    assetModel: "xl50",
    faultType: "offline",
    priority: "high",
    riskLevel: "medium",
    tags: ["receiving", "hvac"],
  };
}

function candidate(
  candidateId: string,
  source: BindingCandidate["source"],
  mode: BindingCandidate["mode"],
  conditions: BindingCandidate["conditions"],
): BindingCandidate {
  return {
    candidateId,
    organizationId: "organization-a",
    source,
    mode,
    workflowVersionId: mode === "none" ? undefined : `version-${candidateId}`,
    workflowVersionStatus: "published",
    enabled: true,
    conditions,
    activeFrom: "2026-07-01T00:00:00.000Z",
    activeUntil: "2026-09-01T00:00:00.000Z",
  };
}
