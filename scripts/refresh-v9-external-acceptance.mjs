import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  initializeExternalAcceptanceWorkspace,
  readExternalAcceptanceReleaseBinding,
} from "./init-v9-external-acceptance.mjs";

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, "utf8").replace(/^\uFEFF/, ""));
}

function sameRelease(left, right) {
  return left?.applicationId === right?.applicationId
    && left?.versionCode === right?.versionCode
    && left?.versionName === right?.versionName
    && left?.apkSha256 === right?.apkSha256
    && left?.deliveryZipSha256 === right?.deliveryZipSha256
    && left?.generatedAt === right?.generatedAt;
}

function hasFiles(directory) {
  return fs.statSync(directory, { throwIfNoEntry: false })?.isDirectory()
    && fs.readdirSync(directory).length > 0;
}

function isSafePendingWorkspace(directory, manifest) {
  if (Object.keys(manifest?.gates ?? {}).length > 0 || (manifest?.signatures ?? []).length > 0) {
    return false;
  }
  if (hasFiles(path.join(directory, "files"))
      || fs.existsSync(path.join(directory, "trusted-approvers.json"))) {
    return false;
  }
  const allowedEntries = new Set([
    "README.md",
    "manifest.json",
    "required-checks.json",
    "templates",
  ]);
  return fs.readdirSync(directory).every((entry) => allowedEntries.has(entry));
}

export function refreshExternalAcceptanceWorkspace({
  repositoryRoot,
  outputDirectory = "evidence/v9-external-acceptance",
  generatedAt = new Date(),
  deliverySidecarPath,
}) {
  const absoluteRepositoryRoot = path.resolve(repositoryRoot);
  const absoluteOutputDirectory = path.resolve(absoluteRepositoryRoot, outputDirectory);
  const expectedRelease = readExternalAcceptanceReleaseBinding(
    absoluteRepositoryRoot,
    deliverySidecarPath,
  );
  const manifestPath = path.join(absoluteOutputDirectory, "manifest.json");

  if (!fs.existsSync(manifestPath)) {
    return {
      refreshed: true,
      reason: "initialized",
      ...initializeExternalAcceptanceWorkspace({
        repositoryRoot: absoluteRepositoryRoot,
        outputDirectory: absoluteOutputDirectory,
        generatedAt,
        deliverySidecarPath,
      }),
    };
  }

  const currentManifest = readJson(manifestPath);
  if (sameRelease(currentManifest.release, expectedRelease)) {
    return {
      refreshed: false,
      reason: "already_current",
      manifestPath,
      outputDirectory: absoluteOutputDirectory,
    };
  }
  if (!isSafePendingWorkspace(absoluteOutputDirectory, currentManifest)) {
    throw new Error(
      `External acceptance workspace contains evidence or approvals and cannot be reset: ${absoluteOutputDirectory}`,
    );
  }

  fs.rmSync(absoluteOutputDirectory, { recursive: true, force: true });
  return {
    refreshed: true,
    reason: "release_binding_changed",
    ...initializeExternalAcceptanceWorkspace({
      repositoryRoot: absoluteRepositoryRoot,
      outputDirectory: absoluteOutputDirectory,
      generatedAt,
      deliverySidecarPath,
    }),
  };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const repositoryRoot = path.resolve(process.argv[2] || path.join(import.meta.dirname, ".."));
  const outputDirectory = process.argv[3] || "evidence/v9-external-acceptance";
  console.log(JSON.stringify(refreshExternalAcceptanceWorkspace({
    repositoryRoot,
    outputDirectory,
  }), null, 2));
}
