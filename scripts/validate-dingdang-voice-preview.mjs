import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourceRoot = path.join(
  root,
  "air3-dingdang-expert-integrated-app",
  "app",
  "src",
  "main",
  "java",
  "com",
  "codex",
  "air3nativecamera",
);
const main = readFileSync(path.join(sourceRoot, "MainActivity.java"), "utf8");
const router = readFileSync(path.join(sourceRoot, "voice", "VoiceCommandRouter.java"), "utf8");
const stateMachine = readFileSync(path.join(sourceRoot, "voice", "VoiceEventStateMachine.java"), "utf8");
const wakeWord = readFileSync(path.join(sourceRoot, "voice", "WakeWordEngine.java"), "utf8");
const wakeWordFactory = readFileSync(path.join(sourceRoot, "voice", "WakeWordEngines.java"), "utf8");
const asrSessionGate = readFileSync(path.join(sourceRoot, "voice", "VoiceAsrSessionGate.java"), "utf8");
const iflytekWakeWord = readFileSync(
  path.join(
    root,
    "air3-dingdang-expert-integrated-app",
    "app",
    "src",
    "offlineWake",
    "java",
    "com",
    "codex",
    "air3nativecamera",
    "voice",
    "IflytekWakeWordEngine.java",
  ),
  "utf8",
);
const testPath = path.join(
  root,
  "air3-dingdang-expert-integrated-app",
  "app",
  "src",
  "test",
  "java",
  "com",
  "codex",
  "air3nativecamera",
  "voice",
  "VoiceEventStateMachineTest.java",
);

assert.ok(existsSync(testPath), "voice event state-machine test is missing");
assert.match(main, /dingdangexpert\.follow\.preview\.voice/);
assert.match(main, /VOICE_EVENT_DESCRIPTION_TIMEOUT_MS = 30000L/);
assert.match(main, /VOICE_AUTO_WAKE_RECORDING_MS = 10000L/);
assert.match(main, /\|\| VOICE_WORKFLOW_ENABLED/);
assert.match(main, /OFFLINE_WAKE_ENABLED/);
assert.match(main, /voiceEventStateMachine\.onPhotoCaptured\(\)/);
assert.match(main, /beginVoiceEventDescription\(\)/);
assert.match(main, /submitVoiceEvent\(voiceEventStateMachine\.onDescriptionTimeout\(\)\)/);
assert.match(main, /handleVoicePreviewInteraction\(effectiveFinalText\)/);
assert.match(main, /beginVoiceCommandAfterWake\(\)/);
assert.match(main, /VoiceSessionPurpose\.WAKE/);
assert.match(main, /VoiceSessionPurpose\.COMMAND/);
assert.match(main, /已唤醒，请说指令/);
assert.match(main, /topBar\.addView\(stateText/);
assert.match(main, /stateText\.setVisibility\(View\.VISIBLE\)/);
assert.match(main, /叮当待命中/);
assert.match(main, /onEngineUnavailable/);
assert.match(main, /composerImageGeneration/);
assert.match(main, /buildDirectAiRequestPrompt/);
assert.match(main, /当前问题：/);
assert.match(main, /不要套用固定栏目/);
assert.match(main, /回答必须与当前问题直接相关/);
assert.match(main, /不得被历史任务或当前检测步骤带偏/);
assert.match(main, /if \(voiceStartedFromAutoWindow\) \{\s*return;\s*\}/s);
assert.match(main, /voiceStreamState = VoiceStreamState\.IDLE;[\s\S]*?scheduleForegroundVoiceListening\("ai-complete"\);/);
assert.match(main, /cancelVoiceEventDescriptionTimeout\(\);[\s\S]*?stopVoiceRecording\(false, "expert_enter"\)/);
assert.match(router, /Everything else remains event narration/);
assert.match(router, /startsWith\("小叮当"\)/);
assert.match(stateMachine, /WAITING_FOR_DESCRIPTION/);
assert.match(stateMachine, /onDescriptionTimeout/);
assert.match(wakeWord, /must not stream standby microphone audio to a server/);
assert.match(wakeWordFactory, /IflytekWakeWordEngine/);
assert.match(asrSessionGate, /invalidatedSessionCannotDeliverLateAsrCallbacks|accepts\(long session\)/);
assert.match(iflytekWakeWord, /func_wake_up/);
assert.match(iflytekWakeWord, /AudioSource\.MIC/);
assert.match(iflytekWakeWord, /SAMPLE_RATE_HZ = 16000/);
assert.match(iflytekWakeWord, /writeKeywordFile/);

console.log("Dingdang 629 voice-preview validation passed.");
