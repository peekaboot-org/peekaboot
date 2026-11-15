#!/usr/bin/env bash
#
# Builds a throwaway origin for the release's pushes: a bare repository with dev and main on
# one base commit, which holds the three files a release commit rewrites.
#
# Usage: fixture.sh <dir>          builds <dir>/origin.git
#        fixture.sh <dir> <clone>  clones it into the directory <clone>
set -euo pipefail

readonly DIR="$1" CLONE="${2:-}"
readonly ORIGIN="$DIR/origin.git"

configure() {
    local repo="$1"
    git -C "$repo" config user.email "fixture@example.invalid"
    git -C "$repo" config user.name "fixture"
    # Anyone with signing on by default would otherwise have every fixture commit and tag try
    # to sign with no key for this address, hanging on a passphrase prompt or failing outright.
    git -C "$repo" config commit.gpgsign false
    git -C "$repo" config tag.gpgsign false
}

if [ -n "$CLONE" ]; then
    git clone -q "$ORIGIN" "$CLONE"
    configure "$CLONE"
    echo "$CLONE ready"
    exit
fi

rm -rf "$DIR"
mkdir -p "$DIR"
# dev is the default branch, as on the real origin, so a clone checks it out.
git init -q --bare -b dev "$ORIGIN"

readonly SEED="$DIR/seed"
git init -q -b dev "$SEED"
configure "$SEED"
echo "<version>1.2.1-SNAPSHOT</version>" > "$SEED/pom.xml"
echo "## 1.2.0" > "$SEED/CHANGELOG.md"
echo "<version>1.2.0</version>" > "$SEED/README.md"
git -C "$SEED" add pom.xml CHANGELOG.md README.md
git -C "$SEED" commit -q -m "feat: add the base"
git -C "$SEED" push -q "$ORIGIN" dev dev:main
rm -rf "$SEED"

echo "$ORIGIN ready"
