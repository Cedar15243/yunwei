from __future__ import annotations

import base64
import hashlib
import json
import secrets
import socket
import ssl
import struct
import threading
import urllib.parse
import uuid
from dataclasses import dataclass
from typing import BinaryIO, Callable, Mapping, Union


PREVIOUS_STABLE_ASR_MODEL = "fun-asr-realtime"
MAX_WEBSOCKET_FRAME_BYTES = 1024 * 1024
WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


class WebSocketProtocolError(RuntimeError):
    pass


@dataclass(frozen=True)
class WebSocketFrame:
    opcode: int
    payload: bytes
    final: bool = True


@dataclass(frozen=True)
class ProtocolAction:
    target: str
    opcode: str
    payload: Union[str, bytes] = b""


def websocket_accept_value(headers: Mapping[str, str]) -> str:
    upgrade = str(headers.get("Upgrade", "")).strip().lower()
    connection_tokens = {
        token.strip().lower() for token in str(headers.get("Connection", "")).split(",")
    }
    version = str(headers.get("Sec-WebSocket-Version", "")).strip()
    key = str(headers.get("Sec-WebSocket-Key", "")).strip()
    if upgrade != "websocket" or "upgrade" not in connection_tokens or version != "13":
        raise WebSocketProtocolError("websocket_upgrade_required")
    try:
        decoded_key = base64.b64decode(key, validate=True)
    except (ValueError, TypeError):
        raise WebSocketProtocolError("websocket_key_invalid") from None
    if len(decoded_key) != 16:
        raise WebSocketProtocolError("websocket_key_invalid")
    digest = hashlib.sha1((key + WEBSOCKET_GUID).encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def connect_dashscope_asr(
    url: str,
    api_key: str,
    *,
    timeout_seconds: int = 15,
    tls_socket_factory: Callable[[str, int, int], socket.socket] | None = None,
) -> socket.socket:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "wss" or not parsed.hostname:
        raise WebSocketProtocolError("asr_provider_requires_wss")
    if not api_key.strip():
        raise WebSocketProtocolError("asr_provider_credential_missing")
    port = parsed.port or 443
    secure_socket: socket.socket | None = None
    try:
        secure_socket = (
            tls_socket_factory(parsed.hostname, port, timeout_seconds)
            if tls_socket_factory is not None
            else _open_ipv4_tls(parsed.hostname, port, timeout_seconds)
        )
        websocket_key = base64.b64encode(secrets.token_bytes(16)).decode("ascii")
        path = parsed.path or "/"
        if parsed.query:
            path += "?" + parsed.query
        host_header = parsed.hostname if port == 443 else f"{parsed.hostname}:{port}"
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host_header}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {websocket_key}\r\n"
            "Sec-WebSocket-Version: 13\r\n"
            f"Authorization: Bearer {api_key.strip()}\r\n\r\n"
        ).encode("ascii")
        secure_socket.sendall(request)
        response_head = _read_http_head(secure_socket)
        lines = response_head.decode("iso-8859-1").split("\r\n")
        if not lines or not re_fullmatch_http_101(lines[0]):
            raise WebSocketProtocolError("asr_provider_handshake_failed")
        response_headers: dict[str, str] = {}
        for line in lines[1:]:
            if not line or ":" not in line:
                continue
            name, value = line.split(":", 1)
            response_headers[name.strip().lower()] = value.strip()
        expected_accept = base64.b64encode(
            hashlib.sha1((websocket_key + WEBSOCKET_GUID).encode("ascii")).digest()
        ).decode("ascii")
        if response_headers.get("sec-websocket-accept") != expected_accept:
            raise WebSocketProtocolError("asr_provider_accept_invalid")
        secure_socket.settimeout(None)
        return secure_socket
    except Exception:
        if secure_socket is not None:
            secure_socket.close()
        raise


def encode_websocket_frame(
    payload: bytes,
    *,
    opcode: int,
    masked: bool,
    mask_key: bytes | None = None,
) -> bytes:
    payload = bytes(payload)
    first = 0x80 | (opcode & 0x0F)
    length = len(payload)
    mask_bit = 0x80 if masked else 0
    if length < 126:
        header = bytes((first, mask_bit | length))
    elif length <= 0xFFFF:
        header = bytes((first, mask_bit | 126)) + struct.pack("!H", length)
    else:
        header = bytes((first, mask_bit | 127)) + struct.pack("!Q", length)
    if not masked:
        return header + payload
    key = mask_key if mask_key is not None else secrets.token_bytes(4)
    if len(key) != 4:
        raise ValueError("websocket_mask_key_invalid")
    masked_payload = bytes(value ^ key[index % 4] for index, value in enumerate(payload))
    return header + key + masked_payload


