import { assertEquals } from "jsr:@std/assert@1";
import { validateWorkflowDraft } from "./workflow-domain.ts";

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

Deno.test("accepts the production workflow node catalog", () => {
  const result = validateWorkflowDraft({
    workflowId: "workflow-a",
    nodes: productionNodeTypes.map((type, index) => ({
      nodeId: `node-${index}`,
      type,
      config: { title: `Node ${index}` },
    })),
  });

  assertEquals(result.errors, []);
});

Deno.test("rejects arbitrary http and script nodes with stable paths", () => {
  const result = validateWorkflowDraft({
    workflowId: "workflow-a",
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

Deno.test("rejects secret-shaped config keys recursively without leaking values", () => {
  const secretValue = "must-not-appear-in-validation-errors";
  const result = validateWorkflowDraft({
    workflowId: "workflow-a",
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
  ]);
  assertEquals(JSON.stringify(result.errors).includes(secretValue), false);
});

Deno.test("rejects malformed draft and node fields with stable errors", () => {
  assertEquals(validateWorkflowDraft(null).errors, [
    { code: "invalid_draft", path: "$" },
  ]);
  assertEquals(
    validateWorkflowDraft({ workflowId: "workflow-a", nodes: {} }).errors,
    [{ code: "nodes_required", path: "$.nodes" }],
  );

  const result = validateWorkflowDraft({
    workflowId: " ",
    nodes: [
      null,
      { nodeId: "", type: "instruction", config: {} },
      { nodeId: "node-b", type: 7, config: "invalid" },
    ],
  });

  assertEquals(result.errors, [
    { code: "workflow_id_required", path: "$.workflowId" },
    { code: "invalid_node", path: "$.nodes[0]" },
    { code: "node_id_required", path: "$.nodes[1].nodeId" },
    { code: "node_type_required", path: "$.nodes[2].type" },
    { code: "invalid_node_config", path: "$.nodes[2].config" },
  ]);
});
