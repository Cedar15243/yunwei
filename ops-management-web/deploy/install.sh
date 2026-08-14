#!/bin/sh
set -eu

SOURCE_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RELEASE_ID=${1:-$(date -u +%Y%m%dT%H%M%SZ)}

"$SOURCE_ROOT/deploy/stage.sh" "$RELEASE_ID"
"$SOURCE_ROOT/deploy/activate.sh" "$RELEASE_ID"
