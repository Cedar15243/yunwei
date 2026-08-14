#!/bin/sh
set -eu

EXPERT_HEALTH=${EXPERT_HEALTH_URL:-https://bb.chinacedar.top:2305/health}
V9_BASE=${V9_HEALTH_BASE_URL:-https://bb.chinacedar.top:2305/v9-ops}
# Public V9 paths are /v9-ops/health and /v9-ops/ready; V9_BASE may be overridden for a staged host.
CURL="curl --fail --silent --show-error --max-time 5"

$CURL "$EXPERT_HEALTH" >/dev/null
$CURL "$V9_BASE/health" >/dev/null
$CURL "$V9_BASE/ready" >/dev/null
printf 'expert, V9 liveness and V9 readiness are healthy\n'
