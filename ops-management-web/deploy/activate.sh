#!/bin/sh
set -eu

APP_ROOT=/srv/dingdang-ops-management-web
CADDYFILE=/opt/ai-edge-caddy/Caddyfile
CONFIG_FILE=${OPS_MANAGEMENT_ENV_FILE:-/etc/dingdang-ops-management-web.env}
EXPERT_HEALTH=https://bb.chinacedar.top:2305/health
V9_HEALTH=https://bb.chinacedar.top:2305/v9-ops/health
EXPERT_CONTAINER=dingdang-expert-collab
CADDY_CONTAINER=ai-edge-caddy
STATE_DIR=$APP_ROOT/deploy-state
RELEASE_ID=${1:-}
OLD_CURRENT=
EXPERT_CONTAINER_BEFORE=
CADDY_CONTAINER_BEFORE=

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
  test "$actual" = "$expected" || fail "$container changed during management Web activation"
}

assert_image_identity() {
  image=$1
  expected=$2
  actual=$(docker image inspect --format '{{.Id}}' "$image")
  test "$actual" = "$expected" || fail "$image identity does not match its release metadata"
}

build_config_sha256() {
  printf '%s\n' \
    "VITE_SUPABASE_URL=$VITE_SUPABASE_URL" \
    "VITE_SUPABASE_ANON_KEY=$VITE_SUPABASE_ANON_KEY" \
    "VITE_OPS_API_BASE_URL=$VITE_OPS_API_BASE_URL" \
    "OPS_MANAGEMENT_DOMAIN=$OPS_MANAGEMENT_DOMAIN" \
    "OPS_MANAGEMENT_PORT=$OPS_MANAGEMENT_PORT" |
    sha256sum | awk '{print $1}'
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
    if test "$attempts" -ge 90; then
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

dns_matches_expert_host() {
  domain=$1
  expert_ips=$(getent ahostsv4 bb.chinacedar.top | awk '{print $1}' | sort -u)
  domain_ips=$(getent ahostsv4 "$domain" | awk '{print $1}' | sort -u)
  test -n "$expert_ips"
  test -n "$domain_ips"
  for ip in $domain_ips; do
    printf '%s\n' "$expert_ips" | grep -Fxq "$ip" && return 0
  done
  return 1
}

test "$(id -u)" -eq 0
for command in awk curl docker getent grep python3 sha256sum; do
  command -v "$command" >/dev/null 2>&1 || fail "$command is required"
done
docker compose version >/dev/null
test -L "$APP_ROOT/candidate"
CANDIDATE=$(readlink -f "$APP_ROOT/candidate")
if test -n "$RELEASE_ID"; then
  case "$RELEASE_ID" in ""|*[!A-Za-z0-9._-]*) fail "release_id_invalid" ;; esac
  RELEASE_DIR=$APP_ROOT/releases/$RELEASE_ID
  test "$CANDIDATE" = "$RELEASE_DIR" || fail "release_is_not_the_staged_candidate"
else
  RELEASE_DIR=$CANDIDATE
  RELEASE_ID=$(basename "$RELEASE_DIR")
fi
test -f "$RELEASE_DIR/release.env"
test -f "$RELEASE_DIR/SHA256SUMS"
(
  cd "$RELEASE_DIR"
  sha256sum --check SHA256SUMS >/dev/null
)
test -f "$CONFIG_FILE"
test "$(stat -c %a "$CONFIG_FILE")" = "600"
test "$(stat -c %U "$CONFIG_FILE")" = "root"
test -f "$CADDYFILE"

set -a
. "$RELEASE_DIR/release.env"
set +a
: "${OPS_MANAGEMENT_IMAGE:?candidate image is missing}"
: "${OPS_MANAGEMENT_IMAGE_ID:?candidate image identity is missing}"
: "${OPS_MANAGEMENT_BUILD_CONFIG_SHA256:?candidate build configuration identity is missing}"
: "${OPS_MANAGEMENT_DOMAIN:?candidate domain is missing}"
OPS_MANAGEMENT_PORT=${OPS_MANAGEMENT_PORT:-8788}
STAGED_DOMAIN=$OPS_MANAGEMENT_DOMAIN
STAGED_PORT=$OPS_MANAGEMENT_PORT
STAGED_BUILD_CONFIG_SHA256=$OPS_MANAGEMENT_BUILD_CONFIG_SHA256
set -a
. "$CONFIG_FILE"
set +a
: "${VITE_SUPABASE_URL:?VITE_SUPABASE_URL is required}"
: "${VITE_SUPABASE_ANON_KEY:?VITE_SUPABASE_ANON_KEY is required}"
: "${VITE_OPS_API_BASE_URL:?VITE_OPS_API_BASE_URL is required}"
: "${OPS_MANAGEMENT_DOMAIN:?OPS_MANAGEMENT_DOMAIN is required}"
OPS_MANAGEMENT_PORT=${OPS_MANAGEMENT_PORT:-8788}
test "$OPS_MANAGEMENT_DOMAIN" = "$STAGED_DOMAIN" || fail "candidate_domain_does_not_match_production_config"
test "$OPS_MANAGEMENT_PORT" = "$STAGED_PORT" || fail "candidate_port_does_not_match_production_config"
CURRENT_BUILD_CONFIG_SHA256=$(build_config_sha256)
test "$CURRENT_BUILD_CONFIG_SHA256" = "$STAGED_BUILD_CONFIG_SHA256" || \
  fail "candidate_build_config_does_not_match_production_config"
