#!/bin/sh
set -eu

APP_ROOT=/opt/dingdang-v9-gateway
CADDYFILE=/opt/ai-edge-caddy/Caddyfile
EXPERT_HEALTH=https://bb.chinacedar.top:2305/health
V9_HEALTH=https://bb.chinacedar.top:2305/v9-ops/health
SOURCE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RELEASE_ID=${1:-$(date -u +%Y%m%dT%H%M%SZ)}
RELEASE_DIR=$APP_ROOT/releases/$RELEASE_ID
STATE_DIR=$APP_ROOT/deploy-state
BACKUP=$STATE_DIR/Caddyfile.pre-$RELEASE_ID
DB_BACKUP_DIR=$STATE_DIR/db-pre-$RELEASE_ID
OLD_CURRENT=

wait_for_health() {
  url=$1
  attempts=0
  until curl --fail --silent "$url" >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if test "$attempts" -ge 30; then
      curl --fail --silent --show-error "$url" >/dev/null
      return 1
    fi
    sleep 1
  done
}

backup_databases() {
  install -d -m 0700 "$DB_BACKUP_DIR"
  python3 - "$DATA_DIR" "$DB_BACKUP_DIR" <<'PY'
import json
from pathlib import Path
import sqlite3
import sys

data_dir = Path(sys.argv[1])
backup_dir = Path(sys.argv[2])
manifest = {}
for name in ("gateway.db", "voiceprint.db"):
    source = data_dir / name
    manifest[name] = source.is_file()
    if not source.is_file():
        continue
    source_connection = sqlite3.connect(f"file:{source}?mode=ro", uri=True)
    backup_connection = sqlite3.connect(backup_dir / name)
    try:
        source_connection.backup(backup_connection)
    finally:
        backup_connection.close()
        source_connection.close()
(backup_dir / "manifest.json").write_text(
    json.dumps(manifest, sort_keys=True), encoding="utf-8"
)
PY
  printf '%s\n' "$DB_BACKUP_DIR" >"$STATE_DIR/last-db-backup"
}

restore_databases() {
  systemctl stop dingdang-v9-gateway.service >/dev/null 2>&1 || true
  python3 - "$DATA_DIR" "$DB_BACKUP_DIR" <<'PY'
import json
from pathlib import Path
import sqlite3
import sys

data_dir = Path(sys.argv[1])
backup_dir = Path(sys.argv[2])
manifest = json.loads((backup_dir / "manifest.json").read_text(encoding="utf-8"))
data_dir.mkdir(parents=True, exist_ok=True)
for name, existed in manifest.items():
    target = data_dir / name
    if not existed:
        target.unlink(missing_ok=True)
        continue
    backup_connection = sqlite3.connect(f"file:{backup_dir / name}?mode=ro", uri=True)
    target_connection = sqlite3.connect(target)
    try:
        backup_connection.backup(target_connection)
    finally:
        target_connection.close()
        backup_connection.close()
PY
}

test "$(id -u)" -eq 0
test -f "$SOURCE_ROOT/gateway.py"
test -f "$SOURCE_ROOT/asr_proxy.py"
test -f "$SOURCE_ROOT/execution_context.py"
test -f "$SOURCE_ROOT/content_manifest_sync.py"
test -f "$SOURCE_ROOT/control_plane_sync.py"
test -f "$SOURCE_ROOT/mvs_work_order.py"
test -f "$SOURCE_ROOT/knowledge_document_parser.py"
test -f "$SOURCE_ROOT/requirements.txt"
test -f "$SOURCE_ROOT/voiceprint_proxy.py"
test -f "$SOURCE_ROOT/voiceprint_lifecycle.py"
test -f "$SOURCE_ROOT/backup_restore.py"
test -f /etc/dingdang-v9-gateway.env
test -f /etc/dingdang-v9-voiceprint.env
test "$(stat -c %a /etc/dingdang-v9-gateway.env)" = "600"
test "$(stat -c %a /etc/dingdang-v9-voiceprint.env)" = "600"
test -f "$CADDYFILE"
curl --fail --silent --show-error "$EXPERT_HEALTH" >/dev/null
if ! python3 -c 'import ensurepip, venv' >/dev/null 2>&1; then
  printf '%s\n' 'python3-venv with ensurepip is required before deploying the V9 gateway.' >&2
  exit 1
fi

set -a
. /etc/dingdang-v9-gateway.env
set +a
test -n "${V9_ORGANIZATION_ID:-}"
test -n "${V9_USER_ID:-}"
if test -n "${V9_CONTENT_MANIFEST_SYNC_BASE_URL:-}${V9_CONTENT_MANIFEST_SYNC_TOKEN:-}"; then
  test -n "${V9_CONTENT_MANIFEST_SYNC_BASE_URL:-}"
  test -n "${V9_CONTENT_MANIFEST_SYNC_TOKEN:-}"
fi
if test -n "${V9_CONTROL_PLANE_SYNC_BASE_URL:-}${V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN:-}"; then
  test -n "${V9_CONTROL_PLANE_SYNC_BASE_URL:-}"
  test -n "${V9_CONTROL_PLANE_SYNC_BOOTSTRAP_TOKEN:-}"
fi
if test -n "${V9_MVS_BASE_URL:-}${V9_MVS_AUTHORIZATION:-}${V9_MVS_ENGINEER_ID:-}${V9_MVS_RETRY_INTERVAL_SECONDS:-}${V9_MVS_RETRY_BASE_SECONDS:-}${V9_MVS_RETRY_STALE_SECONDS:-}${V9_MVS_RETRY_MAX_ATTEMPTS:-}${V9_MVS_RETRY_BATCH_SIZE:-}"; then
  test -n "${V9_MVS_BASE_URL:-}"
  test -n "${V9_MVS_AUTHORIZATION:-}"
  test -n "${V9_MVS_ENGINEER_ID:-}"
