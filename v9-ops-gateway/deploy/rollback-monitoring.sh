#!/bin/sh
set -eu

APP_ROOT=/opt/dingdang-v9-monitor
CURRENT=$(readlink -f "$APP_ROOT/current")
PREVIOUS=$(readlink -f "$APP_ROOT/previous")

test "$(id -u)" -eq 0
test -n "$CURRENT"
test -n "$PREVIOUS"
test "$CURRENT" != "$PREVIOUS"
test -f "$PREVIOUS/monitoring_probe.py"
test -f "$PREVIOUS/dingdang-v9-monitor.service"
test -f "$PREVIOUS/dingdang-v9-monitor.timer"

ln -sfn "$PREVIOUS" "$APP_ROOT/current"
ln -sfn "$CURRENT" "$APP_ROOT/previous"
install -m 0644 "$PREVIOUS/dingdang-v9-monitor.service" \
  /etc/systemd/system/dingdang-v9-monitor.service
install -m 0644 "$PREVIOUS/dingdang-v9-monitor.timer" \
  /etc/systemd/system/dingdang-v9-monitor.timer
systemctl daemon-reload
systemctl enable --now dingdang-v9-monitor.timer
systemctl start dingdang-v9-monitor.service
printf 'Rolled dingdang-v9-monitor back to %s\n' "$PREVIOUS"
