import { createHmac } from "node:crypto";
import { deflateSync } from "node:zlib";

export interface UserSigOptions {
  sdkAppId: number;
  sdkSecret: string;
  userId: string;
  expireSeconds?: number;
  nowSeconds?: number;
}

function toTencentBase64(buffer: Buffer): string {
  return buffer.toString("base64").replaceAll("+", "*").replaceAll("/", "-").replaceAll("=", "_");
}

export function generateUserSig(options: UserSigOptions): string {
  const userId = options.userId.trim();
  if (!userId) {
    throw new Error("userId is required");
  }
  if (!Number.isInteger(options.sdkAppId) || options.sdkAppId <= 0) {
    throw new Error("sdkAppId must be a positive integer");
  }
  if (!options.sdkSecret) {
    throw new Error("sdkSecret is required");
  }

  const expireSeconds = options.expireSeconds ?? 900;
  const nowSeconds = options.nowSeconds ?? Math.floor(Date.now() / 1000);
  if (!Number.isInteger(expireSeconds) || expireSeconds <= 0) {
    throw new Error("expireSeconds must be a positive integer");
  }

  const signatureInput = [
    `TLS.identifier:${userId}`,
    `TLS.sdkappid:${options.sdkAppId}`,
    `TLS.time:${nowSeconds}`,
    `TLS.expire:${expireSeconds}`,
    "",
  ].join("\n");
  const signature = createHmac("sha256", options.sdkSecret)
    .update(signatureInput)
    .digest("base64");

  const document = {
    "TLS.ver": "2.0",
    "TLS.identifier": userId,
    "TLS.sdkappid": options.sdkAppId,
    "TLS.time": nowSeconds,
    "TLS.expire": expireSeconds,
    "TLS.sig": signature,
  };

  return toTencentBase64(deflateSync(Buffer.from(JSON.stringify(document), "utf8")));
}
