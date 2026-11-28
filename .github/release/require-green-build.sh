#!/usr/bin/env bash
#
# Fails unless the newest build-on-push run for a commit has completed successfully.
#
# The release builds and tests the tag's tree once, in release:prepare. This is the check
# that the commit also passed the gates and the Boot 4.1 and Gradle jobs only the push build
# runs. It is a script step and no branch rule: a rule on dev cannot coexist with a release
# that pushes with GITHUB_TOKEN.
#
# Needs `gh` and `jq`, and GH_TOKEN with `actions: read` outside a checkout of the repository.
#
# Usage: require-green-build.sh <sha>
set -euo pipefail

readonly SHA="${1:?usage: require-green-build.sh <sha>}"
readonly WAIT="wait for the build to finish or dispatch build-on-push on dev, then release again"

runs="$(gh run list --workflow build-on-push.yml --commit "$SHA" --limit 20 --json status,conclusion,url)"
newest="$(jq -c '.[0] // empty' <<< "$runs")"

if [ -z "$newest" ]; then
    echo "::error::no build-on-push run for ${SHA}; ${WAIT}" >&2
    exit 1
fi
if [ "$(jq -r '.status' <<< "$newest")" != "completed" ]; then
    echo "::error::the build-on-push run for ${SHA} has not finished ($(jq -r '.url' <<< "$newest")); ${WAIT}" >&2
    exit 1
fi
conclusion="$(jq -r '.conclusion' <<< "$newest")"
if [ "$conclusion" != "success" ]; then
    echo "::error::the build-on-push run for ${SHA} ended ${conclusion} ($(jq -r '.url' <<< "$newest")); fix dev and wait for a green build" >&2
    exit 1
fi
echo "build-on-push is green for ${SHA}"
