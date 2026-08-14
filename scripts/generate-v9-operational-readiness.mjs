import fs from "node:fs";
import path from "node:path";
import { auditOperationalReadiness } from "./v9-operational-readiness.mjs";

const root = path.resolve(process.argv[2] || path.join(import.meta.dirname, ".."));
const destination = path.resolve(process.argv[3] || path.join(root, "output", "v9-security-audit", "OPERATIONAL_READINESS.json"));
const report = auditOperationalReadiness(root);
fs.mkdirSync(path.dirname(destination), { recursive: true });
fs.writeFileSync(destination, `${JSON.stringify(report, null, 2)}\n`, "utf8");
const markdown = [
  "# V9 Operational Readiness",
  "",
  `- status: \`${report.status}\``,
  `- market GA: \`${report.marketGaStatus}\``,
  `- generated at: \`${report.generatedAt}\``,
  "",
  "## Local controls",
  "",
  ...Object.entries(report.controls).map(([name, status]) => `- ${name}: \`${status}\``),
  "",
  "## External gates",
  "",
  ...Object.entries(report.externalGates).map(([name, status]) => `- ${name}: \`${status}\``),
  "",
  "External gates remain pending until their own real production evidence is available. A completed gateway restore drill does not replace notification, supplier, third-party security or market-GA acceptance.",
].join("\n");
fs.writeFileSync(destination.replace(/\.json$/i, ".md"), `${markdown}\n`, "utf8");
if (report.status !== "local_controls_passed_external_gates_pending") {
  throw new Error("V9 operational readiness local controls failed");
}
console.log(`V9 operational readiness report generated: ${destination}`);
