import { createClient, type SupabaseClient } from "@supabase/supabase-js";
import { ensureSchema } from "./automigrate.ts";

type OpsStep =
  | "locate_server"
  | "inspect_console"
  | "run_diagnostic_command"
  | "confirm_diagnostic_output"
  | "run_recovery_command"
  | "verify_remote_access"
  | "new_issue_triage"
  | "completed"
  | "needs_better_photo"
  | "needs_human_expert";

type EventAction =
  | "start_task"
  | "console_photo_uploaded"
  | "diagnostic_output_uploaded"
  | "recovery_command_ready"
  | "recovery_output_uploaded"
  | "voice_intent"
  | "remote_probe_requested"
  | "escalate"
  | "finish_task";

type VoiceIntent =
  | "start_task"
  | "confirm_done"
  | "retake"
  | "escalate"
  | "describe_scene"
  | "unknown";

type ResultType =
  | "instruction"
  | "recognition_problem"
  | "network_error"
  | "remote_probe"
  | "completed"
  | "human_suggested";

type FeedbackCode =
  | "wrong_target"
  | "unclear_photo"
  | "insufficient_info"
  | "voice_unclear"
  | "image_voice_conflict"
  | "ai_unavailable"
  | "network_error"
  | null;

type TextOverflowMode = "single" | "paged";

type AiBrainDecision = {
  resultType: ResultType;
  feedbackCode: FeedbackCode;
  step: OpsStep;
  displayTitle: string;
  displayText: string;
  fullText: string;
  displayPages: string[];
  textOverflowMode: TextOverflowMode;
  displayHint: string;
  safeCommandKey?: string | null;
  humanEscalationSuggestion: boolean;
  requiresPhoto: boolean;
};

type GlassesResponse = {
  ok: true;
  sessionId: string;
  step: OpsStep;
  text: string;
  resultType: ResultType;
  feedbackCode: FeedbackCode;
  displayTitle: string;
  displayText: string;
  fullText: string;
  displayPages: string[];
  textOverflowMode: TextOverflowMode;
  currentPage: number;
  totalPages: number;
  displayHint: string;
  safeCommandKey?: string | null;
  humanEscalationSuggestion: boolean;
  requiresPhoto: boolean;
  canRetake: boolean;
  canUseVoice: boolean;
  canEscalate: boolean;
  canHumanEscalate: boolean;
  imageBytes?: number;
  voiceIntent?: VoiceIntent;
  transcript?: string;
  transcriptError?: string;
  retestResult?: Record<string, unknown>;
  timestamp: string;
};

type Env = {
  SUPABASE_URL: string;
  SUPABASE_SERVICE_ROLE_KEY: string;
  OPENAI_API_KEY?: string;
  OPENAI_BASE_URL: string;
  OPENAI_TRANSCRIBE_API_KEY?: string;
  OPENAI_TRANSCRIBE_BASE_URL: string;
  OPENAI_VISION_MODEL: string;
  OPENAI_TRANSCRIBE_MODEL: string;
  DEMO_ASSET_TAG: string;
  DEMO_TARGET_HOST: string;
  DEMO_TARGET_SSH_PORT: number;
  DEMO_TARGET_APP_PORT?: number;
  REMOTE_PROBE_MODE: "mock" | "tcp" | "http";
  REMOTE_PROBE_URL?: string;
  OPS_GLASSES_API_KEY: string;
  AUTO_MIGRATE: boolean;
  SUPABASE_DB_URL: string;
};

type Supabase = SupabaseClient<any, "public", "public", any, any>;

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-ops-glasses-key",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
};

const storageBucket = "ops-glasses-captures";
const aiBrainPromptVersion = "air3-v2-ai-brain-v2-scene-feedback";
const taskGoal = "基于眼镜照片和现场语音给现场人员提供真实 AI 反馈与运维指导；服务器 SSH 恢复只是默认运维模板之一";
const operatorProfile = "现场小白，不懂 Linux 运维，需要一步一步指导";
const sttRequestTimeoutMs = 115_000;
const aiBrainRequestTimeoutMs = 25_000;

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const env = readEnv();
    const path = urlPath(request);
    if (path !== "/health" && !isAuthorized(request, env)) {
      return json({ ok: false, error: "unauthorized" }, 401);
    }

    const supabase = createClient(env.SUPABASE_URL, env.SUPABASE_SERVICE_ROLE_KEY, {
      auth: { persistSession: false },
    });
    await ensureSchema(env);

    if (request.method === "GET" && path === "/health") {
      return json({ ok: true, service: "ops-glasses" });
    }

    if (request.method === "GET" && path.startsWith("/sessions/")) {
      const sessionId = path.split("/")[2];
      return json(await getSessionResponse(supabase, sessionId));
    }

    if (request.method === "POST" && path === "/sessions/events") {
      const payload = await request.json().catch(() => ({}));
      return json(await handleSessionEvent({ supabase, env, payload }));
    }

    if (request.method === "POST" && path.match(/^\/sessions\/[^/]+\/probe$/)) {
      const sessionId = path.split("/")[2];
      return json(await handleProbe({ supabase, env, sessionId }));
    }

    if (request.method === "POST" && path.match(/^\/sessions\/[^/]+\/voice$/)) {
      const sessionId = path.split("/")[2];
      const payload = await request.json().catch(() => ({}));
      return json(await handleVoice({ supabase, env, sessionId, payload }));
    }

    if (request.method === "POST" && path.match(/^\/sessions\/[^/]+\/escalate$/)) {
      const sessionId = path.split("/")[2];
      return json(await escalateSession(supabase, sessionId));
    }

    return json({ ok: false, error: "not_found" }, 404);
  } catch (error) {
    console.error(error);
    return json(
      { ok: false, error: "internal_error", message: error instanceof Error ? error.message : String(error) },
      500,
    );
  }
});

async function handleSessionEvent(
  { supabase, env, payload }: { supabase: Supabase; env: Env; payload: Record<string, unknown> },
): Promise<GlassesResponse> {
  const action = normalizeAction(payload.action);
  const session = await loadOrCreateSession(supabase, env, stringOrEmpty(payload.sessionId));
  let imageId: string | null = null;
  let imageBase64ForAi = "";
  let imageBytes = 0;

  if (typeof payload.imageBase64 === "string" && payload.imageBase64.trim()) {
    const uploaded = await uploadImage(supabase, session.id, payload.imageBase64, stringOr(payload.imageKind, "console"));
    imageId = uploaded.imageId;
    imageBase64ForAi = payload.imageBase64;
    imageBytes = uploaded.imageBytes;
  }

  const contextBundle = await createContextBundle(supabase, {
    session,
    imageId,
    voiceInputId: null,
    transcript: "",
    payload: { ...payload, source: stringOr(payload.source, "photo") },
  });
  const commands = await loadSafeCommands(supabase);
  const aiResult = imageBase64ForAi
    ? await requestAiBrainDecision({
      supabase,
      env,
      session,
      imageId,
      contextBundle,
      imageBase64: imageBase64ForAi,
      transcript: "",
      commands,
    })
    : null;
  const next = aiResult?.decision ?? (imageBase64ForAi
    ? unavailableDecision()
    : noPhotoDecision());
  const aiDecisionId = await storeAiDecision(supabase, {
    session,
    contextBundleId: contextBundle.id,
    aiRequestId: aiResult?.aiRequestId ?? null,
    decision: next,
  });
  const event = await insertEvent(supabase, {
    sessionId: session.id,
    step: next.step,
    action,
    imageId,
    instructionText: next.displayText,
    payload: { imageBytes, aiDecisionId },
  });

  if (imageId) {
    await supabase.from("ops_images").update({ event_id: event.id }).eq("id", imageId);
  }

  const status = statusFromDecision(next);
  await updateSession(supabase, session.id, next.step, next.displayText, status);

  return responseFromDecision(session.id, next, { imageBytes, canRetake: true, canEscalate: true });
}

