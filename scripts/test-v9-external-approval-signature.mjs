import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

import {
  initializeExternalAcceptanceWorkspace,
} from "./init-v9-external-acceptance.mjs";
import {
  ATTACH_EXTERNAL_APPROVAL_SIGNATURE_CONFIRMATION,
  attachExternalApprovalSignature,
} from "./attach-v9-external-approval-signature.mjs";
import {
  auditExternalAcceptance,
  createExternalAcceptanceSigningPayload,
} from "./v9-external-acceptance.mjs";

const RELEASE_MANIFEST = {
  applicationId: "com.codex.air3nativecamera.dingdangexpert.v9",
  versionCode: 900000,
  versionName: "9.0.0",
  sha256: "A".repeat(64),
  builtAt: "2026-08-08T07:07:28.249Z",
};
const DELIVERY_ZIP_SHA256 = "B".repeat(64);
const GENERATED_AT = new Date("2026-08-08T12:00:00.000Z");
const NOW = new Date("2026-08-08T13:00:00.000Z");

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function sha256Buffer(buffer) {
  return crypto.createHash("sha256").update(buffer).digest("hex").toUpperCase();
}

function sha256File(filePath) {
  return sha256Buffer(fs.readFileSync(filePath));
}

function createRepositoryFixture(root) {
  writeJson(
    path.join(root, "output/v9.0.0-formal-release-current/release-manifest.json"),
    RELEASE_MANIFEST,
  );
  fs.writeFileSync(
    path.join(root, "output/DingdangAI-V9-9.0.0-formal-delivery.zip.sha256"),
    `${DELIVERY_ZIP_SHA256}  DingdangAI-V9-9.0.0-formal-delivery.zip\n`,
    "utf8",
  );
}

function approver(keyId, role) {
  const pair = crypto.generateKeyPairSync("ed25519");
  return {
    keyId,
    role,
    privateKey: pair.privateKey,
    publicKeyPem: pair.publicKey.export({ type: "spki", format: "pem" }),
  };
}

const RELEASE_APPROVER = approver("release-key-1", "release_manager");
const SECURITY_APPROVER = approver("security-key-1", "security_approver");

function createWorkspace(root, name) {
  const workspace = path.join(root, name);
  initializeExternalAcceptanceWorkspace({
    repositoryRoot: root,
    outputDirectory: workspace,
    generatedAt: GENERATED_AT,
  });
  const trustStorePath = path.join(workspace, "trusted-approvers.json");
  writeJson(trustStorePath, {
    schemaVersion: 1,
    keys: [RELEASE_APPROVER, SECURITY_APPROVER].map((item) => ({
      keyId: item.keyId,
      algorithm: "ed25519",
      role: item.role,
      status: "active",
      publicKeyPem: item.publicKeyPem,
    })),
  });
  return {
    workspace,
    manifestPath: path.join(workspace, "manifest.json"),
    trustStorePath,
    trustStoreSha256: sha256File(trustStorePath),
  };
}

function writeSignatureFile(workspace, fileName, manifestPath, privateKey, value) {
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  const signature = value ?? crypto.sign(
    null,
    createExternalAcceptanceSigningPayload(manifest),
    privateKey,
  ).toString("base64");
  const relativePath = `signatures/${fileName}`;
  fs.mkdirSync(path.join(workspace, "signatures"), { recursive: true });
  fs.writeFileSync(path.join(workspace, relativePath), `${signature}\n`, "utf8");
  return relativePath;
}

