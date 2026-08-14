import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const root = new URL("../", import.meta.url);
const main = readFileSync(new URL(
  "air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java",
  root,
), "utf8");
const transport = readFileSync(new URL(
  "air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/HttpTaskSyncTransport.java",
  root,
), "utf8");
const debugWakeReader = readFileSync(new URL(
  "air3-dingdang-expert-integrated-app/app/src/debug/java/com/codex/air3nativecamera/runtime/DebugPrivateWakeProvisioningReader.java",
  root,
), "utf8");
const releaseWakeReader = readFileSync(new URL(
  "air3-dingdang-expert-integrated-app/app/src/release/java/com/codex/air3nativecamera/runtime/DebugPrivateWakeProvisioningReader.java",
  root,
), "utf8");
const appBuild = readFileSync(new URL(
  "air3-dingdang-expert-integrated-app/app/build.gradle",
  root,
), "utf8");
const sessionManager = readFileSync(new URL(
  "air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/DeviceSessionManager.java",
  root,
), "utf8");
const sessionIssuer = readFileSync(new URL(
  "air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/sync/HttpDeviceSessionIssuer.java",
  root,
), "utf8");
const runtimeConfiguration = readFileSync(new URL(
  "air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/runtime/ManagedRuntimeConfiguration.java",
  root,
), "utf8");

for (const contract of [
  /deviceSessionManager\.prewarm\(\)/,
  /new HttpTaskSyncTransport\(deviceSyncConfiguration, tokenProvider\)/,
  /new BackendChatClient\(\s*runtimeConfiguration\.backendBaseUrl\(\),\s*deviceSessionManager\s*\)/,
  /new WebSocketRealtimeAsrSession\(backendAsrUrl\(sessionId\), backendAuthorization/,
  /backendAuthorization\.apply\(connection\)/,
  /backendAuthorization\.bearerToken\(\)/,
  /DebugPrivateWakeProvisioning\.mergeAfterManaged\(/,
  /DebugPrivateWakeProvisioningReader\.load\(this, true\)/,
]) {
  assert.match(main, contract);
}

assert.match(transport, /accessTokenProvider\.accessToken\(\)/);
assert.doesNotMatch(transport, /bootstrapCredential\(\)|deviceToken\(\)/);
assert.match(debugWakeReader, /DebugPrivateWakeProvisioning\.FILE_NAME/);
assert.match(debugWakeReader, /DebugPrivateWakeProvisioning\.parse/);
assert.match(releaseWakeReader, /Collections\.emptyMap\(\)/);
assert.doesNotMatch(releaseWakeReader, /Files\.|DebugPrivateWakeProvisioning\.FILE_NAME/);
assert.match(appBuild, /def v9PackageRequested =/);
assert.match(appBuild, /if \(v9PackageRequested && !secureRuntime\)/);
assert.match(appBuild, /V9 builds require secureRuntime/);
assert.match(appBuild, /if \(debugPrivateProvisioningEnabled && !secureRuntime\)/);
assert.match(main, /runtimeConfiguration\.usesLocalActivation\(\)/);
assert.match(main, /store\.clearIfCurrent\(record\.bootstrapCredential\(\)\)/);
assert.match(sessionManager, /interface BootstrapCredentialProvider/);
assert.match(sessionManager, /device_session_credential_changed/);
assert.match(sessionManager, /isAuthoritativeRevocation\(\)/);
assert.match(sessionIssuer, /device_binding_revoked/);
assert.match(sessionIssuer, /AUTHORITATIVE_REVOCATION_CODES\.contains\(errorCode\)/);
assert.match(runtimeConfiguration, /boolean usesLocalActivation\(\)/);
assert.doesNotMatch(
  main,
  /gpt-4\.1-mini/,
  "V9 Android runtime must not carry a legacy direct-model fallback",
);
assert.match(main, /DIRECT_GPT_MODEL missing/);

console.log("Android short-lived device session contract passed");
