#!/usr/bin/env bash
#
# Checks publish-refs.sh against fixture repositories, and require-green-build.sh against a gh
# that answers from a file, so the order in which a release
# becomes public is pinned before a release depends on it. Every case starts from a fresh
# origin, a runner clone that holds a prepared release, and a contributor clone on the base.
#
# Needs `git` and `jq` on PATH.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
WORK="$(mktemp -d)"
readonly HERE WORK
readonly FIXTURE="$WORK/fixture"
readonly ORIGIN="$FIXTURE/origin.git"
readonly RUNNER="$FIXTURE/runner"
readonly CONTRIBUTOR="$FIXTURE/contributor"
readonly RECOVERY="$FIXTURE/recovery"
readonly PUBLISH="$HERE/../publish-refs.sh"
readonly VERSION="1.3.0"
readonly USAGE="usage: publish-refs.sh tag|branches <version>"
readonly NOT_THIS_ONE="::error::origin already has a tag $VERSION that is not this one"
readonly NOT_AFTER_TAG="::error::HEAD is not a commit after tag $VERSION"
readonly DEV_NOT_PUSHED="::error::dev moved since $VERSION was prepared; main is on the release, dev was not pushed. See BUILD.md, Recovering a release"
trap 'rm -rf "$WORK"' EXIT

clone() {
    "$HERE/fixture.sh" "$FIXTURE" "$1" > /dev/null
}

commit_next_development_version() {
    local repo="$1" version="$2"
    echo "<version>${version%.*}.$((${version##*.} + 1))-SNAPSHOT</version>" > "$repo/pom.xml"
    git -C "$repo" commit -q -a -m "[release] prepare for next development iteration"
}

# The state release:prepare leaves with pushChanges=false: the release commit, its annotated
# tag and the next development version on top, none of them pushed.
prepare_release() {
    local repo="$1" version="$2"
    echo "<version>$version</version>" > "$repo/pom.xml"
    echo "## $version" > "$repo/CHANGELOG.md"
    echo "<version>$version</version>" > "$repo/README.md"
    git -C "$repo" commit -q -a -m "[release] set version to $version"
    git -C "$repo" tag -a "$version" -m "[release] copy for tag $version"
    commit_next_development_version "$repo" "$version"
}

prepared_fixture() {
    "$HERE/fixture.sh" "$FIXTURE" > /dev/null
    clone "$RUNNER"
    clone "$CONTRIBUTOR"
    prepare_release "$RUNNER" "$VERSION"
    BASE="$(git -C "$ORIGIN" rev-parse refs/heads/dev)"
    TAG="$(git -C "$RUNNER" rev-parse "refs/tags/$VERSION")"
    RELEASE="$(git -C "$RUNNER" rev-parse "refs/tags/$VERSION^{commit}")"
    NEXT="$(git -C "$RUNNER" rev-parse HEAD)"
}

# Someone else's commit on dev: in a file of its own, so it merges without a conflict.
push_contribution() {
    git -C "$CONTRIBUTOR" pull -q --ff-only origin dev
    echo "a contribution" >> "$CONTRIBUTOR/contributed.txt"
    git -C "$CONTRIBUTOR" add contributed.txt
    git -C "$CONTRIBUTOR" commit -q -m "feat: add a contribution"
    git -C "$CONTRIBUTOR" push -q origin HEAD:refs/heads/dev
    CONTRIBUTION="$(git -C "$CONTRIBUTOR" rev-parse HEAD)"
}

push_another_tag() {
    git -C "$CONTRIBUTOR" tag -a "$VERSION" -m "another $VERSION"
    git -C "$CONTRIBUTOR" push -q origin "refs/tags/$VERSION"
}

# stderr is left alone: the script writes none when it succeeds, and what it writes when it
# fails is what explains the case.
publish() {
    local repo="$1" mode="$2"
    (cd "$repo" && "$PUBLISH" "$mode" "$VERSION") > "$WORK/out"
}

refused() {
    local repo="$1" mode="$2"
    if (cd "$repo" && "$PUBLISH" "$mode" "$VERSION") > "$WORK/out" 2> "$WORK/err"; then
        echo "publish-refs.sh $mode passed where it has to refuse" >&2
        exit 1
    fi
}

expect_refused() {
    local repo="$1" mode="$2" message="$3"
    refused "$repo" "$mode"
    diff -u <(echo "$message") "$WORK/err"
}

