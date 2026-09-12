#!/usr/bin/env bash
#
# Builds a throwaway repo covering the cases the real history cannot: both spellings of a
# breaking change, a subject repeated with a different body, a non-conventional subject, and
# two tags cut in the same second.
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

commit "$FIRST" "feat: add the first thing"
commit "$FIRST" "fix: correct the first thing"
git -C "$DIR" tag 1.0.0

# `!` plus a BREAKING CHANGE footer: the footer supplies the detail line.
commit "$SECOND" "feat!: drop the legacy endpoint" -m "BREAKING CHANGE: /v1 is gone, use /v2"
# `!` with no footer: git-cliff falls back to the description, so no detail may be appended.
commit "$SECOND" "feat(api)!: rename the field"
commit "$SECOND" "refactor: tidy the helpers"
commit "$SECOND" "refactor: tidy the helpers" -m "a different body, the same subject"
commit "$SECOND" "test: cover the new endpoint"
commit "$SECOND" "build(deps): bump com.example:thing"
commit "$SECOND" "docs: explain the new endpoint"
commit "$SECOND" "this subject is not conventional at all"
commit "$SECOND" "[release] set version to 1.1.0"
git -C "$DIR" tag 1.1.0

# Same second as 1.1.0: proves release ordering survives a timestamp tie.
commit "$SECOND" "fix: correct the rename"
git -C "$DIR" tag 1.2.0

echo "$DIR ready"
