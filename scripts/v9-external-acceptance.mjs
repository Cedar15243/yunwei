import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const PRODUCTION_DEPLOYMENT_GATE_IDS = Object.freeze([
  "production_supabase",
  "management_cloud",
  "device_activation",
]);

export const MARKET_GA_GATE_IDS = Object.freeze([
  ...PRODUCTION_DEPLOYMENT_GATE_IDS,
  "air3_voice_voiceprint_performance",
  "mvs_writeback",
  "expert_collaboration",
  "operations_resilience",
  "security_assessment",
  "supplier_sdk_approval",
]);

export const EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS = Object.freeze({
  production_supabase: Object.freeze([
    "auth_invitation_email",
    "first_login_password_setup",
    "organization_rls_isolation",
    "project_scope_enforcement",
    "multi_role_authorization",
    "last_super_admin_protection",
    "audit_event_persistence",
  ]),
  management_cloud: Object.freeze([
    "isolated_release_directory",
    "dedicated_container_and_port",
    "tls_certificate_valid",
    "caddy_configuration_valid",
    "management_authentication",
    "expert_health_unchanged",
    "rollback_drill",
  ]),
  device_activation: Object.freeze([
    "device_activation",
    "engineer_project_binding",
    "short_lived_session",
    "session_expiry",
    "credential_revocation",
    "unauthorized_access_denied",
    "mdm_compliance_enforced",
  ]),
  air3_voice_voiceprint_performance: Object.freeze([
    "xiaodingdang_single_turn",
    "voiceprint_direct_command",
    "microphone_mutual_exclusion",
    "camera_video_expert_preemption",
    "no_automatic_listener_resume",
    "owner_acceptance",
    "impostor_rejection",
    "replay_rejection",
    "weak_network_recovery",
    "p95_interaction_latency",
    "twenty_minute_stability",
    "power_budget",
    "thermal_budget",
  ]),
  mvs_writeback: Object.freeze([
    "engineer_task_list",
    "work_order_detail_sop",
    "evidence_upload",
    "attachment_binding",
    "dynamic_form_schema",
    "explicit_confirmation",
    "idempotent_write",
    "process_transition",
    "ai_write_prohibited",
  ]),
  expert_collaboration: Object.freeze([
    "bidirectional_video",
    "bidirectional_audio",
    "microphone_handoff",
    "reconnect_recovery",
    "return_to_original_project_step",
    "existing_expert_site_unchanged",
  ]),
  operations_resilience: Object.freeze([
    "gateway_database_restore",
    "voiceprint_database_restore",
    "evidence_media_restore",
    "rpo_rto_met",
    "disk_full_recovery",
    "monitoring_notification",
    "object_storage_cleanup",
  ]),
  security_assessment: Object.freeze([
    "authorized_dast",
    "third_party_penetration_test",
    "critical_findings_resolved",
    "high_findings_resolved",
    "remediation_retest",
    "dependency_audit_clean",
  ]),
  supplier_sdk_approval: Object.freeze([
    "aikit_usage_approved",
    "voiceprint_service_approved",
    "pem_material_reviewed",
    "data_processing_approved",
    "retention_deletion_approved",
    "production_terms_accepted",
  ]),
});

const SHA256_PATTERN = /^[A-F0-9]{64}$/;
const MAX_CLOCK_SKEW_MS = 5 * 60 * 1000;
const MAX_EVIDENCE_FILES_PER_GATE = 20;
const MAX_ATTESTATION_BYTES = 2 * 1024 * 1024;
const MAX_APPROVAL_SIGNATURES = 10;
const APPROVAL_ROLES = new Set(["release_manager", "security_approver"]);

function issue(code, field, detail = "") {
  return { code, field, detail };
}

function readJson(absolutePath) {
  return JSON.parse(fs.readFileSync(absolutePath, "utf8").replace(/^\uFEFF/, ""));
}

function normalizeSha256(value) {
  return typeof value === "string" ? value.trim().toUpperCase() : "";
}

function canonicalize(value) {
  if (Array.isArray(value)) return value.map((item) => canonicalize(item));
  if (value && typeof value === "object") {
    const result = {};
    for (const key of Object.keys(value).sort()) {
      const item = canonicalize(value[key]);
      if (item !== undefined) result[key] = item;
    }
    return result;
  }
  return value;
}

