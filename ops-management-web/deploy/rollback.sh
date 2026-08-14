#!/bin/sh
set -eu

APP_ROOT=/srv/dingdang-ops-management-web
CADDYFILE=/opt/ai-edge-caddy/Caddyfile
EXPERT_HEALTH=https://bb.chinacedar.top:2305/health
V9_HEALTH=https://bb.chinacedar.top:2305/v9-ops/health
EXPERT_CONTAINER=dingdang-expert-collab
CADDY_CONTAINER=ai-edge-caddy
STATE_DIR=$APP_ROOT/deploy-state
ROLLBACK_ID=$(date -u +%Y%m%dT%H%M%SZ)
ROLLBACK_BACKUP=$STATE_DIR/Caddyfile.pre-rollback-$ROLLBACK_ID
HAS_PREVIOUS=false
PREVIOUS=

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

container_fingerprint() {
  docker inspect --format '{{.Id}}|{{.State.StartedAt}}|{{.Image}}' "$1"
}

assert_container_unchanged() {
  container=$1
  expected=$2
  actual=$(container_fingerprint "$container")
  test "$actual" = "$expected" || fail "$container changed during management Web rollback"
}

assert_image_identity() {
  image=$1
  expected=$2
  actual=$(docker image inspect --format '{{.Id}}' "$image")
  test "$actual" = "$expected" || fail "$image identity does not match its release metadata"
}

validate_service_body() {
  expected=$1
  python3 -c 'import json, sys
payload = json.load(sys.stdin)
raise SystemExit(0 if payload.get("ok") is True and payload.get("service") == sys.argv[1] else 1)' \
    "$expected"
}

wait_for_service() {
  url=$1
  service=$2
  attempts=0
  until body=$(curl --fail --silent --show-error --max-time 8 "$url" 2>/dev/null) && \
      printf '%s' "$body" | validate_service_body "$service"; do
    attempts=$((attempts + 1))
    if test "$attempts" -ge 45; then
      curl --fail --silent --show-error --max-time 8 "$url" >/dev/null
      return 1
    fi
    sleep 1
  done
}

compose_release() {
  release=$1
  image=$2
  port=$3
  shift 3
  OPS_MANAGEMENT_IMAGE=$image OPS_MANAGEMENT_PORT=$port docker compose \
    --project-name dingdang-ops-management \
    -f "$release/docker-compose.cloud.yml" "$@"
}

test "$(id -u)" -eq 0
test -L "$APP_ROOT/current"
test -f "$STATE_DIR/last-caddy-backup"
test -f "$CADDYFILE"
CURRENT=$(readlink -f "$APP_ROOT/current")
test -f "$CURRENT/release.env"
CADDY_BACKUP=$(cat "$STATE_DIR/last-caddy-backup")
test -f "$CADDY_BACKUP"
cp -a "$CADDYFILE" "$ROLLBACK_BACKUP"

set -a
. "$CURRENT/release.env"
set +a
: "${OPS_MANAGEMENT_IMAGE:?current release image is missing}"
: "${OPS_MANAGEMENT_IMAGE_ID:?current release image identity is missing}"
: "${OPS_MANAGEMENT_DOMAIN:?current release domain is missing}"
CURRENT_IMAGE=$OPS_MANAGEMENT_IMAGE
CURRENT_IMAGE_ID=$OPS_MANAGEMENT_IMAGE_ID
CURRENT_DOMAIN=$OPS_MANAGEMENT_DOMAIN
CURRENT_PORT=${OPS_MANAGEMENT_PORT:-8788}
assert_image_identity "$CURRENT_IMAGE" "$CURRENT_IMAGE_ID"

