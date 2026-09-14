#!/usr/bin/env bash
#
# Fails if any non-merge commit subject in the range is not a conventional commit.
#
# These subjects do more than fill the release notes: git-cliff derives the release version
# from them too, so a subject it cannot parse is a change it cannot weigh when choosing the
# next version.
#
# Usage: check-commit-subjects.sh <repo> <before-sha> <after-sha>
set -euo pipefail

readonly ZERO="0000000000000000000000000000000000000000"
readonly TYPES='feat|fix|perf|refactor|docs|test|build|ci|chore|style|revert'

repo="$1"
base="$2"
head="$3"

resolves() {
    git -C "$repo" rev-parse --verify --quiet "$1^{commit}" > /dev/null
}

# A push that creates a branch reports the all-zero SHA as its `before`; a force-push
# reports a tip a fresh clone need not have. Neither leaves a usable previous state, so
# compare against the default branch instead.
rewritten=false
if [ "$base" = "$ZERO" ]; then
    base="origin/dev"
elif ! resolves "$base"; then
    base="origin/dev"
    rewritten=true
fi

# A force-push to the default branch lands here with the fallback base equal to the pushed
# head, an empty range that would report success without inspecting anything. The commits
# since the last release are the range that matters there. A brand-new branch pointing at
# the default branch's tip is a genuinely empty range and keeps it.
if [ "$rewritten" = true ] && resolves "$base" \
    && [ "$(git -C "$repo" rev-parse "$base")" = "$(git -C "$repo" rev-parse "$head")" ]; then
    base="$(git -C "$repo" describe --tags --abbrev=0 "$head")"
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
