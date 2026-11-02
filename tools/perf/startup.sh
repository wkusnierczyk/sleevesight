#!/usr/bin/env bash
# Measures Sleevesight's cold start (#626): the time from launching it to its first frame, as
# `am start -W` reports it (`TotalTime`), over several runs, after stopping the app before each.
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
  [[ $(sed -n 's/^LaunchState: //p' <<<"$result") == COLD && -n $total ]] ||
    { echo "run $i was not a cold start:" >&2; echo "$result" >&2; exit 1; }
  echo "run $i: ${total} ms"
  totals+=("$total")
  sleep 3
done
printf '%s\n' "${totals[@]}" | sort -n |
  awk '{ v[NR] = $1 } END { m = NR % 2 ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2;
                           printf "median of %d cold starts: %d ms\n", NR, m }'
