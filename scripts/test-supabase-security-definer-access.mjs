import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";

const migrationsDirectory = path.join(process.cwd(), "supabase/migrations");
const migrationSql = fs.readdirSync(migrationsDirectory)
  .filter((name) => name.endsWith(".sql"))
  .sort()
  .map((name) => fs.readFileSync(path.join(migrationsDirectory, name), "utf8"))
  .join("\n");

assert.match(
  migrationSql,
  /revoke\s+all\s+on\s+function\s+public\.is_ops_organization_member\s*\(\s*uuid\s*\)\s+from\s+public\s*,\s*anon\s*;/i,
  "organization membership helper must revoke default PUBLIC and anon execution",
);
assert.match(
  migrationSql,
  /grant\s+execute\s+on\s+function\s+public\.is_ops_organization_member\s*\(\s*uuid\s*\)\s+to\s+authenticated\s*,\s*service_role\s*;/i,
  "organization membership helper must remain available to authenticated RLS and service_role",
);
assert.doesNotMatch(
  migrationSql,
  /grant\s+execute\s+on\s+function\s+public\.is_ops_organization_member\s*\(\s*uuid\s*\)\s+to\s+anon\s*;/i,
  "organization membership helper must not be granted to anon",
);

console.log("Supabase security definer access tests passed.");
