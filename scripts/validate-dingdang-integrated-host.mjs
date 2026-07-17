import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const mainPath = path.join(
  root,
  "air3-dingdang-expert-integrated-app",
  "app",
  "src",
  "main",
  "java",
  "com",
  "codex",
  "air3nativecamera",
  "MainActivity.java",
);
const main = readFileSync(mainPath, "utf8");

const mustInclude = (needle) => assert.ok(
  main.includes(needle),
  `missing integrated host contract: ${needle}`,
);

mustInclude("private enum ScreenMode { CHAT, CAMERA, EXPERT }");
mustInclude("AI智能运维指导");
mustInclude("专家协同");
mustInclude("设备巡检");
mustInclude("现场记录");
mustInclude("更多运维");
mustInclude("enterExpertMode");
mustInclude("exitExpertMode");
mustInclude("呼叫专家");
mustInclude("打开专家协同");
mustInclude("FeatureRegistry.createDefault()");
mustInclude("BuildConfig.COLLAB_SERVER_URL");

console.log("Integrated host contract validation passed.");
