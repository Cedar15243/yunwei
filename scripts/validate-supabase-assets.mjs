import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const requiredFiles = [
  "supabase/functions/ops-glasses/index.ts",
  "supabase/functions/ops-glasses/automigrate.ts",
  "supabase/functions/ops-glasses/deno.json",
  "supabase/config.toml",
  "supabase/README.md",
];

for (const file of requiredFiles) {
  const fullPath = path.join(root, file);
  if (!fs.existsSync(fullPath)) {
    throw new Error(`missing required file: ${file}`);
  }
}

const functionCode = fs.readFileSync(path.join(root, "supabase/functions/ops-glasses/index.ts"), "utf8");
const automigrateCode = fs.readFileSync(path.join(root, "supabase/functions/ops-glasses/automigrate.ts"), "utf8");

const requiredTables = [
  "ops_assets",
  "safe_commands",
  "ops_sessions",
  "ops_events",
  "ops_images",
  "ai_requests",
  "ai_observations",
  "voice_inputs",
  "remote_probes",
];

for (const table of requiredTables) {
  if (!automigrateCode.includes(`public.${table}`)) {
    throw new Error(`automigrate does not define ${table}`);
  }
  if (!automigrateCode.includes("enable row level security")) {
    throw new Error("automigrate does not enable RLS");
  }
}

if (!functionCode.includes("ensureSchema(env)")) {
  throw new Error("edge function does not call automigrate ensureSchema(env)");
}

if (!automigrateCode.includes("ops-glasses-captures")) {
  throw new Error("automigrate does not create the ops-glasses-captures storage bucket");
}

if (!automigrateCode.includes("AUTO_MIGRATE")) {
  throw new Error("automigrate does not expose AUTO_MIGRATE");
}

const requiredRoutes = [
  "/sessions/events",
  "/sessions/",
  "/probe",
  "/voice",
  "/escalate",
  "/health",
];

for (const route of requiredRoutes) {
  if (!functionCode.includes(route)) {
    throw new Error(`edge function missing route marker: ${route}`);
  }
}

const forbiddenCommandPatterns = [
  "rm -rf",
  "mkfs",
  "dd if=",
  "shutdown",
  "reboot",
];

for (const pattern of forbiddenCommandPatterns) {
  if (functionCode.includes(pattern) || automigrateCode.includes(pattern)) {
    throw new Error(`unsafe command pattern found: ${pattern}`);
  }
}

console.log("Supabase assets validation passed.");
