import { describe, expect, it } from "vitest";
import type { WorkflowDefinition, WorkflowDraft } from "../../api/workflow-types";
import {
  addWorkflowNode,
  connectWorkflowNodes,
  createWorkflowEditorState,
  deleteWorkflowNode,
  deleteWorkflowTransition,
  discardWorkflowChanges,
  moveWorkflowNode,
  updateWorkflowNodeConfig,
  WorkflowEditorError,
} from "./workflow-editor-state";

const definition: WorkflowDefinition = {
  id: "22222222-2222-4222-8222-222222222222",
  field_app_id: "11111111-1111-4111-8111-111111111111",
  workflow_key: "receive_device",
  title: "设备收货检查",
  description: "按步骤采集现场证据",
  status: "draft",
  schema_version: 1,
  latest_version_number: 0,
  draft_graph: {} as WorkflowDraft,
  created_at: "2026-08-01T01:30:00.000Z",
  updated_at: "2026-08-01T02:30:00.000Z",
};

describe("workflow editor state", () => {
  it("creates a stable start-to-complete draft when the server draft is empty", () => {
    const state = createWorkflowEditorState(definition);

    expect(state.draft.nodes.map((node) => node.type)).toEqual(["start", "complete"]);
    expect(state.draft.transitions).toEqual([
      expect.objectContaining({ fromNodeId: "start", toNodeId: "complete" }),
    ]);
    expect(state.dirty).toBe(false);
  });

  it("adds nodes with unique IDs and persists their editor positions", () => {
    const initial = createWorkflowEditorState(definition);
    const first = addWorkflowNode(initial, "instruction", { x: 260, y: 120 });
    const second = addWorkflowNode(first, "instruction", { x: 260, y: 260 });
    const moved = moveWorkflowNode(second, "instruction-2", { x: 420, y: 310 });

    expect(moved.draft.nodes.map((node) => node.nodeId)).toEqual([
      "start",
      "complete",
      "instruction-1",
      "instruction-2",
    ]);
    expect(moved.draft.nodes.find((node) => node.nodeId === "instruction-2")?.position)
      .toEqual({ x: 420, y: 310 });
    expect(moved.selectedNodeId).toBe("instruction-2");
    expect(moved.dirty).toBe(true);
  });

  it("updates node configuration without mutating the saved baseline", () => {
    const initial = addWorkflowNode(
      createWorkflowEditorState(definition),
      "instruction",
      { x: 240, y: 120 },
      { title: "原始标题" },
    );

    const updated = updateWorkflowNodeConfig(initial, "instruction-1", {
      title: "设备铭牌拍摄",
      riskLevel: "high",
    });

    expect(updated.draft.nodes.find((node) => node.nodeId === "instruction-1")?.config).toEqual({
      title: "设备铭牌拍摄",
      riskLevel: "high",
    });
    expect(initial.draft.nodes.find((node) => node.nodeId === "instruction-1")?.config).toEqual({
      title: "原始标题",
    });
    expect(updated.dirty).toBe(true);
  });

  it("rejects illegal connections into start or out of complete", () => {
    const state = addWorkflowNode(createWorkflowEditorState(definition), "instruction", {
      x: 260,
      y: 120,
    });

    expect(() => connectWorkflowNodes(state, "complete", "instruction-1")).toThrowError(
      new WorkflowEditorError("complete_has_no_outgoing"),
    );
    expect(() => connectWorkflowNodes(state, "instruction-1", "start")).toThrowError(
      new WorkflowEditorError("start_has_no_incoming"),
    );
  });

  it("deletes related transitions with a node and supports deleting a single transition", () => {
    let state = addWorkflowNode(createWorkflowEditorState(definition), "instruction", {
      x: 260,
      y: 120,
    });
    state = connectWorkflowNodes(state, "start", "instruction-1");
    const addedTransition = state.draft.transitions.find(
      (transition) => transition.toNodeId === "instruction-1",
    );
    expect(addedTransition).toBeDefined();

    const withoutTransition = deleteWorkflowTransition(state, addedTransition!.transitionId);
    expect(withoutTransition.draft.transitions).not.toContainEqual(addedTransition);

    state = connectWorkflowNodes(withoutTransition, "instruction-1", "complete");
    const deleted = deleteWorkflowNode(state, "instruction-1");
    expect(deleted.draft.nodes.some((node) => node.nodeId === "instruction-1")).toBe(false);
    expect(
      deleted.draft.transitions.some(
        (transition) =>
          transition.fromNodeId === "instruction-1" || transition.toNodeId === "instruction-1",
      ),
    ).toBe(false);
  });

  it("restores the exact server draft after discarding unsaved changes", () => {
    const serverDraft: WorkflowDraft = {
      workflowId: definition.id,
      schemaVersion: 1,
      title: definition.title,
      nodes: [
        { nodeId: "start", type: "start", config: {}, position: { x: 80, y: 160 } },
        { nodeId: "photo", type: "photo_capture", config: {}, position: { x: 320, y: 160 } },
        { nodeId: "complete", type: "complete", config: {}, position: { x: 560, y: 160 } },
      ],
      transitions: [
        { transitionId: "to-photo", fromNodeId: "start", toNodeId: "photo" },
        { transitionId: "to-complete", fromNodeId: "photo", toNodeId: "complete" },
      ],
    };
    const loaded = createWorkflowEditorState({ ...definition, draft_graph: serverDraft });
    const edited = moveWorkflowNode(loaded, "photo", { x: 400, y: 240 });

    const discarded = discardWorkflowChanges(edited);

    expect(discarded.draft).toEqual(serverDraft);
    expect(discarded.dirty).toBe(false);
    expect(discarded.selectedNodeId).toBeNull();
  });
});
