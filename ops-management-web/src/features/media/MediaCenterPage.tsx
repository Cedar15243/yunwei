import { useEffect, useState } from "react";
import { FileText, Image, RefreshCw, RotateCcw, Video, Volume2 } from "lucide-react";
import type {
  ManagementApi,
  MediaAsset,
  MediaListFilters,
  MediaRecord,
  MediaUploadStatus,
} from "../../api/management-api";
import { formatTime } from "../dashboard/DashboardPage";

const PAGE_SIZE = 24;

export function MediaCenterPage({ api }: { api: ManagementApi }) {
  const [items, setItems] = useState<MediaRecord[]>([]);
  const [kind, setKind] = useState<MediaAsset["kind"] | "">("");
  const [uploadStatus, setUploadStatus] = useState<MediaUploadStatus | "">("");
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingNext, setLoadingNext] = useState(false);
  const [retryingId, setRetryingId] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [errorMode, setErrorMode] = useState<"initial" | "next" | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError("");
    setErrorMode(null);
    api.getMedia(mediaFilters(kind, uploadStatus))
      .then((result) => {
        if (!alive) return;
        setItems(uniqueMedia(result.items));
        setNextCursor(result.nextCursor);
      })
      .catch((cause: unknown) => {
        if (!alive) return;
        setError(errorMessage(cause, "无法读取媒体记录"));
        setErrorMode("initial");
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [api, kind, uploadStatus, reloadKey]);

  async function loadNextPage() {
    if (!nextCursor || loadingNext) return;
    setLoadingNext(true);
    setError("");
    setErrorMode(null);
    try {
      const result = await api.getMedia(mediaFilters(kind, uploadStatus, nextCursor));
      setItems((current) => uniqueMedia([...current, ...result.items]));
      setNextCursor(result.nextCursor);
    } catch (cause) {
      setError(errorMessage(cause, "无法加载下一页媒体"));
      setErrorMode("next");
    } finally {
      setLoadingNext(false);
    }
  }

  async function retryAsset(media: MediaRecord) {
    if (retryingId) return;
    setRetryingId(media.id);
    setError("");
    setErrorMode(null);
    try {
      await api.retryMedia(media.task_id, media.id);
      setItems((current) => current.map((item) => item.id === media.id ? {
        ...item,
        upload_status: "queued",
        failure_reason: "",
        canRetryMedia: false,
      } : item));
    } catch (cause) {
      setError(errorMessage(cause, "无法重试该媒体"));
    } finally {
      setRetryingId(null);
    }
  }

  function retryFailedRequest() {
    if (errorMode === "next") {
      void loadNextPage();
      return;
    }
    setReloadKey((value) => value + 1);
  }

  function resetFilters() {
    setKind("");
    setUploadStatus("");
    setReloadKey((value) => value + 1);
  }

  return (
    <section className="page-stack media-center-workbench">
      <div className="filter-row media-filter-row">
        <label>
          证据类型
          <select value={kind} onChange={(event) => setKind(event.target.value as MediaAsset["kind"] | "")}>
            <option value="">全部类型</option>
            <option value="photo">照片</option>
            <option value="video">视频</option>
            <option value="audio">音频</option>
            <option value="annotation">标注</option>
          </select>
        </label>
        <label>
          同步状态
          <select value={uploadStatus} onChange={(event) => setUploadStatus(event.target.value as MediaUploadStatus | "")}>
            <option value="">全部状态</option>
            <option value="failed">同步失败</option>
            <option value="queued">等待同步</option>
            <option value="uploading">同步中</option>
            <option value="synced">已同步</option>
            <option value="local_saved">本机已保存</option>
            <option value="cancelled">已取消</option>
          </select>
        </label>
        <button className="compact-command" type="button" onClick={resetFilters} aria-label="重置媒体筛选">
          <RotateCcw size={16} aria-hidden="true" />
          <span>重置</span>
        </button>
      </div>

      {error ? (
        <section className="notice error recoverable-notice" role="alert">
          <span>{error}</span>
          {errorMode ? (
            <button className="compact-command" type="button" onClick={retryFailedRequest}>重试</button>
          ) : null}
        </section>
      ) : null}

      {loading && items.length === 0 ? (
        <section className="notice">正在加载媒体记录...</section>
      ) : items.length ? (
        <>
          <section className="media-grid">
            {items.map((media) => (
              <article className="media-tile" key={media.id}>
                <MediaPreview media={media} />
                <div className="media-tile-body">
                  <div className="media-tile-heading">
                    <strong>{media.task_title || "未命名任务"}</strong>
                    <span className={`status ${media.upload_status}`}>{mediaStatusLabel(media.upload_status)}</span>
                  </div>
                  <span>{mediaKindLabel(media.kind)} · {formatTime(media.captured_at ?? media.created_at)}</span>
                  {media.upload_status === "failed" ? (
                    <span className="media-failure">{media.failure_reason || "同步失败，未返回原因"}</span>
                  ) : null}
                  {media.canRetryMedia && media.upload_status === "failed" ? (
                    <button
                      className="inline-action media-retry-command"
                      type="button"
                      onClick={() => void retryAsset(media)}
                      disabled={retryingId === media.id}
                      aria-label={`重试 ${media.task_title || "未命名任务"} 的${mediaKindLabel(media.kind)}`}
                    >
                      <RefreshCw className={retryingId === media.id ? "spin" : undefined} size={15} aria-hidden="true" />
                      <span>{retryingId === media.id ? "重试中" : "重试此证据"}</span>
                    </button>
                  ) : null}
                </div>
              </article>
            ))}
          </section>
          {nextCursor ? (
            <div className="pagination-command-row">
              <button
                className="compact-command"
                type="button"
                onClick={() => void loadNextPage()}
                disabled={loadingNext}
                aria-label="加载下一页媒体"
              >
                {loadingNext ? <RefreshCw className="spin" size={16} aria-hidden="true" /> : null}
                <span>{loadingNext ? "加载中" : "加载下一页"}</span>
              </button>
            </div>
          ) : null}
        </>
      ) : <section className="notice">暂无已授权的现场媒体。</section>}
    </section>
  );
}

