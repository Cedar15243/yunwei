import { Archive, Ban, BrainCircuit, CheckCircle2, Plus, RefreshCw, Send, ShieldCheck, Undo2, XCircle } from "lucide-react";
import { useEffect, useState } from "react";
import type {
  SkillAssignment,
  SkillDefinition,
  SkillKnowledgeApi,
  SkillStatus,
  SkillVersion,
} from "../../api/skill-knowledge-types";
import {
  AssignmentDialog,
  ReasonDialog,
  SkillDraftDialog,
} from "../governance/GovernanceDialogs";

type Action =
  | { kind: "transition"; version: SkillVersion; target: SkillStatus; title: string; description: string; confirmLabel: string }
  | { kind: "assign"; version: SkillVersion }
  | { kind: "revoke"; assignment: SkillAssignment };

export function SkillsPage({ api }: { api: SkillKnowledgeApi }) {
  const [skills, setSkills] = useState<SkillDefinition[]>([]);
  const [selectedSkillId, setSelectedSkillId] = useState("");
  const [versions, setVersions] = useState<SkillVersion[]>([]);
  const [loading, setLoading] = useState(true);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [error, setError] = useState("");
  const [versionsError, setVersionsError] = useState("");
  const [directoryReload, setDirectoryReload] = useState(0);
  const [versionReload, setVersionReload] = useState(0);
  const [draftOpen, setDraftOpen] = useState(false);
  const [action, setAction] = useState<Action | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    api.getSkills().then((items) => {
      if (!active) return;
      setSkills(items);
      setSelectedSkillId((current) => current && items.some((item) => item.id === current) ? current : items[0]?.id ?? "");
    }).catch((cause) => {
      if (active) setError(message(cause, "无法读取 AI 运维技能。"));
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [api, directoryReload]);

  useEffect(() => {
    if (!selectedSkillId) {
      setVersions([]);
      return;
    }
    let active = true;
    setVersionsLoading(true);
    setVersionsError("");
    api.getSkillVersions(selectedSkillId).then((items) => {
      if (active) setVersions(items.map(normalizeSkillVersion));
    }).catch((cause) => {
      if (active) setVersionsError(message(cause, "无法读取技能版本。"));
    }).finally(() => {
      if (active) setVersionsLoading(false);
    });
    return () => { active = false; };
  }, [api, selectedSkillId, versionReload]);

  const selectedSkill = skills.find((item) => item.id === selectedSkillId) ?? null;
  const refreshAll = () => {
    setDirectoryReload((value) => value + 1);
    setVersionReload((value) => value + 1);
  };

  if (loading) return <section className="notice governance-loading">正在读取 AI 运维技能…</section>;
  if (error) return <section className="notice error workflow-retry" role="alert"><span>{error}</span><button className="secondary-button" onClick={() => setDirectoryReload((value) => value + 1)} type="button"><RefreshCw size={16} />重试</button></section>;

  return (
    <section className="governance-page page-stack">
      <div className="workspace-actions">
        <div><h2>AI 运维技能</h2><p>{skills.length} 个技能定义 · 发布后由服务端写入任务执行上下文</p></div>
        <button className="primary-button" onClick={() => setDraftOpen(true)} type="button"><Plus size={17} />新建 Skill</button>
      </div>

      {skills.length === 0 ? (
        <section className="workflow-empty"><BrainCircuit size={24} /><strong>尚未创建 AI 运维技能。</strong></section>
      ) : (
        <div className="governance-directory-grid">
          <aside aria-label="技能列表" className="governance-directory-list">
            <div className="pane-title">技能目录</div>
            {skills.map((item) => (
              <button aria-pressed={item.id === selectedSkillId} className={item.id === selectedSkillId ? "governance-directory-row selected" : "governance-directory-row"} key={item.id} onClick={() => setSelectedSkillId(item.id)} type="button">
                <span className="app-icon"><BrainCircuit size={17} /></span>
                <span><strong>{item.name}</strong><small>{item.skill_key}</small></span>
                <span className="directory-count">{item.skill_versions?.length ?? 0}</span>
              </button>
            ))}
          </aside>

          <section aria-label="技能版本" className="governance-detail-pane">
            <header className="pane-heading">
              <div><h3>{selectedSkill?.name ?? "技能版本"}</h3><p>{selectedSkill?.description || "暂无技能说明"}</p></div>
              <button aria-label="刷新技能版本" className="icon-button" onClick={() => setVersionReload((value) => value + 1)} type="button"><RefreshCw size={16} /></button>
            </header>
            {versionsLoading ? <div className="governance-pane-state">正在读取技能版本…</div> : null}
            {!versionsLoading && versionsError ? <div className="governance-pane-state error" role="alert">{versionsError}</div> : null}
            {!versionsLoading && !versionsError && versions.length === 0 ? <div className="governance-pane-state">当前技能尚无版本。</div> : null}
            {!versionsLoading && !versionsError ? versions.map((version) => (
              <SkillVersionRow key={version.id} onAction={setAction} version={version} />
            )) : null}
          </section>
        </div>
      )}

      {draftOpen ? <SkillDraftDialog onCancel={() => setDraftOpen(false)} onSubmit={async (command) => { await api.createSkillDraft(command); setDraftOpen(false); refreshAll(); }} /> : null}
      {action?.kind === "transition" ? <ReasonDialog confirmLabel={action.confirmLabel} description={action.description} onCancel={() => setAction(null)} onConfirm={async (reason, idempotencyKey) => { await api.transitionSkillVersion(action.version.id, { expectedStatus: action.version.status, newStatus: action.target, testResult: action.version.test_result ?? {}, reason, idempotencyKey }); setAction(null); refreshAll(); }} title={action.title} /> : null}
      {action?.kind === "assign" ? <AssignmentDialog allowSkillVersion={false} confirmLabel="确认分配" onCancel={() => setAction(null)} onConfirm={async (command) => { await api.assignSkillVersion(action.version.id, command); setAction(null); refreshAll(); }} title="分配技能版本" /> : null}
      {action?.kind === "revoke" ? <ReasonDialog confirmLabel="确认撤销" description="撤销后，新任务和后续授权清单不再使用此分配；历史任务仍保留原版本。" onCancel={() => setAction(null)} onConfirm={async (reason, idempotencyKey) => { await api.revokeSkillAssignment(action.assignment.id, { reason, idempotencyKey }); setAction(null); refreshAll(); }} title="撤销技能分配" /> : null}
    </section>
  );
}

function SkillVersionRow({ version, onAction }: { version: SkillVersion; onAction: (action: Action) => void }) {
  const scopes = Array.isArray(version.rules.knowledgeScopes) ? version.rules.knowledgeScopes.filter((value): value is string => typeof value === "string") : [];
  const assignments = version.skill_assignments.filter((item) => item.status === "active");
  return (
    <article className="governance-version-row">
      <div className="version-primary"><div><strong>v{version.version}</strong><span className={`status ${version.status}`}>{skillStatusLabel(version.status)}</span></div><time>{formatDate(version.updated_at)}</time></div>
      <dl className="version-metadata"><div><dt>知识范围</dt><dd>{scopes.length ? scopes.join("、") : "未配置"}</dd></div><div><dt>内容摘要</dt><dd><code>{version.content_sha256 ? version.content_sha256.slice(0, 16) : "待发布"}</code></dd></div><div><dt>生命周期说明</dt><dd>{version.lifecycle_reason || "未填写"}</dd></div></dl>
      <div className="version-actions">{skillActions(version, onAction)}</div>
      <section className="assignment-list" aria-label={`v${version.version} 分配记录`}>
        <header><strong>有效分配</strong><span>{assignments.length}</span></header>
        {assignments.length ? assignments.map((assignment) => <div className="assignment-row" key={assignment.id}><span><strong>{scopeLabel(assignment.scope_type)}</strong><small>{scopeId(assignment)}</small></span><time>{assignment.expires_at ? `至 ${formatDate(assignment.expires_at)}` : "长期有效"}</time><button aria-label="撤销技能分配" className="inline-danger" onClick={() => onAction({ kind: "revoke", assignment })} type="button"><XCircle size={15} />撤销</button></div>) : <p className="assignment-empty">当前版本尚未分配。</p>}
      </section>
    </article>
  );
}

function skillActions(version: SkillVersion, onAction: (action: Action) => void) {
  if (version.status === "draft") return <button className="secondary-button" onClick={() => onAction({ kind: "transition", version, target: "review_pending", title: "提交技能审核", description: "提交后草稿进入待审核状态，发布前仍不会影响任何眼镜任务。", confirmLabel: "确认提交" })} type="button"><Send size={15} />提交审核</button>;
  if (version.status === "review_pending") return <><button className="secondary-button" onClick={() => onAction({ kind: "transition", version, target: "draft", title: "退回技能草稿", description: "退回后需要修改并重新提交审核。", confirmLabel: "确认退回" })} type="button"><Undo2 size={15} />退回草稿</button><button className="primary-button" onClick={() => onAction({ kind: "transition", version, target: "published", title: "发布技能版本", description: "发布将生成不可变版本；后续修改必须新建版本。", confirmLabel: "确认发布" })} type="button"><CheckCircle2 size={15} />发布版本</button></>;
  if (version.status === "published") return <><button className="secondary-button" onClick={() => onAction({ kind: "assign", version })} type="button"><ShieldCheck size={15} />分配授权</button><button className="secondary-button danger-button" onClick={() => onAction({ kind: "transition", version, target: "deprecated", title: "停止使用技能", description: "停止使用后不再进入新的设备清单，活跃任务仍保留已固定快照。", confirmLabel: "确认停用" })} type="button"><Ban size={15} />停止使用</button></>;
  if (version.status === "deprecated") return <button className="secondary-button" onClick={() => onAction({ kind: "transition", version, target: "archived", title: "归档技能版本", description: "归档只保留审计和历史任务引用，不再允许重新分配。", confirmLabel: "确认归档" })} type="button"><Archive size={15} />归档版本</button>;
  return null;
}

function normalizeSkillVersion(version: SkillVersion): SkillVersion {
  return { ...version, skill_assignments: Array.isArray(version.skill_assignments) ? version.skill_assignments : [] };
}

function skillStatusLabel(status: SkillStatus): string {
  return ({ draft: "草稿", review_pending: "待审核", published: "已发布", deprecated: "已停用", archived: "已归档" })[status];
}

function scopeLabel(scope: SkillAssignment["scope_type"]): string {
  return ({ organization: "组织授权", project: "项目授权", profile: "人员授权", device: "设备授权" })[scope];
}

function scopeId(assignment: SkillAssignment): string {
  return assignment.project_id ?? assignment.profile_id ?? assignment.device_id ?? "当前组织";
}

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("zh-CN", { hour12: false });
}

function message(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}
