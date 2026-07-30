import type { ParticipantRole, SessionStatus } from "./protocol.js";

export interface ExpertPresence {
  id: string;
  name: string;
  online: boolean;
  busySessionId: string | null;
}

export interface CollabSession {
  id: string;
  glassesId: string;
  status: SessionStatus;
  createdAt: number;
  updatedAt: number;
  primaryExpertId: string | null;
  observerIds: Set<string>;
  invitedObserverIds: Set<string>;
}

export interface AcceptedParticipant {
  sessionId: string;
  expertId: string;
  role: ParticipantRole;
}

export interface ObserverInvitation {
  sessionId: string;
  inviterId: string;
  expertId: string;
}

const defaultIdFactory = (): string => `session-${crypto.randomUUID()}`;
export const DEFAULT_PENDING_CALL_TIMEOUT_MS = 90_000;

export class SessionStore {
  private readonly experts = new Map<string, ExpertPresence>();
  private readonly sessions = new Map<string, CollabSession>();

  constructor(
    private readonly idFactory: () => string = defaultIdFactory,
    private readonly now: () => number = Date.now,
    private readonly pendingCallTimeoutMs = DEFAULT_PENDING_CALL_TIMEOUT_MS,
  ) {}

  registerExpert(id: string, name: string): ExpertPresence {
    const existing = this.experts.get(id);
    const expert: ExpertPresence = existing
      ? { ...existing, name, online: true }
      : { id, name, online: true, busySessionId: null };
    this.experts.set(id, expert);
    return expert;
  }

  setExpertOnline(id: string, online: boolean): void {
    const expert = this.experts.get(id);
    if (!expert) {
      throw new Error(`unknown expert: ${id}`);
    }
    expert.online = online;
  }

  listAvailableExperts(): ExpertPresence[] {
    return [...this.experts.values()].filter((expert) => expert.online && expert.busySessionId === null);
  }

  listPendingCalls(): CollabSession[] {
    return [...this.sessions.values()].filter((session) => session.status === "calling");
  }

  requestCall(glassesId: string): CollabSession {
    const now = this.now();
    const active = this.getActiveSessionForGlasses(glassesId);
    if (active) {
      return active;
    }

    const session: CollabSession = {
      id: this.idFactory(),
      glassesId,
      status: "calling",
      createdAt: now,
      updatedAt: now,
      primaryExpertId: null,
      observerIds: new Set(),
      invitedObserverIds: new Set(),
    };
    this.sessions.set(session.id, session);
    return session;
  }

  getActiveSessionForGlasses(glassesId: string): CollabSession | undefined {
    return [...this.sessions.values()].find(
      (session) => session.glassesId === glassesId && session.status !== "ended",
    );
  }

  listActiveSessionsForExpert(expertId: string): CollabSession[] {
    return [...this.sessions.values()].filter((session) =>
      session.status !== "ended"
      && (session.primaryExpertId === expertId || session.observerIds.has(expertId)),
    );
  }

  endSessionsForExpert(expertId: string): CollabSession[] {
    const ended = this.listActiveSessionsForExpert(expertId);
    const now = this.now();
    for (const session of ended) {
      this.finishSession(session, now);
    }
    return ended;
  }

  acceptCall(sessionId: string, expertId: string): AcceptedParticipant {
    const session = this.requireSession(sessionId);
    if (session.primaryExpertId !== null || session.status !== "calling") {
      throw new Error("call already accepted");
    }

    const expert = this.experts.get(expertId) ?? this.registerExpert(expertId, expertId);
    if (!expert.online || expert.busySessionId !== null) {
      throw new Error("expert is unavailable");
    }

    session.primaryExpertId = expertId;
    session.status = "connecting";
    session.updatedAt = this.now();
    expert.busySessionId = sessionId;
    return { sessionId, expertId, role: "primary" };
  }

  endCall(sessionId: string): void {
    const session = this.requireSession(sessionId);
    this.finishSession(session, this.now());
  }

  endPendingCallsForGlasses(glassesId: string): CollabSession[] {
    const ended: CollabSession[] = [];
    const now = this.now();
    for (const session of this.sessions.values()) {
      if (session.glassesId === glassesId && session.status === "calling") {
        this.finishSession(session, now);
        ended.push(session);
      }
    }
    return ended;
  }

  expirePendingCalls(): CollabSession[] {
    const expired: CollabSession[] = [];
    const now = this.now();
    for (const session of this.sessions.values()) {
      if (session.status === "calling" && now - session.createdAt >= this.pendingCallTimeoutMs) {
        this.finishSession(session, now);
        expired.push(session);
      }
    }
    return expired;
  }

  private finishSession(session: CollabSession, now: number): void {
    session.status = "ended";
    session.updatedAt = now;
    if (session.primaryExpertId) {
      const primary = this.experts.get(session.primaryExpertId);
      if (primary?.busySessionId === session.id) {
        primary.busySessionId = null;
      }
    }
    for (const observerId of session.observerIds) {
      const observer = this.experts.get(observerId);
      if (observer?.busySessionId === session.id) {
        observer.busySessionId = null;
      }
    }
    session.observerIds.clear();
    session.invitedObserverIds.clear();
  }

  inviteObserver(sessionId: string, inviterId: string, expertId: string): ObserverInvitation {
    const session = this.requireSession(sessionId);
    if (session.primaryExpertId !== inviterId) {
      throw new Error("only the primary expert can invite observers");
    }
    if (session.status === "ended") {
      throw new Error("call has ended");
    }
    const expert = this.experts.get(expertId);
    if (!expert || !expert.online || expert.busySessionId !== null || expertId === inviterId) {
      throw new Error("observer expert is unavailable");
    }
    session.invitedObserverIds.add(expertId);
    session.updatedAt = this.now();
    return { sessionId, inviterId, expertId };
  }

  acceptObserver(sessionId: string, expertId: string): AcceptedParticipant {
    const session = this.requireSession(sessionId);
    if (!session.invitedObserverIds.has(expertId)) {
      throw new Error("observer was not invited");
    }
    const expert = this.experts.get(expertId);
    if (!expert || !expert.online || expert.busySessionId !== null) {
      throw new Error("observer expert is unavailable");
    }
    session.invitedObserverIds.delete(expertId);
    session.observerIds.add(expertId);
    session.updatedAt = this.now();
    expert.busySessionId = sessionId;
    return { sessionId, expertId, role: "observer" };
  }

  removeObserver(sessionId: string, expertId: string): void {
    const session = this.requireSession(sessionId);
    session.observerIds.delete(expertId);
    session.invitedObserverIds.delete(expertId);
    session.updatedAt = this.now();
    const expert = this.experts.get(expertId);
    if (expert?.busySessionId === sessionId) {
      expert.busySessionId = null;
    }
  }

  getSession(sessionId: string): CollabSession | undefined {
    return this.sessions.get(sessionId);
  }

  private requireSession(sessionId: string): CollabSession {
    const session = this.sessions.get(sessionId);
    if (!session) {
      throw new Error(`unknown session: ${sessionId}`);
    }
    return session;
  }
}
