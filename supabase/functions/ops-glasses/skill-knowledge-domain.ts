export type SkillStatus =
  | "draft"
  | "review_pending"
  | "published"
  | "deprecated"
  | "archived";

export type KnowledgeStatus =
  | "uploaded"
  | "scanning"
  | "parsing"
  | "parsed"
  | "review_pending"
  | "published"
  | "expired"
  | "deprecated"
  | "archived";

export type SkillStep = {
  id: string;
  instruction: string;
  risk: "low" | "medium" | "high";
};

export type SkillRules = {
  applicableWhen: Record<string, unknown>;
  excludedWhen: Record<string, unknown>;
  requiredInputs: string[];
  evidenceSchema: Record<string, unknown>;
  steps: SkillStep[];
  safety: Record<string, unknown>;
  outputConstraints: Record<string, unknown>;
  knowledgeScopes: string[];
};

export type SkillTestCase = {
  title: string;
  input: string;
  expected: string;
};

export type SkillDraft = {
  name: string;
  description: string;
  rules: SkillRules;
  testCases: SkillTestCase[];
};

export type KnowledgeDraft = {
  title: string;
  summary: string;
  sourceType: "manual" | "uploaded_file" | "case" | "external_link";
  sourceReference: string;
  language: string;
  sensitivity: "public" | "internal" | "confidential" | "restricted";
  license: string;
  projectIds: string[];
  deviceModels: string[];
  skillIds: string[];
  knowledgeScopes: string[];
  content: string;
};

export type PublishedSkillVersion = SkillDraft & {
  skillId: string;
  version: string;
  status: "published";
  contentSha256: string;
};

export type PublishedKnowledgeVersion = KnowledgeDraft & {
  knowledgeId: string;
  version: number;
  status: "published";
  contentSha256: string;
};

const skillTransitions: Readonly<Record<SkillStatus, readonly SkillStatus[]>> = {
  draft: ["review_pending"],
  review_pending: ["draft", "published"],
  published: ["deprecated"],
  deprecated: ["archived"],
  archived: [],
};

const knowledgeTransitions: Readonly<
  Record<KnowledgeStatus, readonly KnowledgeStatus[]>
> = {
  uploaded: ["scanning"],
  scanning: ["parsing"],
  parsing: ["parsed"],
  parsed: ["review_pending"],
  review_pending: ["parsed", "published"],
  published: ["expired", "deprecated"],
  expired: ["archived"],
  deprecated: ["archived"],
  archived: [],
};

