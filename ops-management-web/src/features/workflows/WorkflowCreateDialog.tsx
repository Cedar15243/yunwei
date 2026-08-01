import { type FormEvent, useState } from "react";
import { Plus, X } from "lucide-react";
import type {
  FieldAppCreateCommand,
  WorkflowCreateCommand,
} from "../../api/workflow-types";

type FieldAppDialogProps = {
  kind: "field-app";
  onCancel: () => void;
  onSubmit: (command: FieldAppCreateCommand) => Promise<void>;
};

type WorkflowDialogProps = {
  kind: "workflow";
  fieldAppName: string;
  onCancel: () => void;
  onSubmit: (command: WorkflowCreateCommand) => Promise<void>;
};

export function WorkflowCreateDialog(props: FieldAppDialogProps | WorkflowDialogProps) {
  const [name, setName] = useState("");
  const [key, setKey] = useState("");
  const [description, setDescription] = useState("");
  const [entryMode, setEntryMode] = useState<FieldAppCreateCommand["entryMode"]>("both");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      if (props.kind === "field-app") {
        await props.onSubmit({
          appKey: key.trim(),
          name: name.trim(),
          description: description.trim(),
          iconKey: "workflow",
          entryMode,
        });
      } else {
        await props.onSubmit({
          workflowKey: key.trim(),
          title: name.trim(),
          description: description.trim(),
          schemaVersion: 1,
        });
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "创建失败，请稍后重试。");
    } finally {
      setSubmitting(false);
    }
  }

  const isFieldApp = props.kind === "field-app";
  const title = isFieldApp ? "新建现场应用" : "新建工作流";

  return (
    <div className="dialog-backdrop" role="presentation">
      <section
        aria-labelledby="workflow-create-title"
        aria-modal="true"
        className="workflow-dialog"
        role="dialog"
      >
        <header>
          <div>
            <span className="eyebrow">{isFieldApp ? "FIELD APPLICATION" : props.fieldAppName}</span>
            <h2 id="workflow-create-title">{title}</h2>
          </div>
          <button
            aria-label="关闭"
            className="icon-button"
            disabled={submitting}
            onClick={props.onCancel}
            title="关闭"
            type="button"
          >
            <X size={18} />
          </button>
        </header>
        <form onSubmit={submit}>
          <label>
            {isFieldApp ? "应用名称" : "工作流名称"}
            <input
              aria-label={isFieldApp ? "应用名称" : "工作流名称"}
              autoFocus
              maxLength={isFieldApp ? 120 : 160}
              onChange={(event) => setName(event.target.value)}
              required
              value={name}
            />
          </label>
          <label>
            {isFieldApp ? "应用标识" : "工作流标识"}
            <input
              aria-label={isFieldApp ? "应用标识" : "工作流标识"}
              maxLength={64}
              onChange={(event) => setKey(event.target.value)}
              pattern="[a-z][a-z0-9_-]{2,63}"
              required
              value={key}
            />
          </label>
          <label>
            {isFieldApp ? "应用说明" : "工作流说明"}
            <textarea
              aria-label={isFieldApp ? "应用说明" : "工作流说明"}
              maxLength={2000}
              onChange={(event) => setDescription(event.target.value)}
              required
              rows={4}
              value={description}
            />
          </label>
          {isFieldApp ? (
            <label>
              入口方式
              <select
                aria-label="入口方式"
                onChange={(event) => setEntryMode(event.target.value as FieldAppCreateCommand["entryMode"])}
                value={entryMode}
              >
                <option value="both">现场应用与工单</option>
                <option value="independent">独立现场应用</option>
                <option value="work_order">仅工单绑定</option>
              </select>
            </label>
          ) : null}
          {error ? <p className="form-error" role="alert">{error}</p> : null}
          <footer>
            <button className="secondary-button" disabled={submitting} onClick={props.onCancel} type="button">
              取消
            </button>
            <button className="primary-button" disabled={submitting} type="submit">
              <Plus size={17} />
              {submitting ? "正在创建" : isFieldApp ? "创建应用" : "创建并进入编排"}
            </button>
          </footer>
        </form>
      </section>
    </div>
  );
}
