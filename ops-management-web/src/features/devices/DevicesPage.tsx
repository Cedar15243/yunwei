import {
  Archive,
  Ban,
  CheckCircle2,
  Copy,
  Database,
  KeyRound,
  Link2,
  Link2Off,
  MonitorSmartphone,
  Pencil,
  Plus,
  QrCode,
  RefreshCw,
  ShieldCheck,
  UserRound,
  UserPlus,
  X,
} from "lucide-react";
import QRCode from "qrcode";
import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import {
  ManagementApiError,
  type EquipmentCreateCommand,
  type Device,
  type DeviceActivation,
  type DeviceActivationCommand,
  type DeviceBindingCommand,
  type DeviceCredential,
  type EquipmentMemory,
  type EquipmentStatus,
  type EquipmentUpdateCommand,
  type InvitePersonCommand,
  type ManagementApi,
  type Person,
  type PersonRoleCommand,
  type ProjectMembership,
  type ProjectMembershipCommand,
  type PersonStatus,
  type Project,
} from "../../api/management-api";
import { ReasonDialog } from "../governance/GovernanceDialogs";
import { formatTime } from "../dashboard/DashboardPage";
import "./devices.css";

type View = "devices" | "people" | "equipment-memory";
type Action =
  | { kind: "bind"; device: Device }
  | { kind: "credential"; device: Device }
  | { kind: "activation"; device: Device }
  | { kind: "unbind"; device: Device }
  | { kind: "revoke-device"; device: Device }
  | { kind: "person-status"; person: Person; status: PersonStatus }
  | { kind: "person-recovery"; person: Person }
  | { kind: "person-role"; person: Person; role: Person["role"] }
  | { kind: "person-scope"; person: Person }
  | { kind: "equipment-create" }
  | { kind: "equipment-edit"; equipment: EquipmentMemory }
  | { kind: "invite" };

export function DevicesPage({ api }: { api: ManagementApi }) {
  const [view, setView] = useState<View>("devices");
  const [devices, setDevices] = useState<Device[]>([]);
  const [people, setPeople] = useState<Person[]>([]);
  const [projects, setProjects] = useState<Project[]>([]);
  const [equipment, setEquipment] = useState<EquipmentMemory[]>([]);
  const [devicesLoading, setDevicesLoading] = useState(true);
  const [peopleLoading, setPeopleLoading] = useState(true);
  const [projectsLoading, setProjectsLoading] = useState(true);
  const [equipmentLoading, setEquipmentLoading] = useState(true);
  const [devicesError, setDevicesError] = useState("");
  const [peopleError, setPeopleError] = useState("");
  const [projectsError, setProjectsError] = useState("");
  const [equipmentError, setEquipmentError] = useState("");
  const [action, setAction] = useState<Action | null>(null);
  const [operationNotice, setOperationNotice] = useState("");

  const loadDevices = useCallback(async () => {
    setDevicesLoading(true);
    setDevicesError("");
    try {
      setDevices(await api.getDevices());
    } catch (cause) {
      setDevicesError(errorMessage(cause, "无法读取眼镜设备"));
    } finally {
      setDevicesLoading(false);
    }
  }, [api]);

  const loadPeople = useCallback(async () => {
    setPeopleLoading(true);
    setPeopleError("");
    try {
      setPeople(await api.getPeople());
    } catch (cause) {
      setPeopleError(errorMessage(cause, "无法读取人员账号"));
    } finally {
      setPeopleLoading(false);
    }
  }, [api]);

  const loadProjects = useCallback(async () => {
    setProjectsLoading(true);
    setProjectsError("");
    try {
      setProjects(await api.getProjects());
    } catch (cause) {
      setProjectsError(errorMessage(cause, "无法读取项目目录"));
    } finally {
      setProjectsLoading(false);
    }
  }, [api]);

  const loadEquipment = useCallback(async () => {
    setEquipmentLoading(true);
    setEquipmentError("");
    try {
      setEquipment(api.getEquipment ? await api.getEquipment() : []);
    } catch (cause) {
      setEquipmentError(errorMessage(cause, "无法读取设备记忆目录"));
    } finally {
      setEquipmentLoading(false);
    }
  }, [api]);

  useEffect(() => {
    void loadDevices();
    void loadPeople();
    void loadProjects();
    void loadEquipment();
  }, [loadDevices, loadPeople, loadProjects, loadEquipment]);

  const peopleById = useMemo(
    () => new Map(people.map((person) => [person.id, person.display_name])),
    [people],
  );

  async function completeReasonAction(reason: string) {
    if (!action) return;
    if (action.kind === "unbind") {
      await api.unbindDevice(action.device.id, { reason });
      setAction(null);
      await loadDevices();
      return;
    }
    if (action.kind === "revoke-device") {
      await api.revokeDevice(action.device.id, { reason });
      setAction(null);
      await loadDevices();
      return;
    }
    if (action.kind === "person-status") {
      await api.setPersonStatus(action.person.id, { status: action.status, reason });
      setAction(null);
      await loadPeople();
    }
  }

  return (
    <div className="page-stack identity-device-workspace">
      <section className="workspace-actions">
        <div>
          <h2>组织人员与受管眼镜</h2>
          <p>账号状态、设备归属和凭据操作均由服务端权限与审计规则控制。</p>
        </div>
        <div aria-label="人员与设备视图" className="segmented-tabs" role="tablist">
          <button aria-selected={view === "devices"} onClick={() => setView("devices")} role="tab" type="button">
            <MonitorSmartphone size={16} />眼镜设备
          </button>
          <button aria-selected={view === "people"} onClick={() => setView("people")} role="tab" type="button">
            <UserRound size={16} />人员账号
          </button>
          <button aria-selected={view === "equipment-memory"} onClick={() => setView("equipment-memory")} role="tab" type="button">
            <Database size={16} />设备记忆
          </button>
        </div>
      </section>

      {operationNotice ? <section className="notice success" role="status">{operationNotice}</section> : null}

      {view === "devices" ? (
        <DeviceDirectory
          devices={devices}
          error={devicesError}
          loading={devicesLoading}
          onAction={setAction}
          onRetry={loadDevices}
          peopleById={peopleById}
          peopleReady={!peopleLoading && !peopleError && people.length > 0}
        />
      ) : view === "people" ? (
        <PeopleDirectory
          error={peopleError}
          loading={peopleLoading}
          onAction={setAction}
          onInvite={() => setAction({ kind: "invite" })}
          projects={projects}
          projectsError={projectsError}
          onRetry={loadPeople}
          people={people}
        />
      ) : (
        <EquipmentMemoryDirectory
          equipment={equipment}
          error={equipmentError}
          loading={equipmentLoading}
          onCreate={() => setAction({ kind: "equipment-create" })}
          onEdit={(item) => setAction({ kind: "equipment-edit", equipment: item })}
          onRetry={loadEquipment}
        />
      )}

      {action?.kind === "bind" ? (
        <DeviceBindingDialog
          device={action.device}
          loading={peopleLoading || projectsLoading}
          onCancel={() => setAction(null)}
          onConfirm={async (command) => {
            await api.bindDevice(action.device.id, command);
            setAction(null);
            await loadDevices();
          }}
          people={people.filter((person) => person.status === "active")}
          projects={projects}
          projectsError={projectsError}
        />
      ) : null}

      {action?.kind === "credential" ? (
        <DeviceCredentialDialog
          device={action.device}
          onCancel={() => setAction(null)}
          onIssue={(reason) => api.issueDeviceCredential(action.device.id, { reason })}
        />
      ) : null}

      {action?.kind === "activation" ? (
        <DeviceActivationDialog
          device={action.device}
          onCancel={() => setAction(null)}
          onIssue={(command) => api.issueDeviceActivation(action.device.id, command)}
        />
      ) : null}

      {isReasonAction(action) ? (
        <ReasonDialog
          confirmLabel={reasonActionConfirmLabel(action)}
          description={reasonActionDescription(action)}
          onCancel={() => setAction(null)}
          onConfirm={(reason) => completeReasonAction(reason)}
          title={reasonActionTitle(action)}
        />
      ) : null}

      {action?.kind === "person-role" ? (
        <PersonRoleDialog
          person={action.person}
          initialRole={action.role}
          onCancel={() => setAction(null)}
          onConfirm={async (command) => {
            await api.setPersonRole(action.person.id, command);
            setAction(null);
            await loadPeople();
          }}
        />
      ) : null}

      {action?.kind === "person-recovery" ? (
        <AccountRecoveryDialog
          person={action.person}
          onCancel={() => setAction(null)}
          onConfirm={async (reason) => {
            await api.sendPersonRecovery(action.person.id, { reason });
            setOperationNotice(`恢复邮件已发送给${action.person.display_name}，账号状态与权限未改变。`);
            setAction(null);
          }}
        />
      ) : null}

      {action?.kind === "invite" ? (
        <InvitePersonDialog
          onCancel={() => setAction(null)}
          onConfirm={async (command) => {
            await api.invitePerson(command);
            setAction(null);
            await loadPeople();
          }}
        />
      ) : null}

      {action?.kind === "person-scope" ? (
        <PersonScopeDialog
          api={api}
          person={action.person}
          projects={projects}
          projectsError={projectsError}
          onCancel={() => setAction(null)}
        />
      ) : null}

      {action?.kind === "equipment-create" || action?.kind === "equipment-edit" ? (
        <EquipmentDialog
          api={api}
          equipment={action.kind === "equipment-edit" ? action.equipment : null}
          loadingProjects={projectsLoading}
          onCancel={() => setAction(null)}
          onSaved={async () => {
            const editing = action.kind === "equipment-edit";
            setAction(null);
            await loadEquipment();
            setOperationNotice(editing ? "设备资料已更新并同步到授权目录。" : "设备已登记并同步到授权目录。");
          }}
          projects={projects}
          projectsError={projectsError}
        />
      ) : null}
    </div>
  );
}

