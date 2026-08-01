import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { ManagementApiError } from "../../api/management-api";
import type {
  WorkflowDefinition,
  WorkflowDraft,
  WorkflowVersion,
} from "../../api/workflow-types";
import {
  WorkflowCommandBar,
  type WorkflowCommandApi,
} from "./WorkflowCommandBar";

const draft: WorkflowDraft = {
  workflowId: "22222222-2222-4222-8222-222222222222",
  schemaVersion: 1,
  title: "设备收货检查",
  nodes: [
    { nodeId: "start", type: "start", config: {} },
    { nodeId: "complete", type: "complete", config: {} },
  ],
  transitions: [{ transitionId: "start-complete", fromNodeId: "start", toNodeId: "complete" }],
};

const workflow: WorkflowDefinition = {
  id: draft.workflowId,
  field_app_id: "11111111-1111-4111-8111-111111111111",
  workflow_key: "receive_device",
  title: draft.title,
  description: "按步骤采集现场证据",
  status: "draft",
  schema_version: 1,
  latest_version_number: 0,
  draft_graph: draft,
  created_at: "2026-08-01T01:30:00.000Z",
  updated_at: "2026-08-01T02:30:00.000Z",
};

const version: WorkflowVersion = {
  id: "33333333-3333-4333-8333-333333333333",
  workflow_definition_id: workflow.id,
  version_number: 1,
  status: "published",
  schema_version: 1,
  content_sha256: "a".repeat(64),
  signature_key_id: "workflow-key-a",
  required_capabilities: ["workflow.runtime.v1"],
  min_app_version_code: 9000,
  published_by: "44444444-4444-4444-8444-444444444444",
  published_at: "2026-08-01T03:30:00.000Z",
  status_changed_at: "2026-08-01T03:30:00.000Z",
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function commandApi(overrides: Partial<WorkflowCommandApi> = {}): WorkflowCommandApi {
  return {
    saveWorkflowDraft: vi.fn().mockResolvedValue(workflow),
    validateWorkflow: vi.fn().mockResolvedValue({ valid: true, validationErrors: [] }),
    publishWorkflow: vi.fn().mockResolvedValue(version),
    ...overrides,
  };
}

function Harness({ api, initiallyDirty = true }: { api: WorkflowCommandApi; initiallyDirty?: boolean }) {
  const [dirty, setDirty] = useState(initiallyDirty);
  return (
    <WorkflowCommandBar
      api={api}
      dirty={dirty}
      draft={draft}
      onPublished={vi.fn()}
      onSaved={() => setDirty(false)}
      workflow={workflow}
    />
  );
}

describe("WorkflowCommandBar", () => {
  it("keeps the draft dirty until the real save succeeds and shows server field paths", async () => {
    const user = userEvent.setup();
    const pending = deferred<WorkflowDefinition>();
    const validateWorkflow = vi.fn().mockRejectedValue(new ManagementApiError(422, "workflow_invalid", {
      validationErrors: [{ code: "complete_required", path: "$.nodes" }],
    }));
    const api = commandApi({
      saveWorkflowDraft: vi.fn().mockReturnValue(pending.promise),
      validateWorkflow,
    });
    render(<Harness api={api} />);

    expect(screen.getByRole("button", { name: "服务端校验" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "保存草稿" }));
    expect(screen.getByRole("button", { name: "正在保存" })).toBeDisabled();
    expect(screen.getByText("有未保存修改")).toBeVisible();

    await act(async () => pending.resolve(workflow));
    await waitFor(() => expect(screen.getByText("草稿已保存")).toBeVisible());
    expect(screen.getByText("当前草稿已保存")).toBeVisible();

    await user.click(screen.getByRole("button", { name: "服务端校验" }));
    expect(await screen.findByText("complete_required · $.nodes")).toBeVisible();
    expect(validateWorkflow).toHaveBeenCalledWith(workflow.id);
  });

  it("requires reason, minimum V9 version and the fixed phrase before publishing", async () => {
    const user = userEvent.setup();
    render(<Harness api={commandApi()} initiallyDirty={false} />);

    await user.click(screen.getByRole("button", { name: "发布工作流" }));
    const publish = screen.getByRole("button", { name: "确认发布" });
    expect(publish).toBeDisabled();

    await user.type(screen.getByRole("textbox", { name: "发布理由" }), "通过正常、异常和离线路径验证");
    await user.clear(screen.getByRole("spinbutton", { name: "最低 V9 版本号" }));
    await user.type(screen.getByRole("spinbutton", { name: "最低 V9 版本号" }), "8999");
    await user.type(screen.getByRole("textbox", { name: "确认词" }), "PUBLISH_WORKFLOW");
    expect(publish).toBeDisabled();

    await user.clear(screen.getByRole("spinbutton", { name: "最低 V9 版本号" }));
    await user.type(screen.getByRole("spinbutton", { name: "最低 V9 版本号" }), "9000");
    expect(publish).toBeEnabled();
  });

  it("reuses one idempotency key after a signing failure and only reports server success", async () => {
    const user = userEvent.setup();
    const publishWorkflow = vi.fn()
      .mockRejectedValueOnce(new ManagementApiError(503, "signing_unavailable", {}))
      .mockResolvedValueOnce(version);
    const onPublished = vi.fn();
    render(
      <WorkflowCommandBar
        api={commandApi({ publishWorkflow })}
        dirty={false}
        draft={draft}
        onPublished={onPublished}
        onSaved={vi.fn()}
        workflow={workflow}
      />,
    );

    await user.click(screen.getByRole("button", { name: "发布工作流" }));
    await user.type(screen.getByRole("textbox", { name: "发布理由" }), "通过样例和异常路径验证");
    await user.type(screen.getByRole("textbox", { name: "确认词" }), "PUBLISH_WORKFLOW");
    await user.click(screen.getByRole("button", { name: "确认发布" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("服务端签名暂不可用");
    expect(onPublished).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "确认发布" }));

    await waitFor(() => expect(onPublished).toHaveBeenCalledWith(version));
    expect(screen.getByText("工作流 v1 已发布")).toBeVisible();
    const first = vi.mocked(publishWorkflow).mock.calls[0][1];
    const second = vi.mocked(publishWorkflow).mock.calls[1][1];
    expect(first.idempotencyKey).toBeTruthy();
    expect(second.idempotencyKey).toBe(first.idempotencyKey);
  });
});
