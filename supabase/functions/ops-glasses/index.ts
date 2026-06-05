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
  fullText?: string;
  displayPages?: string[];
  textOverflowMode?: TextOverflowMode;
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
  resultType?: ResultType;
  feedbackCode?: FeedbackCode;
  displayTitle?: string;
  displayText?: string;
  fullText?: string;
  displayPages?: string[];
  textOverflowMode?: TextOverflowMode;
  currentPage?: number;
  totalPages?: number;
  displayHint?: string;
  safeCommandKey?: string | null;
  humanEscalationSuggestion?: boolean;
  requiresPhoto: boolean;
  canRetake: boolean;
  canEscalate: boolean;
  imageBytes?: number;
  voiceIntent?: VoiceIntent;
  transcript?: string;
  retestResult?: Record<string, unknown>;
  timestamp: string;
};

type AiObservation = {
  screenType: string;
  recognizedText: string;
  photoQuality: "readable" | "unclear";
  workflowSignal:
    | "login_screen"
    | "shell_prompt"
    | "ssh_service_running"
    | "ssh_service_stopped"
    | "ssh_service_unknown"
    | "photo_unclear"
    | "unexpected_error";
  riskLevel: "low" | "medium" | "high";
  confidence: number;
  needsBetterPhoto: boolean;
  needsHumanExpert: boolean;
};

type Env = {
  SUPABASE_URL: string;
  SUPABASE_SERVICE_ROLE_KEY: string;
  OPENAI_API_KEY?: string;
  OPENAI_BASE_URL: string;
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
const promptVersion = "ssh-console-recovery-v1";

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
  let imageBytes = 0;
  let observation: AiObservation | null = null;

  if (typeof payload.imageBase64 === "string" && payload.imageBase64.trim()) {
    const uploaded = await uploadImage(supabase, session.id, payload.imageBase64, stringOr(payload.imageKind, "console"));
    imageId = uploaded.imageId;
    imageBytes = uploaded.imageBytes;

    if (env.OPENAI_API_KEY) {
      observation = await analyzeConsoleImage({ supabase, env, sessionId: session.id, imageId, imageBase64: payload.imageBase64 });
    }
  }

  const next = await decideNextStep(supabase, session, action, observation, payload);
  const event = await insertEvent(supabase, {
    sessionId: session.id,
    step: next.step,
    action,
    imageId,
    instructionText: next.text,
    payload: { imageBytes, observation },
  });

  if (imageId) {
    await supabase.from("ops_images").update({ event_id: event.id }).eq("id", imageId);
  }

  await updateSession(supabase, session.id, next.step, next.text, next.status);

  return {
    ok: true,
    sessionId: session.id,
    step: next.step,
    text: next.text,
    requiresPhoto: next.requiresPhoto,
    canRetake: true,
    canEscalate: true,
    imageBytes,
    timestamp: new Date().toISOString(),
  };
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
        transcript = await transcribeAudio(env, audio.bytes, audio.contentType);
      } catch (error) {
        transcriptError = error instanceof Error ? error.message : String(error);
      }
    }
  }

  const voiceIntent = classifyVoiceIntent(transcript);
  const next = voiceIntentToStep(session.current_step as OpsStep, voiceIntent);

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

  await insertEvent(supabase, {
    sessionId,
    step: next.step,
    action: "voice_intent",
    voiceInputId: voiceInput.id,
    instructionText: next.text,
    payload: { transcript, voiceIntent, transcriptError },
  });
  await updateSession(supabase, sessionId, next.step, next.text, next.status);

  return {
    ok: true,
    sessionId,
    step: next.step,
    text: next.text,
    requiresPhoto: next.requiresPhoto,
    canRetake: true,
    canEscalate: true,
    transcript,
    voiceIntent,
    timestamp: new Date().toISOString(),
  };
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

  return {
    ok: true,
    sessionId,
    step,
    text: result.text,
    requiresPhoto: false,
    canRetake: false,
    canEscalate: step !== "completed",
    retestResult: result,
    timestamp: new Date().toISOString(),
  };
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
  return {
    ok: true,
    sessionId,
    step: "needs_human_expert",
    text,
    requiresPhoto: false,
    canRetake: false,
    canEscalate: false,
    timestamp: new Date().toISOString(),
  };
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

