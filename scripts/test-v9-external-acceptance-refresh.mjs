import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { initializeExternalAcceptanceWorkspace } from "./init-v9-external-acceptance.mjs";
import { refreshExternalAcceptanceWorkspace } from "./refresh-v9-external-acceptance.mjs";

const RELEASE_MANIFEST = {
  applicationId: "com.codex.air3nativecamera.dingdangexpert.v9",
  versionCode: 900000,
  versionName: "9.0.0",
  sha256: "A".repeat(64),
  builtAt: "2026-08-14T00:00:00.000Z",
};
const FIRST_ZIP = "B".repeat(64);
const NEXT_ZIP = "C".repeat(64);

function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function writeRepositoryFixture(root, zipHash) {
  writeJson(path.join(root, "output/v9.0.0-formal-release-current/release-manifest.json"), RELEASE_MANIFEST);
  fs.mkdirSync(path.join(root, "output"), { recursive: true });
  fs.writeFileSync(
    path.join(root, "output/DingdangAI-V9-9.0.0-formal-delivery.zip.sha256"),
    `${zipHash}  DingdangAI-V9-9.0.0-formal-delivery.zip\n`,
    "utf8",
  );
}

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "v9-external-acceptance-refresh-"));
try {
  writeRepositoryFixture(temporary, FIRST_ZIP);
  const workspace = "evidence/v9-external-acceptance";
  initializeExternalAcceptanceWorkspace({
    repositoryRoot: temporary,
    outputDirectory: workspace,
    generatedAt: "2026-08-14T00:00:01.000Z",
  });

  const alreadyCurrent = refreshExternalAcceptanceWorkspace({
    repositoryRoot: temporary,
    outputDirectory: workspace,
  });
  assert.equal(alreadyCurrent.refreshed, false);
  assert.equal(alreadyCurrent.reason, "already_current");

  writeRepositoryFixture(temporary, NEXT_ZIP);
  const refreshed = refreshExternalAcceptanceWorkspace({
    repositoryRoot: temporary,
    outputDirectory: workspace,
    generatedAt: "2026-08-14T00:00:02.000Z",
  });
  assert.equal(refreshed.refreshed, true);
  assert.equal(refreshed.reason, "release_binding_changed");
  const renewedManifest = JSON.parse(fs.readFileSync(
    path.join(temporary, workspace, "manifest.json"),
    "utf8",
  ));
  assert.equal(renewedManifest.release.deliveryZipSha256, NEXT_ZIP);
  assert.deepEqual(renewedManifest.gates, {});
  assert.deepEqual(renewedManifest.signatures, []);

  renewedManifest.gates.production_supabase = { status: "passed" };
  writeJson(path.join(temporary, workspace, "manifest.json"), renewedManifest);
  writeRepositoryFixture(temporary, "D".repeat(64));
  assert.throws(
    () => refreshExternalAcceptanceWorkspace({ repositoryRoot: temporary, outputDirectory: workspace }),
    /contains evidence or approvals/i,
  );
  const protectedManifest = JSON.parse(fs.readFileSync(
    path.join(temporary, workspace, "manifest.json"),
    "utf8",
  ));
  assert.deepEqual(protectedManifest.gates, { production_supabase: { status: "passed" } });

  const packageScript = fs.readFileSync(
    path.join(import.meta.dirname, "package-v9-formal-delivery.ps1"),
    "utf8",
  );
  assert.match(packageScript, /refresh-v9-external-acceptance\.mjs/);
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}

console.log("V9 external acceptance refresh tests passed.");
