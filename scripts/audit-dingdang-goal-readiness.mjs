import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { auditExternalAcceptance } from "./v9-external-acceptance.mjs";

const root = process.cwd();

function readText(relativePath) {
  const buffer = fs.readFileSync(path.join(root, relativePath));
  let text = buffer.toString("utf8");
  const nulCount = [...text].filter((char) => char === "\u0000").length;
  if (nulCount > Math.max(8, text.length / 10)) {
    text = buffer.toString("utf16le");
  }
  return text.replace(/^\uFEFF/, "");
}

function readJson(relativePath) {
  return JSON.parse(readText(relativePath));
}

function exists(relativePath) {
  return fs.existsSync(path.join(root, relativePath));
}

function sha256File(relativePath) {
  if (!exists(relativePath)) {
    return null;
  }
  return crypto.createHash("sha256")
    .update(fs.readFileSync(path.join(root, relativePath)))
    .digest("hex")
    .toUpperCase();
}

function readSha256Sidecar(relativePath) {
  if (!exists(relativePath)) {
    return null;
  }
  return readText(relativePath).trim().split(/\s+/)[0]?.toUpperCase() || null;
}

function includesAll(text, markers) {
  return markers.every((marker) => text.includes(marker));
}

function countTextOccurrences(text, marker) {
  if (!text || !marker) {
    return 0;
  }
  return text.split(marker).length - 1;
}

function evidence(relativePath) {
  return exists(relativePath) ? relativePath : null;
}

function firstExisting(relativePaths) {
  return relativePaths.find((relativePath) => exists(relativePath)) || relativePaths[0];
}

export function collectFiles(relativePaths, scanRoot = root) {
  const excludedDirectories = new Set([".git", "build", "node_modules"]);
  const normalizedRoot = path.resolve(scanRoot);
  const files = [];

  function visit(relativePath) {
    const absolutePath = path.resolve(normalizedRoot, relativePath);
    const relativeToRoot = path.relative(normalizedRoot, absolutePath);
    if (relativeToRoot.startsWith("..") || path.isAbsolute(relativeToRoot) || !fs.existsSync(absolutePath)) {
      return;
    }

    const stat = fs.lstatSync(absolutePath);
    if (stat.isSymbolicLink()) {
      return;
    }
    if (stat.isDirectory()) {
      if (excludedDirectories.has(path.basename(absolutePath))) {
        return;
      }
      for (const entry of fs.readdirSync(absolutePath, { withFileTypes: true })) {
        visit(path.join(relativeToRoot, entry.name));
      }
      return;
    }
    if (stat.isFile()) {
      files.push(relativeToRoot.replaceAll("\\", "/"));
    }
  }

  for (const relativePath of relativePaths) {
    visit(relativePath);
  }
  return [...new Set(files)].sort();
}

export function findCommittedSecretLikeMarkers(relativePaths, scanRoot = root) {
  const secretPattern = /(?:^|[^A-Za-z0-9])sk-[A-Za-z0-9][A-Za-z0-9_-]{19,}(?=$|[^A-Za-z0-9])/g;
  const findings = [];

  for (const relativePath of collectFiles(relativePaths, scanRoot)) {
    const absolutePath = path.join(scanRoot, relativePath);
    const buffer = fs.readFileSync(absolutePath);
    if (buffer.includes(0)) {
      continue;
    }
    const lines = buffer.toString("utf8").split(/\r?\n/);
    lines.forEach((line, index) => {
      secretPattern.lastIndex = 0;
      if (secretPattern.test(line)) {
        findings.push({ path: relativePath, line: index + 1 });
      }
    });
  }

  return findings;
}

function latestFileUnder(prefix, fileName) {
  const tmpDir = path.join(root, "tmp");
  if (!fs.existsSync(tmpDir)) {
    return null;
  }
  const candidates = fs.readdirSync(tmpDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name.startsWith(prefix))
    .map((entry) => {
      const relativePath = path.join("tmp", entry.name, fileName);
      const absolutePath = path.join(root, relativePath);
      return fs.existsSync(absolutePath)
        ? { relativePath: relativePath.replaceAll("\\", "/"), mtimeMs: fs.statSync(absolutePath).mtimeMs }
        : null;
    })
    .filter(Boolean)
    .sort((a, b) => b.mtimeMs - a.mtimeMs);
  return candidates[0]?.relativePath || null;
}