async function decideNextStep(
  supabase: Supabase,
  session: Record<string, unknown>,
  action: EventAction,
  observation: AiObservation | null,
  payload: Record<string, unknown>,
): Promise<{ step: OpsStep; text: string; requiresPhoto: boolean; status: "running" | "completed" | "escalated" }> {
  const currentStep = String(session.current_step || "locate_server") as OpsStep;

  if (action === "escalate") {
    return {
      step: "needs_human_expert",
      text: "已停止自动指导，并转人工运维专家接管。",
      requiresPhoto: false,
      status: "escalated",
    };
  }

  if (observation?.needsHumanExpert || observation?.riskLevel === "high") {
    return {
      step: "needs_human_expert",
      text: "AI 识别到高风险或异常状态。请停止操作，转人工运维专家接管。",
      requiresPhoto: false,
      status: "escalated",
    };
  }

  if (observation?.workflowSignal === "unexpected_error") {
    return {
      step: "needs_better_photo",
      text: "当前画面不像服务器本地控制台或终端输出。请把眼镜对准服务器本地屏幕、登录界面或命令行窗口重新拍摄，不要拍聊天窗口、浏览器资料或普通桌面。",
      requiresPhoto: true,
      status: "running",
    };
  }

  if (observation?.needsBetterPhoto || observation?.workflowSignal === "photo_unclear") {
    return {
      step: "needs_better_photo",
      text: "照片不清晰，无法可靠识别。请靠近控制台屏幕重新拍摄，避免反光。",
      requiresPhoto: true,
      status: "running",
    };
  }

  const commands = await loadSafeCommands(supabase);

  const isNewStartWithoutPhoto = !stringOrEmpty(payload.sessionId) &&
    (action === "start_task" || !stringOrEmpty(payload.imageBase64));

  if (isNewStartWithoutPhoto) {
    return {
      step: "locate_server",
      text: [
        `服务器 ${session.target_asset} 远程 SSH 不可达。`,
        "请到服务器本地控制台前，拍摄屏幕和资产标签。",
        "单击拍摄，长按可转人工。",
      ].join("\n"),
      requiresPhoto: true,
      status: "running",
    };
  }

  if (currentStep === "locate_server" || action === "console_photo_uploaded") {
    return {
      step: "run_diagnostic_command",
      text: [
        "已收到本地控制台照片。",
        `请输入：${commands.ssh_status}`,
        "只输入这一条命令。执行后请单击拍摄完整输出。",
      ].join("\n"),
      requiresPhoto: true,
      status: "running",
    };
  }

  if (currentStep === "run_diagnostic_command" || action === "diagnostic_output_uploaded") {
    const signal = observation?.workflowSignal ?? "ssh_service_unknown";
    if (signal === "ssh_service_running") {
      return {
        step: "verify_remote_access",
        text: "控制台显示 SSH 服务可能已运行。现在开始远程复测，请等待结果。",
        requiresPhoto: false,
        status: "running",
      };
    }
    return {
      step: "run_recovery_command",
      text: [
        "SSH 服务未运行或状态异常。",
        `请输入：${commands.ssh_start}`,
        "只允许输入这一条恢复命令。执行后请拍摄命令输出。",
      ].join("\n"),
      requiresPhoto: true,
      status: "running",
    };
  }

  if (currentStep === "run_recovery_command" || action === "recovery_output_uploaded") {
    return {
      step: "verify_remote_access",
      text: "已收到恢复命令输出。后台将复测 Ping、22 端口和应用端口，请等待结果。",
      requiresPhoto: false,
      status: "running",
    };
  }

  return {
    step: "needs_better_photo",
    text: "当前步骤信息不足。请重新拍摄清晰的本地控制台屏幕。",
    requiresPhoto: true,
    status: "running",
  };
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

async function analyzeConsoleImage(
  { supabase, env, sessionId, imageId, imageBase64 }: {
    supabase: Supabase;
    env: Env;
    sessionId: string;
    imageId: string;
    imageBase64: string;
  },
): Promise<AiObservation> {
  const started = Date.now();
  const model = env.OPENAI_VISION_MODEL;

  try {
    const { raw, outputText } = await requestVisionObservation(env, model, imageBase64);
    const observation = validateObservation(JSON.parse(extractJsonObject(outputText)));

    const { data: requestRow, error: requestError } = await supabase.from("ai_requests").insert({
      session_id: sessionId,
      image_id: imageId,
      model,
      prompt_version: promptVersion,
      status: "success",
      latency_ms: Date.now() - started,
      raw_response: raw,
    }).select("id").single();
    throwIf(requestError);

    const { error: observationError } = await supabase.from("ai_observations").insert({
      session_id: sessionId,
      image_id: imageId,
      ai_request_id: requestRow.id,
      screen_type: observation.screenType,
      recognized_text: observation.recognizedText,
      workflow_signal: observation.workflowSignal,
      risk_level: observation.riskLevel,
      confidence: observation.confidence,
      raw_json: observation,
    });
    throwIf(observationError);
    return observation;
  } catch (error) {
    await supabase.from("ai_requests").insert({
      session_id: sessionId,
      image_id: imageId,
      model,
      prompt_version: promptVersion,
      status: "failed",
      latency_ms: Date.now() - started,
      error_message: error instanceof Error ? error.message : String(error),
    });
    return {
      screenType: "unknown",
      recognizedText: "",
      photoQuality: "readable",
      workflowSignal: "ssh_service_unknown",
      riskLevel: "medium",
      confidence: 0.4,
      needsBetterPhoto: false,
      needsHumanExpert: false,
    };
  }
}

async function requestVisionObservation(
  env: Env,
  model: string,
  imageBase64: string,
): Promise<{ raw: Record<string, unknown>; outputText: string }> {
  const prompt = consoleObservationPrompt();
  const imageUrl = ensureDataUrl(imageBase64, "image/jpeg");
  const responseResult = await callResponsesVision(env, model, prompt, imageUrl);
  if (responseResult.ok) return responseResult;

  const chatResult = await callChatCompletionsVision(env, model, prompt, imageUrl);
  if (chatResult.ok) return chatResult;
  throw new Error(JSON.stringify({ responses: responseResult.raw, chat_completions: chatResult.raw }));
}

async function callResponsesVision(
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
          name: "console_observation",
          schema: observationJsonSchema(),
          strict: true,
        },
      },
    }),
  });
  const raw = await safeJson(response);
  if (!response.ok) return { ok: false, raw };

  const outputText = stringOr(raw.output_text, "") ||
    (Array.isArray(raw.output)
      ? raw.output.flatMap((item: { content?: Array<{ text?: string }> }) => item.content ?? [])
        .map((content: { text?: string }) => content.text ?? "")
        .join("")
      : "");
  return { ok: true, raw, outputText };
}