const skillDraftFields = new Set(["name", "description", "rules", "testCases"]);
const skillRuleFields = new Set([
  "applicableWhen",
  "excludedWhen",
  "requiredInputs",
  "evidenceSchema",
  "steps",
  "safety",
  "outputConstraints",
  "knowledgeScopes",
]);
const knowledgeDraftFields = new Set([
  "title",
  "summary",
  "sourceType",
  "sourceReference",
  "language",
  "sensitivity",
  "license",
  "projectIds",
  "deviceModels",
  "skillIds",
  "knowledgeScopes",
  "content",
]);
const forbiddenNestedKeys = new Set([
  "apikey",
  "apisecret",
  "authorization",
  "bearer",
  "clientsecret",
  "credential",
  "credentials",
  "endpoint",
  "password",
  "passwd",
  "refreshtoken",
  "script",
  "secret",
  "secretkey",
  "token",
  "url",
]);
const identifierPattern = /^[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}$/;
const semverPattern = /^(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/;
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const languagePattern = /^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/;
const unsafeReferencePattern = /(?:<\s*\/?\s*[a-z][^>]*>|(?:https?|wss?|file):\/\/|(?:data|javascript):)/i;

export function canTransitionSkillStatus(
  from: SkillStatus,
  to: SkillStatus,
): boolean {
  return skillTransitions[from].includes(to);
}

export function canTransitionKnowledgeStatus(
  from: KnowledgeStatus,
  to: KnowledgeStatus,
): boolean {
  return knowledgeTransitions[from].includes(to);
}

export function normalizeSkillDraft(value: unknown): SkillDraft {
  if (!isRecord(value) || !hasExactFields(value, skillDraftFields)) {
    throw new Error("skill_draft_unknown_field");
  }
  const rules = normalizeSkillRules(value.rules);
  const testCases = normalizeTestCases(value.testCases);
  return {
    name: requiredText(value.name, 240, "skill_name_invalid"),
    description: requiredText(value.description, 4_000, "skill_description_invalid"),
    rules,
    testCases,
  };
}

export function normalizeKnowledgeDraft(value: unknown): KnowledgeDraft {
  if (!isRecord(value) || !hasExactFields(value, knowledgeDraftFields)) {
    throw new Error("knowledge_draft_unknown_field");
  }
  const sourceType = requiredText(
    value.sourceType,
    40,
    "knowledge_source_type_invalid",
  );
  if (!["manual", "uploaded_file", "case", "external_link"].includes(sourceType)) {
    throw new Error("knowledge_source_type_invalid");
  }
  const sourceReference = requiredText(
    value.sourceReference,
    1_000,
    "knowledge_source_reference_invalid",
  );
  if (sourceType === "external_link") {
    let parsed: URL;
    try {
      parsed = new URL(sourceReference);
    } catch {
      throw new Error("knowledge_source_reference_invalid");
    }
    if (parsed.protocol !== "https:" || parsed.username || parsed.password) {
      throw new Error("knowledge_source_reference_invalid");
    }
  } else if (unsafeReferencePattern.test(sourceReference)) {
    throw new Error("knowledge_source_reference_invalid");
  }
  const language = requiredText(value.language, 40, "knowledge_language_invalid");
  if (!languagePattern.test(language)) throw new Error("knowledge_language_invalid");
  const sensitivity = requiredText(
    value.sensitivity,
    40,
    "knowledge_sensitivity_invalid",
  );
  if (!["public", "internal", "confidential", "restricted"].includes(sensitivity)) {
    throw new Error("knowledge_sensitivity_invalid");
  }
  return {
    title: requiredText(value.title, 300, "knowledge_title_invalid"),
    summary: requiredText(value.summary, 4_000, "knowledge_summary_invalid"),
    sourceType: sourceType as KnowledgeDraft["sourceType"],
    sourceReference,
    language,
    sensitivity: sensitivity as KnowledgeDraft["sensitivity"],
    license: requiredText(value.license, 300, "knowledge_license_invalid"),
    projectIds: stringList(value.projectIds, 100, 200, "knowledge_project_ids_invalid", uuidPattern),
    deviceModels: stringList(value.deviceModels, 100, 200, "knowledge_device_models_invalid"),
    skillIds: stringList(value.skillIds, 100, 200, "knowledge_skill_ids_invalid", identifierPattern),
    knowledgeScopes: stringList(value.knowledgeScopes, 100, 200, "knowledge_scopes_invalid"),
    content: knowledgeContent(value.content, sourceType as KnowledgeDraft["sourceType"]),
  };
}

export async function compileSkillVersion(value: unknown): Promise<PublishedSkillVersion> {
  if (!isRecord(value)) throw new Error("skill_version_invalid");
  const skillId = identifier(value.skillId, "skill_id_invalid");
  const version = requiredText(value.version, 40, "skill_version_invalid");
  if (!semverPattern.test(version)) throw new Error("skill_version_invalid");
  const draft = normalizeSkillDraft(withoutFields(value, ["skillId", "version"]));
  const published = { skillId, version, ...draft, status: "published" as const };
  return { ...published, contentSha256: await sha256Hex(canonicalJson(published)) };
}

export async function compileKnowledgeVersion(
  value: unknown,
): Promise<PublishedKnowledgeVersion> {
  if (!isRecord(value)) throw new Error("knowledge_version_invalid");
  const knowledgeId = identifier(value.knowledgeId, "knowledge_id_invalid");
  if (!Number.isInteger(value.version) || Number(value.version) <= 0) {
    throw new Error("knowledge_version_invalid");
  }
  const draft = normalizeKnowledgeDraft(withoutFields(value, ["knowledgeId", "version"]));
  const published = {
    knowledgeId,
    version: Number(value.version),
    ...draft,
    status: "published" as const,
  };
  return { ...published, contentSha256: await sha256Hex(canonicalJson(published)) };
}

function normalizeSkillRules(value: unknown): SkillRules {
  if (!isRecord(value) || !hasExactFields(value, skillRuleFields)) {
    throw new Error("skill_rules_unknown_field");
  }
  for (const field of [
    "applicableWhen",
    "excludedWhen",
    "evidenceSchema",
    "safety",
    "outputConstraints",
  ] as const) {
    if (!isRecord(value[field])) throw new Error("skill_rules_invalid");
  }
  rejectForbiddenNestedKeys(value);
  const steps = normalizeSkillSteps(value.steps);
  const normalized: SkillRules = {
    applicableWhen: normalizeRecord(value.applicableWhen as Record<string, unknown>),
    excludedWhen: normalizeRecord(value.excludedWhen as Record<string, unknown>),
    requiredInputs: stringList(value.requiredInputs, 30, 120, "skill_required_inputs_invalid"),
    evidenceSchema: normalizeRecord(value.evidenceSchema as Record<string, unknown>),
    steps,
    safety: normalizeRecord(value.safety as Record<string, unknown>),
    outputConstraints: normalizeRecord(value.outputConstraints as Record<string, unknown>),
    knowledgeScopes: stringList(value.knowledgeScopes, 100, 200, "skill_knowledge_scopes_invalid"),
  };
  if (canonicalJson(normalized).length > 128_000) throw new Error("skill_rules_too_large");
  return normalized;
}

function normalizeSkillSteps(value: unknown): SkillStep[] {
  if (!Array.isArray(value) || value.length === 0 || value.length > 100) {
    throw new Error("skill_steps_invalid");
  }
  const seen = new Set<string>();
  return value.map((item) => {
    if (!isRecord(item) || !hasExactFields(item, new Set(["id", "instruction", "risk"]))) {
      throw new Error("skill_step_invalid");
    }
    const id = identifier(item.id, "skill_step_id_invalid");
    if (seen.has(id)) throw new Error("skill_step_id_duplicate");
    seen.add(id);
    const risk = requiredText(item.risk, 20, "skill_step_risk_invalid");
    if (!["low", "medium", "high"].includes(risk)) throw new Error("skill_step_risk_invalid");
    return {
      id,
      instruction: requiredText(item.instruction, 2_000, "skill_step_instruction_invalid"),
      risk: risk as SkillStep["risk"],
    };
  });
}

function normalizeTestCases(value: unknown): SkillTestCase[] {
  if (!Array.isArray(value) || value.length > 100) throw new Error("skill_test_cases_invalid");
  return value.map((item) => {
    if (!isRecord(item) || !hasExactFields(item, new Set(["title", "input", "expected"]))) {
      throw new Error("skill_test_case_invalid");
    }
    return {
      title: requiredText(item.title, 240, "skill_test_case_invalid"),
      input: requiredText(item.input, 4_000, "skill_test_case_invalid"),
      expected: requiredText(item.expected, 4_000, "skill_test_case_invalid"),
    };
  });
}

function rejectForbiddenNestedKeys(value: unknown): void {
  if (Array.isArray(value)) {
    value.forEach(rejectForbiddenNestedKeys);
    return;
  }
  if (!isRecord(value)) return;
  for (const [key, child] of Object.entries(value)) {
    if (forbiddenNestedKeys.has(key.toLowerCase().replaceAll("_", ""))) {
      throw new Error("skill_rule_forbidden_field");
    }
    rejectForbiddenNestedKeys(child);
  }
}

function requiredText(value: unknown, maximum: number, error: string): string {
  if (typeof value !== "string") throw new Error(error);
  const result = value.trim();
  if (!result || result.length > maximum || /[\u0000-\u001f]/.test(result)) {
    throw new Error(error);
  }
  return result;
}

function knowledgeContent(value: unknown, sourceType: KnowledgeDraft["sourceType"]): string {
  if (typeof value !== "string") throw new Error("knowledge_content_invalid");
  const result = value.replace(/\r\n?/g, "\n").trim();
  if (sourceType === "uploaded_file") {
    if (result !== "") throw new Error("knowledge_content_invalid");
    return "";
  }
  if (!result || result.length > 512_000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(result)) {
    throw new Error("knowledge_content_invalid");
  }
  return result;
}

function identifier(value: unknown, error: string): string {
  const result = requiredText(value, 200, error);
  if (!identifierPattern.test(result)) throw new Error(error);
  return result;
}

function stringList(
  value: unknown,
  maximumItems: number,
  maximumLength: number,
  error: string,
  pattern?: RegExp,
): string[] {
  if (!Array.isArray(value) || value.length > maximumItems) throw new Error(error);
  const seen = new Set<string>();
  return value.map((item) => {
    const text = requiredText(item, maximumLength, error);
    if ((pattern && !pattern.test(text)) || seen.has(text)) throw new Error(error);
    seen.add(text);
    return text;
  });
}

function hasExactFields(value: Record<string, unknown>, fields: Set<string>): boolean {
  const keys = Object.keys(value);
  return keys.length === fields.size && keys.every((key) => fields.has(key));
}

function withoutFields(
  value: Record<string, unknown>,
  fields: string[],
): Record<string, unknown> {
  const result = { ...value };
  for (const field of fields) delete result[field];
  return result;
}

function normalizeRecord(value: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(value).sort(([left], [right]) => left.localeCompare(right)).map(
      ([key, child]) => [key, normalizeValue(child)],
    ),
  );
}

function normalizeValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(normalizeValue);
  if (isRecord(value)) return normalizeRecord(value);
  if (value === null || ["string", "number", "boolean"].includes(typeof value)) return value;
  throw new Error("domain_value_invalid");
}

function canonicalJson(value: unknown): string {
  return JSON.stringify(normalizeValue(value));
}

async function sha256Hex(value: string): Promise<string> {
  const digest = new Uint8Array(
    await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)),
  );
  return Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
