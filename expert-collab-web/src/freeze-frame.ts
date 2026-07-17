type CanvasFactory = () => HTMLCanvasElement;

export function resolveCollabHttpOrigin(): string {
  return import.meta.env.VITE_COLLAB_HTTP_URL
    ?? `${window.location.protocol}//${window.location.hostname}:8787`;
}

export function captureFreezeFrame(
  videoView: HTMLElement,
  createCanvas: CanvasFactory = () => document.createElement("canvas"),
): string {
  const video = videoView.querySelector("video");
  if (!video || video.videoWidth <= 0 || video.videoHeight <= 0) {
    throw new Error("眼镜视频尚未生成可冻结画面");
  }

  const canvas = createCanvas();
  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  const context = canvas.getContext("2d");
  if (!context) {
    throw new Error("浏览器不支持画面冻结");
  }
  context.drawImage(video, 0, 0, canvas.width, canvas.height);
  return canvas.toDataURL("image/jpeg", 0.82);
}

interface UploadFreezeFrameOptions {
  dataUrl: string;
  fetchImpl?: typeof fetch;
  serverOrigin?: string;
  sessionId: string;
}

export async function uploadFreezeFrame(options: UploadFreezeFrameOptions): Promise<string> {
  const serverOrigin = options.serverOrigin ?? resolveCollabHttpOrigin();
  const response = await (options.fetchImpl ?? fetch)(
    `${serverOrigin}/api/sessions/${encodeURIComponent(options.sessionId)}/freeze`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ imageDataUrl: options.dataUrl }),
    },
  );
  if (!response.ok) {
    throw new Error(`冻结画面上传失败（${response.status}）`);
  }
  const result = await response.json() as { url?: unknown };
  if (typeof result.url !== "string") {
    throw new Error("冻结画面服务未返回文件地址");
  }
  return new URL(result.url, serverOrigin).toString();
}
