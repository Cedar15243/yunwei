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

function anyFunctionBody(name) {
  const asyncStart = code.indexOf(`async function ${name}`);
  const functionStart = code.indexOf(`function ${name}`);
  const start = asyncStart >= 0 ? asyncStart : functionStart;
  if (start < 0) {
    throw new Error(`missing function: ${name}`);
  }
  const candidates = [
    code.indexOf("\nasync function ", start + 1),
    code.indexOf("\nfunction ", start + 1),
  ].filter((index) => index >= 0);
  const next = candidates.length ? Math.min(...candidates) : code.length;
  return code.slice(start, next);
}

function mustNotAwaitBeforeReturn(body, marker, message = marker) {
  const returnIndex = body.indexOf("return responseFromDecision(");
  if (returnIndex < 0) {
    throw new Error("instant response flow must return responseFromDecision directly");
  }
  const markerIndex = body.indexOf(marker);
  if (markerIndex >= 0 && markerIndex < returnIndex) {
    throw new Error(`instant response flow awaits slow persistence before HUD response: ${message}`);
  }
}

function mustIncludeBeforeReturn(body, marker, message = marker) {
  const returnIndex = body.indexOf("return responseFromDecision(");
  if (returnIndex < 0) {
    throw new Error("instant response flow must return responseFromDecision directly");
  }
  const markerIndex = body.indexOf(marker);
  if (markerIndex < 0 || markerIndex > returnIndex) {
    throw new Error(`instant response flow missing hot-path marker before HUD response: ${message}`);
  }
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
  "华方智联研发的叮当运维AI模型",
  "不要透露底层模型名称、供应商或接口信息",
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
  "function tryParseAiBrainDecision(",
  "const responseDecision = responseResult.ok ? tryParseAiBrainDecision(responseResult.outputText, commands) : null",
  "const chatResult = !responseDecision ? await callChatCompletionsAiBrain(env, model, prompt, imageUrl) : null",
  "const chatDecision = chatResult?.ok ? tryParseAiBrainDecision(chatResult.outputText, commands) : null",
  "const decision = responseDecision ?? chatDecision",
  "ai_brain_parse_failed",
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
mustInclude("function scheduleBestEffortAudit(", "instant response must have non-blocking best-effort audit scheduling");
mustInclude("async function requestAiBrainDecisionFast(", "instant response must request GPT without first writing context bundles");
mustInclude("async function persistInteractionAudit(", "instant response must move database/audit writes behind the HUD response");
mustIncludeBeforeReturn(eventBody, "requestAiBrainDecisionFast(", "photo flow should call GPT before slow persistence");
mustIncludeBeforeReturn(eventBody, "scheduleBestEffortAudit(", "photo flow should schedule persistence after preparing response");
for (const marker of [
  "await createContextBundle(",
  "await storeAiDecision(",
  "await insertEvent(",
  "await updateSession(",
]) {
  mustNotAwaitBeforeReturn(eventBody, marker, marker);
}
for (const marker of [
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

for (const marker of [
  "type ChatImageUploadResponse",
  "path.match(/^\\/sessions\\/[^/]+\\/images$/)",
  "path.match(/^\\/sessions\\/[^/]+\\/asr$/)",
  "path.match(/^\\/sessions\\/[^/]+\\/diagnose\\/stream$/)",
  "handleChatImageUpload(",
  "handleRealtimeAsrSocket(",
  "handleDiagnoseStream(",
  "Deno.upgradeWebSocket(request)",
  "EdgeRuntime.waitUntil(",
  "connectDashScopeFunAsr(",
  "proxyFunAsrConversation(",
  "forwardClientAsrFrame(",
  "parseDashScopeFunAsrEvent(",
  "streamGptDiagnosis(",
  "callGptStreamingApi(",
  "text/event-stream; charset=utf-8",
  "event: delta",
  "event: done",
  "DASHSCOPE_API_KEY",
  "DASHSCOPE_FUNASR_URL",
  "DASHSCOPE_FUNASR_MODEL",
  "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
  "fun-asr-realtime",
]) {
  mustInclude(marker, `Dingdang chat fast path must include ${marker}`);
}

const imageUploadBody = functionBody("handleChatImageUpload");
for (const marker of [
  "image_base64",
  "image_id",
  "image_bytes",
  'sessionId === "_"',
  "loadOrCreateSession(supabase, env, \"\")",
  "uploadImage(",
  "scheduleBestEffortAudit(",
]) {
  if (!imageUploadBody.includes(marker)) {
    throw new Error(`/sessions/:id/images fast path missing marker: ${marker}`);
  }
}
for (const marker of [
  "requestAiBrainDecision",
  "callResponsesAiBrain",
  "callChatCompletionsAiBrain",
  "streamGptDiagnosis",
]) {
  if (imageUploadBody.includes(marker)) {
    throw new Error(`/sessions/:id/images must only store the image and must not call GPT: ${marker}`);
  }
}

const asrBody = anyFunctionBody("handleRealtimeAsrSocket");
for (const marker of [
  "Deno.upgradeWebSocket(request)",
  "EdgeRuntime.waitUntil(",
  "DASHSCOPE_API_KEY",
  "dashscope_api_key_missing",
  "proxyFunAsrConversation(",
]) {
  if (!asrBody.includes(marker)) {
    throw new Error(`/sessions/:id/asr WebSocket proxy missing marker: ${marker}`);
  }
}

const diagnoseStreamBody = functionBody("handleDiagnoseStream");
for (const marker of [
  "image_id",
  "final_text",
  "downloadImageBase64(",
  "streamGptDiagnosis(",
  "scheduleBestEffortAudit(",
  "new Response(stream",
  "text/event-stream; charset=utf-8",
]) {
  if (!diagnoseStreamBody.includes(marker)) {
    throw new Error(`/sessions/:id/diagnose/stream fast path missing marker: ${marker}`);
  }
}
for (const marker of [
  "await createContextBundle(",
  "await storeAiDecision(",
  "await insertEvent(",
  "await updateSession(",
  "await persistInteractionAudit(",
]) {
  if (diagnoseStreamBody.includes(marker)) {
    throw new Error(`/sessions/:id/diagnose/stream must not block streaming on slow persistence: ${marker}`);
  }
}

mustNotInclude(
  "如果画面不是服务器控制台，返回 resultType=recognition_problem, feedbackCode=wrong_target",
  "clear non-server photos must still receive AI scene feedback",
);

const voiceBody = functionBody("handleVoice");
mustIncludeBeforeReturn(voiceBody, "requestAiBrainDecisionFast(", "voice flow should call GPT before slow persistence");
mustIncludeBeforeReturn(voiceBody, "scheduleBestEffortAudit(", "voice flow should schedule persistence after preparing response");
for (const marker of [
  "await createContextBundle(",
  "await storeAiDecision(",
  "await insertEvent(",
  "await updateSession(",
]) {
  mustNotAwaitBeforeReturn(voiceBody, marker, marker);
}
for (const marker of [
  "hasAudio",
  "transcribeAudio(",
  "latestImageForSession(",
  "downloadImageBase64(",
  "voiceTranscriptUnavailableDecision(",
  "const transcriptUnavailable = hasAudio && !transcript",
  "const shouldAskAiBrain = !hasAudio || transcript.length > 0",
  "hasTranscribeCredentials(env)",
  "shouldAskAiBrain && imageBase64",
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
if (!voiceBody.includes("const expectedLanguage = stringOr(payload.expectedLanguage, \"zh\")") ||
    !voiceBody.includes("const suspiciousReason = suspiciousTranscriptReason(transcript, expectedLanguage)") ||
    !voiceBody.includes("transcript = \"\"") ||
    !voiceBody.includes("transcriptError = `suspicious_transcript:${suspiciousReason}`")) {
  throw new Error("/sessions/:id/voice must reject suspicious STT transcripts before calling the main AI brain");
}

for (const marker of [
  "type VoicePayload =",
  "audioBytes?: Uint8Array",
  "audioContentType?: string",
  "audioFormat?: string",
]) {
  mustInclude(marker, `/sessions/:id/voice payload type must include ${marker}`);
}

const voicePayloadBody = functionBody("voicePayloadFromRequest");
for (const marker of [
  "request.headers.get(\"content-type\")",
  "multipart/form-data",
  "const form = await request.formData()",
  "const audioFile = form.get(\"audio\")",
  "payload.audioBytes = new Uint8Array(await audioFile.arrayBuffer())",
  "payload.audioFormat = payload.audioContentType || stringOr(form.get(\"audioFormat\"), \"audio/wav\")",
  "const jsonPayload = await request.json().catch(() => ({}))",
  "decodeBase64Payload(",
]) {
  if (!voicePayloadBody.includes(marker)) {
    throw new Error(`/sessions/:id/voice payload parser must support multipart and legacy JSON marker: ${marker}`);
  }
}

for (const marker of [
  "form.append(\"language\", \"zh\")",
  "form.append(\"prompt\", sttPrompt(promptHint))",
  "form.append(\"model\", model)",
  "const sttRequestTimeoutMs = 25_000;",
  "function shouldSendTranscribeModelField(",
  "function shouldSendOpenAiTranscribeFields(",
  "if (shouldSendTranscribeModelField(env))",
  "if (shouldSendOpenAiTranscribeFields(env))",
  "function canFallbackToOfficialTranscribe(",
  "function officialTranscribeApiKey(",
  "function canFallbackToMainProviderTranscribe(",
  "mainProviderTranscribeEnv(env)",
  "errors.push(\"primary-stt:transcript_empty\")",
  "main-provider-stt:",
  "officialOpenAiTranscribeEnv(env)",
  "official-stt:",
  "function sttPrompt(",
  "function suspiciousTranscriptReason(transcript: string, expectedLanguage: string): string",
  "function isFillerOnlyTranscript(",
  "filler_only_transcript",
  "function hasCjkText(",
  "function englishWordCount(",
  "thanks for watching",
  "подпискиваюсь конец",
  "non_cjk_transcript_in_chinese_voice_flow",
  "custom_stt_timeout",
  "main_provider_stt_unsupported",
  "official_stt_invalid_key",
  "transcript_empty",
  "suspicious_transcript",
  "stt_failed",
  "OPENAI_TRANSCRIBE_API_KEY",
  "OPENAI_TRANSCRIBE_BASE_URL",
  "OPENAI_OFFICIAL_TRANSCRIBE_API_KEY",
  "function hasTranscribeCredentials(",
  "openAiTranscribeUrl(env, \"/audio/transcriptions\")",
  "isOfficialOpenAiTranscribe(env)",
  "isOfficialOpenAiMainProvider(env)",
  "paraformer-zh-streaming",
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