export function createExternalAcceptanceSigningPayload(manifest) {
  const unsigned = manifest && typeof manifest === "object" && !Array.isArray(manifest)
    ? { ...manifest }
    : {};
  delete unsigned.signatures;
  return Buffer.from(JSON.stringify(canonicalize(unsigned)), "utf8");
}

function validDate(value) {
  const timestamp = typeof value === "string" ? Date.parse(value) : Number.NaN;
  return Number.isFinite(timestamp) ? timestamp : null;
}

function isWithin(base, candidate) {
  const normalizedBase = path.resolve(base).toLowerCase();
  const normalizedCandidate = path.resolve(candidate).toLowerCase();
  return normalizedCandidate.startsWith(`${normalizedBase}${path.sep}`);
}

function releaseBindingIssues(actual, expected) {
  const issues = [];
  const fields = ["applicationId", "versionCode", "versionName"];
  for (const field of fields) {
    if (actual?.[field] !== expected?.[field]) {
      issues.push(issue("release_binding_mismatch", `release.${field}`));
    }
  }
  for (const field of ["apkSha256", "deliveryZipSha256"]) {
    if (normalizeSha256(actual?.[field]) !== normalizeSha256(expected?.[field])) {
      issues.push(issue("release_binding_mismatch", `release.${field}`));
    }
  }
  const actualReleaseTime = validDate(actual?.generatedAt);
  const expectedReleaseTime = validDate(expected?.generatedAt);
  if (actualReleaseTime === null || expectedReleaseTime === null
      || actualReleaseTime !== expectedReleaseTime) {
    issues.push(issue("release_binding_mismatch", "release.generatedAt"));
  }
  return issues;
}

function validateEvidenceDescriptor(manifestDirectory, field, evidence) {
  if (!evidence || typeof evidence !== "object" || Array.isArray(evidence)) {
    return [issue("evidence_invalid", field)];
  }
  const relativePath = typeof evidence.path === "string" ? evidence.path.trim() : "";
  if (!relativePath || path.isAbsolute(relativePath)) {
    return [issue("evidence_path_invalid", `${field}.path`)];
  }
  const absolutePath = path.resolve(manifestDirectory, relativePath);
  if (!isWithin(manifestDirectory, absolutePath)) {
    return [issue("evidence_path_invalid", `${field}.path`)];
  }
  const stat = fs.statSync(absolutePath, { throwIfNoEntry: false });
  if (!stat?.isFile()) {
    return [issue("evidence_file_missing", `${field}.path`)];
  }
  const realPath = fs.realpathSync(absolutePath);
  if (!isWithin(manifestDirectory, realPath)) {
    return [issue("evidence_path_invalid", `${field}.path`)];
  }
  const expectedBytes = Number(evidence.bytes);
  if (!Number.isSafeInteger(expectedBytes) || expectedBytes < 1 || expectedBytes !== stat.size) {
    return [issue("evidence_size_mismatch", `${field}.bytes`)];
  }
  const expectedSha256 = normalizeSha256(evidence.sha256);
  if (!SHA256_PATTERN.test(expectedSha256)) {
    return [issue("evidence_sha256_invalid", `${field}.sha256`)];
  }
  const actualSha256 = crypto.createHash("sha256")
    .update(fs.readFileSync(realPath))
    .digest("hex")
    .toUpperCase();
  return actualSha256 === expectedSha256
    ? []
    : [issue("evidence_hash_mismatch", `${field}.sha256`)];
}

function validateEvidenceFile(manifestDirectory, gateId, evidence, index) {
  return validateEvidenceDescriptor(
    manifestDirectory,
    `gates.${gateId}.evidence[${index}]`,
    evidence,
  );
}

function sameEvidenceDescriptor(left, right) {
  return left?.path === right?.path
    && normalizeSha256(left?.sha256) === normalizeSha256(right?.sha256)
    && Number(left?.bytes) === Number(right?.bytes);
}

function attestationReleaseMatches(attestationRelease, manifestRelease) {
  return ["applicationId", "versionCode", "versionName", "generatedAt"]
    .every((field) => attestationRelease?.[field] === manifestRelease?.[field])
    && normalizeSha256(attestationRelease?.apkSha256)
      === normalizeSha256(manifestRelease?.apkSha256)
    && normalizeSha256(attestationRelease?.deliveryZipSha256)
      === normalizeSha256(manifestRelease?.deliveryZipSha256);
}

