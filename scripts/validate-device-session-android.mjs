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

for (const contract of [
  /deviceSessionManager\.prewarm\(\)/,
  /new HttpTaskSyncTransport\(deviceSyncConfiguration, tokenProvider\)/,
  /new BackendChatClient\(\s*runtimeConfiguration\.backendBaseUrl\(\),\s*deviceSessionManager\s*\)/,
  /new WebSocketRealtimeAsrSession\(backendAsrUrl\(sessionId\), backendAuthorization/,
  /backendAuthorization\.apply\(connection\)/,
  /backendAuthorization\.bearerToken\(\)/,
]) {
  assert.match(main, contract);
}

assert.match(transport, /accessTokenProvider\.accessToken\(\)/);
assert.doesNotMatch(transport, /bootstrapCredential\(\)|deviceToken\(\)/);

console.log("Android short-lived device session contract passed");