async function handleVoice(
  { supabase, env, sessionId, payload }: {
    supabase: Supabase;
    env: Env;
    sessionId: string;
    payload: Record<string, unknown>;
  },
): Promise<GlassesResponse> {
  const session = await mustGetSession(supabase, sessionId);
  const audioBase64 = stringOrEmpty(payload.audioBase64);
  let transcript = stringOrEmpty(payload.transcript);
  let transcriptError = "";
  let audioBytes = estimateBase64Bytes(audioBase64);
  let filePath: string | null = null;

  if (audioBase64) {
    const audio = decodeBase64Payload(audioBase64, stringOr(payload.audioFormat, "audio/mp4"));
    audioBytes = audio.bytes.length;
    filePath = `${sessionId}/voice/${crypto.randomUUID()}.${extensionForAudio(audio.contentType)}`;
    await uploadBytes(supabase, filePath, audio.bytes, audio.contentType);

    if (env.OPENAI_API_KEY) {
      try {
        transcript = await transcribeAudio(env, audio.bytes, audio.contentType, stringOrEmpty(payload.sttPrompt));
      } catch (error) {
        transcriptError = error instanceof Error ? error.message : String(error);
      }
    }
  }

  const voiceIntent = classifyVoiceIntent(transcript);

  const { data: voiceInput, error: voiceError } = await supabase.from("voice_inputs").insert({
    session_id: sessionId,
    file_path: filePath,
    audio_bytes: audioBytes,
    audio_format: stringOr(payload.audioFormat, "m4a"),
    transcript,
    voice_intent: voiceIntent,
    confidence: voiceIntent === "unknown" ? 0.4 : 0.86,
  }).select("id").single();
  throwIf(voiceError);

  const latestImage = await latestImageForSession(supabase, sessionId);
  const contextBundle = await createContextBundle(supabase, {
    session,
    imageId: latestImage?.id ?? null,
    voiceInputId: voiceInput.id,
    transcript,
    payload: { ...payload, source: "voice", voiceIntent, transcriptError },
  });
  const commands = await loadSafeCommands(supabase);
  const imageBase64 = latestImage ? await downloadImageBase64(supabase, latestImage.file_path) : "";
  const aiResult = imageBase64
    ? await requestAiBrainDecision({
      supabase,
      env,
      session,
      imageId: latestImage?.id ?? null,
      contextBundle,
      imageBase64,
      transcript,
      commands,
    })
    : null;
  const next = aiResult?.decision ?? (latestImage
    ? unavailableDecision()
    : aiDecisionFromLegacy(
      {
        step: "needs_better_photo" as OpsStep,
        text: "我还没有看到可用于判断的服务器控制台照片。请先把控制台或终端文字放进绿色框内拍照，再长按补充语音。",
        requiresPhoto: true,
      },
      "insufficient_info",
    ));
  const aiDecisionId = await storeAiDecision(supabase, {
    session,
    contextBundleId: contextBundle.id,
    aiRequestId: aiResult?.aiRequestId ?? null,
    decision: next,
  });

  await insertEvent(supabase, {
    sessionId,
    step: next.step,
    action: "voice_intent",
    voiceInputId: voiceInput.id,
    imageId: latestImage?.id ?? null,
    instructionText: next.displayText,
    payload: { transcript, voiceIntent, transcriptError, aiDecisionId },
  });
  await updateSession(supabase, sessionId, next.step, next.displayText, statusFromDecision(next));

  return responseFromDecision(sessionId, next, {
    transcript,
    transcriptError,
    voiceIntent,
    canRetake: true,
    canEscalate: true,
  });
}

async function handleProbe(
  { supabase, env, sessionId }: { supabase: Supabase; env: Env; sessionId: string },
): Promise<GlassesResponse> {
  const session = await mustGetSession(supabase, sessionId);
  const probe = await probeTarget(env, {
    host: String(session.target_host || env.DEMO_TARGET_HOST),
    sshPort: Number(env.DEMO_TARGET_SSH_PORT || 22),
    appPort: env.DEMO_TARGET_APP_PORT,
  });
  const result = classifyProbe(probe);

  const { error } = await supabase.from("remote_probes").insert({
    session_id: sessionId,
    host: probe.host,
    ssh_port: probe.sshPort,
    app_port: probe.appPort ?? null,
    ping_reachable: probe.pingReachable,
    ssh_reachable: probe.sshReachable,
    app_port_reachable: probe.appPortReachable ?? null,
    result_type: result.type,
    summary: result.summary,
    raw_json: probe,
  });
  throwIf(error);

  const step = result.type === "recovered"
    ? "completed"
    : result.type === "new_issue_detected"
    ? "new_issue_triage"
    : result.type === "same_issue_unresolved"
    ? "run_recovery_command"
    : "needs_human_expert";
  const status = step === "completed" ? "completed" : step === "needs_human_expert" ? "escalated" : "running";

  await insertEvent(supabase, {
    sessionId,
    step,
    action: "remote_probe_requested",
    instructionText: result.text,
    payload: { probe, result },
  });
  await updateSession(supabase, sessionId, step, result.text, status);

  return responseFromDecision(
    sessionId,
    decisionFromText({
      step,
      text: result.text,
      title: step === "completed" ? "SSH 已恢复" : "远程复测结果",
      resultType: step === "completed" ? "completed" : "remote_probe",
      feedbackCode: null,
      requiresPhoto: false,
      hint: step === "completed" ? "本次会话完成。" : "请按 AI 指导继续补充现场画面。",
    }),
    { canRetake: false, canEscalate: step !== "completed", retestResult: result },
  );
}

async function escalateSession(supabase: Supabase, sessionId: string): Promise<GlassesResponse> {
  await mustGetSession(supabase, sessionId);
  const text = "当前状态不适合继续自动指导。已转人工运维专家，请保持现场画面。";
  await insertEvent(supabase, {
    sessionId,
    step: "needs_human_expert",
    action: "escalate",
    instructionText: text,
    payload: {},
  });
  await updateSession(supabase, sessionId, "needs_human_expert", text, "escalated");
  return responseFromDecision(
    sessionId,
    decisionFromText({
      step: "needs_human_expert",
      text,
      title: "已转人工",
      resultType: "human_suggested",
      feedbackCode: null,
      requiresPhoto: false,
      hint: "请保持现场画面，等待人工运维接管。",
      humanEscalationSuggestion: true,
    }),
    { canRetake: false, canEscalate: false },
  );
}

