import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS,
  MARKET_GA_GATE_IDS,
} from "./v9-external-acceptance.mjs";

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8").replace(/^\uFEFF/, ""));
}

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function latestFile(paths) {
  return paths
    .map((candidate) => ({
      candidate,
      stat: fs.statSync(candidate, { throwIfNoEntry: false }),
    }))
    .filter(({ stat }) => stat?.isFile())
    .sort((left, right) => right.stat.mtimeMs - left.stat.mtimeMs)[0]?.candidate;
}

export function readExternalAcceptanceReleaseBinding(repositoryRoot, deliverySidecarPath) {
  // A formal delivery is immutable once external acceptance starts. Newer local
  // candidate builds must never silently rebind its evidence workspace.
  const deliveryManifestPath = path.join(
    repositoryRoot,
    "output/v9.0.0-formal-delivery/android/release-manifest.json",
  );
  const releaseManifestPath = fs.statSync(
    deliveryManifestPath,
    { throwIfNoEntry: false },
  )?.isFile()
    ? deliveryManifestPath
    : latestFile([
      path.join(repositoryRoot, "output/v9.0.0-formal-release-rerun/release-manifest.json"),
      path.join(repositoryRoot, "output/v9.0.0-formal-release/release-manifest.json"),
      path.join(repositoryRoot, "output/v9.0.0-formal-release-current/release-manifest.json"),
      path.join(repositoryRoot, "android/release-manifest.json"),
    ]);
  if (!releaseManifestPath) {
    throw new Error(`V9 release manifest is missing under ${repositoryRoot}`);
  }
  const resolvedSidecarPath = deliverySidecarPath
    ? path.resolve(repositoryRoot, deliverySidecarPath)
    : path.join(repositoryRoot, "output/DingdangAI-V9-9.0.0-formal-delivery.zip.sha256");
  const releaseManifest = readJson(releaseManifestPath);
  const deliveryZipSha256 = fs.readFileSync(
    resolvedSidecarPath,
    "utf8",
  ).trim().split(/\s+/)[0];
  if (!/^[A-Fa-f0-9]{64}$/.test(deliveryZipSha256)) {
    throw new Error(`Delivery ZIP SHA-256 is invalid: ${resolvedSidecarPath}`);
  }
  return {
    applicationId: releaseManifest.applicationId,
    versionCode: releaseManifest.versionCode,
    versionName: releaseManifest.versionName,
    apkSha256: releaseManifest.sha256,
    deliveryZipSha256: deliveryZipSha256.toUpperCase(),
    generatedAt: releaseManifest.builtAt,
  };
}

function assertEmptyWorkspace(outputDirectory) {
  const stat = fs.statSync(outputDirectory, { throwIfNoEntry: false });
  if (!stat) return;
  if (!stat.isDirectory() || fs.readdirSync(outputDirectory).length > 0) {
    throw new Error(`External acceptance workspace is not empty: ${outputDirectory}`);
  }
}

export function initializeExternalAcceptanceWorkspace({
  repositoryRoot,
  outputDirectory,
  generatedAt = new Date(),
  deliverySidecarPath,
}) {
  const absoluteRepositoryRoot = path.resolve(repositoryRoot);
  const absoluteOutputDirectory = path.resolve(absoluteRepositoryRoot, outputDirectory);
  const release = readExternalAcceptanceReleaseBinding(
    absoluteRepositoryRoot,
    deliverySidecarPath,
  );
  const generatedAtIso = generatedAt instanceof Date
    ? generatedAt.toISOString()
    : new Date(generatedAt).toISOString();

  assertEmptyWorkspace(absoluteOutputDirectory);
  fs.mkdirSync(path.join(absoluteOutputDirectory, "templates"), { recursive: true });

  const manifestPath = path.join(absoluteOutputDirectory, "manifest.json");
  writeJson(manifestPath, {
    schemaVersion: 1,
    generatedAt: generatedAtIso,
    release,
    gates: {},
    signatures: [],
  });
  writeJson(
    path.join(absoluteOutputDirectory, "required-checks.json"),
    EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS,
  );
  fs.writeFileSync(path.join(absoluteOutputDirectory, "README.md"), [
    "# V9 外部验收工作区",
    "",
    "该工作区已绑定当前正式 APK 和交付 ZIP。初始化状态必须保持 pending；templates 下的文件只是待执行清单，不是通过证据。",
    "",
    "1. 仅在真实生产环境执行对应 gate，不得用 fixture、mock、静态文案或本地健康端点替代。",
    "2. 将对应模板复制到 files/，填写真实 executedAt、runner、result 和每个检查结果；只有全部通过时才能把 result/check status 改为 passed。",
    "3. 计算 attestation 的 SHA-256 和字节数，再把描述符写入 manifest.json 的 attestation 与 evidence。",
    "4. 本工具不会生成 trusted-approvers.json、签名或私钥；不得生成或保存审批私钥到该目录。信任库只能保存公钥，并由受保护发布系统固定 SHA-256。",
    "5. 使用 security/v9-external-acceptance.mjs（正式包）或 scripts/v9-external-acceptance.mjs（源码仓库）导出规范签名载荷，由离线介质、HSM 或 KMS 完成 Ed25519 签名。",
    "6. 申请市场 GA 前必须运行 --require-market-ga 强制门禁；任何缺项、失败项、错绑或无效签名都会拒绝放行。",
    "",
  ].join("\n"), "utf8");
  for (const gateId of MARKET_GA_GATE_IDS) {
    writeJson(path.join(
      absoluteOutputDirectory,
      "templates",
      `${gateId}-attestation.json`,
    ), {
      schemaVersion: 1,
      gateId,
      release,
      environment: "production",
      executedAt: null,
      runner: "",
      result: "pending",
      checks: EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS[gateId].map((id) => ({
        id,
        status: "pending",
      })),
    });
  }

  return {
    manifestPath,
    outputDirectory: absoluteOutputDirectory,
    gateIds: [...MARKET_GA_GATE_IDS],
  };
}

function parseCliArguments(args) {
  const positional = [];
  let deliverySidecarPath;
  for (let index = 0; index < args.length; index += 1) {
    const value = args[index];
    if (value === "--delivery-sidecar") {
      deliverySidecarPath = args[index + 1];
      if (!deliverySidecarPath) throw new Error("--delivery-sidecar requires a path");
      index += 1;
      continue;
    }
    positional.push(value);
  }
  return { positional, deliverySidecarPath };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { positional, deliverySidecarPath } = parseCliArguments(process.argv.slice(2));
  const repositoryRoot = path.resolve(positional[0] || path.join(import.meta.dirname, ".."));
  const outputDirectory = positional[1] || "evidence/v9-external-acceptance";
  const result = initializeExternalAcceptanceWorkspace({
    repositoryRoot,
    outputDirectory,
    deliverySidecarPath,
  });
  console.log(JSON.stringify(result, null, 2));
}
