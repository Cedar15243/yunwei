import { describe, expect, it } from "vitest";
import { SessionStore } from "../src/session-store.js";

describe("SessionStore", () => {
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
});