async function getSessionResponse(supabase: Supabase, sessionId: string) {
  const session = await mustGetSession(supabase, sessionId);
  const { data: events, error } = await supabase.from("ops_events")
    .select("id, step, action, instruction_text, created_at")
    .eq("session_id", sessionId)
    .order("created_at", { ascending: false })
    .limit(20);
  throwIf(error);
  return { ok: true, session, events };
}

async function loadOrCreateSession(supabase: Supabase, env: Env, sessionId: string) {
  if (sessionId) {
    return await mustGetSession(supabase, sessionId);
  }

  const { data: asset, error: assetError } = await supabase.from("ops_assets")
    .select("id, asset_tag, host, ssh_port, app_port")
    .eq("asset_tag", env.DEMO_ASSET_TAG)
    .maybeSingle();
  throwIf(assetError);

  const targetAsset = asset?.asset_tag ?? env.DEMO_ASSET_TAG;
  const targetHost = asset?.host ?? env.DEMO_TARGET_HOST;

  const text = [
    `服务器 ${targetAsset} 远程 SSH 不可达。`,
    "请到服务器本地控制台前，拍摄屏幕和资产标签。",
    "只按眼镜提示操作，不要自行输入其他命令。",
  ].join("\n");

  const { data, error } = await supabase.from("ops_sessions").insert({
    target_asset_id: asset?.id ?? null,
    target_asset: targetAsset,
    target_host: targetHost,
    current_step: "locate_server",
    last_instruction: text,
    metadata: { sshPort: asset?.ssh_port ?? env.DEMO_TARGET_SSH_PORT, appPort: asset?.app_port ?? env.DEMO_TARGET_APP_PORT },
  }).select("*").single();
  throwIf(error);
  return data;
}

async function mustGetSession(supabase: Supabase, sessionId: string) {
  const { data, error } = await supabase.from("ops_sessions").select("*").eq("id", sessionId).single();
  throwIf(error);
  return data;
}

async function loadSafeCommands(supabase: Supabase): Promise<Record<string, string>> {
  const { data, error } = await supabase.from("safe_commands")
    .select("command_key, command_text")
    .eq("enabled", true);
  throwIf(error);
  return Object.fromEntries(
    ((data ?? []) as Array<{ command_key: string; command_text: string }>).map((row) => [
      row.command_key,
      row.command_text,
    ]),
  );
}

async function createContextBundle(
  supabase: Supabase,
  input: {
    session: Record<string, unknown>;
    imageId: string | null;
    voiceInputId: string | null;
    transcript: string;
    payload: Record<string, unknown>;
  },
): Promise<{ id: string; context_json: Record<string, unknown> }> {
  const currentStep = normalizeStep(input.session.current_step);
  const context = {
    taskGoal: taskGoal,
    currentStep,
    operatorProfile,
    transcript: input.transcript,
    imageId: input.imageId,
    voiceInputId: input.voiceInputId,
    source: stringOr(input.payload.source, ""),
    timestamp: new Date().toISOString(),
  };

  const { data, error } = await supabase.from("ai_context_bundles").insert({
    session_id: input.session.id,
    image_id: input.imageId,
    voice_input_id: input.voiceInputId,
    current_step: currentStep,
    task_goal: taskGoal,
    transcript: input.transcript,
    context_json: context,
  }).select("id, context_json").single();
  throwIf(error);
  return data as { id: string; context_json: Record<string, unknown> };
}

async function latestImageForSession(
  supabase: Supabase,
  sessionId: string,
): Promise<{ id: string; file_path: string } | null> {
  const { data, error } = await supabase.from("ops_images")
    .select("id, file_path")
    .eq("session_id", sessionId)
    .order("created_at", { ascending: false })
    .limit(1)
    .maybeSingle();
  throwIf(error);
  return data ?? null;
}

async function downloadImageBase64(supabase: Supabase, filePath: string): Promise<string> {
  const { data, error } = await supabase.storage.from(storageBucket).download(filePath);
  throwIf(error);
  const bytes = new Uint8Array(await data.arrayBuffer());
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}

async function uploadImage(
  supabase: Supabase,
  sessionId: string,
  imageBase64: string,
  imageKind: string,
): Promise<{ imageId: string; imageBytes: number; filePath: string }> {
  const decoded = decodeBase64Payload(imageBase64, "image/jpeg");
  const filePath = `${sessionId}/images/${crypto.randomUUID()}.${extensionForImage(decoded.contentType)}`;
  await uploadBytes(supabase, filePath, decoded.bytes, decoded.contentType);

  const { data, error } = await supabase.from("ops_images").insert({
    session_id: sessionId,
    file_path: filePath,
    image_bytes: decoded.bytes.length,
    image_kind: imageKind,
    content_type: decoded.contentType,
  }).select("id").single();
  throwIf(error);
  return { imageId: data.id, imageBytes: decoded.bytes.length, filePath };
}

async function uploadBytes(supabase: Supabase, filePath: string, bytes: Uint8Array, contentType: string) {
  const { error } = await supabase.storage.from(storageBucket).upload(filePath, bytes, {
    contentType,
    upsert: false,
  });
  throwIf(error);
}

async function requestAiBrainDecision(
  { supabase, env, session, imageId, contextBundle, imageBase64, transcript, commands }: {
    supabase: Supabase;
    env: Env;
    session: Record<string, unknown>;
    imageId: string | null;
    contextBundle: { id: string; context_json: Record<string, unknown> };
    imageBase64: string;
    transcript: string;
    commands: Record<string, string>;
  },
): Promise<{ decision: AiBrainDecision; aiRequestId: string | null } | null> {
  if (!env.OPENAI_API_KEY || !imageBase64) {
    return null;
  }

  const started = Date.now();
  const model = env.OPENAI_VISION_MODEL;
  const prompt = aiBrainPrompt({ ...contextBundle.context_json, transcript }, commands);
  const imageUrl = ensureDataUrl(imageBase64, "image/jpeg");

  try {
    const responseResult = await callResponsesAiBrain(env, model, prompt, imageUrl);
    const rawResult = responseResult.ok ? responseResult : await callChatCompletionsAiBrain(env, model, prompt, imageUrl);
    if (!rawResult.ok) {
      throw new Error(JSON.stringify(rawResult.raw));
    }
    const parsed = JSON.parse(extractJsonObject(rawResult.outputText));
    const decision = validateAiBrainDecision(parsed, commands);
    const { data: requestRow, error } = await supabase.from("ai_requests").insert({
      session_id: session.id,
      image_id: imageId,
      model,
      prompt_version: aiBrainPromptVersion,
      status: "success",
      latency_ms: Date.now() - started,
      raw_response: rawResult.raw,
    }).select("id").single();
    throwIf(error);
    return { decision, aiRequestId: requestRow.id };
  } catch (error) {
    const { data } = await supabase.from("ai_requests").insert({
      session_id: session.id,
      image_id: imageId,
      model,
      prompt_version: aiBrainPromptVersion,
      status: "failed",
      latency_ms: Date.now() - started,
      error_message: error instanceof Error ? error.message : String(error),
    }).select("id").maybeSingle();
    return {
      decision: unavailableDecision(),
      aiRequestId: data?.id ?? null,
    };
  }
}

