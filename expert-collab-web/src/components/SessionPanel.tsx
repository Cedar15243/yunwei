import { Activity, Radio, Users } from "lucide-react";
import type { ExpertRole } from "./ExpertStage";

interface SessionPanelProps {
  role: ExpertRole;
  snapshots?: Array<{ id: string; label: string; time: string; url: string }>;
}

const previewSnapshots = [
  { id: "preview-1", label: "接线端子标注", time: "10:34", url: "" },
  { id: "preview-2", label: "设备铭牌", time: "10:32", url: "" },
];

export function SessionPanel({ role, snapshots }: SessionPanelProps) {
  const records = snapshots ?? previewSnapshots;
  return (
    <aside className="session-panel" aria-label="会话信息">
      <section className="panel-section">
        <header className="panel-title"><span><Users aria-hidden="true" size={16} />会话成员</span><small>3人</small></header>
        <div className="member-row"><span className="avatar avatar--small">王</span><strong>王工</strong><span className="role-tag">主专家</span></div>
        <div className="member-row"><span className="avatar avatar--small">刘</span><strong>刘工</strong><span className="role-tag role-tag--observer">旁听语音</span></div>
        <div className="member-row"><span className="avatar avatar--small avatar--device">镜</span><strong>Air3-现场01</strong><span className="role-tag role-tag--observer">现场</span></div>
        {role === "observer" ? <p className="observer-notice">当前以旁听身份加入，标注与邀请功能已锁定。</p> : null}
      </section>

      <section className="panel-section">
        <header className="panel-title"><span><Radio aria-hidden="true" size={16} />截图记录</span><small>{records.length}张</small></header>
        {records.map((record, index) => (
          <button
            className={index % 2 === 0 ? "snapshot" : "snapshot snapshot--secondary"}
            key={record.id}
            style={record.url ? { backgroundImage: `linear-gradient(rgba(5,9,12,.18), rgba(5,9,12,.72)), url(${record.url})` } : undefined}
            type="button"
          >
            <span>{record.time}</span><strong>{record.label}</strong>
          </button>
        ))}
        {records.length === 0 ? <p className="empty-records">暂无截图记录</p> : null}
      </section>

      <section className="panel-section connection-panel">
        <header className="panel-title"><span><Activity aria-hidden="true" size={16} />连接质量</span></header>
        <dl>
          <div><dt>视频</dt><dd>720P · 24fps</dd></div>
          <div><dt>网络</dt><dd className="good">良好 · 186ms</dd></div>
          <div><dt>计费</dt><dd>免费时长包内</dd></div>
        </dl>
      </section>
    </aside>
  );
}
