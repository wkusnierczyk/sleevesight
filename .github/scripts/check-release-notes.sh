#!/usr/bin/env bash
# Checks release notes against Play's limit: 1 to 500 characters per language. They are counted as
# `play-upload.sh` sends them: characters, not bytes, with trailing white space removed and line
# endings kept as written. `release.yml` runs it on the version's notes before a release, and
# `ci.yml` on every file in `release-notes/` on every run, so notes that are too long fail the pull
# request that adds them rather than the release.
#
# Usage: check-release-notes.sh <notes file>...
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <notes file>..." >&2
  exit 2
fi

status=0
for notes in "$@"; do
  length=$(jq -Rs 'sub("\\s+$"; "") | length' "$notes")
  if [ "$length" -eq 0 ] || [ "$length" -gt 500 ]; then
    echo "::error file=$notes::$notes has $length characters; Play takes 1 to 500"
    status=1
  fi
done
exit "$status"
