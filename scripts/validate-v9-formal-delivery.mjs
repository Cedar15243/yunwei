import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import os from "node:os";
import { spawnSync } from "node:child_process";
import {
  EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS,
  auditExternalAcceptance,
  createExternalAcceptanceSigningPayload,
} from "./v9-external-acceptance.mjs";
import { validateV9DastReport } from "./validate-v9-dast-report.mjs";
import { validateReleaseBoundDocuments } from "./v9-delivery-document-binding.mjs";

const root = process.cwd();
const delivery = path.join(root, "output", "v9.0.0-formal-delivery");
const zip = path.join(root, "output", "DingdangAI-V9-9.0.0-formal-delivery.zip");
const zipDigest = path.join(root, "output", "DingdangAI-V9-9.0.0-formal-delivery.zip.sha256");

function fail(message) {
  throw new Error(`V9 formal delivery validation failed: ${message}`);
}

function mustFile(relativePath) {
  const absolute = path.join(delivery, relativePath);
  if (!fs.statSync(absolute, { throwIfNoEntry: false })?.isFile()) fail(`missing file ${relativePath}`);
  return absolute;
}

function sha256(filePath) {
  return crypto.createHash("sha256").update(fs.readFileSync(filePath)).digest("hex").toUpperCase();
}

function listRelativeFiles(directory) {
  return fs.readdirSync(directory, { recursive: true })
    .filter((entry) => fs.statSync(path.join(directory, entry), { throwIfNoEntry: false })?.isFile())
    .map((entry) => entry.replaceAll("\\", "/"))
    .sort();
}

function powerShellLiteral(value) {
  return `'${value.replaceAll("'", "''")}'`;
}

function verifyDeliveryArchiveExtracts() {
  const extractionRoot = fs.mkdtempSync(path.join(os.tmpdir(), "v9-delivery-extract-"));
  try {
    const command = [
      "$ErrorActionPreference = 'Stop'",
      `Expand-Archive -LiteralPath ${powerShellLiteral(zip)} -DestinationPath ${powerShellLiteral(extractionRoot)} -Force`,
    ].join("; ");
    const candidates = process.platform === "win32" ? ["powershell"] : ["pwsh", "powershell"];
    let result = null;
    for (const executable of candidates) {
      result = spawnSync(executable, [
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-Command",
        command,
      ], { encoding: "utf8", maxBuffer: 20 * 1024 * 1024 });
      if (result.error?.code !== "ENOENT") break;
    }
    if (!result || result.error?.code === "ENOENT") {
      fail("delivery zip archive extraction requires PowerShell");
    }
    if (result.status !== 0) {
      fail(`delivery zip archive extraction failed: ${result.stderr || result.stdout}`);
    }

    const stagedFiles = listRelativeFiles(delivery);
    const extractedFiles = listRelativeFiles(extractionRoot);
    if (JSON.stringify(extractedFiles) !== JSON.stringify(stagedFiles)) {
      const missing = stagedFiles.filter((file) => !extractedFiles.includes(file));
      const unexpected = extractedFiles.filter((file) => !stagedFiles.includes(file));
      fail(`delivery zip archive file list mismatch missing=${missing.join(",")} unexpected=${unexpected.join(",")}`);
    }
    for (const relative of stagedFiles) {
      const staged = path.join(delivery, relative);
      const extracted = path.join(extractionRoot, relative);
      if (fs.statSync(staged).size !== fs.statSync(extracted).size) {
        fail(`delivery zip archive size mismatch ${relative}`);
      }
      if (sha256(staged) !== sha256(extracted)) {
        fail(`delivery zip archive hash mismatch ${relative}`);
      }
    }
  } finally {
    fs.rmSync(extractionRoot, { recursive: true, force: true });
  }
}

if (!fs.statSync(delivery, { throwIfNoEntry: false })?.isDirectory()) fail("delivery directory is missing");
if (!fs.statSync(zip, { throwIfNoEntry: false })?.isFile()) fail("delivery zip is missing");
if (!fs.statSync(zipDigest, { throwIfNoEntry: false })?.isFile()) fail("delivery zip digest is missing");

