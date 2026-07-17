import { afterEach, describe, expect, it } from "vitest";
import WebSocket from "ws";
import type { ServerConfig } from "../src/config.js";
import { createCollabServer, type RunningCollabServer } from "../src/server.js";

const config: ServerConfig = {
  sdkAppId: 1600152353,
  sdkSecret: "private-secret",
  host: "127.0.0.1",
  port: 8787,
  allowedOrigin: "http://localhost:5173",
  websocketPath: "/collab",
};

const runningServers: RunningCollabServer[] = [];

afterEach(async () => {
  await Promise.all(runningServers.splice(0).map((server) => server.close()));
});

async function startServer(): Promise<RunningCollabServer> {
  const server = await createCollabServer(config).start(0);
  runningServers.push(server);
  return server;
}

function openSocket(url: string): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    socket.once("open", () => resolve(socket));
    socket.once("error", reject);
  });
}

function nextMessage(socket: WebSocket): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error("websocket message timeout")), 2_000);
    socket.once("message", (data) => {
      clearTimeout(timeout);
      resolve(JSON.parse(data.toString()));
    });
  });
}

describe("collaboration server", () => {
  it("serves health and public config without the secret", async () => {
    const server = await startServer();
    const health = await fetch(`${server.httpUrl}/health`).then((response) => response.json());
    const publicConfig = await fetch(`${server.httpUrl}/api/config`).then((response) => response.json());

    expect(health).toEqual({ ok: true, service: "expert-collab" });
    expect(publicConfig).toEqual({ sdkAppId: 1600152353, websocketPath: "/collab" });
    expect(JSON.stringify(publicConfig)).not.toContain("private-secret");
  });

  it("issues a 15 minute credential without returning the secret", async () => {
    const server = await startServer();
    const response = await fetch(`${server.httpUrl}/api/trtc/credential`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ userId: "expert-wang" }),
    });
    const credential = await response.json() as Record<string, unknown>;

    expect(response.status).toBe(200);
    expect(credential.sdkAppId).toBe(1600152353);
    expect(credential.userId).toBe("expert-wang");
    expect(credential.expiresIn).toBe(900);
    expect(credential.userSig).toEqual(expect.any(String));
    expect(JSON.stringify(credential)).not.toContain("private-secret");
  });

  it("broadcasts an eye-glasses call to every online expert", async () => {
    const server = await startServer();
    const expertWang = await openSocket(server.websocketUrl);
    const expertLiu = await openSocket(server.websocketUrl);
    const glasses = await openSocket(server.websocketUrl);

    expertWang.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "expert-wang", seq: 1, sentAt: 1, payload: { kind: "expert", name: "王工" } }));
    expertLiu.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "expert-liu", seq: 1, sentAt: 1, payload: { kind: "expert", name: "刘工" } }));
    glasses.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "glasses-01", seq: 1, sentAt: 1, payload: { kind: "glasses", name: "Air3-现场01" } }));
    await Promise.all([nextMessage(expertWang), nextMessage(expertLiu), nextMessage(glasses)]);

    const wangCall = nextMessage(expertWang);
    const liuCall = nextMessage(expertLiu);
    glasses.send(JSON.stringify({ type: "call.requested", sessionId: null, senderId: "glasses-01", seq: 2, sentAt: 2, payload: {} }));

    expect((await wangCall).type).toBe("call.requested");
    expect((await liuCall).type).toBe("call.requested");

    expertWang.close();
    expertLiu.close();
    glasses.close();
  });
});