function DeviceDirectory({
  devices,
  error,
  loading,
  onAction,
  onRetry,
  peopleById,
  peopleReady,
}: {
  devices: Device[];
  error: string;
  loading: boolean;
  onAction: (action: Action) => void;
  onRetry: () => Promise<void>;
  peopleById: Map<string, string>;
  peopleReady: boolean;
}) {
  if (loading) return <section className="notice management-loading">正在读取受管设备…</section>;
  if (error) return <RetryNotice error={error} onRetry={onRetry} />;
  return (
    <section className="surface management-directory">
      <div aria-hidden="true" className="management-table-head device-columns">
        <span>设备</span><span>归属</span><span>同步</span><span>最后通信</span><span>操作</span>
      </div>
      {devices.length ? devices.map((device) => {
        const revoked = Boolean(device.revoked_at);
        const assignedName = device.assigned_profile_id
          ? peopleById.get(device.assigned_profile_id) ?? device.assigned_profile_id
          : "未绑定";
        return (
          <div className="management-row device-columns" key={device.id}>
            <span className="management-identity">
              <MonitorSmartphone size={18} />
              <span><strong>{device.display_name}</strong><small>{device.device_key}</small><small>{deviceMeta(device)}</small><small>{contentManifestLabel(device)}</small></span>
            </span>
            <span><strong>{assignedName}</strong><small>{mdmLabel(device)}</small></span>
            <span><span className={`status ${revoked ? "disabled" : device.status}`}>{deviceStatusLabel(device)}</span><small className={`sync-health ${device.sync_health ?? "attention"}`}>{syncHealthLabel(device)}</small></span>
            <span><time>{device.last_seen_at ? formatTime(device.last_seen_at) : "尚未上报"}</time><small>{credentialLabel(device)}</small></span>
            <span className="row-actions">
              <button
                aria-label={`绑定 ${device.display_name}`}
                className="icon-button"
                disabled={revoked || !peopleReady}
                onClick={() => onAction({ kind: "bind", device })}
                title="绑定或换绑人员"
                type="button"
              ><Link2 size={16} /></button>
              {device.assigned_profile_id ? <button aria-label={`解绑 ${device.display_name}`} className="icon-button" disabled={revoked} onClick={() => onAction({ kind: "unbind", device })} title="解绑人员" type="button"><Link2Off size={16} /></button> : null}
              <button aria-label={`签发 ${device.display_name} 凭据`} className="icon-button" disabled={revoked} onClick={() => onAction({ kind: "credential", device })} title="签发一次性凭据" type="button"><KeyRound size={16} /></button>
              <button aria-label={`生成 ${device.display_name} 激活码`} className="icon-button" disabled={revoked} onClick={() => onAction({ kind: "activation", device })} title="生成设备激活码" type="button"><QrCode size={16} /></button>
              <button aria-label={`撤销 ${device.display_name}`} className="icon-button danger-icon" disabled={revoked} onClick={() => onAction({ kind: "revoke-device", device })} title="撤销设备" type="button"><Ban size={16} /></button>
            </span>
          </div>
        );
      }) : <div className="empty">暂无已登记的眼镜设备。</div>}
    </section>
  );
}

