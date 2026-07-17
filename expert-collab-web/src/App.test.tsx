import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { App } from "./App";

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
});
