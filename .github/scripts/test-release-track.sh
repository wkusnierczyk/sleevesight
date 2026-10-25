#!/usr/bin/env bash
# Tests `release-track.sh`, the release's only guard against a `PLAY_TRACK` left at `production`,
# in a throwaway repository with three release tags: `v0.3.2` (code), `v0.3.3` (release notes only)
# and `v0.3.4` (code). `ci.yml` runs it in every CI run. Every failing case is reported.
#
# Usage: test-release-track.sh [<empty directory for the repository>]  (default: a new temporary one)
set -euo pipefail

script=$(cd "$(dirname "$0")" && pwd)/release-track.sh
repo=${1:-$(mktemp -d)}
failures=0

git -C "$repo" init -q
git -C "$repo" config user.name test
git -C "$repo" config user.email test@example.invalid
git -C "$repo" config commit.gpgsign false
git -C "$repo" config tag.gpgsign false
echo one > "$repo/code.txt"
git -C "$repo" add code.txt
git -C "$repo" commit -qm code
git -C "$repo" tag v0.3.2
mkdir "$repo/release-notes"
echo notes > "$repo/release-notes/0.3.3.txt"
git -C "$repo" add release-notes
git -C "$repo" commit -qm notes
git -C "$repo" tag v0.3.3
echo two > "$repo/code.txt"
git -C "$repo" commit -qam code
git -C "$repo" tag v0.3.4

# check <name> <expected exit> <expected output, or ""> <track> <tag>: runs the script on the tag's
# commit, with no `GITHUB_OUTPUT`, so the outputs come on standard output with any messages.
check() {
  local name=$1 code=$2 expected=$3 track=$4 tag=$5 out actual=0
  out=$(cd "$repo" && env -u GITHUB_OUTPUT -u GITHUB_STEP_SUMMARY "$script" "$track" "$tag" "$tag") || actual=$?
  local got
  got=$(printf '%s\n' "$out" | grep -E '^(name|developer-mode)=' | paste -sd ' ' - || true)
  if [ "$actual" -ne "$code" ] || [ "$got" != "$expected" ]; then
    echo "::error::release-track.sh, $name: exit $actual, outputs '$got'; expected exit $code, outputs '$expected'"
    printf '%s\n' "$out"
    failures=$((failures + 1))
  else
    echo "ok: $name"
  fi
}

check "unset is internal" 0 "name=internal developer-mode=true" "" v0.3.4
check "a testing track" 0 "name=beta developer-mode=true" " beta " v0.3.4
check "production on release notes only" 0 "name=production developer-mode=false" production v0.3.3
check "production on new code" 1 "" production v0.3.4
check "production on the first tag" 1 "" production v0.3.2
check "Production spelt otherwise" 1 "" Production v0.3.3
check "not a track id" 1 "" "a b" v0.3.4

[ "$failures" -eq 0 ] || exit 1
