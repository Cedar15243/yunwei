#!/bin/sh
set -eu

APP_ROOT=${V9_APP_ROOT:-/opt/dingdang-v9-gateway}
GATEWAY_ENV_FILE=${V9_GATEWAY_ENV_FILE:-/etc/dingdang-v9-gateway.env}
VOICEPRINT_ENV_FILE=${V9_VOICEPRINT_ENV_FILE:-/etc/dingdang-v9-voiceprint.env}
SOURCE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RELEASE_ID=${1:-$(date -u +%Y%m%dT%H%M%SZ)-runtime-update}
RELEASE_DIR=$APP_ROOT/releases/$RELEASE_ID
STAGING=$APP_ROOT/releases/.stage-$RELEASE_ID-$$
STATE_DIR=$APP_ROOT/deploy-state
LOCAL_BASE=http://127.0.0.1:8790
PUBLIC_V9_BASE=https://bb.chinacedar.top:2305/v9-ops
EXPERT_HEALTH=https://bb.chinacedar.top:2305/health
CURL='curl --fail --silent --show-error --max-time 5'

case "$RELEASE_ID" in
  *[!A-Za-z0-9._-]*|'') printf '%s\n' 'release_id_invalid' >&2; exit 1 ;;
esac

wait_for_health() {
  url=$1
  attempts=0
  until $CURL "$url" >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if test "$attempts" -ge 30; then
      $CURL "$url" >/dev/null
      return 1
    fi
    sleep 1
  done
}

test "$(id -u)" -eq 0
test -L "$APP_ROOT/current"
test -f "$SOURCE_ROOT/gateway.py"
test -f "$SOURCE_ROOT/voiceprint_lifecycle.py"
test ! -e "$RELEASE_DIR"
CURRENT=$(readlink -f "$APP_ROOT/current")
case "$CURRENT" in "$APP_ROOT"/releases/*) ;; *) exit 1 ;; esac
PREVIOUS_PRESENT=0
PREVIOUS_TARGET=
if test -L "$APP_ROOT/previous"; then
  PREVIOUS_TARGET=$(readlink -f "$APP_ROOT/previous")
  case "$PREVIOUS_TARGET" in "$APP_ROOT"/releases/*) ;; *) exit 1 ;; esac
  PREVIOUS_PRESENT=1
fi
test -x "$CURRENT/.venv/bin/python"
test -f "$CURRENT/backup_restore.py"
test -f "$GATEWAY_ENV_FILE"
test -f "$VOICEPRINT_ENV_FILE"
set -a
. "$GATEWAY_ENV_FILE"
. "$VOICEPRINT_ENV_FILE"
set +a
DATA_DIR=${V9_DATA_DIR:-/var/lib/dingdang-v9-gateway}
BACKUP_ROOT=${V9_BACKUP_DIR:-/var/backups/dingdang-v9-gateway}
BACKUP_DIR=$BACKUP_ROOT/runtime-update-$RELEASE_ID
install -d -m 0700 "$STATE_DIR" "$BACKUP_ROOT"

wait_for_health "$LOCAL_BASE/health"
wait_for_health "$LOCAL_BASE/ready"
wait_for_health "$PUBLIC_V9_BASE/health"
wait_for_health "$PUBLIC_V9_BASE/ready"
wait_for_health "$EXPERT_HEALTH"

"$CURRENT/.venv/bin/python" "$CURRENT/backup_restore.py" backup "$DATA_DIR" "$BACKUP_DIR"
"$CURRENT/.venv/bin/python" "$CURRENT/backup_restore.py" verify "$BACKUP_DIR"

umask 077
install -d -m 0755 "$STAGING"
trap 'rm -rf "$STAGING"' EXIT HUP INT TERM
cp -a "$CURRENT/." "$STAGING/"
install -m 0644 "$SOURCE_ROOT/gateway.py" "$STAGING/gateway.py"
install -m 0644 "$SOURCE_ROOT/voiceprint_lifecycle.py" "$STAGING/voiceprint_lifecycle.py"
"$STAGING/.venv/bin/python" -m py_compile \
  "$STAGING/gateway.py" \
  "$STAGING/voiceprint_lifecycle.py"
PYTHONPATH="$STAGING" "$STAGING/.venv/bin/python" -c \
  'import os; from gateway import configuration_from_environment, voiceprint_runtime_configuration_from_environment; config = configuration_from_environment(os.environ); voiceprint = voiceprint_runtime_configuration_from_environment(os.environ, config.device_id); assert voiceprint is not None'
(cd "$STAGING" && sha256sum gateway.py voiceprint_lifecycle.py >RUNTIME_UPDATE_SHA256SUMS)
mv "$STAGING" "$RELEASE_DIR"
trap - EXIT HUP INT TERM

rollback_failed_update() {
  status=$?
  trap - EXIT HUP INT TERM
  if test "$status" -ne 0; then
    ln -sfn "$CURRENT" "$APP_ROOT/current"
    if test "$PREVIOUS_PRESENT" -eq 1; then
      ln -sfn "$PREVIOUS_TARGET" "$APP_ROOT/previous"
    else
      rm -f "$APP_ROOT/previous"
    fi
    systemctl restart dingdang-v9-gateway.service >/dev/null 2>&1 || true
    wait_for_health "$LOCAL_BASE/health" >/dev/null 2>&1 || true
    wait_for_health "$EXPERT_HEALTH" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
interrupt_update() {
  trap - HUP INT TERM
  exit 1
}
trap rollback_failed_update EXIT
trap interrupt_update HUP INT TERM

ln -sfn "$CURRENT" "$APP_ROOT/previous"
ln -sfn "$RELEASE_DIR" "$APP_ROOT/current"
systemctl restart dingdang-v9-gateway.service
wait_for_health "$LOCAL_BASE/health"
wait_for_health "$LOCAL_BASE/ready"
wait_for_health "$PUBLIC_V9_BASE/health"
wait_for_health "$PUBLIC_V9_BASE/ready"
wait_for_health "$EXPERT_HEALTH"
trap - EXIT HUP INT TERM
printf 'Updated dingdang-v9-gateway runtime release %s\n' "$RELEASE_ID"
