#!/bin/sh
set -eu

CONFIG_ROOT=${V9_CONFIG_RELEASE_ROOT:-/opt/dingdang-v9-gateway/config-releases}
GATEWAY_ENV=${V9_GATEWAY_ENV_PATH:-/etc/dingdang-v9-gateway.env}
VOICEPRINT_ENV=${V9_VOICEPRINT_ENV_PATH:-/etc/dingdang-v9-voiceprint.env}
SOURCE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GATEWAY_OVERLAY=${1:?gateway overlay env file is required}
VOICEPRINT_OVERLAY=${2:?voiceprint overlay env file is required}
RELEASE_ID=${3:-$(date -u +%Y%m%dT%H%M%SZ)-production-overlay}
RELEASE_DIR=$CONFIG_ROOT/releases/$RELEASE_ID
TEMP_DIR=$CONFIG_ROOT/.stage-$RELEASE_ID-$$
APPROVED_AI_MODEL=qwen3-vl-plus
APPROVED_ASR_MODEL=fun-asr-realtime
APPROVED_VOICEPRINT_SERVICE=s1aa729d0

case "$RELEASE_ID" in
  *[!A-Za-z0-9._-]*|'') printf '%s\n' 'release_id_invalid' >&2; exit 1 ;;
esac

test "$(id -u)" -eq 0
test -f "$GATEWAY_ENV"
test -f "$VOICEPRINT_ENV"
test -f "$GATEWAY_OVERLAY"
test -f "$VOICEPRINT_OVERLAY"
test ! -e "$RELEASE_DIR"
test "$(stat -c %a "$GATEWAY_ENV")" = "600"
test "$(stat -c %a "$VOICEPRINT_ENV")" = "600"
test "$(stat -c %U:%G "$GATEWAY_ENV")" = "root:root"
test "$(stat -c %U:%G "$VOICEPRINT_ENV")" = "root:root"
test "$(stat -c %a "$GATEWAY_OVERLAY")" = "600"
test "$(stat -c %a "$VOICEPRINT_OVERLAY")" = "600"
test "$(stat -c %U:%G "$GATEWAY_OVERLAY")" = "root:root"
test "$(stat -c %U:%G "$VOICEPRINT_OVERLAY")" = "root:root"
umask 077
install -d -m 0700 "$CONFIG_ROOT" "$CONFIG_ROOT/releases" "$TEMP_DIR"
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

python3 "$SOURCE_ROOT/merge_production_overlay.py" \
  --gateway-base "$GATEWAY_ENV" \
  --voiceprint-base "$VOICEPRINT_ENV" \
  --gateway-overlay "$GATEWAY_OVERLAY" \
  --voiceprint-overlay "$VOICEPRINT_OVERLAY" \
  --gateway-output "$TEMP_DIR/gateway.env" \
  --voiceprint-output "$TEMP_DIR/voiceprint.env"

grep -Fx "V9_AI_MODEL=$APPROVED_AI_MODEL" "$TEMP_DIR/gateway.env" >/dev/null
grep -Fx "V9_ASR_MODEL=$APPROVED_ASR_MODEL" "$TEMP_DIR/gateway.env" >/dev/null
grep -F "/$APPROVED_VOICEPRINT_SERVICE" "$TEMP_DIR/voiceprint.env" >/dev/null
install -m 0700 "$SOURCE_ROOT/merge_production_overlay.py" "$TEMP_DIR/merge_production_overlay.py"
install -m 0700 "$SOURCE_ROOT/stage-production-overlay.sh" "$TEMP_DIR/stage-production-overlay.sh"
install -m 0700 "$SOURCE_ROOT/activate-production-overlay.sh" "$TEMP_DIR/activate-production-overlay.sh"
install -m 0700 "$SOURCE_ROOT/rollback-production-overlay.sh" "$TEMP_DIR/rollback-production-overlay.sh"
(cd "$TEMP_DIR" && sha256sum \
  gateway.env \
  voiceprint.env \
  merge_production_overlay.py \
  stage-production-overlay.sh \
  activate-production-overlay.sh \
  rollback-production-overlay.sh >SHA256SUMS)
mv "$TEMP_DIR" "$RELEASE_DIR"
trap - EXIT HUP INT TERM
ln -sfn "$RELEASE_DIR" "$CONFIG_ROOT/candidate.new.$$"
mv -Tf "$CONFIG_ROOT/candidate.new.$$" "$CONFIG_ROOT/candidate"
printf 'Staged V9 production configuration overlay %s\n' "$RELEASE_ID"
