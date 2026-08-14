import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

import {
  initializeExternalAcceptanceWorkspace,
} from "./init-v9-external-acceptance.mjs";
import {
  EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS,
  MARKET_GA_GATE_IDS,
  auditExternalAcceptance,
} from "./v9-external-acceptance.mjs";

const RELEASE_MANIFEST = {
  applicationId: "com.codex.air3nativecamera.dingdangexpert.v9",
  versionCode: 900000,
  versionName: "9.0.0",
  sha256: "A".repeat(64),
  builtAt: "2026-08-08T07:07:28.249Z",
};
const NEWER_RELEASE_MANIFEST = {
  ...RELEASE_MANIFEST,
  sha256: "C".repeat(64),
  builtAt: "2026-08-08T08:07:28.249Z",
};
const DELIVERY_ZIP_SHA256 = "B".repeat(64);
const GENERATED_AT = new Date("2026-08-08T12:00:00.000Z");

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function createRepositoryFixture(root) {
  writeJson(
    path.join(root, "output/v9.0.0-formal-release-current/release-manifest.json"),
    RELEASE_MANIFEST,
  );
  writeJson(
    path.join(root, "output/v9.0.0-formal-release/release-manifest.json"),
    NEWER_RELEASE_MANIFEST,
  );
  const oldManifestTime = new Date("2026-08-08T07:10:00.000Z");
  const newManifestTime = new Date("2026-08-08T08:10:00.000Z");
  fs.utimesSync(
    path.join(root, "output/v9.0.0-formal-release-current/release-manifest.json"),
    oldManifestTime,
    oldManifestTime,
  );
  fs.utimesSync(
    path.join(root, "output/v9.0.0-formal-release/release-manifest.json"),
    newManifestTime,
    newManifestTime,
  );
  fs.writeFileSync(
    path.join(root, "output/DingdangAI-V9-9.0.0-formal-delivery.zip.sha256"),
    `${DELIVERY_ZIP_SHA256}  DingdangAI-V9-9.0.0-formal-delivery.zip\n`,
    "utf8",
  );
}

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "v9-external-init-"));
try {
  createRepositoryFixture(temporary);
  const workspace = path.join(temporary, "evidence/v9-external-acceptance");
  const result = initializeExternalAcceptanceWorkspace({
    repositoryRoot: temporary,
    outputDirectory: workspace,
    generatedAt: GENERATED_AT,
  });

  assert.equal(result.manifestPath, path.join(workspace, "manifest.json"));
  assert.deepEqual(result.gateIds, MARKET_GA_GATE_IDS);
  assert.equal(fs.existsSync(path.join(workspace, "trusted-approvers.json")), false);

  const manifest = JSON.parse(fs.readFileSync(result.manifestPath, "utf8"));
  const expectedRelease = {
    applicationId: NEWER_RELEASE_MANIFEST.applicationId,
    versionCode: NEWER_RELEASE_MANIFEST.versionCode,
    versionName: NEWER_RELEASE_MANIFEST.versionName,
    apkSha256: NEWER_RELEASE_MANIFEST.sha256,
    deliveryZipSha256: DELIVERY_ZIP_SHA256,
    generatedAt: NEWER_RELEASE_MANIFEST.builtAt,
  };
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.generatedAt, GENERATED_AT.toISOString());
  assert.deepEqual(manifest.release, expectedRelease);
  assert.deepEqual(manifest.gates, {});
  assert.deepEqual(manifest.signatures, []);
  const readme = fs.readFileSync(path.join(workspace, "README.md"), "utf8");
  assert.match(readme, /pending/i);
  assert.match(readme, /不得生成或保存审批私钥/);
  assert.match(readme, /--require-market-ga/);

  const requiredChecks = JSON.parse(fs.readFileSync(
    path.join(workspace, "required-checks.json"),
    "utf8",
  ));
  assert.deepEqual(requiredChecks, EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS);
  for (const gateId of MARKET_GA_GATE_IDS) {
    const template = JSON.parse(fs.readFileSync(
      path.join(workspace, "templates", `${gateId}-attestation.json`),
      "utf8",
    ));
    assert.equal(template.gateId, gateId);
    assert.deepEqual(template.release, expectedRelease);
    assert.equal(template.environment, "production");
    assert.equal(template.executedAt, null);
    assert.equal(template.runner, "");
    assert.equal(template.result, "pending");
    assert.deepEqual(
      template.checks,
      EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS[gateId].map((id) => ({
        id,
        status: "pending",
      })),
    );
  }

  const audit = auditExternalAcceptance({
    root: temporary,
    manifestPath: path.relative(temporary, result.manifestPath),
    expectedRelease,
    now: GENERATED_AT,
  });
  assert.equal(audit.status, "pending_external_validation");
  assert.equal(audit.releaseBindingStatus, "proven");
  assert.deepEqual(audit.issues, []);
  assert.deepEqual(audit.missingMarketGaGates, MARKET_GA_GATE_IDS);

  const protectedWorkspace = path.join(temporary, "protected-evidence");
  fs.mkdirSync(protectedWorkspace, { recursive: true });
  fs.writeFileSync(path.join(protectedWorkspace, "keep.txt"), "real evidence", "utf8");
  assert.throws(
    () => initializeExternalAcceptanceWorkspace({
      repositoryRoot: temporary,
      outputDirectory: protectedWorkspace,
      generatedAt: GENERATED_AT,
    }),
    /not empty/i,
  );
  assert.equal(
    fs.readFileSync(path.join(protectedWorkspace, "keep.txt"), "utf8"),
    "real evidence",
  );

  const deliveryRoot = path.join(temporary, "formal-delivery");
  writeJson(path.join(deliveryRoot, "android/release-manifest.json"), RELEASE_MANIFEST);
  const externalSidecar = path.join(temporary, "formal-delivery.zip.sha256");
  fs.writeFileSync(
    externalSidecar,
    `${DELIVERY_ZIP_SHA256}  formal-delivery.zip\n`,
    "utf8",
  );
  const deliveryWorkspace = path.join(temporary, "delivery-evidence");
  const command = spawnSync(process.execPath, [
    path.join(import.meta.dirname, "init-v9-external-acceptance.mjs"),
    deliveryRoot,
    deliveryWorkspace,
    "--delivery-sidecar",
    externalSidecar,
  ], { encoding: "utf8" });
  assert.equal(command.status, 0, command.stderr);
  const commandResult = JSON.parse(command.stdout);
  assert.equal(commandResult.outputDirectory, deliveryWorkspace);
  const deliveryManifest = JSON.parse(fs.readFileSync(
    path.join(deliveryWorkspace, "manifest.json"),
    "utf8",
  ));
  assert.deepEqual(deliveryManifest.release, {
    applicationId: RELEASE_MANIFEST.applicationId,
    versionCode: RELEASE_MANIFEST.versionCode,
    versionName: RELEASE_MANIFEST.versionName,
    apkSha256: RELEASE_MANIFEST.sha256,
    deliveryZipSha256: DELIVERY_ZIP_SHA256,
    generatedAt: RELEASE_MANIFEST.builtAt,
  });

  const repositoryAudit = spawnSync(process.execPath, [
    path.join(import.meta.dirname, "v9-external-acceptance.mjs"),
    temporary,
    path.relative(temporary, result.manifestPath),
  ], { encoding: "utf8" });
  assert.equal(repositoryAudit.status, 0, repositoryAudit.stderr);
  const repositoryAuditReport = JSON.parse(repositoryAudit.stdout);
  assert.equal(repositoryAuditReport.releaseBindingStatus, "proven");
  assert.equal(repositoryAuditReport.status, "pending_external_validation");

  const sourceRoot = path.join(import.meta.dirname, "..");
  const packageJson = JSON.parse(fs.readFileSync(path.join(sourceRoot, "package.json"), "utf8"));
  assert.equal(
    packageJson.scripts["init:v9-external-acceptance"],
    "node scripts/init-v9-external-acceptance.mjs",
  );
  const packageScript = fs.readFileSync(
    path.join(sourceRoot, "scripts/package-v9-formal-delivery.ps1"),
    "utf8",
  );
  assert.match(packageScript, /security\\init-v9-external-acceptance\.mjs/);
  const deliveryValidator = fs.readFileSync(
    path.join(sourceRoot, "scripts/validate-v9-formal-delivery.mjs"),
    "utf8",
  );
  assert.match(deliveryValidator, /security\/init-v9-external-acceptance\.mjs/);
  assert.match(deliveryValidator, /external acceptance workspace initializer/i);
  const operatorContract = fs.readFileSync(
    path.join(sourceRoot, "docs/operations/v9-external-acceptance-evidence.md"),
    "utf8",
  );
  assert.match(operatorContract, /init:v9-external-acceptance/);
  assert.match(operatorContract, /security\/init-v9-external-acceptance\.mjs/);

  const invalidRoot = path.join(temporary, "invalid-release");
  createRepositoryFixture(invalidRoot);
  fs.writeFileSync(
    path.join(invalidRoot, "output/DingdangAI-V9-9.0.0-formal-delivery.zip.sha256"),
    "not-a-sha256  DingdangAI-V9-9.0.0-formal-delivery.zip\n",
    "utf8",
  );
  const invalidWorkspace = path.join(temporary, "invalid-evidence");
  assert.throws(
    () => initializeExternalAcceptanceWorkspace({
      repositoryRoot: invalidRoot,
      outputDirectory: invalidWorkspace,
      generatedAt: GENERATED_AT,
    }),
    /delivery ZIP SHA-256/i,
  );
  assert.equal(fs.existsSync(invalidWorkspace), false);
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}

console.log("V9 external acceptance workspace initialization tests passed.");
