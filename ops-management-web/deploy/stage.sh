#!/bin/sh
set -eu

APP_ROOT=/srv/dingdang-ops-management-web
CONFIG_FILE=${OPS_MANAGEMENT_ENV_FILE:-/etc/dingdang-ops-management-web.env}
EXPERT_HEALTH=https://bb.chinacedar.top:2305/health
V9_HEALTH=https://bb.chinacedar.top:2305/v9-ops/health
EXPERT_CONTAINER=dingdang-expert-collab
CADDY_CONTAINER=ai-edge-caddy
SOURCE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RELEASE_ID=${1:-$(date -u +%Y%m%dT%H%M%SZ)}
RELEASE_DIR=$APP_ROOT/releases/$RELEASE_ID
STAGING_DIR=$APP_ROOT/.staging-$RELEASE_ID
STATE_DIR=$APP_ROOT/deploy-state
IMAGE=dingdang-ops-management-web:$RELEASE_ID
STAGE_CONTAINER=dingdang-ops-management-stage-$RELEASE_ID
EXPERT_CONTAINER_BEFORE=
CADDY_CONTAINER_BEFORE=
OLD_CANDIDATE=
OLD_CANDIDATE_RECORD=
RELEASE_CREATED=false
CANDIDATE_CHANGED=false

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
  test "$actual" = "$expected" || fail "$container changed during management Web staging"
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

validate_browser_supabase_config() {
  case "$VITE_SUPABASE_ANON_KEY" in
    sb_publishable_*|sb_anon_*) ;;
    eyJ*)
      printf '%s' "$VITE_SUPABASE_ANON_KEY" | python3 -c 'import base64, json, sys
token = sys.stdin.read().strip().split(".")
if len(token) != 3:
    raise SystemExit(1)
payload = token[1] + "=" * (-len(token[1]) % 4)
decoded = json.loads(base64.urlsafe_b64decode(payload.encode("ascii")))
raise SystemExit(0 if decoded.get("role") == "anon" else 1)' ||
        fail "supabase_browser_key_invalid"
      ;;
    *) fail "supabase_browser_key_invalid" ;;
  esac
  curl --fail --silent --show-error --max-time 10 \
    --header "apikey: $VITE_SUPABASE_ANON_KEY" \
    "$VITE_SUPABASE_URL/auth/v1/settings" >/dev/null ||
    fail "supabase_browser_configuration_unreachable"
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

wait_for_container_service() {
  container=$1
  service=$2
  attempts=0
  until body=$(docker exec "$container" wget --quiet --output-document=- \
      http://127.0.0.1:8080/health 2>/dev/null) && \
      printf '%s' "$body" | validate_service_body "$service"; do
    attempts=$((attempts + 1))
    if test "$attempts" -ge 45; then
      docker logs "$container" >&2 || true
      return 1
    fi
    sleep 1
  done
}

case "$RELEASE_ID" in
  ""|*[!A-Za-z0-9._-]*) fail "release_id_invalid" ;;
esac
test "$(id -u)" -eq 0
for command in awk curl docker install python3 sha256sum; do
  command -v "$command" >/dev/null 2>&1 || fail "$command is required"
done
test -f "$CONFIG_FILE"
test "$(stat -c %a "$CONFIG_FILE")" = "600"
test "$(stat -c %U "$CONFIG_FILE")" = "root"
test -f "$SOURCE_ROOT/Dockerfile"
test -f "$SOURCE_ROOT/docker-compose.cloud.yml"
test -f "$SOURCE_ROOT/nginx.conf"
test -f "$SOURCE_ROOT/deploy/Caddyfile.snippet.template"
test -f "$SOURCE_ROOT/deploy/merge_caddy.py"
test -f "$SOURCE_ROOT/deploy/health-check.sh"
test -f "$SOURCE_ROOT/deploy/activate.sh"
test -f "$SOURCE_ROOT/deploy/rollback.sh"
test ! -e "$RELEASE_DIR"
test ! -e "$STAGING_DIR"
if docker image inspect "$IMAGE" >/dev/null 2>&1; then
  fail "$IMAGE already exists"
