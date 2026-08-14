import {
  Database,
  KeyRound,
  LockKeyhole,
  Mic2,
  RefreshCw,
  ServerCog,
  ShieldCheck,
  Workflow,
} from "lucide-react";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import type { ManagementApi, SystemStatus } from "../../api/management-api";
import "./operations.css";

export function SystemSettingsPage({ api }: { api: ManagementApi }) {
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setStatus(await api.getSystemStatus());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "系统设置状态暂不可用，请稍后重试。");
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => { void load(); }, [load]);

  return (
    <section className="page-stack operations-page system-settings-page">
      <header className="workspace-actions">
        <div><h2>系统设置</h2><p>查看 V9 受控运行策略。模型、密钥边界和工单写入权限由服务端管理，管理端不提供本地假配置。</p></div>
        <span className="operations-caption"><LockKeyhole size={16} />受控只读</span>
      </header>

      {loading ? <section className="notice">正在读取系统设置状态…</section> : null}
      {!loading && error ? <section className="notice error operations-retry" role="alert"><span>{error}</span><button className="secondary-button" type="button" onClick={() => void load()}><RefreshCw size={15} />重试设置状态</button></section> : null}
      {!loading && !error && status ? <>
        <section className="settings-grid">
          <SettingGroup icon={<ShieldCheck size={17} />} title="模型与语音策略" badge="服务端锁定">
            <SettingRow label="主 AI / 视觉" value={status.modelContract.mainAiModel} />
            <SettingRow label="实时 ASR" value={status.modelContract.realtimeAsrModel} />
            <SettingRow label="小叮当唤醒" value="上一版讯飞 AIKit" />
            <SettingRow label="声纹 1:1" value={status.modelContract.voiceprintService} />
          </SettingGroup>
          <SettingGroup icon={<KeyRound size={17} />} title="身份与密钥边界" badge="禁止眼镜持有">
            <SettingRow label="设备会话" value="短期会话，最长 15 分钟" />
            <SettingRow label="供应商密钥" value="仅服务器 root-only 环境" />
            <SettingRow label="APK 直连 MVS" value="禁止，统一经过工单网关" />
            <SettingRow label="声纹原始音频" value="不持久化，仅服务端转发" />
          </SettingGroup>
          <SettingGroup icon={<Workflow size={17} />} title="工作流与工单策略" badge="签名后推送">
            <SettingRow label="工作流发布" value="服务端校验、签名、版本不可变" />
            <SettingRow label="眼镜执行" value="仅白名单能力节点" />
            <SettingRow label="高风险操作" value="必须二次确认" />
            <SettingRow label="AI 写入 MVS" value="禁止，只能建议或生成草稿" />
          </SettingGroup>
          <SettingGroup icon={<Mic2 size={17} />} title="音频协调策略" badge="互斥占用麦克风">
            <SettingRow label="小叮当模式" value="保留，唤醒后执行一轮命令" />
            <SettingRow label="声纹监听" value="双击开启，验证通过后才执行" />
            <SettingRow label="相机 / 视频 / 专家通话" value="占用音频时暂停监听" />
            <SettingRow label="监听关闭" value="不录音、不转写、不上传" />
          </SettingGroup>
        </section>

        <section className="surface settings-integrations">
          <div className="section-heading"><div><h2>当前集成状态</h2><p>只显示是否已配置，不显示地址、密钥或令牌。</p></div><button className="secondary-button" type="button" onClick={() => void load()}><RefreshCw size={15} />刷新</button></div>
          <div className="settings-integration-grid">
            <IntegrationStatus icon={<Database size={16} />} label="Skill / 知识同步" ready={status.integrations.contentSyncConfigured} />
            <IntegrationStatus icon={<Mic2 size={16} />} label="声纹管理代理" ready={status.integrations.voiceprintAdminConfigured} />
            <IntegrationStatus icon={<ServerCog size={16} />} label="设备激活后端" ready={status.integrations.deviceActivationBackendConfigured} />
          </div>
        </section>
      </> : null}
    </section>
  );
}

function SettingGroup({ icon, title, badge, children }: { icon: ReactNode; title: string; badge: string; children: ReactNode }) {
  return <section className="surface setting-group"><header><span className="setting-group-title">{icon}<strong>{title}</strong></span><span className="status active">{badge}</span></header><div className="setting-list">{children}</div></section>;
}

function SettingRow({ label, value }: { label: string; value: string }) {
  return <div className="setting-row"><span>{label}</span><strong>{value}</strong></div>;
}

function IntegrationStatus({ icon, label, ready }: { icon: ReactNode; label: string; ready: boolean }) {
  return <div className="settings-integration-item"><span>{icon}{label}</span><strong className={ready ? "ready" : "not-ready"}>{ready ? "已配置" : "未配置"}</strong></div>;
}