function MediaPreview({ media }: { media: MediaRecord }) {
  if (media.url && media.kind === "video") return <video controls src={media.url} />;
  if (media.url && media.kind === "audio") return <audio controls src={media.url} />;
  if (media.url && media.kind === "photo") {
    return <img src={media.url} alt={`${media.task_title || "未命名任务"}的现场证据`} />;
  }
  const Icon = media.kind === "video" ? Video : media.kind === "audio" ? Volume2 : media.kind === "annotation" ? FileText : Image;
  return (
    <div className="media-pending">
      <Icon aria-hidden="true" />
      <span>{mediaStatusLabel(media.upload_status)}</span>
    </div>
  );
}

function mediaFilters(
  kind: MediaAsset["kind"] | "",
  uploadStatus: MediaUploadStatus | "",
  before?: string,
): MediaListFilters {
  return {
    ...(kind ? { kind } : {}),
    ...(uploadStatus ? { uploadStatus } : {}),
    ...(before ? { before } : {}),
    limit: PAGE_SIZE,
  };
}

function uniqueMedia(items: MediaRecord[]): MediaRecord[] {
  return Array.from(new Map(items.map((media) => [media.id, media])).values());
}

function mediaKindLabel(kind: MediaAsset["kind"]): string {
  return { photo: "照片", video: "视频", audio: "音频", annotation: "标注" }[kind];
}

function mediaStatusLabel(status: string): string {
  return {
    local_saved: "本机已保存",
    queued: "等待同步",
    uploading: "同步中",
    synced: "已同步",
    failed: "同步失败",
    cancelled: "已取消",
    deleted: "已删除",
  }[status] ?? status;
}

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error && cause.message ? cause.message : fallback;
}
