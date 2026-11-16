# Reshapes git-cliff's `--context` output into the data the website's release page renders.
# Released versions only, user-facing groups only. git-cliff classifies; this only shapes.

def strip_group_prefix: sub("^<!-- [0-9]+ -->"; "");

# Groups 01-04, features, fixes, performance and dependency upgrades, are what the website
# shows. Breaking changes are synthesised below from the `breaking` flag; no commit parser
# emits a group 00.
def is_user_facing: test("^<!-- 0[1-4] -->");

# Matches the release body's `upper_first`, so both renderings read the same.
def upper_first: (.[0:1] | ascii_upcase) + .[1:];

# A `!` marker with no BREAKING CHANGE footer leaves breaking_description equal to the
# message, which would just repeat the entry. Same rule as the release-body template.
def breaking_detail:
  ((.breaking_description // "") | split("\n")[0]) as $detail
  | if $detail != "" and $detail != .message then $detail else null end;

# The commit body as paragraphs with their wrapped lines joined. Same rule as the release-body
# template: features, fixes, performance and anything breaking are described, and 1.3.0
# predates bodies written for the notes.
def description($version):
  if $version != "1.3.0" and (.breaking or (.group | test("^<!-- 0[1-3] -->")))
  then (.body // "") | split("\n\n") | map(gsub("\n"; " ")) | map(select(. != ""))
  else [] end;

def to_entries_list($version):
  map({ text: (.message | upper_first),
        scope: .scope,
        breaking: .breaking,
        detail: breaking_detail,
        description: description($version) });

# Newest first. `sort_by(-.timestamp)` rather than `sort_by(.timestamp) | reverse`:
# two tags cut in the same second carry the same timestamp, and reverse would invert
# the order git-cliff already put them in. Negating keeps jq's stable sort intact.
[ .[] | select(.version != null) ]
| sort_by(-.timestamp)
| map(
    .version as $version
    | { version: .version,
        date: (.timestamp | todate | split("T")[0]),
        url: ("https://github.com/peekaboot-org/peekaboot/releases/tag/" + .version),
        groups: ((
          [ { title: "Breaking changes",
              entries: ([ .commits[] | select(.breaking) ] | to_entries_list($version)) } ]
          | map(select(.entries | length > 0))
        ) + (
          # A breaking commit is listed under Breaking changes only. group_by sorts by the key,
          # so the <!-- NN --> prefixes order the sections.
          [ .commits[] | select((.group | is_user_facing) and (.breaking | not)) ]
          | group_by(.group)
          | map({ title: (.[0].group | strip_group_prefix),
                  entries: to_entries_list($version) })
        )) })
