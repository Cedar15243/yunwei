import { type FormEvent, useEffect, useState } from "react";
import { Download, RefreshCw, RotateCcw, Search } from "lucide-react";
import type {
  ManagementApi,
  Project,
  TaskExportFilters,
  TaskListFilters,
  TaskStatus,
  TaskSummary,
} from "../../api/management-api";
import { formatTime, statusLabel } from "../dashboard/DashboardPage";

const PAGE_SIZE = 50;

export function TaskListPage({
  api,
  onOpenTask,
}: {
  api: ManagementApi;
  onOpenTask: (taskId: string) => void;
}) {
  const [tasks, setTasks] = useState<TaskSummary[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [status, setStatus] = useState<TaskStatus | "">("");
  const [projectId, setProjectId] = useState("");
  const [queryDraft, setQueryDraft] = useState("");
  const [appliedQuery, setAppliedQuery] = useState("");
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingNext, setLoadingNext] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState("");
  const [errorMode, setErrorMode] = useState<"initial" | "next" | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let alive = true;
    api.getProjects()
      .then((items) => {
        if (alive) setProjects(items);
      })
      .catch(() => {
        if (alive) setProjects([]);
      });
    return () => {
      alive = false;
    };
  }, [api]);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError("");
    setErrorMode(null);
    api.getTasks(taskFilters(status, projectId, appliedQuery))
      .then((result) => {
        if (!alive) return;
        setTasks(uniqueTasks(result.items));
        setNextCursor(result.nextCursor);
      })
      .catch((cause: unknown) => {
        if (!alive) return;
        setError(errorMessage(cause, "无法读取任务记录"));
        setErrorMode("initial");
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [api, status, projectId, appliedQuery, reloadKey]);

  function applySearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setAppliedQuery(queryDraft.trim());
    setReloadKey((value) => value + 1);
  }

  function resetFilters() {
    setStatus("");
    setProjectId("");
    setQueryDraft("");
    setAppliedQuery("");
    setReloadKey((value) => value + 1);
  }

  async function loadNextPage() {
    if (!nextCursor || loadingNext) return;
    setLoadingNext(true);
    setError("");
    setErrorMode(null);
    try {
      const result = await api.getTasks(taskFilters(status, projectId, appliedQuery, nextCursor));
      setTasks((current) => uniqueTasks([...current, ...result.items]));
      setNextCursor(result.nextCursor);
    } catch (cause) {
      setError(errorMessage(cause, "无法加载下一页任务"));
      setErrorMode("next");
    } finally {
      setLoadingNext(false);
    }
  }

  function retryFailedRequest() {
    if (errorMode === "next") {
      void loadNextPage();
      return;
    }
    setReloadKey((value) => value + 1);
  }

  async function exportRecords() {
    if (exporting) return;
    setExporting(true);
    setError("");
    setErrorMode(null);
    try {
      const blob = await api.exportTasks(exportFilters(status, projectId, appliedQuery));
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = "v9-task-records.csv";
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    } catch (cause) {
      setError(errorMessage(cause, "无法导出任务记录"));
      setErrorMode("initial");
    } finally {
      setExporting(false);
    }
  }

  return (
    <section className="page-stack task-record-workbench">
      <form className="filter-row task-filter-row" onSubmit={applySearch}>
        <label className="task-search-field">
          任务标题
          <input
            type="search"
            value={queryDraft}
            onChange={(event) => setQueryDraft(event.target.value)}
            placeholder="输入任务标题"
          />
        </label>
        <label>
          任务状态
          <select value={status} onChange={(event) => setStatus(event.target.value as TaskStatus | "")}>
            <option value="">全部状态</option>
            <option value="active">进行中</option>
            <option value="completed">已完成</option>
            <option value="closed">已关闭</option>
            <option value="aborted">已终止</option>
          </select>
        </label>
        <label>
          所属项目
          <select value={projectId} onChange={(event) => setProjectId(event.target.value)}>
            <option value="">全部项目</option>
            {projects.map((project) => (
              <option value={project.id} key={project.id}>{project.title}</option>
            ))}
          </select>
        </label>
        <div className="task-filter-actions">
          <button className="compact-command primary" type="submit" aria-label="搜索任务">
            <Search size={16} aria-hidden="true" />
            <span>搜索</span>
          </button>
          <button className="compact-command" type="button" onClick={resetFilters} aria-label="重置筛选">
            <RotateCcw size={16} aria-hidden="true" />
            <span>重置</span>
          </button>
          <button
            className="compact-command"
            type="button"
            onClick={() => void exportRecords()}
            disabled={exporting}
            aria-label="导出任务记录"
          >
            {exporting ? <RefreshCw className="spin" size={16} aria-hidden="true" /> : <Download size={16} aria-hidden="true" />}
            <span>{exporting ? "导出中" : "导出"}</span>
          </button>
        </div>
      </form>

      {error ? (
        <section className="notice error recoverable-notice" role="alert">
          <span>{error}</span>
          <button className="compact-command" type="button" onClick={retryFailedRequest}>重试</button>
        </section>
      ) : null}

      {loading && tasks.length === 0 ? (
        <section className="notice">正在加载任务记录...</section>
      ) : (
        <section className="surface">
          <div className="table">
            {tasks.length ? tasks.map((task) => (
              <button className="row-button task-row" key={task.id} onClick={() => onOpenTask(task.id)}>
                <span>
                  <strong>{task.title || "未命名任务"}</strong>
                  <small>{task.current_step || "等待现场输入"}</small>
                </span>
                <span className={`status ${task.status}`}>{statusLabel(task.status)}</span>
                <time>{formatTime(task.updated_at)}</time>
              </button>
            )) : <div className="empty">当前筛选条件下暂无任务。</div>}
          </div>
          {nextCursor ? (
            <div className="pagination-command-row">
              <button
                className="compact-command"
                type="button"
                onClick={() => void loadNextPage()}
                disabled={loadingNext}
                aria-label="加载下一页"
              >
                {loadingNext ? <RefreshCw className="spin" size={16} aria-hidden="true" /> : null}
                <span>{loadingNext ? "加载中" : "加载下一页"}</span>
              </button>
            </div>
          ) : null}
        </section>
      )}
    </section>
  );
}

function taskFilters(
  status: TaskStatus | "",
  projectId: string,
  query: string,
  before?: string,
): TaskListFilters {
  return {
    ...(status ? { status } : {}),
    ...(projectId ? { projectId } : {}),
    ...(query ? { query } : {}),
    ...(before ? { before } : {}),
    limit: PAGE_SIZE,
  };
}

function exportFilters(status: TaskStatus | "", projectId: string, query: string): TaskExportFilters {
  return {
    ...(status ? { status } : {}),
    ...(projectId ? { projectId } : {}),
    ...(query ? { query } : {}),
  };
}

function uniqueTasks(items: TaskSummary[]): TaskSummary[] {
  return Array.from(new Map(items.map((task) => [task.id, task])).values());
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error && cause.message ? cause.message : fallback;
}
