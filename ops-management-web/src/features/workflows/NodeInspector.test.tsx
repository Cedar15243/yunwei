import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import type {
  WorkflowDraftNode,
  WorkflowNodeCatalogItem,
} from "../../api/workflow-types";
import { NodeInspector } from "./NodeInspector";

const catalogItem: WorkflowNodeCatalogItem = {
  type: "photo_capture",
  label: "拍照取证",
  category: "evidence",
  pageTemplate: "evidence_capture",
  requiredCapability: "camera.photo",
  fields: [
    { key: "title", label: "页面标题", kind: "text", maxLength: 12 },
    { key: "description", label: "操作说明", kind: "textarea", maxLength: 100 },
    { key: "evidenceKey", label: "证据字段", kind: "identifier", required: true },
    { key: "minCount", label: "最少照片数", kind: "integer", min: 1, max: 20 },
    { key: "allowRetake", label: "允许重拍", kind: "boolean" },
    { key: "riskLevel", label: "风险等级", kind: "select", values: ["low", "high"] },
    { key: "allowedActions", label: "允许操作", kind: "select_list", values: ["capture", "retake", "next"] },
    { key: "referenceAssetIds", label: "参考图片", kind: "uuid_list", max: 2 },
  ],
};

function InspectorHarness({
  item = catalogItem,
  initialConfig,
}: {
  item?: WorkflowNodeCatalogItem;
  initialConfig?: Record<string, unknown>;
}) {
  const [node, setNode] = useState<WorkflowDraftNode>({
    nodeId: "photo-1",
    type: item.type,
    config: initialConfig ?? (item === catalogItem ? { title: "拍摄设备", minCount: 1 } : {}),
  });
  return (
    <>
      <NodeInspector
        catalog={item}
        node={node}
        onChange={(config) => setNode((current) => ({ ...current, config }))}
      />
      <output data-testid="config-output">{JSON.stringify(node.config)}</output>
    </>
  );
}

