#!/usr/bin/env bash
#
# Checks await-release.sh against the real repo1: 1.0.0 is published and immutable there,
# 0.0.0 never will be.
set -euo pipefail

readonly HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "checking a published version passes"
AWAIT_TIMEOUT_SECONDS=0 "$HERE/../await-release.sh" 1.0.0 > "$WORK/published.out"
diff -u <(echo "1.0.0 is on Maven Central") "$WORK/published.out"

echo "checking a missing version times out"
if AWAIT_TIMEOUT_SECONDS=0 "$HERE/../await-release.sh" 0.0.0 2> "$WORK/missing.err"; then
    echo "await-release.sh passed for a version that is not on Central" >&2
    exit 1
fi
diff -u <(echo "::error::0.0.0 is not on Maven Central after 0s; check its deployment at https://central.sonatype.com/publishing/deployments") \
    "$WORK/missing.err"