async function callResponsesAiBrain(
  env: Env,
  model: string,
  prompt: string,
  imageUrl: string,
): Promise<{ ok: true; raw: Record<string, unknown>; outputText: string } | { ok: false; raw: Record<string, unknown> }> {
  const response = await fetch(openAiUrl(env, "/responses"), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.OPENAI_API_KEY}`,
      "Content-Type": "application/json",
    },
    signal: AbortSignal.timeout(aiBrainRequestTimeoutMs),
    body: JSON.stringify({
      model,
      input: [
        {
          role: "user",
          content: [
            { type: "input_text", text: prompt },
            { type: "input_image", image_url: imageUrl },
          ],
        },
      ],
      text: {
        format: {
          type: "json_schema",
          name: "air3_ai_brain_decision",
          schema: aiBrainJsonSchema(),
          strict: true,
        },
      },
    }),
  });
  const raw = await safeJson(response);
  if (!response.ok) return { ok: false, raw };
  return { ok: true, raw, outputText: outputTextFromResponses(raw) };
}

async function callChatCompletionsAiBrain(
  env: Env,
  model: string,
  prompt: string,
  imageUrl: string,
): Promise<{ ok: true; raw: Record<string, unknown>; outputText: string } | { ok: false; raw: Record<string, unknown> }> {
  const response = await fetch(openAiUrl(env, "/chat/completions"), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.OPENAI_API_KEY}`,
      "Content-Type": "application/json",
    },
    signal: AbortSignal.timeout(aiBrainRequestTimeoutMs),
    body: JSON.stringify({
      model,
      messages: [
        {
          role: "user",
          content: [
            { type: "text", text: `${prompt}\n只返回一个 JSON 对象，不要使用 Markdown。` },
            { type: "image_url", image_url: { url: imageUrl } },
          ],
        },
      ],
      response_format: { type: "json_object" },
    }),
  });
  const raw = await safeJson(response);
  if (!response.ok) return { ok: false, raw };

  const choices = Array.isArray(raw.choices) ? raw.choices : [];
  const first = choices[0] as { message?: { content?: string } } | undefined;
  return { ok: true, raw, outputText: first?.message?.content ?? "" };
}

function aiBrainPrompt(context: Record<string, unknown>, commands: Record<string, string>): string {
  return [
    "你是 Air3 AI 运维眼镜的主 AI 大脑，直接指导现场小白理解现场画面并完成安全操作。",
    "你会同时获得现场图片、现场人员语音转写文字、当前步骤和任务目标。",
    "服务器 SSH 恢复只是默认运维模板之一，不是唯一可识别场景。",
    "只要照片清楚，就必须基于画面给出真实反馈：先说明你看到的关键内容，再结合语音说明回答或给下一步建议。",
    "不要因为画面不是服务器控制台就返回 wrong_target；如果画面清楚但不属于 SSH 恢复任务，请说明画面内容，并提示小白长按说明要你判断什么。",
    "不要把清晰非服务器画面的 displayTitle 写成“不是服务器控制台”；标题应概括你看到的真实对象或场景。",
    "非服务器清晰画面的 displayHint 必须追问小白要判断什么，不能默认要求重新对准服务器、终端、登录界面或机柜。",
    "不要仅凭 taskGoal 或 step 名称进入 SSH 恢复语义；只有图像或 transcript 明确指向服务器/终端/SSH 时才使用 SSH 模板。",
    "现场人员不是运维专家，请用短句、明确、可执行的中文指导。",
    "如果画面确实是服务器控制台、终端、登录界面或命令输出，再按 SSH 恢复模板继续给运维指导。",
    "如果需要展示命令，只能使用 allowedCommands 中的命令文本，不要自由生成新命令。",
    "只有照片模糊、过暗、反光、主体被遮挡或完全看不清时，才返回 feedbackCode=unclear_photo。",
    "如果图片清楚但缺少用户要判断的问题或关键上下文，返回 resultType=instruction, feedbackCode=null，并追问小白要你判断什么。",
    "如果语音转写不清楚，返回 feedbackCode=voice_unclear。",
    "如果图片和语音转写冲突，返回 feedbackCode=image_voice_conflict。",
    "如果建议人工介入，返回 resultType=human_suggested；最终是否人工介入由小白决定。",
    "displayTitle、displayText、displayHint 必须是给现场小白看的中文，不要包含 HTTP、bytes、session、异常类名、原始 JSON 或 debug 信息。",
    "只返回 JSON，不要 Markdown。",
    JSON.stringify({ context, allowedCommands: commands }),
  ].join("\n");
}

function aiBrainJsonSchema(): Record<string, unknown> {
  return {
    type: "object",
    additionalProperties: false,
    required: [
      "resultType",
      "feedbackCode",
      "step",
      "displayTitle",
      "displayText",
      "displayHint",
      "safeCommandKey",
      "humanEscalationSuggestion",
      "requiresPhoto",
    ],
    properties: {
      resultType: {
        type: "string",
        enum: ["instruction", "recognition_problem", "network_error", "remote_probe", "completed", "human_suggested"],
      },
      feedbackCode: {
        anyOf: [
          {
            type: "string",
            enum: [
              "wrong_target",
              "unclear_photo",
              "insufficient_info",
              "voice_unclear",
              "image_voice_conflict",
              "ai_unavailable",
              "network_error",
            ],
          },
          { type: "null" },
        ],
      },
      step: {
        type: "string",
        enum: [
          "locate_server",
          "inspect_console",
          "run_diagnostic_command",
          "confirm_diagnostic_output",
          "run_recovery_command",
          "verify_remote_access",
          "new_issue_triage",
          "completed",
          "needs_better_photo",
          "needs_human_expert",
        ],
      },
      displayTitle: { type: "string" },
      displayText: { type: "string" },
      displayHint: { type: "string" },
      safeCommandKey: { anyOf: [{ type: "string" }, { type: "null" }] },
      humanEscalationSuggestion: { type: "boolean" },
      requiresPhoto: { type: "boolean" },
    },
  };
}

async function safeJson(response: Response): Promise<Record<string, unknown>> {
  const text = await response.text();
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    return { text };
  }
}

function outputTextFromResponses(raw: Record<string, unknown>): string {
  const direct = stringOr(raw.output_text, "");
  if (direct) return direct;
  if (!Array.isArray(raw.output)) return "";
  return raw.output
    .flatMap((item: { content?: Array<{ text?: string }> }) => item.content ?? [])
    .map((content: { text?: string }) => content.text ?? "")
    .join("");
}