function latestSummaryUnder(prefix) {
  return latestFileUnder(prefix, "summary.json");
}

function latestEvidenceUnder(prefix) {
  return latestFileUnder(prefix, "evidence.json");
}

function isValidFigmaFramesEvidence(evidenceFile) {
  if (!evidenceFile || evidenceFile.ok !== true || evidenceFile.figmaFileKey !== "pDX9LEKARKp5GGchwuFLz4") {
    return false;
  }
  if (evidenceFile.metadataVerified !== true || !Array.isArray(evidenceFile.frames)) {
    return false;
  }
  const frameMap = new Map(evidenceFile.frames.map((frame) => [frame.id, frame]));
  return ["chat-main", "camera-capture", "asr-streaming", "gpt-streaming", "shortcut-flow"].every((id) => {
    const frame = frameMap.get(id);
    return Boolean(
      frame &&
        typeof frame.nodeId === "string" &&
        frame.nodeId.includes(":") &&
        frame.metadataVerified === true &&
        frame.screenshotVerified === true &&
        Number.isFinite(frame.width) &&
        frame.width >= 1200 &&
        Number.isFinite(frame.height) &&
        frame.height >= 800,
    );
  });
}

function isValidRealProviderSummary(summary) {
  if (!summary || summary.provider !== "real") {
    return false;
  }
  const booleans = [
    "backendBaseUrlProvided",
    "healthOk",
    "cameraUiVisible",
    "returnedToChatAfterPhoto",
    "imageAttached",
    "asrPartialVisible",
    "asrFinalVisible",
    "gptFirstDeltaObserved",
    "inDingdang",
    "latencyWithinBudget",
  ];
  if (!booleans.every((key) => summary[key] === true)) {
    return false;
  }
  const latencyMs = summary.latencyMs || {};
  const latencyBudgetsMs = summary.latencyBudgetsMs || {};
  const measured = ["cameraUi", "photoReturn", "asrPartial", "asrFinal", "gptFirstDelta", "totalInteraction"];
  const budgeted = ["cameraUi", "photoReturn", "asrPartial", "gptFirstDelta", "totalInteraction"];
  return measured.every((key) => Number.isFinite(latencyMs[key])) &&
    budgeted.every((key) => Number.isFinite(latencyBudgetsMs[key]) && latencyMs[key] <= latencyBudgetsMs[key]) &&
    latencyMs.asrPartialSource === "logcat" &&
    latencyMs.asrFinalSource === "logcat" &&
    latencyMs.gptFirstDeltaSource === "logcat";
}

function makeItem(id, requirement, status, details, evidencePaths = []) {
  return {
    id,
    requirement,
    status,
    details,
    evidence: evidencePaths.filter(Boolean),
  };
}

function statusFromBoolean(value) {
  return value ? "proven" : "incomplete";
}

const items = [];

const context = readText("agent_memory/context.md");
const progress = readText("agent_memory/progress.md");
const bugs = readText("agent_memory/bugs.md");
const nativeCode = readText("air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java");
const expoCode = readText("air3-ops-expo-app/app/index.tsx");
const expoVerifier = readText("air3-ops-expo-app/scripts/verify-hud-contract.mjs");
const figmaSpecPath = "docs/superpowers/specs/2026-06-09-dingdang-ops-ai-chat-design.md";
const figmaSpec = exists(figmaSpecPath) ? readText(figmaSpecPath) : "";
const figmaBlueprintPath = "docs/figma/dingdang-ops-ai-frame-blueprint.json";
const figmaBlueprint = exists(figmaBlueprintPath) ? readJson(figmaBlueprintPath) : null;
const figmaWriteScriptPath = "docs/figma/dingdang-ops-ai-use-figma-script.js";
const figmaWriteScript = exists(figmaWriteScriptPath) ? readText(figmaWriteScriptPath) : "";
const figmaFramesEvidenceValidatorPath = "scripts/validate-dingdang-figma-frames-evidence.mjs";
const figmaFramesEvidenceValidator = exists(figmaFramesEvidenceValidatorPath)
  ? readText(figmaFramesEvidenceValidatorPath)
  : "";
const figmaFramesEvidencePath = latestEvidenceUnder("dingdang-figma-frames-");
const figmaFramesEvidence = figmaFramesEvidencePath && exists(figmaFramesEvidencePath)
  ? readJson(figmaFramesEvidencePath)
  : null;

