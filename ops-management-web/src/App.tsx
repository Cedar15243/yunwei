import { lazy, Suspense, type FormEvent, useEffect, useState } from "react";
import { Activity, BrainCircuit, Camera, ClipboardList, Fingerprint, Library, MonitorSmartphone, ScrollText, Settings2, SlidersHorizontal, Users, Workflow } from "lucide-react";
import type { ManagementApi } from "./api/management-api";
import { DashboardPage } from "./features/dashboard/DashboardPage";
import { DevicesPage } from "./features/devices/DevicesPage";
import { MediaCenterPage } from "./features/media/MediaCenterPage";
import { ProjectTaskWorkspace } from "./features/projects/ProjectTaskWorkspace";
import { TaskTimelinePage } from "./features/tasks/TaskTimelinePage";
import { FieldAppsPage } from "./features/workflows/FieldAppsPage";
import { SkillsPage } from "./features/skills/SkillsPage";
import { KnowledgePage } from "./features/knowledge/KnowledgePage";
import { VoiceprintsPage } from "./features/voiceprints/VoiceprintsPage";
import { AuditPage } from "./features/operations/AuditPage";
import { SystemStatusPage } from "./features/operations/SystemStatusPage";
import { SystemSettingsPage } from "./features/operations/SystemSettingsPage";
import "./styles/app.css";
import "./styles/governance.css";

const loadWorkflowStudio = async () => {
  const module = await import("./features/workflows/WorkflowStudioPage");
  return { default: module.WorkflowStudioPage };
};
const WorkflowStudioPage = lazy(loadWorkflowStudio);

type Section = "工作台" | "项目与任务" | "现场应用" | "AI 运维技能" | "华方知识库" | "媒体中心" | "人员与设备" | "声纹管理" | "统一审计" | "系统运行" | "系统设置";
const navigation: Array<{ label: Section; icon: typeof Activity }> = [{ label: "工作台", icon: Activity }, { label: "项目与任务", icon: ClipboardList }, { label: "现场应用", icon: Workflow }, { label: "AI 运维技能", icon: BrainCircuit }, { label: "华方知识库", icon: Library }, { label: "媒体中心", icon: Camera }, { label: "人员与设备", icon: Users }, { label: "声纹管理", icon: Fingerprint }, { label: "统一审计", icon: ScrollText }, { label: "系统运行", icon: Settings2 }, { label: "系统设置", icon: SlidersHorizontal }];

export type WebAuthState = { authenticated: boolean; passwordRecovery: boolean };
export type WebAuth = {
  restoreSession: () => Promise<boolean | WebAuthState>;
  signIn: (email: string, password: string) => Promise<void>;
  completePasswordRecovery?: (password: string) => Promise<void>;
  subscribe?: (listener: (state: WebAuthState) => void) => () => void;
};

