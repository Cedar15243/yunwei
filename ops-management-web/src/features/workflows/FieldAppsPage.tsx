import { useEffect, useState } from "react";
import { ChevronRight, Plus, RefreshCw, Workflow } from "lucide-react";
import type { ManagementApi } from "../../api/management-api";
import type { FieldApp, WorkflowDefinition } from "../../api/workflow-types";
import { WorkflowCreateDialog } from "./WorkflowCreateDialog";

export type WorkflowDirectoryApi = Pick<
  ManagementApi,
  "getFieldApps" | "createFieldApp" | "getWorkflows" | "createWorkflow"
>;

type DialogKind = "field-app" | "workflow" | null;

export function FieldAppsPage({
  api,
  onOpenWorkflow,
}: {
  api: WorkflowDirectoryApi;
  onOpenWorkflow: (workflowId: string) => void;
}) {
  const [apps, setApps] = useState<FieldApp[]>([]);
  const [selectedAppId, setSelectedAppId] = useState("");
  const [workflows, setWorkflows] = useState<WorkflowDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [workflowsLoading, setWorkflowsLoading] = useState(false);
  const [error, setError] = useState("");
  const [workflowError, setWorkflowError] = useState("");
  const [reloadVersion, setReloadVersion] = useState(0);
  const [workflowReloadVersion, setWorkflowReloadVersion] = useState(0);
  const [dialog, setDialog] = useState<DialogKind>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    api.getFieldApps()
      .then((items) => {
        if (!active) return;
        setApps(items);
        setSelectedAppId((current) =>
          current && items.some((item) => item.id === current) ? current : items[0]?.id ?? ""
        );
      })
      .catch((cause: unknown) => {
        if (active) setError(cause instanceof Error ? cause.message : "无法读取现场应用。");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [api, reloadVersion]);

  useEffect(() => {
    if (!selectedAppId) {
      setWorkflows([]);
      return;
    }
    let active = true;
    setWorkflowsLoading(true);
    setWorkflowError("");
    api.getWorkflows(selectedAppId)
      .then((items) => {
        if (active) setWorkflows(items);
      })
      .catch((cause: unknown) => {
        if (active) setWorkflowError(cause instanceof Error ? cause.message : "无法读取工作流。");
      })
      .finally(() => {
        if (active) setWorkflowsLoading(false);
      });
    return () => {
      active = false;
    };
  }, [api, selectedAppId, workflowReloadVersion]);

  const selectedApp = apps.find((item) => item.id === selectedAppId) ?? null;

  async function createFieldApp(command: Parameters<ManagementApi["createFieldApp"]>[0]) {
    const created = await api.createFieldApp(command);
    setApps((current) => [created, ...current.filter((item) => item.id !== created.id)]);
    setSelectedAppId(created.id);
    setDialog(null);
  }

  async function createWorkflow(command: Parameters<ManagementApi["createWorkflow"]>[1]) {
    if (!selectedApp) throw new Error("请先选择现场应用。");
    const created = await api.createWorkflow(selectedApp.id, command);
    setWorkflows((current) => [created, ...current.filter((item) => item.id !== created.id)]);
    setDialog(null);
    onOpenWorkflow(created.id);
  }

  if (loading) return <section className="notice workflow-loading">正在读取现场应用…</section>;
  if (error) {
    return (
      <section className="notice error workflow-retry" role="alert">
        <span>{error}</span>
        <button className="secondary-button" onClick={() => setReloadVersion((value) => value + 1)} type="button">
          <RefreshCw size={16} />重试
        </button>
      </section>
    );
  }

  return (
    <section className="workflow-directory page-stack">
      <div className="workspace-actions">
        <div>
          <h2>企业现场应用</h2>
          <p>{apps.length} 个应用 · {workflows.length} 个当前流程</p>
        </div>
        <button className="primary-button" onClick={() => setDialog("field-app")} type="button">
          <Plus size={17} />新建现场应用
        </button>
      </div>

      {apps.length === 0 ? (
        <section className="workflow-empty">
          <Workflow size={24} />
          <strong>尚未创建现场应用。</strong>
        </section>
      ) : (
        <div className="workflow-directory-grid">
          <aside aria-label="现场应用列表" className="field-app-list">
            <div className="pane-title">应用目录</div>
            {apps.map((item) => (
              <button
                aria-pressed={item.id === selectedAppId}
                className={item.id === selectedAppId ? "field-app-row selected" : "field-app-row"}
                key={item.id}
                onClick={() => setSelectedAppId(item.id)}
                type="button"
              >
                <span className="app-icon"><Workflow size={17} /></span>
                <span>
                  <strong>{item.name}</strong>
                  <small>{item.app_key}</small>
                </span>
                <span className={`status ${item.status}`}>{fieldAppStatusLabel(item.status)}</span>
              </button>
            ))}
          </aside>

          <section aria-label="工作流列表" className="workflow-list-pane">
            <div className="pane-heading">
              <div>
                <h3>{selectedApp?.name ?? "工作流"}</h3>
                <p>{selectedApp?.description || "暂无应用说明"}</p>
              </div>
              <button className="secondary-button" onClick={() => setDialog("workflow")} type="button">
                <Plus size={16} />新建工作流
              </button>
            </div>
            {workflowsLoading ? <div className="workflow-pane-state">正在读取工作流…</div> : null}
            {!workflowsLoading && workflowError ? (
              <div className="workflow-pane-state error" role="alert">
                <span>{workflowError}</span>
                <button className="secondary-button" onClick={() => setWorkflowReloadVersion((value) => value + 1)} type="button">
                  <RefreshCw size={16} />重试
                </button>
              </div>
            ) : null}
            {!workflowsLoading && !workflowError && workflows.length === 0 ? (
              <div className="workflow-pane-state">当前应用尚未创建工作流。</div>
            ) : null}
            {!workflowsLoading && !workflowError && workflows.length > 0 ? (
              <div className="workflow-table">
                <div className="workflow-table-head" aria-hidden="true">
                  <span>工作流</span><span>状态</span><span>版本</span><span>更新</span><span />
                </div>
                {workflows.map((item) => (
                  <button className="workflow-row" key={item.id} onClick={() => onOpenWorkflow(item.id)} type="button">
                    <span><strong>{item.title}</strong><small>{item.workflow_key}</small></span>
                    <span className={`status ${item.status}`}>{workflowStatusLabel(item.status)}</span>
                    <span>v{item.latest_version_number}</span>
                    <time>{formatDate(item.updated_at)}</time>
                    <ChevronRight size={17} />
                  </button>
                ))}
              </div>
            ) : null}
          </section>
        </div>
      )}

      {dialog === "field-app" ? (
        <WorkflowCreateDialog kind="field-app" onCancel={() => setDialog(null)} onSubmit={createFieldApp} />
      ) : null}
      {dialog === "workflow" && selectedApp ? (
        <WorkflowCreateDialog
          fieldAppName={selectedApp.name}
          kind="workflow"
          onCancel={() => setDialog(null)}
          onSubmit={createWorkflow}
        />
      ) : null}
    </section>
  );
}

function fieldAppStatusLabel(status: FieldApp["status"]): string {
  return ({ draft: "应用草稿", review_pending: "待审核", published: "已发布", deprecated: "已停用", archived: "已归档" })[status];
}

function workflowStatusLabel(status: WorkflowDefinition["status"]): string {
  return ({ draft: "草稿", validating: "校验中", review_pending: "待审核", published: "已发布", deprecated: "已停用", archived: "已归档" })[status];
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" })
    .format(new Date(value));
}
