# Air3 V2 APK AI Brain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the second-version Air3 operations APK around the confirmed architecture: the main AI is the brain that directly guides the field operator, while Air3 captures images/audio, the self-hosted STT model converts speech to text, Supabase stores and routes context, and the HUD displays the AI guidance.

**Architecture:** Use the proven `air3-native-camera-test` Camera2 APK as the installable Air3 base. Supabase Edge Function receives photos and voice, calls the self-hosted STT endpoint, builds a multimodal AI context from image + transcript + current step + task goal, calls the main AI, stores all evidence, and returns a structured HUD response. Expo remains the UI/state prototype and design reference; Figma/即时设计 is used to align HUD pages before native implementation.

**Tech Stack:** Native Android Java Camera2, Expo Router / React Native, Supabase Edge Functions/Postgres/Storage/automigrate, OpenAI-compatible self-hosted STT and main AI APIs, Figma/即时设计, Git/GitHub, adb/Test Android Apps.

---

## Scope And Non-Negotiable Rules

- The main AI is the brain and direct field guide. The backend does not replace the AI as an operations decision-maker.
- The self-hosted speech-to-text model is the ear. It only returns transcript text.
- Image + transcript + current step + task goal must be sent together to the main AI whenever voice context exists.
- The field operator decides whether to escalate to a human. The AI may suggest escalation; it must not force it.
- The backend may validate protocol shape, log evidence, and enforce basic safety boundaries such as command allowlist matching.
- The Air3 native Camera2 APK remains the real-device base for V2.
- Expo is a prototype/reference or later companion surface, not a replacement for Air3 real camera capability.
- Operator HUD must not show raw HTTP status, bytes, session IDs, Java exception classes, English debug text, or secret values.

---

## File Structure

- Modify: `docs/air3-ops-glasses-architecture.md`  
  Locks the V2 architecture language: AI brain, STT ear, Air3 eyes, backend routing/records/safety, operator-owned escalation.

- Create: `docs/air3-v2-hud-pages.md`  
  Defines every HUD page/state and the exact Chinese operator copy.

- Create: `docs/air3-v2-response-contract.md`  
  Defines the API response contract consumed by Expo and native APK.

- Modify: `air3-ops-expo-app/app/index.tsx`  
  Updates the prototype HUD to model V2 pages and structured response states.

- Modify: `supabase/functions/ops-glasses/index.ts`  
  Adds multimodal AI context builder, unified AI brain call, structured HUD response contract, STT-to-AI flow, and response persistence.

- Modify: `supabase/functions/ops-glasses/automigrate.ts`  
  Adds schema support for AI brain decisions/context bundles while preserving existing tables.

- Modify: `supabase/migrations/202606040001_air3_ops_glasses.sql`  
  Keeps the SQL backup aligned with automigrate for audit/reference.

- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`  
  Parses V2 structured response fields and renders HUD states by `resultType` / `feedbackCode`, not just `step`.

- Modify: `air3-native-camera-test/build-native-apk.ps1`  
  Keeps generated config ephemeral, adds versioned APK output, and supports version overrides for release checkpoints.

- Modify/Create: `.gitignore`  
  Protects local keys, generated build outputs, node modules, screenshots/log dumps, and temporary captures.

- Update: `agent_memory/context.md`, `agent_memory/progress.md`, `agent_memory/bugs.md`  
  Keeps project memory consistent at milestones.

---

## Task 1: Lock V2 Architecture And Contracts

**Files:**
- Modify: `docs/air3-ops-glasses-architecture.md`
- Create: `docs/air3-v2-hud-pages.md`
- Create: `docs/air3-v2-response-contract.md`
- Update: `agent_memory/progress.md`

- [x] **Step 1: Rewrite architecture language**

Replace the old “backend orchestration decides” language with this rule:

```markdown
## V2 固定架构

主 AI 是系统大脑，直接指导现场小白操作。后端不替主 AI 做运维判断，只负责接收、保存、组装上下文、调用模型、记录过程、校验返回格式和基础安全边界。小白是执行人，并最终决定是否人工介入。
```

- [x] **Step 2: Add the V2 data flow**

Add this flow to `docs/air3-ops-glasses-architecture.md`:

```text
Air3 眼镜拍照/录音
  -> Supabase 保存图片和音频
  -> 自部署 STT 把音频转成 transcript
  -> AI Context Builder 绑定 imageId + voiceInputId + transcript + currentStep + taskGoal
  -> 主 AI 读取图片和文字后生成现场指导
  -> 后端记录并校验格式/安全边界
  -> Air3 HUD 显示 AI 指导
  -> 小白执行并决定是否转人工
```

- [x] **Step 3: Create HUD page spec**

Create `docs/air3-v2-hud-pages.md` with these page states:

```markdown
# Air3 V2 HUD 页面状态

