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
  RECORD_EXTERNAL_ACCEPTANCE_CONFIRMATION,
  recordExternalAcceptanceAttestation,
} from "./record-v9-external-attestation.mjs";
import {
  EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS,
  auditExternalAcceptance,
} from "./v9-external-acceptance.mjs";

const RELEASE_MANIFEST = {
  applicationId: "com.codex.air3nativecamera.dingdangexpert.v9",
  versionCode: 900000,
  versionName: "9.0.0",
  sha256: "A".repeat(64),
  builtAt: "2026-08-08T07:07:28.249Z",
};
const DELIVERY_ZIP_SHA256 = "B".repeat(64);
const NOW = new Date("2026-08-08T12:00:00.000Z");
const EXECUTED_AT = "2026-08-08T11:00:00.000Z";

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

function createWorkspace(root, name) {
  const workspace = path.join(root, name);
  initializeExternalAcceptanceWorkspace({
    repositoryRoot: root,
    outputDirectory: workspace,
    generatedAt: NOW,
  });
  return workspace;
}

function writePassedAttestation(workspace, gateId, mutate = () => {}) {
  const manifest = JSON.parse(fs.readFileSync(path.join(workspace, "manifest.json"), "utf8"));
  const attestation = {
    schemaVersion: 1,
    gateId,
    release: manifest.release,
    environment: "production",
    executedAt: EXECUTED_AT,
    runner: "controlled-acceptance-runner",
    result: "passed",
    checks: EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS[gateId].map((id) => ({
      id,
      status: "passed",
    })),
  };
  mutate(attestation);
  const relativePath = `files/${gateId}-attestation.json`;
  writeJson(path.join(workspace, relativePath), attestation);
  return relativePath;
}

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "v9-external-record-"));
try {
  createRepositoryFixture(temporary);

  const successWorkspace = createWorkspace(temporary, "success");
  const successManifestPath = path.join(successWorkspace, "manifest.json");
  const attestationPath = writePassedAttestation(successWorkspace, "production_supabase");
  const beforeSha256 = sha256File(successManifestPath);
  const result = recordExternalAcceptanceAttestation({
    repositoryRoot: temporary,
    workspaceDirectory: successWorkspace,
    gateId: "production_supabase",
    attestationPath,
    approver: "approval-ticket-1001",
    expectedManifestSha256: beforeSha256,
    confirmation: RECORD_EXTERNAL_ACCEPTANCE_CONFIRMATION,
    now: NOW,
  });
  assert.equal(result.gateId, "production_supabase");
  assert.equal(result.previousManifestSha256, beforeSha256);
  assert.equal(result.manifestSha256, sha256File(successManifestPath));
  assert.notEqual(result.manifestSha256, beforeSha256);

  const recordedManifest = JSON.parse(fs.readFileSync(successManifestPath, "utf8"));
  assert.deepEqual(recordedManifest.signatures, []);
  assert.equal(recordedManifest.gates.production_supabase.status, "passed");
  assert.equal(recordedManifest.gates.production_supabase.completedAt, EXECUTED_AT);
  assert.equal(recordedManifest.gates.production_supabase.approver, "approval-ticket-1001");
  assert.deepEqual(
    recordedManifest.gates.production_supabase.attestation,
    recordedManifest.gates.production_supabase.evidence[0],
  );
  assert.equal(
    recordedManifest.gates.production_supabase.attestation.sha256,
    sha256File(path.join(successWorkspace, attestationPath)),
  );

  const audit = auditExternalAcceptance({
    root: temporary,
    manifestPath: path.relative(temporary, successManifestPath),
    expectedRelease: recordedManifest.release,
    now: NOW,
  });
  assert.equal(audit.status, "pending_external_validation");
  assert.equal(audit.gates.production_supabase.status, "passed");
  assert.deepEqual(audit.issues, []);

  const duplicateSha256 = sha256File(successManifestPath);
  assert.throws(
    () => recordExternalAcceptanceAttestation({
      repositoryRoot: temporary,
      workspaceDirectory: successWorkspace,
      gateId: "production_supabase",
      attestationPath,
      approver: "approval-ticket-1002",
      expectedManifestSha256: duplicateSha256,
      confirmation: RECORD_EXTERNAL_ACCEPTANCE_CONFIRMATION,
      now: NOW,
    }),
    /already recorded/i,
  );
  assert.equal(sha256File(successManifestPath), duplicateSha256);

  const staleWorkspace = createWorkspace(temporary, "stale");
  const staleManifestPath = path.join(staleWorkspace, "manifest.json");
  const staleAttestationPath = writePassedAttestation(staleWorkspace, "management_cloud");
  const staleBefore = fs.readFileSync(staleManifestPath);
  assert.throws(
    () => recordExternalAcceptanceAttestation({
      repositoryRoot: temporary,
      workspaceDirectory: staleWorkspace,
      gateId: "management_cloud",
      attestationPath: staleAttestationPath,
      approver: "approval-ticket-2001",
      expectedManifestSha256: "C".repeat(64),
      confirmation: RECORD_EXTERNAL_ACCEPTANCE_CONFIRMATION,
      now: NOW,
    }),
    /manifest SHA-256 mismatch/i,
  );
  assert.deepEqual(fs.readFileSync(staleManifestPath), staleBefore);

  const signedWorkspace = createWorkspace(temporary, "signed");
  const signedManifestPath = path.join(signedWorkspace, "manifest.json");
  const signedManifest = JSON.parse(fs.readFileSync(signedManifestPath, "utf8"));
  signedManifest.signatures = [{ keyId: "existing-signature" }];
  writeJson(signedManifestPath, signedManifest);
  const signedAttestationPath = writePassedAttestation(signedWorkspace, "device_activation");
  const signedBefore = fs.readFileSync(signedManifestPath);
  assert.throws(
    () => recordExternalAcceptanceAttestation({
      repositoryRoot: temporary,
      workspaceDirectory: signedWorkspace,
      gateId: "device_activation",
      attestationPath: signedAttestationPath,
      approver: "approval-ticket-3001",
      expectedManifestSha256: sha256Buffer(signedBefore),
      confirmation: RECORD_EXTERNAL_ACCEPTANCE_CONFIRMATION,
      now: NOW,
    }),
    /signed manifest/i,
  );
  assert.deepEqual(fs.readFileSync(signedManifestPath), signedBefore);

  const invalidWorkspace = createWorkspace(temporary, "invalid");
  const invalidManifestPath = path.join(invalidWorkspace, "manifest.json");
  const invalidAttestationPath = writePassedAttestation(
    invalidWorkspace,
    "expert_collaboration",
    (attestation) => attestation.checks.pop(),
  );
  const invalidBefore = fs.readFileSync(invalidManifestPath);
  assert.throws(
    () => recordExternalAcceptanceAttestation({
      repositoryRoot: temporary,
      workspaceDirectory: invalidWorkspace,
      gateId: "expert_collaboration",
      attestationPath: invalidAttestationPath,
      approver: "approval-ticket-4001",
      expectedManifestSha256: sha256Buffer(invalidBefore),
      confirmation: RECORD_EXTERNAL_ACCEPTANCE_CONFIRMATION,
      now: NOW,
    }),
    /attestation_required_check_missing/i,
  );
  assert.deepEqual(fs.readFileSync(invalidManifestPath), invalidBefore);

  const traversalWorkspace = createWorkspace(temporary, "traversal");
  const traversalManifestPath = path.join(traversalWorkspace, "manifest.json");
  const outsideAttestation = path.join(temporary, "outside-attestation.json");
  writeJson(outsideAttestation, { result: "passed" });
  const traversalBefore = fs.readFileSync(traversalManifestPath);
  assert.throws(
    () => recordExternalAcceptanceAttestation({
      repositoryRoot: temporary,
      workspaceDirectory: traversalWorkspace,
      gateId: "mvs_writeback",
      attestationPath: "../outside-attestation.json",
      approver: "approval-ticket-5001",
      expectedManifestSha256: sha256Buffer(traversalBefore),
      confirmation: RECORD_EXTERNAL_ACCEPTANCE_CONFIRMATION,
      now: NOW,
    }),
    /attestation path/i,
  );
  assert.deepEqual(fs.readFileSync(traversalManifestPath), traversalBefore);

  const cliWorkspace = createWorkspace(temporary, "cli");
  const cliManifestPath = path.join(cliWorkspace, "manifest.json");
  const cliAttestationPath = writePassedAttestation(cliWorkspace, "operations_resilience");
  const cliCommand = spawnSync(process.execPath, [
    path.join(import.meta.dirname, "record-v9-external-attestation.mjs"),
    temporary,
    cliWorkspace,
    "operations_resilience",
    cliAttestationPath,
    "--approver",
    "approval-ticket-6001",
    "--expected-manifest-sha256",
    sha256File(cliManifestPath),
    "--confirm",
    RECORD_EXTERNAL_ACCEPTANCE_CONFIRMATION,
  ], { encoding: "utf8" });
  assert.equal(cliCommand.status, 0, cliCommand.stderr);
  const cliResult = JSON.parse(cliCommand.stdout);
  assert.equal(cliResult.gateId, "operations_resilience");
  assert.equal(
    JSON.parse(fs.readFileSync(cliManifestPath, "utf8")).gates.operations_resilience.status,
    "passed",
  );

  const sourceRoot = path.join(import.meta.dirname, "..");
  const packageJson = JSON.parse(fs.readFileSync(path.join(sourceRoot, "package.json"), "utf8"));
  assert.equal(
    packageJson.scripts["record:v9-external-attestation"],
    "node scripts/record-v9-external-attestation.mjs",
  );
  assert.match(
    packageJson.scripts["test:v9-external-acceptance"],
    /test-v9-external-attestation-record\.mjs/,
  );
  const packageScript = fs.readFileSync(
    path.join(sourceRoot, "scripts/package-v9-formal-delivery.ps1"),
    "utf8",
  );
  assert.match(packageScript, /security\\record-v9-external-attestation\.mjs/);
  const deliveryValidator = fs.readFileSync(
    path.join(sourceRoot, "scripts/validate-v9-formal-delivery.mjs"),
    "utf8",
  );
  assert.match(deliveryValidator, /security\/record-v9-external-attestation\.mjs/);
  assert.match(deliveryValidator, /external attestation recorder/i);
  const operatorContract = fs.readFileSync(
    path.join(sourceRoot, "docs/operations/v9-external-acceptance-evidence.md"),
    "utf8",
  );
  assert.match(operatorContract, /record:v9-external-attestation/);
  assert.match(operatorContract, /security\/record-v9-external-attestation\.mjs/);
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}

console.log("V9 external attestation recorder tests passed.");