for (const required of [
  "android/DingdangAI-V9-9.0.0-release.apk",
  "android/release-manifest.json",
  "management-web/dist/index.html",
  "management-web/public/favicon.svg",
  "management-web/Dockerfile",
  "management-web/docker-compose.cloud.yml",
  "management-web/deploy/install.sh",
  "management-web/deploy/stage.sh",
  "management-web/deploy/activate.sh",
  "management-web/deploy/rollback.sh",
  "management-web/deploy/health-check.sh",
  "management-web/deploy/merge_caddy.py",
  "management-web/deploy/Caddyfile.snippet.template",
  "v9-ops-gateway/gateway.py",
  "v9-ops-gateway/backup_restore.py",
  "v9-ops-gateway/knowledge_document_parser.py",
  "v9-ops-gateway/requirements.txt",
  "v9-ops-gateway/voiceprint_proxy.py",
  "v9-ops-gateway/deploy/install.sh",
  "v9-ops-gateway/deploy/backup.sh",
  "v9-ops-gateway/deploy/restore-drill.sh",
  "v9-ops-gateway/deploy/dingdang-v9-backup.service",
  "v9-ops-gateway/deploy/dingdang-v9-backup.timer",
  "v9-ops-gateway/monitoring_probe.py",
  "v9-ops-gateway/deploy/dingdang-v9-monitor.env.example",
  "v9-ops-gateway/deploy/dingdang-v9-monitor.service",
  "v9-ops-gateway/deploy/dingdang-v9-monitor.timer",
  "v9-ops-gateway/deploy/install-monitoring.sh",
  "v9-ops-gateway/deploy/rollback-monitoring.sh",
  "v9-ops-gateway/deploy/health-check.sh",
  "v9-ops-gateway/deploy/merge_production_overlay.py",
  "v9-ops-gateway/deploy/stage-production-overlay.sh",
  "v9-ops-gateway/deploy/activate-production-overlay.sh",
  "v9-ops-gateway/deploy/rollback-production-overlay.sh",
  "v9-ops-gateway/deploy/update-runtime.sh",
  "v9-ops-gateway/deploy/monitoring/prometheus-rules.yml",
  "supabase/functions/ops-glasses/index.ts",
  "supabase/functions/ops-glasses/knowledge-document-parser-client.ts",
  "supabase/migrations/20260805094500_knowledge_case_drafts_from_tasks.sql",
  "supabase/migrations/20260805110000_account_lifecycle_management.sql",
  "supabase/migrations/20260805113000_account_email_uniqueness.sql",
  "supabase/migrations/20260805210000_workflow_terminal_convergence.sql",
  "docs/RELEASE_NOTES.md",
  "docs/DEPLOYMENT.md",
  "docs/ROLLBACK.md",
  "docs/EXTERNAL_PREREQUISITES.md",
  "docs/MODEL_CONTRACT.md",
  "docs/VERIFICATION.md",
  "docs/CURRENT_STATE_AUDIT.md",
  "docs/COMPLETION_MATRIX.md",
  "docs/FORMAL_RELEASE.md",
  "docs/AIR3_HARDWARE_SOAK.md",
  "verification/air3/manifest.json",
  "verification/air3/inspection.png",
  "verification/air3/home.png",
  "verification/air3/capabilities.xml",
  "verification/air3/inspection.xml",
  "verification/air3/back-capabilities.xml",
  "verification/air3/back-home.xml",
  "verification/air3-soak/summary.json",
  "security/SBOM.cdx.json",
  "security/DEPENDENCY_AUDIT.json",
  "security/DEPENDENCY_AUDIT.md",
  "security/OPERATIONAL_READINESS.json",
  "security/OPERATIONAL_READINESS.md",
  "security/dast/README.md",
  "security/dast/v9-zap-baseline.json",
  "security/dast/v9-zap-baseline.html",
  "security/verify-v9-delivery.ps1",
  "security/run-v9-dast-baseline.ps1",
  "security/validate-v9-dast-report.mjs",
  "security/v9-external-acceptance.mjs",
  "security/init-v9-external-acceptance.mjs",
  "security/record-v9-external-attestation.mjs",
  "security/attach-v9-external-approval-signature.mjs",
  "docs/operations/v9-backup-restore-runbook.md",
  "docs/operations/v9-observability-alerting-contract.md",
  "docs/operations/v9-external-acceptance-evidence.md",
  "docs/operations/v9-external-acceptance-manifest.example.json",
  "docs/operations/v9-external-acceptance-trusted-approvers.example.json",
  "docs/operations/v9-external-acceptance-required-checks.json",
  "docs/security/v9-sast-and-penetration-test-plan.md",
  "DELIVERY_MANIFEST.json",
  "SHA256SUMS.txt",
]) mustFile(required);

const manifest = JSON.parse(fs.readFileSync(path.join(delivery, "DELIVERY_MANIFEST.json"), "utf8").replace(/^\uFEFF/, ""));
if (manifest.product !== "Dingdang AI Operations Glasses") fail("unexpected product identifier");
if (manifest.release !== "V9.0.0") fail(`unexpected release ${manifest.release}`);
if (manifest.applicationId !== "com.codex.air3nativecamera.dingdangexpert.v9") fail("unexpected applicationId");
if (manifest.versionCode !== 900000) fail("unexpected versionCode");
if (manifest.secureRuntime !== true) fail("secureRuntime must be true");
if (manifest.externalPrerequisitesRequired !== true) fail("external prerequisite gate missing");
if (manifest.productionDeploymentStatus !== "pending_external_credentials_and_hardware_acceptance") {
  fail("production status must remain explicit until external acceptance");
}
const contract = manifest.modelContract ?? {};
for (const [key, expected] of Object.entries({
  ai: "qwen3-vl-plus",
  asr: "fun-asr-realtime",
  wake: "previous-iflytek-aikit",
  voiceprint: "s1aa729d0",
})) {
  if (contract[key] !== expected) fail(`model contract ${key} is ${contract[key]}`);
}

