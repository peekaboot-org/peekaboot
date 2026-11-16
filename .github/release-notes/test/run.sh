#!/usr/bin/env bash
#
# Checks the unreleased list against a fixture repo whose history is fixed, and the scripts
# that read the written notes against a fixture changelog.
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

echo "checking the --unreleased render (the draft and the push preview)"
"$HERE/../render.sh" --repository "$FIXTURE" --unreleased > "$WORK/unreleased.md"
diff -u "$HERE/expected-unreleased.md" "$WORK/unreleased.md"

echo "checking the bumped version"
bumped="$("$HERE/../render.sh" --repository "$FIXTURE" --bumped-version)"
if [ "$bumped" != "$BUMPED" ]; then
    echo "expected the untagged feat: to bump 1.2.0 to $BUMPED, got $bumped" >&2
    exit 1
fi

readonly CHANGELOG="$HERE/changelog.md"

echo "checking the section of a released version"
"$HERE/../section.sh" 1.1.0 "$CHANGELOG" > "$WORK/section.md"
diff -u "$HERE/expected-section-1.1.0.md" "$WORK/section.md"

echo "checking the section of a version written but not yet released"
"$HERE/../section.sh" 1.10.0 "$CHANGELOG" | grep -q "written and not yet released"

# 1.1 and 1.1.00 are prefixes or look-alikes of headings that exist; 9.9.9 has no heading;
# 1.0.5 has one with nothing under it.
for refused in 1.1 1.1.00 9.9.9 1.0.5; do
    echo "checking that section $refused is refused"
    if "$HERE/../section.sh" "$refused" "$CHANGELOG" > "$WORK/refused.md" 2> "$WORK/refused.err"; then
        echo "expected section.sh to fail for $refused, it printed:" >&2
        cat "$WORK/refused.md" >&2
        exit 1
    fi
    grep -q "$refused" "$WORK/refused.err"
done

echo "checking the stamp on an undated and on a dated heading"
cp "$CHANGELOG" "$WORK/stamped.md"
"$HERE/../stamp.sh" 1.10.0 2026-03-01 "$WORK/stamped.md"
"$HERE/../stamp.sh" 1.1.0 2026-03-02 "$WORK/stamped.md"
sed -e 's/^## 1\.10\.0$/## 1.10.0 - 2026-03-01/' -e 's/^## 1\.1\.0 - .*$/## 1.1.0 - 2026-03-02/' \
    "$CHANGELOG" > "$WORK/expected-stamped.md"
diff -u "$WORK/expected-stamped.md" "$WORK/stamped.md"

# 9.9.9 has no heading, 1.0.5 a heading with nothing under it.
for unwritten in 9.9.9 1.0.5; do
    echo "checking that $unwritten, which has no notes, is not stamped"
    if "$HERE/../stamp.sh" "$unwritten" 2026-03-03 "$WORK/stamped.md" 2> /dev/null; then
        echo "expected stamp.sh to fail for $unwritten" >&2
        exit 1
    fi
    diff -u "$WORK/expected-stamped.md" "$WORK/stamped.md"
done

echo "checking that a stamped section reaches the site data"
jq -Rs -f "$HERE/../site.jq" "$WORK/stamped.md" | jq -e '.[0].version == "1.10.0"' > /dev/null

echo "checking the site data"
jq -Rs -f "$HERE/../site.jq" "$CHANGELOG" > "$WORK/releases.json"
diff -u "$HERE/expected-releases.json" "$WORK/releases.json"

echo "all release-notes checks passed"
