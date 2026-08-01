import { Plus, Trash2 } from "lucide-react";
import type {
  WorkflowConfigField,
  WorkflowDraftNode,
  WorkflowNodeCatalogItem,
} from "../../api/workflow-types";
import "./NodeInspector.css";

type OptionItem = { value: string; label: string };
type FormFieldItem = {
  key: string;
  label: string;
  type: "text" | "number" | "boolean" | "single_choice" | "multi_choice";
  required: boolean;
  options?: OptionItem[];
  min?: number;
  max?: number;
  unit?: string;
};
type MappingItem = { sourceField: string; targetField: string };

const valueLabels: Record<string, string> = {
  low: "低风险",
  medium: "中风险",
  high: "高风险",
  critical: "严重风险",
  allowed: "允许离线执行",
  blocked: "断网时暂停",
  server_required: "必须连接服务端",
  next: "下一步",
  back: "上一步",
  home: "返回首页",
  capture: "采集",
  retake: "重拍/重录",
  confirm: "确认",
  cancel: "取消",
  retry: "重试",
  expert: "呼叫专家",
  complete: "完成",
  skip: "跳过",
};

const formFieldTypeLabels: Record<FormFieldItem["type"], string> = {
  text: "文字",
  number: "数字",
  boolean: "是/否",
  single_choice: "单选",
  multi_choice: "多选",
};

export function NodeInspector({
  catalog,
  node,
  onChange,
  onDelete,
}: {
  catalog: WorkflowNodeCatalogItem;
  node: WorkflowDraftNode;
  onChange: (config: Record<string, unknown>) => void;
  onDelete?: () => void;
}) {
  function setValue(key: string, value: unknown) {
    const next = { ...node.config };
    if (value === undefined || value === "") delete next[key];
    else next[key] = value;
    onChange(next);
  }
  const configuredKeys = new Set(catalog.fields.map((field) => field.key));
  const unknownKeys = Object.keys(node.config).filter((key) => !configuredKeys.has(key));

  return (
    <section aria-label="节点属性配置" className="node-config-panel">
      <dl className="node-summary compact">
        <div><dt>节点类型</dt><dd>{catalog.label}</dd></div>
        <div><dt>节点标识</dt><dd>{node.nodeId}</dd></div>
        <div><dt>页面模板</dt><dd>{catalog.pageTemplate}</dd></div>
        <div><dt>所需能力</dt><dd>{catalog.requiredCapability ?? "基础运行时"}</dd></div>
      </dl>

      {unknownKeys.length ? (
        <div className="node-config-warning" role="alert">
          当前草稿包含不受支持的配置：{unknownKeys.join("、")}
        </div>
      ) : null}

      <div className="node-config-fields">
        {catalog.fields.map((field) => (
          <ConfigField
            field={field}
            key={field.key}
            nodeId={node.nodeId}
            onChange={(value) => setValue(field.key, value)}
            value={node.config[field.key]}
          />
        ))}
        {catalog.fields.length === 0 ? (
          <p className="node-config-empty">该节点没有可配置属性</p>
        ) : null}
      </div>

      {onDelete ? (
        <div className="node-config-danger">
          <button aria-label="删除节点" className="icon-button" onClick={onDelete} title="删除节点" type="button">
            <Trash2 size={16} />
          </button>
        </div>
      ) : null}
    </section>
  );
}