const releaseManifestForEvidence = JSON.parse(fs.readFileSync(
  mustFile("android/release-manifest.json"),
  "utf8",
).replace(/^\uFEFF/, ""));
try {
  validateReleaseBoundDocuments({
    apkSha256: releaseManifestForEvidence.sha256,
    documents: Object.fromEntries([
      "docs/FORMAL_RELEASE.md",
      "docs/VERIFICATION.md",
      "docs/CURRENT_STATE_AUDIT.md",
      "docs/COMPLETION_MATRIX.md",
    ].map((relative) => [relative, fs.readFileSync(mustFile(relative), "utf8").replace(/^\uFEFF/, "")])),
  });
} catch (error) {
  fail(error instanceof Error ? error.message : String(error));
}
const air3Evidence = JSON.parse(fs.readFileSync(
  mustFile("verification/air3/manifest.json"),
  "utf8",
).replace(/^\uFEFF/, ""));
if (air3Evidence.schemaVersion !== 1
    || air3Evidence.evidenceType !== "local_air3_ui_regression") {
  fail("Air3 evidence manifest contract is invalid");
}
if (air3Evidence.release?.applicationId !== releaseManifestForEvidence.applicationId
    || air3Evidence.release?.versionCode !== releaseManifestForEvidence.versionCode
    || air3Evidence.release?.versionName !== releaseManifestForEvidence.versionName
    || air3Evidence.release?.apkSha256 !== releaseManifestForEvidence.sha256) {
  fail("Air3 evidence release binding mismatch");
}
const evidenceCapturedAt = Date.parse(air3Evidence.capturedAt ?? "");
const releaseBuiltAt = Date.parse(releaseManifestForEvidence.builtAt ?? "");
if (!Number.isFinite(evidenceCapturedAt)
    || !Number.isFinite(releaseBuiltAt)
    || evidenceCapturedAt < releaseBuiltAt) {
  fail("Air3 evidence capture time must be valid and after the formal build");
}
if (air3Evidence.device?.model !== "IMA301"
    || !/^YM00\.\.\.0031$/.test(air3Evidence.device?.serialMasked ?? "")) {
  fail("Air3 evidence device identity is invalid or not masked");
}
const expectedInspectionTasks = [
  "实训室设备巡检",
  "水电暖系统巡检",
  "空调系统巡检",
  "消防系统巡检",
];
if (JSON.stringify(air3Evidence.observations?.inspectionTasks)
    !== JSON.stringify(expectedInspectionTasks)
    || air3Evidence.observations?.placeholderVisible !== false
    || JSON.stringify(air3Evidence.observations?.returnChain)
      !== JSON.stringify(["巡检详情", "AI 能力中心", "首页"])
    || air3Evidence.observations?.camera?.activeClients !== 0
    || air3Evidence.observations?.camera?.deviceState !== "closed"
    || air3Evidence.observations?.crashBufferEmpty !== true) {
  fail("Air3 evidence observations are incomplete");
}
if (!Array.isArray(air3Evidence.limitations)
    || !air3Evidence.limitations.includes("managed_services_unavailable")
    || !air3Evidence.limitations.includes("production_voice_voiceprint_not_accepted")) {
  fail("Air3 evidence limitations must remain explicit");
}
const expectedAir3Artifacts = [
  "inspection.png",
  "home.png",
  "capabilities.xml",
  "inspection.xml",
  "back-capabilities.xml",
  "back-home.xml",
];
if (!Array.isArray(air3Evidence.artifacts)
    || air3Evidence.artifacts.length !== expectedAir3Artifacts.length) {
  fail("Air3 evidence artifact list is incomplete");
}
for (const name of expectedAir3Artifacts) {
  const artifact = air3Evidence.artifacts.find((item) => item?.name === name);
  if (!artifact || artifact.path !== `verification/air3/${name}`) {
    fail(`Air3 evidence artifact is missing ${name}`);
  }
  const artifactPath = mustFile(artifact.path);
  if (sha256(artifactPath) !== artifact.sha256
      || fs.statSync(artifactPath).size !== artifact.bytes) {
    fail(`Air3 evidence artifact mismatch ${name}`);
  }
}
for (const name of ["inspection.png", "home.png"]) {
  const signature = fs.readFileSync(mustFile(`verification/air3/${name}`)).subarray(0, 8);
  if (!signature.equals(Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]))) {
    fail(`Air3 evidence image is not a PNG ${name}`);
  }
}
const inspectionUi = fs.readFileSync(mustFile("verification/air3/inspection.xml"), "utf8");
for (const task of expectedInspectionTasks) {
  if (!inspectionUi.includes(task)) fail(`Air3 inspection UI is missing ${task}`);
}
if (inspectionUi.includes("该能力没有可展示的本地内容")) {
  fail("Air3 inspection UI still contains the placeholder message");
}
const capabilitiesUi = fs.readFileSync(
  mustFile("verification/air3/back-capabilities.xml"),
  "utf8",
);
if (!capabilitiesUi.includes("AI 能力中心")) fail("Air3 return chain is missing the capability center");
const homeUi = fs.readFileSync(mustFile("verification/air3/back-home.xml"), "utf8");
if (!homeUi.includes("设备待激活") || !homeUi.includes("AI 能力中心")) {
  fail("Air3 return chain is missing the confirmed home state");
}
if (fs.existsSync(path.join(delivery, "verification", "air3", "logcat.txt"))) {
  fail("raw Air3 logcat must not be shipped in the formal delivery");
}

const air3SoakPath = mustFile("verification/air3-soak/summary.json");
const air3SoakContent = fs.readFileSync(air3SoakPath, "utf8").replace(/^\uFEFF/, "");
const air3Soak = JSON.parse(air3SoakContent);
if (air3Soak.schemaVersion !== 1 || air3Soak.evidenceType !== "local_air3_hardware_soak") {
  fail("Air3 soak evidence contract is invalid");
}
if (air3Soak.release?.applicationId !== releaseManifestForEvidence.applicationId
    || air3Soak.release?.versionCode !== releaseManifestForEvidence.versionCode
    || air3Soak.release?.versionName !== releaseManifestForEvidence.versionName
    || air3Soak.release?.apkSha256 !== releaseManifestForEvidence.sha256) {
  fail("Air3 soak evidence release binding mismatch");
}
const soakCapturedAt = Date.parse(air3Soak.capturedAt ?? "");
if (!Number.isFinite(soakCapturedAt) || soakCapturedAt < releaseBuiltAt) {
  fail("Air3 soak evidence capture time must be valid and after the formal build");
}
if (air3Soak.device?.model !== "IMA301"
    || !/^YM00\.\.\.0031$/.test(air3Soak.device?.serialMasked ?? "")) {
  fail("Air3 soak device identity is invalid or not masked");
}
const soak = air3Soak.measurements ?? {};
const thermalPeaks = soak.thermalSensorPeaksC ?? {};
const finite = (value) => typeof value === "number" && Number.isFinite(value);
const expectedSoakSampleCount = Number.isInteger(soak.sampleIntervalSeconds) &&
  soak.sampleIntervalSeconds > 0 && finite(soak.durationSecondsRequested)
  ? Math.ceil(soak.durationSecondsRequested / soak.sampleIntervalSeconds) + 1
  : NaN;
