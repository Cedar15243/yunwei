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
