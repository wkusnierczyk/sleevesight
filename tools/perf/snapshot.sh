#!/usr/bin/env bash
# Takes one snapshot of what Sleevesight costs the phone (#626; the page *How it performs*,
# `docs/performance/`): its memory, CPU time, network bytes, battery use, wakeups, alarms and jobs,
# its size on disk, and the phone's battery, as the raw `dumpsys`, `ps` and `stat` output, one file
# each, in a new directory. Two snapshots, before and after a listening session, are compared by
# `report.py`. Everything here only reads: nothing is installed, started, stopped, reset or changed
# on the phone, apart from `dumpsys netstats --poll`, which makes the system count the bytes so far
# and changes nothing an app or the user can see.
#
# The phone is the one `adb` picks, or `ANDROID_SERIAL`'s. For battery figures, run it over
# wireless debugging (Developer options), so the phone drains on its battery rather than charging
# from the cable; the snapshot records whether it was plugged in.
#
# Usage: snapshot.sh <directory> [<label>]
#   Writes <directory>/<UTC time>-<label>/, and prints its path.
# Environment: PACKAGE, the app to measure (default `app.sleevesight`, the release build from Play).
set -euo pipefail

[[ $# -ge 1 && $# -le 2 ]] || { echo "usage: snapshot.sh <directory> [<label>]" >&2; exit 2; }
package=${PACKAGE:-app.sleevesight}
label=${2:-snapshot}
out="$1/$(date -u +%Y%m%dT%H%M%SZ)-$label"

adb get-state >/dev/null
adb shell pm path "$package" >/dev/null || { echo "$package is not installed" >&2; exit 1; }
mkdir -p "$out"

# One command's output into one file; a command that fails stops the snapshot, so a missing
# figure is never mistaken for a zero.
take() {
  local name=$1; shift
  adb shell "$@" >"$out/$name.txt"
}

{
  echo "package=$package"
  echo "label=$label"
  echo "taken_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "android=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "build=$(adb shell getprop ro.build.display.id | tr -d '\r')"
  # The uid is `userId=` up to Android 16, `appId=` from 17; both are written as `userId=`.
  adb shell dumpsys package "$package" | tr -d '\r' |
    grep -E '^ +(userId|appId|versionCode|versionName|firstInstallTime|lastUpdateTime)=' |
    sed -E 's/^ +//; s/ .*//; s/^appId=/userId=/' | head -5
  echo "uptime_ms=$(adb shell cat /proc/uptime | tr -d '\r' | awk '{printf "%d", $1 * 1000}')"
} >"$out/meta.txt"

take battery dumpsys battery
# The app's processes, with their CPU time so far and resident memory; the app may not be running.
take ps ps -A -o PID,UID,TIME+,RSS,NAME
take meminfo dumpsys meminfo "$package"
take batterystats dumpsys batterystats --charged "$package"
take batterystats-checkin dumpsys batterystats --checkin
adb shell dumpsys netstats --poll >/dev/null
take netstats dumpsys netstats detail
take alarm dumpsys alarm
take jobs dumpsys jobscheduler "$package"
take diskstats dumpsys diskstats
# The installed APKs' sizes; the data and caches are in diskstats (as of its last daily count) and
# on the phone's own app info screen.
# shellcheck disable=SC2016 # expanded by the phone's shell, not here
adb shell 'for f in $(pm path '"$package"' | sed s/^package://); do stat -c "%s %n" "$f"; done' \
  >"$out/apk.txt"

echo "$out"
