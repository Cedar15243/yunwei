import { describe, expect, it } from "vitest";
import { TrtcClient, type TrtcSdk } from "./trtc-client";

describe("TrtcClient", () => {
  it("joins, publishes microphone, subscribes to glasses video, then leaves", async () => {
    const calls: string[] = [];
    const sdk: TrtcSdk = {
      async enterRoom(config) {
        calls.push(`enter:${config.strRoomId}:${config.userId}`);
      },
      async startLocalAudio() {
        calls.push("audio:start");
      },
      async startRemoteVideo(config) {
        calls.push(`video:${config.userId}`);
      },
      async exitRoom() {
        calls.push("exit");
      },
    };
    const view = document.createElement("div");
    const client = new TrtcClient({
      createSdk: () => sdk,
      getCredential: async (userId) => {
        calls.push(`credential:${userId}`);
        return { sdkAppId: 1600152353, userId, userSig: "short-user-sig" };
      },
      mainStreamType: "main",
    });

    await client.join({
      sessionId: "session-1",
      userId: "expert-wang",
      glassesUserId: "glasses-01",
      videoView: view,
    });
    await client.leave();

    expect(calls).toEqual([
      "credential:expert-wang",
      "enter:session-1:expert-wang",
      "audio:start",
      "video:glasses-01",
      "exit",
    ]);
  });

  it("leaves the room when media startup fails", async () => {
    const calls: string[] = [];
    const sdk: TrtcSdk = {
      async enterRoom() {
        calls.push("enter");
      },
      async startLocalAudio() {
        throw new Error("microphone denied");
      },
      async startRemoteVideo() {
        calls.push("video");
      },
      async exitRoom() {
        calls.push("exit");
      },
    };
    const client = new TrtcClient({
      createSdk: () => sdk,
      getCredential: async (userId) => ({ sdkAppId: 1600152353, userId, userSig: "sig" }),
      mainStreamType: "main",
    });

    await expect(client.join({
      sessionId: "session-1",
      userId: "expert-wang",
      glassesUserId: "glasses-01",
      videoView: document.createElement("div"),
    })).rejects.toThrow("microphone denied");

    expect(calls).toEqual(["enter", "exit"]);
  });
});
