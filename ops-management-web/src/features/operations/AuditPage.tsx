import { ClipboardCheck, Filter, RefreshCw, ShieldAlert } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import type { AuditEvent, ManagementApi } from "../../api/management-api";
import { formatTime } from "../dashboard/DashboardPage";
import "./operations.css";

export function AuditPage({ api }: { api: ManagementApi }) {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [action, setAction] = useState("");
  const [targetType, setTargetType] = useState("");
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const initialLoad = useRef(false);

  const load = useCallback(async (before?: string) => {
    setLoading(true);
    setError("");
    try {
      const result = await api.getAuditEvents({
        action: action.trim() || undefined,
        targetType: targetType.trim() || undefined,
        limit: 50,
        before,
      });
      setEvents((current) => before ? [...current, ...result.items] : result.items);
      setNextCursor(result.nextCursor);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "审计服务暂不可用，请稍后重试。");
    } finally {
      setLoading(false);
    }
  }, [action, api, targetType]);

  useEffect(() => {
    if (initialLoad.current) return;
    initialLoad.current = true;
    void load();
  }, [load]);

  return (
    <section className="page-stack operations-page">
      <header className="workspace-actions">
        <div><h2>统一审计</h2><p>查看设备、任务、Skill、知识、工作流和声纹管理的服务端操作记录。</p></div>
        <span className="operations-caption"><ClipboardCheck size={16} />组织范围</span>
      </header>

      <section className="surface operations-filter">
        <div className="operations-filter-heading"><Filter size={16} /><strong>筛选审计事件</strong></div>
        <label>动作筛选<input aria-label="动作筛选" value={action} onChange={(event) => setAction(event.target.value)} placeholder="例如 voiceprint_revoked" /></label>
        <label>对象类型筛选<input aria-label="对象类型筛选" value={targetType} onChange={(event) => setTargetType(event.target.value)} placeholder="例如 workflow_version" /></label>
        <button className="primary-button" type="button" onClick={() => void load()}><Filter size={15} />查询审计</button>
      </section>

      {loading ? <section className="notice">正在读取审计记录…</section> : null}
      {!loading && error ? <section className="notice error operations-retry" role="alert"><span>{error}</span><button className="secondary-button" type="button" onClick={() => void load()}><RefreshCw size={15} />重试</button></section> : null}
      {!loading && !error && events.length === 0 ? <section className="notice">当前筛选条件没有审计记录。</section> : null}
      {!loading && !error && events.length > 0 ? (
        <section className="surface audit-table">
          <div aria-hidden="true" className="audit-row audit-head"><span>时间与操作者</span><span>动作</span><span>对象</span><span>请求摘要</span></div>
          {events.map((event) => (
            <article className="audit-row" key={event.id}>
              <span><strong>{formatTime(event.createdAt)}</strong><small>{event.actor.displayName || "系统"}</small></span>
              <span><strong>{actionLabel(event.action)}</strong><small>{event.action}</small></span>
              <span><strong>{targetLabel(event.targetType)} {event.targetId}</strong><small>{event.targetType}</small></span>
              <code>{JSON.stringify(event.metadata ?? {})}</code>
            </article>
          ))}
        </section>
      ) : null}
      {!loading && !error && nextCursor ? <button className="secondary-button operations-more" type="button" onClick={() => void load(nextCursor)}><ShieldAlert size={15} />加载更早记录</button> : null}
    </section>
  );
}

function actionLabel(action: string): string {
  return {
    media_retry_requested: "媒体重试请求",
    voiceprint_revoked: "撤销声纹",
    skill_version_published: "发布 Skill",
    knowledge_version_published: "发布知识版本",
    workflow_published: "发布工作流",
    device_revoked: "撤销设备",
  }[action] ?? action;
}

function targetLabel(targetType: string): string {
  return {
    media_asset: "媒体",
    voiceprint_profile: "声纹",
    skill_version: "Skill 版本",
    knowledge_version: "知识版本",
    workflow_version: "工作流版本",
    glasses_device: "设备",
  }[targetType] ?? targetType;
}
