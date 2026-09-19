#!/usr/bin/env bash
#
# Checks the snapshot version derivation against a fixture pom, so the expectations stay
# fixed while the project's own version moves.
set -euo pipefail

readonly HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
readonly SCRIPT="$HERE/../branch-version.sh"
readonly WORK="$(mktemp -d)"
readonly POM="$WORK/pom.xml"
trap 'rm -rf "$WORK"' EXIT

write_pom() {
    cat > "$POM" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <groupId>org.peekaboot</groupId>
    <artifactId>peekaboot-parent</artifactId>
    <version>$1</version>
</project>
POM
}

expect_version() {
    local branch="$1" expected="$2" actual
    actual="$("$SCRIPT" "$POM" "$branch")"
    if [ "$actual" != "$expected" ]; then
        echo "branch '$branch': expected $expected, got $actual" >&2
        exit 1
    fi
}

expect_failure() {
    local description="$1" message="$2"
    shift 2
    if "$SCRIPT" "$@" > "$WORK/out" 2>&1; then
        echo "expected a failure: $description" >&2
        exit 1
    fi
    grep -q "$message" "$WORK/out"
}

write_pom "0.2.1-SNAPSHOT"

echo "checking dev keeps the coordinate consumers depend on"
expect_version dev "0.2.1-SNAPSHOT"

echo "checking a branch name becomes part of the version"
expect_version feat/async-instrumentation "0.2.1-feat-async-instrumentation-SNAPSHOT"

# Dependabot's branch names carry dots and slashes, and a run of them must collapse to one
# separator rather than leave a version with an empty segment.
echo "checking every character Maven will not take becomes one separator"
expect_version "dependabot/maven/org.foo//bar-1.2.3" \
    "0.2.1-dependabot-maven-org-foo-bar-1-2-3-SNAPSHOT"

echo "checking a branch name that is all separators is refused"
expect_failure "a branch that slugs to nothing" "no usable version" "$POM" "///"

# Fail closed. Deriving a branch version from a release would publish a version that is not
# a snapshot to the snapshot repository, under a coordinate Central will never expire.
echo "checking a release version in the pom is refused"
write_pom "0.2.0"
expect_failure "a release version" "not a snapshot" "$POM" feat/thing

echo "checking an unreadable pom is refused"
expect_failure "a missing pom" "cannot read" "$WORK/absent.xml" dev

echo "all snapshot-publish checks passed"
