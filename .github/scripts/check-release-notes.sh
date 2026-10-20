#!/usr/bin/env bash
# Checks release notes as the release will read them: each file is named `<version>.txt` for a
# version Gradle takes (`ReleaseVersion`: its form, and a `versionCode` in 1..2 100 000 000), since
# `release.yml` looks for exactly that name, and holds 1 to 500 characters, Play's limit per
# language. The length is counted as `play-upload.sh` sends the notes: characters, not bytes, with
# trailing white space removed and line endings kept as written. `release.yml` runs it on the
# version's notes before a release, and `ci.yml` on every file in `release-notes/` (hidden ones
# included) in every CI run, so a misnamed or overlong file fails the pull request that adds it
# rather than the release. Every failing file is reported.
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

# Whether a file name is `<version>.txt` for a version `ReleaseVersion` takes.
release_name() {
  [[ $1 =~ ^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.txt$ ]] || return 1
  local code=$((BASH_REMATCH[1] * 1000000 + BASH_REMATCH[2] * 1000 + BASH_REMATCH[3]))
  [ "$code" -ge 1 ] && [ "$code" -le 2100000000 ]
}

status=0
for notes in "$@"; do
  # Not `basename`: its command substitution would drop a trailing newline from the name.
  name=${notes##*/}
  if [[ $name != *.txt ]]; then
    error "$notes" "$notes is not named <version>.txt, such as 0.1.2.txt"
    status=1
  elif ! release_name "$name"; then
    error "$notes" "${name%.txt} is not a release version: <major>.<minor>.<patch> without leading zeros, minor and patch below 1000, from 0.0.1 to 2100.0.0 ($notes)"
    status=1
  elif [ ! -e "$notes" ]; then
    error "$notes" "No release notes: add $notes (design/release.md)"
    status=1
  elif [ ! -f "$notes" ] || [ ! -r "$notes" ]; then
    error "$notes" "$notes is not a readable file"
    status=1
  elif ! length=$(jq -Rs 'sub("\\s+$"; "") | length' "$notes"); then
    error "$notes" "$notes could not be read"
    status=1
  elif [ "$length" -eq 0 ] || [ "$length" -gt 500 ]; then
    error "$notes" "$notes has $length characters; Play takes 1 to 500"
    status=1
  fi
done
exit "$status"
