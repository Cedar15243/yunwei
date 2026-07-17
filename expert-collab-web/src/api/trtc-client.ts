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

export interface TrtcSdk {
  enterRoom(config: EnterRoomOptions): Promise<void>;
  startLocalAudio(): Promise<void>;
  startRemoteVideo(config: RemoteVideoOptions): Promise<void>;
  stopRemoteVideo?(config: { userId: string; streamType: unknown }): Promise<void>;
  stopLocalAudio?(): Promise<void>;
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
}

interface JoinOptions {
  sessionId: string;
  userId: string;
  glassesUserId: string;
  videoView: HTMLElement;
}

async function createBrowserSdk(): Promise<TrtcSdk> {
  const { default: TRTC } = await import("trtc-sdk-v5");
  const sdk = TRTC.create();
  return {
    enterRoom: (config) => sdk.enterRoom(config),
    startLocalAudio: () => sdk.startLocalAudio(),
    startRemoteVideo: (config) => sdk.startRemoteVideo({
      ...config,
      streamType: config.streamType as Parameters<typeof sdk.startRemoteVideo>[0]["streamType"],
    }),
    stopRemoteVideo: (config) => sdk.stopRemoteVideo({
      userId: config.userId,
      streamType: config.streamType as Parameters<typeof sdk.stopRemoteVideo>[0]["streamType"],
    }),
    stopLocalAudio: () => sdk.stopLocalAudio(),
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
  private sdk: TrtcSdk | null = null;
  private remoteUserId: string | null = null;

  constructor(options: TrtcClientOptions = {}) {
    this.createSdk = options.createSdk ?? createBrowserSdk;
    this.getCredential = options.getCredential ?? requestCredential;
    this.mainStreamType = options.mainStreamType ?? "main";
  }

  async join(options: JoinOptions): Promise<void> {
    if (this.sdk) {
      throw new Error("TRTC client is already in a room");
    }

    const credential = await this.getCredential(options.userId);
    const sdk = await this.createSdk();
    this.sdk = sdk;
    try {
      await sdk.enterRoom({
        sdkAppId: credential.sdkAppId,
        userId: credential.userId,
        userSig: credential.userSig,
        strRoomId: options.sessionId,
      });
      await sdk.startLocalAudio();
      await sdk.startRemoteVideo({
        userId: options.glassesUserId,
        streamType: this.mainStreamType,
        view: options.videoView,
      });
      this.remoteUserId = options.glassesUserId;
    } catch (error) {
      await sdk.exitRoom();
      this.sdk = null;
      throw error;
    }
  }

  async leave(): Promise<void> {
    const sdk = this.sdk;
    if (!sdk) {
      return;
    }

    if (this.remoteUserId) {
      await sdk.stopRemoteVideo?.({ userId: this.remoteUserId, streamType: this.mainStreamType });
    }
    await sdk.stopLocalAudio?.();
    await sdk.exitRoom();
    this.sdk = null;
    this.remoteUserId = null;
  }
}
