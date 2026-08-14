import base64
import hashlib
import io
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import zipfile

from gateway import (
    GatewayConfig,
    GatewayHttpServer,
    GatewayService,
    SqliteStore,
    configuration_from_environment,
)


def docx_bytes() -> bytes:
    xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>检查控制器供电</w:t></w:r></w:p><w:p><w:r><w:t>确认总线极性</w:t></w:r></w:p></w:body></w:document>"""
    target = io.BytesIO()
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("word/document.xml", xml)
    return target.getvalue()


class UnusedProvider:
    def stream_chat(self, *_args, **_kwargs):
        raise AssertionError("knowledge parser must not call the AI provider")


class KnowledgeDocumentGatewayTest(unittest.TestCase):
    def setUp(self):
        self.parser_token = "server-only-knowledge-parser-token"
        self.temp = tempfile.TemporaryDirectory()
        self.store = SqliteStore(
            f"{self.temp.name}/gateway.db",
            f"{self.temp.name}/evidence",
        )
        self.service = GatewayService(
            GatewayConfig(
                bootstrap_token_hash=hashlib.sha256(b"bootstrap").hexdigest(),
                device_id="air3-parser-test",
                organization_id="org-huafang",
                user_id="user-admin",
                provider_base_url="https://dashscope.aliyuncs.com/compatible-mode/v1",
                provider_api_key="server-only-provider-key",
                provider_model="qwen3-vl-plus",
                knowledge_parser_token_hash=hashlib.sha256(
                    self.parser_token.encode("utf-8")
                ).hexdigest(),
            ),
            self.store,
            UnusedProvider(),
        )

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def payload(self):
        data = docx_bytes()
        return {
            "fileName": "controller.docx",
            "contentType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "sha256": hashlib.sha256(data).hexdigest(),
            "dataBase64": base64.b64encode(data).decode("ascii"),
        }

    def test_service_requires_the_dedicated_token_and_returns_only_extracted_content(self):
        unauthorized = self.service.parse_knowledge_document("", self.payload())
        response = self.service.parse_knowledge_document(
            f"Bearer {self.parser_token}", self.payload()
        )

        self.assertEqual(401, unauthorized.status)
        self.assertEqual(200, response.status)
        self.assertEqual("检查控制器供电\n确认总线极性", response.json_body["content"])
        self.assertEqual(
            hashlib.sha256(response.json_body["content"].encode("utf-8")).hexdigest(),
            response.json_body["contentSha256"],
        )
        serialized = json.dumps(response.json_body, ensure_ascii=False)
        self.assertNotIn(self.parser_token, serialized)
        self.assertNotIn("dataBase64", serialized)

    def test_http_route_exposes_no_device_or_provider_credentials(self):
        server = GatewayHttpServer(("127.0.0.1", 0), self.service)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            request = urllib.request.Request(
                f"http://127.0.0.1:{server.server_port}/internal/knowledge/parse",
                data=json.dumps(self.payload()).encode("utf-8"),
                headers={
                    "Authorization": f"Bearer {self.parser_token}",
                    "Content-Type": "application/json",
                },
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=3) as response:
                body = json.loads(response.read())
            self.assertEqual(200, response.status)
            self.assertEqual("no-store", response.headers["Cache-Control"])
            self.assertEqual("检查控制器供电\n确认总线极性", body["content"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_environment_rejects_a_malformed_parser_token_hash(self):
        environment = {
            "V9_BOOTSTRAP_TOKEN_SHA256": hashlib.sha256(b"bootstrap").hexdigest(),
            "V9_DEVICE_ID": "air3-parser-test",
            "V9_ORGANIZATION_ID": "org-huafang",
            "V9_USER_ID": "user-admin",
            "V9_AI_BASE_URL": "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "V9_AI_API_KEY": "server-only-provider-key",
            "V9_AI_MODEL": "qwen3-vl-plus",
            "V9_KNOWLEDGE_PARSER_TOKEN_SHA256": "not-a-sha256",
        }
        with self.assertRaisesRegex(
            ValueError,
            "knowledge_parser_token_hash_invalid",
        ):
            configuration_from_environment(environment)


if __name__ == "__main__":
    unittest.main()
