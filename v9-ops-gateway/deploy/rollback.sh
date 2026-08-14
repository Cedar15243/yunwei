#!/bin/sh
set -eu

APP_ROOT=/opt/dingdang-v9-gateway
CADDYFILE=/opt/ai-edge-caddy/Caddyfile
EXPERT_HEALTH=https://bb.chinacedar.top:2305/health
STATE_DIR=$APP_ROOT/deploy-state

test "$(id -u)" -eq 0
test -f "$STATE_DIR/last-caddy-backup"
test -f "$STATE_DIR/last-db-backup"
BACKUP=$(cat "$STATE_DIR/last-caddy-backup")
DB_BACKUP_DIR=$(cat "$STATE_DIR/last-db-backup")
test -f "$BACKUP"
test -f "$DB_BACKUP_DIR/manifest.json"
set -a
. /etc/dingdang-v9-gateway.env
set +a
DATA_DIR=${V9_DATA_DIR:-/var/lib/dingdang-v9-gateway}

cp -a "$BACKUP" "$CADDYFILE"
docker exec ai-edge-caddy caddy validate --config /etc/caddy/Caddyfile
docker exec ai-edge-caddy caddy reload --config /etc/caddy/Caddyfile

systemctl stop dingdang-v9-gateway.service >/dev/null 2>&1 || true
systemctl disable --now dingdang-v9-backup.timer >/dev/null 2>&1 || true
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

if test -L "$APP_ROOT/previous"; then
  PREVIOUS=$(readlink -f "$APP_ROOT/previous")
  CURRENT=$(readlink -f "$APP_ROOT/current")
  ln -sfn "$PREVIOUS" "$APP_ROOT/current"
  ln -sfn "$CURRENT" "$APP_ROOT/previous"
  systemctl restart dingdang-v9-gateway.service
  systemctl enable --now dingdang-v9-backup.timer
else
  systemctl disable --now dingdang-v9-gateway.service
fi

curl --fail --silent --show-error "$EXPERT_HEALTH" >/dev/null
printf 'Rolled back dingdang-v9-gateway\n'
