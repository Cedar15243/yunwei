import { createHash, randomBytes } from "node:crypto";
import { access, readFile, rename, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import process from "node:process";

const APPROVED_AI_MODEL = "qwen3-vl-plus";
const APPROVED_ASR_MODEL = "fun-asr-realtime";
const APPROVED_VOICEPRINT_SERVICE = "s1aa729d0";

function argumentsMap(argv) {
  const result = new Map();
  for (let index = 0; index < argv.length; index += 2) {
    const name = argv[index];
    const value = argv[index + 1];
    if (!name?.startsWith("--") || value === undefined) {
      throw new Error("invalid_arguments");
    }
    result.set(name.slice(2), value);
  }
  return result;
}

function requiredArgument(args, name) {
  const value = String(args.get(name) ?? "").trim();
  if (!value) {
    throw new Error(`missing_argument:${name}`);
  }
  return value;
}

function parseEnv(text) {
  const result = {};
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator <= 0) throw new Error("environment_line_invalid");
    const name = line.slice(0, separator).trim();
    let value = line.slice(separator + 1).trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) {
      throw new Error("environment_name_invalid");
    }
    if (
      value.length >= 2 &&
      ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'")))
    ) {
      value = value.slice(1, -1);
    }
    result[name] = value;
  }
  return result;
}

function requiredEnv(environment, name) {
  const value = String(environment[name] ?? "").trim();
  if (!value) throw new Error(`environment_missing:${name}`);
  return value;
}

function validateSingleLine(name, value) {
  if (/[\r\n\0]/.test(value)) {
    throw new Error(`environment_value_invalid:${name}`);
  }
  return value;
}

function approvedHttpsUrl(name, value) {
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(`url_invalid:${name}`);
  }
  if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error(`url_invalid:${name}`);
  }
  return value.replace(/\/+$/, "");
}

function strongToken(existing) {
  if (existing && /^[A-Za-z0-9_-]{43,}$/.test(existing)) return existing;
  return randomBytes(32).toString("base64url");
}

