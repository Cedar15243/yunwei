import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const functionPath = path.join(root, "supabase/functions/ops-glasses/index.ts");
const code = fs.readFileSync(functionPath, "utf8");

function mustInclude(marker, message = marker) {
  if (!code.includes(marker)) {
    throw new Error(`AI brain flow missing marker: ${message}`);
  }
}

function functionBody(name) {
  const start = code.indexOf(`async function ${name}`);
  if (start < 0) {
    throw new Error(`missing function: ${name}`);
  }
  const next = code.indexOf("\nasync function ", start + 1);
  return code.slice(start, next < 0 ? code.length : next);
}

const responseType = code.slice(code.indexOf("type GlassesResponse"), code.indexOf("type Env"));
for (const field of [
  "resultType: ResultType",
  "feedbackCode: FeedbackCode",
  "displayTitle: string",
  "displayText: string",
  "displayHint: string",
  "humanEscalationSuggestion: boolean",
  "canUseVoice: boolean",
]) {
  if (!responseType.includes(field)) {
    throw new Error(`GlassesResponse must expose required field: ${field}`);
  }
}

for (const marker of [
  "function aiBrainPrompt(",
  "Air3 AI 运维眼镜的主 AI 大脑",
  "现场小白，不懂 Linux 运维，需要一步一步指导",
  "allowedCommands",
  "function aiBrainJsonSchema(",
  "function validateAiBrainDecision(",
  "function paginateFullText(",
  "async function createContextBundle(",
  "ai_context_bundles",
  "taskGoal: taskGoal",
  "operatorProfile",
  "transcript: input.transcript",
  "imageId: input.imageId",
  "voiceInputId: input.voiceInputId",
  "async function latestImageForSession(",
  "async function downloadImageBase64(",
  "async function requestAiBrainDecision(",
  "async function storeAiDecision(",
  "ai_decisions",
  "full_text:",
  "display_pages:",
  "text_overflow_mode:",
  "page_count:",
  "function responseFromDecision(",
]) {
  mustInclude(marker);
}

const eventBody = functionBody("handleSessionEvent");
for (const marker of [
  "createContextBundle(",
  "requestAiBrainDecision(",
  "storeAiDecision(",
  "responseFromDecision(",
  "noPhotoDecision(",
]) {
  if (!eventBody.includes(marker)) {
    throw new Error(`/sessions/events flow must call ${marker}`);
  }
}
if (eventBody.includes("analyzeConsoleImage(")) {
  throw new Error("/sessions/events must not call the old observer AI; the main AI brain is the only V2 AI decision path");
}
for (const oldMarker of [
  "function analyzeConsoleImage(",
  "function consoleObservationPrompt(",
  "workflowSignal",
  "voiceIntentToStep(",
]) {
  if (code.includes(oldMarker)) {
    throw new Error(`V2 backend must not keep old observer/state-machine marker: ${oldMarker}`);
  }
}

const voiceBody = functionBody("handleVoice");
for (const marker of [
  "transcribeAudio(",
  "latestImageForSession(",
  "downloadImageBase64(",
  "createContextBundle(",
  "requestAiBrainDecision(",
  "storeAiDecision(",
  "responseFromDecision(",
]) {
  if (!voiceBody.includes(marker)) {
    throw new Error(`/sessions/:id/voice flow must call ${marker}`);
  }
}

console.log("AI brain flow validation passed.");
