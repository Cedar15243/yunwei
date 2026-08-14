import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const readText = (filePath) => fs.readFileSync(filePath, "utf8");
const readJson = (filePath) => JSON.parse(readText(filePath).replace(/^\uFEFF/, ""));

function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function uuidFromDigest(value) {
  const bytes = Buffer.from(sha256(value).slice(0, 32), "hex");
  bytes[6] = (bytes[6] & 0x0f) | 0x50;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function purlPart(value) {
  return encodeURIComponent(value).replaceAll("%2F", "/");
}

function bomRef(purl, source) {
  return `urn:v9:component:${sha256(`${source}\n${purl}`).slice(0, 32)}`;
}

function component({
  type = "library",
  name,
  version,
  purl,
  scope = "required",
  source,
  identity,
  license,
  hashes,
  externalReferences,
}) {
  const item = {
    type,
    name,
    version,
    scope,
    "bom-ref": bomRef(purl, `${source}:${identity ?? purl}`),
    purl,
    properties: [{ name: "v9:source", value: source }],
  };
  if (license) item.licenses = [{ license: { id: license } }];
  if (hashes?.length) item.hashes = hashes;
  if (externalReferences?.length) item.externalReferences = externalReferences;
  return item;
}

function npmPackageName(packagePath) {
  const marker = "/node_modules/";
  const last = packagePath.lastIndexOf(marker);
  const relative = (last >= 0 ? packagePath.slice(last + marker.length) : packagePath)
    .replace(/^node_modules\//, "");
  if (relative.startsWith("@")) return relative.split("/").slice(0, 2).join("/");
  return relative.split("/")[0];
}

function npmComponents(root, relativePath) {
  const source = relativePath.replaceAll("\\", "/");
  const lock = readJson(path.join(root, relativePath));
  const packages = lock.packages ?? {};
  return Object.entries(packages)
    .filter(([key, value]) => key.startsWith("node_modules/") && value?.version)
    .map(([key, value]) => {
      const name = npmPackageName(key);
      const purl = `pkg:npm/${purlPart(name)}@${purlPart(value.version)}`;
      const hashes = typeof value.integrity === "string" && value.integrity.startsWith("sha512-")
        ? [{ alg: "SHA-512", content: value.integrity.slice("sha512-".length) }]
        : undefined;
      return component({
        name,
        version: value.version,
        purl,
        source,
        identity: key,
        scope: value.optional ? "optional" : value.dev ? "optional" : "required",
        license: typeof value.license === "string" && /^[A-Za-z0-9.+-]+$/.test(value.license)
          ? value.license
          : undefined,
        hashes,
        externalReferences: value.resolved
          ? [{ type: "distribution", url: value.resolved }]
          : undefined,
      });
    });
}

function pythonComponents(root) {
  const source = "v9-ops-gateway/requirements.txt";
  return readText(path.join(root, source))
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith("#"))
    .map((line) => {
      const match = line.match(/^([A-Za-z0-9_.-]+)==([A-Za-z0-9_.!+-]+)$/);
      if (!match) throw new Error(`Python dependency must be pinned: ${line}`);
      const [, name, version] = match;
      return component({
        name,
        version,
        purl: `pkg:pypi/${purlPart(name.toLowerCase())}@${purlPart(version)}`,
        source,
      });
    });
}

function androidComponents(root) {
  const source = "air3-dingdang-expert-integrated-app/app/build.gradle";
  const build = readText(path.join(root, source));
  const items = [];
  const dependencyPattern = /^\s*(implementation|api|testImplementation|androidTestImplementation|debugImplementation|releaseImplementation)\s+["']([^:]+):([^:]+):([^"']+)["']/gm;
  for (const match of build.matchAll(dependencyPattern)) {
    const [, configuration, group, name, version] = match;
    items.push(component({
      name: `${group}:${name}`,
      version,
      purl: `pkg:maven/${purlPart(group)}/${purlPart(name)}@${purlPart(version)}`,
      source,
      scope: configuration === "implementation" || configuration === "api" ? "required" : "optional",
    }));
  }
  if (/implementation\s+files\(aiKitAar\)/.test(build)) {
    items.push(component({
      name: "iflytek-aikit",
      version: "previous-model-contract",
      purl: "pkg:generic/iflytek/aikit@previous-model-contract",
      source,
      externalReferences: [{ type: "distribution", url: "https://www.iflytek.cn/" }],
    }));
  }
  if (/sherpaOnnxRoot/.test(build)) {
    items.push(component({
      name: "sherpa-onnx-runtime-assets",
      version: "managed-build-input",
      purl: "pkg:generic/sherpa-onnx/runtime-assets@managed-build-input",
      source,
    }));
  }
  return items;
}

function denoComponents(root) {
  const source = "deno.lock";
  const lock = readJson(path.join(root, source));
  const items = [];
  for (const [specifier, version] of Object.entries(lock.specifiers ?? {})) {
    if (!specifier.startsWith("jsr:")) continue;
    const requested = specifier.slice("jsr:".length);
    const versionSeparator = requested.lastIndexOf("@");
    const name = versionSeparator > 0 ? requested.slice(0, versionSeparator) : requested;
    const resolved = Object.keys(lock.jsr ?? {}).find((key) => key.startsWith(`${name}@`));
    const resolvedVersion = resolved?.slice(name.length + 1) ?? String(version);
    items.push(component({
      name,
      version: resolvedVersion,
      purl: `pkg:generic/jsr/${purlPart(name)}@${purlPart(resolvedVersion)}`,
      source,
      identity: specifier,
    }));
  }
  const remoteUrls = Object.keys(lock.remote ?? {}).sort();
  if (remoteUrls.length) {
    items.push(component({
      name: "deno-remote-modules",
      version: `lock-v${lock.version ?? "unknown"}`,
      purl: `pkg:generic/deno/remote-modules@${purlPart(`lock-v${lock.version ?? "unknown"}`)}`,
      source,
      externalReferences: remoteUrls.map((url) => ({ type: "distribution", url })),
    }));
  }
  for (const [name, version] of Object.entries(lock.npm ?? {})) {
    items.push(component({
      name,
      version: String(version),
      purl: `pkg:npm/${purlPart(name)}@${purlPart(String(version))}`,
      source,
    }));
  }
  return items;
}

export function buildV9Sbom(root) {
  const rootComponent = {
    type: "application",
    name: "dingdang-ai-operations-glasses-v9",
    version: "9.0.0",
    "bom-ref": "urn:v9:application:dingdang-ai-operations-glasses-v9",
    purl: "pkg:generic/com.codex.air3nativecamera/dingdang-ai-operations-glasses-v9@9.0.0",
  };
  const components = [
    component({
      type: "application",
      name: "v9-ops-gateway",
      version: "9.0.0",
      purl: "pkg:generic/com.codex/v9-ops-gateway@9.0.0",
      source: "v9-ops-gateway/requirements.txt",
    }),
    component({
      type: "application",
      name: "dingdang-ops-management-web",
      version: "0.1.0",
      purl: "pkg:generic/com.codex/dingdang-ops-management-web@0.1.0",
      source: "ops-management-web/package-lock.json",
    }),
    ...npmComponents(root, "package-lock.json"),
    ...npmComponents(root, "ops-management-web/package-lock.json"),
    ...pythonComponents(root),
    ...androidComponents(root),
    ...denoComponents(root),
  ];
  const sorted = components.sort((left, right) => (
    `${left.purl}\n${left["bom-ref"]}`.localeCompare(`${right.purl}\n${right["bom-ref"]}`)
  ));
  const serialNumber = `urn:uuid:${uuidFromDigest(sorted.map((item) => `${item.purl}\n${item.version}`).join("\n"))}`;
  return {
    bomFormat: "CycloneDX",
    specVersion: "1.5",
    serialNumber,
    version: 1,
    metadata: {
      component: rootComponent,
      properties: [
        { name: "v9:model-contract", value: "qwen3-vl-plus|fun-asr-realtime|previous-iflytek-aikit|s1aa729d0" },
        { name: "v9:secrets-policy", value: "provider-keys-server-side-only" },
      ],
    },
    components: sorted,
    dependencies: [{ ref: rootComponent["bom-ref"], dependsOn: sorted.map((item) => item["bom-ref"]) }],
  };
}

export function writeV9Sbom(root, destination) {
  const sbom = buildV9Sbom(root);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, `${JSON.stringify(sbom, null, 2)}\n`, "utf8");
  return sbom;
}
