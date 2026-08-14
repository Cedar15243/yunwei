import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

import { findCommittedSecretLikeMarkers } from "./audit-dingdang-goal-readiness.mjs";

const repositoryRoot = path.resolve(import.meta.dirname, "..");
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "dingdang-goal-audit-"));

try {
  fs.writeFileSync(
    path.join(temporary, "safe.css"),
    ".task-record-workbench{min-width:0}\n.version-9.0.25-task-end-display-text-audit{}\n",
    "utf8",
  );
  fs.writeFileSync(
    path.join(temporary, "unsafe.txt"),
    `const providerKey = "${"sk-" + "A".repeat(24)}";\n`,
    "utf8",
  );

  assert.deepEqual(
    findCommittedSecretLikeMarkers(["safe.css"], temporary),
    [],
    "task-* markers must not be treated as provider keys",
  );
  assert.deepEqual(
    findCommittedSecretLikeMarkers(["unsafe.txt"], temporary),
    [{ path: "unsafe.txt", line: 1 }],
    "secret findings must identify the location without returning the secret value",
  );

  const audit = spawnSync(process.execPath, ["scripts/audit-dingdang-goal-readiness.mjs"], {
    cwd: repositoryRoot,
    encoding: "utf8",
  });
  assert.equal(audit.status, 0, audit.stderr || audit.stdout);

  const report = JSON.parse(audit.stdout);
  assert.equal(report.scope, "legacy-chat-prototype-baseline");
  assert.match(report.supersededBy, /V9 full product contract/i);
  assert.equal(
    report.currentV9Gate.release.manifestPath,
    "output/v9.0.0-formal-release-current/release-manifest.json",
  );
  assert.equal(report.currentV9Gate.release.apkSha256Matches, true);
  assert.equal(report.currentV9Gate.delivery.status, "proven");
  assert.equal(report.currentV9Gate.delivery.zipSha256Matches, true);
  assert.ok(report.currentV9Gate.delivery.manifestArtifacts >= 200);
  assert.equal(
    report.currentV9Gate.delivery.checksummedFiles,
    report.currentV9Gate.delivery.manifestArtifacts + 1,
  );
  assert.equal(report.currentV9Gate.localCandidate.status, "proven");
  assert.equal(
    report.currentV9Gate.externalAcceptance.status,
    "pending_external_validation",
  );
  assert.equal(
    report.currentV9Gate.externalAcceptance.manifestPath,
    "evidence/v9-external-acceptance/manifest.json",
  );
  assert.ok(report.currentV9Gate.externalAcceptance.requiredMarketGaGates.includes(
    "air3_voice_voiceprint_performance",
  ));
  assert.equal(
    report.currentV9Gate.externalAcceptance.approval.status,
    "pending_external_approval",
  );
  assert.equal(
    report.currentV9Gate.externalAcceptance.approval.trustStorePath,
    "evidence/v9-external-acceptance/trusted-approvers.json",
  );
  assert.equal(
    report.currentV9Gate.productionDeployment.status,
    report.currentV9Gate.externalAcceptance.productionDeploymentStatus,
  );
  assert.equal(report.currentV9Gate.marketGa.status, "pending_external_validation");
  assert.equal(
    report.currentV9Gate.marketGa.status,
    report.currentV9Gate.externalAcceptance.marketGaStatus,
  );
  assert.equal(
    report.currentV9Gate.release.applicationId,
    "com.codex.air3nativecamera.dingdangexpert.v9",
  );
  assert.equal(report.currentV9Gate.release.aiModel, "qwen3-vl-plus");
  assert.equal(report.currentV9Gate.release.asrModel, "fun-asr-realtime");
  assert.equal(report.currentV9Gate.release.voiceprintService, "s1aa729d0");

  const auditSource = fs.readFileSync(
    path.join(repositoryRoot, "scripts", "audit-dingdang-goal-readiness.mjs"),
    "utf8",
  );
  assert.match(auditSource, /V9_EXTERNAL_ACCEPTANCE_TRUST_STORE_SHA256/);

  const finalRunbook = report.items.find((item) => item.id === "final-verification-runbook");
  assert.equal(finalRunbook?.status, "proven");
  assert.match(finalRunbook?.details || "", /currentV9Gate/i);

  const secretsSafety = report.items.find((item) => item.id === "secrets-safety");
  assert.equal(secretsSafety?.status, "proven");
  assert.match(secretsSafety?.details || "", /boundary-aware scan/i);
  assert.ok(secretsSafety?.evidence.includes(
    "output/v9.0.0-formal-release-current/release-manifest.json",
  ));
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}

console.log("Dingdang goal readiness audit tests passed.");
