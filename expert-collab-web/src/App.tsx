import { ShieldCheck } from "lucide-react";
import { ContactRail } from "./components/ContactRail";
import { ExpertStage, type ExpertRole } from "./components/ExpertStage";
import { SessionPanel } from "./components/SessionPanel";
import "./styles.css";

interface AppProps {
  initialRole?: ExpertRole;
}

export function App({ initialRole = "primary" }: AppProps) {
  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand-lockup"><span className="brand-mark" /><strong>叮当专家协同</strong><span>演示工作台</span></div>
        <div className="service-status"><ShieldCheck aria-hidden="true" size={16} /><span>TRTC 免费试用 · 后付费关闭</span></div>
      </header>
      <div className="workspace-grid">
        <ContactRail />
        <ExpertStage role={initialRole} />
        <SessionPanel role={initialRole} />
      </div>
    </div>
  );
}
