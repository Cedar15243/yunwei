import assert from "node:assert/strict";
import path from "node:path";

import { buildV9Sbom } from "./v9-sbom.mjs";

const root = path.resolve(import.meta.dirname, "..");
const sbom = buildV9Sbom(root);

assert.equal(sbom.bomFormat, "CycloneDX");
assert.equal(sbom.specVersion, "1.5");
assert.equal(sbom.version, 1);
assert.match(sbom.serialNumber, /^urn:uuid:[0-9a-f-]{36}$/);
assert.equal(sbom.metadata.component.name, "dingdang-ai-operations-glasses-v9");
assert.equal(sbom.metadata.component.version, "9.0.0");

const components = sbom.components ?? [];
assert.ok(components.length > 50, "SBOM must inventory transitive release dependencies");
assert.equal(new Set(components.map((item) => item["bom-ref"])).size, components.length);
for (const component of components) {
  assert.ok(component["bom-ref"], "component bom-ref is required");
  assert.ok(component.name, `component name is required: ${component["bom-ref"]}`);
  assert.ok(component.version, `component version is required: ${component["bom-ref"]}`);
  assert.ok(["required", "optional", "excluded"].includes(component.scope));
}

const purls = new Set(components.map((item) => item.purl).filter(Boolean));
assert.ok([...purls].some((value) => value.startsWith("pkg:npm/react@")));
assert.ok(purls.has("pkg:pypi/pypdf@6.15.0"));
assert.ok(purls.has("pkg:maven/com.squareup.okhttp3/okhttp@4.12.0"));
assert.ok(purls.has("pkg:generic/iflytek/aikit@previous-model-contract"));
assert.ok(purls.has("pkg:generic/sherpa-onnx/runtime-assets@managed-build-input"));
assert.ok([...purls].some((value) => value.startsWith("pkg:generic/jsr/%40std/assert@")));
assert.ok(
  components.some((item) => item.externalReferences?.some((reference) => (
    reference.type === "distribution" && reference.url.startsWith("https://deno.land/")
  ))),
  "Deno remote modules must remain traceable",
);

const sources = new Set(components.flatMap((item) => (
  item.properties?.filter((property) => property.name === "v9:source")
    .map((property) => property.value) ?? []
)));
for (const source of [
  "package-lock.json",
  "ops-management-web/package-lock.json",
  "v9-ops-gateway/requirements.txt",
  "air3-dingdang-expert-integrated-app/app/build.gradle",
  "deno.lock",
]) {
  assert.ok(sources.has(source), `SBOM source missing: ${source}`);
}

console.log(`V9 SBOM tests passed (${components.length} components).`);
