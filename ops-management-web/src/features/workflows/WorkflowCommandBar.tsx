import { useState } from "react";
import { Save, Send, ShieldCheck, X } from "lucide-react";
import { ManagementApiError, type ManagementApi } from "../../api/management-api";
import type {
  WorkflowDefinition,
  WorkflowDraft,
  WorkflowValidationError,
  WorkflowVersion,
} from "../../api/workflow-types";

export type WorkflowCommandApi = Pick<
  ManagementApi,
  "saveWorkflowDraft" | "validateWorkflow" | "publishWorkflow"
>;

type CommandState = "idle" | "saving" | "validating" | "publishing";

export function WorkflowCommandBar({
  api,
  dirty,
  draft,
  workflow,
  onSaved,
  onPublished,
}: {
  api: WorkflowCommandApi;
  dirty: boolean;
  draft: WorkflowDraft;
  workflow: WorkflowDefinition;
  onSaved: (workflow: WorkflowDefinition) => void;
  onPublished: (version: WorkflowVersion) => void;
}) {
  const [commandState, setCommandState] = useState<CommandState>("idle");
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [validationErrors, setValidationErrors] = useState<WorkflowValidationError[]>([]);
  const [publishOpen, setPublishOpen] = useState(false);
  const [publishReason, setPublishReason] = useState("");
  const [minAppVersionCode, setMinAppVersionCode] = useState(9000);
  const [confirmation, setConfirmation] = useState("");
  const [publishIdempotencyKey, setPublishIdempotencyKey] = useState("");

  const busy = commandState !== "idle";
  const canPublish = !dirty && !busy && publishReason.trim().length >= 3 &&
    Number.isInteger(minAppVersionCode) && minAppVersionCode >= 9000 &&
    confirmation === "PUBLISH_WORKFLOW";

  async function saveDraft() {
    setCommandState("saving");
    clearFeedback();
    try {
      const saved = await api.saveWorkflowDraft(workflow.id, draft);
      onSaved(saved);
      setNotice("草稿已保存");
    } catch (cause) {
      showError(cause, "草稿保存失败，当前修改仍保留在浏览器中。");
    } finally {
      setCommandState("idle");
    }
  }

  async function validateDraft() {
    setCommandState("validating");
    clearFeedback();
    try {
      const result = await api.validateWorkflow(workflow.id);
      setValidationErrors(result.validationErrors);
      if (result.valid) setNotice("服务端校验通过");
    } catch (cause) {
      const errors = managementValidationErrors(cause);
      setValidationErrors(errors);
      if (errors.length === 0) {
        showError(cause, "服务端校验失败，请重试。");
      }
    } finally {
      setCommandState("idle");
    }
  }

  function openPublish() {
    setPublishOpen(true);
    setError("");
    setPublishIdempotencyKey(newIdempotencyKey("publish", workflow.id));
  }

  function closePublish() {
    if (commandState === "publishing") return;
    setPublishOpen(false);
    setPublishReason("");
    setConfirmation("");
    setMinAppVersionCode(9000);
    setPublishIdempotencyKey("");
    setError("");
  }

  async function publishWorkflow() {
    if (!canPublish) return;
    const idempotencyKey = publishIdempotencyKey ||
      newIdempotencyKey("publish", workflow.id);
    if (!publishIdempotencyKey) setPublishIdempotencyKey(idempotencyKey);
    setCommandState("publishing");
    setError("");
    setNotice("");
    try {
      const version = await api.publishWorkflow(workflow.id, {
        reason: publishReason.trim(),
        minAppVersionCode,
        idempotencyKey,
      });
      onPublished(version);
      setNotice(`工作流 v${version.version_number} 已发布`);
      setPublishOpen(false);
      setPublishReason("");
      setConfirmation("");
      setPublishIdempotencyKey("");
    } catch (cause) {
      showError(cause, "工作流发布失败，未生成新版本，可重试本次操作。");
    } finally {
      setCommandState("idle");
    }
  }

  function clearFeedback() {
    setNotice("");
    setError("");
    setValidationErrors([]);
  }

  function showError(cause: unknown, fallback: string) {
    if (cause instanceof ManagementApiError) {
      setError(commandErrorText(cause));
      return;
    }
    setError(cause instanceof Error && cause.message ? cause.message : fallback);
  }

  return (
    <div className="workflow-command-area">
      <div className="workflow-command-status" aria-live="polite">
        <span className={dirty ? "draft-state dirty" : "draft-state"}>
          {dirty ? "有未保存修改" : "当前草稿已保存"}
        </span>
        {notice ? <strong>{notice}</strong> : null}
      </div>
      <div className="workflow-command-buttons">
        <button
          className="secondary-button"
          disabled={!dirty || busy}
          onClick={saveDraft}
          type="button"
        >
          <Save size={16} />{commandState === "saving" ? "正在保存" : "保存草稿"}
        </button>
        <button
          className="secondary-button"
          disabled={dirty || busy}
          onClick={validateDraft}
          type="button"
        >
          <ShieldCheck size={16} />{commandState === "validating" ? "正在校验" : "服务端校验"}
        </button>
        <button
          className="primary-button"
          disabled={dirty || busy}
          onClick={openPublish}
          type="button"
        >
          <Send size={16} />发布工作流
        </button>
      </div>

      {validationErrors.length > 0 ? (
        <div className="workflow-command-errors" role="alert">
          {validationErrors.map((item, index) => (
            <span key={`${item.code}-${item.path}-${index}`}>{item.code} · {item.path}</span>
          ))}
        </div>
      ) : null}
      {error && !publishOpen ? <div className="workflow-command-errors" role="alert">{error}</div> : null}

      {publishOpen ? (
        <div className="dialog-backdrop" role="presentation">
          <section aria-labelledby="workflow-publish-title" aria-modal="true" className="workflow-dialog workflow-publish-dialog" role="dialog">
            <header>
              <div>
                <span className="eyebrow">签名发布</span>
                <h2 id="workflow-publish-title">发布不可变工作流版本</h2>
              </div>
              <button aria-label="关闭发布窗口" className="icon-button" disabled={commandState === "publishing"} onClick={closePublish} title="关闭" type="button">
                <X size={18} />
              </button>
            </header>
            <div className="workflow-command-form">
              <label>
                发布理由
                <textarea
                  aria-label="发布理由"
                  maxLength={1000}
                  onChange={(event) => setPublishReason(event.target.value)}
                  rows={3}
                  value={publishReason}
                />
              </label>
              <label>
                最低 V9 版本号
                <input
                  aria-label="最低 V9 版本号"
                  min={9000}
                  onChange={(event) => setMinAppVersionCode(event.target.valueAsNumber)}
                  type="number"
                  value={Number.isNaN(minAppVersionCode) ? "" : minAppVersionCode}
                />
              </label>
              <label>
                确认词
                <input
                  aria-label="确认词"
                  autoComplete="off"
                  onChange={(event) => setConfirmation(event.target.value)}
                  value={confirmation}
                />
                <small>输入 PUBLISH_WORKFLOW</small>
              </label>
              {error ? <div className="workflow-command-errors" role="alert">{error}</div> : null}
            </div>
            <footer>
              <button className="secondary-button" disabled={commandState === "publishing"} onClick={closePublish} type="button">取消</button>
              <button className="primary-button" disabled={!canPublish} onClick={publishWorkflow} type="button">
                <Send size={16} />{commandState === "publishing" ? "正在发布" : "确认发布"}
              </button>
            </footer>
          </section>
        </div>
      ) : null}
    </div>
  );
}

function managementValidationErrors(cause: unknown): WorkflowValidationError[] {
  if (!(cause instanceof ManagementApiError)) return [];
  const value = cause.payload.validationErrors;
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!isRecord(item) || typeof item.code !== "string" || typeof item.path !== "string") return [];
    return [{ code: item.code, path: item.path }];
  });
}

function commandErrorText(error: ManagementApiError): string {
  if (error.code === "signing_unavailable") {
    return "服务端签名暂不可用，未发布任何版本，可重试本次操作。";
  }
  if (error.code === "workflow_invalid") {
    return "工作流未通过服务端校验，未发布任何版本。";
  }
  if (error.code === "confirmation_required") {
    return "服务端拒绝发布：缺少有效的二次确认。";
  }
  return error.message;
}

function newIdempotencyKey(operation: string, targetId: string): string {
  const random = typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `${operation}:${targetId}:${random}`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
