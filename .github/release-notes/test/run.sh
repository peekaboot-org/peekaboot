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
readonly UNKNOWN_SHA="deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
trap 'rm -rf "$WORK"' EXIT

"$HERE/fixture.sh" "$FIXTURE" > /dev/null

echo "checking the rendered notes"
"$HERE/../render.sh" --repository "$FIXTURE" > "$WORK/notes.md"
diff -u "$HERE/expected-notes.md" "$WORK/notes.md"

echo "checking the site data"
"$HERE/../render.sh" --repository "$FIXTURE" --context \
    | jq -f "$HERE/../site.jq" > "$WORK/releases.json"
diff -u "$HERE/expected-releases.json" "$WORK/releases.json"

# 1.1.0..1.2.0 is one conventional commit: a real pass, not an empty range.
echo "checking the commit-subject gate accepts a clean range"
"$HERE/../check-commit-subjects.sh" "$FIXTURE" 1.1.0 1.2.0

echo "checking the commit-subject gate rejects the fixture's second release"
if "$HERE/../check-commit-subjects.sh" "$FIXTURE" 1.0.0 1.1.0 > "$WORK/gate.out" 2>&1; then
    echo "expected the gate to fail on the non-conventional subject" >&2
    exit 1
fi
grep -q 'this subject is not conventional at all' "$WORK/gate.out"
# The `[release]` commit is in the same range and must not be reported.
if grep -q '\[release\]' "$WORK/gate.out"; then
    echo "the gate must exempt [release] commits" >&2
    exit 1
fi

# A range git cannot read must fail, not pass. Piping git log straight into the filters let
# `|| true` swallow its exit 128 and report a clean gate.
echo "checking the commit-subject gate fails on an unreadable range"
if "$HERE/../check-commit-subjects.sh" "$FIXTURE" 1.0.0 "$UNKNOWN_SHA" > "$WORK/range.out" 2>&1; then
    echo "expected the gate to fail on an unreadable range" >&2
    exit 1
fi
grep -q 'cannot read the commit range' "$WORK/range.out"

echo "all release-notes checks passed"