1. 相机就绪：提示对准服务器本地控制台。
2. 拍照上传：显示照片正在上传。
3. 语音录入：显示正在录音。
4. 语音转写：显示自部署语音模型正在转文字。
5. AI 综合分析：显示主 AI 正在结合图片和文字生成指导。
6. 操作指令：显示主 AI 给小白的下一步。
7. 拍错目标：提示只拍登录界面、黑底终端或命令输出。
8. 照片不清晰：提示靠近屏幕、避免反光、放入绿色框。
9. 信息不足：提示拍完整命令输出。
10. 语音不清楚：提示重新长按，说短一点。
11. 网络错误：提示确认网络后重试。
12. 远程复测：提示正在检测 SSH 远程访问。
13. 完成：提示 SSH 已恢复。
14. 建议转人工：AI 建议，小白自行决定。
```

- [x] **Step 4: Create response contract**

Create `docs/air3-v2-response-contract.md` with this canonical success shape:

```json
{
  "ok": true,
  "sessionId": "uuid",
  "step": "run_recovery_command",
  "resultType": "instruction",
  "feedbackCode": null,
  "displayTitle": "请输入恢复命令",
  "displayText": "sudo systemctl start ssh",
  "displayHint": "输入完成后，单击中心拍摄输出结果。",
  "safeCommandKey": "ssh_start",
  "transcript": "我已经输入完成了",
  "humanEscalationSuggestion": false,
  "canRetake": true,
  "canUseVoice": true,
  "canHumanEscalate": true,
  "requiresPhoto": true,
  "timestamp": "2026-06-05T00:00:00.000Z"
}
```

And this recognition-problem shape:

```json
{
  "ok": true,
  "sessionId": "uuid",
  "step": "needs_better_photo",
  "resultType": "recognition_problem",
  "feedbackCode": "wrong_target",
  "displayTitle": "拍错目标",
  "displayText": "当前画面不是服务器控制台。",
  "displayHint": "请只拍登录界面、黑底终端或命令输出。",
  "humanEscalationSuggestion": false,
  "canRetake": true,
  "canUseVoice": true,
  "canHumanEscalate": true,
  "requiresPhoto": true,
  "timestamp": "2026-06-05T00:00:00.000Z"
}
```

- [x] **Step 5: Verify documentation**

Run:

```powershell
rg -n "AI 是.*大脑|后端不替主 AI|resultType|feedbackCode|小白" docs agent_memory
```

Expected: the new rules appear in architecture, contract, HUD pages, and project memory.

- [x] **Step 6: Commit documentation checkpoint**

```powershell
git status --short
git add docs/air3-ops-glasses-architecture.md docs/air3-v2-hud-pages.md docs/air3-v2-response-contract.md agent_memory/progress.md
git commit -m "docs: lock Air3 V2 AI brain architecture"
```

If the root is not a Git repo, initialize it first in Task 8 before committing.

---

## Task 2: Figma / 即时设计 HUD Alignment

**Files:**
- Read: `docs/air3-v2-hud-pages.md`
- Read: `docs/air3-hud-post-demo-fix-design.md`
- Optional Figma file: existing `https://www.figma.com/design/zEyO259e18lqpicT6IfNjg`

- [x] **Step 1: Load Figma use skill before any Figma write**

Read the local `figma-use` skill before calling `use_figma`. If creating a new file, read `figma-create-new-file` first.

- [x] **Step 2: Create/update one 1920x1080 HUD frame per state**

Frames to represent:

```text
相机就绪
拍照上传
语音录入
语音转写
AI 综合分析
操作指令
拍错目标
照片不清晰
信息不足
语音不清楚
网络错误
远程复测
完成
建议转人工
```

- [x] **Step 3: Match V2 design constraints**

Each frame must follow:

```text
全屏相机预览背景
顶部：叮当X AI 运维眼镜 / 服务器 SSH 恢复
中部：绿色取景框 + 主 AI 指导
底部：短状态 + 操作提示
不显示 debug、HTTP、bytes、session、异常类名
```

- [x] **Step 4: Export design reference screenshot**

Save a screenshot or Figma URL in `docs/air3-v2-hud-pages.md` under a section named `设计链接`.

- [x] **Step 5: Commit design checkpoint**

```powershell
git add docs/air3-v2-hud-pages.md docs/air3-hud-post-demo-fix-design.md
git commit -m "design: align Air3 V2 HUD states"
```

---

## Task 3: Expo Prototype For V2 HUD States

**Files:**
- Modify: `air3-ops-expo-app/app/index.tsx`
- Verify: `air3-ops-expo-app/package.json`

- [ ] **Step 1: Replace current step list with V2 result states**

Use this state model in `air3-ops-expo-app/app/index.tsx`:

