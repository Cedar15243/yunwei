interface EnterRoomOptions {
  sdkAppId: number;
  userId: string;
  userSig: string;
  strRoomId: string;
}

interface RemoteVideoOptions {
  userId: string;
  streamType: unknown;
  view: HTMLElement;
}

interface LocalVideoOptions {
  view: HTMLElement;
}

export interface RemoteVideoEvent {
  userId: string;
  streamType: unknown;
}

export interface TrtcSdk {
  enterRoom(config: EnterRoomOptions): Promise<void>;
  startLocalAudio(): Promise<void>;
  startLocalVideo(config: LocalVideoOptions): Promise<void>;
  startRemoteVideo(config: RemoteVideoOptions): Promise<void>;
  onRemoteVideoAvailable(handler: (event: RemoteVideoEvent) => void): void;
  offRemoteVideoAvailable(handler: (event: RemoteVideoEvent) => void): void;
  stopRemoteVideo?(config: { userId: string; streamType: unknown }): Promise<void>;
  stopLocalAudio?(): Promise<void>;
  stopLocalVideo?(): Promise<void>;
  exitRoom(): Promise<void>;
}

export interface TrtcCredential {
  sdkAppId: number;
  userId: string;
  userSig: string;
}

interface TrtcClientOptions {
  createSdk?: () => TrtcSdk | Promise<TrtcSdk>;
  getCredential?: (userId: string) => Promise<TrtcCredential>;
  mainStreamType?: unknown;
  remoteVideoTimeoutMs?: number;
  onLocalVideoError?: (error: Error) => void;
}

interface JoinOptions {
  sessionId: string;
  userId: string;
  glassesUserId: string;
  videoView: HTMLElement;
  localVideoView: HTMLElement;
  publishVideo: boolean;
}

async function createBrowserSdk(): Promise<TrtcSdk> {
  const { default: TRTC } = await import("trtc-sdk-v5");
  const sdk = TRTC.create();
  return {
    enterRoom: (config) => sdk.enterRoom(config),
    startLocalAudio: () => sdk.startLocalAudio(),
    startLocalVideo: (config) => sdk.startLocalVideo({
      view: config.view,
      option: {
        profile: { width: 1280, height: 720, frameRate: 20, bitrate: 1500 },
        qosPreference: "clear",
      },
    }),
    startRemoteVideo: (config) => sdk.startRemoteVideo({
      ...config,
      streamType: config.streamType as Parameters<typeof sdk.startRemoteVideo>[0]["streamType"],
    }),
    onRemoteVideoAvailable: (handler) => sdk.on(TRTC.EVENT.REMOTE_VIDEO_AVAILABLE, handler),
    offRemoteVideoAvailable: (handler) => sdk.off(TRTC.EVENT.REMOTE_VIDEO_AVAILABLE, handler),
    stopRemoteVideo: (config) => sdk.stopRemoteVideo({
      userId: config.userId,
      streamType: config.streamType as Parameters<typeof sdk.stopRemoteVideo>[0]["streamType"],
    }),
    stopLocalAudio: () => sdk.stopLocalAudio(),
    stopLocalVideo: () => sdk.stopLocalVideo(),
    exitRoom: () => sdk.exitRoom(),
  };
}

async function requestCredential(userId: string): Promise<TrtcCredential> {
  const serverOrigin = import.meta.env.VITE_COLLAB_HTTP_URL
    ?? `${window.location.protocol}//${window.location.hostname}:8787`;
  const response = await fetch(`${serverOrigin}/api/trtc/credential`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ userId }),
  });
  if (!response.ok) {
    throw new Error(`TRTC credential request failed with status ${response.status}`);
  }
  return response.json() as Promise<TrtcCredential>;
}

export class TrtcClient {
  private readonly createSdk: () => TrtcSdk | Promise<TrtcSdk>;
  private readonly getCredential: (userId: string) => Promise<TrtcCredential>;
  private readonly mainStreamType: unknown;
  private readonly remoteVideoTimeoutMs: number;
  private readonly onLocalVideoError: (error: Error) => void;
  private sdk: TrtcSdk | null = null;
  private remoteUserId: string | null = null;
  private localVideoStarted = false;
  private cancelRemoteVideoWait: (() => void) | null = null;

