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
# The fixture's one untagged commit is a feat:, so the next version off 1.2.0 is a minor bump.
readonly BUMPED="1.3.0"
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
    echo "expected the untagged feat: to bump 1.2.0 to $BUMPED, got $bumped" >&2
    exit 1
fi

# The release renders its changelog before the tag exists, so --tag has to label the tip.
echo "checking the --tag render (the changelog the release commit carries)"
"$HERE/../render.sh" --repository "$FIXTURE" --tag "$BUMPED" > "$WORK/tagged.md"
grep -q "^## $BUMPED - [0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}$" "$WORK/tagged.md"
# Below the heading it is the plain render with the compare link closed at the tag, so the
# two lines that differ are normalised back rather than kept in a second golden file.
sed -e "1s/.*/## Unreleased/" -e "s|/1\.2\.0\.\.\.$BUMPED$|/1.2.0...dev|" \
    "$WORK/tagged.md" > "$WORK/tagged-as-unreleased.md"
diff -u "$HERE/expected-notes.md" "$WORK/tagged-as-unreleased.md"

# The per-sha overrides name real commits, so they are checked on this repository's own
# history rather than on the fixture. CI checks out the full history for the same reason.
echo "checking the per-sha overrides on the real history"
readonly REPOSITORY="$HERE/../../.."
if [ "$(git -C "$REPOSITORY" rev-parse --is-shallow-repository)" = true ]; then
    echo "the per-sha overrides need full history; CI checks out depth 0" >&2
    exit 1
fi
override_shas="$(sed -n 's/.*sha = "\([0-9a-f]*\)".*/\1/p' "$HERE/../cliff.toml")"
if [ -z "$override_shas" ]; then
    echo "found no sha = \"...\" override in cliff.toml; the extraction here no longer matches it" >&2
    exit 1
fi
while read -r sha; do
    if ! git -C "$REPOSITORY" cat-file -e "$sha^{commit}" 2> /dev/null; then
        echo "cliff.toml names a commit this history lacks: $sha; a rewrite needs the override updated" >&2
        exit 1
    fi
done <<< "$override_shas"
"$HERE/../render.sh" --repository "$REPOSITORY" --context > "$WORK/real.json"

expect_group() {
    local sha="$1" expected="$2" actual
    actual="$(jq -r --arg sha "$sha" '[.[].commits[] | select(.id == $sha)] | .[0].group' "$WORK/real.json")"
    if [ "$actual" != "$expected" ]; then
        echo "expected $sha in group \"$expected\", got \"$actual\"" >&2
        exit 1
    fi
}

expect_skipped() {
    local sha="$1" count
    count="$(jq --arg sha "$sha" '[.[].commits[] | select(.id == $sha)] | length' "$WORK/real.json")"
    if [ "$count" != 0 ]; then
        echo "expected $sha to be skipped, found it $count time(s)" >&2
        exit 1
    fi
}

expect_group ca362a2ec9ef976392c84fe7dc6fc986509289c3 "<!-- 08 -->Tests"
expect_group 76fc25377de62e302c41447826f85580ccae0d8d "<!-- 08 -->Tests"
expect_skipped f5449589da9d6b7187d462028897cf0139850236

echo "all release-notes checks passed"
