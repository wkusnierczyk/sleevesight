#!/bin/zsh
# Spike #272, part 2: the Mac side of the on-device bench.
#
#   mac/bench.sh prompts            rebuilds app/src/main/assets/prompts.json from the fixtures
#   mac/bench.sh check <results>    runs GroundingCheck on <results>/replies.jsonl (a folder pulled
#                                   from the phone), writing checked.jsonl and checked-summary.txt
#
# Copies BenchSpike272.kt into :core's tests for the one run and removes it afterwards, so the
# repository's own build and checks never see it. Run it from a worktree, not the main clone.
set -u
here=${0:A:h}
repo=${here:h:h:h}
target=$repo/core/src/test/kotlin/app/sleevesight/core/synthesis/BenchSpike272.kt
slot=${GRADLE_SLOT:-$HOME/dev/sleevesight/.dev/scratchpad/gradle-slot.sh}
tmo=${GRADLE_TMO:-$HOME/dev/sleevesight/.dev/scratchpad/tmo.sh}

case ${1:-} in
  prompts)
    export BENCH_OUT=$here:h/app/src/main/assets/prompts.json
    ;;
  check)
    [[ -f ${2:-}/replies.jsonl ]] || { echo "no replies.jsonl in '${2:-}'" >&2; exit 2; }
    export BENCH_RESULTS=${2:A}
    ;;
  *)
    echo "usage: $0 prompts | check <results folder>" >&2
    exit 2
    ;;
esac

cp $here/BenchSpike272.kt $target || exit 1
trap 'rm -f $target' EXIT
cd $repo
if [[ -x $slot && -x $tmo ]]; then
  $slot $tmo 1800 ./gradlew --no-daemon :core:test --tests "app.sleevesight.core.synthesis.BenchSpike272" --rerun
else
  ./gradlew --no-daemon :core:test --tests "app.sleevesight.core.synthesis.BenchSpike272" --rerun
fi
rc=$?
exit $rc
