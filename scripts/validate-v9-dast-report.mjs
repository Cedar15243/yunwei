import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

export const V9_DAST_TARGET = "https://bb.chinacedar.top:2305";

function fail(message) {
  throw new Error(`V9 DAST report validation failed: ${message}`);
}

function readJson(filePath) {
  if (!fs.statSync(filePath, { throwIfNoEntry: false })?.isFile()) {
    fail(`report is missing: ${filePath}`);
  }
  try {
    return JSON.parse(fs.readFileSync(filePath, "utf8").replace(/^\uFEFF/, ""));
  } catch (error) {
    fail(`report is not valid JSON: ${error.message}`);
  }
}

function riskCode(alert) {
  const code = Number.parseInt(String(alert?.riskcode ?? ""), 10);
  return Number.isInteger(code) ? code : null;
}

function uriIsInV9Ops(uri, target) {
  let parsed;
  try {
    parsed = new URL(uri);
  } catch {
    return false;
  }
  const expected = new URL(target);
  if (parsed.origin !== expected.origin) return false;
  return parsed.pathname === "/v9-ops" || parsed.pathname.startsWith("/v9-ops/");
}

export function validateV9DastReport(reportPath, {
  target = V9_DAST_TARGET,
} = {}) {
  const report = typeof reportPath === "string" ? readJson(reportPath) : reportPath;
  if (report?.["@programName"] !== "ZAP") fail("@programName must be ZAP");
  if (report?.["@version"] !== "2.17.0") fail("@version must be 2.17.0");
  if (!Array.isArray(report.site) || report.site.length !== 1) {
    fail("report must contain exactly one target site");
  }

  const site = report.site[0];
  if (site?.["@name"] !== target) fail(`target site must be ${target}`);
  const alerts = Array.isArray(site.alerts) ? site.alerts : [];
  const counts = { high: 0, medium: 0, low: 0, informational: 0 };
  let instanceCount = 0;
  for (const alert of alerts) {
    const code = riskCode(alert);
    if (code === null || code < 0 || code > 3) fail("alert riskcode is invalid");
    if (code === 3) counts.high += 1;
    else if (code === 2) counts.medium += 1;
    else if (code === 1) counts.low += 1;
    else counts.informational += 1;
    const instances = Array.isArray(alert.instances) ? alert.instances : [];
    if (instances.length === 0) fail("alert instances must not be empty");
    for (const instance of instances) {
      if (!uriIsInV9Ops(instance?.uri, target)) {
        fail(`alert instance is outside /v9-ops: ${instance?.uri ?? "<missing>"}`);
      }
      instanceCount += 1;
    }
  }
  if (instanceCount === 0) fail("report contains no alert instances");
  if (counts.high !== 0 || counts.medium !== 0 || counts.low !== 0) {
    fail(`risk counts must be zero: high=${counts.high} medium=${counts.medium} low=${counts.low}`);
  }

  return {
    program: report["@programName"],
    version: report["@version"],
    target,
    alertCounts: counts,
    alertCount: alerts.length,
    instanceCount,
    informationalAlerts: alerts.map((alert) => alert.alert ?? alert.name ?? "unknown"),
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const args = process.argv.slice(2);
  const reportIndex = args.indexOf("--json");
  const targetIndex = args.indexOf("--target");
  const reportPath = reportIndex >= 0 ? args[reportIndex + 1] : undefined;
  const target = targetIndex >= 0 ? args[targetIndex + 1] : V9_DAST_TARGET;
  if (!reportPath) fail("usage: node validate-v9-dast-report.mjs --json <path> [--target <origin>]");
  const result = validateV9DastReport(path.resolve(reportPath), { target });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}
