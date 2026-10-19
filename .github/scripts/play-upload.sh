#!/usr/bin/env bash
# Uploads a signed bundle to a Google Play track through the Play Developer API (androidpublisher
# v3), in one edit: open it, upload the bundle, set the track to a release named for the version
# with its notes, and commit. The release workflow runs it (`design/release.md`); it calls the API
# with curl rather than a third-party action, since the job holds the release credentials.
#
# Usage: play-upload.sh <package> <bundle> <version> <track> <notes file>
# The access token, scoped to androidpublisher, is in PLAY_ACCESS_TOKEN. PLAY_API overrides the
# API's address, for a test against a local server.
set -euo pipefail

if [ "$#" -ne 5 ]; then
  echo "usage: $0 <package> <bundle> <version> <track> <notes file>" >&2
  exit 2
fi
package=$1 bundle=$2 version=$3 track=$4 notes=$5
: "${PLAY_ACCESS_TOKEN:?PLAY_ACCESS_TOKEN is not set}"

# Play's listing language, which the notes are written in.
language=en-US

# `versionCode` as Gradle derives it (`design/release.md`, *Versioning*), to check that Play read the
# bundle that was built for this tag. 10# keeps a part such as `08` decimal.
IFS=. read -r major minor patch <<<"$version"
expected_code=$((10#$major * 1000000 + 10#$minor * 1000 + 10#$patch))

api=${PLAY_API:-https://androidpublisher.googleapis.com}
app="$api/androidpublisher/v3/applications/$package"

# A failed call prints Play's error body, which says what it refused, to the log.
call() {
  local body
  if ! body=$(curl --silent --show-error --fail-with-body \
    -H "Authorization: Bearer $PLAY_ACCESS_TOKEN" "$@"); then
    printf '%s\n' "$body" >&2
    return 1
  fi
  printf '%s\n' "$body"
}

edit=$(call -X POST "$app/edits" | jq -er .id)
echo "Opened edit $edit"

# An edit left open expires on its own; deleting it on failure only tidies up, so its own failure
# is ignored.
committed=false
discard() {
  if [ "$committed" = false ]; then
    call -X DELETE "$app/edits/$edit" >/dev/null 2>&1 || true
  fi
}
trap discard EXIT

code=$(call -X POST -H "Content-Type: application/octet-stream" --data-binary "@$bundle" \
  "$api/upload/androidpublisher/v3/applications/$package/edits/$edit/bundles?uploadType=media" |
  jq -er .versionCode)
echo "Uploaded $bundle as version code $code"
if [ "$code" != "$expected_code" ]; then
  echo "::error::Play read version code $code; $version should be $expected_code"
  exit 1
fi

# The release is named for the version, not Play's default `<code> (<version>)`. `completed` rolls it
# out to the whole track at once.
jq -n --arg track "$track" --arg name "$version" --arg code "$code" \
  --arg language "$language" --rawfile notes "$notes" '{
    track: $track,
    releases: [{
      name: $name,
      versionCodes: [$code],
      status: "completed",
      releaseNotes: [{language: $language, text: ($notes | sub("\\s+$"; ""))}]
    }]
  }' |
  call -X PUT -H "Content-Type: application/json" --data-binary @- "$app/edits/$edit/tracks/$track" >/dev/null
echo "Set the $track track to release $version"

call -X POST "$app/edits/$edit:commit" >/dev/null
committed=true
echo "Committed edit $edit: $version is on the $track track"
