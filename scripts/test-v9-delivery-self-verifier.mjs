import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = process.cwd();
const verifier = path.join(root, "scripts", "verify-v9-delivery.ps1");
if (!fs.statSync(verifier, { throwIfNoEntry: false })?.isFile()) {
  throw new Error("formal delivery self-verifier script is missing");
}

function sha256(filePath) {
  return crypto.createHash("sha256").update(fs.readFileSync(filePath)).digest("hex").toUpperCase();
}

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function runVerifier(deliveryRoot) {
  return spawnSync("powershell", [
    "-NoProfile",
    "-NonInteractive",
    "-ExecutionPolicy",
    "Bypass",
    "-File",
    verifier,
    "-DeliveryRoot",
    deliveryRoot,
  ], { encoding: "utf8", maxBuffer: 10 * 1024 * 1024 });
}

const workspace = fs.mkdtempSync(path.join(os.tmpdir(), "v9-self-verifier-test-"));
try {
  const apkRelative = "android/DingdangAI-V9-9.0.0-release.apk";
  const releaseManifestRelative = "android/release-manifest.json";
  const payloadRelative = "docs/payload.txt";
  const soakRelative = "verification/air3-soak/summary.json";
  const dastJsonRelative = "security/dast/v9-zap-baseline.json";
  const dastHtmlRelative = "security/dast/v9-zap-baseline.html";
  const dastReadmeRelative = "security/dast/README.md";
  const apkPath = path.join(workspace, apkRelative);
  const releaseManifestPath = path.join(workspace, releaseManifestRelative);
  const payloadPath = path.join(workspace, payloadRelative);
  const soakPath = path.join(workspace, soakRelative);
  const dastJsonPath = path.join(workspace, dastJsonRelative);
  const dastHtmlPath = path.join(workspace, dastHtmlRelative);
  const dastReadmePath = path.join(workspace, dastReadmeRelative);
  fs.mkdirSync(path.dirname(apkPath), { recursive: true });
  fs.mkdirSync(path.dirname(payloadPath), { recursive: true });
  fs.writeFileSync(apkPath, Buffer.from("fixture-apk"));
  fs.writeFileSync(payloadPath, "verified payload\n", "utf8");
  writeJson(releaseManifestPath, {
    applicationId: "com.codex.air3nativecamera.dingdangexpert.v9",
    versionCode: 900000,
    versionName: "9.0.0",
    sha256: sha256(apkPath),
    aiModel: "qwen3-vl-plus",
    asrModel: "fun-asr-realtime",
    voiceprintService: "s1aa729d0",
    secureRuntime: true,
    directGptEnabled: false,
    builtAt: "2026-08-08T23:14:51.413Z",
  });

  const validSoak = {
    schemaVersion: 1,
    evidenceType: "local_air3_hardware_soak",
    capturedAt: "2026-08-09T03:07:15.209Z",
    release: {
      applicationId: "com.codex.air3nativecamera.dingdangexpert.v9",
      versionCode: 900000,
      versionName: "9.0.0",
      apkSha256: sha256(apkPath),
    },
    device: {
      model: "IMA301",
      serialMasked: "YM00...0031",
    },
    measurements: {
      durationSecondsRequested: 1200,
      durationSecondsObserved: 1251,
      sampleCount: 41,
      cameraCyclesRequested: 3,
      cameraCyclesPassed: true,
      maxBatteryTemperatureC: 32,
      maxThermalStatus: 0,
      thermalSensorPeaksC: { cpu: 51.2, gpu: 48.9, skin: 48.186 },
      thermalSensorSampleCount: 41,
      baselinePssKb: 81645,
      maxPssKb: 106810,
      pssDeltaKb: 25165,
      finalJankPercent: 1.12,
      externalPowerConnected: true,
      powerMeasurementValid: false,
      cameraLeak: false,
      audioLeak: false,
      crashBufferEmpty: true,
      anrObserved: false,
      foregroundFinal: true,
      protectedV8Present: true,
      protectedV8Unchanged: true,
      passed: true,
    },
    limitations: [
      "usb_powered_power_measurement_invalid",
      "managed_services_unavailable",
      "production_voice_voiceprint_not_accepted",
    ],
  };
  writeJson(soakPath, validSoak);
  writeJson(dastJsonPath, {
    "@programName": "ZAP",
    "@version": "2.17.0",
    site: [{
      "@name": "https://bb.chinacedar.top:2305",
      alerts: [{
        alert: "Re-examine Cache-control Directives",
        riskcode: "0",
        instances: [{ uri: "https://bb.chinacedar.top:2305/v9-ops" }],
      }],
    }],
  });
  fs.writeFileSync(dastHtmlPath, "<!doctype html><title>V9 DAST</title>\n", "utf8");
  fs.writeFileSync(
    dastReadmePath,
    "development-team authorized passive DAST; third-party penetration remains pending.\n",
    "utf8",
  );

  const artifactPaths = [
    apkRelative,
    releaseManifestRelative,
    payloadRelative,
    soakRelative,
    dastJsonRelative,
    dastHtmlRelative,
    dastReadmeRelative,
  ];
  const deliveryManifestBase = {
    product: "Dingdang AI Operations Glasses",
    release: "V9.0.0",
    applicationId: "com.codex.air3nativecamera.dingdangexpert.v9",
    versionCode: 900000,
    secureRuntime: true,
    modelContract: {
      ai: "qwen3-vl-plus",
      asr: "fun-asr-realtime",
      wake: "previous-iflytek-aikit",
      voiceprint: "s1aa729d0",
    },
    externalPrerequisitesRequired: true,
    productionDeploymentStatus: "pending_external_credentials_and_hardware_acceptance",
  };

  function refreshIntegrity() {
    writeJson(path.join(workspace, "DELIVERY_MANIFEST.json"), {
      ...deliveryManifestBase,
      artifacts: artifactPaths.map((relative) => {
        const absolute = path.join(workspace, relative);
        return { path: relative, sha256: sha256(absolute), bytes: fs.statSync(absolute).size };
      }),
    });

    const checksummedPaths = [...artifactPaths, "DELIVERY_MANIFEST.json"].sort();
    fs.writeFileSync(
      path.join(workspace, "SHA256SUMS.txt"),
      `${checksummedPaths.map((relative) => `${sha256(path.join(workspace, relative))}  ${relative}`).join("\n")}\n`,
      "ascii",
    );
  }

  refreshIntegrity();

  const valid = runVerifier(workspace);
  if (valid.status !== 0) {
    throw new Error(`self-verifier rejected a valid delivery fixture:\n${valid.stdout}\n${valid.stderr}`);
  }

  function expectSemanticFailure(name, mutate, pattern) {
    const summary = structuredClone(validSoak);
    mutate(summary);
    writeJson(soakPath, summary);
    refreshIntegrity();
    const result = runVerifier(workspace);
    const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
    if (result.status === 0 || !pattern.test(output)) {
      throw new Error(`self-verifier did not reject ${name} correctly:\n${output}`);
    }
  }

  function expectDastFailure(name, mutate, pattern) {
    const report = JSON.parse(fs.readFileSync(dastJsonPath, "utf8"));
    mutate(report);
    writeJson(dastJsonPath, report);
    refreshIntegrity();
    const result = runVerifier(workspace);
    const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
    if (result.status === 0 || !pattern.test(output)) {
      throw new Error(`self-verifier did not reject ${name} correctly:\n${output}`);
    }
    writeJson(dastJsonPath, {
      "@programName": "ZAP",
      "@version": "2.17.0",
      site: [{
        "@name": "https://bb.chinacedar.top:2305",
        alerts: [{
          alert: "Re-examine Cache-control Directives",
          riskcode: "0",
          instances: [{ uri: "https://bb.chinacedar.top:2305/v9-ops" }],
        }],
      }],
    });
    refreshIntegrity();
  }

  expectSemanticFailure("short duration", (summary) => {
    summary.measurements.durationSecondsObserved = 1199;
  }, /duration/i);
  expectSemanticFailure("insufficient camera cycles", (summary) => {
    summary.measurements.cameraCyclesRequested = 2;
  }, /camera/i);
  expectSemanticFailure("thermal sample mismatch", (summary) => {
    summary.measurements.thermalSensorSampleCount = 40;
  }, /thermal sensor/i);
  expectSemanticFailure("PSS budget", (summary) => {
    summary.measurements.pssDeltaKb = 30001;
  }, /PSS/i);
  expectSemanticFailure("jank budget", (summary) => {
    summary.measurements.finalJankPercent = 10.01;
  }, /jank/i);
  expectSemanticFailure("power measurement claim", (summary) => {
    summary.measurements.powerMeasurementValid = true;
  }, /power measurement/i);
  expectSemanticFailure("camera leak", (summary) => {
    summary.measurements.cameraLeak = true;
  }, /camera leak/i);
  expectSemanticFailure("V8 change", (summary) => {
    summary.measurements.protectedV8Unchanged = false;
  }, /V8/i);
  expectSemanticFailure("release binding", (summary) => {
    summary.release.apkSha256 = "A".repeat(64);
  }, /release binding/i);
  expectSemanticFailure("missing limitation", (summary) => {
    summary.limitations = summary.limitations.filter(
      (item) => item !== "usb_powered_power_measurement_invalid",
    );
  }, /limitation/i);
  expectSemanticFailure("full Air3 serial privacy marker", (summary) => {
    summary.privateDeviceIdentifier = "YM00ABCD123456";
  }, /private device/i);
  expectDastFailure("DAST medium alert", (report) => {
    report.site[0].alerts[0].riskcode = "2";
  }, /DAST risk counts/i);
  expectDastFailure("DAST low alert", (report) => {
    report.site[0].alerts[0].riskcode = "1";
  }, /DAST risk counts/i);
  expectDastFailure("DAST path escape", (report) => {
    report.site[0].alerts[0].instances[0].uri = "https://bb.chinacedar.top:2305/health";
  }, /outside \/v9-ops/i);
  expectDastFailure("DAST missing instances", (report) => {
    report.site[0].alerts[0].instances = [];
  }, /instances.*empty/i);

  const privateAir3Serial = ["YM00", "FCF3", "NW00", "31"].join("");
  const verifierSource = fs.readFileSync(verifier, "utf8");
  if (verifierSource.includes(privateAir3Serial)) {
    throw new Error("formal delivery self-verifier source contains the full private Air3 serial");
  }

  writeJson(soakPath, validSoak);
  refreshIntegrity();

  fs.appendFileSync(payloadPath, "tampered\n", "utf8");
  const tampered = runVerifier(workspace);
  const tamperedOutput = `${tampered.stdout ?? ""}\n${tampered.stderr ?? ""}`;
  if (tampered.status === 0 || !/hash mismatch/i.test(tamperedOutput)) {
    throw new Error(`self-verifier did not reject tampered content correctly:\n${tamperedOutput}`);
  }
} finally {
  fs.rmSync(workspace, { recursive: true, force: true });
}

console.log("V9 formal delivery self-verifier tests passed.");
