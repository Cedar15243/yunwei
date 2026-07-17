import { inflateSync } from "node:zlib";
import { describe, expect, it } from "vitest";
import { generateUserSig } from "../src/trtc-user-sig.js";

function decodeUserSig(userSig: string): Record<string, unknown> {
  const base64 = userSig.replaceAll("-", "+").replaceAll("_", "/");
  const padding = "=".repeat((4 - (base64.length % 4)) % 4);
  return JSON.parse(inflateSync(Buffer.from(base64 + padding, "base64")).toString("utf8"));
}

describe("generateUserSig", () => {
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
