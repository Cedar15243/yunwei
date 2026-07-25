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

export class SessionStore {
  private readonly experts = new Map<string, ExpertPresence>();
  private readonly sessions = new Map<string, CollabSession>();

  constructor(private readonly idFactory: () => string = defaultIdFactory) {}

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
    const session: CollabSession = {
      id: this.idFactory(),
      glassesId,
      status: "calling",
      primaryExpertId: null,
      observerIds: new Set(),
      invitedObserverIds: new Set(),
    };
    this.sessions.set(session.id, session);
    return session;
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
    expert.busySessionId = sessionId;
    return { sessionId, expertId, role: "primary" };
  }

  endCall(sessionId: string): void {
    const session = this.requireSession(sessionId);
    session.status = "ended";
    if (session.primaryExpertId) {
      const primary = this.experts.get(session.primaryExpertId);
      if (primary?.busySessionId === sessionId) {
        primary.busySessionId = null;
      }
    }
    for (const observerId of session.observerIds) {
      const observer = this.experts.get(observerId);
      if (observer?.busySessionId === sessionId) {
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
    expert.busySessionId = sessionId;
    return { sessionId, expertId, role: "observer" };
  }

  removeObserver(sessionId: string, expertId: string): void {
    const session = this.requireSession(sessionId);
    session.observerIds.delete(expertId);
    session.invitedObserverIds.delete(expertId);
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
