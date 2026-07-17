import { describe, expect, it } from "vitest";
import { denormalizePoint, normalizePoint } from "./video-coordinates";

describe("video coordinates", () => {
  it("maps pointer coordinates into the contained video rectangle", () => {
    expect(normalizePoint(
      { x: 500, y: 250 },
      { x: 100, y: 50, width: 800, height: 400 },
    )).toEqual({ x: 0.5, y: 0.5 });
  });

  it("rejects points outside the visible video rectangle", () => {
    expect(normalizePoint(
      { x: 80, y: 250 },
      { x: 100, y: 50, width: 800, height: 400 },
    )).toBeNull();
  });

  it("maps normalized coordinates back to pixels", () => {
    expect(denormalizePoint(
      { x: 0.25, y: 0.75 },
      { x: 0, y: 0, width: 1280, height: 720 },
    )).toEqual({ x: 320, y: 540 });
  });
});
