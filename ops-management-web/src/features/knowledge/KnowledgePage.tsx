import { Archive, Ban, BookOpen, CheckCircle2, Clock3, Download, FileUp, Plus, RefreshCw, RotateCcw, Send, ShieldCheck, Undo2, XCircle } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import type {
  KnowledgeEntry,
  KnowledgeGrant,
  KnowledgeAttachment,
  KnowledgeStatus,
  KnowledgeVersion,
  SkillKnowledgeApi,
} from "../../api/skill-knowledge-types";
import {
  AssignmentDialog,
  KnowledgeDraftDialog,
  ReasonDialog,
} from "../governance/GovernanceDialogs";

type Action =
  | { kind: "transition"; version: KnowledgeVersion; target: KnowledgeStatus; title: string; description: string; confirmLabel: string }
  | { kind: "grant"; version: KnowledgeVersion }
  | { kind: "revoke"; grant: KnowledgeGrant }
  | { kind: "retry"; attachment: KnowledgeAttachment };

export function KnowledgePage({ api }: { api: SkillKnowledgeApi }) {
  const [entries, setEntries] = useState<KnowledgeEntry[]>([]);
  const [selectedEntryId, setSelectedEntryId] = useState("");
  const [versions, setVersions] = useState<KnowledgeVersion[]>([]);
  const [loading, setLoading] = useState(true);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [error, setError] = useState("");
  const [versionsError, setVersionsError] = useState("");
  const [directoryReload, setDirectoryReload] = useState(0);
  const [versionReload, setVersionReload] = useState(0);
  const [draftOpen, setDraftOpen] = useState(false);
  const [action, setAction] = useState<Action | null>(null);
  const [attachmentBusy, setAttachmentBusy] = useState("");
  const [attachmentError, setAttachmentError] = useState("");

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    api.getKnowledge().then((items) => {
      if (!active) return;
      setEntries(items);
      setSelectedEntryId((current) => current && items.some((item) => item.id === current) ? current : items[0]?.id ?? "");
    }).catch((cause) => {
      if (active) setError(message(cause, "无法读取华方知识库。"));
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [api, directoryReload]);

  useEffect(() => {
    if (!selectedEntryId) {
      setVersions([]);
      return;
    }
    let active = true;
    setVersionsLoading(true);
    setVersionsError("");
    api.getKnowledgeVersions(selectedEntryId).then((items) => {
      if (active) setVersions(items.map(normalizeKnowledgeVersion));
    }).catch((cause) => {
      if (active) setVersionsError(message(cause, "无法读取知识版本。"));
    }).finally(() => {
      if (active) setVersionsLoading(false);
    });
    return () => { active = false; };
  }, [api, selectedEntryId, versionReload]);

  const selectedEntry = entries.find((item) => item.id === selectedEntryId) ?? null;
  const refreshAll = () => {
    setDirectoryReload((value) => value + 1);
    setVersionReload((value) => value + 1);
  };

  async function uploadAttachment(version: KnowledgeVersion, file: File) {
    setAttachmentBusy(version.id);
    setAttachmentError("");
    try {
      await api.uploadKnowledgeAttachment(version.id, file, newIdempotencyKey());
      refreshAll();
    } catch (cause) {
      setAttachmentError(message(cause, "附件上传或处理失败，请检查文件状态后重试。"));
    } finally {
      setAttachmentBusy("");
    }
  }

  if (loading) return <section className="notice governance-loading">正在读取华方知识库…</section>;
  if (error) return <section className="notice error workflow-retry" role="alert"><span>{error}</span><button className="secondary-button" onClick={() => setDirectoryReload((value) => value + 1)} type="button"><RefreshCw size={16} />重试</button></section>;

  return (
    <section className="governance-page page-stack">
      <div className="workspace-actions">
        <div><h2>华方知识库</h2><p>{entries.length} 个知识条目 · 仅已发布、有效且获授权内容可供 AI 引用</p></div>
        <button className="primary-button" onClick={() => setDraftOpen(true)} type="button"><Plus size={17} />新建知识</button>
      </div>

      {entries.length === 0 ? (
        <section className="workflow-empty"><BookOpen size={24} /><strong>尚未导入华方知识。</strong></section>
      ) : (
        <div className="governance-directory-grid">
          <aside aria-label="知识目录" className="governance-directory-list">
            <div className="pane-title">知识目录</div>
            {entries.map((item) => (
              <button aria-pressed={item.id === selectedEntryId} className={item.id === selectedEntryId ? "governance-directory-row selected" : "governance-directory-row"} key={item.id} onClick={() => setSelectedEntryId(item.id)} type="button">
                <span className="app-icon"><BookOpen size={17} /></span>
                <span><strong>{item.title}</strong><small>{item.knowledge_key}</small></span>
                <span className="directory-count">{item.knowledge_versions?.length ?? 0}</span>
              </button>
            ))}
          </aside>

          <section aria-label="知识版本" className="governance-detail-pane">
            <header className="pane-heading">
              <div><h3>{selectedEntry?.title ?? "知识版本"}</h3><p>{selectedEntry?.knowledge_key ?? ""}</p></div>
              <button aria-label="刷新知识版本" className="icon-button" onClick={() => setVersionReload((value) => value + 1)} type="button"><RefreshCw size={16} /></button>
            </header>
            {versionsLoading ? <div className="governance-pane-state">正在读取知识版本…</div> : null}
            {!versionsLoading && versionsError ? <div className="governance-pane-state error" role="alert">{versionsError}</div> : null}
            {!versionsLoading && !versionsError && versions.length === 0 ? <div className="governance-pane-state">当前知识尚无版本。</div> : null}
            {!versionsLoading && !versionsError ? versions.map((version) => <KnowledgeVersionRow key={version.id} attachmentBusy={attachmentBusy === version.id} attachmentError={attachmentError} onAction={setAction} onUpload={(file) => uploadAttachment(version, file)} version={version} />) : null}
          </section>
        </div>
      )}

      {draftOpen ? <KnowledgeDraftDialog onCancel={() => setDraftOpen(false)} onSubmit={async (command, file) => { const created = await api.createKnowledgeDraft(command); if (file) await api.uploadKnowledgeAttachment(created.id, file, newIdempotencyKey()); setDraftOpen(false); refreshAll(); }} /> : null}
      {action?.kind === "transition" ? <ReasonDialog confirmLabel={action.confirmLabel} description={action.description} onCancel={() => setAction(null)} onConfirm={async (reason, idempotencyKey) => { await api.transitionKnowledgeVersion(action.version.id, { expectedStatus: action.version.status, newStatus: action.target, processingError: "", reason, idempotencyKey }); setAction(null); refreshAll(); }} title={action.title} /> : null}
      {action?.kind === "grant" ? <AssignmentDialog allowSkillVersion confirmLabel="确认授权" onCancel={() => setAction(null)} onConfirm={async (command) => { await api.grantKnowledgeVersion(action.version.id, command); setAction(null); refreshAll(); }} title="授权知识版本" /> : null}
      {action?.kind === "revoke" ? <ReasonDialog confirmLabel="确认撤销" description="撤销后，新清单和新的 AI 检索不再包含此授权；历史引用仍保留审计。" onCancel={() => setAction(null)} onConfirm={async (reason, idempotencyKey) => { await api.revokeKnowledgeGrant(action.grant.id, { reason, idempotencyKey }); setAction(null); refreshAll(); }} title="撤销知识授权" /> : null}
      {action?.kind === "retry" ? <ReasonDialog confirmLabel="确认重试解析" description="服务端将从私有对象存储重新读取原文件，不接受客户端伪造正文。" onCancel={() => setAction(null)} onConfirm={async (reason, idempotencyKey) => { await api.retryKnowledgeAttachment(action.attachment.id, { reason, idempotencyKey }); setAction(null); refreshAll(); }} title="重试附件解析" /> : null}
    </section>
  );
}

function KnowledgeVersionRow({ version, onAction, onUpload, attachmentBusy, attachmentError }: { version: KnowledgeVersion; onAction: (action: Action) => void; onUpload: (file: File) => void; attachmentBusy: boolean; attachmentError: string }) {
  const grants = version.knowledge_grants.filter((item) => item.status === "active");
  const attachments = version.knowledge_attachments;
  const currentParsedAttachment = attachments.some((item) => item.is_current && item.status === "parsed");
  const uploadSource = version.source_type === "uploaded_file";
  return (
    <article className="governance-version-row">
      <div className="version-primary"><div><strong>v{version.version}</strong><span className={`status ${version.status}`}>{knowledgeStatusLabel(version.status)}</span><span className={`sensitivity ${version.sensitivity}`}>{sensitivityLabel(version.sensitivity)}</span></div><time>{formatDate(version.updated_at)}</time></div>
      <p className="version-summary">{version.summary}</p>
      <dl className="version-metadata knowledge-metadata"><div><dt>来源</dt><dd>{version.source_reference}</dd></div><div><dt>知识范围</dt><dd>{version.knowledge_scopes.length ? version.knowledge_scopes.join("、") : "未配置"}</dd></div><div><dt>适用设备</dt><dd>{version.device_models.length ? version.device_models.join("、") : "未限制"}</dd></div><div><dt>内容摘要</dt><dd><code>{version.content_sha256 ? version.content_sha256.slice(0, 16) : "待发布"}</code></dd></div></dl>
      {version.processing_error ? <p className="processing-error" role="alert">处理失败：{version.processing_error}</p> : null}
      <div className="version-actions">{knowledgeActions(version, onAction, currentParsedAttachment)}</div>
      {uploadSource ? <KnowledgeAttachmentSection attachments={attachments} busy={attachmentBusy} error={attachmentError} onRetry={(attachment) => onAction({ kind: "retry", attachment })} onUpload={onUpload} version={version} /> : null}
      <section className="assignment-list" aria-label={`v${version.version} 授权记录`}>
        <header><strong>有效授权</strong><span>{grants.length}</span></header>
        {grants.length ? grants.map((grant) => <div className="assignment-row" key={grant.id}><span><strong>{scopeLabel(grant.scope_type)}</strong><small>{scopeId(grant)}</small></span><time>{grant.expires_at ? `至 ${formatDate(grant.expires_at)}` : "长期有效"}</time><button aria-label="撤销知识授权" className="inline-danger" onClick={() => onAction({ kind: "revoke", grant })} type="button"><XCircle size={15} />撤销</button></div>) : <p className="assignment-empty">当前版本尚未授权。</p>}
      </section>
    </article>
  );
}

function KnowledgeAttachmentSection({ attachments, busy, error, onRetry, onUpload, version }: { attachments: KnowledgeAttachment[]; busy: boolean; error: string; onRetry: (attachment: KnowledgeAttachment) => void; onUpload: (file: File) => void; version: KnowledgeVersion }) {
  return <section aria-label={`v${version.version} 附件记录`} className="knowledge-attachments">
    <header><strong>附件处理</strong><span>{attachments.length}</span></header>
    {attachments.length ? attachments.map((attachment) => <div className="knowledge-attachment-row" key={attachment.id}>
      <div className="knowledge-attachment-main"><FileUp size={16} /><span><strong>{attachment.original_file_name}</strong><small>{attachmentStatusLabel(attachment.status)} · <span className="attachment-size">{formatBytes(attachment.byte_size)}</span> · SHA-256 {attachment.file_sha256.slice(0, 12)}</small></span></div>
      <div className="knowledge-attachment-actions">
        {attachment.is_current && attachment.status === "parsed" ? <span className="attachment-current">当前附件</span> : null}
        {attachment.download_url ? <a aria-label="下载附件" className="icon-button" href={attachment.download_url} rel="noreferrer" target="_blank" title="下载附件"><Download size={15} /></a> : null}
        {["failed", "processing_unavailable"].includes(attachment.status) ? <button aria-label="重试附件解析" className="icon-button" onClick={() => onRetry(attachment)} title="重试附件解析" type="button"><RotateCcw size={15} /></button> : null}
      </div>
      {attachment.processing_error ? <p className="processing-error" role="alert">{attachment.processing_error}</p> : null}
    </div>) : <p className="assignment-empty">尚未上传附件。</p>}
    <label className="secondary-button knowledge-attachment-upload"><FileUp size={15} />{busy ? "正在上传与处理" : attachments.length ? "替换附件" : "上传附件"}<input accept=".txt,.md,.markdown,.pdf,.docx,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document" aria-label={`上传或替换附件 v${version.version}`} disabled={busy} onChange={(event) => { const file = event.target.files?.[0]; if (file) onUpload(file); event.currentTarget.value = ""; }} type="file" /></label>
    {error ? <p className="processing-error" role="alert">{error}</p> : null}
    {version.status === "parsed" && !attachments.some((item) => item.is_current && item.status === "parsed") ? <p className="processing-error" role="alert">附件尚未完成解析，不能提交审核。</p> : null}
  </section>;
}

function knowledgeActions(version: KnowledgeVersion, onAction: (action: Action) => void, currentParsedAttachment: boolean) {
  const action = (target: KnowledgeStatus, title: string, description: string, confirmLabel: string, label: string, icon: ReactNode) => <button className="secondary-button" onClick={() => onAction({ kind: "transition", version, target, title, description, confirmLabel })} type="button">{icon}{label}</button>;
  if (version.status === "uploaded") return <span className="governance-waiting-state">等待后台安全扫描</span>;
  if (version.status === "scanning") return <span className="governance-waiting-state">等待后台扫描结果</span>;
  if (version.status === "parsing") return <span className="governance-waiting-state">等待后台解析结果</span>;
  if (version.status === "parsed") return version.source_type === "uploaded_file" && !currentParsedAttachment ? <span className="governance-waiting-state">附件尚未完成解析，不能提交审核</span> : action("review_pending", "提交知识审核", "提交后进入人工来源、权限、有效期和内容复核。", "确认提交", "提交审核", <Send size={15} />);
  if (version.status === "review_pending") return version.source_type === "uploaded_file" && !currentParsedAttachment ? <span className="governance-waiting-state">附件尚未完成解析，不能发布</span> : <>{action("parsed", "退回知识草稿", "退回后可修订元数据和正文，再重新提交审核。", "确认退回", "退回修改", <Undo2 size={15} />)}<button className="primary-button" onClick={() => onAction({ kind: "transition", version, target: "published", title: "发布知识版本", description: "发布后版本不可变，并仅在有效授权范围内进入 AI 检索。", confirmLabel: "确认发布" })} type="button"><CheckCircle2 size={15} />发布版本</button></>;
  if (version.status === "published") return <><button className="secondary-button" onClick={() => onAction({ kind: "grant", version })} type="button"><ShieldCheck size={15} />授权</button>{action("expired", "标记知识到期", "到期后停止用于新的 AI 请求，历史任务仍保留原引用。", "确认到期", "标记到期", <Clock3 size={15} />)}{action("deprecated", "停止使用知识", "停止使用后不再进入新的清单和检索结果。", "确认停用", "停止使用", <Ban size={15} />)}</>;
  if (version.status === "expired" || version.status === "deprecated") return action("archived", "归档知识版本", "归档后仅保留历史引用、版本和审计信息。", "确认归档", "归档版本", <Archive size={15} />);
  return null;
}

function normalizeKnowledgeVersion(version: KnowledgeVersion): KnowledgeVersion {
  return {
    ...version,
    project_ids: Array.isArray(version.project_ids) ? version.project_ids : [],
    device_models: Array.isArray(version.device_models) ? version.device_models : [],
    skill_ids: Array.isArray(version.skill_ids) ? version.skill_ids : [],
    knowledge_scopes: Array.isArray(version.knowledge_scopes) ? version.knowledge_scopes : [],
    knowledge_attachments: Array.isArray(version.knowledge_attachments) ? version.knowledge_attachments : [],
    knowledge_grants: Array.isArray(version.knowledge_grants) ? version.knowledge_grants : [],
  };
}

function knowledgeStatusLabel(status: KnowledgeStatus): string {
  return ({ uploaded: "已上传", scanning: "扫描中", parsing: "解析中", parsed: "待提交", review_pending: "待审核", published: "已发布", expired: "已到期", deprecated: "已停用", archived: "已归档" })[status];
}

function sensitivityLabel(value: KnowledgeVersion["sensitivity"]): string {
  return ({ public: "公开", internal: "内部", confidential: "机密", restricted: "受限" })[value];
}

function attachmentStatusLabel(status: KnowledgeAttachment["status"]): string {
  return ({ uploaded: "已上传", parsing: "解析中", parsed: "已解析", processing_unavailable: "解析待重试", failed: "处理失败", replaced: "已替换" })[status];
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function newIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `knowledge-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function scopeLabel(scope: KnowledgeGrant["scope_type"]): string {
  return ({ organization: "组织授权", project: "项目授权", profile: "人员授权", device: "设备授权", skill_version: "技能版本授权" })[scope];
}

function scopeId(grant: KnowledgeGrant): string {
  return grant.project_id ?? grant.profile_id ?? grant.device_id ?? grant.skill_version_id ?? "当前组织";
}

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}

function message(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}
