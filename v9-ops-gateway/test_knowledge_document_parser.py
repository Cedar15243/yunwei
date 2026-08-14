import hashlib
import io
import unittest
import zipfile

from knowledge_document_parser import (
    KnowledgeDocumentParserError,
    parse_knowledge_document,
)


def pdf_with_text(text: str) -> bytes:
    escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    stream = f"BT /F1 12 Tf 72 720 Td ({escaped}) Tj ET".encode("ascii")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length " + str(len(stream)).encode("ascii") + b" >>\nstream\n" + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    output = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for index, value in enumerate(objects, start=1):
        offsets.append(len(output))
        output.extend(f"{index} 0 obj\n".encode("ascii"))
        output.extend(value)
        output.extend(b"\nendobj\n")
    xref = len(output)
    output.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    output.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        output.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    output.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode("ascii")
    )
    return bytes(output)


def docx_with_content() -> bytes:
    document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    <w:p><w:r><w:t>设备收货检查</w:t></w:r></w:p>
    <w:tbl><w:tr><w:tc><w:p><w:r><w:t>序列号</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>HF-001</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
    <w:p><w:r><w:t>确认包装无破损</w:t></w:r></w:p>
  </w:body>
</w:document>"""
    target = io.BytesIO()
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>")
        archive.writestr("word/document.xml", document)
    return target.getvalue()


class KnowledgeDocumentParserTest(unittest.TestCase):
    def test_extracts_pdf_text_with_stable_content_hash(self):
        data = pdf_with_text("DDC power bus address")
        parsed = parse_knowledge_document(
            "ddc-manual.pdf",
            "application/pdf",
            data,
            hashlib.sha256(data).hexdigest(),
        )

        self.assertEqual("DDC power bus address", parsed.content)
        self.assertEqual(
            hashlib.sha256(parsed.content.encode("utf-8")).hexdigest(),
            parsed.content_sha256,
        )

    def test_extracts_docx_paragraphs_and_table_cells_in_document_order(self):
        data = docx_with_content()
        parsed = parse_knowledge_document(
            "receipt-check.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            data,
            hashlib.sha256(data).hexdigest(),
        )

        self.assertEqual(
            "设备收货检查\n序列号\nHF-001\n确认包装无破损",
            parsed.content,
        )

    def test_rejects_hash_mismatch_and_documents_without_extractable_text(self):
        data = pdf_with_text("valid")
        with self.assertRaisesRegex(
            KnowledgeDocumentParserError,
            "knowledge_document_sha256_mismatch",
        ):
            parse_knowledge_document(
                "manual.pdf",
                "application/pdf",
                data,
                "0" * 64,
            )

        empty_docx = io.BytesIO()
        with zipfile.ZipFile(empty_docx, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body/></w:document>")
        empty_data = empty_docx.getvalue()
        with self.assertRaisesRegex(
            KnowledgeDocumentParserError,
            "knowledge_document_text_empty",
        ):
            parse_knowledge_document(
                "empty.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                empty_data,
                hashlib.sha256(empty_data).hexdigest(),
            )


if __name__ == "__main__":
    unittest.main()
