import { act, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

class FakeBrowserSocket extends EventTarget {
  static instance: FakeBrowserSocket | null = null;
  static instances: FakeBrowserSocket[] = [];
  readonly sent: string[] = [];

  constructor(readonly url: string) {
    super();
    FakeBrowserSocket.instance = this;
    FakeBrowserSocket.instances.push(this);
  }

  send(data: string): void {
    this.sent.push(data);
  }

  close(): void {}

  open(): void {
    this.dispatchEvent(new Event("open"));
  }

  disconnect(): void {
    this.dispatchEvent(new Event("close"));
  }

  receive(message: Record<string, unknown>): void {
    this.dispatchEvent(new MessageEvent("message", { data: JSON.stringify(message) }));
  }
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  sessionStorage.clear();
  FakeBrowserSocket.instance = null;
  FakeBrowserSocket.instances = [];
});

describe("expert collaboration console", () => {
  it("shows the accepted three-column operations layout", () => {
    render(<App initialRole="primary" />);

    expect(screen.getByText("叮当云 AI 专家协同")).toBeVisible();
    expect(screen.getByText("等待现场设备接入")).toBeVisible();
    expect(screen.getByRole("main", { name: "专家协同视频工作区" })).toBeVisible();
    expect(screen.getByText("本次协同")).toBeVisible();
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
    expect(screen.getByText("专家在线 · 等待现场呼叫")).toBeVisible();
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

  it("reconnects and registers the expert again after the socket closes", () => {
    vi.useFakeTimers();
    vi.stubGlobal("WebSocket", FakeBrowserSocket);
    render(<App live />);
    const firstSocket = FakeBrowserSocket.instances[0];

    act(() => firstSocket.open());
    expect(firstSocket.sent.map((data) => JSON.parse(data).type)).toEqual(["presence.registered"]);

    act(() => firstSocket.disconnect());
    act(() => vi.runOnlyPendingTimers());
    expect(FakeBrowserSocket.instances).toHaveLength(2);

    const secondSocket = FakeBrowserSocket.instances[1];
    act(() => secondSocket.open());
    expect(secondSocket.sent.map((data) => JSON.parse(data).type)).toEqual(["presence.registered"]);
  });

  it("reuses the same expert identity after a reload-style remount", () => {
    vi.stubGlobal("WebSocket", FakeBrowserSocket);
    const firstRender = render(<App live />);
    const firstSocket = FakeBrowserSocket.instances[0];
    act(() => firstSocket.open());
    const firstRegistration = JSON.parse(firstSocket.sent[0]) as { senderId: string };

    firstRender.unmount();
    render(<App live />);
    const secondSocket = FakeBrowserSocket.instances[1];
    act(() => secondSocket.open());
    const secondRegistration = JSON.parse(secondSocket.sent[0]) as { senderId: string };

    expect(secondRegistration.senderId).toBe(firstRegistration.senderId);
  });

  it("does not restore the accept prompt when an ended call is replayed after reconnecting", () => {
    vi.useFakeTimers();
    vi.stubGlobal("WebSocket", FakeBrowserSocket);
    render(<App live />);
    const firstSocket = FakeBrowserSocket.instances[0];
    const callRequested = {
      type: "call.requested",
      sessionId: "session-1",
      senderId: "server",
      seq: 1,
      sentAt: 1,
      payload: { glassesId: "glasses-01", glassesName: "Air3-现场01" },
    };

    act(() => {
      firstSocket.open();
      firstSocket.receive(callRequested);
    });
    expect(screen.getByRole("button", { name: "接听" })).toBeVisible();

    act(() => firstSocket.receive({
      type: "call.ended",
      sessionId: "session-1",
      senderId: "server",
      seq: 2,
      sentAt: 2,
      payload: {},
    }));
    expect(screen.queryByRole("button", { name: "接听" })).not.toBeInTheDocument();

    act(() => firstSocket.disconnect());
    act(() => vi.runOnlyPendingTimers());
    const secondSocket = FakeBrowserSocket.instances[1];
    act(() => {
      secondSocket.open();
      secondSocket.receive({ ...callRequested, seq: 3, sentAt: 3 });
    });

    expect(screen.queryByRole("button", { name: "接听" })).not.toBeInTheDocument();
  });
});