if (soak.durationSecondsRequested < 1200
    || soak.durationSecondsObserved < soak.durationSecondsRequested
    || soak.sampleIntervalSeconds !== 30
    || soak.requiredSampleCount !== expectedSoakSampleCount
    || soak.sampleCount < soak.requiredSampleCount
    || soak.sampleCoveragePassed !== true
    || soak.cameraCyclesRequested < 3
    || soak.cameraCyclesPassed !== true
    || soak.thermalSensorSampleCount !== soak.sampleCount
    || !finite(thermalPeaks.cpu)
    || !finite(thermalPeaks.gpu)
    || !finite(thermalPeaks.skin)
    || !finite(soak.maxBatteryTemperatureC)
    || soak.maxBatteryTemperatureC > 45
    || !finite(soak.maxThermalStatus)
    || soak.maxThermalStatus > 2
    || !finite(soak.pssDeltaKb)
    || soak.pssDeltaKb > 30000
    || !finite(soak.finalJankPercent)
    || soak.finalJankPercent > 10
    || soak.externalPowerConnected !== true
    || soak.powerMeasurementValid !== false
    || soak.cameraLeak !== false
    || soak.audioLeak !== false
    || soak.crashBufferEmpty !== true
    || soak.anrObserved !== false
    || soak.foregroundFinal !== true
    || soak.protectedV8Present !== true
    || soak.protectedV8Unchanged !== true
    || soak.passed !== true) {
  fail("Air3 soak measurements are incomplete or outside the release budget");
}
if (!Array.isArray(air3Soak.limitations)
    || !air3Soak.limitations.includes("usb_powered_power_measurement_invalid")
    || !air3Soak.limitations.includes("managed_services_unavailable")
    || !air3Soak.limitations.includes("production_voice_voiceprint_not_accepted")) {
  fail("Air3 soak limitations must remain explicit");
}
for (const rawName of ["snapshots.json", "logcat-all.txt", "logcat-crash.txt", "logcat-filtered.txt"]) {
  if (fs.existsSync(path.join(delivery, "verification", "air3-soak", rawName))) {
    fail("raw Air3 soak evidence must not be shipped");
  }
}
if (air3SoakContent.includes("YM00FCF3NW0031")
    || /[A-Z]:\\Users\\/i.test(air3SoakContent)
    || air3SoakContent.includes(".worktrees")) {
  fail("Air3 soak summary contains private device or workstation identifiers");
}

