import { assertEquals } from "jsr:@std/assert@1";
import {
  compileWorkflowDraft,
  validateWorkflowDraft,
  WorkflowValidationException,
} from "./workflow-domain.ts";

const productionNodeTypes = [
  "start",
  "instruction",
  "choice",
  "form",
  "photo_capture",
  "video_capture",
  "voice_input",
  "ai_assist",
  "expert_call",
  "confirmation",
  "condition",
  "repeat_group",
  "subflow",
  "connector_action",
  "complete",
] as const;

type TestNode = {
  nodeId: string;
  type: string;
  config?: Record<string, unknown>;
  editorMetadata?: Record<string, unknown>;
};

type TestTransition = {
  transitionId: string;
  fromNodeId: string;
  toNodeId: string;
  condition?: Record<string, unknown>;
};

type TestDraft = {
  workflowId: string;
  schemaVersion: number;
  title: string;
  nodes: TestNode[];
  transitions: TestTransition[];
  editorMetadata?: Record<string, unknown>;
};

Deno.test("accepts the production workflow node catalog", () => {
  const result = validateWorkflowDraft(productionCatalogDraft());

  assertEquals(result.errors, []);
});

Deno.test("rejects missing entry completion unreachable nodes and dead ends", () => {
  const missingEntry = linearDraft();
  missingEntry.nodes[0].type = "instruction";
  assertEquals(validateWorkflowDraft(missingEntry).errors, [
    { code: "single_start_required", path: "$.nodes" },
  ]);

  const missingCompletion = linearDraft();
  missingCompletion.nodes[2].type = "instruction";
  assertEquals(validateWorkflowDraft(missingCompletion).errors, [
    { code: "complete_required", path: "$.nodes" },
  ]);

  const unreachable = linearDraft();
  unreachable.nodes.push({ nodeId: "orphan", type: "instruction", config: {} });
  assertEquals(validateWorkflowDraft(unreachable).errors, [
    { code: "unreachable_node", path: "$.nodes[3]" },
    { code: "node_cannot_reach_complete", path: "$.nodes[3]" },
  ]);

  const deadEnd = linearDraft();
  deadEnd.nodes.push({ nodeId: "dead-end", type: "instruction", config: {} });
  deadEnd.transitions.push({
    transitionId: "branch-dead",
    fromNodeId: "start",
    toNodeId: "dead-end",
  });
  assertEquals(validateWorkflowDraft(deadEnd).errors, [
    { code: "node_cannot_reach_complete", path: "$.nodes[3]" },
  ]);
});

Deno.test("rejects duplicate nodes dangling transitions and ordinary cycles", () => {
  const duplicate = linearDraft();
  duplicate.nodes.push({ nodeId: "capture", type: "instruction", config: {} });
  assertEquals(validateWorkflowDraft(duplicate).errors, [
    { code: "duplicate_node_id", path: "$.nodes[3].nodeId" },
  ]);

  const dangling = linearDraft();
  dangling.transitions.push({
    transitionId: "dangling",
    fromNodeId: "missing",
    toNodeId: "complete",
  });
  assertEquals(validateWorkflowDraft(dangling).errors, [
    { code: "transition_node_not_found", path: "$.transitions[2].fromNodeId" },
  ]);

  const cycle = linearDraft();
  cycle.nodes.push({ nodeId: "review", type: "instruction", config: {} });
  cycle.transitions[1] = {
    transitionId: "capture-review",
    fromNodeId: "capture",
    toNodeId: "review",
  };
  cycle.transitions.push({
    transitionId: "review-cycle",
    fromNodeId: "review",
    toNodeId: "capture",
  }, {
    transitionId: "review-complete",
    fromNodeId: "review",
    toNodeId: "complete",
  });
  assertEquals(validateWorkflowDraft(cycle).errors, [
    { code: "cycle_not_allowed", path: "$.transitions" },
  ]);
});

Deno.test("allows bounded repeat groups and rejects invalid bounds", () => {
  const valid = linearDraft();
  valid.nodes[1] = {
    nodeId: "capture",
    type: "repeat_group",
    config: { maxIterations: 3 },
  };
  assertEquals(validateWorkflowDraft(valid).errors, []);

  const invalid = structuredClone(valid);
  invalid.nodes[1].config = { maxIterations: 0 };
  assertEquals(validateWorkflowDraft(invalid).errors, [
    {
      code: "repeat_iterations_invalid",
      path: "$.nodes[1].config.maxIterations",
    },
  ]);

  const missing = linearDraft();
  missing.nodes[1] = { nodeId: "capture", type: "repeat_group" };
  assertEquals(validateWorkflowDraft(missing).errors, [
    {
      code: "repeat_iterations_invalid",
      path: "$.nodes[1].config.maxIterations",
    },
  ]);
});

