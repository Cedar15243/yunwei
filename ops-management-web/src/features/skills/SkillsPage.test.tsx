import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { SkillKnowledgeApi, SkillDefinition, SkillVersion } from "../../api/skill-knowledge-types";
import { SkillsPage } from "./SkillsPage";

const version: SkillVersion = {
  id: "skill-version-a",
  skill_definition_id: "skill-a",
  version: "1.0.0",
  status: "published",
  rules: { knowledgeScopes: ["hvac/ddc"] },
  test_cases: [],
  test_result: { passed: 12, failed: 0 },
  content_sha256: "a".repeat(64),
  lifecycle_reason: "首个稳定版本",
  created_at: "2026-08-03T01:00:00.000Z",
  updated_at: "2026-08-03T02:00:00.000Z",
  skill_assignments: [{
    id: "assignment-a",
    scope_type: "device",
    project_id: null,
    profile_id: null,
    device_id: "device-a",
    status: "active",
    active_from: "2026-08-03T02:00:00.000Z",
    expires_at: null,
    assigned_at: "2026-08-03T02:00:00.000Z",
  }],
};

const skill: SkillDefinition = {
  id: "skill-a",
  skill_key: "hvac_ddc_repair",
  name: "DDC 维修",
  description: "控制器报警处置",
  created_at: "2026-08-03T01:00:00.000Z",
  updated_at: "2026-08-03T02:00:00.000Z",
  skill_versions: [version],
};

function api(overrides: Partial<SkillKnowledgeApi> = {}): SkillKnowledgeApi {
  return {
    getSkills: vi.fn().mockResolvedValue([skill]),
    createSkillDraft: vi.fn().mockResolvedValue(version),
    getSkillVersions: vi.fn().mockResolvedValue([version]),
    transitionSkillVersion: vi.fn().mockResolvedValue(version),
    assignSkillVersion: vi.fn().mockResolvedValue(version.skill_assignments[0]),
    revokeSkillAssignment: vi.fn().mockResolvedValue({ ...version.skill_assignments[0], status: "revoked" }),
    getKnowledge: vi.fn().mockResolvedValue([]),
    createKnowledgeDraft: vi.fn(),
    getKnowledgeVersions: vi.fn().mockResolvedValue([]),
    transitionKnowledgeVersion: vi.fn(),
    grantKnowledgeVersion: vi.fn(),
    revokeKnowledgeGrant: vi.fn(),
    ...overrides,
  };
}

describe("SkillsPage", () => {
  it("loads the real Skill directory, immutable versions and assignments", async () => {
    render(<SkillsPage api={api()} />);

    const directory = await screen.findByRole("complementary", { name: "技能列表" });
    expect(within(directory).getByRole("button", { name: /DDC 维修/ })).toBeVisible();
    expect(await screen.findByText("v1.0.0")).toBeVisible();
    expect(screen.getByText("已发布")).toBeVisible();
    expect(screen.getByText("设备授权")).toBeVisible();
  });

  it("shows empty and error recovery states without fake entries", async () => {
    const user = userEvent.setup();
    const getSkills = vi.fn().mockRejectedValueOnce(new Error("技能服务暂不可用")).mockResolvedValueOnce([]);
    render(<SkillsPage api={api({ getSkills })} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("技能服务暂不可用");
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("尚未创建 AI 运维技能。")).toBeVisible();
    expect(getSkills).toHaveBeenCalledTimes(2);
  });

  it("requires a reason and explicit confirmation before a lifecycle transition", async () => {
    const user = userEvent.setup();
    const draft = { ...version, status: "draft" as const, skill_assignments: [] };
    const transitionSkillVersion = vi.fn().mockResolvedValue({ ...draft, status: "review_pending" });
    render(<SkillsPage api={api({
      getSkills: vi.fn().mockResolvedValue([{ ...skill, skill_versions: [draft] }]),
      getSkillVersions: vi.fn().mockResolvedValue([draft]),
      transitionSkillVersion,
    })} />);

    await screen.findByText("v1.0.0");
    await user.click(screen.getByRole("button", { name: "提交审核" }));
    expect(screen.getByRole("dialog", { name: "提交技能审核" })).toBeVisible();
    await user.type(screen.getByLabelText("操作原因"), "结构化规则和测试集已复核");
    await user.click(screen.getByRole("button", { name: "确认提交" }));

    await waitFor(() => expect(transitionSkillVersion).toHaveBeenCalledWith("skill-version-a", expect.objectContaining({
      expectedStatus: "draft",
      newStatus: "review_pending",
      reason: "结构化规则和测试集已复核",
    })));
  });
});
