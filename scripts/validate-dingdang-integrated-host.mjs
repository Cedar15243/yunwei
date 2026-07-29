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
const manifestPath = path.join(
  root,
  "air3-dingdang-expert-integrated-app",
  "app",
  "src",
  "main",
  "AndroidManifest.xml",
);
const trtc = readFileSync(trtcPath, "utf8");
const coordinator = readFileSync(coordinatorPath, "utf8");
const manifest = readFileSync(manifestPath, "utf8");

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

assert.ok(trtc.includes("TRTC_VIDEO_RESOLUTION_1920_1080"), "integrated expert video must use 1920x1080");
assert.ok(trtc.includes("TRTC_VIDEO_RESOLUTION_MODE_LANDSCAPE"), "integrated expert video must use landscape encoding");
assert.ok(trtc.includes("onUserVideoAvailable"), "integrated expert mode must observe expert video publication");
assert.ok(trtc.includes("startRemoteView"), "integrated expert mode must render the primary expert video");
assert.ok(manifest.includes("android.permission.MODIFY_AUDIO_SETTINGS"), "integrated expert mode must be allowed to set call volume");
assert.ok(trtc.includes("TRTC_AUDIO_ROUTE_SPEAKER"), "integrated expert mode must force remote audio to the speaker");
assert.ok(trtc.includes("setRemoteAudioVolume(expertUserId, 100)"), "integrated expert mode must use maximum expert audio volume");
assert.ok(trtc.includes("getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)"), "integrated expert mode must read the device call-volume limit");
assert.ok(trtc.includes("setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0)"), "integrated expert mode must raise call volume for the expert session");
assert.ok(coordinator.includes("expertVideoFrame"), "integrated expert UI must expose an expert picture-in-picture view");
assert.doesNotMatch(coordinator, /exitButton/, "integrated expert mode must expose only one hangup action");

const retryCleanup = coordinator.indexOf("endCurrentCall(true);", coordinator.indexOf("private void onPrimaryAction()"));
const retryRequest = coordinator.indexOf("signaling.requestCall();", coordinator.indexOf("private void onPrimaryAction()"));
assert.ok(
  retryCleanup >= 0 && retryCleanup < retryRequest,
  "integrated expert mode must clean up a failed session before requesting a replacement call",
);

const signalingConnectedStart = coordinator.indexOf("public void onSignalingConnected()");
const signalingConnectedEnd = coordinator.indexOf("\n    @Override", signalingConnectedStart);
const signalingConnected = coordinator.slice(signalingConnectedStart, signalingConnectedEnd);
assert.ok(
  coordinator.includes("private boolean initialCallRequested;"),
  "integrated expert mode must guard its initial automatic call",
);
assert.ok(
  signalingConnected.includes("!initialCallRequested") &&
    signalingConnected.includes("initialCallRequested = true;") &&
    signalingConnected.includes("onPrimaryAction();"),
  "opening expert mode must automatically call once after signaling connects",
);

const primaryActionStart = coordinator.indexOf("private void onPrimaryAction()");
const primaryActionEnd = coordinator.indexOf("\n    private void renderState()", primaryActionStart);
const primaryAction = coordinator.slice(primaryActionStart, primaryActionEnd);
assert.ok(
  primaryAction.includes("endCurrentCall(true);") &&
    primaryAction.includes("host.requestExitExpertMode();"),
  "hanging up must end the session and automatically return to AI guidance",
);
assert.ok(
  coordinator.includes("primaryButton.setText(\"挂断\");"),
  "the expert call action must be labeled as hangup while waiting or connected",
);

console.log("Integrated host contract validation passed.");
