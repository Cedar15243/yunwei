import fs from "node:fs";
import path from "node:path";
import { validateV9DastReport, V9_DAST_TARGET } from "./validate-v9-dast-report.mjs";

const root = process.cwd();
const reportPath = path.join(root, "output", "v9-dast-20260812-r4", "v9-zap-baseline.json");
if (!fs.statSync(reportPath, { throwIfNoEntry: false })?.isFile()) {
  throw new Error("r4 ZAP report fixture is missing");
}
const validReport = JSON.parse(fs.readFileSync(reportPath, "utf8").replace(/^\uFEFF/, ""));

function expectFailure(name, mutate, pattern) {
  const report = structuredClone(validReport);
  mutate(report);
  try {
    validateV9DastReport(report);
  } catch (error) {
    if (pattern.test(error.message)) return;
    throw new Error(`${name} failed for the wrong reason: ${error.message}`);
  }
  throw new Error(`${name} was not rejected`);
}

const result = validateV9DastReport(validReport);
if (result.target !== V9_DAST_TARGET
    || result.version !== "2.17.0"
    || result.alertCounts.high !== 0
    || result.alertCounts.medium !== 0
    || result.alertCounts.low !== 0
    || result.alertCounts.informational !== 1) {
  throw new Error(`valid r4 report summary is incorrect: ${JSON.stringify(result)}`);
}

expectFailure("medium alert", (report) => {
  report.site[0].alerts[0].riskcode = "2";
}, /risk counts/i);
expectFailure("low alert", (report) => {
  report.site[0].alerts[0].riskcode = "1";
}, /risk counts/i);
expectFailure("target origin change", (report) => {
  report.site[0]["@name"] = "https://example.test";
}, /target site/i);
expectFailure("target path escape", (report) => {
  report.site[0].alerts[0].instances[0].uri = `${V9_DAST_TARGET}/health`;
}, /outside \/v9-ops/i);
expectFailure("missing alert instances", (report) => {
  report.site[0].alerts[0].instances = [];
}, /instances.*empty/i);
expectFailure("ZAP version drift", (report) => {
  report["@version"] = "2.16.1";
}, /@version/i);

console.log("V9 DAST report validator tests passed.");
