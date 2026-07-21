import { useRef, useState, type RefObject } from "react";
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
import type { CollaborationSnapshot } from "../collaboration-controller";
import {
  AnnotationCanvas,
  type AnnotationCanvasHandle,
  type AnnotationTransport,
} from "../canvas/AnnotationCanvas";
import type { AnnotationTool } from "../canvas/annotation-model";

export type ExpertRole = "primary" | "observer";

interface ExpertStageProps {
  actionError?: string | null;
  annotationAuthorId?: string;
  annotationTransport?: AnnotationTransport | null;
  call?: CollaborationSnapshot | null;
  freezeUrl?: string | null;
  onAccept?: () => void;
  onEnd?: () => void;
  onInvite?: () => void;
  onRetry?: () => void;
  onScreenshot?: () => void;
  onToggleFreeze?: () => void;
  role: ExpertRole;
  localVideoViewRef?: RefObject<HTMLDivElement | null>;
  videoViewRef?: RefObject<HTMLDivElement | null>;
}

const toolButtons = [
  { label: "撤销", icon: Undo2, action: "undo" },
  { label: "重做", icon: Redo2, action: "unsupported" },
  { label: "箭头", icon: ArrowUpRight, action: "arrow" },
  { label: "画笔", icon: Pencil, action: "pen" },
  { label: "圆圈", icon: Circle, action: "circle" },
  { label: "橡皮", icon: Eraser, action: "unsupported" },
  { label: "清空", icon: Trash2, action: "clear" },
] as const;