function extractJsonObject(value: string): string {
  const trimmed = value.trim();
  if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed;
  const match = trimmed.match(/\{[\s\S]*\}/);
  if (!match) throw new Error(`ai_json_not_found:${trimmed.slice(0, 200)}`);
  return match[0];
}

async function transcribeAudio(env: Env, bytes: Uint8Array, contentType: string, promptHint = ""): Promise<string> {
  const primary = await transcribeAudioWithModel(env, env.OPENAI_TRANSCRIBE_MODEL, bytes, contentType, promptHint);
  if (primary) return primary;
  if (isOfficialOpenAiTranscribe(env) && env.OPENAI_TRANSCRIBE_MODEL !== "whisper-1") {
    const fallback = await transcribeAudioWithModel(env, "whisper-1", bytes, contentType, promptHint);
    if (fallback) return fallback;
  }
  throw new Error("transcript_empty");
}

async function transcribeAudioWithModel(
  env: Env,
  model: string,
  bytes: Uint8Array,
  contentType: string,
  promptHint: string,
): Promise<string> {
  const form = new FormData();
  form.append("model", model);
  form.append("language", "zh");
  form.append("prompt", sttPrompt(promptHint));
  const audioBuffer = bytes.slice().buffer as ArrayBuffer;
  form.append("file", new Blob([audioBuffer], { type: contentType }), `voice.${extensionForAudio(contentType)}`);
  const response = await fetch(openAiTranscribeUrl(env, "/audio/transcriptions"), {
    method: "POST",
    headers: { Authorization: `Bearer ${env.OPENAI_TRANSCRIBE_API_KEY || env.OPENAI_API_KEY}` },
    body: form,
    signal: AbortSignal.timeout(sttRequestTimeoutMs),
  });
  const body = await safeJson(response);
  if (!response.ok) {
    throw new Error(JSON.stringify(body));
  }
  const text = stringOr(body.text, "");
  if (text) return text;
  const rawText = stringOr(body.text, "");
  const jsonText = stringOr(body.text, "") || stringOr(body.output_text, "");
  return jsonText || rawText;
}

function sttPrompt(promptHint = ""): string {
  const base = [
    "中文普通话现场问题，通常很短。",
    "常见说法包括：这个是什么、这是什么、有什么问题、下一步怎么做、帮我看屏幕报错、帮我判断这个页面。",
    "请尽量按中文原话转写，不要翻译，不要解释。",
  ].join(" ");
  return promptHint ? `${base} ${promptHint}` : base;
}

async function probeTarget(env: Env, target: { host: string; sshPort: number; appPort?: number }) {
  if (env.REMOTE_PROBE_MODE === "http" && env.REMOTE_PROBE_URL) {
    const response = await fetch(env.REMOTE_PROBE_URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(target),
    });
    if (!response.ok) {
      throw new Error(`remote_probe_http_failed:${response.status}`);
    }
    return await response.json();
  }

  if (env.REMOTE_PROBE_MODE === "mock") {
    const sshReachable = Deno.env.get("MOCK_SSH_REACHABLE") === "true";
    const appPortReachable = target.appPort ? Deno.env.get("MOCK_APP_PORT_REACHABLE") === "true" : null;
    return {
      host: target.host,
      sshPort: target.sshPort,
      appPort: target.appPort,
      pingReachable: sshReachable || Boolean(appPortReachable),
      sshReachable,
      appPortReachable,
      checkedAt: new Date().toISOString(),
      mode: "mock",
    };
  }

  const sshReachable = await tcpProbe(target.host, target.sshPort, 1800);
  const appPortReachable = target.appPort ? await tcpProbe(target.host, target.appPort, 1800) : null;
  return {
    host: target.host,
    sshPort: target.sshPort,
    appPort: target.appPort,
    pingReachable: sshReachable || Boolean(appPortReachable),
    sshReachable,
    appPortReachable,
    checkedAt: new Date().toISOString(),
    mode: "tcp",
  };
}

async function tcpProbe(host: string, port: number, timeoutMs: number): Promise<boolean> {
  try {
    const connection = Deno.connect({ hostname: host, port });
    const timeout = new Promise<never>((_, reject) => setTimeout(() => reject(new Error("timeout")), timeoutMs));
    const conn = await Promise.race([connection, timeout]);
    try {
      conn.close();
    } catch {
      // ignore close errors
    }
    return true;
  } catch {
    return false;
  }
}

function classifyProbe(probe: { sshReachable: boolean; appPort?: number; appPortReachable?: boolean | null }) {
  if (probe.sshReachable && (!probe.appPort || probe.appPortReachable !== false)) {
    return {
      type: "recovered",
      summary: "SSH 已恢复",
      text: "SSH 已恢复，服务器可重新远程运维。",
      canContinueAutomatically: false,
    };
  }
  if (probe.sshReachable && probe.appPort && probe.appPortReachable === false) {
    return {
      type: "new_issue_detected",
      summary: "SSH 已恢复，但应用端口不可达",
      text: "SSH 已恢复，但发现应用端口不可达。单击继续排查，长按转人工。",
      newIssueType: "app_port_unreachable",
      canContinueAutomatically: true,
    };
  }
  return {
    type: "same_issue_unresolved",
    summary: "SSH 仍不可达",
    text: "远程 SSH 仍不可达。请确认刚才命令是否执行成功，并重新拍摄控制台输出。",
    canContinueAutomatically: true,
  };
}

async function insertEvent(
  supabase: Supabase,
  event: {
    sessionId: string;
    step: OpsStep;
    action: EventAction;
    imageId?: string | null;
    voiceInputId?: string | null;
    instructionText: string;
    payload: Record<string, unknown>;
  },
) {
  const { data, error } = await supabase.from("ops_events").insert({
    session_id: event.sessionId,
    step: event.step,
    action: event.action,
    image_id: event.imageId ?? null,
    voice_input_id: event.voiceInputId ?? null,
    instruction_text: event.instructionText,
    payload: event.payload,
  }).select("id").single();
  throwIf(error);
  return data;
}

async function storeAiDecision(
  supabase: Supabase,
  input: {
    session: Record<string, unknown>;
    contextBundleId: string | null;
    aiRequestId: string | null;
    decision: AiBrainDecision;
  },
): Promise<string | null> {
  const { data, error } = await supabase.from("ai_decisions").insert({
    session_id: input.session.id,
    context_bundle_id: input.contextBundleId,
    ai_request_id: input.aiRequestId,
    result_type: input.decision.resultType,
    feedback_code: input.decision.feedbackCode,
    step: input.decision.step,
    safe_command_key: input.decision.safeCommandKey ?? null,
    display_title: input.decision.displayTitle,
    display_text: input.decision.displayText,
    full_text: input.decision.fullText,
    display_pages: input.decision.displayPages,
    text_overflow_mode: input.decision.textOverflowMode,
    page_count: input.decision.displayPages.length || 1,
    display_hint: input.decision.displayHint,
    human_escalation_suggestion: input.decision.humanEscalationSuggestion,
    raw_json: input.decision,
  }).select("id").maybeSingle();
  throwIf(error);
  return data?.id ?? null;
}