async function callChatCompletionsVision(
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

function consoleObservationPrompt(): string {
  return [
    "如果画面是聊天软件、网页资料、普通桌面或与服务器本地控制台无关，请将 workflowSignal 设为 unexpected_error，needsBetterPhoto 设为 false。",
    "如果画面确实是服务器本地控制台、登录界面或终端，但文字模糊、反光或过远，请将 workflowSignal 设为 photo_unclear，needsBetterPhoto 设为 true。",
    "你是企业内部服务器运维眼镜的控制台识别模块。",
    "只识别照片内容并输出 JSON，不要生成任何 shell 命令。",
    "判断照片是否清晰、是否是登录界面/shell/命令输出、SSH 服务状态。",
    "允许 workflowSignal: login_screen, shell_prompt, ssh_service_running, ssh_service_stopped, ssh_service_unknown, photo_unclear, unexpected_error。",
    "输出 JSON 字段: screenType, recognizedText, photoQuality, workflowSignal, riskLevel, confidence, needsBetterPhoto, needsHumanExpert。",
  ].join("\n");
}

function observationJsonSchema(): Record<string, unknown> {
  return {
    type: "object",
    additionalProperties: false,
    required: [
      "screenType",
      "recognizedText",
      "photoQuality",
      "workflowSignal",
      "riskLevel",
      "confidence",
      "needsBetterPhoto",
      "needsHumanExpert",
    ],
    properties: {
      screenType: { type: "string" },
      recognizedText: { type: "string" },
      photoQuality: { type: "string", enum: ["readable", "unclear"] },
      workflowSignal: {
        type: "string",
        enum: [
          "login_screen",
          "shell_prompt",
          "ssh_service_running",
          "ssh_service_stopped",
          "ssh_service_unknown",
          "photo_unclear",
          "unexpected_error",
        ],
      },
      riskLevel: { type: "string", enum: ["low", "medium", "high"] },
      confidence: { type: "number" },
      needsBetterPhoto: { type: "boolean" },
      needsHumanExpert: { type: "boolean" },
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

function extractJsonObject(value: string): string {
  const trimmed = value.trim();
  if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed;
  const match = trimmed.match(/\{[\s\S]*\}/);
  if (!match) throw new Error(`ai_json_not_found:${trimmed.slice(0, 200)}`);
  return match[0];
}

async function transcribeAudio(env: Env, bytes: Uint8Array, contentType: string): Promise<string> {
  const form = new FormData();
  form.append("model", env.OPENAI_TRANSCRIBE_MODEL);
  const audioBuffer = bytes.slice().buffer as ArrayBuffer;
  form.append("file", new Blob([audioBuffer], { type: contentType }), `voice.${extensionForAudio(contentType)}`);
  const response = await fetch(openAiUrl(env, "/audio/transcriptions"), {
    method: "POST",
    headers: { Authorization: `Bearer ${env.OPENAI_API_KEY}` },
    body: form,
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

function voiceIntentToStep(currentStep: OpsStep, voiceIntent: VoiceIntent) {
  if (voiceIntent === "escalate") {
    return {
      step: "needs_human_expert" as OpsStep,
      text: "已转人工运维专家，请保持现场等待进一步指示。",
      requiresPhoto: false,
      status: "escalated" as const,
    };
  }
  if (voiceIntent === "retake") {
    return {
      step: "needs_better_photo" as OpsStep,
      text: "请重新拍摄清晰的控制台屏幕，文字尽量完整。",
      requiresPhoto: true,
      status: "running" as const,
    };
  }
  if (voiceIntent === "confirm_done") {
    return {
      step: currentStep === "run_recovery_command" ? "verify_remote_access" as OpsStep : "confirm_diagnostic_output" as OpsStep,
      text: currentStep === "run_recovery_command"
        ? "收到确认。后台开始复测 SSH，请等待结果。"
        : "收到确认。请拍摄完整命令输出，方便 AI 判断下一步。",
      requiresPhoto: currentStep !== "run_recovery_command",
      status: "running" as const,
    };
  }
  return {
    step: currentStep,
    text: "已收到语音，但没有识别出明确动作。请单击继续、重拍，或说“转人工”。",
    requiresPhoto: true,
    status: "running" as const,
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

function validateObservation(value: Record<string, unknown>): AiObservation {
  const workflowSignal = stringOr(value.workflowSignal, "ssh_service_unknown") as AiObservation["workflowSignal"];
  const allowedSignals = new Set([
    "login_screen",
    "shell_prompt",
    "ssh_service_running",
    "ssh_service_stopped",
    "ssh_service_unknown",
    "photo_unclear",
    "unexpected_error",
  ]);
  return {
    screenType: stringOr(value.screenType, "unknown"),
    recognizedText: stringOr(value.recognizedText, ""),
    photoQuality: value.photoQuality === "unclear" ? "unclear" : "readable",
    workflowSignal: allowedSignals.has(workflowSignal) ? workflowSignal : "ssh_service_unknown",
    riskLevel: value.riskLevel === "high" ? "high" : value.riskLevel === "medium" ? "medium" : "low",
    confidence: clampNumber(Number(value.confidence ?? 0.5), 0, 1),
    needsBetterPhoto: Boolean(value.needsBetterPhoto),
    needsHumanExpert: Boolean(value.needsHumanExpert),
  };
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