def read_websocket_frame(
    stream: BinaryIO,
    *,
    expect_masked: bool,
    max_payload_bytes: int = MAX_WEBSOCKET_FRAME_BYTES,
) -> WebSocketFrame:
    header = _read_exact(stream, 2)
    first, second = header
    final = bool(first & 0x80)
    if first & 0x70:
        raise WebSocketProtocolError("websocket_extensions_not_supported")
    if not final:
        raise WebSocketProtocolError("websocket_fragmentation_not_supported")
    opcode = first & 0x0F
    if opcode not in {0x1, 0x2, 0x8, 0x9, 0xA}:
        raise WebSocketProtocolError("websocket_opcode_not_supported")
    masked = bool(second & 0x80)
    if expect_masked and not masked:
        raise WebSocketProtocolError("websocket_mask_required")
    if not expect_masked and masked:
        raise WebSocketProtocolError("websocket_unexpected_mask")
    length = second & 0x7F
    if length == 126:
        length = struct.unpack("!H", _read_exact(stream, 2))[0]
    elif length == 127:
        encoded_length = _read_exact(stream, 8)
        if encoded_length[0] & 0x80:
            raise WebSocketProtocolError("websocket_length_invalid")
        length = struct.unpack("!Q", encoded_length)[0]
    if length > max_payload_bytes:
        raise WebSocketProtocolError("websocket_frame_too_large")
    if opcode >= 0x8 and length > 125:
        raise WebSocketProtocolError("websocket_control_frame_too_large")
    key = _read_exact(stream, 4) if masked else b""
    payload = _read_exact(stream, length)
    if masked:
        payload = bytes(value ^ key[index % 4] for index, value in enumerate(payload))
    return WebSocketFrame(opcode=opcode, payload=payload, final=final)


def relay_asr_websockets(
    client_socket: socket.socket,
    provider_socket: socket.socket,
    *,
    session_id: str,
    model: str = PREVIOUS_STABLE_ASR_MODEL,
    client_reader: BinaryIO | None = None,
    provider_reader: BinaryIO | None = None,
) -> None:
    protocol = FunAsrProtocol(session_id=session_id, model=model)
    client_stream = client_reader or client_socket.makefile("rb")
    provider_stream = provider_reader or provider_socket.makefile("rb")
    owns_client_stream = client_reader is None
    owns_provider_stream = provider_reader is None
    client_write_lock = threading.Lock()
    provider_write_lock = threading.Lock()
    protocol_lock = threading.RLock()
    stopped = threading.Event()

    def send_frame(target: str, opcode: int, payload: bytes = b"") -> None:
        if target == "client":
            active_socket = client_socket
            masked = False
            write_lock = client_write_lock
        else:
            active_socket = provider_socket
            masked = True
            write_lock = provider_write_lock
        encoded = encode_websocket_frame(payload, opcode=opcode, masked=masked)
        with write_lock:
            active_socket.sendall(encoded)

    def dispatch(actions: list[ProtocolAction]) -> None:
        for action in actions:
            if stopped.is_set():
                return
            if action.opcode == "text":
                payload = (
                    action.payload.encode("utf-8")
                    if isinstance(action.payload, str)
                    else bytes(action.payload)
                )
                send_frame(action.target, 0x1, payload)
            elif action.opcode == "binary":
                send_frame(action.target, 0x2, bytes(action.payload))
            elif action.opcode == "close":
                send_frame(action.target, 0x8)
                stopped.set()

    def fail_client(code: str) -> None:
        if stopped.is_set():
            return
        try:
            send_frame("client", 0x1, _client_event("error", code=code).encode("utf-8"))
            send_frame("client", 0x8)
        except OSError:
            pass
        stopped.set()

    def read_client() -> None:
        try:
            while not stopped.is_set():
                frame = read_websocket_frame(client_stream, expect_masked=True)
                if frame.opcode == 0x8:
                    try:
                        send_frame("provider", 0x8)
                    except OSError:
                        pass
                    stopped.set()
                    return
                if frame.opcode == 0x9:
                    send_frame("client", 0xA, frame.payload)
                    continue
                if frame.opcode == 0xA:
                    continue
                with protocol_lock:
                    if frame.opcode == 0x1:
                        actions = protocol.handle_client_text(frame.payload.decode("utf-8"))
                    else:
                        actions = protocol.handle_client_binary(frame.payload)
                dispatch(actions)
        except (EOFError, OSError, UnicodeDecodeError):
            stopped.set()
        except WebSocketProtocolError:
            fail_client("asr_protocol_error")

    def read_provider() -> None:
        try:
            while not stopped.is_set():
                frame = read_websocket_frame(provider_stream, expect_masked=False)
                if frame.opcode == 0x8:
                    fail_client("asr_unavailable")
                    return
                if frame.opcode == 0x9:
                    send_frame("provider", 0xA, frame.payload)
                    continue
                if frame.opcode == 0xA:
                    continue
                if frame.opcode != 0x1:
                    raise WebSocketProtocolError("asr_provider_frame_unsupported")
                with protocol_lock:
                    actions = protocol.handle_provider_text(frame.payload.decode("utf-8"))
                dispatch(actions)
        except (EOFError, OSError, UnicodeDecodeError, WebSocketProtocolError):
            fail_client("asr_unavailable")

    workers = (
        threading.Thread(target=read_client, name="AsrClientReader", daemon=True),
        threading.Thread(target=read_provider, name="AsrProviderReader", daemon=True),
    )
    for worker in workers:
        worker.start()
    stopped.wait()
    for active_socket in (client_socket, provider_socket):
        try:
            active_socket.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
    for worker in workers:
        worker.join(timeout=2)
    if owns_client_stream:
        client_stream.close()
    if owns_provider_stream:
        provider_stream.close()