function validateGateAttestation(manifestDirectory, gateId, gate, manifestRelease) {
  const field = `gates.${gateId}.attestation`;
  const descriptor = gate?.attestation;
  const issues = validateEvidenceDescriptor(manifestDirectory, field, descriptor);
  if (issues.length > 0) return issues;
  if (!Array.isArray(gate.evidence)
      || !gate.evidence.some((item) => sameEvidenceDescriptor(item, descriptor))) {
    issues.push(issue("attestation_not_in_evidence", field));
    return issues;
  }
  const absolutePath = path.resolve(manifestDirectory, descriptor.path);
  if (fs.statSync(absolutePath).size > MAX_ATTESTATION_BYTES) {
    issues.push(issue("attestation_too_large", field));
    return issues;
  }
  let attestation;
  try {
    attestation = readJson(absolutePath);
  } catch (error) {
    issues.push(issue(
      "attestation_invalid",
      field,
      error instanceof Error ? error.message : "",
    ));
    return issues;
  }
  if (attestation?.schemaVersion !== 1) {
    issues.push(issue("attestation_schema_unsupported", `${field}.schemaVersion`));
  }
  if (attestation?.gateId !== gateId) {
    issues.push(issue("attestation_gate_mismatch", `${field}.gateId`));
  }
  if (!attestationReleaseMatches(attestation?.release, manifestRelease)) {
    issues.push(issue("attestation_release_mismatch", `${field}.release`));
  }
  if (attestation?.environment !== "production") {
    issues.push(issue("attestation_environment_invalid", `${field}.environment`));
  }
  if (validDate(attestation?.executedAt) !== validDate(gate?.completedAt)) {
    issues.push(issue("attestation_time_mismatch", `${field}.executedAt`));
  }
  const runner = typeof attestation?.runner === "string" ? attestation.runner.trim() : "";
  if (!runner || runner.length > 200) {
    issues.push(issue("attestation_runner_invalid", `${field}.runner`));
  }
  if (attestation?.result !== "passed") {
    issues.push(issue("attestation_result_invalid", `${field}.result`));
  }
  const checks = Array.isArray(attestation?.checks) ? attestation.checks : [];
  if (checks.length < 1 || checks.length > 100) {
    issues.push(issue("attestation_checks_invalid", `${field}.checks`));
    return issues;
  }
  const checkStatuses = new Map();
  for (let index = 0; index < checks.length; index += 1) {
    const check = checks[index];
    const checkField = `${field}.checks[${index}]`;
    const checkId = typeof check?.id === "string" ? check.id.trim() : "";
    if (!/^[a-z0-9_]{1,100}$/.test(checkId) || checkStatuses.has(checkId)) {
      issues.push(issue("attestation_check_id_invalid", `${checkField}.id`));
      continue;
    }
    checkStatuses.set(checkId, check.status);
    if (check.status !== "passed") {
      issues.push(issue("attestation_check_failed", `${checkField}.status`, checkId));
    }
  }
  for (const requiredCheck of EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS[gateId] || []) {
    if (!checkStatuses.has(requiredCheck)) {
      issues.push(issue(
        "attestation_required_check_missing",
        `${field}.checks`,
        requiredCheck,
      ));
    }
  }
  return issues;
}

function validateGate(manifestDirectory, gateId, gate, releaseTime, nowTime, manifestRelease) {
  const issues = [];
  if (!gate || typeof gate !== "object" || Array.isArray(gate)) {
    return { status: "pending", evidenceCount: 0, issues };
  }
  if (gate.status !== "passed") {
    issues.push(issue("gate_status_invalid", `gates.${gateId}.status`));
  }
  if (gate.environment !== "production") {
    issues.push(issue("gate_environment_invalid", `gates.${gateId}.environment`));
  }
  const completedAt = validDate(gate.completedAt);
  if (completedAt === null) {
    issues.push(issue("gate_completed_at_invalid", `gates.${gateId}.completedAt`));
  } else {
    if (completedAt < releaseTime) {
      issues.push(issue("gate_predates_release", `gates.${gateId}.completedAt`));
    }
    if (completedAt > nowTime + MAX_CLOCK_SKEW_MS) {
      issues.push(issue("gate_completed_at_future", `gates.${gateId}.completedAt`));
    }
  }
  const approver = typeof gate.approver === "string" ? gate.approver.trim() : "";
  if (!approver || approver.length > 200) {
    issues.push(issue("gate_approver_invalid", `gates.${gateId}.approver`));
  }
  const evidence = Array.isArray(gate.evidence) ? gate.evidence : [];
  if (evidence.length < 1 || evidence.length > MAX_EVIDENCE_FILES_PER_GATE) {
    issues.push(issue("gate_evidence_count_invalid", `gates.${gateId}.evidence`));
  } else {
    for (let index = 0; index < evidence.length; index += 1) {
      issues.push(...validateEvidenceFile(manifestDirectory, gateId, evidence[index], index));
    }
  }
  issues.push(...validateGateAttestation(manifestDirectory, gateId, gate, manifestRelease));
  return {
    status: issues.length === 0 ? "passed" : "invalid",
    evidenceCount: evidence.length,
    issues,
  };
}

