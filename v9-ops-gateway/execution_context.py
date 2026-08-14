from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
import re
import sqlite3
import threading
import time
import unicodedata
import uuid
from typing import Callable, Mapping, Optional, Sequence


_IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:@-]{0,199}")
_SEMVER = re.compile(r"0|[1-9][0-9]*\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)")
_SHA256 = re.compile(r"[0-9a-f]{64}")
_SKILL_FIELDS = {
    "versionId",
    "organizationId",
    "skillId",
    "version",
    "name",
    "status",
    "rules",
    "publishedBy",
    "publishedAt",
}
_RULE_FIELDS = {
    "applicableWhen",
    "excludedWhen",
    "requiredInputs",
    "evidenceSchema",
    "steps",
    "safety",
    "outputConstraints",
    "knowledgeScopes",
}
_ASSIGNMENT_FIELDS = {
    "assignmentId",
    "organizationId",
    "skillVersionId",
    "userId",
    "deviceId",
    "projectId",
    "status",
    "activeFrom",
    "expiresAt",
}
_CONDITION_FIELDS = {"containsAny", "systems", "assetIds"}
_ATTRIBUTE_FIELDS = {"system", "assetId"}
_MANIFEST_FIELDS = {
    "manifestVersion",
    "etag",
    "organizationId",
    "userId",
    "deviceId",
    "projectId",
    "generatedAt",
    "expiresAt",
    "skills",
    "knowledge",
}
_MANIFEST_SKILL_FIELDS = {
    "skillId",
    "versionId",
    "version",
    "name",
    "description",
    "status",
    "contentSha256",
    "rules",
    "authorizationScope",
}
_MANIFEST_KNOWLEDGE_FIELDS = {
    "knowledgeId",
    "versionId",
    "version",
    "title",
    "summary",
    "language",
    "sensitivity",
    "sourceType",
    "sourceReference",
    "contentSha256",
    "knowledgeScopes",
    "validFrom",
    "expiresAt",
    "authorizationScope",
}
_DIRECT_AUTHORIZATION_SCOPES = {"organization", "project", "profile", "device"}
_KNOWLEDGE_AUTHORIZATION_SCOPES = _DIRECT_AUTHORIZATION_SCOPES | {"skill_version"}
_FORBIDDEN_NESTED_KEYS = {
    "apikey",
    "apisecret",
    "credential",
    "endpoint",
    "password",
    "script",
    "secret",
    "secretkey",
    "token",
    "url",
}


def _canonical_json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _required_text(value: object, maximum: int, error: str) -> str:
    if not isinstance(value, str):
        raise ValueError(error)
    result = value.strip()
    if not result or len(result) > maximum or any(ord(character) < 32 for character in result):
        raise ValueError(error)
    return result


def _required_display_text(value: object, maximum: int, error: str) -> str:
    if not isinstance(value, str):
        raise ValueError(error)
    result = value.strip()
    if (
        not result
        or len(result) > maximum
        or any(
            unicodedata.category(character) == "Cc"
            and character not in {"\t", "\n", "\r"}
            for character in result
        )
    ):
        raise ValueError(error)
    return result


def _identifier(value: object, error: str) -> str:
    result = _required_text(value, 200, error)
    if not _IDENTIFIER.fullmatch(result):
        raise ValueError(error)
    return result


def _optional_identifier(value: object, error: str) -> str:
    if value is None or value == "":
        return ""
    return _identifier(value, error)


def _string_list(value: object, maximum_items: int, maximum_length: int, error: str) -> list[str]:
    if not isinstance(value, list) or len(value) > maximum_items:
        raise ValueError(error)
    result: list[str] = []
    seen: set[str] = set()
    for item in value:
        text = _required_text(item, maximum_length, error)
        if text in seen:
            raise ValueError(error)
        seen.add(text)
        result.append(text)
    return result


def _valid_iso_timestamp(value: object, error: str) -> str:
    text = _required_text(value, 100, error)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        raise ValueError(error) from None
    if parsed.tzinfo is None:
        raise ValueError(error)
    return text


def _timestamp_epoch(value: object, error: str) -> float:
    text = _valid_iso_timestamp(value, error)
    return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()


def _optional_iso_timestamp(value: object, error: str) -> Optional[str]:
    if value is None or value == "":
        return None
    return _valid_iso_timestamp(value, error)


def _sha256(value: object, error: str) -> str:
    text = _required_text(value, 64, error)
    if not _SHA256.fullmatch(text):
        raise ValueError(error)
    return text


def _reject_forbidden_nested_keys(value: object) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if not isinstance(key, str) or key.lower().replace("_", "") in _FORBIDDEN_NESTED_KEYS:
                raise ValueError("skill_rule_forbidden_field")
            _reject_forbidden_nested_keys(child)
    elif isinstance(value, list):
        for child in value:
            _reject_forbidden_nested_keys(child)
    elif isinstance(value, (str, int, float, bool)) or value is None:
        return
    else:
        raise ValueError("skill_rule_invalid_value")


def _validate_skill_rules(value: object) -> dict:
    if not isinstance(value, dict):
        raise ValueError("skill_rules_invalid")
    if set(value) != _RULE_FIELDS:
        raise ValueError("skill_rules_unknown_field")
    for field in ("applicableWhen", "excludedWhen", "evidenceSchema", "safety", "outputConstraints"):
        if not isinstance(value[field], dict):
            raise ValueError("skill_rules_invalid")
    required_inputs = _string_list(value["requiredInputs"], 30, 120, "skill_required_inputs_invalid")
    knowledge_scopes = _string_list(value["knowledgeScopes"], 100, 200, "skill_knowledge_scopes_invalid")
    steps = value["steps"]
    if not isinstance(steps, list) or not steps or len(steps) > 100:
        raise ValueError("skill_steps_invalid")
    normalized_steps: list[dict[str, str]] = []
    seen_steps: set[str] = set()
    for step in steps:
        if not isinstance(step, dict) or set(step) != {"id", "instruction", "risk"}:
            raise ValueError("skill_step_invalid")
        step_id = _identifier(step["id"], "skill_step_id_invalid")
        if step_id in seen_steps:
            raise ValueError("skill_step_id_duplicate")
        seen_steps.add(step_id)
        instruction = _required_text(step["instruction"], 2000, "skill_step_instruction_invalid")
        risk = _required_text(step["risk"], 20, "skill_step_risk_invalid")
        if risk not in {"low", "medium", "high"}:
            raise ValueError("skill_step_risk_invalid")
        normalized_steps.append({"id": step_id, "instruction": instruction, "risk": risk})
    normalized = {
        "applicableWhen": value["applicableWhen"],
        "excludedWhen": value["excludedWhen"],
        "requiredInputs": required_inputs,
        "evidenceSchema": value["evidenceSchema"],
        "steps": normalized_steps,
        "safety": value["safety"],
        "outputConstraints": value["outputConstraints"],
        "knowledgeScopes": knowledge_scopes,
    }
    _reject_forbidden_nested_keys(normalized)
    if len(_canonical_json(normalized)) > 128_000:
        raise ValueError("skill_rules_too_large")
    _skill_knowledge_policy(normalized)
    return normalized


def _skill_knowledge_policy(rules: Mapping[str, object]) -> tuple[tuple[str, ...], tuple[str, ...]]:
    configured_scopes = tuple(str(item) for item in rules.get("knowledgeScopes", []))
    allowed_scopes = set(configured_scopes)
    constraints = rules.get("outputConstraints", {})
    if not isinstance(constraints, Mapping):
        raise ValueError("skill_knowledge_policy_invalid")
    required = tuple(
        _string_list(
            constraints.get("requiredKnowledgeScopes", []),
            100,
            200,
            "skill_knowledge_policy_invalid",
        )
    )
    optional_value = constraints.get("optionalKnowledgeScopes")
    if optional_value is None:
        optional = tuple(scope for scope in configured_scopes if scope not in set(required))
    else:
        optional = tuple(
            _string_list(
                optional_value,
                100,
                200,
                "skill_knowledge_policy_invalid",
            )
        )
    if set(required) & set(optional):
        raise ValueError("skill_knowledge_policy_invalid")
    if not set(required).issubset(allowed_scopes) or not set(optional).issubset(allowed_scopes):
        raise ValueError("skill_knowledge_policy_invalid")
    return required, optional


def _normalize_manifest_skill(value: object) -> dict:
    if not isinstance(value, Mapping) or set(value) != _MANIFEST_SKILL_FIELDS:
        raise ValueError("content_manifest_skill_invalid")
    version = _required_text(value["version"], 40, "content_manifest_skill_invalid")
    if not _SEMVER.fullmatch(version):
        raise ValueError("content_manifest_skill_invalid")
    if value["status"] != "published":
        raise ValueError("content_manifest_skill_not_published")
    authorization_scope = _required_text(
        value["authorizationScope"], 40, "content_manifest_skill_invalid"
    )
    if authorization_scope not in _DIRECT_AUTHORIZATION_SCOPES:
        raise ValueError("content_manifest_skill_invalid")
    return {
        "skillId": _identifier(value["skillId"], "content_manifest_skill_invalid"),
        "versionId": _identifier(value["versionId"], "content_manifest_skill_invalid"),
        "version": version,
        "name": _required_text(value["name"], 240, "content_manifest_skill_invalid"),
        "description": _required_text(
            value["description"], 2_000, "content_manifest_skill_invalid"
        ),
        "status": "published",
        "contentSha256": _sha256(
            value["contentSha256"], "content_manifest_skill_invalid"
        ),
        "rules": _validate_skill_rules(value["rules"]),
        "authorizationScope": authorization_scope,
    }


def _normalize_manifest_knowledge(value: object) -> dict:
    if not isinstance(value, Mapping) or set(value) != _MANIFEST_KNOWLEDGE_FIELDS:
        raise ValueError("content_manifest_knowledge_invalid")
    version = value["version"]
    if not isinstance(version, int) or isinstance(version, bool) or version <= 0:
        raise ValueError("content_manifest_knowledge_invalid")
    authorization_scope = _required_text(
        value["authorizationScope"], 40, "content_manifest_knowledge_invalid"
    )
    if authorization_scope not in _KNOWLEDGE_AUTHORIZATION_SCOPES:
        raise ValueError("content_manifest_knowledge_invalid")
    valid_from = _optional_iso_timestamp(
        value["validFrom"], "content_manifest_knowledge_invalid"
    )
    expires_at = _optional_iso_timestamp(
        value["expiresAt"], "content_manifest_knowledge_invalid"
    )
    if valid_from and expires_at:
        if _timestamp_epoch(expires_at, "content_manifest_knowledge_invalid") <= _timestamp_epoch(
            valid_from, "content_manifest_knowledge_invalid"
        ):
            raise ValueError("content_manifest_knowledge_invalid")
    return {
        "knowledgeId": _identifier(
            value["knowledgeId"], "content_manifest_knowledge_invalid"
        ),
        "versionId": _identifier(
            value["versionId"], "content_manifest_knowledge_invalid"
        ),
        "version": version,
        "title": _required_text(value["title"], 300, "content_manifest_knowledge_invalid"),
        "summary": _required_text(
            value["summary"], 4_000, "content_manifest_knowledge_invalid"
        ),
        "language": _required_text(
            value["language"], 40, "content_manifest_knowledge_invalid"
        ),
        "sensitivity": _required_text(
            value["sensitivity"], 40, "content_manifest_knowledge_invalid"
        ),
        "sourceType": _required_text(
            value["sourceType"], 80, "content_manifest_knowledge_invalid"
        ),
        "sourceReference": _required_text(
            value["sourceReference"], 1_000, "content_manifest_knowledge_invalid"
        ),
        "contentSha256": _sha256(
            value["contentSha256"], "content_manifest_knowledge_invalid"
        ),
        "knowledgeScopes": _string_list(
            value["knowledgeScopes"], 100, 200, "content_manifest_knowledge_invalid"
        ),
        "validFrom": valid_from,
        "expiresAt": expires_at,
        "authorizationScope": authorization_scope,
    }


