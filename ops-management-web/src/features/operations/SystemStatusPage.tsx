import { CheckCircle2, LockKeyhole, RefreshCw, Settings2, TriangleAlert } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import type { ContentDistributionItem, ManagementApi, SystemStatus } from "../../api/management-api";
import { formatTime } from "../dashboard/DashboardPage";
import "./operations.css";

export function SystemStatusPage({ api }: { api: ManagementApi }) {
  const [status, setStatus] = useState<SystemStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setStatus(await api.getSystemStatus());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "系统状态暂不可用，请稍后重试。");
    } finally {
      setLoading(false);
    }
  }, [api]);

  useEffect(() => { void load(); }, [load]);

  return (
    <section className="page-stack operations-page">
      <header className="workspace-actions">
        <div><h2>系统运行</h2><p>查看 V9 服务合同、受控集成和眼镜内容清单状态。</p></div>
        <span className="operations-caption"><Settings2 size={16} />只读运行合同</span>
      </header>
      {loading ? <section className="notice">正在读取系统状态…</section> : null}
      {!loading && error ? <section className="notice error operations-retry" role="alert"><span>{error}</span><button className="secondary-button" type="button" onClick={() => void load()}><RefreshCw size={15} />重试系统状态</button></section> : null}
      {!loading && !error && status ? <>
        <section className="surface system-contract">
          <div className="section-heading"><div><h2>模型与安全合同</h2><p>以下能力由 V9 服务端固定，管理端不能改成其他模型。</p></div><span className="status active"><LockKeyhole size={14} />{status.modelContract.locked ? "已锁定" : "未锁定"}</span></div>
          <div className="contract-grid">
            <ContractItem label="主 AI / 视觉" value={status.modelContract.mainAiModel} />
            <ContractItem label="实时语音识别" value={status.modelContract.realtimeAsrModel} />
            <ContractItem label="小叮当唤醒" value={status.modelContract.wakeEngine === "iflytek-aikit-previous" ? "上一版讯飞 AIKit" : status.modelContract.wakeEngine} />
            <ContractItem label="声纹 1:1" value={status.modelContract.voiceprintService === "iflytek/s1aa729d0" ? "讯飞 s1aa729d0" : status.modelContract.voiceprintService} />
          </div>
        </section>
        <section className="surface system-integrations">
          <div className="section-heading"><div><h2>受控集成</h2><p>只显示配置是否完整，不显示密钥、令牌或供应商凭据。</p></div></div>
          <div className="integration-grid">
            <IntegrationItem label="Skill / 知识同步" ready={status.integrations.contentSyncConfigured} />
            <IntegrationItem label="声纹管理代理" ready={status.integrations.voiceprintAdminConfigured} />
            <IntegrationItem label="设备激活后端" ready={status.integrations.deviceActivationBackendConfigured} />
          </div>
        </section>
        <section className="surface content-distribution">
          <div className="section-heading"><div><h2>内容清单状态</h2><p>这是眼镜最近生成的权威清单状态；异常不会被标记为已同步。</p></div><strong>{status.contentDistribution.healthyDevices}/{status.contentDistribution.totalDevices} 台正常</strong></div>
          {status.contentDistribution.items.length === 0 ? <p className="empty">组织内暂无受管眼镜。</p> : <div className="distribution-table"><div aria-hidden="true" className="distribution-row distribution-head"><span>设备</span><span>清单</span><span>版本</span><span>有效期</span></div>{status.contentDistribution.items.map((item) => <DistributionRow item={item} key={item.deviceId} />)}</div>}
        </section>
      </> : null}
    </section>
  );
}

function ContractItem({ label, value }: { label: string; value: string }) {
  return <div className="contract-item"><span>{label}</span><strong>{value}</strong></div>;
}

function IntegrationItem({ label, ready }: { label: string; ready: boolean }) {
  return <div className="integration-item"><span>{ready ? <CheckCircle2 size={16} /> : <TriangleAlert size={16} />}{label}</span><strong className={ready ? "ready" : "not-ready"}>{ready ? "已配置" : `${label}未配置`}</strong></div>;
}

function DistributionRow({ item }: { item: ContentDistributionItem }) {
  return <div className="distribution-row"><span><strong>{item.displayName}</strong><small>{item.deviceKey} · {item.deviceStatus}</small></span><span><strong className={`distribution-status ${item.manifestStatus}`}>{distributionLabel(item.manifestStatus)}</strong><small>{item.projectId ?? "未绑定项目"}</small></span><span>{item.manifestVersion ?? "—"}</span><span><strong>{item.expiresAt ? formatTime(item.expiresAt) : "—"}</strong><small>{item.generatedAt ? `生成于 ${formatTime(item.generatedAt)}` : "没有清单记录"}</small></span></div>;
}

function distributionLabel(status: ContentDistributionItem["manifestStatus"]): string {
  return { healthy: "正常", missing: "缺少清单", expired: "内容清单已过期", revoked: "清单已撤销", superseded: "旧清单已替换" }[status];
}
