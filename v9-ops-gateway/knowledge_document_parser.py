from __future__ import annotations

import hashlib
import io
import re
import zipfile
from dataclasses import dataclass
from pathlib import PurePosixPath
from xml.etree import ElementTree


MAX_DOCUMENT_BYTES = 8 * 1024 * 1024
MAX_EXTRACTED_BYTES = 512_000
MAX_PDF_PAGES = 500
MAX_DOCX_ENTRIES = 2_048
MAX_DOCX_UNCOMPRESSED_BYTES = 32 * 1024 * 1024
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
UNSAFE_CONTROL_PATTERN = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
DOCX_CONTENT_TYPE = (
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
)
WORD_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"


class KnowledgeDocumentParserError(ValueError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class ParsedKnowledgeDocument:
    content: str
    content_sha256: str


def parse_knowledge_document(
    file_name: str,
    content_type: str,
    data: bytes,
    expected_sha256: str,
) -> ParsedKnowledgeDocument:
    normalized_name = str(file_name or "").strip()
    normalized_type = str(content_type or "").split(";", 1)[0].strip().lower()
    normalized_hash = str(expected_sha256 or "").strip().lower()
    if not normalized_name or len(normalized_name) > 240 or "/" in normalized_name or "\\" in normalized_name:
        raise KnowledgeDocumentParserError("knowledge_document_file_name_invalid")
    if not isinstance(data, bytes) or not data:
        raise KnowledgeDocumentParserError("knowledge_document_empty")
    if len(data) > MAX_DOCUMENT_BYTES:
        raise KnowledgeDocumentParserError("knowledge_document_too_large")
    if not SHA256_PATTERN.fullmatch(normalized_hash):
        raise KnowledgeDocumentParserError("knowledge_document_sha256_invalid")
    if not hashlib.sha256(data).hexdigest() == normalized_hash:
        raise KnowledgeDocumentParserError("knowledge_document_sha256_mismatch")

    lower_name = normalized_name.lower()
    if lower_name.endswith(".pdf") and normalized_type == "application/pdf":
        content = _parse_pdf(data)
    elif lower_name.endswith(".docx") and normalized_type == DOCX_CONTENT_TYPE:
        content = _parse_docx(data)
    else:
        raise KnowledgeDocumentParserError("knowledge_document_type_mismatch")
    normalized_content = _normalize_content(content)
    return ParsedKnowledgeDocument(
        content=normalized_content,
        content_sha256=hashlib.sha256(normalized_content.encode("utf-8")).hexdigest(),
    )


def _parse_pdf(data: bytes) -> str:
    if not data.startswith(b"%PDF-"):
        raise KnowledgeDocumentParserError("knowledge_document_signature_invalid")
    try:
        from pypdf import PdfReader
    except ImportError as cause:
        raise KnowledgeDocumentParserError(
            "knowledge_document_processor_unavailable"
        ) from cause
    try:
        reader = PdfReader(io.BytesIO(data), strict=True)
        if reader.is_encrypted:
            raise KnowledgeDocumentParserError("knowledge_document_pdf_encrypted")
        if len(reader.pages) > MAX_PDF_PAGES:
            raise KnowledgeDocumentParserError("knowledge_document_page_limit_exceeded")
        pages = []
        for page in reader.pages:
            text = page.extract_text() or ""
            if text.strip():
                pages.append(text)
        return "\n\n".join(pages)
    except KnowledgeDocumentParserError:
        raise
    except Exception as cause:
        raise KnowledgeDocumentParserError("knowledge_document_pdf_invalid") from cause


def _parse_docx(data: bytes) -> str:
    if not data.startswith((b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08")):
        raise KnowledgeDocumentParserError("knowledge_document_signature_invalid")
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            entries = archive.infolist()
            if len(entries) > MAX_DOCX_ENTRIES:
                raise KnowledgeDocumentParserError("knowledge_document_archive_limit_exceeded")
            total_size = 0
            for entry in entries:
                path = PurePosixPath(entry.filename.replace("\\", "/"))
                if path.is_absolute() or ".." in path.parts:
                    raise KnowledgeDocumentParserError("knowledge_document_archive_path_invalid")
                total_size += entry.file_size
                if total_size > MAX_DOCX_UNCOMPRESSED_BYTES:
                    raise KnowledgeDocumentParserError("knowledge_document_archive_limit_exceeded")
                if entry.file_size > 1024 * 1024 and entry.compress_size > 0:
                    if entry.file_size / entry.compress_size > 1_000:
                        raise KnowledgeDocumentParserError("knowledge_document_archive_limit_exceeded")
            try:
                document = archive.read("word/document.xml")
            except KeyError as cause:
                raise KnowledgeDocumentParserError("knowledge_document_docx_invalid") from cause
    except KnowledgeDocumentParserError:
        raise
    except (OSError, zipfile.BadZipFile) as cause:
        raise KnowledgeDocumentParserError("knowledge_document_docx_invalid") from cause
    if b"<!DOCTYPE" in document.upper() or b"<!ENTITY" in document.upper():
        raise KnowledgeDocumentParserError("knowledge_document_docx_invalid")
    try:
        root = ElementTree.fromstring(document)
    except ElementTree.ParseError as cause:
        raise KnowledgeDocumentParserError("knowledge_document_docx_invalid") from cause

    paragraphs = []
    paragraph_tag = f"{{{WORD_NAMESPACE}}}p"
    text_tag = f"{{{WORD_NAMESPACE}}}t"
    tab_tag = f"{{{WORD_NAMESPACE}}}tab"
    break_tag = f"{{{WORD_NAMESPACE}}}br"
    for paragraph in root.iter(paragraph_tag):
        parts = []
        for node in paragraph.iter():
            if node.tag == text_tag and node.text:
                parts.append(node.text)
            elif node.tag == tab_tag:
                parts.append("\t")
            elif node.tag == break_tag:
                parts.append("\n")
        text = "".join(parts).strip()
        if text:
            paragraphs.append(text)
    return "\n".join(paragraphs)


def _normalize_content(content: str) -> str:
    normalized = str(content or "").replace("\r\n", "\n").replace("\r", "\n")
    normalized = "\n".join(line.rstrip() for line in normalized.split("\n")).strip()
    if not normalized:
        raise KnowledgeDocumentParserError("knowledge_document_text_empty")
    if UNSAFE_CONTROL_PATTERN.search(normalized):
        raise KnowledgeDocumentParserError("knowledge_document_text_invalid")
    if len(normalized.encode("utf-8")) > MAX_EXTRACTED_BYTES:
        raise KnowledgeDocumentParserError("knowledge_document_text_too_large")
    return normalized
