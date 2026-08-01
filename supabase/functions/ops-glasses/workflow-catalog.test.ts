import { assert, assertEquals } from "jsr:@std/assert@1";
import {
  publicWorkflowCatalog,
  WORKFLOW_NODE_CATALOG,
  WORKFLOW_NODE_TYPES,
} from "./workflow-catalog.ts";

const pageTemplates = new Set([
  "instruction",
  "evidence_capture",
  "form",
  "choice",
  "conversation",
  "confirmation",
  "completion",
  "none",
]);
const categories = new Set([
  "flow",
  "content",
  "evidence",
  "input",
  "assist",
  "integration",
]);
const fieldKinds = new Set([
  "text",
  "textarea",
  "identifier",
  "boolean",
  "integer",
  "select",
  "select_list",
  "uuid_list",
  "options",
  "fields",
  "mappings",
]);
const capabilities = new Set([
  "workflow.runtime.v1",
  "camera.photo",
  "camera.video",
  "audio.voice_input",
  "ai.execution_context",
  "expert.video",
  "connector.gateway",
]);

Deno.test("publishes the stable production node order", () => {
  assertEquals(WORKFLOW_NODE_CATALOG.map((item) => item.type), [
    ...WORKFLOW_NODE_TYPES,
  ]);
  assertEquals(publicWorkflowCatalog.schemaVersion, 1);
  assertEquals(publicWorkflowCatalog.nodes.length, 15);
});

Deno.test("uses unique typed fields and fixed HUD contracts", () => {
  for (const node of WORKFLOW_NODE_CATALOG) {
    assert(categories.has(node.category), `unknown category: ${node.category}`);
    assert(
      pageTemplates.has(node.pageTemplate),
      `unknown page template: ${node.pageTemplate}`,
    );
    if (node.requiredCapability !== null) {
      assert(
        capabilities.has(node.requiredCapability),
        `unknown capability: ${node.requiredCapability}`,
      );
    }
    const keys = node.fields.map((field) => field.key);
    assertEquals(new Set(keys).size, keys.length, `${node.type} field keys`);
    for (const field of node.fields) {
      assert(fieldKinds.has(field.kind), `unknown field kind: ${field.kind}`);
    }
  }

  const photo = WORKFLOW_NODE_CATALOG.find((node) =>
    node.type === "photo_capture"
  );
  assertEquals(photo?.pageTemplate, "evidence_capture");
  assertEquals(photo?.requiredCapability, "camera.photo");
});

Deno.test("public catalog contains no executable or credential transport fields", () => {
  const forbidden = new Set([
    "url",
    "uri",
    "endpoint",
    "baseurl",
    "script",
    "javascript",
    "password",
    "token",
    "apikey",
    "apisecret",
    "authorization",
    "credential",
    "credentials",
  ]);

  walkKeys(publicWorkflowCatalog, (key) => {
    const normalized = key.replace(/[_\-.\s]/g, "").toLowerCase();
    assert(!forbidden.has(normalized), `forbidden public key: ${key}`);
  });
});

function walkKeys(value: unknown, visit: (key: string) => void): void {
  if (Array.isArray(value)) {
    value.forEach((item) => walkKeys(item, visit));
    return;
  }
  if (typeof value !== "object" || value === null) return;
  for (const [key, child] of Object.entries(value)) {
    visit(key);
    walkKeys(child, visit);
  }
}