async function updateSession(
  supabase: Supabase,
  sessionId: string,
  step: OpsStep,
  text: string,
  status: "running" | "completed" | "escalated",
) {
  const { error } = await supabase.from("ops_sessions").update({
    current_step: step,
    last_instruction: text,
    status,
    updated_at: new Date().toISOString(),
    completed_at: status === "completed" ? new Date().toISOString() : null,
  }).eq("id", sessionId);
  throwIf(error);
}

function validateAiBrainDecision(value: Record<string, unknown>, commands: Record<string, string>): AiBrainDecision {
  const safeCommandKey = nullableString(value.safeCommandKey);
  if (safeCommandKey && !(safeCommandKey in commands)) {
    return humanSuggestedDecision();
  }
  const resultType = normalizeResultType(value.resultType);
  const feedbackCode = normalizeFeedbackCode(value.feedbackCode);
  const displayTitle = stripDebugText(stringOr(value.displayTitle, ""));
  const displayText = stripDebugText(stringOr(value.displayText, ""));
  const displayHint = stripDebugText(stringOr(value.displayHint, ""));
  const fullText = stripDebugText(stringOr(value.fullText, displayText || displayTitle));
  const pages = Array.isArray(value.displayPages)
    ? value.displayPages.map((page) => stripDebugText(String(page))).filter(Boolean)
    : paginateFullText(fullText);
  const displayPages = pages.length ? pages : paginateFullText(displayText || displayTitle);
  const textOverflowMode: TextOverflowMode = displayPages.length > 1 ? "paged" : "single";

  return removeServerOnlyBiasFromGeneralScene({
    resultType,
    feedbackCode,
    step: normalizeStep(value.step),
    displayTitle: displayTitle || defaultTitleFor(resultType, feedbackCode),
    displayText: displayPages[0] || displayText || displayTitle || "请按提示继续。",
    fullText: fullText || displayText || displayTitle || "请按提示继续。",
    displayPages,
    textOverflowMode,
    displayHint: displayHint || defaultHintFor(resultType, feedbackCode),
    safeCommandKey,
    humanEscalationSuggestion: Boolean(value.humanEscalationSuggestion) || resultType === "human_suggested",
    requiresPhoto: Boolean(value.requiresPhoto),
  });
}

function aiDecisionFromLegacy(
  legacy: { step: OpsStep; text: string; requiresPhoto: boolean },
  feedbackCode: FeedbackCode,
): AiBrainDecision {
  const resultType: ResultType = legacy.step === "needs_human_expert"
    ? "human_suggested"
    : feedbackCode
    ? feedbackCode === "ai_unavailable" || feedbackCode === "network_error" ? "network_error" : "recognition_problem"
    : legacy.step === "completed"
    ? "completed"
    : "instruction";
  return decisionFromText({
    step: legacy.step,
    text: legacy.text,
    title: defaultTitleFor(resultType, feedbackCode),
    resultType,
    feedbackCode,
    requiresPhoto: legacy.requiresPhoto,
    hint: defaultHintFor(resultType, feedbackCode),
    humanEscalationSuggestion: resultType === "human_suggested",
  });
}

function decisionFromText(input: {
  step: OpsStep;
  text: string;
  title: string;
  resultType: ResultType;
  feedbackCode: FeedbackCode;
  requiresPhoto: boolean;
  hint: string;
  safeCommandKey?: string | null;
  humanEscalationSuggestion?: boolean;
}): AiBrainDecision {
  const fullText = stripDebugText(input.text);
  const displayPages = paginateFullText(fullText);
  return {
    resultType: input.resultType,
    feedbackCode: input.feedbackCode,
    step: input.step,
    displayTitle: stripDebugText(input.title),
    displayText: displayPages[0] || fullText,
    fullText,
    displayPages,
    textOverflowMode: displayPages.length > 1 ? "paged" : "single",
    displayHint: stripDebugText(input.hint),
    safeCommandKey: input.safeCommandKey ?? null,
    humanEscalationSuggestion: Boolean(input.humanEscalationSuggestion),
    requiresPhoto: input.requiresPhoto,
  };
}

function unavailableDecision(): AiBrainDecision {
  return decisionFromText({
    step: "needs_better_photo",
    text: "AI 运维服务暂时不可用。请保持现场画面，稍后重试；如果现场情况紧急，可以选择转人工。",
    title: "AI 暂时不可用",
    resultType: "network_error",
    feedbackCode: "ai_unavailable",
    requiresPhoto: true,
    hint: "请检查网络后重试，或由你决定是否转人工。",
    humanEscalationSuggestion: true,
  });
}

function noPhotoDecision(): AiBrainDecision {
  return decisionFromText({
    step: "locate_server",
    text: "请先把需要判断的现场画面放进绿色框内拍照。AI 需要先看到图片，才能结合语音给你真实反馈或下一步指导。",
    title: "请先拍摄现场画面",
    resultType: "recognition_problem",
    feedbackCode: "insufficient_info",
    requiresPhoto: true,
    hint: "单击中心拍照，长按中心可补充语音。",
  });
}

function humanSuggestedDecision(): AiBrainDecision {
  return decisionFromText({
    step: "needs_human_expert",
    text: "当前指令超出安全范围，我建议请运维专家介入。",
    title: "建议转人工",
    resultType: "human_suggested",
    feedbackCode: null,
    requiresPhoto: false,
    hint: "AI 只是建议，是否转人工由你决定。",
    humanEscalationSuggestion: true,
  });
}

function responseFromDecision(
  sessionId: string,
  decision: AiBrainDecision,
  extras: {
    imageBytes?: number;
    transcript?: string;
    transcriptError?: string;
    voiceIntent?: VoiceIntent;
    retestResult?: Record<string, unknown>;
    canRetake?: boolean;
    canEscalate?: boolean;
  } = {},
): GlassesResponse {
  const displayPages = decision.displayPages.length ? decision.displayPages : paginateFullText(decision.fullText);
  const textOverflowMode: TextOverflowMode = displayPages.length > 1 ? "paged" : "single";
  return {
    ok: true,
    sessionId,
    step: decision.step,
    text: decision.displayText,
    resultType: decision.resultType,
    feedbackCode: decision.feedbackCode,
    displayTitle: decision.displayTitle,
    displayText: displayPages[0] || decision.displayText,
    fullText: decision.fullText,
    displayPages,
    textOverflowMode,
    currentPage: 1,
    totalPages: displayPages.length || 1,
    displayHint: decision.displayHint,
    safeCommandKey: decision.safeCommandKey ?? null,
    humanEscalationSuggestion: decision.humanEscalationSuggestion,
    requiresPhoto: decision.requiresPhoto,
    canRetake: extras.canRetake ?? decision.requiresPhoto,
    canUseVoice: true,
    canEscalate: extras.canEscalate ?? true,
    canHumanEscalate: extras.canEscalate ?? true,
    imageBytes: extras.imageBytes,
    voiceIntent: extras.voiceIntent,
    transcript: extras.transcript,
    transcriptError: extras.transcriptError,
    retestResult: extras.retestResult,
    timestamp: new Date().toISOString(),
  };
}

