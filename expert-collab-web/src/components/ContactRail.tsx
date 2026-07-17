import { Search } from "lucide-react";

const experts = [
  { id: "expert-wang", name: "王工", specialty: "电气设备", status: "online" },
  { id: "expert-liu", name: "刘工", specialty: "自动化控制", status: "online" },
  { id: "expert-zhou", name: "周工", specialty: "网络与服务器", status: "busy" },
  { id: "expert-chen", name: "陈工", specialty: "机械维护", status: "offline" },
] as const;

export function ContactRail() {
  return (
    <aside className="contact-rail" aria-label="专家与现场联系人">
      <div className="rail-heading">专家与现场</div>
      <label className="search-field">
        <Search aria-hidden="true" size={16} />
        <input aria-label="搜索专家或眼镜设备" placeholder="搜索专家或眼镜设备" />
      </label>

      <button className="contact-row contact-row--active" type="button">
        <span className="avatar avatar--device">镜</span>
        <span className="contact-copy">
          <strong>Air3-现场01</strong>
          <small>配电柜巡检</small>
        </span>
        <span className="presence presence--online" title="在线" />
      </button>

      <div className="rail-heading rail-heading--section">在线专家</div>
      <div className="contact-list">
        {experts.map((expert) => (
          <button className="contact-row" type="button" key={expert.id} disabled={expert.status === "offline"}>
            <span className="avatar">{expert.name.slice(0, 1)}</span>
            <span className="contact-copy">
              <strong>{expert.name}</strong>
              <small>{expert.specialty}</small>
            </span>
            <span className={`presence presence--${expert.status}`} title={expert.status} />
          </button>
        ))}
      </div>

      <div className="call-rule">
        <strong>广播呼叫</strong>
        <p>第一位接听者成为主专家，其他专家可被邀请旁听。</p>
      </div>
    </aside>
  );
}
