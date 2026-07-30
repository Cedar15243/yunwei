import { describe, expect, it } from "vitest";
import { SessionStore } from "../src/session-store.js";

describe("SessionStore", () => {
  it("reuses the same pending call when one pair of glasses retries", () => {
    let now = 1_000;
    let sequence = 0;
    const store = new SessionStore(() => `session-${++sequence}`, () => now, 90_000);

    const first = store.requestCall("glasses-01");
    now = 2_000;
    const second = store.requestCall("glasses-01");

    expect(second).toBe(first);
    expect(first).toMatchObject({ createdAt: 1_000, updatedAt: 1_000, status: "calling" });
    expect(store.listPendingCalls().map((session) => session.id)).toEqual(["session-1"]);
  });

  it("expires an unanswered call after 90 seconds", () => {
    let now = 10_000;
    const store = new SessionStore(() => "session-01", () => now, 90_000);
    const session = store.requestCall("glasses-01");

    now = 99_999;
    expect(store.expirePendingCalls()).toEqual([]);

    now = 100_000;
    expect(store.expirePendingCalls().map((expired) => expired.id)).toEqual([session.id]);
    expect(store.getSession(session.id)).toMatchObject({ status: "ended", updatedAt: 100_000 });
    expect(store.expirePendingCalls()).toEqual([]);
  });

  it("keeps the first expert as primary", () => {
    const store = new SessionStore(() => "session-01");
    const session = store.requestCall("glasses-01");

    expect(store.acceptCall(session.id, "expert-wang").role).toBe("primary");
    expect(() => store.acceptCall(session.id, "expert-liu")).toThrow("already accepted");
    expect(store.getSession(session.id)?.primaryExpertId).toBe("expert-wang");
  });

  it("tracks expert presence without exposing offline experts as available", () => {
    const store = new SessionStore(() => "session-01");

    store.registerExpert("expert-wang", "王工");
    store.registerExpert("expert-liu", "刘工");
    store.setExpertOnline("expert-liu", false);

    expect(store.listAvailableExperts().map((expert) => expert.id)).toEqual(["expert-wang"]);
  });

  it("ends a call and clears the busy primary expert", () => {
    const store = new SessionStore(() => "session-01");
    store.registerExpert("expert-wang", "王工");
    const session = store.requestCall("glasses-01");
    store.acceptCall(session.id, "expert-wang");

    store.endCall(session.id);

    expect(store.getSession(session.id)?.status).toBe("ended");
    expect(store.listAvailableExperts().map((expert) => expert.id)).toEqual(["expert-wang"]);
  });

  it("reuses an accepted session when the glasses reconnect during acceptance", () => {
    let sequence = 0;
    const store = new SessionStore(() => `session-${++sequence}`);
    store.registerExpert("expert-wang", "Wang");
    const first = store.requestCall("glasses-01");
    store.acceptCall(first.id, "expert-wang");

    const replayed = store.requestCall("glasses-01");

    expect(replayed).toBe(first);
    expect(replayed).toMatchObject({ status: "connecting", primaryExpertId: "expert-wang" });
  });

  it("ends active sessions owned by a disconnected expert", () => {
    const store = new SessionStore(() => "session-01");
    store.registerExpert("expert-wang", "Wang");
    const session = store.requestCall("glasses-01");
    store.acceptCall(session.id, "expert-wang");

    expect(store.listActiveSessionsForExpert("expert-wang").map((item) => item.id))
      .toEqual([session.id]);
    expect(store.endSessionsForExpert("expert-wang").map((item) => item.id))
      .toEqual([session.id]);
    expect(store.getSession(session.id)?.status).toBe("ended");
    expect(store.listAvailableExperts().map((expert) => expert.id)).toEqual(["expert-wang"]);
  });

  it("allows only the primary expert to invite an observer", () => {
    const store = new SessionStore(() => "session-01");
    store.registerExpert("expert-wang", "王工");
    store.registerExpert("expert-liu", "刘工");
    const session = store.requestCall("glasses-01");
    store.acceptCall(session.id, "expert-wang");

    expect(store.inviteObserver(session.id, "expert-wang", "expert-liu").expertId).toBe("expert-liu");
    expect(() => store.inviteObserver(session.id, "expert-liu", "expert-wang")).toThrow("primary expert");
  });

  it("adds and removes an observer without ending the call", () => {
    const store = new SessionStore(() => "session-01");
    store.registerExpert("expert-wang", "王工");
    store.registerExpert("expert-liu", "刘工");
    const session = store.requestCall("glasses-01");
    store.acceptCall(session.id, "expert-wang");
    store.inviteObserver(session.id, "expert-wang", "expert-liu");

    expect(store.acceptObserver(session.id, "expert-liu").role).toBe("observer");
    store.removeObserver(session.id, "expert-liu");

    expect(store.getSession(session.id)?.status).toBe("connecting");
    expect(store.listAvailableExperts().map((expert) => expert.id)).toContain("expert-liu");
  });
});
