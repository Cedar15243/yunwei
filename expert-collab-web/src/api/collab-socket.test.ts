import { describe, expect, it } from "vitest";
import { CollabSocket, type SocketLike } from "./collab-socket";

class FakeSocket implements SocketLike {
  readonly sent: string[] = [];

  send(data: string): void {
    this.sent.push(data);
  }
}

describe("CollabSocket", () => {
  it("sends accept only once for an incoming call", () => {
    const socket = new FakeSocket();
    const client = new CollabSocket(socket, { senderId: "expert-wang" });

    client.accept("session-1");
    client.accept("session-1");

    const accepted = socket.sent
      .map((message) => JSON.parse(message) as { type: string })
      .filter((message) => message.type === "call.accepted");
    expect(accepted).toHaveLength(1);
  });

  it("increments the sequence for every outgoing event", () => {
    const socket = new FakeSocket();
    const client = new CollabSocket(socket, { senderId: "expert-wang" });

    client.register("王工");
    client.accept("session-1");

    expect(socket.sent.map((message) => JSON.parse(message).seq)).toEqual([1, 2]);
  });
});
