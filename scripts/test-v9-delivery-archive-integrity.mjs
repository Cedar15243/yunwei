import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

const root = process.cwd();
const sourceDelivery = path.join(root, "output", "v9.0.0-formal-delivery");
const sourceZip = path.join(root, "output", "DingdangAI-V9-9.0.0-formal-delivery.zip");
const sourceSidecar = `${sourceZip}.sha256`;
const validator = path.join(root, "scripts", "validate-v9-formal-delivery.mjs");
const workspace = fs.mkdtempSync(path.join(os.tmpdir(), "v9-delivery-archive-test-"));

try {
  const output = path.join(workspace, "output");
  const delivery = path.join(output, "v9.0.0-formal-delivery");
  const zip = path.join(output, "DingdangAI-V9-9.0.0-formal-delivery.zip");
  const sidecar = `${zip}.sha256`;
  fs.mkdirSync(output, { recursive: true });
  fs.cpSync(sourceDelivery, delivery, { recursive: true });

  fs.writeFileSync(zip, "this is not a zip archive\n", "utf8");
  const digest = crypto.createHash("sha256").update(fs.readFileSync(zip)).digest("hex").toUpperCase();
  fs.writeFileSync(sidecar, `${digest}  ${path.basename(zip)}\n`, "ascii");

  const result = spawnSync(process.execPath, [validator], {
    cwd: workspace,
    encoding: "utf8",
    maxBuffer: 20 * 1024 * 1024,
  });
  const outputText = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
  if (result.status === 0) {
    throw new Error("formal delivery validator accepted an unreadable archive with a matching sidecar");
  }
  if (!/archive|extract|zip/i.test(outputText)) {
    throw new Error(`formal delivery validator failed for the wrong reason:\n${outputText}`);
  }

  fs.copyFileSync(sourceZip, zip);
  fs.copyFileSync(sourceSidecar, sidecar);
  const validResult = spawnSync(process.execPath, [validator], {
    cwd: workspace,
    encoding: "utf8",
    maxBuffer: 20 * 1024 * 1024,
  });
  if (validResult.status !== 0) {
    throw new Error(`formal delivery validator rejected the valid archive:\n${validResult.stdout}\n${validResult.stderr}`);
  }
} finally {
  fs.rmSync(workspace, { recursive: true, force: true });
}

console.log("V9 formal delivery archive integrity regression test passed.");