dns_matches_expert_host "$OPS_MANAGEMENT_DOMAIN" || fail "management_domain_dns_not_ready"
assert_image_identity "$OPS_MANAGEMENT_IMAGE" "$OPS_MANAGEMENT_IMAGE_ID"

wait_for_service "$EXPERT_HEALTH" expert-collab
wait_for_service "$V9_HEALTH" dingdang-v9-gateway
EXPERT_CONTAINER_BEFORE=$(container_fingerprint "$EXPERT_CONTAINER")
CADDY_CONTAINER_BEFORE=$(container_fingerprint "$CADDY_CONTAINER")

install -d -m 0755 "$STATE_DIR"
CADDY_BACKUP=$STATE_DIR/Caddyfile.pre-$RELEASE_ID
cp -a "$CADDYFILE" "$CADDY_BACKUP"
# Persist the recovery point before any public route or container changes.
printf '%s\n' "$CADDY_BACKUP" >"$STATE_DIR/last-caddy-backup"
if test -L "$APP_ROOT/current"; then
  OLD_CURRENT=$(readlink -f "$APP_ROOT/current")
  ln -sfn "$OLD_CURRENT" "$APP_ROOT/previous"
fi

rollback_failed_activation() {
  status=$?
  trap - EXIT HUP INT TERM
  if test "$status" -eq 0; then
    return
  fi
  cp -a "$CADDY_BACKUP" "$CADDYFILE" || true
  docker exec ai-edge-caddy caddy validate --config /etc/caddy/Caddyfile || true
  docker exec ai-edge-caddy caddy reload --config /etc/caddy/Caddyfile || true
  if test -n "$OLD_CURRENT"; then
    ln -sfn "$OLD_CURRENT" "$APP_ROOT/current"
    OLD_IMAGE=$(sed -n 's/^OPS_MANAGEMENT_IMAGE=//p' "$OLD_CURRENT/release.env" | head -n 1)
    OLD_IMAGE_ID=$(sed -n 's/^OPS_MANAGEMENT_IMAGE_ID=//p' "$OLD_CURRENT/release.env" | head -n 1)
    if test -n "$OLD_IMAGE" && test -n "$OLD_IMAGE_ID" && \
        assert_image_identity "$OLD_IMAGE" "$OLD_IMAGE_ID"; then
      OLD_PORT=$(sed -n 's/^OPS_MANAGEMENT_PORT=//p' "$OLD_CURRENT/release.env" | head -n 1)
      OLD_PORT=${OLD_PORT:-8788}
      compose_release "$OLD_CURRENT" "$OLD_IMAGE" "$OLD_PORT" up -d --no-build --remove-orphans || true
    fi
  else
    compose_release "$RELEASE_DIR" "$OPS_MANAGEMENT_IMAGE" "$OPS_MANAGEMENT_PORT" down --remove-orphans || true
    test ! -L "$APP_ROOT/current" || unlink "$APP_ROOT/current"
  fi
  wait_for_service "$EXPERT_HEALTH" expert-collab || true
  wait_for_service "$V9_HEALTH" dingdang-v9-gateway || true
  exit "$status"
}
trap rollback_failed_activation EXIT HUP INT TERM

python3 "$RELEASE_DIR/deploy/merge_caddy.py" \
  "$CADDYFILE" "$RELEASE_DIR/deploy/Caddyfile.snippet.template" \
  "$OPS_MANAGEMENT_DOMAIN" "$OPS_MANAGEMENT_PORT"
docker exec ai-edge-caddy caddy validate --config /etc/caddy/Caddyfile
ln -sfn "$RELEASE_DIR" "$APP_ROOT/current"
compose_release "$RELEASE_DIR" "$OPS_MANAGEMENT_IMAGE" "$OPS_MANAGEMENT_PORT" up -d --no-build --remove-orphans
wait_for_service "http://127.0.0.1:$OPS_MANAGEMENT_PORT/health" dingdang-ops-management-web
docker exec ai-edge-caddy caddy reload --config /etc/caddy/Caddyfile
wait_for_service "https://$OPS_MANAGEMENT_DOMAIN/health" dingdang-ops-management-web
wait_for_service "$EXPERT_HEALTH" expert-collab
wait_for_service "$V9_HEALTH" dingdang-v9-gateway
assert_container_unchanged "$EXPERT_CONTAINER" "$EXPERT_CONTAINER_BEFORE"
assert_container_unchanged "$CADDY_CONTAINER" "$CADDY_CONTAINER_BEFORE"
OPS_MANAGEMENT_DOMAIN=$OPS_MANAGEMENT_DOMAIN \
OPS_MANAGEMENT_PORT=$OPS_MANAGEMENT_PORT \
  "$RELEASE_DIR/deploy/health-check.sh"

printf '%s\n' "$RELEASE_DIR" >"$STATE_DIR/current-release"
trap - EXIT HUP INT TERM
printf 'Activated dingdang-ops-management-web release %s\n' "$RELEASE_ID"
