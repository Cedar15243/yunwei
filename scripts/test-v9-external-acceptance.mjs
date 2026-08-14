import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

import {
  EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS,
  MARKET_GA_GATE_IDS,
  PRODUCTION_DEPLOYMENT_GATE_IDS,
  auditExternalAcceptance,
  createExternalAcceptanceSigningPayload,
} from "./v9-external-acceptance.mjs";

const RELEASE = {
  applicationId: "com.codex.air3nativecamera.dingdangexpert.v9",
  versionCode: 900000,
  versionName: "9.0.0",
  apkSha256: "A".repeat(64),
  deliveryZipSha256: "B".repeat(64),
  generatedAt: "2026-08-08T00:00:00.000Z",
};
const NOW = new Date("2026-08-08T12:00:00.000Z");
const SIGNED_AT = "2026-08-08T06:11:00.000Z";

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
const APPROVERS = [RELEASE_APPROVER, SECURITY_APPROVER];

function sha256(buffer) {
  return crypto.createHash("sha256").update(buffer).digest("hex").toUpperCase();
}

function writeTrustStore(root) {
  const trustStore = {
    schemaVersion: 1,
    keys: APPROVERS.map((item) => ({
      keyId: item.keyId,
      algorithm: "ed25519",
      role: item.role,
      status: "active",
      publicKeyPem: item.publicKeyPem,
    })),
  };
  const payload = Buffer.from(`${JSON.stringify(trustStore, null, 2)}\n`, "utf8");
  fs.writeFileSync(path.join(root, "trusted-approvers.json"), payload);
  return sha256(payload);
}

function writeEvidence(root, fileId, value) {
  const relativePath = `files/${fileId}.json`;
  const absolutePath = path.join(root, relativePath);
  const payload = Buffer.from(JSON.stringify(value), "utf8");
  fs.mkdirSync(path.dirname(absolutePath), { recursive: true });
  fs.writeFileSync(absolutePath, payload);
  return {
    path: relativePath,
    sha256: sha256(payload),
    bytes: payload.length,
  };
}

function writeAttestation(root, gateId, completedAt) {
  return writeEvidence(root, `${gateId}.attestation`, {
    schemaVersion: 1,
    gateId,
    release: RELEASE,
    environment: "production",
    executedAt: completedAt,
    runner: "controlled-acceptance-runner",
    result: "passed",
    checks: EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS[gateId].map((id) => ({
      id,
      status: "passed",
    })),
  });
}

function signManifest(manifest, signers) {
  const payload = createExternalAcceptanceSigningPayload(manifest);
  return {
    ...manifest,
    signatures: signers.map((signer) => ({
      keyId: signer.keyId,
      algorithm: "ed25519",
      signedAt: SIGNED_AT,
      signatureBase64: crypto.sign(null, payload, signer.privateKey).toString("base64"),
    })),
  };
}

function writeManifest(root, gateIds, overrides = {}, signers = []) {
  const gates = {};
  for (const gateId of gateIds) {
    const completedAt = "2026-08-08T06:00:00.000Z";
    const attestation = writeAttestation(root, gateId, completedAt);
    gates[gateId] = {
      status: "passed",
      environment: "production",
      completedAt,
      approver: "release-owner@example.com",
      attestation,
      evidence: [attestation],
    };
  }
  const manifest = signManifest({
    schemaVersion: 1,
    generatedAt: "2026-08-08T06:10:00.000Z",
    release: RELEASE,
    gates,
    ...overrides,
  }, signers);
  fs.writeFileSync(
    path.join(root, "manifest.json"),
    `${JSON.stringify(manifest, null, 2)}\n`,
    "utf8",
  );
  return manifest;
}

function mutateAttestation(root, manifest, gateId, mutate, signers = APPROVERS) {
  const gate = manifest.gates[gateId];
  const absolutePath = path.join(root, gate.attestation.path);
  const attestation = JSON.parse(fs.readFileSync(absolutePath, "utf8"));
  mutate(attestation);
  const payload = Buffer.from(JSON.stringify(attestation), "utf8");
  fs.writeFileSync(absolutePath, payload);
  const descriptor = {
    path: gate.attestation.path,
    sha256: sha256(payload),
    bytes: payload.length,
  };
  gate.attestation = descriptor;
  gate.evidence[0] = descriptor;
  const signed = signManifest({ ...manifest, signatures: undefined }, signers);
  fs.writeFileSync(path.join(root, "manifest.json"), `${JSON.stringify(signed, null, 2)}\n`, "utf8");
  return signed;
}