describe("NodeInspector", () => {
  it("builds simple controls from the server catalog and emits typed values", async () => {
    const user = userEvent.setup();
    render(<InspectorHarness />);

    await user.clear(screen.getByRole("textbox", { name: "页面标题" }));
    await user.type(screen.getByRole("textbox", { name: "页面标题" }), "设备铭牌");
    await user.clear(screen.getByRole("spinbutton", { name: "最少照片数" }));
    await user.type(screen.getByRole("spinbutton", { name: "最少照片数" }), "3");
    await user.click(screen.getByRole("checkbox", { name: "允许重拍" }));
    await user.selectOptions(screen.getByRole("combobox", { name: "风险等级" }), "high");
    await user.click(screen.getByRole("checkbox", { name: "采集" }));

    expect(screen.getByTestId("config-output")).toHaveTextContent('"title":"设备铭牌"');
    expect(screen.getByTestId("config-output")).toHaveTextContent('"minCount":3');
    expect(screen.getByTestId("config-output")).toHaveTextContent('"allowRetake":true');
    expect(screen.getByTestId("config-output")).toHaveTextContent('"riskLevel":"high"');
    expect(screen.getByTestId("config-output")).toHaveTextContent('"allowedActions":["capture"]');
  });

  it("shows catalog constraint errors without executing unsafe markup", async () => {
    const user = userEvent.setup();
    render(<InspectorHarness />);

    await user.clear(screen.getByRole("textbox", { name: "页面标题" }));
    await user.type(screen.getByRole("textbox", { name: "页面标题" }), "<img src=x>");
    await user.clear(screen.getByRole("spinbutton", { name: "最少照片数" }));
    await user.type(screen.getByRole("spinbutton", { name: "最少照片数" }), "21");

    expect(screen.getAllByRole("alert")).toHaveLength(3);
    expect(screen.getByText("不能包含 HTML、URL 或脚本协议")).toBeVisible();
    expect(screen.getByText("必须填写证据字段")).toBeVisible();
    expect(screen.getByText("请输入 1 到 20 之间的整数")).toBeVisible();
    expect(document.querySelector("img")).toBeNull();
  });

  it("edits structured option, field and mapping arrays without a raw JSON input", async () => {
    const user = userEvent.setup();
    const structured: WorkflowNodeCatalogItem = {
      type: "form",
      label: "表单",
      category: "input",
      pageTemplate: "form",
      requiredCapability: null,
      fields: [
        { key: "options", label: "选项", kind: "options", max: 3 },
        { key: "fields", label: "表单字段", kind: "fields", max: 3 },
        { key: "parameterMappings", label: "参数映射", kind: "mappings", max: 3 },
      ],
    };
    render(<InspectorHarness item={structured} />);

    await user.click(screen.getByRole("button", { name: "添加选项" }));
    const optionGroup = screen.getByRole("group", { name: "选项 1" });
    await user.type(within(optionGroup).getByRole("textbox", { name: "选项值" }), "normal");
    await user.type(within(optionGroup).getByRole("textbox", { name: "显示名称" }), "正常");

    await user.click(screen.getByRole("button", { name: "添加表单字段" }));
    const fieldGroup = screen.getByRole("group", { name: "表单字段 1" });
    await user.type(within(fieldGroup).getByRole("textbox", { name: "字段标识" }), "temperature");
    await user.type(within(fieldGroup).getByRole("textbox", { name: "字段名称" }), "温度");

    await user.click(screen.getByRole("button", { name: "添加参数映射" }));
    const mappingGroup = screen.getByRole("group", { name: "参数映射 1" });
    await user.type(within(mappingGroup).getByRole("textbox", { name: "来源字段" }), "task.temperature");
    await user.type(within(mappingGroup).getByRole("textbox", { name: "目标字段" }), "mvs.temperature");

    const output = screen.getByTestId("config-output");
    expect(output).toHaveTextContent('"options":[{"value":"normal","label":"正常"}]');
    expect(output).toHaveTextContent('"fields":[{"key":"temperature","label":"温度","type":"text","required":false}]');
    expect(output).toHaveTextContent('"parameterMappings":[{"sourceField":"task.temperature","targetField":"mvs.temperature"}]');
    expect(screen.queryByLabelText("JSON")).toBeNull();
  });

  it("surfaces unknown legacy configuration instead of silently presenting it as valid", () => {
    render(
      <InspectorHarness
        initialConfig={{
          title: "拍摄设备",
          evidenceKey: "device.photo",
          legacyRequestUrl: "https://legacy.invalid/action",
        }}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "当前草稿包含不受支持的配置：legacyRequestUrl",
    );
  });

  it("reports structured configuration conflicts before server validation", () => {
    const structured: WorkflowNodeCatalogItem = {
      type: "form",
      label: "表单",
      category: "input",
      pageTemplate: "form",
      requiredCapability: null,
      fields: [
        { key: "options", label: "选项", kind: "options", max: 5 },
        { key: "fields", label: "表单字段", kind: "fields", max: 5 },
      ],
    };
    render(
      <InspectorHarness
        item={structured}
        initialConfig={{
          options: [
            { value: "normal", label: "正常" },
            { value: "normal", label: "重复" },
          ],
          fields: [
            {
              key: "mode",
              label: "模式",
              type: "single_choice",
              required: true,
              options: [],
            },
            {
              key: "mode",
              label: "阈值",
              type: "number",
              required: false,
              min: 10,
              max: 1,
            },
          ],
        }}
      />,
    );

    const alerts = screen.getAllByRole("alert").map((alert) => alert.textContent).join("；");
    expect(alerts).toContain("选项值不能重复");
    expect(alerts).toContain("字段标识不能重复");
    expect(alerts).toContain("选择字段必须至少添加一个选项");
    expect(alerts).toContain("最小值不能大于最大值");
  });

  it("does not hide unsupported keys inside structured configuration", () => {
    const structured: WorkflowNodeCatalogItem = {
      type: "form",
      label: "表单",
      category: "input",
      pageTemplate: "form",
      requiredCapability: null,
      fields: [
        { key: "options", label: "选项", kind: "options", max: 5 },
        { key: "fields", label: "表单字段", kind: "fields", max: 5 },
      ],
    };
    render(
      <InspectorHarness
        item={structured}
        initialConfig={{
          options: [{ value: "normal", label: "正常", script: "ignored" }],
          fields: [{
            key: "note",
            label: "说明",
            type: "text",
            required: false,
            legacyRequestUrl: "https://legacy.invalid/action",
          }],
        }}
      />,
    );

    const alerts = screen.getAllByRole("alert").map((alert) => alert.textContent).join("；");
    expect(alerts).toContain("选项格式错误");
    expect(alerts).toContain("表单字段格式错误");
  });

  it("keeps delete as an explicit parent action", () => {
    const onDelete = vi.fn();
    render(
      <NodeInspector
        catalog={catalogItem}
        node={{ nodeId: "photo-1", type: "photo_capture", config: {} }}
        onChange={vi.fn()}
        onDelete={onDelete}
      />,
    );

    screen.getByRole("button", { name: "删除节点" }).click();
    expect(onDelete).toHaveBeenCalledTimes(1);
  });
});
