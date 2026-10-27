#!/usr/bin/env bash
# Checks how an APK names itself on a phone (#614): its launcher label and its `versionName`, read
# from the APK by `aapt2 dump badging`, the newest in the Android SDK's build tools. `ci.yml` runs it
# on the debug APK (*Sleevesight Debug*, `<version>-debug`) and on the release APK (*Sleevesight*,
# `<version>`). Fails, rather than passes, when the APK, `aapt2` or a field is not where it looks.
#
# Usage: check-build-identity.sh <apk> <label> <versionName pattern, an extended regular expression>
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <apk> <label> <versionName pattern>" >&2
  exit 2
fi
apk=$1
label=$2
pattern=$3

if [ ! -f "$apk" ]; then
  echo "::error::$apk not found"
  exit 1
fi
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
aapt2=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -name aapt2 2>/dev/null | sort -V | tail -n 1)
if [ -z "$aapt2" ]; then
  echo "::error::no aapt2 under ${sdk:-<no ANDROID_HOME>}/build-tools"
  exit 1
fi
badging=$("$aapt2" dump badging "$apk")
actual_label=$(sed -n "s/^application-label:'\(.*\)'\$/\1/p" <<< "$badging")
actual_version=$(sed -n "s/^package: .* versionName='\([^']*\)'.*/\1/p" <<< "$badging")

failed=0
if [ "$actual_label" != "$label" ]; then
  echo "::error::$apk is labelled '$actual_label', not '$label'"
  failed=1
fi
if ! [[ $actual_version =~ $pattern ]]; then
  echo "::error::$apk has versionName '$actual_version', which does not match $pattern"
  failed=1
fi
[ "$failed" = 0 ] && echo "$apk: '$actual_label', version $actual_version."
exit "$failed"