function attach(root, fixture, signer, signedAt, signaturePath, overrides = {}) {
  return attachExternalApprovalSignature({
    repositoryRoot: root,
    workspaceDirectory: fixture.workspace,
    keyId: signer.keyId,
    signedAt,
    signaturePath,
    trustStorePath: "trusted-approvers.json",
    expectedManifestSha256: sha256File(fixture.manifestPath),
    expectedTrustStoreSha256: fixture.trustStoreSha256,
    confirmation: ATTACH_EXTERNAL_APPROVAL_SIGNATURE_CONFIRMATION,
    now: NOW,
    ...overrides,
  });
}

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "v9-external-signature-"));
try {
  createRepositoryFixture(temporary);

  const success = createWorkspace(temporary, "success");
  const releaseSignaturePath = writeSignatureFile(
    success.workspace,
    "release-manager.sig",
    success.manifestPath,
    RELEASE_APPROVER.privateKey,
  );
  const first = attach(
    temporary,
    success,
    RELEASE_APPROVER,
    "2026-08-08T12:05:00.000Z",
    releaseSignaturePath,
  );
  assert.equal(first.keyId, RELEASE_APPROVER.keyId);
  assert.equal(first.role, "release_manager");
  assert.equal(first.validSignatureCount, 1);
  assert.equal(first.manifestSha256, sha256File(success.manifestPath));

  const securitySignaturePath = writeSignatureFile(
    success.workspace,
    "security-approver.sig",
    success.manifestPath,
    SECURITY_APPROVER.privateKey,
  );
  const second = attach(
    temporary,
    success,
    SECURITY_APPROVER,
    "2026-08-08T12:06:00.000Z",
    securitySignaturePath,
  );
  assert.equal(second.role, "security_approver");
  assert.equal(second.validSignatureCount, 2);
  assert.deepEqual(second.validRoles, ["release_manager", "security_approver"]);

  const signedManifest = JSON.parse(fs.readFileSync(success.manifestPath, "utf8"));
  assert.equal(signedManifest.signatures.length, 2);
  assert.deepEqual(
    signedManifest.signatures.map((item) => item.keyId),
    [RELEASE_APPROVER.keyId, SECURITY_APPROVER.keyId],
  );
  const audit = auditExternalAcceptance({
    root: temporary,
    manifestPath: path.relative(temporary, success.manifestPath),
    trustStorePath: path.relative(temporary, success.trustStorePath),
    expectedTrustStoreSha256: success.trustStoreSha256,
    expectedRelease: signedManifest.release,
    now: NOW,
  });
  assert.equal(audit.approval.status, "market_ga_approved");
  assert.deepEqual(audit.approval.validRoles, ["release_manager", "security_approver"]);
  assert.deepEqual(audit.issues, []);

  const duplicateBefore = fs.readFileSync(success.manifestPath);
  assert.throws(
    () => attach(
      temporary,
      success,
      RELEASE_APPROVER,
      "2026-08-08T12:07:00.000Z",
      releaseSignaturePath,
    ),
    /already attached/i,
  );
  assert.deepEqual(fs.readFileSync(success.manifestPath), duplicateBefore);

  const forged = createWorkspace(temporary, "forged");
  const forgedSignaturePath = writeSignatureFile(
    forged.workspace,
    "forged.sig",
    forged.manifestPath,
    RELEASE_APPROVER.privateKey,
    Buffer.alloc(64, 7).toString("base64"),
  );
  const forgedBefore = fs.readFileSync(forged.manifestPath);
  assert.throws(
    () => attach(
      temporary,
      forged,
      RELEASE_APPROVER,
      "2026-08-08T12:05:00.000Z",
      forgedSignaturePath,
    ),
    /approval_signature_invalid/i,
  );
  assert.deepEqual(fs.readFileSync(forged.manifestPath), forgedBefore);

  const wrongPin = createWorkspace(temporary, "wrong-pin");
  const wrongPinSignaturePath = writeSignatureFile(
    wrongPin.workspace,
    "release.sig",
    wrongPin.manifestPath,
    RELEASE_APPROVER.privateKey,
  );
  const wrongPinBefore = fs.readFileSync(wrongPin.manifestPath);
  assert.throws(
    () => attach(
      temporary,
      wrongPin,
      RELEASE_APPROVER,
      "2026-08-08T12:05:00.000Z",
      wrongPinSignaturePath,
      { expectedTrustStoreSha256: "C".repeat(64) },
    ),
    /trust store SHA-256 mismatch/i,
  );
  assert.deepEqual(fs.readFileSync(wrongPin.manifestPath), wrongPinBefore);

  const stale = createWorkspace(temporary, "stale");
  const staleSignaturePath = writeSignatureFile(
    stale.workspace,
    "release.sig",
    stale.manifestPath,
    RELEASE_APPROVER.privateKey,
  );
  const staleBefore = fs.readFileSync(stale.manifestPath);
  assert.throws(
    () => attach(
      temporary,
      stale,
      RELEASE_APPROVER,
      "2026-08-08T12:05:00.000Z",
      staleSignaturePath,
      { expectedManifestSha256: "D".repeat(64) },
    ),
    /manifest SHA-256 mismatch/i,
  );
  assert.deepEqual(fs.readFileSync(stale.manifestPath), staleBefore);

  const staleTime = createWorkspace(temporary, "stale-time");
  const staleTimeSignaturePath = writeSignatureFile(
    staleTime.workspace,
    "release.sig",
    staleTime.manifestPath,
    RELEASE_APPROVER.privateKey,
  );
  const staleTimeBefore = fs.readFileSync(staleTime.manifestPath);
  assert.throws(
    () => attach(
      temporary,
      staleTime,
      RELEASE_APPROVER,
      "2026-08-08T11:59:59.000Z",
      staleTimeSignaturePath,
    ),
    /approval_signature_time_invalid/i,
  );
  assert.deepEqual(fs.readFileSync(staleTime.manifestPath), staleTimeBefore);

  const traversal = createWorkspace(temporary, "traversal");
  const outsideSignature = path.join(temporary, "outside.sig");
  fs.writeFileSync(outsideSignature, `${Buffer.alloc(64).toString("base64")}\n`, "utf8");
  const traversalBefore = fs.readFileSync(traversal.manifestPath);
  assert.throws(
    () => attach(
      temporary,
      traversal,
      RELEASE_APPROVER,
      "2026-08-08T12:05:00.000Z",
      "../outside.sig",
    ),
    /signature path/i,
  );
  assert.deepEqual(fs.readFileSync(traversal.manifestPath), traversalBefore);

  const cli = createWorkspace(temporary, "cli");
  const cliSignaturePath = writeSignatureFile(
    cli.workspace,
    "release.sig",
    cli.manifestPath,
    RELEASE_APPROVER.privateKey,
  );
  const cliCommand = spawnSync(process.execPath, [
    path.join(import.meta.dirname, "attach-v9-external-approval-signature.mjs"),
    temporary,
    cli.workspace,
    RELEASE_APPROVER.keyId,
    "2026-08-08T12:05:00.000Z",
    cliSignaturePath,
    "--trust-store",
    "trusted-approvers.json",
    "--expected-manifest-sha256",
    sha256File(cli.manifestPath),
    "--expected-trust-store-sha256",
    cli.trustStoreSha256,
    "--confirm",
    ATTACH_EXTERNAL_APPROVAL_SIGNATURE_CONFIRMATION,
  ], { encoding: "utf8" });
  assert.equal(cliCommand.status, 0, cliCommand.stderr);
  const cliResult = JSON.parse(cliCommand.stdout);
  assert.equal(cliResult.role, "release_manager");
  assert.equal(JSON.parse(fs.readFileSync(cli.manifestPath, "utf8")).signatures.length, 1);

  const sourceRoot = path.join(import.meta.dirname, "..");
  const packageJson = JSON.parse(fs.readFileSync(path.join(sourceRoot, "package.json"), "utf8"));
  assert.equal(
    packageJson.scripts["attach:v9-external-approval-signature"],
    "node scripts/attach-v9-external-approval-signature.mjs",
  );
  assert.match(
    packageJson.scripts["test:v9-external-acceptance"],
    /test-v9-external-approval-signature\.mjs/,
  );
  const packageScript = fs.readFileSync(
    path.join(sourceRoot, "scripts/package-v9-formal-delivery.ps1"),
    "utf8",
  );
  assert.match(packageScript, /security\\attach-v9-external-approval-signature\.mjs/);
  const deliveryValidator = fs.readFileSync(
    path.join(sourceRoot, "scripts/validate-v9-formal-delivery.mjs"),
    "utf8",
  );
  assert.match(deliveryValidator, /security\/attach-v9-external-approval-signature\.mjs/);
  assert.match(deliveryValidator, /external approval signature attacher/i);
  const operatorContract = fs.readFileSync(
    path.join(sourceRoot, "docs/operations/v9-external-acceptance-evidence.md"),
    "utf8",
  );
  assert.match(operatorContract, /attach:v9-external-approval-signature/);
  assert.match(operatorContract, /security\/attach-v9-external-approval-signature\.mjs/);
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}

console.log("V9 external approval signature attachment tests passed.");
