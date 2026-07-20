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
const trtcPath = path.join(
  root,
  "air3-dingdang-expert-integrated-app",
  "app",
  "src",
  "main",
  "java",
  "com",
  "codex",
  "expertcollab",
  "TrtcSessionController.java",
);
const coordinatorPath = path.join(
  root,
  "air3-dingdang-expert-integrated-app",
  "app",
  "src",
  "main",
  "java",
  "com",
  "codex",
  "expertcollab",
  "ExpertCollabCoordinator.java",
);
const trtc = readFileSync(trtcPath, "utf8");
const coordinator = readFileSync(coordinatorPath, "utf8");

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

assert.ok(trtc.includes("TRTC_VIDEO_RESOLUTION_1280_720"), "integrated expert video must use 1280x720");
assert.ok(trtc.includes("TRTC_VIDEO_RESOLUTION_MODE_LANDSCAPE"), "integrated expert video must use landscape encoding");
assert.ok(trtc.includes("onUserVideoAvailable"), "integrated expert mode must observe expert video publication");
assert.ok(trtc.includes("startRemoteView"), "integrated expert mode must render the primary expert video");
assert.ok(coordinator.includes("expertVideoFrame"), "integrated expert UI must expose an expert picture-in-picture view");

const retryCleanup = coordinator.indexOf("endCurrentCall(true);", coordinator.indexOf("private void onPrimaryAction()"));
const retryRequest = coordinator.indexOf("signaling.requestCall();", coordinator.indexOf("private void onPrimaryAction()"));
assert.ok(
  retryCleanup >= 0 && retryCleanup < retryRequest,
  "integrated expert mode must clean up a failed session before requesting a replacement call",
);

console.log("Integrated host contract validation passed.");