const shortcutSummaryPath = "tmp/sidekey-shortcut-regression-20260610/summary.json";
const shortcutSummary = exists(shortcutSummaryPath) ? readJson(shortcutSummaryPath) : null;
const mockSmokePath = firstExisting([
  "tmp/image-id-context-autosend-clean-20260610/summary.json",
  "tmp/image-id-context-fallback-20260610/summary.json",
  "tmp/clean-home-actions-ui-20260610/summary.json",
  "tmp/live-chat-camera-20260610/summary.json",
  "tmp/no-symbol-actions-ui-20260610/summary.json",
  "tmp/home-actions-ui-20260610/summary.json",
  "tmp/mock-live-smoke-latency-20260610/summary.json",
  "tmp/mock-live-smoke-script-20260610-v2/summary.json",
]);
const mockSmoke = exists(mockSmokePath) ? readJson(mockSmokePath) : null;
const mockSmokeLogPath = mockSmokePath.replace(/summary\.json$/, "logcat.txt");
const mockSmokeLog = exists(mockSmokeLogPath) ? readText(mockSmokeLogPath) : "";
const supabaseReadinessPath = "tmp/dingdang-supabase-live-readiness-20260610.json";
const supabaseReadiness = exists(supabaseReadinessPath) ? readJson(supabaseReadinessPath) : null;
const realSmokeScriptPath = "scripts/run-dingdang-real-live-smoke.ps1";
const realSmokeScript = exists(realSmokeScriptPath) ? readText(realSmokeScriptPath) : "";
const realSmokeValidatorPath = "scripts/validate-dingdang-real-smoke-summary.mjs";
const realSmokeValidator = exists(realSmokeValidatorPath) ? readText(realSmokeValidatorPath) : "";
const realSmokeSummaryPath = latestSummaryUnder("real-live-smoke-");
const realSmokeSummary = realSmokeSummaryPath && exists(realSmokeSummaryPath) ? readJson(realSmokeSummaryPath) : null;
const finalRunbookPath = "docs/dingdang-final-verification-runbook.md";
const finalRunbook = exists(finalRunbookPath) ? readText(finalRunbookPath) : "";
const installUiPath = "tmp/dingdang-ops-ai-610-ui.xml";
const installUi = exists(installUiPath) ? readText(installUiPath) : "";
const v9CompletionMatrixPath = "docs/audits/2026-08-06-v9-completion-matrix.md";
const v9CompletionMatrix = exists(v9CompletionMatrixPath) ? readText(v9CompletionMatrixPath) : "";
const v9ReleaseManifestPath = firstExisting([
  "output/v9.0.0-formal-release-current/release-manifest.json",
  "output/v9.0.0-formal-release/release-manifest.json",
  "output/v9.0.0-formal-delivery/android/release-manifest.json",
]);
const v9ReleaseManifest = exists(v9ReleaseManifestPath) ? readJson(v9ReleaseManifestPath) : null;
const v9ReleaseDir = path.dirname(v9ReleaseManifestPath).replaceAll("\\", "/");
const v9ReleaseApkPath = `${v9ReleaseDir}/DingdangAI-V9-9.0.0-release.apk`;
const v9ReleaseApkSha256 = sha256File(v9ReleaseApkPath);
const v9ReleaseApkSha256Matches = Boolean(
  v9ReleaseManifest?.sha256 && v9ReleaseApkSha256 === String(v9ReleaseManifest.sha256).toUpperCase(),
);
const v9DeliveryManifestPath = "output/v9.0.0-formal-delivery/DELIVERY_MANIFEST.json";
const v9DeliveryManifest = exists(v9DeliveryManifestPath) ? readJson(v9DeliveryManifestPath) : null;
const v9DeliveryZipPath = "output/DingdangAI-V9-9.0.0-formal-delivery.zip";
const v9DeliveryZipSidecarPath = `${v9DeliveryZipPath}.sha256`;
const v9DeliveryChecksumsPath = "output/v9.0.0-formal-delivery/SHA256SUMS.txt";
const v9DeliveryZipSha256 = sha256File(v9DeliveryZipPath);
const v9DeliveryZipSidecar = readSha256Sidecar(v9DeliveryZipSidecarPath);
const v9DeliveryManifestArtifacts = Array.isArray(v9DeliveryManifest?.artifacts)
  ? v9DeliveryManifest.artifacts.length
  : 0;