function EquipmentMemoryDirectory({
  equipment,
  error,
  loading,
  onCreate,
  onEdit,
  onRetry,
}: {
  equipment: EquipmentMemory[];
  error: string;
  loading: boolean;
  onCreate: () => void;
  onEdit: (equipment: EquipmentMemory) => void;
  onRetry: () => Promise<void>;
}) {
  if (loading) return <section className="notice management-loading">正在读取设备记忆目录…</section>;
  if (error) return <RetryNotice error={error} onRetry={onRetry} />;
  return (
    <section className="surface management-directory">
      <div className="directory-toolbar">
        <span>设备记忆仅来自服务端授权目录，并按项目绑定过滤。</span>
        <span className="directory-toolbar-actions">
          <button aria-label="登记设备" className="primary-button" onClick={onCreate} type="button"><Plus size={15} />登记设备</button>
          <button className="secondary-button" onClick={() => void onRetry()} type="button"><RefreshCw size={15} />刷新</button>
        </span>
      </div>
      <div aria-hidden="true" className="management-table-head equipment-columns">
        <span>设备</span><span>状态</span><span>数量</span><span>关联项目</span><span>更新时间</span>
      </div>
      {equipment.length ? equipment.map((item) => (
        <div className="management-row equipment-columns" key={item.id}>
          <span className="management-identity equipment-identity"><Database size={18} /><span><strong>{item.system} / {item.brand} {item.model}</strong><small>{item.equipmentKey}</small><small>{item.keyParameter || "无关键参数"}</small></span><button aria-label={`编辑设备 ${item.equipmentKey}`} className="icon-button" disabled={!item.updatedAt} onClick={() => onEdit(item)} title={item.updatedAt ? "编辑设备资料" : "缺少版本信息，请刷新后再试"} type="button"><Pencil size={16} /></button></span>
          <span className={`status ${item.status}`}>{equipmentStatusLabel(item.status)}<small>故障 {item.faultCount} · 维修 {item.repairCount}</small></span>
          <span>{item.quantity}</span>
          <span>{item.linkedProjects.map((project) => <small key={project.projectId}>{project.title} · {project.taskCount} 个任务</small>)}</span>
          <time>{item.updatedAt ? formatTime(item.updatedAt) : "暂无"}</time>
        </div>
      )) : <div className="empty">当前没有已绑定项目的设备记忆。</div>}
    </section>
  );
}

