# Reshapes git-cliff's `--context` output into the data the website's release page renders.
# Released versions only, user-facing groups only. git-cliff classifies; this only shapes.

def strip_group_prefix: sub("^<!-- [0-9]+ -->"; "");

# Groups 00-05 are the user-facing half of the numbering in the design doc.
def is_user_facing: test("^<!-- 0[0-5] -->");

# Matches the release body's `upper_first`, so both renderings read the same.
def upper_first: (.[0:1] | ascii_upcase) + .[1:];

# jq's unique_by sorts by the key, which would alphabetise entries that git-cliff
# deliberately ordered oldest-first. This keeps first-occurrence order.
def dedup_by_message:
  reduce .[] as $commit ({ seen: {}, out: [] };
    if .seen[$commit.message] then .
    else .seen[$commit.message] = true | .out += [$commit]
    end)
  | .out;

# A `!` marker with no BREAKING CHANGE footer leaves breaking_description equal to the
# message, which would just repeat the entry. Same rule as the release-body template.
def breaking_detail:
  ((.breaking_description // "") | split("\n")[0]) as $detail
  | if $detail != "" and $detail != .message then $detail else null end;

def to_entries_list:
  dedup_by_message
  | map({ text: (.message | upper_first),
          scope: .scope,
          breaking: .breaking,
          detail: breaking_detail });

# Newest first. `sort_by(-.timestamp)` rather than `sort_by(.timestamp) | reverse`:
# two tags cut in the same second carry the same timestamp, and reverse would invert
# the order git-cliff already put them in. Negating keeps jq's stable sort intact.
[ .[] | select(.version != null) ]
| sort_by(-.timestamp)
| map(
    { version: .version,
      date: (.timestamp | todate | split("T")[0]),
      url: ("https://github.com/peekaboot-org/peekaboot/releases/tag/" + .version),
      groups: ((
        [ { title: "Breaking changes",
            entries: ([ .commits[] | select(.breaking) ] | to_entries_list) } ]
        | map(select(.entries | length > 0))
      ) + (
        # group_by sorts by the key, so the <!-- NN --> prefixes order the sections.
        [ .commits[] | select(.group | is_user_facing) ]
        | group_by(.group)
        | map({ title: (.[0].group | strip_group_prefix), entries: to_entries_list })
      )) })
