import { FileUp, X } from "lucide-react";
import { useState, type ChangeEvent, type FormEvent, type ReactNode } from "react";
import type {
  AssignmentCommand,
  ContentScopeType,
  KnowledgeCaseDraftCommand,
  KnowledgeDraftCommand,
  SkillDraftCommand,
} from "../../api/skill-knowledge-types";

type DialogFrameProps = {
  title: string;
  eyebrow: string;
  onCancel: () => void;
  children: ReactNode;
};

export function DialogFrame({ title, eyebrow, onCancel, children }: DialogFrameProps) {
  const headingId = `dialog-${title.replace(/\s+/g, "-")}`;
  return (
    <div className="dialog-backdrop">
      <section aria-labelledby={headingId} aria-modal="true" className="workflow-dialog governance-dialog" role="dialog">
        <header>
          <div><span className="eyebrow">{eyebrow}</span><h2 id={headingId}>{title}</h2></div>
          <button aria-label="关闭" className="icon-button" onClick={onCancel} type="button"><X size={17} /></button>
        </header>
        {children}
      </section>
    </div>
  );
}

export function KnowledgeCaseDraftDialog({
  taskId,
  taskTitle,
  onCancel,
  onSubmit,
}: {
  taskId: string;
  taskTitle: string;
  onCancel: () => void;
  onSubmit: (command: KnowledgeCaseDraftCommand) => Promise<void>;
}) {
  const [knowledgeKey, setKnowledgeKey] = useState(`case_${taskId.replace(/[^A-Za-z0-9._:@-]/g, "_")}`);
  const [version, setVersion] = useState("1");
  const [title, setTitle] = useState(`${taskTitle || "现场任务"}维修案例`);
  const [sensitivity, setSensitivity] = useState<KnowledgeCaseDraftCommand["sensitivity"]>("internal");
  const [license, setLicense] = useState("华方内部授权");
  const [knowledgeScopes, setKnowledgeScopes] = useState("");
  const [reason, setReason] = useState("");
  const [idempotencyKey] = useState(newIdempotencyKey);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      await onSubmit({
        knowledgeKey: knowledgeKey.trim(),
        version: Number(version),
        title: title.trim(),
        sensitivity,
        license: license.trim(),
        knowledgeScopes: splitList(knowledgeScopes),
        reason: reason.trim(),
        idempotencyKey,
      });
    } catch (cause) {
      setError(knowledgeCaseErrorMessage(cause));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <DialogFrame eyebrow="已完成人工确认任务" onCancel={onCancel} title="沉淀为知识草稿">
      <form onSubmit={submit}>
        <p className="dialog-copy">正文、任务来源和现场证据由服务端从当前任务记录生成，本页只设置知识元数据。</p>
        <div className="dialog-grid two-column">
          <label>知识标题<input aria-label="知识标题" maxLength={300} onChange={(event) => setTitle(event.target.value)} required value={title} /></label>
          <label>知识标识<input aria-label="知识标识" onChange={(event) => setKnowledgeKey(event.target.value)} pattern="[A-Za-z0-9][A-Za-z0-9._:@-]*" required value={knowledgeKey} /></label>
          <label>版本号<input aria-label="版本号" min="1" onChange={(event) => setVersion(event.target.value)} required type="number" value={version} /></label>
          <label>敏感级别<select aria-label="敏感级别" onChange={(event) => setSensitivity(event.target.value as KnowledgeCaseDraftCommand["sensitivity"])} value={sensitivity}><option value="public">公开</option><option value="internal">内部</option><option value="confidential">机密</option><option value="restricted">受限</option></select></label>
        </div>
        <label>许可信息<input aria-label="许可信息" maxLength={300} onChange={(event) => setLicense(event.target.value)} required value={license} /></label>
        <label>知识范围<input aria-label="知识范围" onChange={(event) => setKnowledgeScopes(event.target.value)} placeholder="hvac/ddc" required value={knowledgeScopes} /></label>
        <label>创建原因<textarea aria-label="创建原因" minLength={3} onChange={(event) => setReason(event.target.value)} required rows={3} value={reason} /></label>
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <footer><button className="secondary-button" onClick={onCancel} type="button">取消</button><button className="primary-button" disabled={submitting} type="submit">{submitting ? "正在创建" : "确认创建草稿"}</button></footer>
      </form>
    </DialogFrame>
  );
}

