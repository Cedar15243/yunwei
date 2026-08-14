import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { ManagementApi } from "../../api/management-api";
import { DevicesPage } from "./DevicesPage";

const devicesCss = readFileSync(resolve(process.cwd(), "src/features/devices/devices.css"), "utf8");

function managementApi(overrides: Partial<ManagementApi> = {}): ManagementApi {
  return {
    getPeople: vi.fn().mockResolvedValue([
      {
        id: "person-a",
        display_name: "张工",
        role: "field_engineer",
        status: "active",
        active: true,
        status_changed_at: "2026-08-03T08:00:00.000Z",
      },
    ]),
    getProjects: vi.fn().mockResolvedValue([
      { id: "project-a", title: "一号机房", status: "active" },
    ]),
    getProjectMemberships: vi.fn().mockResolvedValue([]),
    grantProjectMembership: vi.fn().mockResolvedValue(undefined),
    revokeProjectMembership: vi.fn().mockResolvedValue(undefined),
    getDevices: vi.fn().mockResolvedValue([
      {
        id: "device-a",
        device_key: "AIR3-001",
        display_name: "Air3 一号机",
        status: "online",
        assigned_profile_id: null,
        model: "Air3",
        app_version: "9.0.17",
        mdm_policy_version: "policy-3",
        mdm_compliance_status: "compliant",
        last_seen_at: "2026-08-03T08:30:00.000Z",
        revoked_at: null,
        credential_status: "active",
        credential_issued_at: "2026-08-01T08:00:00.000Z",
        credential_expires_at: "2026-11-01T08:00:00.000Z",
        session_status: "active",
        session_last_used_at: "2026-08-03T08:30:00.000Z",
        session_expires_at: "2026-08-03T08:45:00.000Z",
        manifest_version: 7,
        manifest_status: "healthy",
        manifest_generated_at: "2026-08-03T08:29:00.000Z",
        manifest_expires_at: "2026-08-03T09:29:00.000Z",
        sync_health: "healthy",
        sync_issue: "",
      },
    ]),
    getEquipment: vi.fn().mockResolvedValue([]),
    createEquipment: vi.fn().mockResolvedValue({}),
    updateEquipment: vi.fn().mockResolvedValue({}),
    setPersonStatus: vi.fn().mockResolvedValue(undefined),
    sendPersonRecovery: vi.fn().mockResolvedValue(undefined),
    invitePerson: vi.fn().mockResolvedValue({ profileId: "invited-a", email: "li@example.com", status: "invited" }),
    setPersonRole: vi.fn().mockResolvedValue(undefined),
    bindDevice: vi.fn().mockResolvedValue(undefined),
    unbindDevice: vi.fn().mockResolvedValue(undefined),
    revokeDevice: vi.fn().mockResolvedValue(undefined),
    issueDeviceCredential: vi.fn().mockResolvedValue({
      token: "one-time-device-token",
      expiresAt: "2026-11-01T08:00:00.000Z",
    }),
    issueDeviceActivation: vi.fn().mockResolvedValue({
      activationCode: "HF9-ABCD-EFGH-JKLM",
      expiresAt: "2026-08-03T12:10:00.000Z",
      deviceId: "device-a",
    }),
    ...overrides,
  } as unknown as ManagementApi;
}

