import { useEffect, useRef, useState } from "react";
import { ShieldCheck } from "lucide-react";
import { ReconnectingCollabSocket } from "./api/collab-socket";
import { TrtcClient } from "./api/trtc-client";
import type { AnnotationTransport } from "./canvas/AnnotationCanvas";
import { captureFreezeFrame, uploadFreezeFrame } from "./freeze-frame";
import {
  CollaborationController,
  type CollaborationSnapshot,
} from "./collaboration-controller";
import { ContactRail } from "./components/ContactRail";
import { ExpertStage, type ExpertRole } from "./components/ExpertStage";
import { SessionPanel } from "./components/SessionPanel";
import "./styles.css";

interface AppProps {
  initialRole?: ExpertRole;
  live?: boolean;
}

const waitingSnapshot: CollaborationSnapshot = {
  status: "available",
  sessionId: null,
  glassesId: null,
  glassesName: null,
  primaryExpertId: null,
  error: null,
  role: null,
};

const EXPERT_ID_SESSION_KEY = "dingdang.expert.id";

function resolveWebsocketUrl(): string {
  if (import.meta.env.VITE_COLLAB_WS_URL) {
    return import.meta.env.VITE_COLLAB_WS_URL;
  }
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${window.location.host}/collab`;
}

function resolveExpertIdentity(): { id: string; name: string } {
  const params = new URLSearchParams(window.location.search);
  const explicitId = params.get("expertId");
  let id = explicitId ?? window.sessionStorage.getItem(EXPERT_ID_SESSION_KEY);
  if (!id) {
    id = `expert-${crypto.randomUUID().slice(0, 8)}`;
    window.sessionStorage.setItem(EXPERT_ID_SESSION_KEY, id);
  }
  return {
    id,
    name: params.get("name") ?? "演示专家",
  };
}

export function App({ initialRole, live }: AppProps) {
  const liveEnabled = live ?? initialRole === undefined;
  const videoViewRef = useRef<HTMLDivElement>(null);
  const localVideoViewRef = useRef<HTMLDivElement>(null);
  const controllerRef = useRef<CollaborationController | null>(null);
  const signalingRef = useRef<ReconnectingCollabSocket | null>(null);
  const [call, setCall] = useState<CollaborationSnapshot>(waitingSnapshot);
  const role = initialRole ?? (call.role === "observer" ? "observer" : "primary");
  const [annotationAuthorId, setAnnotationAuthorId] = useState("expert-preview");
  const [annotationTransport, setAnnotationTransport] = useState<AnnotationTransport | null>(null);
  const [freezeUrl, setFreezeUrl] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [snapshots, setSnapshots] = useState<Array<{ id: string; label: string; time: string; url: string }>>([]);
  const [serviceStatus, setServiceStatus] = useState<"connecting" | "connected" | "disconnected">("connecting");

  useEffect(() => {
    if (!liveEnabled || !videoViewRef.current || !localVideoViewRef.current) {
      return;
    }

    const identity = resolveExpertIdentity();
    const signaling = new ReconnectingCollabSocket(resolveWebsocketUrl(), {
      senderId: identity.id,
      onStatusChange: setServiceStatus,
    });
    const controller = new CollaborationController({
      signaling,
      trtc: new TrtcClient({
        onLocalVideoError: () => setActionError("专家摄像头未授权，语音协同继续"),
      }),
      expertId: identity.id,
      expertName: identity.name,
      videoView: videoViewRef.current,
      localVideoView: localVideoViewRef.current,
    });
    const canvasTransport: AnnotationTransport = {
      send(type, payload) {
        const sessionId = controller.getSnapshot().sessionId;
        if (sessionId) {
          signaling.sendSessionEvent(type, sessionId, payload);
        }
      },
      subscribe(listener) {
        return signaling.subscribe(listener);
      },
    };
    controllerRef.current = controller;
    signalingRef.current = signaling;
    setAnnotationAuthorId(identity.id);
    setAnnotationTransport(canvasTransport);
    const unsubscribe = controller.subscribe(setCall);
    const unsubscribeFreeze = signaling.subscribe((message) => {
      if (message.type === "freeze.created" && typeof message.payload.url === "string") {
        setFreezeUrl(message.payload.url);
      } else if (message.type === "freeze.cleared") {
        setFreezeUrl(null);
      }
    });
    controller.start();
    signaling.connect();

    return () => {
      unsubscribe();
      unsubscribeFreeze();
      void controller.stop();
      signaling.close();
      controllerRef.current = null;
      signalingRef.current = null;
      setAnnotationTransport(null);
    };
  }, [liveEnabled]);

  const captureAndUpload = async (): Promise<string> => {
    const sessionId = controllerRef.current?.getSnapshot().sessionId;
    if (!sessionId || !videoViewRef.current) {
      throw new Error("当前没有可截图的协同会话");
    }
    return uploadFreezeFrame({
      dataUrl: captureFreezeFrame(videoViewRef.current),
      sessionId,
    });
  };

  const serviceStatusLabel = serviceStatus === "connected"
    ? "协同服务已连接"
    : serviceStatus === "connecting"
      ? "正在连接协同服务"
      : "协同服务未连接";

  const toggleFreeze = async (): Promise<void> => {
    const sessionId = controllerRef.current?.getSnapshot().sessionId;
    if (!sessionId || !signalingRef.current) {
      return;
    }
    setActionError(null);
    if (freezeUrl) {
      signalingRef.current.sendSessionEvent("freeze.cleared", sessionId, {});
      setFreezeUrl(null);
      return;
    }
    try {
      const url = await captureAndUpload();
      signalingRef.current.sendSessionEvent("freeze.created", sessionId, { url });
      setFreezeUrl(url);
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "冻结画面失败");
    }
  };

  const createSnapshot = async (): Promise<void> => {
    setActionError(null);
    try {
      const url = await captureAndUpload();
      const now = new Date();
      setSnapshots((current) => [{
        id: crypto.randomUUID(),
        label: `专家截图 ${current.length + 1}`,
        time: now.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" }),
        url,
      }, ...current]);
    } catch (error) {
      setActionError(error instanceof Error ? error.message : "截图失败");
    }
  };

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="brand-lockup"><strong>叮当云 AI 专家协同</strong><span>设备远程运维工作台</span></div>
        <div className={`service-status service-status--${serviceStatus}`}><ShieldCheck aria-hidden="true" size={16} /><span>{serviceStatusLabel}</span></div>
      </header>
      <div className="workspace-grid">
        <ContactRail call={liveEnabled ? call : null} />
        <ExpertStage
          call={liveEnabled ? call : null}
          annotationAuthorId={annotationAuthorId}
          annotationTransport={annotationTransport}
          actionError={actionError}
          freezeUrl={freezeUrl}
          onAccept={() => controllerRef.current?.accept()}
          onEnd={() => void controllerRef.current?.end()}
          onInvite={() => controllerRef.current?.inviteObserver("expert-liu")}
          onRetry={() => controllerRef.current?.retry()}
          onScreenshot={() => void createSnapshot()}
          onToggleFreeze={() => void toggleFreeze()}
          role={role}
          localVideoViewRef={localVideoViewRef}
          videoViewRef={videoViewRef}
        />
        <SessionPanel call={liveEnabled ? call : null} role={role} snapshots={liveEnabled ? snapshots : undefined} />
      </div>
    </div>
  );
}
