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

const requiredPaths = [
  "expert-collab-server/package.json",
  "expert-collab-web/package.json",
  "air3-expert-collab-app/settings.gradle",
  "scripts/build-dingdang-expert-collab-apk.ps1",
];

for (const relativePath of requiredPaths) {
  assert.ok(existsSync(path.join(root, relativePath)), `missing expert collaboration file: ${relativePath}`);
}

const buildScript = readFileSync(path.join(root, "scripts/build-dingdang-expert-collab-apk.ps1"), "utf8");
assert.match(buildScript, /com\.codex\.air3nativecamera\.dingdangexpert\.collab/);
assert.match(buildScript, /801/);
assert.match(buildScript, /8\.0\.1-expert-collab-demo/);

console.log("Expert collaboration isolation validation passed.");
