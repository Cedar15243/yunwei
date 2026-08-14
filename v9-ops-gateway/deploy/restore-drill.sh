#!/bin/sh
set -eu

APP_ROOT=/opt/dingdang-v9-gateway
set -a
. /etc/dingdang-v9-gateway.env
set +a
BACKUP_ROOT=${V9_BACKUP_DIR:-/var/backups/dingdang-v9-gateway}
BACKUP_DIR=${1:-$(cat "$BACKUP_ROOT/last-good")}
PYTHON=$APP_ROOT/current/.venv/bin/python
PUBLIC_V9_BASE=${V9_PUBLIC_V9_BASE:-https://bb.chinacedar.top:2305/v9-ops}
EXPERT_HEALTH=${V9_EXPERT_HEALTH_URL:-https://bb.chinacedar.top:2305/health}
DRILL_DIR=$(mktemp -d /tmp/dingdang-v9-restore-drill.XXXXXX)
trap 'rm -rf "$DRILL_DIR"' EXIT HUP INT TERM

test -x "$PYTHON"
test -d "$BACKUP_DIR"
started_at=$(date -u +%FT%TZ)
start=$(date +%s)
verify_before=$("$PYTHON" "$APP_ROOT/current/backup_restore.py" verify "$BACKUP_DIR") # backup_restore.py verify
restore_result=$("$PYTHON" "$APP_ROOT/current/backup_restore.py" restore "$DRILL_DIR/data" "$BACKUP_DIR")
verify_after=$("$PYTHON" "$APP_ROOT/current/backup_restore.py" verify "$BACKUP_DIR")
test -f "$DRILL_DIR/data/gateway.db"
test -f "$DRILL_DIR/data/voiceprint.db"
"$PYTHON" - "$DRILL_DIR/data/gateway.db" "$DRILL_DIR/data/voiceprint.db" <<'PY'
import sqlite3
import sys

for label, path in zip(("GATEWAY_DB", "VOICEPRINT_DB"), sys.argv[1:]):
    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        assert connection.execute("PRAGMA quick_check").fetchone() == ("ok",)
    finally:
        connection.close()
PY
printf 'GATEWAY_DB_QUICK_CHECK=ok\n'
printf 'VOICEPRINT_DB_QUICK_CHECK=ok\n'
if test -d "$BACKUP_DIR/evidence"; then
  test -d "$DRILL_DIR/data/evidence"
  expected=$(find "$BACKUP_DIR/evidence" -type f | wc -l)
  actual=$(find "$DRILL_DIR/data/evidence" -type f | wc -l)
  test "$expected" -eq "$actual"
else
  expected=0
  actual=0
fi
end=$(date +%s)
completed_at=$(date -u +%FT%TZ)
printf 'DRILL_STARTED_AT=%s\n' "$started_at"
printf 'DRILL_COMPLETED_AT=%s\n' "$completed_at"
printf 'BACKUP_DIR=%s\n' "$BACKUP_DIR"
printf 'BACKUP_MANIFEST_SHA256=%s\n' "$(sha256sum "$BACKUP_DIR/manifest.json" | awk '{print $1}')"
printf 'RESTORE_DRILL_SECONDS=%s\n' "$((end-start))"
printf 'EVIDENCE_FILES_EXPECTED=%s\n' "$expected"
printf 'EVIDENCE_FILES_RESTORED=%s\n' "$actual"
printf 'VERIFY_BEFORE=%s\n' "$verify_before"
printf 'RESTORE_RESULT=%s\n' "$restore_result"
printf 'VERIFY_AFTER=%s\n' "$verify_after"
printf 'LOCAL_HEALTH=%s\n' "$(curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8790/health)"
printf 'LOCAL_READY=%s\n' "$(curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8790/ready)"
printf 'PUBLIC_HEALTH=%s\n' "$(curl --fail --silent --show-error --max-time 8 "$PUBLIC_V9_BASE/health")"
printf 'PUBLIC_READY=%s\n' "$(curl --fail --silent --show-error --max-time 8 "$PUBLIC_V9_BASE/ready")"
printf 'EXPERT_HEALTH=%s\n' "$(curl --fail --silent --show-error --max-time 8 "$EXPERT_HEALTH")"