const sbom = JSON.parse(fs.readFileSync(mustFile("security/SBOM.cdx.json"), "utf8").replace(/^\uFEFF/, ""));
if (sbom.bomFormat !== "CycloneDX" || sbom.specVersion !== "1.5" || sbom.version !== 1) {
  fail("security SBOM must be CycloneDX 1.5 version 1");
}
if (!/^urn:uuid:[0-9a-f-]{36}$/.test(sbom.serialNumber ?? "")) fail("security SBOM serialNumber is invalid");
if (sbom.metadata?.component?.name !== "dingdang-ai-operations-glasses-v9") {
  fail("security SBOM root component is invalid");
}
const sbomComponents = Array.isArray(sbom.components) ? sbom.components : [];
if (sbomComponents.length < 50) fail("security SBOM is missing transitive components");
const bomRefs = new Set();
for (const item of sbomComponents) {
  if (!item["bom-ref"] || bomRefs.has(item["bom-ref"])) fail("security SBOM contains duplicate bom-ref");
  bomRefs.add(item["bom-ref"]);
  if (!item.name || !item.version || !item.purl) fail("security SBOM component is incomplete");
}
const sbomPurls = new Set(sbomComponents.map((item) => item.purl));
for (const requiredPurl of [
  "pkg:pypi/pypdf@6.15.0",
  "pkg:maven/com.squareup.okhttp3/okhttp@4.12.0",
  "pkg:generic/iflytek/aikit@previous-model-contract",
]) {
  if (!sbomPurls.has(requiredPurl)) fail(`security SBOM missing ${requiredPurl}`);
}
const dependencyAudit = JSON.parse(fs.readFileSync(mustFile("security/DEPENDENCY_AUDIT.json"), "utf8").replace(/^\uFEFF/, ""));
if (dependencyAudit.status !== "clean" || !Array.isArray(dependencyAudit.summaries)) {
  fail("dependency audit status is not clean");
}
for (const summary of dependencyAudit.summaries) {
  if (summary.total !== 0) fail(`dependency audit reports vulnerabilities for ${summary.source}`);
}
const operationalReadiness = JSON.parse(fs.readFileSync(mustFile("security/OPERATIONAL_READINESS.json"), "utf8").replace(/^\uFEFF/, ""));
if (operationalReadiness.status !== "local_controls_passed_external_gates_pending") {
  fail("operational readiness local controls are not passing");
}
if (operationalReadiness.marketGaStatus !== "blocked") fail("market GA gate must remain blocked");
for (const [name, status] of Object.entries(operationalReadiness.externalGates ?? {})) {
  const expected = name === "productionRestoreDrill" ? "passed" : "pending";
  if (status !== expected) fail(`external operational gate ${name} expected ${expected}, got ${status}`);
}
if (operationalReadiness.controls?.managementWebDeployment !== "passed") {
  fail("management Web deployment control is not passing");
}
if (operationalReadiness.controls?.gatewayConfigDeployment !== "passed") {
  fail("V9 gateway production configuration deployment control is not passing");
}
if (!operationalReadiness.requiredFiles?.includes("v9-ops-gateway/deploy/update-runtime.sh")) {
  fail("operational readiness report is missing the V9 runtime update control");
}
const gatewayOverlayMerger = fs.readFileSync(
  mustFile("v9-ops-gateway/deploy/merge_production_overlay.py"),
  "utf8",
);
const gatewayOverlayStage = fs.readFileSync(
  mustFile("v9-ops-gateway/deploy/stage-production-overlay.sh"),
  "utf8",
);
const gatewayOverlayActivate = fs.readFileSync(
  mustFile("v9-ops-gateway/deploy/activate-production-overlay.sh"),
  "utf8",
);
const gatewayOverlayRollback = fs.readFileSync(
  mustFile("v9-ops-gateway/deploy/rollback-production-overlay.sh"),
  "utf8",
);
const gatewayRuntimeUpdate = fs.readFileSync(
  mustFile("v9-ops-gateway/deploy/update-runtime.sh"),
  "utf8",
);
for (const marker of [
  "qwen3-vl-plus",
  "fun-asr-realtime",
  "s1aa729d0",
  "V9_CONTENT_MANIFEST_SYNC_TOKEN",
  "V9_KNOWLEDGE_PARSER_TOKEN_SHA256",
  "V9_VOICEPRINT_ADMIN_TOKEN_SHA256",
  "overlay_key_forbidden",
]) {
  if (!gatewayOverlayMerger.includes(marker)) fail(`gateway overlay merger is missing ${marker}`);
}
for (const marker of [
  "candidate",
  "SHA256SUMS",
  "merge_production_overlay.py",
  'install -m 0700 "$SOURCE_ROOT/activate-production-overlay.sh"',
  'install -m 0700 "$SOURCE_ROOT/rollback-production-overlay.sh"',
  'stat -c %a "$GATEWAY_OVERLAY"',
  'stat -c %U:%G "$VOICEPRINT_OVERLAY"',
]) {
  if (!gatewayOverlayStage.includes(marker)) fail(`gateway overlay stage is missing ${marker}`);
}
if (gatewayOverlayStage.includes("systemctl restart")) {
  fail("gateway overlay stage may not restart the V9 service");
}
for (const marker of [
  "systemctl restart dingdang-v9-gateway.service",
  'wait_for_health "$LOCAL_BASE/health"',
  'wait_for_health "$LOCAL_BASE/ready"',
  'wait_for_health "$PUBLIC_V9_BASE/health"',
  'wait_for_health "$PUBLIC_V9_BASE/ready"',
  'wait_for_health "$EXPERT_HEALTH"',
  "trap rollback_failed_activation EXIT",
  "trap interrupt_activation HUP INT TERM",
  "CHANGES_STARTED=1",
  'install_environment "$CURRENT/gateway.env" "$GATEWAY_ENV"',
  'install_environment "$CURRENT/voiceprint.env" "$VOICEPRINT_ENV"',
  'atomic_link "$CURRENT" "$CONFIG_ROOT/current"',
  'atomic_link "$PREVIOUS_TARGET" "$CONFIG_ROOT/previous"',
]) {
  if (!gatewayOverlayActivate.includes(marker)) fail(`gateway overlay activation is missing ${marker}`);
}
for (const marker of [
  "previous",
  "systemctl restart dingdang-v9-gateway.service",
  'wait_for_health "$LOCAL_BASE/ready"',
  'wait_for_health "$EXPERT_HEALTH"',
]) {
  if (!gatewayOverlayRollback.includes(marker)) fail(`gateway overlay rollback is missing ${marker}`);
}
for (const content of [gatewayOverlayStage, gatewayOverlayActivate, gatewayOverlayRollback]) {
  for (const forbidden of ["ai-edge-caddy", "dingdang-expert-collab", "docker restart"]) {
    if (content.includes(forbidden)) fail(`gateway overlay deployment may not touch ${forbidden}`);
  }
}
for (const marker of [
  "backup_restore.py",
  "RUNTIME_UPDATE_SHA256SUMS",
  "rollback_failed_update",
  'ln -sfn "$CURRENT" "$APP_ROOT/previous"',
  'ln -sfn "$RELEASE_DIR" "$APP_ROOT/current"',
  'wait_for_health "$LOCAL_BASE/health"',
  'wait_for_health "$LOCAL_BASE/ready"',
  'wait_for_health "$PUBLIC_V9_BASE/health"',
  'wait_for_health "$PUBLIC_V9_BASE/ready"',
  'wait_for_health "$EXPERT_HEALTH"',
]) {
  if (!gatewayRuntimeUpdate.includes(marker)) fail(`gateway runtime update is missing ${marker}`);
}
for (const forbidden of ["ai-edge-caddy", "dingdang-expert-collab", "docker restart", "/etc/caddy", "Caddyfile"]) {
  if (gatewayRuntimeUpdate.includes(forbidden)) fail(`gateway runtime update may not touch ${forbidden}`);
}
const managementCompose = fs.readFileSync(
  mustFile("management-web/docker-compose.cloud.yml"),
  "utf8",
);
for (const marker of [
  "OPS_MANAGEMENT_IMAGE",
  "127.0.0.1:${OPS_MANAGEMENT_PORT:-8788}:8080",
  "healthcheck:",
  "read_only: true",
  "no-new-privileges:true",
]) {
  if (!managementCompose.includes(marker)) fail(`management Web compose is missing ${marker}`);
}
for (const forbidden of ["8787", "8790", "expert-collab"]) {
  if (managementCompose.includes(forbidden)) fail(`management Web compose is not isolated from ${forbidden}`);
}
const managementInstall = fs.readFileSync(
  mustFile("management-web/deploy/install.sh"),
  "utf8",
);
const managementStage = fs.readFileSync(
  mustFile("management-web/deploy/stage.sh"),
  "utf8",
);
const managementActivate = fs.readFileSync(
  mustFile("management-web/deploy/activate.sh"),
  "utf8",
);
const managementRollback = fs.readFileSync(
  mustFile("management-web/deploy/rollback.sh"),
  "utf8",
);
for (const marker of [
  '"$SOURCE_ROOT/deploy/stage.sh" "$RELEASE_ID"',
  '"$SOURCE_ROOT/deploy/activate.sh" "$RELEASE_ID"',
]) {
  if (!managementInstall.includes(marker)) fail(`management Web installer is missing ${marker}`);
}
for (const marker of [
  "RELEASE_DIR=$APP_ROOT/releases/$RELEASE_ID",
  "OPS_MANAGEMENT_IMAGE_ID",
  'ln -sfn "$RELEASE_DIR" "$APP_ROOT/candidate"',
  "docker build",
  "docker run",
  "--read-only",
  "https://bb.chinacedar.top:2305/health",
  "https://bb.chinacedar.top:2305/v9-ops/health",
  "assert_container_unchanged",
]) {
  if (!managementStage.includes(marker)) fail(`management Web stage is missing ${marker}`);
}
for (const forbidden of [
  'python3 "$RELEASE_DIR/deploy/merge_caddy.py"',
  "caddy reload",
  'ln -sfn "$RELEASE_DIR" "$APP_ROOT/current"',
]) {
  if (managementStage.includes(forbidden)) fail(`management Web stage may not execute ${forbidden}`);
}
for (const marker of [
  'readlink -f "$APP_ROOT/candidate"',
  "RELEASE_DIR=$APP_ROOT/releases/$RELEASE_ID",
  "OPS_MANAGEMENT_IMAGE_ID",
  "trap rollback_failed_activation EXIT HUP INT TERM",
  "docker exec ai-edge-caddy caddy validate",
  "docker exec ai-edge-caddy caddy reload",
  "https://bb.chinacedar.top:2305/health",
  "https://bb.chinacedar.top:2305/v9-ops/health",
  "assert_container_unchanged",
]) {
  if (!managementActivate.includes(marker)) fail(`management Web activation is missing ${marker}`);
}
for (const marker of [
  'readlink -f "$APP_ROOT/previous"',
  "OPS_MANAGEMENT_IMAGE_ID",
  "docker exec ai-edge-caddy caddy validate",
  "docker exec ai-edge-caddy caddy reload",
  "https://bb.chinacedar.top:2305/health",
  "https://bb.chinacedar.top:2305/v9-ops/health",
  "assert_container_unchanged",
]) {
  if (!managementRollback.includes(marker)) fail(`management Web rollback is missing ${marker}`);
}
for (const content of [managementStage, managementActivate, managementRollback]) {
  for (const forbidden of [
    "docker restart",
    "systemctl restart caddy",
    "systemctl restart dingdang-expert",
  ]) {
    if (content.includes(forbidden)) fail(`management Web deployment may not execute ${forbidden}`);
  }
}
const dastReportPath = mustFile("security/dast/v9-zap-baseline.json");
const dastResult = validateV9DastReport(dastReportPath);
if (dastResult.alertCounts.high !== 0
    || dastResult.alertCounts.medium !== 0
    || dastResult.alertCounts.low !== 0) {
  fail("packaged DAST report contains a High, Medium or Low alert");
}
const dastReadme = fs.readFileSync(mustFile("security/dast/README.md"), "utf8");
for (const marker of ["development-team authorized passive DAST", "third-party penetration", "pending"]) {
  if (!dastReadme.toLowerCase().includes(marker.toLowerCase())) {
    fail(`DAST README is missing boundary marker ${marker}`);
  }
}
const packagedSelfVerifier = mustFile("security/verify-v9-delivery.ps1");
const packagedSelfVerification = spawnSync("powershell", [
  "-NoProfile",
  "-NonInteractive",
  "-ExecutionPolicy",
  "Bypass",
  "-File",
  packagedSelfVerifier,
  "-DeliveryRoot",
  delivery,
], { encoding: "utf8", maxBuffer: 20 * 1024 * 1024 });
if (packagedSelfVerification.status !== 0) {
  fail(`packaged delivery self-verifier failed: ${packagedSelfVerification.stderr || packagedSelfVerification.stdout}`);
}
const externalAcceptanceVerifier = fs.readFileSync(
  mustFile("security/v9-external-acceptance.mjs"),
  "utf8",
);
for (const marker of [
  "createExternalAcceptanceSigningPayload",
  "crypto.verify",
  "release_manager",
  "security_approver",
  "V9_EXTERNAL_ACCEPTANCE_TRUST_STORE_SHA256",
  "attestation_required_check_missing",
]) {
  if (!externalAcceptanceVerifier.includes(marker)) {
    fail(`external acceptance verifier missing ${marker}`);
  }
}
const externalAcceptanceInitializer = mustFile("security/init-v9-external-acceptance.mjs");
const externalAttestationRecorder = mustFile("security/record-v9-external-attestation.mjs");
const externalApprovalSignatureAttacher = mustFile(
  "security/attach-v9-external-approval-signature.mjs",
);
const initializerWorkspace = fs.mkdtempSync(path.join(os.tmpdir(), "v9-delivery-acceptance-init-"));
try {
  const initializerResult = spawnSync(process.execPath, [
    externalAcceptanceInitializer,
    delivery,
    initializerWorkspace,
    "--delivery-sidecar",
    zipDigest,
  ], { encoding: "utf8" });
  if (initializerResult.status !== 0) {
    fail(`external acceptance workspace initializer failed: ${initializerResult.stderr}`);
  }
  const initializedManifest = JSON.parse(fs.readFileSync(
    path.join(initializerWorkspace, "manifest.json"),
    "utf8",
  ));
  const releaseManifest = JSON.parse(fs.readFileSync(
    mustFile("android/release-manifest.json"),
    "utf8",
  ).replace(/^\uFEFF/, ""));
  const deliveryZipSha256 = fs.readFileSync(zipDigest, "utf8").trim().split(/\s+/)[0];
  if (initializedManifest.release?.applicationId !== releaseManifest.applicationId
      || initializedManifest.release?.versionCode !== releaseManifest.versionCode
      || initializedManifest.release?.versionName !== releaseManifest.versionName
      || initializedManifest.release?.apkSha256 !== releaseManifest.sha256
      || initializedManifest.release?.deliveryZipSha256 !== deliveryZipSha256
      || initializedManifest.release?.generatedAt !== releaseManifest.builtAt) {
    fail("external acceptance workspace initializer release binding mismatch");
  }
  if (Object.keys(initializedManifest.gates ?? {}).length !== 0
      || (initializedManifest.signatures ?? []).length !== 0) {
    fail("external acceptance workspace initializer must remain pending");
  }
  if (fs.existsSync(path.join(initializerWorkspace, "trusted-approvers.json"))) {
    fail("external acceptance workspace initializer generated a trust store");
  }
  for (const [gateId, requiredChecks] of Object.entries(EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS)) {
    const template = JSON.parse(fs.readFileSync(
      path.join(initializerWorkspace, "templates", `${gateId}-attestation.json`),
      "utf8",
    ));
    if (template.result !== "pending"
        || template.checks?.length !== requiredChecks.length
        || template.checks.some((check, index) => (
          check.id !== requiredChecks[index] || check.status !== "pending"
        ))) {
      fail(`external acceptance workspace initializer template mismatch ${gateId}`);
    }
  }
  const recordedGateId = "production_supabase";
  const recordedAttestationPath = path.join(
    initializerWorkspace,
    "files",
    `${recordedGateId}-attestation.json`,
  );
  fs.mkdirSync(path.dirname(recordedAttestationPath), { recursive: true });
  const executedAt = new Date().toISOString();
  fs.writeFileSync(recordedAttestationPath, `${JSON.stringify({
    schemaVersion: 1,
    gateId: recordedGateId,
    release: initializedManifest.release,
    environment: "production",
    executedAt,
    runner: "formal-delivery-validator",
    result: "passed",
    checks: EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS[recordedGateId].map((id) => ({
      id,
      status: "passed",
    })),
  }, null, 2)}\n`, "utf8");
  const initializedManifestPath = path.join(initializerWorkspace, "manifest.json");
  const initializedManifestSha256 = sha256(initializedManifestPath);
  const recorderResult = spawnSync(process.execPath, [
    externalAttestationRecorder,
    delivery,
    initializerWorkspace,
    recordedGateId,
    `files/${recordedGateId}-attestation.json`,
    "--approver",
    "formal-delivery-validator",
    "--expected-manifest-sha256",
    initializedManifestSha256,
    "--confirm",
    "RECORD_EXTERNAL_ACCEPTANCE",
    "--delivery-sidecar",
    zipDigest,
  ], { encoding: "utf8" });
  if (recorderResult.status !== 0) {
    fail(`external attestation recorder failed: ${recorderResult.stderr}`);
  }
  const recordedManifest = JSON.parse(fs.readFileSync(initializedManifestPath, "utf8"));
  if (recordedManifest.gates?.[recordedGateId]?.status !== "passed"
      || recordedManifest.gates?.[recordedGateId]?.completedAt !== executedAt
      || recordedManifest.signatures?.length !== 0) {
    fail("external attestation recorder output is invalid");
  }
  const recordedAudit = auditExternalAcceptance({
    root: delivery,
    manifestPath: path.relative(delivery, initializedManifestPath),
    expectedRelease: initializedManifest.release,
  });
  if (recordedAudit.gates?.[recordedGateId]?.status !== "passed"
      || recordedAudit.issues.length > 0) {
    fail("external attestation recorder semantic validation failed");
  }
  const testApprovers = [
    { keyId: "delivery-release-key", role: "release_manager" },
    { keyId: "delivery-security-key", role: "security_approver" },
  ].map((item) => {
    const pair = crypto.generateKeyPairSync("ed25519");
    return {
      ...item,
      privateKey: pair.privateKey,
      publicKeyPem: pair.publicKey.export({ type: "spki", format: "pem" }),
    };
  });
  const temporaryTrustStorePath = path.join(initializerWorkspace, "trusted-approvers.json");
  fs.writeFileSync(temporaryTrustStorePath, `${JSON.stringify({
    schemaVersion: 1,
    keys: testApprovers.map((item) => ({
      keyId: item.keyId,
      algorithm: "ed25519",
      role: item.role,
      status: "active",
      publicKeyPem: item.publicKeyPem,
    })),
  }, null, 2)}\n`, "utf8");
  const temporaryTrustStoreSha256 = sha256(temporaryTrustStorePath);
  for (const [index, approver] of testApprovers.entries()) {
    const currentManifest = JSON.parse(fs.readFileSync(initializedManifestPath, "utf8"));
    const signatureBase64 = crypto.sign(
      null,
      createExternalAcceptanceSigningPayload(currentManifest),
      approver.privateKey,
    ).toString("base64");
    const relativeSignaturePath = `signatures/${approver.keyId}.sig`;
    const absoluteSignaturePath = path.join(initializerWorkspace, relativeSignaturePath);
    fs.mkdirSync(path.dirname(absoluteSignaturePath), { recursive: true });
    fs.writeFileSync(absoluteSignaturePath, `${signatureBase64}\n`, "utf8");
    const signatureResult = spawnSync(process.execPath, [
      externalApprovalSignatureAttacher,
      delivery,
      initializerWorkspace,
      approver.keyId,
      new Date(Date.now() + index).toISOString(),
      relativeSignaturePath,
      "--trust-store",
      "trusted-approvers.json",
      "--expected-manifest-sha256",
      sha256(initializedManifestPath),
      "--expected-trust-store-sha256",
      temporaryTrustStoreSha256,
      "--confirm",
      "ATTACH_EXTERNAL_APPROVAL_SIGNATURE",
      "--delivery-sidecar",
      zipDigest,
    ], { encoding: "utf8" });
    if (signatureResult.status !== 0) {
      fail(`external approval signature attacher failed: ${signatureResult.stderr}`);
    }
  }
  const approvedAudit = auditExternalAcceptance({
    root: delivery,
    manifestPath: path.relative(delivery, initializedManifestPath),
    trustStorePath: path.relative(delivery, temporaryTrustStorePath),
    expectedTrustStoreSha256: temporaryTrustStoreSha256,
    expectedRelease: initializedManifest.release,
  });
  if (approvedAudit.approval.status !== "market_ga_approved"
      || approvedAudit.approval.validSignatureCount !== 2
      || JSON.stringify(approvedAudit.approval.validRoles)
        !== JSON.stringify(["release_manager", "security_approver"])) {
    fail("external approval signature attacher dual-role validation failed");
  }
} finally {
  fs.rmSync(initializerWorkspace, { recursive: true, force: true });
}
const packagedRequiredChecks = JSON.parse(fs.readFileSync(
  mustFile("docs/operations/v9-external-acceptance-required-checks.json"),
  "utf8",
).replace(/^\uFEFF/, ""));
if (JSON.stringify(packagedRequiredChecks) !== JSON.stringify(EXTERNAL_ACCEPTANCE_REQUIRED_CHECKS)) {
  fail("external acceptance required-check contract drifted from the verifier");
}
if (!Array.isArray(dependencyAudit.lockfiles) || dependencyAudit.lockfiles.length < 3) {
  fail("dependency audit lockfile evidence is incomplete");
}

