import { assertEquals, assertRejects, assertThrows } from "jsr:@std/assert@1";
import {
  canTransitionKnowledgeStatus,
  canTransitionSkillStatus,
  compileKnowledgeVersion,
  compileSkillVersion,
  normalizeKnowledgeDraft,
  normalizeSkillDraft,
} from "./skill-knowledge-domain.ts";

Deno.test("allows only the published Skill lifecycle transitions", () => {
  assertEquals(canTransitionSkillStatus("draft", "review_pending"), true);
  assertEquals(canTransitionSkillStatus("review_pending", "draft"), true);
  assertEquals(canTransitionSkillStatus("review_pending", "published"), true);
  assertEquals(canTransitionSkillStatus("published", "deprecated"), true);
  assertEquals(canTransitionSkillStatus("deprecated", "archived"), true);
  assertEquals(canTransitionSkillStatus("published", "draft"), false);
  assertEquals(canTransitionSkillStatus("archived", "published"), false);
});

Deno.test("allows only the controlled knowledge ingestion and publication lifecycle", () => {
  const allowed = [
    ["uploaded", "scanning"],
    ["scanning", "parsing"],
    ["parsing", "parsed"],
    ["parsed", "review_pending"],
    ["review_pending", "parsed"],
    ["review_pending", "published"],
    ["published", "expired"],
    ["published", "deprecated"],
    ["expired", "archived"],
    ["deprecated", "archived"],
  ] as const;
  for (const [from, to] of allowed) {
    assertEquals(canTransitionKnowledgeStatus(from, to), true, `${from} -> ${to}`);
  }
  assertEquals(canTransitionKnowledgeStatus("uploaded", "published"), false);
  assertEquals(canTransitionKnowledgeStatus("published", "parsed"), false);
  assertEquals(canTransitionKnowledgeStatus("archived", "published"), false);
});

Deno.test("normalizes strict Skill rules that match the V9 gateway snapshot contract", () => {
  const normalized = normalizeSkillDraft({
    name: "暖通控制器现场处置",
    description: "指导现场人员采集证据并按风险门禁完成排查。",
    rules: skillRules(),
    testCases: [{ title: "控制器报警", input: "DDC 显示离线报警", expected: "先记录故障码" }],
  });

  assertEquals(normalized.name, "暖通控制器现场处置");
  assertEquals(normalized.rules.steps, [{
    id: "record_alarm",
    instruction: "先拍摄并记录完整故障码。",
    risk: "low",
  }]);
  assertEquals(normalized.rules.knowledgeScopes, ["hvac/ddc"]);
});

Deno.test("rejects unknown or dangerous Skill fields instead of storing executable client content", () => {
  assertThrows(
    () => normalizeSkillDraft({ ...validSkillDraft(), organizationId: "forged-org" }),
    Error,
    "skill_draft_unknown_field",
  );
  const unsafe = validSkillDraft();
  unsafe.rules.outputConstraints = { endpoint: "https://unsafe.example" };
  assertThrows(
    () => normalizeSkillDraft(unsafe),
    Error,
    "skill_rule_forbidden_field",
  );
  const scripted = validSkillDraft();
  scripted.rules.safety = { script: "rm -rf /" };
  assertThrows(
    () => normalizeSkillDraft(scripted),
    Error,
    "skill_rule_forbidden_field",
  );
});

Deno.test("normalizes knowledge metadata without accepting arbitrary URLs credentials or cross-organization identity", () => {
  const normalized = normalizeKnowledgeDraft({
    title: "HF-DDC-100 控制器离线排查",
    summary: "确认供电、总线和地址配置后再更换控制器。",
    sourceType: "manual",
    sourceReference: "HF-DDC-100-2026-R2",
    language: "zh-CN",
    sensitivity: "internal",
    license: "华方内部授权",
    projectIds: ["11111111-1111-4111-8111-111111111111"],
    deviceModels: ["HF-DDC-100"],
    skillIds: ["hvac_ddc_repair"],
    knowledgeScopes: ["hvac/ddc"],
    content: "先确认 24V 供电，再检查总线极性和地址冲突。",
  });

  assertEquals(normalized.sourceReference, "HF-DDC-100-2026-R2");
  assertEquals(normalized.projectIds.length, 1);
  assertEquals(normalized.skillIds, ["hvac_ddc_repair"]);
  assertEquals(normalized.knowledgeScopes, ["hvac/ddc"]);

  assertThrows(
    () => normalizeKnowledgeDraft({ ...validKnowledgeDraft(), organizationId: "forged-org" }),
    Error,
    "knowledge_draft_unknown_field",
  );
  assertThrows(
    () => normalizeKnowledgeDraft({ ...validKnowledgeDraft(), sourceReference: "https://unsafe.example/doc" }),
    Error,
    "knowledge_source_reference_invalid",
  );
  assertThrows(
    () => normalizeKnowledgeDraft({ ...validKnowledgeDraft(), apiKey: "secret" }),
    Error,
    "knowledge_draft_unknown_field",
  );
});

