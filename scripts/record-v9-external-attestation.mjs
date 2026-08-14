import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  readExternalAcceptanceReleaseBinding,
} from "./init-v9-external-acceptance.mjs";
import {
  MARKET_GA_GATE_IDS,
  auditExternalAcceptance,
} from "./v9-external-acceptance.mjs";

export const RECORD_EXTERNAL_ACCEPTANCE_CONFIRMATION = "RECORD_EXTERNAL_ACCEPTANCE";

function readJsonBuffer(buffer) {
  return JSON.parse(buffer.toString("utf8").replace(/^\uFEFF/, ""));
}

function jsonBuffer(value) {
  return Buffer.from(`${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function sha256(buffer) {
  return crypto.createHash("sha256").update(buffer).digest("hex").toUpperCase();
}

function normalizedSha256(value) {
  return typeof value === "string" ? value.trim().toUpperCase() : "";
}

function releaseMatches(actual, expected) {
  return ["applicationId", "versionCode", "versionName", "generatedAt"]
    .every((field) => actual?.[field] === expected?.[field])
    && normalizedSha256(actual?.apkSha256) === normalizedSha256(expected?.apkSha256)
    && normalizedSha256(actual?.deliveryZipSha256)
      === normalizedSha256(expected?.deliveryZipSha256);
}

function isWithin(base, candidate) {
  const normalizedBase = path.resolve(base).toLowerCase();
  const normalizedCandidate = path.resolve(candidate).toLowerCase();
  return normalizedCandidate.startsWith(`${normalizedBase}${path.sep}`);
}

function validateAttestationPath(workspaceDirectory, attestationPath) {
  if (typeof attestationPath !== "string" || !attestationPath.trim()
      || path.isAbsolute(attestationPath)) {
    throw new Error("Attestation path must be relative to the external acceptance workspace");
  }
  const absolutePath = path.resolve(workspaceDirectory, attestationPath);
  if (!isWithin(workspaceDirectory, absolutePath)) {
    throw new Error("Attestation path escapes the external acceptance workspace");
  }
  const stat = fs.statSync(absolutePath, { throwIfNoEntry: false });
  if (!stat?.isFile()) {
    throw new Error(`Attestation path is not a file: ${attestationPath}`);
  }
  const realPath = fs.realpathSync(absolutePath);
  if (!isWithin(workspaceDirectory, realPath)) {
    throw new Error("Attestation path resolves outside the external acceptance workspace");
  }
  return realPath;
}

export function recordExternalAcceptanceAttestation({
  repositoryRoot,
  workspaceDirectory,
  gateId,
  attestationPath,
  approver,
  expectedManifestSha256,
  confirmation,
  deliverySidecarPath,
  now = new Date(),
}) {
  if (confirmation !== RECORD_EXTERNAL_ACCEPTANCE_CONFIRMATION) {
    throw new Error(`Confirmation must be ${RECORD_EXTERNAL_ACCEPTANCE_CONFIRMATION}`);
  }
  if (!MARKET_GA_GATE_IDS.includes(gateId)) {
    throw new Error(`Unknown external acceptance gate: ${gateId}`);
  }
  const normalizedApprover = typeof approver === "string" ? approver.trim() : "";
  if (!normalizedApprover || normalizedApprover.length > 200) {
    throw new Error("Approver is required and must not exceed 200 characters");
  }
  const expectedSha256 = normalizedSha256(expectedManifestSha256);
  if (!/^[A-F0-9]{64}$/.test(expectedSha256)) {
    throw new Error("Expected manifest SHA-256 is invalid");
  }

  const absoluteRepositoryRoot = path.resolve(repositoryRoot);
  const absoluteWorkspaceDirectory = path.resolve(
    absoluteRepositoryRoot,
    workspaceDirectory,
  );
  const manifestPath = path.join(absoluteWorkspaceDirectory, "manifest.json");
  const manifestBuffer = fs.readFileSync(manifestPath);
  const previousManifestSha256 = sha256(manifestBuffer);
  if (previousManifestSha256 !== expectedSha256) {
    throw new Error(
      `Manifest SHA-256 mismatch: expected ${expectedSha256}, actual ${previousManifestSha256}`,
    );
  }
  const manifest = readJsonBuffer(manifestBuffer);
  if (Array.isArray(manifest.signatures) && manifest.signatures.length > 0) {
    throw new Error("Signed manifest cannot be modified; record gates before approval signing");
  }
  if (manifest.gates?.[gateId]) {
    throw new Error(`External acceptance gate is already recorded: ${gateId}`);
  }

  const expectedRelease = readExternalAcceptanceReleaseBinding(
    absoluteRepositoryRoot,
    deliverySidecarPath,
  );
  if (!releaseMatches(manifest.release, expectedRelease)) {
    throw new Error("External acceptance manifest release binding mismatch");
  }

  const realAttestationPath = validateAttestationPath(
    absoluteWorkspaceDirectory,
    attestationPath,
  );
  const attestationBuffer = fs.readFileSync(realAttestationPath);
  const attestation = readJsonBuffer(attestationBuffer);
  const relativeAttestationPath = path.relative(
    absoluteWorkspaceDirectory,
    realAttestationPath,
  ).replaceAll("\\", "/");
  const descriptor = {
    path: relativeAttestationPath,
    sha256: sha256(attestationBuffer),
    bytes: attestationBuffer.length,
  };
  const candidate = {
    ...manifest,
    gates: {
      ...(manifest.gates ?? {}),
      [gateId]: {
        status: "passed",
        environment: "production",
        completedAt: attestation.executedAt,
        approver: normalizedApprover,
        attestation: descriptor,
        evidence: [descriptor],
      },
    },
    signatures: [],
  };
  const temporaryManifestPath = path.join(
    absoluteWorkspaceDirectory,
    `.manifest.${process.pid}.${crypto.randomUUID()}.json`,
  );
  const candidateBuffer = jsonBuffer(candidate);
  fs.writeFileSync(temporaryManifestPath, candidateBuffer, { flag: "wx" });
  try {
    const report = auditExternalAcceptance({
      root: absoluteRepositoryRoot,
      manifestPath: path.relative(absoluteRepositoryRoot, temporaryManifestPath),
      expectedRelease,
      now,
    });
    if (report.gates?.[gateId]?.status !== "passed" || report.issues.length > 0) {
      const codes = report.issues.map((item) => item.code).join(",") || "gate_invalid";
      throw new Error(`External attestation validation failed: ${codes}`);
    }
    const latestManifestSha256 = sha256(fs.readFileSync(manifestPath));
    if (latestManifestSha256 !== expectedSha256) {
      throw new Error(
        `Manifest SHA-256 mismatch: expected ${expectedSha256}, actual ${latestManifestSha256}`,
      );
    }
    fs.renameSync(temporaryManifestPath, manifestPath);
  } finally {
    fs.rmSync(temporaryManifestPath, { force: true });
  }

  return {
    gateId,
    manifestPath,
    previousManifestSha256,
    manifestSha256: sha256(candidateBuffer),
    attestation: descriptor,
  };
}

function parseCliArguments(args) {
  const positional = [];
  const options = {};
  const optionNames = new Map([
    ["--approver", "approver"],
    ["--expected-manifest-sha256", "expectedManifestSha256"],
    ["--confirm", "confirmation"],
    ["--delivery-sidecar", "deliverySidecarPath"],
  ]);
  for (let index = 0; index < args.length; index += 1) {
    const value = args[index];
    const optionName = optionNames.get(value);
    if (optionName) {
      const optionValue = args[index + 1];
      if (!optionValue) throw new Error(`${value} requires a value`);
      options[optionName] = optionValue;
      index += 1;
      continue;
    }
    if (value.startsWith("--")) throw new Error(`Unknown option: ${value}`);
    positional.push(value);
  }
  return { positional, options };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { positional, options } = parseCliArguments(process.argv.slice(2));
  if (positional.length < 4) {
    throw new Error(
      "Usage: record-v9-external-attestation.mjs <root> <workspace> <gateId> <attestationPath> --approver <value> --expected-manifest-sha256 <sha256> --confirm RECORD_EXTERNAL_ACCEPTANCE",
    );
  }
  const result = recordExternalAcceptanceAttestation({
    repositoryRoot: positional[0],
    workspaceDirectory: positional[1],
    gateId: positional[2],
    attestationPath: positional[3],
    ...options,
  });
  console.log(JSON.stringify(result, null, 2));
}