function ConfigField({
  field,
  nodeId,
  value,
  onChange,
}: {
  field: WorkflowConfigField;
  nodeId: string;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  const id = `${nodeId}-${field.key}`.replace(/[^A-Za-z0-9_-]/g, "-");
  const issue = fieldIssue(field, value);
  const describedBy = issue ? `${id}-error` : undefined;

  if (field.kind === "boolean") {
    return (
      <div className="config-field boolean-field">
        <label htmlFor={id}>
          <input
            checked={value === true}
            id={id}
            onChange={(event) => onChange(event.target.checked)}
            type="checkbox"
          />
          <span>{field.label}</span>
        </label>
        <FieldIssue id={describedBy} issue={issue} />
      </div>
    );
  }

  if (field.kind === "select_list" && field.values?.length) {
    const selected = stringList(value);
    return (
      <fieldset className="config-field checkbox-fieldset">
        <legend>{field.label}{field.required ? <RequiredMark /> : null}</legend>
        <div className="checkbox-grid">
          {field.values.map((option) => (
            <label key={option}>
              <input
                checked={selected.includes(option)}
                onChange={(event) => {
                  const next = event.target.checked
                    ? [...selected, option]
                    : selected.filter((item) => item !== option);
                  onChange(next.length ? next : undefined);
                }}
                type="checkbox"
              />
              <span>{valueLabel(option)}</span>
            </label>
          ))}
        </div>
        <FieldIssue id={describedBy} issue={issue} />
      </fieldset>
    );
  }

  if (field.kind === "uuid_list" || field.kind === "select_list") {
    return (
      <StringListEditor
        field={field}
        issue={issue}
        onChange={onChange}
        values={stringList(value)}
      />
    );
  }

  if (field.kind === "options") {
    return (
      <OptionsEditor
        field={field}
        issue={issue}
        onChange={onChange}
        options={optionList(value)}
      />
    );
  }

  if (field.kind === "fields") {
    return (
      <FormFieldsEditor
        field={field}
        fields={formFieldList(value)}
        issue={issue}
        onChange={onChange}
      />
    );
  }

  if (field.kind === "mappings") {
    return (
      <MappingsEditor
        field={field}
        issue={issue}
        mappings={mappingList(value)}
        onChange={onChange}
      />
    );
  }

  return (
    <div className="config-field">
      <label htmlFor={id}>{field.label}{field.required ? <RequiredMark /> : null}</label>
      {field.kind === "textarea" ? (
        <textarea
          aria-describedby={describedBy}
          aria-invalid={Boolean(issue)}
          id={id}
          maxLength={field.maxLength}
          onChange={(event) => onChange(event.target.value || undefined)}
          rows={4}
          value={textValue(value)}
        />
      ) : field.kind === "integer" ? (
        <input
          aria-describedby={describedBy}
          aria-invalid={Boolean(issue)}
          id={id}
          max={field.max}
          min={field.min}
          onChange={(event) => onChange(event.target.value === "" ? undefined : Number(event.target.value))}
          step={1}
          type="number"
          value={numberValue(value)}
        />
      ) : field.kind === "select" ? (
        <select
          aria-describedby={describedBy}
          aria-invalid={Boolean(issue)}
          id={id}
          onChange={(event) => onChange(event.target.value || undefined)}
          value={textValue(value)}
        >
          <option value="">未设置</option>
          {field.values?.map((option) => (
            <option key={option} value={option}>{valueLabel(option)}</option>
          ))}
        </select>
      ) : (
        <input
          aria-describedby={describedBy}
          aria-invalid={Boolean(issue)}
          id={id}
          maxLength={field.maxLength}
          onChange={(event) => onChange(event.target.value || undefined)}
          type="text"
          value={textValue(value)}
        />
      )}
      <FieldIssue id={describedBy} issue={issue} />
    </div>
  );
}

function StringListEditor({
  field,
  values,
  issue,
  onChange,
}: {
  field: WorkflowConfigField;
  values: string[];
  issue: string | null;
  onChange: (value: unknown) => void;
}) {
  function commit(next: string[]) {
    onChange(next.length ? next : undefined);
  }
  return (
    <fieldset className="config-field repeat-editor">
      <legend>{field.label}{field.required ? <RequiredMark /> : null}</legend>
      {values.map((item, index) => (
        <div className="repeat-row string-row" key={index}>
          <label className="sr-only" htmlFor={`${field.key}-${index}`}>{field.label} {index + 1}</label>
          <input
            id={`${field.key}-${index}`}
            onChange={(event) => commit(values.map((current, itemIndex) => itemIndex === index ? event.target.value : current))}
            type="text"
            value={item}
          />
          <RemoveButton label={`删除${field.label} ${index + 1}`} onClick={() => commit(values.filter((_, itemIndex) => itemIndex !== index))} />
        </div>
      ))}
      <AddButton disabled={values.length >= (field.max ?? 100)} label={`添加${field.label}`} onClick={() => commit([...values, ""])} />
      <FieldIssue issue={issue} />
    </fieldset>
  );
}

function OptionsEditor({
  field,
  options,
  issue,
  onChange,
  addLabel = "添加选项",
}: {
  field: WorkflowConfigField;
  options: OptionItem[];
  issue: string | null;
  onChange: (value: unknown) => void;
  addLabel?: string;
}) {
  function commit(next: OptionItem[]) {
    onChange(next.length ? next : undefined);
  }
  function update(index: number, patch: Partial<OptionItem>) {
    commit(options.map((item, itemIndex) => itemIndex === index ? { ...item, ...patch } : item));
  }
  return (
    <fieldset className="config-field repeat-editor">
      <legend>{field.label}{field.required ? <RequiredMark /> : null}</legend>
      {options.map((option, index) => (
        <fieldset aria-label={`${field.label} ${index + 1}`} className="repeat-item" key={index}>
          <div className="repeat-item-heading"><span>{index + 1}</span><RemoveButton label={`删除${field.label} ${index + 1}`} onClick={() => commit(options.filter((_, itemIndex) => itemIndex !== index))} /></div>
          <label>选项值<input aria-label="选项值" onChange={(event) => update(index, { value: event.target.value })} value={option.value} /></label>
          <label>显示名称<input aria-label="显示名称" onChange={(event) => update(index, { label: event.target.value })} value={option.label} /></label>
        </fieldset>
      ))}
      <AddButton disabled={options.length >= (field.max ?? 50)} label={addLabel} onClick={() => commit([...options, { value: "", label: "" }])} />
      <FieldIssue issue={issue} />
    </fieldset>
  );
}

function FormFieldsEditor({
  field,
  fields,
  issue,
  onChange,
}: {
  field: WorkflowConfigField;
  fields: FormFieldItem[];
  issue: string | null;
  onChange: (value: unknown) => void;
}) {
  function commit(next: FormFieldItem[]) {
    onChange(next.length ? next : undefined);
  }
  function update(index: number, patch: Partial<FormFieldItem>) {
    commit(fields.map((item, itemIndex) => itemIndex === index ? normalizeFormField({ ...item, ...patch }) : item));
  }
  return (
    <fieldset className="config-field repeat-editor">
      <legend>{field.label}{field.required ? <RequiredMark /> : null}</legend>
      {fields.map((item, index) => (
        <fieldset aria-label={`${field.label} ${index + 1}`} className="repeat-item form-field-item" key={index}>
          <div className="repeat-item-heading"><span>{index + 1}</span><RemoveButton label={`删除${field.label} ${index + 1}`} onClick={() => commit(fields.filter((_, itemIndex) => itemIndex !== index))} /></div>
          <label>字段标识<input aria-label="字段标识" onChange={(event) => update(index, { key: event.target.value })} value={item.key} /></label>
          <label>字段名称<input aria-label="字段名称" onChange={(event) => update(index, { label: event.target.value })} value={item.label} /></label>
          <label>字段类型<select aria-label="字段类型" onChange={(event) => update(index, { type: event.target.value as FormFieldItem["type"] })} value={item.type}>{Object.entries(formFieldTypeLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
          <label className="inline-checkbox"><input aria-label="必填字段" checked={item.required} onChange={(event) => update(index, { required: event.target.checked })} type="checkbox" /><span>必填字段</span></label>
          {item.type === "number" ? (
            <div className="form-number-settings">
              <label>最小值<input aria-label="最小值" onChange={(event) => update(index, { min: optionalNumber(event.target.value) })} type="number" value={item.min ?? ""} /></label>
              <label>最大值<input aria-label="最大值" onChange={(event) => update(index, { max: optionalNumber(event.target.value) })} type="number" value={item.max ?? ""} /></label>
              <label>单位<input aria-label="单位" onChange={(event) => update(index, { unit: event.target.value || undefined })} value={item.unit ?? ""} /></label>
            </div>
          ) : null}
          {item.type === "single_choice" || item.type === "multi_choice" ? (
            <OptionsEditor
              addLabel="添加字段选项"
              field={{ key: `${field.key}-${index}-options`, label: "字段选项", kind: "options", max: 50 }}
              issue={null}
              onChange={(options) => update(index, { options: optionList(options) })}
              options={item.options ?? []}
            />
          ) : null}
        </fieldset>
      ))}
      <AddButton disabled={fields.length >= (field.max ?? 30)} label="添加表单字段" onClick={() => commit([...fields, { key: "", label: "", type: "text", required: false }])} />
      <FieldIssue issue={issue} />
    </fieldset>
  );
}

function MappingsEditor({
  field,
  mappings,
  issue,
  onChange,
}: {
  field: WorkflowConfigField;
  mappings: MappingItem[];
  issue: string | null;
  onChange: (value: unknown) => void;
}) {
  function commit(next: MappingItem[]) {
    onChange(next.length ? next : undefined);
  }
  function update(index: number, patch: Partial<MappingItem>) {
    commit(mappings.map((item, itemIndex) => itemIndex === index ? { ...item, ...patch } : item));
  }
  return (
    <fieldset className="config-field repeat-editor">
      <legend>{field.label}{field.required ? <RequiredMark /> : null}</legend>
      {mappings.map((mapping, index) => (
        <fieldset aria-label={`${field.label} ${index + 1}`} className="repeat-item" key={index}>
          <div className="repeat-item-heading"><span>{index + 1}</span><RemoveButton label={`删除${field.label} ${index + 1}`} onClick={() => commit(mappings.filter((_, itemIndex) => itemIndex !== index))} /></div>
          <label>来源字段<input aria-label="来源字段" onChange={(event) => update(index, { sourceField: event.target.value })} value={mapping.sourceField} /></label>
          <label>目标字段<input aria-label="目标字段" onChange={(event) => update(index, { targetField: event.target.value })} value={mapping.targetField} /></label>
        </fieldset>
      ))}
      <AddButton disabled={mappings.length >= (field.max ?? 50)} label="添加参数映射" onClick={() => commit([...mappings, { sourceField: "", targetField: "" }])} />
      <FieldIssue issue={issue} />
    </fieldset>
  );
}

function AddButton({ label, disabled, onClick }: { label: string; disabled: boolean; onClick: () => void }) {
  return <button className="repeat-add" disabled={disabled} onClick={onClick} type="button"><Plus size={14} />{label}</button>;
}

function RemoveButton({ label, onClick }: { label: string; onClick: () => void }) {
  return <button aria-label={label} className="repeat-remove" onClick={onClick} title={label} type="button"><Trash2 size={14} /></button>;
}

function RequiredMark() {
  return <span aria-hidden="true" className="required-mark"> *</span>;
}

function FieldIssue({ id, issue }: { id?: string; issue: string | null }) {
  return issue ? <span className="field-issue" id={id} role="alert">{issue}</span> : null;
}

function fieldIssue(field: WorkflowConfigField, value: unknown): string | null {
  if (value === undefined || value === "") {
    return field.required ? `必须填写${field.label}` : null;
  }
  if (field.kind === "boolean") return typeof value === "boolean" ? null : `${field.label}格式错误`;
  if (field.kind === "integer") {
    if (!Number.isInteger(value)) return "请输入整数";
    if ((field.min !== undefined && Number(value) < field.min) || (field.max !== undefined && Number(value) > field.max)) {
      return `请输入 ${field.min ?? "允许"} 到 ${field.max ?? "允许"} 之间的整数`;
    }
    return null;
  }
  if (field.kind === "select") return typeof value === "string" && field.values?.includes(value) ? null : `${field.label}不在允许范围内`;
  if (field.kind === "select_list" || field.kind === "uuid_list") {
    if (!Array.isArray(value)) return `${field.label}格式错误`;
    const values = stringList(value);
    if (values.length !== value.length) return `${field.label}格式错误`;
    if (values.length > (field.max ?? 100)) return `最多添加 ${field.max} 项`;
    if (values.some((item) => !item.trim())) return "不能包含空项";
    if (values.some((item) => item.length > 160 || unsafeText(item))) return "列表项过长或包含不安全内容";
    if (new Set(values).size !== values.length) return "不能包含重复项";
    if (field.kind === "select_list" && field.values && values.some((item) => !field.values?.includes(item))) return "包含不在允许范围内的选项";
    if (field.kind === "uuid_list" && values.some((item) => !isUuid(item))) return "请输入有效 UUID";
    return null;
  }
  if (field.kind === "options") return optionsIssue(value, field);
  if (field.kind === "fields") return formFieldsIssue(value, field);
  if (field.kind === "mappings") return mappingsIssue(value, field);
  if (typeof value !== "string") return `${field.label}格式错误`;
  if (!value.trim()) return field.required ? `必须填写${field.label}` : `${field.label}不能为空`;
  if (field.maxLength !== undefined && value.length > field.maxLength) return `最多输入 ${field.maxLength} 个字符`;
  if (unsafeText(value)) return "不能包含 HTML、URL 或脚本协议";
  if (field.kind === "identifier" && !identifier(value)) return "只能使用字母、数字、点、冒号、下划线和连字符";
  return null;
}

function optionsIssue(value: unknown, field: WorkflowConfigField, requireOne = true): string | null {
  if (!Array.isArray(value)) return `${field.label}格式错误`;
  if (value.some((item) => !validOptionShape(item))) return `${field.label}格式错误`;
  const options = optionList(value);
  const issues: string[] = [];
  if (options.length > (field.max ?? 50)) issues.push(`最多添加 ${field.max} 项`);
  if (requireOne && options.length === 0) issues.push(`至少添加一项${field.label}`);
  if (options.some((item) => !item.value.trim() || !item.label.trim())) issues.push("选项值和显示名称不能为空");
  if (options.some((item) => item.value.length > 120 || item.label.length > 240 || unsafeText(item.value) || unsafeText(item.label))) issues.push("选项过长或包含不安全内容");
  const values = options.map((item) => item.value);
  if (new Set(values).size !== values.length) issues.push("选项值不能重复");
  return issues.length ? issues.join("；") : null;
}

function formFieldsIssue(value: unknown, field: WorkflowConfigField): string | null {
  if (!Array.isArray(value)) return `${field.label}格式错误`;
  if (value.some((item) => !validFormFieldShape(item))) return `${field.label}格式错误`;
  const fields = formFieldList(value);
  const issues: string[] = [];
  if (fields.length > (field.max ?? 30)) issues.push(`最多添加 ${field.max} 项`);
  if (fields.length === 0) issues.push(`至少添加一项${field.label}`);
  if (fields.some((item) => !identifier(item.key) || !boundedPlainText(item.label, 240))) issues.push("字段标识或名称无效");
  const keys = fields.map((item) => item.key);
  if (new Set(keys).size !== keys.length) issues.push("字段标识不能重复");
  if (fields.some((item) => (item.type === "single_choice" || item.type === "multi_choice") && Boolean(optionsIssue(item.options, { key: "options", label: "字段选项", kind: "options", max: 50 })))) issues.push("选择字段必须至少添加一个选项且选项有效");
  if (fields.some((item) => item.type !== "single_choice" && item.type !== "multi_choice" && item.options !== undefined)) issues.push("非选择字段不能配置选项");
  if (fields.some((item) => item.type === "number" && item.min !== undefined && item.max !== undefined && item.min > item.max)) issues.push("最小值不能大于最大值");
  if (fields.some((item) => item.unit !== undefined && !boundedPlainText(item.unit, 40))) issues.push("单位过长或包含不安全内容");
  return issues.length ? issues.join("；") : null;
}

function mappingsIssue(value: unknown, field: WorkflowConfigField): string | null {
  if (!Array.isArray(value)) return `${field.label}格式错误`;
  if (value.some((item) => !isRecord(item) || !onlyKeys(item, ["sourceField", "targetField"]) || typeof item.sourceField !== "string" || typeof item.targetField !== "string")) return `${field.label}格式错误`;
  const mappings = mappingList(value);
  if (mappings.length > (field.max ?? 50)) return `最多添加 ${field.max} 项`;
  return mappings.every((item) => identifier(item.sourceField) && identifier(item.targetField))
    ? null
    : "来源字段或目标字段无效";
}

function normalizeFormField(field: FormFieldItem): FormFieldItem {
  if (field.type !== "number") {
    delete field.min;
    delete field.max;
    delete field.unit;
  }
  if (field.type !== "single_choice" && field.type !== "multi_choice") delete field.options;
  return field;
}

function optionList(value: unknown): OptionItem[] {
  return Array.isArray(value) ? value.flatMap((item) => isRecord(item) && typeof item.value === "string" && typeof item.label === "string" ? [{ value: item.value, label: item.label }] : []) : [];
}

function formFieldList(value: unknown): FormFieldItem[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (!isRecord(item) || typeof item.key !== "string" || typeof item.label !== "string" || !isFormFieldType(item.type)) return [];
    return [{
      key: item.key,
      label: item.label,
      type: item.type,
      required: item.required === true,
      ...(Array.isArray(item.options) ? { options: optionList(item.options) } : {}),
      ...(typeof item.min === "number" ? { min: item.min } : {}),
      ...(typeof item.max === "number" ? { max: item.max } : {}),
      ...(typeof item.unit === "string" ? { unit: item.unit } : {}),
    }];
  });
}

function mappingList(value: unknown): MappingItem[] {
  return Array.isArray(value) ? value.flatMap((item) => isRecord(item) && typeof item.sourceField === "string" && typeof item.targetField === "string" ? [{ sourceField: item.sourceField, targetField: item.targetField }] : []) : [];
}

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string") : [];
}

