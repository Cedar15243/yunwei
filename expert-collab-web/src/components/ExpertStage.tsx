import { useState } from "react";
import {
  ArrowUpRight,
  Camera,
  Circle,
  Eraser,
  ImageDown,
  Mic,
  MicOff,
  Pencil,
  PhoneOff,
  Redo2,
  Snowflake,
  Trash2,
  Undo2,
  UserPlus,
  Volume2,
  VolumeX,
} from "lucide-react";

export type ExpertRole = "primary" | "observer";

interface ExpertStageProps {
  role: ExpertRole;
}

const toolButtons = [
  { label: "撤销", icon: Undo2 },
  { label: "重做", icon: Redo2 },
  { label: "箭头", icon: ArrowUpRight },
  { label: "画笔", icon: Pencil },
  { label: "圆圈", icon: Circle },
  { label: "橡皮", icon: Eraser },
  { label: "清空", icon: Trash2 },
] as const;

export function ExpertStage({ role }: ExpertStageProps) {
  const [muted, setMuted] = useState(false);
  const [speakerOff, setSpeakerOff] = useState(false);
  const [activeTool, setActiveTool] = useState("箭头");
  const [frozen, setFrozen] = useState(false);
  const controlsDisabled = role !== "primary";

  return (
    <main className="expert-stage" aria-label="专家协同视频工作区">
      <section className="video-stage" aria-label="Air3第一视角">
        <video id="glasses-video" autoPlay playsInline aria-label="眼镜实时视频" />
        <div className="video-fallback" aria-hidden="true">
          <div className="equipment-line equipment-line--left" />
          <div className="equipment-line equipment-line--right" />
        </div>
        <div className="live-indicator"><span /> LIVE · Air3 第一视角 · 00:04:18</div>
        <div className="role-indicator">{role === "primary" ? "主专家：王工 · 标注权" : "旁听语音"}</div>
        <div className="demo-annotation demo-annotation--arrow" aria-hidden="true" />
        <div className="demo-annotation demo-annotation--circle" aria-hidden="true" />
        <div className="annotation-note">检查右侧接线端子</div>

        <div className="annotation-toolbar" aria-label="标注工具栏">
          {toolButtons.map(({ label, icon: Icon }) => (
            <button
              aria-label={label}
              className={activeTool === label ? "icon-button icon-button--selected" : "icon-button"}
              disabled={controlsDisabled}
              key={label}
              onClick={() => setActiveTool(label)}
              title={label}
              type="button"
            >
              <Icon aria-hidden="true" size={19} />
            </button>
          ))}
        </div>
      </section>

      <footer className="meeting-controls">
        <div className="control-group">
          <button className="command-button" onClick={() => setMuted((value) => !value)} type="button">
            {muted ? <MicOff aria-hidden="true" size={17} /> : <Mic aria-hidden="true" size={17} />}
            {muted ? "取消静音" : "静音"}
          </button>
          <button className="command-button" onClick={() => setSpeakerOff((value) => !value)} type="button">
            {speakerOff ? <VolumeX aria-hidden="true" size={17} /> : <Volume2 aria-hidden="true" size={17} />}
            {speakerOff ? "开启声音" : "关闭声音"}
          </button>
          <button
            className={frozen ? "command-button command-button--active" : "command-button"}
            disabled={controlsDisabled}
            onClick={() => setFrozen((value) => !value)}
            type="button"
          >
            <Snowflake aria-hidden="true" size={17} />
            {frozen ? "恢复实时" : "冻结画面"}
          </button>
          <button className="command-button" disabled={controlsDisabled} type="button">
            <Camera aria-hidden="true" size={17} />截图
          </button>
          <button className="command-button" type="button">
            <ImageDown aria-hidden="true" size={17} />文件记录
          </button>
        </div>
        <div className="control-group">
          <button className="command-button command-button--primary" disabled={controlsDisabled} type="button">
            <UserPlus aria-hidden="true" size={17} />邀请专家
          </button>
          <button className="command-button command-button--danger" type="button">
            <PhoneOff aria-hidden="true" size={17} />挂断
          </button>
        </div>
      </footer>
    </main>
  );
}