```ts
type ResultType =
  | "ready"
  | "uploading"
  | "recording_voice"
  | "transcribing_voice"
  | "ai_analyzing"
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

type HudState = {
  id: string;
  resultType: ResultType;
  feedbackCode: FeedbackCode;
  step: string;
  displayTitle: string;
  displayText: string;
  displayHint: string;
  safeCommandKey?: string;
  humanEscalationSuggestion: boolean;
  tone: "blue" | "green" | "yellow" | "red";
};
```

- [ ] **Step 2: Add all V2 states**

Include sample states for:

```text
ready, uploading, recording_voice, transcribing_voice, ai_analyzing,
instruction/ssh_status, instruction/ssh_start,
recognition_problem/wrong_target,
recognition_problem/unclear_photo,
recognition_problem/insufficient_info,
recognition_problem/voice_unclear,
network_error, remote_probe, completed, human_suggested
```

- [ ] **Step 3: Keep the UI landscape HUD**

Keep:

```text
16:9 frame
green guide frame
top title
large center instruction
bottom operation strip
no nested cards
no marketing hero
no raw debug data
```

- [ ] **Step 4: Typecheck Expo**

Run:

```powershell
Set-Location "C:\Users\59979\Documents\New project\air3-ops-expo-app"
npm run typecheck
```

Expected: TypeScript passes without errors.

- [ ] **Step 5: Run Expo web preview**

Run:

```powershell
Set-Location "C:\Users\59979\Documents\New project\air3-ops-expo-app"
npm run web
```

Expected: Expo starts and prints a local web URL.

- [ ] **Step 6: Verify with browser or screenshot**

Open the local Expo URL. Check:

```text
All text fits in the 16:9 HUD.
Command text is readable.
Problem states are visibly different from instruction states.
No debug data appears.
```

- [ ] **Step 7: Commit Expo checkpoint**

```powershell
git add air3-ops-expo-app/app/index.tsx
git commit -m "feat: prototype Air3 V2 HUD states"
```

---

## Task 3.5: Long AI Text HUD Contract

**Reason:** The user confirmed that AI guidance text may be long, and all operator-safe AI text must be visible on the glasses. The HUD must therefore page long AI text instead of overflowing, truncating, or hiding it.

**Files:**
- Modify: `docs/air3-v2-response-contract.md`
- Modify: `docs/air3-v2-hud-pages.md`
- Modify: `air3-ops-expo-app/app/index.tsx`
- Modify: `air3-ops-expo-app/scripts/verify-hud-contract.mjs`

- [x] **Step 1: Extend the response contract**

Add `fullText`, `displayPages`, `textOverflowMode`, `currentPage`, and `totalPages`. Long text must set `textOverflowMode="paged"` and cover the complete operator-safe AI answer in `displayPages`.

- [x] **Step 2: Define HUD pagination behavior**

Center click turns to the next page before moving to the next action. Back returns to the previous page before retake/back-step behavior. Long press remains voice confirm/supplement.

- [x] **Step 3: Add an Expo long-text sample**

Add one `instruction` sample with `displayPages` and a page indicator while keeping the fixed glasses HUD layout.

- [x] **Step 4: Lock verification**

Update `npm run verify:hud` so removing the long-text contract or pagination sample fails.

---

## Task 4: Supabase Schema For AI Brain Context

**Files:**
- Modify: `supabase/functions/ops-glasses/automigrate.ts`
- Modify: `supabase/migrations/202606040001_air3_ops_glasses.sql`
- Modify: `supabase/functions/ops-glasses/index.ts`

- [x] **Step 1: Add new enum values**

In `automigrate.ts`, add:

```ts
{
  name: "public.ai_decision_result_type",
  values: ["instruction", "recognition_problem", "network_error", "remote_probe", "completed", "human_suggested"],
},
{
  name: "public.ai_feedback_code",
  values: [
    "wrong_target",
    "unclear_photo",
    "insufficient_info",
    "voice_unclear",
    "image_voice_conflict",
    "ai_unavailable",
    "network_error",
  ],
},
```

- [x] **Step 2: Add context bundle table**

Add a table definition:

```ts
{
  name: "public.ai_context_bundles",
  columns: [
    "id uuid primary key default gen_random_uuid()",
    "session_id uuid not null references public.ops_sessions(id) on delete cascade",
    "image_id uuid references public.ops_images(id) on delete set null",
    "voice_input_id uuid references public.voice_inputs(id) on delete set null",
    "current_step public.ops_step not null",
    "task_goal text not null default '指导现场人员恢复服务器 SSH 远程访问'",
    "transcript text not null default ''",
    "context_json jsonb not null default '{}'::jsonb",
    "context_version text not null default 'air3-v2-ai-brain-v1'",
    "created_at timestamptz not null default now()",
  ],
  indexes: ["create index if not exists ai_context_bundles_session_id_idx on public.ai_context_bundles(session_id, created_at desc)"],
  rls: true,
},
```

