import { useEffect, useState } from "react";
import { History, RefreshCw } from "lucide-react";
import type { ManagementApi } from "../../api/management-api";
import type { WorkflowVersion } from "../../api/workflow-types";

type WorkflowVersionsApi = Pick<ManagementApi, "getWorkflowVersions">;

export function WorkflowVersionsPanel({
  api,
  workflowId,
  reloadToken,
}: {
  api: WorkflowVersionsApi;
  workflowId: string;
  reloadToken: number;
}) {
  const [versions, setVersions] = useState<WorkflowVersion[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [retryToken, setRetryToken] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    api.getWorkflowVersions(workflowId)
      .then((items) => {
        if (active) setVersions(items);
      })
      .catch((cause: unknown) => {
        if (!active) return;
        setVersions([]);
        setError(cause instanceof Error ? cause.message : "无法读取工作流版本。");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [api, workflowId, reloadToken, retryToken]);

  return (
    <section className="workflow-ops-panel workflow-versions-panel">
      <header className="workflow-ops-heading">
        <div>
          <span className="eyebrow">版本仓库</span>
          <h3><History size={17} />不可变版本</h3>
        </div>
        <small>{versions.length} 个版本</small>
      </header>
      {loading ? <div className="workflow-ops-state">正在读取版本…</div> : null}
      {!loading && error ? (
        <div className="workflow-ops-state error" role="alert">
          <span>{error}</span>
          <button aria-label="重试版本列表" className="secondary-button" onClick={() => setRetryToken((value) => value + 1)} type="button">
            <RefreshCw size={15} />重试
          </button>
        </div>
      ) : null}
      {!loading && !error && versions.length === 0 ? (
        <div className="workflow-ops-state">尚未发布不可变版本</div>
      ) : null}
      {!loading && !error && versions.length > 0 ? (
        <div className="workflow-version-list">
          {versions.map((version) => (
            <article className="workflow-version-row" key={version.id}>
              <strong>v{version.version_number}</strong>
              <span className={`status ${version.status}`}>{versionStatusLabel(version.status)}</span>
              <span>最低客户端 {version.min_app_version_code}</span>
              <span>{version.required_capabilities.length > 0 ? version.required_capabilities.join("、") : "无额外能力"}</span>
              <code title={version.content_sha256}>{version.content_sha256.slice(0, 16)}</code>
              <time dateTime={version.published_at}>{formatDateTime(version.published_at)}</time>
            </article>
          ))}
        </div>
      ) : null}
    </section>
  );
}

function versionStatusLabel(status: WorkflowVersion["status"]): string {
  return ({ published: "已发布", deprecated: "已停用", revoked: "已撤销", archived: "已归档" })[status];
}

function formatDateTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}
