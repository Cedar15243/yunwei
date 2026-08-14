from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
import secrets
import sqlite3
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Callable, Mapping, Optional

from voiceprint_proxy import (
    MAX_VOICEPRINT_AUDIO_BYTES,
    VoiceprintProviderUnavailable,
    validate_voiceprint_wav,
)


VOICEPRINT_CONSENT_VERSION = "2026-08-02.v1"
VOICEPRINT_SAMPLE_COUNT = 3
VOICEPRINT_MAX_FAILURES = 3
VOICEPRINT_REPLAY_RETENTION_SECONDS = 24 * 60 * 60
IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9_.:-]{1,160}$")


@dataclass(frozen=True)
class VoiceprintLifecycleConfig:
    organization_id: str
    user_id: str
    device_id: str
    admin_token_hash: str
    replay_hmac_key: str
    admin_previous_token_hash: str = ""
    consent_version: str = VOICEPRINT_CONSENT_VERSION
    threshold: float = 0.70

    def __post_init__(self) -> None:
        for value, error in (
            (self.organization_id, "voiceprint_organization_id_missing"),
            (self.user_id, "voiceprint_user_id_missing"),
            (self.device_id, "voiceprint_device_id_missing"),
            (self.replay_hmac_key, "voiceprint_replay_key_missing"),
        ):
            if not value or not IDENTIFIER_PATTERN.fullmatch(value):
                raise ValueError(error)
        if not re.fullmatch(r"[0-9a-f]{64}", self.admin_token_hash):
            raise ValueError("voiceprint_admin_token_hash_invalid")
        if self.admin_previous_token_hash and not re.fullmatch(
            r"[0-9a-f]{64}", self.admin_previous_token_hash
        ):
            raise ValueError("voiceprint_admin_previous_token_hash_invalid")
        if self.consent_version != VOICEPRINT_CONSENT_VERSION:
            raise ValueError("voiceprint_consent_version_unsupported")
        if self.threshold < 0.6 or self.threshold > 0.95:
            raise ValueError("voiceprint_threshold_out_of_range")


@dataclass(frozen=True)
class VoiceprintResponse:
    status: int
    body: dict[str, object]


