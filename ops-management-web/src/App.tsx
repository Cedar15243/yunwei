import { FormEvent, useEffect, useState } from "react";
import { Activity, Camera, ClipboardList, MonitorSmartphone, Users, Workflow } from "lucide-react";
import type { ManagementApi } from "./api/management-api";
import { DashboardPage } from "./features/dashboard/DashboardPage";
import { DevicesPage } from "./features/devices/DevicesPage";
import { MediaCenterPage } from "./features/media/MediaCenterPage";
import { TaskListPage } from "./features/tasks/TaskListPage";
import { TaskTimelinePage } from "./features/tasks/TaskTimelinePage";
import { FieldAppsPage } from "./features/workflows/FieldAppsPage";
import "./styles/app.css";

type Section = "工作台" | "项目与任务" | "现场应用" | "媒体中心" | "人员与设备";
const navigation: Array<{ label: Section; icon: typeof Activity }> = [{ label: "工作台", icon: Activity }, { label: "项目与任务", icon: ClipboardList }, { label: "现场应用", icon: Workflow }, { label: "媒体中心", icon: Camera }, { label: "人员与设备", icon: Users }];

export type WebAuth = { restoreSession: () => Promise<boolean>; signIn: (email: string, password: string) => Promise<void> };

export function App({ initialAuthenticated = false, api, auth }: { initialAuthenticated?: boolean; api?: ManagementApi; auth?: WebAuth }) {
  const [authenticated, setAuthenticated] = useState(initialAuthenticated); const [section, setSection] = useState<Section>("工作台"); const [taskId, setTaskId] = useState(""); const [email, setEmail] = useState(""); const [password, setPassword] = useState(""); const [loginError, setLoginError] = useState(""); const [signingIn, setSigningIn] = useState(false);
  useEffect(() => { let alive = true; auth?.restoreSession().then((active) => alive && setAuthenticated(active)).catch(() => undefined); return () => { alive = false; }; }, [auth]);
  async function signIn(event: FormEvent) { event.preventDefault(); if (!auth) { setLoginError("管理服务尚未完成授权配置。"); return; } setSigningIn(true); setLoginError(""); try { await auth.signIn(email, password); setAuthenticated(true); } catch (cause) { setLoginError(cause instanceof Error ? cause.message : "登录失败，请核对账号和密码。"); } finally { setSigningIn(false); } }
  if (!authenticated) return <main className="login-page"><section className="login-panel"><div className="brand-mark"><MonitorSmartphone size={22} /> D</div><p className="eyebrow">DINGDANG OPS</p><h1>叮当 AI 运维管理平台</h1><p className="login-copy">登录后查看项目内的现场任务、证据与维修记录。</p><form onSubmit={signIn}><label>账号邮箱<input aria-label="账号邮箱" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} required /></label><label>登录密码<input aria-label="登录密码" type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required /></label>{loginError ? <p className="form-error" role="alert">{loginError}</p> : null}<button type="submit" disabled={signingIn}>{signingIn ? "正在验证" : "登录工作台"}</button></form></section></main>;
  return <div className="shell"><aside className="nav"><div className="nav-brand"><span className="brand-mark"><MonitorSmartphone size={18} /> D</span><strong>叮当运维</strong></div><nav aria-label="主导航">{navigation.map(({ label, icon: Icon }) => <a key={label} href={`#${label}`} className={section === label ? "active" : ""} onClick={(event) => { event.preventDefault(); setSection(label); setTaskId(""); }}><Icon size={18} />{label}</a>)}</nav><div className="nav-foot"><span className="presence" />云端管理服务</div></aside><main className="workspace"><header className="command-bar"><div><span className="eyebrow">现场运维工作台</span><h1>{taskId ? "任务时间线" : section}</h1></div><div className="operator"><span className="presence" />已授权访问</div></header><section className="content">{api ? taskId ? <TaskTimelinePage taskId={taskId} api={api} onBack={() => setTaskId("")} /> : <Page section={section} api={api} onOpenTask={setTaskId} /> : <section className="notice error">管理服务尚未完成授权配置，无法读取任务数据。</section>}</section></main></div>;
}

function Page({ section, api, onOpenTask }: { section: Section; api: ManagementApi; onOpenTask: (taskId: string) => void }) { if (section === "工作台") return <DashboardPage api={api} onOpenTask={onOpenTask} />; if (section === "项目与任务") return <TaskListPage api={api} onOpenTask={onOpenTask} />; if (section === "现场应用") return <FieldAppsPage api={api} onOpenWorkflow={() => undefined} />; if (section === "媒体中心") return <MediaCenterPage api={api} />; return <DevicesPage api={api} />; }
