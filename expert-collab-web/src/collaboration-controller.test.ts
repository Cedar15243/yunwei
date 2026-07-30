import { describe, expect, it, vi } from "vitest";
import type { CollabEnvelope } from "./api/collab-socket";
import { CollaborationController, type CollabSignaling, type TrtcSession } from "./collaboration-controller";

function setup() {
  let listener: ((message: CollabEnvelope) => void) | null = null;
  const signaling: CollabSignaling = {
    register: vi.fn(),
    accept: vi.fn(() => true),
    acceptObserver: vi.fn(() => true),
    end: vi.fn(),
    inviteObserver: vi.fn(),
    leaveObserver: vi.fn(),
    subscribe(next) {
      listener = next;
      return () => {
        listener = null;
      };
    },
  };
  const trtc: TrtcSession = {
    join: vi.fn(async () => undefined),
    leave: vi.fn(async () => undefined),
  };
  const controller = new CollaborationController({
    signaling,
    trtc,
    expertId: "expert-wang",
    expertName: "王工",
    videoView: document.createElement("div"),
    localVideoView: document.createElement("div"),
  });
  controller.start();

  return {
    controller,
    signaling,
    trtc,
    emit(message: CollabEnvelope) {
      if (!listener) {
        throw new Error("controller is not subscribed");
      }
      listener(message);
    },
  };
}

const incomingCall: CollabEnvelope = {
  type: "call.requested",
  sessionId: "session-1",
  senderId: "server",
  seq: 1,
  sentAt: 1,
  payload: { glassesId: "glasses-01", glassesName: "Air3-现场01" },
};

