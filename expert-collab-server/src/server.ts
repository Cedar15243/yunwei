import http from "node:http";
import { pathToFileURL } from "node:url";
import express from "express";
import { WebSocket, WebSocketServer } from "ws";
import { createPublicConfig, loadConfig, type ServerConfig } from "./config.js";
import { parseMessage, type Envelope } from "./protocol.js";
import { SessionStore } from "./session-store.js";
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function send(socket: WebSocket, message: Envelope): void {
  if (socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(message));
  }
}

export function createCollabServer(config: ServerConfig): CollabServer {
  const app = express();
  const server = http.createServer(app);
  const websocketServer = new WebSocketServer({ noServer: true });
  const store = new SessionStore();
  const clients = new Map<WebSocket, RegisteredClient>();
  let serverSequence = 0;

  const envelope = (type: string, sessionId: string | null, payload: Record<string, unknown>): Envelope => ({
    type,
    sessionId,
    senderId: "server",
    seq: ++serverSequence,
    sentAt: Date.now(),
    payload,
  });

  app.use(express.json({ limit: "2mb" }));
  app.use((request, response, next) => {
    response.setHeader("Access-Control-Allow-Origin", config.allowedOrigin);
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
    const userId = typeof request.body?.userId === "string" ? request.body.userId.trim() : "";
    if (!userId || userId.length > 32) {
      response.status(400).json({ error: "userId must contain 1 to 32 characters" });
      return;
    }

    response.json({
      sdkAppId: config.sdkAppId,
      userId,
      userSig: generateUserSig({
        sdkAppId: config.sdkAppId,
        sdkSecret: config.sdkSecret,
        userId,
        expireSeconds: 900,
      }),
      expiresIn: 900,
    });
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
            store.registerExpert(message.senderId, name.trim());
          }
          send(socket, envelope("presence.registered", null, { id: message.senderId, kind, name: name.trim() }));
          return;
        }

        const client = clients.get(socket);
        if (!client || client.id !== message.senderId) {
          throw new Error("client must register before sending events");
        }

        if (message.type === "call.requested" && client.kind === "glasses") {
          const session = store.requestCall(client.id);
          const call = envelope("call.requested", session.id, { glassesId: client.id, glassesName: client.name });
          const availableIds = new Set(store.listAvailableExperts().map((expert) => expert.id));
          for (const [peerSocket, peer] of clients) {
            if (peer.kind === "expert" && availableIds.has(peer.id)) {
              send(peerSocket, call);
            }
          }
          send(socket, envelope("call.requested", session.id, { accepted: true }));
          return;
        }

        if (message.type === "call.accepted" && client.kind === "expert" && message.sessionId) {
          const participant = store.acceptCall(message.sessionId, client.id);
          const accepted = envelope("call.accepted", message.sessionId, participant as unknown as Record<string, unknown>);
          for (const [peerSocket, peer] of clients) {
            const session = store.getSession(message.sessionId);
            if (peer.id === client.id || peer.id === session?.glassesId) {
              send(peerSocket, accepted);
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
          store.setExpertOnline(client.id, false);
        } catch {
          // A client can disconnect before registration is committed.
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
          resolve({
            httpUrl,
            websocketUrl: `ws://${config.host}:${address.port}${config.websocketPath}`,
            close: async () => {
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
