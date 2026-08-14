import assert from "node:assert/strict";

import { parseAuditSummary, resolveAuditCommand } from "./v9-security-audit.mjs";

assert.deepEqual(
  resolveAuditCommand("win32", "npm", ["audit", "--omit=dev", "--json"], "C:\\Windows\\System32\\cmd.exe"),
  {
    command: "C:\\Windows\\System32\\cmd.exe",
    args: ["/d", "/s", "/c", "npm audit --omit=dev --json"],
  },
);
assert.deepEqual(resolveAuditCommand("linux", "npm", ["audit", "--json"]), {
  command: "npm",
  args: ["audit", "--json"],
});

const npm = parseAuditSummary("npm-root", {
  metadata: { vulnerabilities: { total: 0, critical: 0, high: 0, moderate: 0, low: 0, info: 0 } },
});
assert.deepEqual(npm, {
  source: "npm-root",
  total: 0,
  critical: 0,
  high: 0,
  moderate: 0,
  low: 0,
  info: 0,
});

const pip = parseAuditSummary("python-gateway", {
  dependencies: [{ name: "pypdf", version: "6.15.0", vulns: [] }],
  fixes: [],
});
assert.deepEqual(pip, {
  source: "python-gateway",
  total: 0,
  critical: 0,
  high: 0,
  moderate: 0,
  low: 0,
  info: 0,
});

assert.throws(
  () => parseAuditSummary("bad", { metadata: { vulnerabilities: { total: 1, high: 1 } } }),
  /vulnerabilities detected/i,
);

console.log("V9 security audit tests passed.");