Deno.test("requires exact subflow and connector references", () => {
  const subflow = linearDraft();
  subflow.nodes[1] = { nodeId: "capture", type: "subflow" };
  assertEquals(validateWorkflowDraft(subflow).errors, [
    {
      code: "subflow_version_required",
      path: "$.nodes[1].config.workflowVersionId",
    },
  ]);

  const connector = linearDraft();
  connector.nodes[1] = { nodeId: "capture", type: "connector_action" };
  assertEquals(validateWorkflowDraft(connector).errors, [
    { code: "connector_reference_required", path: "$.nodes[1].config" },
  ]);
});

Deno.test("enforces typed node configuration keys and values", () => {
  const unknownKey = linearDraft();
  unknownKey.nodes[1].config = {
    minCount: 1,
    arbitraryHtml: "<iframe src='https://unsafe.example'>",
  };
  assertEquals(validateWorkflowDraft(unknownKey).errors, [{
    code: "node_config_key_invalid",
    path: "$.nodes[1].config.arbitraryHtml",
  }]);

  const invalidValues = linearDraft();
  invalidValues.nodes[1].config = {
    title: 7,
    minCount: 0,
    offlinePolicy: "silently_continue",
  };
  assertEquals(validateWorkflowDraft(invalidValues).errors, [{
    code: "node_config_value_invalid",
    path: "$.nodes[1].config.title",
  }, {
    code: "node_config_value_invalid",
    path: "$.nodes[1].config.minCount",
  }, {
    code: "node_config_value_invalid",
    path: "$.nodes[1].config.offlinePolicy",
  }]);

  const valid = linearDraft();
  valid.nodes[1].config = {
    title: "拍摄设备铭牌",
    description: "画面需包含完整型号和序列号。",
    minCount: 1,
    allowRetake: true,
    offlinePolicy: "allowed",
    riskLevel: "low",
  };
  assertEquals(validateWorkflowDraft(valid).errors, []);

  const unsafeDeclaredValues = linearDraft();
  unsafeDeclaredValues.nodes[1].config = {
    title: "x".repeat(161),
    description: "<div>伪造页面</div>",
    voicePrompt: "访问 https://unsafe.example",
    allowedActions: ["next", "shell"],
    minCount: 1,
  };
  assertEquals(validateWorkflowDraft(unsafeDeclaredValues).errors, [{
    code: "node_config_value_invalid",
    path: "$.nodes[1].config.title",
  }, {
    code: "node_config_value_invalid",
    path: "$.nodes[1].config.description",
  }, {
    code: "node_config_value_invalid",
    path: "$.nodes[1].config.voicePrompt",
  }, {
    code: "node_config_value_invalid",
    path: "$.nodes[1].config.allowedActions",
  }]);
});

