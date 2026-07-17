import { resolve } from "node:path";

export interface ServerConfig {
  sdkAppId: number;
  sdkSecret: string;
  host: string;
  port: number;
  allowedOrigin: string;
  websocketPath: "/collab";
  freezeDirectory: string;
}

export interface PublicConfig {
  sdkAppId: number;
  websocketPath: "/collab";
}

type Environment = Record<string, string | undefined>;

function readPositiveInteger(value: string | undefined, name: string, fallback?: number): number {
  if ((value === undefined || value.trim() === "") && fallback !== undefined) {
    return fallback;
  }
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}

export function loadConfig(environment: Environment = process.env): ServerConfig {
  const sdkSecret = environment.TRTC_SDK_SECRET?.trim();
  if (!sdkSecret) {
    throw new Error("TRTC_SDK_SECRET is required");
  }

  return {
    sdkAppId: readPositiveInteger(environment.TRTC_SDK_APP_ID, "TRTC_SDK_APP_ID"),
    sdkSecret,
    host: environment.COLLAB_HOST?.trim() || "127.0.0.1",
    port: readPositiveInteger(environment.COLLAB_PORT, "COLLAB_PORT", 8787),
    allowedOrigin: environment.COLLAB_ORIGIN?.trim() || "http://localhost:5173",
    websocketPath: "/collab",
    freezeDirectory: resolve(environment.COLLAB_FREEZE_DIR?.trim() || "tmp/expert-collab-freezes"),
  };
}

export function createPublicConfig(config: ServerConfig): PublicConfig {
  return {
    sdkAppId: config.sdkAppId,
    websocketPath: config.websocketPath,
  };
}
