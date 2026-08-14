#!/bin/sh
set -eu

APP_ROOT=/opt/dingdang-v9-gateway
set -a
. /etc/dingdang-v9-gateway.env
set +a
DATA_DIR=${V9_DATA_DIR:-/var/lib/dingdang-v9-gateway}
BACKUP_ROOT=${V9_BACKUP_DIR:-/var/backups/dingdang-v9-gateway}
RETENTION_DAYS=${V9_BACKUP_RETENTION_DAYS:-14}
RELEASE_ID=$(date -u +%Y%m%dT%H%M%SZ)
BACKUP_DIR=$BACKUP_ROOT/$RELEASE_ID
PYTHON=$APP_ROOT/current/.venv/bin/python

test -x "$PYTHON"
test -f "$APP_ROOT/current/backup_restore.py"
case "$RETENTION_DAYS" in
  ''|*[!0-9]*) echo "invalid V9_BACKUP_RETENTION_DAYS" >&2; exit 1 ;;
esac
install -d -m 0700 "$BACKUP_ROOT"
"$PYTHON" "$APP_ROOT/current/backup_restore.py" backup "$DATA_DIR" "$BACKUP_DIR" # backup_restore.py backup
"$PYTHON" "$APP_ROOT/current/backup_restore.py" verify "$BACKUP_DIR"
printf '%s\n' "$BACKUP_DIR" >"$BACKUP_ROOT/last-good"
find "$BACKUP_ROOT" -mindepth 1 -maxdepth 1 -type d -mtime +"$RETENTION_DAYS" -exec rm -rf -- {} +
printf 'V9 backup created: %s\n' "$BACKUP_DIR"
