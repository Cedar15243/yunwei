import { Fingerprint, MonitorSmartphone, RefreshCw, ShieldCheck, UserRound, XCircle } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import type {
  Device,
  ManagementApi,
  Person,
  VoiceprintProfile,
  VoiceprintStatus,
} from "../../api/management-api";
import { formatTime } from "../dashboard/DashboardPage";
import { ReasonDialog } from "../governance/GovernanceDialogs";
import "./voiceprints.css";

export function VoiceprintsPage({ api }: { api: ManagementApi }) {
  const [profiles, setProfiles] = useState<VoiceprintProfile[]>([]);
  const [people, setPeople] = useState<Person[]>([]);
  const [devices, setDevices] = useState<Device[]>([]);
  const [auditChainValid, setAuditChainValid] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<VoiceprintProfile | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [voiceprints, peopleResult, devicesResult] = await Promise.all([
        api.getVoiceprints(),
        api.getPeople(),
        api.getDevices(),
      ]);
      setProfiles(voiceprints.items);
      setAuditChainValid(voiceprints.auditChainValid);
      setPeople(peopleResult);
      setDevices(devicesResult);
    } catch (cause) {
      setError(errorMessage(cause, "声纹管理服务暂不可用，请检查云端网关配置。"));
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => {
    void load();
  }, [load]);

  const peopleById = useMemo(
    () => new Map(people.map((person) => [person.id, person.display_name])),
    [people],
  );
  const devicesById = useMemo(() => {
    const entries: Array<[string, string]> = [];
    for (const device of devices) {
      entries.push([device.id, device.display_name], [device.device_key, device.display_name]);
    }
    return new Map(entries);
  }, [devices]);

  return (
    <section className="page-stack voiceprint-page">
      <header className="workspace-actions">
        <div><h2>声纹管理</h2><p>查看本人授权、录入、验证与锁定状态；管理员不能代替用户录入。</p></div>
        <span className={`voiceprint-audit ${auditChainValid ? "healthy" : "unhealthy"}`}>
          <ShieldCheck size={16} />{auditChainValid ? "审计链正常" : "审计链异常"}
        </span>
      </header>

      {loading ? <section className="notice voiceprint-loading">正在读取声纹档案…</section> : null}
      {!loading && error ? (
        <section className="notice error voiceprint-retry" role="alert">
          <span>{error}</span>
          <button className="secondary-button" onClick={() => void load()} type="button"><RefreshCw size={15} />重试</button>
        </section>
      ) : null}
      {!loading && !error && profiles.length === 0 ? <section className="notice">当前组织暂无声纹档案。</section> : null}
      {!loading && !error && profiles.length > 0 ? (
        <section className="surface voiceprint-directory">
          <div aria-hidden="true" className="voiceprint-table-head voiceprint-columns">
            <span>人员</span><span>眼镜设备</span><span>状态</span><span>录入与失败</span><span>最近验证</span><span>操作</span>
          </div>
          {profiles.map((profile) => {
            const personName = peopleById.get(profile.userId) ?? profile.userId;
            const deviceName = devicesById.get(profile.deviceId) ?? profile.deviceId;
            return (
              <article className="voiceprint-row voiceprint-columns" key={profile.profileId}>
                <span className="voiceprint-identity"><UserRound size={18} /><span><strong>{personName}</strong><small>{profile.userId}</small></span></span>
                <span className="voiceprint-identity"><MonitorSmartphone size={18} /><span><strong>{deviceName}</strong><small>{profile.deviceId}</small></span></span>
                <span><span className={`status ${profile.status}`}>{statusLabel(profile.status)}</span><small>讯飞声纹（新）</small></span>
                <span><strong>{profile.sampleCount}/{profile.requiredSamples} 段</strong><small>连续失败 {profile.verificationFailures} 次</small></span>
                <span><strong>{profile.lastVerifiedAt ? formatTime(profile.lastVerifiedAt) : "尚未验证"}</strong><small>{profile.consentVersion ? `授权 ${profile.consentVersion}` : "尚未授权"}</small></span>
                <span className="row-actions">
                  {profile.canRevoke ? <button aria-label={`撤销 ${personName} 的声纹`} className="icon-button danger-icon" onClick={() => setSelected(profile)} title="撤销声纹" type="button"><XCircle size={16} /></button> : null}
                </span>
              </article>
            );
          })}
        </section>
      ) : null}

      {selected ? (
        <ReasonDialog
          confirmLabel="确认撤销声纹"
          description="撤销后服务端会删除讯飞模板引用，眼镜不能继续使用声纹监听；历史审计仍保留。"
          onCancel={() => setSelected(null)}
          onConfirm={async (reason, idempotencyKey) => {
            await api.revokeVoiceprint(selected.profileId, { reason, idempotencyKey });
            setSelected(null);
            await load();
          }}
          title={`撤销 ${peopleById.get(selected.userId) ?? selected.userId} 的声纹`}
        />
      ) : null}
    </section>
  );
}

function statusLabel(status: VoiceprintStatus): string {
  return {
    new: "待授权",
    enrolling: "录入中",
    pending_verification: "待验证",
    active: "已激活",
    locked: "已锁定",
    deleted: "本人已删除",
    revoked: "管理员已撤销",
  }[status];
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error ? cause.message : fallback;
}
