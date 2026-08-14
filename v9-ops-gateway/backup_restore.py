"""Safe, self-describing SQLite backup and restore primitives for the V9 gateway."""

from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import tempfile
from typing import Any
from pathlib import PurePosixPath


DATABASE_NAMES = ("gateway.db", "voiceprint.db")
SCHEMA_VERSION = 2


class BackupValidationError(ValueError):
    """Raised when a backup cannot be proven safe to verify or restore."""


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def _quick_check(path: Path) -> None:
    try:
        connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        try:
            result = connection.execute("PRAGMA quick_check").fetchone()
        finally:
            connection.close()
    except sqlite3.DatabaseError as error:
        raise BackupValidationError("backup_sqlite_invalid") from error
    if not result or result[0] != "ok":
        raise BackupValidationError("backup_sqlite_invalid")


def _copy_sqlite(source: Path, destination: Path) -> None:
    source_connection = sqlite3.connect(f"file:{source}?mode=ro", uri=True)
    destination_connection = sqlite3.connect(destination)
    try:
        source_connection.backup(destination_connection)
        destination_connection.commit()
    except sqlite3.DatabaseError as error:
        raise BackupValidationError("backup_sqlite_copy_failed") from error
    finally:
        destination_connection.close()
        source_connection.close()


def _safe_relative_path(value: object) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        raise BackupValidationError("backup_evidence_path_invalid")
    candidate = PurePosixPath(value)
    if candidate.is_absolute() or any(part in ("", ".", "..") for part in candidate.parts):
        raise BackupValidationError("backup_evidence_path_invalid")
    return candidate.as_posix()


def _evidence_files(evidence_dir: Path) -> list[Path]:
    if evidence_dir.is_symlink() or not evidence_dir.is_dir():
        raise BackupValidationError("backup_evidence_directory_invalid")
    files: list[Path] = []
    for candidate in evidence_dir.rglob("*"):
        if candidate.is_symlink():
            raise BackupValidationError("backup_evidence_symlink_forbidden")
        if candidate.is_file():
            files.append(candidate)
        elif not candidate.is_dir():
            raise BackupValidationError("backup_evidence_entry_invalid")
    return sorted(files, key=lambda item: item.relative_to(evidence_dir).as_posix())


def _evidence_manifest(source_dir: Path, destination_dir: Path) -> dict[str, Any]:
    source = source_dir / "evidence"
    if not source.exists():
        return {"existed": False, "files": []}
    if source.is_symlink() or not source.is_dir():
        raise BackupValidationError("backup_evidence_directory_invalid")
    files: list[dict[str, Any]] = []
    for original in _evidence_files(source):
        relative = original.relative_to(source).as_posix()
        target = destination_dir / "evidence" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(original, target)
        target.chmod(0o600)
        files.append(
            {
                "path": relative,
                "sha256": _sha256(target),
                "bytes": target.stat().st_size,
            }
        )
    return {"existed": True, "files": files}


def _remove_evidence_target(target: Path) -> None:
    if not target.exists():
        return
    if target.is_symlink() or not target.is_dir():
        raise BackupValidationError("restore_evidence_target_invalid")
    shutil.rmtree(target)


