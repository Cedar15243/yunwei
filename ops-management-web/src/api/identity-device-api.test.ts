import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createManagementApi, ManagementApiError } from "./management-api";

const baseUrl = "https://ops.example.test/functions/v1/ops-glasses/";
const token = "session-token";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function requestAt(index: number): [string, RequestInit] {
  const call = vi.mocked(fetch).mock.calls[index];
  return [String(call[0]), (call[1] ?? {}) as RequestInit];
}

describe("identity and device management API", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(async () => jsonResponse({ ok: true, items: [] })));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("reads people and devices from the authenticated management surface", async () => {
    const api = createManagementApi(baseUrl, async () => token);

    await api.getPeople();
    await api.getDevices();

    expect(requestAt(0)[0]).toBe(
      "https://ops.example.test/functions/v1/ops-glasses/management/people",
    );
    expect(requestAt(1)[0]).toBe(
      "https://ops.example.test/functions/v1/ops-glasses/management/devices",
    );
    expect(requestAt(0)[1].headers).toMatchObject({ Authorization: `Bearer ${token}` });
  });

  it("creates and updates equipment with generated idempotency and fixed confirmations", async () => {
    const api = createManagementApi(baseUrl, async () => token);
    const command = {
      equipmentKey: "UPS-01",
      system: "供配电系统",
      brand: "华方",
      model: "HF-UPS-20K",
      quantity: 2,
      status: "attention" as const,
      lastInspectionAt: "2026-08-08T02:00:00.000Z",
      faultCount: 3,
      repairCount: 2,
      keyParameter: "20 kVA / 380 V",
      projectIds: ["project/a"],
      reason: "登记项目现场设备",
    };

    await api.createEquipment(command);
    await api.updateEquipment("equipment/a", {
      ...command,
      expectedUpdatedAt: "2026-08-08T03:00:00.000Z",
      reason: "维修后更新设备资料",
    });

    const createBody = JSON.parse(String(requestAt(0)[1].body));
    const updateBody = JSON.parse(String(requestAt(1)[1].body));
    expect(requestAt(0)[0]).toContain("/management/equipment");
    expect(requestAt(0)[1].method).toBe("POST");
    expect(createBody).toMatchObject({ ...command, confirmation: "CREATE_EQUIPMENT" });
    expect(createBody.idempotencyKey).toMatch(/^equipment-create-/);
    expect(requestAt(1)[0]).toContain("/management/equipment/equipment%2Fa");
    expect(requestAt(1)[1].method).toBe("PUT");
    expect(updateBody).toMatchObject({
      ...command,
      expectedUpdatedAt: "2026-08-08T03:00:00.000Z",
      reason: "维修后更新设备资料",
      confirmation: "UPDATE_EQUIPMENT",
    });
    expect(updateBody.idempotencyKey).toMatch(/^equipment-update-/);
    expect(updateBody.idempotencyKey).not.toBe(createBody.idempotencyKey);
  });

  it("reads voiceprint profiles and revokes them through the controlled management surface", async () => {
    const api = createManagementApi(baseUrl, async () => token);

    await api.getVoiceprints();
    await api.revokeVoiceprint("voiceprint/a", {
      reason: "设备交回",
      idempotencyKey: "voiceprint-revoke-1",
    });

    expect(requestAt(0)[0]).toBe(
      "https://ops.example.test/functions/v1/ops-glasses/management/voiceprints",
    );
    expect(requestAt(1)[0]).toContain("/management/voiceprints/voiceprint%2Fa/revoke");
    expect(JSON.parse(String(requestAt(1)[1].body))).toEqual({
      reason: "设备交回",
      idempotencyKey: "voiceprint-revoke-1",
      confirmation: "REVOKE_VOICEPRINT",
    });
  });

  it("adds fixed confirmations to account and destructive device commands", async () => {
    const api = createManagementApi(baseUrl, async () => token);

    await api.setPersonStatus("person/a", { status: "disabled", reason: "人员离岗" });
    await api.unbindDevice("device/a", { reason: "设备交回" });
    await api.revokeDevice("device/a", { reason: "设备遗失" });
    await api.issueDeviceCredential("device/a", { reason: "轮换受管凭据" });

    expect(JSON.parse(String(requestAt(0)[1].body))).toEqual({
      status: "disabled",
      reason: "人员离岗",
      confirmation: "CHANGE_ACCOUNT_STATUS",
    });
    expect(JSON.parse(String(requestAt(1)[1].body))).toEqual({
      reason: "设备交回",
      confirmation: "UNBIND_DEVICE",
    });
    expect(JSON.parse(String(requestAt(2)[1].body))).toEqual({
      reason: "设备遗失",
      confirmation: "REVOKE_DEVICE",
    });
    expect(JSON.parse(String(requestAt(3)[1].body))).toEqual({
      reason: "轮换受管凭据",
      confirmation: "ISSUE_DEVICE_CREDENTIAL",
    });
  });

  it("invites accounts and changes roles through explicit audited commands", async () => {
    const api = createManagementApi(baseUrl, async () => token);

    await api.invitePerson({
      email: "li@example.com",
      displayName: "李工",
      role: "field_engineer",
      reason: "新增现场工程师",
    });
    await api.setPersonRole("person/a", { role: "remote_expert", reason: "转为远程专家" });

    expect(requestAt(0)[0]).toContain("/management/people/invitations");
    expect(JSON.parse(String(requestAt(0)[1].body))).toEqual({
      email: "li@example.com",
      displayName: "李工",
      role: "field_engineer",
      reason: "新增现场工程师",
      confirmation: "INVITE_ACCOUNT",
    });
    expect(requestAt(1)[0]).toContain("/management/people/person%2Fa/role");
    expect(JSON.parse(String(requestAt(1)[1].body))).toEqual({
      role: "remote_expert",
      reason: "转为远程专家",
      confirmation: "CHANGE_ACCOUNT_ROLE",
    });
  });

  it("requests identity-provider recovery without accepting a password or recovery link", async () => {
    const api = createManagementApi(baseUrl, async () => token);

    await (api as any).sendPersonRecovery("person/a", { reason: "账号本人无法登录" });

    expect(requestAt(0)[0]).toContain("/management/people/person%2Fa/recovery");
    expect(JSON.parse(String(requestAt(0)[1].body))).toEqual({
      reason: "账号本人无法登录",
      confirmation: "SEND_ACCOUNT_RECOVERY",
    });
    expect(String(requestAt(0)[1].body)).not.toMatch(/password|recoveryLink|actionLink/i);
  });

  it("issues a short-lived device activation code with one-time confirmation", async () => {
    const api = createManagementApi(baseUrl, async () => token);

    await api.issueDeviceActivation("device/a", {
      reason: "设备首次部署",
      expiresInSeconds: 600,
    });

    const [url, init] = requestAt(0);
    expect(url).toBe(
      "https://ops.example.test/functions/v1/ops-glasses/management/devices/device%2Fa/activation-codes",
    );
    expect(JSON.parse(String(init.body))).toEqual({
      reason: "设备首次部署",
      expiresInSeconds: 600,
      confirmation: "ISSUE_DEVICE_ACTIVATION",
    });
  });

  it("binds a device only to an explicit person and optional project", async () => {
    const api = createManagementApi(baseUrl, async () => token);

    await api.bindDevice("device/a", {
      profileId: "person/a",
      projectId: "project/a",
      reason: "项目设备领用",
    });

    const [url, init] = requestAt(0);
    expect(url).toContain("/management/devices/device%2Fa/bindings");
    expect(JSON.parse(String(init.body))).toEqual({
      profileId: "person/a",
      projectId: "project/a",
      reason: "项目设备领用",
    });
  });

  it("manages project-scoped access through the audited membership endpoints", async () => {
    const api = createManagementApi(baseUrl, async () => token);

    await api.getProjectMemberships("project/a");
    await api.grantProjectMembership("project/a", {
      profileId: "person/a",
      accessRole: "engineer",
      reason: "加入现场项目",
    });
    await api.revokeProjectMembership("project/a", "person/a", { reason: "人员退出项目" });

    expect(requestAt(0)[0]).toContain("/management/projects/project%2Fa/memberships");
    expect(JSON.parse(String(requestAt(1)[1].body))).toEqual({
      profileId: "person/a",
      accessRole: "engineer",
      reason: "加入现场项目",
    });
    expect(requestAt(2)[0]).toContain("/management/projects/project%2Fa/memberships/person%2Fa/revoke");
    expect(JSON.parse(String(requestAt(2)[1].body))).toEqual({
      reason: "人员退出项目",
      confirmation: "REVOKE_PROJECT_ACCESS",
    });
  });

  it("reads one encoded project record from the authenticated cloud surface", async () => {
    const api = createManagementApi(baseUrl, async () => token);

    await api.getProjectRecord("project/a");

    expect(requestAt(0)[0]).toBe(
      "https://ops.example.test/functions/v1/ops-glasses/management/projects/project%2Fa/record",
    );
    expect(requestAt(0)[1].headers).toMatchObject({ Authorization: `Bearer ${token}` });
  });

  it("encodes task and media record filters exactly once", async () => {
    const api = createManagementApi(baseUrl, async () => token);

    await api.getTasks({
      status: "completed",
      projectId: "project/a",
      query: "pump & valve",
      before: "task/cursor+1",
      limit: 25,
    });
    await api.getMedia({
      kind: "photo",
      uploadStatus: "failed",
      taskId: "task/a",
      before: "media/cursor+1",
      limit: 15,
    });

    expect(requestAt(0)[0]).toBe(
      "https://ops.example.test/functions/v1/ops-glasses/management/tasks?status=completed&projectId=project%2Fa&query=pump+%26+valve&before=task%2Fcursor%2B1&limit=25",
    );
    expect(requestAt(1)[0]).toBe(
      "https://ops.example.test/functions/v1/ops-glasses/management/media?kind=photo&uploadStatus=failed&taskId=task%2Fa&before=media%2Fcursor%2B1&limit=15",
    );
  });

  it("downloads an authorized task CSV as a blob", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(new Response("task_id,title\r\ntask-a,Pump\r\n", {
      status: 200,
      headers: { "Content-Type": "text/csv; charset=utf-8" },
    }));
    const api = createManagementApi(baseUrl, async () => token);

    const result = await api.exportTasks({
      status: "completed",
      projectId: "project/a",
      query: "pump & valve",
    });

    const [url, init] = requestAt(0);
    expect(url).toBe(
      "https://ops.example.test/functions/v1/ops-glasses/management/tasks/export?status=completed&projectId=project%2Fa&query=pump+%26+valve",
    );
    expect(init.headers).toMatchObject({ Authorization: `Bearer ${token}` });
    expect(result).toBeInstanceOf(Blob);
    expect(result.type).toContain("text/csv");
    expect(result.size).toBeGreaterThan(0);
  });

  it("preserves structured management errors for failed task exports", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({ error: "task_export_too_large" }, 413));
    const api = createManagementApi(baseUrl, async () => token);

    const failure = await api.exportTasks({ status: "active" }).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ManagementApiError);
    expect(failure).toMatchObject({
      status: 413,
      code: "task_export_too_large",
      payload: { error: "task_export_too_large" },
    });
  });
});