const v9DeliveryChecksummedFiles = exists(v9DeliveryChecksumsPath)
  ? readText(v9DeliveryChecksumsPath).split(/\r?\n/).filter(Boolean).length
  : 0;
const v9DeliveryApkArtifact = v9DeliveryManifest?.artifacts?.find((artifact) =>
  artifact?.path === "android/DingdangAI-V9-9.0.0-release.apk",
);
const v9DeliveryManifestValid = Boolean(
  v9DeliveryManifest?.applicationId === "com.codex.air3nativecamera.dingdangexpert.v9" &&
    v9DeliveryManifest?.versionCode === 900000 &&
    v9DeliveryManifest?.secureRuntime === true &&
    v9DeliveryManifest?.modelContract?.ai === "qwen3-vl-plus" &&
    v9DeliveryManifest?.modelContract?.asr === "fun-asr-realtime" &&
    v9DeliveryManifest?.modelContract?.voiceprint === "s1aa729d0" &&
    v9DeliveryApkArtifact?.sha256 === v9ReleaseManifest?.sha256 &&
    v9DeliveryZipSha256 !== null &&
    v9DeliveryZipSha256 === v9DeliveryZipSidecar &&
    v9DeliveryManifestArtifacts > 0 &&
    v9DeliveryChecksummedFiles === v9DeliveryManifestArtifacts + 1,
);
const v9SecretScanRoots = [
  "air3-dingdang-expert-integrated-app",
  "ops-management-web",
  "supabase/functions/ops-glasses",
  "v9-ops-gateway",
  "scripts",
  "docs",
];
const v9SecretFindings = findCommittedSecretLikeMarkers(v9SecretScanRoots);
const v9ReleaseManifestValid = Boolean(
  v9ReleaseManifest?.applicationId === "com.codex.air3nativecamera.dingdangexpert.v9" &&
    v9ReleaseManifest?.versionCode === 900000 &&
    v9ReleaseManifest?.versionName === "9.0.0" &&
    v9ReleaseManifest?.aiModel === "qwen3-vl-plus" &&
    v9ReleaseManifest?.asrModel === "fun-asr-realtime" &&
    v9ReleaseManifest?.voiceprintService === "s1aa729d0" &&
    v9ReleaseManifest?.secureRuntime === true &&
    v9ReleaseManifest?.directGptEnabled === false &&
    v9ReleaseApkSha256Matches,
);
const v9LocalCandidateProven = v9ReleaseManifestValid &&
  v9DeliveryManifestValid &&
  v9CompletionMatrix.includes("本地候选版：通过，可构建、可回归、可审计、可交付");
const v9ExternalAcceptanceManifestPath = "evidence/v9-external-acceptance/manifest.json";
const v9ExternalAcceptance = auditExternalAcceptance({
  root,
  manifestPath: v9ExternalAcceptanceManifestPath,
  expectedTrustStoreSha256: process.env.V9_EXTERNAL_ACCEPTANCE_TRUST_STORE_SHA256 || "",
  expectedRelease: {
    applicationId: v9ReleaseManifest?.applicationId,
    versionCode: v9ReleaseManifest?.versionCode,
    versionName: v9ReleaseManifest?.versionName,
    apkSha256: v9ReleaseManifest?.sha256,
    deliveryZipSha256: v9DeliveryZipSha256,
    generatedAt: v9ReleaseManifest?.builtAt,
  },
});

items.push(makeItem(
  "superpowers-plan",
  "Superpowers/spec documents define the chat-first, low-operation product direction",
  statusFromBoolean(exists(figmaSpecPath) && figmaSpec.includes("叮当运维AI") && figmaSpec.includes("聊天")),
  "Uses the existing Superpowers spec as the current design contract.",
  [evidence(figmaSpecPath)],
));

items.push(makeItem(
  "figma-file",
  "Figma design file exists for the chat-style现场诊断 UI",
  statusFromBoolean(context.includes("https://www.figma.com/design/pDX9LEKARKp5GGchwuFLz4")),
  "File URL is recorded, but this only proves file creation, not canvas frames.",
  ["agent_memory/context.md"],
));