- [x] **Step 3: Add AI decisions table**

Add a table definition:

```ts
{
  name: "public.ai_decisions",
  columns: [
    "id uuid primary key default gen_random_uuid()",
    "session_id uuid not null references public.ops_sessions(id) on delete cascade",
    "context_bundle_id uuid references public.ai_context_bundles(id) on delete set null",
    "ai_request_id uuid references public.ai_requests(id) on delete set null",
    "result_type public.ai_decision_result_type not null",
    "feedback_code public.ai_feedback_code",
    "step public.ops_step not null",
    "safe_command_key text",
    "display_title text not null",
    "display_text text not null",
    "display_hint text not null",
    "human_escalation_suggestion boolean not null default false",
    "raw_json jsonb not null default '{}'::jsonb",
    "created_at timestamptz not null default now()",
  ],
  indexes: ["create index if not exists ai_decisions_session_id_idx on public.ai_decisions(session_id, created_at desc)"],
  rls: true,
},
```

- [x] **Step 4: Mirror schema in SQL backup**

Add matching enum/table SQL to `supabase/migrations/202606040001_air3_ops_glasses.sql`.

- [x] **Step 5: Validate Supabase assets**

Run:

```powershell
Set-Location "C:\Users\59979\Documents\New project"
npm run validate:supabase
```

Expected:

```text
Supabase assets validation passed.
Text balance check passed.
```

- [x] **Step 6: Commit schema checkpoint**

```powershell
git add supabase/functions/ops-glasses/automigrate.ts supabase/migrations/202606040001_air3_ops_glasses.sql
git commit -m "feat: add AI brain context schema"
```

---

## Task 5: Supabase AI Brain Flow

**Files:**
- Modify: `supabase/functions/ops-glasses/index.ts`
- Test by command: `deno check`, `npm run validate:supabase`, HTTP calls to `/sessions/events` and `/sessions/:id/voice`

- [x] **Step 1: Add response types**

Add these types near the current `GlassesResponse` type:

```ts
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

type AiBrainDecision = {
  resultType: ResultType;
  feedbackCode: FeedbackCode;
  step: OpsStep;
  displayTitle: string;
  displayText: string;
  displayHint: string;
  safeCommandKey?: string | null;
  humanEscalationSuggestion: boolean;
  requiresPhoto: boolean;
};
```

- [x] **Step 2: Extend `GlassesResponse`**

Add fields:

```ts
resultType: ResultType;
feedbackCode: FeedbackCode;
displayTitle: string;
displayText: string;
displayHint: string;
safeCommandKey?: string | null;
humanEscalationSuggestion: boolean;
canUseVoice: boolean;
```

Keep `text` temporarily for backward compatibility with the current APK.

- [x] **Step 3: Build context bundle after photo upload**

Add a helper:

```ts
async function createContextBundle(
  supabase: Supabase,
  input: {
    session: Record<string, unknown>;
    imageId: string | null;
    voiceInputId: string | null;
    transcript: string;
    payload: Record<string, unknown>;
  },
) {
  const context = {
    taskGoal: "指导现场人员恢复服务器 SSH 远程访问",
    currentStep: String(input.session.current_step || "locate_server"),
    operatorProfile: "现场小白，不懂 Linux 运维，需要一步一步指导",
    transcript: input.transcript,
    imageId: input.imageId,
    voiceInputId: input.voiceInputId,
    source: input.payload.source ?? "",
    timestamp: new Date().toISOString(),
  };
  const { data, error } = await supabase.from("ai_context_bundles").insert({
    session_id: input.session.id,
    image_id: input.imageId,
    voice_input_id: input.voiceInputId,
    current_step: String(input.session.current_step || "locate_server"),
    transcript: input.transcript,
    context_json: context,
  }).select("id, context_json").single();
  throwIf(error);
  return data as { id: string; context_json: Record<string, unknown> };
}
```

- [x] **Step 4: Replace voice-only intent flow**

Change `handleVoice` so it:

```text
1. saves audio
2. calls transcribeAudio
3. inserts voice_inputs
4. loads the latest image for the same session
5. creates an ai_context_bundles row
6. calls the main AI brain with latest image + transcript
7. stores ai_decisions
8. returns structured HUD response
```

Do not use `voiceIntentToStep` as the primary V2 path. Keep it only as fallback if the main AI is unavailable.

- [x] **Step 5: Add latest-image lookup**

Add:

```ts
async function latestImageForSession(supabase: Supabase, sessionId: string): Promise<{ id: string; file_path: string } | null> {
  const { data, error } = await supabase.from("ops_images")
    .select("id, file_path")
    .eq("session_id", sessionId)
    .order("created_at", { ascending: false })
    .limit(1)
    .maybeSingle();
  throwIf(error);
  return data ?? null;
}
```

