import { describe, expect, it, vi } from "vitest";
import { captureFreezeFrame, uploadFreezeFrame } from "./freeze-frame";

describe("freeze frame", () => {
  it("captures the TRTC video at its intrinsic resolution", () => {
    const view = document.createElement("div");
    const video = document.createElement("video");
    Object.defineProperties(video, {
      videoWidth: { value: 1280 },
      videoHeight: { value: 720 },
    });
    view.append(video);
    const drawImage = vi.fn();
    const canvas = {
      width: 0,
      height: 0,
      getContext: () => ({ drawImage }),
      toDataURL: () => "data:image/jpeg;base64,/9j/2Q==",
    };

    const dataUrl = captureFreezeFrame(view, () => canvas as unknown as HTMLCanvasElement);

    expect(canvas.width).toBe(1280);
    expect(canvas.height).toBe(720);
    expect(drawImage).toHaveBeenCalledWith(video, 0, 0, 1280, 720);
    expect(dataUrl).toBe("data:image/jpeg;base64,/9j/2Q==");
  });

  it("uploads a frame and returns an absolute URL", async () => {
    const fetchImpl = vi.fn(async () => new Response(
      JSON.stringify({ url: "/api/freezes/frame.jpg" }),
      { status: 201, headers: { "content-type": "application/json" } },
    ));

    await expect(uploadFreezeFrame({
      dataUrl: "data:image/jpeg;base64,/9j/2Q==",
      fetchImpl,
      serverOrigin: "http://192.168.1.10:8787",
      sessionId: "session-1",
    })).resolves.toBe("http://192.168.1.10:8787/api/freezes/frame.jpg");
  });
});
