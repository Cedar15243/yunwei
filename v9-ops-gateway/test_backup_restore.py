import json
from pathlib import Path
import sqlite3
import shutil
import tempfile
import unittest

from backup_restore import BackupValidationError, create_backup, restore_backup, verify_backup


class BackupRestoreTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.data_dir = self.root / "data"
        self.backup_dir = self.root / "backup"
        self.data_dir.mkdir()

    def tearDown(self):
        self.temporary_directory.cleanup()

    def create_database(self, name, value):
        connection = sqlite3.connect(self.data_dir / name)
        try:
            connection.execute("CREATE TABLE state(value TEXT NOT NULL)")
            connection.execute("INSERT INTO state(value) VALUES (?)", (value,))
            connection.commit()
        finally:
            connection.close()

    def read_value(self, name):
        connection = sqlite3.connect(self.data_dir / name)
        try:
            return connection.execute("SELECT value FROM state").fetchone()[0]
        finally:
            connection.close()

    def test_backup_and_restore_preserve_existing_database_and_remove_absent_database(self):
        self.create_database("gateway.db", "before")

        manifest = create_backup(self.data_dir, self.backup_dir)

        self.assertEqual(2, manifest["schemaVersion"])
        self.assertEqual("ok", verify_backup(self.backup_dir)["status"])
        self.assertTrue(manifest["databases"]["gateway.db"]["existed"])
        self.assertFalse(manifest["databases"]["voiceprint.db"]["existed"])

        (self.data_dir / "gateway.db").unlink()
        self.create_database("gateway.db", "after")
        self.create_database("voiceprint.db", "must-be-removed")

        restore_backup(self.data_dir, self.backup_dir)

        self.assertEqual("before", self.read_value("gateway.db"))
        self.assertFalse((self.data_dir / "voiceprint.db").exists())

    def test_backup_and_restore_include_nested_evidence_files(self):
        self.create_database("gateway.db", "with-evidence")
        evidence = self.data_dir / "evidence" / "nested"
        evidence.mkdir(parents=True)
        (self.data_dir / "evidence" / "photo.jpg").write_bytes(b"photo-bytes")
        (evidence / "part.bin").write_bytes(b"video-part")

        manifest = create_backup(self.data_dir, self.backup_dir)

        self.assertTrue(manifest["evidence"]["existed"])
        self.assertEqual(
            {"photo.jpg", "nested/part.bin"},
            {item["path"] for item in manifest["evidence"]["files"]},
        )
        shutil.rmtree(self.data_dir / "evidence")
        (self.data_dir / "gateway.db").unlink()

        restore_backup(self.data_dir, self.backup_dir)

        self.assertEqual(b"photo-bytes", (self.data_dir / "evidence" / "photo.jpg").read_bytes())
        self.assertEqual(b"video-part", (self.data_dir / "evidence" / "nested" / "part.bin").read_bytes())

    def test_tampered_backup_fails_before_live_database_is_changed(self):
        self.create_database("gateway.db", "live")
        create_backup(self.data_dir, self.backup_dir)
        with (self.backup_dir / "gateway.db").open("ab") as output:
            output.write(b"tampered")

        with self.assertRaisesRegex(BackupValidationError, "backup_sha256_mismatch"):
            restore_backup(self.data_dir, self.backup_dir)

        self.assertEqual("live", self.read_value("gateway.db"))

    def test_manifest_rejects_unknown_database_names(self):
        self.create_database("gateway.db", "live")
        create_backup(self.data_dir, self.backup_dir)
        manifest_path = self.backup_dir / "manifest.json"
        manifest = json.loads(manifest_path.read_text("utf-8"))
        manifest["databases"]["../outside.db"] = {"existed": False}
        manifest_path.write_text(json.dumps(manifest), "utf-8")

        with self.assertRaisesRegex(BackupValidationError, "backup_database_set_invalid"):
            verify_backup(self.backup_dir)

    def test_tampered_evidence_fails_before_live_evidence_is_changed(self):
        evidence = self.data_dir / "evidence"
        evidence.mkdir()
        (evidence / "photo.jpg").write_bytes(b"live-photo")
        create_backup(self.data_dir, self.backup_dir)
        (self.backup_dir / "evidence" / "photo.jpg").write_bytes(b"tampered")

        with self.assertRaisesRegex(BackupValidationError, "backup_sha256_mismatch"):
            restore_backup(self.data_dir, self.backup_dir)

        self.assertEqual(b"live-photo", (evidence / "photo.jpg").read_bytes())

    def test_unlisted_evidence_file_is_rejected(self):
        evidence = self.data_dir / "evidence"
        evidence.mkdir()
        (evidence / "photo.jpg").write_bytes(b"photo")
        create_backup(self.data_dir, self.backup_dir)
        (self.backup_dir / "evidence" / "unlisted.bin").write_bytes(b"unexpected")

        with self.assertRaisesRegex(BackupValidationError, "backup_evidence_set_invalid"):
            verify_backup(self.backup_dir)


if __name__ == "__main__":
    unittest.main()
