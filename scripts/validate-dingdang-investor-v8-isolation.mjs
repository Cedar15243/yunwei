import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const buildPath = path.join(root, "scripts", "build-dingdang-investor-v8.ps1");
const installPath = path.join(root, "scripts", "install-and-verify-dingdang-investor-v8.ps1");

assert.ok(existsSync(buildPath), "investor v8 build script is missing");
assert.ok(existsSync(installPath), "investor v8 install script is missing");

const build = readFileSync(buildPath, "utf8");
const install = readFileSync(installPath, "utf8");
const packageId = "com.codex.air3nativecamera.dingdangexpert.follow.preview.investorv8";

assert.match(build, new RegExp(packageId.replaceAll(".", "\\.")));
assert.match(build, /VersionCode 800/);
assert.match(build, /8\.0\.0-investor-demo/);
assert.match(build, /https:\/\/bb\.chinacedar\.top:2305/);
assert.match(build, /FromBase64String/);
assert.match(build, /5Y\+u5b2TQUnov5Dnu7TkuJPlrrbCt\+ihjOS4mua8lOekug==/);
assert.match(build, /build-dingdang-integrated-preview\.ps1/);
assert.match(build, /\$buildParameters\s*=\s*@\{/);
assert.doesNotMatch(build, /\binstall\b/i);

assert.match(install, new RegExp(packageId.replaceAll(".", "\\.")));
assert.match(install, /Snapshot-ExistingPackages/);
assert.match(install, /Assert-ExistingPackagesUnchanged/);
assert.match(install, /\$buildParameters\s*=\s*@\{/);
assert.match(install, /aapt.*dump.*badging/is);
assert.match(install, /install.*-r/is);
assert.doesNotMatch(install, /\buninstall\b/i);

console.log("Investor v8 package isolation validation passed.");
