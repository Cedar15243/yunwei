import { useEffect, useState } from "react";
import { ArrowLeft, Bot, Image, Mic, RefreshCw, Video } from "lucide-react";
import type { ManagementApi, MediaAsset, TaskDetail, TaskEvent } from "../../api/management-api";
import { eventText } from "../../api/management-api";
import { formatTime, statusLabel } from "../dashboard/DashboardPage";

type TimelineItem = { id: string; createdAt: string; kind: "message" | "media"; value: TaskEvent | MediaAsset };

export function TaskTimelinePage({ taskId, api, onBack }: { taskId: string; api: ManagementApi; onBack?: () => void }) {
  const [detail, setDetail] = useState<TaskDetail | null>(null); const [error, setError] = useState(""); const [retrying, setRetrying] = useState("");
  useEffect(() => { let alive = true; api.getTask(taskId).then((value) => alive && setDetail(value)).catch((cause: unknown) => alive && setError(cause instanceof Error ? cause.message : "无法读取任务详情")); return () => { alive = false; }; }, [api, taskId]);
  async function retry(mediaId: string) { setRetrying(mediaId); try { await api.retryMedia(taskId, mediaId); setDetail(await api.getTask(taskId)); } catch (cause) { setError(cause instanceof Error ? cause.message : "重传请求失败"); } finally { setRetrying(""); } }
  if (error) return <section className="notice error">{error}</section>; if (!detail) return <section className="notice">正在加载任务时间线…</section>;
  const items: TimelineItem[] = [...detail.messages.map((value) => ({ id: value.id, createdAt: value.created_at, kind: "message" as const, value })), ...detail.media.map((value) => ({ id: value.id, createdAt: value.captured_at ?? value.created_at ?? "", kind: "media" as const, value }))].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  return <section className="page-stack"><div className="task-title"><button className="icon-button" aria-label="返回任务列表" onClick={onBack} title="返回任务列表"><ArrowLeft size={19} /></button><div><h2>{detail.task.title || "未命名任务"}</h2><p>{detail.task.current_step || "现场任务进行中"}</p></div><span className={`status ${detail.task.status}`}>{statusLabel(detail.task.status)}</span></div><section className="surface timeline">{items.length ? items.map((item) => item.kind === "message" ? <MessageItem event={item.value as TaskEvent} key={item.id} /> : <MediaItem media={item.value as MediaAsset} retrying={retrying === item.id} onRetry={() => retry(item.id)} key={item.id} />) : <div className="empty">该任务尚未同步对话或现场证据。</div>}</section></section>;
}

function MessageItem({ event }: { event: TaskEvent }) { const isAi = event.event_type === "ai_response"; const Icon = isAi ? Bot : event.event_type === "voice_transcript" ? Mic : Image; return <article className={`timeline-item message ${isAi ? "ai" : "user"}`}><div className="timeline-icon"><Icon size={16} /></div><div><span>{isAi ? "AI 运维回复" : event.event_type === "voice_transcript" ? "现场语音" : "现场输入"}</span><p>{eventText(event)}</p><time>{formatTime(event.created_at)}</time></div></article>; }
function MediaItem({ media, retrying, onRetry }: { media: MediaAsset; retrying: boolean; onRetry: () => void }) { const isVideo = media.kind === "video"; return <article className="timeline-item media"><div className="timeline-icon">{isVideo ? <Video size={16} /> : <Image size={16} />}</div><div><span>{isVideo ? "现场视频" : "现场照片"}</span>{media.url ? isVideo ? <video controls aria-label="现场视频证据" src={media.url} /> : <img src={media.url} alt="现场采集证据" /> : <p className="media-state">{media.upload_status === "failed" ? `同步失败：${media.failure_reason || "等待重传"}` : "媒体正在同步"}</p>}<time>{formatTime(media.captured_at ?? media.created_at)}</time>{media.canRetryMedia ? <button className="inline-action" onClick={onRetry} disabled={retrying}>{retrying ? "正在请求重传" : <><RefreshCw size={14} />重传媒体</>}</button> : null}</div></article>; }