items.push(makeItem(
  "figma-blueprint",
  "Local Figma frame blueprint covers the five required pages before MCP write access is restored",
  statusFromBoolean(Boolean(
    figmaBlueprint?.figmaFileKey === "pDX9LEKARKp5GGchwuFLz4" &&
      figmaBlueprint?.designContract?.background === "white" &&
      figmaBlueprint?.designContract?.primaryMode === "chat" &&
      figmaBlueprint?.designContract?.diagnosisSource === "GPT only" &&
      figmaBlueprint?.designContract?.asrRole === "transcript only" &&
      figmaBlueprint?.designContract?.lowOperation &&
      figmaBlueprint?.designContract?.noHudPanels &&
      ["chat-main", "camera-capture", "asr-streaming", "gpt-streaming", "shortcut-flow"].every((frameId) =>
        figmaBlueprint?.requiredFrames?.some((frame) => frame.id === frameId),
      )
  )),
  "This proves the local Figma storyboard is ready to write, but it does not prove frames exist in Figma.",
  [evidence(figmaBlueprintPath), "scripts/validate-dingdang-figma-blueprint.mjs"],
));

items.push(makeItem(
  "figma-write-script",
  "A use_figma-compatible write script is prepared for the five required frames",
  statusFromBoolean(includesAll(figmaWriteScript, [
    "await figma.setCurrentPageAsync(page)",
    "await figma.loadFontAsync",
    "Dingdang Ops AI / 01 Chat main screen",
    "Dingdang Ops AI / 02 Camera capture screen",
    "Dingdang Ops AI / 03 ASR partial and final state",
    "Dingdang Ops AI / 04 GPT streaming answer",
    "Dingdang Ops AI / 05 AR glasses shortcut flow",
    "return {",
    "createdNodeIds",
  ])),
  "This proves the Figma write operation is prepared locally, but it does not prove the frames exist in Figma.",
  [evidence(figmaWriteScriptPath), "scripts/validate-dingdang-figma-write-script.mjs"],
));

items.push(makeItem(
  "figma-frames-evidence-validator",
  "Figma frame metadata and screenshot evidence has a machine-checkable validation gate",
  statusFromBoolean(includesAll(figmaFramesEvidenceValidator, [
    "pDX9LEKARKp5GGchwuFLz4",
    "metadataVerified",
    "screenshotVerified",
    "chat-main",
    "camera-capture",
    "asr-streaming",
    "gpt-streaming",
    "shortcut-flow",
  ])),
  "This proves the final Figma evidence can be checked automatically after MCP write access is restored.",
  [evidence(figmaFramesEvidenceValidatorPath)],
));

items.push(makeItem(
  "figma-frames",
  "Figma pages/frames for chat main screen, camera, ASR, GPT streaming, and shortcut flow are written and verified",
  isValidFigmaFramesEvidence(figmaFramesEvidence) ? "proven" : "incomplete",
  isValidFigmaFramesEvidence(figmaFramesEvidence)
    ? "A Figma frames evidence file exists and proves metadata plus screenshot verification for all five frames."
    : "Figma MCP currently returns Starter tool call limit; the local write script and evidence validator are ready, but metadata and screenshot verification are unavailable.",
  ["agent_memory/bugs.md", figmaFramesEvidencePath ? evidence(figmaFramesEvidencePath) : null],
));

items.push(makeItem(
  "expo-prototype",
  "Expo prototype preserves a white GPT-style chat UI and low-operation voice flow",
  statusFromBoolean(includesAll(expoCode, [
    "type ChatMessage =",
    "type ProjectThread =",
    "type VoiceState =",
    "handleCameraPress",
    "handleVoicePress",
    "handleSend",
    "simulatePartialTranscript",
    "autoSendFinalTranscript",
    'backgroundColor: "#ffffff"',
  ]) && includesAll(expoVerifier, [
    "chatPrototype: true",
    "lowOperationVoiceFlow: true",
  ])),
  "Source markers align with the verified Expo HUD contract.",
  ["air3-ops-expo-app/app/index.tsx", "air3-ops-expo-app/scripts/verify-hud-contract.mjs"],
));

items.push(makeItem(
  "native-chat-ui",
  "Native APK implements the white INMO-assistant-style voice UI with project history",
  statusFromBoolean(includesAll(nativeCode, [
    "root.setBackgroundColor(Color.WHITE)",
    "projectHeader.setText(",
    "titleText.setText(",
    "transcriptDraftText.setText(",
    "private AudioWaveView voiceWaveView;",
    "voiceButton.setContentDescription(\"点我说话\")",
    "persistChatProjects()",
  ]) && installUi.includes("com.codex.air3nativecamera.dingdangops")),
  "Native source and Air3 UI dump both show the no-input voice console, wave recording entry, and session/project history.",
  ["air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java", evidence(installUiPath)],
));