Deno.test("validates safe typed transition conditions", () => {
  const valid = linearDraft();
  valid.transitions[1].condition = {
    operator: "all",
    conditions: [
      { operator: "eq", field: "form.damage", value: false },
      { operator: "exists", field: "evidence.serial_photo" },
    ],
  };
  assertEquals(validateWorkflowDraft(valid).errors, []);

  const unsafe = linearDraft();
  unsafe.transitions[1].condition = {
    operator: "javascript",
    script: "run()",
  };
  assertEquals(validateWorkflowDraft(unsafe).errors, [
    {
      code: "condition_operator_invalid",
      path: "$.transitions[1].condition.operator",
    },
    {
      code: "condition_key_invalid",
      path: "$.transitions[1].condition.script",
    },
  ]);

  const unsafeField = linearDraft();
  unsafeField.transitions[1].condition = {
    operator: "eq",
    field: "form.__proto__.approved",
    value: true,
  };
  assertEquals(validateWorkflowDraft(unsafeField).errors, [
    {
      code: "condition_field_invalid",
      path: "$.transitions[1].condition.field",
    },
  ]);

  const malformedGroup = linearDraft();
  malformedGroup.transitions[1].condition = {
    operator: "all",
    conditions: [],
  };
  assertEquals(validateWorkflowDraft(malformedGroup).errors, [
    {
      code: "condition_children_invalid",
      path: "$.transitions[1].condition.conditions",
    },
  ]);

  const unexpectedKey = linearDraft();
  unexpectedKey.transitions[1].condition = {
    operator: "exists",
    field: "form.damage",
    value: true,
  };
  assertEquals(validateWorkflowDraft(unexpectedKey).errors, [
    {
      code: "condition_key_invalid",
      path: "$.transitions[1].condition.value",
    },
  ]);

  const unsafeValue = linearDraft();
  unsafeValue.transitions[1].condition = {
    operator: "eq",
    field: "form.damage",
    value: { script: "run()" },
  };
  assertEquals(validateWorkflowDraft(unsafeValue).errors, [
    {
      code: "condition_invalid",
      path: "$.transitions[1].condition.value",
    },
  ]);

  const invalidShape = linearDraft();
  invalidShape.transitions[1].condition = [] as unknown as Record<
    string,
    unknown
  >;
  assertEquals(validateWorkflowDraft(invalidShape).errors, [
    {
      code: "condition_invalid",
      path: "$.transitions[1].condition",
    },
  ]);
});

Deno.test("rejects incoming start and outgoing complete transitions", () => {
  const invalid = linearDraft();
  invalid.transitions.push({
    transitionId: "complete-start",
    fromNodeId: "complete",
    toNodeId: "start",
  });
  assertEquals(validateWorkflowDraft(invalid).errors, [
    { code: "complete_has_outgoing", path: "$.transitions[2].fromNodeId" },
    { code: "start_has_incoming", path: "$.transitions[2].toNodeId" },
  ]);
});

Deno.test("compiles equivalent editor drafts to one canonical execution package", async () => {
  const firstDraft = linearDraft();
  firstDraft.editorMetadata = { zoom: 0.8 };
  firstDraft.nodes[1].editorMetadata = { x: 200, y: 80 };
  const secondDraft = linearDraft();
  secondDraft.nodes.reverse();
  secondDraft.transitions.reverse();
  secondDraft.editorMetadata = { zoom: 1.3 };

  const first = await compileWorkflowDraft(firstDraft);
  const second = await compileWorkflowDraft(secondDraft);

  assertEquals(first.contentSha256, second.contentSha256);
  assertEquals(first.nodes, second.nodes);
  assertEquals(first.transitions, second.transitions);
  assertEquals(first.requiredCapabilities, [
    "camera.photo",
    "workflow.runtime.v1",
  ]);
  assertEquals("editorMetadata" in first, false);
});

Deno.test("refuses to compile an invalid workflow", async () => {
  const invalid = linearDraft();
  invalid.transitions = [];
  try {
    await compileWorkflowDraft(invalid);
    throw new Error("compile unexpectedly succeeded");
  } catch (cause) {
    assertEquals(cause instanceof WorkflowValidationException, true);
    assertEquals((cause as WorkflowValidationException).errors, [
      { code: "unreachable_node", path: "$.nodes[1]" },
      { code: "unreachable_node", path: "$.nodes[2]" },
      { code: "node_cannot_reach_complete", path: "$.nodes[0]" },
      { code: "node_cannot_reach_complete", path: "$.nodes[1]" },
    ]);
  }
});

Deno.test("rejects arbitrary http and script nodes with stable paths", () => {
  const result = validateWorkflowDraft({
    workflowId: "workflow-a",
    schemaVersion: 1,
    title: "不安全流程",
    nodes: [
      { nodeId: "unsafe-http", type: "http", config: {} },
      { nodeId: "unsafe-script", type: "script", config: {} },
    ],
  });

  assertEquals(result.errors, [
    { code: "unsupported_node_type", path: "$.nodes[0].type" },
    { code: "unsupported_node_type", path: "$.nodes[1].type" },
  ]);
});

