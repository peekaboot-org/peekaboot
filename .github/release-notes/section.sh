#!/usr/bin/env bash
#
# Prints the written notes of one version: what stands under its `## x.y.z` heading in the
# changelog, dated or not, up to the next version's heading.
#
# The release fails on a missing or empty section, because the notes are written by hand
# before a release and nothing else would notice that they were not.
#
# Usage: section.sh <version> [changelog]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly HERE
readonly VERSION="$1"
readonly CHANGELOG="${2:-$HERE/../../CHANGELOG.md}"

section="$(awk -v version="$VERSION" '
    /^## / { inside = ($2 == version); next }
    inside { print }
' "$CHANGELOG")"

if [ -z "${section//[[:space:]]/}" ]; then
    echo "$CHANGELOG has no notes under a \"## $VERSION\" heading; write them before releasing" >&2
    exit 1
fi

# The assignment dropped the trailing newlines; sed drops the blank lines after the heading.
printf '%s\n' "$section" | sed '/./,$!d'