for (const artifact of manifest.artifacts ?? []) {
  const relative = artifact.path.replaceAll("\\", "/");
  const absolute = mustFile(relative);
  if (sha256(absolute) !== artifact.sha256) fail(`manifest hash mismatch ${relative}`);
  if (fs.statSync(absolute).size !== artifact.bytes) fail(`manifest size mismatch ${relative}`);
}

const checksumLines = fs.readFileSync(path.join(delivery, "SHA256SUMS.txt"), "utf8")
  .split(/\r?\n/)
  .map((line) => line.trim())
  .filter(Boolean);
for (const line of checksumLines) {
  const match = line.match(/^([A-F0-9]{64})\s+(.+)$/i);
  if (!match) fail(`invalid checksum line ${line}`);
  const absolute = mustFile(match[2].replaceAll("/", path.sep));
  if (sha256(absolute) !== match[1].toUpperCase()) fail(`checksum mismatch ${match[2]}`);
}

const forbiddenFilePattern = /(^|[\\/])(?:\.env|.*\.local|.*\.pem|.*\.key|.*\.p12)$/i;
const forbiddenDirectories = new Set(["node_modules", "__pycache__", ".git", ".temp"]);
for (const entry of fs.readdirSync(delivery, { recursive: true })) {
  const normalized = entry.replaceAll("\\", "/");
  const basename = path.posix.basename(normalized);
  if (forbiddenDirectories.has(basename)) fail(`development directory shipped: ${normalized}`);
  if (/^v9-ops-gateway\/test_.*\.py$/i.test(normalized)) fail(`gateway test fixture shipped: ${normalized}`);
  if (forbiddenFilePattern.test(normalized) && !basename.endsWith(".example")) fail(`secret-like file shipped: ${normalized}`);
}

