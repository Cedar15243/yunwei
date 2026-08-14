from __future__ import annotations

import base64
import hashlib
import hmac
import http.client
import json
import os
import re
import secrets
import signal
import socket
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable, Iterable, Iterator, Mapping, Optional

from asr_proxy import (
    PREVIOUS_STABLE_ASR_MODEL,
    WebSocketProtocolError,
    connect_dashscope_asr,
    relay_asr_websockets,
    websocket_accept_value,
)
from content_manifest_sync import (
    ContentManifestSyncClient,
    ContentManifestSyncConfig,
    ContentManifestSyncWorker,
)
from control_plane_sync import (
    ControlPlaneSyncClient,
    ControlPlaneSyncConfig,
    ControlPlaneSyncWorker,
)
from execution_context import ExecutionContext, ExecutionContextRepository
from knowledge_document_parser import (
    KnowledgeDocumentParserError,
    parse_knowledge_document as parse_binary_knowledge_document,
)
from mvs_work_order import (
    MvsConnectorConfig,
    MvsProviderUnavailable,
    MvsWorkOrderConnector,
)
from voiceprint_lifecycle import (
    VOICEPRINT_CONSENT_VERSION,
    VoiceprintLifecycleConfig,
    VoiceprintLifecycleService,
    VoiceprintResponse,
    VoiceprintStore,
)
from voiceprint_proxy import (
    IFLYTEK_VOICEPRINT_URL,
    IflytekVoiceprintConfig,
    IflytekVoiceprintProvider,
)


SESSION_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
IMAGE_KINDS = {"field_photo", "device_photo", "configuration_photo", "receipt_photo"}
PREVIOUS_STABLE_AI_MODEL = "qwen3-vl-plus"
MAX_WORKFLOW_PHOTO_BYTES = 5 * 1024 * 1024
MAX_WORKFLOW_VIDEO_BYTES = 8 * 1024 * 1024
MAX_WORKFLOW_EVIDENCE_CHUNK_BYTES = 1024 * 1024
MAX_WORKFLOW_EVIDENCE_CHUNKS = 10_000
DEVICE_EVENT_TYPES = {
    "task_started",
    "user_message",
    "voice_transcript",
    "photo_captured",
    "video_recorded",
    "ai_response",
    "step_changed",
    "task_completed",
    "task_closed",
    "media_upload_failed",
}
WORKFLOW_ASSIGNMENT_MODES = {"required", "optional", "none"}
WORKFLOW_ASSIGNMENT_STATUSES = {
    "queued",
    "notified",
    "delivered",
    "verified",
    "ready",
    "active",
    "completed",
    "failed",
    "revoked",
}
WORKFLOW_REPORTABLE_STATUSES = {
    "delivered",
    "verified",
    "ready",
    "active",
    "completed",
    "failed",
}
WORKFLOW_STEP_STATUSES = {
    "pending",
    "active",
    "draft_saved",
    "waiting_upload",
    "waiting_server",
    "completed",
    "skipped",
    "failed",
}


class ProviderUnavailable(RuntimeError):
    pass


def _create_ipv4_connection(
    address: tuple[str, int],
    timeout: object = socket._GLOBAL_DEFAULT_TIMEOUT,
    source_address: Optional[tuple[str, int]] = None,
) -> socket.socket:
    host, port = address
    last_error: Optional[OSError] = None
    for family, socktype, proto, _canonical_name, socket_address in socket.getaddrinfo(
        host, port, socket.AF_INET, socket.SOCK_STREAM
    ):
        connection: Optional[socket.socket] = None
        try:
            connection = socket.socket(family, socktype, proto)
            if timeout is not socket._GLOBAL_DEFAULT_TIMEOUT:
                connection.settimeout(timeout)
            if source_address:
                connection.bind(source_address)
            connection.connect(socket_address)
            return connection
        except OSError as error:
            last_error = error
            if connection is not None:
                connection.close()
    if last_error is not None:
        raise last_error
    raise OSError("provider_ipv4_address_unavailable")


class _IPv4HttpsConnection(http.client.HTTPSConnection):
    def __init__(self, *args: object, **kwargs: object):
        super().__init__(*args, **kwargs)
        self._create_connection = _create_ipv4_connection


class _IPv4HttpsHandler(urllib.request.HTTPSHandler):
    def https_open(self, request: urllib.request.Request):
        return self.do_open(
            _IPv4HttpsConnection,
            request,
            context=self._context,
            check_hostname=self._check_hostname,
        )


def _open_provider_request(request: urllib.request.Request, timeout_seconds: int):
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        _IPv4HttpsHandler(),
    )
    return opener.open(request, timeout=timeout_seconds)


@dataclass(frozen=True)
class GatewayConfig:
    bootstrap_token_hash: str
    device_id: str
    organization_id: str
    user_id: str
    provider_base_url: str
    provider_api_key: str
    provider_model: str
    session_ttl_seconds: int = 900
    max_image_bytes: int = 8 * 1024 * 1024
    asr_url: str = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
    asr_api_key: str = ""
    asr_model: str = PREVIOUS_STABLE_ASR_MODEL
    knowledge_parser_token_hash: str = ""

    def __post_init__(self) -> None:
        parsed = urllib.parse.urlparse(self.provider_base_url)
        if not re.fullmatch(r"[0-9a-f]{64}", self.bootstrap_token_hash):
            raise ValueError("bootstrap_token_hash_invalid")
        if not self.device_id.strip():
            raise ValueError("device_id_missing")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}", self.organization_id):
            raise ValueError("organization_id_invalid")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}", self.user_id):
            raise ValueError("user_id_invalid")
        if parsed.scheme != "https" or not parsed.hostname:
            raise ValueError("provider_base_url_must_be_https")
        if not self.provider_api_key.strip():
            raise ValueError("provider_api_key_missing")
        if not self.provider_model.strip():
            raise ValueError("provider_model_missing")
        if self.provider_model.strip() != PREVIOUS_STABLE_AI_MODEL:
            raise ValueError("provider_model_not_previous_stable")
        if self.session_ttl_seconds < 60 or self.session_ttl_seconds > 900:
            raise ValueError("session_ttl_out_of_range")
        if self.max_image_bytes < 1024 or self.max_image_bytes > 12 * 1024 * 1024:
            raise ValueError("max_image_bytes_out_of_range")
        asr_url = urllib.parse.urlparse(self.asr_url)
        if asr_url.scheme != "wss" or not asr_url.hostname:
            raise ValueError("asr_url_must_be_wss")
        if self.asr_model != PREVIOUS_STABLE_ASR_MODEL:
            raise ValueError("asr_model_not_previous_stable")
        if self.knowledge_parser_token_hash and not re.fullmatch(
            r"[0-9a-f]{64}", self.knowledge_parser_token_hash
        ):
            raise ValueError("knowledge_parser_token_hash_invalid")


@dataclass
class GatewayResponse:
    status: int
    headers: dict[str, str]
    json_body: Optional[dict] = None
    body: Optional[Iterable[bytes]] = None