function seededStrongToken(environment, name) {
  const value = String(environment[name] ?? "").trim();
  if (!value) return "";
  if (!/^[A-Za-z0-9_-]{43,}$/.test(value)) {
    throw new Error(`seed_token_invalid:${name}`);
  }
  return value;
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

async function readExisting(filePath) {
  try {
    await access(filePath);
    return parseEnv(await readFile(filePath, "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") return {};
    throw error;
  }
}

async function atomicWrite(filePath, environment) {
  const entries = Object.entries(environment).map(([name, value]) => {
    return `${name}=${validateSingleLine(name, String(value))}`;
  });
  const temporary = `${filePath}.${process.pid}.tmp`;
  await writeFile(temporary, `${entries.join("\n")}\n`, { encoding: "utf8", mode: 0o600 });
  await rm(filePath, { force: true });
  await rename(temporary, filePath);
}

function validateProviderBaseline(gateway, voiceprint) {
  if (requiredEnv(gateway, "V9_AI_MODEL") !== APPROVED_AI_MODEL) {
    throw new Error("provider_model_not_approved");
  }
  if (requiredEnv(gateway, "V9_ASR_MODEL") !== APPROVED_ASR_MODEL) {
    throw new Error("asr_model_not_approved");
  }
  const voiceprintUrl = requiredEnv(voiceprint, "V9_VOICEPRINT_URL");
  if (!voiceprintUrl.endsWith(`/${APPROVED_VOICEPRINT_SERVICE}`)) {
    throw new Error("voiceprint_service_not_approved");
  }
  requiredEnv(voiceprint, "IFLYTEK_APP_ID");
  requiredEnv(voiceprint, "IFLYTEK_API_KEY");
  requiredEnv(voiceprint, "IFLYTEK_API_SECRET");
}

async function main() {
  const args = argumentsMap(process.argv.slice(2));
  const gatewayEnvPath = path.resolve(requiredArgument(args, "gateway-env"));
  const voiceprintEnvPath = path.resolve(requiredArgument(args, "voiceprint-env"));
  const bootstrapTokenPath = path.resolve(requiredArgument(args, "bootstrap-token-file"));
  const supabaseSeedPath = path.resolve(requiredArgument(args, "supabase-seed-env"));
  const gatewaySeedPath = path.resolve(requiredArgument(args, "gateway-seed-env"));
  const supabaseOutputPath = path.resolve(requiredArgument(args, "supabase-output"));
  const gatewayOutputPath = path.resolve(requiredArgument(args, "gateway-output"));
  const voiceprintOutputPath = path.resolve(requiredArgument(args, "voiceprint-output"));
  const voiceprintRotationMode = requiredArgument(args, "voiceprint-rotation-mode").toLowerCase();
  if (!new Set(["steady", "transition"]).has(voiceprintRotationMode)) {
    throw new Error("voiceprint_rotation_mode_invalid");
  }
  const projectRef = requiredArgument(args, "project-ref");
  if (!/^[a-z0-9]{20}$/.test(projectRef)) throw new Error("project_ref_invalid");

  const publicGatewayBaseUrl = approvedHttpsUrl(
    "public-gateway-base-url",
    requiredArgument(args, "public-gateway-base-url"),
  );
  const recoveryRedirectUrl = approvedHttpsUrl(
    "account-recovery-redirect-url",
    requiredArgument(args, "account-recovery-redirect-url"),
  );
  const defaultAssetTag = requiredArgument(args, "default-asset-tag");
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{2,99}$/.test(defaultAssetTag)) {
    throw new Error("default_asset_tag_invalid");
  }
  const defaultSshPort = Number(requiredArgument(args, "default-target-ssh-port"));
  if (!Number.isInteger(defaultSshPort) || defaultSshPort <= 0 || defaultSshPort >= 65536) {
    throw new Error("default_target_ssh_port_invalid");
  }
  const defaultAppPortText = String(args.get("default-target-app-port") ?? "").trim();
  const defaultAppPort = defaultAppPortText ? Number(defaultAppPortText) : null;
  if (
    defaultAppPort !== null &&
    (!Number.isInteger(defaultAppPort) || defaultAppPort <= 0 || defaultAppPort >= 65536)
  ) {
    throw new Error("default_target_app_port_invalid");
  }
  const remoteProbeMode = requiredArgument(args, "remote-probe-mode").toLowerCase();
  if (!new Set(["tcp", "http"]).has(remoteProbeMode)) {
    throw new Error("remote_probe_mode_invalid");
  }
  const remoteProbeUrl = String(args.get("remote-probe-url") ?? "").trim();
  if (remoteProbeMode === "http" && !remoteProbeUrl) {
    throw new Error("remote_probe_url_missing");
  }
  if (remoteProbeUrl) approvedHttpsUrl("remote-probe-url", remoteProbeUrl);

  const gateway = parseEnv(await readFile(gatewayEnvPath, "utf8"));
  const voiceprint = parseEnv(await readFile(voiceprintEnvPath, "utf8"));
  const supabaseSeed = parseEnv(await readFile(supabaseSeedPath, "utf8"));
  const gatewaySeed = parseEnv(await readFile(gatewaySeedPath, "utf8"));
  validateProviderBaseline(gateway, voiceprint);
  const bootstrapToken = (await readFile(bootstrapTokenPath, "utf8")).trim();
  if (bootstrapToken.length < 24 || /\s/.test(bootstrapToken)) {
    throw new Error("bootstrap_token_invalid");
  }

  const existingSupabase = await readExisting(supabaseOutputPath);
  const existingGateway = await readExisting(gatewayOutputPath);
  const opsApiKey = strongToken(
    seededStrongToken(supabaseSeed, "OPS_GLASSES_API_KEY") || existingSupabase.OPS_GLASSES_API_KEY,
  );
  const voiceprintAdminToken = strongToken(
    seededStrongToken(supabaseSeed, "V9_VOICEPRINT_ADMIN_TOKEN") ||
      existingSupabase.V9_VOICEPRINT_ADMIN_TOKEN,
  );
  const knowledgeParserToken = strongToken(
    seededStrongToken(supabaseSeed, "V9_KNOWLEDGE_PARSER_TOKEN") ||
      existingSupabase.V9_KNOWLEDGE_PARSER_TOKEN,
  );
  const manifestSyncToken = strongToken(
    seededStrongToken(gatewaySeed, "V9_CONTENT_MANIFEST_SYNC_TOKEN") ||
      existingGateway.V9_CONTENT_MANIFEST_SYNC_TOKEN,
  );
  const supabaseBaseUrl = `https://${projectRef}.supabase.co`;
  const edgeBaseUrl = `${supabaseBaseUrl}/functions/v1/ops-glasses`;
  const providerApiKey = requiredEnv(gateway, "V9_AI_API_KEY");
  const providerBaseUrl = requiredEnv(gateway, "V9_AI_BASE_URL");
  const asrApiKey = String(gateway.V9_ASR_API_KEY ?? providerApiKey).trim() || providerApiKey;

  const supabaseSecrets = {
    AUTO_MIGRATE: "true",
    OPS_GLASSES_API_KEY: opsApiKey,
    OPENAI_API_KEY: providerApiKey,
    OPENAI_BASE_URL: providerBaseUrl,
    OPENAI_VISION_MODEL: APPROVED_AI_MODEL,
    OPENAI_TRANSCRIBE_API_KEY: asrApiKey,
    OPENAI_TRANSCRIBE_BASE_URL: providerBaseUrl,
    OPENAI_TRANSCRIBE_MODEL: APPROVED_ASR_MODEL,
    DASHSCOPE_API_KEY: asrApiKey,
    DASHSCOPE_FUNASR_URL: requiredEnv(gateway, "V9_ASR_URL"),
    DASHSCOPE_FUNASR_MODEL: APPROVED_ASR_MODEL,
    V9_GATEWAY_SYNC_TOKEN_SHA256: sha256(manifestSyncToken),
    V9_GATEWAY_SYNC_ORGANIZATION_ID: requiredEnv(gateway, "V9_ORGANIZATION_ID"),
    V9_GATEWAY_SYNC_USER_ID: requiredEnv(gateway, "V9_USER_ID"),
    V9_GATEWAY_SYNC_DEVICE_ID: requiredEnv(gateway, "V9_DEVICE_ID"),
    V9_VOICEPRINT_ADMIN_URL: publicGatewayBaseUrl,
    V9_VOICEPRINT_ADMIN_TOKEN: voiceprintAdminToken,
    V9_KNOWLEDGE_PARSER_URL: publicGatewayBaseUrl,
    V9_KNOWLEDGE_PARSER_TOKEN: knowledgeParserToken,
    V9_DEVICE_ACTIVATION_BACKEND_BASE_URL: publicGatewayBaseUrl,
    V9_DEVICE_ACTIVATION_POLICY_VERSION: "v9-production-1",
    OPS_ACCOUNT_RECOVERY_REDIRECT_URL: recoveryRedirectUrl,
    DEFAULT_ASSET_TAG: defaultAssetTag,
    DEFAULT_TARGET_SSH_PORT: String(defaultSshPort),
    DEFAULT_TARGET_APP_PORT: defaultAppPort === null ? "" : String(defaultAppPort),
    REMOTE_PROBE_MODE: remoteProbeMode,
    REMOTE_PROBE_URL: remoteProbeUrl,
  };
  const gatewayOverlay = {
    V9_CONTENT_MANIFEST_SYNC_BASE_URL: edgeBaseUrl,
    V9_CONTENT_MANIFEST_SYNC_TOKEN: manifestSyncToken,
    V9_CONTROL_PLANE_SYNC_BASE_URL: edgeBaseUrl,
    V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN: bootstrapToken,
    V9_KNOWLEDGE_PARSER_TOKEN_SHA256: sha256(knowledgeParserToken),
  };
  const voiceprintOverlay = {
    V9_VOICEPRINT_ADMIN_TOKEN_SHA256: sha256(voiceprintAdminToken),
    V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256: voiceprintRotationMode === "transition"
      ? requiredEnv(voiceprint, "V9_VOICEPRINT_ADMIN_TOKEN_SHA256")
      : "",
  };
  if (
    voiceprintOverlay.V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256 &&
    !/^[0-9a-f]{64}$/.test(voiceprintOverlay.V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256)
  ) {
    throw new Error("voiceprint_previous_token_hash_invalid");
  }

  await atomicWrite(supabaseOutputPath, supabaseSecrets);
  await atomicWrite(gatewayOutputPath, gatewayOverlay);
  await atomicWrite(voiceprintOutputPath, voiceprintOverlay);
  process.stdout.write(
    `Prepared ${Object.keys(supabaseSecrets).length} Supabase variables at ${supabaseOutputPath}\n` +
      `Prepared ${Object.keys(gatewayOverlay).length} gateway variables at ${gatewayOutputPath}\n` +
      `Prepared ${Object.keys(voiceprintOverlay).length} voiceprint variable at ${voiceprintOutputPath}\n`,
  );
}

main().catch((error) => {
  process.stderr.write(`${error?.message || "production_secret_preparation_failed"}\n`);
  process.exitCode = 1;
});
