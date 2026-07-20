import { describe, expect, it, vi } from "vitest";
import { TrtcClient, type TrtcSdk } from "./trtc-client";

describe("TrtcClient", () => {
  it("keeps the call connected when the expert camera is denied", async () => {
    const calls: string[] = [];
    const cameraErrors: string[] = [];
    let remoteVideoHandler: ((event: { userId: string; streamType: unknown }) => void) | null = null;
    const sdk: TrtcSdk = {
      async enterRoom() {
        remoteVideoHandler?.({ userId: "glasses-01", streamType: "main" });
      },
      async startLocalAudio() {
        calls.push("audio:start");
      },
      async startLocalVideo() {
        throw new Error("Permission denied by system");
      },
      async startRemoteVideo() {
        calls.push("video:remote");
      },
      onRemoteVideoAvailable(handler) {
        remoteVideoHandler = handler;
      },
      offRemoteVideoAvailable() {},
      async exitRoom() {
        calls.push("exit");
      },
    };
    const client = new TrtcClient({
      createSdk: () => sdk,
      getCredential: async (userId) => ({ sdkAppId: 1600152353, userId, userSig: "sig" }),
      onLocalVideoError: (error) => cameraErrors.push(error.message),
    });

    await client.join({
      sessionId: "session-1",
      userId: "expert-wang",
      glassesUserId: "glasses-01",
      videoView: document.createElement("div"),
      localVideoView: document.createElement("div"),
      publishVideo: true,
    });

    expect(calls).toEqual(["audio:start", "video:remote"]);
    expect(cameraErrors).toEqual(["Permission denied by system"]);
  });

  it("keeps the expert connected while waiting for the glasses main video", async () => {
    const calls: string[] = [];
    const remoteVideoHandlers: Array<(event: { userId: string; streamType: unknown }) => void> = [];
    const sdk = {
      async enterRoom() {
        calls.push("enter");
      },
      async startLocalAudio() {
        calls.push("audio:start");
      },
      async startLocalVideo() {
        calls.push("camera:start");
      },
      async startRemoteVideo(config: { userId: string }) {
        calls.push(`video:${config.userId}`);
      },
      onRemoteVideoAvailable(handler: (event: { userId: string; streamType: unknown }) => void) {
        remoteVideoHandlers.push(handler);
      },
      offRemoteVideoAvailable() {
        calls.push("video-listener:off");
      },
      async exitRoom() {
        calls.push("exit");
      },
      async stopLocalVideo() {
        calls.push("camera:stop");
      },
    } as TrtcSdk & {
      onRemoteVideoAvailable(handler: (event: { userId: string; streamType: unknown }) => void): void;
      offRemoteVideoAvailable(handler: (event: { userId: string; streamType: unknown }) => void): void;
    };
    const client = new TrtcClient({
      createSdk: () => sdk,
      getCredential: async (userId) => ({ sdkAppId: 1600152353, userId, userSig: "sig" }),
      mainStreamType: "main",
    });

    await client.join({
      sessionId: "session-1",
      userId: "expert-wang",
      glassesUserId: "glasses-01",
      videoView: document.createElement("div"),
      localVideoView: document.createElement("div"),
      publishVideo: false,
    });
    expect(calls).toContain("audio:start");

    expect(calls).not.toContain("video:glasses-01");
    expect(remoteVideoHandlers).toHaveLength(1);
    remoteVideoHandlers.forEach((handler) => handler({ userId: "other-device", streamType: "main" }));
    remoteVideoHandlers.forEach((handler) => handler({ userId: "glasses-01", streamType: "sub" }));
    expect(calls).not.toContain("video:glasses-01");

    remoteVideoHandlers.forEach((handler) => handler({ userId: "glasses-01", streamType: "main" }));
    await vi.waitFor(() => expect(calls).toContain("video:glasses-01"));

    expect(calls).toEqual(["enter", "audio:start", "video-listener:off", "video:glasses-01"]);
  });

  it("does not leave the room when glasses video is not published in time", async () => {
    const calls: string[] = [];
    const sdk: TrtcSdk = {
      async enterRoom() { calls.push("enter"); },
      async startLocalAudio() { calls.push("audio:start"); },
      async startLocalVideo() { calls.push("camera:start"); },
      async startRemoteVideo() { calls.push("video:remote"); },
      onRemoteVideoAvailable() {},
      offRemoteVideoAvailable() { calls.push("video-listener:off"); },
      async exitRoom() { calls.push("exit"); },
    };
    const client = new TrtcClient({
      createSdk: () => sdk,
      getCredential: async (userId) => ({ sdkAppId: 1600152353, userId, userSig: "sig" }),
      remoteVideoTimeoutMs: 1,
    });

    await client.join({
      sessionId: "session-1",
      userId: "expert-wang",
      glassesUserId: "glasses-01",
      videoView: document.createElement("div"),
      localVideoView: document.createElement("div"),
      publishVideo: true,
    });
    await new Promise((resolve) => setTimeout(resolve, 5));

    expect(calls).toEqual(["enter", "audio:start", "camera:start", "video-listener:off"]);
  });

  it("retries the glasses video subscription after a transient TRTC failure", async () => {
    const calls: string[] = [];
    let remoteVideoHandler: ((event: { userId: string; streamType: unknown }) => void) | null = null;
    const sdk: TrtcSdk = {
      async enterRoom() { remoteVideoHandler?.({ userId: "glasses-01", streamType: "main" }); },
      async startLocalAudio() { calls.push("audio:start"); },
      async startLocalVideo() {},
      async startRemoteVideo() {
        calls.push("video:remote");
        if (calls.filter((call) => call === "video:remote").length === 1) {
          throw new Error("peer connection not ready");
        }
      },
      onRemoteVideoAvailable(handler) { remoteVideoHandler = handler; },
      offRemoteVideoAvailable() {},
      async exitRoom() { calls.push("exit"); },
    };
    const client = new TrtcClient({
      createSdk: () => sdk,
      getCredential: async (userId) => ({ sdkAppId: 1600152353, userId, userSig: "sig" }),
    });

    await client.join({
      sessionId: "session-1",
      userId: "expert-wang",
      glassesUserId: "glasses-01",
      videoView: document.createElement("div"),
      localVideoView: document.createElement("div"),
      publishVideo: false,
    });

    await vi.waitFor(() => expect(calls).toEqual(["audio:start", "video:remote", "video:remote"]), { timeout: 2_000 });
    expect(calls).not.toContain("exit");
  });

  it("joins, publishes microphone, subscribes to glasses video, then leaves", async () => {
    const calls: string[] = [];
    let remoteVideoHandler: ((event: { userId: string; streamType: unknown }) => void) | null = null;
    const sdk: TrtcSdk = {
      async enterRoom(config) {
        calls.push(`enter:${config.strRoomId}:${config.userId}`);
        remoteVideoHandler?.({ userId: "glasses-01", streamType: "main" });
      },
      async startLocalAudio() {
        calls.push("audio:start");
      },
      async startLocalVideo() {
        calls.push("camera:start");
      },
      async startRemoteVideo(config) {
        calls.push(`video:${config.userId}`);
      },
      onRemoteVideoAvailable(handler) {
        remoteVideoHandler = handler;
      },
      offRemoteVideoAvailable() {},
      async stopLocalVideo() {
        calls.push("camera:stop");
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
      localVideoView: document.createElement("div"),
      publishVideo: true,
    });
    await client.leave();

    expect(calls).toEqual([
      "credential:expert-wang",
      "enter:session-1:expert-wang",
      "audio:start",
      "camera:start",
      "video:glasses-01",
      "camera:stop",
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
      async startLocalVideo() {
        calls.push("camera:start");
      },
      async startRemoteVideo() {
        calls.push("video");
      },
      onRemoteVideoAvailable() {},
      offRemoteVideoAvailable() {},
      async stopLocalVideo() {
        calls.push("camera:stop");
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
      localVideoView: document.createElement("div"),
      publishVideo: false,
    })).rejects.toThrow("microphone denied");

    expect(calls).toEqual(["enter", "exit"]);
  });
});
