import io
import json
import socket
import threading
import unittest

from asr_proxy import (
    FunAsrProtocol,
    WebSocketProtocolError,
    connect_dashscope_asr,
    encode_websocket_frame,
    read_websocket_frame,
    relay_asr_websockets,
)


class FakeProviderSocket:
    def __init__(self):
        self.sent = b""
        self.response = io.BytesIO()
        self.timeout = None
        self.closed = False

    def sendall(self, payload):
        self.sent += payload
        request = payload.decode("ascii")
        websocket_key = next(
            line.split(":", 1)[1].strip()
            for line in request.split("\r\n")
            if line.lower().startswith("sec-websocket-key:")
        )
        import base64
        import hashlib

        accept = base64.b64encode(
            hashlib.sha1(
                (websocket_key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()
            ).digest()
        ).decode()
        self.response = io.BytesIO(
            (
                "HTTP/1.1 101 Switching Protocols\r\n"
                "Upgrade: websocket\r\n"
                "Connection: Upgrade\r\n"
                f"Sec-WebSocket-Accept: {accept}\r\n\r\n"
            ).encode()
        )

    def recv(self, length):
        return self.response.read(length)

    def settimeout(self, timeout):
        self.timeout = timeout

    def close(self):
        self.closed = True


class FunAsrProtocolTest(unittest.TestCase):
    def setUp(self):
        self.protocol = FunAsrProtocol(
            session_id="session-a",
            model="fun-asr-realtime",
            task_id_factory=lambda: "task-a",
        )

    def test_uses_previous_model_and_the_same_task_id_for_finish(self):
        started = self.protocol.handle_client_text(
            json.dumps({"type": "start", "sample_rate": 16000, "format": "pcm"})
        )
        run_task = json.loads(started[0].payload)

        provider_ready = self.protocol.handle_provider_text(
            json.dumps({"header": {"event": "task-started"}, "payload": {}})
        )
        finished = self.protocol.handle_client_text(json.dumps({"type": "finish"}))
        finish_task = json.loads(finished[0].payload)

        self.assertEqual("provider", started[0].target)
        self.assertEqual("fun-asr-realtime", run_task["payload"]["model"])
        self.assertEqual("task-a", run_task["header"]["task_id"])
        self.assertEqual("ready", json.loads(provider_ready[0].payload)["type"])
        self.assertEqual("task-a", finish_task["header"]["task_id"])

    def test_queues_audio_until_provider_is_ready_and_caps_memory(self):
        self.protocol.handle_client_text(
            json.dumps({"type": "start", "sample_rate": 16000, "format": "pcm"})
        )
        self.assertEqual([], self.protocol.handle_client_binary(b"pcm-a"))

        actions = self.protocol.handle_provider_text(
            json.dumps({"header": {"event": "task-started"}, "payload": {}})
        )

        self.assertEqual("client", actions[0].target)
        self.assertEqual("provider", actions[1].target)
        self.assertEqual(b"pcm-a", actions[1].payload)
        with self.assertRaisesRegex(WebSocketProtocolError, "asr_audio_buffer_exceeded"):
            self.protocol.handle_client_binary(b"x" * (FunAsrProtocol.MAX_AUDIO_BYTES + 1))

    def test_forwards_partial_and_only_finalizes_on_task_finished(self):
        self.protocol.handle_client_text(
            json.dumps({"type": "start", "sample_rate": 16000, "format": "pcm"})
        )
        self.protocol.handle_provider_text(
            json.dumps({"header": {"event": "task-started"}, "payload": {}})
        )

        partial = self.protocol.handle_provider_text(
            json.dumps(
                {
                    "header": {"event": "result-generated"},
                    "payload": {"output": {"sentence": {"text": "检查电源"}}},
                }
            )
        )
        final = self.protocol.handle_provider_text(
            json.dumps({"header": {"event": "task-finished"}, "payload": {}})
        )

        self.assertEqual("partial", json.loads(partial[0].payload)["type"])
        self.assertEqual("检查电源", json.loads(partial[0].payload)["text"])
        self.assertEqual("final", json.loads(final[0].payload)["type"])
        self.assertEqual("检查电源", json.loads(final[0].payload)["text"])
        self.assertEqual("close", final[1].opcode)

    def test_provider_failure_is_normalized_without_leaking_vendor_payload(self):
        failed = self.protocol.handle_provider_text(
            json.dumps(
                {
                    "header": {
                        "event": "task-failed",
                        "error_code": "InvalidApiKey",
                        "error_message": "secret vendor details",
                    }
                }
            )
        )

        error = json.loads(failed[0].payload)
        self.assertEqual("error", error["type"])
        self.assertEqual("asr_unavailable", error["code"])
        self.assertNotIn("secret vendor details", failed[0].payload)


class WebSocketFrameTest(unittest.TestCase):
    def test_decodes_masked_client_binary_and_unmasked_provider_text(self):
        masked = encode_websocket_frame(
            b"pcm", opcode=0x2, masked=True, mask_key=b"\x01\x02\x03\x04"
        )
        provider = encode_websocket_frame(b'{"ok":true}', opcode=0x1, masked=False)

        client_frame = read_websocket_frame(io.BytesIO(masked), expect_masked=True)
        provider_frame = read_websocket_frame(io.BytesIO(provider), expect_masked=False)

        self.assertEqual(0x2, client_frame.opcode)
        self.assertEqual(b"pcm", client_frame.payload)
        self.assertEqual(0x1, provider_frame.opcode)
        self.assertEqual(b'{"ok":true}', provider_frame.payload)

    def test_rejects_unmasked_client_frames_and_oversized_payloads(self):
        unmasked = encode_websocket_frame(b"bad", opcode=0x2, masked=False)
        oversized_header = bytes([0x82, 127]) + (2_000_000).to_bytes(8, "big")

        with self.assertRaisesRegex(WebSocketProtocolError, "websocket_mask_required"):
            read_websocket_frame(io.BytesIO(unmasked), expect_masked=True)
        with self.assertRaisesRegex(WebSocketProtocolError, "websocket_frame_too_large"):
            read_websocket_frame(io.BytesIO(oversized_header), expect_masked=False)

    def test_provider_connection_uses_wss_ipv4_factory_and_authorization(self):
        provider = FakeProviderSocket()

        connected = connect_dashscope_asr(
            "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
            "server-only-asr-key",
            tls_socket_factory=lambda host, port, timeout: provider,
        )

        request = provider.sent.decode("ascii")
        self.assertIs(provider, connected)
        self.assertIn("GET /api-ws/v1/inference HTTP/1.1", request)
        self.assertIn("Host: dashscope.aliyuncs.com", request)
        self.assertIn("Authorization: Bearer server-only-asr-key", request)
        self.assertIsNone(provider.timeout)


class AsrRelayTest(unittest.TestCase):
    def test_relays_the_backend_protocol_to_the_previous_funasr_model(self):
        gateway_client, glasses = socket.socketpair()
        gateway_provider, provider = socket.socketpair()
        for active_socket in (gateway_client, glasses, gateway_provider, provider):
            active_socket.settimeout(2)
        relay = threading.Thread(
            target=relay_asr_websockets,
            args=(gateway_client, gateway_provider),
            kwargs={"session_id": "session-relay", "model": "fun-asr-realtime"},
            daemon=True,
        )
        relay.start()
        glasses_reader = glasses.makefile("rb")
        provider_reader = provider.makefile("rb")
        try:
            glasses.sendall(
                encode_websocket_frame(
                    json.dumps(
                        {"type": "start", "sample_rate": 16000, "format": "pcm"}
                    ).encode(),
                    opcode=0x1,
                    masked=True,
                    mask_key=b"\x01\x02\x03\x04",
                )
            )
            run_task_frame = read_websocket_frame(provider_reader, expect_masked=True)
            run_task = json.loads(run_task_frame.payload)

            glasses.sendall(
                encode_websocket_frame(
                    b"early-pcm",
                    opcode=0x2,
                    masked=True,
                    mask_key=b"\x05\x06\x07\x08",
                )
            )
            provider.sendall(
                encode_websocket_frame(
                    json.dumps(
                        {"header": {"event": "task-started"}, "payload": {}}
                    ).encode(),
                    opcode=0x1,
                    masked=False,
                )
            )

            ready = json.loads(
                read_websocket_frame(glasses_reader, expect_masked=False).payload
            )
            forwarded_audio = read_websocket_frame(provider_reader, expect_masked=True)

            glasses.sendall(
                encode_websocket_frame(
                    b'{"type":"finish"}',
                    opcode=0x1,
                    masked=True,
                    mask_key=b"\x09\x0a\x0b\x0c",
                )
            )
            finish_task = json.loads(
                read_websocket_frame(provider_reader, expect_masked=True).payload
            )
            provider.sendall(
                encode_websocket_frame(
                    json.dumps(
                        {
                            "header": {"event": "result-generated"},
                            "payload": {
                                "output": {"sentence": {"text": "检查电源"}}
                            },
                        },
                        ensure_ascii=False,
                    ).encode("utf-8"),
                    opcode=0x1,
                    masked=False,
                )
                + encode_websocket_frame(
                    b'{"header":{"event":"task-finished"},"payload":{}}',
                    opcode=0x1,
                    masked=False,
                )
            )

            partial = json.loads(
                read_websocket_frame(glasses_reader, expect_masked=False).payload
            )
            final = json.loads(
                read_websocket_frame(glasses_reader, expect_masked=False).payload
            )
            close = read_websocket_frame(glasses_reader, expect_masked=False)

            self.assertEqual("fun-asr-realtime", run_task["payload"]["model"])
            self.assertEqual("ready", ready["type"])
            self.assertEqual(b"early-pcm", forwarded_audio.payload)
            self.assertEqual(
                run_task["header"]["task_id"], finish_task["header"]["task_id"]
            )
            self.assertEqual({"type": "partial", "text": "检查电源"}, partial)
            self.assertEqual({"type": "final", "text": "检查电源"}, final)
            self.assertEqual(0x8, close.opcode)
        finally:
            for stream in (glasses_reader, provider_reader):
                stream.close()
            for active_socket in (glasses, provider, gateway_client, gateway_provider):
                try:
                    active_socket.close()
                except OSError:
                    pass
            relay.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
