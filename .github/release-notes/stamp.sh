#!/usr/bin/env bash
#
# Writes the release date into a version's changelog heading: `## x.y.z` becomes
# `## x.y.z - <date>`. The date in the heading is what marks the section as released.
#
# Usage: stamp.sh <version> <date> [changelog]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly HERE
readonly VERSION="$1"
readonly DATE="$2"
readonly CHANGELOG="${3:-$HERE/../../CHANGELOG.md}"

# Refuses a version without written notes, so the heading below is known to exist.
"$HERE/section.sh" "$VERSION" "$CHANGELOG" > /dev/null

sed -i -E "s|^## ${VERSION//./\\.}( - .*)?$|## $VERSION - $DATE|" "$CHANGELOG"
