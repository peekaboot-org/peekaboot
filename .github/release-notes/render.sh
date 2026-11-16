#!/usr/bin/env bash
#
# Single entry point for every git-cliff invocation, so the config path is written once.
#
# git-cliff only warns and falls back to its own built-in configuration when --config names
# a file that does not exist, and still exits 0 - a typo here would quietly publish notes
# grouped git-cliff's way instead of ours. Fail loudly instead.
#
# Usage: render.sh [git-cliff args...]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly HERE
readonly CONFIG="$HERE/cliff.toml"

if [ ! -f "$CONFIG" ]; then
    echo "missing $CONFIG; refusing to fall back to git-cliff's default grouping" >&2
    exit 1
fi

# The notes begin with 1.3.0. The releases before it listed every commit and are left out.
readonly LAST_RELEASE_LEFT_OUT="1.2.0"

exec git-cliff --config "$CONFIG" "$@" -- "$LAST_RELEASE_LEFT_OUT..HEAD"