  constructor(options: TrtcClientOptions = {}) {
    this.createSdk = options.createSdk ?? createBrowserSdk;
    this.getCredential = options.getCredential ?? requestCredential;
    this.mainStreamType = options.mainStreamType ?? "main";
    this.remoteVideoTimeoutMs = options.remoteVideoTimeoutMs ?? 15_000;
    this.onLocalVideoError = options.onLocalVideoError ?? (() => undefined);
  }

  async join(options: JoinOptions): Promise<void> {
    if (this.sdk) {
      throw new Error("TRTC client is already in a room");
    }

    const credential = await this.getCredential(options.userId);
    const sdk = await this.createSdk();
    this.sdk = sdk;
    const remoteVideoReady = this.waitForRemoteVideo(sdk, options.glassesUserId);
    try {
      await sdk.enterRoom({
        sdkAppId: credential.sdkAppId,
        userId: credential.userId,
        userSig: credential.userSig,
        strRoomId: options.sessionId,
      });
      await Promise.all([
        sdk.startLocalAudio(),
        options.publishVideo
          ? sdk.startLocalVideo({ view: options.localVideoView }).then(() => {
              this.localVideoStarted = true;
            }).catch((error: unknown) => {
              this.onLocalVideoError(error instanceof Error ? error : new Error("专家摄像头启动失败"));
            })
          : Promise.resolve(),
      ]);
      void remoteVideoReady.promise.then(async (videoAvailable) => {
        if (!videoAvailable || this.sdk !== sdk) {
          return;
        }
        await sdk.startRemoteVideo({
          userId: options.glassesUserId,
          streamType: this.mainStreamType,
          view: options.videoView,
        });
        if (this.sdk === sdk) {
          this.remoteUserId = options.glassesUserId;
        }
      }).catch(() => {
        // A delayed glasses video must not terminate an otherwise active voice call.
      });
    } catch (error) {
      remoteVideoReady.cancel();
      await sdk.exitRoom();
      this.sdk = null;
      throw error;
    }
  }

  async leave(): Promise<void> {
    this.cancelRemoteVideoWait?.();
    const sdk = this.sdk;
    if (!sdk) {
      return;
    }

    if (this.remoteUserId) {
      await sdk.stopRemoteVideo?.({ userId: this.remoteUserId, streamType: this.mainStreamType });
    }
    await sdk.stopLocalAudio?.();
    if (this.localVideoStarted) {
      await sdk.stopLocalVideo?.();
    }
    await sdk.exitRoom();
    this.sdk = null;
    this.remoteUserId = null;
    this.localVideoStarted = false;
  }

  private waitForRemoteVideo(sdk: TrtcSdk, userId: string): { promise: Promise<boolean>; cancel(): void } {
    let settled = false;
    let resolveWait: (available: boolean) => void = () => undefined;
    let rejectWait: (error: Error) => void = () => undefined;
    let timeoutId: ReturnType<typeof setTimeout>;
    const handler = (event: RemoteVideoEvent) => {
      if (event.userId === userId && event.streamType === this.mainStreamType) {
        finish(() => resolveWait(true));
      }
    };
    const cleanup = () => {
      clearTimeout(timeoutId);
      sdk.offRemoteVideoAvailable(handler);
      if (this.cancelRemoteVideoWait === cancel) {
        this.cancelRemoteVideoWait = null;
      }
    };
    const finish = (complete: () => void) => {
      if (settled) {
        return;
      }
      settled = true;
      cleanup();
      complete();
    };
    const cancel = () => finish(() => resolveWait(false));
    const promise = new Promise<boolean>((resolve, reject) => {
      resolveWait = resolve;
      rejectWait = reject;
    });

    sdk.onRemoteVideoAvailable(handler);
    timeoutId = setTimeout(() => {
      finish(() => rejectWait(new Error(`等待 ${userId} 发布主路视频超时`)));
    }, this.remoteVideoTimeoutMs);
    this.cancelRemoteVideoWait = cancel;

    return { promise, cancel };
  }
}
