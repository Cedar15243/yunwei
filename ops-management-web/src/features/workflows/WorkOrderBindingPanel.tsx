import { useEffect, useState } from "react";
import { ClipboardList, RefreshCw, Route, X } from "lucide-react";
import { ManagementApiError, type ManagementApi } from "../../api/management-api";
import type {
  WorkOrder,
  WorkOrderWorkflowResolution,
  WorkflowBindingMode,
  WorkflowBindingSource,
} from "../../api/workflow-types";

export type WorkOrderBindingApi = Pick<
  ManagementApi,
  "getWorkOrders" | "resolveWorkOrderWorkflow"
>;

export function WorkOrderBindingPanel({ api }: { api: WorkOrderBindingApi }) {
  const [orders, setOrders] = useState<WorkOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [reloadToken, setReloadToken] = useState(0);
  const [selected, setSelected] = useState<WorkOrder | null>(null);
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [idempotencyKey, setIdempotencyKey] = useState("");
  const [resolving, setResolving] = useState(false);
  const [commandError, setCommandError] = useState("");
  const [resolutions, setResolutions] = useState<Record<string, WorkOrderWorkflowResolution>>({});

  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError("");
    api.getWorkOrders({ limit: 50 })
      .then((items) => {
        if (active) setOrders(items);
      })
      .catch((cause: unknown) => {
        if (!active) return;
        setOrders([]);
        setLoadError(cause instanceof Error ? cause.message : "无法读取工单。");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [api, reloadToken]);

  function openResolution(order: WorkOrder) {
    if (!order.id || workOrderHasStarted(order)) return;
    setSelected(order);
    setReason("");
    setConfirmation("");
    setCommandError("");
    setIdempotencyKey(newIdempotencyKey("resolve", order.id));
  }

  function closeResolution() {
    if (resolving) return;
    setSelected(null);
    setReason("");
    setConfirmation("");
    setCommandError("");
    setIdempotencyKey("");
  }

  async function resolveWorkflow() {
    if (!selected?.id || reason.trim().length < 3 || confirmation !== "RESOLVE_WORKFLOW") return;
    const operationKey = idempotencyKey || newIdempotencyKey("resolve", selected.id);
    if (!idempotencyKey) setIdempotencyKey(operationKey);
    setResolving(true);
    setCommandError("");
    try {
      const result = await api.resolveWorkOrderWorkflow(selected.id, {
        reason: reason.trim(),
        idempotencyKey: operationKey,
        assignedProfileId: null,
        assignedDeviceId: null,
      });
      setResolutions((current) => ({ ...current, [selected.id!]: result }));
      setSelected(null);
      setReason("");
      setConfirmation("");
      setIdempotencyKey("");
    } catch (cause) {
      setCommandError(bindingErrorText(cause));
    } finally {
      setResolving(false);
    }
  }

  const canResolve = Boolean(selected?.id) && !resolving && reason.trim().length >= 3 &&
    confirmation === "RESOLVE_WORKFLOW";

  return (
    <section className="workflow-ops-panel work-order-binding-panel">
      <header className="workflow-ops-heading">
        <div>
          <span className="eyebrow">任务分配</span>
          <h3><ClipboardList size={17} />工单绑定</h3>
        </div>
        <small>{orders.length} 张工单</small>
      </header>
      {loading ? <div className="workflow-ops-state">正在读取工单…</div> : null}
      {!loading && loadError ? (
        <div className="workflow-ops-state error" role="alert">
          <span>{loadError}</span>
          <button className="secondary-button" onClick={() => setReloadToken((value) => value + 1)} type="button">
            <RefreshCw size={15} />重试
          </button>
        </div>
      ) : null}
      {!loading && !loadError && orders.length === 0 ? <div className="workflow-ops-state">暂无可管理工单</div> : null}
      {!loading && !loadError && orders.length > 0 ? (
        <div className="work-order-table-wrap">
          <table className="work-order-table">
            <thead>
              <tr><th>工单</th><th>状态</th><th>工作流</th><th>操作</th></tr>
            </thead>
            <tbody>
              {orders.map((order, index) => {
                const key = order.id ?? `order-${index}`;
                const resolution = order.id ? resolutions[order.id] : undefined;
                return (
                  <tr key={key}>
                    <td>
                      <strong>{order.title ?? "未命名工单"}</strong>
                      <small>{[order.sourceSystem, order.externalWorkOrderId].filter(Boolean).join(" · ") || "内部工单"}</small>
                    </td>
                    <td>{workOrderStatusLabel(order)}</td>
                    <td>
                      {resolution ? <ResolutionSummary resolution={resolution} /> : bindingStatusLabel(order)}
                    </td>
                    <td>
                      {workOrderHasStarted(order) ? (
                        <span className="workflow-locked">已开始，禁止换版</span>
                      ) : order.status === "cancelled" ? (
                        <span className="workflow-locked">已取消</span>
                      ) : order.id ? (
                        <button className="secondary-button compact-button" onClick={() => openResolution(order)} type="button">
                          <Route size={15} />解析工作流
                        </button>
                      ) : <span className="workflow-locked">工单标识无效</span>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      ) : null}

      {selected ? (
        <div className="dialog-backdrop" role="presentation">
          <section aria-labelledby="resolve-workflow-title" aria-modal="true" className="workflow-dialog workflow-resolution-dialog" role="dialog">
            <header>
              <div>
                <span className="eyebrow">服务端规则解析</span>
                <h2 id="resolve-workflow-title">{selected.title ?? "解析工单工作流"}</h2>
              </div>
              <button aria-label="关闭解析窗口" className="icon-button" disabled={resolving} onClick={closeResolution} title="关闭" type="button"><X size={18} /></button>
            </header>
            <div className="workflow-command-form">
              <label>
                解析理由
                <textarea aria-label="解析理由" maxLength={1000} onChange={(event) => setReason(event.target.value)} rows={3} value={reason} />
              </label>
              <label>
                确认词
                <input aria-label="确认词" autoComplete="off" onChange={(event) => setConfirmation(event.target.value)} value={confirmation} />
                <small>输入 RESOLVE_WORKFLOW</small>
              </label>
              {commandError ? <div className="workflow-command-errors" role="alert">{commandError}</div> : null}
            </div>
            <footer>
              <button className="secondary-button" disabled={resolving} onClick={closeResolution} type="button">取消</button>
              <button className="primary-button" disabled={!canResolve} onClick={resolveWorkflow} type="button">
                <Route size={16} />{resolving ? "正在解析" : "确认解析"}
              </button>
            </footer>
          </section>
        </div>
      ) : null}
    </section>
  );
}

function ResolutionSummary({ resolution }: { resolution: WorkOrderWorkflowResolution }) {
  if (resolution.kind === "conflict") {
    return <span className="binding-conflict">规则冲突：{resolution.conflictRuleIds.join("、") || "候选规则不可用"}</span>;
  }
  return (
    <span className="binding-resolution">
      <strong>{bindingModeLabel(resolution.mode)} · {bindingSourceLabel(resolution.resolutionSource)}</strong>
      <small>{resolution.assignmentId ? "已生成眼镜分配" : "未生成眼镜分配"}</small>
    </span>
  );
}

function bindingStatusLabel(order: WorkOrder): string {
  if (order.bindingStatus === "conflict") return "规则冲突";
  if (order.bindingStatus === "unsupported") return "当前不支持";
  if (order.bindingStatus === "resolved" && order.bindingMode) {
    return `${bindingModeLabel(order.bindingMode)} · ${bindingSourceLabel(order.bindingSource)}`;
  }
  return "待解析";
}

function bindingModeLabel(mode: WorkflowBindingMode): string {
  return ({ required: "必须执行", optional: "用户可选", none: "普通任务" })[mode];
}

function bindingSourceLabel(source: WorkflowBindingSource | null): string {
  if (!source) return "默认路径";
  return ({
    manual: "人工指定",
    trusted_external: "受信外部映射",
    project: "项目规则",
    asset_order_type: "资产/工单规则",
    organization_default: "组织默认规则",
  })[source];
}

function workOrderHasStarted(order: WorkOrder): boolean {
  return order.status === "in_progress" || order.status === "completed" || order.status === "closed";
}

function workOrderStatusLabel(order: WorkOrder): string {
  if (!order.status) return "状态未知";
  return ({
    received: "待接收",
    accepted: "已接收",
    in_progress: "执行中",
    completed: "已完成",
    closed: "已关闭",
    cancelled: "已取消",
  })[order.status];
}

function bindingErrorText(cause: unknown): string {
  if (!(cause instanceof ManagementApiError)) {
    return cause instanceof Error && cause.message ? cause.message : "工单解析暂时失败，未改变当前绑定，可重试。";
  }
  if (cause.code === "workflow_already_started") return "工单已经开始，服务端拒绝更换工作流版本。";
  if (cause.code === "work_order_unassigned") return "工单尚未分配现场工程师，不能创建眼镜分配。";
  if (cause.code === "work_order_project_required") return "工单尚未绑定项目，不能创建工作流执行。";
  return "工单解析暂时失败，未改变当前绑定，可重试。";
}

function newIdempotencyKey(operation: string, targetId: string): string {
  const random = typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${operation}:${targetId}:${random}`;
}