expect_usage() {
    local status=0
    "$PUBLISH" "$@" > "$WORK/out" 2> "$WORK/err" || status=$?
    if [ "$status" != 2 ]; then
        echo "expected publish-refs.sh $* to exit 2, got $status" >&2
        exit 1
    fi
    diff -u <(echo "$USAGE") "$WORK/err"
}

expect_origin() {
    local ref="$1" expected="$2" actual
    actual="$(git -C "$ORIGIN" rev-parse "$ref")"
    if [ "$actual" != "$expected" ]; then
        echo "expected origin's $ref on $expected, got $actual" >&2
        exit 1
    fi
}

remember_origin() {
    git ls-remote "$ORIGIN" > "$WORK/refs"
}

expect_origin_unchanged() {
    git ls-remote "$ORIGIN" | diff -u "$WORK/refs" -
}

echo "checking a tag push publishes the tag alone"
prepared_fixture
publish "$RUNNER" tag
expect_origin "refs/tags/$VERSION" "$TAG"
expect_origin refs/heads/dev "$BASE"
expect_origin refs/heads/main "$BASE"

echo "checking a second tag push changes nothing"
prepared_fixture
publish "$RUNNER" tag
remember_origin
publish "$RUNNER" tag
diff -u <(echo "tag $VERSION is already on origin") "$WORK/out"
expect_origin_unchanged

echo "checking a tag that is not this one is refused"
prepared_fixture
push_another_tag
remember_origin
expect_refused "$RUNNER" tag "$NOT_THIS_ONE"
expect_origin_unchanged

echo "checking dev moved before the tag push"
prepared_fixture
push_contribution
remember_origin
expect_refused "$RUNNER" tag \
    "::error::dev moved since $VERSION was prepared; nothing was pushed, dispatch again"
expect_origin_unchanged

echo "checking a tag that is not on the commit before HEAD is refused"
prepared_fixture
git -C "$RUNNER" tag -f -a "$VERSION" -m "[release] copy for tag $VERSION" HEAD > /dev/null
remember_origin
expect_refused "$RUNNER" tag "::error::tag $VERSION is not on the commit before HEAD"
expect_origin_unchanged

echo "checking branches follow the tag"
prepared_fixture
publish "$RUNNER" tag
publish "$RUNNER" branches
expect_origin refs/heads/main "$RELEASE"
expect_origin refs/heads/dev "$NEXT"
expect_origin "refs/tags/$VERSION" "$TAG"

echo "checking a second branches run changes nothing"
prepared_fixture
publish "$RUNNER" tag
publish "$RUNNER" branches
remember_origin
publish "$RUNNER" branches
expect_origin_unchanged

echo "checking branches without the tag on origin are refused"
prepared_fixture
remember_origin
expect_refused "$RUNNER" branches "::error::origin has no tag $VERSION; the tag is pushed first"
expect_origin_unchanged

echo "checking branches against a tag that is not this one are refused"
prepared_fixture
push_another_tag
remember_origin
expect_refused "$RUNNER" branches "$NOT_THIS_ONE"
expect_origin_unchanged

echo "checking dev moved after the tag push"
prepared_fixture
publish "$RUNNER" tag
push_contribution
expect_refused "$RUNNER" branches "$DEV_NOT_PUSHED"
expect_origin refs/heads/main "$RELEASE"
expect_origin refs/heads/dev "$CONTRIBUTION"

# The fetch saw dev where it was and the push lost the race: origin's update hook stands in
# for the commit that landed in between. git reports its rejection first, the script last.
echo "checking a dev push that origin rejects is reported"
prepared_fixture
publish "$RUNNER" tag
cat > "$ORIGIN/hooks/update" << 'HOOK'
#!/bin/sh
[ "$1" != refs/heads/dev ]
HOOK
chmod +x "$ORIGIN/hooks/update"
# A developer's global core.hooksPath would otherwise send origin past its own hook.
git -C "$ORIGIN" config core.hooksPath "$ORIGIN/hooks"
refused "$RUNNER" branches
diff -u <(echo "$DEV_NOT_PUSHED") <(tail -n 1 "$WORK/err")
expect_origin refs/heads/main "$RELEASE"
expect_origin refs/heads/dev "$BASE"

echo "checking a run that stopped after main finishes"
prepared_fixture
publish "$RUNNER" tag
git -C "$RUNNER" push -q origin "$RELEASE:refs/heads/main"
publish "$RUNNER" branches
expect_origin refs/heads/main "$RELEASE"
expect_origin refs/heads/dev "$NEXT"