function EquipmentDialog({
  api,
  equipment,
  loadingProjects,
  onCancel,
  onSaved,
  projects,
  projectsError,
}: {
  api: ManagementApi;
  equipment: EquipmentMemory | null;
  loadingProjects: boolean;
  onCancel: () => void;
  onSaved: () => Promise<void>;
  projects: Project[];
  projectsError: string;
}) {
  const editing = equipment !== null;
  const [equipmentKey, setEquipmentKey] = useState(equipment?.equipmentKey ?? "");
  const [system, setSystem] = useState(equipment?.system ?? "");
  const [brand, setBrand] = useState(equipment?.brand ?? "");
  const [model, setModel] = useState(equipment?.model ?? "");
  const [quantity, setQuantity] = useState(equipment?.quantity ?? 1);
  const [status, setStatus] = useState<EquipmentStatus>(equipmentStatusValue(equipment?.status));
  const [lastInspectionAt, setLastInspectionAt] = useState(dateTimeLocalValue(equipment?.lastInspectionAt));
  const [faultCount, setFaultCount] = useState(equipment?.faultCount ?? 0);
  const [repairCount, setRepairCount] = useState(equipment?.repairCount ?? 0);
  const [keyParameter, setKeyParameter] = useState(equipment?.keyParameter ?? "");
  const [projectIds, setProjectIds] = useState(() => equipment?.linkedProjects.map((project) => project.projectId) ?? []);
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  function toggleProject(projectId: string, checked: boolean) {
    setProjectIds((current) => checked
      ? current.includes(projectId) ? current : [...current, projectId]
      : current.filter((id) => id !== projectId));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (projectIds.length === 0) {
      setError("至少选择一个关联项目。");
      return;
    }
    if (editing && !equipment.updatedAt) {
      setError("设备缺少版本信息，请刷新目录后重新编辑。");
      return;
    }
    setSubmitting(true);
    setError("");
    const command: EquipmentCreateCommand = {
      equipmentKey: equipmentKey.trim(),
      system: system.trim(),
      brand: brand.trim(),
      model: model.trim(),
      quantity,
      status,
      lastInspectionAt: lastInspectionAt ? new Date(lastInspectionAt).toISOString() : null,
      faultCount,
      repairCount,
      keyParameter: keyParameter.trim(),
      projectIds,
      reason: reason.trim(),
    };
    try {
      if (editing) {
        const updateCommand: EquipmentUpdateCommand = {
          ...command,
          expectedUpdatedAt: equipment.updatedAt!,
        };
        await api.updateEquipment(equipment.id, updateCommand);
      } else {
        await api.createEquipment(command);
      }
      await onSaved();
    } catch (cause) {
      setError(equipmentCommandErrorMessage(cause));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <DialogFrame
      className="equipment-dialog"
      closeLabel={editing ? "关闭设备编辑窗口" : "关闭设备登记窗口"}
      eyebrow="设备目录"
      onCancel={onCancel}
      title={editing ? `编辑 ${equipment.equipmentKey}` : "登记设备"}
    >
      <form className="equipment-form" onSubmit={submit}>
        <p className="dialog-copy">保存后会原子更新设备资料、项目绑定和审计记录，眼镜端仍通过现有只读目录获取最新内容。</p>
        <div className="equipment-form-grid">
          <label>设备编号<input aria-label="设备编号" maxLength={120} onChange={(event) => setEquipmentKey(event.target.value)} required value={equipmentKey} /></label>
          <label>所属系统<input aria-label="所属系统" maxLength={120} onChange={(event) => setSystem(event.target.value)} required value={system} /></label>
          <label>设备品牌<input aria-label="设备品牌" maxLength={120} onChange={(event) => setBrand(event.target.value)} required value={brand} /></label>
          <label>设备型号<input aria-label="设备型号" maxLength={120} onChange={(event) => setModel(event.target.value)} required value={model} /></label>
          <label>设备数量<input aria-label="设备数量" max={100000} min={0} onChange={(event) => setQuantity(Number(event.target.value))} required type="number" value={quantity} /></label>
          <label>设备状态<select aria-label="设备状态" onChange={(event) => setStatus(event.target.value as EquipmentStatus)} value={status}><option value="normal">正常</option><option value="attention">需关注</option><option value="maintenance">维修中</option><option value="decommissioned">已退役</option></select></label>
          <label>故障次数<input aria-label="故障次数" max={1000000} min={0} onChange={(event) => setFaultCount(Number(event.target.value))} required type="number" value={faultCount} /></label>
          <label>维修次数<input aria-label="维修次数" max={1000000} min={0} onChange={(event) => setRepairCount(Number(event.target.value))} required type="number" value={repairCount} /></label>
          <label className="equipment-field-wide">最近巡检时间<input aria-label="最近巡检时间" onChange={(event) => setLastInspectionAt(event.target.value)} type="datetime-local" value={lastInspectionAt} /></label>
          <label className="equipment-field-wide">关键参数<textarea aria-label="关键参数" maxLength={1000} onChange={(event) => setKeyParameter(event.target.value)} rows={3} value={keyParameter} /></label>
          <fieldset className="equipment-project-fieldset equipment-field-wide">
            <legend>关联项目</legend>
            {loadingProjects ? <span className="dialog-copy">正在读取项目目录…</span> : projects.length ? (
              <div className="equipment-project-grid">
                {projects.map((project) => (
                  <label key={project.id}><input aria-label={project.title} checked={projectIds.includes(project.id)} disabled={Boolean(projectsError)} onChange={(event) => toggleProject(project.id, event.target.checked)} type="checkbox" /><span>{project.title}<small>{project.status}</small></span></label>
                ))}
              </div>
            ) : <span className="dialog-copy">当前没有可绑定项目。</span>}
          </fieldset>
          <label className="equipment-field-wide">操作原因<textarea aria-label="操作原因" maxLength={240} minLength={3} onChange={(event) => setReason(event.target.value)} required rows={3} value={reason} /></label>
        </div>
        {projectsError ? <p className="form-error" role="alert">{projectsError}</p> : null}
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <footer><button className="secondary-button" onClick={onCancel} type="button">取消</button><button className="primary-button" disabled={submitting || loadingProjects || Boolean(projectsError) || projectIds.length === 0} type="submit">{submitting ? "正在保存" : editing ? "确认更新" : "确认登记"}</button></footer>
      </form>
    </DialogFrame>
  );
}

function PeopleDirectory({
  error,
  loading,
  onAction,
  onInvite,
  projects,
  projectsError,
  onRetry,
  people,
}: {
  error: string;
  loading: boolean;
  onAction: (action: Action) => void;
  onInvite: () => void;
  projects: Project[];
  projectsError: string;
  onRetry: () => Promise<void>;
  people: Person[];
}) {
  if (loading) return <section className="notice management-loading">正在读取人员账号…</section>;
  if (error) return <RetryNotice error={error} onRetry={onRetry} />;
  return (
    <section className="surface management-directory">
      <div className="directory-toolbar">
        <span>邀请后由账号本人通过邮件完成首次登录设置，平台不保存密码。</span>
        <button aria-label="邀请人员账号" className="secondary-button" onClick={onInvite} type="button"><UserPlus size={15} />邀请人员</button>
      </div>
      <div aria-hidden="true" className="management-table-head people-columns">
        <span>人员</span><span>角色</span><span>账号状态</span><span>状态更新</span><span>操作</span>
      </div>
      {people.length ? people.map((person) => (
        <div className="management-row people-columns" key={person.id}>
          <span className="management-identity"><UserRound size={18} /><span><strong>{person.display_name || "未命名账号"}</strong><small>{person.email || person.id}</small></span></span>
          <span>{roleLabel(person.role)}</span>
          <span className={`status ${person.status}`}>{personStatusLabel(person.status)}</span>
          <time>{person.status_changed_at ? formatTime(person.status_changed_at) : "尚无变更"}</time>
          <span className="row-actions">
            {person.status !== "archived" ? <button aria-label={`调整 ${person.display_name} 角色`} className="icon-button" onClick={() => onAction({ kind: "person-role", person, role: person.role })} title="调整账号角色" type="button"><ShieldCheck size={16} /></button> : null}
            {person.status === "active" ? <button aria-label={`管理 ${person.display_name} 项目范围`} className="icon-button" disabled={!projects.length || Boolean(projectsError)} onClick={() => onAction({ kind: "person-scope", person })} title="管理项目范围" type="button"><Link2 size={16} /></button> : null}
            {person.status === "active" || person.status === "disabled" ? <button aria-label={`恢复 ${person.display_name} 登录身份`} className="icon-button" onClick={() => onAction({ kind: "person-recovery", person })} title="发送身份恢复邮件" type="button"><KeyRound size={16} /></button> : null}
            {person.status === "disabled" ? <button aria-label={`启用 ${person.display_name}`} className="icon-button" onClick={() => onAction({ kind: "person-status", person, status: "active" })} title="启用账号" type="button"><CheckCircle2 size={16} /></button> : null}
            {person.status === "active" ? <button aria-label={`停用 ${person.display_name}`} className="icon-button" onClick={() => onAction({ kind: "person-status", person, status: "disabled" })} title="停用账号" type="button"><Ban size={16} /></button> : null}
            {person.status !== "archived" ? <button aria-label={`归档 ${person.display_name}`} className="icon-button danger-icon" onClick={() => onAction({ kind: "person-status", person, status: "archived" })} title="归档账号" type="button"><Archive size={16} /></button> : null}
          </span>
        </div>
      )) : <div className="empty">当前组织暂无人员账号。</div>}
    </section>
  );
}

function RetryNotice({ error, onRetry }: { error: string; onRetry: () => Promise<void> }) {
  return (
    <section className="notice error management-retry" role="alert">
      <span>{error}</span>
      <button className="secondary-button" onClick={() => void onRetry()} type="button"><RefreshCw size={15} />重试</button>
    </section>
  );
}

function DeviceBindingDialog({
  device,
  loading,
  onCancel,
  onConfirm,
  people,
  projects,
  projectsError,
}: {
  device: Device;
  loading: boolean;
  onCancel: () => void;
  onConfirm: (command: DeviceBindingCommand) => Promise<void>;
  people: Person[];
  projects: Project[];
  projectsError: string;
}) {
  const [profileId, setProfileId] = useState("");
  const [projectId, setProjectId] = useState("");
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      await onConfirm({ profileId, projectId: projectId || null, reason: reason.trim() });
    } catch (cause) {
      setError(errorMessage(cause, "设备绑定失败，请检查权限与项目状态。"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <DialogFrame closeLabel="关闭绑定窗口" eyebrow="设备归属" onCancel={onCancel} title={`绑定 ${device.display_name}`}>
      <form onSubmit={submit}>
        <label>绑定人员<select aria-label="绑定人员" disabled={loading} onChange={(event) => setProfileId(event.target.value)} required value={profileId}><option value="">请选择人员</option>{people.map((person) => <option key={person.id} value={person.id}>{person.display_name}</option>)}</select></label>
        <label>所属项目<select aria-label="所属项目" disabled={loading || Boolean(projectsError)} onChange={(event) => setProjectId(event.target.value)} value={projectId}><option value="">不限定项目</option>{projects.map((project) => <option key={project.id} value={project.id}>{project.title}</option>)}</select></label>
        <label>操作原因<textarea aria-label="操作原因" minLength={3} onChange={(event) => setReason(event.target.value)} required rows={3} value={reason} /></label>
        {projectsError ? <p className="form-error" role="alert">{projectsError}</p> : null}
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <footer><button className="secondary-button" onClick={onCancel} type="button">取消</button><button className="primary-button" disabled={submitting || loading || !profileId} type="submit">{submitting ? "正在绑定" : "确认绑定"}</button></footer>
      </form>
    </DialogFrame>
  );
}

function DeviceCredentialDialog({
  device,
  onCancel,
  onIssue,
}: {
  device: Device;
  onCancel: () => void;
  onIssue: (reason: string) => Promise<DeviceCredential>;
}) {
  const [reason, setReason] = useState("");
  const [credential, setCredential] = useState<DeviceCredential | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      setCredential(await onIssue(reason.trim()));
    } catch (cause) {
      setError(errorMessage(cause, "凭据签发失败，请检查管理员权限后重试。"));
    } finally {
      setSubmitting(false);
    }
  }

  async function copyCredential() {
    if (!credential || !navigator.clipboard) return;
    await navigator.clipboard.writeText(credential.token);
    setCopied(true);
  }

  return (
    <DialogFrame closeLabel="关闭一次性凭据" eyebrow="受控凭据" onCancel={onCancel} title={`签发 ${device.display_name} 凭据`}>
      {credential ? (
        <div className="credential-result">
          <ShieldCheck size={28} />
          <div><strong>一次性设备凭据</strong><p>关闭窗口后不再显示，服务端只保存摘要。</p></div>
          <code>{credential.token}</code>
          <button aria-label="复制一次性凭据" className="icon-button" onClick={() => void copyCredential()} title="复制" type="button"><Copy size={16} /></button>
          <time>有效期至 {formatTime(credential.expiresAt)}</time>
          {copied ? <span className="credential-copied">已复制</span> : null}
        </div>
      ) : (
        <form onSubmit={submit}>
          <p className="dialog-copy">新凭据签发后旧凭据立即失效，操作会写入组织审计。</p>
          <label>操作原因<textarea aria-label="操作原因" minLength={3} onChange={(event) => setReason(event.target.value)} required rows={4} value={reason} /></label>
          {error ? <p className="form-error" role="alert">{error}</p> : null}
          <footer><button className="secondary-button" onClick={onCancel} type="button">取消</button><button className="primary-button" disabled={submitting} type="submit">{submitting ? "正在签发" : "确认签发"}</button></footer>
        </form>
      )}
    </DialogFrame>
  );
}

