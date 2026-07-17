import { describe, expect, it, vi } from "vitest";
import type { CollabEnvelope } from "./api/collab-socket";
import { CollaborationController, type CollabSignaling, type TrtcSession } from "./collaboration-controller";

function setup() {
  let listener: ((message: CollabEnvelope) => void) | null = null;
  const signaling: CollabSignaling = {
    register: vi.fn(),
    accept: vi.fn(() => true),
    end: vi.fn(),
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
    }));
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
});
