#!/usr/bin/env bash
#
# Fails if any non-merge commit subject in the range is not a conventional commit.
#
# These subjects do more than fill the release notes: conventional-commits-version-policy
# derives the release version from them, so a subject it cannot parse is a change it cannot
# weigh when choosing the next version.
#
# Usage: check-commit-subjects.sh <repo> <before-sha> <after-sha>
set -euo pipefail

readonly ZERO="0000000000000000000000000000000000000000"
readonly TYPES='feat|fix|perf|refactor|docs|test|build|ci|chore|style|revert'

repo="$1"
base="$2"
head="$3"

# A push that creates a branch reports the all-zero SHA as its `before`, and a force-push
# reports a tip a fresh clone need not have. Both mean the same thing here: there is no
# usable previous state, so compare against the default branch.
if [ "$base" = "$ZERO" ] \
    || ! git -C "$repo" rev-parse --verify --quiet "$base^{commit}" > /dev/null; then
    base="origin/dev"
fi

# Captured separately so a failure to read the range cannot reach the filters below, where
# `|| true` would turn it into a silent pass.
if ! subjects="$(git -C "$repo" log --no-merges --format='%s' "$base..$head")"; then
    echo "::error::cannot read the commit range $base..$head"
    exit 1
fi

if [ -z "$subjects" ]; then
    echo "no non-merge commits in $base..$head"
    exit 0
fi

offenders="$(printf '%s\n' "$subjects" \
    | grep -vE "^($TYPES)(\([^)]+\))?!?: .+" \
    | grep -v '^\[release\]' || true)"

if [ -n "$offenders" ]; then
    echo "::error::these commit subjects are not conventional commits:"
    printf '%s\n' "$offenders" | sed 's/^/  /'
    exit 1
fi

echo "all commit subjects in $base..$head are conventional"