describe("DevicesPage", () => {
  it("releases the desktop body minimum width on the identity and device workspace", () => {
    expect(devicesCss).toContain("body:has(.identity-device-workspace){min-width:0}");
  });

  it("collapses the application shell for the identity workspace on phone-sized screens", () => {
    expect(devicesCss).toContain("@media(max-width:720px){body:has(.identity-device-workspace) .shell{grid-template-columns:minmax(0,1fr)}");
    expect(devicesCss).toContain("body:has(.identity-device-workspace) .nav nav{display:flex;overflow-x:auto");
    expect(devicesCss).toContain("body:has(.identity-device-workspace) .content{margin:18px 16px}");
  });

  it("defines one stable three-column tab grid with a narrow-screen fit rule", () => {
    const tabGridRules = [...devicesCss.matchAll(/\.segmented-tabs\{[^}]*grid-template-columns:[^;}]+/g)];

    expect(tabGridRules).toHaveLength(1);
    expect(tabGridRules[0]?.[0]).toContain("grid-template-columns:repeat(3,minmax(0,1fr))");
    expect(devicesCss).toContain("@media(max-width:640px){.segmented-tabs{width:100%}");
    expect(devicesCss).toContain(".segmented-tabs button{min-width:0}");
  });

  it("collapses equipment memory to two useful columns on phone-sized screens", () => {
    expect(devicesCss).toContain(".equipment-columns{grid-template-columns:minmax(0,1fr) minmax(96px,.55fr)}");
    expect(devicesCss).toContain(".equipment-columns>time,.management-table-head.equipment-columns>span:nth-child(5){display:none}");
    expect(devicesCss).toContain(".management-identity>span{min-width:0}");
    expect(devicesCss).toContain("overflow-wrap:anywhere");
  });

  it("reserves a fixed mobile hit target for the equipment edit action", () => {
    expect(devicesCss).toContain(".equipment-identity{display:grid;grid-template-columns:18px minmax(0,1fr) 36px");
    expect(devicesCss).toContain(".equipment-identity>.icon-button{margin-left:0;justify-self:end}");
  });

  it("keeps devices as the default view and exposes the people account tab", async () => {
    const user = userEvent.setup();
    render(<DevicesPage api={managementApi()} />);

    expect(await screen.findByText("Air3 一号机")).toBeVisible();
    expect(screen.getByText("Air3 · App 9.0.17")).toBeVisible();
    expect(screen.getByText("Skill/知识清单 v7")).toBeVisible();
    expect(screen.getByText("同步正常")).toBeVisible();
    expect(screen.getByText("设备凭据有效")).toBeVisible();

    await user.click(screen.getByRole("tab", { name: "人员账号" }));

    expect(await screen.findByText("张工")).toBeVisible();
    expect(screen.getByText("现场工程师")).toBeVisible();
  });

  it("loads authorized equipment memory and renders its linked project history", async () => {
    const user = userEvent.setup();
    const getEquipment = vi.fn().mockResolvedValue([
      {
        id: "equipment-a",
        equipmentKey: "UPS-01",
        system: "供配电系统",
        brand: "华方",
        model: "HF-UPS-20K",
        quantity: 2,
        status: "attention",
        lastInspectionAt: "2026-08-06T07:00:00.000Z",
        faultCount: 3,
        repairCount: 2,
        keyParameter: "20 kVA / 380 V",
        linkedProjects: [
          {
            projectId: "project-a",
            localProjectId: "local-project-a",
            title: "一号机房",
            status: "active",
            taskCount: 6,
          },
        ],
        updatedAt: "2026-08-07T03:00:00.000Z",
      },
    ]);
    const api = managementApi({ getEquipment });
    render(<DevicesPage api={api} />);

    await user.click(screen.getByRole("tab", { name: "设备记忆" }));

    expect(await screen.findByText("供配电系统 / 华方 HF-UPS-20K")).toBeVisible();
    expect(screen.getByText("20 kVA / 380 V")).toBeVisible();
    expect(screen.getByText("一号机房 · 6 个任务")).toBeVisible();
    expect(getEquipment).toHaveBeenCalledTimes(1);
  });

  it("shows an empty authorized catalog without manufacturing sample equipment", async () => {
    const user = userEvent.setup();
    render(<DevicesPage api={managementApi()} />);

    await user.click(screen.getByRole("tab", { name: "设备记忆" }));

    expect(await screen.findByText("当前没有已绑定项目的设备记忆。")).toBeVisible();
    expect(screen.queryByText(/示例|演示设备/)).not.toBeInTheDocument();
  });

  it("retries the equipment catalog request after a service failure", async () => {
    const user = userEvent.setup();
    const getEquipment = vi.fn()
      .mockRejectedValueOnce(new Error("设备记忆服务暂不可用"))
      .mockResolvedValueOnce([]);
    render(<DevicesPage api={managementApi({ getEquipment })} />);

    await user.click(screen.getByRole("tab", { name: "设备记忆" }));

    expect(await screen.findByText("设备记忆服务暂不可用")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("当前没有已绑定项目的设备记忆。")).toBeVisible();
    expect(getEquipment).toHaveBeenCalledTimes(2);
  });

  it("registers equipment with selected projects and refreshes the real catalog", async () => {
    const user = userEvent.setup();
    const createdEquipment = {
      id: "equipment-a",
      equipmentKey: "UPS-01",
      system: "供配电系统",
      brand: "华方",
      model: "HF-UPS-20K",
      quantity: 2,
      status: "attention",
      lastInspectionAt: null,
      faultCount: 3,
      repairCount: 2,
      keyParameter: "20 kVA / 380 V",
      linkedProjects: [{
        projectId: "project-a",
        localProjectId: "local-project-a",
        title: "一号机房",
        status: "active",
        taskCount: 0,
      }],
      updatedAt: "2026-08-08T03:00:00.000Z",
    };
    const getEquipment = vi.fn().mockResolvedValueOnce([]).mockResolvedValueOnce([createdEquipment]);
    const createEquipment = vi.fn().mockResolvedValue(createdEquipment);
    const api = managementApi({ getEquipment, createEquipment });
    render(<DevicesPage api={api} />);

    await user.click(screen.getByRole("tab", { name: "设备记忆" }));
    await user.click(await screen.findByRole("button", { name: "登记设备" }));
    await user.type(screen.getByLabelText("设备编号"), "UPS-01");
    await user.type(screen.getByLabelText("所属系统"), "供配电系统");
    await user.type(screen.getByLabelText("设备品牌"), "华方");
    await user.type(screen.getByLabelText("设备型号"), "HF-UPS-20K");
    await user.clear(screen.getByLabelText("设备数量"));
    await user.type(screen.getByLabelText("设备数量"), "2");
    await user.selectOptions(screen.getByLabelText("设备状态"), "attention");
    await user.clear(screen.getByLabelText("故障次数"));
    await user.type(screen.getByLabelText("故障次数"), "3");
    await user.clear(screen.getByLabelText("维修次数"));
    await user.type(screen.getByLabelText("维修次数"), "2");
    await user.type(screen.getByLabelText("关键参数"), "20 kVA / 380 V");
    await user.click(screen.getByRole("checkbox", { name: "一号机房" }));
    await user.type(screen.getByLabelText("操作原因"), "登记项目现场设备");
    await user.click(screen.getByRole("button", { name: "确认登记" }));

    expect(createEquipment).toHaveBeenCalledWith({
      equipmentKey: "UPS-01",
      system: "供配电系统",
      brand: "华方",
      model: "HF-UPS-20K",
      quantity: 2,
      status: "attention",
      lastInspectionAt: null,
      faultCount: 3,
      repairCount: 2,
      keyParameter: "20 kVA / 380 V",
      projectIds: ["project-a"],
      reason: "登记项目现场设备",
    });
    await waitFor(() => expect(getEquipment).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("供配电系统 / 华方 HF-UPS-20K")).toBeVisible();
  });

  it("edits equipment with its current version and existing project selection", async () => {
    const user = userEvent.setup();
    const equipment = {
      id: "equipment-a",
      equipmentKey: "UPS-01",
      system: "供配电系统",
      brand: "华方",
      model: "HF-UPS-20K",
      quantity: 2,
      status: "attention",
      lastInspectionAt: null,
      faultCount: 3,
      repairCount: 2,
      keyParameter: "20 kVA / 380 V",
      linkedProjects: [{
        projectId: "project-a",
        localProjectId: "local-project-a",
        title: "一号机房",
        status: "active",
        taskCount: 6,
      }],
      updatedAt: "2026-08-08T03:00:00.000Z",
    };
    const updateEquipment = vi.fn().mockResolvedValue({ ...equipment, model: "HF-UPS-20K-R2" });
    const api = managementApi({
      getEquipment: vi.fn().mockResolvedValue([equipment]),
      updateEquipment,
    });
    render(<DevicesPage api={api} />);

    await user.click(screen.getByRole("tab", { name: "设备记忆" }));
    await user.click(await screen.findByRole("button", { name: "编辑设备 UPS-01" }));
    expect(screen.getByRole("checkbox", { name: "一号机房" })).toBeChecked();
    await user.clear(screen.getByLabelText("设备型号"));
    await user.type(screen.getByLabelText("设备型号"), "HF-UPS-20K-R2");
    await user.selectOptions(screen.getByLabelText("设备状态"), "maintenance");
    await user.type(screen.getByLabelText("操作原因"), "维修后更新设备资料");
    await user.click(screen.getByRole("button", { name: "确认更新" }));

    expect(updateEquipment).toHaveBeenCalledWith("equipment-a", expect.objectContaining({
      model: "HF-UPS-20K-R2",
      status: "maintenance",
      projectIds: ["project-a"],
      expectedUpdatedAt: "2026-08-08T03:00:00.000Z",
      reason: "维修后更新设备资料",
    }));
  });

  it("keeps equipment form values when the server rejects the command", async () => {
    const user = userEvent.setup();
    const createEquipment = vi.fn().mockRejectedValue(new Error("设备编号已存在"));
    render(<DevicesPage api={managementApi({ createEquipment })} />);

    await user.click(screen.getByRole("tab", { name: "设备记忆" }));
    await user.click(await screen.findByRole("button", { name: "登记设备" }));
    await user.type(screen.getByLabelText("设备编号"), "UPS-01");
    await user.type(screen.getByLabelText("所属系统"), "供配电系统");
    await user.type(screen.getByLabelText("设备品牌"), "华方");
    await user.type(screen.getByLabelText("设备型号"), "HF-UPS-20K");
    await user.click(screen.getByRole("checkbox", { name: "一号机房" }));
    await user.type(screen.getByLabelText("操作原因"), "登记项目现场设备");
    await user.click(screen.getByRole("button", { name: "确认登记" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("设备编号已存在");
    expect(screen.getByLabelText("设备编号")).toHaveValue("UPS-01");
    expect(screen.getByLabelText("操作原因")).toHaveValue("登记项目现场设备");
    expect(screen.getByRole("dialog", { name: "登记设备" })).toBeVisible();
  });

  it("binds a device to the selected person and project then refreshes the list", async () => {
    const user = userEvent.setup();
    const api = managementApi();
    render(<DevicesPage api={api} />);

    await user.click(await screen.findByRole("button", { name: "绑定 Air3 一号机" }));
    await user.selectOptions(screen.getByLabelText("绑定人员"), "person-a");
    await user.selectOptions(screen.getByLabelText("所属项目"), "project-a");
    await user.type(screen.getByLabelText("操作原因"), "项目设备领用");
    await user.click(screen.getByRole("button", { name: "确认绑定" }));

    expect(api.bindDevice).toHaveBeenCalledWith("device-a", {
      profileId: "person-a",
      projectId: "project-a",
      reason: "项目设备领用",
    });
    expect(api.getDevices).toHaveBeenCalledTimes(2);
  });

  it("shows a newly issued credential once inside the controlled dialog", async () => {
    const user = userEvent.setup();
    const api = managementApi();
    render(<DevicesPage api={api} />);

    await user.click(await screen.findByRole("button", { name: "签发 Air3 一号机 凭据" }));
    await user.type(screen.getByLabelText("操作原因"), "轮换受管凭据");
    await user.click(screen.getByRole("button", { name: "确认签发" }));

    expect(await screen.findByText("one-time-device-token")).toBeVisible();
    expect(api.issueDeviceCredential).toHaveBeenCalledWith("device-a", {
      reason: "轮换受管凭据",
    });

    await user.click(screen.getByRole("button", { name: "关闭一次性凭据" }));
    expect(screen.queryByText("one-time-device-token")).not.toBeInTheDocument();
  });

  it("shows a short-lived activation code once with copy and QR delivery", async () => {
    const user = userEvent.setup();
    const api = managementApi();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });
    render(<DevicesPage api={api} />);

    await user.click(await screen.findByRole("button", { name: "生成 Air3 一号机 激活码" }));
    await user.selectOptions(screen.getByLabelText("有效期"), "900");
    await user.type(screen.getByLabelText("操作原因"), "设备首次部署");
    await user.click(screen.getByRole("button", { name: "确认生成" }));

    expect(await screen.findByText("HF9-ABCD-EFGH-JKLM")).toBeVisible();
    expect(await screen.findByRole("img", { name: "设备激活二维码" })).toHaveAttribute(
      "src",
      expect.stringMatching(/^data:image\/png;base64,/),
    );
    expect(api.issueDeviceActivation).toHaveBeenCalledWith("device-a", {
      reason: "设备首次部署",
      expiresInSeconds: 900,
    });

    await user.click(screen.getByRole("button", { name: "复制设备激活码" }));
    expect(writeText).toHaveBeenCalledWith("HF9-ABCD-EFGH-JKLM");

    await user.click(screen.getByRole("button", { name: "关闭设备激活码" }));
    expect(screen.queryByText("HF9-ABCD-EFGH-JKLM")).not.toBeInTheDocument();
  });

  it("preserves the operation reason when a device command fails", async () => {
    const user = userEvent.setup();
    const api = managementApi({
      getDevices: vi.fn().mockResolvedValue([
        {
          id: "device-a",
          device_key: "AIR3-001",
          display_name: "Air3 一号机",
          status: "online",
          assigned_profile_id: "person-a",
          model: "Air3",
          app_version: "9.0.17",
          mdm_policy_version: "policy-3",
          mdm_compliance_status: "compliant",
          last_seen_at: "2026-08-03T08:30:00.000Z",
          revoked_at: null,
        },
      ]),
      unbindDevice: vi.fn().mockRejectedValue(new Error("设备解绑失败")),
    });
    render(<DevicesPage api={api} />);

    await user.click(await screen.findByRole("button", { name: "解绑 Air3 一号机" }));
    const reason = screen.getByLabelText("操作原因");
    await user.type(reason, "人员调离项目");
    await user.click(screen.getByRole("button", { name: "确认解绑" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("设备解绑失败");
    expect(reason).toHaveValue("人员调离项目");
  });

  it("changes an account status through the controlled people view", async () => {
    const user = userEvent.setup();
    const api = managementApi();
    render(<DevicesPage api={api} />);

    await user.click(screen.getByRole("tab", { name: "人员账号" }));
    await user.click(await screen.findByRole("button", { name: "停用 张工" }));
    await user.type(screen.getByLabelText("操作原因"), "人员离岗");
    await user.click(screen.getByRole("button", { name: "确认停用" }));

    expect(api.setPersonStatus).toHaveBeenCalledWith("person-a", {
      status: "disabled",
      reason: "人员离岗",
    });
    expect(api.getPeople).toHaveBeenCalledTimes(2);
  });

  it("requests account recovery with a reason and never asks for a password", async () => {
    const user = userEvent.setup();
    const api = managementApi();
    render(<DevicesPage api={api} />);

    await user.click(screen.getByRole("tab", { name: "人员账号" }));
    await user.click(await screen.findByRole("button", { name: "恢复 张工 登录身份" }));
    expect(screen.queryByLabelText(/密码|恢复链接/)).not.toBeInTheDocument();
    await user.type(screen.getByLabelText("恢复原因"), "账号本人无法登录");
    await user.click(screen.getByRole("button", { name: "发送恢复邮件" }));

    expect((api as any).sendPersonRecovery).toHaveBeenCalledWith("person-a", {
      reason: "账号本人无法登录",
    });
    expect(await screen.findByText("恢复邮件已发送给张工，账号状态与权限未改变。")).toBeVisible();
  });

  it("invites a person without exposing a password field", async () => {
    const user = userEvent.setup();
    const api = managementApi();
    render(<DevicesPage api={api} />);

    await user.click(screen.getByRole("tab", { name: "人员账号" }));
    await user.click(await screen.findByRole("button", { name: "邀请人员账号" }));
    expect(screen.queryByLabelText("登录密码")).not.toBeInTheDocument();
    await user.type(screen.getByLabelText("账号邮箱"), "li@example.com");
    await user.type(screen.getByLabelText("显示名称"), "李工");
    await user.type(screen.getByLabelText("邀请原因"), "新增现场工程师");
    await user.click(screen.getByRole("button", { name: "确认邀请" }));

    expect(api.invitePerson).toHaveBeenCalledWith({
      email: "li@example.com",
      displayName: "李工",
      role: "field_engineer",
      reason: "新增现场工程师",
    });
  });

  it("changes a person role with an explicit reason", async () => {
    const user = userEvent.setup();
    const api = managementApi();
    render(<DevicesPage api={api} />);

    await user.click(screen.getByRole("tab", { name: "人员账号" }));
    await user.click(await screen.findByRole("button", { name: "调整 张工 角色" }));
    await user.selectOptions(screen.getByLabelText("组织角色"), "remote_expert");
    await user.type(screen.getByLabelText("变更原因"), "转为远程专家");
    await user.click(screen.getByRole("button", { name: "确认变更" }));

    expect(api.setPersonRole).toHaveBeenCalledWith("person-a", {
      role: "remote_expert",
      reason: "转为远程专家",
    });
  });

  it("grants a project-scoped role from the people directory", async () => {
    const user = userEvent.setup();
    const api = managementApi();
    render(<DevicesPage api={api} />);

    await user.click(screen.getByRole("tab", { name: "人员账号" }));
    await user.click(await screen.findByRole("button", { name: "管理 张工 项目范围" }));
    await user.selectOptions(screen.getByLabelText("项目角色"), "viewer");
    await user.type(screen.getByLabelText("项目授权原因"), "仅查看项目记录");
    await user.click(screen.getByRole("button", { name: "确认授权" }));

    expect(api.grantProjectMembership).toHaveBeenCalledWith("project-a", {
      profileId: "person-a",
      accessRole: "viewer",
      reason: "仅查看项目记录",
    });
  });
});
