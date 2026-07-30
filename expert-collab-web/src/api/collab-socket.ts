export interface SocketLike {
  send(data: string): void;
  addEventListener?(type: "message", listener: (event: MessageEvent<string>) => void): void;
  removeEventListener?(type: "message", listener: (event: MessageEvent<string>) => void): void;
}

export interface CollabEnvelope<T extends Record<string, unknown> = Record<string, unknown>> {
  type: string;
  sessionId: string | null;
  senderId: string;
  seq: number;
  sentAt: number;
  payload: T;
}

interface CollabSocketOptions {
  senderId: string;
  now?: () => number;
}

export type CollabConnectionStatus = "connecting" | "connected" | "disconnected";

interface ReconnectingCollabSocketOptions extends CollabSocketOptions {
  reconnectDelayMs?: number;
  onStatusChange?: (status: CollabConnectionStatus) => void;
}

type MessageListener = (message: CollabEnvelope) => void;

export class CollabSocket {
  private readonly acceptedSessions = new Set<string>();
  private readonly listeners = new Set<MessageListener>();
  private readonly now: () => number;
  private sequence = 0;

  constructor(
    private readonly socket: SocketLike,
    private readonly options: CollabSocketOptions,
  ) {
    this.now = options.now ?? Date.now;
    this.socket.addEventListener?.("message", this.handleMessage);
  }

  register(name: string): void {
    this.send("presence.registered", null, { kind: "expert", name });
  }

  accept(sessionId: string): boolean {
    if (this.acceptedSessions.has(sessionId)) {
      return false;
    }

    this.acceptedSessions.add(sessionId);
    this.send("call.accepted", sessionId, {});
    return true;
  }

  end(sessionId: string): void {
    this.send("call.ended", sessionId, {});
  }

  inviteObserver(sessionId: string, expertId: string): void {
    this.send("observer.invited", sessionId, { expertId });
  }

  acceptObserver(sessionId: string): boolean {
    if (this.acceptedSessions.has(`observer:${sessionId}`)) {
      return false;
    }
    this.acceptedSessions.add(`observer:${sessionId}`);
    this.send("observer.accepted", sessionId, {});
    return true;
  }

  leaveObserver(sessionId: string): void {
    this.send("observer.left", sessionId, {});
  }

  sendSessionEvent(type: string, sessionId: string, payload: Record<string, unknown>): void {
    this.send(type, sessionId, payload);
  }

  subscribe(listener: MessageListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  close(): void {
    this.socket.removeEventListener?.("message", this.handleMessage);
    this.listeners.clear();
  }

  private readonly handleMessage = (event: MessageEvent<string>): void => {
    const message = JSON.parse(event.data) as CollabEnvelope;
    for (const listener of this.listeners) {
      listener(message);
    }
  };

  private send(type: string, sessionId: string | null, payload: Record<string, unknown>): void {
    const message: CollabEnvelope = {
      type,
      sessionId,
      senderId: this.options.senderId,
      seq: ++this.sequence,
      sentAt: this.now(),
      payload,
    };
    this.socket.send(JSON.stringify(message));
  }
}

export class ReconnectingCollabSocket {
  private readonly listeners = new Set<MessageListener>();
  private readonly reconnectDelayMs: number;
  private socket: WebSocket | null = null;
  private client: CollabSocket | null = null;
  private unsubscribeClient: (() => void) | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private registeredName: string | null = null;
  private stopped = true;

  constructor(
    private readonly url: string,
    private readonly options: ReconnectingCollabSocketOptions,
  ) {
    this.reconnectDelayMs = options.reconnectDelayMs ?? 1_000;
  }

  connect(): void {
    if (!this.stopped || this.socket) {
      return;
    }
    this.stopped = false;
    this.openSocket();
  }

  register(name: string): void {
    this.registeredName = name;
    this.client?.register(name);
  }

  accept(sessionId: string): boolean {
    return this.client?.accept(sessionId) ?? false;
  }

  acceptObserver(sessionId: string): boolean {
    return this.client?.acceptObserver(sessionId) ?? false;
  }

  end(sessionId: string): void {
    this.client?.end(sessionId);
  }

  inviteObserver(sessionId: string, expertId: string): void {
    this.client?.inviteObserver(sessionId, expertId);
  }

  leaveObserver(sessionId: string): void {
    this.client?.leaveObserver(sessionId);
  }

  sendSessionEvent(type: string, sessionId: string, payload: Record<string, unknown>): void {
    this.client?.sendSessionEvent(type, sessionId, payload);
  }

  subscribe(listener: MessageListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  close(): void {
    this.stopped = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    const socket = this.socket;
    this.releaseSocket(socket);
    socket?.close();
    this.listeners.clear();
  }

  private openSocket(): void {
    this.options.onStatusChange?.("connecting");
    const socket = new WebSocket(this.url);
    this.socket = socket;

    const handleOpen = (): void => {
      if (this.socket !== socket || this.stopped) {
        return;
      }
      const client = new CollabSocket(socket, { senderId: this.options.senderId, now: this.options.now });
      this.client = client;
      this.unsubscribeClient = client.subscribe((message) => {
        for (const listener of this.listeners) {
          listener(message);
        }
      });
      if (this.registeredName) {
        client.register(this.registeredName);
      }
      this.options.onStatusChange?.("connected");
    };
    const handleClose = (): void => this.handleDisconnect(socket);
    const handleError = (): void => {
      socket.close();
      this.handleDisconnect(socket);
    };
    socket.addEventListener("open", handleOpen);
    socket.addEventListener("close", handleClose);
    socket.addEventListener("error", handleError);
    this.detachSocketListeners = () => {
      socket.removeEventListener("open", handleOpen);
      socket.removeEventListener("close", handleClose);
      socket.removeEventListener("error", handleError);
    };
  }

  private detachSocketListeners: (() => void) | null = null;

  private handleDisconnect(socket: WebSocket): void {
    if (this.socket !== socket) {
      return;
    }
    this.releaseSocket(socket);
    this.options.onStatusChange?.("disconnected");
    if (!this.stopped && !this.reconnectTimer) {
      this.reconnectTimer = setTimeout(() => {
        this.reconnectTimer = null;
        this.openSocket();
      }, this.reconnectDelayMs);
    }
  }

  private releaseSocket(socket: WebSocket | null): void {
    if (socket && this.socket !== socket) {
      return;
    }
    this.detachSocketListeners?.();
    this.detachSocketListeners = null;
    this.unsubscribeClient?.();
    this.unsubscribeClient = null;
    this.client?.close();
    this.client = null;
    this.socket = null;
  }
}
