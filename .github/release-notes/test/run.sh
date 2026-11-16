#!/usr/bin/env bash
#
# Checks the release-notes generator against a fixture repo whose history is fixed, so the
# expectations are golden files rather than counts that drift as dev grows.
#
# Needs `git-cliff` and `jq` on PATH.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
WORK="$(mktemp -d)"
readonly HERE WORK
readonly FIXTURE="$WORK/fixture"
# The fixture's one untagged commit is a feat:, so the next version off 1.5.0 is a minor bump.
readonly BUMPED="1.6.0"
trap 'rm -rf "$WORK"' EXIT

"$HERE/fixture.sh" "$FIXTURE" > /dev/null

echo "checking the rendered notes"
"$HERE/../render.sh" --repository "$FIXTURE" > "$WORK/notes.md"
diff -u "$HERE/expected-notes.md" "$WORK/notes.md"

echo "checking the site data"
"$HERE/../render.sh" --repository "$FIXTURE" --context \
    | jq -f "$HERE/../site.jq" > "$WORK/releases.json"
diff -u "$HERE/expected-releases.json" "$WORK/releases.json"

echo "checking the --latest render (the release body)"
"$HERE/../render.sh" --repository "$FIXTURE" --latest > "$WORK/latest.md"
diff -u "$HERE/expected-latest.md" "$WORK/latest.md"

echo "checking the --unreleased render (the draft and the push preview)"
"$HERE/../render.sh" --repository "$FIXTURE" --unreleased > "$WORK/unreleased.md"
diff -u "$HERE/expected-unreleased.md" "$WORK/unreleased.md"

echo "checking the bumped version"
bumped="$("$HERE/../render.sh" --repository "$FIXTURE" --bumped-version)"
if [ "$bumped" != "$BUMPED" ]; then
    echo "expected the untagged feat: to bump 1.5.0 to $BUMPED, got $bumped" >&2
    exit 1
fi

# The release renders its changelog before the tag exists, so --tag has to label the tip.
echo "checking the --tag render (the changelog the release commit carries)"
"$HERE/../render.sh" --repository "$FIXTURE" --tag "$BUMPED" > "$WORK/tagged.md"
grep -q "^## $BUMPED - [0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}$" "$WORK/tagged.md"
# Below the heading it is the plain render with the compare link closed at the tag, so the
# two lines that differ are normalised back rather than kept in a second golden file.
sed -e "1s/.*/## Unreleased/" -e "s|/1\.5\.0\.\.\.$BUMPED$|/1.5.0...dev|" \
    "$WORK/tagged.md" > "$WORK/tagged-as-unreleased.md"
diff -u "$HERE/expected-notes.md" "$WORK/tagged-as-unreleased.md"

# The fixture proves the cutoff on its own tags. This proves it on the real ones, where a
# shallow clone would lack the tag the range starts from.
echo "checking that the real history starts at 1.3.0"
readonly REPOSITORY="$HERE/../../.."
if [ "$(git -C "$REPOSITORY" rev-parse --is-shallow-repository)" = true ]; then
    echo "the real history is shallow; CI checks out depth 0" >&2
    exit 1
fi
oldest="$("$HERE/../render.sh" --repository "$REPOSITORY" --context \
    | jq -r '[.[] | select(.version != null)] | min_by(.timestamp) | .version')"
if [ "$oldest" != "1.3.0" ]; then
    echo "expected the oldest release in the notes to be 1.3.0, got $oldest" >&2
    exit 1
fi

echo "all release-notes checks passed"
