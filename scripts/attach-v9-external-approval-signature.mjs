import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  readExternalAcceptanceReleaseBinding,
} from "./init-v9-external-acceptance.mjs";
import {
  auditExternalAcceptance,
} from "./v9-external-acceptance.mjs";

export const ATTACH_EXTERNAL_APPROVAL_SIGNATURE_CONFIRMATION =
  "ATTACH_EXTERNAL_APPROVAL_SIGNATURE";

function readJsonBuffer(buffer) {
  return JSON.parse(buffer.toString("utf8").replace(/^\uFEFF/, ""));
}

function jsonBuffer(value) {
  return Buffer.from(`${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function sha256(buffer) {
  return crypto.createHash("sha256").update(buffer).digest("hex").toUpperCase();
}

function normalizeSha256(value) {
  return typeof value === "string" ? value.trim().toUpperCase() : "";
}

function releaseMatches(actual, expected) {
  return ["applicationId", "versionCode", "versionName", "generatedAt"]
    .every((field) => actual?.[field] === expected?.[field])
    && normalizeSha256(actual?.apkSha256) === normalizeSha256(expected?.apkSha256)
    && normalizeSha256(actual?.deliveryZipSha256)
      === normalizeSha256(expected?.deliveryZipSha256);
}

function isWithin(base, candidate) {
  const normalizedBase = path.resolve(base).toLowerCase();
  const normalizedCandidate = path.resolve(candidate).toLowerCase();
  return normalizedCandidate.startsWith(`${normalizedBase}${path.sep}`);
}

function resolveWorkspaceFile(workspaceDirectory, relativePath, label) {
  if (typeof relativePath !== "string" || !relativePath.trim() || path.isAbsolute(relativePath)) {
    throw new Error(`${label} path must be relative to the external acceptance workspace`);
  }
  const absolutePath = path.resolve(workspaceDirectory, relativePath);
  if (!isWithin(workspaceDirectory, absolutePath)) {
    throw new Error(`${label} path escapes the external acceptance workspace`);
  }
  const stat = fs.statSync(absolutePath, { throwIfNoEntry: false });
  if (!stat?.isFile()) throw new Error(`${label} path is not a file: ${relativePath}`);
  const realPath = fs.realpathSync(absolutePath);
  if (!isWithin(workspaceDirectory, realPath)) {
    throw new Error(`${label} path resolves outside the external acceptance workspace`);
  }
  return realPath;
}

export function attachExternalApprovalSignature({
  repositoryRoot,
  workspaceDirectory,
  keyId,
  signedAt,
  signaturePath,
  trustStorePath = "trusted-approvers.json",
  expectedManifestSha256,
  expectedTrustStoreSha256,
  confirmation,
  deliverySidecarPath,
  now = new Date(),
}) {
  if (confirmation !== ATTACH_EXTERNAL_APPROVAL_SIGNATURE_CONFIRMATION) {
    throw new Error(
      `Confirmation must be ${ATTACH_EXTERNAL_APPROVAL_SIGNATURE_CONFIRMATION}`,
    );
  }
  const normalizedKeyId = typeof keyId === "string" ? keyId.trim() : "";
  if (!/^[A-Za-z0-9._-]{1,80}$/.test(normalizedKeyId)) {
    throw new Error("Approval keyId is invalid");
  }
  const expectedManifestHash = normalizeSha256(expectedManifestSha256);
  if (!/^[A-F0-9]{64}$/.test(expectedManifestHash)) {
    throw new Error("Expected manifest SHA-256 is invalid");
  }
  const expectedTrustHash = normalizeSha256(expectedTrustStoreSha256);
  if (!/^[A-F0-9]{64}$/.test(expectedTrustHash)) {
    throw new Error("Expected trust store SHA-256 is invalid");
  }

  const absoluteRepositoryRoot = path.resolve(repositoryRoot);
  const absoluteWorkspaceDirectory = path.resolve(
    absoluteRepositoryRoot,
    workspaceDirectory,
  );
  const manifestPath = path.join(absoluteWorkspaceDirectory, "manifest.json");
  const manifestBuffer = fs.readFileSync(manifestPath);
  const previousManifestSha256 = sha256(manifestBuffer);
  if (previousManifestSha256 !== expectedManifestHash) {
    throw new Error(
      `Manifest SHA-256 mismatch: expected ${expectedManifestHash}, actual ${previousManifestSha256}`,
    );
  }
  const manifest = readJsonBuffer(manifestBuffer);
  const signatures = Array.isArray(manifest.signatures) ? manifest.signatures : [];
  if (signatures.some((item) => item?.keyId === normalizedKeyId)) {
    throw new Error(`Approval signature key is already attached: ${normalizedKeyId}`);
  }

  const expectedRelease = readExternalAcceptanceReleaseBinding(
    absoluteRepositoryRoot,
    deliverySidecarPath,
  );
  if (!releaseMatches(manifest.release, expectedRelease)) {
    throw new Error("External acceptance manifest release binding mismatch");
  }

  const realTrustStorePath = resolveWorkspaceFile(
    absoluteWorkspaceDirectory,
    trustStorePath,
    "Trust store",
  );
  const trustStoreBuffer = fs.readFileSync(realTrustStorePath);
  const actualTrustStoreSha256 = sha256(trustStoreBuffer);
  if (actualTrustStoreSha256 !== expectedTrustHash) {
    throw new Error(
      `Trust store SHA-256 mismatch: expected ${expectedTrustHash}, actual ${actualTrustStoreSha256}`,
    );
  }
  const trustStore = readJsonBuffer(trustStoreBuffer);
  const trustedKey = Array.isArray(trustStore.keys)
    ? trustStore.keys.find((item) => item?.keyId === normalizedKeyId)
    : undefined;
  if (!trustedKey) throw new Error(`Approval key is not present in trust store: ${normalizedKeyId}`);

  const realSignaturePath = resolveWorkspaceFile(
    absoluteWorkspaceDirectory,
    signaturePath,
    "Signature",
  );
  const signatureBase64 = fs.readFileSync(realSignaturePath, "utf8").trim();
  const candidate = {
    ...manifest,
    signatures: [
      ...signatures,
      {
        keyId: normalizedKeyId,
        algorithm: "ed25519",
        signedAt,
        signatureBase64,
      },
    ],
  };
  const candidateBuffer = jsonBuffer(candidate);
  const temporaryManifestPath = path.join(
    absoluteWorkspaceDirectory,
    `.manifest.${process.pid}.${crypto.randomUUID()}.json`,
  );
  fs.writeFileSync(temporaryManifestPath, candidateBuffer, { flag: "wx" });
  try {
    const report = auditExternalAcceptance({
      root: absoluteRepositoryRoot,
      manifestPath: path.relative(absoluteRepositoryRoot, temporaryManifestPath),
      trustStorePath: path.relative(absoluteRepositoryRoot, realTrustStorePath),
      expectedTrustStoreSha256: expectedTrustHash,
      expectedRelease,
      now,
    });
    const approval = report.approval;
    if (approval.issues.length > 0
        || approval.validSignatureCount !== candidate.signatures.length
        || !approval.validRoles.includes(trustedKey.role)) {
      const codes = approval.issues.map((item) => item.code).join(",")
        || "approval_signature_invalid";
      throw new Error(`External approval signature validation failed: ${codes}`);
    }
    const latestManifestSha256 = sha256(fs.readFileSync(manifestPath));
    if (latestManifestSha256 !== expectedManifestHash) {
      throw new Error(
        `Manifest SHA-256 mismatch: expected ${expectedManifestHash}, actual ${latestManifestSha256}`,
      );
    }
    fs.renameSync(temporaryManifestPath, manifestPath);
    return {
      keyId: normalizedKeyId,
      role: trustedKey.role,
      manifestPath,
      previousManifestSha256,
      manifestSha256: sha256(candidateBuffer),
      trustStoreSha256: actualTrustStoreSha256,
      validSignatureCount: approval.validSignatureCount,
      validRoles: approval.validRoles,
    };
  } finally {
    fs.rmSync(temporaryManifestPath, { force: true });
  }
}

function parseCliArguments(args) {
  const positional = [];
  const options = {};
  const optionNames = new Map([
    ["--trust-store", "trustStorePath"],
    ["--expected-manifest-sha256", "expectedManifestSha256"],
    ["--expected-trust-store-sha256", "expectedTrustStoreSha256"],
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
  if (positional.length < 5) {
    throw new Error(
      "Usage: attach-v9-external-approval-signature.mjs <root> <workspace> <keyId> <signedAt> <signaturePath> --trust-store <path> --expected-manifest-sha256 <sha256> --expected-trust-store-sha256 <sha256> --confirm ATTACH_EXTERNAL_APPROVAL_SIGNATURE",
    );
  }
  const result = attachExternalApprovalSignature({
    repositoryRoot: positional[0],
    workspaceDirectory: positional[1],
    keyId: positional[2],
    signedAt: positional[3],
    signaturePath: positional[4],
    ...options,
  });
  console.log(JSON.stringify(result, null, 2));
}
