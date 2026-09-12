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

readonly HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly CONFIG="$HERE/cliff.toml"

if [ ! -f "$CONFIG" ]; then
    echo "missing $CONFIG; refusing to fall back to git-cliff's default grouping" >&2
    exit 1
fi

exec git-cliff --config "$CONFIG" "$@"