- [x] **Step 6: Add AI brain prompt**

Add:

```ts
function aiBrainPrompt(context: Record<string, unknown>, commands: Record<string, string>): string {
  return [
    "你是 Air3 AI 运维眼镜的主 AI 大脑，直接指导现场小白恢复服务器 SSH 远程访问。",
    "你会同时获得服务器控制台图片、现场人员语音转写文字、当前步骤和任务目标。",
    "现场人员不是运维专家，请用短句、明确、可执行的中文指导。",
    "如果需要展示命令，只能使用 allowedCommands 中的命令文本，不要自由生成新命令。",
    "如果画面不是服务器控制台，返回 resultType=recognition_problem, feedbackCode=wrong_target。",
    "如果画面是控制台但文字不清，返回 feedbackCode=unclear_photo。",
    "如果缺少关键命令输出，返回 feedbackCode=insufficient_info。",
    "如果语音转写不清楚，返回 feedbackCode=voice_unclear。",
    "如果建议人工介入，返回 resultType=human_suggested；最终是否人工介入由小白决定。",
    "只返回 JSON，不要 Markdown。",
    JSON.stringify({ context, allowedCommands: commands }),
  ].join("\n");
}
```

- [x] **Step 7: Add response schema enforcement**

The main AI response must include:

```text
resultType
feedbackCode
step
displayTitle
displayText
displayHint
safeCommandKey
humanEscalationSuggestion
requiresPhoto
```

When `safeCommandKey` is present, check it exists in `safe_commands`. If not, return a `human_suggested` response:

```json
{
  "resultType": "human_suggested",
  "feedbackCode": null,
  "displayTitle": "建议转人工",
  "displayText": "当前指令超出安全范围，我建议请运维专家介入。",
  "displayHint": "你可以长按中心选择转人工，或重新拍摄补充信息。"
}
```

- [x] **Step 8: Store AI decision**

Insert into `ai_decisions` after each AI brain call:

```ts
await supabase.from("ai_decisions").insert({
  session_id: session.id,
  context_bundle_id: contextBundle.id,
  ai_request_id: aiRequestId,
  result_type: decision.resultType,
  feedback_code: decision.feedbackCode,
  step: decision.step,
  safe_command_key: decision.safeCommandKey ?? null,
  display_title: decision.displayTitle,
  display_text: decision.displayText,
  display_hint: decision.displayHint,
  human_escalation_suggestion: decision.humanEscalationSuggestion,
  raw_json: decision,
});
```

- [x] **Step 9: Deno check**

Run:

```powershell
& "C:\Users\59979\.deno\bin\deno.exe" check --config "C:\Users\59979\Documents\New project\supabase\functions\ops-glasses\deno.json" "C:\Users\59979\Documents\New project\supabase\functions\ops-glasses\index.ts"
```

Expected: no type errors.

- [x] **Step 10: Validate Supabase assets**

Run:

```powershell
Set-Location "C:\Users\59979\Documents\New project"
npm run validate:supabase
```

Expected: validation passes.

- [x] **Step 11: Commit backend checkpoint**

```powershell
git add supabase/functions/ops-glasses/index.ts
git commit -m "feat: route Air3 context through AI brain"
```

---

## Task 6: Native APK Structured HUD Rendering

**Files:**
- Modify: `air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java`
- Build: `air3-native-camera-test/build-native-apk.ps1`

- [x] **Step 1: Add HUD response value object**

Inside `MainActivity`, add:

```java
private static final class HudResponse {
    final String sessionId;
    final String step;
    final String resultType;
    final String feedbackCode;
    final String displayTitle;
    final String displayText;
    final String displayHint;
    final boolean humanEscalationSuggestion;

    HudResponse(JSONObject response, String fallbackSessionId, String fallbackStep) {
        sessionId = response.optString("sessionId", fallbackSessionId);
        step = response.optString("step", fallbackStep);
        resultType = response.optString("resultType", "instruction");
        feedbackCode = response.optString("feedbackCode", "");
        String legacyText = response.optString("text", "");
        displayTitle = response.optString("displayTitle", firstInstructionLine(legacyText));
        displayText = response.optString("displayText", legacyText);
        displayHint = response.optString("displayHint", "");
        humanEscalationSuggestion = response.optBoolean("humanEscalationSuggestion", false);
    }
}
```

- [x] **Step 2: Render by structured fields**

Add:

```java
private void applyHudResponse(final HudResponse hud) {
    persistSession(hud.sessionId, hud.step);
    runOnUiThread(new Runnable() {
        @Override
        public void run() {
            stepText.setText(stepLabel(hud.step));
            resultText.setText(hud.displayTitle.length() == 0 ? firstInstructionLine(hud.displayText) : hud.displayTitle);
            hintText.setText(hud.displayHint.length() == 0 ? fallbackHint(hud) : hud.displayHint);
            statusText.setText(statusForHud(hud));
        }
    });
}
```