function approvalBase(repositoryRoot, trustStorePath) {
  const absoluteTrustStorePath = path.resolve(repositoryRoot, trustStorePath);
  return {
    trustStorePath: path.relative(repositoryRoot, absoluteTrustStorePath).replaceAll("\\", "/"),
    requiredProductionDeploymentRoles: ["release_manager"],
    requiredMarketGaRoles: ["release_manager", "security_approver"],
  };
}

function pendingApproval(base, reasons) {
  return {
    ...base,
    status: "pending_external_approval",
    productionDeploymentStatus: "pending_external_validation",
    marketGaStatus: "pending_external_validation",
    trustStoreSha256: null,
    trustStorePinned: false,
    validSignatureCount: 0,
    validRoles: [],
    pendingReasons: reasons,
    issues: [],
  };
}

function invalidApproval(base, issues, trustStoreSha256 = null) {
  return {
    ...base,
    status: "invalid_external_approval",
    productionDeploymentStatus: "invalid_external_evidence",
    marketGaStatus: "invalid_external_evidence",
    trustStoreSha256,
    trustStorePinned: false,
    validSignatureCount: 0,
    validRoles: [],
    pendingReasons: [],
    issues,
  };
}

function validateApproval({
  repositoryRoot,
  trustStorePath,
  expectedTrustStoreSha256,
  manifest,
  manifestTime,
  nowTime,
}) {
  const base = approvalBase(repositoryRoot, trustStorePath);
  const signatures = Array.isArray(manifest?.signatures) ? manifest.signatures : [];
  if (signatures.length === 0) {
    return pendingApproval(base, ["approval_signatures_missing"]);
  }
  if (signatures.length > MAX_APPROVAL_SIGNATURES) {
    return invalidApproval(base, [issue("approval_signature_count_invalid", "signatures")]);
  }

  const absoluteTrustStorePath = path.resolve(repositoryRoot, trustStorePath);
  if (!fs.statSync(absoluteTrustStorePath, { throwIfNoEntry: false })?.isFile()) {
    return pendingApproval(base, ["trust_store_missing"]);
  }
  const expectedPin = normalizeSha256(expectedTrustStoreSha256);
  if (!SHA256_PATTERN.test(expectedPin)) {
    return pendingApproval(base, ["trust_store_pin_missing"]);
  }
  const trustStoreBuffer = fs.readFileSync(absoluteTrustStorePath);
  const trustStoreSha256 = crypto.createHash("sha256")
    .update(trustStoreBuffer)
    .digest("hex")
    .toUpperCase();
  if (trustStoreSha256 !== expectedPin) {
    return invalidApproval(base, [
      issue("trust_store_hash_mismatch", "expectedTrustStoreSha256"),
    ], trustStoreSha256);
  }

  let trustStore;
  try {
    trustStore = JSON.parse(trustStoreBuffer.toString("utf8").replace(/^\uFEFF/, ""));
  } catch (error) {
    return invalidApproval(base, [
      issue("trust_store_invalid", "trustStore", error instanceof Error ? error.message : ""),
    ], trustStoreSha256);
  }

  const trustIssues = [];
  if (trustStore?.schemaVersion !== 1) {
    trustIssues.push(issue("trust_store_schema_unsupported", "trustStore.schemaVersion"));
  }
  const keys = Array.isArray(trustStore?.keys) ? trustStore.keys : [];
  if (keys.length < 1 || keys.length > 50) {
    trustIssues.push(issue("trust_store_key_count_invalid", "trustStore.keys"));
  }
  const trustedKeys = new Map();
  for (let index = 0; index < keys.length; index += 1) {
    const key = keys[index];
    const field = `trustStore.keys[${index}]`;
    const keyId = typeof key?.keyId === "string" ? key.keyId.trim() : "";
    if (!/^[A-Za-z0-9._-]{1,80}$/.test(keyId) || trustedKeys.has(keyId)) {
      trustIssues.push(issue("trust_store_key_id_invalid", `${field}.keyId`));
      continue;
    }
    if (key.algorithm !== "ed25519") {
      trustIssues.push(issue("trust_store_algorithm_invalid", `${field}.algorithm`));
      continue;
    }
    if (!APPROVAL_ROLES.has(key.role)) {
      trustIssues.push(issue("trust_store_role_invalid", `${field}.role`));
      continue;
    }
    if (key.status !== "active" && key.status !== "revoked") {
      trustIssues.push(issue("trust_store_status_invalid", `${field}.status`));
      continue;
    }
    try {
      const publicKey = crypto.createPublicKey(key.publicKeyPem);
      if (publicKey.asymmetricKeyType !== "ed25519") {
        trustIssues.push(issue("trust_store_public_key_invalid", `${field}.publicKeyPem`));
        continue;
      }
      trustedKeys.set(keyId, { publicKey, role: key.role, status: key.status });
    } catch {
      trustIssues.push(issue("trust_store_public_key_invalid", `${field}.publicKeyPem`));
    }
  }
  if (trustIssues.length > 0) {
    return invalidApproval(base, trustIssues, trustStoreSha256);
  }

  const payload = createExternalAcceptanceSigningPayload(manifest);
  const signatureIssues = [];
  const validKeyIds = new Set();
  const validRoles = new Set();
  for (let index = 0; index < signatures.length; index += 1) {
    const signature = signatures[index];
    const field = `signatures[${index}]`;
    const keyId = typeof signature?.keyId === "string" ? signature.keyId.trim() : "";
    if (!keyId || validKeyIds.has(keyId)) {
      signatureIssues.push(issue("approval_signature_key_invalid", `${field}.keyId`));
      continue;
    }
    if (signature.algorithm !== "ed25519") {
      signatureIssues.push(issue("approval_signature_algorithm_invalid", `${field}.algorithm`));
      continue;
    }
    const trusted = trustedKeys.get(keyId);
    if (!trusted || trusted.status !== "active") {
      signatureIssues.push(issue("approval_signature_untrusted", `${field}.keyId`));
      continue;
    }
    const signedAt = validDate(signature.signedAt);
    if (signedAt === null || signedAt < manifestTime || signedAt > nowTime + MAX_CLOCK_SKEW_MS) {
      signatureIssues.push(issue("approval_signature_time_invalid", `${field}.signedAt`));
      continue;
    }
    const encoded = typeof signature.signatureBase64 === "string"
      ? signature.signatureBase64.trim() : "";
    let decoded;
    try {
      decoded = Buffer.from(encoded, "base64");
    } catch {
      decoded = Buffer.alloc(0);
    }
    if (!encoded || decoded.length !== 64 || decoded.toString("base64") !== encoded) {
      signatureIssues.push(issue("approval_signature_encoding_invalid", `${field}.signatureBase64`));
      continue;
    }
    if (!crypto.verify(null, payload, trusted.publicKey, decoded)) {
      signatureIssues.push(issue("approval_signature_invalid", `${field}.signatureBase64`));
      continue;
    }
    validKeyIds.add(keyId);
    validRoles.add(trusted.role);
  }
  if (signatureIssues.length > 0) {
    return invalidApproval(base, signatureIssues, trustStoreSha256);
  }

  const productionApproved = validRoles.has("release_manager");
  const marketApproved = productionApproved
    && validRoles.has("security_approver")
    && validKeyIds.size >= 2;
  return {
    ...base,
    status: marketApproved
      ? "market_ga_approved"
      : productionApproved
        ? "production_deployment_approved"
        : "pending_external_approval",
    productionDeploymentStatus: productionApproved ? "proven" : "pending_external_validation",
    marketGaStatus: marketApproved ? "proven" : "pending_external_validation",
    trustStoreSha256,
    trustStorePinned: true,
    validSignatureCount: validKeyIds.size,
    validRoles: [...validRoles].sort(),
    pendingReasons: marketApproved
      ? []
      : productionApproved
        ? ["security_approver_signature_missing"]
        : ["release_manager_signature_missing"],
    issues: [],
  };
}

