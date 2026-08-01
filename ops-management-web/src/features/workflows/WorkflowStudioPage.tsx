import { useEffect, useState } from "react";
import {
  ArrowLeft,
  Braces,
  Plus,
  RefreshCw,
  RotateCcw,
} from "lucide-react";
import type { ManagementApi } from "../../api/management-api";
import type {
  WorkflowCatalog,
  WorkflowDefinition,
  WorkflowNodeCatalogItem,
  WorkflowVersion,
} from "../../api/workflow-types";
import { WorkflowCanvas } from "./WorkflowCanvas";
import { Air3HudPreview } from "./Air3HudPreview";
import { NodeInspector } from "./NodeInspector";
import {
  acceptSavedWorkflow,
  addWorkflowNode,
  connectWorkflowNodes,
  createWorkflowEditorState,
  deleteWorkflowNode,
  deleteWorkflowTransition,
  discardWorkflowChanges,
  moveWorkflowNode,
  selectWorkflowNode,
  updateWorkflowNodeConfig,
  WorkflowEditorError,
  type WorkflowEditorState,
} from "./workflow-editor-state";
import { WorkflowCommandBar } from "./WorkflowCommandBar";
import { WorkflowVersionsPanel } from "./WorkflowVersionsPanel";
import { WorkOrderBindingPanel } from "./WorkOrderBindingPanel";
import "./workflow-operations.css";

export type WorkflowStudioApi = Pick<
  ManagementApi,
  | "getWorkflow"
  | "getWorkflowCatalog"
  | "saveWorkflowDraft"
  | "validateWorkflow"
  | "publishWorkflow"
  | "getWorkflowVersions"
  | "getWorkOrders"
  | "resolveWorkOrderWorkflow"
>;

type LoadedStudio = {
  workflow: WorkflowDefinition;
  catalog: WorkflowCatalog;
};

const categoryLabels: Record<WorkflowNodeCatalogItem["category"], string> = {
  flow: "流程控制",
  content: "作业内容",
  evidence: "现场证据",
  input: "现场输入",
  assist: "协作与 AI",
  integration: "系统连接",
};

