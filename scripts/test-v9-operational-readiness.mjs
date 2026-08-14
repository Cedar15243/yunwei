import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { auditOperationalReadiness, scanStaticSecurity } from "./v9-operational-readiness.mjs";

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "v9-operational-readiness-"));
try {
  const source = path.join(temporary, "source");
  fs.mkdirSync(source, { recursive: true });
  fs.writeFileSync(path.join(source, "safe.py"), "print('ok')\n", "utf8");
  assert.deepEqual(scanStaticSecurity(source), []);

  fs.writeFileSync(path.join(source, "unsafe.py"), "eval(user_input)\n", "utf8");
  assert.deepEqual(scanStaticSecurity(source).map((finding) => finding.rule), ["python-dynamic-eval"]);

  const report = auditOperationalReadiness(path.resolve(import.meta.dirname, ".."));
  assert.equal(report.status, "local_controls_passed_external_gates_pending");
  assert.equal(report.marketGaStatus, "blocked");
  assert.equal(report.controls.backupRestore, "passed");
  assert.equal(report.controls.monitoringAlerting, "passed");
  assert.equal(report.controls.gatewayConfigDeployment, "passed");
  assert.ok(report.requiredFiles.includes("v9-ops-gateway/deploy/update-runtime.sh"));
  assert.ok(report.requiredFiles.includes("v9-ops-gateway/monitoring_probe.py"));
  assert.ok(report.requiredFiles.includes("v9-ops-gateway/deploy/install-monitoring.sh"));
  assert.ok(report.requiredFiles.includes("v9-ops-gateway/deploy/rollback-monitoring.sh"));
  assert.equal(report.controls.managementWebDeployment, "passed");
  assert.equal(report.controls.repositorySecurityScan, "passed");
  assert.equal(report.externalGates.productionRestoreDrill, "passed");
  assert.equal(report.externalGates.dastPenetrationTest, "pending");
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}

console.log("V9 operational readiness tests passed.");
