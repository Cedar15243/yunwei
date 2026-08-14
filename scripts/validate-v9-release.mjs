import fs from "node:fs";
import path from "node:path";

const root = process.cwd();
const buildGradlePath = path.join(
  root,
  "air3-dingdang-expert-integrated-app/app/build.gradle",
);
const buildScriptPath = path.join(root, "scripts/build-v9-release.ps1");
const gatewayPath = path.join(root, "v9-ops-gateway/gateway.py");
const asrPath = path.join(root, "v9-ops-gateway/asr_proxy.py");
const voiceprintPath = path.join(root, "v9-ops-gateway/voiceprint_proxy.py");
const knowledgeDocumentParserPath = path.join(root, "v9-ops-gateway/knowledge_document_parser.py");
const gatewayRequirementsPath = path.join(root, "v9-ops-gateway/requirements.txt");
const gatewayEnvPath = path.join(
  root,
  "v9-ops-gateway/deploy/dingdang-v9-gateway.env.example",
);
const voiceprintEnvPath = path.join(
  root,
  "v9-ops-gateway/deploy/dingdang-v9-voiceprint.env.example",
);
const integratedPreviewBuildPath = path.join(
  root,
  "scripts/build-dingdang-integrated-preview.ps1",
);
const integratedMainActivityPath = path.join(
  root,
  "air3-dingdang-expert-integrated-app/app/src/main/java/com/codex/air3nativecamera/MainActivity.java",
);
const supabaseFunctionPath = path.join(
  root,
  "supabase/functions/ops-glasses/index.ts",
);
const knowledgeDocumentParserClientPath = path.join(
  root,
  "supabase/functions/ops-glasses/knowledge-document-parser-client.ts",
);
const supabaseEnvPath = path.join(root, "supabase/.env.example");
const supabaseReadmePath = path.join(root, "supabase/README.md");

const failures = [];

function readRequired(filePath) {
  if (!fs.existsSync(filePath)) {
    failures.push(`missing file: ${path.relative(root, filePath)}`);
    return "";
  }
  return fs.readFileSync(filePath, "utf8");
}

function requireMarker(source, marker, label) {
  if (!source.includes(marker)) failures.push(`${label}: missing ${marker}`);
}

function forbidMarker(source, marker, label) {
  if (source.toLowerCase().includes(marker.toLowerCase())) {
    failures.push(`${label}: forbidden ${marker}`);
  }
}

const buildGradle = readRequired(buildGradlePath);
const buildScript = readRequired(buildScriptPath);
const gateway = readRequired(gatewayPath);
const asr = readRequired(asrPath);
const voiceprint = readRequired(voiceprintPath);
const knowledgeDocumentParser = readRequired(knowledgeDocumentParserPath);
const gatewayRequirements = readRequired(gatewayRequirementsPath);
const gatewayEnv = readRequired(gatewayEnvPath);
const voiceprintEnv = readRequired(voiceprintEnvPath);
const integratedPreviewBuild = readRequired(integratedPreviewBuildPath);
const integratedMainActivity = readRequired(integratedMainActivityPath);
const supabaseFunction = readRequired(supabaseFunctionPath);
const knowledgeDocumentParserClient = readRequired(knowledgeDocumentParserClientPath);
const supabaseEnv = readRequired(supabaseEnvPath);
const supabaseReadme = readRequired(supabaseReadmePath);

for (const marker of [
  "def formalRelease",
  '"com.codex.air3nativecamera.dingdangexpert.v9"',
  "900000",
  '"9.0.0"',
  "formalRelease requires secureRuntime",
  "formalRelease forbids debugPrivateProvisioning",
  "V9_RELEASE_KEYSTORE",
  "V9_RELEASE_KEY_ALIAS",
  "V9_RELEASE_STORE_PASSWORD",
  "V9_RELEASE_KEY_PASSWORD",
  "signingConfigs",
  "signingConfig signingConfigs.release",
]) {
  requireMarker(buildGradle, marker, "app/build.gradle");
}

