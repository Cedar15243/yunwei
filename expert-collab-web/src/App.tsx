import { useEffect, useRef, useState } from "react";
import { ShieldCheck } from "lucide-react";
import { CollabSocket } from "./api/collab-socket";
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

function resolveWebsocketUrl(): string {
  if (import.meta.env.VITE_COLLAB_WS_URL) {
    return import.meta.env.VITE_COLLAB_WS_URL;
  }
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${protocol}//${window.location.hostname}:8787/collab`;
}

function resolveExpertIdentity(): { id: string; name: string } {
  const params = new URLSearchParams(window.location.search);
  return {
    id: params.get("expertId") ?? `expert-${crypto.randomUUID().slice(0, 8)}`,
    name: params.get("name") ?? "演示专家",
  };
}

export function App({ initialRole, live }: AppProps) {
  const liveEnabled = live ?? initialRole === undefined;
  const videoViewRef = useRef<HTMLDivElement>(null);
  const localVideoViewRef = useRef<HTMLDivElement>(null);
  const controllerRef = useRef<CollaborationController | null>(null);
  const signalingRef = useRef<CollabSocket | null>(null);
  const [call, setCall] = useState<CollaborationSnapshot>(waitingSnapshot);
  const role = initialRole ?? (call.role === "observer" ? "observer" : "primary");
  const [annotationAuthorId, setAnnotationAuthorId] = useState("expert-preview");
  const [annotationTransport, setAnnotationTransport] = useState<AnnotationTransport | null>(null);
  const [freezeUrl, setFreezeUrl] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [snapshots, setSnapshots] = useState<Array<{ id: string; label: string; time: string; url: string }>>([]);

  useEffect(() => {
    if (!liveEnabled || !videoViewRef.current || !localVideoViewRef.current) {
      return;
    }

    const identity = resolveExpertIdentity();
    const socket = new WebSocket(resolveWebsocketUrl());
    const signaling = new CollabSocket(socket, { senderId: identity.id });
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
    const start = () => controller.start();
    socket.addEventListener("open", start);

    return () => {
      socket.removeEventListener("open", start);
      unsubscribe();
      unsubscribeFreeze();
      signaling.close();
      socket.close();
      controllerRef.current = null;
      signalingRef.current = null;
      setAnnotationTransport(null);
      void controller.stop();
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
        <div className="brand-lockup"><span className="brand-mark" /><strong>叮当专家协同</strong><span>演示工作台</span></div>
        <div className="service-status"><ShieldCheck aria-hidden="true" size={16} /><span>TRTC 免费试用 · 后付费关闭</span></div>
      </header>
      <div className="workspace-grid">
        <ContactRail />
        <ExpertStage
          call={liveEnabled ? call : null}
          annotationAuthorId={annotationAuthorId}
          annotationTransport={annotationTransport}
          actionError={actionError}
          freezeUrl={freezeUrl}
          onAccept={() => controllerRef.current?.accept()}
          onEnd={() => void controllerRef.current?.end()}
          onInvite={() => controllerRef.current?.inviteObserver("expert-liu")}
          onScreenshot={() => void createSnapshot()}
          onToggleFreeze={() => void toggleFreeze()}
          role={role}
          localVideoViewRef={localVideoViewRef}
          videoViewRef={videoViewRef}
        />
        <SessionPanel role={role} snapshots={liveEnabled ? snapshots : undefined} />
      </div>
    </div>
  );
}