class FunAsrProtocol:
    MAX_AUDIO_BYTES = 512 * 1024

    def __init__(
        self,
        *,
        session_id: str,
        model: str = PREVIOUS_STABLE_ASR_MODEL,
        task_id_factory: Callable[[], str] = lambda: str(uuid.uuid4()),
    ):
        if model != PREVIOUS_STABLE_ASR_MODEL:
            raise ValueError("asr_model_not_previous_stable")
        self.session_id = session_id
        self.model = model
        self.task_id_factory = task_id_factory
        self.task_id = ""
        self.started = False
        self.provider_ready = False
        self.finish_requested = False
        self.finished = False
        self.latest_transcript = ""
        self.buffered_audio: list[bytes] = []
        self.buffered_audio_bytes = 0

    def handle_client_text(self, text: str) -> list[ProtocolAction]:
        try:
            message = json.loads(text)
        except (TypeError, ValueError):
            raise WebSocketProtocolError("asr_client_message_invalid") from None
        if not isinstance(message, dict):
            raise WebSocketProtocolError("asr_client_message_invalid")
        message_type = str(message.get("type") or "").strip()
        if message_type == "start":
            return self._start(message)
        if message_type == "finish":
            return self._finish()
        raise WebSocketProtocolError("asr_client_message_unsupported")

    def handle_client_binary(self, audio: bytes) -> list[ProtocolAction]:
        if not self.started or self.finished:
            raise WebSocketProtocolError("asr_audio_out_of_sequence")
        audio = bytes(audio)
        if not audio:
            return []
        if len(audio) > self.MAX_AUDIO_BYTES:
            raise WebSocketProtocolError("asr_audio_buffer_exceeded")
        if self.provider_ready:
            return [ProtocolAction("provider", "binary", audio)]
        if self.buffered_audio_bytes + len(audio) > self.MAX_AUDIO_BYTES:
            raise WebSocketProtocolError("asr_audio_buffer_exceeded")
        self.buffered_audio.append(audio)
        self.buffered_audio_bytes += len(audio)
        return []

    def handle_provider_text(self, text: str) -> list[ProtocolAction]:
        try:
            message = json.loads(text)
        except (TypeError, ValueError):
            raise WebSocketProtocolError("asr_provider_message_invalid") from None
        if not isinstance(message, dict):
            raise WebSocketProtocolError("asr_provider_message_invalid")
        header = message.get("header")
        payload = message.get("payload")
        header = header if isinstance(header, dict) else {}
        payload = payload if isinstance(payload, dict) else {}
        event = str(header.get("event") or header.get("status") or "").strip()
        if event == "task-started":
            return self._provider_started()
        if event == "result-generated":
            transcript = _extract_transcript(payload)
            if not transcript:
                return []
            self.latest_transcript = transcript
            return [ProtocolAction("client", "text", _client_event("partial", text=transcript))]
        if event == "task-finished":
            self.finished = True
            return [
                ProtocolAction(
                    "client", "text", _client_event("final", text=self.latest_transcript)
                ),
                ProtocolAction("client", "close"),
            ]
        if event == "task-failed" or "failed" in event or "error" in event:
            self.finished = True
            return [
                ProtocolAction(
                    "client", "text", _client_event("error", code="asr_unavailable")
                ),
                ProtocolAction("client", "close"),
            ]
        return []

    def _start(self, message: dict) -> list[ProtocolAction]:
        if self.started or self.finished:
            raise WebSocketProtocolError("asr_start_out_of_sequence")
        try:
            sample_rate = int(message.get("sample_rate", message.get("sampleRate", 16000)))
        except (TypeError, ValueError):
            raise WebSocketProtocolError("asr_sample_rate_unsupported") from None
        audio_format = str(message.get("format") or "pcm").strip().lower()
        if sample_rate != 16000:
            raise WebSocketProtocolError("asr_sample_rate_unsupported")
        if audio_format != "pcm":
            raise WebSocketProtocolError("asr_format_unsupported")
        self.started = True
        self.task_id = self.task_id_factory()
        if not self.task_id:
            raise WebSocketProtocolError("asr_task_id_missing")
        event = {
            "header": {
                "action": "run-task",
                "task_id": self.task_id,
                "streaming": "duplex",
            },
            "payload": {
                "model": self.model,
                "task_group": "audio",
                "task": "asr",
                "function": "recognition",
                "input": {
                    "session_id": self.session_id,
                    "format": audio_format,
                    "sample_rate": sample_rate,
                },
                "parameters": {
                    "format": audio_format,
                    "sample_rate": sample_rate,
                    "language_hints": ["zh"],
                },
            },
        }
        return [ProtocolAction("provider", "text", json.dumps(event, ensure_ascii=False))]

    def _finish(self) -> list[ProtocolAction]:
        if not self.started or self.finished:
            raise WebSocketProtocolError("asr_finish_out_of_sequence")
        if self.finish_requested:
            return []
        self.finish_requested = True
        if not self.provider_ready:
            return []
        return [self._finish_action()]

    def _provider_started(self) -> list[ProtocolAction]:
        if not self.started or self.provider_ready or self.finished:
            raise WebSocketProtocolError("asr_provider_start_out_of_sequence")
        self.provider_ready = True
        actions = [ProtocolAction("client", "text", _client_event("ready"))]
        actions.extend(
            ProtocolAction("provider", "binary", audio) for audio in self.buffered_audio
        )
        self.buffered_audio.clear()
        self.buffered_audio_bytes = 0
        if self.finish_requested:
            actions.append(self._finish_action())
        return actions

    def _finish_action(self) -> ProtocolAction:
        event = {
            "header": {
                "action": "finish-task",
                "task_id": self.task_id,
                "streaming": "duplex",
            },
            "payload": {"input": {}},
        }
        return ProtocolAction("provider", "text", json.dumps(event, ensure_ascii=False))