function DeviceActivationDialog({
  device,
  onCancel,
  onIssue,
}: {
  device: Device;
  onCancel: () => void;
  onIssue: (command: DeviceActivationCommand) => Promise<DeviceActivation>;
}) {
  const [reason, setReason] = useState("");
  const [expiresInSeconds, setExpiresInSeconds] = useState(600);
  const [activation, setActivation] = useState<DeviceActivation | null>(null);
  const [qrDataUrl, setQrDataUrl] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let active = true;
    if (!activation) {
      setQrDataUrl("");
      return () => { active = false; };
    }
    const payload = `dingdang-v9://activate?code=${encodeURIComponent(activation.activationCode)}`;
    void QRCode.toDataURL(payload, {
      errorCorrectionLevel: "M",
      margin: 2,
      width: 240,
      color: { dark: "#183f34", light: "#ffffff" },
    }).then((dataUrl) => {
      if (active) setQrDataUrl(dataUrl);
    }).catch(() => {
      if (active) setError("二维码生成失败，可复制激活码后在眼镜端手工输入。");
    });
    return () => { active = false; };
  }, [activation]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      setActivation(await onIssue({ reason: reason.trim(), expiresInSeconds }));
    } catch (cause) {
      setError(errorMessage(cause, "激活码生成失败，请检查设备绑定、账号权限和有效期。"));
    } finally {
      setSubmitting(false);
    }
  }

  async function copyActivationCode() {
    if (!activation || !navigator.clipboard) return;
    await navigator.clipboard.writeText(activation.activationCode);
    setCopied(true);
  }

  return (
    <DialogFrame closeLabel="关闭设备激活码" eyebrow="安全激活" onCancel={onCancel} title={`生成 ${device.display_name} 激活码`}>
      {activation ? (
        <div className="activation-result">
          <div className="activation-qr">
            {qrDataUrl ? <img alt="设备激活二维码" height={240} src={qrDataUrl} width={240} /> : <span>正在生成二维码…</span>}
          </div>
          <div className="activation-delivery">
            <div><strong>一次性设备激活码</strong><p>明文只在本窗口显示一次，服务端仅保存摘要。</p></div>
            <div className="activation-code-row">
              <code>{activation.activationCode}</code>
              <button aria-label="复制设备激活码" className="icon-button" onClick={() => void copyActivationCode()} title="复制" type="button"><Copy size={16} /></button>
            </div>
            <time>有效期至 {formatTime(activation.expiresAt)}</time>
            {copied ? <span className="credential-copied">已复制</span> : null}
            {error ? <p className="form-error" role="alert">{error}</p> : null}
          </div>
        </div>
      ) : (
        <form onSubmit={submit}>
          <p className="dialog-copy">生成后可由眼镜直接扫描，不会向 APK 下发供应商密钥、密码或管理员令牌。</p>
          <label>有效期<select aria-label="有效期" onChange={(event) => setExpiresInSeconds(Number(event.target.value))} value={expiresInSeconds}><option value={300}>5 分钟</option><option value={600}>10 分钟</option><option value={900}>15 分钟</option></select></label>
          <label>操作原因<textarea aria-label="操作原因" minLength={3} onChange={(event) => setReason(event.target.value)} required rows={4} value={reason} /></label>
          {error ? <p className="form-error" role="alert">{error}</p> : null}
          <footer><button className="secondary-button" onClick={onCancel} type="button">取消</button><button className="primary-button" disabled={submitting} type="submit">{submitting ? "正在生成" : "确认生成"}</button></footer>
        </form>
      )}
    </DialogFrame>
  );
}

