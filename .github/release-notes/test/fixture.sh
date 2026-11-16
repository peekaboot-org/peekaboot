#!/usr/bin/env bash
#
# Builds a throwaway repo covering the cases the real history cannot: a release older than the
# notes reach, the release whose bodies stay unprinted, bodies that wrap, run over two
# paragraphs or end in trailers, both spellings of a breaking change, a breaking change of an
# internal type, user-facing types that classify as internal, a runtime and a build-only
# dependency bump, a repeated subject, a non-conventional subject, and two tags cut in the
# same second.
#
# Commit dates are fixed so the generated notes are byte-comparable against the golden files.
set -euo pipefail

readonly DIR="$1"
rm -rf "$DIR"
git init -q -b dev "$DIR"
git -C "$DIR" config user.email "fixture@example.invalid"
git -C "$DIR" config user.name "fixture"
# Anyone with signing on by default would otherwise have every fixture commit try to sign
# with no key for this address, hanging on a passphrase prompt or failing outright.
git -C "$DIR" config commit.gpgsign false
git -C "$DIR" config tag.gpgsign false

commit() {
    local date="$1" subject="$2"
    shift 2
    echo "$subject" >> "$DIR/log"
    git -C "$DIR" add log
    GIT_AUTHOR_DATE="$date" GIT_COMMITTER_DATE="$date" \
        git -C "$DIR" commit -q -m "$subject" "$@"
}

readonly FIRST="2026-01-01T10:00:00+00:00"
readonly SECOND="2026-02-01T10:00:00+00:00"
readonly THIRD="2026-03-01T10:00:00+00:00"
readonly FOURTH="2026-04-01T10:00:00+00:00"

# render.sh starts the notes after 1.2.0, so nothing up to this tag may appear anywhere.
commit "$FIRST" "feat: add a thing the notes leave out"
commit "$FIRST" "fix: correct a thing the notes leave out"
git -C "$DIR" tag 1.2.0

# 1.3.0 is listed by subject alone: its bodies were not written for the notes.
commit "$SECOND" "feat: add the first thing" -m "A body that explains the code and must not print."
commit "$SECOND" "fix: correct the first thing"
git -C "$DIR" tag 1.3.0

# `!` plus a BREAKING CHANGE footer: the footer supplies the detail line, the body the description.
commit "$THIRD" "feat!: drop the legacy endpoint" \
    -m "The /v1 endpoint answered with the old payload." \
    -m "BREAKING CHANGE: /v1 is gone, use /v2"
# `!` with no footer: git-cliff falls back to the description, so no detail may be appended.
commit "$THIRD" "feat(api)!: rename the field"
# A wrapped line joins its paragraph, a second paragraph stays apart, trailers stay out.
commit "$THIRD" "feat: add the second thing" \
    -m "The second thing lists every entry and
says how many it left out." \
    -m "Set thing.limit to list more." \
    -m "Refs: #12
Signed-off-by: fixture <fixture@example.invalid>"
commit "$THIRD" "fix: keep the order stable" -m "Two entries of the same age swapped places."
commit "$THIRD" "perf: cache the lookups"
# User-facing types whose scope says the change is test harness work.
commit "$THIRD" "feat(testing-app): add a demo page" -m "A body of test harness work must not print."
commit "$THIRD" "fix(test): pin the flaky wait"
commit "$THIRD" "revert: restore the old rule"
# A breaking change of an internal type still belongs under Breaking changes, body included.
commit "$THIRD" "refactor(store)!: key traces by id" -m "A custom store has to implement findById."
commit "$THIRD" "refactor: tidy the helpers"
# The same subject again is a second entry, and an internal body does not print.
commit "$THIRD" "refactor: tidy the helpers" -m "a different body, the same subject"
commit "$THIRD" "test: cover the new endpoint"
# A bump of a library the starter puts on the classpath, then one of a build plugin.
commit "$THIRD" "build(deps): bump com.cronutils:cron-utils from 9.2.1 to 9.2.2" \
    -m "Release notes the bot copied in must not print."
commit "$THIRD" "build(deps): bump org.apache.maven.plugins:maven-surefire-plugin"
commit "$THIRD" "build(deps-dev): bump com.example:plugin"
commit "$THIRD" "docs: explain the new endpoint"
commit "$THIRD" "this subject is not conventional at all"
commit "$THIRD" "[release] set version to 1.4.0"
git -C "$DIR" tag 1.4.0

# Same second as 1.4.0: proves release ordering survives a timestamp tie.
commit "$THIRD" "fix: correct the rename"
git -C "$DIR" tag 1.5.0

# Untagged, so --unreleased has something to render and --latest has something to exclude.
commit "$FOURTH" "feat: add something after the last tag" \
    -m "It shows up in the draft
with its description."

echo "$DIR ready"