class VoiceprintStore:
    def __init__(self, database_path: str, clock: Callable[[], float] = time.time):
        self.clock = clock
        self.connection = sqlite3.connect(database_path, check_same_thread=False)
        self.connection.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        with self.lock:
            self.connection.executescript(
                """
                PRAGMA journal_mode=WAL;
                PRAGMA foreign_keys=ON;
                CREATE TABLE IF NOT EXISTS voiceprint_profiles (
                    profile_id TEXT PRIMARY KEY,
                    organization_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    provider TEXT NOT NULL CHECK (provider = 'iflytek'),
                    provider_group_id TEXT,
                    provider_feature_id TEXT,
                    consent_version TEXT,
                    consented_at REAL,
                    status TEXT NOT NULL,
                    enrollment_samples INTEGER NOT NULL DEFAULT 0,
                    group_created INTEGER NOT NULL DEFAULT 0,
                    feature_created INTEGER NOT NULL DEFAULT 0,
                    verification_failures INTEGER NOT NULL DEFAULT 0,
                    locked_at REAL,
                    last_verified_at REAL,
                    created_at REAL NOT NULL,
                    updated_at REAL NOT NULL,
                    UNIQUE(organization_id, user_id, device_id)
                );
                CREATE TABLE IF NOT EXISTS voiceprint_operations (
                    operation_id TEXT PRIMARY KEY,
                    profile_id TEXT NOT NULL REFERENCES voiceprint_profiles(profile_id),
                    idempotency_key TEXT NOT NULL,
                    operation TEXT NOT NULL,
                    status TEXT NOT NULL,
                    response_json TEXT,
                    created_at REAL NOT NULL,
                    completed_at REAL,
                    UNIQUE(profile_id, idempotency_key)
                );
                CREATE TABLE IF NOT EXISTS voiceprint_replay_digests (
                    digest TEXT PRIMARY KEY,
                    created_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS voiceprint_audit_events (
                    event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    profile_id TEXT NOT NULL REFERENCES voiceprint_profiles(profile_id),
                    organization_id TEXT NOT NULL,
                    user_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    outcome TEXT NOT NULL,
                    detail_json TEXT NOT NULL,
                    previous_hash TEXT NOT NULL,
                    event_hash TEXT NOT NULL,
                    created_at REAL NOT NULL
                );
                CREATE TRIGGER IF NOT EXISTS voiceprint_audit_no_update
                BEFORE UPDATE ON voiceprint_audit_events
                BEGIN SELECT RAISE(ABORT, 'voiceprint_audit_immutable'); END;
                CREATE TRIGGER IF NOT EXISTS voiceprint_audit_no_delete
                BEFORE DELETE ON voiceprint_audit_events
                BEGIN SELECT RAISE(ABORT, 'voiceprint_audit_immutable'); END;
                """
            )
            self.connection.commit()

    def close(self) -> None:
        with self.lock:
            self.connection.close()

    def ensure_profile(
        self,
        organization_id: str,
        user_id: str,
        device_id: str,
        group_id: str,
        feature_id: str,
    ) -> sqlite3.Row:
        profile_id = str(
            uuid.uuid5(uuid.NAMESPACE_URL, f"dingdang-v9-voiceprint:{organization_id}:{user_id}:{device_id}")
        )
        now = self.clock()
        with self.lock:
            self.connection.execute(
                """
                INSERT OR IGNORE INTO voiceprint_profiles(
                    profile_id, organization_id, user_id, device_id, provider,
                    provider_group_id, provider_feature_id, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'iflytek', ?, ?, 'new', ?, ?)
                """,
                (profile_id, organization_id, user_id, device_id, group_id, feature_id, now, now),
            )
            self.connection.commit()
            return self.connection.execute(
                "SELECT * FROM voiceprint_profiles WHERE profile_id = ?", (profile_id,)
            ).fetchone()

    def current_profile(self, device_id: str) -> Optional[sqlite3.Row]:
        with self.lock:
            return self.connection.execute(
                """
                SELECT * FROM voiceprint_profiles
                WHERE device_id = ? ORDER BY updated_at DESC LIMIT 1
                """,
                (device_id,),
            ).fetchone()

    def profile_by_id(self, profile_id: str) -> Optional[sqlite3.Row]:
        with self.lock:
            return self.connection.execute(
                "SELECT * FROM voiceprint_profiles WHERE profile_id = ?", (profile_id,)
            ).fetchone()

    def list_profiles(self, organization_id: str) -> list[sqlite3.Row]:
        with self.lock:
            return self.connection.execute(
                """
                SELECT * FROM voiceprint_profiles
                WHERE organization_id = ?
                ORDER BY updated_at DESC, profile_id ASC
                LIMIT 200
                """,
                (organization_id,),
            ).fetchall()

    def begin_operation(
        self, profile_id: str, operation: str, idempotency_key: str
    ) -> tuple[str, Optional[dict[str, object]]]:
        now = self.clock()
        with self.lock:
            row = self.connection.execute(
                """
                SELECT status, response_json FROM voiceprint_operations
                WHERE profile_id = ? AND idempotency_key = ?
                """,
                (profile_id, idempotency_key),
            ).fetchone()
            if row is not None:
                if row["status"] == "success":
                    return "duplicate", json.loads(row["response_json"])
                return "recovery_required", None
            self.connection.execute(
                """
                INSERT INTO voiceprint_operations(
                    operation_id, profile_id, idempotency_key, operation, status, created_at
                ) VALUES (?, ?, ?, ?, 'pending', ?)
                """,
                (str(uuid.uuid4()), profile_id, idempotency_key, operation, now),
            )
            self.connection.commit()
        return "new", None

    def operation_result(
        self, profile_id: str, idempotency_key: str
    ) -> tuple[str, Optional[dict[str, object]]]:
        with self.lock:
            row = self.connection.execute(
                """
                SELECT status, response_json FROM voiceprint_operations
                WHERE profile_id = ? AND idempotency_key = ?
                """,
                (profile_id, idempotency_key),
            ).fetchone()
        if row is None:
            return "new", None
        if row["status"] == "success":
            return "duplicate", json.loads(row["response_json"])
        return "recovery_required", None

    def finish_operation(
        self,
        profile_id: str,
        idempotency_key: str,
        status: str,
        response: Optional[dict[str, object]],
    ) -> None:
        with self.lock:
            self.connection.execute(
                """
                UPDATE voiceprint_operations
                SET status = ?, response_json = ?, completed_at = ?
                WHERE profile_id = ? AND idempotency_key = ?
                """,
                (
                    status,
                    json.dumps(response, ensure_ascii=False, sort_keys=True)
                    if response is not None
                    else None,
                    self.clock(),
                    profile_id,
                    idempotency_key,
                ),
            )
            self.connection.commit()

    def accept_consent(self, profile_id: str, consent_version: str) -> sqlite3.Row:
        now = self.clock()
        with self.lock:
            self.connection.execute(
                """
                UPDATE voiceprint_profiles SET
                    consent_version = ?, consented_at = ?, status = 'enrolling',
                    enrollment_samples = 0, group_created = 0, feature_created = 0,
                    verification_failures = 0, locked_at = NULL, last_verified_at = NULL,
                    updated_at = ?
                WHERE profile_id = ?
                """,
                (consent_version, now, now, profile_id),
            )
            self.connection.commit()
            return self.profile_by_id(profile_id)

    def mark_group_created(self, profile_id: str) -> None:
        self._update_profile(profile_id, "group_created = 1")

    def mark_first_feature(self, profile_id: str) -> sqlite3.Row:
        return self._update_profile(
            profile_id,
            "feature_created = 1, enrollment_samples = 1",
        )

    def mark_merged_sample(self, profile_id: str, final_sample: bool) -> sqlite3.Row:
        updates = "enrollment_samples = enrollment_samples + 1"
        if final_sample:
            updates += ", status = 'pending_verification'"
        return self._update_profile(profile_id, updates)

    def mark_verification(
        self, profile_id: str, verified: bool
    ) -> tuple[sqlite3.Row, str]:
        with self.lock:
            profile = self.connection.execute(
                "SELECT * FROM voiceprint_profiles WHERE profile_id = ?", (profile_id,)
            ).fetchone()
            if profile is None:
                raise LookupError("voiceprint_profile_not_found")
            now = self.clock()
            if verified:
                self.connection.execute(
                    """
                    UPDATE voiceprint_profiles SET status = 'active',
                        verification_failures = 0, locked_at = NULL,
                        last_verified_at = ?, updated_at = ? WHERE profile_id = ?
                    """,
                    (now, now, profile_id),
                )
                resulting_status = "active"
            else:
                failures = int(profile["verification_failures"]) + 1
                locked = failures >= VOICEPRINT_MAX_FAILURES
                self.connection.execute(
                    """
                    UPDATE voiceprint_profiles SET
                        status = CASE WHEN ? THEN 'locked' ELSE status END,
                        verification_failures = ?, locked_at = CASE WHEN ? THEN ? ELSE locked_at END,
                        updated_at = ? WHERE profile_id = ?
                    """,
                    (locked, failures, locked, now, now, profile_id),
                )
                resulting_status = "locked" if locked else str(profile["status"])
            self.connection.commit()
            return self.profile_by_id(profile_id), resulting_status

    def reset_for_reenrollment(self, profile_id: str, group_id: str, feature_id: str) -> sqlite3.Row:
        return self._update_profile(
            profile_id,
            "provider_group_id = ?, provider_feature_id = ?, status = 'enrolling', "
            "consent_version = ?, consented_at = ?, enrollment_samples = 0, "
            "group_created = 0, feature_created = 0, verification_failures = 0, "
            "locked_at = NULL, last_verified_at = NULL",
            (group_id, feature_id, VOICEPRINT_CONSENT_VERSION, self.clock()),
        )

    def mark_deleted(self, profile_id: str, status: str) -> sqlite3.Row:
        return self._update_profile(
            profile_id,
            "provider_group_id = NULL, provider_feature_id = NULL, status = ?, "
            "enrollment_samples = 0, group_created = 0, feature_created = 0, "
            "verification_failures = 0, locked_at = NULL",
            (status,),
        )

    def reserve_replay_digest(self, digest: str) -> bool:
        cutoff = self.clock() - VOICEPRINT_REPLAY_RETENTION_SECONDS
        with self.lock:
            self.connection.execute(
                "DELETE FROM voiceprint_replay_digests WHERE created_at < ?", (cutoff,)
            )
            try:
                self.connection.execute(
                    "INSERT INTO voiceprint_replay_digests(digest, created_at) VALUES (?, ?)",
                    (digest, self.clock()),
                )
            except sqlite3.IntegrityError:
                self.connection.rollback()
                return False
            self.connection.commit()
        return True

    def replay_digests(self) -> set[str]:
        with self.lock:
            rows = self.connection.execute(
                "SELECT digest FROM voiceprint_replay_digests"
            ).fetchall()
        return {str(row["digest"]) for row in rows}

    def append_audit(
        self,
        profile_id: str,
        event_type: str,
        outcome: str,
        details: Optional[Mapping[str, object]] = None,
    ) -> None:
        with self.lock:
            profile = self.profile_by_id(profile_id)
            if profile is None:
                raise LookupError("voiceprint_profile_not_found")
            previous = self.connection.execute(
                "SELECT event_hash FROM voiceprint_audit_events ORDER BY event_id DESC LIMIT 1"
            ).fetchone()
            previous_hash = str(previous["event_hash"]) if previous else ""
            detail_json = json.dumps(details or {}, ensure_ascii=False, sort_keys=True)
            created_at = self.clock()
            canonical = "|".join(
                (
                    previous_hash,
                    profile_id,
                    event_type,
                    outcome,
                    detail_json,
                    str(created_at),
                )
            ).encode("utf-8")
            event_hash = hashlib.sha256(canonical).hexdigest()
            self.connection.execute(
                """
                INSERT INTO voiceprint_audit_events(
                    profile_id, organization_id, user_id, device_id, event_type,
                    outcome, detail_json, previous_hash, event_hash, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    profile_id,
                    profile["organization_id"],
                    profile["user_id"],
                    profile["device_id"],
                    event_type,
                    outcome,
                    detail_json,
                    previous_hash,
                    event_hash,
                    created_at,
                ),
            )
            self.connection.commit()

    def audit_event_types(self) -> list[str]:
        with self.lock:
            return [
                str(row["event_type"])
                for row in self.connection.execute(
                    "SELECT event_type FROM voiceprint_audit_events ORDER BY event_id"
                ).fetchall()
            ]

    def verify_audit_chain(self) -> bool:
        with self.lock:
            rows = self.connection.execute(
                "SELECT * FROM voiceprint_audit_events ORDER BY event_id"
            ).fetchall()
        previous = ""
        for row in rows:
            if str(row["previous_hash"]) != previous:
                return False
            canonical = "|".join(
                (
                    previous,
                    str(row["profile_id"]),
                    str(row["event_type"]),
                    str(row["outcome"]),
                    str(row["detail_json"]),
                    str(row["created_at"]),
                )
            ).encode("utf-8")
            expected = hashlib.sha256(canonical).hexdigest()
            if not hmac.compare_digest(expected, str(row["event_hash"])):
                return False
            previous = expected
        return True

    def _update_profile(
        self, profile_id: str, expressions: str, values: tuple[object, ...] = ()
    ) -> sqlite3.Row:
        with self.lock:
            self.connection.execute(
                f"UPDATE voiceprint_profiles SET {expressions}, updated_at = ? WHERE profile_id = ?",
                (*values, self.clock(), profile_id),
            )
            self.connection.commit()
            profile = self.profile_by_id(profile_id)
            if profile is None:
                raise LookupError("voiceprint_profile_not_found")
            return profile


class VoiceprintLifecycleService:
    def __init__(
        self,
        config: VoiceprintLifecycleConfig,
        store: VoiceprintStore,
        provider: object,
        clock: Callable[[], float] = time.time,
    ):
        self.config = config
        self.store = store
        self.provider = provider
        self.clock = clock
        self.lock = threading.RLock()

    def profile(self, device_id: str) -> VoiceprintResponse:
        if not self._device_allowed(device_id):
            return self._error(403, "voiceprint_device_forbidden")
        current = self.store.current_profile(device_id)
        if current is None:
            return self._error(404, "voiceprint_not_enrolled")
        return self._ok_profile(current, 200)

    def consent(self, device_id: str, payload: Mapping[str, object]) -> VoiceprintResponse:
        if not self._device_allowed(device_id):
            return self._error(403, "voiceprint_device_forbidden")
        if (
            payload.get("consentAccepted") is not True
            or payload.get("consentVersion") != self.config.consent_version
            or not self._valid_idempotency(payload.get("idempotencyKey"))
        ):
            return self._error(400, "voiceprint_consent_required")
        with self.lock:
            existing = self.store.current_profile(device_id)
            if existing is None:
                profile = self.store.ensure_profile(
                    self.config.organization_id,
                    self.config.user_id,
                    device_id,
                    self._new_ref("g"),
                    self._new_ref("f"),
                )
            else:
                profile = existing
            operation = self.store.begin_operation(
                profile["profile_id"], "consent", str(payload["idempotencyKey"])
            )
            if operation[0] == "duplicate":
                body = dict(operation[1] or {})
                body["duplicate"] = True
                return VoiceprintResponse(200, body)
            if operation[0] != "new":
                return self._error(409, "voiceprint_operation_recovery_required")
            if str(profile["status"]) in {
                "active",
                "locked",
                "enrolling",
                "pending_verification",
            }:
                self.store.finish_operation(
                    profile["profile_id"],
                    str(payload["idempotencyKey"]),
                    "failed",
                    None,
                )
                return self._error(409, "voiceprint_reenroll_required")
            updated = self.store.accept_consent(
                profile["profile_id"], self.config.consent_version
            )
            body = self._profile_body(updated)
            self.store.finish_operation(profile["profile_id"], str(payload["idempotencyKey"]), "success", body)
            self.store.append_audit(profile["profile_id"], "voiceprint_consent_accepted", "success")
            return VoiceprintResponse(201, body)

    def enroll_sample(self, device_id: str, payload: Mapping[str, object]) -> VoiceprintResponse:
        if not self._device_allowed(device_id):
            return self._error(403, "voiceprint_device_forbidden")
        if not isinstance(payload.get("sampleIndex"), int) or isinstance(payload.get("sampleIndex"), bool):
            return self._error(400, "voiceprint_sample_invalid")
        sample_index = int(payload["sampleIndex"])
        if sample_index < 1 or sample_index > VOICEPRINT_SAMPLE_COUNT:
            return self._error(400, "voiceprint_sample_invalid")
        if not self._valid_idempotency(payload.get("idempotencyKey")):
            return self._error(400, "voiceprint_sample_invalid")
        with self.lock:
            profile = self.store.current_profile(device_id)
            if profile is None or str(profile["status"]) == "new":
                return self._error(409, "voiceprint_consent_required")
            key = str(payload["idempotencyKey"])
            previous = self.store.operation_result(profile["profile_id"], key)
            if previous[0] == "duplicate":
                body = dict(previous[1] or {})
                body["duplicate"] = True
                return VoiceprintResponse(200, body)
            if previous[0] != "new":
                return self._error(409, "voiceprint_operation_recovery_required")
            if str(profile["status"]) != "enrolling":
                return self._error(409, "voiceprint_enrollment_not_open")
            expected = int(profile["enrollment_samples"]) + 1
            if sample_index != expected:
                return self._error(409, "voiceprint_sample_order_invalid")
            audio = self._decode_audio(payload.get("audioBase64"))
            if isinstance(audio, VoiceprintResponse):
                return audio
            if not self._reserve_audio(audio):
                return self._error(409, "voiceprint_replay_detected")
            operation = self.store.begin_operation(profile["profile_id"], f"sample_{sample_index}", key)
            if operation[0] == "duplicate":
                body = dict(operation[1] or {})
                body["duplicate"] = True
                return VoiceprintResponse(200, body)
            if operation[0] != "new":
                return self._error(409, "voiceprint_operation_recovery_required")
            try:
                group_id = str(profile["provider_group_id"])
                feature_id = str(profile["provider_feature_id"])
                if sample_index == 1:
                    if not profile["group_created"]:
                        self.provider.create_group(group_id)
                        self.store.mark_group_created(profile["profile_id"])
                    self.provider.create_feature(group_id, feature_id, audio)
                    updated = self.store.mark_first_feature(profile["profile_id"])
                else:
                    self.provider.merge_feature(group_id, feature_id, audio)
                    updated = self.store.mark_merged_sample(
                        profile["profile_id"], sample_index == VOICEPRINT_SAMPLE_COUNT
                    )
            except (VoiceprintProviderUnavailable, RuntimeError):
                self.store.finish_operation(profile["profile_id"], key, "failed", None)
                return self._error(503, "voiceprint_unavailable")
            body = self._profile_body(updated)
            self.store.finish_operation(profile["profile_id"], key, "success", body)
            self.store.append_audit(
                profile["profile_id"],
                "voiceprint_sample_processed",
                "success",
                {"sampleIndex": sample_index, "durationSeconds": 3},
            )
            return VoiceprintResponse(201, body)

    def verify(self, device_id: str, payload: Mapping[str, object]) -> VoiceprintResponse:
        if not self._device_allowed(device_id):
            return self._error(403, "voiceprint_device_forbidden")
        if not self._valid_idempotency(payload.get("idempotencyKey")):
            return self._error(400, "voiceprint_verify_invalid")
        with self.lock:
            profile = self.store.current_profile(device_id)
            if profile is None or str(profile["status"]) == "new":
                return self._error(409, "voiceprint_consent_required")
            key = str(payload["idempotencyKey"])
            previous = self.store.operation_result(profile["profile_id"], key)
            if previous[0] == "duplicate":
                body = dict(previous[1] or {})
                body["duplicate"] = True
                return VoiceprintResponse(200, body)
            if previous[0] != "new":
                return self._error(409, "voiceprint_operation_recovery_required")
            if str(profile["status"]) == "locked":
                return self._error(423, "voiceprint_locked")
            if str(profile["status"]) not in {"pending_verification", "active"}:
                return self._error(409, "voiceprint_verification_not_ready")
            audio = self._decode_audio(payload.get("audioBase64"))
            if isinstance(audio, VoiceprintResponse):
                return audio
            if not self._reserve_audio(audio):
                return self._error(409, "voiceprint_replay_detected")
            operation = self.store.begin_operation(profile["profile_id"], "verify", key)
            if operation[0] == "duplicate":
                body = dict(operation[1] or {})
                body["duplicate"] = True
                return VoiceprintResponse(200, body)
            if operation[0] != "new":
                return self._error(409, "voiceprint_operation_recovery_required")
            try:
                score = float(
                    self.provider.verify_feature(
                        str(profile["provider_group_id"]),
                        str(profile["provider_feature_id"]),
                        audio,
                    )
                )
            except (VoiceprintProviderUnavailable, RuntimeError):
                self.store.finish_operation(profile["profile_id"], key, "failed", None)
                return self._error(503, "voiceprint_unavailable")
            verified = score >= self.config.threshold
            updated, status = self.store.mark_verification(profile["profile_id"], verified)
            body = self._profile_body(updated)
            body["verified"] = verified
            body["status"] = status
            self.store.finish_operation(profile["profile_id"], key, "success", body)
            self.store.append_audit(
                profile["profile_id"],
                "voiceprint_verified" if verified else "voiceprint_rejected",
                "success",
                {"verified": verified},
            )
            return VoiceprintResponse(200, body)

    def reenroll(self, device_id: str, payload: Mapping[str, object]) -> VoiceprintResponse:
        if not self._device_allowed(device_id):
            return self._error(403, "voiceprint_device_forbidden")
        if (
            payload.get("consentAccepted") is not True
            or payload.get("consentVersion") != self.config.consent_version
            or payload.get("confirmation") != "重新录入我的声纹"
            or not self._valid_idempotency(payload.get("idempotencyKey"))
        ):
            return self._error(400, "voiceprint_reenroll_confirmation_required")
        with self.lock:
            profile = self.store.current_profile(device_id)
            if profile is None:
                return self._error(404, "voiceprint_not_enrolled")
            key = str(payload["idempotencyKey"])
            operation = self.store.begin_operation(profile["profile_id"], "reenroll", key)
            if operation[0] == "duplicate":
                body = dict(operation[1] or {})
                body["duplicate"] = True
                return VoiceprintResponse(200, body)
            if operation[0] != "new":
                return self._error(409, "voiceprint_operation_recovery_required")
            try:
                if profile["provider_group_id"]:
                    self.provider.delete_group(str(profile["provider_group_id"]))
            except (VoiceprintProviderUnavailable, RuntimeError):
                self.store.finish_operation(profile["profile_id"], key, "failed", None)
                return self._error(503, "voiceprint_unavailable")
            updated = self.store.reset_for_reenrollment(
                profile["profile_id"], self._new_ref("g"), self._new_ref("f")
            )
            body = self._profile_body(updated)
            self.store.finish_operation(profile["profile_id"], key, "success", body)
            self.store.append_audit(profile["profile_id"], "voiceprint_reenrolled", "success")
            return VoiceprintResponse(200, body)

    def delete(self, device_id: str, payload: Mapping[str, object]) -> VoiceprintResponse:
        if not self._device_allowed(device_id):
            return self._error(403, "voiceprint_device_forbidden")
        if (
            payload.get("confirmation") != "删除我的声纹"
            or not self._valid_idempotency(payload.get("idempotencyKey"))
        ):
            return self._error(400, "voiceprint_delete_confirmation_required")
        with self.lock:
            profile = self.store.current_profile(device_id)
            if profile is None:
                return self._error(404, "voiceprint_not_enrolled")
            key = str(payload["idempotencyKey"])
            operation = self.store.begin_operation(profile["profile_id"], "delete", key)
            if operation[0] == "duplicate":
                body = dict(operation[1] or {})
                body["duplicate"] = True
                return VoiceprintResponse(200, body)
            if operation[0] != "new":
                return self._error(409, "voiceprint_operation_recovery_required")
            try:
                if profile["provider_group_id"]:
                    self.provider.delete_group(str(profile["provider_group_id"]))
            except (VoiceprintProviderUnavailable, RuntimeError):
                self.store.finish_operation(profile["profile_id"], key, "failed", None)
                return self._error(503, "voiceprint_unavailable")
            updated = self.store.mark_deleted(profile["profile_id"], "deleted")
            body = self._profile_body(updated)
            self.store.finish_operation(profile["profile_id"], key, "success", body)
            self.store.append_audit(profile["profile_id"], "voiceprint_deleted", "success")
            return VoiceprintResponse(200, body)

    def admin_revoke(
        self, authorization: str, profile_id: str, payload: Mapping[str, object]
    ) -> VoiceprintResponse:
        if not self._admin_allowed(authorization):
            return self._error(401, "unauthorized")
        if (
            payload.get("confirmation") != "REVOKE VOICEPRINT"
            or not self._valid_idempotency(payload.get("idempotencyKey"))
            or not isinstance(payload.get("reason"), str)
            or not str(payload["reason"]).strip()
        ):
            return self._error(400, "voiceprint_revoke_confirmation_required")
        with self.lock:
            profile = self.store.profile_by_id(profile_id)
            if profile is None:
                return self._error(404, "voiceprint_not_enrolled")
            key = str(payload["idempotencyKey"])
            operation = self.store.begin_operation(profile_id, "admin_revoke", key)
            if operation[0] == "duplicate":
                body = dict(operation[1] or {})
                body["duplicate"] = True
                return VoiceprintResponse(200, body)
            if operation[0] != "new":
                return self._error(409, "voiceprint_operation_recovery_required")
            try:
                if profile["provider_group_id"]:
                    self.provider.delete_group(str(profile["provider_group_id"]))
            except (VoiceprintProviderUnavailable, RuntimeError):
                self.store.finish_operation(profile_id, key, "failed", None)
                return self._error(503, "voiceprint_unavailable")
            updated = self.store.mark_deleted(profile_id, "revoked")
            body = self._profile_body(updated)
            self.store.finish_operation(profile_id, key, "success", body)
            self.store.append_audit(
                profile_id,
                "voiceprint_revoked",
                "success",
                {"reason": str(payload["reason"]).strip()[:240]},
            )
            return VoiceprintResponse(200, body)

    def admin_list(self, authorization: str) -> VoiceprintResponse:
        if not self._admin_allowed(authorization):
            return self._error(401, "unauthorized")
        items = [self._admin_profile_body(profile) for profile in self.store.list_profiles(self.config.organization_id)]
        return VoiceprintResponse(
            200,
            {
                "ok": True,
                "items": items,
                "auditChainValid": self.store.verify_audit_chain(),
            },
        )

    def _decode_audio(self, value: object) -> bytes | VoiceprintResponse:
        if not isinstance(value, str) or not value or len(value) > ((MAX_VOICEPRINT_AUDIO_BYTES + 2) // 3) * 4 + 4:
            return self._error(400, "voiceprint_audio_invalid")
        try:
            audio = base64.b64decode(value, validate=True)
            validate_voiceprint_wav(audio)
        except (ValueError, TypeError):
            return self._error(400, "voiceprint_audio_invalid")
        return audio

    def _reserve_audio(self, audio: bytes) -> bool:
        digest = hmac.new(
            self.config.replay_hmac_key.encode("utf-8"), audio, hashlib.sha256
        ).hexdigest()
        return self.store.reserve_replay_digest(digest)

    def _device_allowed(self, device_id: str) -> bool:
        return hmac.compare_digest(device_id, self.config.device_id)

    def _admin_allowed(self, authorization: str) -> bool:
        token = authorization[7:].strip() if authorization.startswith("Bearer ") else ""
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest() if token else ""
        current_allowed = hmac.compare_digest(token_hash, self.config.admin_token_hash)
        previous_allowed = hmac.compare_digest(
            token_hash,
            self.config.admin_previous_token_hash or "0" * 64,
        )
        return current_allowed or previous_allowed

    def _profile_body(self, profile: sqlite3.Row) -> dict[str, object]:
        return {
            "ok": True,
            "profileId": str(profile["profile_id"]),
            "status": str(profile["status"]),
            "sampleCount": int(profile["enrollment_samples"]),
            "requiredSamples": VOICEPRINT_SAMPLE_COUNT,
            "verificationFailures": int(profile["verification_failures"]),
            "consentVersion": profile["consent_version"],
            "consentedAt": _iso_timestamp(profile["consented_at"]) if profile["consented_at"] else None,
            "lastVerifiedAt": _iso_timestamp(profile["last_verified_at"]) if profile["last_verified_at"] else None,
        }

    def _admin_profile_body(self, profile: sqlite3.Row) -> dict[str, object]:
        return {
            **self._profile_body(profile),
            "organizationId": str(profile["organization_id"]),
            "userId": str(profile["user_id"]),
            "deviceId": str(profile["device_id"]),
            "provider": str(profile["provider"]),
            "lockedAt": _iso_timestamp(profile["locked_at"]) if profile["locked_at"] else None,
            "createdAt": _iso_timestamp(profile["created_at"]),
            "updatedAt": _iso_timestamp(profile["updated_at"]),
        }

    def _ok_profile(self, profile: sqlite3.Row, status: int) -> VoiceprintResponse:
        return VoiceprintResponse(status, self._profile_body(profile))

    @staticmethod
    def _error(status: int, error: str) -> VoiceprintResponse:
        return VoiceprintResponse(status, {"ok": False, "error": error})

    @staticmethod
    def _valid_idempotency(value: object) -> bool:
        return isinstance(value, str) and bool(IDENTIFIER_PATTERN.fullmatch(value))

    @staticmethod
    def _new_ref(prefix: str) -> str:
        return prefix + secrets.token_hex(15)


def _iso_timestamp(epoch_seconds: float) -> str:
    return datetime.fromtimestamp(epoch_seconds, timezone.utc).isoformat().replace("+00:00", "Z")
