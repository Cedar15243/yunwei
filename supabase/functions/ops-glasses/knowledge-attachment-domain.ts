export type KnowledgeAttachmentFileKind = "text" | "markdown" | "pdf" | "docx";

export type PreparedKnowledgeAttachment = {
  originalFileName: string;
  contentType: string;
  byteSize: number;
  fileSha256: string;
  fileKind: KnowledgeAttachmentFileKind;
  processorAvailable: boolean;
};

export type KnowledgeAttachmentUploadInput = {
  fileName: string;
  contentType: string;
  bytes: Uint8Array;
  claimedSha256: string;
};

const textMaximumBytes = 512_000;
const binaryMaximumBytes = 8_388_608;
const sha256Pattern = /^[0-9a-f]{64}$/;
const unsafeControlPattern = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/;

const allowedTypes: Readonly<Record<string, {
  kind: KnowledgeAttachmentFileKind;
  acceptedContentTypes: readonly string[];
  canonicalContentType: string;
  maximumBytes: number;
  processorAvailable: boolean;
}>> = {
  ".txt": {
    kind: "text",
    acceptedContentTypes: ["text/plain"],
    canonicalContentType: "text/plain",
    maximumBytes: textMaximumBytes,
    processorAvailable: true,
  },
  ".md": {
    kind: "markdown",
    acceptedContentTypes: ["text/markdown", "text/plain"],
    canonicalContentType: "text/markdown",
    maximumBytes: textMaximumBytes,
    processorAvailable: true,
  },
  ".markdown": {
    kind: "markdown",
    acceptedContentTypes: ["text/markdown", "text/plain"],
    canonicalContentType: "text/markdown",
    maximumBytes: textMaximumBytes,
    processorAvailable: true,
  },
  ".pdf": {
    kind: "pdf",
    acceptedContentTypes: ["application/pdf"],
    canonicalContentType: "application/pdf",
    maximumBytes: binaryMaximumBytes,
    processorAvailable: false,
  },
  ".docx": {
    kind: "docx",
    acceptedContentTypes: ["application/vnd.openxmlformats-officedocument.wordprocessingml.document"],
    canonicalContentType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    maximumBytes: binaryMaximumBytes,
    processorAvailable: false,
  },
};

export async function prepareKnowledgeAttachmentUpload(
  input: KnowledgeAttachmentUploadInput,
): Promise<PreparedKnowledgeAttachment> {
  const originalFileName = normalizeFileName(input.fileName);
  const extension = fileExtension(originalFileName);
  const policy = allowedTypes[extension];
  if (!policy) throw new Error("knowledge_attachment_type_not_allowed");

  const contentType = normalizeContentType(input.contentType);
  if (!policy.acceptedContentTypes.includes(contentType)) {
    throw new Error("knowledge_attachment_type_mismatch");
  }
  if (!(input.bytes instanceof Uint8Array) || input.bytes.byteLength === 0) {
    throw new Error("knowledge_attachment_empty");
  }
  if (input.bytes.byteLength > policy.maximumBytes) {
    throw new Error("knowledge_attachment_too_large");
  }
  validateSignature(policy.kind, input.bytes);

  const claimedSha256 = String(input.claimedSha256 ?? "").trim().toLowerCase();
  if (!sha256Pattern.test(claimedSha256)) {
    throw new Error("knowledge_attachment_sha256_invalid");
  }
  const fileSha256 = await sha256Hex(input.bytes);
  if (fileSha256 !== claimedSha256) {
    throw new Error("knowledge_attachment_sha256_mismatch");
  }

  return {
    originalFileName,
    contentType: policy.canonicalContentType,
    byteSize: input.bytes.byteLength,
    fileSha256,
    fileKind: policy.kind,
    processorAvailable: policy.processorAvailable,
  };
}

export async function parseKnowledgeTextAttachment(
  attachment: PreparedKnowledgeAttachment,
  bytes: Uint8Array,
): Promise<{ content: string; contentSha256: string }> {
  if (!attachment.processorAvailable || !["text", "markdown"].includes(attachment.fileKind)) {
    throw new Error("knowledge_attachment_processor_unavailable");
  }
  let decoded: string;
  try {
    decoded = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch {
    throw new Error("knowledge_attachment_text_invalid");
  }
  const content = decoded
    .replace(/^\uFEFF/, "")
    .replace(/\r\n?/g, "\n")
    .split("\n")
    .map((line) => line.replace(/[ \t]+$/g, ""))
    .join("\n")
    .trim();
  if (!content || content.length > textMaximumBytes || unsafeControlPattern.test(content)) {
    throw new Error("knowledge_attachment_text_invalid");
  }
  return {
    content,
    contentSha256: await sha256Hex(new TextEncoder().encode(content)),
  };
}

export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const copy = Uint8Array.from(bytes);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", copy.buffer));
  return Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function normalizeFileName(value: string): string {
  const fileName = String(value ?? "").normalize("NFC").trim();
  if (!fileName || fileName.length > 240 || /[\\/\u0000-\u001f\u007f]/.test(fileName)) {
    throw new Error("knowledge_attachment_file_name_invalid");
  }
  return fileName;
}

function fileExtension(fileName: string): string {
  const dot = fileName.lastIndexOf(".");
  return dot > 0 ? fileName.slice(dot).toLowerCase() : "";
}

function normalizeContentType(value: string): string {
  return String(value ?? "").split(";", 1)[0].trim().toLowerCase();
}

function validateSignature(kind: KnowledgeAttachmentFileKind, bytes: Uint8Array): void {
  if (kind === "pdf") {
    const signature = new TextDecoder().decode(bytes.subarray(0, 5));
    if (signature !== "%PDF-") throw new Error("knowledge_attachment_signature_invalid");
  }
  if (kind === "docx") {
    const isZip = bytes.byteLength >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b &&
      ((bytes[2] === 0x03 && bytes[3] === 0x04) ||
        (bytes[2] === 0x05 && bytes[3] === 0x06) ||
        (bytes[2] === 0x07 && bytes[3] === 0x08));
    if (!isZip) throw new Error("knowledge_attachment_signature_invalid");
  }
}