const serverOnlyBiasPattern = /不是服务器控制台|不是运维终端|请把镜头对准服务器|请对准服务器|服务器屏幕|终端窗口|登录界面|机柜设备/i;
const serverOnlyPageBiasPattern = /没有看到服务器|没有看到.*终端|没有看到.*机柜|不是服务器|不是运维终端|请把镜头对准服务器|请对准服务器|服务器屏幕|终端窗口|登录界面|机柜设备/i;

function removeServerOnlyBiasFromGeneralScene(decision: AiBrainDecision): AiBrainDecision {
  if (
    decision.resultType !== "instruction" ||
    decision.feedbackCode !== null ||
    decision.safeCommandKey ||
    decision.step !== "new_issue_triage"
  ) {
    return decision;
  }
  const biasedTitle = serverOnlyBiasPattern.test(decision.displayTitle);
  const biasedHint = serverOnlyBiasPattern.test(decision.displayHint);
  if (!biasedTitle && !biasedHint) {
    const displayPages = replaceBiasedGeneralScenePages(decision.displayPages);
    if (displayPages === decision.displayPages) {
      return decision;
    }
    return {
      ...decision,
      displayText: displayPages[0] || decision.displayText,
      fullText: displayPages.join(""),
      displayPages,
      textOverflowMode: displayPages.length > 1 ? "paged" : "single",
    };
  }
  const displayPages = replaceBiasedGeneralScenePages(decision.displayPages);
  return {
    ...decision,
    displayTitle: biasedTitle ? "已识别当前画面" : decision.displayTitle,
    displayText: displayPages[0] || decision.displayText,
    fullText: displayPages.join(""),
    displayPages,
    textOverflowMode: displayPages.length > 1 ? "paged" : "single",
    displayHint: biasedHint ? "请长按说明你要 AI 判断什么，或重新拍摄关键位置。" : decision.displayHint,
  };
}

function replaceBiasedGeneralScenePages(displayPages: string[]): string[] {
  const replacement = "请说明你要 AI 判断的问题，例如是否正常、哪里有异常、下一步要做什么。";
  const cleaned = displayPages
    .map((page) => page.trim())
    .filter((page) => page && !serverOnlyPageBiasPattern.test(page));
  if (cleaned.length === displayPages.length) {
    return displayPages;
  }
  if (!cleaned.some((page) => page.includes("要 AI 判断") || page.includes("想让我判断"))) {
    cleaned.push(replacement);
  }
  return cleaned.length ? cleaned : [replacement];
}

function statusFromDecision(decision: AiBrainDecision): "running" | "completed" | "escalated" {
  if (decision.resultType === "completed" || decision.step === "completed") return "completed";
  if (decision.step === "needs_human_expert") return "escalated";
  return "running";
}

function paginateFullText(text: string): string[] {
  const clean = stripDebugText(text).trim();
  if (!clean) return ["请按提示继续。"];
  const hardLimit = 54;
  const sentences = clean
    .split(/(?<=[。！？；\n])/)
    .map((item) => item.trim())
    .filter(Boolean);
  const pages: string[] = [];
  let current = "";
  for (const sentence of sentences.length ? sentences : [clean]) {
    if ((current + sentence).length > hardLimit && current) {
      pages.push(current.trim());
      current = "";
    }
    if (sentence.length > hardLimit) {
      for (let index = 0; index < sentence.length; index += hardLimit) {
        const chunk = sentence.slice(index, index + hardLimit).trim();
        if (chunk) pages.push(chunk);
      }
      continue;
    }
    current = current ? `${current}${sentence}` : sentence;
  }
  if (current.trim()) pages.push(current.trim());
  return pages.length ? pages : [clean];
}

function normalizeStep(value: unknown): OpsStep {
  const allowed = new Set<OpsStep>([
    "locate_server",
    "inspect_console",
    "run_diagnostic_command",
    "confirm_diagnostic_output",
    "run_recovery_command",
    "verify_remote_access",
    "new_issue_triage",
    "completed",
    "needs_better_photo",
    "needs_human_expert",
  ]);
  return allowed.has(value as OpsStep) ? value as OpsStep : "needs_better_photo";
}

function normalizeResultType(value: unknown): ResultType {
  const allowed = new Set<ResultType>([
    "instruction",
    "recognition_problem",
    "network_error",
    "remote_probe",
    "completed",
    "human_suggested",
  ]);
  return allowed.has(value as ResultType) ? value as ResultType : "recognition_problem";
}

function normalizeFeedbackCode(value: unknown): FeedbackCode {
  if (value === null || value === undefined || value === "") return null;
  const allowed = new Set<Exclude<FeedbackCode, null>>([
    "wrong_target",
    "unclear_photo",
    "insufficient_info",
    "voice_unclear",
    "image_voice_conflict",
    "ai_unavailable",
    "network_error",
  ]);
  return allowed.has(value as Exclude<FeedbackCode, null>) ? value as FeedbackCode : "insufficient_info";
}