const textFiles = [];
for (const entry of fs.readdirSync(delivery, { recursive: true })) {
  const absolute = path.join(delivery, entry);
  if (fs.statSync(absolute, { throwIfNoEntry: false })?.isFile()) textFiles.push(absolute);
}
for (const file of textFiles) {
  if (file.toLowerCase().endsWith(".apk")) continue;
  const content = fs.readFileSync(file);
  if (content.includes(Buffer.from("-----BEGIN PRIVATE KEY-----"))) fail(`private key material shipped: ${file}`);
  if (content.includes(Buffer.from("-----BEGIN RSA PRIVATE KEY-----"))) fail(`RSA private key material shipped: ${file}`);
}

const forbiddenDeliveryPrivacyMarkers = [
  ["developer account identifier", "59979"],
  ["developer Windows profile path", "C:\\Users\\59979"],
  ["developer Windows profile path", "C:/Users/59979"],
  ["developer Windows profile path", "D:\\Users\\59979"],
  ["developer Windows profile path", "D:/Users/59979"],
  ["full Air3 serial number", "YM00FCF3NW0031"],
  ["现场内网地址", "192.168.30.244"],
  ["现场 Wi-Fi 名称", "huafangzhilian"],
  ["developer temporary directory", "AppData\\Local\\Temp"],
];
for (const file of textFiles) {
  if (file.toLowerCase().endsWith(".apk")) continue;
  const content = fs.readFileSync(file);
  for (const [label, marker] of forbiddenDeliveryPrivacyMarkers) {
    if (content.includes(Buffer.from(marker, "utf8"))) {
      fail(`${label} shipped in ${path.relative(delivery, file).replaceAll("\\", "/")}`);
    }
  }
}

const expectedZipHash = fs.readFileSync(zipDigest, "utf8").trim().split(/\s+/)[0]?.toUpperCase();
if (expectedZipHash !== sha256(zip)) fail("delivery zip hash mismatch");
verifyDeliveryArchiveExtracts();

console.log(`V9 formal delivery package validation passed (${manifest.artifacts.length} manifest artifacts, ${checksumLines.length} checksummed files).`);
console.log(`ZIP_SHA256=${expectedZipHash}`);
console.log("NOTICE=APK vendor SDK PEM material is retained for supplier security review; no production credentials are packaged.");
