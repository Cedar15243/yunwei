import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { KnowledgeEntry, KnowledgeVersion, SkillKnowledgeApi } from "../../api/skill-knowledge-types";
import { KnowledgePage } from "./KnowledgePage";

const version: KnowledgeVersion = {
  id: "knowledge-version-a",
  knowledge_entry_id: "knowledge-a",
  version: 2,
  status: "published",
  title: "DDC 离线排查",
  summary: "确认供电、总线和地址配置。",
  source_type: "manual",
  source_reference: "HF-DDC-100-2026-R2",
  language: "zh-CN",
  sensitivity: "internal",
  license: "华方内部授权",
  project_ids: [],
  device_models: ["HF-DDC-100"],
  skill_ids: ["hvac_ddc_repair"],
  knowledge_scopes: ["hvac/ddc"],
  content: "先确认 24V 供电，再检查总线极性。",
  content_sha256: "b".repeat(64),
  processing_error: "",
  lifecycle_reason: "厂商手册复核",
  valid_from: "2026-08-03T02:00:00.000Z",
  expires_at: null,
  created_at: "2026-08-03T01:00:00.000Z",
  updated_at: "2026-08-03T02:00:00.000Z",
  knowledge_attachments: [],
  knowledge_grants: [{
    id: "grant-a",
    scope_type: "skill_version",
    project_id: null,
    profile_id: null,
    device_id: null,
    skill_version_id: "skill-version-a",
    status: "active",
    active_from: "2026-08-03T02:00:00.000Z",
    expires_at: null,
    granted_at: "2026-08-03T02:00:00.000Z",
  }],
};

const entry: KnowledgeEntry = {
  id: "knowledge-a",
  knowledge_key: "hf_ddc_guide",
  title: "DDC 指南",
  created_at: "2026-08-03T01:00:00.000Z",
  updated_at: "2026-08-03T02:00:00.000Z",
  knowledge_versions: [version],
};

function api(overrides: Partial<SkillKnowledgeApi> = {}): SkillKnowledgeApi {
  return {
    getSkills: vi.fn().mockResolvedValue([]),
    createSkillDraft: vi.fn(),
    getSkillVersions: vi.fn().mockResolvedValue([]),
    transitionSkillVersion: vi.fn(),
    assignSkillVersion: vi.fn(),
    revokeSkillAssignment: vi.fn(),
    getKnowledge: vi.fn().mockResolvedValue([entry]),
    createKnowledgeDraft: vi.fn().mockResolvedValue(version),
    createKnowledgeCaseDraft: vi.fn(),
    getKnowledgeVersions: vi.fn().mockResolvedValue([version]),
    uploadKnowledgeAttachment: vi.fn(),
    retryKnowledgeAttachment: vi.fn(),
    transitionKnowledgeVersion: vi.fn().mockResolvedValue(version),
    grantKnowledgeVersion: vi.fn().mockResolvedValue(version.knowledge_grants[0]),
    revokeKnowledgeGrant: vi.fn().mockResolvedValue({ ...version.knowledge_grants[0], status: "revoked" }),
    ...overrides,
  };
}

