#!/bin/sh
set -eu

CONFIG_ROOT=${V9_CONFIG_RELEASE_ROOT:-/opt/dingdang-v9-gateway/config-releases}
GATEWAY_ENV=${V9_GATEWAY_ENV_PATH:-/etc/dingdang-v9-gateway.env}
VOICEPRINT_ENV=${V9_VOICEPRINT_ENV_PATH:-/etc/dingdang-v9-voiceprint.env}
LOCAL_BASE=http://127.0.0.1:8790
PUBLIC_V9_BASE=https://bb.chinacedar.top:2305/v9-ops
EXPERT_HEALTH=https://bb.chinacedar.top:2305/health
CURL='curl --fail --silent --show-error --max-time 5'

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

atomic_link() {
  target=$1
  link=$2
  ln -sfn "$target" "$link.new.$$"
  mv -Tf "$link.new.$$" "$link"
}

install_environment() {
  source=$1
  target=$2
  install -m 0600 -o root -g root "$source" "$target.next.$$"
  mv -f "$target.next.$$" "$target"
}

test "$(id -u)" -eq 0
test -L "$CONFIG_ROOT/current"
test -L "$CONFIG_ROOT/previous"
CURRENT=$(readlink -f "$CONFIG_ROOT/current")
PREVIOUS=$(readlink -f "$CONFIG_ROOT/previous")
(cd "$CURRENT" && sha256sum -c SHA256SUMS)
(cd "$PREVIOUS" && sha256sum -c SHA256SUMS)

restore_current_on_failure() {
  status=$?
  trap - EXIT HUP INT TERM
  if test "$status" -ne 0; then
    install_environment "$CURRENT/gateway.env" "$GATEWAY_ENV" || true
    install_environment "$CURRENT/voiceprint.env" "$VOICEPRINT_ENV" || true
    atomic_link "$CURRENT" "$CONFIG_ROOT/current" || true
    atomic_link "$PREVIOUS" "$CONFIG_ROOT/previous" || true
    systemctl restart dingdang-v9-gateway.service >/dev/null 2>&1 || true
    wait_for_health "$LOCAL_BASE/health" >/dev/null 2>&1 || true
    wait_for_health "$EXPERT_HEALTH" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
interrupt_rollback() {
  trap - HUP INT TERM
  exit 1
}
trap restore_current_on_failure EXIT
trap interrupt_rollback HUP INT TERM

install_environment "$PREVIOUS/gateway.env" "$GATEWAY_ENV"
install_environment "$PREVIOUS/voiceprint.env" "$VOICEPRINT_ENV"
systemctl restart dingdang-v9-gateway.service
wait_for_health "$LOCAL_BASE/health"
wait_for_health "$LOCAL_BASE/ready"
wait_for_health "$PUBLIC_V9_BASE/health"
wait_for_health "$PUBLIC_V9_BASE/ready"
wait_for_health "$EXPERT_HEALTH"
atomic_link "$PREVIOUS" "$CONFIG_ROOT/current"
atomic_link "$CURRENT" "$CONFIG_ROOT/previous"
trap - EXIT HUP INT TERM
printf 'Rolled back V9 production configuration overlay to %s\n' "$(basename "$PREVIOUS")"