items.push(makeItem(
  "native-clean-home-actions",
  "Native home screen avoids duplicate idle action controls",
  statusFromBoolean(includesAll(nativeCode, [
    "shouldShowHomeActions()",
    "shouldShowComposerPanel()",
    "composerPanel.setVisibility(showComposerPanel ? View.VISIBLE : View.GONE)",
    "transcriptDraftText.setVisibility(View.GONE)",
  ]) &&
    countTextOccurrences(installUi, 'text="点我拍照"') === 1 &&
    countTextOccurrences(installUi, 'text="点我说话"') === 1 &&
    !installUi.includes('text="+"') &&
    !installUi.includes('text="▷"') &&
    !installUi.includes('text="■"')),
  "Air3 UI dump must show only one idle photo button and one idle voice button, with no plus/play/stop symbols.",
  ["air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java", evidence(installUiPath)],
));

items.push(makeItem(
  "native-camera-composer",
  "Photo capture enters a camera screen and returns the image to the composer before sending",
  statusFromBoolean(includesAll(nativeCode, [
    "enterCameraScreen(",
    "confirmCapturedPhoto(",
    "composerImageBytes = jpegBytes",
    "showComposerAttachment(",
  ])),
  "Static source proves the composer attachment path; mock live smoke proves the flow on Air3.",
  ["air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java", evidence(mockSmokePath)],
));

items.push(makeItem(
  "realtime-asr-ui",
  "Voice input streams partial/final text with no fake transcript and final auto-send",
  statusFromBoolean(includesAll(nativeCode, [
    "feedRealtimeAsrPcm(buffer, read)",
    "onAsrPartial(",
    "onAsrFinal(",
    "voiceStreamState = VoiceStreamState.AI_PENDING",
    "sendComposerToAi();",
    "请再说一次",
  ]) && Boolean(mockSmoke?.asrPartialVisible && mockSmoke?.finalTranscriptVisible)),
  "Static source and Air3 mock smoke prove the realtime UI contract against mock ASR.",
  ["air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java", evidence(mockSmokePath)],
));

items.push(makeItem(
  "gpt-sse-streaming",
  "GPT response is rendered as streaming chat bubbles",
  statusFromBoolean(includesAll(nativeCode, [
    "text/event-stream",
    "parseSseDelta(",
    "appendAssistantStreamingMessage(",
    "updateAssistantStreamingMessage(",
    "finalizeAssistantStreamingMessage(",
  ]) && Boolean(mockSmoke?.gptStreamVisible)),
  "Static source and Air3 mock smoke prove the UI/SSE parsing path against mock GPT.",
  ["air3-native-camera-test/app/src/main/java/com/codex/air3nativecamera/MainActivity.java", evidence(mockSmokePath)],
));

items.push(makeItem(
  "shortcut-matrix",
  "AR glasses shortcut keys support low-operation use and avoid system-reserved camera keys",
  statusFromBoolean(Boolean(shortcutSummary?.allExpectedOk && shortcutSummary?.finalInDingdang)),
  "Shortcut regression verifies ENTER/DPAD_CENTER, FOCUS/F9(139), send/back/volume keys, and reserved CAMERA/DVR behavior.",
  [evidence(shortcutSummaryPath), "docs/air3-shortcut-key-research.md"],
));

items.push(makeItem(
  "mock-air3-e2e",
  "Air3 mock end-to-end flow proves low-operation camera -> ASR -> GPT stream",
  statusFromBoolean(Boolean(
    mockSmoke?.cameraUiVisible &&
      mockSmoke?.returnedToChatAfterPhoto &&
      mockSmoke?.imageAttached &&
      mockSmoke?.asrPartialVisible &&
      mockSmoke?.finalTranscriptVisible &&
      mockSmoke?.gptStreamVisible &&
      mockSmoke?.inDingdang &&
      mockSmoke?.restoredStandard &&
      mockSmokeLog.includes("GPT stream start") &&
      mockSmokeLog.includes("hasImageId=true"),
  )),
  "Script-level Air3 smoke runs against local mock backend, proves image_id reaches the GPT request, and restores the standard APK.",
  [evidence(mockSmokePath), evidence(mockSmokeLogPath), "scripts/run-dingdang-mock-live-smoke.ps1"],
));

