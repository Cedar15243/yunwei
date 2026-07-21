import { Search } from "lucide-react";
import type { CollaborationSnapshot } from "../collaboration-controller";

const experts = [
  { id: "expert-wang", name: "王工", specialty: "电气设备", status: "unknown", avatar: "https://images.unsplash.com/photo-1708010842657-76604986a083?auto=format&fit=crop&w=160&h=160&q=85" },
  { id: "expert-liu", name: "刘工", specialty: "自动化控制", status: "unknown", avatar: "https://images.unsplash.com/photo-1544168190-79c17527004f?auto=format&fit=crop&w=160&h=160&q=85" },
  { id: "expert-zhou", name: "周工", specialty: "网络与服务器", status: "unknown", avatar: "https://images.unsplash.com/photo-1672155411493-d36070f0c8f8?auto=format&fit=crop&w=160&h=160&q=85" },
  { id: "expert-chen", name: "陈工", specialty: "机械维护", status: "unknown", avatar: "https://images.unsplash.com/photo-1520689728498-7dd1a9814607?auto=format&fit=crop&w=160&h=160&q=85" },
] as const;

interface ContactRailProps {
  call: CollaborationSnapshot | null;
}

export function ContactRail({ call }: ContactRailProps) {
  const hasActiveDevice = Boolean(call?.sessionId && call.glassesId && call.status !== "ended");
  const deviceName = hasActiveDevice ? (call?.glassesName ?? "现场设备") : "等待现场设备接入";
  const deviceDetail = hasActiveDevice
    ? call?.status === "in_call" ? "正在专家协同" : "正在建立协同会话"
    : "实时状态将在设备注册后显示";
  return (
    <aside className="contact-rail" aria-label="协同对象">
      <div className="rail-heading">协同对象</div>
      <label className="search-field">
        <Search aria-hidden="true" size={16} />
        <input aria-label="搜索专家或眼镜设备" placeholder="搜索专家或眼镜设备" />
      </label>

      <button className={hasActiveDevice ? "contact-row contact-row--active" : "contact-row"} type="button">
        <span className="avatar avatar--device">镜</span>
        <span className="contact-copy">
          <strong>{deviceName}</strong>
          <small>{deviceDetail}</small>
        </span>
        <span className={`presence presence--${hasActiveDevice ? "online" : "unknown"}`} title={hasActiveDevice ? "设备已接入" : "等待设备接入"} />
      </button>

      <div className="rail-heading rail-heading--section">专家目录</div>
      <div className="contact-list">
        {experts.map((expert) => (
          <button className="contact-row" type="button" key={expert.id}>
            <span className="avatar avatar--portrait"><img alt={`${expert.name}${expert.specialty}头像`} src={expert.avatar} /></span>
            <span className="contact-copy">
              <strong>{expert.name}</strong>
              <small>{expert.specialty}</small>
            </span>
            <span className={`presence presence--${expert.status}`} title="接入状态待同步" />
          </button>
        ))}
      </div>

      <div className="call-rule">
        <strong>专家协同规则</strong>
        <p>首位接听者成为主专家，其他专家可受邀加入语音旁听。</p>
      </div>
    </aside>
  );
}
