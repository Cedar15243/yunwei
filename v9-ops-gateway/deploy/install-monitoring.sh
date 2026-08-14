#!/bin/sh
set -eu

APP_ROOT=/opt/dingdang-v9-monitor
SOURCE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RELEASE_ID=${1:-$(date -u +%Y%m%dT%H%M%SZ)}
RELEASE_DIR=$APP_ROOT/releases/$RELEASE_ID
OLD_CURRENT=

test "$(id -u)" -eq 0
if ! printf '%s\n' "$RELEASE_ID" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$' \
  || printf '%s\n' "$RELEASE_ID" | grep -Fq '..'; then
  printf '%s\n' 'release_id_invalid' >&2
  exit 1
fi
test -f "$SOURCE_ROOT/monitoring_probe.py"
test -f "$SOURCE_ROOT/deploy/dingdang-v9-monitor.service"
test -f "$SOURCE_ROOT/deploy/dingdang-v9-monitor.timer"
test -f "$SOURCE_ROOT/deploy/dingdang-v9-monitor.env.example"
test ! -e "$RELEASE_DIR"

if test -e /etc/dingdang-v9-monitor.env; then
  test -f /etc/dingdang-v9-monitor.env
  test "$(stat -c %a /etc/dingdang-v9-monitor.env)" = "600"
else
  install -m 0600 "$SOURCE_ROOT/deploy/dingdang-v9-monitor.env.example" \
    /etc/dingdang-v9-monitor.env
fi

install -d -m 0755 "$APP_ROOT/releases"
install -d -m 0755 "$RELEASE_DIR"
install -m 0644 "$SOURCE_ROOT/monitoring_probe.py" "$RELEASE_DIR/monitoring_probe.py"
install -m 0644 "$SOURCE_ROOT/deploy/dingdang-v9-monitor.service" "$RELEASE_DIR/dingdang-v9-monitor.service"
install -m 0644 "$SOURCE_ROOT/deploy/dingdang-v9-monitor.timer" "$RELEASE_DIR/dingdang-v9-monitor.timer"

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
  if test -n "$OLD_CURRENT"; then
    ln -sfn "$OLD_CURRENT" "$APP_ROOT/current"
    install -m 0644 "$OLD_CURRENT/dingdang-v9-monitor.service" \
      /etc/systemd/system/dingdang-v9-monitor.service
    install -m 0644 "$OLD_CURRENT/dingdang-v9-monitor.timer" \
      /etc/systemd/system/dingdang-v9-monitor.timer
  elif test -L "$APP_ROOT/current"; then
    unlink "$APP_ROOT/current"
    systemctl disable --now dingdang-v9-monitor.timer || true
  fi
  rm -rf "$RELEASE_DIR"
  systemctl daemon-reload || true
  exit "$status"
}
trap rollback_failed_install EXIT HUP INT TERM

ln -sfn "$RELEASE_DIR" "$APP_ROOT/current"
install -m 0644 "$RELEASE_DIR/dingdang-v9-monitor.service" \
  /etc/systemd/system/dingdang-v9-monitor.service
install -m 0644 "$RELEASE_DIR/dingdang-v9-monitor.timer" \
  /etc/systemd/system/dingdang-v9-monitor.timer
systemctl daemon-reload
systemctl enable --now dingdang-v9-monitor.timer
systemctl start dingdang-v9-monitor.service

trap - EXIT HUP INT TERM
printf 'Installed dingdang-v9-monitor release %s\n' "$RELEASE_ID"
