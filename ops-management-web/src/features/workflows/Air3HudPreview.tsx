import {
  Bot,
  Camera,
  CheckCircle2,
  ClipboardList,
  MessageSquareText,
  ShieldAlert,
  Video,
} from "lucide-react";
import type {
  WorkflowDraftNode,
  WorkflowNodeCatalogItem,
  WorkflowPageTemplate,
} from "../../api/workflow-types";

const riskLabels: Record<string, string> = {
  low: "低风险",
  medium: "中风险",
  high: "高风险",
  critical: "严重风险",
};

export function Air3HudPreview({
  catalog,
  node,
}: {
  catalog: WorkflowNodeCatalogItem;
  node: WorkflowDraftNode;
}) {
  const template = catalog.pageTemplate;
  const config = node.config;
  const title = stringConfig(config, "title") ?? catalog.label;
  const description = stringConfig(config, "description") ?? previewDescription(template);
  const risk = riskLabels[stringConfig(config, "riskLevel") ?? ""];
  const voicePrompt = stringConfig(config, "voicePrompt");

  return (
    <section aria-label="Air3 HUD 预览" className="hud-preview-section">
      <div className="hud-preview-heading">
        <span>Air3 HUD 预览</span>
        <small>1920 × 1080</small>
      </div>
      <div className="air3-hud" data-template={template} data-testid="air3-hud-preview">
        <div className="hud-topbar">
          <span>叮当 AI 运维眼镜</span>
          <span>V9 现场指导</span>
        </div>
        <div className="hud-stage">
          <div className="hud-viewfinder">
            <PreviewBody catalog={catalog} config={config} description={description} template={template} title={title} />
            <span aria-hidden="true" className="hud-crosshair" />
          </div>
          <div className="hud-stage-prompt">
            {template === "none" ? "此节点不生成独立页面" : "把关键画面放入绿色框内"}
          </div>
        </div>
        <div className="hud-status-row">
          <span className={risk ? "hud-risk active" : "hud-risk"}>{risk ?? "现场任务"}</span>
          <span>{voicePrompt ?? "等待现场操作"}</span>
        </div>
        <div className="hud-action-row">
          <div><strong>中心点击</strong><span>{primaryAction(template)}</span></div>
          <div><strong>长按中心</strong><span>语音补充</span></div>
          <div><strong>返回键</strong><span>返回上一步</span></div>
        </div>
      </div>
    </section>
  );
}

function PreviewBody({
  catalog,
  config,
  description,
  template,
  title,
}: {
  catalog: WorkflowNodeCatalogItem;
  config: Record<string, unknown>;
  description: string;
  template: WorkflowPageTemplate;
  title: string;
}) {
  if (template === "none") {
    return (
      <div className="hud-message hud-control-node">
        <ClipboardList aria-hidden="true" size={24} />
        <strong>{catalog.label}</strong>
        <span>{description}</span>
      </div>
    );
  }
  if (template === "evidence_capture") {
    const isVideo = catalog.type === "video_capture";
    const count = numberConfig(config, "minCount") ?? 1;
    return (
      <div className="hud-message">
        {isVideo ? <Video aria-hidden="true" size={24} /> : <Camera aria-hidden="true" size={24} />}
        <h3>{title}</h3>
        <p>{`至少采集 ${count} ${isVideo ? "段视频" : "张照片"}`}</p>
        {stringConfig(config, "evidenceKey") ? <code>{stringConfig(config, "evidenceKey")}</code> : null}
      </div>
    );
  }
  if (template === "form") {
    const fields = recordList(config.fields ?? config.outputFields).slice(0, 3);
    return (
      <div className="hud-message hud-form-preview">
        <ClipboardList aria-hidden="true" size={22} />
        <h3>{title}</h3>
        <p>{description}</p>
        <ul>{fields.length ? fields.map((field, index) => <li key={index}>{stringValue(field.label) ?? stringValue(field.key) ?? `字段 ${index + 1}`}</li>) : <li>等待填写</li>}</ul>
      </div>
    );
  }
  if (template === "choice") {
    const options = recordList(config.options).slice(0, 4);
    return (
      <div className="hud-message hud-choice-preview">
        <MessageSquareText aria-hidden="true" size={22} />
        <h3>{title}</h3>
        <p>{description}</p>
        <div>{options.length ? options.map((option, index) => <span key={index}>{stringValue(option.label) ?? `选项 ${index + 1}`}</span>) : <span>等待选择</span>}</div>
      </div>
    );
  }
  if (template === "conversation") {
    return (
      <div className="hud-message">
        <Bot aria-hidden="true" size={24} />
        <h3>{title}</h3>
        <p>{description}</p>
        <span className="hud-mode-label">{catalog.type === "expert_call" ? "专家协同" : "AI 任务对话"}</span>
      </div>
    );
  }
  if (template === "confirmation") {
    return (
      <div className="hud-message hud-confirm-preview">
        <ShieldAlert aria-hidden="true" size={24} />
        <h3>{title}</h3>
        <p>{stringConfig(config, "confirmationText") ?? description}</p>
        <span>确认 / 取消</span>
      </div>
    );
  }
  if (template === "completion") {
    return (
      <div className="hud-message hud-complete-preview">
        <CheckCircle2 aria-hidden="true" size={25} />
        <h3>{title}</h3>
        <p>{description}</p>
        <span>{stringList(config.summaryFields).length ? `${stringList(config.summaryFields).length} 项摘要字段` : "生成任务结束摘要"}</span>
      </div>
    );
  }
  return (
    <div className="hud-message">
      <ClipboardList aria-hidden="true" size={24} />
      <h3>{title}</h3>
      <p>{description}</p>
      {stringConfig(config, "riskNotice") ? <span className="hud-risk-notice">{stringConfig(config, "riskNotice")}</span> : null}
    </div>
  );
}

function previewDescription(template: WorkflowPageTemplate): string {
  return ({
    instruction: "查看当前操作要求",
    evidence_capture: "采集当前步骤所需证据",
    form: "填写当前步骤信息",
    choice: "选择符合现场情况的选项",
    conversation: "进入当前任务对话",
    confirmation: "核对信息后人工确认",
    completion: "核对步骤、证据与同步状态",
    none: "由工作流状态机在后台执行",
  })[template];
}

function primaryAction(template: WorkflowPageTemplate): string {
  return ({
    instruction: "下一步",
    evidence_capture: "拍摄/录制",
    form: "确认填写",
    choice: "确认选择",
    conversation: "继续对话",
    confirmation: "人工确认",
    completion: "完成任务",
    none: "继续流程",
  })[template];
}

function stringConfig(config: Record<string, unknown>, key: string): string | null {
  return stringValue(config[key]);
}

function stringValue(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value : null;
}

function numberConfig(config: Record<string, unknown>, key: string): number | null {
  return typeof config[key] === "number" && Number.isFinite(config[key]) ? Number(config[key]) : null;
}

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

function recordList(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? value.filter(isRecord) : [];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