items.push(makeItem(
  "mock-low-latency-feedback",
  "Air3 mock smoke records and passes low-wait latency budgets for camera, ASR partial, and GPT stream feedback",
  statusFromBoolean(Boolean(
    mockSmoke?.latencyWithinBudget &&
      Number.isFinite(mockSmoke?.latencyMs?.cameraUi) &&
      Number.isFinite(mockSmoke?.latencyMs?.photoReturn) &&
      Number.isFinite(mockSmoke?.latencyMs?.asrPartial) &&
      Number.isFinite(mockSmoke?.latencyMs?.gptFirstDelta) &&
      Number.isFinite(mockSmoke?.latencyMs?.totalInteraction),
  )),
  "The mock Air3 smoke must prove not only that the flow completes, but that the user receives timely visual/voice/GPT feedback.",
  [evidence(mockSmokePath), "scripts/validate-dingdang-mock-smoke-latency.mjs"],
));

items.push(makeItem(
  "real-provider-smoke-script",
  "Real provider live smoke has a safe Air3 execution script ready for rotated Supabase secrets",
  statusFromBoolean(includesAll(realSmokeScript, [
    "BackendBaseUrl",
    "DryRun",
    "AllowNoRealProviderEvidence",
    "Realtime ASR partial",
    "Realtime ASR final",
    "GPT stream first delta latencyMs",
    "asrPartialSource",
    "asrFinalSource",
    "No API key or secret value is read or printed",
  ])),
  "This proves the live-smoke runner is ready, but it does not prove DashScope/GPT real provider execution.",
  [evidence(realSmokeScriptPath)],
));

items.push(makeItem(
  "real-provider-smoke-validator",
  "Real provider live smoke summary has an automatic validation gate",
  statusFromBoolean(includesAll(realSmokeValidator, [
    "latestRealSummaryPath",
    "backendBaseUrlProvided",
    "healthOk",
    "asrPartialSource",
    "asrFinalSource",
    "gptFirstDeltaSource",
    "latencyWithinBudget",
  ])),
  "This proves the real provider smoke has a machine-checkable completion gate, but it does not replace running the real provider.",
  [evidence(realSmokeValidatorPath)],
));

items.push(makeItem(
  "real-provider-live-smoke",
  "Real DashScope Fun-ASR and GPT-compatible provider live smoke is completed through Supabase secrets",
  isValidRealProviderSummary(realSmokeSummary) ? "proven" : "incomplete",
  isValidRealProviderSummary(realSmokeSummary)
    ? "A real provider smoke summary exists and passes the completion gate."
    : "No valid real provider smoke summary is present; Supabase is not linked/logged in locally and rotated production secrets are not configured, so current proof remains mock-only.",
  ["agent_memory/bugs.md", evidence(supabaseReadinessPath), realSmokeSummaryPath ? evidence(realSmokeSummaryPath) : null],
));

items.push(makeItem(
  "final-verification-runbook",
  "Final verification runbook defines the exact Figma, Supabase, Air3, and audit commands required before completion",
  statusFromBoolean(includesAll(finalRunbook, [
    "npm run validate:figma-frames-evidence",
    "npm run validate:real-smoke-summary",
    "scripts\\run-dingdang-real-live-smoke.ps1",
    "scripts\\test-dingdang-air3-shortcuts.ps1",
    "npm run audit:v9-external-acceptance",
    "currentV9Gate.localCandidate.status=proven",
    "currentV9Gate.productionDeployment.status=proven",
    "currentV9Gate.marketGa.status=proven",
    "currentV9Gate.delivery.status=proven",
    "currentV9Gate.secretScan.status=proven",
  ])),
  "The runbook uses the currentV9Gate contract and release-bound external evidence instead of the superseded legacy allComplete flag.",
  [evidence(finalRunbookPath)],
));

