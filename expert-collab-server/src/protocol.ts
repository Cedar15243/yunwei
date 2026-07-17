export type ParticipantRole = "glasses" | "primary" | "observer";
export type SessionStatus = "calling" | "connecting" | "in_call" | "ended";

export interface Envelope<T = Record<string, unknown>> {
  type: string;
  sessionId: string | null;
  senderId: string;
  seq: number;
  sentAt: number;
  payload: T;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function requireString(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`${field} must be a non-empty string`);
  }
  return value;
}

function validateAnnotationPoints(payload: Record<string, unknown>): void {
  if (!Array.isArray(payload.points)) {
    throw new Error("annotation points must be an array");
  }

  for (const point of payload.points) {
    if (!isRecord(point)) {
      throw new Error("annotation point must be an object");
    }
    const { x, y } = point;
    if (
      typeof x !== "number" || !Number.isFinite(x) || x < 0 || x > 1 ||
      typeof y !== "number" || !Number.isFinite(y) || y < 0 || y > 1
    ) {
      throw new Error("annotation point must use a normalized coordinate between 0 and 1");
    }
  }
}

export function parseMessage(input: unknown): Envelope {
  if (!isRecord(input)) {
    throw new Error("message must be an object");
  }

  const type = requireString(input.type, "type");
  const senderId = requireString(input.senderId, "senderId");
  if (input.sessionId !== null && typeof input.sessionId !== "string") {
    throw new Error("sessionId must be a string or null");
  }
  if (!Number.isInteger(input.seq) || (input.seq as number) <= 0) {
    throw new Error("sequence number must be a positive integer");
  }
  if (typeof input.sentAt !== "number" || !Number.isFinite(input.sentAt)) {
    throw new Error("sentAt must be a finite number");
  }
  if (!isRecord(input.payload)) {
    throw new Error("payload must be an object");
  }

  if (type.startsWith("annotation.")) {
    validateAnnotationPoints(input.payload);
  }

  return {
    type,
    sessionId: input.sessionId,
    senderId,
    seq: input.seq as number,
    sentAt: input.sentAt,
    payload: input.payload,
  };
}