for (const marker of [
  '$applicationId = "com.codex.air3nativecamera.dingdangexpert.v9"',
  "$versionCode = 900000",
  '$versionName = "9.0.0"',
  '"-PformalRelease=true"',
  "assembleRelease",
  "apksigner",
  "Get-FileHash",
  "qwen3-vl-plus",
  "fun-asr-realtime",
  "s1aa729d0",
  "forbidden model",
  "[IO.Compression.ZipFile]::OpenRead",
]) {
  requireMarker(buildScript, marker, "scripts/build-v9-release.ps1");
}
forbidMarker(buildScript, "ExtractToDirectory", "scripts/build-v9-release.ps1");

for (const [source, marker, label] of [
  [buildGradle, 'optionalEnvironment("DIRECT_GPT_MODEL", "qwen3-vl-plus")', "app/build.gradle"],
  [buildGradle, 'direct_gpt_model_not_previous_stable', "app/build.gradle"],
  [gateway, 'PREVIOUS_STABLE_AI_MODEL = "qwen3-vl-plus"', "gateway.py"],
  [asr, 'PREVIOUS_STABLE_ASR_MODEL = "fun-asr-realtime"', "asr_proxy.py"],
  [voiceprint, 's1aa729d0', "voiceprint_proxy.py"],
  [knowledgeDocumentParser, 'parse_knowledge_document', "knowledge_document_parser.py"],
  [gatewayRequirements, 'pypdf==6.15.0', "gateway requirements"],
  [gatewayEnv, "V9_AI_MODEL=qwen3-vl-plus", "gateway env"],
  [gatewayEnv, "V9_ASR_MODEL=fun-asr-realtime", "gateway env"],
  [voiceprintEnv, "V9_VOICEPRINT_URL=https://api.xf-yun.com/v1/private/s1aa729d0", "voiceprint env"],
  [integratedPreviewBuild, '-DefaultValue "qwen3-vl-plus"', "integrated preview build"],
  [integratedMainActivity, 'https://dashscope.aliyuncs.com/compatible-mode/v1', "MainActivity"],
  [supabaseFunction, '"qwen3-vl-plus"', "Supabase vision default"],
  [supabaseFunction, '"fun-asr-realtime"', "Supabase ASR default"],
  [supabaseFunction, 'V9_KNOWLEDGE_PARSER_URL', "Supabase knowledge parser URL"],
  [supabaseFunction, 'V9_KNOWLEDGE_PARSER_TOKEN', "Supabase knowledge parser token"],
  [knowledgeDocumentParserClient, 'parsedUrl.protocol !== "https:"', "knowledge parser HTTPS guard"],
  [knowledgeDocumentParserClient, 'Authorization: `Bearer ${token}`', "knowledge parser bearer guard"],
  [supabaseEnv, "OPENAI_VISION_MODEL=qwen3-vl-plus", "Supabase env vision model"],
  [supabaseEnv, "OPENAI_TRANSCRIBE_MODEL=fun-asr-realtime", "Supabase env ASR model"],
  [supabaseReadme, "OPENAI_VISION_MODEL=qwen3-vl-plus", "Supabase README vision model"],
  [supabaseReadme, "OPENAI_TRANSCRIBE_MODEL=fun-asr-realtime", "Supabase README ASR model"],
]) {
  requireMarker(source, marker, label);
}

const productionContract = [
  buildGradle,
  gateway,
  asr,
  voiceprint,
  knowledgeDocumentParser,
  gatewayRequirements,
  gatewayEnv,
  voiceprintEnv,
  integratedPreviewBuild,
  integratedMainActivity,
  supabaseFunction,
  knowledgeDocumentParserClient,
  supabaseEnv,
  supabaseReadme,
].join("\n");
for (const forbidden of [
  "openclaw",
  "gpt-4.1-mini",
  "gpt-5.5",
  "gpt-5.6",
  "deepseek",
  "claude",
  "qwen-max",
  "api.openai.com",
]) {
  forbidMarker(productionContract, forbidden, "V9 production contract");
}

if (failures.length > 0) {
  throw new Error(`V9 release validation failed:\n- ${failures.join("\n- ")}`);
}

console.log("V9 formal release and previous-model contract validation passed.");
