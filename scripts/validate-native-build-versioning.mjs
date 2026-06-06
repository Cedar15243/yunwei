import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const buildScriptPath = path.join(root, "air3-native-camera-test/build-native-apk.ps1");
const versionLogPath = path.join(root, "docs/air3-v2-version-log.md");

const buildScript = fs.readFileSync(buildScriptPath, "utf8");
const versionLog = fs.readFileSync(versionLogPath, "utf8");

function mustInclude(source, marker, message = marker) {
  if (!source.includes(marker)) {
    throw new Error(`native build versioning missing marker: ${message}`);
  }
}

function mustNotInclude(source, marker, message = marker) {
  if (source.includes(marker)) {
    throw new Error(`native build versioning keeps forbidden marker: ${message}`);
  }
}

for (const marker of [
  "$versionCode = if ($env:AIR3_APK_VERSION_CODE) { [int]$env:AIR3_APK_VERSION_CODE } else { 208 }",
  '$versionName = if ($env:AIR3_APK_VERSION_NAME) { $env:AIR3_APK_VERSION_NAME } else { "2.0.8" }',
  "$gitOutput = & git -C $repoRoot rev-parse --short HEAD",
  "--version-code $versionCode",
  "--version-name $versionName",
  'Air3NativeCameraTest-v$versionName-$gitSha.apk',
  "Copy-Item -LiteralPath $signed -Destination $versionedSigned -Force",
  'Write-Output "VersionCode=$versionCode VersionName=$versionName Git=$gitSha"',
]) {
  mustInclude(buildScript, marker);
}

mustNotInclude(buildScript, '--version-code 1', "old versionCode 1 must not remain");
mustNotInclude(buildScript, '--version-name "1.01"', "old versionName 1.01 must not remain");

for (const marker of [
  "v2.0.8-task8-versioned-build",
  "`208`",
  "`2.0.8`",
  "Air3NativeCameraTest-v<versionName>-<gitSha>.apk",
  "AIR3_APK_VERSION_CODE",
  "AIR3_APK_VERSION_NAME",
]) {
  mustInclude(versionLog, marker);
}

console.log("Native build versioning validation passed.");
