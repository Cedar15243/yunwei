#!/bin/sh
set -eu

CONFIG_ROOT=${V9_CONFIG_RELEASE_ROOT:-/opt/dingdang-v9-gateway/config-releases}
GATEWAY_ENV=${V9_GATEWAY_ENV_PATH:-/etc/dingdang-v9-gateway.env}
VOICEPRINT_ENV=${V9_VOICEPRINT_ENV_PATH:-/etc/dingdang-v9-voiceprint.env}
SOURCE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RELEASE_ID=${1:-}
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
test -L "$CONFIG_ROOT/candidate"
CANDIDATE=$(readlink -f "$CONFIG_ROOT/candidate")
case "$CANDIDATE" in "$CONFIG_ROOT"/releases/*) ;; *) exit 1 ;; esac
if test -n "$RELEASE_ID"; then
  test "$(basename "$CANDIDATE")" = "$RELEASE_ID"
fi
(cd "$CANDIDATE" && sha256sum -c SHA256SUMS)

CURRENT_LINK_PRESENT=0
BASELINE_CREATED=0
if test -L "$CONFIG_ROOT/current"; then
  CURRENT=$(readlink -f "$CONFIG_ROOT/current")
  case "$CURRENT" in "$CONFIG_ROOT"/releases/*) ;; *) exit 1 ;; esac
  (cd "$CURRENT" && sha256sum -c SHA256SUMS)
  CURRENT_LINK_PRESENT=1
else
  BASELINE_ID=baseline-$(date -u +%Y%m%dT%H%M%SZ)
  CURRENT=$CONFIG_ROOT/releases/$BASELINE_ID
  install -d -m 0700 "$CURRENT"
  install -m 0600 -o root -g root "$GATEWAY_ENV" "$CURRENT/gateway.env"
  install -m 0600 -o root -g root "$VOICEPRINT_ENV" "$CURRENT/voiceprint.env"
  (cd "$CURRENT" && sha256sum gateway.env voiceprint.env >SHA256SUMS)
  BASELINE_CREATED=1
fi
PREVIOUS_LINK_PRESENT=0
PREVIOUS_TARGET=
if test -L "$CONFIG_ROOT/previous"; then
  PREVIOUS_TARGET=$(readlink -f "$CONFIG_ROOT/previous")
  case "$PREVIOUS_TARGET" in "$CONFIG_ROOT"/releases/*) ;; *) exit 1 ;; esac
  PREVIOUS_LINK_PRESENT=1
fi
CHANGES_STARTED=0

rollback_failed_activation() {
  status=$?
  trap - EXIT HUP INT TERM
  if test "$status" -ne 0 && test "$CHANGES_STARTED" -eq 1; then
    install_environment "$CURRENT/gateway.env" "$GATEWAY_ENV" || true
    install_environment "$CURRENT/voiceprint.env" "$VOICEPRINT_ENV" || true
    if test "$CURRENT_LINK_PRESENT" -eq 1; then
      atomic_link "$CURRENT" "$CONFIG_ROOT/current" || true
    else
      rm -f "$CONFIG_ROOT/current"
    fi
    if test "$PREVIOUS_LINK_PRESENT" -eq 1; then
      atomic_link "$PREVIOUS_TARGET" "$CONFIG_ROOT/previous" || true
    else
      rm -f "$CONFIG_ROOT/previous"
    fi
    systemctl restart dingdang-v9-gateway.service >/dev/null 2>&1 || true
    wait_for_health "$LOCAL_BASE/health" >/dev/null 2>&1 || true
    wait_for_health "$EXPERT_HEALTH" >/dev/null 2>&1 || true
  fi
  if test "$status" -ne 0 && test "$BASELINE_CREATED" -eq 1; then
    rm -rf "$CURRENT" || true
  fi
  exit "$status"
}
interrupt_activation() {
  trap - HUP INT TERM
  exit 1
}
trap rollback_failed_activation EXIT
trap interrupt_activation HUP INT TERM

CHANGES_STARTED=1
atomic_link "$CURRENT" "$CONFIG_ROOT/previous"
atomic_link "$CANDIDATE" "$CONFIG_ROOT/current"

install_environment "$CANDIDATE/gateway.env" "$GATEWAY_ENV"
install_environment "$CANDIDATE/voiceprint.env" "$VOICEPRINT_ENV"
systemctl restart dingdang-v9-gateway.service
wait_for_health "$LOCAL_BASE/health"
wait_for_health "$LOCAL_BASE/ready"
wait_for_health "$PUBLIC_V9_BASE/health"
wait_for_health "$PUBLIC_V9_BASE/ready"
wait_for_health "$EXPERT_HEALTH"
rm -f "$CONFIG_ROOT/candidate"
trap - EXIT HUP INT TERM
printf 'Activated V9 production configuration overlay %s\n' "$(basename "$CANDIDATE")"
