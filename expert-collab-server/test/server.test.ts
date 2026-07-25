import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, afterEach, describe, expect, it } from "vitest";
import WebSocket from "ws";
import type { ServerConfig } from "../src/config.js";
import { createCollabServer, type RunningCollabServer } from "../src/server.js";

const freezeDirectory = mkdtempSync(join(tmpdir(), "expert-collab-freezes-"));

const config: ServerConfig = {
  sdkAppId: 1600152353,
  sdkSecret: "private-secret",
  host: "127.0.0.1",
  port: 8787,
  allowedOrigin: "http://localhost:5173",
  websocketPath: "/collab",
  freezeDirectory,
};

const runningServers: RunningCollabServer[] = [];

afterEach(async () => {
  await Promise.all(runningServers.splice(0).map((server) => server.close()));
});

afterAll(() => {
  rmSync(freezeDirectory, { recursive: true, force: true });
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
  it("allows each configured expert console origin", async () => {
    const server = await createCollabServer({
      ...config,
      allowedOrigin: "http://localhost:5174,http://192.168.30.205:5174",
    }).start(0);
    runningServers.push(server);

    const response = await fetch(`${server.httpUrl}/api/config`, {
      headers: { origin: "http://localhost:5174" },
    });

    expect(response.headers.get("access-control-allow-origin")).toBe("http://localhost:5174");
  });

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

  it("stores and serves a frozen JPEG frame", async () => {
    const server = await startServer();
    const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xd9]);
    const response = await fetch(`${server.httpUrl}/api/sessions/session-1/freeze`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ imageDataUrl: `data:image/jpeg;base64,${jpeg.toString("base64")}` }),
    });
    const result = await response.json() as { url: string };

    expect(response.status).toBe(201);
    expect(result.url).toMatch(/^\/api\/freezes\/[a-f0-9-]+\.jpg$/);
    const stored = await fetch(`${server.httpUrl}${result.url}`);
    expect(stored.headers.get("content-type")).toContain("image/jpeg");
    expect(Buffer.from(await stored.arrayBuffer())).toEqual(jpeg);
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

  it("delivers a pending eye-glasses call to an expert who opens the console later", async () => {
    const server = await startServer();
    const glasses = await openSocket(server.websocketUrl);

    glasses.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "glasses-01", seq: 1, sentAt: 1, payload: { kind: "glasses", name: "Air3-01" } }));
    await nextMessage(glasses);
    glasses.send(JSON.stringify({ type: "call.requested", sessionId: null, senderId: "glasses-01", seq: 2, sentAt: 2, payload: {} }));
    await nextMessage(glasses);

    const expertWang = await openSocket(server.websocketUrl);
    const received = new Promise<Record<string, unknown>[]>((resolve, reject) => {
      const messages: Record<string, unknown>[] = [];
      const timeout = setTimeout(() => reject(new Error("websocket messages timeout")), 2_000);
      expertWang.on("message", (data) => {
        messages.push(JSON.parse(data.toString()));
        if (messages.length === 2) {
          clearTimeout(timeout);
          resolve(messages);
        }
      });
    });
    expertWang.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "expert-wang", seq: 1, sentAt: 3, payload: { kind: "expert", name: "Wang" } }));

    const [registered, incoming] = await received;
    expect(registered.type).toBe("presence.registered");
    expect(incoming.type).toBe("call.requested");
    expect((incoming.payload as Record<string, unknown>).glassesId).toBe("glasses-01");

    expertWang.close();
    glasses.close();
  });

  it("keeps an expert online while another tab with the same identity remains connected", async () => {
    const server = await startServer();
    const firstTab = await openSocket(server.websocketUrl);
    const secondTab = await openSocket(server.websocketUrl);
    const glasses = await openSocket(server.websocketUrl);

    firstTab.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "expert-wang", seq: 1, sentAt: 1, payload: { kind: "expert", name: "Wang" } }));
    secondTab.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "expert-wang", seq: 1, sentAt: 1, payload: { kind: "expert", name: "Wang" } }));
    glasses.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "glasses-01", seq: 1, sentAt: 1, payload: { kind: "glasses", name: "Air3-01" } }));
    await Promise.all([nextMessage(firstTab), nextMessage(secondTab), nextMessage(glasses)]);

    const firstTabClosed = new Promise<void>((resolve) => firstTab.once("close", () => resolve()));
    firstTab.close();
    await firstTabClosed;
    await new Promise((resolve) => setTimeout(resolve, 50));

    const incomingCall = nextMessage(secondTab);
    glasses.send(JSON.stringify({ type: "call.requested", sessionId: null, senderId: "glasses-01", seq: 2, sentAt: 2, payload: {} }));

    expect((await incomingCall).type).toBe("call.requested");

    secondTab.close();
    glasses.close();
  });

  it("notifies every expert when the first expert accepts a call", async () => {
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
    const [incoming] = await Promise.all([wangCall, liuCall, nextMessage(glasses)]);
    const sessionId = incoming.sessionId as string;

    const wangAccepted = nextMessage(expertWang);
    const liuAccepted = nextMessage(expertLiu);
    const glassesAccepted = nextMessage(glasses);
    expertWang.send(JSON.stringify({ type: "call.accepted", sessionId, senderId: "expert-wang", seq: 2, sentAt: 3, payload: {} }));

    const results = await Promise.all([wangAccepted, liuAccepted, glassesAccepted]);
    expect(results.map((message) => message.type)).toEqual(["call.accepted", "call.accepted", "call.accepted"]);
    expect(results.map((message) => (message.payload as Record<string, unknown>).expertId)).toEqual([
      "expert-wang",
      "expert-wang",
      "expert-wang",
    ]);

    expertWang.close();
    expertLiu.close();
    glasses.close();
  });
});