function nullableString(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function stripDebugText(value: string): string {
  return value
    .replace(/\bhttps?:\/\/\S+/gi, "")
    .replace(/\b(session|bytes|debug|exception|http)\b\s*[:=]?\s*\S*/gi, "")
    .trim();
}

function defaultTitleFor(resultType: ResultType, feedbackCode: FeedbackCode): string {
  if (resultType === "completed") return "SSH 已恢复";
  if (resultType === "human_suggested") return "建议转人工";
  if (resultType === "network_error") return "服务暂时不可用";
  if (feedbackCode === "wrong_target") return "场景不匹配";
  if (feedbackCode === "unclear_photo") return "照片不清楚";
  if (feedbackCode === "voice_unclear") return "语音不清楚";
  if (feedbackCode === "insufficient_info") return "信息不足";
  return "下一步操作";
}

function defaultHintFor(resultType: ResultType, feedbackCode: FeedbackCode): string {
  if (resultType === "completed") return "本次会话完成。";
  if (resultType === "human_suggested") return "AI 只是建议，是否转人工由你决定。";
  if (resultType === "network_error") return "请检查网络后重试，或由你决定是否转人工。";
  if (feedbackCode === "wrong_target") return "请补充你要 AI 判断的目标或重新拍摄关键现场。";
  if (feedbackCode === "unclear_photo") return "请靠近屏幕，避免反光，把文字放进绿色框后重拍。";
  if (feedbackCode === "voice_unclear") return "请重新长按，说短一点。";
  if (feedbackCode === "insufficient_info") return "请补拍完整现场，或长按说明你要 AI 判断什么。";
  return "单击中心可继续拍照，长按中心可补充语音。";
}

function classifyVoiceIntent(text: string): VoiceIntent {
  const normalized = text.trim().toLowerCase();
  if (!normalized) return "unknown";
  if (/转人工|人工|专家|接管/.test(normalized)) return "escalate";
  if (/重拍|重新拍|看不清|不清楚/.test(normalized)) return "retake";
  if (/开始|启动任务/.test(normalized)) return "start_task";
  if (/已输入|输完|执行完|完成了|好了|确认/.test(normalized)) return "confirm_done";
  if (/情况|说明|看到|显示/.test(normalized)) return "describe_scene";
  return "unknown";
}

function normalizeAction(value: unknown): EventAction {
  const allowed = new Set<EventAction>([
    "start_task",
    "console_photo_uploaded",
    "diagnostic_output_uploaded",
    "recovery_command_ready",
    "recovery_output_uploaded",
    "voice_intent",
    "remote_probe_requested",
    "escalate",
    "finish_task",
  ]);
  return allowed.has(value as EventAction) ? value as EventAction : "console_photo_uploaded";
}

function decodeBase64Payload(value: string, fallbackContentType: string): { bytes: Uint8Array; contentType: string } {
  const match = value.match(/^data:([^;]+);base64,(.*)$/s);
  const contentType = match?.[1] ?? fallbackContentType;
  const raw = (match?.[2] ?? value).replace(/\s/g, "");
  const binary = atob(raw);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return { bytes, contentType };
}

function estimateBase64Bytes(value: string): number {
  const raw = value.includes(",") ? value.slice(value.indexOf(",") + 1) : value;
  const normalized = raw.replace(/\s/g, "");
  if (!normalized) return 0;
  const padding = normalized.endsWith("==") ? 2 : normalized.endsWith("=") ? 1 : 0;
  return Math.max(0, Math.floor((normalized.length * 3) / 4) - padding);
}

function ensureDataUrl(value: string, contentType: string): string {
  return value.startsWith("data:") ? value : `data:${contentType};base64,${value}`;
}

function extensionForImage(contentType: string): string {
  if (contentType.includes("png")) return "png";
  if (contentType.includes("webp")) return "webp";
  return "jpg";
}

function extensionForAudio(contentType: string): string {
  if (contentType.includes("wav")) return "wav";
  if (contentType.includes("mpeg")) return "mp3";
  if (contentType.includes("webm")) return "webm";
  return "m4a";
}

function readEnv(): Env {
  const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const opsGlassesApiKey = Deno.env.get("OPS_GLASSES_API_KEY") ?? "";
  if (!supabaseUrl || !serviceRoleKey || !opsGlassesApiKey) {
    throw new Error("SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY and OPS_GLASSES_API_KEY are required");
  }
  return {
    SUPABASE_URL: supabaseUrl,
    SUPABASE_SERVICE_ROLE_KEY: serviceRoleKey,
    OPENAI_API_KEY: Deno.env.get("OPENAI_API_KEY") ?? "",
    OPENAI_BASE_URL: normalizeOpenAiBaseUrl(Deno.env.get("OPENAI_BASE_URL") ?? "https://api.openai.com/v1"),
    OPENAI_TRANSCRIBE_API_KEY: Deno.env.get("OPENAI_TRANSCRIBE_API_KEY") ?? "",
    OPENAI_TRANSCRIBE_BASE_URL: normalizeOpenAiBaseUrl(
      Deno.env.get("OPENAI_TRANSCRIBE_BASE_URL") ?? "https://api.openai.com/v1",
    ),
    OPENAI_VISION_MODEL: Deno.env.get("OPENAI_VISION_MODEL") ?? "gpt-4.1-mini",
    OPENAI_TRANSCRIBE_MODEL: Deno.env.get("OPENAI_TRANSCRIBE_MODEL") ?? "gpt-4o-mini-transcribe",
    DEMO_ASSET_TAG: Deno.env.get("DEMO_ASSET_TAG") ?? "ASSET-CONSOLE-001",
    DEMO_TARGET_HOST: Deno.env.get("DEMO_TARGET_HOST") ?? "192.168.1.50",
    DEMO_TARGET_SSH_PORT: Number(Deno.env.get("DEMO_TARGET_SSH_PORT") ?? 22),
    DEMO_TARGET_APP_PORT: Deno.env.get("DEMO_TARGET_APP_PORT") ? Number(Deno.env.get("DEMO_TARGET_APP_PORT")) : undefined,
    REMOTE_PROBE_MODE: normalizeProbeMode(Deno.env.get("REMOTE_PROBE_MODE")),
    REMOTE_PROBE_URL: Deno.env.get("REMOTE_PROBE_URL") ?? "",
    OPS_GLASSES_API_KEY: opsGlassesApiKey,
    AUTO_MIGRATE: Deno.env.get("AUTO_MIGRATE") === "true",
    SUPABASE_DB_URL: Deno.env.get("SUPABASE_DB_URL") ?? Deno.env.get("OPS_DB_URL") ?? "",
  };
}

function normalizeProbeMode(value: string | undefined): "mock" | "tcp" | "http" {
  return value === "tcp" || value === "http" ? value : "mock";
}

function normalizeOpenAiBaseUrl(value: string): string {
  const trimmed = value.trim().replace(/\/+$/, "");
  return trimmed.endsWith("/v1") ? trimmed : `${trimmed}/v1`;
}

function openAiUrl(env: Env, path: string): string {
  return `${env.OPENAI_BASE_URL}${path.startsWith("/") ? path : `/${path}`}`;
}

function openAiTranscribeUrl(env: Env, path: string): string {
  return `${env.OPENAI_TRANSCRIBE_BASE_URL}${path.startsWith("/") ? path : `/${path}`}`;
}

function isOfficialOpenAiTranscribe(env: Env): boolean {
  return env.OPENAI_TRANSCRIBE_BASE_URL.includes("api.openai.com");
}

function isAuthorized(request: Request, env: Env): boolean {
  const directKey = request.headers.get("x-ops-glasses-key") ?? "";
  const authorization = request.headers.get("authorization") ?? "";
  return directKey === env.OPS_GLASSES_API_KEY || authorization === `Bearer ${env.OPS_GLASSES_API_KEY}`;
}

function urlPath(request: Request): string {
  const url = new URL(request.url);
  const path = url.pathname
    .replace(/^\/functions\/v1\/ops-glasses/, "")
    .replace(/^\/ops-glasses/, "") || "/";
  return path === "/" ? path : path.replace(/\/$/, "");
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json; charset=utf-8" },
  });
}

function stringOr(value: unknown, fallback: string): string {
  return typeof value === "string" ? value : fallback;
}

function stringOrEmpty(value: unknown): string {
  return stringOr(value, "");
}

function clampNumber(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.max(min, Math.min(max, value));
}

function throwIf(error: unknown): asserts error is null | undefined {
  if (error) {
    throw error instanceof Error ? error : new Error(JSON.stringify(error));
  }
}