class SqliteStore:
    def __init__(self, database_path: str, evidence_dir: str, clock: Callable[[], float] = time.time):
        self.clock = clock
        self.evidence_dir = Path(evidence_dir)
        self.evidence_dir.mkdir(parents=True, exist_ok=True)
        Path(database_path).parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(database_path, check_same_thread=False)
        self.connection.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        with self.lock:
            self.connection.executescript(
                """
                PRAGMA journal_mode=WAL;
                PRAGMA foreign_keys=ON;
                CREATE TABLE IF NOT EXISTS device_sessions (
                    token_hash TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    issued_at REAL NOT NULL,
                    expires_at REAL NOT NULL,
                    revoked_at REAL
                );
                CREATE TABLE IF NOT EXISTS chat_sessions (
                    id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS images (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL REFERENCES chat_sessions(id),
                    device_id TEXT NOT NULL,
                    image_kind TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    byte_count INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    created_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL REFERENCES chat_sessions(id),
                    device_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    image_id TEXT,
                    trace_id TEXT NOT NULL,
                    created_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS ai_requests (
                    trace_id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    model TEXT NOT NULL,
                    status TEXT NOT NULL,
                    error_code TEXT,
                    started_at REAL NOT NULL,
                    completed_at REAL
                );
                CREATE TABLE IF NOT EXISTS ops_projects (
                    id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    local_project_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    UNIQUE(device_id, local_project_id)
                );
                CREATE TABLE IF NOT EXISTS maintenance_tasks (
                    id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    project_id TEXT NOT NULL REFERENCES ops_projects(id),
                    local_task_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    UNIQUE(device_id, local_task_id)
                );
                CREATE TABLE IF NOT EXISTS task_events (
                    id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL REFERENCES maintenance_tasks(id),
                    device_id TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    occurred_at TEXT NOT NULL,
                    received_at REAL NOT NULL,
                    UNIQUE(task_id, idempotency_key)
                );
                CREATE TABLE IF NOT EXISTS control_plane_event_outbox (
                    outbox_id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    local_project_id TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    event_json TEXT NOT NULL,
                    event_sha256 TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('pending', 'delivered')),
                    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count >= 0),
                    next_attempt_at REAL NOT NULL DEFAULT 0,
                    last_error_code TEXT NOT NULL DEFAULT '',
                    cloud_duplicate INTEGER NOT NULL DEFAULT 0 CHECK(cloud_duplicate IN (0, 1)),
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    delivered_at REAL,
                    UNIQUE(device_id, idempotency_key)
                );
                CREATE INDEX IF NOT EXISTS control_plane_event_outbox_pending_idx
                ON control_plane_event_outbox(status, next_attempt_at, created_at);
                CREATE TABLE IF NOT EXISTS device_context_commands (
                    device_id TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    command_type TEXT NOT NULL,
                    request_sha256 TEXT NOT NULL,
                    response_json TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    PRIMARY KEY(device_id, idempotency_key)
                );
                CREATE TABLE IF NOT EXISTS workflow_assignments (
                    assignment_id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    work_order_id TEXT NOT NULL,
                    project_id TEXT,
                    workflow_version_id TEXT,
                    mode TEXT NOT NULL,
                    status TEXT NOT NULL,
                    delivery_sequence INTEGER NOT NULL,
                    assigned_at TEXT NOT NULL,
                    work_order_json TEXT NOT NULL,
                    package_json TEXT,
                    provisioned_at REAL NOT NULL,
                    UNIQUE(device_id, delivery_sequence)
                );
                CREATE TABLE IF NOT EXISTS workflow_assignment_events (
                    id TEXT PRIMARY KEY,
                    assignment_id TEXT NOT NULL REFERENCES workflow_assignments(assignment_id),
                    device_id TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    status TEXT NOT NULL,
                    failure_stage TEXT,
                    failure_reason TEXT,
                    reported_at REAL NOT NULL,
                    UNIQUE(assignment_id, idempotency_key)
                );
                CREATE TABLE IF NOT EXISTS workflow_executions (
                    execution_id TEXT PRIMARY KEY,
                    assignment_id TEXT NOT NULL REFERENCES workflow_assignments(assignment_id),
                    device_id TEXT NOT NULL,
                    project_id TEXT NOT NULL,
                    task_id TEXT NOT NULL,
                    local_task_id TEXT NOT NULL,
                    initial_node_id TEXT NOT NULL,
                    current_node_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    runtime_snapshot_json TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    updated_at REAL NOT NULL,
                    UNIQUE(device_id, idempotency_key)
                );
                CREATE TABLE IF NOT EXISTS workflow_step_events (
                    step_execution_id TEXT PRIMARY KEY,
                    execution_id TEXT NOT NULL REFERENCES workflow_executions(execution_id),
                    device_id TEXT NOT NULL,
                    node_id TEXT NOT NULL,
                    attempt_number INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    evidence_asset_ids_json TEXT NOT NULL,
                    next_node_id TEXT,
                    updated_at TEXT NOT NULL,
                    UNIQUE(execution_id, idempotency_key)
                );
                CREATE TABLE IF NOT EXISTS workflow_evidence (
                    asset_id TEXT PRIMARY KEY,
                    assignment_id TEXT NOT NULL REFERENCES workflow_assignments(assignment_id),
                    execution_id TEXT NOT NULL REFERENCES workflow_executions(execution_id),
                    device_id TEXT NOT NULL,
                    local_evidence_id TEXT NOT NULL,
                    node_id TEXT NOT NULL,
                    evidence_key TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    content_type TEXT NOT NULL,
                    duration_seconds INTEGER NOT NULL,
                    byte_size INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    captured_at TEXT NOT NULL,
                    upload_status TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    UNIQUE(execution_id, local_evidence_id)
                );
                CREATE TABLE IF NOT EXISTS workflow_evidence_uploads (
                    upload_id TEXT PRIMARY KEY,
                    asset_id TEXT NOT NULL UNIQUE REFERENCES workflow_evidence(asset_id),
                    device_id TEXT NOT NULL,
                    chunk_size INTEGER NOT NULL,
                    chunk_count INTEGER NOT NULL,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    completed_at REAL,
                    cancelled_at REAL
                );
                CREATE TABLE IF NOT EXISTS workflow_evidence_parts (
                    upload_id TEXT NOT NULL REFERENCES workflow_evidence_uploads(upload_id),
                    asset_id TEXT NOT NULL REFERENCES workflow_evidence(asset_id),
                    chunk_index INTEGER NOT NULL,
                    byte_size INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    PRIMARY KEY(upload_id, chunk_index),
                    UNIQUE(asset_id, chunk_index)
                );
                CREATE TABLE IF NOT EXISTS mvs_work_order_operations (
                    id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    idempotency_key TEXT NOT NULL,
                    command_type TEXT NOT NULL,
                    order_id TEXT NOT NULL,
                    request_sha256 TEXT NOT NULL,
                    request_json TEXT NOT NULL,
                    trace_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    http_status INTEGER,
                    response_json TEXT,
                    error_code TEXT,
                    attempt_count INTEGER NOT NULL,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    UNIQUE(device_id, idempotency_key)
                );
                CREATE TABLE IF NOT EXISTS mvs_work_order_audit_events (
                    id TEXT PRIMARY KEY,
                    operation_id TEXT NOT NULL REFERENCES mvs_work_order_operations(id),
                    device_id TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    status TEXT NOT NULL,
                    error_code TEXT,
                    created_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS mvs_work_order_resource_audit_events (
                    id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    order_id TEXT NOT NULL,
                    resource_type TEXT NOT NULL,
                    outcome TEXT NOT NULL,
                    filtered_count INTEGER NOT NULL,
                    created_at REAL NOT NULL
                );
                """
            )
            upload_columns = {
                str(row["name"])
                for row in self.connection.execute(
                    "PRAGMA table_info(workflow_evidence_uploads)"
                ).fetchall()
            }
            if "cancelled_at" not in upload_columns:
                self.connection.execute(
                    "ALTER TABLE workflow_evidence_uploads ADD COLUMN cancelled_at REAL"
                )
            self.connection.commit()
        self.execution_context = ExecutionContextRepository(
            self.connection,
            self.lock,
            clock=self.clock,
            require_authoritative_manifest=True,
        )
        self._recover_interrupted_ai_requests()

    def _recover_interrupted_ai_requests(self) -> None:
        now = self.clock()
        with self.lock:
            self.connection.execute(
                """
                UPDATE ai_requests
                SET status = 'failed', error_code = 'gateway_restarted', completed_at = ?
                WHERE status = 'started'
                """,
                (now,),
            )
            self.connection.execute(
                """
                UPDATE execution_context_runs
                SET status = 'failed', error_code = 'gateway_restarted',
                    latency_ms = CAST(MAX(0, (? - created_at) * 1000) AS INTEGER),
                    completed_at = ?
                WHERE status = 'started'
                """,
                (now, now),
            )
            self.connection.commit()

    def close(self) -> None:
        with self.lock:
            self.connection.close()

    def readiness(self) -> dict[str, str]:
        with self.lock:
            result = self.connection.execute("PRAGMA quick_check").fetchone()
        if not result or result[0] != "ok":
            raise sqlite3.DatabaseError("sqlite_quick_check_failed")
        if not self.evidence_dir.is_dir():
            raise OSError("evidence_directory_missing")
        return {"storage": "ready"}

    def issue_device_session(self, device_id: str, token: str, expires_at: float) -> None:
        now = self.clock()
        token_hash = _sha256(token.encode("utf-8"))
        with self.lock:
            self.connection.execute("DELETE FROM device_sessions WHERE expires_at <= ?", (now,))
            self.connection.execute(
                "INSERT INTO device_sessions(token_hash, device_id, issued_at, expires_at) VALUES (?, ?, ?, ?)",
                (token_hash, device_id, now, expires_at),
            )
            self.connection.commit()

    def authenticate_device_session(self, token: str) -> Optional[str]:
        if not token:
            return None
        token_hash = _sha256(token.encode("utf-8"))
        with self.lock:
            row = self.connection.execute(
                """
                SELECT device_id FROM device_sessions
                WHERE token_hash = ? AND expires_at > ? AND revoked_at IS NULL
                """,
                (token_hash, self.clock()),
            ).fetchone()
        return str(row["device_id"]) if row else None

    def ensure_chat_session(self, requested_id: str, device_id: str) -> str:
        session_id = requested_id.strip()
        if not session_id or session_id == "_":
            session_id = str(uuid.uuid4())
        if not SESSION_ID_PATTERN.fullmatch(session_id):
            raise ValueError("invalid_session_id")
        now = self.clock()
        with self.lock:
            row = self.connection.execute(
                "SELECT device_id FROM chat_sessions WHERE id = ?", (session_id,)
            ).fetchone()
            if row and str(row["device_id"]) != device_id:
                raise PermissionError("session_device_mismatch")
            if row:
                self.connection.execute(
                    "UPDATE chat_sessions SET updated_at = ? WHERE id = ?", (now, session_id)
                )
            else:
                self.connection.execute(
                    "INSERT INTO chat_sessions(id, device_id, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    (session_id, device_id, now, now),
                )
            self.connection.commit()
        return session_id

    def save_image(self, session_id: str, device_id: str, image_kind: str, content: bytes) -> dict:
        image_id = str(uuid.uuid4())
        digest = _sha256(content)
        session_dir = self.evidence_dir / session_id
        session_dir.mkdir(parents=True, exist_ok=True)
        path = session_dir / f"{image_id}.jpg"
        temporary = session_dir / f".{image_id}.tmp"
        temporary.write_bytes(content)
        os.replace(temporary, path)
        with self.lock:
            self.connection.execute(
                """
                INSERT INTO images(
                    id, session_id, device_id, image_kind, file_path,
                    byte_count, sha256, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    image_id,
                    session_id,
                    device_id,
                    image_kind,
                    str(path),
                    len(content),
                    digest,
                    self.clock(),
                ),
            )
            self.connection.commit()
        return {"id": image_id, "byte_count": len(content), "sha256": digest}

    def load_image(self, image_id: str, session_id: str, device_id: str) -> bytes:
        with self.lock:
            row = self.connection.execute(
                """
                SELECT file_path FROM images
                WHERE id = ? AND session_id = ? AND device_id = ?
                """,
                (image_id, session_id, device_id),
            ).fetchone()
        if not row:
            raise LookupError("image_not_found")
        return Path(str(row["file_path"])).read_bytes()

    def append_message(
        self,
        session_id: str,
        device_id: str,
        role: str,
        content: str,
        image_id: str,
        trace_id: str,
    ) -> None:
        with self.lock:
            self.connection.execute(
                """
                INSERT INTO messages(
                    id, session_id, device_id, role, content, image_id, trace_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    str(uuid.uuid4()),
                    session_id,
                    device_id,
                    role,
                    content,
                    image_id or None,
                    trace_id,
                    self.clock(),
                ),
            )
            self.connection.commit()

    def recent_messages(
        self, session_id: str, device_id: str, limit: int = 12
    ) -> list[dict[str, str]]:
        bounded_limit = min(max(limit, 1), 12)
        with self.lock:
            rows = self.connection.execute(
                """
                SELECT role, content FROM messages
                WHERE session_id = ? AND device_id = ?
                ORDER BY created_at DESC, rowid DESC
                LIMIT ?
                """,
                (session_id, device_id, bounded_limit),
            ).fetchall()
        return [
            {"role": str(row["role"]), "content": str(row["content"])}
            for row in reversed(rows)
        ]

    def start_ai_request(self, trace_id: str, session_id: str, device_id: str, model: str) -> None:
        with self.lock:
            self.connection.execute(
                """
                INSERT INTO ai_requests(
                    trace_id, session_id, device_id, model, status, started_at
                ) VALUES (?, ?, ?, ?, 'started', ?)
                """,
                (trace_id, session_id, device_id, model, self.clock()),
            )
            self.connection.commit()

    def finish_ai_request(self, trace_id: str, status: str, error_code: str = "") -> None:
        with self.lock:
            self.connection.execute(
                """
                UPDATE ai_requests
                SET status = ?, error_code = ?, completed_at = ?
                WHERE trace_id = ?
                """,
                (status, error_code or None, self.clock(), trace_id),
            )
            self.connection.commit()

    def append_device_event(
        self, device_id: str, event: Mapping[str, object]
    ) -> bool:
        now = self.clock()
        local_project_id = str(event["localProjectId"]).strip()
        local_task_id = str(event["localTaskId"]).strip()
        event_type = str(event["eventType"]).strip()
        idempotency_key = str(event["idempotencyKey"]).strip()
        project_title = str(event.get("projectTitle") or "").strip()
        task_title = str(event.get("taskTitle") or "").strip()
        task_status = _task_status(event_type)
        event_json = json.dumps(
            dict(event),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        event_sha256 = _sha256(event_json.encode("utf-8"))
        payload_json = json.dumps(
            event["payload"],
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        with self.lock:
            existing_outbox = self.connection.execute(
                """
                SELECT event_sha256 FROM control_plane_event_outbox
                WHERE device_id = ? AND idempotency_key = ?
                """,
                (device_id, idempotency_key),
            ).fetchone()
            if existing_outbox:
                if str(existing_outbox["event_sha256"]) != event_sha256:
                    raise ValueError("idempotency_conflict")
                return True

            project = self.connection.execute(
                "SELECT id FROM ops_projects WHERE device_id = ? AND local_project_id = ?",
                (device_id, local_project_id),
            ).fetchone()
            if project:
                project_id = str(project["id"])
                self.connection.execute(
                    """
                    UPDATE ops_projects
                    SET title = CASE WHEN ? = '' THEN title ELSE ? END,
                        updated_at = ?
                    WHERE id = ?
                    """,
                    (project_title, project_title, now, project_id),
                )
            else:
                project_id = str(uuid.uuid4())
                self.connection.execute(
                    """
                    INSERT INTO ops_projects(
                        id, device_id, local_project_id, title, status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 'active', ?, ?)
                    """,
                    (project_id, device_id, local_project_id, project_title, now, now),
                )

            task = self.connection.execute(
                "SELECT id, status FROM maintenance_tasks WHERE device_id = ? AND local_task_id = ?",
                (device_id, local_task_id),
            ).fetchone()
            if task:
                task_id = str(task["id"])
                current_status = str(task["status"])
                next_status = (
                    current_status
                    if current_status in {"completed", "closed"} and task_status == "active"
                    else task_status
                )
                self.connection.execute(
                    """
                    UPDATE maintenance_tasks
                    SET title = CASE WHEN ? = '' THEN title ELSE ? END,
                        status = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    (task_title, task_title, next_status, now, task_id),
                )
            else:
                task_id = str(uuid.uuid4())
                self.connection.execute(
                    """
                    INSERT INTO maintenance_tasks(
                        id, device_id, project_id, local_task_id, title,
                        status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        task_id,
                        device_id,
                        project_id,
                        local_task_id,
                        task_title,
                        task_status,
                        now,
                        now,
                    ),
                )
            try:
                self.connection.execute(
                    """
                    INSERT INTO task_events(
                        id, task_id, device_id, idempotency_key, event_type,
                        payload_json, occurred_at, received_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        str(uuid.uuid4()),
                        task_id,
                        device_id,
                        idempotency_key,
                        event_type,
                        payload_json,
                        str(event["occurredAt"]),
                        now,
                    ),
                )
                duplicate = False
            except sqlite3.IntegrityError:
                existing_event = self.connection.execute(
                    """
                    SELECT event_type, payload_json, occurred_at
                    FROM task_events
                    WHERE task_id = ? AND idempotency_key = ?
                    """,
                    (task_id, idempotency_key),
                ).fetchone()
                if (
                    not existing_event
                    or str(existing_event["event_type"]) != event_type
                    or str(existing_event["occurred_at"]) != str(event["occurredAt"])
                    or _canonical_json(json.loads(str(existing_event["payload_json"])))
                    != _canonical_json(event["payload"])
                ):
                    self.connection.rollback()
                    raise ValueError("idempotency_conflict")
                duplicate = True
            inserted_outbox = self.connection.execute(
                """
                INSERT OR IGNORE INTO control_plane_event_outbox(
                    outbox_id, device_id, idempotency_key, local_project_id,
                    event_type, event_json, event_sha256, status,
                    attempt_count, next_attempt_at, last_error_code,
                    cloud_duplicate, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', 0, 0, '', 0, ?, ?)
                """,
                (
                    str(uuid.uuid4()),
                    device_id,
                    idempotency_key,
                    local_project_id,
                    event_type,
                    event_json,
                    event_sha256,
                    now,
                    now,
                ),
            )
            if inserted_outbox.rowcount == 0:
                existing_outbox = self.connection.execute(
                    """
                    SELECT event_sha256 FROM control_plane_event_outbox
                    WHERE device_id = ? AND idempotency_key = ?
                    """,
                    (device_id, idempotency_key),
                ).fetchone()
                if (
                    not existing_outbox
                    or str(existing_outbox["event_sha256"]) != event_sha256
                ):
                    self.connection.rollback()
                    raise ValueError("idempotency_conflict")
            self.connection.commit()
        return duplicate

    def pending_control_plane_events(
        self, now: float, limit: int = 25
    ) -> list[dict]:
        bounded_limit = min(max(int(limit), 1), 100)
        with self.lock:
            rows = self.connection.execute(
                """
                SELECT * FROM control_plane_event_outbox
                WHERE status = 'pending' AND next_attempt_at <= ?
                ORDER BY created_at, rowid
                LIMIT ?
                """,
                (float(now), bounded_limit),
            ).fetchall()
        return [
            {
                "outboxId": str(row["outbox_id"]),
                "deviceId": str(row["device_id"]),
                "localProjectId": str(row["local_project_id"]),
                "eventType": str(row["event_type"]),
                "attemptCount": int(row["attempt_count"]),
                "event": json.loads(str(row["event_json"])),
            }
            for row in rows
        ]

    def mark_control_plane_event_failed(
        self,
        outbox_id: str,
        error_code: str,
        attempt_count: int,
        next_attempt_at: float,
        failed_at: float,
    ) -> None:
        with self.lock:
            self.connection.execute(
                """
                UPDATE control_plane_event_outbox
                SET attempt_count = ?, next_attempt_at = ?,
                    last_error_code = ?, updated_at = ?
                WHERE outbox_id = ? AND status = 'pending'
                """,
                (
                    int(attempt_count),
                    float(next_attempt_at),
                    str(error_code),
                    float(failed_at),
                    outbox_id,
                ),
            )
            self.connection.commit()

    def mark_control_plane_event_succeeded(
        self, outbox_id: str, duplicate: bool, delivered_at: float
    ) -> None:
        with self.lock:
            self.connection.execute(
                """
                UPDATE control_plane_event_outbox
                SET status = 'delivered', cloud_duplicate = ?,
                    last_error_code = '', next_attempt_at = 0,
                    delivered_at = ?, updated_at = ?
                WHERE outbox_id = ? AND status = 'pending'
                """,
                (
                    1 if duplicate else 0,
                    float(delivered_at),
                    float(delivered_at),
                    outbox_id,
                ),
            )
            self.connection.commit()

    def load_device_context_command(
        self,
        device_id: str,
        idempotency_key: str,
        command_type: str,
        payload: Mapping[str, object],
    ) -> Optional[dict]:
        request_json = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        request_sha256 = _sha256(request_json.encode("utf-8"))
        with self.lock:
            row = self.connection.execute(
                """
                SELECT command_type, request_sha256, response_json
                FROM device_context_commands
                WHERE device_id = ? AND idempotency_key = ?
                """,
                (device_id, idempotency_key),
            ).fetchone()
        if not row:
            return None
        if (
            str(row["command_type"]) != command_type
            or str(row["request_sha256"]) != request_sha256
        ):
            raise ValueError("idempotency_conflict")
        return json.loads(str(row["response_json"]))

    def save_device_context_command(
        self,
        device_id: str,
        idempotency_key: str,
        command_type: str,
        payload: Mapping[str, object],
        response: Mapping[str, object],
    ) -> None:
        request_json = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        response_json = json.dumps(
            response,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        with self.lock:
            self.connection.execute(
                """
                INSERT INTO device_context_commands(
                    device_id, idempotency_key, command_type,
                    request_sha256, response_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    device_id,
                    idempotency_key,
                    command_type,
                    _sha256(request_json.encode("utf-8")),
                    response_json,
                    self.clock(),
                ),
            )
            self.connection.commit()

    def begin_mvs_work_order_operation(
        self,
        device_id: str,
        idempotency_key: str,
        command_type: str,
        order_id: str,
        payload: Mapping[str, object],
        trace_id: str,
    ) -> dict:
        request_json = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        request_sha256 = _sha256(request_json.encode("utf-8"))
        now = self.clock()
        with self.lock:
            row = self.connection.execute(
                """
                SELECT * FROM mvs_work_order_operations
                WHERE device_id = ? AND idempotency_key = ?
                """,
                (device_id, idempotency_key),
            ).fetchone()
            if row:
                if (
                    str(row["command_type"]) != command_type
                    or str(row["order_id"]) != order_id
                    or str(row["request_sha256"]) != request_sha256
                ):
                    raise ValueError("idempotency_conflict")
                return self._mvs_operation_payload(row, duplicate=True)
            operation_id = str(uuid.uuid4())
            self.connection.execute(
                """
                INSERT INTO mvs_work_order_operations(
                    id, device_id, idempotency_key, command_type, order_id,
                    request_sha256, request_json, trace_id, status,
                    attempt_count, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', 1, ?, ?)
                """,
                (
                    operation_id,
                    device_id,
                    idempotency_key,
                    command_type,
                    order_id,
                    request_sha256,
                    request_json,
                    trace_id,
                    now,
                    now,
                ),
            )
            self._append_mvs_work_order_audit_event(
                operation_id,
                device_id,
                "requested",
                "pending",
            )
            self.connection.commit()
        return {
            "id": operation_id,
            "status": "pending",
            "duplicate": False,
            "traceId": trace_id,
        }

    def finish_mvs_work_order_operation(
        self,
        operation_id: str,
        device_id: str,
        status: str,
        http_status: int,
        response: Mapping[str, object],
        error_code: str = "",
    ) -> dict:
        response_json = json.dumps(
            response,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        with self.lock:
            self.connection.execute(
                """
                UPDATE mvs_work_order_operations
                SET status = ?, http_status = ?, response_json = ?,
                    error_code = ?, updated_at = ?
                WHERE id = ? AND device_id = ?
                """,
                (
                    status,
                    http_status,
                    response_json,
                    error_code or None,
                    self.clock(),
                    operation_id,
                    device_id,
                ),
            )
            self._append_mvs_work_order_audit_event(
                operation_id,
                device_id,
                "completed" if status == "succeeded" else "deferred",
                status,
                error_code,
            )
            self.connection.commit()
            row = self.connection.execute(
                "SELECT * FROM mvs_work_order_operations WHERE id = ?",
                (operation_id,),
            ).fetchone()
        if not row:
            raise LookupError("mvs_operation_not_found")
        return self._mvs_operation_payload(row, duplicate=False)

    def load_mvs_work_order_operation(
        self, device_id: str, idempotency_key: str
    ) -> Optional[dict]:
        with self.lock:
            row = self.connection.execute(
                """
                SELECT * FROM mvs_work_order_operations
                WHERE device_id = ? AND idempotency_key = ?
                """,
                (device_id, idempotency_key),
            ).fetchone()
        return self._mvs_operation_payload(row, duplicate=False) if row else None

    def claim_retryable_mvs_work_order_operations(
        self,
        *,
        limit: int = 10,
        max_attempts: int = 5,
        retry_base_seconds: float = 5.0,
        retry_stale_seconds: float = 30.0,
    ) -> list[dict]:
        if limit < 1 or limit > 100 or max_attempts < 2 or max_attempts > 20:
            raise ValueError("mvs_retry_policy_invalid")
        if retry_base_seconds < 0 or retry_stale_seconds < 0:
            raise ValueError("mvs_retry_policy_invalid")
        now = self.clock()
        claimed: list[dict] = []
        with self.lock:
            rows = self.connection.execute(
                """
                SELECT * FROM mvs_work_order_operations
                WHERE status IN ('pending', 'retryable', 'retrying')
                ORDER BY updated_at ASC, created_at ASC
                LIMIT ?
                """,
                (limit * 4,),
            ).fetchall()
            for row in rows:
                attempt_count = int(row["attempt_count"])
                status = str(row["status"])
                if attempt_count >= max_attempts:
                    response = {
                        "ok": False,
                        "operationId": str(row["id"]),
                        "duplicate": False,
                        "retryable": False,
                        "error": "mvs_retry_exhausted",
                    }
                    self.connection.execute(
                        """
                        UPDATE mvs_work_order_operations
                        SET status = 'failed', http_status = 503,
                            response_json = ?, error_code = ?, updated_at = ?
                        WHERE id = ? AND status IN ('pending', 'retryable', 'retrying')
                        """,
                        (
                            json.dumps(
                                response,
                                ensure_ascii=False,
                                sort_keys=True,
                                separators=(",", ":"),
                            ),
                            "mvs_retry_exhausted",
                            now,
                            str(row["id"]),
                        ),
                    )
                    self._append_mvs_work_order_audit_event(
                        str(row["id"]),
                        str(row["device_id"]),
                        "retry_exhausted",
                        "failed",
                        "mvs_retry_exhausted",
                    )
                    continue
                age = max(0.0, now - float(row["updated_at"]))
                if status == "retryable":
                    delay = retry_base_seconds * (2 ** max(0, attempt_count - 1))
                else:
                    delay = retry_stale_seconds
                if age < delay:
                    continue
                updated = self.connection.execute(
                    """
                    UPDATE mvs_work_order_operations
                    SET status = 'retrying', attempt_count = attempt_count + 1,
                        updated_at = ?
                    WHERE id = ? AND status = ? AND attempt_count = ?
                    """,
                    (now, str(row["id"]), status, attempt_count),
                ).rowcount
                if updated != 1:
                    continue
                self._append_mvs_work_order_audit_event(
                    str(row["id"]),
                    str(row["device_id"]),
                    "retry_started",
                    "retrying",
                )
                claimed_row = self.connection.execute(
                    "SELECT * FROM mvs_work_order_operations WHERE id = ?",
                    (str(row["id"]),),
                ).fetchone()
                if claimed_row is None:
                    continue
                payload = self._mvs_operation_payload(claimed_row, duplicate=False)
                request = json.loads(str(claimed_row["request_json"]))
                if not isinstance(request, dict):
                    raise ValueError("mvs_operation_request_invalid")
                payload["request"] = request
                claimed.append(payload)
                if len(claimed) >= limit:
                    break
            self.connection.commit()
        return claimed

    def list_mvs_work_order_audit_events(self, operation_id: str) -> list[dict]:
        with self.lock:
            rows = self.connection.execute(
                """
                SELECT id, event_type, status, error_code, created_at
                FROM mvs_work_order_audit_events
                WHERE operation_id = ?
                ORDER BY created_at ASC, rowid ASC
                """,
                (operation_id,),
            ).fetchall()
        return [
            {
                "id": str(row["id"]),
                "eventType": str(row["event_type"]),
                "status": str(row["status"]),
                "errorCode": str(row["error_code"] or ""),
                "createdAt": float(row["created_at"]),
            }
            for row in rows
        ]

    def record_mvs_work_order_resource_audit_event(
        self,
        device_id: str,
        order_id: str,
        resource_type: str,
        outcome: str,
        filtered_count: int = 0,
    ) -> str:
        accepted_device_id = str(device_id or "").strip()
        accepted_order_id = str(order_id or "").strip()
        accepted_resource_type = str(resource_type or "").strip()
        accepted_outcome = str(outcome or "").strip()
        if not _valid_identifier(accepted_device_id, 200):
            raise ValueError("mvs_resource_audit_device_invalid")
        if re.fullmatch(r"[1-9][0-9]{0,18}", accepted_order_id) is None:
            raise ValueError("mvs_resource_audit_order_invalid")
        if accepted_resource_type != "task_operations":
            raise ValueError("mvs_resource_audit_type_invalid")
        if accepted_outcome not in {"allowed", "failed"}:
            raise ValueError("mvs_resource_audit_outcome_invalid")
        if (
            not isinstance(filtered_count, int)
            or isinstance(filtered_count, bool)
            or filtered_count < 0
            or filtered_count > 100
        ):
            raise ValueError("mvs_resource_audit_filtered_count_invalid")
        event_id = str(uuid.uuid4())
        with self.lock:
            self.connection.execute(
                """
                INSERT INTO mvs_work_order_resource_audit_events(
                    id, device_id, order_id, resource_type, outcome,
                    filtered_count, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    event_id,
                    accepted_device_id,
                    accepted_order_id,
                    accepted_resource_type,
                    accepted_outcome,
                    filtered_count,
                    self.clock(),
                ),
            )
            self.connection.commit()
        return event_id

    def list_mvs_work_order_resource_audit_events(
        self, device_id: str, order_id: str
    ) -> list[dict]:
        with self.lock:
            rows = self.connection.execute(
                """
                SELECT id, resource_type, outcome, filtered_count, created_at
                FROM mvs_work_order_resource_audit_events
                WHERE device_id = ? AND order_id = ?
                ORDER BY created_at ASC, rowid ASC
                """,
                (str(device_id or "").strip(), str(order_id or "").strip()),
            ).fetchall()
        return [
            {
                "id": str(row["id"]),
                "resourceType": str(row["resource_type"]),
                "outcome": str(row["outcome"]),
                "filteredCount": int(row["filtered_count"]),
                "createdAt": float(row["created_at"]),
            }
            for row in rows
        ]

    def _append_mvs_work_order_audit_event(
        self,
        operation_id: str,
        device_id: str,
        event_type: str,
        status: str,
        error_code: str = "",
    ) -> None:
        self.connection.execute(
            """
            INSERT INTO mvs_work_order_audit_events(
                id, operation_id, device_id, event_type, status,
                error_code, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                str(uuid.uuid4()),
                operation_id,
                device_id,
                event_type,
                status,
                error_code or None,
                self.clock(),
            ),
        )

    @staticmethod
    def _mvs_operation_payload(row: sqlite3.Row, duplicate: bool) -> dict:
        response = (
            json.loads(str(row["response_json"]))
            if row["response_json"] is not None
            else None
        )
        return {
            "id": str(row["id"]),
            "deviceId": str(row["device_id"]),
            "idempotencyKey": str(row["idempotency_key"]),
            "commandType": str(row["command_type"]),
            "orderId": str(row["order_id"]),
            "traceId": str(row["trace_id"]),
            "status": str(row["status"]),
            "httpStatus": int(row["http_status"]) if row["http_status"] is not None else None,
            "response": response,
            "errorCode": str(row["error_code"] or ""),
            "attemptCount": int(row["attempt_count"]),
            "duplicate": duplicate,
        }
    def provision_workflow_assignment(
        self,
        device_id: str,
        assignment: Mapping[str, object],
        package: Optional[Mapping[str, object]],
    ) -> None:
        assignment_id = str(assignment.get("assignmentId") or "").strip()
        work_order_id = str(assignment.get("workOrderId") or "").strip()
        mode = str(assignment.get("mode") or "").strip()
        status = str(assignment.get("status") or "").strip()
        sequence = assignment.get("deliverySequence")
        assigned_at = str(assignment.get("assignedAt") or "").strip()
        work_order = assignment.get("workOrder")
        if (
            not device_id.strip()
            or not _valid_identifier(assignment_id, 200)
            or not _valid_identifier(work_order_id, 200)
            or mode not in WORKFLOW_ASSIGNMENT_MODES
            or status not in WORKFLOW_ASSIGNMENT_STATUSES
            or not isinstance(sequence, int)
            or isinstance(sequence, bool)
            or sequence < 1
            or sequence > 9_007_199_254_740_991
            or not _valid_iso_timestamp(assigned_at)
            or not isinstance(work_order, dict)
            or (package is not None and not isinstance(package, Mapping))
        ):
            raise ValueError("workflow_assignment_invalid")
        package_json = (
            json.dumps(package, ensure_ascii=False, separators=(",", ":"))
            if package is not None
            else None
        )
        with self.lock:
            self.connection.execute(
                """
                INSERT INTO workflow_assignments(
                    assignment_id, device_id, work_order_id, project_id,
                    workflow_version_id, mode, status, delivery_sequence,
                    assigned_at, work_order_json, package_json, provisioned_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(assignment_id) DO UPDATE SET
                    device_id = excluded.device_id,
                    work_order_id = excluded.work_order_id,
                    project_id = excluded.project_id,
                    workflow_version_id = excluded.workflow_version_id,
                    mode = excluded.mode,
                    status = excluded.status,
                    delivery_sequence = excluded.delivery_sequence,
                    assigned_at = excluded.assigned_at,
                    work_order_json = excluded.work_order_json,
                    package_json = excluded.package_json,
                    provisioned_at = excluded.provisioned_at
                """,
                (
                    assignment_id,
                    device_id.strip(),
                    work_order_id,
                    str(assignment.get("projectId") or "").strip() or None,
                    str(assignment.get("workflowVersionId") or "").strip() or None,
                    mode,
                    status,
                    sequence,
                    assigned_at,
                    json.dumps(work_order, ensure_ascii=False, separators=(",", ":")),
                    package_json,
                    self.clock(),
                ),
            )
            self.connection.commit()

    def list_workflow_assignments(
        self, device_id: str, after_sequence: int, limit: int
    ) -> list[dict]:
        with self.lock:
            rows = self.connection.execute(
                """
                SELECT assignment_id, work_order_id, project_id, workflow_version_id,
                       mode, status, delivery_sequence, assigned_at, work_order_json
                FROM workflow_assignments
                WHERE device_id = ? AND delivery_sequence > ?
                ORDER BY delivery_sequence ASC
                LIMIT ?
                """,
                (device_id, after_sequence, limit),
            ).fetchall()
        return [
            {
                "assignmentId": str(row["assignment_id"]),
                "workOrderId": str(row["work_order_id"]),
                "projectId": row["project_id"],
                "workflowVersionId": row["workflow_version_id"],
                "mode": str(row["mode"]),
                "status": str(row["status"]),
                "deliverySequence": int(row["delivery_sequence"]),
                "assignedAt": str(row["assigned_at"]),
                "workOrder": json.loads(str(row["work_order_json"])),
            }
            for row in rows
        ]

    def load_workflow_package(
        self, device_id: str, assignment_id: str
    ) -> Optional[dict]:
        with self.lock:
            row = self.connection.execute(
                """
                SELECT mode, workflow_version_id, package_json
                FROM workflow_assignments
                WHERE device_id = ? AND assignment_id = ?
                """,
                (device_id, assignment_id),
            ).fetchone()
        if not row:
            return None
        package = json.loads(str(row["package_json"])) if row["package_json"] else None
        return {
            "mode": str(row["mode"]),
            "workflowVersionId": row["workflow_version_id"],
            "package": package,
        }

    def report_workflow_assignment_status(
        self, device_id: str, assignment_id: str, command: Mapping[str, object]
    ) -> Optional[dict]:
        now = self.clock()
        with self.lock:
            assignment = self.connection.execute(
                """
                SELECT status FROM workflow_assignments
                WHERE assignment_id = ? AND device_id = ?
                """,
                (assignment_id, device_id),
            ).fetchone()
            if not assignment:
                return None
            current_status = str(assignment["status"])
            if current_status == "revoked":
                raise ValueError("workflow_assignment_revoked")
            duplicate = self.connection.execute(
                """
                SELECT status FROM workflow_assignment_events
                WHERE assignment_id = ? AND idempotency_key = ?
                """,
                (assignment_id, str(command["idempotencyKey"])),
            ).fetchone()
            if duplicate:
                return {"assignmentId": assignment_id, "status": current_status}
            requested_status = str(command["status"])
            next_status = _advanced_assignment_status(current_status, requested_status)
            self.connection.execute(
                """
                INSERT INTO workflow_assignment_events(
                    id, assignment_id, device_id, idempotency_key, status,
                    failure_stage, failure_reason, reported_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    str(uuid.uuid4()),
                    assignment_id,
                    device_id,
                    str(command["idempotencyKey"]),
                    requested_status,
                    command.get("failureStage"),
                    command.get("failureReason"),
                    now,
                ),
            )
            self.connection.execute(
                "UPDATE workflow_assignments SET status = ? WHERE assignment_id = ?",
                (next_status, assignment_id),
            )
            self.connection.commit()
        return {"assignmentId": assignment_id, "status": next_status}

    def start_workflow_execution(
        self, device_id: str, command: Mapping[str, object]
    ) -> Optional[dict]:
        execution_id = str(command["executionId"])
        assignment_id = str(command["assignmentId"])
        now = self.clock()
        started_at = _iso_timestamp(now)
        with self.lock:
            assignment = self.connection.execute(
                """
                SELECT project_id, mode, status FROM workflow_assignments
                WHERE assignment_id = ? AND device_id = ?
                """,
                (assignment_id, device_id),
            ).fetchone()
            if (
                not assignment
                or str(assignment["project_id"] or "") != str(command["projectId"])
                or str(assignment["mode"]) == "none"
                or str(assignment["status"]) == "revoked"
            ):
                return None
            existing = self.connection.execute(
                """
                SELECT * FROM workflow_executions
                WHERE execution_id = ? OR (device_id = ? AND idempotency_key = ?)
                """,
                (execution_id, device_id, str(command["idempotencyKey"])),
            ).fetchone()
            if existing:
                if (
                    str(existing["execution_id"]) != execution_id
                    or str(existing["assignment_id"]) != assignment_id
                    or str(existing["project_id"]) != str(command["projectId"])
                    or str(existing["local_task_id"]) != str(command["localTaskId"])
                    or str(existing["initial_node_id"]) != str(command["initialNodeId"])
                ):
                    raise ValueError("workflow_execution_conflict")
                return _workflow_execution_row(existing)
            task_id = str(uuid.uuid4())
            self.connection.execute(
                """
                INSERT INTO workflow_executions(
                    execution_id, assignment_id, device_id, project_id, task_id,
                    local_task_id, initial_node_id, current_node_id, status,
                    runtime_snapshot_json, idempotency_key, started_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, ?)
                """,
                (
                    execution_id,
                    assignment_id,
                    device_id,
                    str(command["projectId"]),
                    task_id,
                    str(command["localTaskId"]),
                    str(command["initialNodeId"]),
                    str(command["initialNodeId"]),
                    json.dumps(
                        command["runtimeSnapshot"],
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                    str(command["idempotencyKey"]),
                    started_at,
                    now,
                ),
            )
            self.connection.execute(
                "UPDATE workflow_assignments SET status = 'active' WHERE assignment_id = ?",
                (assignment_id,),
            )
            self.connection.commit()
            row = self.connection.execute(
                "SELECT * FROM workflow_executions WHERE execution_id = ?",
                (execution_id,),
            ).fetchone()
        return _workflow_execution_row(row)

    def append_workflow_step(
        self, device_id: str, execution_id: str, command: Mapping[str, object]
    ) -> Optional[dict]:
        now = self.clock()
        updated_at = _iso_timestamp(now)
        with self.lock:
            execution = self.connection.execute(
                """
                SELECT assignment_id FROM workflow_executions
                WHERE execution_id = ? AND device_id = ?
                """,
                (execution_id, device_id),
            ).fetchone()
            if not execution:
                return None
            existing = self.connection.execute(
                """
                SELECT * FROM workflow_step_events
                WHERE execution_id = ? AND idempotency_key = ?
                """,
                (execution_id, str(command["idempotencyKey"])),
            ).fetchone()
            if existing:
                return _workflow_step_row(existing)
            step_execution_id = str(uuid.uuid4())
            next_node_id = command.get("nextNodeId")
            self.connection.execute(
                """
                INSERT INTO workflow_step_events(
                    step_execution_id, execution_id, device_id, node_id,
                    attempt_number, status, idempotency_key, payload_json,
                    evidence_asset_ids_json, next_node_id, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    step_execution_id,
                    execution_id,
                    device_id,
                    str(command["nodeId"]),
                    int(command["attemptNumber"]),
                    str(command["status"]),
                    str(command["idempotencyKey"]),
                    json.dumps(command, ensure_ascii=False, separators=(",", ":")),
                    json.dumps(command["evidenceAssetIds"], separators=(",", ":")),
                    next_node_id,
                    updated_at,
                ),
            )
            execution_status = (
                "failed" if command["status"] == "failed" else "active"
            )
            current_node_id = str(next_node_id or command["nodeId"])
            self.connection.execute(
                """
                UPDATE workflow_executions
                SET current_node_id = ?, status = ?, runtime_snapshot_json = ?, updated_at = ?
                WHERE execution_id = ?
                """,
                (
                    current_node_id,
                    execution_status,
                    json.dumps(
                        command["runtimeSnapshot"],
                        ensure_ascii=False,
                        separators=(",", ":"),
                    ),
                    now,
                    execution_id,
                ),
            )
            self.connection.commit()
            row = self.connection.execute(
                "SELECT * FROM workflow_step_events WHERE step_execution_id = ?",
                (step_execution_id,),
            ).fetchone()
        return _workflow_step_row(row)

    def store_workflow_evidence(
        self, device_id: str, command: Mapping[str, object]
    ) -> Optional[dict]:
        assignment_id = str(command["assignmentId"])
        execution_id = str(command["executionId"])
        local_evidence_id = str(command["localEvidenceId"])
        with self.lock:
            execution = self.connection.execute(
                """
                SELECT execution_id FROM workflow_executions
                WHERE execution_id = ? AND assignment_id = ? AND device_id = ?
                """,
                (execution_id, assignment_id, device_id),
            ).fetchone()
            if not execution:
                return None
            existing = self.connection.execute(
                """
                SELECT * FROM workflow_evidence
                WHERE execution_id = ? AND local_evidence_id = ?
                """,
                (execution_id, local_evidence_id),
            ).fetchone()
            if existing:
                if not _matching_workflow_evidence(existing, command):
                    raise ValueError("workflow_evidence_conflict")
                return _workflow_evidence_row(existing)

            extension = "mp4" if command["kind"] == "video" else "jpg"
            evidence_dir = self.evidence_dir / "workflow" / execution_id
            evidence_dir.mkdir(parents=True, exist_ok=True)
            file_path = evidence_dir / f"{local_evidence_id}.{extension}"
            temporary = evidence_dir / f".{local_evidence_id}.{uuid.uuid4().hex}.tmp"
            temporary.write_bytes(command["bytes"])
            os.replace(temporary, file_path)
            asset_id = str(uuid.uuid4())
            self.connection.execute(
                """
                INSERT INTO workflow_evidence(
                    asset_id, assignment_id, execution_id, device_id,
                    local_evidence_id, node_id, evidence_key, kind, content_type,
                    duration_seconds, byte_size, sha256, file_path, captured_at,
                    upload_status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'synced', ?)
                """,
                (
                    asset_id,
                    assignment_id,
                    execution_id,
                    device_id,
                    local_evidence_id,
                    str(command["nodeId"]),
                    str(command["evidenceKey"]),
                    str(command["kind"]),
                    str(command["contentType"]),
                    int(command["durationSeconds"]),
                    int(command["byteSize"]),
                    str(command["sha256"]),
                    str(file_path),
                    str(command["capturedAt"]),
                    self.clock(),
                ),
            )
            self.connection.commit()
            row = self.connection.execute(
                "SELECT * FROM workflow_evidence WHERE asset_id = ?", (asset_id,)
            ).fetchone()
        return _workflow_evidence_row(row)

    def create_workflow_evidence_upload(
        self, device_id: str, command: Mapping[str, object]
    ) -> Optional[tuple[dict, bool]]:
        assignment_id = str(command["assignmentId"])
        execution_id = str(command["executionId"])
        local_evidence_id = str(command["localEvidenceId"])
        with self.lock:
            execution = self.connection.execute(
                """
                SELECT execution_id FROM workflow_executions
                WHERE execution_id = ? AND assignment_id = ? AND device_id = ?
                """,
                (execution_id, assignment_id, device_id),
            ).fetchone()
            if not execution:
                return None
            existing = self.connection.execute(
                """
                SELECT * FROM workflow_evidence
                WHERE execution_id = ? AND local_evidence_id = ?
                """,
                (execution_id, local_evidence_id),
            ).fetchone()
            created = existing is None
            if existing:
                if not _matching_workflow_evidence_metadata(existing, command):
                    raise ValueError("workflow_evidence_conflict")
                asset_id = str(existing["asset_id"])
                upload = self.connection.execute(
                    "SELECT * FROM workflow_evidence_uploads WHERE asset_id = ?",
                    (asset_id,),
                ).fetchone()
                if upload:
                    if (
                        int(upload["chunk_size"]) != command["chunkSize"]
                        or int(upload["chunk_count"]) != command["chunkCount"]
                    ):
                        raise ValueError("workflow_evidence_upload_conflict")
                    if upload["cancelled_at"] is not None:
                        now = self.clock()
                        self.connection.execute(
                            "UPDATE workflow_evidence SET upload_status = 'uploading' WHERE asset_id = ?",
                            (asset_id,),
                        )
                        self.connection.execute(
                            """
                            UPDATE workflow_evidence_uploads
                            SET updated_at = ?, completed_at = NULL, cancelled_at = NULL
                            WHERE upload_id = ?
                            """,
                            (now, str(upload["upload_id"])),
                        )
                        self.connection.commit()
                        existing = self.connection.execute(
                            "SELECT * FROM workflow_evidence WHERE asset_id = ?",
                            (asset_id,),
                        ).fetchone()
                        upload = self.connection.execute(
                            "SELECT * FROM workflow_evidence_uploads WHERE upload_id = ?",
                            (str(upload["upload_id"]),),
                        ).fetchone()
                    return self._workflow_evidence_upload_state(existing, upload), False
                if str(existing["upload_status"]) != "synced":
                    raise ValueError("workflow_evidence_upload_conflict")
            else:
                extension = "mp4" if command["kind"] == "video" else "jpg"
                evidence_dir = self.evidence_dir / "workflow" / execution_id
                evidence_dir.mkdir(parents=True, exist_ok=True)
                asset_id = str(uuid.uuid4())
                file_path = evidence_dir / f"{local_evidence_id}.{extension}"
                self.connection.execute(
                    """
                    INSERT INTO workflow_evidence(
                        asset_id, assignment_id, execution_id, device_id,
                        local_evidence_id, node_id, evidence_key, kind, content_type,
                        duration_seconds, byte_size, sha256, file_path, captured_at,
                        upload_status, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'uploading', ?)
                    """,
                    (
                        asset_id,
                        assignment_id,
                        execution_id,
                        device_id,
                        local_evidence_id,
                        str(command["nodeId"]),
                        str(command["evidenceKey"]),
                        str(command["kind"]),
                        str(command["contentType"]),
                        int(command["durationSeconds"]),
                        int(command["byteSize"]),
                        str(command["sha256"]),
                        str(file_path),
                        str(command["capturedAt"]),
                        self.clock(),
                    ),
                )
                existing = self.connection.execute(
                    "SELECT * FROM workflow_evidence WHERE asset_id = ?", (asset_id,)
                ).fetchone()

            upload_id = str(uuid.uuid4())
            now = self.clock()
            completed_at = now if str(existing["upload_status"]) == "synced" else None
            self.connection.execute(
                """
                INSERT INTO workflow_evidence_uploads(
                    upload_id, asset_id, device_id, chunk_size, chunk_count,
                    created_at, updated_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    upload_id,
                    asset_id,
                    device_id,
                    int(command["chunkSize"]),
                    int(command["chunkCount"]),
                    now,
                    now,
                    completed_at,
                ),
            )
            self.connection.commit()
            upload = self.connection.execute(
                "SELECT * FROM workflow_evidence_uploads WHERE upload_id = ?",
                (upload_id,),
            ).fetchone()
            return self._workflow_evidence_upload_state(existing, upload), created

    def store_workflow_evidence_chunk(
        self, device_id: str, command: Mapping[str, object]
    ) -> Optional[dict]:
        upload_id = str(command["uploadId"])
        chunk_index = int(command["chunkIndex"])
        with self.lock:
            joined = self.connection.execute(
                """
                SELECT u.*, e.execution_id, e.byte_size AS evidence_byte_size,
                       e.upload_status
                FROM workflow_evidence_uploads u
                JOIN workflow_evidence e ON e.asset_id = u.asset_id
                WHERE u.upload_id = ? AND u.device_id = ? AND e.device_id = ?
                """,
                (upload_id, device_id, device_id),
            ).fetchone()
            if not joined:
                return None
            if joined["cancelled_at"] is not None:
                raise ValueError("workflow_evidence_upload_cancelled")
            if str(joined["upload_status"]) == "synced":
                raise ValueError("workflow_evidence_upload_completed")
            if int(joined["chunk_count"]) != command["chunkCount"]:
                raise ValueError("workflow_evidence_chunk_conflict")
            expected_size = _workflow_evidence_expected_chunk_size(
                int(joined["evidence_byte_size"]),
                int(joined["chunk_size"]),
                int(joined["chunk_count"]),
                chunk_index,
            )
            if expected_size != command["chunkByteSize"]:
                raise ValueError("workflow_evidence_chunk_size_mismatch")
            existing = self.connection.execute(
                """
                SELECT * FROM workflow_evidence_parts
                WHERE upload_id = ? AND chunk_index = ?
                """,
                (upload_id, chunk_index),
            ).fetchone()
            if existing and (
                int(existing["byte_size"]) != command["chunkByteSize"]
                or str(existing["sha256"]) != command["chunkSha256"]
            ):
                raise ValueError("workflow_evidence_chunk_conflict")

            part_dir = (
                self.evidence_dir
                / "workflow"
                / str(joined["execution_id"])
                / ".parts"
                / upload_id
            )
            part_dir.mkdir(parents=True, exist_ok=True)
            part_path = part_dir / f"{chunk_index:08d}.part"
            temporary = part_dir / f".{chunk_index:08d}.{uuid.uuid4().hex}.tmp"
            temporary.write_bytes(command["bytes"])
            os.replace(temporary, part_path)
            now = self.clock()
            if existing:
                self.connection.execute(
                    """
                    UPDATE workflow_evidence_parts
                    SET file_path = ?, created_at = ?
                    WHERE upload_id = ? AND chunk_index = ?
                    """,
                    (str(part_path), now, upload_id, chunk_index),
                )
            else:
                self.connection.execute(
                    """
                    INSERT INTO workflow_evidence_parts(
                        upload_id, asset_id, chunk_index, byte_size, sha256,
                        file_path, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        upload_id,
                        str(joined["asset_id"]),
                        chunk_index,
                        int(command["chunkByteSize"]),
                        str(command["chunkSha256"]),
                        str(part_path),
                        now,
                    ),
                )
            self.connection.execute(
                "UPDATE workflow_evidence_uploads SET updated_at = ? WHERE upload_id = ?",
                (now, upload_id),
            )
            self.connection.commit()
            evidence = self.connection.execute(
                "SELECT * FROM workflow_evidence WHERE asset_id = ?",
                (str(joined["asset_id"]),),
            ).fetchone()
            upload = self.connection.execute(
                "SELECT * FROM workflow_evidence_uploads WHERE upload_id = ?",
                (upload_id,),
            ).fetchone()
            return self._workflow_evidence_upload_state(evidence, upload)

    def complete_workflow_evidence_upload(
        self, device_id: str, command: Mapping[str, object]
    ) -> Optional[tuple[dict, bool]]:
        upload_id = str(command["uploadId"])
        part_paths: list[Path] = []
        part_dir: Optional[Path] = None
        with self.lock:
            joined = self.connection.execute(
                """
                SELECT u.*, e.*
                FROM workflow_evidence_uploads u
                JOIN workflow_evidence e ON e.asset_id = u.asset_id
                WHERE u.upload_id = ? AND u.device_id = ? AND e.device_id = ?
                """,
                (upload_id, device_id, device_id),
            ).fetchone()
            if not joined:
                return None
            if joined["cancelled_at"] is not None:
                raise ValueError("workflow_evidence_upload_cancelled")
            if (
                int(joined["chunk_count"]) != command["chunkCount"]
                or str(joined["sha256"]) != command["sha256"]
            ):
                raise ValueError("workflow_evidence_upload_conflict")
            if str(joined["upload_status"]) == "synced":
                final_path = Path(str(joined["file_path"]))
                if not final_path.is_file():
                    raise ValueError("workflow_evidence_file_missing")
                return _workflow_evidence_row(joined), False

            parts = self.connection.execute(
                """
                SELECT * FROM workflow_evidence_parts
                WHERE upload_id = ? ORDER BY chunk_index ASC
                """,
                (upload_id,),
            ).fetchall()
            chunk_count = int(joined["chunk_count"])
            if len(parts) != chunk_count or [int(row["chunk_index"]) for row in parts] != list(
                range(chunk_count)
            ):
                raise ValueError("workflow_evidence_chunks_missing")

            final_path = Path(str(joined["file_path"]))
            final_path.parent.mkdir(parents=True, exist_ok=True)
            temporary = final_path.parent / f".{final_path.name}.{uuid.uuid4().hex}.tmp"
            digest = hashlib.sha256()
            total_size = 0
            try:
                with temporary.open("wb") as output:
                    for part in parts:
                        part_path = Path(str(part["file_path"]))
                        content = part_path.read_bytes()
                        if (
                            len(content) != int(part["byte_size"])
                            or _sha256(content) != str(part["sha256"])
                        ):
                            raise ValueError("workflow_evidence_chunk_corrupt")
                        output.write(content)
                        digest.update(content)
                        total_size += len(content)
                        part_paths.append(part_path)
                if (
                    total_size != int(joined["byte_size"])
                    or digest.hexdigest() != str(joined["sha256"])
                    or not _valid_workflow_media_file(
                        str(joined["kind"]), temporary, total_size
                    )
                ):
                    raise ValueError("workflow_evidence_digest_mismatch")
                os.replace(temporary, final_path)
            except BaseException:
                temporary.unlink(missing_ok=True)
                raise

            now = self.clock()
            self.connection.execute(
                "UPDATE workflow_evidence SET upload_status = 'synced' WHERE asset_id = ?",
                (str(joined["asset_id"]),),
            )
            self.connection.execute(
                """
                UPDATE workflow_evidence_uploads
                SET updated_at = ?, completed_at = ?
                WHERE upload_id = ?
                """,
                (now, now, upload_id),
            )
            self.connection.execute(
                "DELETE FROM workflow_evidence_parts WHERE upload_id = ?",
                (upload_id,),
            )
            self.connection.commit()
            row = self.connection.execute(
                "SELECT * FROM workflow_evidence WHERE asset_id = ?",
                (str(joined["asset_id"]),),
            ).fetchone()
            if part_paths:
                part_dir = part_paths[0].parent

        for path in part_paths:
            path.unlink(missing_ok=True)
        if part_dir is not None:
            try:
                part_dir.rmdir()
                part_dir.parent.rmdir()
            except OSError:
                pass
        return _workflow_evidence_row(row), True

    def cancel_workflow_evidence_upload(
        self, device_id: str, command: Mapping[str, object]
    ) -> Optional[dict]:
        upload_id = str(command["uploadId"])
        asset_id = str(command["assetId"])
        part_paths: list[Path] = []
        part_dir: Optional[Path] = None
        with self.lock:
            joined = self.connection.execute(
                """
                SELECT u.*, e.upload_status
                FROM workflow_evidence_uploads u
                JOIN workflow_evidence e ON e.asset_id = u.asset_id
                WHERE u.upload_id = ? AND u.asset_id = ?
                  AND u.device_id = ? AND e.device_id = ?
                """,
                (upload_id, asset_id, device_id, device_id),
            ).fetchone()
            if not joined:
                return None
            if joined["completed_at"] is not None or str(joined["upload_status"]) == "synced":
                raise ValueError("workflow_evidence_upload_completed")
            parts = self.connection.execute(
                """
                SELECT file_path FROM workflow_evidence_parts
                WHERE upload_id = ? ORDER BY chunk_index ASC
                """,
                (upload_id,),
            ).fetchall()
            part_paths = [Path(str(row["file_path"])) for row in parts]
            try:
                for path in part_paths:
                    path.unlink(missing_ok=True)
            except OSError as error:
                raise ValueError("workflow_evidence_storage_failed") from error
            now = float(joined["cancelled_at"]) if joined["cancelled_at"] is not None else self.clock()
            self.connection.execute(
                "UPDATE workflow_evidence SET upload_status = 'cancelled' WHERE asset_id = ?",
                (asset_id,),
            )
            self.connection.execute(
                """
                UPDATE workflow_evidence_uploads
                SET updated_at = ?, completed_at = NULL, cancelled_at = ?
                WHERE upload_id = ?
                """,
                (now, now, upload_id),
            )
            self.connection.execute(
                "DELETE FROM workflow_evidence_parts WHERE upload_id = ?",
                (upload_id,),
            )
            self.connection.commit()
            evidence = self.connection.execute(
                "SELECT * FROM workflow_evidence WHERE asset_id = ?",
                (asset_id,),
            ).fetchone()
            upload = self.connection.execute(
                "SELECT * FROM workflow_evidence_uploads WHERE upload_id = ?",
                (upload_id,),
            ).fetchone()
            if part_paths:
                part_dir = part_paths[0].parent

        if part_dir is not None:
            try:
                part_dir.rmdir()
                part_dir.parent.rmdir()
            except OSError:
                pass
        return self._workflow_evidence_upload_state(evidence, upload)

    def _workflow_evidence_upload_state(
        self, evidence: sqlite3.Row, upload: sqlite3.Row
    ) -> dict:
        received = [
            int(row["chunk_index"])
            for row in self.connection.execute(
                """
                SELECT chunk_index FROM workflow_evidence_parts
                WHERE upload_id = ? ORDER BY chunk_index ASC
                """,
                (str(upload["upload_id"]),),
            ).fetchall()
        ]
        chunk_count = int(upload["chunk_count"])
        received_set = set(received)
        next_chunk = next(
            (index for index in range(chunk_count) if index not in received_set),
            chunk_count,
        )
        response = _workflow_evidence_row(evidence)
        response.update(
            {
                "uploadId": str(upload["upload_id"]),
                "chunkSize": int(upload["chunk_size"]),
                "chunkCount": chunk_count,
                "receivedChunks": received,
                "nextChunkIndex": next_chunk,
            }
        )
        if upload["cancelled_at"] is not None:
            response["uploadStatus"] = "cancelled"
        return response


class MvsWorkOrderRetryWorker:
    def __init__(
        self,
        store: SqliteStore,
        connector: MvsWorkOrderConnector,
        *,
        refresh_interval_seconds: float = 5.0,
        retry_base_seconds: float = 5.0,
        retry_stale_seconds: float = 30.0,
        max_attempts: int = 5,
        batch_size: int = 10,
    ):
        if refresh_interval_seconds <= 0 or refresh_interval_seconds > 300:
            raise ValueError("mvs_retry_interval_invalid")
        if retry_base_seconds < 0 or retry_base_seconds > 3600:
            raise ValueError("mvs_retry_base_invalid")
        if retry_stale_seconds < 0 or retry_stale_seconds > 3600:
            raise ValueError("mvs_retry_stale_invalid")
        if max_attempts < 2 or max_attempts > 20 or batch_size < 1 or batch_size > 100:
            raise ValueError("mvs_retry_policy_invalid")
        self.store = store
        self.connector = connector
        self.refresh_interval_seconds = refresh_interval_seconds
        self.retry_base_seconds = retry_base_seconds
        self.retry_stale_seconds = retry_stale_seconds
        self.max_attempts = max_attempts
        self.batch_size = batch_size
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def run_once(self) -> int:
        operations = self.store.claim_retryable_mvs_work_order_operations(
            limit=self.batch_size,
            max_attempts=self.max_attempts,
            retry_base_seconds=self.retry_base_seconds,
            retry_stale_seconds=self.retry_stale_seconds,
        )
        for operation in operations:
            self._retry(operation)
        return len(operations)

    def start(self) -> None:
        if self._thread is not None and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._run,
            name="mvs-work-order-retry",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        thread = self._thread
        if thread is not None:
            thread.join(timeout=max(1.0, self.refresh_interval_seconds + 1.0))
        self._thread = None

    def _run(self) -> None:
        while not self._stop_event.is_set():
            try:
                self.run_once()
            except Exception:
                pass
            self._stop_event.wait(self.refresh_interval_seconds)

    def _retry(self, operation: Mapping[str, object]) -> None:
        operation_id = str(operation.get("id") or "")
        device_id = str(operation.get("deviceId") or "")
        command_type = str(operation.get("commandType") or "")
        direction = {
            "mvs_checkin_in": "in",
            "mvs_checkin_out": "out",
        }.get(command_type)
        request = operation.get("request")
        if direction is None or not isinstance(request, Mapping):
            body = {
                "ok": False,
                "operationId": operation_id,
                "duplicate": False,
                "retryable": False,
                "error": "mvs_operation_request_invalid",
            }
            self.store.finish_mvs_work_order_operation(
                operation_id,
                device_id,
                "failed",
                400,
                body,
                "mvs_operation_request_invalid",
            )
            return
        connector_payload = {
            key: request[key]
            for key in ("lat", "lng", "address", "formId", "formContent")
            if key in request
        }
        try:
            result = self.connector.submit_checkin(
                str(operation.get("orderId") or ""),
                direction,
                connector_payload,
                idempotency_key=str(operation.get("idempotencyKey") or ""),
                trace_id=str(operation.get("traceId") or ""),
            )
            body = {
                "ok": True,
                "operationId": operation_id,
                "duplicate": False,
                "retryable": False,
                "result": result,
            }
            self.store.finish_mvs_work_order_operation(
                operation_id, device_id, "succeeded", 200, body
            )
        except MvsProviderUnavailable:
            body = {
                "ok": False,
                "operationId": operation_id,
                "duplicate": False,
                "retryable": True,
                "error": "mvs_provider_unavailable",
            }
            self.store.finish_mvs_work_order_operation(
                operation_id,
                device_id,
                "retryable",
                503,
                body,
                "mvs_provider_unavailable",
            )
        except (PermissionError, ValueError):
            body = {
                "ok": False,
                "operationId": operation_id,
                "duplicate": False,
                "retryable": False,
                "error": "mvs_retry_rejected",
            }
            self.store.finish_mvs_work_order_operation(
                operation_id,
                device_id,
                "failed",
                400,
                body,
                "mvs_retry_rejected",
            )


class DashscopeProvider:
    SYSTEM_PROMPT = (
        "你是华方智联研发的叮当运维AI模型，面向现场运维人员。"
        "直接、简洁地回答当前问题；涉及操作时每轮只给一个可立即执行并验证的原子步骤。"
        "只依据用户提供的现场事实、图片和当前上下文，不把推测当成结论。"
        "执行当前 executionContext 中已匹配且已授权的 Skill 和项目指令；"
        "不得套用未出现在当前 executionContext 中的规则。"
        "不得自行接单、提交工单、关闭任务、呼叫专家或执行高风险动作。"
        "不要透露底层模型、供应商、密钥或接口信息。"
    )

    def __init__(
        self,
        config: GatewayConfig,
        timeout_seconds: int = 45,
        opener: Callable[[urllib.request.Request, int], object] = _open_provider_request,
    ):
        self.config = config
        self.timeout_seconds = timeout_seconds
        self.opener = opener

    def stream_chat(
        self, context: ExecutionContext, image_data_url: str, trace_id: str
    ) -> Iterator[str]:
        context_json = json.dumps(
            {"executionContext": context.to_model_payload()},
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        content: object = [{"type": "text", "text": context_json}]
        if image_data_url:
            content.append(
                {"type": "image_url", "image_url": {"url": image_data_url}}
            )
        payload = json.dumps(
            {
                "model": self.config.provider_model,
                "stream": True,
                "enable_thinking": False,
                "max_tokens": 600,
                "messages": [
                    {"role": "system", "content": self.SYSTEM_PROMPT},
                    {"role": "user", "content": content},
                ],
            },
            ensure_ascii=False,
        ).encode("utf-8")
        request = urllib.request.Request(
            self.config.provider_base_url.rstrip("/") + "/chat/completions",
            data=payload,
            headers={
                "Authorization": "Bearer " + self.config.provider_api_key,
                "Content-Type": "application/json",
                "X-Request-Id": trace_id,
            },
            method="POST",
        )
        try:
            response = self.opener(request, self.timeout_seconds)
        except urllib.error.HTTPError as error:
            error.read()
            raise ProviderUnavailable(f"provider_http_{error.code}") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise ProviderUnavailable("provider_network_unavailable") from None

        def chunks() -> Iterator[str]:
            try:
                for raw_line in response:
                    line = raw_line.decode("utf-8", "replace").strip()
                    if not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if not data:
                        continue
                    if data == "[DONE]":
                        break
                    try:
                        event = json.loads(data)
                        choices = event.get("choices") or []
                        delta = (choices[0].get("delta") or {}).get("content") if choices else ""
                    except (ValueError, AttributeError, IndexError):
                        continue
                    if isinstance(delta, str) and delta:
                        yield delta
            except (urllib.error.URLError, TimeoutError, OSError):
                raise ProviderUnavailable("provider_stream_interrupted") from None
            finally:
                response.close()

        return chunks()


class GatewayService:
    def __init__(
        self,
        config: GatewayConfig,
        store: SqliteStore,
        provider: object,
        clock: Callable[[], float] = time.time,
        voiceprint_service: Optional[VoiceprintLifecycleService] = None,
        content_manifest_sync_worker: Optional[object] = None,
        control_plane_sync_worker: Optional[object] = None,
        mvs_connector: Optional[MvsWorkOrderConnector] = None,
        mvs_write_enabled: bool = False,
    ):
        self.config = config
        self.store = store
        self.provider = provider
        self.clock = clock
        self.voiceprint_service = voiceprint_service
        self.content_manifest_sync_worker = content_manifest_sync_worker
        self.control_plane_sync_worker = control_plane_sync_worker
        self.mvs_connector = mvs_connector
        self.mvs_write_enabled = bool(mvs_write_enabled)
        self.asr_connector: Callable[[], socket.socket] = lambda: connect_dashscope_asr(
            self.config.asr_url,
            self.config.asr_api_key or self.config.provider_api_key,
        )

    def health(self) -> GatewayResponse:
        return _json_response(200, {"ok": True, "service": "dingdang-v9-gateway"})

    def readiness(self) -> GatewayResponse:
        try:
            storage = self.store.readiness()
        except (OSError, sqlite3.DatabaseError):
            return _json_response(
                503,
                {"ok": False, "service": "dingdang-v9-gateway", "error": "storage_unavailable"},
                {"Cache-Control": "no-store"},
            )
        return _json_response(
            200,
            {"ok": True, "service": "dingdang-v9-gateway", **storage},
            {"Cache-Control": "no-store"},
        )

    def parse_knowledge_document(
        self, authorization: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        if not self.config.knowledge_parser_token_hash:
            return _json_response(
                503,
                {"ok": False, "error": "knowledge_document_parser_unavailable"},
                {"Cache-Control": "no-store"},
            )
        token = _bearer_token(authorization)
        candidate_hash = _sha256(token.encode("utf-8")) if token else ""
        if not candidate_hash or not hmac.compare_digest(
            candidate_hash, self.config.knowledge_parser_token_hash
        ):
            return _json_response(
                401,
                {"ok": False, "error": "unauthorized"},
                {"Cache-Control": "no-store"},
            )
        if set(payload) != {"fileName", "contentType", "sha256", "dataBase64"}:
            return _json_response(
                400,
                {"ok": False, "error": "invalid_knowledge_document_request"},
                {"Cache-Control": "no-store"},
            )
        try:
            data = base64.b64decode(str(payload.get("dataBase64") or ""), validate=True)
        except (ValueError, TypeError):
            return _json_response(
                400,
                {"ok": False, "error": "knowledge_document_base64_invalid"},
                {"Cache-Control": "no-store"},
            )
        try:
            parsed = parse_binary_knowledge_document(
                str(payload.get("fileName") or ""),
                str(payload.get("contentType") or ""),
                data,
                str(payload.get("sha256") or ""),
            )
        except KnowledgeDocumentParserError as error:
            status = 413 if error.code in {
                "knowledge_document_too_large",
                "knowledge_document_text_too_large",
                "knowledge_document_page_limit_exceeded",
                "knowledge_document_archive_limit_exceeded",
            } else 503 if error.code == "knowledge_document_processor_unavailable" else 422
            return _json_response(
                status,
                {"ok": False, "error": error.code},
                {"Cache-Control": "no-store"},
            )
        return _json_response(
            200,
            {
                "ok": True,
                "content": parsed.content,
                "contentSha256": parsed.content_sha256,
            },
            {"Cache-Control": "no-store"},
        )

    def exchange_device_session(self, authorization: str) -> GatewayResponse:
        bootstrap = _bearer_token(authorization)
        candidate_hash = _sha256(bootstrap.encode("utf-8")) if bootstrap else ""
        if not candidate_hash or not hmac.compare_digest(
            candidate_hash, self.config.bootstrap_token_hash
        ):
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        access_token = secrets.token_urlsafe(32)
        expires_at = self.clock() + self.config.session_ttl_seconds
        self.store.issue_device_session(self.config.device_id, access_token, expires_at)
        return _json_response(
            201,
            {
                "ok": True,
                "accessToken": access_token,
                "expiresAt": _iso_timestamp(expires_at),
                "expiresAtEpochSeconds": expires_at,
                "tokenType": "Bearer",
            },
            {"Cache-Control": "no-store"},
        )

    def upload_image(
        self, authorization: str, requested_session_id: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        encoded = str(payload.get("image_base64") or payload.get("imageBase64") or "").strip()
        if not encoded:
            return _json_response(400, {"ok": False, "error": "image_base64_required"})
        try:
            content = base64.b64decode(encoded, validate=True)
        except (ValueError, TypeError):
            return _json_response(400, {"ok": False, "error": "invalid_image_base64"})
        if len(content) > self.config.max_image_bytes:
            return _json_response(413, {"ok": False, "error": "image_too_large"})
        if len(content) < 4 or not content.startswith(b"\xff\xd8") or not content.endswith(b"\xff\xd9"):
            return _json_response(400, {"ok": False, "error": "invalid_jpeg"})
        image_kind = str(payload.get("image_kind") or payload.get("imageKind") or "field_photo")
        if image_kind not in IMAGE_KINDS:
            return _json_response(400, {"ok": False, "error": "unsupported_image_kind"})
        try:
            session_id = self.store.ensure_chat_session(requested_session_id, device_id)
        except ValueError:
            return _json_response(400, {"ok": False, "error": "invalid_session_id"})
        except PermissionError:
            return _json_response(403, {"ok": False, "error": "session_forbidden"})
        image = self.store.save_image(session_id, device_id, image_kind, content)
        return _json_response(
            201,
            {
                "ok": True,
                "session_id": session_id,
                "image_id": image["id"],
                "image_bytes": image["byte_count"],
                "image_sha256": image["sha256"],
            },
        )

    def append_device_event(
        self, authorization: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        if not _valid_device_event(payload):
            return _json_response(400, {"ok": False, "error": "invalid_event"})
        try:
            duplicate = self.store.append_device_event(device_id, payload)
        except ValueError as error:
            error_code = str(error.args[0]) if error.args else "event_append_failed"
            if error_code == "idempotency_conflict":
                return _json_response(
                    409,
                    {"ok": False, "error": error_code},
                    {"Cache-Control": "no-store"},
                )
            raise
        if not duplicate:
            if self.control_plane_sync_worker is not None:
                self.control_plane_sync_worker.trigger()
            elif (
                str(payload["eventType"]).strip() == "task_started"
                and self.content_manifest_sync_worker is not None
            ):
                self.content_manifest_sync_worker.trigger(
                    str(payload["localProjectId"]).strip()
                )
        return _json_response(202, {"ok": True, "duplicate": duplicate})

    def list_device_skills(
        self,
        authorization: str,
        local_project_id: str = "",
        local_task_id: str = "",
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        try:
            items = self.store.execution_context.list_authorized_skills(
                self.config.organization_id,
                self.config.user_id,
                device_id,
                local_project_id,
                local_task_id,
            )
        except (LookupError, PermissionError, ValueError) as error:
            return self._context_error_response(error)
        return _json_response(
            200,
            {"items": items},
            {"Cache-Control": "no-store"},
        )

    def get_device_content_manifest(
        self,
        authorization: str,
        local_project_id: str,
        if_none_match: str = "",
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        try:
            manifest = self.store.execution_context.device_content_manifest(
                self.config.organization_id,
                self.config.user_id,
                device_id,
                local_project_id,
            )
        except (LookupError, PermissionError, ValueError) as error:
            return self._context_error_response(error)
        etag = str(manifest["etag"])
        headers = {
            "Cache-Control": "private, max-age=60, must-revalidate",
            "ETag": f'"{etag}"',
            "X-Manifest-Version": str(manifest["manifestVersion"]),
            "X-Manifest-Expires-At": str(manifest["expiresAt"]),
        }
        if _etag_matches(if_none_match, etag):
            return GatewayResponse(304, headers)
        return _json_response(200, manifest, headers)

    def set_task_skill(
        self,
        authorization: str,
        local_task_id: str,
        payload: Mapping[str, object],
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        allowed = {
            "action",
            "localProjectId",
            "skillVersionId",
            "confirmation",
            "idempotencyKey",
        }
        if set(payload) - allowed or not _valid_identifier(local_task_id, 200):
            return _json_response(400, {"ok": False, "error": "invalid_skill_command"})
        action = str(payload.get("action") or "").strip()
        local_project_id = str(payload.get("localProjectId") or "").strip()
        idempotency_key = str(payload.get("idempotencyKey") or "").strip()
        expected_confirmation = {
            "activate": "ACTIVATE_SKILL",
            "deactivate": "DEACTIVATE_SKILL",
        }.get(action)
        if not expected_confirmation:
            return _json_response(400, {"ok": False, "error": "skill_action_invalid"})
        if str(payload.get("confirmation") or "") != expected_confirmation:
            return _json_response(400, {"ok": False, "error": "confirmation_required"})
        if not _valid_identifier(local_project_id, 200) or not _valid_identifier(
            idempotency_key, 200
        ):
            return _json_response(400, {"ok": False, "error": "invalid_skill_command"})
        skill_version_id = str(payload.get("skillVersionId") or "").strip()
        if action == "activate" and not _valid_identifier(skill_version_id, 200):
            return _json_response(400, {"ok": False, "error": "skill_version_required"})

        def operation() -> dict:
            if action == "activate":
                skill = self.store.execution_context.activate_task_skill(
                    self.config.organization_id,
                    self.config.user_id,
                    device_id,
                    local_task_id,
                    skill_version_id,
                    local_project_id,
                )
                return {"active": True, "skill": skill.to_catalog_payload()}
            return self.store.execution_context.deactivate_task_skill(
                self.config.organization_id,
                self.config.user_id,
                device_id,
                local_project_id,
                local_task_id,
            )

        return self._run_context_command(
            device_id,
            idempotency_key,
            "task_skill_" + action,
            payload,
            operation,
        )

    def list_device_projects(self, authorization: str) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        try:
            items = self.store.execution_context.list_projects(
                self.config.organization_id, device_id
            )
        except (LookupError, PermissionError, ValueError) as error:
            return self._context_error_response(error)
        return _json_response(
            200,
            {"items": items},
            {"Cache-Control": "no-store"},
        )

    def list_mvs_work_orders(
        self,
        authorization: str,
        view: str,
        limit: int,
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        if self.mvs_connector is None:
            return _json_response(503, {"ok": False, "error": "mvs_unavailable"})
        try:
            items = self.mvs_connector.list_work_orders(view, limit)
        except ValueError as error:
            return self._mvs_error_response(error)
        except MvsProviderUnavailable as error:
            return self._mvs_error_response(error)
        return _json_response(
            200,
            {"items": items, "view": view},
            {"Cache-Control": "no-store"},
        )

    def get_mvs_work_order_resource(
        self,
        authorization: str,
        order_id: str,
        resource: str,
        query: Mapping[str, object],
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        if self.mvs_connector is None:
            return _json_response(503, {"ok": False, "error": "mvs_unavailable"})
        normalized_resource = str(resource or "detail").strip()
        try:
            if normalized_resource == "detail":
                result = self.mvs_connector.get_work_order_detail(order_id)
            elif normalized_resource == "node_form":
                result = self.mvs_connector.get_node_form(order_id)
            elif normalized_resource == "flow_records":
                result = self.mvs_connector.get_flow_records(order_id)
            elif normalized_resource == "execution_records":
                result = self.mvs_connector.get_execution_records(order_id)
            elif normalized_resource == "sop_tree":
                result = self.mvs_connector.get_sop_tree(order_id)
            elif normalized_resource == "attachments":
                result = self.mvs_connector.list_attachments(order_id)
            elif normalized_resource == "checkins":
                result = self.mvs_connector.list_checkins(order_id)
            elif normalized_resource == "checkin_form":
                result = self.mvs_connector.get_checkin_form(
                    order_id, str(query.get("direction") or "")
                )
            elif normalized_resource == "checkin_required":
                result = self.mvs_connector.get_checkin_required(
                    order_id, str(query.get("definitionId") or "")
                )
            elif normalized_resource == "task_operations":
                result = self.mvs_connector.get_task_operations(order_id)
                self.store.record_mvs_work_order_resource_audit_event(
                    device_id,
                    order_id,
                    normalized_resource,
                    "allowed",
                    int(result.get("filteredCount", 0)),
                )
            else:
                return _json_response(
                    400, {"ok": False, "error": "mvs_resource_not_allowed"}
                )
        except (ValueError, PermissionError, MvsProviderUnavailable) as error:
            return self._mvs_error_response(error)
        return _json_response(
            200,
            {
                "orderId": str(order_id),
                "resourceType": normalized_resource,
                "resource": result,
            },
            {"Cache-Control": "no-store"},
        )

    def submit_mvs_checkin(
        self,
        authorization: str,
        order_id: str,
        payload: Mapping[str, object],
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        if self.mvs_connector is None:
            return _json_response(503, {"ok": False, "error": "mvs_unavailable"})
        if not self.mvs_write_enabled:
            return _json_response(
                503,
                {
                    "ok": False,
                    "error": "mvs_write_not_enabled",
                    "retryable": False,
                },
                {"Cache-Control": "no-store"},
            )
        allowed = {
            "direction",
            "confirmation",
            "idempotencyKey",
            "traceId",
            "lat",
            "lng",
            "address",
            "formId",
            "formContent",
        }
        if set(payload) - allowed:
            return _json_response(
                400, {"ok": False, "error": "mvs_checkin_payload_invalid"}
            )
        direction = str(payload.get("direction") or "").strip()
        expected_confirmation = {
            "in": "CONFIRM_MVS_CHECKIN",
            "out": "CONFIRM_MVS_CHECKOUT",
        }.get(direction)
        if expected_confirmation is None:
            return _json_response(
                400, {"ok": False, "error": "mvs_checkin_direction_invalid"}
            )
        if str(payload.get("confirmation") or "") != expected_confirmation:
            return _json_response(
                400, {"ok": False, "error": "confirmation_required"}
            )
        idempotency_key = str(payload.get("idempotencyKey") or "").strip()
        trace_id = str(payload.get("traceId") or "").strip()
        if not _valid_identifier(idempotency_key, 200) or not _valid_identifier(
            trace_id, 200
        ):
            return _json_response(
                400, {"ok": False, "error": "mvs_checkin_payload_invalid"}
            )
        command_payload = dict(payload)
        try:
            operation = self.store.begin_mvs_work_order_operation(
                device_id,
                idempotency_key,
                "mvs_checkin_" + direction,
                str(order_id),
                command_payload,
                trace_id,
            )
        except ValueError as error:
            return self._mvs_error_response(error)
        if operation["duplicate"]:
            body = dict(operation.get("response") or {})
            body["duplicate"] = True
            return _json_response(
                int(operation.get("httpStatus") or 503),
                body,
                {"Cache-Control": "no-store"},
            )
        connector_payload = {
            key: payload[key]
            for key in ("lat", "lng", "address", "formId", "formContent")
            if key in payload
        }
        try:
            result = self.mvs_connector.submit_checkin(
                order_id,
                direction,
                connector_payload,
                idempotency_key=idempotency_key,
                trace_id=trace_id,
            )
            body = {
                "ok": True,
                "operationId": operation["id"],
                "duplicate": False,
                "retryable": False,
                "result": result,
            }
            self.store.finish_mvs_work_order_operation(
                operation["id"], device_id, "succeeded", 200, body
            )
            return _json_response(200, body, {"Cache-Control": "no-store"})
        except MvsProviderUnavailable:
            body = {
                "ok": False,
                "operationId": operation["id"],
                "duplicate": False,
                "retryable": True,
                "error": "mvs_provider_unavailable",
            }
            self.store.finish_mvs_work_order_operation(
                operation["id"],
                device_id,
                "retryable",
                503,
                body,
                "mvs_provider_unavailable",
            )
            return _json_response(503, body, {"Cache-Control": "no-store"})
        except (ValueError, PermissionError) as error:
            response = self._mvs_error_response(error)
            body = dict(response.json_body or {})
            body.update(
                {
                    "operationId": operation["id"],
                    "duplicate": False,
                    "retryable": False,
                }
            )
            self.store.finish_mvs_work_order_operation(
                operation["id"],
                device_id,
                "rejected",
                response.status,
                body,
                str(body.get("error") or "mvs_checkin_rejected"),
            )
            return _json_response(
                response.status, body, {"Cache-Control": "no-store"}
            )

    @staticmethod
    def _mvs_error_response(error: Exception) -> GatewayResponse:
        error_code = str(error.args[0]) if error.args else "mvs_request_failed"
        if isinstance(error, PermissionError):
            status = 403
        elif isinstance(error, MvsProviderUnavailable):
            status = 503
            error_code = "mvs_provider_unavailable"
        elif error_code == "idempotency_conflict":
            status = 409
        else:
            status = 400
        return _json_response(
            status,
            {"ok": False, "error": error_code},
            {"Cache-Control": "no-store"},
        )

    def get_device_project(
        self, authorization: str, local_project_id: str
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        try:
            detail = self.store.execution_context.project_detail(
                self.config.organization_id,
                device_id,
                local_project_id,
            )
        except (LookupError, PermissionError, ValueError) as error:
            return self._context_error_response(error)
        return _json_response(200, detail, {"Cache-Control": "no-store"})

    def end_task_summary(
        self,
        authorization: str,
        local_task_id: str,
        payload: Mapping[str, object],
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        required = {
            "localProjectId",
            "taskStatus",
            "summary",
            "confirmedFacts",
            "excludedFacts",
            "risks",
            "expectedMemoryRevision",
            "confirmation",
            "idempotencyKey",
        }
        if set(payload) != required or not _valid_identifier(local_task_id, 200):
            if str(payload.get("confirmation") or "") != "END_TASK":
                return _json_response(400, {"ok": False, "error": "confirmation_required"})
            return _json_response(400, {"ok": False, "error": "invalid_task_summary"})
        if payload.get("confirmation") != "END_TASK":
            return _json_response(400, {"ok": False, "error": "confirmation_required"})
        idempotency_key = str(payload.get("idempotencyKey") or "").strip()
        if not _valid_identifier(idempotency_key, 200):
            return _json_response(400, {"ok": False, "error": "invalid_task_summary"})

        def operation() -> dict:
            return self.store.execution_context.end_task(
                self.config.organization_id,
                self.config.user_id,
                device_id,
                str(payload["localProjectId"]),
                local_task_id,
                str(payload["taskStatus"]),
                str(payload["summary"]),
                payload["expectedMemoryRevision"],
                payload["confirmedFacts"],
                payload["excludedFacts"],
                payload["risks"],
            )

        return self._run_context_command(
            device_id,
            idempotency_key,
            "task_end_summary",
            payload,
            operation,
        )

    def confirm_project_instruction(
        self,
        authorization: str,
        local_project_id: str,
        payload: Mapping[str, object],
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        required = {
            "instructionId",
            "expectedVersion",
            "status",
            "condition",
            "action",
            "exceptions",
            "sourceTraceId",
            "confirmation",
            "idempotencyKey",
        }
        if set(payload) != required or not _valid_identifier(local_project_id, 200):
            if str(payload.get("confirmation") or "") != "CONFIRM_PROJECT_INSTRUCTION":
                return _json_response(400, {"ok": False, "error": "confirmation_required"})
            return _json_response(400, {"ok": False, "error": "invalid_project_instruction"})
        if payload.get("confirmation") != "CONFIRM_PROJECT_INSTRUCTION":
            return _json_response(400, {"ok": False, "error": "confirmation_required"})
        idempotency_key = str(payload.get("idempotencyKey") or "").strip()
        if not _valid_identifier(idempotency_key, 200):
            return _json_response(400, {"ok": False, "error": "invalid_project_instruction"})

        def operation() -> dict:
            return self.store.execution_context.put_project_instruction(
                self.config.organization_id,
                self.config.user_id,
                device_id,
                local_project_id,
                payload["instructionId"],
                payload["expectedVersion"],
                str(payload["status"]),
                payload["condition"],
                str(payload["action"]),
                payload["exceptions"],
                str(payload["sourceTraceId"]),
            )

        return self._run_context_command(
            device_id,
            idempotency_key,
            "project_instruction",
            payload,
            operation,
        )

    def _run_context_command(
        self,
        device_id: str,
        idempotency_key: str,
        command_type: str,
        payload: Mapping[str, object],
        operation: Callable[[], dict],
    ) -> GatewayResponse:
        try:
            cached = self.store.load_device_context_command(
                device_id,
                idempotency_key,
                command_type,
                payload,
            )
            if cached is not None:
                return _json_response(
                    200,
                    dict(cached, duplicate=True),
                    {"Cache-Control": "no-store"},
                )
            result = operation()
            stored = dict(result, duplicate=False)
            self.store.save_device_context_command(
                device_id,
                idempotency_key,
                command_type,
                payload,
                stored,
            )
            return _json_response(200, stored, {"Cache-Control": "no-store"})
        except (LookupError, PermissionError, ValueError) as error:
            return self._context_error_response(error)

    def _context_error_response(self, error: Exception) -> GatewayResponse:
        error_code = str(error.args[0]) if error.args else "context_operation_failed"
        if isinstance(error, LookupError):
            status = 404
        elif isinstance(error, PermissionError):
            status = 403
        elif error_code in {
            "content_manifest_unavailable",
            "content_manifest_expired",
            "content_manifest_snapshot_missing",
            "required_knowledge_unavailable",
        }:
            status = 503
        elif error_code in {
            "idempotency_conflict",
            "project_identity_conflict",
            "project_identity_unbound",
            "project_instruction_no_change",
            "project_instruction_transition_invalid",
            "project_instruction_version_conflict",
            "project_memory_revision_conflict",
            "skill_not_active",
            "task_not_active",
            "task_skill_already_active",
        }:
            status = 409
        else:
            status = 400
        return _json_response(
            status,
            {"ok": False, "error": error_code},
            {"Cache-Control": "no-store"},
        )

    def voiceprint_profile(self, authorization: str) -> GatewayResponse:
        return self._voiceprint_device_call(authorization, "profile")

    def voiceprint_consent(
        self, authorization: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        return self._voiceprint_device_call(authorization, "consent", payload)

    def voiceprint_enroll_sample(
        self, authorization: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        return self._voiceprint_device_call(authorization, "enroll_sample", payload)

    def voiceprint_verify(
        self, authorization: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        return self._voiceprint_device_call(authorization, "verify", payload)

    def voiceprint_reenroll(
        self, authorization: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        return self._voiceprint_device_call(authorization, "reenroll", payload)

    def voiceprint_delete(
        self, authorization: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        return self._voiceprint_device_call(authorization, "delete", payload)

    def voiceprint_admin_revoke(
        self,
        authorization: str,
        profile_id: str,
        payload: Mapping[str, object],
    ) -> GatewayResponse:
        if self.voiceprint_service is None:
            return _json_response(503, {"ok": False, "error": "voiceprint_unavailable"})
        response = self.voiceprint_service.admin_revoke(
            authorization, profile_id, payload
        )
        return _voiceprint_gateway_response(response)

    def voiceprint_admin_list(self, authorization: str) -> GatewayResponse:
        if self.voiceprint_service is None:
            return _json_response(503, {"ok": False, "error": "voiceprint_unavailable"})
        return _voiceprint_gateway_response(
            self.voiceprint_service.admin_list(authorization)
        )

    def list_workflow_assignments(
        self, authorization: str, after_sequence: int, limit: int
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        if (
            after_sequence < 0
            or after_sequence > 9_007_199_254_740_991
            or limit < 1
            or limit > 100
        ):
            return _json_response(400, {"ok": False, "error": "invalid_cursor"})
        items = self.store.list_workflow_assignments(device_id, after_sequence, limit)
        next_sequence = max(
            [after_sequence] + [int(item["deliverySequence"]) for item in items]
        )
        return _json_response(200, {"items": items, "nextSequence": next_sequence})

    def get_workflow_package(
        self,
        authorization: str,
        assignment_id: str,
        app_version_code: object,
        workflow_schema_version: object,
        capability_header: str,
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        capabilities = _workflow_capability_declaration(
            app_version_code, workflow_schema_version, capability_header
        )
        if capabilities is None:
            return _json_response(
                400, {"ok": False, "error": "device_capabilities_required"}
            )
        if not _valid_identifier(assignment_id, 200):
            return _json_response(404, {"ok": False, "error": "not_found"})
        record = self.store.load_workflow_package(device_id, assignment_id)
        if record is None:
            return _json_response(404, {"ok": False, "error": "not_found"})
        if record["mode"] == "none" or not record["workflowVersionId"]:
            return _json_response(409, {"ok": False, "error": "workflow_not_required"})
        package = record["package"]
        if not _valid_workflow_package(package, assignment_id, record["workflowVersionId"]):
            return _json_response(
                502, {"ok": False, "error": "workflow_package_invalid"}
            )
        app_version, schema_version, device_capabilities = capabilities
        required_capabilities = package["requiredCapabilities"]
        if (
            int(package["schemaVersion"]) > schema_version
            or int(package["minAppVersionCode"]) > app_version
            or any(value not in device_capabilities for value in required_capabilities)
        ):
            return _json_response(
                409,
                {
                    "ok": False,
                    "error": "workflow_incompatible",
                    "required": {
                        "schemaVersion": package["schemaVersion"],
                        "minAppVersionCode": package["minAppVersionCode"],
                        "capabilities": required_capabilities,
                    },
                },
            )
        response = dict(package)
        response["assignmentId"] = assignment_id
        return _json_response(200, response, {"Cache-Control": "no-store"})

    def report_workflow_assignment_status(
        self, authorization: str, assignment_id: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        if not _valid_assignment_status_command(payload):
            return _json_response(400, {"ok": False, "error": "invalid_status_report"})
        try:
            item = self.store.report_workflow_assignment_status(
                device_id, assignment_id, payload
            )
        except ValueError as error:
            return _json_response(409, {"ok": False, "error": str(error)})
        if item is None:
            return _json_response(404, {"ok": False, "error": "not_found"})
        return _json_response(200, item)

    def start_workflow_execution(
        self, authorization: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        if not _valid_execution_start_command(payload):
            return _json_response(400, {"ok": False, "error": "invalid_execution_start"})
        try:
            item = self.store.start_workflow_execution(device_id, payload)
        except ValueError as error:
            return _json_response(409, {"ok": False, "error": str(error)})
        if item is None:
            return _json_response(404, {"ok": False, "error": "not_found"})
        return _json_response(201, item)

    def append_workflow_step(
        self,
        authorization: str,
        execution_id: str,
        payload: Mapping[str, object],
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        if not _valid_uuid(execution_id) or not _valid_step_command(payload):
            return _json_response(400, {"ok": False, "error": "invalid_step_report"})
        item = self.store.append_workflow_step(device_id, execution_id, payload)
        if item is None:
            return _json_response(404, {"ok": False, "error": "not_found"})
        return _json_response(200, item)

    def upload_workflow_evidence(
        self, authorization: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        command = _workflow_evidence_command(payload)
        if command is None:
            return _json_response(
                400, {"ok": False, "error": "invalid_workflow_evidence"}
            )
        try:
            item = self.store.store_workflow_evidence(device_id, command)
        except ValueError as error:
            return _json_response(409, {"ok": False, "error": str(error)})
        if item is None:
            return _json_response(404, {"ok": False, "error": "not_found"})
        return _json_response(201, item)

    def create_workflow_evidence_session(
        self, authorization: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        command = _workflow_evidence_session_command(payload)
        if command is None:
            return _json_response(
                400, {"ok": False, "error": "invalid_workflow_evidence_session"}
            )
        try:
            result = self.store.create_workflow_evidence_upload(device_id, command)
        except ValueError as error:
            return _json_response(409, {"ok": False, "error": str(error)})
        if result is None:
            return _json_response(404, {"ok": False, "error": "not_found"})
        item, created = result
        return _json_response(201 if created else 200, item)

    def upload_workflow_evidence_chunk(
        self, authorization: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        command = _workflow_evidence_chunk_command(payload)
        if command is None:
            return _json_response(
                400, {"ok": False, "error": "invalid_workflow_evidence_chunk"}
            )
        try:
            item = self.store.store_workflow_evidence_chunk(device_id, command)
        except ValueError as error:
            return _json_response(409, {"ok": False, "error": str(error)})
        if item is None:
            return _json_response(404, {"ok": False, "error": "not_found"})
        return _json_response(200, item)

    def complete_workflow_evidence_upload(
        self, authorization: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        command = _workflow_evidence_complete_command(payload)
        if command is None:
            return _json_response(
                400, {"ok": False, "error": "invalid_workflow_evidence_complete"}
            )
        try:
            result = self.store.complete_workflow_evidence_upload(device_id, command)
        except ValueError as error:
            return _json_response(409, {"ok": False, "error": str(error)})
        if result is None:
            return _json_response(404, {"ok": False, "error": "not_found"})
        item, finalized = result
        return _json_response(201 if finalized else 200, item)

    def cancel_workflow_evidence_upload(
        self, authorization: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        command = _workflow_evidence_cancel_command(payload)
        if command is None:
            return _json_response(
                400, {"ok": False, "error": "invalid_workflow_evidence_cancel"}
            )
        try:
            item = self.store.cancel_workflow_evidence_upload(device_id, command)
        except ValueError as error:
            status = 502 if str(error) == "workflow_evidence_storage_failed" else 409
            return _json_response(status, {"ok": False, "error": str(error)})
        if item is None:
            return _json_response(404, {"ok": False, "error": "not_found"})
        return _json_response(200, item)

    def diagnose(
        self, authorization: str, requested_session_id: str, payload: Mapping[str, object]
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        prompt = str(payload.get("final_text") or payload.get("finalText") or "").strip()
        if not prompt:
            return _json_response(400, {"ok": False, "error": "final_text_required"})
        if len(prompt) > 12_000:
            return _json_response(413, {"ok": False, "error": "final_text_too_large"})
        local_project_id = str(
            payload.get("localProjectId") or payload.get("local_project_id") or ""
        ).strip()
        local_task_id = str(
            payload.get("localTaskId") or payload.get("local_task_id") or ""
        ).strip()
        if not local_project_id or not local_task_id:
            return _json_response(
                400, {"ok": False, "error": "project_task_required"}
            )
        attributes = payload.get("contextAttributes") or payload.get("context_attributes") or {}
        if not isinstance(attributes, Mapping):
            return _json_response(
                400, {"ok": False, "error": "context_attributes_invalid"}
            )
        task_session_id = requested_session_id.strip()
        if task_session_id and task_session_id != "_" and task_session_id != local_task_id:
            return _json_response(
                409, {"ok": False, "error": "task_session_mismatch"}
            )
        task_session_id = local_task_id
        try:
            session_id = self.store.ensure_chat_session(task_session_id, device_id)
        except ValueError:
            return _json_response(400, {"ok": False, "error": "invalid_session_id"})
        except PermissionError:
            return _json_response(403, {"ok": False, "error": "session_forbidden"})
        image_id = str(payload.get("image_id") or payload.get("imageId") or "").strip()
        image_data_url = ""
        if image_id:
            try:
                image_bytes = self.store.load_image(image_id, session_id, device_id)
            except LookupError:
                return _json_response(404, {"ok": False, "error": "image_not_found"})
            image_data_url = "data:image/jpeg;base64," + base64.b64encode(image_bytes).decode("ascii")
        recent_messages = self.store.recent_messages(session_id, device_id)
        try:
            context = self.store.execution_context.build_context(
                organization_id=self.config.organization_id,
                user_id=self.config.user_id,
                device_id=device_id,
                local_project_id=local_project_id,
                local_task_id=local_task_id,
                user_text=prompt,
                recent_messages=recent_messages,
                attributes=dict(attributes),
            )
        except (LookupError, PermissionError, ValueError) as error:
            return self._context_error_response(error)
        trace_id = str(uuid.uuid4())
        execution_started_at = self.clock()
        self.store.append_message(session_id, device_id, "user", prompt, image_id, trace_id)
        self.store.start_ai_request(trace_id, session_id, device_id, self.config.provider_model)
        self.store.execution_context.start_execution_run(
            trace_id, context, self.config.provider_model
        )
        try:
            provider_chunks = self.provider.stream_chat(context, image_data_url, trace_id)
        except (ProviderUnavailable, RuntimeError):
            self.store.finish_ai_request(trace_id, "failed", "provider_unavailable")
            self.store.execution_context.finish_execution_run(
                trace_id,
                "failed",
                self._execution_latency_ms(execution_started_at),
                "provider_unavailable",
            )
            return _json_response(
                502,
                {"ok": False, "error": "provider_unavailable", "traceId": trace_id},
            )

        return GatewayResponse(
            200,
            {
                "Content-Type": "text/event-stream; charset=utf-8",
                "Cache-Control": "no-cache, no-transform",
                "X-Trace-Id": trace_id,
            },
            body=self._stream_response(
                provider_chunks,
                session_id,
                device_id,
                image_id,
                trace_id,
                execution_started_at,
            ),
        )

    def _execution_latency_ms(self, started_at: float) -> int:
        return max(0, int(round((self.clock() - started_at) * 1000)))

    def _authenticated_device(self, authorization: str) -> Optional[str]:
        return self.store.authenticate_device_session(_bearer_token(authorization))

    def _voiceprint_device_call(
        self,
        authorization: str,
        operation: str,
        payload: Optional[Mapping[str, object]] = None,
    ) -> GatewayResponse:
        device_id = self._authenticated_device(authorization)
        if not device_id:
            return _json_response(401, {"ok": False, "error": "unauthorized"})
        if self.voiceprint_service is None:
            return _json_response(503, {"ok": False, "error": "voiceprint_unavailable"})
        method = getattr(self.voiceprint_service, operation)
        response = method(device_id) if payload is None else method(device_id, payload)
        return _voiceprint_gateway_response(response)

    def _stream_response(
        self,
        chunks: Iterable[str],
        session_id: str,
        device_id: str,
        image_id: str,
        trace_id: str,
        execution_started_at: float,
    ) -> Iterator[bytes]:
        completed: list[str] = []
        try:
            for chunk in chunks:
                if not isinstance(chunk, str) or not chunk:
                    continue
                completed.append(chunk)
                yield _sse("delta", {"text": chunk})
            text = "".join(completed).strip()
            if not text:
                raise ProviderUnavailable("provider_empty_response")
            self.store.append_message(session_id, device_id, "assistant", text, image_id, trace_id)
            self.store.finish_ai_request(trace_id, "success")
            self.store.execution_context.finish_execution_run(
                trace_id,
                "success",
                self._execution_latency_ms(execution_started_at),
            )
            yield _sse("done", {"message_id": trace_id, "session_id": session_id})
        except GeneratorExit:
            self.store.finish_ai_request(trace_id, "failed", "client_disconnected")
            self.store.execution_context.finish_execution_run(
                trace_id,
                "failed",
                self._execution_latency_ms(execution_started_at),
                "client_disconnected",
            )
            raise
        except (ProviderUnavailable, RuntimeError):
            self.store.finish_ai_request(trace_id, "failed", "provider_stream_failed")
            self.store.execution_context.finish_execution_run(
                trace_id,
                "failed",
                self._execution_latency_ms(execution_started_at),
                "provider_stream_failed",
            )
            yield _sse("error", {"code": "provider_stream_failed", "traceId": trace_id})
        finally:
            close = getattr(chunks, "close", None)
            if callable(close):
                close()


class GatewayHttpServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], service: GatewayService):
        super().__init__(address, GatewayRequestHandler)
        self.service = service


class GatewayRequestHandler(BaseHTTPRequestHandler):
    server: GatewayHttpServer
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path == "/health":
            self._send(self.server.service.health())
            return
        if parsed.path == "/ready":
            self._send(self.server.service.readiness())
            return
        if parsed.path == "/device-sync/voiceprint/profile":
            self._send(
                self.server.service.voiceprint_profile(
                    self.headers.get("Authorization", "")
                )
            )
            return
        if parsed.path == "/admin/voiceprints":
            self._send(
                self.server.service.voiceprint_admin_list(
                    self.headers.get("Authorization", "")
                )
            )
            return
        if parsed.path == "/device-sync/content-manifest":
            query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
            try:
                local_project_id = _single_text_query(query, "localProjectId")
            except ValueError:
                self._send(
                    _json_response(
                        400, {"ok": False, "error": "invalid_content_manifest_query"}
                    )
                )
                return
            self._send(
                self.server.service.get_device_content_manifest(
                    self.headers.get("Authorization", ""),
                    local_project_id,
                    self.headers.get("If-None-Match", ""),
                )
            )
            return
        if parsed.path == "/device-sync/skills":
            query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
            try:
                local_project_id = _single_text_query(query, "localProjectId")
                local_task_id = _single_text_query(query, "localTaskId")
            except ValueError:
                self._send(
                    _json_response(
                        400, {"ok": False, "error": "invalid_skill_query"}
                    )
                )
                return
            self._send(
                self.server.service.list_device_skills(
                    self.headers.get("Authorization", ""),
                    local_project_id,
                    local_task_id,
                )
            )
            return
        if parsed.path == "/device-sync/projects":
            self._send(
                self.server.service.list_device_projects(
                    self.headers.get("Authorization", "")
                )
            )
            return
        if parsed.path == "/device-sync/work-orders":
            query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
            try:
                if set(query) - {"view", "limit"}:
                    raise ValueError("query_key_invalid")
                view = _single_text_query(query, "view") or "pending"
                limit = _single_integer_query(query, "limit", 50)
            except ValueError:
                self._send(
                    _json_response(
                        400, {"ok": False, "error": "invalid_work_order_query"}
                    )
                )
                return
            self._send(
                self.server.service.list_mvs_work_orders(
                    self.headers.get("Authorization", ""), view, limit
                )
            )
            return
        work_order_match = re.fullmatch(
            r"/device-sync/work-orders/([^/]+)", parsed.path
        )
        if work_order_match:
            query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
            try:
                if set(query) - {"resource", "direction", "definitionId"}:
                    raise ValueError("query_key_invalid")
                resource = _single_text_query(query, "resource") or "detail"
                direction = _single_text_query(query, "direction")
                definition_id = _single_text_query(query, "definitionId")
            except ValueError:
                self._send(
                    _json_response(
                        400, {"ok": False, "error": "invalid_work_order_query"}
                    )
                )
                return
            self._send(
                self.server.service.get_mvs_work_order_resource(
                    self.headers.get("Authorization", ""),
                    urllib.parse.unquote(work_order_match.group(1)),
                    resource,
                    {"direction": direction, "definitionId": definition_id},
                )
            )
            return
        project_match = re.fullmatch(r"/device-sync/projects/([^/]+)", parsed.path)
        if project_match:
            self._send(
                self.server.service.get_device_project(
                    self.headers.get("Authorization", ""),
                    urllib.parse.unquote(project_match.group(1)),
                )
            )
            return
        asr_match = re.fullmatch(r"/sessions/([^/]+)/asr", parsed.path)
        if asr_match:
            self._handle_asr_websocket(asr_match.group(1))
            return
        if parsed.path == "/device-sync/workflows/assignments":
            query = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
            try:
                after_sequence = _single_integer_query(query, "afterSequence", 0)
                limit = _single_integer_query(query, "limit", 50)
            except ValueError:
                self._send(_json_response(400, {"ok": False, "error": "invalid_cursor"}))
                return
            self._send(
                self.server.service.list_workflow_assignments(
                    self.headers.get("Authorization", ""), after_sequence, limit
                )
            )
            return
        package_match = re.fullmatch(
            r"/device-sync/workflows/assignments/([^/]+)/package", parsed.path
        )
        if package_match:
            self._send(
                self.server.service.get_workflow_package(
                    self.headers.get("Authorization", ""),
                    package_match.group(1),
                    self.headers.get("X-App-Version-Code", ""),
                    self.headers.get("X-Workflow-Schema-Version", ""),
                    self.headers.get("X-Workflow-Capabilities", ""),
                )
            )
            return
        self._send(_json_response(404, {"ok": False, "error": "not_found"}))

    def _handle_asr_websocket(self, requested_session_id: str) -> None:
        service = self.server.service
        device_id = service._authenticated_device(self.headers.get("Authorization", ""))
        if not device_id:
            self._send(_json_response(401, {"ok": False, "error": "unauthorized"}))
            return
        try:
            session_id = service.store.ensure_chat_session(requested_session_id, device_id)
        except ValueError:
            self._send(_json_response(400, {"ok": False, "error": "invalid_session_id"}))
            return
        except PermissionError:
            self._send(_json_response(403, {"ok": False, "error": "session_forbidden"}))
            return
        try:
            accept_value = websocket_accept_value(self.headers)
        except WebSocketProtocolError:
            self._send(
                _json_response(
                    426,
                    {"ok": False, "error": "websocket_upgrade_required"},
                    {"Sec-WebSocket-Version": "13"},
                )
            )
            return
        try:
            provider_socket = service.asr_connector()
        except (OSError, TimeoutError, WebSocketProtocolError):
            self._send(_json_response(503, {"ok": False, "error": "asr_unavailable"}))
            return
        self.send_response(101, "Switching Protocols")
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept_value)
        self.end_headers()
        self.wfile.flush()
        self.close_connection = True
        relay_asr_websockets(
            self.connection,
            provider_socket,
            session_id=session_id,
            model=service.config.asr_model,
            client_reader=self.rfile,
        )

    def do_POST(self) -> None:
        payload = self._json_payload()
        if payload is None:
            return
        if self.path == "/internal/knowledge/parse":
            self._send(
                self.server.service.parse_knowledge_document(
                    self.headers.get("Authorization", ""), payload
                )
            )
            return
        if self.path == "/device-sync/session":
            self._send(
                self.server.service.exchange_device_session(self.headers.get("Authorization", ""))
            )
            return
        if self.path == "/device-sync/events":
            self._send(
                self.server.service.append_device_event(
                    self.headers.get("Authorization", ""), payload
                )
            )
            return
        task_skill_match = re.fullmatch(
            r"/device-sync/tasks/([^/]+)/skill", self.path
        )
        if task_skill_match:
            self._send(
                self.server.service.set_task_skill(
                    self.headers.get("Authorization", ""),
                    urllib.parse.unquote(task_skill_match.group(1)),
                    payload,
                )
            )
            return
        task_end_match = re.fullmatch(
            r"/device-sync/tasks/([^/]+)/end-summary", self.path
        )
        if task_end_match:
            self._send(
                self.server.service.end_task_summary(
                    self.headers.get("Authorization", ""),
                    urllib.parse.unquote(task_end_match.group(1)),
                    payload,
                )
            )
            return
        instruction_match = re.fullmatch(
            r"/device-sync/projects/([^/]+)/instructions", self.path
        )
        if instruction_match:
            self._send(
                self.server.service.confirm_project_instruction(
                    self.headers.get("Authorization", ""),
                    urllib.parse.unquote(instruction_match.group(1)),
                    payload,
                )
            )
            return
        work_order_checkin_match = re.fullmatch(
            r"/device-sync/work-orders/([^/]+)/checkin", self.path
        )
        if work_order_checkin_match:
            self._send(
                self.server.service.submit_mvs_checkin(
                    self.headers.get("Authorization", ""),
                    urllib.parse.unquote(work_order_checkin_match.group(1)),
                    payload,
                )
            )
            return
        voiceprint_routes = {
            "/device-sync/voiceprint/consent": "voiceprint_consent",
            "/device-sync/voiceprint/enrollment/samples": "voiceprint_enroll_sample",
            "/device-sync/voiceprint/verify": "voiceprint_verify",
            "/device-sync/voiceprint/reenroll": "voiceprint_reenroll",
            "/device-sync/voiceprint/delete": "voiceprint_delete",
        }
        voiceprint_method = voiceprint_routes.get(self.path)
        if voiceprint_method:
            self._send(
                getattr(self.server.service, voiceprint_method)(
                    self.headers.get("Authorization", ""), payload
                )
            )
            return
        admin_voiceprint_match = re.fullmatch(
            r"/admin/voiceprints/([^/]+)/revoke", self.path
        )
        if admin_voiceprint_match:
            self._send(
                self.server.service.voiceprint_admin_revoke(
                    self.headers.get("Authorization", ""),
                    admin_voiceprint_match.group(1),
                    payload,
                )
            )
            return
        status_match = re.fullmatch(
            r"/device-sync/workflows/assignments/([^/]+)/status", self.path
        )
        if status_match:
            self._send(
                self.server.service.report_workflow_assignment_status(
                    self.headers.get("Authorization", ""),
                    status_match.group(1),
                    payload,
                )
            )
            return
        if self.path == "/device-sync/workflows/executions":
            self._send(
                self.server.service.start_workflow_execution(
                    self.headers.get("Authorization", ""), payload
                )
            )
            return
        if self.path == "/device-sync/workflows/evidence":
            self._send(
                self.server.service.upload_workflow_evidence(
                    self.headers.get("Authorization", ""), payload
                )
            )
            return
        if self.path == "/device-sync/workflows/evidence/session":
            self._send(
                self.server.service.create_workflow_evidence_session(
                    self.headers.get("Authorization", ""), payload
                )
            )
            return
        if self.path == "/device-sync/workflows/evidence/chunk":
            self._send(
                self.server.service.upload_workflow_evidence_chunk(
                    self.headers.get("Authorization", ""), payload
                )
            )
            return
        if self.path == "/device-sync/workflows/evidence/complete":
            self._send(
                self.server.service.complete_workflow_evidence_upload(
                    self.headers.get("Authorization", ""), payload
                )
            )
            return
        if self.path == "/device-sync/workflows/evidence/cancel":
            self._send(
                self.server.service.cancel_workflow_evidence_upload(
                    self.headers.get("Authorization", ""), payload
                )
            )
            return
        step_match = re.fullmatch(
            r"/device-sync/workflows/executions/([^/]+)/steps", self.path
        )
        if step_match:
            self._send(
                self.server.service.append_workflow_step(
                    self.headers.get("Authorization", ""),
                    step_match.group(1),
                    payload,
                )
            )
            return
        match = re.fullmatch(r"/sessions/([^/]+)/(images|diagnose/stream)", self.path)
        if not match:
            self._send(_json_response(404, {"ok": False, "error": "not_found"}))
            return
        session_id, action = match.groups()
        authorization = self.headers.get("Authorization", "")
        if action == "images":
            self._send(self.server.service.upload_image(authorization, session_id, payload))
        else:
            self._send(self.server.service.diagnose(authorization, session_id, payload))

    def _json_payload(self) -> Optional[dict]:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        maximum = int(self.server.service.config.max_image_bytes * 1.5) + 64 * 1024
        if length < 0 or length > maximum:
            self._send(_json_response(413, {"ok": False, "error": "request_too_large"}))
            return None
        try:
            payload = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, UnicodeDecodeError):
            self._send(_json_response(400, {"ok": False, "error": "invalid_json"}))
            return None
        if not isinstance(payload, dict):
            self._send(_json_response(400, {"ok": False, "error": "invalid_json"}))
            return None
        return payload

    def _send(self, response: GatewayResponse) -> None:
        if response.json_body is not None:
            body = json.dumps(response.json_body, ensure_ascii=False).encode("utf-8")
            self.send_response(response.status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            for key, value in response.headers.items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(response.status)
        for key, value in response.headers.items():
            self.send_header(key, value)
        self.send_header("Connection", "close")
        self.end_headers()
        body = iter(response.body or ())
        try:
            for chunk in body:
                self.wfile.write(chunk)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            close = getattr(body, "close", None)
            if callable(close):
                close()

    def log_message(self, format_string: str, *args: object) -> None:
        # Authorization headers and request bodies must never enter service logs.
        print(
            json.dumps(
                {
                    "timestamp": _iso_timestamp(time.time()),
                    "client": self.client_address[0],
                    "method": self.command,
                    "path": self.path,
                    "message": format_string % args,
                },
                ensure_ascii=False,
            ),
            flush=True,
        )


def configuration_from_environment(environment: Mapping[str, str] = os.environ) -> GatewayConfig:
    required = (
        "V9_BOOTSTRAP_TOKEN_SHA256",
        "V9_DEVICE_ID",
        "V9_ORGANIZATION_ID",
        "V9_USER_ID",
        "V9_AI_BASE_URL",
        "V9_AI_API_KEY",
        "V9_AI_MODEL",
    )
    missing = [name for name in required if not str(environment.get(name, "")).strip()]
    if missing:
        raise ValueError("missing_environment:" + ",".join(missing))
    return GatewayConfig(
        bootstrap_token_hash=str(environment["V9_BOOTSTRAP_TOKEN_SHA256"]).strip(),
        device_id=str(environment["V9_DEVICE_ID"]).strip(),
        organization_id=str(environment["V9_ORGANIZATION_ID"]).strip(),
        user_id=str(environment["V9_USER_ID"]).strip(),
        provider_base_url=str(environment["V9_AI_BASE_URL"]).strip(),
        provider_api_key=str(environment["V9_AI_API_KEY"]).strip(),
        provider_model=str(environment["V9_AI_MODEL"]).strip(),
        session_ttl_seconds=int(environment.get("V9_SESSION_TTL_SECONDS", "900")),
        max_image_bytes=int(environment.get("V9_MAX_IMAGE_BYTES", str(8 * 1024 * 1024))),
        asr_url=str(
            environment.get(
                "V9_ASR_URL", "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
            )
        ).strip(),
        asr_api_key=str(
            environment.get("V9_ASR_API_KEY") or environment["V9_AI_API_KEY"]
        ).strip(),
        asr_model=str(environment.get("V9_ASR_MODEL", PREVIOUS_STABLE_ASR_MODEL)).strip(),
        knowledge_parser_token_hash=str(
            environment.get("V9_KNOWLEDGE_PARSER_TOKEN_SHA256", "")
        ).strip(),
    )


def content_manifest_sync_worker_from_environment(
    environment: Mapping[str, str],
    config: GatewayConfig,
    repository: ExecutionContextRepository,
) -> Optional[ContentManifestSyncWorker]:
    required = (
        "V9_CONTENT_MANIFEST_SYNC_BASE_URL",
        "V9_CONTENT_MANIFEST_SYNC_TOKEN",
    )
    related = required + (
        "V9_CONTENT_MANIFEST_REFRESH_SECONDS",
        "V9_CONTENT_MANIFEST_MAX_BACKOFF_SECONDS",
    )
    present = [name for name in related if str(environment.get(name, "")).strip()]
    if not present:
        return None
    missing = [name for name in required if not str(environment.get(name, "")).strip()]
    if missing:
        raise ValueError(
            "missing_content_manifest_sync_environment:" + ",".join(missing)
        )
    try:
        refresh_interval_seconds = float(
            environment.get("V9_CONTENT_MANIFEST_REFRESH_SECONDS", "60")
        )
        max_backoff_seconds = float(
            environment.get("V9_CONTENT_MANIFEST_MAX_BACKOFF_SECONDS", "900")
        )
    except ValueError:
        raise ValueError("content_manifest_sync_environment_invalid") from None
    client = ContentManifestSyncClient(
        ContentManifestSyncConfig(
            base_url=str(environment["V9_CONTENT_MANIFEST_SYNC_BASE_URL"]).strip(),
            sync_token=str(environment["V9_CONTENT_MANIFEST_SYNC_TOKEN"]).strip(),
        )
    )
    return ContentManifestSyncWorker(
        repository,
        client,
        config.organization_id,
        config.user_id,
        config.device_id,
        refresh_interval_seconds=refresh_interval_seconds,
        max_backoff_seconds=max_backoff_seconds,
    )


def control_plane_sync_worker_from_environment(
    environment: Mapping[str, str],
    store: SqliteStore,
    content_manifest_sync_worker: Optional[object] = None,
) -> Optional[ControlPlaneSyncWorker]:
    required = (
        "V9_CONTROL_PLANE_SYNC_BASE_URL",
        "V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN",
    )
    present = [name for name in required if str(environment.get(name, "")).strip()]
    if not present:
        return None
    missing = [name for name in required if not str(environment.get(name, "")).strip()]
    if missing:
        raise ValueError(
            "missing_control_plane_sync_environment:" + ",".join(missing)
        )
    try:
        retry_base_seconds = float(
            environment.get("V9_CONTROL_PLANE_SYNC_RETRY_BASE_SECONDS", "5")
        )
        max_backoff_seconds = float(
            environment.get("V9_CONTROL_PLANE_SYNC_MAX_BACKOFF_SECONDS", "900")
        )
        poll_interval_seconds = float(
            environment.get("V9_CONTROL_PLANE_SYNC_POLL_SECONDS", "1")
        )
        batch_size = int(environment.get("V9_CONTROL_PLANE_SYNC_BATCH_SIZE", "25"))
    except ValueError:
        raise ValueError("control_plane_sync_environment_invalid") from None
    manifest_trigger = (
        content_manifest_sync_worker.trigger
        if content_manifest_sync_worker is not None
        else None
    )
    return ControlPlaneSyncWorker(
        store,
        ControlPlaneSyncClient(
            ControlPlaneSyncConfig(
                base_url=str(
                    environment["V9_CONTROL_PLANE_SYNC_BASE_URL"]
                ).strip(),
                bootstrap_credential=str(
                    environment["V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN"]
                ).strip(),
            )
        ),
        retry_base_seconds=retry_base_seconds,
        max_backoff_seconds=max_backoff_seconds,
        batch_size=batch_size,
        poll_interval_seconds=poll_interval_seconds,
        manifest_trigger=manifest_trigger,
    )


def voiceprint_runtime_configuration_from_environment(
    environment: Mapping[str, str], device_id: str
) -> Optional[tuple[IflytekVoiceprintConfig, VoiceprintLifecycleConfig]]:
    required = (
        "IFLYTEK_APP_ID",
        "IFLYTEK_API_KEY",
        "IFLYTEK_API_SECRET",
        "V9_VOICEPRINT_ORGANIZATION_ID",
        "V9_VOICEPRINT_USER_ID",
        "V9_VOICEPRINT_ADMIN_TOKEN_SHA256",
        "V9_VOICEPRINT_REPLAY_HMAC_KEY",
    )
    present = [name for name in required if str(environment.get(name, "")).strip()]
    if not present:
        return None
    missing = [name for name in required if not str(environment.get(name, "")).strip()]
    if missing:
        raise ValueError("missing_voiceprint_environment:" + ",".join(missing))
    try:
        threshold = float(environment.get("V9_VOICEPRINT_THRESHOLD", "0.70"))
        timeout_seconds = int(environment.get("V9_VOICEPRINT_TIMEOUT_SECONDS", "8"))
    except ValueError:
        raise ValueError("voiceprint_environment_invalid") from None
    provider = IflytekVoiceprintConfig(
        app_id=str(environment["IFLYTEK_APP_ID"]).strip(),
        api_key=str(environment["IFLYTEK_API_KEY"]).strip(),
        api_secret=str(environment["IFLYTEK_API_SECRET"]).strip(),
        endpoint=str(environment.get("V9_VOICEPRINT_URL", IFLYTEK_VOICEPRINT_URL)).strip(),
        threshold=threshold,
        timeout_seconds=timeout_seconds,
    )
    lifecycle = VoiceprintLifecycleConfig(
        organization_id=str(environment["V9_VOICEPRINT_ORGANIZATION_ID"]).strip(),
        user_id=str(environment["V9_VOICEPRINT_USER_ID"]).strip(),
        device_id=device_id,
        admin_token_hash=str(environment["V9_VOICEPRINT_ADMIN_TOKEN_SHA256"]).strip(),
        admin_previous_token_hash=str(
            environment.get("V9_VOICEPRINT_ADMIN_PREVIOUS_TOKEN_SHA256", "")
        ).strip(),
        replay_hmac_key=str(environment["V9_VOICEPRINT_REPLAY_HMAC_KEY"]).strip(),
        consent_version=str(
            environment.get("V9_VOICEPRINT_CONSENT_VERSION", VOICEPRINT_CONSENT_VERSION)
        ).strip(),
        threshold=threshold,
    )
    return provider, lifecycle


def mvs_connector_from_environment(
    environment: Mapping[str, str],
) -> Optional[MvsWorkOrderConnector]:
    required = (
        "V9_MVS_BASE_URL",
        "V9_MVS_AUTHORIZATION",
        "V9_MVS_ENGINEER_ID",
    )
    related = required + (
        "V9_MVS_TIMEOUT_SECONDS",
        "V9_MVS_RETRY_INTERVAL_SECONDS",
        "V9_MVS_RETRY_BASE_SECONDS",
        "V9_MVS_RETRY_STALE_SECONDS",
        "V9_MVS_RETRY_MAX_ATTEMPTS",
        "V9_MVS_RETRY_BATCH_SIZE",
    )
    present = [name for name in related if str(environment.get(name, "")).strip()]
    if not present:
        return None
    missing = [name for name in required if not str(environment.get(name, "")).strip()]
    if missing:
        raise ValueError("missing_mvs_environment:" + ",".join(missing))
    try:
        timeout_seconds = int(environment.get("V9_MVS_TIMEOUT_SECONDS", "8"))
    except ValueError:
        raise ValueError("mvs_environment_invalid") from None
    return MvsWorkOrderConnector(
        MvsConnectorConfig(
            base_url=str(environment["V9_MVS_BASE_URL"]).strip(),
            authorization=str(environment["V9_MVS_AUTHORIZATION"]).strip(),
            engineer_id=str(environment["V9_MVS_ENGINEER_ID"]).strip(),
            timeout_seconds=timeout_seconds,
        ),
        opener=_open_provider_request,
    )


def mvs_write_enabled_from_environment(environment: Mapping[str, str]) -> bool:
    """Require an explicit production approval before any MVS write is possible."""
    return str(environment.get("V9_MVS_WRITE_ENABLED", "")).strip().lower() == "true"


def mvs_retry_worker_from_environment(
    environment: Mapping[str, str],
    store: SqliteStore,
    connector: Optional[MvsWorkOrderConnector],
    write_enabled: bool = False,
) -> Optional[MvsWorkOrderRetryWorker]:
    if connector is None or not write_enabled:
        return None
    try:
        refresh_interval_seconds = float(
            environment.get("V9_MVS_RETRY_INTERVAL_SECONDS", "5")
        )
        retry_base_seconds = float(
            environment.get("V9_MVS_RETRY_BASE_SECONDS", "5")
        )
        retry_stale_seconds = float(
            environment.get("V9_MVS_RETRY_STALE_SECONDS", "30")
        )
        max_attempts = int(environment.get("V9_MVS_RETRY_MAX_ATTEMPTS", "5"))
        batch_size = int(environment.get("V9_MVS_RETRY_BATCH_SIZE", "10"))
    except ValueError:
        raise ValueError("mvs_retry_environment_invalid") from None
    return MvsWorkOrderRetryWorker(
        store,
        connector,
        refresh_interval_seconds=refresh_interval_seconds,
        retry_base_seconds=retry_base_seconds,
        retry_stale_seconds=retry_stale_seconds,
        max_attempts=max_attempts,
        batch_size=batch_size,
    )


def serve_gateway(
    server: object,
    store: object,
    voiceprint_store: Optional[object] = None,
    content_manifest_sync_worker: Optional[ContentManifestSyncWorker] = None,
    mvs_retry_worker: Optional[MvsWorkOrderRetryWorker] = None,
    control_plane_sync_worker: Optional[ControlPlaneSyncWorker] = None,
) -> None:
    if content_manifest_sync_worker is not None:
        content_manifest_sync_worker.start()
    if control_plane_sync_worker is not None:
        control_plane_sync_worker.start()
    if mvs_retry_worker is not None:
        mvs_retry_worker.start()
    try:
        server.serve_forever(poll_interval=0.2)
    finally:
        if mvs_retry_worker is not None:
            mvs_retry_worker.stop()
        if control_plane_sync_worker is not None:
            control_plane_sync_worker.stop()
        if content_manifest_sync_worker is not None:
            content_manifest_sync_worker.stop()
        server.server_close()
        store.close()
        if voiceprint_store is not None:
            voiceprint_store.close()


def run() -> None:
    config = configuration_from_environment()
    data_dir = Path(os.environ.get("V9_DATA_DIR", "/var/lib/dingdang-v9-gateway"))
    store = SqliteStore(str(data_dir / "gateway.db"), str(data_dir / "evidence"))
    content_manifest_sync_worker = content_manifest_sync_worker_from_environment(
        os.environ,
        config,
        store.execution_context,
    )
    control_plane_sync_worker = control_plane_sync_worker_from_environment(
        os.environ,
        store,
        content_manifest_sync_worker,
    )
    voiceprint_store: Optional[VoiceprintStore] = None
    voiceprint_service: Optional[VoiceprintLifecycleService] = None
    voiceprint_configuration = voiceprint_runtime_configuration_from_environment(
        os.environ, config.device_id
    )
    if voiceprint_configuration is not None:
        provider_config, lifecycle_config = voiceprint_configuration
        voiceprint_store = VoiceprintStore(str(data_dir / "voiceprint.db"))
        voiceprint_service = VoiceprintLifecycleService(
            lifecycle_config,
            voiceprint_store,
            IflytekVoiceprintProvider(
                provider_config,
                opener=_open_provider_request,
            ),
        )
    mvs_connector = mvs_connector_from_environment(os.environ)
    mvs_write_enabled = mvs_write_enabled_from_environment(os.environ)
    mvs_retry_worker = mvs_retry_worker_from_environment(
        os.environ, store, mvs_connector, mvs_write_enabled
    )
    service = GatewayService(
        config,
        store,
        DashscopeProvider(config),
        voiceprint_service=voiceprint_service,
        content_manifest_sync_worker=content_manifest_sync_worker,
        control_plane_sync_worker=control_plane_sync_worker,
        mvs_connector=mvs_connector,
        mvs_write_enabled=mvs_write_enabled,
    )
    host = os.environ.get("V9_LISTEN_HOST", "127.0.0.1")
    port = int(os.environ.get("V9_LISTEN_PORT", "8790"))
    server = GatewayHttpServer((host, port), service)

    def stop(_signum: int, _frame: object) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    serve_gateway(
        server,
        store,
        voiceprint_store,
        content_manifest_sync_worker,
        mvs_retry_worker,
        control_plane_sync_worker,
    )


def _json_response(
    status: int, body: dict, extra_headers: Optional[dict[str, str]] = None
) -> GatewayResponse:
    return GatewayResponse(status, extra_headers or {}, json_body=body)


def _voiceprint_gateway_response(response: VoiceprintResponse) -> GatewayResponse:
    return _json_response(
        response.status,
        dict(response.body),
        {"Cache-Control": "no-store"},
    )


def _bearer_token(authorization: str) -> str:
    value = authorization.strip()
    return value[7:].strip() if value.startswith("Bearer ") else ""


def _sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _iso_timestamp(epoch_seconds: float) -> str:
    return datetime.fromtimestamp(epoch_seconds, timezone.utc).isoformat().replace("+00:00", "Z")


def _valid_device_event(payload: Mapping[str, object]) -> bool:
    if not isinstance(payload, Mapping):
        return False
    required = {
        "localProjectId": 160,
        "localTaskId": 160,
        "eventType": 160,
        "idempotencyKey": 240,
    }
    for key, maximum in required.items():
        if not _valid_text(payload.get(key), maximum):
            return False
    if str(payload["eventType"]).strip() not in DEVICE_EVENT_TYPES:
        return False
    for key, maximum in (("projectTitle", 240), ("taskTitle", 240)):
        value = payload.get(key, "")
        if value is not None and not _valid_optional_text(value, maximum):
            return False
    if not isinstance(payload.get("payload"), dict):
        return False
    occurred_at = payload.get("occurredAt")
    if not _valid_text(occurred_at, 100):
        return False
    try:
        parsed = datetime.fromisoformat(str(occurred_at).replace("Z", "+00:00"))
    except ValueError:
        return False
    return parsed.tzinfo is not None


def _valid_text(value: object, maximum: int) -> bool:
    if not isinstance(value, str):
        return False
    text = value.strip()
    return 0 < len(text) <= maximum and not any(ord(character) < 32 for character in text)


def _valid_optional_text(value: object, maximum: int) -> bool:
    if not isinstance(value, str):
        return False
    text = value.strip()
    return len(text) <= maximum and not any(ord(character) < 32 for character in text)


def _task_status(event_type: str) -> str:
    if event_type == "task_completed":
        return "completed"
    if event_type == "task_closed":
        return "closed"
    return "active"


def _valid_identifier(value: object, maximum: int) -> bool:
    return _valid_text(value, maximum) and "/" not in str(value) and "\\" not in str(value)


def _valid_iso_timestamp(value: object) -> bool:
    if not _valid_text(value, 100):
        return False
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return False
    return parsed.tzinfo is not None


def _single_integer_query(
    query: Mapping[str, list[str]], key: str, default: int
) -> int:
    values = query.get(key)
    if values is None:
        return default
    if len(values) != 1 or not re.fullmatch(r"0|[1-9][0-9]*", values[0]):
        raise ValueError("invalid_integer_query")
    return int(values[0])


def _single_text_query(query: Mapping[str, list[str]], name: str) -> str:
    values = query.get(name, [])
    if len(values) > 1:
        raise ValueError("query_value_invalid")
    return values[0].strip() if values else ""


def _etag_matches(header_value: str, expected: str) -> bool:
    value = str(header_value or "").strip()
    if value.startswith("W/"):
        value = value[2:].strip()
    if len(value) >= 2 and value[0] == '"' and value[-1] == '"':
        value = value[1:-1]
    return hmac.compare_digest(value, expected) if value else False


def _workflow_capability_declaration(
    app_version_code: object,
    workflow_schema_version: object,
    capability_header: str,
) -> Optional[tuple[int, int, set[str]]]:
    try:
        app_version = int(str(app_version_code))
        schema_version = int(str(workflow_schema_version))
    except ValueError:
        return None
    if (
        app_version < 9000
        or schema_version < 1
        or not isinstance(capability_header, str)
        or len(capability_header) > 4000
    ):
        return None
    values = [value.strip() for value in capability_header.split(",") if value.strip()]
    if not values or len(values) > 100 or len(values) != len(set(values)):
        return None
    return app_version, schema_version, set(values)


def _valid_workflow_package(
    package: object, assignment_id: str, workflow_version_id: str
) -> bool:
    if not isinstance(package, dict):
        return False
    required = (
        "workflowVersionId",
        "schemaVersion",
        "executionPackage",
        "contentSha256",
        "packageSignature",
        "signatureKeyId",
        "requiredCapabilities",
        "minAppVersionCode",
    )
    if any(key not in package for key in required):
        return False
    execution_package = package["executionPackage"]
    digest = package["contentSha256"]
    capabilities = package["requiredCapabilities"]
    schema_version = package["schemaVersion"]
    min_app_version = package["minAppVersionCode"]
    return (
        _valid_identifier(assignment_id, 200)
        and package["workflowVersionId"] == workflow_version_id
        and isinstance(schema_version, int)
        and not isinstance(schema_version, bool)
        and schema_version >= 1
        and isinstance(min_app_version, int)
        and not isinstance(min_app_version, bool)
        and min_app_version >= 1
        and isinstance(execution_package, dict)
        and execution_package.get("schemaVersion") == schema_version
        and execution_package.get("contentSha256") == digest
        and isinstance(digest, str)
        and re.fullmatch(r"[0-9a-f]{64}", digest) is not None
        and _valid_text(package["packageSignature"], 8192)
        and _valid_text(package["signatureKeyId"], 200)
        and isinstance(capabilities, list)
        and len(capabilities) <= 100
        and all(_valid_text(value, 200) for value in capabilities)
        and len(capabilities) == len(set(capabilities))
    )


def _valid_uuid(value: object) -> bool:
    if not isinstance(value, str):
        return False
    try:
        parsed = uuid.UUID(value)
    except (ValueError, AttributeError):
        return False
    return str(parsed) == value.lower() and parsed.version in {1, 2, 3, 4, 5}


def _valid_assignment_status_command(payload: Mapping[str, object]) -> bool:
    status = payload.get("status")
    failure_stage = payload.get("failureStage")
    failure_reason = payload.get("failureReason")
    return (
        status in WORKFLOW_REPORTABLE_STATUSES
        and _valid_identifier(payload.get("idempotencyKey"), 200)
        and _valid_nullable_text(failure_stage, 160)
        and _valid_nullable_text(failure_reason, 1000)
        and (status != "failed" or bool(str(failure_reason or "").strip()))
    )


def _valid_execution_start_command(payload: Mapping[str, object]) -> bool:
    return (
        _valid_uuid(payload.get("executionId"))
        and _valid_uuid(payload.get("assignmentId"))
        and _valid_identifier(payload.get("projectId"), 200)
        and _valid_identifier(payload.get("localTaskId"), 200)
        and _valid_identifier(payload.get("initialNodeId"), 160)
        and isinstance(payload.get("runtimeSnapshot"), dict)
        and _valid_identifier(payload.get("idempotencyKey"), 200)
    )


def _valid_step_command(payload: Mapping[str, object]) -> bool:
    attempt = payload.get("attemptNumber")
    evidence = payload.get("evidenceAssetIds")
    status = payload.get("status")
    failure_reason = payload.get("failureReason")
    return (
        _valid_identifier(payload.get("nodeId"), 160)
        and isinstance(attempt, int)
        and not isinstance(attempt, bool)
        and 1 <= attempt <= 2_147_483_647
        and status in WORKFLOW_STEP_STATUSES
        and _valid_identifier(payload.get("idempotencyKey"), 200)
        and isinstance(payload.get("inputData"), dict)
        and isinstance(payload.get("outputData"), dict)
        and isinstance(evidence, list)
        and len(evidence) <= 1000
        and len(evidence) == len(set(evidence))
        and all(_valid_uuid(value) for value in evidence)
        and isinstance(payload.get("transitionResult"), dict)
        and _valid_nullable_text(payload.get("failureCode"), 160)
        and _valid_nullable_text(failure_reason, 1000)
        and _valid_nullable_text(payload.get("nextNodeId"), 160)
        and isinstance(payload.get("runtimeSnapshot"), dict)
        and (status != "failed" or bool(str(failure_reason or "").strip()))
    )


def _valid_nullable_text(value: object, maximum: int) -> bool:
    return value is None or _valid_optional_text(value, maximum)


def _advanced_assignment_status(current: str, requested: str) -> str:
    if requested == "failed":
        return "failed"
    order = {
        "queued": 0,
        "notified": 1,
        "delivered": 2,
        "verified": 3,
        "ready": 4,
        "active": 5,
        "completed": 6,
    }
    if current in {"failed", "completed", "revoked"}:
        return current
    return requested if order.get(requested, -1) >= order.get(current, -1) else current


def _workflow_execution_row(row: sqlite3.Row) -> dict:
    return {
        "executionId": str(row["execution_id"]),
        "assignmentId": str(row["assignment_id"]),
        "taskId": str(row["task_id"]),
        "status": str(row["status"]),
        "currentNodeId": str(row["current_node_id"]),
        "startedAt": str(row["started_at"]),
    }


def _workflow_step_row(row: sqlite3.Row) -> dict:
    return {
        "stepExecutionId": str(row["step_execution_id"]),
        "executionId": str(row["execution_id"]),
        "nodeId": str(row["node_id"]),
        "attemptNumber": int(row["attempt_number"]),
        "status": str(row["status"]),
        "evidenceAssetIds": json.loads(str(row["evidence_asset_ids_json"])),
        "nextNodeId": row["next_node_id"],
        "updatedAt": str(row["updated_at"]),
    }


def _workflow_evidence_command(
    payload: Mapping[str, object]
) -> Optional[dict[str, object]]:
    allowed = {
        "assignmentId",
        "executionId",
        "localEvidenceId",
        "nodeId",
        "evidenceKey",
        "kind",
        "contentType",
        "durationSeconds",
        "byteSize",
        "sha256",
        "dataBase64",
        "capturedAt",
    }
    if set(payload) != allowed:
        return None
    kind = payload.get("kind")
    content_type = payload.get("contentType")
    duration = payload.get("durationSeconds")
    byte_size = payload.get("byteSize")
    maximum = MAX_WORKFLOW_VIDEO_BYTES if kind == "video" else MAX_WORKFLOW_PHOTO_BYTES
    if (
        not _valid_uuid(payload.get("assignmentId"))
        or not _valid_uuid(payload.get("executionId"))
        or not _valid_identifier(payload.get("localEvidenceId"), 160)
        or not _valid_identifier(payload.get("nodeId"), 160)
        or not _valid_identifier(payload.get("evidenceKey"), 160)
        or not isinstance(duration, int)
        or isinstance(duration, bool)
        or not isinstance(byte_size, int)
        or isinstance(byte_size, bool)
        or byte_size < 1
        or byte_size > maximum
        or not isinstance(payload.get("sha256"), str)
        or re.fullmatch(r"[0-9a-f]{64}", str(payload["sha256"])) is None
        or not isinstance(payload.get("dataBase64"), str)
        or len(str(payload["dataBase64"])) > ((maximum + 2) // 3) * 4 + 4
        or not _valid_iso_timestamp(payload.get("capturedAt"))
    ):
        return None
    if kind == "photo":
        if content_type != "image/jpeg" or duration != 0:
            return None
    elif kind == "video":
        if content_type != "video/mp4" or duration < 1 or duration > 15:
            return None
    else:
        return None
    try:
        content = base64.b64decode(str(payload["dataBase64"]), validate=True)
    except (ValueError, TypeError):
        return None
    if len(content) != byte_size or _sha256(content) != payload["sha256"]:
        return None
    if kind == "photo":
        if len(content) < 4 or not content.startswith(b"\xff\xd8") or not content.endswith(b"\xff\xd9"):
            return None
    elif len(content) < 12 or b"ftyp" not in content[:32]:
        return None
    command = dict(payload)
    command.pop("dataBase64")
    command["bytes"] = content
    return command


def _workflow_evidence_session_command(
    payload: Mapping[str, object]
) -> Optional[dict[str, object]]:
    allowed = {
        "assignmentId",
        "executionId",
        "localEvidenceId",
        "nodeId",
        "evidenceKey",
        "kind",
        "contentType",
        "durationSeconds",
        "byteSize",
        "sha256",
        "capturedAt",
        "chunkSize",
        "chunkCount",
    }
    if set(payload) != allowed or not _valid_workflow_evidence_metadata(payload):
        return None
    chunk_size = payload.get("chunkSize")
    chunk_count = payload.get("chunkCount")
    byte_size = int(payload["byteSize"])
    if (
        not isinstance(chunk_size, int)
        or isinstance(chunk_size, bool)
        or chunk_size < 1
        or chunk_size > MAX_WORKFLOW_EVIDENCE_CHUNK_BYTES
        or not isinstance(chunk_count, int)
        or isinstance(chunk_count, bool)
        or chunk_count < 1
        or chunk_count > MAX_WORKFLOW_EVIDENCE_CHUNKS
        or chunk_count != (byte_size + chunk_size - 1) // chunk_size
    ):
        return None
    return dict(payload)


def _workflow_evidence_chunk_command(
    payload: Mapping[str, object]
) -> Optional[dict[str, object]]:
    allowed = {
        "uploadId",
        "chunkIndex",
        "chunkCount",
        "chunkByteSize",
        "chunkSha256",
        "dataBase64",
    }
    if set(payload) != allowed:
        return None
    chunk_index = payload.get("chunkIndex")
    chunk_count = payload.get("chunkCount")
    chunk_byte_size = payload.get("chunkByteSize")
    data_base64 = payload.get("dataBase64")
    if (
        not _valid_uuid(payload.get("uploadId"))
        or not isinstance(chunk_index, int)
        or isinstance(chunk_index, bool)
        or chunk_index < 0
        or not isinstance(chunk_count, int)
        or isinstance(chunk_count, bool)
        or chunk_count < 1
        or chunk_count > MAX_WORKFLOW_EVIDENCE_CHUNKS
        or chunk_index >= chunk_count
        or not isinstance(chunk_byte_size, int)
        or isinstance(chunk_byte_size, bool)
        or chunk_byte_size < 1
        or chunk_byte_size > MAX_WORKFLOW_EVIDENCE_CHUNK_BYTES
        or not isinstance(payload.get("chunkSha256"), str)
        or re.fullmatch(r"[0-9a-f]{64}", str(payload["chunkSha256"])) is None
        or not isinstance(data_base64, str)
        or len(data_base64) > ((MAX_WORKFLOW_EVIDENCE_CHUNK_BYTES + 2) // 3) * 4 + 4
    ):
        return None
    try:
        content = base64.b64decode(data_base64, validate=True)
    except (ValueError, TypeError):
        return None
    if len(content) != chunk_byte_size or _sha256(content) != payload["chunkSha256"]:
        return None
    command = dict(payload)
    command.pop("dataBase64")
    command["bytes"] = content
    return command


def _workflow_evidence_complete_command(
    payload: Mapping[str, object]
) -> Optional[dict[str, object]]:
    if set(payload) != {"uploadId", "chunkCount", "sha256"}:
        return None
    chunk_count = payload.get("chunkCount")
    return (
        dict(payload)
        if (
            _valid_uuid(payload.get("uploadId"))
            and isinstance(chunk_count, int)
            and not isinstance(chunk_count, bool)
            and 1 <= chunk_count <= MAX_WORKFLOW_EVIDENCE_CHUNKS
            and isinstance(payload.get("sha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", str(payload["sha256"])) is not None
        )
        else None
    )


def _workflow_evidence_cancel_command(
    payload: Mapping[str, object]
) -> Optional[dict[str, object]]:
    return (
        dict(payload)
        if (
            set(payload) == {"uploadId", "assetId"}
            and _valid_uuid(payload.get("uploadId"))
            and _valid_uuid(payload.get("assetId"))
        )
        else None
    )


def _valid_workflow_evidence_metadata(payload: Mapping[str, object]) -> bool:
    kind = payload.get("kind")
    content_type = payload.get("contentType")
    duration = payload.get("durationSeconds")
    byte_size = payload.get("byteSize")
    maximum = MAX_WORKFLOW_VIDEO_BYTES if kind == "video" else MAX_WORKFLOW_PHOTO_BYTES
    if (
        not _valid_uuid(payload.get("assignmentId"))
        or not _valid_uuid(payload.get("executionId"))
        or not _valid_identifier(payload.get("localEvidenceId"), 160)
        or not _valid_identifier(payload.get("nodeId"), 160)
        or not _valid_identifier(payload.get("evidenceKey"), 160)
        or not isinstance(duration, int)
        or isinstance(duration, bool)
        or not isinstance(byte_size, int)
        or isinstance(byte_size, bool)
        or byte_size < 1
        or byte_size > maximum
        or not isinstance(payload.get("sha256"), str)
        or re.fullmatch(r"[0-9a-f]{64}", str(payload["sha256"])) is None
        or not _valid_iso_timestamp(payload.get("capturedAt"))
    ):
        return False
    if kind == "photo":
        return content_type == "image/jpeg" and duration == 0
    if kind == "video":
        return content_type == "video/mp4" and 1 <= duration <= 15
    return False


def _workflow_evidence_expected_chunk_size(
    byte_size: int, chunk_size: int, chunk_count: int, chunk_index: int
) -> int:
    if chunk_index < chunk_count - 1:
        return chunk_size
    return byte_size - chunk_size * (chunk_count - 1)


def _valid_workflow_media_file(kind: str, path: Path, byte_size: int) -> bool:
    if byte_size < 4:
        return False
    with path.open("rb") as stream:
        head = stream.read(32)
        if kind == "photo":
            stream.seek(-2, os.SEEK_END)
            return head.startswith(b"\xff\xd8") and stream.read(2) == b"\xff\xd9"
    return kind == "video" and byte_size >= 12 and b"ftyp" in head


def _matching_workflow_evidence(
    row: sqlite3.Row, command: Mapping[str, object]
) -> bool:
    return (
        _matching_workflow_evidence_metadata(row, command)
        and str(row["upload_status"]) == "synced"
        and Path(str(row["file_path"])).is_file()
    )


def _matching_workflow_evidence_metadata(
    row: sqlite3.Row, command: Mapping[str, object]
) -> bool:
    return (
        str(row["assignment_id"]) == command["assignmentId"]
        and str(row["execution_id"]) == command["executionId"]
        and str(row["local_evidence_id"]) == command["localEvidenceId"]
        and str(row["node_id"]) == command["nodeId"]
        and str(row["evidence_key"]) == command["evidenceKey"]
        and str(row["kind"]) == command["kind"]
        and str(row["content_type"]) == command["contentType"]
        and int(row["duration_seconds"]) == command["durationSeconds"]
        and int(row["byte_size"]) == command["byteSize"]
        and str(row["sha256"]) == command["sha256"]
        and str(row["captured_at"]) == command["capturedAt"]
    )


def _workflow_evidence_row(row: sqlite3.Row) -> dict:
    response = {
        "assetId": str(row["asset_id"]),
        "uploadStatus": str(row["upload_status"]),
        "byteSize": int(row["byte_size"]),
        "sha256": str(row["sha256"]),
    }
    if str(row["kind"]) == "video":
        response["durationSeconds"] = int(row["duration_seconds"])
    return response


def _sse(event: str, data: dict) -> bytes:
    payload = json.dumps(data, ensure_ascii=False)
    return f"event: {event}\ndata: {payload}\n\n".encode("utf-8")


if __name__ == "__main__":
    run()
