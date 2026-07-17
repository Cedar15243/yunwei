import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

class FakeBrowserSocket extends EventTarget {
  static instance: FakeBrowserSocket | null = null;
  readonly sent: string[] = [];

  constructor(readonly url: string) {
    super();
    FakeBrowserSocket.instance = this;
  }

  send(data: string): void {
    this.sent.push(data);
  }

  close(): void {}

  open(): void {
    this.dispatchEvent(new Event("open"));
  }

  receive(message: Record<string, unknown>): void {
    this.dispatchEvent(new MessageEvent("message", { data: JSON.stringify(message) }));
  }
}

afterEach(() => {
  vi.unstubAllGlobals();
  FakeBrowserSocket.instance = null;
});

describe("expert collaboration console", () => {
  it("shows the accepted three-column operations layout", () => {
    render(<App initialRole="primary" />);

    expect(screen.getByText("叮当专家协同")).toBeVisible();
    expect(screen.getAllByText("Air3-现场01")).toHaveLength(2);
    expect(screen.getByRole("main", { name: "专家协同视频工作区" })).toBeVisible();
    expect(screen.getByText("会话成员")).toBeVisible();
  });

  it("disables primary controls for observers", () => {
    render(<App initialRole="observer" />);

    expect(screen.getByText("当前以旁听身份加入，标注与邀请功能已锁定。")).toBeVisible();
    expect(screen.getByRole("button", { name: "箭头" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "画笔" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "冻结画面" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "邀请专家" })).toBeDisabled();
  });

  it("registers online and lets an expert accept an incoming glasses call", async () => {
    vi.stubGlobal("WebSocket", FakeBrowserSocket);
    render(<App live />);
    const socket = FakeBrowserSocket.instance;
    expect(socket).not.toBeNull();

    act(() => socket?.open());
    await waitFor(() => expect(socket?.sent).toHaveLength(1));
    act(() => socket?.receive({
      type: "call.requested",
      sessionId: "session-1",
      senderId: "server",
      seq: 1,
      sentAt: 1,
      payload: { glassesId: "glasses-01", glassesName: "Air3-现场01" },
    }));

    expect(await screen.findByText("Air3-现场01 正在呼叫")).toBeVisible();
    screen.getByRole("button", { name: "接听" }).click();

    const messages = socket?.sent.map((data) => JSON.parse(data) as { type: string }) ?? [];
    expect(messages.map((message) => message.type)).toEqual(["presence.registered", "call.accepted"]);
  });
});