def _validate_condition(value: object) -> dict[str, list[str]]:
    if not isinstance(value, dict) or any(key not in _CONDITION_FIELDS for key in value):
        raise ValueError("project_instruction_condition_invalid")
    result: dict[str, list[str]] = {}
    for key in _CONDITION_FIELDS:
        if key in value:
            result[key] = _string_list(
                value[key], 50, 200, "project_instruction_condition_invalid"
            )
    return result


def _condition_matches(condition: Mapping[str, Sequence[str]], user_text: str, attributes: Mapping[str, str]) -> bool:
    contains_any = condition.get("containsAny", ())
    if contains_any and not any(term in user_text for term in contains_any):
        return False
    systems = condition.get("systems", ())
    if systems and attributes.get("system", "") not in systems:
        return False
    asset_ids = condition.get("assetIds", ())
    if asset_ids and attributes.get("assetId", "") not in asset_ids:
        return False
    return True


@dataclass(frozen=True)
class SkillSnapshot:
    version_id: str
    skill_id: str
    version: str
    name: str
    rules: dict
    digest: str

    def to_payload(self) -> dict:
        return {
            "versionId": self.version_id,
            "skillId": self.skill_id,
            "version": self.version,
            "name": self.name,
            "rules": self.rules,
            "sha256": self.digest,
        }

    def to_catalog_payload(self) -> dict:
        return {
            "versionId": self.version_id,
            "skillId": self.skill_id,
            "version": self.version,
            "name": self.name,
            "sha256": self.digest,
        }


@dataclass(frozen=True)
class KnowledgeReference:
    knowledge_id: str
    version_id: str
    version: int
    title: str
    summary: str
    language: str
    sensitivity: str
    source_type: str
    source_reference: str
    content_sha256: str
    knowledge_scopes: tuple[str, ...]
    valid_from: Optional[str]
    expires_at: Optional[str]
    authorization_scope: str

    def to_payload(self) -> dict:
        return {
            "knowledgeId": self.knowledge_id,
            "versionId": self.version_id,
            "version": self.version,
            "title": self.title,
            "summary": self.summary,
            "language": self.language,
            "sensitivity": self.sensitivity,
            "sourceType": self.source_type,
            "sourceReference": self.source_reference,
            "contentSha256": self.content_sha256,
            "knowledgeScopes": list(self.knowledge_scopes),
            "validFrom": self.valid_from,
            "expiresAt": self.expires_at,
            "authorizationScope": self.authorization_scope,
        }


@dataclass(frozen=True)
class ProjectInstructionSnapshot:
    instruction_id: str
    version: int
    condition: dict[str, list[str]]
    action: str
    exceptions: tuple[str, ...]
    source_trace_id: str

    def to_payload(self) -> dict:
        return {
            "instructionId": self.instruction_id,
            "version": self.version,
            "condition": self.condition,
            "action": self.action,
            "exceptions": list(self.exceptions),
            "sourceTraceId": self.source_trace_id,
        }


@dataclass(frozen=True)
class ExecutionContext:
    organization_id: str
    user_id: str
    device_id: str
    project_id: str
    task_id: str
    local_project_id: str
    local_task_id: str
    skill: Optional[SkillSnapshot]
    project_summary: str
    confirmed_facts: tuple[str, ...]
    excluded_facts: tuple[str, ...]
    risks: tuple[str, ...]
    memory_revision: int
    instructions: tuple[ProjectInstructionSnapshot, ...]
    recent_messages: tuple[dict[str, str], ...]
    user_text: str
    manifest_version: int = 0
    manifest_etag: str = ""
    knowledge_references: tuple[KnowledgeReference, ...] = ()
    knowledge_omissions: tuple[dict[str, str], ...] = ()
    authoritative_project_id: str = ""

    def to_provider_payload(self) -> dict:
        identity = {
            "organizationId": self.organization_id,
            "userId": self.user_id,
            "deviceId": self.device_id,
            "projectId": self.project_id,
            "taskId": self.task_id,
            "localProjectId": self.local_project_id,
            "localTaskId": self.local_task_id,
        }
        if self.authoritative_project_id:
            identity["authoritativeProjectId"] = self.authoritative_project_id
        return {
            "identity": identity,
            **self.to_model_payload(),
        }

    def to_model_payload(self) -> dict:
        return {
            "skill": self.skill.to_payload() if self.skill else None,
            "contentAuthorization": (
                {
                    "manifestVersion": self.manifest_version,
                    "etag": self.manifest_etag,
                }
                if self.manifest_version
                else None
            ),
            "knowledge": {
                "references": [item.to_payload() for item in self.knowledge_references],
                "omissions": list(self.knowledge_omissions),
            },
            "projectMemory": {
                "summary": self.project_summary,
                "confirmedFacts": list(self.confirmed_facts),
                "excludedFacts": list(self.excluded_facts),
                "risks": list(self.risks),
                "revision": self.memory_revision,
            },
            "projectInstructions": [item.to_payload() for item in self.instructions],
            "recentMessages": list(self.recent_messages),
            "currentInput": {"text": self.user_text},
        }

    @property
    def digest(self) -> str:
        return _sha256_text(_canonical_json(self.to_provider_payload()))