items.push(makeItem(
  "secrets-safety",
  "No real API keys are stored in source, scripts, docs, or APK config",
  statusFromBoolean(v9ReleaseManifestValid && v9SecretFindings.length === 0),
  v9SecretFindings.length === 0
    ? "Boundary-aware scan found no provider-key markers in the current V9 Android, management Web, Supabase Edge, gateway, scripts, or docs; the formal release manifest also confirms secure runtime."
    : "Boundary-aware scan found " + v9SecretFindings.length + " secret-like location(s); values are intentionally not printed.",
  [
    evidence(v9ReleaseManifestPath),
    evidence(v9CompletionMatrixPath),
    ...v9SecretFindings.map((finding) => finding.path + ":" + finding.line),
  ],
));

const counts = items.reduce((acc, item) => {
  acc[item.status] = (acc[item.status] || 0) + 1;
  return acc;
}, {});

const allComplete = items.every((item) => item.status === "proven");
const result = {
  generatedAt: new Date().toISOString(),
  scope: "legacy-chat-prototype-baseline",
  supersededBy: "V9 full product contract and release gates documented in docs/audits/2026-08-06-v9-completion-matrix.md",
  allComplete,
  counts,
  currentV9Gate: {
    contract: {
      status: exists(v9CompletionMatrixPath) ? "proven" : "incomplete",
      evidence: [v9CompletionMatrixPath],
    },
    localCandidate: {
      status: v9LocalCandidateProven ? "proven" : "incomplete",
      details: v9LocalCandidateProven
        ? "The current V9 local candidate has a matching secure release manifest, APK hash, delivery manifest, ZIP sidecar, and completed local release matrix."
        : "The current V9 local candidate is missing a matching release artifact, delivery integrity proof, or completion-matrix proof.",
      evidence: [v9ReleaseManifestPath, v9ReleaseApkPath, v9DeliveryManifestPath, v9DeliveryZipSidecarPath, v9CompletionMatrixPath],
    },
    productionDeployment: {
      status: v9ExternalAcceptance.productionDeploymentStatus,
      details: v9ExternalAcceptance.productionDeploymentStatus === "proven"
        ? "Release-bound production Supabase, isolated management cloud, and device activation evidence passed the external acceptance gate."
        : "Production credentials, account/RLS checks, isolated cloud deployment, and device activation remain external gates.",
      evidence: [v9ExternalAcceptanceManifestPath],
    },
    marketGa: {
      status: v9ExternalAcceptance.marketGaStatus,
      details: v9ExternalAcceptance.marketGaStatus === "proven"
        ? "All release-bound production, Air3, MVS, expert collaboration, resilience, security, and supplier approval gates passed."
        : "Market GA remains blocked until the release-bound external acceptance evidence is complete and valid.",
      evidence: [v9ExternalAcceptanceManifestPath],
    },
    externalAcceptance: v9ExternalAcceptance,
    release: v9ReleaseManifest ? {
      manifestPath: v9ReleaseManifestPath,
      apkPath: v9ReleaseApkPath,
      apkSha256Matches: v9ReleaseApkSha256Matches,
      applicationId: v9ReleaseManifest.applicationId,
      versionCode: v9ReleaseManifest.versionCode,
      versionName: v9ReleaseManifest.versionName,
      sha256: v9ReleaseManifest.sha256,
      aiModel: v9ReleaseManifest.aiModel,
      asrModel: v9ReleaseManifest.asrModel,
      voiceprintService: v9ReleaseManifest.voiceprintService,
      secureRuntime: v9ReleaseManifest.secureRuntime,
      directGptEnabled: v9ReleaseManifest.directGptEnabled,
    } : null,
    delivery: {
      status: v9DeliveryManifestValid ? "proven" : "incomplete",
      manifestPath: v9DeliveryManifestPath,
      zipPath: v9DeliveryZipPath,
      zipSha256: v9DeliveryZipSha256,
      zipSha256Matches: v9DeliveryZipSha256 !== null && v9DeliveryZipSha256 === v9DeliveryZipSidecar,
      manifestArtifacts: v9DeliveryManifestArtifacts,
      checksummedFiles: v9DeliveryChecksummedFiles,
    },
    secretScan: {
      status: v9ReleaseManifestValid && v9SecretFindings.length === 0 ? "proven" : "incomplete",
      roots: v9SecretScanRoots,
      findingCount: v9SecretFindings.length,
      findings: v9SecretFindings,
    },
  },
  items,
  nextRequiredActions: items
    .filter((item) => item.status !== "proven")
    .map((item) => ({ id: item.id, requirement: item.requirement, details: item.details })),
};

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  console.log(JSON.stringify(result, null, 2));
}
