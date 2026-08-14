import path from "node:path";
import { spawnSync } from "node:child_process";

const root = process.cwd();
const validator = path.join(root, "scripts", "validate-dingdang-mock-smoke-latency.mjs");
const result = spawnSync(process.execPath, [validator, "--self-test"], {
  cwd: root,
  encoding: "utf8",
});

if (result.status !== 0) {
  throw new Error(`mock smoke latency validator self-test failed:\n${result.stdout}\n${result.stderr}`);
}

const output = JSON.parse(result.stdout);
if (output.ok !== true || output.selfTest !== true || output.invalidFailureCount < 1) {
  throw new Error(`mock smoke latency validator returned an invalid self-test result: ${result.stdout}`);
}

console.log("Mock smoke latency validator tests passed.");
