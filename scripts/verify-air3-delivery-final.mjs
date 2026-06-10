import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = process.cwd();
const deliveryPackage = "com.codex.air3nativecamera.delivery";
const deliveryVersionCode = "300";
const deliveryVersionName = "3.0.0-delivery";
const deliveryLabel = "叮当保AI";
const serial = "YM00FCF3NW0031";

const unityAndroid = "C:/Users/59979/UnityEditors/2022.3.62f3c1/Editor/Data/PlaybackEngines/AndroidPlayer";
const buildTools = path.join(unityAndroid, "SDK/build-tools/34.0.0");
const javaHome = path.join(unityAndroid, "OpenJDK");
const aapt2 = path.join(buildTools, "aapt2.exe");
const apksigner = path.join(buildTools, "apksigner.bat");
const apkPath = path.join(root, "air3-native-camera-test/build/Air3NativeCameraDelivery.apk");
const buildDir = path.join(root, "air3-native-camera-test/build");

const summary = {
  package: deliveryPackage,
  versionCode: deliveryVersionCode,
  versionName: deliveryVersionName,
  label: deliveryLabel,
  deviceSerial: serial,
  checks: [],
};

function note(name, status, detail = "") {
  summary.checks.push({ name, status, detail });
}

function run(name, command, args, options = {}) {
  const isBatch = process.platform === "win32" && /\.(bat|cmd)$/i.test(command);
  const needsCmdLookup = process.platform === "win32" &&
    !/[\\/]/.test(command) &&
    !/\.(exe|bat|cmd)$/i.test(command);
  const execCommand = isBatch || needsCmdLookup ? "cmd.exe" : command;
  const execArgs = isBatch || needsCmdLookup ? ["/d", "/s", "/c", command, ...args] : args;
  const result = spawnSync(execCommand, execArgs, {
    cwd: root,
    encoding: "utf8",
    shell: false,
    timeout: options.timeout ?? 180_000,
    env: { ...process.env, ...(options.env ?? {}) },
  });
  const output = `${result.stdout ?? ""}${result.stderr ?? ""}`.trim();
  if (result.status !== 0) {
    const errorText = result.error ? `\n${result.error.message}` : "";
    const detail = `${output}${errorText}`.trim();
    note(name, "failed", detail.slice(0, 1000));
    throw new Error(`${name} failed:\n${detail}`);
  }
  note(name, "passed", output.split("\n").slice(-5).join("\n"));
  return output;
}

function mustExist(filePath, name) {
  if (!fs.existsSync(filePath)) {
    throw new Error(`${name} missing: ${filePath}`);
  }
}

function mustInclude(source, marker, name) {
  if (!source.includes(marker)) {
    throw new Error(`${name} missing marker: ${marker}`);
  }
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

mustExist(apkPath, "delivery APK");
mustExist(aapt2, "aapt2");
mustExist(apksigner, "apksigner");

run("validate:native-hud", "npm", ["run", "validate:native-hud"]);
run("validate:native-build", "npm", ["run", "validate:native-build"]);
run("validate:supabase", "npm", ["run", "validate:supabase"]);
run("validate:ai-brain", "npm", ["run", "validate:ai-brain"]);
run("deno check ops-glasses", "deno", [
  "check",
  "--config",
  "supabase/functions/ops-glasses/deno.json",
  "supabase/functions/ops-glasses/index.ts",
]);

const badging = run("delivery APK badging", aapt2, ["dump", "badging", apkPath]);
mustInclude(badging, `package: name='${deliveryPackage}'`, "badging");
mustInclude(badging, `versionCode='${deliveryVersionCode}'`, "badging");
mustInclude(badging, `versionName='${deliveryVersionName}'`, "badging");
mustInclude(badging, `application-label:'${deliveryLabel}'`, "badging");
mustInclude(badging, "targetSdkVersion:'34'", "badging");

const versionedApks = fs
  .readdirSync(buildDir)
  .filter((name) => /^Air3NativeCameraDelivery-v3\.0\.0-delivery-.+\.apk$/.test(name));
if (versionedApks.length === 0) {
  throw new Error("versioned delivery APK missing");
}
note("versioned delivery APK", "passed", versionedApks.join(", "));

run("delivery APK signature", apksigner, ["verify", "--verbose", apkPath], {
  env: {
    JAVA_HOME: javaHome,
    PATH: `${path.join(javaHome, "bin")}${path.delimiter}${process.env.PATH ?? ""}`,
  },
});

run("install and verify delivery on Air3", "powershell", [
  "-ExecutionPolicy",
  "Bypass",
  "-File",
  "scripts/install-and-verify-air3-delivery.ps1",
], { timeout: 180_000 });

run("online AI contract", "node", ["scripts/verify-air3-v2-supabase.mjs"], { timeout: 180_000 });
const photo = readJson(path.join(root, "tmp/air3-v2-photo-response.json"));
const voice = readJson(path.join(root, "tmp/air3-v2-voice-response.json"));
if (photo.resultType === "network_error" || photo.feedbackCode === "ai_unavailable") {
  throw new Error(`online photo AI unavailable: ${photo.resultType}/${photo.feedbackCode}`);
}
if (voice.resultType === "network_error" || voice.feedbackCode === "ai_unavailable") {
  throw new Error(`online voice AI unavailable: ${voice.resultType}/${voice.feedbackCode}`);
}
if (photo.resultType !== "instruction" || photo.feedbackCode !== null) {
  throw new Error(`online photo did not return delivery instruction: ${photo.resultType}/${photo.feedbackCode}`);
}
if (!voice.transcript) {
  throw new Error("online voice transcript missing");
}
note("online AI contract result", "passed", `photo=${photo.displayTitle}; voice=${voice.displayTitle}`);

console.log(JSON.stringify(summary, null, 2));
