import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";

const root = path.join(import.meta.dirname, "..");
const packager = fs.readFileSync(
  path.join(root, "scripts/package-v9-formal-delivery.ps1"),
  "utf8",
).replace(/^\uFEFF/, "");
const validator = fs.readFileSync(
  path.join(root, "scripts/validate-v9-formal-delivery.mjs"),
  "utf8",
).replace(/^\uFEFF/, "");

const requiredGatewayDeployFiles = [
  "activate-production-overlay.sh",
  "backup.sh",
  "Caddyfile.snippet",
  "dingdang-v9-backup.service",
  "dingdang-v9-backup.timer",
  "dingdang-v9-gateway.env.example",
  "dingdang-v9-gateway.service",
  "dingdang-v9-monitor.env.example",
  "dingdang-v9-monitor.service",
  "dingdang-v9-monitor.timer",
  "dingdang-v9-voiceprint.env.example",
  "health-check.sh",
  "install.sh",
  "install-monitoring.sh",
  "merge_caddy.py",
  "merge_production_overlay.py",
  "restore-drill.sh",
  "rollback-production-overlay.sh",
  "rollback-monitoring.sh",
  "rollback.sh",
  "stage-production-overlay.sh",
  "update-runtime.sh",
];

assert.doesNotMatch(
  packager,
  /Copy-RequiredDirectory\s+\(Join-Path \$repoRoot "v9-ops-gateway\\deploy"\)/,
  "gateway deploy packaging must not recursively copy ignored production .local files",
);
for (const file of requiredGatewayDeployFiles) {
  assert.match(packager, new RegExp(file.replaceAll(".", "\\.")));
}
assert.match(packager, /v9-ops-gateway\\deploy\\monitoring/);
assert.match(packager, /\.local/);
assert.match(packager, /secret-like file/i);

assert.match(validator, /v9-ops-gateway\/deploy\/update-runtime\.sh/);
assert.doesNotMatch(
  validator,
  /gatewayOverlayActivate[\s\S]{0,1000}"rollback-production-overlay\.sh"/,
  "formal delivery validation must require self-contained activation recovery",
);
for (const marker of [
  "rollback_failed_activation",
  "interrupt_activation",
  "CHANGES_STARTED=1",
  'install_environment "$CURRENT/gateway.env" "$GATEWAY_ENV"',
  'install_environment "$CURRENT/voiceprint.env" "$VOICEPRINT_ENV"',
  'atomic_link "$CURRENT" "$CONFIG_ROOT/current"',
  'atomic_link "$PREVIOUS_TARGET" "$CONFIG_ROOT/previous"',
]) {
  assert.match(validator, new RegExp(marker.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
}
for (const marker of [
  "backup_restore.py",
  "RUNTIME_UPDATE_SHA256SUMS",
  "rollback_failed_update",
  'ln -sfn "$CURRENT" "$APP_ROOT/previous"',
  'wait_for_health "$PUBLIC_V9_BASE/ready"',
]) {
  assert.match(validator, new RegExp(marker.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
}

for (const marker of [
  "update-runtime.sh",
  "transition",
  "steady",
  "previous hash",
  "Supabase secrets/Edge",
]) {
  assert.match(packager, new RegExp(marker.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "i"));
}

console.log("V9 gateway formal-delivery contract tests passed.");
