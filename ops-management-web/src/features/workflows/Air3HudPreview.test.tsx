import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type {
  WorkflowDraftNode,
  WorkflowNodeCatalogItem,
  WorkflowPageTemplate,
} from "../../api/workflow-types";
import { Air3HudPreview } from "./Air3HudPreview";

function item(template: WorkflowPageTemplate): WorkflowNodeCatalogItem {
  return {
    type: template === "completion" ? "complete" : "instruction",
    label: "操作说明",
    category: "content",
    pageTemplate: template,
    requiredCapability: null,
    fields: [],
  };
}

function node(config: Record<string, unknown> = {}): WorkflowDraftNode {
  return { nodeId: "instruction-1", type: "instruction", config };
}

describe("Air3HudPreview", () => {
  it.each([
    "instruction",
    "evidence_capture",
    "form",
    "choice",
    "conversation",
    "confirmation",
    "completion",
    "none",
  ] as WorkflowPageTemplate[])("maps %s to a fixed HUD template", (template) => {
    render(<Air3HudPreview catalog={item(template)} node={node()} />);

    expect(screen.getByTestId("air3-hud-preview")).toHaveAttribute("data-template", template);
  });

  it("renders configured content as text inside the frozen Air3 frame", () => {
    render(
      <Air3HudPreview
        catalog={item("instruction")}
        node={node({
          title: "拍摄设备铭牌",
          description: "<img src=x onerror=alert(1)>",
          riskLevel: "high",
          voicePrompt: "说下一步继续",
        })}
      />,
    );

    expect(screen.getByRole("heading", { name: "拍摄设备铭牌" })).toBeVisible();
    expect(screen.getByText("<img src=x onerror=alert(1)>")).toBeVisible();
    expect(screen.getByText("高风险")).toBeVisible();
    expect(screen.getByText("说下一步继续")).toBeVisible();
    expect(document.querySelector("img")).toBeNull();
    expect(screen.getByText("中心点击")).toBeVisible();
    expect(screen.getByText("长按中心")).toBeVisible();
    expect(screen.getByText("返回键")).toBeVisible();
  });

  it("shows evidence requirements without changing the fixed frame", () => {
    render(
      <Air3HudPreview
        catalog={{ ...item("evidence_capture"), type: "photo_capture", label: "拍照取证" }}
        node={{
          ...node({ title: "收货照片", minCount: 3, evidenceKey: "delivery.photos" }),
          type: "photo_capture",
        }}
      />,
    );

    expect(screen.getByText("至少采集 3 张照片")).toBeVisible();
    expect(screen.getByText("delivery.photos")).toBeVisible();
  });
});
