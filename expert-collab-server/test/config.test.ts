import { describe, expect, it } from "vitest";
import { createPublicConfig, loadConfig } from "../src/config.js";

describe("server config", () => {
  it("never exposes the TRTC secret in public config", () => {
    const config = loadConfig({
      TRTC_SDK_APP_ID: "1600152353",
      TRTC_SDK_SECRET: "private-secret",
      COLLAB_PORT: "8787",
    });

    const publicConfig = createPublicConfig(config);

    expect(publicConfig).toEqual({ sdkAppId: 1600152353, websocketPath: "/collab" });
    expect(JSON.stringify(publicConfig)).not.toContain("private-secret");
  });

  it("rejects startup without a TRTC secret", () => {
    expect(() => loadConfig({ TRTC_SDK_APP_ID: "1600152353" })).toThrow("TRTC_SDK_SECRET");
  });
});
