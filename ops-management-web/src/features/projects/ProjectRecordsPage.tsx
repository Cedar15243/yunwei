import {
  AlertTriangle,
  CheckCircle2,
  Cloud,
  ExternalLink,
  FolderClock,
  RefreshCw,
  ShieldCheck,
  XCircle,
} from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import type { ManagementApi, Project, ProjectRecord, TaskSummary } from "../../api/management-api";
import { formatTime, statusLabel } from "../dashboard/DashboardPage";
import "./project-records.css";

export function ProjectRecordsPage({
  api,
  onOpenTask,
}: {
  api: ManagementApi;
  onOpenTask: (taskId: string) => void;
}) {
  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState("");
  const [record, setRecord] = useState<ProjectRecord | null>(null);
  const [projectsLoading, setProjectsLoading] = useState(true);
  const [recordLoading, setRecordLoading] = useState(false);
  const [projectsError, setProjectsError] = useState("");
  const [recordError, setRecordError] = useState("");
  const [projectsReload, setProjectsReload] = useState(0);
  const [recordReload, setRecordReload] = useState(0);

  useEffect(() => {
    let active = true;
    setProjectsLoading(true);
    setProjectsError("");
    api.getProjects().then((items) => {
      if (!active) return;
      setProjects(items);
      setSelectedProjectId((current) => current && items.some((item) => item.id === current)
        ? current
        : items[0]?.id ?? "");
    }).catch((cause) => {
      if (active) setProjectsError(errorMessage(cause, "无法读取项目目录。"));
    }).finally(() => {
      if (active) setProjectsLoading(false);
    });
    return () => { active = false; };
  }, [api, projectsReload]);

  useEffect(() => {
    if (!selectedProjectId) {
      setRecord(null);
      return;
    }
    let active = true;
    setRecordLoading(true);
    setRecordError("");
    setRecord(null);
    api.getProjectRecord(selectedProjectId).then((value) => {
      if (active) setRecord(value);
    }).catch((cause) => {
      if (active) setRecordError(errorMessage(cause, "无法读取项目记录。"));
    }).finally(() => {
      if (active) setRecordLoading(false);
    });
    return () => { active = false; };
  }, [api, selectedProjectId, recordReload]);

  return (
    <section className="page-stack project-record-workbench">
      <header className="workspace-actions">
        <div><h2>项目记忆</h2><p>查看已同步到云端的任务、人工确认摘要、事实、风险和 Skill 版本。</p></div>
        <button className="secondary-button" onClick={() => { setProjectsReload((value) => value + 1); setRecordReload((value) => value + 1); }} type="button"><RefreshCw size={16} />刷新</button>
      </header>

      {projectsLoading ? <section className="notice">正在读取项目目录…</section> : null}
      {!projectsLoading && projectsError ? <RetryNotice error={projectsError} label="重试项目目录" onRetry={() => setProjectsReload((value) => value + 1)} /> : null}
      {!projectsLoading && !projectsError && projects.length === 0 ? <section className="workflow-empty"><FolderClock size={24} /><strong>当前权限范围内暂无项目记录。</strong></section> : null}
      {!projectsLoading && !projectsError && projects.length > 0 ? (
        <div className="project-record-layout">
          <aside aria-label="项目目录" className="project-record-directory">
            <div className="project-record-pane-title">项目目录</div>
            {projects.map((project) => (
              <button aria-pressed={project.id === selectedProjectId} className={project.id === selectedProjectId ? "selected" : ""} key={project.id} onClick={() => setSelectedProjectId(project.id)} type="button">
                <span className="app-icon"><FolderClock size={17} /></span>
                <span><strong>{project.title || "未命名项目"}</strong><small>{project.summary || "尚无项目摘要"}</small></span>
                <time>{formatTime(project.updated_at)}</time>
              </button>
            ))}
          </aside>

          <section aria-label="项目云端记录" className="project-record-detail">
            {recordLoading ? <div className="project-record-state">正在读取项目记录…</div> : null}
            {!recordLoading && recordError ? <RetryNotice error={recordError} label="重试项目记录" onRetry={() => setRecordReload((value) => value + 1)} /> : null}
            {!recordLoading && !recordError && record ? <ProjectRecordDetail onOpenTask={onOpenTask} record={record} /> : null}
          </section>
        </div>
      ) : null}
    </section>
  );
}