export function WorkflowStudioPage({
  api,
  workflowId,
  onBack,
}: {
  api: WorkflowStudioApi;
  workflowId: string;
  onBack: () => void;
}) {
  const [loaded, setLoaded] = useState<LoadedStudio | null>(null);
  const [editor, setEditor] = useState<WorkflowEditorState | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [editorError, setEditorError] = useState("");
  const [reloadVersion, setReloadVersion] = useState(0);
  const [versionsReloadToken, setVersionsReloadToken] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    Promise.all([api.getWorkflow(workflowId), api.getWorkflowCatalog()])
      .then(([workflow, catalog]) => {
        if (!active) return;
        setLoaded({ workflow, catalog });
        setEditor(createWorkflowEditorState(workflow));
      })
      .catch((cause: unknown) => {
        if (active) {
          setLoaded(null);
          setEditor(null);
          setError(cause instanceof Error ? cause.message : "无法读取工作流。");
        }
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [api, workflowId, reloadVersion]);

  if (loading) {
    return <section className="notice workflow-loading">正在读取工作流…</section>;
  }
  if (error || !loaded || !editor) {
    return (
      <section className="notice error workflow-retry" role="alert">
        <span>{error || "工作流数据不完整。"}</span>
        <button className="secondary-button" onClick={() => setReloadVersion((value) => value + 1)} type="button">
          <RefreshCw size={16} />重试
        </button>
      </section>
    );
  }
  const activeEditor = editor;

  const selectedNode = activeEditor.draft.nodes.find(
    (node) => node.nodeId === activeEditor.selectedNodeId,
  ) ?? null;
  const selectedCatalog = selectedNode
    ? loaded.catalog.nodes.find((item) => item.type === selectedNode.type) ?? null
    : null;

  function update(
    action: (current: WorkflowEditorState) => WorkflowEditorState,
  ) {
    setEditor((current) => {
      if (!current) return current;
      try {
        setEditorError("");
        return action(current);
      } catch (cause) {
        setEditorError(editorErrorText(cause));
        return current;
      }
    });
  }

  function addNode(item: WorkflowNodeCatalogItem) {
    const index = activeEditor.draft.nodes.length - 2;
    update((current) =>
      addWorkflowNode(
        current,
        item.type,
        { x: 280 + (index % 2) * 240, y: 100 + Math.floor(index / 2) * 150 },
        { title: item.label },
      )
    );
  }

  function requestBack() {
    if (activeEditor.dirty && !window.confirm("未保存的修改将丢失，确认返回？")) return;
    onBack();
  }

  function acceptSave(savedWorkflow: WorkflowDefinition) {
    setLoaded((current) => current ? { ...current, workflow: savedWorkflow } : current);
    setEditor((current) => current ? acceptSavedWorkflow(current, savedWorkflow) : current);
  }

  function acceptPublication(version: WorkflowVersion) {
    setLoaded((current) => current
      ? {
        ...current,
        workflow: {
          ...current.workflow,
          status: "published",
          latest_version_number: Math.max(
            current.workflow.latest_version_number,
            version.version_number,
          ),
        },
      }
      : current);
    setVersionsReloadToken((value) => value + 1);
  }

  return (
    <section className="workflow-studio">
      <header className="studio-heading">
        <div className="studio-title">
          <button aria-label="返回现场应用" className="icon-button" onClick={requestBack} title="返回现场应用" type="button">
            <ArrowLeft size={18} />
          </button>
          <div>
            <span className="eyebrow">{loaded.workflow.workflow_key}</span>
            <h2>{loaded.workflow.title}</h2>
          </div>
          <span className={`status ${loaded.workflow.status}`}>{workflowStatusLabel(loaded.workflow.status)}</span>
        </div>
        <div className="studio-actions">
          <WorkflowCommandBar
            api={api}
            dirty={activeEditor.dirty}
            draft={activeEditor.draft}
            onPublished={acceptPublication}
            onSaved={acceptSave}
            workflow={loaded.workflow}
          />
          <button className="secondary-button" disabled={!activeEditor.dirty} onClick={() => update(discardWorkflowChanges)} type="button">
            <RotateCcw size={16} />撤销未保存修改
          </button>
        </div>
      </header>

      {editorError ? <div className="studio-error" role="alert">{editorError}</div> : null}

      <div className="workflow-studio-layout">
        <aside aria-label="工作流节点库" className="node-library">
          <div className="studio-pane-title">
            <span>节点库</span>
            <small>{loaded.catalog.nodes.length} 类</small>
          </div>
          {Object.entries(categoryLabels).map(([category, label]) => {
            const items = loaded.catalog.nodes.filter(
              (item) => item.category === category,
            );
            if (items.length === 0) return null;
            return (
              <section className="node-library-group" key={category}>
                <h3>{label}</h3>
                {items.map((item) => {
                  const fixed = item.type === "start" || item.type === "complete";
                  return fixed ? (
                    <div className="node-library-fixed" key={item.type}>
                      <Braces size={15} /><span>{item.label}</span><small>固定</small>
                    </div>
                  ) : (
                    <button aria-label={`添加${item.label}节点`} key={item.type} onClick={() => addNode(item)} type="button">
                      <Plus size={15} /><span>{item.label}</span>
                    </button>
                  );
                })}
              </section>
            );
          })}
        </aside>

        <WorkflowCanvas
          catalog={loaded.catalog.nodes}
          draft={activeEditor.draft}
          onConnect={(source, target) => update((current) => connectWorkflowNodes(current, source, target))}
          onDeleteNode={(nodeId) => update((current) => deleteWorkflowNode(current, nodeId))}
          onDeleteTransition={(transitionId) => update((current) => deleteWorkflowTransition(current, transitionId))}
          onMoveNode={(nodeId, position) => update((current) => moveWorkflowNode(current, nodeId, position))}
          onSelectNode={(nodeId) => update((current) => selectWorkflowNode(current, nodeId))}
          selectedNodeId={activeEditor.selectedNodeId}
        />

        <aside aria-label="节点属性" className="node-inspector">
          <div className="studio-pane-title">
            <span>节点属性</span>
            <small>{selectedCatalog?.label ?? "未选择"}</small>
          </div>
          {selectedNode && selectedCatalog ? (
            <>
              <NodeInspector
                catalog={selectedCatalog}
                node={selectedNode}
                onChange={(config) => update((current) => updateWorkflowNodeConfig(current, selectedNode.nodeId, config))}
                onDelete={selectedNode.type !== "start" && selectedNode.type !== "complete"
                  ? () => update((current) => deleteWorkflowNode(current, selectedNode.nodeId))
                  : undefined}
              />
              <Air3HudPreview catalog={selectedCatalog} node={selectedNode} />
            </>
          ) : (
            <div className="node-inspector-empty">未选择节点</div>
          )}
        </aside>
      </div>

      <div className="workflow-ops-grid">
        <WorkflowVersionsPanel
          api={api}
          reloadToken={versionsReloadToken}
          workflowId={loaded.workflow.id}
        />
        <WorkOrderBindingPanel api={api} />
      </div>
    </section>
  );
}

function editorErrorText(cause: unknown): string {
  if (!(cause instanceof WorkflowEditorError)) return "无法修改当前工作流。";
  return ({
    fixed_node_required: "开始和完成节点不能新增或删除。",
    node_not_found: "节点已经不存在，请刷新工作流。",
    transition_not_found: "连接已经不存在，请刷新工作流。",
    complete_has_no_outgoing: "完成节点不能连接后续步骤。",
    start_has_no_incoming: "开始节点不能接收前序步骤。",
    self_connection_invalid: "节点不能连接到自身。",
  })[cause.code];
}

function workflowStatusLabel(status: WorkflowDefinition["status"]): string {
  return ({
    draft: "草稿",
    validating: "校验中",
    review_pending: "待审核",
    published: "已发布",
    deprecated: "已停用",
    archived: "已归档",
  })[status];
}
