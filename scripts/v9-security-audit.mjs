import childProcess from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

function sha256File(filePath) {
  return crypto.createHash("sha256").update(fs.readFileSync(filePath)).digest("hex").toUpperCase();
}

export function parseAuditSummary(source, payload) {
  const npmCounts = payload?.metadata?.vulnerabilities;
  const pipDependencies = Array.isArray(payload?.dependencies) ? payload.dependencies : null;
  let counts;
  if (npmCounts) {
    counts = {
      total: Number(npmCounts.total ?? 0),
      critical: Number(npmCounts.critical ?? 0),
      high: Number(npmCounts.high ?? 0),
      moderate: Number(npmCounts.moderate ?? 0),
      low: Number(npmCounts.low ?? 0),
      info: Number(npmCounts.info ?? 0),
    };
  } else if (pipDependencies) {
    const vulnerabilities = pipDependencies.flatMap((dependency) => dependency.vulns ?? []);
    counts = { total: vulnerabilities.length, critical: 0, high: 0, moderate: 0, low: 0, info: 0 };
  } else {
    throw new Error(`unsupported dependency audit payload: ${source}`);
  }
  if (counts.total > 0) throw new Error(`${source}: ${counts.total} vulnerabilities detected`);
  return { source, ...counts };
}

export function resolveAuditCommand(platform, command, args, comspec = process.env.ComSpec || "cmd.exe") {
  if (platform === "win32" && command === "npm") {
    return { command: comspec, args: ["/d", "/s", "/c", [command, ...args].join(" ")] };
  }
  return { command, args };
}

function runJson(command, args, cwd) {
  const invocation = resolveAuditCommand(process.platform, command, args);
  const result = childProcess.spawnSync(invocation.command, invocation.args, {
    cwd,
    encoding: "utf8",
    maxBuffer: 16 * 1024 * 1024,
    windowsHide: true,
  });
  const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`.trim();
  if (result.error) throw new Error(`${command} audit could not start: ${result.error.message}`);
  let payload;
  try {
    payload = JSON.parse(result.stdout || output);
  } catch (error) {
    throw new Error(`${command} audit did not return JSON (exit ${result.status}): ${error.message}`);
  }
  return payload;
}

function runVersion(command, args, cwd) {
  const invocation = resolveAuditCommand(process.platform, command, args);
  const result = childProcess.spawnSync(invocation.command, invocation.args, { cwd, encoding: "utf8", windowsHide: true });
  if (result.error) return `unavailable: ${result.error.message}`;
  return (result.stdout || result.stderr || "unknown").trim().split(/\r?\n/)[0];
}

export function runV9DependencyAudit(root) {
  const npmRoot = runJson("npm", ["audit", "--omit=dev", "--json"], root);
  const npmWeb = runJson("npm", ["audit", "--omit=dev", "--json"], path.join(root, "ops-management-web"));
  const pip = runJson("py", ["-3", "-m", "pip_audit", "-r", "v9-ops-gateway/requirements.txt", "--format", "json"], root);
  const summaries = [
    parseAuditSummary("npm-root", npmRoot),
    parseAuditSummary("npm-management-web", npmWeb),
    parseAuditSummary("python-gateway", pip),
  ];
  return {
    status: "clean",
    generatedAt: new Date().toISOString(),
    platform: `${os.platform()} ${os.release()}`,
    tools: {
      node: runVersion("node", ["--version"], root),
      npm: runVersion("npm", ["--version"], root),
      python: runVersion("py", ["-3", "--version"], root),
      pipAudit: runVersion("py", ["-3", "-m", "pip_audit", "--version"], root),
    },
    lockfiles: [
      "package-lock.json",
      "ops-management-web/package-lock.json",
      "v9-ops-gateway/requirements.txt",
    ].map((relative) => ({ path: relative, sha256: sha256File(path.join(root, relative)) })),
    summaries,
  };
}

export function writeV9DependencyAudit(root, destination) {
  const report = runV9DependencyAudit(root);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, `${JSON.stringify(report, null, 2)}\n`, "utf8");
  const markdown = [
    "# V9 Dependency Audit",
    "",
    `- status: \`${report.status}\``,
    `- generated at: \`${report.generatedAt}\``,
    `- node: \`${report.tools.node}\``,
    `- npm: \`${report.tools.npm}\``,
    `- python: \`${report.tools.python}\``,
    `- pip-audit: \`${report.tools.pipAudit}\``,
    "",
    "All audited runtime dependency sources reported zero known vulnerabilities.",
    "",
    "| Source | Total | Critical | High | Moderate | Low | Info |",
    "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ...report.summaries.map((summary) => (
      `| ${summary.source} | ${summary.total} | ${summary.critical} | ${summary.high} | ${summary.moderate} | ${summary.low} | ${summary.info} |`
    )),
    "",
    "The JSON report records the exact lockfile/requirements hashes used for this audit.",
  ].join("\n");
  fs.writeFileSync(destination.replace(/\.json$/i, ".md"), `${markdown}\n`, "utf8");
  return report;
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname)) {
  const root = path.resolve(process.argv[2] || path.join(import.meta.dirname, ".."));
  const destination = path.resolve(process.argv[3] || path.join(root, "output", "v9-security-audit", "DEPENDENCY_AUDIT.json"));
  const report = writeV9DependencyAudit(root, destination);
  console.log(`V9 dependency audit passed (${report.summaries.length} sources).`);
}
