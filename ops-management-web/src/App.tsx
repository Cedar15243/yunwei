import { useState } from "react";
import { Activity, Camera, ClipboardList, MonitorSmartphone, Users } from "lucide-react";
import "./styles/app.css";

type Section = "工作台" | "项目与任务" | "媒体中心" | "人员与设备";

const navigation: Array<{ label: Section; icon: typeof Activity }> = [
  { label: "工作台", icon: Activity },
  { label: "项目与任务", icon: ClipboardList },
  { label: "媒体中心", icon: Camera },
  { label: "人员与设备", icon: Users },
];

export function App({ initialAuthenticated = false }: { initialAuthenticated?: boolean }) {
  const [authenticated, setAuthenticated] = useState(initialAuthenticated);
  const [section, setSection] = useState<Section>("工作台");
  if (!authenticated) {
    return <main className="login-page"><section className="login-panel">
      <div className="brand-mark"><MonitorSmartphone size={22} /> D</div>
      <p className="eyebrow">DINGDANG OPS</p><h1>叮当 AI 运维管理平台</h1>
      <p className="login-copy">统一管理现场任务、设备证据与运维记录。</p>
      <label>账号邮箱<input aria-label="账号邮箱" type="email" autoComplete="email" /></label>
      <label>登录密码<input aria-label="登录密码" type="password" autoComplete="current-password" /></label>
      <button type="button" onClick={() => setAuthenticated(true)}>登录工作台</button>
    </section></main>;
  }
  return <div className="shell">
    <aside className="nav"><div className="nav-brand"><span className="brand-mark"><MonitorSmartphone size={18} /> D</span><strong>叮当运维</strong></div>
      <nav aria-label="主导航">{navigation.map(({ label, icon: Icon }) => <a key={label} href={`#${label}`} className={section === label ? "active" : ""} onClick={(event) => { event.preventDefault(); setSection(label); }}><Icon size={18} />{label}</a>)}</nav>
      <div className="nav-foot"><span className="presence" />系统运行正常</div>
    </aside>
    <main className="workspace"><header className="command-bar"><div><span className="eyebrow">现场运维工作台</span><h1>{section}</h1></div><div className="operator"><span className="presence" />王工</div></header>
      <section className="content"><h2>{section === "工作台" ? "运行概览" : section}</h2><p>已连接管理服务后将在此显示真实的项目、任务与现场证据。</p></section>
    </main>
  </div>;
}