function linearDraft(): TestDraft {
  return {
    workflowId: "workflow-a",
    schemaVersion: 1,
    title: "收货验收",
    nodes: [
      { nodeId: "start", type: "start", config: {}, editorMetadata: {} },
      {
        nodeId: "capture",
        type: "photo_capture",
        config: { minCount: 1 },
        editorMetadata: {},
      },
      { nodeId: "complete", type: "complete", config: {}, editorMetadata: {} },
    ],
    transitions: [
      {
        transitionId: "start-capture",
        fromNodeId: "start",
        toNodeId: "capture",
      },
      {
        transitionId: "capture-complete",
        fromNodeId: "capture",
        toNodeId: "complete",
      },
    ],
    editorMetadata: {},
  };
}

function productionCatalogDraft() {
  const nodes = productionNodeTypes.map((type, index) => ({
    nodeId: `node-${index}`,
    type,
    config: type === "repeat_group"
      ? { maxIterations: 3 }
      : type === "subflow"
      ? { workflowVersionId: "version-1" }
      : type === "connector_action"
      ? { connectorId: "mvs", actionId: "read_order" }
      : {},
  }));
  return {
    workflowId: "workflow-catalog",
    schemaVersion: 1,
    title: "生产节点目录",
    nodes,
    transitions: nodes.slice(0, -1).map((node, index) => ({
      transitionId: `edge-${index}`,
      fromNodeId: node.nodeId,
      toNodeId: nodes[index + 1].nodeId,
    })),
  };
}

Deno.test("rejects secret-shaped config keys recursively without leaking values", () => {
  const secretValue = "must-not-appear-in-validation-errors";
  const result = validateWorkflowDraft({
    workflowId: "workflow-a",
    schemaVersion: 1,
    title: "秘密字段检查",
    nodes: [
      {
        nodeId: "safe-node-a",
        type: "instruction",
        config: { content: [{ apiKey: secretValue }] },
      },
      {
        nodeId: "safe-node-b",
        type: "instruction",
        config: { nested: { authorization: secretValue } },
      },
      {
        nodeId: "safe-node-c",
        type: "instruction",
        config: { url: secretValue },
      },
      {
        nodeId: "safe-node-d",
        type: "instruction",
        config: { accessToken: secretValue },
      },
      {
        nodeId: "safe-node-e",
        type: "instruction",
        config: { clientSecret: secretValue },
      },
      {
        nodeId: "safe-node-f",
        type: "instruction",
        config: { baseUrl: secretValue },
      },
      {
        nodeId: "safe-node-g",
        type: "instruction",
        config: { endpoint: secretValue },
      },
    ],
  });

  assertEquals(result.errors, [
    {
      code: "forbidden_config_key",
      path: "$.nodes[0].config.content[0].apiKey",
    },
    {
      code: "forbidden_config_key",
      path: "$.nodes[1].config.nested.authorization",
    },
    {
      code: "forbidden_config_key",
      path: "$.nodes[2].config.url",
    },
    {
      code: "forbidden_config_key",
      path: "$.nodes[3].config.accessToken",
    },
    {
      code: "forbidden_config_key",
      path: "$.nodes[4].config.clientSecret",
    },
    {
      code: "forbidden_config_key",
      path: "$.nodes[5].config.baseUrl",
    },
    {
      code: "forbidden_config_key",
      path: "$.nodes[6].config.endpoint",
    },
  ]);
  assertEquals(JSON.stringify(result.errors).includes(secretValue), false);
});

Deno.test("rejects malformed draft and node fields with stable errors", () => {
  assertEquals(validateWorkflowDraft(null).errors, [
    { code: "invalid_draft", path: "$" },
  ]);
  assertEquals(
    validateWorkflowDraft({
      workflowId: "workflow-a",
      schemaVersion: 1,
      title: "流程",
      nodes: {},
    }).errors,
    [{ code: "nodes_required", path: "$.nodes" }],
  );

  const result = validateWorkflowDraft({
    workflowId: " ",
    schemaVersion: 0,
    title: " ",
    nodes: [
      null,
      { nodeId: "", type: "instruction", config: {} },
      { nodeId: "node-b", type: 7, config: "invalid" },
    ],
  });

  assertEquals(result.errors, [
    { code: "workflow_id_required", path: "$.workflowId" },
    { code: "schema_version_required", path: "$.schemaVersion" },
    { code: "title_required", path: "$.title" },
    { code: "invalid_node", path: "$.nodes[0]" },
    { code: "node_id_required", path: "$.nodes[1].nodeId" },
    { code: "node_type_required", path: "$.nodes[2].type" },
    { code: "invalid_node_config", path: "$.nodes[2].config" },
  ]);
});
