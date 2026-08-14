import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";

const migrationPath = new URL(
  "../supabase/migrations/20260805210000_workflow_terminal_convergence.sql",
  import.meta.url,
);

assert.ok(existsSync(migrationPath), "workflow terminal convergence migration is missing");
const sql = readFileSync(migrationPath, "utf8");
const deviceRoute = readFileSync(
  new URL("../supabase/functions/ops-glasses/workflow-device.ts", import.meta.url),
  "utf8",
);

const reportableStatuses = deviceRoute.match(
  /const reportableStatuses = new Set\(\[[\s\S]*?\]\);/i,
);
assert.ok(reportableStatuses, "workflow device reportable status set is missing");
assert.doesNotMatch(
  reportableStatuses[0],
  /"completed"/i,
  "device status reports must not accept completed as a direct command",
);

assert.match(
  sql,
  /workflow assignment completion requires a completed execution/i,
  "device-side assignment completion must fail closed",
);
assert.match(
  sql,
  /node ->> 'type' = 'complete'/i,
  "assignment completion must be backed by the signed completion node",
);
assert.match(
  sql,
  /create trigger workflow_assignments_terminal_guard/i,
  "workflow assignment completion guard must be installed",
);
assert.match(
  sql,
  /create trigger workflow_executions_terminal_sync/i,
  "workflow execution terminal sync must be installed",
);
assert.match(
  sql,
  /update public\.maintenance_tasks[\s\S]*?status\s*=\s*'completed'/i,
  "workflow completion must close the maintenance task",
);
assert.match(
  sql,
  /update public\.work_orders[\s\S]*?status\s*=\s*'completed'/i,
  "workflow completion must close the work order",
);
assert.match(
  sql,
  /task_completed/i,
  "workflow completion must append a task_completed event",
);
assert.match(
  sql,
  /update public\.maintenance_tasks[\s\S]*?status\s*=\s*'aborted'/i,
  "workflow failure must abort the maintenance task",
);
assert.match(
  sql,
  /update public\.work_orders[\s\S]*?status\s*=\s*'cancelled'/i,
  "workflow failure must cancel the work order",
);
assert.match(
  sql,
  /task_closed/i,
  "workflow failure must append a task_closed event",
);

console.log("workflow terminal convergence contract passed");
