#!/usr/bin/env bash
#
# Checks the release-notes generator against a fixture repo whose history is fixed, so the
# expectations are golden files rather than counts that drift as dev grows.
#
# Needs `git-cliff` and `jq` on PATH.
set -euo pipefail

readonly HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly WORK="$(mktemp -d)"
readonly FIXTURE="$WORK/fixture"
trap 'rm -rf "$WORK"' EXIT

"$HERE/fixture.sh" "$FIXTURE" > /dev/null

echo "checking the rendered notes"
"$HERE/../render.sh" --repository "$FIXTURE" > "$WORK/notes.md"
diff -u "$HERE/expected-notes.md" "$WORK/notes.md"

echo "checking the site data"
"$HERE/../render.sh" --repository "$FIXTURE" --context \
    | jq -f "$HERE/../site.jq" > "$WORK/releases.json"
diff -u "$HERE/expected-releases.json" "$WORK/releases.json"

echo "all release-notes checks passed"
