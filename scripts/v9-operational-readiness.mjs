import fs from "node:fs";
import path from "node:path";

const SOURCE_EXTENSIONS = new Set([".py", ".js", ".jsx", ".ts", ".tsx", ".java", ".kt", ".xml"]);
const SKIP_PARTS = new Set([".git", "node_modules", "__pycache__", "tmp", "output", "docs", "agent_memory"]);

const PRODUCTION_RESTORE_DRILL_EVIDENCE = "docs/operations/v9-production-restore-drill-2026-08-13.md";

function walkFiles(root) {
  const files = [];
  if (!fs.existsSync(root)) return files;
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    if (SKIP_PARTS.has(entry.name)) continue;
    const absolute = path.join(root, entry.name);
    if (entry.isDirectory()) files.push(...walkFiles(absolute));
    else if (SOURCE_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) files.push(absolute);
  }
  return files;
}

function shouldScan(relativePath) {
  const normalized = relativePath.replaceAll("\\", "/");
  if (/(^|\/)(test_|.*\.test\.)/.test(normalized)) return false;
  if (/(^|\/)(tests?|fixtures?)\//.test(normalized)) return false;
  return normalized.startsWith("v9-ops-gateway/") ||
    normalized.startsWith("ops-management-web/src/") ||
    normalized.startsWith("supabase/functions/ops-glasses/") ||
    normalized.startsWith("air3-dingdang-expert-integrated-app/app/src/main/");
}

export function scanStaticSecurity(root) {
  const findings = [];
  const repositoryMode = fs.existsSync(path.join(root, "v9-ops-gateway"));
  for (const file of walkFiles(root)) {
    const relativePath = path.relative(root, file).replaceAll("\\", "/");
    if (repositoryMode && !shouldScan(relativePath)) continue;
    if (!repositoryMode && !shouldScanFixture(relativePath)) continue;
    const content = fs.readFileSync(file, "utf8");
    const extension = path.extname(file).toLowerCase();
    const rules = [
      ["python-dynamic-eval", new Set([".py"]), /\beval\s*\(/, "dynamic eval is forbidden"],
      ["python-dynamic-exec", new Set([".py"]), /\bexec\s*\(/, "dynamic exec is forbidden"],
      ["python-unsafe-pickle", new Set([".py"]), /pickle\.(?:loads|load)\s*\(/, "unsafe pickle deserialization is forbidden"],
      ["python-shell-exec", new Set([".py"]), /shell\s*=\s*True/, "shell=True is forbidden"],
      ["web-raw-html", new Set([".js", ".jsx", ".ts", ".tsx"]), /dangerouslySetInnerHTML|new\s+Function\s*\(|\beval\s*\(/, "raw code or HTML execution is forbidden"],
      ["android-cleartext", new Set([".xml"]), /usesCleartextTraffic\s*=\s*[\"']true[\"']/i, "Android cleartext traffic is forbidden"],
      ["private-key-material", SOURCE_EXTENSIONS, /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/, "private key material is forbidden in source"],
    ];
    for (const [rule, extensions, pattern, message] of rules) {
      if (!extensions.has(extension)) continue;
      if (!pattern.test(content)) continue;
      findings.push({ rule, file: relativePath, message });
    }
  }
  return findings;
}

function shouldScanFixture(relativePath) {
  const normalized = relativePath.replaceAll("\\", "/");
  return !/(^|\/)(test_|.*\.test\.)/.test(normalized);
}

function hasFiles(root, files) {
  return files.every((relative) => fs.statSync(path.join(root, relative), { throwIfNoEntry: false })?.isFile());
}

export function auditOperationalReadiness(root) {
  const gatewayRuntimeRequired = [
    "v9-ops-gateway/backup_restore.py",
    "v9-ops-gateway/deploy/backup.sh",
    "v9-ops-gateway/deploy/restore-drill.sh",
    "v9-ops-gateway/deploy/dingdang-v9-backup.service",
    "v9-ops-gateway/deploy/dingdang-v9-backup.timer",
    "v9-ops-gateway/deploy/health-check.sh",
    "v9-ops-gateway/deploy/monitoring/prometheus-rules.yml",
    "v9-ops-gateway/monitoring_probe.py",
    "v9-ops-gateway/deploy/dingdang-v9-monitor.env.example",
    "v9-ops-gateway/deploy/dingdang-v9-monitor.service",
    "v9-ops-gateway/deploy/dingdang-v9-monitor.timer",
    "v9-ops-gateway/deploy/install-monitoring.sh",
    "v9-ops-gateway/deploy/rollback-monitoring.sh",
  ];
  const gatewayConfigDeploymentRequired = [
    "v9-ops-gateway/deploy/update-runtime.sh",
    "v9-ops-gateway/deploy/merge_production_overlay.py",
    "v9-ops-gateway/deploy/stage-production-overlay.sh",
    "v9-ops-gateway/deploy/activate-production-overlay.sh",
    "v9-ops-gateway/deploy/rollback-production-overlay.sh",
  ];
  const managementWebRequired = [
    "ops-management-web/deploy/install.sh",
    "ops-management-web/deploy/stage.sh",
    "ops-management-web/deploy/activate.sh",
    "ops-management-web/deploy/rollback.sh",
    "ops-management-web/deploy/health-check.sh",
    "ops-management-web/deploy/merge_caddy.py",
    "ops-management-web/deploy/Caddyfile.snippet.template",
    "ops-management-web/docker-compose.cloud.yml",
  ];
  const assuranceRequired = [
    "docs/operations/v9-backup-restore-runbook.md",
    "docs/operations/v9-observability-alerting-contract.md",
    PRODUCTION_RESTORE_DRILL_EVIDENCE,
    "docs/security/v9-sast-and-penetration-test-plan.md",
    "scripts/run-v9-dast-baseline.ps1",
  ];
  const required = [
    ...gatewayRuntimeRequired,
    ...gatewayConfigDeploymentRequired,
    ...managementWebRequired,
    ...assuranceRequired,
  ];
  const gateway = fs.readFileSync(path.join(root, "v9-ops-gateway", "gateway.py"), "utf8");
  const productionRestoreEvidence = fs.readFileSync(
    path.join(root, PRODUCTION_RESTORE_DRILL_EVIDENCE),
    "utf8",
  );
  const findings = scanStaticSecurity(root);
  const controls = {
    backupRestore: hasFiles(root, gatewayRuntimeRequired.slice(0, 5)) ? "passed" : "failed",
    monitoringAlerting: hasFiles(root, gatewayRuntimeRequired.slice(5)) && gateway.includes('parsed.path == "/ready"') ? "passed" : "failed",
    gatewayConfigDeployment: hasFiles(root, gatewayConfigDeploymentRequired) ? "passed" : "failed",
    managementWebDeployment: hasFiles(root, managementWebRequired) ? "passed" : "failed",
    repositorySecurityScan: findings.length === 0 ? "passed" : "failed",
  };
  const report = {
    status: Object.values(controls).every((value) => value === "passed")
      ? "local_controls_passed_external_gates_pending"
      : "local_controls_failed",
    marketGaStatus: "blocked",
    generatedAt: new Date().toISOString(),
    controls,
    staticSecurityFindings: findings,
    externalGates: {
      productionRestoreDrill: productionRestoreEvidence.includes("PRAGMA quick_check=ok")
        && productionRestoreEvidence.includes("备份 `3` 个文件，恢复 `3` 个文件")
        && productionRestoreEvidence.includes("公网 `/v9-ops/ready`")
        ? "passed"
        : "pending",
      monitoringNotificationDrill: "pending",
      dastPenetrationTest: "pending",
      supplierSdkSecurityApproval: "pending",
    },
    requiredFiles: required,
  };
  return report;
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname)) {
  const root = path.resolve(process.argv[2] || path.join(import.meta.dirname, ".."));
  const report = auditOperationalReadiness(root);
  console.log(JSON.stringify(report, null, 2));
  if (report.status !== "local_controls_passed_external_gates_pending") process.exitCode = 1;
}
