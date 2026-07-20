import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const baseline626 = new Map([
  ["scripts/build-dingdang-ai-expert-follow-apk.ps1", "9292d2b7227f4bcabcb2683a3eb1d8a50ac6735e"],
  ["scripts/install-and-verify-dingdang-ai-expert-follow.ps1", "fd0033de90590fa85463bbc915cf556e6700abc9"],
  ["scripts/build-install-dingdang-ai-expert-follow-direct-apk.ps1", "562efbf6095efb886641161861fbc6d9e92b56a3"],
  ["air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java", "931a0b853d48c26be6c0f6805258fb5f897e9517"],
]);

for (const [relativePath, expectedHash] of baseline626) {
  const actualHash = execFileSync("git", ["hash-object", relativePath], {
    cwd: root,
    encoding: "utf8",
  }).trim();
  assert.equal(actualHash, expectedHash, `626 baseline changed: ${relativePath}`);
}

const buildPath = path.join(root, "air3-dingdang-expert-integrated-app", "app", "build.gradle");
assert.ok(existsSync(buildPath), "integrated preview app/build.gradle is missing");
const build = readFileSync(buildPath, "utf8");
assert.match(build, /previewApplicationId[^\n]+"com\.codex\.air3nativecamera\.dingdangexpert\.follow\.preview"/);
assert.match(build, /previewVersionCode[^\n]+"627"/);
assert.match(build, /previewVersionName[^\n]+"6\.2\.7-expert-preview"/);
assert.match(build, /applicationId previewApplicationId/);
assert.doesNotMatch(build, /TRTC_SDK_SECRET/);

const buildScriptPath = path.join(root, "scripts", "build-dingdang-integrated-preview.ps1");
const installScriptPath = path.join(root, "scripts", "install-and-verify-dingdang-integrated-preview.ps1");
assert.ok(existsSync(buildScriptPath), "integrated preview build script is missing");
assert.ok(existsSync(installScriptPath), "integrated preview install script is missing");

const buildScript = readFileSync(buildScriptPath, "utf8");
const installScript = readFileSync(installScriptPath, "utf8");
assert.match(buildScript, /collabServerUrl/);
assert.match(buildScript, /ApplicationId must remain in the protected integrated preview namespace/);
assert.match(buildScript, /assembleDebug/);
assert.match(installScript, /com\.codex\.air3nativecamera\.dingdangexpert\.follow\.preview/);
assert.match(installScript, /com\.codex\.air3nativecamera\.dingdangexpert\.follow/);
assert.match(installScript, /com\.codex\.air3nativecamera\.dingdangexpert\.collab/);
assert.match(installScript, /versionCode=626/);
assert.match(installScript, /versionCode=801/);
assert.match(installScript, /versionCode=627/);
assert.doesNotMatch(installScript, /\buninstall\b/i);

console.log("Integrated preview isolation validation passed.");