fi
if docker inspect "$STAGE_CONTAINER" >/dev/null 2>&1; then
  fail "$STAGE_CONTAINER already exists"
fi

set -a
. "$CONFIG_FILE"
set +a
: "${VITE_SUPABASE_URL:?VITE_SUPABASE_URL is required}"
: "${VITE_SUPABASE_ANON_KEY:?VITE_SUPABASE_ANON_KEY is required}"
: "${VITE_OPS_API_BASE_URL:?VITE_OPS_API_BASE_URL is required}"
: "${OPS_MANAGEMENT_DOMAIN:?OPS_MANAGEMENT_DOMAIN is required}"
OPS_MANAGEMENT_PORT=${OPS_MANAGEMENT_PORT:-8788}

case "$VITE_SUPABASE_URL" in https://*) ;; *) fail "VITE_SUPABASE_URL must use HTTPS" ;; esac
case "$VITE_OPS_API_BASE_URL" in https://*) ;; *) fail "VITE_OPS_API_BASE_URL must use HTTPS" ;; esac
case "$VITE_SUPABASE_URL$VITE_SUPABASE_ANON_KEY$VITE_OPS_API_BASE_URL" in
  *your-project*|*replace-with*) fail "production management Web configuration is incomplete" ;;
esac
validate_browser_supabase_config
case "$OPS_MANAGEMENT_DOMAIN" in
  bb.chinacedar.top|bb.chinacedar.top.|localhost|ops.example.com) \
    fail "management Web requires a dedicated production domain" ;;
esac
printf '%s' "$OPS_MANAGEMENT_DOMAIN" | grep -Eq \
  '^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$' || fail "OPS_MANAGEMENT_DOMAIN is invalid"
case "$OPS_MANAGEMENT_PORT" in ""|*[!0-9]*) fail "OPS_MANAGEMENT_PORT is invalid" ;; esac
test "$OPS_MANAGEMENT_PORT" -ge 1
test "$OPS_MANAGEMENT_PORT" -le 65535
test "$OPS_MANAGEMENT_PORT" -ne 8787
test "$OPS_MANAGEMENT_PORT" -ne 8790

wait_for_service "$EXPERT_HEALTH" expert-collab
wait_for_service "$V9_HEALTH" dingdang-v9-gateway
EXPERT_CONTAINER_BEFORE=$(container_fingerprint "$EXPERT_CONTAINER")
CADDY_CONTAINER_BEFORE=$(container_fingerprint "$CADDY_CONTAINER")
if test -L "$APP_ROOT/candidate"; then
  OLD_CANDIDATE=$(readlink -f "$APP_ROOT/candidate")
fi
if test -f "$STATE_DIR/candidate-release"; then
  OLD_CANDIDATE_RECORD=$(cat "$STATE_DIR/candidate-release")
fi

install -d -m 0755 "$APP_ROOT/releases" "$STATE_DIR" "$STAGING_DIR"
IMAGE_CREATED=false
cleanup_stage() {
  status=$?
  trap - EXIT HUP INT TERM
  docker rm --force "$STAGE_CONTAINER" >/dev/null 2>&1 || true
  if test "$status" -ne 0; then
    rm -rf "$STAGING_DIR"
    if test "$CANDIDATE_CHANGED" = true; then
      if test -n "$OLD_CANDIDATE"; then
        ln -sfn "$OLD_CANDIDATE" "$APP_ROOT/candidate"
      elif test -L "$APP_ROOT/candidate"; then
        unlink "$APP_ROOT/candidate"
      fi
      if test -n "$OLD_CANDIDATE_RECORD"; then
        printf '%s\n' "$OLD_CANDIDATE_RECORD" >"$STATE_DIR/candidate-release"
      else
        rm -f "$STATE_DIR/candidate-release"
      fi
    fi
    if test "$RELEASE_CREATED" = true; then
      chmod -R u+w "$RELEASE_DIR" >/dev/null 2>&1 || true
      rm -rf "$RELEASE_DIR"
    fi
    if test "$IMAGE_CREATED" = true; then
      docker image rm "$IMAGE" >/dev/null 2>&1 || true
    fi
  fi
  exit "$status"
}
trap cleanup_stage EXIT HUP INT TERM