- [x] **Step 3: Add fallback hint and status helpers**

Add:

```java
private static String fallbackHint(HudResponse hud) {
    if ("wrong_target".equals(hud.feedbackCode)) {
        return "请只拍服务器登录界面、黑底终端或命令输出";
    }
    if ("unclear_photo".equals(hud.feedbackCode)) {
        return "请靠近屏幕，避免反光，把文字放进绿色框后重拍";
    }
    if ("insufficient_info".equals(hud.feedbackCode)) {
        return "请拍摄完整终端内容后重试";
    }
    if ("voice_unclear".equals(hud.feedbackCode)) {
        return "请重新长按，说短一点";
    }
    if ("human_suggested".equals(hud.resultType)) {
        return "你可以长按中心选择转人工，也可以重新拍摄补充信息";
    }
    return hud.displayText.length() == 0 ? "请按提示继续" : hud.displayText;
}

private static String statusForHud(HudResponse hud) {
    if ("recognition_problem".equals(hud.resultType)) {
        return "AI 需要你重新补充现场信息。";
    }
    if ("human_suggested".equals(hud.resultType)) {
        return "AI 建议转人工，是否转人工由你决定。";
    }
    if ("completed".equals(hud.resultType)) {
        return "远程访问已恢复，本次会话完成。";
    }
    if ("network_error".equals(hud.resultType)) {
        return "暂时连接不到 AI 运维服务，请确认网络后重试。";
    }
    return "AI 已返回下一步，请按屏幕中央指令继续。";
}
```

- [x] **Step 4: Use structured response in image upload**

Replace:

```java
String text = response.optString("text", responseText);
String nextSessionId = response.optString("sessionId", sessionId);
String nextStep = response.optString("step", currentStep);
persistSession(nextSessionId, nextStep);
setResultText(firstInstructionLine(text));
setHintForStep(nextStep, text);
setStatus(statusForStep(nextStep));
```

With:

```java
HudResponse hud = new HudResponse(response, sessionId, currentStep);
applyHudResponse(hud);
```

- [x] **Step 5: Use structured response in voice upload**

Replace voice upload UI updates with:

```java
HudResponse hud = new HudResponse(response, sessionId, currentStep);
applyHudResponse(hud);
```

Do not display `voiceIntent` as the primary result in V2.

- [x] **Step 6: Localize empty fallback**

Change `firstInstructionLine` empty return from:

```java
return "No instruction";
```

To:

```java
return "等待 AI 指导";
```

- [x] **Step 7: Build APK**

Run:

```powershell
Set-Location "C:\Users\59979\Documents\New project"
$env:OPS_GLASSES_API_KEY=(Get-Content -Raw "tmp\ops_glasses_api_key.local").Trim()
powershell -NoProfile -ExecutionPolicy Bypass -File "air3-native-camera-test\build-native-apk.ps1"
```

Expected: APK builds and signature verification passes.

- [x] **Step 8: Commit native checkpoint**

```powershell
git add air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java air3-native-camera-test/build-native-apk.ps1
git commit -m "feat: render structured AI brain HUD responses"
```

---

## Task 7: End-To-End Supabase Verification

**Files:**
- Verify: `supabase/functions/ops-glasses/index.ts`
- Verify: `supabase/functions/ops-glasses/automigrate.ts`
- Evidence: `tmp/air3-v2-*.json`

- [x] **Step 1: Check function health**

Run:

```powershell
$base="https://zasgzaatthvfglhbxpgo.supabase.co/functions/v1/ops-glasses"
Invoke-RestMethod "$base/health"
```

Expected:

```json
{"ok":true,"service":"ops-glasses"}
```

- [x] **Step 2: Validate unauthorized protection**

Run a POST without `x-ops-glasses-key`. Expected: 401.

- [x] **Step 3: Test photo event with known console image**

Use an existing test image or captured console image and call `/sessions/events` with:

```json
{
  "taskType": "ssh_console_recovery",
  "step": "locate_server",
  "action": "console_photo_uploaded",
  "imageKind": "console",
  "imageBase64": "data:image/jpeg;base64,...",
  "source": "manual-contract-test"
}
```

Expected response includes:

```text
resultType
displayTitle
displayText
displayHint
canUseVoice
canHumanEscalate
```

- [x] **Step 4: Test voice event after photo**

Call `/sessions/:id/voice` with either `audioBase64` or direct `transcript`:

```json
{
  "transcript": "我已经输入完成了，现在屏幕显示 inactive",
  "audioFormat": "text-only-test",
  "source": "manual-contract-test"
}
```

Expected: the transcript is included in the AI brain context and response.

- [x] **Step 5: Verify database records**

Use Supabase SQL Editor or CLI to verify latest records exist in:

