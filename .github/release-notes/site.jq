# Reshapes the changelog into the data the website's release page renders. Run with -Rs, so
# the input is the whole file as one string.
#
# A section counts as released once the release job has stamped its date into the heading.
# The notes stay markdown, which the page renders itself.

("\n" + .)
| split("\n## ")
| .[1:]
| map(
    split("\n") as $lines
    | ($lines[0]
       | capture("^(?<version>[0-9]+\\.[0-9]+\\.[0-9]+) - (?<date>[0-9]{4}-[0-9]{2}-[0-9]{2})$")?)
      as $heading
    | { version: $heading.version,
        date: $heading.date,
        url: ("https://github.com/peekaboot-org/peekaboot/releases/tag/" + $heading.version),
        notes: ($lines[1:] | join("\n") | sub("^\\s+"; "") | sub("\\s+$"; "")) })