for file in Dockerfile nginx.conf docker-compose.cloud.yml package.json package-lock.json \
  vite.config.ts tsconfig.json index.html .dockerignore; do
  test -f "$SOURCE_ROOT/$file"
  install -m 0644 "$SOURCE_ROOT/$file" "$STAGING_DIR/$file"
done
cp -a "$SOURCE_ROOT/src" "$STAGING_DIR/src"
if test -d "$SOURCE_ROOT/public"; then
  cp -a "$SOURCE_ROOT/public" "$STAGING_DIR/public"
fi
install -d -m 0755 "$STAGING_DIR/deploy"
for file in Caddyfile.snippet.template merge_caddy.py health-check.sh rollback.sh \
  install.sh stage.sh activate.sh; do
  test -f "$SOURCE_ROOT/deploy/$file"
  mode=0644
  case "$file" in *.sh) mode=0755 ;; esac
  install -m "$mode" "$SOURCE_ROOT/deploy/$file" "$STAGING_DIR/deploy/$file"
done

docker build --pull \
  --label "com.huafang.product=dingdang-ops-management-web" \
  --label "com.huafang.release=$RELEASE_ID" \
  --build-arg "VITE_SUPABASE_URL=$VITE_SUPABASE_URL" \
  --build-arg "VITE_SUPABASE_ANON_KEY=$VITE_SUPABASE_ANON_KEY" \
  --build-arg "VITE_OPS_API_BASE_URL=$VITE_OPS_API_BASE_URL" \
  --tag "$IMAGE" "$STAGING_DIR"
IMAGE_CREATED=true
IMAGE_ID=$(docker image inspect --format '{{.Id}}' "$IMAGE")
test -n "$IMAGE_ID"
assert_image_identity "$IMAGE" "$IMAGE_ID"

docker run --detach --name "$STAGE_CONTAINER" \
  --read-only \
  --security-opt no-new-privileges:true \
  --cap-drop ALL \
  --cap-add CHOWN \
  --cap-add SETGID \
  --cap-add SETUID \
  --tmpfs /var/cache/nginx:size=16m,mode=0755 \
  --tmpfs /var/run:size=1m,mode=0755 \
  --tmpfs /tmp:size=8m,mode=1777 \
  --entrypoint nginx \
  "$IMAGE" -g "daemon off;" >/dev/null
wait_for_container_service "$STAGE_CONTAINER" dingdang-ops-management-web
docker rm --force "$STAGE_CONTAINER" >/dev/null

BUILD_CONFIG_SHA256=$(build_config_sha256)
test -n "$BUILD_CONFIG_SHA256"
printf '%s\n' \
  "OPS_MANAGEMENT_IMAGE=$IMAGE" \
  "OPS_MANAGEMENT_IMAGE_ID=$IMAGE_ID" \
  "OPS_MANAGEMENT_BUILD_CONFIG_SHA256=$BUILD_CONFIG_SHA256" \
  "OPS_MANAGEMENT_DOMAIN=$OPS_MANAGEMENT_DOMAIN" \
  "OPS_MANAGEMENT_PORT=$OPS_MANAGEMENT_PORT" \
  >"$STAGING_DIR/release.env"
(
  cd "$STAGING_DIR"
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum >SHA256SUMS
)
chmod -R a-w "$STAGING_DIR"
mv "$STAGING_DIR" "$RELEASE_DIR"
RELEASE_CREATED=true
ln -sfn "$RELEASE_DIR" "$APP_ROOT/candidate"
CANDIDATE_CHANGED=true
printf '%s\n' "$RELEASE_DIR" >"$STATE_DIR/candidate-release"

wait_for_service "$EXPERT_HEALTH" expert-collab
wait_for_service "$V9_HEALTH" dingdang-v9-gateway
assert_container_unchanged "$EXPERT_CONTAINER" "$EXPERT_CONTAINER_BEFORE"
assert_container_unchanged "$CADDY_CONTAINER" "$CADDY_CONTAINER_BEFORE"

trap - EXIT HUP INT TERM
printf 'Staged dingdang-ops-management-web release %s\n' "$RELEASE_ID"
