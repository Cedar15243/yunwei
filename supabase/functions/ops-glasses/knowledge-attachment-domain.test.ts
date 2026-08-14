import { assertEquals, assertRejects } from "jsr:@std/assert@1";
import {
  parseKnowledgeTextAttachment,
  prepareKnowledgeAttachmentUpload,
  sha256Hex,
} from "./knowledge-attachment-domain.ts";

const encoder = new TextEncoder();

Deno.test("accepts TXT and Markdown with a matching client SHA-256", async () => {
  const textBytes = encoder.encode("第一步：检查电源。\r\n第二步：检查总线。\r\n");
  const textSha256 = await sha256Hex(textBytes);
  const text = await prepareKnowledgeAttachmentUpload({
    fileName: "DDC 离线排查.txt",
    contentType: "text/plain",
    bytes: textBytes,
    claimedSha256: textSha256,
  });
  const markdownBytes = encoder.encode("# DDC 离线排查\n\n- 检查电源\n");
  const markdown = await prepareKnowledgeAttachmentUpload({
    fileName: "ddc-offline.md",
    contentType: "text/plain",
    bytes: markdownBytes,
    claimedSha256: await sha256Hex(markdownBytes),
  });

  assertEquals(text.fileKind, "text");
  assertEquals(text.processorAvailable, true);
  assertEquals(text.byteSize, textBytes.byteLength);
  assertEquals(text.fileSha256, textSha256);
  assertEquals(markdown.fileKind, "markdown");
  assertEquals(markdown.contentType, "text/markdown");
  assertEquals(markdown.processorAvailable, true);
});

Deno.test("accepts PDF and DOCX originals but marks their processor unavailable", async () => {
  const pdfBytes = encoder.encode("%PDF-1.7\ncontrolled fixture");
  const pdf = await prepareKnowledgeAttachmentUpload({
    fileName: "controller-manual.pdf",
    contentType: "application/pdf",
    bytes: pdfBytes,
    claimedSha256: await sha256Hex(pdfBytes),
  });
  const docxBytes = new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0x14, 0x00]);
  const docx = await prepareKnowledgeAttachmentUpload({
    fileName: "controller-manual.docx",
    contentType: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    bytes: docxBytes,
    claimedSha256: await sha256Hex(docxBytes),
  });

  assertEquals(pdf.fileKind, "pdf");
  assertEquals(pdf.processorAvailable, false);
  assertEquals(docx.fileKind, "docx");
  assertEquals(docx.processorAvailable, false);
});

Deno.test("rejects mismatched hashes MIME types extensions and binary signatures", async () => {
  const textBytes = encoder.encode("valid text");
  const textSha256 = await sha256Hex(textBytes);
  await assertRejects(
    () => prepareKnowledgeAttachmentUpload({
      fileName: "manual.txt",
      contentType: "text/plain",
      bytes: textBytes,
      claimedSha256: "0".repeat(64),
    }),
    Error,
    "knowledge_attachment_sha256_mismatch",
  );
  await assertRejects(
    () => prepareKnowledgeAttachmentUpload({
      fileName: "manual.pdf",
      contentType: "text/plain",
      bytes: textBytes,
      claimedSha256: textSha256,
    }),
    Error,
    "knowledge_attachment_type_mismatch",
  );

  const fakePdf = encoder.encode("not-a-pdf");
  const fakePdfSha256 = await sha256Hex(fakePdf);
  await assertRejects(
    () => prepareKnowledgeAttachmentUpload({
      fileName: "manual.pdf",
      contentType: "application/pdf",
      bytes: fakePdf,
      claimedSha256: fakePdfSha256,
    }),
    Error,
    "knowledge_attachment_signature_invalid",
  );
});

Deno.test("rejects files outside the controlled size and type whitelist", async () => {
  const tooLarge = new Uint8Array(512_001);
  const tooLargeSha256 = await sha256Hex(tooLarge);
  await assertRejects(
    () => prepareKnowledgeAttachmentUpload({
      fileName: "manual.txt",
      contentType: "text/plain",
      bytes: tooLarge,
      claimedSha256: tooLargeSha256,
    }),
    Error,
    "knowledge_attachment_too_large",
  );
  const csvBytes = encoder.encode("a,b\n1,2\n");
  const csvSha256 = await sha256Hex(csvBytes);
  await assertRejects(
    () => prepareKnowledgeAttachmentUpload({
      fileName: "manual.csv",
      contentType: "text/csv",
      bytes: csvBytes,
      claimedSha256: csvSha256,
    }),
    Error,
    "knowledge_attachment_type_not_allowed",
  );
});

Deno.test("parses multiline UTF-8 text without flattening content", async () => {
  const bytes = encoder.encode("  第一段。\r\n\r\n- 步骤一\r\n- 步骤二  \r\n");
  const prepared = await prepareKnowledgeAttachmentUpload({
    fileName: "manual.md",
    contentType: "text/markdown",
    bytes,
    claimedSha256: await sha256Hex(bytes),
  });
  const parsed = await parseKnowledgeTextAttachment(prepared, bytes);

  assertEquals(parsed.content, "第一段。\n\n- 步骤一\n- 步骤二");
  assertEquals(parsed.contentSha256, await sha256Hex(encoder.encode(parsed.content)));
});

Deno.test("rejects malformed UTF-8 control characters and non-text parsing", async () => {
  const malformed = new Uint8Array([0xc3, 0x28]);
  const malformedPrepared = await prepareKnowledgeAttachmentUpload({
    fileName: "manual.txt",
    contentType: "text/plain",
    bytes: malformed,
    claimedSha256: await sha256Hex(malformed),
  });
  await assertRejects(
    () => parseKnowledgeTextAttachment(malformedPrepared, malformed),
    Error,
    "knowledge_attachment_text_invalid",
  );

  const controlBytes = encoder.encode("valid\u0000invalid");
  const controlPrepared = await prepareKnowledgeAttachmentUpload({
    fileName: "manual.txt",
    contentType: "text/plain",
    bytes: controlBytes,
    claimedSha256: await sha256Hex(controlBytes),
  });
  await assertRejects(
    () => parseKnowledgeTextAttachment(controlPrepared, controlBytes),
    Error,
    "knowledge_attachment_text_invalid",
  );

  const pdfBytes = encoder.encode("%PDF-1.7\nfixture");
  const pdf = await prepareKnowledgeAttachmentUpload({
    fileName: "manual.pdf",
    contentType: "application/pdf",
    bytes: pdfBytes,
    claimedSha256: await sha256Hex(pdfBytes),
  });
  await assertRejects(
    () => parseKnowledgeTextAttachment(pdf, pdfBytes),
    Error,
    "knowledge_attachment_processor_unavailable",
  );
});
