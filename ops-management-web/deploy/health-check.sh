#!/bin/sh
set -eu

OPS_MANAGEMENT_DOMAIN=${OPS_MANAGEMENT_DOMAIN:?OPS_MANAGEMENT_DOMAIN is required}
OPS_MANAGEMENT_PORT=${OPS_MANAGEMENT_PORT:-8788}
EXPERT_HEALTH=${EXPERT_HEALTH_URL:-https://bb.chinacedar.top:2305/health}
V9_HEALTH=${V9_HEALTH_URL:-https://bb.chinacedar.top:2305/v9-ops/health}
CURL="curl --fail --silent --show-error --max-time 8"

validate_service_body() {
  expected=$1
  python3 -c 'import json, sys
payload = json.load(sys.stdin)
raise SystemExit(0 if payload.get("ok") is True and payload.get("service") == sys.argv[1] else 1)' \
    "$expected"
}

require_service() {
  url=$1
  service=$2
  body=$($CURL "$url")
  printf '%s' "$body" | validate_service_body "$service"
}

require_service "http://127.0.0.1:${OPS_MANAGEMENT_PORT:-8788}/health" \
  dingdang-ops-management-web
require_service "https://${OPS_MANAGEMENT_DOMAIN}/health" \
  dingdang-ops-management-web
require_service "$EXPERT_HEALTH" expert-collab
require_service "$V9_HEALTH" dingdang-v9-gateway

printf 'management Web, expert collaboration and V9 gateway are healthy\n'
