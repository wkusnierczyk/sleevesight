#!/usr/bin/env bash
# Checks developer mode's default in a build of the app (#451): `BuildConfig.DEVELOPER_MODE_DEFAULT`,
# which `-Psleevesight.developerMode` sets, read from the `BuildConfig` Gradle generated for the
# variant, so it is the value the build compiled. `release.yml` runs it on the bundle's build (on
# for a testing track, off for production), and `ci.yml` on the debug build under both values.
# Fails, rather than passes, when the file or the field is not where it expects them, as after a
# change to where the Android Gradle plugin writes it.
#
# Usage: check-developer-mode.sh <variant> <true|false>
set -euo pipefail

if [ "$#" -ne 2 ] || { [ "$2" != true ] && [ "$2" != false ]; }; then
  echo "usage: $0 <variant> <true|false>" >&2
  exit 2
fi
variant=$1
expected=$2

file=app/build/generated/source/buildConfig/$variant/app/sleevesight/app/BuildConfig.java
if [ ! -f "$file" ]; then
  echo "::error::$file not found: the $variant variant is not built, or its BuildConfig is elsewhere"
  exit 1
fi
if ! line=$(grep -E '^[[:space:]]*public static final boolean DEVELOPER_MODE_DEFAULT = (true|false);' "$file"); then
  echo "::error::$file has no DEVELOPER_MODE_DEFAULT"
  exit 1
fi
case "$line" in
  *"= $expected;"*) echo "The $variant build has developer mode $expected by default." ;;
  *)
    actual=${line##*= }
    echo "::error::The $variant build has developer mode ${actual%;} by default, not $expected"
    exit 1
    ;;
esac
