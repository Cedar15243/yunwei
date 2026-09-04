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

async function startServerWithLifecycle(options: {
  pendingCallTimeoutMs?: number;
  pendingCallSweepIntervalMs?: number;
  participantDisconnectGraceMs?: number;
  heartbeatIntervalMs?: number;
}): Promise<RunningCollabServer> {
  const server = await createCollabServer(config, options).start(0);
  runningServers.push(server);
  return server;
}

function expectNoMessage(socket: WebSocket, timeoutMs = 120): Promise<void> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      socket.off("message", handleMessage);
      resolve();
    }, timeoutMs);
    const handleMessage = (data: WebSocket.RawData): void => {
      clearTimeout(timeout);
      reject(new Error(`unexpected websocket message: ${data.toString()}`));
    };
    socket.once("message", handleMessage);
  });
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

  it("accepts the SDK expertId contract and returns the room identity", async () => {
    const server = await startServer();
    const response = await fetch(`${server.httpUrl}/api/trtc/credential`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ sessionId: "session-1", expertId: "expert-wang", expertName: "王工" }),
    });
    const credential = await response.json() as Record<string, unknown>;

    expect(response.status).toBe(200);
    expect(credential).toMatchObject({
      userId: "expert-wang",
      expertId: "expert-wang",
      expertName: "王工",
      roomId: "session-1",
    });
    expect(credential.userSig).toEqual(expect.any(String));
  });

  it("rejects conflicting userId and expertId values", async () => {
    const server = await startServer();
    const response = await fetch(`${server.httpUrl}/api/trtc/credential`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ userId: "glasses-01", expertId: "expert-wang" }),
    });

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toEqual({ error: "userId and expertId must match" });
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

  it("deduplicates a retried pending call from the same glasses", async () => {
    const server = await startServer();
    const expert = await openSocket(server.websocketUrl);
    const glasses = await openSocket(server.websocketUrl);

    expert.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "expert-wang", seq: 1, sentAt: 1, payload: { kind: "expert", name: "Wang" } }));
    glasses.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "glasses-01", seq: 1, sentAt: 1, payload: { kind: "glasses", name: "Air3-01" } }));
    await Promise.all([nextMessage(expert), nextMessage(glasses)]);

    const firstIncoming = nextMessage(expert);
    glasses.send(JSON.stringify({ type: "call.requested", sessionId: null, senderId: "glasses-01", seq: 2, sentAt: 2, payload: {} }));
    const [firstCall] = await Promise.all([firstIncoming, nextMessage(glasses)]);

    const replayAck = nextMessage(glasses);
    const noDuplicateRing = expectNoMessage(expert);
    glasses.send(JSON.stringify({ type: "call.requested", sessionId: null, senderId: "glasses-01", seq: 3, sentAt: 3, payload: {} }));
    const replayed = await replayAck;

    expect(replayed).toMatchObject({ type: "call.requested", sessionId: firstCall.sessionId });
    await noDuplicateRing;

    expert.close();
    glasses.close();
  });

  it("ends and broadcasts a pending call when the last glasses connection closes", async () => {
    const server = await startServer();
    const expert = await openSocket(server.websocketUrl);
    const glasses = await openSocket(server.websocketUrl);

    expert.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "expert-wang", seq: 1, sentAt: 1, payload: { kind: "expert", name: "Wang" } }));
    glasses.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "glasses-01", seq: 1, sentAt: 1, payload: { kind: "glasses", name: "Air3-01" } }));
    await Promise.all([nextMessage(expert), nextMessage(glasses)]);

    const incoming = nextMessage(expert);
    glasses.send(JSON.stringify({ type: "call.requested", sessionId: null, senderId: "glasses-01", seq: 2, sentAt: 2, payload: {} }));
    const [call] = await Promise.all([incoming, nextMessage(glasses)]);

    const ended = nextMessage(expert);
    glasses.close();

    expect(await ended).toMatchObject({ type: "call.ended", sessionId: call.sessionId, payload: {} });

    expert.close();
  });

  it("ends and broadcasts an unanswered call after its timeout", async () => {
    const server = await startServerWithLifecycle({
      pendingCallTimeoutMs: 40,
      pendingCallSweepIntervalMs: 5,
    });
    const expert = await openSocket(server.websocketUrl);
    const glasses = await openSocket(server.websocketUrl);

    expert.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "expert-wang", seq: 1, sentAt: 1, payload: { kind: "expert", name: "Wang" } }));
    glasses.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "glasses-01", seq: 1, sentAt: 1, payload: { kind: "glasses", name: "Air3-01" } }));
    await Promise.all([nextMessage(expert), nextMessage(glasses)]);

    const incoming = nextMessage(expert);
    glasses.send(JSON.stringify({ type: "call.requested", sessionId: null, senderId: "glasses-01", seq: 2, sentAt: 2, payload: {} }));
    const [call] = await Promise.all([incoming, nextMessage(glasses)]);

    expect(await nextMessage(expert)).toMatchObject({ type: "call.ended", sessionId: call.sessionId, payload: {} });

    expert.close();
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

  it("notifies one accepting expert and marks every other console as taken", async () => {
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
    expect(results.map((message) => message.type)).toEqual(["call.accepted", "call.taken", "call.accepted"]);
    expect(results.map((message) => (message.payload as Record<string, unknown>).expertId)).toEqual([
      "expert-wang",
      "expert-wang",
      "expert-wang",
    ]);

    expertWang.close();
    expertLiu.close();
    glasses.close();
  });

  it("replays an accepted call when the expert reconnects within the grace period", async () => {
    const server = await startServerWithLifecycle({ participantDisconnectGraceMs: 200 });
    const expert = await openSocket(server.websocketUrl);
    const glasses = await openSocket(server.websocketUrl);
    expert.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "expert-wang", seq: 1, sentAt: 1, payload: { kind: "expert", name: "Wang" } }));
    glasses.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "glasses-01", seq: 1, sentAt: 1, payload: { kind: "glasses", name: "Air3-01" } }));
    await Promise.all([nextMessage(expert), nextMessage(glasses)]);
    const incoming = nextMessage(expert);
    glasses.send(JSON.stringify({ type: "call.requested", sessionId: null, senderId: "glasses-01", seq: 2, sentAt: 2, payload: {} }));
    const [call] = await Promise.all([incoming, nextMessage(glasses)]);
    const expertAccepted = nextMessage(expert);
    const glassesAccepted = nextMessage(glasses);
    expert.send(JSON.stringify({ type: "call.accepted", sessionId: call.sessionId, senderId: "expert-wang", seq: 2, sentAt: 3, payload: {} }));
    await Promise.all([expertAccepted, glassesAccepted]);
    await new Promise<void>((resolve) => expert.once("close", resolve).close());

    const reconnected = await openSocket(server.websocketUrl);
    const replay = new Promise<Record<string, unknown>[]>((resolve, reject) => {
      const messages: Record<string, unknown>[] = [];
      const timeout = setTimeout(() => reject(new Error("accepted replay timeout")), 2_000);
      reconnected.on("message", (data) => {
        messages.push(JSON.parse(data.toString()));
        if (messages.length === 2) {
          clearTimeout(timeout);
          resolve(messages);
        }
      });
    });
    reconnected.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "expert-wang", seq: 1, sentAt: 4, payload: { kind: "expert", name: "Wang" } }));

    const [, accepted] = await replay;
    expect(accepted).toMatchObject({
      type: "call.accepted",
      sessionId: call.sessionId,
      payload: { expertId: "expert-wang", glassesId: "glasses-01" },
    });

    reconnected.close();
    glasses.close();
  });

  it("ends an accepted call when the expert does not reconnect", async () => {
    const server = await startServerWithLifecycle({ participantDisconnectGraceMs: 20 });
    const expert = await openSocket(server.websocketUrl);
    const glasses = await openSocket(server.websocketUrl);
    expert.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "expert-wang", seq: 1, sentAt: 1, payload: { kind: "expert", name: "Wang" } }));
    glasses.send(JSON.stringify({ type: "presence.registered", sessionId: null, senderId: "glasses-01", seq: 1, sentAt: 1, payload: { kind: "glasses", name: "Air3-01" } }));
    await Promise.all([nextMessage(expert), nextMessage(glasses)]);
    const incoming = nextMessage(expert);
    glasses.send(JSON.stringify({ type: "call.requested", sessionId: null, senderId: "glasses-01", seq: 2, sentAt: 2, payload: {} }));
    const [call] = await Promise.all([incoming, nextMessage(glasses)]);
    const expertAccepted = nextMessage(expert);
    const glassesAccepted = nextMessage(glasses);
    expert.send(JSON.stringify({ type: "call.accepted", sessionId: call.sessionId, senderId: "expert-wang", seq: 2, sentAt: 3, payload: {} }));
    await Promise.all([expertAccepted, glassesAccepted]);
    const ended = nextMessage(glasses);
    expert.terminate();

    expect(await ended).toMatchObject({ type: "call.ended", sessionId: call.sessionId });
    glasses.close();
  });

  it("terminates a half-open websocket that does not answer protocol pings", async () => {
    const server = await startServerWithLifecycle({ heartbeatIntervalMs: 20 });
    const socket = await new Promise<WebSocket>((resolve, reject) => {
      const candidate = new WebSocket(server.websocketUrl, { autoPong: false });
      candidate.once("open", () => resolve(candidate));
      candidate.once("error", reject);
    });

    await new Promise<void>((resolve) => socket.once("close", () => resolve()));
    expect(socket.readyState).toBe(WebSocket.CLOSED);
  });
});