```text
ops_sessions
ops_events
ops_images
voice_inputs
ai_context_bundles
ai_requests
ai_decisions
```

- [x] **Step 6: Save evidence**

Save request/response evidence under `tmp/`:

```text
tmp/air3-v2-photo-response.json
tmp/air3-v2-voice-response.json
```

- [x] **Step 7: Commit verification notes**

Update `agent_memory/progress.md` with what passed and what remains.

```powershell
git add agent_memory/progress.md
git commit -m "test: verify Air3 V2 backend contract"
```

---

## Task 8: GitHub Storage

**Files:**
- Modify/Create: `.gitignore`
- Modify: `air3-native-camera-test/build-native-apk.ps1`
- Create: `docs/air3-v2-version-log.md`
- Create: `scripts/validate-native-build-versioning.mjs`
- Use: Git / GitHub CLI

- [x] **Step 1: Confirm git repo**

Run:

```powershell
Set-Location "C:\Users\59979\Documents\New project"
git rev-parse --show-toplevel
```

If it fails, run:

```powershell
git init
```

- [x] **Step 2: Add `.gitignore` protections**

Ensure `.gitignore` contains:

```gitignore
node_modules/
air3-ops-expo-app/node_modules/
tmp/
*.local
.env
.env.*
!.env.example
supabase/.env
air3-native-camera-test/build/generated-src/
air3-native-camera-test/build/classes/
air3-native-camera-test/build/dex/
air3-native-camera-test/build/*.idsig
*.apk
```

- [x] **Step 3: Commit current plan and code**

```powershell
git status --short
git add .
git commit -m "chore: prepare Air3 V2 APK plan"
```

- [x] **Step 4: Check GitHub auth**

```powershell
gh auth status
```

If not logged in, record GitHub push as blocked and ask the user to log in.

- [x] **Step 4.5: Add APK version differentiation**

Add version metadata and output naming so every local APK checkpoint can be distinguished:

```text
versionCode=208
versionName=2.0.8
tag=v2.0.8-task8-versioned-build
output=Air3NativeCameraTest-v<versionName>-<gitSha>.apk
```

Verify with:

```powershell
npm run validate:native-build
powershell -NoProfile -ExecutionPolicy Bypass -File "air3-native-camera-test\build-native-apk.ps1"
```

- [x] **Step 5: Push branch after auth**

Completed on 2026-06-06: `git push -u origin air3-v2-task1-docs` pushed the branch, and `git push origin v2.0.8-task8-versioned-build` pushed the version tag.

If auth works:

```powershell
git branch --show-current
git remote -v
git push -u origin HEAD
```

If there is no remote, ask the user for the GitHub repository or create one only after user approval.

---

## Task 9: Test Android Apps Real Device Verification

**Files:**
- APK: `air3-native-camera-test/build/Air3NativeCameraTest.apk`
- ADB: `tmp/tools/platform-tools/adb.exe`
- Evidence: `tmp/air3-v2-*.png`, `tmp/air3-v2-logcat.txt`

- [x] **Step 1: Load Test Android Apps skill**

Before device testing, read `test-android-apps:android-emulator-qa` skill and follow its adb evidence workflow, adapted for real Air3 device `YM00FCF3NW0031`.

- [x] **Step 2: Confirm device online**

Run:

```powershell
& "C:\Users\59979\Documents\New project\tmp\tools\platform-tools\adb.exe" devices
```

Expected: `YM00FCF3NW0031 device`.

- [x] **Step 3: Install V2 APK**

Run:

```powershell
& "C:\Users\59979\Documents\New project\tmp\tools\platform-tools\adb.exe" -s YM00FCF3NW0031 install -r "C:\Users\59979\Documents\New project\air3-native-camera-test\build\Air3NativeCameraTest.apk"
```

- [x] **Step 4: Grant permissions**

Run:

```powershell
& "C:\Users\59979\Documents\New project\tmp\tools\platform-tools\adb.exe" -s YM00FCF3NW0031 shell pm grant com.codex.air3nativecamera android.permission.CAMERA
& "C:\Users\59979\Documents\New project\tmp\tools\platform-tools\adb.exe" -s YM00FCF3NW0031 shell pm grant com.codex.air3nativecamera android.permission.RECORD_AUDIO
```

- [x] **Step 5: Launch app**

Run:

```powershell
& "C:\Users\59979\Documents\New project\tmp\tools\platform-tools\adb.exe" -s YM00FCF3NW0031 shell am start -n com.codex.air3nativecamera/.MainActivity
```

- [x] **Step 6: Capture initial HUD screenshot**

Run:

```powershell
& "C:\Users\59979\Documents\New project\tmp\tools\platform-tools\adb.exe" -s YM00FCF3NW0031 shell screencap -p /sdcard/air3-v2-home.png
& "C:\Users\59979\Documents\New project\tmp\tools\platform-tools\adb.exe" -s YM00FCF3NW0031 pull /sdcard/air3-v2-home.png "C:\Users\59979\Documents\New project\tmp\air3-v2-home.png"
```