export function ExpertStage({
  actionError,
  annotationAuthorId = "expert-preview",
  annotationTransport,
  call,
  freezeUrl,
  onAccept,
  onEnd,
  onInvite,
  onRetry,
  onScreenshot,
  onToggleFreeze,
  role,
  localVideoViewRef,
  videoViewRef,
}: ExpertStageProps) {
  const [muted, setMuted] = useState(false);
  const [speakerOff, setSpeakerOff] = useState(false);
  const [activeTool, setActiveTool] = useState<AnnotationTool>("arrow");
  const [localFrozen, setLocalFrozen] = useState(false);
  const annotationCanvasRef = useRef<AnnotationCanvasHandle>(null);
  const controlsDisabled = role !== "primary" || (call !== null && call !== undefined && call.status !== "in_call");
  const liveLabel = call === null || call === undefined
    ? "LIVE · Air3 第一视角 · 00:04:18"
    : call.status === "in_call"
      ? "LIVE · Air3 第一视角"
      : call.status === "ringing" || call.status === "invited"
        ? "现场设备正在呼叫"
        : call.status === "connecting"
          ? "正在建立安全音视频连接"
          : call.status === "taken"
            ? "已由其他专家接听"
            : call.status === "failed"
              ? "协同连接失败"
              : "专家在线 · 等待现场呼叫";
  const roleLabel = call === null || call === undefined
    ? role === "primary" ? "主专家：王工 · 标注权" : "旁听语音"
    : call.status === "in_call" ? role === "primary" ? "主专家 · 标注权" : "旁听语音"
      : call.status === "ringing" ? "请接听现场呼叫"
        : call.status === "connecting" ? "会话建立中"
          : call.status === "taken" ? "本次会话已被接听"
            : call.status === "failed" ? "请重新连接"
              : "未进入会话";

  return (
    <main className="expert-stage" aria-label="专家协同视频工作区">
      <section className="video-stage" aria-label="Air3第一视角">
        <div id="glasses-video" ref={videoViewRef} aria-label="眼镜实时视频" />
        {freezeUrl ? <img className="freeze-frame" src={freezeUrl} alt="当前冻结画面" /> : null}
        {call?.status !== "in_call" && !freezeUrl ? (
          <div className="video-fallback" aria-hidden="true">
            <div className="equipment-line equipment-line--left" />
            <div className="equipment-line equipment-line--right" />
            <div className="video-fallback__copy">
              <span>现场视频待接入</span>
              <small>专家工作台已就绪</small>
            </div>
          </div>
        ) : null}
        <div className="live-indicator"><span /> {liveLabel}</div>
        <div className="role-indicator">{roleLabel}</div>
        <div className={call?.status === "in_call" && role === "primary" ? "expert-self-preview" : "expert-self-preview expert-self-preview--hidden"}>
          <div ref={localVideoViewRef} aria-label="专家本地视频" />
          <span>我的画面</span>
        </div>
        {call === null || call === undefined ? (
          <>
            <div className="demo-annotation demo-annotation--arrow" aria-hidden="true" />
            <div className="demo-annotation demo-annotation--circle" aria-hidden="true" />
            <div className="annotation-note">检查右侧接线端子</div>
          </>
        ) : null}
        {call?.sessionId && annotationTransport ? (
          <AnnotationCanvas
            authorId={annotationAuthorId}
            editable={!controlsDisabled}
            ref={annotationCanvasRef}
            sessionId={call.sessionId}
            tool={activeTool}
            transport={annotationTransport}
          />
        ) : null}

        {call?.status === "ringing" || call?.status === "invited" ? (
          <div className="incoming-call" role="dialog" aria-label="眼镜来电">
            <span className="incoming-call__signal"><span /></span>
            <div>
              <strong>{call.status === "invited" ? "主专家邀请旁听语音" : `${call.glassesName} 正在呼叫`}</strong>
              <small>{call.status === "invited" ? "加入同一协同房间，仅开放语音" : "现场请求远程专家协助"}</small>
            </div>
            <button className="command-button command-button--primary" onClick={onAccept} type="button">{call.status === "invited" ? "加入" : "接听"}</button>
          </div>
        ) : null}
        {call?.status === "taken" ? <div className="call-toast">已由其他专家接听</div> : null}
        {call?.status === "connecting" ? <div className="call-toast">正在建立安全音视频连接</div> : null}
        {call?.status === "failed" ? (
          <div className="call-toast call-toast--error">
            <span>连接失败：{call.error}</span>
            <button className="command-button" onClick={onRetry} type="button">重新连接</button>
          </div>
        ) : null}
        {actionError ? <div className="call-toast call-toast--error">{actionError}</div> : null}

        <div className="annotation-toolbar" aria-label="标注工具栏">
          {toolButtons.map(({ label, icon: Icon, action }) => (
            <button
              aria-label={label}
              className={activeTool === action ? "icon-button icon-button--selected" : "icon-button"}
              disabled={controlsDisabled || action === "unsupported"}
              key={label}
              onClick={() => {
                if (action === "undo") {
                  annotationCanvasRef.current?.undo();
                } else if (action === "clear") {
                  annotationCanvasRef.current?.clear();
                } else if (action !== "unsupported") {
                  setActiveTool(action);
                }
              }}
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
            className={(freezeUrl || localFrozen) ? "command-button command-button--active" : "command-button"}
            disabled={controlsDisabled}
            onClick={() => onToggleFreeze ? onToggleFreeze() : setLocalFrozen((value) => !value)}
            type="button"
          >
            <Snowflake aria-hidden="true" size={17} />
            {(freezeUrl || localFrozen) ? "恢复实时" : "冻结画面"}
          </button>
          <button className="command-button" disabled={controlsDisabled} onClick={onScreenshot} type="button">
            <Camera aria-hidden="true" size={17} />截图
          </button>
          <button className="command-button" type="button">
            <ImageDown aria-hidden="true" size={17} />文件记录
          </button>
        </div>
        <div className="control-group">
          <button className="command-button command-button--primary" disabled={controlsDisabled} onClick={onInvite} type="button">
            <UserPlus aria-hidden="true" size={17} />邀请专家
          </button>
          <button className="command-button command-button--danger" onClick={onEnd} type="button">
            <PhoneOff aria-hidden="true" size={17} />挂断
          </button>
        </div>
      </footer>
    </main>
  );
}
