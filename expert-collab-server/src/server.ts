import http from "node:http";
import { randomUUID } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import express from "express";
import { WebSocket, WebSocketServer } from "ws";
import { createPublicConfig, loadConfig, type ServerConfig } from "./config.js";
import { parseMessage, type Envelope } from "./protocol.js";
import { DEFAULT_PENDING_CALL_TIMEOUT_MS, SessionStore } from "./session-store.js";
import { generateUserSig } from "./trtc-user-sig.js";

interface RegisteredClient {
  id: string;
  kind: "expert" | "glasses";
  name: string;
}

export interface RunningCollabServer {
  httpUrl: string;
  websocketUrl: string;
  close(): Promise<void>;
}

export interface CollabServer {
  start(portOverride?: number): Promise<RunningCollabServer>;
}

export interface CollabServerOptions {
  pendingCallTimeoutMs?: number;
  pendingCallSweepIntervalMs?: number;
  participantDisconnectGraceMs?: number;
  heartbeatIntervalMs?: number;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function send(socket: WebSocket, message: Envelope): void {
  if (socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(message));
  }
}

export function createCollabServer(config: ServerConfig, options: CollabServerOptions = {}): CollabServer {
  const app = express();
  const server = http.createServer(app);
  const websocketServer = new WebSocketServer({ noServer: true });
  const store = new SessionStore(
    undefined,
    Date.now,
    options.pendingCallTimeoutMs ?? DEFAULT_PENDING_CALL_TIMEOUT_MS,
  );
  const clients = new Map<WebSocket, RegisteredClient>();
  const allowedOrigins = new Set(config.allowedOrigin.split(",").map((origin) => origin.trim()).filter(Boolean));
  let serverSequence = 0;
  let pendingCallSweep: ReturnType<typeof setInterval> | null = null;
  let heartbeat: ReturnType<typeof setInterval> | null = null;
  const socketAlive = new WeakMap<WebSocket, boolean>();
  const expertDisconnectTimers = new Map<string, ReturnType<typeof setTimeout>>();
  const participantDisconnectGraceMs = options.participantDisconnectGraceMs ?? 8_000;

  const envelope = (type: string, sessionId: string | null, payload: Record<string, unknown>): Envelope => ({
    type,
    sessionId,
    senderId: "server",
    seq: ++serverSequence,
    sentAt: Date.now(),
    payload,
  });

  const broadcastEnded = (sessionIds: string[]): void => {
    for (const sessionId of sessionIds) {
      const ended = envelope("call.ended", sessionId, {});
      for (const peerSocket of clients.keys()) {
        send(peerSocket, ended);
      }
    }
  };

  const clearExpertDisconnectTimer = (expertId: string): void => {
    const timer = expertDisconnectTimers.get(expertId);
    if (timer) {
      clearTimeout(timer);
      expertDisconnectTimers.delete(expertId);
    }
  };

  const sendAcceptedSession = (socket: WebSocket, expertId: string): boolean => {
    const session = store.listActiveSessionsForExpert(expertId)
      .find((candidate) => candidate.primaryExpertId === expertId);
    if (!session) {
      return false;
    }
    const glasses = [...clients.values()].find(
      (peer) => peer.kind === "glasses" && peer.id === session.glassesId,
    );
    send(socket, envelope("call.accepted", session.id, {
      sessionId: session.id,
      expertId,
      role: "primary",
      glassesId: session.glassesId,
      glassesName: glasses?.name ?? session.glassesId,
      resumed: true,
    }));
    return true;
  };

  app.use(express.json({ limit: "2mb" }));
  app.use((request, response, next) => {
    const requestOrigin = request.headers.origin;
    if (requestOrigin && allowedOrigins.has(requestOrigin)) {
      response.setHeader("Access-Control-Allow-Origin", requestOrigin);
      response.setHeader("Vary", "Origin");
    }
    response.setHeader("Access-Control-Allow-Headers", "content-type");
    response.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
    if (request.method === "OPTIONS") {
      response.sendStatus(204);
      return;
    }
    next();
  });

  app.get("/health", (_request, response) => {
    response.json({ ok: true, service: "expert-collab" });
  });

  app.get("/api/config", (_request, response) => {
    response.json(createPublicConfig(config));
  });

  app.post("/api/trtc/credential", (request, response) => {
    // Keep the eye-side contract backward compatible while accepting the SDK
    // contract used by the expert console. Both identities must resolve to the
    // same TRTC user ID; never trust a second conflicting identity.
    const requestedUserId = typeof request.body?.userId === "string" ? request.body.userId.trim() : "";
    const requestedExpertId = typeof request.body?.expertId === "string" ? request.body.expertId.trim() : "";
    if (requestedUserId && requestedExpertId && requestedUserId !== requestedExpertId) {
      response.status(400).json({ error: "userId and expertId must match" });
      return;
    }
    const userId = requestedUserId || requestedExpertId;
    if (!userId || userId.length > 32) {
      response.status(400).json({ error: "userId must contain 1 to 32 characters" });
      return;
    }

    const roomId = typeof request.body?.sessionId === "string" ? request.body.sessionId.trim() : "";
    if (roomId.length > 64) {
      response.status(400).json({ error: "sessionId must contain 1 to 64 characters" });
      return;
    }

    response.json({
      sdkAppId: config.sdkAppId,
      userId,
      expertId: userId,
      expertName: typeof request.body?.expertName === "string" ? request.body.expertName.trim() : "",
      roomId,
      userSig: generateUserSig({
        sdkAppId: config.sdkAppId,
        sdkSecret: config.sdkSecret,
        userId,
        expireSeconds: 900,
      }),
      expiresIn: 900,
    });
  });

  app.post("/api/sessions/:sessionId/freeze", async (request, response) => {
    const imageDataUrl = typeof request.body?.imageDataUrl === "string" ? request.body.imageDataUrl : "";
    const match = /^data:image\/jpeg;base64,([A-Za-z0-9+/]+={0,2})$/.exec(imageDataUrl);
    if (!match) {
      response.status(400).json({ error: "imageDataUrl must be a base64 JPEG" });
      return;
    }

    const image = Buffer.from(match[1], "base64");
    if (image.length === 0 || image.length > 1_500_000 || image[0] !== 0xff || image[1] !== 0xd8) {
      response.status(400).json({ error: "frozen JPEG is invalid or too large" });
      return;
    }

    const file = `${randomUUID()}.jpg`;
    await mkdir(config.freezeDirectory, { recursive: true });
    await writeFile(join(config.freezeDirectory, file), image, { flag: "wx" });
    response.status(201).json({ url: `/api/freezes/${file}` });
  });

  app.get("/api/freezes/:file", async (request, response) => {
    const file = request.params.file;
    if (!/^[a-f0-9-]{36}\.jpg$/.test(file)) {
      response.status(404).end();
      return;
    }
    try {
      const image = await readFile(join(config.freezeDirectory, file));
      response.setHeader("Content-Type", "image/jpeg");
      response.setHeader("Cache-Control", "no-store");
      response.send(image);
    } catch {
      response.status(404).end();
    }
  });

  server.on("upgrade", (request, socket, head) => {
    const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
    if (url.pathname !== config.websocketPath) {
      socket.destroy();
      return;
    }
    websocketServer.handleUpgrade(request, socket, head, (websocket) => {
      websocketServer.emit("connection", websocket, request);
    });
  });

  websocketServer.on("connection", (socket) => {
    socketAlive.set(socket, true);
    socket.on("pong", () => socketAlive.set(socket, true));
    socket.on("message", (data) => {
      try {
        const message = parseMessage(JSON.parse(data.toString()));
        if (message.type === "presence.registered") {
          const payload = message.payload;
          const kind = payload.kind;
          const name = payload.name;
          if ((kind !== "expert" && kind !== "glasses") || typeof name !== "string" || !name.trim()) {
            throw new Error("presence payload is invalid");
          }
          clients.set(socket, { id: message.senderId, kind, name: name.trim() });
          if (kind === "expert") {
            clearExpertDisconnectTimer(message.senderId);
            store.registerExpert(message.senderId, name.trim());
          }
          send(socket, envelope("presence.registered", null, { id: message.senderId, kind, name: name.trim() }));
          if (kind === "expert") {
            if (!sendAcceptedSession(socket, message.senderId)) {
              for (const session of store.listPendingCalls()) {
                const glasses = [...clients.values()].find((peer) => peer.kind === "glasses" && peer.id === session.glassesId);
                send(socket, envelope("call.requested", session.id, {
                  glassesId: session.glassesId,
                  glassesName: glasses?.name ?? session.glassesId,
                }));
              }
            }
          }
          return;
        }

        const client = clients.get(socket);
        if (!client || client.id !== message.senderId) {
          throw new Error("client must register before sending events");
        }

        if (message.type === "call.requested" && client.kind === "glasses") {
          const existing = store.getActiveSessionForGlasses(client.id);
          const session = store.requestCall(client.id);
          if (!existing && session.status === "calling") {
            const call = envelope("call.requested", session.id, { glassesId: client.id, glassesName: client.name });
            const availableIds = new Set(store.listAvailableExperts().map((expert) => expert.id));
            for (const [peerSocket, peer] of clients) {
              if (peer.kind === "expert" && availableIds.has(peer.id)) {
                send(peerSocket, call);
              }
            }
          }
          send(socket, envelope("call.requested", session.id, { accepted: true }));
          if (session.primaryExpertId) {
            send(socket, envelope("call.accepted", session.id, {
              sessionId: session.id,
              expertId: session.primaryExpertId,
              role: "primary",
            }));
          }
          return;
        }

        if (message.type === "call.accepted" && client.kind === "expert" && message.sessionId) {
          const participant = store.acceptCall(message.sessionId, client.id);
          const accepted = envelope("call.accepted", message.sessionId, participant as unknown as Record<string, unknown>);
          const session = store.getSession(message.sessionId);
          for (const [peerSocket, peer] of clients) {
            if (peerSocket === socket || peer.id === session?.glassesId) {
              send(peerSocket, accepted);
            } else if (peer.kind === "expert") {
              send(peerSocket, envelope("call.taken", message.sessionId, {
                expertId: participant.expertId,
              }));
            }
          }
          return;
        }

        if (message.type === "call.ended" && message.sessionId) {
          store.endCall(message.sessionId);
          const ended = envelope("call.ended", message.sessionId, {});
          for (const peerSocket of clients.keys()) {
            send(peerSocket, ended);
          }
          return;
        }

        if (message.type === "observer.invited" && client.kind === "expert" && message.sessionId) {
          const expertId = typeof message.payload.expertId === "string" ? message.payload.expertId : "";
          const invitation = store.inviteObserver(message.sessionId, client.id, expertId);
          const session = store.getSession(message.sessionId);
          const invited = envelope("observer.invited", message.sessionId, {
            ...invitation,
            glassesId: session?.glassesId,
            glassesName: "Air3-现场01",
          });
          for (const [peerSocket, peer] of clients) {
            if (peer.id === expertId || peer.id === client.id) {
              send(peerSocket, invited);
            }
          }
          return;
        }

        if (message.type === "observer.accepted" && client.kind === "expert" && message.sessionId) {
          const participant = store.acceptObserver(message.sessionId, client.id);
          const session = store.getSession(message.sessionId);
          const accepted = envelope("observer.accepted", message.sessionId, {
            ...participant,
            glassesId: session?.glassesId,
          });
          for (const peerSocket of clients.keys()) {
            send(peerSocket, accepted);
          }
          return;
        }

        if (message.type === "observer.left" && client.kind === "expert" && message.sessionId) {
          store.removeObserver(message.sessionId, client.id);
          const left = envelope("observer.left", message.sessionId, { expertId: client.id });
          for (const peerSocket of clients.keys()) {
            send(peerSocket, left);
          }
          return;
        }

        if (message.sessionId) {
          const forwarded = envelope(message.type, message.sessionId, {
            ...message.payload,
            originalSenderId: message.senderId,
          });
          for (const peerSocket of clients.keys()) {
            if (peerSocket !== socket) {
              send(peerSocket, forwarded);
            }
          }
        }
      } catch (error) {
        const reason = error instanceof Error ? error.message : "invalid event";
        send(socket, envelope("error", null, { reason }));
      }
    });

    socket.on("close", () => {
      const client = clients.get(socket);
      clients.delete(socket);
      if (client?.kind === "expert") {
        try {
          const hasAnotherConnection = [...clients.values()].some(
            (peer) => peer.kind === "expert" && peer.id === client.id,
          );
          if (!hasAnotherConnection) {
            store.setExpertOnline(client.id, false);
            clearExpertDisconnectTimer(client.id);
            const timer = setTimeout(() => {
              expertDisconnectTimers.delete(client.id);
              const stillDisconnected = ![...clients.values()].some(
                (peer) => peer.kind === "expert" && peer.id === client.id,
              );
              if (stillDisconnected) {
                const ended = store.endSessionsForExpert(client.id);
                broadcastEnded(ended.map((session) => session.id));
              }
            }, participantDisconnectGraceMs);
            expertDisconnectTimers.set(client.id, timer);
          }
        } catch {
          // A client can disconnect before registration is committed.
        }
      } else if (client?.kind === "glasses") {
        const hasAnotherConnection = [...clients.values()].some(
          (peer) => peer.kind === "glasses" && peer.id === client.id,
        );
        if (!hasAnotherConnection) {
          const ended = store.endPendingCallsForGlasses(client.id);
          broadcastEnded(ended.map((session) => session.id));
        }
      }
    });
  });

  return {
    start(portOverride = config.port): Promise<RunningCollabServer> {
      return new Promise((resolve, reject) => {
        server.once("error", reject);
        server.listen(portOverride, config.host, () => {
          server.off("error", reject);
          const address = server.address();
          if (!address || typeof address === "string") {
            reject(new Error("collaboration server did not expose a TCP address"));
            return;
          }
          const httpUrl = `http://${config.host}:${address.port}`;
          const sweepIntervalMs = options.pendingCallSweepIntervalMs ?? 1_000;
          pendingCallSweep = setInterval(() => {
            const expired = store.expirePendingCalls();
            broadcastEnded(expired.map((session) => session.id));
          }, sweepIntervalMs);
          pendingCallSweep.unref();
          const heartbeatIntervalMs = options.heartbeatIntervalMs ?? 15_000;
          heartbeat = setInterval(() => {
            for (const socket of websocketServer.clients) {
              if (socketAlive.get(socket) === false) {
                socket.terminate();
                continue;
              }
              socketAlive.set(socket, false);
              socket.ping();
            }
          }, heartbeatIntervalMs);
          heartbeat.unref();
          resolve({
            httpUrl,
            websocketUrl: `ws://${config.host}:${address.port}${config.websocketPath}`,
            close: async () => {
              if (pendingCallSweep) {
                clearInterval(pendingCallSweep);
                pendingCallSweep = null;
              }
              if (heartbeat) {
                clearInterval(heartbeat);
                heartbeat = null;
              }
              for (const timer of expertDisconnectTimers.values()) {
                clearTimeout(timer);
              }
              expertDisconnectTimers.clear();
              for (const socket of websocketServer.clients) {
                socket.terminate();
              }
              await new Promise<void>((closeResolve, closeReject) => {
                server.close((error) => error ? closeReject(error) : closeResolve());
              });
            },
          });
        });
      });
    },
  };
}

const entryPath = process.argv[1] ? pathToFileURL(process.argv[1]).href : "";
if (import.meta.url === entryPath) {
  const config = loadConfig();
  const running = await createCollabServer(config).start();
  console.log(`Expert collaboration server listening on ${running.httpUrl}`);
}