export function auditExternalAcceptance({
  root,
  manifestPath = "evidence/v9-external-acceptance/manifest.json",
  trustStorePath = "evidence/v9-external-acceptance/trusted-approvers.json",
  expectedTrustStoreSha256 = "",
  expectedRelease,
  now = new Date(),
}) {
  const repositoryRoot = path.resolve(root);
  const absoluteManifestPath = path.resolve(repositoryRoot, manifestPath);
  const reportBase = {
    manifestPath: path.relative(repositoryRoot, absoluteManifestPath).replaceAll("\\", "/"),
    trustStorePath: path.relative(
      repositoryRoot,
      path.resolve(repositoryRoot, trustStorePath),
    ).replaceAll("\\", "/"),
    requiredProductionDeploymentGates: [...PRODUCTION_DEPLOYMENT_GATE_IDS],
    requiredMarketGaGates: [...MARKET_GA_GATE_IDS],
  };
  if (!fs.statSync(absoluteManifestPath, { throwIfNoEntry: false })?.isFile()) {
    return {
      ...reportBase,
      status: "pending_external_validation",
      productionDeploymentStatus: "pending_external_validation",
      marketGaStatus: "pending_external_validation",
      releaseBindingStatus: "pending",
      approval: pendingApproval(
        approvalBase(repositoryRoot, trustStorePath),
        ["acceptance_manifest_missing"],
      ),
      gates: {},
      missingProductionDeploymentGates: [...PRODUCTION_DEPLOYMENT_GATE_IDS],
      missingMarketGaGates: [...MARKET_GA_GATE_IDS],
      issues: [],
    };
  }

  let manifest;
  try {
    manifest = readJson(absoluteManifestPath);
  } catch (error) {
    return {
      ...reportBase,
      status: "invalid_external_evidence",
      productionDeploymentStatus: "invalid_external_evidence",
      marketGaStatus: "invalid_external_evidence",
      releaseBindingStatus: "invalid",
      approval: invalidApproval(
        approvalBase(repositoryRoot, trustStorePath),
        [issue("manifest_invalid", "manifest")],
      ),
      gates: {},
      missingProductionDeploymentGates: [...PRODUCTION_DEPLOYMENT_GATE_IDS],
      missingMarketGaGates: [...MARKET_GA_GATE_IDS],
      issues: [issue("manifest_invalid", "manifest", error instanceof Error ? error.message : "")],
    };
  }

  const issues = [];
  if (manifest?.schemaVersion !== 1) {
    issues.push(issue("manifest_schema_unsupported", "schemaVersion"));
  }
  const nowTime = now instanceof Date ? now.getTime() : new Date(now).getTime();
  const releaseTime = validDate(expectedRelease?.generatedAt);
  const manifestTime = validDate(manifest?.generatedAt);
  if (!Number.isFinite(nowTime) || releaseTime === null) {
    issues.push(issue("expected_release_invalid", "expectedRelease.generatedAt"));
  }
  if (manifestTime === null) {
    issues.push(issue("manifest_generated_at_invalid", "generatedAt"));
  } else if (Number.isFinite(nowTime) && manifestTime > nowTime + MAX_CLOCK_SKEW_MS) {
    issues.push(issue("manifest_generated_at_future", "generatedAt"));
  }
  const bindingIssues = releaseBindingIssues(manifest?.release, expectedRelease);
  issues.push(...bindingIssues);

  const gates = {};
  const manifestDirectory = path.dirname(absoluteManifestPath);
  const manifestGates = manifest?.gates && typeof manifest.gates === "object"
    && !Array.isArray(manifest.gates) ? manifest.gates : {};
  for (const gateId of MARKET_GA_GATE_IDS) {
    const result = validateGate(
      manifestDirectory,
      gateId,
      manifestGates[gateId],
      releaseTime ?? Number.POSITIVE_INFINITY,
      nowTime,
      manifest?.release,
    );
    gates[gateId] = result;
    issues.push(...result.issues);
  }

  const approval = validateApproval({
    repositoryRoot,
    trustStorePath,
    expectedTrustStoreSha256,
    manifest,
    manifestTime: manifestTime ?? Number.POSITIVE_INFINITY,
    nowTime,
  });
  issues.push(...approval.issues);

  const missingProductionDeploymentGates = PRODUCTION_DEPLOYMENT_GATE_IDS
    .filter((gateId) => gates[gateId].status !== "passed");
  const missingMarketGaGates = MARKET_GA_GATE_IDS
    .filter((gateId) => gates[gateId].status !== "passed");
  const invalid = issues.length > 0;
  const productionProven = !invalid
    && missingProductionDeploymentGates.length === 0
    && approval.productionDeploymentStatus === "proven";
  const marketGaProven = productionProven
    && missingMarketGaGates.length === 0
    && approval.marketGaStatus === "proven";

  return {
    ...reportBase,
    status: invalid
      ? "invalid_external_evidence"
      : marketGaProven
        ? "market_ga_proven"
        : productionProven
          ? "production_deployment_proven"
          : "pending_external_validation",
    productionDeploymentStatus: invalid
      ? "invalid_external_evidence"
      : productionProven ? "proven" : "pending_external_validation",
    marketGaStatus: invalid
      ? "invalid_external_evidence"
      : marketGaProven ? "proven" : "pending_external_validation",
    releaseBindingStatus: bindingIssues.length === 0 ? "proven" : "invalid",
    approval,
    gates,
    missingProductionDeploymentGates,
    missingMarketGaGates,
    issues,
  };
}

