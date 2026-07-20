import type { CollabEnvelope } from "./api/collab-socket";

export type CollaborationStatus =
  | "available"
  | "ringing"
  | "invited"
  | "connecting"
  | "in_call"
  | "taken"
  | "ended"
  | "failed";

export interface CollaborationSnapshot {
  status: CollaborationStatus;
  sessionId: string | null;
  glassesId: string | null;
  glassesName: string | null;
  primaryExpertId: string | null;
  error: string | null;
  role: "primary" | "observer" | null;
}

export interface CollabSignaling {
  register(name: string): void;
  accept(sessionId: string): boolean;
  acceptObserver(sessionId: string): boolean;
  end(sessionId: string): void;
  inviteObserver(sessionId: string, expertId: string): void;
  leaveObserver(sessionId: string): void;
  subscribe(listener: (message: CollabEnvelope) => void): () => void;
}

export interface TrtcSession {
  join(options: {
    sessionId: string;
    userId: string;
    glassesUserId: string;
    videoView: HTMLElement;
    localVideoView: HTMLElement;
    publishVideo: boolean;
  }): Promise<void>;
  leave(): Promise<void>;
}

interface CollaborationControllerOptions {
  signaling: CollabSignaling;
  trtc: TrtcSession;
  expertId: string;
  expertName: string;
  videoView: HTMLElement;
  localVideoView: HTMLElement;
}

type SnapshotListener = (snapshot: CollaborationSnapshot) => void;

const initialSnapshot: CollaborationSnapshot = {
  status: "available",
  sessionId: null,
  glassesId: null,
  glassesName: null,
  primaryExpertId: null,
  error: null,
  role: null,
};

export class CollaborationController {
  private snapshot = initialSnapshot;
  private readonly listeners = new Set<SnapshotListener>();
  private unsubscribeSignal: (() => void) | null = null;

  constructor(private readonly options: CollaborationControllerOptions) {}

  start(): void {
    if (this.unsubscribeSignal) {
      return;
    }
    this.unsubscribeSignal = this.options.signaling.subscribe(this.handleMessage);
    this.options.signaling.register(this.options.expertName);
  }

  getSnapshot(): CollaborationSnapshot {
    return this.snapshot;
  }

  subscribe(listener: SnapshotListener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  accept(): boolean {
    const sessionId = this.snapshot.sessionId;
    if ((this.snapshot.status !== "ringing" && this.snapshot.status !== "invited") || !sessionId) {
      return false;
    }
    const sent = this.snapshot.status === "invited"
      ? this.options.signaling.acceptObserver(sessionId)
      : this.options.signaling.accept(sessionId);
    if (!sent) {
      return false;
    }
    this.update({ status: "connecting" });
    return true;
  }

  inviteObserver(expertId: string): boolean {
    if (this.snapshot.status !== "in_call" || this.snapshot.role !== "primary" || !this.snapshot.sessionId) {
      return false;
    }
    this.options.signaling.inviteObserver(this.snapshot.sessionId, expertId);
    return true;
  }

  retry(): boolean {
    const { sessionId, primaryExpertId, role, status } = this.snapshot;
    if (status !== "failed" || !sessionId || !primaryExpertId || !role) {
      return false;
    }
    void this.joinCall(primaryExpertId, role);
    return true;
  }

  async end(): Promise<void> {
    if (this.snapshot.sessionId) {
      if (this.snapshot.role === "observer") {
        this.options.signaling.leaveObserver(this.snapshot.sessionId);
      } else {
        this.options.signaling.end(this.snapshot.sessionId);
      }
    }
    await this.options.trtc.leave();
    this.update({ status: "ended" });
  }

  async stop(): Promise<void> {
    this.unsubscribeSignal?.();
    this.unsubscribeSignal = null;
    this.listeners.clear();
    await this.options.trtc.leave();
  }

  private readonly handleMessage = (message: CollabEnvelope): void => {
    if (message.type === "call.requested" && message.sessionId) {
      this.snapshot = {
        status: "ringing",
        sessionId: message.sessionId,
        glassesId: typeof message.payload.glassesId === "string" ? message.payload.glassesId : null,
        glassesName: typeof message.payload.glassesName === "string" ? message.payload.glassesName : "Air3现场",
        primaryExpertId: null,
        error: null,
        role: null,
      };
      this.notify();
      return;
    }

    if (message.type === "call.accepted" && message.sessionId === this.snapshot.sessionId) {
      const primaryExpertId = typeof message.payload.expertId === "string" ? message.payload.expertId : null;
      if (primaryExpertId !== this.options.expertId) {
        this.update({ status: "taken", primaryExpertId });
        return;
      }
      void this.joinCall(primaryExpertId, "primary");
      return;
    }

    if (message.type === "observer.invited" && message.sessionId && message.payload.expertId === this.options.expertId) {
      this.snapshot = {
        status: "invited",
        sessionId: message.sessionId,
        glassesId: typeof message.payload.glassesId === "string" ? message.payload.glassesId : null,
        glassesName: typeof message.payload.glassesName === "string" ? message.payload.glassesName : "Air3-现场01",
        primaryExpertId: typeof message.payload.inviterId === "string" ? message.payload.inviterId : null,
        error: null,
        role: null,
      };
      this.notify();
      return;
    }

    if (message.type === "observer.accepted" && message.sessionId === this.snapshot.sessionId
      && message.payload.expertId === this.options.expertId) {
      if (!this.snapshot.glassesId && typeof message.payload.glassesId === "string") {
        this.update({ glassesId: message.payload.glassesId });
      }
      void this.joinCall(this.snapshot.primaryExpertId ?? "primary", "observer");
      return;
    }

    if (message.type === "call.ended" && message.sessionId === this.snapshot.sessionId) {
      void this.options.trtc.leave();
      this.update({ status: "ended" });
      return;
    }

    if (message.type === "error") {
      const reason = typeof message.payload.reason === "string" ? message.payload.reason : "协同服务发生错误";
      this.update({ status: "failed", error: reason });
    }
  };

  private async joinCall(primaryExpertId: string, role: "primary" | "observer"): Promise<void> {
    const { sessionId, glassesId } = this.snapshot;
    if (!sessionId || !glassesId) {
      this.update({ status: "failed", error: "来电缺少眼镜设备信息" });
      return;
    }

    this.update({ status: "connecting", primaryExpertId, role });
    try {
      await this.options.trtc.join({
        sessionId,
        userId: this.options.expertId,
        glassesUserId: glassesId,
        videoView: this.options.videoView,
        localVideoView: this.options.localVideoView,
        publishVideo: role === "primary",
      });
      this.update({ status: "in_call" });
    } catch (error) {
      await this.options.trtc.leave();
      if (role === "observer") {
        this.options.signaling.leaveObserver(sessionId);
      }
      this.update({
        status: "failed",
        error: error instanceof Error ? error.message : "加入TRTC房间失败",
      });
    }
  }

  private update(patch: Partial<CollaborationSnapshot>): void {
    this.snapshot = { ...this.snapshot, ...patch };
    this.notify();
  }

  private notify(): void {
    for (const listener of this.listeners) {
      listener(this.snapshot);
    }
  }
}
