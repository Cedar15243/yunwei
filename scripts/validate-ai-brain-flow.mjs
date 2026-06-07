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

function mustNotInclude(marker, message = marker) {
  if (code.includes(marker)) {
    throw new Error(`AI brain flow keeps forbidden marker: ${message}`);
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
  "diagnosticCode?: string",
  "transcriptError?: string",
]) {
  if (!responseType.includes(field)) {
    throw new Error(`GlassesResponse must expose required field: ${field}`);
  }
}

for (const marker of [
  "function aiBrainPrompt(",
  "Air3 AI 运维眼镜的主 AI 大脑",
  "现场小白，不懂 Linux 运维，需要一步一步指导",
  "只要照片清楚，就必须基于画面给出真实反馈",
  "不要因为画面不是服务器控制台就返回 wrong_target",
  "不要把清晰非服务器画面的 displayTitle 写成“不是服务器控制台”",
  "非服务器清晰画面的 displayHint 必须追问小白要判断什么",
  "不要仅凭 taskGoal 或 step 名称进入 SSH 恢复语义",
  "服务器 SSH 恢复只是默认运维模板之一",
  "allowedCommands",
  "function aiBrainJsonSchema(",
  "function validateAiBrainDecision(",
  "function removeServerOnlyBiasFromGeneralScene(",
  "serverOnlyBiasPattern",
  "serverOnlyPageBiasPattern",
  "replaceBiasedGeneralScenePages(",
  "请说明你要 AI 判断的问题，例如是否正常、哪里有异常、下一步要做什么。",
  "请长按说明你要 AI 判断什么，或重新拍摄关键位置。",
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
  "function voiceTranscriptUnavailableDecision(",
  "type DiagnosticCode",
  "function voiceDiagnosticCode(",
  "const diagnosticCode = transcriptUnavailable ? voiceDiagnosticCode(transcriptError) : null",
  "diagnosticCode,",
  "diagnosticCode: extras.diagnosticCode",
  "feedbackCode: \"voice_unclear\"",
  "语音识别超时",
  "请重新长按，说一句短问题",
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

mustNotInclude(
  "如果画面不是服务器控制台，返回 resultType=recognition_problem, feedbackCode=wrong_target",
  "clear non-server photos must still receive AI scene feedback",
);

const voiceBody = functionBody("handleVoice");
for (const marker of [
  "transcribeAudio(",
  "latestImageForSession(",
  "downloadImageBase64(",
  "voiceTranscriptUnavailableDecision(",
  "const transcriptUnavailable = audioBase64 && !transcript",
  "const shouldAskAiBrain = !audioBase64 || transcript.length > 0",
  "hasTranscribeCredentials(env)",
  "shouldAskAiBrain && imageBase64",
  "createContextBundle(",
  "requestAiBrainDecision(",
  "storeAiDecision(",
  "responseFromDecision(",
]) {
  if (!voiceBody.includes(marker)) {
    throw new Error(`/sessions/:id/voice flow must call ${marker}`);
  }
}
if (!voiceBody.includes("voiceTranscriptUnavailableDecision(transcriptError)") ||
    !voiceBody.includes("const next = transcriptUnavailable ??")) {
  throw new Error("/sessions/:id/voice must return voice_unclear when STT produces no transcript");
}

for (const marker of [
  "form.append(\"language\", \"zh\")",
  "form.append(\"prompt\", sttPrompt(promptHint))",
  "const sttRequestTimeoutMs = 25_000;",
  "function shouldSendOpenAiTranscribeFields(",
  "if (shouldSendOpenAiTranscribeFields(env))",
  "function canFallbackToOfficialTranscribe(",
  "function officialTranscribeApiKey(",
  "function canFallbackToMainProviderTranscribe(",
  "mainProviderTranscribeEnv(env)",
  "main-provider-stt:",
  "officialOpenAiTranscribeEnv(env)",
  "official-stt:",
  "function sttPrompt(",
  "custom_stt_timeout",
  "main_provider_stt_unsupported",
  "official_stt_invalid_key",
  "transcript_empty",
  "stt_failed",
  "OPENAI_TRANSCRIBE_API_KEY",
  "OPENAI_TRANSCRIBE_BASE_URL",
  "OPENAI_OFFICIAL_TRANSCRIBE_API_KEY",
  "function hasTranscribeCredentials(",
  "openAiTranscribeUrl(env, \"/audio/transcriptions\")",
  "isOfficialOpenAiTranscribe(env)",
  "isOfficialOpenAiMainProvider(env)",
  "AbortSignal.timeout",
  "whisper-1",
  "transcript_empty",
]) {
  mustInclude(marker, `STT must include Chinese prompt/language and empty transcript fallback: ${marker}`);
}

mustNotInclude("const sttRequestTimeoutMs = 115_000;", "STT must not block the glasses HUD for nearly two minutes");
mustNotInclude("return env.OPENAI_OFFICIAL_TRANSCRIBE_API_KEY || env.OPENAI_API_KEY || \"\";", "official STT fallback must not reuse a non-official main AI key");
mustNotInclude("if (env.OPENAI_API_KEY) {\n      try {\n        transcript = await transcribeAudio", "voice STT must run when only OPENAI_TRANSCRIBE_API_KEY is configured");

console.log("AI brain flow validation passed.");