function textValue(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function numberValue(value: unknown): number | "" {
  return typeof value === "number" && Number.isFinite(value) ? value : "";
}

function valueLabel(value: string): string {
  return valueLabels[value] ?? value;
}

function optionalNumber(value: string): number | undefined {
  return value === "" ? undefined : Number(value);
}

function identifier(value: string): boolean {
  return /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}$/.test(value.trim());
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

function unsafeText(value: string): boolean {
  return /<\s*\/?\s*[a-z][^>]*>/i.test(value) || /(?:https?|wss?|file):\/\//i.test(value) || /(?:data|javascript):/i.test(value);
}

function boundedPlainText(value: string, maxLength: number): boolean {
  const text = value.trim();
  return Boolean(text && text.length <= maxLength && !unsafeText(text));
}

function validOptionShape(value: unknown): boolean {
  return isRecord(value) &&
    onlyKeys(value, ["value", "label"]) &&
    typeof value.value === "string" &&
    typeof value.label === "string";
}

function validFormFieldShape(value: unknown): boolean {
  if (!isRecord(value) || !onlyKeys(value, ["key", "label", "type", "required", "options", "min", "max", "unit"])) return false;
  return typeof value.key === "string" &&
    typeof value.label === "string" &&
    isFormFieldType(value.type) &&
    (value.required === undefined || typeof value.required === "boolean") &&
    (value.options === undefined || Array.isArray(value.options)) &&
    optionalFiniteNumberValue(value.min) &&
    optionalFiniteNumberValue(value.max) &&
    (value.unit === undefined || typeof value.unit === "string");
}

function onlyKeys(value: Record<string, unknown>, allowed: readonly string[]): boolean {
  return Object.keys(value).every((key) => allowed.includes(key));
}

function optionalFiniteNumberValue(value: unknown): boolean {
  return value === undefined || (typeof value === "number" && Number.isFinite(value));
}

function isFormFieldType(value: unknown): value is FormFieldItem["type"] {
  return typeof value === "string" && Object.hasOwn(formFieldTypeLabels, value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
