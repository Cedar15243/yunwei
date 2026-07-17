import { createRequire } from "node:module";
import { inflateSync } from "node:zlib";
import { describe, expect, it, vi } from "vitest";
import { generateUserSig } from "../src/trtc-user-sig.js";

const require = createRequire(import.meta.url);
const { Api: OfficialTlsSigApi } = require("tls-sig-api-v2") as {
  Api: new (sdkAppId: number, sdkSecret: string) => {
    genUserSig(userId: string, expireSeconds: number): string;
  };
};

function decodeUserSig(userSig: string): Record<string, unknown> {
  const base64 = userSig.replaceAll("*", "+").replaceAll("-", "/").replaceAll("_", "=");
  return JSON.parse(inflateSync(Buffer.from(base64, "base64")).toString("utf8"));
}

describe("generateUserSig", () => {
  it("matches the Tencent TLS Sig API v2 encoding", () => {
    const nowSeconds = 1_784_253_600;
    const dateNow = vi.spyOn(Date, "now").mockReturnValue(nowSeconds * 1000);

    try {
      const officialApi = new OfficialTlsSigApi(1600152353, "private-secret");
      const officialUserSig = officialApi.genUserSig("expert-wang", 900);

      expect(generateUserSig({
        sdkAppId: 1600152353,
        sdkSecret: "private-secret",
        userId: "expert-wang",
        expireSeconds: 900,
        nowSeconds,
      })).toBe(officialUserSig);
    } finally {
      dateNow.mockRestore();
    }
  });

  it("creates a short-lived TLS signature for the requested identity", () => {
    const userSig = generateUserSig({
      sdkAppId: 1600152353,
      sdkSecret: "private-secret",
      userId: "expert-wang",
      expireSeconds: 900,
      nowSeconds: 1_784_253_600,
    });

    const payload = decodeUserSig(userSig);

    expect(payload["TLS.ver"]).toBe("2.0");
    expect(payload["TLS.identifier"]).toBe("expert-wang");
    expect(payload["TLS.sdkappid"]).toBe(1600152353);
    expect(payload["TLS.time"]).toBe(1_784_253_600);
    expect(payload["TLS.expire"]).toBe(900);
    expect(payload["TLS.sig"]).toMatch(/^[A-Za-z0-9+/=]+$/);
  });

  it("rejects identities that cannot be safely signed", () => {
    expect(() => generateUserSig({
      sdkAppId: 1600152353,
      sdkSecret: "private-secret",
      userId: "",
      expireSeconds: 900,
      nowSeconds: 1_784_253_600,
    })).toThrow("userId");
  });
});