- [x] **Step 7: Trigger photo capture**

Use either the touch coordinate that hits the center HUD or Air3 center click. Then capture:

```powershell
& "C:\Users\59979\Documents\New project\tmp\tools\platform-tools\adb.exe" -s YM00FCF3NW0031 shell screencap -p /sdcard/air3-v2-after-photo.png
& "C:\Users\59979\Documents\New project\tmp\tools\platform-tools\adb.exe" -s YM00FCF3NW0031 pull /sdcard/air3-v2-after-photo.png "C:\Users\59979\Documents\New project\tmp\air3-v2-after-photo.png"
```

- [x] **Step 8: Trigger voice capture**

Long press the HUD or use the Air3 center hold. Speak one short phrase:

```text
好了，已经完成
```

Capture:

```powershell
& "C:\Users\59979\Documents\New project\tmp\tools\platform-tools\adb.exe" -s YM00FCF3NW0031 shell screencap -p /sdcard/air3-v2-after-voice.png
& "C:\Users\59979\Documents\New project\tmp\tools\platform-tools\adb.exe" -s YM00FCF3NW0031 pull /sdcard/air3-v2-after-voice.png "C:\Users\59979\Documents\New project\tmp\air3-v2-after-voice.png"
```

- [x] **Step 9: Pull local diagnostics**

Run:

```powershell
& "C:\Users\59979\Documents\New project\tmp\tools\platform-tools\adb.exe" -s YM00FCF3NW0031 exec-out run-as com.codex.air3nativecamera cat files/last_ops_response.json > "C:\Users\59979\Documents\New project\tmp\air3-v2-last-ops-response.json"
& "C:\Users\59979\Documents\New project\tmp\tools\platform-tools\adb.exe" -s YM00FCF3NW0031 exec-out run-as com.codex.air3nativecamera cat files/last_voice_response.json > "C:\Users\59979\Documents\New project\tmp\air3-v2-last-voice-response.json"
```

- [x] **Step 10: Capture logcat**

Run:

```powershell
& "C:\Users\59979\Documents\New project\tmp\tools\platform-tools\adb.exe" -s YM00FCF3NW0031 logcat -d -s Air3NativeCameraTest > "C:\Users\59979\Documents\New project\tmp\air3-v2-logcat.txt"
```

- [x] **Step 11: Visual acceptance**

Screenshots must show:

```text
No English debug text
No HTTP/bytes/session data
No Java exception class names
Top task label visible
Green guide frame visible
AI result/problem states clearly distinguishable
```

- [x] **Step 12: Update memory and commit**

Update `agent_memory/progress.md` and `agent_memory/bugs.md` with test outcome.

```powershell
git add agent_memory/progress.md agent_memory/bugs.md
git commit -m "test: verify Air3 V2 APK on device"
```

Completed on 2026-06-06. Evidence and caveats are recorded in `docs/air3-v2-task9-test-report.md`.

---

## Acceptance Criteria

- Architecture docs state that main AI is the brain and backend is not the operations decision-maker.
- Figma/即时设计 has V2 HUD states or the design doc records the exact frame plan if MCP limits block writing.
- Expo prototype typechecks and models every V2 HUD result state.
- Supabase automigrate supports AI context bundles and AI decisions.
- Supabase Edge Function returns structured V2 fields: `resultType`, `feedbackCode`, `displayTitle`, `displayText`, `displayHint`, `humanEscalationSuggestion`, `canUseVoice`, `canHumanEscalate`.
- Voice flow sends transcript plus latest image context to the main AI brain instead of relying on voice-only intent mapping.
- Native APK renders V2 structured HUD responses and falls back gracefully for old `text` responses.
- APK builds successfully, includes versioned output `Air3NativeCameraTest-v<versionName>-<gitSha>.apk`, and does not leave generated key source folders after build.
- Git has local commits; GitHub push is completed or explicitly blocked by missing auth/remote.
- Test Android Apps / adb evidence confirms the V2 APK launches, captures, uploads, handles voice, and shows no operator-facing debug text.

---

## Self-Review

- Spec coverage: The plan covers architecture, Figma/即时设计, Expo, Supabase schema, Supabase AI brain flow, native APK, GitHub, and Test Android Apps verification.
- Placeholder scan: No implementation step depends on unspecified file names or undefined state categories. The only user-dependent item is GitHub remote/auth, and the plan explicitly treats it as blocked until user approval/auth.
- Type consistency: `resultType`, `feedbackCode`, `displayTitle`, `displayText`, `displayHint`, `safeCommandKey`, and `humanEscalationSuggestion` are used consistently across docs, Supabase, Expo, and native APK tasks.
