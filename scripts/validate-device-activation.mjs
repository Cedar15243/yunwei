import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";

const migrationPath = new URL(
  "../supabase/migrations/202608030003_device_activation_codes.sql",
  import.meta.url,
);
const activationPath = new URL(
  "../supabase/functions/ops-glasses/device-activation.ts",
  import.meta.url,
);
const managementPath = new URL(
  "../supabase/functions/ops-glasses/management.ts",
  import.meta.url,
);
const indexPath = new URL(
  "../supabase/functions/ops-glasses/index.ts",
  import.meta.url,
);
const androidClientPath = new URL(
  "../air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/runtime/DeviceActivationClient.java",
  import.meta.url,
);
const androidIdentityPath = new URL(
  "../air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/runtime/DeviceInstallationIdentity.java",
  import.meta.url,
);
const androidPolicyPath = new URL(
  "../air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/runtime/DeviceActivationUiPolicy.java",
  import.meta.url,
);
const androidActivityPath = new URL(
  "../air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java",
  import.meta.url,
);
const androidBuildPath = new URL(
  "../air3-dingdang-expert-integrated-app/app/build.gradle",
  import.meta.url,
);

assert.ok(existsSync(migrationPath), "device activation migration is missing");
const sql = readFileSync(migrationPath, "utf8");
const activation = readFileSync(activationPath, "utf8");
const management = readFileSync(managementPath, "utf8");
const index = readFileSync(indexPath, "utf8");
const androidClient = readFileSync(androidClientPath, "utf8");
const androidIdentity = readFileSync(androidIdentityPath, "utf8");
const androidPolicy = readFileSync(androidPolicyPath, "utf8");
const androidActivity = readFileSync(androidActivityPath, "utf8");
const androidBuild = readFileSync(androidBuildPath, "utf8");

for (const contract of [
  /create table public\.device_activation_codes\b/i,
  /code_hash text not null unique/i,
  /status text not null default 'pending'/i,
  /expires_at timestamptz not null/i,
  /consumed_device_instance_hash text/i,
  /create or replace function public\.issue_device_activation_code/i,
  /expires_at >= now\(\) \+ interval '5 minutes'/i,
  /expires_at <= now\(\) \+ interval '15 minutes'/i,
  /create or replace function public\.redeem_device_activation_code/i,
  /for update/i,
  /activation_status text/i,
  /claimed_package_name <> 'com\.codex\.air3nativecamera\.dingdangexpert\.v9'/i,
  /binding\.status = 'active'/i,
  /profile\.status = 'active'/i,
  /set status = 'used'/i,
  /insert into public\.glasses_device_tokens/i,
  /new_bootstrap_expires_at > now\(\)/i,
  /new_bootstrap_expires_at > now\(\) \+ interval '90 days'/i,
  /enable row level security/i,
  /grant execute on function public\.issue_device_activation_code/i,
  /grant execute on function public\.redeem_device_activation_code/i,
]) {
  assert.match(sql, contract);
}

for (const marker of [
  "routeDeviceActivation",
  "createDeviceActivationGateway",
  "deviceActivationConfigurationError",
  "redeem_device_activation_code",
  "Cache-Control",
  "no-store",
]) {
  assert.ok(activation.includes(marker), `device activation route missing ${marker}`);
}

for (const marker of [
  "management\\/devices\\/",
  "activation-codes",
  "ISSUE_DEVICE_ACTIVATION",
  "issue_device_activation_code",
]) {
  assert.ok(management.includes(marker), `management activation route missing ${marker}`);
}

for (const marker of [
  'from "./device-activation.ts"',
  'path === "/device-activation/redeem"',
  "routeDeviceActivation(",
  "createDeviceActivationGateway(",
  "V9_DEVICE_ACTIVATION_BACKEND_BASE_URL",
  "V9_DEVICE_ACTIVATION_POLICY_VERSION",
  'stage: "device_activation"',
  'recoverableAction: "configure_device_activation_backend"',
]) {
  assert.ok(index.includes(marker), `index activation wiring missing ${marker}`);
}

assert.doesNotMatch(
  index,
  /V9_DEVICE_ACTIVATION_BACKEND_BASE_URL[\s\S]{0,120}\?\?\s*"https:\/\//,
  "device activation backend must not use a hard-coded production fallback",
);
assert.doesNotMatch(
  index,
  /V9_DEVICE_ACTIVATION_POLICY_VERSION[\s\S]{0,120}\?\?\s*"v9-production-1"/,
  "device activation policy must not use a hard-coded production fallback",
);

for (const marker of [
  "device_activation_response_cacheable",
  "device_activation_response_forbidden_field",
  "credentialStore.save(record)",
]) {
  assert.ok(androidClient.includes(marker), `Android activation client missing ${marker}`);
}

for (const marker of ["getOrCreate()", "UUID.randomUUID()", "commit()"] ) {
  assert.ok(androidIdentity.includes(marker), `Android installation identity missing ${marker}`);
}

for (const marker of ["MDM_MANAGED", "LOCAL_ACTIVE", "ACTIVATION_REQUIRED"] ) {
  assert.ok(androidPolicy.includes(marker), `Android activation policy missing ${marker}`);
}

for (const marker of [
  "DEVICE_ACTIVATION_BASE_URL",
  "device_activation_enter_code",
  "device_activation_confirm_clear",
  "device_activation_scan_qr",
  "device_activation_confirm_qr",
  "openDeviceActivationCodeDialog",
  "submitDeviceActivation",
  "handleDeviceActivationQrPhoto",
  "DeviceActivationQrDecoder",
]) {
  assert.ok(androidActivity.includes(marker), `Android activation settings missing ${marker}`);
}

for (const marker of [
  "V9_DEVICE_ACTIVATION_BASE_URL",
  "DEVICE_ACTIVATION_BASE_URL",
]) {
  assert.ok(androidBuild.includes(marker), `Android activation build contract missing ${marker}`);
}

console.log("device activation schema and routing contract passed");
