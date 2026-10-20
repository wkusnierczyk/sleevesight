#!/usr/bin/env bash
# Checks release notes as the release will read them: each file is named `<version>.txt`, in the
# form Gradle takes (`ReleaseVersion`), since `release.yml` looks for exactly that name, and holds
# 1 to 500 characters, Play's limit per language. The length is counted as `play-upload.sh` sends
# the notes: characters, not bytes, with trailing white space removed and line endings kept as
# written. `release.yml` runs it on the version's notes before a release, and `ci.yml` on every
# file in `release-notes/` in every CI run, so a misnamed or overlong file fails the pull request
# that adds it rather than the release. Every failing file is reported.
#
# Usage: check-release-notes.sh <notes file>...
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <notes file>..." >&2
  exit 2
fi

# Workflow commands take `%`, CR and LF escaped in the message, and `:` and `,` as well in a
# property such as `file=`.
escape_message() {
  local s=${1//%/%25}
  s=${s//$'\r'/%0D}
  printf '%s' "${s//$'\n'/%0A}"
}
escape_property() {
  local s
  s=$(escape_message "$1")
  s=${s//:/%3A}
  printf '%s' "${s//,/%2C}"
}
error() {
  echo "::error file=$(escape_property "$1")::$(escape_message "$2")"
}

status=0
for notes in "$@"; do
  if ! [[ $(basename "$notes") =~ ^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.txt$ ]]; then
    error "$notes" "$notes is not named <version>.txt, such as 0.1.2.txt, so no release would find it"
    status=1
  elif [ ! -f "$notes" ]; then
    error "$notes" "$notes is not a file"
    status=1
  else
    length=$(jq -Rs 'sub("\\s+$"; "") | length' "$notes")
    if [ "$length" -eq 0 ] || [ "$length" -gt 500 ]; then
      error "$notes" "$notes has $length characters; Play takes 1 to 500"
      status=1
    fi
  fi
done
exit "$status"