function ProjectRecordDetail({ record, onOpenTask }: { record: ProjectRecord; onOpenTask: (taskId: string) => void }) {
  const memory = record.latestMemory;
  return (
    <>
      <header className="project-record-heading">
        <div><h3>{record.project.title || "未命名项目"}</h3><p>{record.project.summary || "尚无项目摘要"}</p></div>
        <span className={`status ${record.project.status}`}>{projectStatusLabel(record.project.status)}</span>
      </header>

      <section className="project-memory-section">
        <header><span><Cloud size={17} /><strong>人工确认项目记忆</strong></span>{memory ? <span className="status active">项目记忆 v{memory.revision}</span> : null}</header>
        {memory ? <>
          <p className="project-memory-summary">{memory.summary}</p>
          <div className="project-memory-groups">
            <MemoryGroup icon={<CheckCircle2 size={16} />} items={memory.confirmedFacts} title="已确认事实" />
            <MemoryGroup icon={<XCircle size={16} />} items={memory.excludedFacts} title="已排除事实" />
            <MemoryGroup icon={<AlertTriangle size={16} />} items={memory.risks} title="遗留风险" />
          </div>
          <small className="project-memory-updated">同步时间 {formatTime(memory.updatedAt)}</small>
        </> : <p className="project-memory-empty">尚无人工确认并同步的项目结束摘要。</p>}
      </section>

      <section className="project-skill-section">
        <header><ShieldCheck size={17} /><strong>历史 Skill 版本</strong></header>
        <div className="project-skill-list">{record.skillVersions.length ? record.skillVersions.map((version) => <code key={version}>{version}</code>) : <span>尚无已同步 Skill 版本</span>}</div>
      </section>

      <section className="project-task-section">
        <header><FolderClock size={17} /><strong>项目任务</strong><span>{record.tasks.length}</span></header>
        <div className="project-task-list">{record.tasks.length ? record.tasks.map((task) => <ProjectTask key={task.id} onOpenTask={onOpenTask} task={task} />) : <p className="project-memory-empty">当前项目尚无已同步任务。</p>}</div>
      </section>
    </>
  );
}

function MemoryGroup({ icon, title, items }: { icon: ReactNode; title: string; items: string[] }) {
  return <section><header>{icon}<strong>{title}</strong></header>{items.length ? <ul>{items.map((item) => <li key={item}>{item}</li>)}</ul> : <p>无</p>}</section>;
}

function ProjectTask({ task, onOpenTask }: { task: TaskSummary; onOpenTask: (taskId: string) => void }) {
  return <article aria-label={task.title || "未命名任务"} className="project-task-row"><span><strong>{task.title || "未命名任务"}</strong><small>{task.current_step || "等待现场输入"}</small></span><span className={`status ${task.status}`}>{statusLabel(task.status)}</span><time>{formatTime(task.updated_at)}</time><button aria-label="查看任务" className="icon-button" onClick={() => onOpenTask(task.id)} title="查看任务" type="button"><ExternalLink size={16} /></button></article>;
}

function RetryNotice({ error, label, onRetry }: { error: string; label: string; onRetry: () => void }) {
  return <div className="notice error project-record-retry" role="alert"><span>{error}</span><button className="secondary-button" onClick={onRetry} type="button"><RefreshCw size={15} />{label}</button></div>;
}

function projectStatusLabel(status: string): string {
  if (status === "active") return "进行中";
  if (status === "completed") return "已完成";
  if (status === "closed") return "已关闭";
  return status || "状态未知";
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}