export function ReasonDialog({
  title,
  description,
  confirmLabel,
  onCancel,
  onConfirm,
}: {
  title: string;
  description: string;
  confirmLabel: string;
  onCancel: () => void;
  onConfirm: (reason: string, idempotencyKey: string) => Promise<void>;
}) {
  const [reason, setReason] = useState("");
  const [idempotencyKey] = useState(newIdempotencyKey);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      await onConfirm(reason.trim(), idempotencyKey);
    } catch (cause) {
      setError(errorMessage(cause, "操作失败，请检查服务状态后重试。"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <DialogFrame eyebrow="受控操作" onCancel={onCancel} title={title}>
      <form onSubmit={submit}>
        <p className="dialog-copy">{description}</p>
        <label>操作原因<textarea aria-label="操作原因" minLength={3} onChange={(event) => setReason(event.target.value)} required rows={4} value={reason} /></label>
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <footer><button className="secondary-button" onClick={onCancel} type="button">取消</button><button className="primary-button" disabled={submitting} type="submit">{submitting ? "正在提交" : confirmLabel}</button></footer>
      </form>
    </DialogFrame>
  );
}

export function AssignmentDialog({
  title,
  confirmLabel,
  allowSkillVersion,
  onCancel,
  onConfirm,
}: {
  title: string;
  confirmLabel: string;
  allowSkillVersion: boolean;
  onCancel: () => void;
  onConfirm: (command: AssignmentCommand) => Promise<void>;
}) {
  const [scopeType, setScopeType] = useState<ContentScopeType>(allowSkillVersion ? "skill_version" : "device");
  const [scopeId, setScopeId] = useState("");
  const [activeFrom, setActiveFrom] = useState("");
  const [expiresAt, setExpiresAt] = useState("");
  const [reason, setReason] = useState("");
  const [idempotencyKey] = useState(newIdempotencyKey);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      await onConfirm({
        scopeType,
        scopeId: scopeType === "organization" ? null : scopeId.trim(),
        activeFrom: toIso(activeFrom),
        expiresAt: toIso(expiresAt),
        reason: reason.trim(),
        idempotencyKey,
      });
    } catch (cause) {
      setError(errorMessage(cause, "授权失败，请检查范围和服务状态。"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <DialogFrame eyebrow="授权范围" onCancel={onCancel} title={title}>
      <form onSubmit={submit}>
        <div className="dialog-grid two-column">
          <label>授权范围<select aria-label="授权范围" onChange={(event) => setScopeType(event.target.value as ContentScopeType)} value={scopeType}>
            <option value="organization">当前组织</option><option value="project">项目</option><option value="profile">人员</option><option value="device">眼镜设备</option>{allowSkillVersion ? <option value="skill_version">技能版本</option> : null}
          </select></label>
          <label>范围 ID<input aria-label="范围 ID" disabled={scopeType === "organization"} onChange={(event) => setScopeId(event.target.value)} required={scopeType !== "organization"} value={scopeId} /></label>
          <label>生效时间<input aria-label="生效时间" onChange={(event) => setActiveFrom(event.target.value)} type="datetime-local" value={activeFrom} /></label>
          <label>到期时间<input aria-label="到期时间" onChange={(event) => setExpiresAt(event.target.value)} type="datetime-local" value={expiresAt} /></label>
        </div>
        <label>操作原因<textarea aria-label="操作原因" minLength={3} onChange={(event) => setReason(event.target.value)} required rows={3} value={reason} /></label>
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <footer><button className="secondary-button" onClick={onCancel} type="button">取消</button><button className="primary-button" disabled={submitting} type="submit">{submitting ? "正在授权" : confirmLabel}</button></footer>
      </form>
    </DialogFrame>
  );
}

export function SkillDraftDialog({ onCancel, onSubmit }: { onCancel: () => void; onSubmit: (command: SkillDraftCommand) => Promise<void> }) {
  const [skillKey, setSkillKey] = useState("");
  const [version, setVersion] = useState("1.0.0");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [requiredInputs, setRequiredInputs] = useState("");
  const [knowledgeScopes, setKnowledgeScopes] = useState("");
  const [steps, setSteps] = useState("");
  const [reason, setReason] = useState("");
  const [idempotencyKey] = useState(newIdempotencyKey);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      const normalizedSteps = splitLines(steps).map((instruction, index) => ({ id: `step_${index + 1}`, instruction, risk: "low" }));
      await onSubmit({
        skillKey: skillKey.trim(), version: version.trim(), name: name.trim(), description: description.trim(),
        rules: {
          applicableWhen: {}, excludedWhen: {}, requiredInputs: splitList(requiredInputs), evidenceSchema: {},
          steps: normalizedSteps, safety: {}, outputConstraints: {}, knowledgeScopes: splitList(knowledgeScopes),
        },
        testCases: [], reason: reason.trim(), idempotencyKey,
      });
    } catch (cause) {
      setError(errorMessage(cause, "创建失败，请检查字段后重试。"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <DialogFrame eyebrow="受控规则包" onCancel={onCancel} title="新建技能草稿">
      <form onSubmit={submit}>
        <div className="dialog-grid two-column">
          <label>技能名称<input aria-label="技能名称" onChange={(event) => setName(event.target.value)} required value={name} /></label>
          <label>技能标识<input aria-label="技能标识" onChange={(event) => setSkillKey(event.target.value)} pattern="[A-Za-z0-9][A-Za-z0-9._:@-]*" required value={skillKey} /></label>
          <label>版本号<input aria-label="版本号" onChange={(event) => setVersion(event.target.value)} pattern="[0-9]+\.[0-9]+\.[0-9]+" required value={version} /></label>
          <label>所需输入<input aria-label="所需输入" onChange={(event) => setRequiredInputs(event.target.value)} placeholder="alarm_code, device_model" value={requiredInputs} /></label>
        </div>
        <label>技能说明<textarea aria-label="技能说明" onChange={(event) => setDescription(event.target.value)} required rows={3} value={description} /></label>
        <label>知识范围<input aria-label="知识范围" onChange={(event) => setKnowledgeScopes(event.target.value)} placeholder="hvac/ddc" required value={knowledgeScopes} /></label>
        <label>维修步骤<textarea aria-label="维修步骤" onChange={(event) => setSteps(event.target.value)} placeholder="每行一个受控步骤" required rows={5} value={steps} /></label>
        <label>创建原因<textarea aria-label="创建原因" minLength={3} onChange={(event) => setReason(event.target.value)} required rows={3} value={reason} /></label>
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <footer><button className="secondary-button" onClick={onCancel} type="button">取消</button><button className="primary-button" disabled={submitting} type="submit">{submitting ? "正在创建" : "创建草稿"}</button></footer>
      </form>
    </DialogFrame>
  );
}

export function KnowledgeDraftDialog({ onCancel, onSubmit }: { onCancel: () => void; onSubmit: (command: KnowledgeDraftCommand, file: File | null) => Promise<void> }) {
  const [form, setForm] = useState({
    knowledgeKey: "", version: "1", title: "", summary: "", sourceType: "manual", sourceReference: "",
    language: "zh-CN", sensitivity: "internal", license: "华方内部授权", projectIds: "", deviceModels: "",
    skillIds: "", knowledgeScopes: "", content: "", reason: "",
  });
  const [file, setFile] = useState<File | null>(null);
  const [idempotencyKey] = useState(newIdempotencyKey);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const set = (field: keyof typeof form) => (event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => setForm((current) => ({ ...current, [field]: event.target.value }));

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      if (form.sourceType === "uploaded_file" && !file) {
        setError("请选择要导入的 TXT、Markdown、PDF 或 DOCX 文件。");
        return;
      }
      await onSubmit({
        knowledgeKey: form.knowledgeKey.trim(), version: Number(form.version), title: form.title.trim(), summary: form.summary.trim(),
        sourceType: form.sourceType as KnowledgeDraftCommand["sourceType"], sourceReference: form.sourceReference.trim(), language: form.language.trim(),
        sensitivity: form.sensitivity as KnowledgeDraftCommand["sensitivity"], license: form.license.trim(), projectIds: splitList(form.projectIds),
        deviceModels: splitList(form.deviceModels), skillIds: splitList(form.skillIds), knowledgeScopes: splitList(form.knowledgeScopes),
        content: form.sourceType === "uploaded_file" ? "" : form.content.trim(), reason: form.reason.trim(), idempotencyKey,
      }, file);
    } catch (cause) {
      setError(errorMessage(cause, "创建失败，请检查知识元数据后重试。"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <DialogFrame eyebrow="华方知识库" onCancel={onCancel} title="新建知识草稿">
      <form onSubmit={submit}>
        <div className="dialog-grid two-column">
          <label>知识标题<input aria-label="知识标题" onChange={set("title")} required value={form.title} /></label>
          <label>知识标识<input aria-label="知识标识" onChange={set("knowledgeKey")} pattern="[A-Za-z0-9][A-Za-z0-9._:@-]*" required value={form.knowledgeKey} /></label>
          <label>版本号<input aria-label="版本号" min="1" onChange={set("version")} required type="number" value={form.version} /></label>
          <label>来源类型<select aria-label="来源类型" onChange={(event) => { const sourceType = event.target.value; setForm((current) => ({ ...current, sourceType, sourceReference: sourceType === "uploaded_file" ? file?.name ?? "" : "" })); if (sourceType !== "uploaded_file") setFile(null); }} value={form.sourceType}><option value="manual">内部手册</option><option value="uploaded_file">上传文件</option><option value="case">完成案例</option><option value="external_link">外部链接</option></select></label>
          {form.sourceType === "uploaded_file" ? <label>知识附件<span className="file-input-control"><FileUp size={16} /><span>{file?.name ?? "选择 TXT、Markdown、PDF 或 DOCX"}</span><input accept=".txt,.md,.markdown,.pdf,.docx,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document" aria-label="知识附件" onChange={(event) => { const selected = event.target.files?.[0] ?? null; setFile(selected); setForm((current) => ({ ...current, sourceReference: selected?.name ?? "" })); }} type="file" /></span><small>TXT/Markdown 可立即解析；PDF/DOCX 会保留原件并明确等待真实解析器。</small></label> : <label>来源标识<input aria-label="来源标识" onChange={set("sourceReference")} required value={form.sourceReference} /></label>}
          <label>敏感级别<select aria-label="敏感级别" onChange={set("sensitivity")} value={form.sensitivity}><option value="public">公开</option><option value="internal">内部</option><option value="confidential">机密</option><option value="restricted">受限</option></select></label>
          <label>语言<input aria-label="语言" onChange={set("language")} required value={form.language} /></label>
          <label>许可信息<input aria-label="许可信息" onChange={set("license")} required value={form.license} /></label>
          <label>适用设备型号<input aria-label="适用设备型号" onChange={set("deviceModels")} placeholder="HF-DDC-100" value={form.deviceModels} /></label>
          <label>适用 Skill<input aria-label="适用 Skill" onChange={set("skillIds")} placeholder="hvac_ddc_repair" value={form.skillIds} /></label>
        </div>
        <label>知识摘要<textarea aria-label="知识摘要" onChange={set("summary")} required rows={3} value={form.summary} /></label>
        <label>知识范围<input aria-label="知识范围" onChange={set("knowledgeScopes")} placeholder="hvac/ddc" required value={form.knowledgeScopes} /></label>
        <label>项目 ID<input aria-label="项目 ID" onChange={set("projectIds")} placeholder="多个 UUID 用逗号分隔" value={form.projectIds} /></label>
        {form.sourceType !== "uploaded_file" ? <label>知识正文<textarea aria-label="知识正文" onChange={set("content")} required rows={8} value={form.content} /></label> : null}
        <label>创建原因<textarea aria-label="创建原因" minLength={3} onChange={set("reason")} required rows={3} value={form.reason} /></label>
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <footer><button className="secondary-button" onClick={onCancel} type="button">取消</button><button className="primary-button" disabled={submitting} type="submit">{submitting ? (form.sourceType === "uploaded_file" ? "正在创建并上传" : "正在创建") : (form.sourceType === "uploaded_file" ? "创建并上传" : "创建草稿")}</button></footer>
      </form>
    </DialogFrame>
  );
}

function splitList(value: string): string[] {
  return value.split(/[,，\n]/).map((item) => item.trim()).filter(Boolean);
}

function splitLines(value: string): string[] {
  return value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean);
}

function toIso(value: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function newIdempotencyKey(): string {
  return typeof crypto.randomUUID === "function" ? crypto.randomUUID() : `web-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}

function knowledgeCaseErrorMessage(cause: unknown): string {
  const code = typeof cause === "object" && cause !== null && "code" in cause
    ? String((cause as { code?: unknown }).code ?? "")
    : "";
  if (code === "task_evidence_not_synced") return "现场证据尚未同步完成，请先重传媒体后再创建知识草稿。";
  if (code === "task_not_completed") return "任务尚未完成或关闭，不能沉淀为知识草稿。";
  if (code === "task_completion_not_confirmed") return "缺少眼镜端人工确认完成快照，请先完成任务同步。";
  return errorMessage(cause, "创建失败，请检查任务同步和服务状态后重试。");
}