function InvitePersonDialog({
  onCancel,
  onConfirm,
}: {
  onCancel: () => void;
  onConfirm: (command: InvitePersonCommand) => Promise<void>;
}) {
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [role, setRole] = useState<Person["role"]>("field_engineer");
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      await onConfirm({ email: email.trim(), displayName: displayName.trim(), role, reason: reason.trim() });
    } catch (cause) {
      setError(errorMessage(cause, "邀请失败，请检查账号权限、邮箱和邮件服务状态。"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <DialogFrame closeLabel="关闭邀请窗口" eyebrow="账号生命周期" onCancel={onCancel} title="邀请人员账号">
      <form onSubmit={submit}>
        <p className="dialog-copy">平台只发送 Supabase Auth 邀请邮件，不接收、不生成、不保存登录密码。</p>
        <label>账号邮箱<input aria-label="账号邮箱" autoComplete="email" onChange={(event) => setEmail(event.target.value)} required type="email" value={email} /></label>
        <label>显示名称<input aria-label="显示名称" onChange={(event) => setDisplayName(event.target.value)} required value={displayName} /></label>
        <label>组织角色<select aria-label="组织角色" onChange={(event) => setRole(event.target.value as Person["role"])} value={role}>{roleOptions().map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
        <label>邀请原因<textarea aria-label="邀请原因" minLength={3} onChange={(event) => setReason(event.target.value)} required rows={3} value={reason} /></label>
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <footer><button className="secondary-button" onClick={onCancel} type="button">取消</button><button className="primary-button" disabled={submitting} type="submit">{submitting ? "正在发送邀请" : "确认邀请"}</button></footer>
      </form>
    </DialogFrame>
  );
}

function AccountRecoveryDialog({
  person,
  onCancel,
  onConfirm,
}: {
  person: Person;
  onCancel: () => void;
  onConfirm: (reason: string) => Promise<void>;
}) {
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      await onConfirm(reason.trim());
    } catch (cause) {
      setError(errorMessage(cause, "恢复邮件发送失败，请检查邮件服务和账号状态后重试。"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <DialogFrame closeLabel="关闭身份恢复窗口" eyebrow="身份恢复" onCancel={onCancel} title={`恢复 ${person.display_name} 登录身份`}>
      <form onSubmit={submit}>
        <p className="dialog-copy">平台只请求 Supabase Auth 发送恢复邮件，不生成或显示恢复链接和新密码；账号状态、角色与项目权限保持不变。</p>
        <label>恢复原因<textarea aria-label="恢复原因" minLength={3} onChange={(event) => setReason(event.target.value)} required rows={4} value={reason} /></label>
        {person.status === "disabled" ? <p className="dialog-copy">该账号仍处于停用状态，设置新密码后也必须由超级管理员重新启用才能登录。</p> : null}
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <footer><button className="secondary-button" onClick={onCancel} type="button">取消</button><button className="primary-button" disabled={submitting} type="submit">{submitting ? "正在发送" : "发送恢复邮件"}</button></footer>
      </form>
    </DialogFrame>
  );
}

function PersonRoleDialog({
  person,
  initialRole,
  onCancel,
  onConfirm,
}: {
  person: Person;
  initialRole: Person["role"];
  onCancel: () => void;
  onConfirm: (command: PersonRoleCommand) => Promise<void>;
}) {
  const [role, setRole] = useState<Person["role"]>(initialRole);
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      await onConfirm({ role, reason: reason.trim() });
    } catch (cause) {
      setError(errorMessage(cause, "角色调整失败，请检查超级管理员权限和组织状态。"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <DialogFrame closeLabel="关闭角色窗口" eyebrow="权限治理" onCancel={onCancel} title={`调整 ${person.display_name} 的角色`}>
      <form onSubmit={submit}>
        <p className="dialog-copy">角色变更会立即影响组织权限，并写入不可修改的审计记录。</p>
        <label>组织角色<select aria-label="组织角色" onChange={(event) => setRole(event.target.value as Person["role"])} value={role}>{roleOptions().map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
        <label>变更原因<textarea aria-label="变更原因" minLength={3} onChange={(event) => setReason(event.target.value)} required rows={3} value={reason} /></label>
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <footer><button className="secondary-button" onClick={onCancel} type="button">取消</button><button className="primary-button" disabled={submitting} type="submit">{submitting ? "正在保存" : "确认变更"}</button></footer>
      </form>
    </DialogFrame>
  );
}

function roleOptions(): Array<{ value: Person["role"]; label: string }> {
  return [
    { value: "super_admin", label: "超级管理员" },
    { value: "ops_admin", label: "运维管理员" },
    { value: "field_engineer", label: "现场工程师" },
    { value: "remote_expert", label: "远程专家" },
    { value: "viewer", label: "只读人员" },
  ];
}

function PersonScopeDialog({
  api,
  person,
  projects,
  projectsError,
  onCancel,
}: {
  api: ManagementApi;
  person: Person;
  projects: Project[];
  projectsError: string;
  onCancel: () => void;
}) {
  const [projectId, setProjectId] = useState(projects[0]?.id ?? "");
  const [accessRole, setAccessRole] = useState<ProjectMembershipCommand["accessRole"]>("engineer");
  const [membership, setMembership] = useState<ProjectMembership | null>(null);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");

  const loadMembership = useCallback(async () => {
    if (!projectId) {
      setMembership(null);
      return;
    }
    setLoading(true);
    setError("");
    try {
      const items = await api.getProjectMemberships(projectId);
      const current = items.find((item) => item.profile_id === person.id && item.status === "active") ?? null;
      setMembership(current);
      setAccessRole(current?.access_role ?? "engineer");
    } catch (cause) {
      setError(errorMessage(cause, "无法读取项目授权，请检查管理员权限和网络状态。"));
    } finally {
      setLoading(false);
    }
  }, [api, person.id, projectId]);

  useEffect(() => { void loadMembership(); }, [loadMembership]);

  async function grant(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      await api.grantProjectMembership(projectId, {
        profileId: person.id,
        accessRole,
        reason: reason.trim(),
      });
      setReason("");
      await loadMembership();
    } catch (cause) {
      setError(errorMessage(cause, "项目授权失败，请检查人员状态、项目状态和权限。"));
    } finally {
      setSubmitting(false);
    }
  }

  async function revoke() {
    if (!membership || !reason.trim()) {
      setError("撤销授权必须填写原因。");
      return;
    }
    setSubmitting(true);
    setError("");
    try {
      await api.revokeProjectMembership(projectId, person.id, { reason: reason.trim() });
      setMembership(null);
      setReason("");
    } catch (cause) {
      setError(errorMessage(cause, "撤销项目授权失败，请稍后重试。"));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <DialogFrame closeLabel="关闭项目授权窗口" eyebrow="项目范围" onCancel={onCancel} title={`管理 ${person.display_name} 的项目授权`}>
      <form onSubmit={grant}>
        <p className="dialog-copy">项目范围决定该账号能看到的现场任务、设备和证据，不会改变组织角色。</p>
        <label>项目<select aria-label="授权项目" disabled={loading || Boolean(projectsError)} onChange={(event) => setProjectId(event.target.value)} value={projectId}>{projects.map((project) => <option key={project.id} value={project.id}>{project.title}</option>)}</select></label>
        <label>项目角色<select aria-label="项目角色" disabled={loading} onChange={(event) => setAccessRole(event.target.value as ProjectMembershipCommand["accessRole"])} value={accessRole}><option value="manager">项目负责人</option><option value="engineer">现场工程师</option><option value="expert">远程专家</option><option value="viewer">只读成员</option></select></label>
        {membership ? <p className="dialog-copy">当前授权：{projectAccessRoleLabel(membership.access_role)}。再次确认可更新项目角色。</p> : <p className="dialog-copy">当前项目尚未授权给该账号。</p>}
        <label>操作原因<textarea aria-label="项目授权原因" minLength={3} onChange={(event) => setReason(event.target.value)} required rows={3} value={reason} /></label>
        {projectsError ? <p className="form-error" role="alert">{projectsError}</p> : null}
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <footer>
          <button className="secondary-button" onClick={onCancel} type="button">关闭</button>
          {membership ? <button className="danger-button" disabled={submitting || loading} onClick={() => void revoke()} type="button">{submitting ? "正在处理" : "撤销授权"}</button> : null}
          <button className="primary-button" disabled={submitting || loading || !projectId} type="submit">{submitting ? "正在保存" : membership ? "更新授权" : "确认授权"}</button>
        </footer>
      </form>
    </DialogFrame>
  );
}

function projectAccessRoleLabel(role: ProjectMembershipCommand["accessRole"]): string {
  return role === "manager" ? "项目负责人" : role === "engineer" ? "现场工程师" : role === "expert" ? "远程专家" : "只读成员";
}

function DialogFrame({
  children,
  className = "",
  closeLabel,
  eyebrow,
  onCancel,
  title,
}: {
  children: React.ReactNode;
  className?: string;
  closeLabel: string;
  eyebrow: string;
  onCancel: () => void;
  title: string;
}) {
  const headingId = `dialog-${title.replace(/\s+/g, "-")}`;
  return (
    <div className="dialog-backdrop">
      <section aria-labelledby={headingId} aria-modal="true" className={`workflow-dialog governance-dialog ${className}`.trim()} role="dialog">
        <header><div><span className="eyebrow">{eyebrow}</span><h2 id={headingId}>{title}</h2></div><button aria-label={closeLabel} className="icon-button" onClick={onCancel} type="button"><X size={17} /></button></header>
        {children}
      </section>
    </div>
  );
}

type ReasonAction = Exclude<Action, { kind: "bind" } | { kind: "credential" } | { kind: "activation" } | { kind: "person-recovery" } | { kind: "person-role" } | { kind: "person-scope" } | { kind: "equipment-create" } | { kind: "equipment-edit" } | { kind: "invite" }>;

function isReasonAction(action: Action | null): action is ReasonAction {
  return Boolean(
    action
      && action.kind !== "bind"
      && action.kind !== "credential"
      && action.kind !== "activation"
      && action.kind !== "person-recovery"
      && action.kind !== "person-role"
      && action.kind !== "person-scope"
      && action.kind !== "equipment-create"
      && action.kind !== "equipment-edit"
      && action.kind !== "invite",
  );
}

function reasonActionTitle(action: ReasonAction): string {
  if (action.kind === "unbind") return `解绑 ${action.device.display_name}`;
  if (action.kind === "revoke-device") return `撤销 ${action.device.display_name}`;
  return `${personStatusActionLabel(action.status)} ${action.person.display_name}`;
}

function reasonActionConfirmLabel(action: ReasonAction): string {
  if (action.kind === "unbind") return "确认解绑";
  if (action.kind === "revoke-device") return "确认撤销";
  return `确认${personStatusActionLabel(action.status)}`;
}

function reasonActionDescription(action: ReasonAction): string {
  if (action.kind === "unbind") return "解绑后设备不再属于当前人员或项目，但历史任务与审计记录保留。";
  if (action.kind === "revoke-device") return "撤销后设备会话和受管凭据失效，设备不能继续访问 V9 服务。";
  if (action.status === "active") return "启用后账号恢复按角色和项目范围访问。";
  if (action.status === "disabled") return "停用后账号不能继续登录，已有审计与历史任务保留。";
  return "归档后账号仅保留历史引用和审计记录。";
}

function personStatusActionLabel(status: PersonStatus): string {
  return status === "active" ? "启用" : status === "disabled" ? "停用" : "归档";
}

function personStatusLabel(status: PersonStatus): string {
  return status === "invited" ? "待接受邀请" : status === "active" ? "正常" : status === "disabled" ? "已停用" : "已归档";
}

function roleLabel(role: Person["role"]): string {
  return ({
    super_admin: "超级管理员",
    ops_admin: "运维管理员",
    field_engineer: "现场工程师",
    remote_expert: "远程专家",
    viewer: "只读人员",
  })[role];
}

function deviceMeta(device: Device): string {
  const parts = [device.model, device.app_version ? `App ${device.app_version}` : ""].filter(Boolean);
  return parts.length ? parts.join(" · ") : "型号与版本尚未上报";
}

function mdmLabel(device: Device): string {
  if (!device.mdm_policy_version && !device.mdm_compliance_status) return "MDM 未上报";
  const state = device.mdm_compliance_status === "compliant" ? "合规" : device.mdm_compliance_status || "状态未知";
  return `${device.mdm_policy_version || "未标版本"} · ${state}`;
}

function contentManifestLabel(device: Device): string {
  if (device.manifest_version === null || device.manifest_version === undefined) return "Skill/知识清单未生成";
  return `Skill/知识清单 v${device.manifest_version}`;
}

function credentialLabel(device: Device): string {
  if (device.credential_status === "active") return "设备凭据有效";
  if (device.credential_status === "expired") return "设备凭据已过期";
  if (device.credential_status === "revoked") return "设备凭据已撤销";
  return "设备凭据未签发";
}

function syncHealthLabel(device: Device): string {
  if (device.sync_health === "healthy") return "同步正常";
  if (device.sync_health === "blocked") return `同步阻断 · ${syncIssueLabel(device.sync_issue)}`;
  if (device.sync_health === "offline") return `设备离线 · ${syncIssueLabel(device.sync_issue)}`;
  return `需要处理 · ${syncIssueLabel(device.sync_issue)}`;
}

function syncIssueLabel(issue?: string): string {
  return ({
    device_revoked: "设备已撤销",
    mdm_noncompliant: "MDM 不合规",
    credential_missing: "缺少凭据",
    credential_expired: "凭据过期",
    credential_revoked: "凭据撤销",
    device_offline: "超过 20 分钟未同步",
    device_never_seen: "从未上线",
    mdm_unknown: "MDM 未确认",
    session_missing: "暂无短期会话",
    session_expired: "短期会话过期",
    session_revoked: "短期会话撤销",
    manifest_missing: "清单未生成",
    manifest_expired: "清单过期",
    manifest_revoked: "清单撤销",
    manifest_superseded: "清单已替换",
  } as Record<string, string>)[issue ?? ""] ?? "状态待确认";
}

function deviceStatusLabel(device: Device): string {
  if (device.revoked_at) return "已撤销";
  if (device.status === "online") return "在线";
  if (device.status === "disabled") return "已停用";
  return "离线";
}

function equipmentStatusLabel(status: string): string {
  if (status === "normal") return "正常";
  if (status === "attention") return "需关注";
  if (status === "maintenance") return "维修中";
  if (status === "decommissioned") return "已停用";
  return status || "未知";
}

function equipmentStatusValue(status: string | undefined): EquipmentStatus {
  return status === "attention" || status === "maintenance" || status === "decommissioned" ? status : "normal";
}

function dateTimeLocalValue(value: string | null | undefined): string {
  if (!value) return "";
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return "";
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return local.toISOString().slice(0, 16);
}

function equipmentCommandErrorMessage(cause: unknown): string {
  if (cause instanceof ManagementApiError) {
    if (cause.code === "equipment_version_conflict") return "设备资料已被其他管理员更新，请刷新后重新编辑。";
    if (cause.code === "equipment_key_conflict") return "设备编号已存在，请更换后重试。";
    if (cause.code === "equipment_idempotency_conflict") return "本次提交与已处理请求不一致，请重新打开窗口再试。";
    if (cause.code === "equipment_project_not_found") return "关联项目已失效，请刷新项目目录后重试。";
    if (cause.code === "equipment_not_found") return "设备已不存在，请刷新目录。";
    if (cause.code === "equipment_management_unavailable") return "设备目录写入服务暂不可用，已填写内容不会丢失。";
  }
  return errorMessage(cause, "设备资料保存失败，请检查网络和项目绑定后重试。");
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}
