export type RemoteProbeMode = "disabled" | "tcp" | "http";

export type RemoteProbeAsset = {
  asset_tag?: unknown;
  display_name?: unknown;
  host?: unknown;
};

const controlledConfigurationErrors = new Set([
  "default_asset_not_configured",
  "default_asset_not_found",
  "legacy_demo_asset_forbidden",
  "remote_probe_not_configured",
  "remote_probe_mode_invalid",
  "remote_probe_url_missing",
  "remote_probe_https_required",
]);

export function normalizeRemoteProbeMode(value: string | undefined): RemoteProbeMode {
  const normalized = (value ?? "").trim().toLowerCase();
  if (!normalized || normalized === "disabled") return "disabled";
  if (normalized === "tcp" || normalized === "http") return normalized;
  throw new Error("remote_probe_mode_invalid");
}

export function assertRemoteProbeConfigured(mode: RemoteProbeMode, endpoint: string): void {
  if (mode === "disabled") throw new Error("remote_probe_not_configured");
  if (mode !== "http") return;
  const trimmed = endpoint.trim();
  if (!trimmed) throw new Error("remote_probe_url_missing");
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    throw new Error("remote_probe_https_required");
  }
  if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password) {
    throw new Error("remote_probe_https_required");
  }
}

export function isLegacyDemoAsset(asset: RemoteProbeAsset): boolean {
  return asset.asset_tag === "ASSET-CONSOLE-001" ||
    (asset.display_name === "SSH console recovery demo server" && asset.host === "192.168.1.50");
}

export function controlledConfigurationError(error: unknown): string | null {
  const code = error instanceof Error ? error.message : "";
  return controlledConfigurationErrors.has(code) ? code : null;
}
