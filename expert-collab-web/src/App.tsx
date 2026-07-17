import { useEffect, useRef, useState } from "react";
import { ShieldCheck } from "lucide-react";
import { CollabSocket } from "./api/collab-socket";
import { TrtcClient } from "./api/trtc-client";
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
  const role = initialRole ?? "primary";
  const videoViewRef = useRef<HTMLDivElement>(null);
  const controllerRef = useRef<CollaborationController | null>(null);
  const [call, setCall] = useState<CollaborationSnapshot>(waitingSnapshot);

  useEffect(() => {
    if (!liveEnabled || !videoViewRef.current) {
      return;
    }

    const identity = resolveExpertIdentity();
    const socket = new WebSocket(resolveWebsocketUrl());
    const signaling = new CollabSocket(socket, { senderId: identity.id });
    const controller = new CollaborationController({
      signaling,
      trtc: new TrtcClient(),
      expertId: identity.id,
      expertName: identity.name,
      videoView: videoViewRef.current,
    });
    controllerRef.current = controller;
    const unsubscribe = controller.subscribe(setCall);
    const start = () => controller.start();
    socket.addEventListener("open", start);

    return () => {
      socket.removeEventListener("open", start);
      unsubscribe();
      signaling.close();
      socket.close();
      controllerRef.current = null;
      void controller.stop();
    };
  }, [liveEnabled]);

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
          onAccept={() => controllerRef.current?.accept()}
          onEnd={() => void controllerRef.current?.end()}
          role={role}
          videoViewRef={videoViewRef}
        />
        <SessionPanel role={role} />
      </div>
    </div>
  );
}
