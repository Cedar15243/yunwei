import { useEffect, useState } from "react";
import { AlertTriangle, Glasses, ListChecks } from "lucide-react";
import type { Dashboard, ManagementApi } from "../../api/management-api";

export function DashboardPage({ api, onOpenTask }: { api: ManagementApi; onOpenTask: (taskId: string) => void }) {
  const [data, setData] = useState<Dashboard | null>(null);
  const [error, setError] = useState("");
  useEffect(() => { let alive = true; api.getDashboard().then((value) => alive && setData(value)).catch((cause: unknown) => alive && setError(cause instanceof Error ? cause.message : "无法读取工作台数据")); return () => { alive = false; }; }, [api]);
  if (error) return <section className="notice error">{error}</section>;
  if (!data) return <section className="notice">正在读取现场任务…</section>;
  const stats = [{ label: "执行中任务", value: data.activeTaskCount, icon: ListChecks }, { label: "在线眼镜", value: data.onlineDeviceCount, icon: Glasses }, { label: "同步异常", value: data.failedMediaCount, icon: AlertTriangle }];
  return <section className="page-stack"><div className="stat-row">{stats.map(({ label, value, icon: Icon }) => <div className="stat" key={label}><Icon size={18} /><span>{label}</span><strong>{value}</strong></div>)}</div><section className="surface"><div className="section-heading"><div><h2>最近更新</h2><p>按现场任务的最新活动排序。</p></div></div><div className="table">{data.recentTasks.length ? data.recentTasks.map((task) => <button className="row-button" key={task.id} onClick={() => onOpenTask(task.id)}><span>{task.title || "未命名任务"}</span><span className={`status ${task.status}`}>{statusLabel(task.status)}</span><time>{formatTime(task.updated_at)}</time></button>) : <div className="empty">暂无已同步的任务记录。</div>}</div></section></section>;
}

export function statusLabel(status: string) { return ({ active: "进行中", completed: "已完成", closed: "已关闭", aborted: "已终止" } as Record<string, string>)[status] ?? status; }
export function formatTime(value?: string) { return value ? new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value)) : "--"; }
