import {
  assertRemoteProbeConfigured,
  controlledConfigurationError,
  isLegacyDemoAsset,
  normalizeRemoteProbeMode,
} from "./remote-probe-policy.ts";

import { assertEquals, assertThrows } from "jsr:@std/assert@1";

Deno.test("remote probe defaults to disabled and rejects the former mock mode", () => {
  assertEquals(normalizeRemoteProbeMode(undefined), "disabled");
  assertEquals(normalizeRemoteProbeMode(""), "disabled");
  assertEquals(normalizeRemoteProbeMode("tcp"), "tcp");
  assertEquals(normalizeRemoteProbeMode("http"), "http");
  const invalidMode = assertThrows(
    () => normalizeRemoteProbeMode("mock"),
    Error,
    "remote_probe_mode_invalid",
  );
  assertEquals(controlledConfigurationError(invalidMode), "remote_probe_mode_invalid");
});

Deno.test("remote HTTP probe requires an explicit HTTPS endpoint", () => {
  assertThrows(
    () => assertRemoteProbeConfigured("disabled", ""),
    Error,
    "remote_probe_not_configured",
  );
  assertThrows(
    () => assertRemoteProbeConfigured("http", ""),
    Error,
    "remote_probe_url_missing",
  );
  assertThrows(
    () => assertRemoteProbeConfigured("http", "http://probe.example.test"),
    Error,
    "remote_probe_https_required",
  );
  assertRemoteProbeConfigured("http", "https://probe.example.test/check");
  assertRemoteProbeConfigured("tcp", "");
});

Deno.test("known legacy demo asset cannot become a real task target", () => {
  assertEquals(isLegacyDemoAsset({
    asset_tag: "ASSET-CONSOLE-001",
    display_name: "SSH console recovery demo server",
    host: "192.168.1.50",
  }), true);
  assertEquals(isLegacyDemoAsset({
    asset_tag: "ASSET-CONSOLE-001",
    display_name: "renamed asset",
    host: "10.20.30.40",
  }), true);
  assertEquals(isLegacyDemoAsset({
    asset_tag: "HF-SERVER-001",
    display_name: "华方生产服务器",
    host: "10.20.30.40",
  }), false);
});