def _read_exact(stream: BinaryIO, length: int) -> bytes:
    chunks: list[bytes] = []
    remaining = length
    while remaining:
        chunk = stream.read(remaining)
        if not chunk:
            raise EOFError("websocket_stream_closed")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def _connect_ipv4(host: str, port: int, timeout_seconds: int) -> socket.socket:
    last_error: OSError | None = None
    for family, socktype, protocol, _canonical_name, address in socket.getaddrinfo(
        host, port, socket.AF_INET, socket.SOCK_STREAM
    ):
        active_socket: socket.socket | None = None
        try:
            active_socket = socket.socket(family, socktype, protocol)
            active_socket.settimeout(timeout_seconds)
            active_socket.connect(address)
            return active_socket
        except OSError as error:
            last_error = error
            if active_socket is not None:
                active_socket.close()
    if last_error is not None:
        raise last_error
    raise OSError("asr_provider_ipv4_address_unavailable")


def _open_ipv4_tls(host: str, port: int, timeout_seconds: int) -> socket.socket:
    raw_socket = _connect_ipv4(host, port, timeout_seconds)
    try:
        return ssl.create_default_context().wrap_socket(raw_socket, server_hostname=host)
    except Exception:
        raw_socket.close()
        raise


def _read_http_head(active_socket: socket.socket, limit: int = 16 * 1024) -> bytes:
    received = bytearray()
    while not received.endswith(b"\r\n\r\n"):
        if len(received) >= limit:
            raise WebSocketProtocolError("asr_provider_headers_too_large")
        chunk = active_socket.recv(1)
        if not chunk:
            raise WebSocketProtocolError("asr_provider_handshake_closed")
        received.extend(chunk)
    return bytes(received[:-4])


def re_fullmatch_http_101(status_line: str) -> bool:
    parts = status_line.strip().split()
    return len(parts) >= 2 and parts[0] in {"HTTP/1.0", "HTTP/1.1"} and parts[1] == "101"


def _extract_transcript(payload: dict) -> str:
    output = payload.get("output")
    output = output if isinstance(output, dict) else payload
    sentence = output.get("sentence")
    sentence = sentence if isinstance(sentence, dict) else {}
    candidates = (
        sentence.get("text"),
        output.get("text"),
        output.get("transcription"),
        payload.get("text"),
    )
    for candidate in candidates:
        if isinstance(candidate, str) and candidate.strip():
            return candidate.strip()
    return ""


def _client_event(event_type: str, **fields: str) -> str:
    event = {"type": event_type}
    event.update({key: value for key, value in fields.items() if value is not None})
    return json.dumps(event, ensure_ascii=False)
