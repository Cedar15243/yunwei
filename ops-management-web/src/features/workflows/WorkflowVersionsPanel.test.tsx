import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { WorkflowVersion } from "../../api/workflow-types";
import { WorkflowVersionsPanel } from "./WorkflowVersionsPanel";

const version: WorkflowVersion = {
  id: "33333333-3333-4333-8333-333333333333",
  workflow_definition_id: "22222222-2222-4222-8222-222222222222",
  version_number: 3,
  status: "published",
  schema_version: 1,
  content_sha256: "abcdef".repeat(10) + "abcd",
  signature_key_id: "workflow-key-a",
  required_capabilities: ["camera.photo", "ai.execution_context"],
  min_app_version_code: 9002,
  published_by: "44444444-4444-4444-8444-444444444444",
  published_at: "2026-08-01T03:30:00.000Z",
  status_changed_at: "2026-08-01T03:30:00.000Z",
};

describe("WorkflowVersionsPanel", () => {
  it("renders immutable server versions and recovers a failed load", async () => {
    const user = userEvent.setup();
    const getWorkflowVersions = vi.fn()
      .mockRejectedValueOnce(new Error("版本服务暂不可用"))
      .mockResolvedValueOnce([version]);
    render(
      <WorkflowVersionsPanel
        api={{ getWorkflowVersions }}
        reloadToken={0}
        workflowId={version.workflow_definition_id}
      />,
    );

    expect(await screen.findByRole("alert")).toHaveTextContent("版本服务暂不可用");
    await user.click(screen.getByRole("button", { name: "重试版本列表" }));

    expect(await screen.findByText("v3")).toBeVisible();
    expect(screen.getByText("最低客户端 9002")).toBeVisible();
    expect(screen.getByText("camera.photo、ai.execution_context")).toBeVisible();
    expect(screen.getByText(/abcdef/)).toBeVisible();
  });
});