describe("KnowledgePage", () => {
  it("loads published knowledge with source, scopes, versions and grants", async () => {
    render(<KnowledgePage api={api()} />);

    const directory = await screen.findByRole("complementary", { name: "知识目录" });
    expect(within(directory).getByRole("button", { name: /DDC 指南/ })).toBeVisible();
    expect(await screen.findByText("v2")).toBeVisible();
    expect(screen.getByText("HF-DDC-100-2026-R2")).toBeVisible();
    expect(screen.getByText("hvac/ddc")).toBeVisible();
    expect(screen.getByText("技能版本授权")).toBeVisible();
  });

  it("opens a structured knowledge draft form instead of a fake upload success", async () => {
    const user = userEvent.setup();
    render(<KnowledgePage api={api({ getKnowledge: vi.fn().mockResolvedValue([]) })} />);

    expect(await screen.findByText("尚未导入华方知识。")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "新建知识" }));
    expect(screen.getByRole("dialog", { name: "新建知识草稿" })).toBeVisible();
    expect(screen.getByLabelText("知识范围")).toBeVisible();
    expect(screen.getByLabelText("知识正文")).toBeVisible();
    expect(screen.getByRole("option", { name: "上传文件" })).toBeInTheDocument();
  });

  it("does not expose fake scan controls before a real processing worker exists", async () => {
    const uploaded = { ...version, status: "uploaded" as const, content_sha256: "", knowledge_grants: [] };
    render(<KnowledgePage api={api({
      getKnowledge: vi.fn().mockResolvedValue([{ ...entry, knowledge_versions: [uploaded] }]),
      getKnowledgeVersions: vi.fn().mockResolvedValue([uploaded]),
    })} />);

    expect(await screen.findByText("等待后台安全扫描")).toBeVisible();
    expect(screen.queryByRole("button", { name: "开始扫描" })).not.toBeInTheDocument();
  });

  it("creates an uploaded-file draft and sends the selected file to the real attachment API", async () => {
    const user = userEvent.setup();
    const uploadedDraft = {
      ...version,
      id: "knowledge-version-upload-a",
      status: "uploaded" as const,
      source_type: "uploaded_file" as const,
      source_reference: "ddc-guide.md",
      content: "",
      content_sha256: "",
      knowledge_attachments: [],
      knowledge_grants: [],
    };
    const createKnowledgeDraft = vi.fn().mockResolvedValue(uploadedDraft);
    const uploadKnowledgeAttachment = vi.fn().mockResolvedValue({
      version: { ...uploadedDraft, status: "parsed" },
      attachment: { id: "attachment-a", status: "parsed" },
    });
    render(<KnowledgePage api={api({
      getKnowledge: vi.fn().mockResolvedValue([]),
      createKnowledgeDraft,
      uploadKnowledgeAttachment,
    })} />);

    await screen.findByText("尚未导入华方知识。");
    await user.click(screen.getByRole("button", { name: "新建知识" }));
    await user.type(screen.getByLabelText("知识标题"), "DDC 离线排查");
    await user.type(screen.getByLabelText("知识标识"), "hf_ddc_upload");
    await user.selectOptions(screen.getByLabelText("来源类型"), "uploaded_file");
    await user.type(screen.getByLabelText("知识摘要"), "检查供电、总线和地址配置。");
    await user.type(screen.getByLabelText("知识范围"), "hvac/ddc");
    await user.type(screen.getByLabelText("创建原因"), "导入受控厂商手册");
    const file = new File(["# DDC 离线排查\n\n- 检查供电"], "ddc-guide.md", { type: "text/markdown" });
    await user.upload(screen.getByLabelText("知识附件"), file);
    await user.click(screen.getByRole("button", { name: "创建并上传" }));

    await waitFor(() => expect(createKnowledgeDraft).toHaveBeenCalledWith(expect.objectContaining({
      sourceType: "uploaded_file",
      sourceReference: "ddc-guide.md",
      content: "",
    })));
    await waitFor(() => expect(uploadKnowledgeAttachment).toHaveBeenCalledWith(
      "knowledge-version-upload-a",
      file,
      expect.any(String),
    ));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "新建知识草稿" })).not.toBeInTheDocument());
  });

  it("shows downloadable attachment history without storage internals", async () => {
    const uploadedFile = {
      ...version,
      source_type: "uploaded_file" as const,
      source_reference: "ddc-guide.md",
      knowledge_attachments: [{
        id: "attachment-a",
        knowledge_version_id: version.id,
        status: "parsed" as const,
        original_file_name: "ddc-guide.md",
        content_type: "text/markdown",
        byte_size: 1024,
        file_sha256: "a".repeat(64),
        extracted_content_sha256: "b".repeat(64),
        processing_error: "",
        is_current: true,
        created_at: "2026-08-05T10:00:00.000Z",
        updated_at: "2026-08-05T10:00:01.000Z",
        download_url: "https://signed.example/attachment-a",
        download_expires_at: "2026-08-05T10:05:00.000Z",
      }],
    };
    render(<KnowledgePage api={api({
      getKnowledge: vi.fn().mockResolvedValue([{ ...entry, knowledge_versions: [uploadedFile] }]),
      getKnowledgeVersions: vi.fn().mockResolvedValue([uploadedFile]),
    })} />);

    expect((await screen.findAllByText("ddc-guide.md"))[1]).toBeVisible();
    expect(screen.getByText("当前附件")).toBeVisible();
    expect(screen.getByText("1 KB")).toBeVisible();
    expect(screen.getByRole("link", { name: "下载附件" })).toHaveAttribute("href", "https://signed.example/attachment-a");
    expect(screen.queryByText("ops-knowledge-attachments")).not.toBeInTheDocument();
  });

  it("allows an unavailable document parser attachment to be retried after service recovery", async () => {
    const user = userEvent.setup();
    const retryKnowledgeAttachment = vi.fn().mockResolvedValue({
      version: { ...version, status: "parsed" },
      attachment: { id: "attachment-pdf-a", status: "parsed" },
    });
    const pendingVersion = {
      ...version,
      status: "uploaded" as const,
      source_type: "uploaded_file" as const,
      source_reference: "manual.pdf",
      content: "",
      content_sha256: "",
      knowledge_grants: [],
      knowledge_attachments: [{
        id: "attachment-pdf-a",
        knowledge_version_id: version.id,
        status: "processing_unavailable" as const,
        original_file_name: "manual.pdf",
        content_type: "application/pdf",
        byte_size: 2048,
        file_sha256: "a".repeat(64),
        extracted_content_sha256: "",
        processing_error: "knowledge_document_processor_unavailable",
        is_current: false,
        created_at: "2026-08-05T10:00:00.000Z",
        updated_at: "2026-08-05T10:00:01.000Z",
        download_url: "https://signed.example/attachment-pdf-a",
        download_expires_at: "2026-08-05T10:05:00.000Z",
      }],
    };
    render(<KnowledgePage api={api({
      getKnowledge: vi.fn().mockResolvedValue([{ ...entry, knowledge_versions: [pendingVersion] }]),
      getKnowledgeVersions: vi.fn().mockResolvedValue([pendingVersion]),
      retryKnowledgeAttachment,
    })} />);

    expect(await screen.findByText(/解析待重试/)).toBeVisible();
    await user.click(screen.getByRole("button", { name: "重试附件解析" }));
    await user.type(screen.getByLabelText("操作原因"), "解析服务恢复后重新处理");
    await user.click(screen.getByRole("button", { name: "确认重试解析" }));

    await waitFor(() => expect(retryKnowledgeAttachment).toHaveBeenCalledWith(
      "attachment-pdf-a",
      expect.objectContaining({ reason: "解析服务恢复后重新处理" }),
    ));
  });

  it("does not allow an uploaded-file version without a parsed current attachment into review", async () => {
    const missingAttachment = {
      ...version,
      status: "parsed" as const,
      source_type: "uploaded_file" as const,
      content_sha256: "",
      knowledge_attachments: [],
      knowledge_grants: [],
    };
    render(<KnowledgePage api={api({
      getKnowledge: vi.fn().mockResolvedValue([{ ...entry, knowledge_versions: [missingAttachment] }]),
      getKnowledgeVersions: vi.fn().mockResolvedValue([missingAttachment]),
    })} />);

    expect(await screen.findByText("附件尚未完成解析，不能提交审核。")) .toBeVisible();
    expect(screen.queryByRole("button", { name: "提交审核" })).not.toBeInTheDocument();
  });

  it("revokes a grant only after a reasoned confirmation", async () => {
    const user = userEvent.setup();
    const revokeKnowledgeGrant = vi.fn().mockResolvedValue({ ...version.knowledge_grants[0], status: "revoked" });
    render(<KnowledgePage api={api({ revokeKnowledgeGrant })} />);

    await screen.findByText("技能版本授权");
    await user.click(screen.getByRole("button", { name: "撤销知识授权" }));
    await user.type(screen.getByLabelText("操作原因"), "资料已被新版手册替代");
    await user.click(screen.getByRole("button", { name: "确认撤销" }));

    await waitFor(() => expect(revokeKnowledgeGrant).toHaveBeenCalledWith("grant-a", expect.objectContaining({
      reason: "资料已被新版手册替代",
    })));
  });
});