export function App({ initialAuthenticated = false, api, auth }: { initialAuthenticated?: boolean; api?: ManagementApi; auth?: WebAuth }) {
  const [authenticated, setAuthenticated] = useState(initialAuthenticated);
  const [passwordRecovery, setPasswordRecovery] = useState(false);
  const [recoveryComplete, setRecoveryComplete] = useState(false);
  const [section, setSection] = useState<Section>("工作台");
  const [taskId, setTaskId] = useState("");
  const [workflowId, setWorkflowId] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [loginError, setLoginError] = useState("");
  const [signingIn, setSigningIn] = useState(false);

  useEffect(() => {
    let alive = true;
    const applyState = (state: boolean | WebAuthState) => {
      if (!alive) return;
      if (typeof state === "boolean") {
        setAuthenticated(state);
        return;
      }
      setAuthenticated(state.authenticated);
      setPasswordRecovery(state.passwordRecovery);
    };
    const unsubscribe = auth?.subscribe?.(applyState);
    auth?.restoreSession().then(applyState).catch(() => undefined);
    return () => {
      alive = false;
      unsubscribe?.();
    };
  }, [auth]);

  async function signIn(event: FormEvent) { event.preventDefault(); if (!auth) { setLoginError("管理服务尚未完成授权配置。"); return; } setSigningIn(true); setLoginError(""); try { await auth.signIn(email, password); setAuthenticated(true); } catch (cause) { setLoginError(cause instanceof Error ? cause.message : "登录失败，请核对账号和密码。"); } finally { setSigningIn(false); } }
  if (passwordRecovery) {
    return <PasswordRecoveryPage auth={auth} onComplete={() => { setPasswordRecovery(false); setAuthenticated(false); setRecoveryComplete(true); }} />;
  }
  if (!authenticated) return <main className="login-page"><section className="login-panel"><div className="brand-mark"><MonitorSmartphone size={22} /> D</div><p className="eyebrow">DINGDANG OPS</p><h1>叮当 AI 运维管理平台</h1><p className="login-copy">登录后查看项目内的现场任务、证据与维修记录。</p>{recoveryComplete ? <p className="notice success" role="status">密码已更新，请使用新密码登录。</p> : null}<form onSubmit={signIn}><label>账号邮箱<input aria-label="账号邮箱" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} required /></label><label>登录密码<input aria-label="登录密码" type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required /></label>{loginError ? <p className="form-error" role="alert">{loginError}</p> : null}<button type="submit" disabled={signingIn}>{signingIn ? "正在验证" : "登录工作台"}</button></form></section></main>;
  return <div className="shell"><aside className="nav"><div className="nav-brand"><span className="brand-mark"><MonitorSmartphone size={18} /> D</span><strong>叮当运维</strong></div><nav aria-label="主导航">{navigation.map(({ label, icon: Icon }) => <a aria-current={section === label ? "page" : undefined} key={label} href={`#${label}`} className={section === label ? "active" : ""} onClick={(event) => { event.preventDefault(); if (label === "现场应用") void loadWorkflowStudio(); setSection(label); setTaskId(""); setWorkflowId(""); }}><Icon size={18} />{label}</a>)}</nav><div className="nav-foot"><span className="presence" />云端管理服务</div></aside><main className="workspace"><header className="command-bar"><div><span className="eyebrow">现场运维工作台</span><h1>{workflowId ? "工作流编排" : taskId ? "任务时间线" : section}</h1></div><div className="operator"><span className="presence" />已授权访问</div></header><section className={workflowId ? "content workflow-content" : "content"}>{api ? workflowId ? <Suspense fallback={<section className="notice workflow-loading">正在打开工作流编排…</section>}><WorkflowStudioPage api={api} workflowId={workflowId} onBack={() => setWorkflowId("")} /></Suspense> : taskId ? <TaskTimelinePage taskId={taskId} api={api} onBack={() => setTaskId("")} /> : <Page section={section} api={api} onOpenTask={setTaskId} onOpenWorkflow={setWorkflowId} /> : <section className="notice error">管理服务尚未完成授权配置，无法读取任务数据。</section>}</section></main></div>;
}

function PasswordRecoveryPage({ auth, onComplete }: { auth?: WebAuth; onComplete: () => void }) {
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (password !== confirmation) {
      setError("两次输入的新密码不一致。");
      return;
    }
    if (!auth?.completePasswordRecovery) {
      setError("身份恢复服务尚未完成授权配置。");
      return;
    }
    setSubmitting(true);
    setError("");
    try {
      await auth.completePasswordRecovery(password);
      onComplete();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "密码更新失败，请重新打开最新恢复邮件后再试。");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="login-page">
      <section className="login-panel">
        <div className="brand-mark"><MonitorSmartphone size={22} /> D</div>
        <p className="eyebrow">IDENTITY RECOVERY</p>
        <h1>设置新密码</h1>
        <p className="login-copy">恢复会话只用于更新账号密码，完成后需要重新登录并再次校验账号状态与项目权限。</p>
        <form onSubmit={submit}>
          <label>新密码<input aria-label="新密码" autoComplete="new-password" minLength={8} onChange={(event) => setPassword(event.target.value)} required type="password" value={password} /></label>
          <label>确认新密码<input aria-label="确认新密码" autoComplete="new-password" minLength={8} onChange={(event) => setConfirmation(event.target.value)} required type="password" value={confirmation} /></label>
          {error ? <p className="form-error" role="alert">{error}</p> : null}
          <button disabled={submitting} type="submit">{submitting ? "正在更新" : "确认更新密码"}</button>
        </form>
      </section>
    </main>
  );
}

function Page({ section, api, onOpenTask, onOpenWorkflow }: { section: Section; api: ManagementApi; onOpenTask: (taskId: string) => void; onOpenWorkflow: (workflowId: string) => void }) { if (section === "工作台") return <DashboardPage api={api} onOpenTask={onOpenTask} />; if (section === "项目与任务") return <ProjectTaskWorkspace api={api} onOpenTask={onOpenTask} />; if (section === "现场应用") return <FieldAppsPage api={api} onOpenWorkflow={onOpenWorkflow} />; if (section === "AI 运维技能") return <SkillsPage api={api} />; if (section === "华方知识库") return <KnowledgePage api={api} />; if (section === "媒体中心") return <MediaCenterPage api={api} />; if (section === "声纹管理") return <VoiceprintsPage api={api} />; if (section === "统一审计") return <AuditPage api={api} />; if (section === "系统运行") return <SystemStatusPage api={api} />; if (section === "系统设置") return <SystemSettingsPage api={api} />; return <DevicesPage api={api} />; }
