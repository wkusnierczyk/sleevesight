#!/usr/bin/env bash
# Measures Sleevesight's start (#626): the time from launching it to its first frame, as
# `am start -W` reports it (`TotalTime`), over several runs, after stopping the app before each.
# With notification access granted, the system binds the listener again within about half a second
# of the stop, which starts the app's process before the launch: the start is then warm (a new
# activity in a running process), which is how the app opens in use. Each run prints its
# `LaunchState`, and the median is given per state.
# Unlike `snapshot.sh`, this changes the phone: it force-stops the app, which also stops its
# listener until the system binds it again, and opens it on screen. Run it only with the phone's
# owner's go-ahead, not during a listening session, and check afterwards that *Now playing* follows
# the player again.
#
# Usage: startup.sh --yes [<runs>]   (default 10 runs; prints each run's times and the median)
# Environment: PACKAGE (default `app.sleevesight`), ACTIVITY (default its launcher activity).
set -euo pipefail

[[ ${1:-} == --yes ]] || { sed -n '2,10p' "$0" >&2; exit 2; }
runs=${2:-10}
package=${PACKAGE:-app.sleevesight}
activity=${ACTIVITY:-app.sleevesight.app.MainActivity}

adb get-state >/dev/null
totals=()
for ((i = 1; i <= runs; i++)); do
  adb shell am force-stop "$package"
  sleep 2
  result=$(adb shell am start -W -n "$package/$activity" | tr -d '\r')
  total=$(sed -n 's/^TotalTime: //p' <<<"$result")
  state=$(sed -n 's/^LaunchState: //p' <<<"$result")
  [[ ($state == COLD || $state == WARM) && -n $total ]] ||
    { echo "run $i was neither a cold nor a warm start:" >&2; echo "$result" >&2; exit 1; }
  echo "run $i: ${total} ms ($state)"
  totals+=("$state $total")
  sleep 3
done
for state in COLD WARM; do
  printf '%s\n' "${totals[@]}" | awk -v s="$state" '$1 == s { print $2 }' | sort -n |
    awk -v s="$state" '{ v[NR] = $1 } END { if (!NR) exit;
      m = NR % 2 ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2;
      printf "median of %d %s starts: %d ms\n", NR, tolower(s), m }'
done