if test -L "$APP_ROOT/previous"; then
  PREVIOUS=$(readlink -f "$APP_ROOT/previous")
  test "$CURRENT" != "$PREVIOUS"
  test -f "$PREVIOUS/release.env"
  HAS_PREVIOUS=true
  set -a
  . "$PREVIOUS/release.env"
  set +a
  : "${OPS_MANAGEMENT_IMAGE:?previous release image is missing}"
  : "${OPS_MANAGEMENT_IMAGE_ID:?previous release image identity is missing}"
  : "${OPS_MANAGEMENT_DOMAIN:?previous release domain is missing}"
  PREVIOUS_IMAGE=$OPS_MANAGEMENT_IMAGE
  PREVIOUS_IMAGE_ID=$OPS_MANAGEMENT_IMAGE_ID
  PREVIOUS_DOMAIN=$OPS_MANAGEMENT_DOMAIN
  PREVIOUS_PORT=${OPS_MANAGEMENT_PORT:-8788}
  assert_image_identity "$PREVIOUS_IMAGE" "$PREVIOUS_IMAGE_ID"
fi

EXPERT_CONTAINER_BEFORE=$(container_fingerprint "$EXPERT_CONTAINER")
CADDY_CONTAINER_BEFORE=$(container_fingerprint "$CADDY_CONTAINER")
wait_for_service "$EXPERT_HEALTH" expert-collab
wait_for_service "$V9_HEALTH" dingdang-v9-gateway

restore_failed_rollback() {
  status=$?
  trap - EXIT HUP INT TERM
  if test "$status" -eq 0; then
    return
  fi
  cp -a "$ROLLBACK_BACKUP" "$CADDYFILE" || true
  docker exec ai-edge-caddy caddy validate --config /etc/caddy/Caddyfile || true
  docker exec ai-edge-caddy caddy reload --config /etc/caddy/Caddyfile || true
  ln -sfn "$CURRENT" "$APP_ROOT/current"
  if test "$HAS_PREVIOUS" = true; then
    ln -sfn "$PREVIOUS" "$APP_ROOT/previous"
  elif test -L "$APP_ROOT/previous"; then
    unlink "$APP_ROOT/previous"
  fi
  compose_release "$CURRENT" "$CURRENT_IMAGE" "$CURRENT_PORT" up -d --no-build --remove-orphans || true
  exit "$status"
}
trap restore_failed_rollback EXIT HUP INT TERM

cp -a "$CADDY_BACKUP" "$CADDYFILE"
docker exec ai-edge-caddy caddy validate --config /etc/caddy/Caddyfile

if test "$HAS_PREVIOUS" = true; then
  ln -sfn "$PREVIOUS" "$APP_ROOT/current"
  ln -sfn "$CURRENT" "$APP_ROOT/previous"
  compose_release "$PREVIOUS" "$PREVIOUS_IMAGE" "$PREVIOUS_PORT" up -d --no-build --remove-orphans
  wait_for_service "http://127.0.0.1:$PREVIOUS_PORT/health" dingdang-ops-management-web
else
  compose_release "$CURRENT" "$CURRENT_IMAGE" "$CURRENT_PORT" down --remove-orphans
  unlink "$APP_ROOT/current"
fi

docker exec ai-edge-caddy caddy reload --config /etc/caddy/Caddyfile
if test "$HAS_PREVIOUS" = true; then
  wait_for_service "https://$PREVIOUS_DOMAIN/health" dingdang-ops-management-web
fi
wait_for_service "$EXPERT_HEALTH" expert-collab
wait_for_service "$V9_HEALTH" dingdang-v9-gateway
assert_container_unchanged "$EXPERT_CONTAINER" "$EXPERT_CONTAINER_BEFORE"
assert_container_unchanged "$CADDY_CONTAINER" "$CADDY_CONTAINER_BEFORE"

printf '%s\n' "$ROLLBACK_BACKUP" >"$STATE_DIR/last-caddy-backup"
if test "$HAS_PREVIOUS" = true; then
  printf '%s\n' "$PREVIOUS" >"$STATE_DIR/current-release"
  printf 'Rolled back dingdang-ops-management-web to %s\n' "$(basename "$PREVIOUS")"
else
  rm -f "$STATE_DIR/current-release"
  printf 'Rolled back initial dingdang-ops-management-web activation\n'
fi
trap - EXIT HUP INT TERM