fi
V9_MVS_WRITE_ENABLED=${V9_MVS_WRITE_ENABLED:-false}
test "$V9_MVS_WRITE_ENABLED" = "true" || test "$V9_MVS_WRITE_ENABLED" = "false"
export V9_MVS_WRITE_ENABLED
DATA_DIR=${V9_DATA_DIR:-/var/lib/dingdang-v9-gateway}
BACKUP_ROOT=${V9_BACKUP_DIR:-/var/backups/dingdang-v9-gateway}

install -d -m 0755 "$RELEASE_DIR" "$STATE_DIR"
install -d -m 0700 "$BACKUP_ROOT"
install -m 0644 "$SOURCE_ROOT/gateway.py" "$RELEASE_DIR/gateway.py"
install -m 0644 "$SOURCE_ROOT/asr_proxy.py" "$RELEASE_DIR/asr_proxy.py"
install -m 0644 "$SOURCE_ROOT/execution_context.py" "$RELEASE_DIR/execution_context.py"
install -m 0644 "$SOURCE_ROOT/content_manifest_sync.py" "$RELEASE_DIR/content_manifest_sync.py"
install -m 0644 "$SOURCE_ROOT/control_plane_sync.py" "$RELEASE_DIR/control_plane_sync.py"
install -m 0644 "$SOURCE_ROOT/mvs_work_order.py" "$RELEASE_DIR/mvs_work_order.py"
install -m 0644 "$SOURCE_ROOT/knowledge_document_parser.py" "$RELEASE_DIR/knowledge_document_parser.py"
install -m 0644 "$SOURCE_ROOT/requirements.txt" "$RELEASE_DIR/requirements.txt"
install -m 0644 "$SOURCE_ROOT/voiceprint_proxy.py" "$RELEASE_DIR/voiceprint_proxy.py"
install -m 0644 "$SOURCE_ROOT/voiceprint_lifecycle.py" "$RELEASE_DIR/voiceprint_lifecycle.py"
install -m 0644 "$SOURCE_ROOT/backup_restore.py" "$RELEASE_DIR/backup_restore.py"
install -m 0755 "$SOURCE_ROOT/deploy/backup.sh" "$RELEASE_DIR/backup.sh"
install -m 0755 "$SOURCE_ROOT/deploy/restore-drill.sh" "$RELEASE_DIR/restore-drill.sh"
python3 -m venv "$RELEASE_DIR/.venv"
"$RELEASE_DIR/.venv/bin/python" -m pip install --requirement "$RELEASE_DIR/requirements.txt" \
  --disable-pip-version-check --no-cache-dir
install -m 0644 "$SOURCE_ROOT/deploy/dingdang-v9-gateway.service" \
  /etc/systemd/system/dingdang-v9-gateway.service
install -m 0644 "$SOURCE_ROOT/deploy/dingdang-v9-backup.service" \
  /etc/systemd/system/dingdang-v9-backup.service
install -m 0644 "$SOURCE_ROOT/deploy/dingdang-v9-backup.timer" \
  /etc/systemd/system/dingdang-v9-backup.timer
cp -a "$CADDYFILE" "$BACKUP"
printf '%s\n' "$BACKUP" >"$STATE_DIR/last-caddy-backup"
backup_databases

if test -L "$APP_ROOT/current"; then
  OLD_CURRENT=$(readlink -f "$APP_ROOT/current")
  ln -sfn "$OLD_CURRENT" "$APP_ROOT/previous"
fi

rollback_failed_install() {
  status=$?
  trap - EXIT HUP INT TERM
  if test "$status" -eq 0; then
    return
  fi
  cp -a "$BACKUP" "$CADDYFILE" || true
  docker exec ai-edge-caddy caddy validate --config /etc/caddy/Caddyfile || true
  docker exec ai-edge-caddy caddy reload --config /etc/caddy/Caddyfile || true
  restore_databases || true
  if test -n "$OLD_CURRENT"; then
    ln -sfn "$OLD_CURRENT" "$APP_ROOT/current"
    systemctl restart dingdang-v9-gateway.service || true
  else
    systemctl disable --now dingdang-v9-backup.timer || true
    systemctl disable --now dingdang-v9-gateway.service || true
    if test -L "$APP_ROOT/current"; then
      unlink "$APP_ROOT/current"
    fi
  fi
  curl --fail --silent --show-error "$EXPERT_HEALTH" >/dev/null || true
  exit "$status"
}
trap rollback_failed_install EXIT HUP INT TERM

ln -sfn "$RELEASE_DIR" "$APP_ROOT/current"

python3 "$SOURCE_ROOT/deploy/merge_caddy.py" \
  "$CADDYFILE" "$SOURCE_ROOT/deploy/Caddyfile.snippet"

docker exec ai-edge-caddy caddy validate --config /etc/caddy/Caddyfile
systemctl daemon-reload
systemctl enable dingdang-v9-gateway.service
systemctl restart dingdang-v9-gateway.service
wait_for_health http://127.0.0.1:8790/health
systemctl daemon-reload
systemctl enable --now dingdang-v9-backup.timer
docker exec ai-edge-caddy caddy reload --config /etc/caddy/Caddyfile
wait_for_health "$V9_HEALTH"
wait_for_health "$EXPERT_HEALTH"

trap - EXIT HUP INT TERM
printf 'Installed dingdang-v9-gateway release %s\n' "$RELEASE_ID"
