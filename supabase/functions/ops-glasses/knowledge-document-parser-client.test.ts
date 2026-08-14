import { assertEquals, assertRejects, assertThrows } from "jsr:@std/assert@1";
import {
  KnowledgeDocumentParserClientError,
  createKnowledgeDocumentParserClient,
} from "./knowledge-document-parser-client.ts";

Deno.test("calls the private HTTPS parser with a dedicated bearer token", async () => {
  let received: Record<string, unknown> | null = null;
  const content = "设备收货检查\n确认包装无破损";
  const contentSha256 = await sha256Hex(new TextEncoder().encode(content));
  const client = createKnowledgeDocumentParserClient({
    baseUrl: "https://gateway.example/v9-ops",
    token: "server-only-parser-token",
  }, async (input, init) => {
    received = {
      url: String(input),
      method: init?.method,
      authorization: new Headers(init?.headers).get("Authorization"),
      body: JSON.parse(String(init?.body)),
    };
    return new Response(JSON.stringify({
      ok: true,
      content,
      contentSha256,
    }), { status: 200, headers: { "Content-Type": "application/json" } });
  });

  const result = await client?.parse({
    fileName: "receipt.docx",
    contentType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    bytes: new Uint8Array([0x50, 0x4b, 0x03, 0x04]),
    fileSha256: "b".repeat(64),
  });

  assertEquals(received, {
    url: "https://gateway.example/v9-ops/internal/knowledge/parse",
    method: "POST",
    authorization: "Bearer server-only-parser-token",
    body: {
      fileName: "receipt.docx",
      contentType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      sha256: "b".repeat(64),
      dataBase64: "UEsDBA==",
    },
  });
  assertEquals(result, {
    content,
    contentSha256,
  });
});

Deno.test("keeps the parser optional but rejects partial or insecure configuration", () => {
  assertEquals(createKnowledgeDocumentParserClient({ baseUrl: "", token: "" }), null);
  assertThrows(
    () => createKnowledgeDocumentParserClient({
      baseUrl: "https://gateway.example/v9-ops",
      token: "",
    }),
    Error,
    "knowledge_document_parser_configuration_incomplete",
  );
  assertThrows(
    () => createKnowledgeDocumentParserClient({
      baseUrl: "http://gateway.example/v9-ops",
      token: "server-only-parser-token",
    }),
    Error,
    "knowledge_document_parser_url_must_be_https",
  );
});

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const copy = Uint8Array.from(bytes);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", copy.buffer));
  return Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

Deno.test("maps parser failures to stable recoverable errors without leaking the response", async () => {
  const client = createKnowledgeDocumentParserClient({
    baseUrl: "https://gateway.example/v9-ops",
    token: "server-only-parser-token",
  }, async () => new Response(JSON.stringify({
    ok: false,
    error: "knowledge_document_pdf_invalid",
    detail: "provider stack must not escape",
  }), { status: 422 }));

  await assertRejects(
    () => client!.parse({
      fileName: "manual.pdf",
      contentType: "application/pdf",
      bytes: new Uint8Array([0x25, 0x50, 0x44, 0x46]),
      fileSha256: "c".repeat(64),
    }),
    KnowledgeDocumentParserClientError,
    "knowledge_document_pdf_invalid",
  );
});
