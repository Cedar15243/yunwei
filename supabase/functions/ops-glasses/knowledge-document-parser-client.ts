export type KnowledgeDocumentParserInput = {
  fileName: string;
  contentType: string;
  bytes: Uint8Array;
  fileSha256: string;
};

export type ParsedKnowledgeDocument = {
  content: string;
  contentSha256: string;
};

export type KnowledgeDocumentParser = {
  parse(input: KnowledgeDocumentParserInput): Promise<ParsedKnowledgeDocument>;
};

export class KnowledgeDocumentParserClientError extends Error {
  constructor(
    public readonly code: string,
    public readonly status: number,
  ) {
    super(code);
    this.name = "KnowledgeDocumentParserClientError";
  }
}

export function createKnowledgeDocumentParserClient(
  config: { baseUrl: string; token: string; timeoutMs?: number },
  fetcher: typeof fetch = fetch,
): KnowledgeDocumentParser | null {
  const baseUrl = String(config.baseUrl ?? "").trim().replace(/\/+$/, "");
  const token = String(config.token ?? "").trim();
  if (!baseUrl && !token) return null;
  if (!baseUrl || !token) {
    throw new Error("knowledge_document_parser_configuration_incomplete");
  }
  const parsedUrl = new URL(baseUrl);
  if (parsedUrl.protocol !== "https:") {
    throw new Error("knowledge_document_parser_url_must_be_https");
  }
  const timeoutMs = config.timeoutMs ?? 15_000;
  if (!Number.isInteger(timeoutMs) || timeoutMs < 1_000 || timeoutMs > 60_000) {
    throw new Error("knowledge_document_parser_timeout_invalid");
  }

  return {
    async parse(input) {
      let response: Response;
      try {
        response = await fetcher(`${baseUrl}/internal/knowledge/parse`, {
          method: "POST",
          headers: {
            Authorization: `Bearer ${token}`,
            "Content-Type": "application/json",
          },
          signal: AbortSignal.timeout(timeoutMs),
          body: JSON.stringify({
            fileName: input.fileName,
            contentType: input.contentType,
            sha256: input.fileSha256,
            dataBase64: bytesToBase64(input.bytes),
          }),
        });
      } catch {
        throw new KnowledgeDocumentParserClientError(
          "knowledge_attachment_processor_unavailable",
          503,
        );
      }
      const payload = await safeJson(response);
      if (!response.ok) {
        const code = typeof payload.error === "string" && /^[a-z0-9_]{1,120}$/.test(payload.error)
          ? payload.error
          : "knowledge_attachment_processor_failed";
        throw new KnowledgeDocumentParserClientError(code, response.status);
      }
      const content = typeof payload.content === "string" ? payload.content.trim() : "";
      const contentSha256 = typeof payload.contentSha256 === "string"
        ? payload.contentSha256.toLowerCase()
        : "";
      if (!content || !/^[0-9a-f]{64}$/.test(contentSha256)) {
        throw new KnowledgeDocumentParserClientError(
          "knowledge_attachment_processor_response_invalid",
          502,
        );
      }
      const actualSha256 = await sha256Hex(new TextEncoder().encode(content));
      if (actualSha256 !== contentSha256) {
        throw new KnowledgeDocumentParserClientError(
          "knowledge_attachment_processor_hash_mismatch",
          502,
        );
      }
      return { content, contentSha256 };
    },
  };
}

function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  for (let offset = 0; offset < bytes.byteLength; offset += 32_768) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 32_768));
  }
  return btoa(binary);
}

async function safeJson(response: Response): Promise<Record<string, unknown>> {
  try {
    const payload = await response.json();
    return typeof payload === "object" && payload !== null && !Array.isArray(payload)
      ? payload as Record<string, unknown>
      : {};
  } catch {
    return {};
  }
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const copy = Uint8Array.from(bytes);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", copy.buffer));
  return Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
}