describe("CollaborationController", () => {
  it("rings when a glasses call arrives", () => {
    const { controller, emit } = setup();

    emit(incomingCall);

    expect(controller.getSnapshot()).toMatchObject({
      status: "ringing",
      glassesName: "Air3-现场01",
      sessionId: "session-1",
    });
  });

  it("does not revive an ended call when the server replays it", () => {
    const { controller, emit } = setup();
    emit(incomingCall);
    emit({
      type: "call.ended",
      sessionId: "session-1",
      senderId: "server",
      seq: 2,
      sentAt: 2,
      payload: {},
    });

    expect(controller.getSnapshot().status).toBe("ended");
    emit({ ...incomingCall, seq: 3, sentAt: 3 });

    expect(controller.getSnapshot().status).toBe("ended");
  });

  it("ignores a late acceptance after the call ended", () => {
    const { controller, emit, trtc } = setup();
    emit(incomingCall);
    emit({
      type: "call.ended",
      sessionId: "session-1",
      senderId: "server",
      seq: 2,
      sentAt: 2,
      payload: {},
    });

    emit({
      type: "call.accepted",
      sessionId: "session-1",
      senderId: "server",
      seq: 3,
      sentAt: 3,
      payload: { expertId: "expert-wang", role: "primary" },
    });

    expect(controller.getSnapshot().status).toBe("ended");
    expect(trtc.join).not.toHaveBeenCalled();
  });

  it("does not replace a connecting call with an unrelated incoming call", () => {
    const { controller, emit } = setup();
    emit(incomingCall);
    controller.accept();

    emit({
      ...incomingCall,
      sessionId: "session-2",
      seq: 2,
      sentAt: 2,
      payload: { glassesId: "glasses-02", glassesName: "Air3-02" },
    });

    expect(controller.getSnapshot()).toMatchObject({
      status: "connecting",
      sessionId: "session-1",
      glassesId: "glasses-01",
    });
  });

  it("does not replace an active call with an unrelated incoming call", async () => {
    const { controller, emit } = setup();
    emit(incomingCall);
    controller.accept();
    emit({
      type: "call.accepted",
      sessionId: "session-1",
      senderId: "server",
      seq: 2,
      sentAt: 2,
      payload: { expertId: "expert-wang", role: "primary" },
    });
    await vi.waitFor(() => expect(controller.getSnapshot().status).toBe("in_call"));

    emit({
      ...incomingCall,
      sessionId: "session-2",
      seq: 3,
      sentAt: 3,
      payload: { glassesId: "glasses-02", glassesName: "Air3-02" },
    });

    expect(controller.getSnapshot()).toMatchObject({
      status: "in_call",
      sessionId: "session-1",
      glassesId: "glasses-01",
    });
  });

  it("stays ended when TRTC finishes joining after the call ended", async () => {
    const { controller, emit, trtc } = setup();
    let finishJoin = (): void => undefined;
    vi.mocked(trtc.join).mockImplementationOnce(() => new Promise<void>((resolve) => {
      finishJoin = resolve;
    }));
    emit(incomingCall);
    controller.accept();
    emit({
      type: "call.accepted",
      sessionId: "session-1",
      senderId: "server",
      seq: 2,
      sentAt: 2,
      payload: { expertId: "expert-wang", role: "primary" },
    });
    expect(controller.getSnapshot().status).toBe("connecting");

    emit({
      type: "call.ended",
      sessionId: "session-1",
      senderId: "server",
      seq: 3,
      sentAt: 3,
      payload: {},
    });
    finishJoin();

    await vi.waitFor(() => expect(trtc.leave).toHaveBeenCalled());
    expect(controller.getSnapshot().status).toBe("ended");
  });

  it("joins TRTC only after this expert wins the call", async () => {
    const { controller, emit, signaling, trtc } = setup();
    emit(incomingCall);

    controller.accept();
    emit({
      type: "call.accepted",
      sessionId: "session-1",
      senderId: "server",
      seq: 2,
      sentAt: 2,
      payload: { expertId: "expert-wang", role: "primary" },
    });
    await vi.waitFor(() => expect(controller.getSnapshot().status).toBe("in_call"));

    expect(signaling.accept).toHaveBeenCalledWith("session-1");
    expect(trtc.join).toHaveBeenCalledWith(expect.objectContaining({
      sessionId: "session-1",
      userId: "expert-wang",
      glassesUserId: "glasses-01",
      publishVideo: true,
    }));
  });

  it("restores an accepted call after the expert console reconnects", async () => {
    const { controller, emit, trtc } = setup();

    emit({
      type: "call.accepted",
      sessionId: "session-1",
      senderId: "server",
      seq: 1,
      sentAt: 1,
      payload: {
        expertId: "expert-wang",
        role: "primary",
        glassesId: "glasses-01",
        glassesName: "Air3-01",
      },
    });

    await vi.waitFor(() => expect(controller.getSnapshot().status).toBe("in_call"));
    expect(controller.getSnapshot()).toMatchObject({
      sessionId: "session-1",
      glassesId: "glasses-01",
      primaryExpertId: "expert-wang",
    });
    expect(trtc.join).toHaveBeenCalledOnce();
  });

  it("does not join TRTC twice when an accepted call is replayed", async () => {
    const { controller, emit, trtc } = setup();
    emit(incomingCall);
    controller.accept();
    const accepted: CollabEnvelope = {
      type: "call.accepted",
      sessionId: "session-1",
      senderId: "server",
      seq: 2,
      sentAt: 2,
      payload: { expertId: "expert-wang", role: "primary", glassesId: "glasses-01" },
    };
    emit(accepted);
    await vi.waitFor(() => expect(controller.getSnapshot().status).toBe("in_call"));

    emit({ ...accepted, seq: 3, sentAt: 3 });

    expect(trtc.join).toHaveBeenCalledOnce();
  });

  it("keeps an owned primary call available for reconnect when the console stops", async () => {
    const { controller, emit, signaling, trtc } = setup();
    emit(incomingCall);
    controller.accept();
    emit({
      type: "call.accepted",
      sessionId: "session-1",
      senderId: "server",
      seq: 2,
      sentAt: 2,
      payload: { expertId: "expert-wang", role: "primary" },
    });
    await vi.waitFor(() => expect(controller.getSnapshot().status).toBe("in_call"));

    await controller.stop();

    expect(signaling.end).not.toHaveBeenCalled();
    expect(trtc.leave).toHaveBeenCalledOnce();
  });

  it("keeps the session alive when the primary expert cannot join TRTC", async () => {
    const { controller, emit, signaling, trtc } = setup();
    vi.mocked(trtc.join).mockRejectedValueOnce(new Error("TRTC join failed"));
    emit(incomingCall);

    controller.accept();
    emit({
      type: "call.accepted",
      sessionId: "session-1",
      senderId: "server",
      seq: 2,
      sentAt: 2,
      payload: { expertId: "expert-wang", role: "primary" },
    });
    await vi.waitFor(() => expect(controller.getSnapshot().status).toBe("failed"));

    expect(signaling.end).not.toHaveBeenCalled();
    expect(signaling.leaveObserver).not.toHaveBeenCalled();
    expect(trtc.leave).toHaveBeenCalledOnce();
  });

  it("retries the retained primary session after a TRTC join failure", async () => {
    const { controller, emit, trtc } = setup();
    vi.mocked(trtc.join).mockRejectedValueOnce(new Error("TRTC join failed"));
    emit(incomingCall);
    controller.accept();
    emit({
      type: "call.accepted", sessionId: "session-1", senderId: "server", seq: 2, sentAt: 2,
      payload: { expertId: "expert-wang", role: "primary" },
    });
    await vi.waitFor(() => expect(controller.getSnapshot().status).toBe("failed"));
    vi.mocked(trtc.join).mockResolvedValueOnce();

    expect(controller.retry()).toBe(true);
    await vi.waitFor(() => expect(controller.getSnapshot().status).toBe("in_call"));
    expect(trtc.join).toHaveBeenCalledTimes(2);
  });

  it("marks the call taken when another expert wins", () => {
    const { controller, emit, trtc } = setup();
    emit(incomingCall);

    emit({
      type: "call.accepted",
      sessionId: "session-1",
      senderId: "server",
      seq: 2,
      sentAt: 2,
      payload: { expertId: "expert-liu", role: "primary" },
    });

    expect(controller.getSnapshot().status).toBe("taken");
    expect(trtc.join).not.toHaveBeenCalled();
  });

  it("joins an invited expert as an observer", async () => {
    const { controller, emit, signaling, trtc } = setup();
    emit({
      type: "observer.invited",
      sessionId: "session-1",
      senderId: "server",
      seq: 1,
      sentAt: 1,
      payload: { expertId: "expert-wang", inviterId: "expert-liu", glassesId: "glasses-01" },
    });

    expect(controller.getSnapshot().status).toBe("invited");
    controller.accept();
    emit({
      type: "observer.accepted",
      sessionId: "session-1",
      senderId: "server",
      seq: 2,
      sentAt: 2,
      payload: { expertId: "expert-wang", role: "observer", glassesId: "glasses-01" },
    });
    await vi.waitFor(() => expect(controller.getSnapshot().status).toBe("in_call"));

    expect(trtc.join).toHaveBeenCalledWith(expect.objectContaining({ publishVideo: false }));

    expect(signaling.acceptObserver).toHaveBeenCalledWith("session-1");
    expect(controller.getSnapshot().role).toBe("observer");
    expect(trtc.join).toHaveBeenCalled();
  });

  it("lets an observer leave without ending the primary call", async () => {
    const { controller, emit, signaling } = setup();
    emit({
      type: "observer.invited", sessionId: "session-1", senderId: "server", seq: 1, sentAt: 1,
      payload: { expertId: "expert-wang", inviterId: "expert-liu", glassesId: "glasses-01" },
    });
    controller.accept();
    emit({
      type: "observer.accepted", sessionId: "session-1", senderId: "server", seq: 2, sentAt: 2,
      payload: { expertId: "expert-wang", role: "observer", glassesId: "glasses-01" },
    });
    await vi.waitFor(() => expect(controller.getSnapshot().status).toBe("in_call"));

    await controller.end();

    expect(signaling.leaveObserver).toHaveBeenCalledWith("session-1");
    expect(signaling.end).not.toHaveBeenCalled();
  });

  it("leaves only the observer seat when an observer cannot join TRTC", async () => {
    const { controller, emit, signaling, trtc } = setup();
    vi.mocked(trtc.join).mockRejectedValueOnce(new Error("TRTC join failed"));
    emit({
      type: "observer.invited", sessionId: "session-1", senderId: "server", seq: 1, sentAt: 1,
      payload: { expertId: "expert-wang", inviterId: "expert-liu", glassesId: "glasses-01" },
    });
    controller.accept();
    emit({
      type: "observer.accepted", sessionId: "session-1", senderId: "server", seq: 2, sentAt: 2,
      payload: { expertId: "expert-wang", role: "observer", glassesId: "glasses-01" },
    });
    await vi.waitFor(() => expect(controller.getSnapshot().status).toBe("failed"));

    expect(signaling.leaveObserver).toHaveBeenCalledWith("session-1");
    expect(signaling.end).not.toHaveBeenCalled();
    expect(trtc.leave).toHaveBeenCalledOnce();
  });
});
