#!/usr/bin/env bash
#
# Pushes the refs that make a release public, in the two steps a release takes.
#
# tag pushes the tag alone, before the upload, so release:perform builds what origin serves
# under it. release:prepare runs the full build between the pre-flight and this push, so dev
# is read again here: a dev that moved in that time is caught while nothing is public.
#
# branches pushes main and then dev, once Maven Central has the release.
#
# Neither mode forces a push or deletes a ref, and a ref that is already in place is left
# alone, so a release that stopped halfway is finished by running the same mode again. Both
# read origin themselves instead of trusting what the checkout fetched.
#
# Usage: publish-refs.sh tag|branches <version>
set -euo pipefail

usage() {
    echo "usage: publish-refs.sh tag|branches <version>" >&2
    exit 2
}

fail() {
    echo "::error::$1" >&2
    exit 1
}

# Empty for a tag this clone lacks, which both modes refuse like a tag in the wrong place.
local_tag_commit() {
    git rev-parse --verify --quiet "refs/tags/${VERSION}^{commit}" || true
}

# Compared by object, so a tag someone else made under this name is never taken for this
# release's, even on the same commit. Leaves ORIGIN_TAG empty when origin has none.
read_origin_tag() {
    ORIGIN_TAG="$(git ls-remote --refs origin "refs/tags/${VERSION}" | cut -f1)"
    if [ -n "$ORIGIN_TAG" ] && [ "$ORIGIN_TAG" != "$(git rev-parse "refs/tags/${VERSION}")" ]; then
        fail "origin already has a tag ${VERSION} that is not this one"
    fi
}

fetch_branches() {
    git fetch --quiet --no-tags origin \
        "+refs/heads/dev:refs/remotes/origin/dev" "+refs/heads/main:refs/remotes/origin/main"
}

publish_tag() {
    local tag_commit
    tag_commit="$(local_tag_commit)"
    # The state release:prepare leaves with pushChanges=false.
    if [ -z "$tag_commit" ] \
        || [ "$tag_commit" != "$(git rev-parse --verify --quiet HEAD~1 || true)" ]; then
        fail "tag ${VERSION} is not on the commit before HEAD"
    fi
    read_origin_tag
    if [ -n "$ORIGIN_TAG" ]; then
        echo "tag ${VERSION} is already on origin"
        return
    fi
    fetch_branches
    if [ "$(git rev-parse refs/remotes/origin/dev)" != "$(git rev-parse "${tag_commit}^")" ]; then
        fail "dev moved since ${VERSION} was prepared; nothing was pushed, dispatch again"
    fi
    git push --quiet origin "refs/tags/${VERSION}"
    echo "pushed tag ${VERSION}"
}

publish_branches() {
    local tag_commit
    tag_commit="$(local_tag_commit)"
    # Not HEAD~1: a release finished by hand after dev moved has a merge in between. Not HEAD
    # either, or dev would end on a release version.
    if [ -z "$tag_commit" ] || [ "$tag_commit" = "$(git rev-parse HEAD)" ] \
        || ! git merge-base --is-ancestor "$tag_commit" HEAD; then
        fail "HEAD is not a commit after tag ${VERSION}"
    fi
    read_origin_tag
    if [ -z "$ORIGIN_TAG" ]; then
        fail "origin has no tag ${VERSION}; the tag is pushed first"
    fi
    fetch_branches

    # main originates nothing; it is a pointer that only ever fast-forwards onto the commit
    # that was published. Pushing a bare commit SHA makes git refuse anything but a
    # fast-forward, so this needs no --force and can never rewrite main. The tag is annotated,
    # hence its commit: pushing a tag object to a branch ref is rejected. Before dev, because
    # this push cannot be refused over someone else's commit, so a dev that moved still
    # leaves main on what was published.
    if git merge-base --is-ancestor "$tag_commit" refs/remotes/origin/main; then
        echo "main already has ${VERSION}"
    else
        git push --quiet origin "${tag_commit}:refs/heads/main"
        echo "pushed ${VERSION} to main"
    fi

    # A dev that moved is reported and never rewritten. The push is checked as well as the
    # fetch, because a commit can land between the two.
    if git merge-base --is-ancestor HEAD refs/remotes/origin/dev; then
        echo "dev already has HEAD"
    elif git merge-base --is-ancestor refs/remotes/origin/dev HEAD \
        && git push --quiet origin HEAD:refs/heads/dev; then
        echo "pushed HEAD to dev"
    else
        fail "dev moved since ${VERSION} was prepared; main is on the release, dev was not pushed. See BUILD.md, Recovering a release"
    fi
}

[ $# -eq 2 ] || usage
readonly MODE="$1" VERSION="$2"
case "$MODE" in
    tag) publish_tag ;;
    branches) publish_branches ;;
    *) usage ;;
esac