function audit(root, trustStoreSha256) {
  return auditExternalAcceptance({
    root,
    manifestPath: "manifest.json",
    trustStorePath: "trusted-approvers.json",
    expectedTrustStoreSha256: trustStoreSha256,
    expectedRelease: RELEASE,
    now: NOW,
  });
}

function reset(root) {
  fs.rmSync(root, { recursive: true, force: true });
  fs.mkdirSync(root, { recursive: true });
  return writeTrustStore(root);
}

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "v9-external-acceptance-"));
try {
  const missing = auditExternalAcceptance({
    root: temporary,
    manifestPath: "manifest.json",
    expectedRelease: RELEASE,
    now: NOW,
  });
  assert.equal(missing.status, "pending_external_validation");
  assert.equal(missing.productionDeploymentStatus, "pending_external_validation");
  assert.equal(missing.marketGaStatus, "pending_external_validation");

  let trustStoreSha256 = writeTrustStore(temporary);
  writeManifest(temporary, MARKET_GA_GATE_IDS);
  const payloadCommand = spawnSync(process.execPath, [
    path.join(import.meta.dirname, "v9-external-acceptance.mjs"),
    temporary,
    "manifest.json",
    "--print-signing-payload",
  ]);
  assert.equal(payloadCommand.status, 0, payloadCommand.stderr?.toString() || "");
  assert.deepEqual(
    payloadCommand.stdout,
    createExternalAcceptanceSigningPayload(JSON.parse(
      fs.readFileSync(path.join(temporary, "manifest.json"), "utf8"),
    )),
  );
  const unsigned = audit(temporary, trustStoreSha256);
  assert.equal(unsigned.status, "pending_external_validation");
  assert.equal(unsigned.approval.status, "pending_external_approval");
  assert.equal(unsigned.productionDeploymentStatus, "pending_external_validation");

  writeManifest(temporary, PRODUCTION_DEPLOYMENT_GATE_IDS, {}, [RELEASE_APPROVER]);
  const production = audit(temporary, trustStoreSha256);
  assert.equal(production.status, "production_deployment_proven");
  assert.equal(production.approval.productionDeploymentStatus, "proven");
  assert.equal(production.approval.marketGaStatus, "pending_external_validation");
  assert.equal(production.productionDeploymentStatus, "proven");
  assert.equal(production.marketGaStatus, "pending_external_validation");
  assert.deepEqual(production.missingMarketGaGates.sort(),
    MARKET_GA_GATE_IDS.filter((id) => !PRODUCTION_DEPLOYMENT_GATE_IDS.includes(id)).sort());

  writeManifest(temporary, MARKET_GA_GATE_IDS, {}, APPROVERS);
  const market = audit(temporary, trustStoreSha256);
  assert.equal(market.status, "market_ga_proven");
  assert.equal(market.approval.marketGaStatus, "proven");
  assert.deepEqual(market.approval.validRoles.sort(), ["release_manager", "security_approver"]);
  assert.equal(market.productionDeploymentStatus, "proven");
  assert.equal(market.marketGaStatus, "proven");
  assert.deepEqual(market.issues, []);

  const forged = JSON.parse(fs.readFileSync(path.join(temporary, "manifest.json"), "utf8"));
  forged.signatures[0].signatureBase64 = Buffer.alloc(64, 7).toString("base64");
  fs.writeFileSync(path.join(temporary, "manifest.json"), `${JSON.stringify(forged, null, 2)}\n`, "utf8");
  const forgedResult = audit(temporary, trustStoreSha256);
  assert.equal(forgedResult.status, "invalid_external_evidence");
  assert.ok(forgedResult.issues.some((item) => item.code === "approval_signature_invalid"));

  writeManifest(temporary, MARKET_GA_GATE_IDS, {}, APPROVERS);
  const wrongTrustPin = audit(temporary, "C".repeat(64));
  assert.equal(wrongTrustPin.status, "invalid_external_evidence");
  assert.ok(wrongTrustPin.issues.some((item) => item.code === "trust_store_hash_mismatch"));

  const tamperedPath = path.join(temporary, "files", `${MARKET_GA_GATE_IDS[0]}.attestation.json`);
  const tamperedPayload = fs.readFileSync(tamperedPath);
  tamperedPayload[0] = tamperedPayload[0] === 0x7b ? 0x5b : 0x7b;
  fs.writeFileSync(tamperedPath, tamperedPayload);
  const tampered = audit(temporary, trustStoreSha256);
  assert.equal(tampered.status, "invalid_external_evidence");
  assert.equal(tampered.marketGaStatus, "invalid_external_evidence");
  assert.ok(tampered.issues.some((item) => item.code === "evidence_hash_mismatch"));

  trustStoreSha256 = reset(temporary);
  writeManifest(temporary, MARKET_GA_GATE_IDS, {
    release: { ...RELEASE, apkSha256: "C".repeat(64) },
  }, APPROVERS);
  const mismatchedRelease = audit(temporary, trustStoreSha256);
  assert.equal(mismatchedRelease.status, "invalid_external_evidence");
  assert.ok(mismatchedRelease.issues.some((item) => item.code === "release_binding_mismatch"));

  trustStoreSha256 = reset(temporary);
  const stale = writeManifest(temporary, MARKET_GA_GATE_IDS, {}, APPROVERS);
  stale.gates.production_supabase.completedAt = "2026-08-07T23:59:59.000Z";
  const staleSigned = signManifest({ ...stale, signatures: undefined }, APPROVERS);
  fs.writeFileSync(path.join(temporary, "manifest.json"), `${JSON.stringify(staleSigned, null, 2)}\n`, "utf8");
  const staleResult = audit(temporary, trustStoreSha256);
  assert.equal(staleResult.status, "invalid_external_evidence");
  assert.ok(staleResult.issues.some((item) => item.code === "gate_predates_release"));

  trustStoreSha256 = reset(temporary);
  const traversal = writeManifest(temporary, MARKET_GA_GATE_IDS, {}, APPROVERS);
  traversal.gates.production_supabase.evidence[0].path = "../outside.json";
  const traversalSigned = signManifest({ ...traversal, signatures: undefined }, APPROVERS);
  fs.writeFileSync(path.join(temporary, "manifest.json"), `${JSON.stringify(traversalSigned, null, 2)}\n`, "utf8");
  const traversalResult = audit(temporary, trustStoreSha256);
  assert.equal(traversalResult.status, "invalid_external_evidence");
  assert.ok(traversalResult.issues.some((item) => item.code === "evidence_path_invalid"));

  trustStoreSha256 = reset(temporary);
  let semantic = writeManifest(temporary, MARKET_GA_GATE_IDS, {}, APPROVERS);
  semantic = mutateAttestation(temporary, semantic, "production_supabase", (attestation) => {
    attestation.checks.pop();
  });
  const missingCheck = audit(temporary, trustStoreSha256);
  assert.equal(missingCheck.status, "invalid_external_evidence");
  assert.ok(missingCheck.issues.some((item) => item.code === "attestation_required_check_missing"));

  trustStoreSha256 = reset(temporary);
  semantic = writeManifest(temporary, MARKET_GA_GATE_IDS, {}, APPROVERS);
  semantic = mutateAttestation(temporary, semantic, "mvs_writeback", (attestation) => {
    attestation.checks[0].status = "failed";
  });
  const failedCheck = audit(temporary, trustStoreSha256);
  assert.equal(failedCheck.status, "invalid_external_evidence");
  assert.ok(failedCheck.issues.some((item) => item.code === "attestation_check_failed"));

  trustStoreSha256 = reset(temporary);
  semantic = writeManifest(temporary, MARKET_GA_GATE_IDS, {}, APPROVERS);
  semantic = mutateAttestation(temporary, semantic, "expert_collaboration", (attestation) => {
    attestation.gateId = "management_cloud";
  });
  const wrongGate = audit(temporary, trustStoreSha256);
  assert.equal(wrongGate.status, "invalid_external_evidence");
  assert.ok(wrongGate.issues.some((item) => item.code === "attestation_gate_mismatch"));
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}

console.log("V9 external acceptance tests passed.");
