import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  type PointerEvent as ReactPointerEvent,
} from "react";
import type { CollabEnvelope } from "../api/collab-socket";
import { AnnotationModel, type AnnotationTool } from "./annotation-model";
import { denormalizePoint, normalizePoint, type Point } from "./video-coordinates";

export interface AnnotationTransport {
  send(type: string, payload: Record<string, unknown>): void;
  subscribe(listener: (message: CollabEnvelope) => void): () => void;
}

export interface AnnotationCanvasHandle {
  clear(): void;
  undo(): void;
}

interface AnnotationCanvasProps {
  authorId: string;
  editable: boolean;
  sessionId: string;
  tool: AnnotationTool;
  transport: AnnotationTransport;
}

function isTool(value: unknown): value is AnnotationTool {
  return value === "pen" || value === "arrow" || value === "circle";
}

function readPoints(value: unknown): Point[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.filter((point): point is Point => (
    typeof point === "object" && point !== null
    && typeof (point as Point).x === "number"
    && typeof (point as Point).y === "number"
  ));
}

export const AnnotationCanvas = forwardRef<AnnotationCanvasHandle, AnnotationCanvasProps>(
  function AnnotationCanvas({ authorId, editable, sessionId, tool, transport }, ref) {
    const canvasRef = useRef<HTMLCanvasElement>(null);
    const modelRef = useRef(new AnnotationModel());
    const activeObjectIdRef = useRef<string | null>(null);
    const pendingPointsRef = useRef<Point[]>([]);
    const lastSentAtRef = useRef(0);

    const draw = (): void => {
      const canvas = canvasRef.current;
      if (!canvas || typeof CanvasRenderingContext2D === "undefined") {
        return;
      }
      const context = canvas.getContext("2d");
      if (!context) {
        return;
      }
      const rectangle = { x: 0, y: 0, width: canvas.clientWidth, height: canvas.clientHeight };
      context.clearRect(0, 0, canvas.width, canvas.height);
      context.strokeStyle = "#ff5f67";
      context.fillStyle = "#ff5f67";
      context.lineWidth = 4;
      context.lineCap = "round";
      context.lineJoin = "round";

      for (const annotation of modelRef.current.list()) {
        const points = annotation.points.map((point) => denormalizePoint(point, rectangle));
        if (points.length === 0) {
          continue;
        }
        const first = points[0];
        const last = points.at(-1) ?? first;
        context.beginPath();
        if (annotation.tool === "pen") {
          context.moveTo(first.x, first.y);
          for (const point of points.slice(1)) {
            context.lineTo(point.x, point.y);
          }
          context.stroke();
        } else if (annotation.tool === "circle") {
          context.ellipse(
            (first.x + last.x) / 2,
            (first.y + last.y) / 2,
            Math.abs(last.x - first.x) / 2,
            Math.abs(last.y - first.y) / 2,
            0,
            0,
            Math.PI * 2,
          );
          context.stroke();
        } else {
          context.moveTo(first.x, first.y);
          context.lineTo(last.x, last.y);
          context.stroke();
          const angle = Math.atan2(last.y - first.y, last.x - first.x);
          context.beginPath();
          context.moveTo(last.x, last.y);
          context.lineTo(last.x - 16 * Math.cos(angle - Math.PI / 6), last.y - 16 * Math.sin(angle - Math.PI / 6));
          context.lineTo(last.x - 16 * Math.cos(angle + Math.PI / 6), last.y - 16 * Math.sin(angle + Math.PI / 6));
          context.closePath();
          context.fill();
        }
      }
    };

    const flushPoints = (): void => {
      const objectId = activeObjectIdRef.current;
      if (!objectId || pendingPointsRef.current.length === 0) {
        return;
      }
      transport.send("annotation.append", {
        objectId,
        points: pendingPointsRef.current.splice(0),
      });
      lastSentAtRef.current = performance.now();
    };

    useImperativeHandle(ref, () => ({
      clear() {
        if (!editable) {
          return;
        }
        modelRef.current.clear();
        draw();
        transport.send("annotation.clear", { points: [] });
      },
      undo() {
        if (!editable) {
          return;
        }
        const objectId = modelRef.current.undo(authorId);
        if (objectId) {
          draw();
          transport.send("annotation.undo", { objectId, points: [] });
        }
      },
    }));

    useEffect(() => transport.subscribe((message) => {
      if (message.sessionId !== sessionId || !message.type.startsWith("annotation.")) {
        return;
      }
      const objectId = typeof message.payload.objectId === "string" ? message.payload.objectId : "";
      if (message.type === "annotation.begin" && objectId && isTool(message.payload.tool)) {
        const [point] = readPoints(message.payload.points);
        if (point) {
          modelRef.current.begin({
            id: objectId,
            authorId: typeof message.payload.authorId === "string" ? message.payload.authorId : "remote",
            tool: message.payload.tool,
            point,
          });
        }
      } else if (message.type === "annotation.append" && objectId) {
        modelRef.current.append(objectId, readPoints(message.payload.points));
      } else if (message.type === "annotation.complete" && objectId) {
        modelRef.current.complete(objectId);
      } else if (message.type === "annotation.undo" && objectId) {
        modelRef.current.remove(objectId);
      } else if (message.type === "annotation.clear") {
        modelRef.current.clear();
      }
      draw();
    }), [sessionId, transport]);

    useEffect(() => {
      const canvas = canvasRef.current;
      if (!canvas) {
        return;
      }
      const resize = () => {
        const ratio = window.devicePixelRatio || 1;
        canvas.width = Math.max(1, Math.round(canvas.clientWidth * ratio));
        canvas.height = Math.max(1, Math.round(canvas.clientHeight * ratio));
        const context = typeof CanvasRenderingContext2D === "undefined" ? null : canvas.getContext("2d");
        context?.setTransform(ratio, 0, 0, ratio, 0, 0);
        draw();
      };
      resize();
      const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(resize);
      observer?.observe(canvas);
      return () => observer?.disconnect();
    }, []);

    const pointFromEvent = (event: ReactPointerEvent<HTMLCanvasElement>): Point | null => {
      const rectangle = event.currentTarget.getBoundingClientRect();
      return normalizePoint(
        { x: event.clientX, y: event.clientY },
        { x: rectangle.left, y: rectangle.top, width: rectangle.width, height: rectangle.height },
      );
    };

    const handlePointerDown = (event: ReactPointerEvent<HTMLCanvasElement>): void => {
      if (!editable) {
        return;
      }
      const point = pointFromEvent(event);
      if (!point) {
        return;
      }
      const objectId = crypto.randomUUID();
      activeObjectIdRef.current = objectId;
      pendingPointsRef.current = [];
      modelRef.current.begin({ id: objectId, authorId, tool, point });
      event.currentTarget.setPointerCapture?.(event.pointerId);
      transport.send("annotation.begin", { objectId, authorId, tool, points: [point] });
      draw();
    };

    const handlePointerMove = (event: ReactPointerEvent<HTMLCanvasElement>): void => {
      const objectId = activeObjectIdRef.current;
      if (!editable || !objectId) {
        return;
      }
      const point = pointFromEvent(event);
      if (!point) {
        return;
      }
      modelRef.current.append(objectId, [point]);
      pendingPointsRef.current.push(point);
      if (performance.now() - lastSentAtRef.current >= 50) {
        flushPoints();
      }
      draw();
    };

    const handlePointerUp = (event: ReactPointerEvent<HTMLCanvasElement>): void => {
      const objectId = activeObjectIdRef.current;
      if (!editable || !objectId) {
        return;
      }
      const point = pointFromEvent(event);
      if (point) {
        modelRef.current.append(objectId, [point]);
        pendingPointsRef.current.push(point);
      }
      flushPoints();
      modelRef.current.complete(objectId);
      transport.send("annotation.complete", { objectId, points: [] });
      activeObjectIdRef.current = null;
      event.currentTarget.releasePointerCapture?.(event.pointerId);
      draw();
    };

    return (
      <canvas
        aria-label="实时标注画布"
        className="annotation-canvas"
        onPointerCancel={handlePointerUp}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        ref={canvasRef}
      />
    );
  },
);
