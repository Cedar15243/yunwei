import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { App } from "./App";

describe("App", () => {
  it("shows sign in without a session", () => {
    render(<App initialAuthenticated={false} />);
    expect(screen.getByRole("heading", { name: "叮当 AI 运维管理平台" })).toBeVisible();
    expect(screen.getByLabelText("账号邮箱")).toBeVisible();
  });

  it("keeps desktop navigation after selecting tasks", async () => {
    const user = userEvent.setup();
    render(<App initialAuthenticated />);
    await user.click(screen.getByRole("link", { name: "项目与任务" }));
    expect(screen.getByRole("navigation", { name: "主导航" })).toBeVisible();
    expect(screen.getByRole("heading", { name: "项目与任务", level: 1 })).toBeVisible();
  });
});
