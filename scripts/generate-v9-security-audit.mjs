import path from "node:path";

import { writeV9DependencyAudit } from "./v9-security-audit.mjs";

const root = path.resolve(process.argv[2] || path.join(import.meta.dirname, ".."));
const destination = path.resolve(process.argv[3] || path.join(root, "output", "v9-security-audit", "DEPENDENCY_AUDIT.json"));
const report = writeV9DependencyAudit(root, destination);
console.log(`V9 dependency audit generated (${report.summaries.length} sources).`);