class ExecutionContextRepository:
    def __init__(
        self,
        connection: sqlite3.Connection,
        lock: threading.RLock,
        clock: Callable[[], float] = time.time,
        require_authoritative_manifest: bool = False,
    ):
        self.connection = connection
        self.lock = lock
        self.clock = clock
        self.require_authoritative_manifest = require_authoritative_manifest
        with self.lock:
            self.connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS authoritative_content_manifests (
                    manifest_id TEXT PRIMARY KEY,
                    organization_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    local_project_id TEXT NOT NULL,
                    authoritative_project_id TEXT,
                    manifest_version INTEGER NOT NULL,
                    etag TEXT NOT NULL,
                    generated_at REAL NOT NULL,
                    expires_at REAL NOT NULL,
                    payload_json TEXT NOT NULL,
                    payload_sha256 TEXT NOT NULL,
                    synced_at REAL NOT NULL,
                    UNIQUE(
                        organization_id, user_id, device_id,
                        local_project_id, manifest_version
                    )
                );
                CREATE INDEX IF NOT EXISTS authoritative_content_manifest_lookup_idx
                ON authoritative_content_manifests(
                    organization_id, user_id, device_id,
                    local_project_id, manifest_version DESC
                );

                CREATE TABLE IF NOT EXISTS project_identity_bindings (
                    binding_id TEXT PRIMARY KEY,
                    organization_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    local_project_id TEXT NOT NULL,
                    gateway_project_id TEXT NOT NULL REFERENCES ops_projects(id),
                    authoritative_project_id TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('active', 'revoked')),
                    source_manifest_version INTEGER NOT NULL,
                    source_manifest_etag TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    UNIQUE(organization_id, user_id, device_id, local_project_id)
                );
                CREATE UNIQUE INDEX IF NOT EXISTS project_identity_gateway_active_idx
                ON project_identity_bindings(gateway_project_id)
                WHERE status = 'active';
                CREATE UNIQUE INDEX IF NOT EXISTS project_identity_authoritative_active_idx
                ON project_identity_bindings(
                    organization_id, device_id, authoritative_project_id
                )
                WHERE status = 'active';
                CREATE TABLE IF NOT EXISTS project_identity_audit_events (
                    event_id TEXT PRIMARY KEY,
                    binding_id TEXT NOT NULL REFERENCES project_identity_bindings(binding_id),
                    event_type TEXT NOT NULL CHECK(event_type IN ('bound', 'refreshed', 'revoked')),
                    organization_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    local_project_id TEXT NOT NULL,
                    gateway_project_id TEXT NOT NULL,
                    authoritative_project_id TEXT NOT NULL,
                    source_manifest_version INTEGER NOT NULL,
                    source_manifest_etag TEXT NOT NULL,
                    created_at REAL NOT NULL
                );

                CREATE TABLE IF NOT EXISTS content_manifest_sync_status (
                    device_id TEXT NOT NULL,
                    local_project_id TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('success', 'failed')),
                    error_code TEXT NOT NULL DEFAULT '',
                    failure_count INTEGER NOT NULL DEFAULT 0 CHECK(failure_count >= 0),
                    next_retry_at REAL NOT NULL DEFAULT 0,
                    updated_at REAL NOT NULL,
                    PRIMARY KEY(device_id, local_project_id)
                );
                CREATE TABLE IF NOT EXISTS skill_versions (
                    version_id TEXT PRIMARY KEY,
                    organization_id TEXT NOT NULL,
                    skill_id TEXT NOT NULL,
                    version TEXT NOT NULL,
                    name TEXT NOT NULL,
                    status TEXT NOT NULL,
                    rules_json TEXT NOT NULL,
                    canonical_json TEXT NOT NULL,
                    content_sha256 TEXT NOT NULL,
                    published_by TEXT NOT NULL,
                    published_at TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    UNIQUE(organization_id, skill_id, version)
                );
                CREATE TABLE IF NOT EXISTS skill_assignments (
                    assignment_id TEXT PRIMARY KEY,
                    organization_id TEXT NOT NULL,
                    skill_version_id TEXT NOT NULL REFERENCES skill_versions(version_id),
                    user_id TEXT,
                    device_id TEXT,
                    project_id TEXT REFERENCES ops_projects(id),
                    status TEXT NOT NULL,
                    active_from REAL NOT NULL,
                    expires_at REAL,
                    canonical_json TEXT NOT NULL,
                    created_at REAL NOT NULL
                );
                CREATE INDEX IF NOT EXISTS skill_assignments_lookup_idx
                ON skill_assignments(
                    organization_id, skill_version_id, status, device_id, user_id, project_id
                );
                CREATE TABLE IF NOT EXISTS task_skill_snapshots (
                    snapshot_id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL REFERENCES maintenance_tasks(id),
                    organization_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    skill_version_id TEXT NOT NULL REFERENCES skill_versions(version_id),
                    snapshot_json TEXT NOT NULL,
                    snapshot_sha256 TEXT NOT NULL,
                    activated_at REAL NOT NULL,
                    deactivated_at REAL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS task_skill_snapshots_active_idx
                ON task_skill_snapshots(task_id)
                WHERE deactivated_at IS NULL;
                CREATE TABLE IF NOT EXISTS task_content_snapshots (
                    snapshot_id TEXT PRIMARY KEY REFERENCES task_skill_snapshots(snapshot_id),
                    task_id TEXT NOT NULL REFERENCES maintenance_tasks(id),
                    organization_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    local_project_id TEXT NOT NULL,
                    authoritative_project_id TEXT,
                    source_manifest_version INTEGER NOT NULL,
                    source_manifest_etag TEXT NOT NULL,
                    skill_id TEXT NOT NULL,
                    skill_version_id TEXT NOT NULL,
                    knowledge_snapshot_json TEXT NOT NULL,
                    knowledge_omissions_json TEXT NOT NULL,
                    activated_at REAL NOT NULL,
                    deactivated_at REAL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS task_content_snapshots_active_idx
                ON task_content_snapshots(task_id)
                WHERE deactivated_at IS NULL;
                CREATE TABLE IF NOT EXISTS project_memories (
                    project_id TEXT PRIMARY KEY REFERENCES ops_projects(id),
                    organization_id TEXT NOT NULL,
                    updated_by TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    confirmed_facts_json TEXT NOT NULL,
                    excluded_facts_json TEXT NOT NULL,
                    risks_json TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    updated_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS project_instruction_versions (
                    project_id TEXT NOT NULL REFERENCES ops_projects(id),
                    organization_id TEXT NOT NULL,
                    instruction_id TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    condition_json TEXT NOT NULL,
                    action_text TEXT NOT NULL,
                    exceptions_json TEXT NOT NULL,
                    source_trace_id TEXT NOT NULL,
                    created_by TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    PRIMARY KEY(project_id, instruction_id, version)
                );
                CREATE INDEX IF NOT EXISTS project_instruction_latest_idx
                ON project_instruction_versions(project_id, instruction_id, version DESC);
                CREATE TABLE IF NOT EXISTS execution_context_runs (
                    trace_id TEXT PRIMARY KEY,
                    organization_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    project_id TEXT NOT NULL,
                    authoritative_project_id TEXT,
                    task_id TEXT NOT NULL,
                    context_sha256 TEXT NOT NULL,
                    skill_version_id TEXT,
                    skill_snapshot_sha256 TEXT,
                    manifest_version INTEGER,
                    manifest_etag TEXT,
                    knowledge_references_json TEXT,
                    knowledge_omissions_json TEXT,
                    memory_revision INTEGER NOT NULL,
                    instruction_versions_json TEXT NOT NULL,
                    model TEXT NOT NULL,
                    status TEXT NOT NULL,
                    error_code TEXT,
                    latency_ms INTEGER,
                    created_at REAL NOT NULL,
                    completed_at REAL
                );
                CREATE TABLE IF NOT EXISTS task_end_summaries (
                    task_id TEXT PRIMARY KEY REFERENCES maintenance_tasks(id),
                    organization_id TEXT NOT NULL,
                    ended_by TEXT NOT NULL,
                    task_status TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    memory_revision INTEGER NOT NULL,
                    created_at REAL NOT NULL
                );
                """
            )
            self._ensure_column(
                "execution_context_runs", "manifest_version", "INTEGER"
            )
            self._ensure_column("execution_context_runs", "manifest_etag", "TEXT")
            self._ensure_column(
                "execution_context_runs", "knowledge_references_json", "TEXT"
            )
            self._ensure_column(
                "execution_context_runs", "knowledge_omissions_json", "TEXT"
            )
            self._ensure_column(
                "execution_context_runs", "authoritative_project_id", "TEXT"
            )
            self.connection.commit()

    def _ensure_column(self, table: str, column: str, definition: str) -> None:
        columns = {
            str(row["name"])
            for row in self.connection.execute(f"PRAGMA table_info({table})").fetchall()
        }
        if column not in columns:
            self.connection.execute(
                f"ALTER TABLE {table} ADD COLUMN {column} {definition}"
            )

    def list_content_manifest_sync_projects(self, device_id: str) -> list[str]:
        device_id = _identifier(device_id, "device_id_invalid")
        with self.lock:
            rows = self.connection.execute(
                """
                SELECT local_project_id
                FROM ops_projects
                WHERE device_id = ? AND status = 'active'
                ORDER BY updated_at DESC, local_project_id
                """,
                (device_id,),
            ).fetchall()
        return [str(row["local_project_id"]) for row in rows]

    def content_manifest_sync_state(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        local_project_id: str,
    ) -> Optional[dict]:
        organization_id = _identifier(organization_id, "organization_id_invalid")
        user_id = _identifier(user_id, "user_id_invalid")
        device_id = _identifier(device_id, "device_id_invalid")
        local_project_id = _identifier(local_project_id, "project_id_invalid")
        with self.lock:
            row = self.connection.execute(
                """
                SELECT manifest_version, etag, payload_json
                FROM authoritative_content_manifests
                WHERE organization_id = ? AND user_id = ? AND device_id = ?
                  AND local_project_id = ?
                ORDER BY manifest_version DESC
                LIMIT 1
                """,
                (organization_id, user_id, device_id, local_project_id),
            ).fetchone()
            sync_row = self.connection.execute(
                """
                SELECT status, error_code, failure_count, next_retry_at
                FROM content_manifest_sync_status
                WHERE device_id = ? AND local_project_id = ?
                """,
                (device_id, local_project_id),
            ).fetchone()
        if not row and not sync_row:
            return None
        state = {
            "manifestVersion": 0,
            "etag": "",
            "expiresAt": "",
            "syncStatus": "",
            "errorCode": "",
            "failureCount": 0,
            "nextRetryAt": 0.0,
        }
        if row:
            try:
                payload = json.loads(str(row["payload_json"]))
            except json.JSONDecodeError as error:
                raise ValueError("content_manifest_invalid") from error
            state.update(
                {
                    "manifestVersion": int(row["manifest_version"]),
                    "etag": str(row["etag"]),
                    "expiresAt": str(payload.get("expiresAt") or ""),
                }
            )
        if sync_row:
            state.update(
                {
                    "syncStatus": str(sync_row["status"]),
                    "errorCode": str(sync_row["error_code"] or ""),
                    "failureCount": int(sync_row["failure_count"]),
                    "nextRetryAt": float(sync_row["next_retry_at"]),
                }
            )
        return state

    def project_identity_binding(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        local_project_id: str,
    ) -> dict:
        organization_id = _identifier(organization_id, "organization_id_invalid")
        user_id = _identifier(user_id, "user_id_invalid")
        device_id = _identifier(device_id, "device_id_invalid")
        local_project_id = _identifier(local_project_id, "project_id_invalid")
        with self.lock:
            row = self.connection.execute(
                """
                SELECT * FROM project_identity_bindings
                WHERE organization_id = ? AND user_id = ? AND device_id = ?
                  AND local_project_id = ? AND status = 'active'
                """,
                (organization_id, user_id, device_id, local_project_id),
            ).fetchone()
        if not row:
            raise LookupError("project_identity_unbound")
        return {
            "bindingId": str(row["binding_id"]),
            "organizationId": str(row["organization_id"]),
            "userId": str(row["user_id"]),
            "deviceId": str(row["device_id"]),
            "localProjectId": str(row["local_project_id"]),
            "gatewayProjectId": str(row["gateway_project_id"]),
            "authoritativeProjectId": str(row["authoritative_project_id"]),
            "status": str(row["status"]),
            "sourceManifestVersion": int(row["source_manifest_version"]),
            "sourceManifestEtag": str(row["source_manifest_etag"]),
        }

    def record_content_manifest_sync_status(
        self,
        device_id: str,
        local_project_id: str,
        status: str,
        error_code: str,
        failure_count: int,
        next_retry_at: float,
    ) -> None:
        device_id = _identifier(device_id, "device_id_invalid")
        local_project_id = _identifier(local_project_id, "project_id_invalid")
        status = str(status).strip()
        if status not in {"success", "failed"}:
            raise ValueError("content_sync_status_invalid")
        error_code = str(error_code).strip()
        if error_code and not _IDENTIFIER.fullmatch(error_code):
            raise ValueError("content_sync_error_invalid")
        if status == "success" and error_code:
            raise ValueError("content_sync_error_invalid")
        if (
            not isinstance(failure_count, int)
            or isinstance(failure_count, bool)
            or failure_count < 0
        ):
            raise ValueError("content_sync_failure_count_invalid")
        next_retry_at = float(next_retry_at)
        if next_retry_at < 0:
            raise ValueError("content_sync_retry_invalid")
        with self.lock:
            if not self._project(device_id, local_project_id):
                raise LookupError("project_not_found")
            self.connection.execute(
                """
                INSERT INTO content_manifest_sync_status(
                    device_id, local_project_id, status, error_code,
                    failure_count, next_retry_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id, local_project_id) DO UPDATE SET
                    status = excluded.status,
                    error_code = excluded.error_code,
                    failure_count = excluded.failure_count,
                    next_retry_at = excluded.next_retry_at,
                    updated_at = excluded.updated_at
                """,
                (
                    device_id,
                    local_project_id,
                    status,
                    error_code,
                    failure_count,
                    next_retry_at,
                    self.clock(),
                ),
            )
            self.connection.commit()

    def sync_authoritative_manifest(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        local_project_id: str,
        manifest: Mapping[str, object],
    ) -> dict:
        organization_id = _identifier(organization_id, "organization_id_invalid")
        user_id = _identifier(user_id, "user_id_invalid")
        device_id = _identifier(device_id, "device_id_invalid")
        local_project_id = _identifier(local_project_id, "project_id_invalid")
        normalized = self._normalize_authoritative_manifest(manifest)
        if (
            normalized["organizationId"] != organization_id
            or normalized["userId"] != user_id
            or normalized["deviceId"] != device_id
        ):
            raise PermissionError("content_manifest_identity_mismatch")
        now = self.clock()
        if normalized["expiresAtEpoch"] <= now:
            raise ValueError("content_manifest_expired")
        if normalized["generatedAtEpoch"] > now + 300:
            raise ValueError("content_manifest_invalid")
        payload = {
            key: normalized[key]
            for key in _MANIFEST_FIELDS
        }
        payload_json = _canonical_json(payload)
        if len(payload_json) > 2_000_000:
            raise ValueError("content_manifest_too_large")
        payload_sha256 = _sha256_text(payload_json)
        manifest_id = _sha256_text(
            "|".join(
                (
                    organization_id,
                    user_id,
                    device_id,
                    local_project_id,
                    str(normalized["manifestVersion"]),
                    str(normalized["etag"]),
                )
            )
        )
        with self.lock:
            project = self._project(device_id, local_project_id)
            if not project:
                raise LookupError("project_not_found")
            authoritative_project_id = normalized["projectId"]
            if not authoritative_project_id:
                raise ValueError("content_manifest_project_mismatch")
            existing = self.connection.execute(
                """
                SELECT manifest_id, payload_sha256
                FROM authoritative_content_manifests
                WHERE organization_id = ? AND user_id = ? AND device_id = ?
                  AND local_project_id = ? AND manifest_version = ?
                """,
                (
                    organization_id,
                    user_id,
                    device_id,
                    local_project_id,
                    normalized["manifestVersion"],
                ),
            ).fetchone()
            if existing:
                if str(existing["payload_sha256"]) != payload_sha256:
                    raise ValueError("content_manifest_version_conflict")
                return {
                    "manifestVersion": normalized["manifestVersion"],
                    "etag": normalized["etag"],
                    "duplicate": True,
                }
            latest = self.connection.execute(
                """
                SELECT manifest_version
                FROM authoritative_content_manifests
                WHERE organization_id = ? AND user_id = ? AND device_id = ?
                  AND local_project_id = ?
                ORDER BY manifest_version DESC
                LIMIT 1
                """,
                (organization_id, user_id, device_id, local_project_id),
            ).fetchone()
            if latest and int(latest["manifest_version"]) > normalized["manifestVersion"]:
                raise ValueError("content_manifest_stale")
            try:
                for skill in normalized["skills"]:
                    self._cache_authoritative_skill(
                        organization_id,
                        skill,
                        str(normalized["generatedAt"]),
                    )
                self._bind_project_identity(
                    organization_id,
                    user_id,
                    device_id,
                    local_project_id,
                    str(project["id"]),
                    authoritative_project_id,
                    int(normalized["manifestVersion"]),
                    str(normalized["etag"]),
                    now,
                )
                self.connection.execute(
                    """
                    INSERT INTO authoritative_content_manifests(
                        manifest_id, organization_id, user_id, device_id,
                        local_project_id, authoritative_project_id,
                        manifest_version, etag, generated_at, expires_at,
                        payload_json, payload_sha256, synced_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        manifest_id,
                        organization_id,
                        user_id,
                        device_id,
                        local_project_id,
                        authoritative_project_id,
                        normalized["manifestVersion"],
                        normalized["etag"],
                        normalized["generatedAtEpoch"],
                        normalized["expiresAtEpoch"],
                        payload_json,
                        payload_sha256,
                        now,
                    ),
                )
            except Exception:
                self.connection.rollback()
                raise
            self.connection.commit()
        return {
            "manifestVersion": normalized["manifestVersion"],
            "etag": normalized["etag"],
            "duplicate": False,
        }

    def _bind_project_identity(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        local_project_id: str,
        gateway_project_id: str,
        authoritative_project_id: str,
        source_manifest_version: int,
        source_manifest_etag: str,
        now: float,
    ) -> None:
        existing = self.connection.execute(
            """
            SELECT * FROM project_identity_bindings
            WHERE organization_id = ? AND user_id = ? AND device_id = ?
              AND local_project_id = ? AND status = 'active'
            """,
            (organization_id, user_id, device_id, local_project_id),
        ).fetchone()
        if existing and (
            str(existing["gateway_project_id"]) != gateway_project_id
            or str(existing["authoritative_project_id"]) != authoritative_project_id
        ):
            raise ValueError("project_identity_conflict")
        reverse = self.connection.execute(
            """
            SELECT * FROM project_identity_bindings
            WHERE organization_id = ? AND device_id = ?
              AND authoritative_project_id = ? AND status = 'active'
            """,
            (organization_id, device_id, authoritative_project_id),
        ).fetchone()
        if reverse and (
            str(reverse["gateway_project_id"]) != gateway_project_id
            or str(reverse["local_project_id"]) != local_project_id
        ):
            raise ValueError("project_identity_conflict")

        if existing:
            binding_id = str(existing["binding_id"])
            self.connection.execute(
                """
                UPDATE project_identity_bindings
                SET source_manifest_version = ?, source_manifest_etag = ?, updated_at = ?
                WHERE binding_id = ?
                """,
                (source_manifest_version, source_manifest_etag, now, binding_id),
            )
            event_type = "refreshed"
        else:
            binding_id = str(uuid.uuid4())
            self.connection.execute(
                """
                INSERT INTO project_identity_bindings(
                    binding_id, organization_id, user_id, device_id,
                    local_project_id, gateway_project_id, authoritative_project_id,
                    status, source_manifest_version, source_manifest_etag,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, ?)
                """,
                (
                    binding_id,
                    organization_id,
                    user_id,
                    device_id,
                    local_project_id,
                    gateway_project_id,
                    authoritative_project_id,
                    source_manifest_version,
                    source_manifest_etag,
                    now,
                    now,
                ),
            )
            event_type = "bound"
        self.connection.execute(
            """
            INSERT INTO project_identity_audit_events(
                event_id, binding_id, event_type, organization_id, user_id,
                device_id, local_project_id, gateway_project_id,
                authoritative_project_id, source_manifest_version,
                source_manifest_etag, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                str(uuid.uuid4()),
                binding_id,
                event_type,
                organization_id,
                user_id,
                device_id,
                local_project_id,
                gateway_project_id,
                authoritative_project_id,
                source_manifest_version,
                source_manifest_etag,
                now,
            ),
        )

    def _normalize_authoritative_manifest(self, manifest: object) -> dict:
        if not isinstance(manifest, Mapping) or set(manifest) != _MANIFEST_FIELDS:
            raise ValueError("content_manifest_invalid")
        manifest_version = manifest["manifestVersion"]
        if (
            not isinstance(manifest_version, int)
            or isinstance(manifest_version, bool)
            or manifest_version <= 0
        ):
            raise ValueError("content_manifest_invalid")
        project_value = manifest["projectId"]
        project_id = (
            None
            if project_value is None or project_value == ""
            else _identifier(project_value, "content_manifest_invalid")
        )
        skills_value = manifest["skills"]
        knowledge_value = manifest["knowledge"]
        if not isinstance(skills_value, list) or len(skills_value) > 100:
            raise ValueError("content_manifest_invalid")
        if not isinstance(knowledge_value, list) or len(knowledge_value) > 1_000:
            raise ValueError("content_manifest_invalid")
        skills = [_normalize_manifest_skill(item) for item in skills_value]
        knowledge = [_normalize_manifest_knowledge(item) for item in knowledge_value]
        skill_version_ids = [str(item["versionId"]) for item in skills]
        knowledge_version_ids = [str(item["versionId"]) for item in knowledge]
        if len(skill_version_ids) != len(set(skill_version_ids)):
            raise ValueError("content_manifest_skill_duplicate")
        if len(knowledge_version_ids) != len(set(knowledge_version_ids)):
            raise ValueError("content_manifest_knowledge_duplicate")
        generated_at = _valid_iso_timestamp(
            manifest["generatedAt"], "content_manifest_invalid"
        )
        expires_at = _valid_iso_timestamp(
            manifest["expiresAt"], "content_manifest_invalid"
        )
        generated_epoch = _timestamp_epoch(generated_at, "content_manifest_invalid")
        expires_epoch = _timestamp_epoch(expires_at, "content_manifest_invalid")
        if expires_epoch <= generated_epoch:
            raise ValueError("content_manifest_invalid")
        return {
            "manifestVersion": manifest_version,
            "etag": _sha256(manifest["etag"], "content_manifest_invalid"),
            "organizationId": _identifier(
                manifest["organizationId"], "content_manifest_invalid"
            ),
            "userId": _identifier(manifest["userId"], "content_manifest_invalid"),
            "deviceId": _identifier(manifest["deviceId"], "content_manifest_invalid"),
            "projectId": project_id,
            "generatedAt": generated_at,
            "expiresAt": expires_at,
            "skills": skills,
            "knowledge": knowledge,
            "generatedAtEpoch": generated_epoch,
            "expiresAtEpoch": expires_epoch,
        }

    def _cache_authoritative_skill(
        self, organization_id: str, skill: Mapping[str, object], published_at: str
    ) -> None:
        version_id = str(skill["versionId"])
        rules = skill["rules"]
        canonical = _canonical_json(skill)
        existing = self.connection.execute(
            "SELECT * FROM skill_versions WHERE version_id = ?", (version_id,)
        ).fetchone()
        if existing:
            if (
                str(existing["organization_id"]) != organization_id
                or str(existing["skill_id"]) != str(skill["skillId"])
                or str(existing["version"]) != str(skill["version"])
                or str(existing["name"]) != str(skill["name"])
                or json.loads(str(existing["rules_json"])) != rules
                or str(existing["content_sha256"]) != str(skill["contentSha256"])
            ):
                raise ValueError("skill_version_immutable_conflict")
            return
        self.connection.execute(
            """
            INSERT INTO skill_versions(
                version_id, organization_id, skill_id, version, name, status,
                rules_json, canonical_json, content_sha256, published_by,
                published_at, created_at
            ) VALUES (?, ?, ?, ?, ?, 'published', ?, ?, ?, ?, ?, ?)
            """,
            (
                version_id,
                organization_id,
                skill["skillId"],
                skill["version"],
                skill["name"],
                _canonical_json(rules),
                canonical,
                skill["contentSha256"],
                "supabase-control-plane",
                published_at,
                self.clock(),
            ),
        )

    def _latest_authoritative_manifest(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        local_project_id: str,
        required: bool,
    ) -> Optional[tuple[sqlite3.Row, dict]]:
        row = self.connection.execute(
            """
            SELECT * FROM authoritative_content_manifests
            WHERE organization_id = ? AND user_id = ? AND device_id = ?
              AND local_project_id = ?
            ORDER BY manifest_version DESC
            LIMIT 1
            """,
            (organization_id, user_id, device_id, local_project_id),
        ).fetchone()
        if not row:
            if required:
                raise ValueError("content_manifest_unavailable")
            return None
        if float(row["expires_at"]) <= self.clock():
            raise ValueError("content_manifest_expired")
        payload = json.loads(str(row["payload_json"]))
        if (
            payload.get("organizationId") != organization_id
            or payload.get("userId") != user_id
            or payload.get("deviceId") != device_id
        ):
            raise PermissionError("content_manifest_identity_mismatch")
        return row, payload

    def _require_project_identity_binding(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        local_project_id: str,
        gateway_project_id: str,
        manifest_row: sqlite3.Row,
        manifest: Mapping[str, object],
    ) -> sqlite3.Row:
        binding = self.connection.execute(
            """
            SELECT * FROM project_identity_bindings
            WHERE organization_id = ? AND user_id = ? AND device_id = ?
              AND local_project_id = ? AND status = 'active'
            """,
            (organization_id, user_id, device_id, local_project_id),
        ).fetchone()
        if not binding:
            raise ValueError("project_identity_unbound")
        authoritative_project_id = str(manifest_row["authoritative_project_id"] or "")
        if (
            not authoritative_project_id
            or manifest.get("projectId") != authoritative_project_id
            or str(binding["gateway_project_id"]) != gateway_project_id
            or str(binding["authoritative_project_id"]) != authoritative_project_id
            or int(binding["source_manifest_version"]) != int(manifest_row["manifest_version"])
            or str(binding["source_manifest_etag"]) != str(manifest_row["etag"])
        ):
            raise ValueError("project_identity_conflict")
        return binding

    def _knowledge_snapshot_for_skill(
        self, skill: SkillSnapshot, manifest: Mapping[str, object]
    ) -> tuple[tuple[KnowledgeReference, ...], tuple[dict[str, str], ...]]:
        required_scopes, optional_scopes = _skill_knowledge_policy(skill.rules)
        allowed_scopes = set(str(item) for item in skill.rules["knowledgeScopes"])
        now = self.clock()
        references: list[KnowledgeReference] = []
        coverage: set[str] = set()
        knowledge_items = manifest.get("knowledge", [])
        if not isinstance(knowledge_items, list):
            raise ValueError("content_manifest_invalid")
        for item in knowledge_items:
            if not isinstance(item, Mapping):
                raise ValueError("content_manifest_invalid")
            scopes = tuple(str(value) for value in item.get("knowledgeScopes", []))
            matched = allowed_scopes & set(scopes)
            if not matched:
                continue
            valid_from = item.get("validFrom")
            expires_at = item.get("expiresAt")
            if valid_from and _timestamp_epoch(valid_from, "content_manifest_invalid") > now:
                continue
            if expires_at and _timestamp_epoch(expires_at, "content_manifest_invalid") <= now:
                continue
            references.append(self._knowledge_reference(item))
            coverage.update(matched)
        missing_required = [scope for scope in required_scopes if scope not in coverage]
        if missing_required:
            raise ValueError("required_knowledge_unavailable")
        omissions = tuple(
            {
                "scope": scope,
                "reason": "not_authorized_or_unavailable",
            }
            for scope in optional_scopes
            if scope not in coverage
        )
        references.sort(key=lambda item: (item.knowledge_id, item.version_id))
        return tuple(references), omissions

    def _knowledge_reference(self, value: Mapping[str, object]) -> KnowledgeReference:
        return KnowledgeReference(
            knowledge_id=str(value["knowledgeId"]),
            version_id=str(value["versionId"]),
            version=int(value["version"]),
            title=str(value["title"]),
            summary=str(value["summary"]),
            language=str(value["language"]),
            sensitivity=str(value["sensitivity"]),
            source_type=str(value["sourceType"]),
            source_reference=str(value["sourceReference"]),
            content_sha256=str(value["contentSha256"]),
            knowledge_scopes=tuple(str(item) for item in value["knowledgeScopes"]),
            valid_from=str(value["validFrom"]) if value.get("validFrom") else None,
            expires_at=str(value["expiresAt"]) if value.get("expiresAt") else None,
            authorization_scope=str(value["authorizationScope"]),
        )

    def _knowledge_reference_from_payload(self, value: Mapping[str, object]) -> KnowledgeReference:
        return self._knowledge_reference(value)

    def publish_skill_version(self, command: Mapping[str, object]) -> dict:
        if not isinstance(command, Mapping) or set(command) != _SKILL_FIELDS:
            raise ValueError("skill_version_unknown_field")
        version_id = _identifier(command["versionId"], "skill_version_id_invalid")
        organization_id = _identifier(command["organizationId"], "organization_id_invalid")
        skill_id = _identifier(command["skillId"], "skill_id_invalid")
        version = _required_text(command["version"], 40, "skill_version_invalid")
        if not _SEMVER.fullmatch(version):
            raise ValueError("skill_version_invalid")
        name = _required_text(command["name"], 240, "skill_name_invalid")
        if command["status"] != "published":
            raise ValueError("skill_version_not_published")
        rules = _validate_skill_rules(command["rules"])
        published_by = _identifier(command["publishedBy"], "skill_publisher_invalid")
        published_at = _valid_iso_timestamp(command["publishedAt"], "skill_published_at_invalid")
        normalized = {
            "versionId": version_id,
            "organizationId": organization_id,
            "skillId": skill_id,
            "version": version,
            "name": name,
            "status": "published",
            "rules": rules,
            "publishedBy": published_by,
            "publishedAt": published_at,
        }
        canonical = _canonical_json(normalized)
        digest = _sha256_text(canonical)
        with self.lock:
            existing = self.connection.execute(
                "SELECT canonical_json, content_sha256 FROM skill_versions WHERE version_id = ?",
                (version_id,),
            ).fetchone()
            if existing:
                if str(existing["canonical_json"]) != canonical:
                    raise ValueError("skill_version_immutable_conflict")
                return {"versionId": version_id, "sha256": str(existing["content_sha256"])}
            try:
                self.connection.execute(
                    """
                    INSERT INTO skill_versions(
                        version_id, organization_id, skill_id, version, name, status,
                        rules_json, canonical_json, content_sha256, published_by,
                        published_at, created_at
                    ) VALUES (?, ?, ?, ?, ?, 'published', ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        version_id,
                        organization_id,
                        skill_id,
                        version,
                        name,
                        _canonical_json(rules),
                        canonical,
                        digest,
                        published_by,
                        published_at,
                        self.clock(),
                    ),
                )
            except sqlite3.IntegrityError:
                raise ValueError("skill_version_immutable_conflict") from None
            self.connection.commit()
        return {"versionId": version_id, "sha256": digest}

    def assign_skill(self, command: Mapping[str, object]) -> dict:
        if not isinstance(command, Mapping) or set(command) != _ASSIGNMENT_FIELDS:
            raise ValueError("skill_assignment_unknown_field")
        assignment_id = _identifier(command["assignmentId"], "skill_assignment_id_invalid")
        organization_id = _identifier(command["organizationId"], "organization_id_invalid")
        version_id = _identifier(command["skillVersionId"], "skill_version_id_invalid")
        user_id = _optional_identifier(command["userId"], "skill_assignment_user_invalid")
        device_id = _optional_identifier(command["deviceId"], "skill_assignment_device_invalid")
        project_id = _optional_identifier(command["projectId"], "skill_assignment_project_invalid")
        if not any((user_id, device_id, project_id)):
            raise ValueError("skill_assignment_scope_required")
        if command["status"] != "active":
            raise ValueError("skill_assignment_status_invalid")
        active_from = command["activeFrom"]
        expires_at = command["expiresAt"]
        if not isinstance(active_from, (int, float)) or isinstance(active_from, bool):
            raise ValueError("skill_assignment_time_invalid")
        if expires_at is not None and (
            not isinstance(expires_at, (int, float))
            or isinstance(expires_at, bool)
            or float(expires_at) <= float(active_from)
        ):
            raise ValueError("skill_assignment_time_invalid")
        normalized = {
            "assignmentId": assignment_id,
            "organizationId": organization_id,
            "skillVersionId": version_id,
            "userId": user_id or None,
            "deviceId": device_id or None,
            "projectId": project_id or None,
            "status": "active",
            "activeFrom": float(active_from),
            "expiresAt": float(expires_at) if expires_at is not None else None,
        }
        canonical = _canonical_json(normalized)
        with self.lock:
            version = self.connection.execute(
                """
                SELECT organization_id FROM skill_versions
                WHERE version_id = ? AND status = 'published'
                """,
                (version_id,),
            ).fetchone()
            if not version or str(version["organization_id"]) != organization_id:
                raise LookupError("skill_version_not_found")
            if project_id:
                project = self.connection.execute(
                    "SELECT id FROM ops_projects WHERE id = ?", (project_id,)
                ).fetchone()
                if not project:
                    raise LookupError("project_not_found")
            existing = self.connection.execute(
                "SELECT canonical_json FROM skill_assignments WHERE assignment_id = ?",
                (assignment_id,),
            ).fetchone()
            if existing:
                if str(existing["canonical_json"]) != canonical:
                    raise ValueError("skill_assignment_immutable_conflict")
                return {"assignmentId": assignment_id, "duplicate": True}
            self.connection.execute(
                """
                INSERT INTO skill_assignments(
                    assignment_id, organization_id, skill_version_id, user_id,
                    device_id, project_id, status, active_from, expires_at,
                    canonical_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, ?)
                """,
                (
                    assignment_id,
                    organization_id,
                    version_id,
                    user_id or None,
                    device_id or None,
                    project_id or None,
                    float(active_from),
                    float(expires_at) if expires_at is not None else None,
                    canonical,
                    self.clock(),
                ),
            )
            self.connection.commit()
        return {"assignmentId": assignment_id, "duplicate": False}

    def activate_task_skill(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        local_task_id: str,
        skill_version_id: str,
        local_project_id: str = "",
    ) -> SkillSnapshot:
        organization_id = _identifier(organization_id, "organization_id_invalid")
        user_id = _identifier(user_id, "user_id_invalid")
        device_id = _identifier(device_id, "device_id_invalid")
        local_task_id = _identifier(local_task_id, "task_id_invalid")
        skill_version_id = _identifier(skill_version_id, "skill_version_id_invalid")
        if local_project_id:
            local_project_id = _identifier(local_project_id, "project_id_invalid")
        now = self.clock()
        with self.lock:
            project_id = ""
            if local_project_id:
                project = self._project(device_id, local_project_id)
                if not project:
                    raise LookupError("project_not_found")
                project_id = str(project["id"])
            if project_id:
                task = self.connection.execute(
                    """
                    SELECT task.id, task.status, task.project_id
                    FROM maintenance_tasks task
                    WHERE task.device_id = ? AND task.project_id = ?
                      AND task.local_task_id = ?
                    """,
                    (device_id, project_id, local_task_id),
                ).fetchone()
            else:
                task = self.connection.execute(
                    """
                    SELECT task.id, task.status, task.project_id
                    FROM maintenance_tasks task
                    WHERE task.device_id = ? AND task.local_task_id = ?
                    """,
                    (device_id, local_task_id),
                ).fetchone()
            if not task:
                raise LookupError("task_not_found")
            if str(task["status"]) != "active":
                raise ValueError("task_not_active")
            task_project = self.connection.execute(
                "SELECT local_project_id FROM ops_projects WHERE id = ?",
                (str(task["project_id"]),),
            ).fetchone()
            if not task_project:
                raise LookupError("project_not_found")
            resolved_local_project_id = str(task_project["local_project_id"])
            authoritative = self._latest_authoritative_manifest(
                organization_id,
                user_id,
                device_id,
                resolved_local_project_id,
                self.require_authoritative_manifest,
            )
            knowledge_references: tuple[KnowledgeReference, ...] = ()
            knowledge_omissions: tuple[dict[str, str], ...] = ()
            if authoritative:
                manifest_row, manifest = authoritative
                self._require_project_identity_binding(
                    organization_id,
                    user_id,
                    device_id,
                    resolved_local_project_id,
                    str(task["project_id"]),
                    manifest_row,
                    manifest,
                )
                manifest_skill = next(
                    (
                        item
                        for item in manifest["skills"]
                        if item["versionId"] == skill_version_id
                    ),
                    None,
                )
                if not manifest_skill:
                    raise PermissionError("skill_not_authorized")
                version = self.connection.execute(
                    """
                    SELECT * FROM skill_versions
                    WHERE version_id = ? AND organization_id = ? AND status = 'published'
                    """,
                    (skill_version_id, organization_id),
                ).fetchone()
                if not version:
                    raise LookupError("skill_version_not_found")
                skill = self._skill_snapshot(version)
                knowledge_references, knowledge_omissions = self._knowledge_snapshot_for_skill(
                    skill, manifest
                )
            else:
                manifest_row = None
                version = self.connection.execute(
                    """
                    SELECT * FROM skill_versions
                    WHERE version_id = ? AND organization_id = ? AND status = 'published'
                    """,
                    (skill_version_id, organization_id),
                ).fetchone()
                if not version:
                    raise LookupError("skill_version_not_found")
                authorized = self.connection.execute(
                    """
                    SELECT assignment_id FROM skill_assignments
                    WHERE organization_id = ? AND skill_version_id = ? AND status = 'active'
                      AND active_from <= ? AND (expires_at IS NULL OR expires_at > ?)
                      AND (user_id IS NULL OR user_id = ?)
                      AND (device_id IS NULL OR device_id = ?)
                      AND (project_id IS NULL OR project_id = ?)
                    LIMIT 1
                    """,
                    (
                        organization_id,
                        skill_version_id,
                        now,
                        now,
                        user_id,
                        device_id,
                        str(task["project_id"]),
                    ),
                ).fetchone()
                if not authorized:
                    raise PermissionError("skill_not_authorized")
                skill = self._skill_snapshot(version)
            existing = self.connection.execute(
                "SELECT * FROM task_skill_snapshots WHERE task_id = ? AND deactivated_at IS NULL",
                (str(task["id"]),),
            ).fetchone()
            if existing:
                if str(existing["skill_version_id"]) != skill_version_id:
                    raise ValueError("task_skill_already_active")
                if manifest_row is not None:
                    content_snapshot = self.connection.execute(
                        """
                        SELECT snapshot_id FROM task_content_snapshots
                        WHERE snapshot_id = ? AND deactivated_at IS NULL
                        """,
                        (str(existing["snapshot_id"]),),
                    ).fetchone()
                    if not content_snapshot:
                        raise ValueError("content_manifest_snapshot_missing")
                return self._snapshot_from_task_row(existing)
            snapshot_json = _canonical_json(skill.to_payload())
            snapshot_id = str(uuid.uuid4())
            self.connection.execute(
                """
                INSERT INTO task_skill_snapshots(
                    snapshot_id, task_id, organization_id, user_id, device_id, skill_version_id,
                    snapshot_json, snapshot_sha256, activated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    snapshot_id,
                    str(task["id"]),
                    organization_id,
                    user_id,
                    device_id,
                    skill_version_id,
                    snapshot_json,
                    skill.digest,
                    now,
                ),
            )
            if manifest_row is not None:
                self.connection.execute(
                    """
                    INSERT INTO task_content_snapshots(
                        snapshot_id, task_id, organization_id, user_id, device_id,
                        local_project_id, authoritative_project_id,
                        source_manifest_version, source_manifest_etag,
                        skill_id, skill_version_id, knowledge_snapshot_json,
                        knowledge_omissions_json, activated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        snapshot_id,
                        str(task["id"]),
                        organization_id,
                        user_id,
                        device_id,
                        resolved_local_project_id,
                        str(manifest_row["authoritative_project_id"]),
                        int(manifest_row["manifest_version"]),
                        str(manifest_row["etag"]),
                        skill.skill_id,
                        skill.version_id,
                        _canonical_json(
                            [item.to_payload() for item in knowledge_references]
                        ),
                        _canonical_json(list(knowledge_omissions)),
                        now,
                    ),
                )
            self.connection.commit()
        return skill

    def list_authorized_skills(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        local_project_id: str = "",
        local_task_id: str = "",
    ) -> list[dict]:
        organization_id = _identifier(organization_id, "organization_id_invalid")
        user_id = _identifier(user_id, "user_id_invalid")
        device_id = _identifier(device_id, "device_id_invalid")
        if local_project_id:
            local_project_id = _identifier(local_project_id, "project_id_invalid")
        if local_task_id:
            local_task_id = _identifier(local_task_id, "task_id_invalid")
        if local_task_id and not local_project_id:
            raise ValueError("project_id_required")
        now = self.clock()
        with self.lock:
            project_id: Optional[str] = None
            task_id: Optional[str] = None
            active_version_id = ""
            if local_project_id:
                project = self._project(device_id, local_project_id)
                if not project:
                    raise LookupError("project_not_found")
                project_id = str(project["id"])
            if local_task_id:
                task = self.connection.execute(
                    """
                    SELECT id FROM maintenance_tasks
                    WHERE device_id = ? AND project_id = ? AND local_task_id = ?
                    """,
                    (device_id, project_id, local_task_id),
                ).fetchone()
                if not task:
                    raise LookupError("task_not_found")
                task_id = str(task["id"])
                active = self.connection.execute(
                    """
                    SELECT skill_version_id FROM task_skill_snapshots
                    WHERE task_id = ? AND deactivated_at IS NULL
                    """,
                    (task_id,),
                ).fetchone()
                active_version_id = str(active["skill_version_id"]) if active else ""
            authoritative = self._latest_authoritative_manifest(
                organization_id,
                user_id,
                device_id,
                local_project_id,
                self.require_authoritative_manifest,
            )
            if authoritative:
                _, manifest = authoritative
                items = []
                for item in manifest["skills"]:
                    items.append(
                        {
                            "versionId": item["versionId"],
                            "skillId": item["skillId"],
                            "version": item["version"],
                            "name": item["name"],
                            "description": item["description"],
                            "sha256": item["contentSha256"],
                            "authorizationScope": item["authorizationScope"],
                            "activeForTask": item["versionId"] == active_version_id,
                        }
                    )
                return items
            rows = self.connection.execute(
                """
                SELECT DISTINCT version.*
                FROM skill_versions version
                JOIN skill_assignments assignment
                  ON assignment.skill_version_id = version.version_id
                WHERE version.organization_id = ? AND version.status = 'published'
                  AND assignment.organization_id = ? AND assignment.status = 'active'
                  AND assignment.active_from <= ?
                  AND (assignment.expires_at IS NULL OR assignment.expires_at > ?)
                  AND (assignment.user_id IS NULL OR assignment.user_id = ?)
                  AND (assignment.device_id IS NULL OR assignment.device_id = ?)
                  AND (assignment.project_id IS NULL OR assignment.project_id = ?)
                ORDER BY version.name, version.version_id
                """,
                (
                    organization_id,
                    organization_id,
                    now,
                    now,
                    user_id,
                    device_id,
                    project_id,
                ),
            ).fetchall()
        items: list[dict] = []
        for row in rows:
            payload = self._skill_snapshot(row).to_catalog_payload()
            payload["activeForTask"] = payload["versionId"] == active_version_id
            items.append(payload)
        return items

    def device_content_manifest(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        local_project_id: str,
    ) -> dict:
        organization_id = _identifier(organization_id, "organization_id_invalid")
        user_id = _identifier(user_id, "user_id_invalid")
        device_id = _identifier(device_id, "device_id_invalid")
        local_project_id = _identifier(local_project_id, "project_id_invalid")
        with self.lock:
            project = self._project(device_id, local_project_id)
            if not project:
                raise LookupError("project_not_found")
            row, manifest = self._latest_authoritative_manifest(
                organization_id,
                user_id,
                device_id,
                local_project_id,
                True,
            )
            self._require_project_identity_binding(
                organization_id,
                user_id,
                device_id,
                local_project_id,
                str(project["id"]),
                row,
                manifest,
            )
            skills = [
                {
                    "skillId": item["skillId"],
                    "versionId": item["versionId"],
                    "version": item["version"],
                    "name": item["name"],
                    "description": item["description"],
                    "contentSha256": item["contentSha256"],
                    "knowledgeScopes": list(item["rules"]["knowledgeScopes"]),
                    "authorizationScope": item["authorizationScope"],
                }
                for item in manifest["skills"]
            ]
            knowledge = [
                {
                    "knowledgeId": item["knowledgeId"],
                    "versionId": item["versionId"],
                    "version": item["version"],
                    "title": item["title"],
                    "summary": item["summary"],
                    "language": item["language"],
                    "sensitivity": item["sensitivity"],
                    "sourceType": item["sourceType"],
                    "sourceReference": item["sourceReference"],
                    "contentSha256": item["contentSha256"],
                    "knowledgeScopes": list(item["knowledgeScopes"]),
                    "validFrom": item["validFrom"],
                    "expiresAt": item["expiresAt"],
                    "authorizationScope": item["authorizationScope"],
                }
                for item in manifest["knowledge"]
            ]
        return {
            "manifestVersion": int(row["manifest_version"]),
            "etag": str(row["etag"]),
            "localProjectId": local_project_id,
            "projectId": str(row["authoritative_project_id"]),
            "generatedAt": str(manifest["generatedAt"]),
            "expiresAt": str(manifest["expiresAt"]),
            "skills": skills,
            "knowledge": knowledge,
        }

    def deactivate_task_skill(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        local_project_id: str,
        local_task_id: str,
    ) -> dict:
        organization_id = _identifier(organization_id, "organization_id_invalid")
        user_id = _identifier(user_id, "user_id_invalid")
        device_id = _identifier(device_id, "device_id_invalid")
        local_project_id = _identifier(local_project_id, "project_id_invalid")
        local_task_id = _identifier(local_task_id, "task_id_invalid")
        with self.lock:
            project = self._project(device_id, local_project_id)
            if not project:
                raise LookupError("project_not_found")
            task = self.connection.execute(
                """
                SELECT id, status FROM maintenance_tasks
                WHERE device_id = ? AND project_id = ? AND local_task_id = ?
                """,
                (device_id, str(project["id"]), local_task_id),
            ).fetchone()
            if not task:
                raise LookupError("task_not_found")
            if str(task["status"]) != "active":
                raise ValueError("task_not_active")
            snapshot = self.connection.execute(
                """
                SELECT * FROM task_skill_snapshots
                WHERE task_id = ? AND deactivated_at IS NULL
                """,
                (str(task["id"]),),
            ).fetchone()
            if not snapshot:
                raise ValueError("skill_not_active")
            if (
                str(snapshot["organization_id"]) != organization_id
                or str(snapshot["user_id"]) != user_id
                or str(snapshot["device_id"]) != device_id
            ):
                raise PermissionError("skill_not_authorized")
            self.connection.execute(
                """
                UPDATE task_skill_snapshots SET deactivated_at = ?
                WHERE snapshot_id = ? AND deactivated_at IS NULL
                """,
                (self.clock(), str(snapshot["snapshot_id"])),
            )
            self.connection.execute(
                """
                UPDATE task_content_snapshots SET deactivated_at = ?
                WHERE snapshot_id = ? AND deactivated_at IS NULL
                """,
                (self.clock(), str(snapshot["snapshot_id"])),
            )
            self.connection.commit()
        return {
            "active": False,
            "skillVersionId": str(snapshot["skill_version_id"]),
        }

    def list_projects(
        self, organization_id: str, device_id: str
    ) -> list[dict]:
        organization_id = _identifier(organization_id, "organization_id_invalid")
        device_id = _identifier(device_id, "device_id_invalid")
        with self.lock:
            rows = self.connection.execute(
                """
                SELECT project.*,
                       COUNT(task.id) AS task_count,
                       SUM(CASE WHEN task.status = 'active' THEN 1 ELSE 0 END) AS active_task_count,
                       memory.revision AS memory_revision,
                       memory.organization_id AS memory_organization_id
                FROM ops_projects project
                LEFT JOIN maintenance_tasks task ON task.project_id = project.id
                LEFT JOIN project_memories memory ON memory.project_id = project.id
                WHERE project.device_id = ?
                GROUP BY project.id
                ORDER BY project.updated_at DESC, project.local_project_id
                """,
                (device_id,),
            ).fetchall()
        items: list[dict] = []
        for row in rows:
            if row["memory_organization_id"] and str(row["memory_organization_id"]) != organization_id:
                continue
            items.append(
                {
                    "projectId": str(row["id"]),
                    "localProjectId": str(row["local_project_id"]),
                    "title": str(row["title"]),
                    "status": str(row["status"]),
                    "taskCount": int(row["task_count"] or 0),
                    "activeTaskCount": int(row["active_task_count"] or 0),
                    "memoryRevision": int(row["memory_revision"] or 0),
                }
            )
        return items

    def project_detail(
        self, organization_id: str, device_id: str, local_project_id: str
    ) -> dict:
        organization_id = _identifier(organization_id, "organization_id_invalid")
        device_id = _identifier(device_id, "device_id_invalid")
        local_project_id = _identifier(local_project_id, "project_id_invalid")
        with self.lock:
            project = self._project(device_id, local_project_id)
            if not project:
                raise LookupError("project_not_found")
            project_id = str(project["id"])
            identity_binding = self.connection.execute(
                """
                SELECT authoritative_project_id FROM project_identity_bindings
                WHERE organization_id = ? AND device_id = ?
                  AND local_project_id = ? AND gateway_project_id = ?
                  AND status = 'active'
                """,
                (organization_id, device_id, local_project_id, project_id),
            ).fetchone()
            memory = self.connection.execute(
                "SELECT * FROM project_memories WHERE project_id = ?",
                (project_id,),
            ).fetchone()
            if memory and str(memory["organization_id"]) != organization_id:
                raise PermissionError("project_forbidden")
            task_rows = self.connection.execute(
                """
                SELECT task.*,
                       snapshot.snapshot_id AS skill_snapshot_id,
                       snapshot.skill_version_id AS skill_version_id,
                       snapshot.deactivated_at AS skill_deactivated_at,
                       content.snapshot_id AS content_snapshot_id,
                       content.source_manifest_version AS content_manifest_version,
                       content.knowledge_snapshot_json AS knowledge_snapshot_json,
                       content.deactivated_at AS content_deactivated_at,
                       summary.summary AS end_summary
                FROM maintenance_tasks task
                LEFT JOIN task_skill_snapshots snapshot
                  ON snapshot.snapshot_id = (
                      SELECT candidate.snapshot_id
                      FROM task_skill_snapshots candidate
                      WHERE candidate.task_id = task.id
                      ORDER BY candidate.activated_at DESC, candidate.rowid DESC
                      LIMIT 1
                  )
                LEFT JOIN task_content_snapshots content
                  ON content.snapshot_id = snapshot.snapshot_id
                LEFT JOIN task_end_summaries summary ON summary.task_id = task.id
                WHERE task.project_id = ? AND task.device_id = ?
                ORDER BY task.updated_at DESC, task.local_task_id
                """,
                (project_id, device_id),
            ).fetchall()
            instruction_rows = self.connection.execute(
                """
                SELECT item.* FROM project_instruction_versions item
                JOIN (
                    SELECT instruction_id, MAX(version) AS version
                    FROM project_instruction_versions
                    WHERE project_id = ?
                    GROUP BY instruction_id
                ) latest
                  ON latest.instruction_id = item.instruction_id
                 AND latest.version = item.version
                WHERE item.project_id = ? AND item.organization_id = ?
                ORDER BY item.instruction_id
                """,
                (project_id, project_id, organization_id),
            ).fetchall()
        tasks: list[dict] = []
        for row in task_rows:
            skill_version_id = str(row["skill_version_id"] or "")
            knowledge_version_ids: list[str] = []
            if row["knowledge_snapshot_json"]:
                references = json.loads(str(row["knowledge_snapshot_json"]))
                knowledge_version_ids = [
                    str(item["versionId"])
                    for item in references
                    if isinstance(item, Mapping) and item.get("versionId")
                ]
            content_snapshot_active = bool(
                row["content_snapshot_id"]
                and row["skill_deactivated_at"] is None
                and row["content_deactivated_at"] is None
            )
            tasks.append(
                {
                    "taskId": str(row["id"]),
                    "localTaskId": str(row["local_task_id"]),
                    "title": str(row["title"]),
                    "status": str(row["status"]),
                    "activeSkillVersionId": (
                        skill_version_id
                        if row["skill_deactivated_at"] is None
                        else ""
                    ),
                    "skillVersionId": skill_version_id,
                    "knowledgeVersionIds": knowledge_version_ids,
                    "contentManifestVersion": int(row["content_manifest_version"] or 0),
                    "contentSnapshotActive": content_snapshot_active,
                    "endSummary": str(row["end_summary"] or ""),
                }
            )
        return {
            "projectId": project_id,
            "authoritativeProjectId": (
                str(identity_binding["authoritative_project_id"])
                if identity_binding else ""
            ),
            "localProjectId": local_project_id,
            "title": str(project["title"]),
            "status": str(project["status"]),
            "projectMemory": {
                "summary": str(memory["summary"]) if memory else "",
                "confirmedFacts": json.loads(str(memory["confirmed_facts_json"])) if memory else [],
                "excludedFacts": json.loads(str(memory["excluded_facts_json"])) if memory else [],
                "risks": json.loads(str(memory["risks_json"])) if memory else [],
                "revision": int(memory["revision"]) if memory else 0,
            },
            "tasks": tasks,
            "projectInstructions": [
                {
                    "instructionId": str(row["instruction_id"]),
                    "version": int(row["version"]),
                    "status": str(row["status"]),
                    "condition": json.loads(str(row["condition_json"])),
                    "action": str(row["action_text"]),
                    "exceptions": json.loads(str(row["exceptions_json"])),
                    "sourceTraceId": str(row["source_trace_id"]),
                }
                for row in instruction_rows
            ],
        }

    def end_task(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        local_project_id: str,
        local_task_id: str,
        task_status: str,
        summary: str,
        expected_memory_revision: int,
        confirmed_facts: list[str],
        excluded_facts: list[str],
        risks: list[str],
    ) -> dict:
        organization_id = _identifier(organization_id, "organization_id_invalid")
        user_id = _identifier(user_id, "user_id_invalid")
        device_id = _identifier(device_id, "device_id_invalid")
        local_project_id = _identifier(local_project_id, "project_id_invalid")
        local_task_id = _identifier(local_task_id, "task_id_invalid")
        if task_status not in {"completed", "closed"}:
            raise ValueError("task_status_invalid")
        summary = _required_display_text(summary, 8000, "task_summary_invalid")
        if not isinstance(expected_memory_revision, int) or isinstance(expected_memory_revision, bool) or expected_memory_revision < 0:
            raise ValueError("project_memory_revision_invalid")
        confirmed = _string_list(confirmed_facts, 100, 1000, "project_memory_facts_invalid")
        excluded = _string_list(excluded_facts, 100, 1000, "project_memory_facts_invalid")
        normalized_risks = _string_list(risks, 100, 1000, "project_memory_risks_invalid")
        now = self.clock()
        with self.lock:
            project = self._project(device_id, local_project_id)
            if not project:
                raise LookupError("project_not_found")
            project_id = str(project["id"])
            task = self.connection.execute(
                """
                SELECT id, status FROM maintenance_tasks
                WHERE device_id = ? AND project_id = ? AND local_task_id = ?
                """,
                (device_id, project_id, local_task_id),
            ).fetchone()
            if not task:
                raise LookupError("task_not_found")
            if str(task["status"]) != "active":
                raise ValueError("task_not_active")
            memory = self.connection.execute(
                "SELECT revision, organization_id FROM project_memories WHERE project_id = ?",
                (project_id,),
            ).fetchone()
            current_revision = int(memory["revision"]) if memory else 0
            if current_revision != expected_memory_revision:
                raise ValueError("project_memory_revision_conflict")
            if memory and str(memory["organization_id"]) != organization_id:
                raise PermissionError("project_forbidden")
            memory_revision = current_revision + 1
            self.connection.execute(
                """
                INSERT INTO project_memories(
                    project_id, organization_id, updated_by, summary,
                    confirmed_facts_json, excluded_facts_json, risks_json,
                    revision, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_id) DO UPDATE SET
                    organization_id = excluded.organization_id,
                    updated_by = excluded.updated_by,
                    summary = excluded.summary,
                    confirmed_facts_json = excluded.confirmed_facts_json,
                    excluded_facts_json = excluded.excluded_facts_json,
                    risks_json = excluded.risks_json,
                    revision = excluded.revision,
                    updated_at = excluded.updated_at
                """,
                (
                    project_id,
                    organization_id,
                    user_id,
                    summary,
                    _canonical_json(confirmed),
                    _canonical_json(excluded),
                    _canonical_json(normalized_risks),
                    memory_revision,
                    now,
                ),
            )
            self.connection.execute(
                """
                INSERT INTO task_end_summaries(
                    task_id, organization_id, ended_by, task_status,
                    summary, memory_revision, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    str(task["id"]),
                    organization_id,
                    user_id,
                    task_status,
                    summary,
                    memory_revision,
                    now,
                ),
            )
            self.connection.execute(
                "UPDATE maintenance_tasks SET status = ?, updated_at = ? WHERE id = ?",
                (task_status, now, str(task["id"])),
            )
            self.connection.execute(
                """
                UPDATE task_skill_snapshots SET deactivated_at = ?
                WHERE task_id = ? AND deactivated_at IS NULL
                """,
                (now, str(task["id"])),
            )
            self.connection.execute(
                """
                UPDATE task_content_snapshots SET deactivated_at = ?
                WHERE task_id = ? AND deactivated_at IS NULL
                """,
                (now, str(task["id"])),
            )
            self.connection.commit()
        return {
            "projectId": project_id,
            "taskId": str(task["id"]),
            "localProjectId": local_project_id,
            "localTaskId": local_task_id,
            "taskStatus": task_status,
            "memoryRevision": memory_revision,
            "summary": summary,
        }

    def update_project_memory(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        local_project_id: str,
        expected_revision: int,
        summary: str,
        confirmed_facts: list[str],
        excluded_facts: list[str],
        risks: list[str],
    ) -> dict:
        organization_id = _identifier(organization_id, "organization_id_invalid")
        user_id = _identifier(user_id, "user_id_invalid")
        device_id = _identifier(device_id, "device_id_invalid")
        local_project_id = _identifier(local_project_id, "project_id_invalid")
        if not isinstance(expected_revision, int) or isinstance(expected_revision, bool) or expected_revision < 0:
            raise ValueError("project_memory_revision_invalid")
        if not isinstance(summary, str) or len(summary.strip()) > 8000:
            raise ValueError("project_memory_summary_invalid")
        summary = summary.strip()
        confirmed = _string_list(confirmed_facts, 100, 1000, "project_memory_facts_invalid")
        excluded = _string_list(excluded_facts, 100, 1000, "project_memory_facts_invalid")
        normalized_risks = _string_list(risks, 100, 1000, "project_memory_risks_invalid")
        with self.lock:
            project = self._project(device_id, local_project_id)
            if not project:
                raise LookupError("project_not_found")
            existing = self.connection.execute(
                "SELECT revision, organization_id FROM project_memories WHERE project_id = ?",
                (str(project["id"]),),
            ).fetchone()
            current_revision = int(existing["revision"]) if existing else 0
            if current_revision != expected_revision:
                raise ValueError("project_memory_revision_conflict")
            if existing and str(existing["organization_id"]) != organization_id:
                raise PermissionError("project_forbidden")
            revision = current_revision + 1
            self.connection.execute(
                """
                INSERT INTO project_memories(
                    project_id, organization_id, updated_by, summary,
                    confirmed_facts_json, excluded_facts_json, risks_json,
                    revision, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_id) DO UPDATE SET
                    organization_id = excluded.organization_id,
                    updated_by = excluded.updated_by,
                    summary = excluded.summary,
                    confirmed_facts_json = excluded.confirmed_facts_json,
                    excluded_facts_json = excluded.excluded_facts_json,
                    risks_json = excluded.risks_json,
                    revision = excluded.revision,
                    updated_at = excluded.updated_at
                """,
                (
                    str(project["id"]),
                    organization_id,
                    user_id,
                    summary,
                    _canonical_json(confirmed),
                    _canonical_json(excluded),
                    _canonical_json(normalized_risks),
                    revision,
                    self.clock(),
                ),
            )
            self.connection.commit()
        return {"projectId": str(project["id"]), "revision": revision}

    def put_project_instruction(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        local_project_id: str,
        instruction_id: str,
        expected_version: int,
        status: str,
        condition: dict,
        action: str,
        exceptions: list[str],
        source_trace_id: str,
    ) -> dict:
        organization_id = _identifier(organization_id, "organization_id_invalid")
        user_id = _identifier(user_id, "user_id_invalid")
        device_id = _identifier(device_id, "device_id_invalid")
        local_project_id = _identifier(local_project_id, "project_id_invalid")
        instruction_id = _identifier(instruction_id, "project_instruction_id_invalid")
        source_trace_id = _identifier(source_trace_id, "project_instruction_source_invalid")
        if not isinstance(expected_version, int) or isinstance(expected_version, bool) or expected_version < 0:
            raise ValueError("project_instruction_version_invalid")
        if status not in {"active", "disabled", "deleted"}:
            raise ValueError("project_instruction_status_invalid")
        normalized_condition = _validate_condition(condition)
        normalized_action = _required_text(action, 4000, "project_instruction_action_invalid")
        normalized_exceptions = _string_list(
            exceptions, 50, 1000, "project_instruction_exceptions_invalid"
        )
        with self.lock:
            project = self._project(device_id, local_project_id)
            if not project:
                raise LookupError("project_not_found")
            row = self.connection.execute(
                """
                SELECT * FROM project_instruction_versions
                WHERE project_id = ? AND instruction_id = ?
                ORDER BY version DESC
                LIMIT 1
                """,
                (str(project["id"]), instruction_id),
            ).fetchone()
            current_version = int(row["version"]) if row else 0
            if current_version != expected_version:
                raise ValueError("project_instruction_version_conflict")
            if row and str(row["organization_id"]) != organization_id:
                raise PermissionError("project_forbidden")
            if not row:
                if status != "active":
                    raise ValueError("project_instruction_transition_invalid")
            else:
                current_status = str(row["status"])
                if current_status == "deleted" or current_status not in {"active", "disabled"}:
                    raise ValueError("project_instruction_transition_invalid")
                if status not in {"active", "disabled", "deleted"}:
                    raise ValueError("project_instruction_transition_invalid")
                unchanged = (
                    current_status == status
                    and str(row["condition_json"]) == _canonical_json(normalized_condition)
                    and str(row["action_text"]) == normalized_action
                    and str(row["exceptions_json"]) == _canonical_json(normalized_exceptions)
                )
                if unchanged:
                    raise ValueError("project_instruction_no_change")
            version = current_version + 1
            self.connection.execute(
                """
                INSERT INTO project_instruction_versions(
                    project_id, organization_id, instruction_id, version, status,
                    condition_json, action_text, exceptions_json, source_trace_id,
                    created_by, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    str(project["id"]),
                    organization_id,
                    instruction_id,
                    version,
                    status,
                    _canonical_json(normalized_condition),
                    normalized_action,
                    _canonical_json(normalized_exceptions),
                    source_trace_id,
                    user_id,
                    self.clock(),
                ),
            )
            self.connection.execute(
                "UPDATE ops_projects SET updated_at = ? WHERE id = ?",
                (self.clock(), str(project["id"])),
            )
            self.connection.commit()
        return {"instructionId": instruction_id, "version": version, "status": status}

    def build_context(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        local_project_id: str,
        local_task_id: str,
        user_text: str,
        recent_messages: list[dict[str, str]],
        attributes: dict[str, str],
    ) -> ExecutionContext:
        organization_id = _identifier(organization_id, "organization_id_invalid")
        user_id = _identifier(user_id, "user_id_invalid")
        device_id = _identifier(device_id, "device_id_invalid")
        local_project_id = _identifier(local_project_id, "project_id_invalid")
        local_task_id = _identifier(local_task_id, "task_id_invalid")
        user_text = _required_display_text(user_text, 12_000, "user_text_invalid")
        normalized_messages = self._recent_messages(recent_messages)
        normalized_attributes = self._attributes(attributes)
        now = self.clock()
        manifest_version = 0
        manifest_etag = ""
        authoritative_project_id = ""
        knowledge_references: tuple[KnowledgeReference, ...] = ()
        knowledge_omissions: tuple[dict[str, str], ...] = ()
        with self.lock:
            project = self._project(device_id, local_project_id)
            if not project:
                raise LookupError("project_not_found")
            task = self.connection.execute(
                """
                SELECT * FROM maintenance_tasks
                WHERE device_id = ? AND local_task_id = ? AND project_id = ?
                """,
                (device_id, local_task_id, str(project["id"])),
            ).fetchone()
            if not task:
                raise LookupError("task_not_found")
            if str(task["status"]) != "active":
                raise ValueError("task_not_active")
            authoritative = self._latest_authoritative_manifest(
                organization_id,
                user_id,
                device_id,
                local_project_id,
                self.require_authoritative_manifest,
            )
            current_manifest_row: Optional[sqlite3.Row] = None
            current_manifest: Optional[dict] = None
            if authoritative:
                current_manifest_row, current_manifest = authoritative
                binding = self._require_project_identity_binding(
                    organization_id,
                    user_id,
                    device_id,
                    local_project_id,
                    str(project["id"]),
                    current_manifest_row,
                    current_manifest,
                )
                authoritative_project_id = str(binding["authoritative_project_id"])
                manifest_version = int(current_manifest_row["manifest_version"])
                manifest_etag = str(current_manifest_row["etag"])
            snapshot_row = self.connection.execute(
                """
                SELECT snapshot.*, version.organization_id
                FROM task_skill_snapshots snapshot
                JOIN skill_versions version ON version.version_id = snapshot.skill_version_id
                WHERE snapshot.task_id = ? AND snapshot.deactivated_at IS NULL
                """,
                (str(task["id"]),),
            ).fetchone()
            skill: Optional[SkillSnapshot] = None
            if snapshot_row:
                if (
                    str(snapshot_row["organization_id"]) != organization_id
                    or str(snapshot_row["device_id"]) != device_id
                    or str(snapshot_row["user_id"]) != user_id
                ):
                    raise PermissionError("skill_not_authorized")
                skill = self._snapshot_from_task_row(snapshot_row)
                content_snapshot = self.connection.execute(
                    """
                    SELECT * FROM task_content_snapshots
                    WHERE snapshot_id = ? AND deactivated_at IS NULL
                    """,
                    (str(snapshot_row["snapshot_id"]),),
                ).fetchone()
                if content_snapshot:
                    if str(content_snapshot["local_project_id"]) != local_project_id:
                        raise ValueError("project_identity_conflict")
                    if current_manifest_row is None or current_manifest is None:
                        current_manifest_row, current_manifest = self._latest_authoritative_manifest(
                            organization_id,
                            user_id,
                            device_id,
                            local_project_id,
                            True,
                        )
                        binding = self._require_project_identity_binding(
                            organization_id,
                            user_id,
                            device_id,
                            local_project_id,
                            str(project["id"]),
                            current_manifest_row,
                            current_manifest,
                        )
                        authoritative_project_id = str(binding["authoritative_project_id"])
                    if (
                        str(content_snapshot["authoritative_project_id"] or "")
                        != authoritative_project_id
                    ):
                        raise ValueError("project_identity_conflict")
                    current_skill = next(
                        (
                            item
                            for item in current_manifest["skills"]
                            if item["skillId"] == skill.skill_id
                        ),
                        None,
                    )
                    if not current_skill:
                        raise PermissionError("skill_not_authorized")
                    manifest_version = int(current_manifest_row["manifest_version"])
                    manifest_etag = str(current_manifest_row["etag"])
                    current_knowledge = {
                        str(item["knowledgeId"]): item
                        for item in current_manifest["knowledge"]
                    }
                    pinned_values = json.loads(
                        str(content_snapshot["knowledge_snapshot_json"])
                    )
                    pinned = tuple(
                        self._knowledge_reference_from_payload(item)
                        for item in pinned_values
                    )
                    required_scopes, optional_scopes = _skill_knowledge_policy(skill.rules)
                    allowed_scopes = set(str(item) for item in skill.rules["knowledgeScopes"])
                    available: list[KnowledgeReference] = []
                    coverage: set[str] = set()
                    for reference in pinned:
                        current = current_knowledge.get(reference.knowledge_id)
                        if not current:
                            continue
                        current_valid_from = current.get("validFrom")
                        current_expires_at = current.get("expiresAt")
                        if current_valid_from and _timestamp_epoch(
                            current_valid_from, "content_manifest_invalid"
                        ) > now:
                            continue
                        if current_expires_at and _timestamp_epoch(
                            current_expires_at, "content_manifest_invalid"
                        ) <= now:
                            continue
                        if reference.expires_at and _timestamp_epoch(
                            reference.expires_at, "content_manifest_invalid"
                        ) <= now:
                            continue
                        current_scopes = set(
                            str(item) for item in current.get("knowledgeScopes", [])
                        )
                        matched = (
                            set(reference.knowledge_scopes)
                            & current_scopes
                            & allowed_scopes
                        )
                        if not matched:
                            continue
                        available.append(reference)
                        coverage.update(matched)
                    if any(scope not in coverage for scope in required_scopes):
                        raise ValueError("required_knowledge_unavailable")
                    knowledge_references = tuple(available)
                    knowledge_omissions = tuple(
                        {
                            "scope": scope,
                            "reason": "not_authorized_or_unavailable",
                        }
                        for scope in optional_scopes
                        if scope not in coverage
                    )
                else:
                    if self.require_authoritative_manifest:
                        raise ValueError("content_manifest_snapshot_missing")
                    authorized = self.connection.execute(
                        """
                        SELECT assignment_id FROM skill_assignments
                        WHERE organization_id = ? AND skill_version_id = ? AND status = 'active'
                          AND active_from <= ? AND (expires_at IS NULL OR expires_at > ?)
                          AND (user_id IS NULL OR user_id = ?)
                          AND (device_id IS NULL OR device_id = ?)
                          AND (project_id IS NULL OR project_id = ?)
                        LIMIT 1
                        """,
                        (
                            organization_id,
                            str(snapshot_row["skill_version_id"]),
                            now,
                            now,
                            user_id,
                            device_id,
                            str(project["id"]),
                        ),
                    ).fetchone()
                    if not authorized:
                        raise PermissionError("skill_not_authorized")
            memory = self.connection.execute(
                "SELECT * FROM project_memories WHERE project_id = ?",
                (str(project["id"]),),
            ).fetchone()
            if memory and str(memory["organization_id"]) != organization_id:
                raise PermissionError("project_forbidden")
            instruction_rows = self.connection.execute(
                """
                SELECT item.* FROM project_instruction_versions item
                JOIN (
                    SELECT instruction_id, MAX(version) AS version
                    FROM project_instruction_versions
                    WHERE project_id = ?
                    GROUP BY instruction_id
                ) latest
                  ON latest.instruction_id = item.instruction_id
                 AND latest.version = item.version
                WHERE item.project_id = ? AND item.organization_id = ? AND item.status = 'active'
                ORDER BY item.instruction_id
                """,
                (str(project["id"]), str(project["id"]), organization_id),
            ).fetchall()
            instructions: list[ProjectInstructionSnapshot] = []
            for row in instruction_rows:
                condition = json.loads(str(row["condition_json"]))
                if not _condition_matches(condition, user_text, normalized_attributes):
                    continue
                instructions.append(
                    ProjectInstructionSnapshot(
                        instruction_id=str(row["instruction_id"]),
                        version=int(row["version"]),
                        condition=condition,
                        action=str(row["action_text"]),
                        exceptions=tuple(json.loads(str(row["exceptions_json"]))),
                        source_trace_id=str(row["source_trace_id"]),
                    )
                )
        return ExecutionContext(
            organization_id=organization_id,
            user_id=user_id,
            device_id=device_id,
            project_id=str(project["id"]),
            task_id=str(task["id"]),
            local_project_id=local_project_id,
            local_task_id=local_task_id,
            skill=skill,
            project_summary=str(memory["summary"]) if memory else "",
            confirmed_facts=tuple(json.loads(str(memory["confirmed_facts_json"]))) if memory else (),
            excluded_facts=tuple(json.loads(str(memory["excluded_facts_json"]))) if memory else (),
            risks=tuple(json.loads(str(memory["risks_json"]))) if memory else (),
            memory_revision=int(memory["revision"]) if memory else 0,
            instructions=tuple(instructions),
            recent_messages=tuple(normalized_messages),
            user_text=user_text,
            manifest_version=manifest_version,
            manifest_etag=manifest_etag,
            knowledge_references=knowledge_references,
            knowledge_omissions=knowledge_omissions,
            authoritative_project_id=authoritative_project_id,
        )

    def start_execution_run(self, trace_id: str, context: ExecutionContext, model: str) -> None:
        trace_id = _identifier(trace_id, "trace_id_invalid")
        model = _required_text(model, 120, "model_invalid")
        versions = [
            {"instructionId": item.instruction_id, "version": item.version}
            for item in context.instructions
        ]
        with self.lock:
            self.connection.execute(
                """
                INSERT INTO execution_context_runs(
                    trace_id, organization_id, user_id, device_id, project_id,
                    authoritative_project_id, task_id, context_sha256, skill_version_id,
                    skill_snapshot_sha256, manifest_version, manifest_etag,
                    knowledge_references_json, knowledge_omissions_json, memory_revision,
                    instruction_versions_json, model, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'started', ?)
                """,
                (
                    trace_id,
                    context.organization_id,
                    context.user_id,
                    context.device_id,
                    context.project_id,
                    context.authoritative_project_id or None,
                    context.task_id,
                    context.digest,
                    context.skill.version_id if context.skill else None,
                    context.skill.digest if context.skill else None,
                    context.manifest_version or None,
                    context.manifest_etag or None,
                    _canonical_json(
                        [item.to_payload() for item in context.knowledge_references]
                    ),
                    _canonical_json(list(context.knowledge_omissions)),
                    context.memory_revision,
                    _canonical_json(versions),
                    model,
                    self.clock(),
                ),
            )
            self.connection.commit()

    def finish_execution_run(
        self, trace_id: str, status: str, latency_ms: int, error_code: str = ""
    ) -> None:
        trace_id = _identifier(trace_id, "trace_id_invalid")
        if status not in {"success", "failed"}:
            raise ValueError("execution_status_invalid")
        if not isinstance(latency_ms, int) or isinstance(latency_ms, bool) or latency_ms < 0:
            raise ValueError("execution_latency_invalid")
        if error_code:
            error_code = _identifier(error_code, "execution_error_invalid")
        with self.lock:
            cursor = self.connection.execute(
                """
                UPDATE execution_context_runs
                SET status = ?, error_code = ?, latency_ms = ?, completed_at = ?
                WHERE trace_id = ? AND status = 'started'
                """,
                (status, error_code or None, latency_ms, self.clock(), trace_id),
            )
            if cursor.rowcount != 1:
                raise LookupError("execution_run_not_found")
            self.connection.commit()

    def _project(self, device_id: str, local_project_id: str) -> Optional[sqlite3.Row]:
        return self.connection.execute(
            "SELECT * FROM ops_projects WHERE device_id = ? AND local_project_id = ?",
            (device_id, local_project_id),
        ).fetchone()

    def _skill_snapshot(self, row: sqlite3.Row) -> SkillSnapshot:
        return SkillSnapshot(
            version_id=str(row["version_id"]),
            skill_id=str(row["skill_id"]),
            version=str(row["version"]),
            name=str(row["name"]),
            rules=json.loads(str(row["rules_json"])),
            digest=str(row["content_sha256"]),
        )

    def _snapshot_from_task_row(self, row: sqlite3.Row) -> SkillSnapshot:
        payload = json.loads(str(row["snapshot_json"]))
        return SkillSnapshot(
            version_id=str(payload["versionId"]),
            skill_id=str(payload["skillId"]),
            version=str(payload["version"]),
            name=str(payload["name"]),
            rules=payload["rules"],
            digest=str(row["snapshot_sha256"]),
        )

    def _recent_messages(self, value: object) -> list[dict[str, str]]:
        if not isinstance(value, list) or len(value) > 12:
            raise ValueError("recent_messages_invalid")
        result: list[dict[str, str]] = []
        total = 0
        for item in value:
            if not isinstance(item, dict) or set(item) != {"role", "content"}:
                raise ValueError("recent_messages_invalid")
            role = item["role"]
            if role not in {"user", "assistant"}:
                raise ValueError("recent_messages_invalid")
            content = _required_display_text(
                item["content"], 4000, "recent_messages_invalid"
            )
            total += len(content)
            if total > 16_000:
                raise ValueError("recent_messages_invalid")
            result.append({"role": str(role), "content": content})
        return result

    def _attributes(self, value: object) -> dict[str, str]:
        if not isinstance(value, dict) or any(key not in _ATTRIBUTE_FIELDS for key in value):
            raise ValueError("context_attributes_invalid")
        result: dict[str, str] = {}
        for key, item in value.items():
            result[key] = _required_text(item, 200, "context_attributes_invalid")
        return result