function readExpectedRelease(repositoryRoot) {
  const deliveryManifestPath = path.join(
    repositoryRoot,
    "output/v9.0.0-formal-delivery/android/release-manifest.json",
  );
  const releaseManifestPath = fs.statSync(
    deliveryManifestPath,
    { throwIfNoEntry: false },
  )?.isFile()
    ? deliveryManifestPath
    : [
      "output/v9.0.0-formal-release-rerun/release-manifest.json",
      "output/v9.0.0-formal-release/release-manifest.json",
      "output/v9.0.0-formal-release-current/release-manifest.json",
      "android/release-manifest.json",
    ]
      .map((relativePath) => {
        const absolutePath = path.join(repositoryRoot, relativePath);
        return {
          absolutePath,
          stat: fs.statSync(absolutePath, { throwIfNoEntry: false }),
        };
      })
      .filter(({ stat }) => stat?.isFile())
      .sort((left, right) => right.stat.mtimeMs - left.stat.mtimeMs)[0]?.absolutePath;
  if (!releaseManifestPath) {
    throw new Error(`V9 release manifest is missing under ${repositoryRoot}`);
  }
  const releaseManifest = readJson(releaseManifestPath);
  const zipSidecar = fs.readFileSync(
    path.join(repositoryRoot, "output/DingdangAI-V9-9.0.0-formal-delivery.zip.sha256"),
    "utf8",
  ).trim().split(/\s+/)[0];
  return {
    applicationId: releaseManifest.applicationId,
    versionCode: releaseManifest.versionCode,
    versionName: releaseManifest.versionName,
    apkSha256: releaseManifest.sha256,
    deliveryZipSha256: zipSidecar,
    generatedAt: releaseManifest.builtAt,
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const repositoryRoot = path.resolve(process.argv[2] || path.join(import.meta.dirname, ".."));
  const manifestPath = process.argv[3] || "evidence/v9-external-acceptance/manifest.json";
  if (process.argv.includes("--print-signing-payload")) {
    const manifest = readJson(path.resolve(repositoryRoot, manifestPath));
    process.stdout.write(createExternalAcceptanceSigningPayload(manifest));
    process.exit(0);
  }
  const report = auditExternalAcceptance({
    root: repositoryRoot,
    manifestPath,
    expectedTrustStoreSha256: process.env.V9_EXTERNAL_ACCEPTANCE_TRUST_STORE_SHA256 || "",
    expectedRelease: readExpectedRelease(repositoryRoot),
  });
  console.log(JSON.stringify(report, null, 2));
  if (report.status === "invalid_external_evidence"
      || (process.argv.includes("--require-market-ga") && report.marketGaStatus !== "proven")) {
    process.exitCode = 1;
  }
}
