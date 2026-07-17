import { describe, expect, it } from "vitest";
import { parseMessage } from "../src/protocol.js";

describe("parseMessage", () => {
  it("accepts a valid collaboration envelope", () => {
    const message = parseMessage({
      type: "call.accepted",
      sessionId: "session-01",
      senderId: "expert-wang",
      seq: 1,
      sentAt: 1_784_253_600_000,
      payload: {},
    });

    expect(message.type).toBe("call.accepted");
    expect(message.sessionId).toBe("session-01");
  });

  it("rejects annotation points outside normalized coordinates", () => {
    expect(() => parseMessage({
      type: "annotation.append",
      sessionId: "session-01",
      senderId: "expert-wang",
      seq: 2,
      sentAt: 1_784_253_600_001,
      payload: {
        objectId: "stroke-01",
        points: [{ x: 1.2, y: 0.5 }],
      },
    })).toThrow("normalized coordinate");
  });

  it("rejects envelopes without a positive sequence number", () => {
    expect(() => parseMessage({
      type: "call.requested",
      sessionId: null,
      senderId: "glasses-01",
      seq: 0,
      sentAt: 1_784_253_600_002,
      payload: {},
    })).toThrow("sequence");
  });
});