echo "checking a later commit on dev leaves a finished release alone"
prepared_fixture
publish "$RUNNER" tag
publish "$RUNNER" branches
push_contribution
remember_origin
publish "$RUNNER" branches
expect_origin_unchanged

# A later release has moved main on by the time an older one is finished by hand.
echo "checking a main ahead of the tag is left alone"
prepared_fixture
publish "$RUNNER" tag
publish "$RUNNER" branches
git -C "$RUNNER" push -q origin "$NEXT:refs/heads/main"
remember_origin
publish "$RUNNER" branches
expect_origin_unchanged

echo "checking a rebuilt next-development commit publishes from a fresh clone"
prepared_fixture
publish "$RUNNER" tag
clone "$RECOVERY"
git -C "$RECOVERY" merge -q --ff-only "$VERSION^{commit}"
commit_next_development_version "$RECOVERY" "$VERSION"
publish "$RECOVERY" branches
expect_origin refs/heads/main "$RELEASE"
expect_origin refs/heads/dev "$(git -C "$RECOVERY" rev-parse HEAD)"

echo "checking a merged release publishes after dev moved"
prepared_fixture
publish "$RUNNER" tag
push_contribution
clone "$RECOVERY"
# --no-ff: a developer's merge.ff=only would refuse the merge commit this case is about.
git -C "$RECOVERY" merge -q --no-ff --no-edit "$VERSION^{commit}"
commit_next_development_version "$RECOVERY" "$VERSION"
publish "$RECOVERY" branches
expect_origin refs/heads/main "$RELEASE"
expect_origin refs/heads/dev "$(git -C "$RECOVERY" rev-parse HEAD)"

echo "checking the release commit alone is not pushed to dev"
prepared_fixture
publish "$RUNNER" tag
git -C "$RUNNER" reset -q --hard "$RELEASE"
remember_origin
expect_refused "$RUNNER" branches "$NOT_AFTER_TAG"
expect_origin_unchanged

# Someone finishing a release by hand who has not merged it yet: dev would look done.
echo "checking a clone that lacks the release is refused"
prepared_fixture
publish "$RUNNER" tag
clone "$RECOVERY"
remember_origin
expect_refused "$RECOVERY" branches "$NOT_AFTER_TAG"
expect_origin_unchanged

echo "checking an unknown mode or a missing version prints the usage"
expect_usage
expect_usage tag
expect_usage publish "$VERSION"

# gh itself is out of reach of a test, so a stand-in prints what the file RUNS holds, and the
# query is checked once against the real gh by hand.
readonly GREEN_BUILD="$HERE/../require-green-build.sh"
readonly SHA="1111111111111111111111111111111111111111"
readonly WAIT="wait for the build to finish or dispatch build-on-push on dev, then release again"
mkdir "$WORK/bin"
printf '#!/usr/bin/env bash\ncat "$RUNS"\n' > "$WORK/bin/gh"
chmod +x "$WORK/bin/gh"

green_build() {
    RUNS="$WORK/runs.json" PATH="$WORK/bin:$PATH" "$GREEN_BUILD" "$SHA"
}

expect_green_build_refused() {
    local message="$1"
    if green_build > "$WORK/out" 2> "$WORK/err"; then
        echo "require-green-build.sh passed where it has to refuse" >&2
        exit 1
    fi
    diff -u <(echo "$message") "$WORK/err"
}

echo "checking a successful build passes"
echo '[{"status":"completed","conclusion":"success","url":"u"}]' > "$WORK/runs.json"
green_build > "$WORK/out"
diff -u <(echo "build-on-push is green for $SHA") "$WORK/out"

echo "checking a commit without a build is refused"
echo '[]' > "$WORK/runs.json"
expect_green_build_refused "::error::no build-on-push run for $SHA; $WAIT"

echo "checking a build still running is refused"
echo '[{"status":"in_progress","conclusion":"","url":"u"}]' > "$WORK/runs.json"
expect_green_build_refused "::error::the build-on-push run for $SHA has not finished (u); $WAIT"

echo "checking a failed build is refused"
echo '[{"status":"completed","conclusion":"failure","url":"u"}]' > "$WORK/runs.json"
expect_green_build_refused "::error::the build-on-push run for $SHA ended failure (u); fix dev and wait for a green build"

echo "checking the newest run decides"
echo '[{"status":"completed","conclusion":"failure","url":"new"},{"status":"completed","conclusion":"success","url":"old"}]' > "$WORK/runs.json"
expect_green_build_refused "::error::the build-on-push run for $SHA ended failure (new); fix dev and wait for a green build"