Deno.test("allows an empty server-owned body only for uploaded file drafts", () => {
  const uploaded = normalizeKnowledgeDraft({
    ...validKnowledgeDraft(),
    sourceType: "uploaded_file",
    sourceReference: "ddc-guide.md",
    content: "",
  });

  assertEquals(uploaded.sourceType, "uploaded_file");
  assertEquals(uploaded.content, "");
  assertThrows(
    () => normalizeKnowledgeDraft({ ...validKnowledgeDraft(), content: "" }),
    Error,
    "knowledge_content_invalid",
  );
  assertThrows(
    () => normalizeKnowledgeDraft({
      ...validKnowledgeDraft(),
      sourceType: "uploaded_file",
      sourceReference: "ddc-guide.md",
      content: "客户端伪造的附件正文",
    }),
    Error,
    "knowledge_content_invalid",
  );
});

Deno.test("preserves controlled multiline knowledge content", () => {
  const normalized = normalizeKnowledgeDraft({
    ...validKnowledgeDraft(),
    content: "第一步：检查供电。\n\n第二步：检查总线。",
  });

  assertEquals(normalized.content, "第一步：检查供电。\n\n第二步：检查总线。");
});

Deno.test("compiles immutable Skill and knowledge versions with stable content hashes", async () => {
  const firstSkill = await compileSkillVersion({
    skillId: "hvac_ddc_repair",
    version: "1.0.0",
    ...validSkillDraft(),
  });
  const secondSkill = await compileSkillVersion({
    version: "1.0.0",
    description: validSkillDraft().description,
    name: validSkillDraft().name,
    rules: {
      knowledgeScopes: ["hvac/ddc"],
      outputConstraints: {},
      safety: {},
      steps: skillRules().steps,
      evidenceSchema: {},
      requiredInputs: ["alarm_code"],
      excludedWhen: {},
      applicableWhen: {},
    },
    testCases: validSkillDraft().testCases,
    skillId: "hvac_ddc_repair",
  });
  assertEquals(firstSkill.contentSha256, secondSkill.contentSha256);
  assertEquals(firstSkill.status, "published");

  const knowledge = await compileKnowledgeVersion({
    knowledgeId: "hf_ddc_offline_guide",
    version: 1,
    ...validKnowledgeDraft(),
  });
  assertEquals(knowledge.status, "published");
  assertEquals(knowledge.contentSha256.length, 64);
  assertEquals(knowledge.projectIds, []);
});

Deno.test("refuses invalid immutable version identifiers before hashing", async () => {
  await assertRejects(
    () => compileSkillVersion({ skillId: "hvac_ddc_repair", version: "latest", ...validSkillDraft() }),
    Error,
    "skill_version_invalid",
  );
  await assertRejects(
    () => compileKnowledgeVersion({ knowledgeId: "hf_ddc_offline_guide", version: 0, ...validKnowledgeDraft() }),
    Error,
    "knowledge_version_invalid",
  );
});

function skillRules(): Record<string, unknown> {
  return {
    applicableWhen: {},
    excludedWhen: {},
    requiredInputs: ["alarm_code"],
    evidenceSchema: {},
    steps: [{ id: "record_alarm", instruction: "先拍摄并记录完整故障码。", risk: "low" }],
    safety: {},
    outputConstraints: {},
    knowledgeScopes: ["hvac/ddc"],
  };
}

function validSkillDraft(): Record<string, any> {
  return {
    name: "暖通控制器现场处置",
    description: "指导现场人员采集证据并按风险门禁完成排查。",
    rules: skillRules(),
    testCases: [{ title: "控制器报警", input: "DDC 显示离线报警", expected: "先记录故障码" }],
  };
}

function validKnowledgeDraft(): Record<string, any> {
  return {
    title: "HF-DDC-100 控制器离线排查",
    summary: "确认供电、总线和地址配置后再更换控制器。",
    sourceType: "manual",
    sourceReference: "HF-DDC-100-2026-R2",
    language: "zh-CN",
    sensitivity: "internal",
    license: "华方内部授权",
    projectIds: [],
    deviceModels: ["HF-DDC-100"],
    skillIds: ["hvac_ddc_repair"],
    knowledgeScopes: ["hvac/ddc"],
    content: "先确认 24V 供电，再检查总线极性和地址冲突。",
  };
}
