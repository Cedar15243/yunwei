import { Activity, Radio, Users } from "lucide-react";
import type { CollaborationSnapshot } from "../collaboration-controller";
import type { ExpertRole } from "./ExpertStage";

interface SessionPanelProps {
  call: CollaborationSnapshot | null;
  role: ExpertRole;
  snapshots?: Array<{ id: string; label: string; time: string; url: string }>;
}

const previewSnapshots = [
  { id: "preview-1", label: "接线端子标注", time: "10:46", url: "" },
  { id: "preview-2", label: "设备铭牌识别", time: "10:41", url: "" },
  { id: "preview-3", label: "语音诊断摘要", time: "10:37", url: "" },
  { id: "preview-4", label: "处理建议确认", time: "10:34", url: "" },
];

export function SessionPanel({ call, role, snapshots }: SessionPanelProps) {
  const records = snapshots && snapshots.length > 0 ? snapshots : previewSnapshots;
  const isInCall = call?.status === "in_call";
  const hasSession = Boolean(call?.sessionId);
  const sessionLabel = call?.status === "in_call"
    ? "进行中"
    : call?.status === "ringing"
      ? "来电中"
      : call?.status === "connecting"
        ? "连接中"
        : call?.status === "failed"
          ? "连接失败"
          : call?.status === "taken"
            ? "已被接听"
            : "暂无会话";
  return (
    <aside className="session-panel" aria-label="本次协同">
      <section className="panel-section">
        <header className="panel-title"><span><Users aria-hidden="true" size={16} />本次协同</span><small>{sessionLabel}</small></header>
        {hasSession ? <>
          <div className="member-row"><span className="avatar avatar--small avatar--device">镜</span><strong>{call?.glassesName ?? "现场设备"}</strong><span className="role-tag role-tag--observer">现场</span></div>
          {isInCall ? <div className="member-row"><span className="avatar avatar--small">专</span><strong>当前专家</strong><span className="role-tag">{role === "primary" ? "主专家" : "旁听"}</span></div> : null}
        </> : <p className="empty-session">暂未接入现场设备</p>}
        {role === "observer" ? <p className="observer-notice">当前以旁听身份加入，标注与邀请功能已锁定。</p> : null}
      </section>

      <section className="panel-section">
        <header className="panel-title"><span><Radio aria-hidden="true" size={16} />协同记录</span><small>{records.length}张</small></header>
        <div className="record-grid">
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
        </div>
        {records.length === 0 ? <p className="empty-records">暂无截图记录</p> : null}
      </section>

      <section className="panel-section connection-panel">
        <header className="panel-title"><span><Activity aria-hidden="true" size={16} />设备链路</span></header>
        <dl>
          <div><dt>视频</dt><dd>{isInCall ? "实时媒体已接入" : hasSession ? "等待媒体接入" : "暂无实时数据"}</dd></div>
          <div><dt>网络</dt><dd className={isInCall ? "good" : ""}>{isInCall ? "连接正常" : hasSession ? "正在协商连接" : "暂无实时数据"}</dd></div>
          <div><dt>状态</dt><dd>{sessionLabel}</dd></div>
        </dl>
      </section>
    </aside>
  );
}
