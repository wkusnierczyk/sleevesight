#!/usr/bin/env bash
# Decides the track a release's bundle goes to and whether its build starts in developer mode
# (#451), and stops a production release that is not the tested code. `release.yml` runs it before
# the build; it writes `name=<track>` and `developer-mode=<true|false>` to `$GITHUB_OUTPUT` (to
# standard output when that is unset), a line naming both to `$GITHUB_STEP_SUMMARY` when set, and its
# messages, workflow commands, to standard output.
#
# The track is the environment's variable `PLAY_TRACK` (`internal` when unset), trimmed of white space
# at either end. It must be a track id of lower-case letters, digits and hyphens; any spelling of
# `production` other than exactly `production` is refused, so a typo cannot build a test bundle that
# goes on to a track named like production. A testing track gets developer mode on, `production` off.
#
# A production tag must carry the tested code: the tag before it on its history (the highest
# `v<major>.<minor>.<patch>` tag the tagged commit contains, other than its own) differs from it in
# `release-notes/` only (`design/release.md`, *Test and production builds*). So a `PLAY_TRACK` left
# at `production` stops the next test tag, which brings new code, rather than ship that code to
# production untested. The tags must be fetched before it runs. `test-release-track.sh` tests it.
#
# Usage: release-track.sh <PLAY_TRACK value> <tag> <tagged commit>
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <track> <tag> <commit>" >&2
  exit 2
fi
track=$1
tag=$2
commit=$3

# Trim white space at either end.
track=${track#"${track%%[![:space:]]*}"}
track=${track%"${track##*[![:space:]]}"}
[ -n "$track" ] || track=internal

lower=$(printf '%s' "$track" | tr '[:upper:]' '[:lower:]')
if [ "$lower" = production ] && [ "$track" != production ]; then
  echo "::error::PLAY_TRACK is \`$track\`; the production track is \`production\`, in lower case"
  exit 1
fi
if ! [[ "$track" =~ ^[a-z0-9-]+$ ]]; then
  echo "::error::PLAY_TRACK is \`$track\`, not a track id (lower-case letters, digits and hyphens)"
  exit 1
fi

if [ "$track" = production ]; then
  tags=$(git tag --merged "$commit" --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname)
  previous=$(printf '%s\n' "$tags" | grep -vxF -e "$tag" -e '' | head -n 1 || true)
  if [ -z "$previous" ]; then
    echo "::error::A production release is the code of a tested release, and $tag has no release tag before it"
    exit 1
  fi
  changed=$(git diff --name-only "$previous" "$commit")
  untested=$(printf '%s\n' "$changed" | grep -v '^release-notes/' | grep -v '^$' || true)
  if [ -n "$untested" ]; then
    echo "::error::PLAY_TRACK is production, but $tag changes more than release notes since $previous, the release tested before it; set PLAY_TRACK to the testing track, or tag the tested code (design/release.md, Test and production builds):"
    printf '%s\n' "$untested"
    exit 1
  fi
  echo "::warning::Production release: $tag, the code of $previous, with developer mode off"
  developer=false
else
  developer=true
fi

out=${GITHUB_OUTPUT:-/dev/stdout}
echo "name=$track" >> "$out"
echo "developer-mode=$developer" >> "$out"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  echo "Track \`$track\`: developer mode $developer by default." >> "$GITHUB_STEP_SUMMARY"
fi