def _read_manifest(backup_dir: Path) -> dict[str, Any]:
    manifest_path = backup_dir / "manifest.json"
    if not manifest_path.is_file():
        raise BackupValidationError("backup_manifest_missing")
    try:
        manifest = json.loads(manifest_path.read_text("utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BackupValidationError("backup_manifest_invalid") from error
    if not isinstance(manifest, dict) or manifest.get("schemaVersion") != SCHEMA_VERSION:
        raise BackupValidationError("backup_manifest_schema_invalid")
    databases = manifest.get("databases")
    if not isinstance(databases, dict) or set(databases) != set(DATABASE_NAMES):
        raise BackupValidationError("backup_database_set_invalid")
    evidence = manifest.get("evidence")
    if not isinstance(evidence, dict):
        raise BackupValidationError("backup_evidence_manifest_missing")
    return manifest


def verify_backup(backup_dir: str | Path) -> dict[str, Any]:
    directory = Path(backup_dir)
    manifest = _read_manifest(directory)
    verified: dict[str, dict[str, Any]] = {}
    for name in DATABASE_NAMES:
        entry = manifest["databases"][name]
        if not isinstance(entry, dict) or not isinstance(entry.get("existed"), bool):
            raise BackupValidationError("backup_database_entry_invalid")
        source = directory / name
        if not entry["existed"]:
            if source.exists():
                raise BackupValidationError("backup_unexpected_database_file")
            verified[name] = {"existed": False}
            continue
        if not source.is_file():
            raise BackupValidationError("backup_database_file_missing")
        expected_hash = str(entry.get("sha256") or "").upper()
        expected_bytes = entry.get("bytes")
        if len(expected_hash) != 64 or not isinstance(expected_bytes, int):
            raise BackupValidationError("backup_database_metadata_invalid")
        actual_hash = _sha256(source)
        if actual_hash != expected_hash:
            raise BackupValidationError("backup_sha256_mismatch")
        if source.stat().st_size != expected_bytes:
            raise BackupValidationError("backup_bytes_mismatch")
        _quick_check(source)
        verified[name] = {
            "existed": True,
            "sha256": actual_hash,
            "bytes": expected_bytes,
        }
    evidence_entry = manifest["evidence"]
    if not isinstance(evidence_entry.get("existed"), bool) or not isinstance(
        evidence_entry.get("files"), list
    ):
        raise BackupValidationError("backup_evidence_manifest_invalid")
    evidence_dir = directory / "evidence"
    if not evidence_entry["existed"]:
        if evidence_dir.exists():
            raise BackupValidationError("backup_unexpected_evidence_directory")
        verified_evidence = {"existed": False, "files": []}
    else:
        if not evidence_dir.is_dir() or evidence_dir.is_symlink():
            raise BackupValidationError("backup_evidence_directory_missing")
        expected: dict[str, dict[str, Any]] = {}
        for item in evidence_entry["files"]:
            if not isinstance(item, dict):
                raise BackupValidationError("backup_evidence_entry_invalid")
            relative = _safe_relative_path(item.get("path"))
            if relative in expected:
                raise BackupValidationError("backup_evidence_set_invalid")
            expected_hash = str(item.get("sha256") or "").upper()
            expected_bytes = item.get("bytes")
            if len(expected_hash) != 64 or not isinstance(expected_bytes, int):
                raise BackupValidationError("backup_evidence_metadata_invalid")
            expected[relative] = {"sha256": expected_hash, "bytes": expected_bytes}
        actual_files = _evidence_files(evidence_dir)
        actual_paths = {file.relative_to(evidence_dir).as_posix() for file in actual_files}
        if actual_paths != set(expected):
            raise BackupValidationError("backup_evidence_set_invalid")
        verified_files = []
        for file in actual_files:
            relative = file.relative_to(evidence_dir).as_posix()
            actual_hash = _sha256(file)
            metadata = expected[relative]
            if actual_hash != metadata["sha256"]:
                raise BackupValidationError("backup_sha256_mismatch")
            if file.stat().st_size != metadata["bytes"]:
                raise BackupValidationError("backup_bytes_mismatch")
            verified_files.append({"path": relative, "sha256": actual_hash, "bytes": metadata["bytes"]})
        verified_evidence = {"existed": True, "files": verified_files}
    return {
        "status": "ok",
        "schemaVersion": SCHEMA_VERSION,
        "databases": verified,
        "evidence": verified_evidence,
    }


def create_backup(data_dir: str | Path, backup_dir: str | Path) -> dict[str, Any]:
    source_dir = Path(data_dir)
    destination_dir = Path(backup_dir)
    if destination_dir.exists():
        raise BackupValidationError("backup_destination_exists")
    destination_dir.mkdir(parents=True, mode=0o700)
    try:
        databases: dict[str, dict[str, Any]] = {}
        for name in DATABASE_NAMES:
            source = source_dir / name
            if not source.is_file():
                databases[name] = {"existed": False}
                continue
            target = destination_dir / name
            _copy_sqlite(source, target)
            _quick_check(target)
            databases[name] = {
                "existed": True,
                "sha256": _sha256(target),
                "bytes": target.stat().st_size,
            }
        evidence = _evidence_manifest(source_dir, destination_dir)
        manifest = {
            "schemaVersion": SCHEMA_VERSION,
            "createdAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "databases": databases,
            "evidence": evidence,
        }
        temporary = destination_dir / ".manifest.json.tmp"
        temporary.write_text(json.dumps(manifest, ensure_ascii=False, sort_keys=True) + "\n", "utf-8")
        temporary.replace(destination_dir / "manifest.json")
        return manifest
    except Exception:
        shutil.rmtree(destination_dir, ignore_errors=True)
        raise


def restore_backup(data_dir: str | Path, backup_dir: str | Path) -> dict[str, Any]:
    source_dir = Path(data_dir)
    backup = Path(backup_dir)
    verification = verify_backup(backup)
    source_dir.mkdir(parents=True, mode=0o700, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix=".v9-restore-", dir=source_dir))
    try:
        for name in DATABASE_NAMES:
            entry = verification["databases"][name]
            if not entry["existed"]:
                continue
            staged = staging / name
            shutil.copy2(backup / name, staged)
            _quick_check(staged)
        for name in DATABASE_NAMES:
            target = source_dir / name
            staged = staging / name
            if staged.is_file():
                staged.replace(target)
            else:
                target.unlink(missing_ok=True)
        evidence_target = source_dir / "evidence"
        evidence_entry = verification["evidence"]
        staged_evidence = staging / "evidence"
        if evidence_entry["existed"]:
            staged_evidence.mkdir(parents=True, exist_ok=True)
            for item in evidence_entry["files"]:
                relative = _safe_relative_path(item["path"])
                staged_file = staged_evidence / relative
                staged_file.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(backup / "evidence" / relative, staged_file)
                staged_file.chmod(0o600)
            _remove_evidence_target(evidence_target)
            staged_evidence.replace(evidence_target)
        else:
            _remove_evidence_target(evidence_target)
        return verification
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def _main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="V9 gateway SQLite backup and restore")
    subparsers = parser.add_subparsers(dest="command", required=True)
    backup_parser = subparsers.add_parser("backup")
    backup_parser.add_argument("data_dir")
    backup_parser.add_argument("backup_dir")
    verify_parser = subparsers.add_parser("verify")
    verify_parser.add_argument("backup_dir")
    restore_parser = subparsers.add_parser("restore")
    restore_parser.add_argument("data_dir")
    restore_parser.add_argument("backup_dir")
    args = parser.parse_args()
    if args.command == "backup":
        result = create_backup(args.data_dir, args.backup_dir)
    elif args.command == "verify":
        result = verify_backup(args.backup_dir)
    else:
        result = restore_backup(args.data_dir, args.backup_dir)
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
