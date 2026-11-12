#!/usr/bin/env bash
# Tests the merge check (`.githooks/merge-check`) through the hooks as `.githooks/install` installs
# them, in throwaway repositories: merges that keep both sides, resolutions that drop what the
# incoming side added, the `Dropped:` trailer that excuses it, and the check without a `commit-msg`
# hook. `ci.yml` runs it in every CI run. Every failing case is reported.
#
# Usage: test-merge-check.sh
set -euo pipefail

hooks=$(cd "$(dirname "$0")/../.." && pwd)/.githooks
top=$(mktemp -d)
trap 'rm -rf "$top"' EXIT
failures=0
n=0

# fresh: a new repository in $repo with the hooks installed, on branch `work`, and a first commit of
# f.txt (lines one to ten). `ours` and `theirs` branch from it.
fresh() {
  n=$((n + 1))
  repo=$top/$n
  git init -q -b work "$repo"
  git -C "$repo" config user.name test
  git -C "$repo" config user.email test@example.invalid
  git -C "$repo" config commit.gpgsign false
  git -C "$repo" config merge.conflictStyle merge
  cp -R "$hooks" "$repo/.githooks"
  echo .githooks/ >>"$repo/.git/info/exclude"
  (cd "$repo" && .githooks/install >/dev/null)
  printf '%s\n' one two three four five six seven eight nine ten >"$repo/f.txt"
  git -C "$repo" add f.txt
  git -C "$repo" commit -qm base
  git -C "$repo" branch theirs
}

# on <branch> <commit message> <command>...: runs the command in the repository on <branch> and
# commits everything it changed.
on() {
  local branch=$1 message=$2
  shift 2
  git -C "$repo" checkout -q "$branch"
  (cd "$repo" && "$@")
  git -C "$repo" add -A
  git -C "$repo" commit -qm "$message"
}

# edit <line number> <text>: replaces a line of f.txt; append <text>: adds one at its end.
edit() { awk -v n="$1" -v t="$2" 'NR == n { print t; next } { print }' f.txt >f.new && mv f.new f.txt; }
append() { printf '%s\n' "$1" >>f.txt; }

# expect <case> <exit> <text the output has, or ""> <text it must not have, or ""> <command>...: runs
# the command in the repository and checks its exit status and output.
expect() {
  local name=$1 code=$2 has=$3 hasnt=$4 out actual=0
  shift 4
  out=$(cd "$repo" && "$@" 2>&1) || actual=$?
  if [ "$actual" -ne "$code" ] ||
    { [ -n "$has" ] && ! grep -qF -- "$has" <<<"$out"; } ||
    { [ -n "$hasnt" ] && grep -qF -- "$hasnt" <<<"$out"; }; then
    echo "::error::merge-check, $name: exit $actual (expected $code); output:"
    printf '%s\n' "$out"
    failures=$((failures + 1))
  else
    echo "ok: $name"
  fi
}

# conflicted: `work` and `theirs` both change line two; `theirs` also changes line nine and adds a
# line at the end. The merge stops on the conflict and is resolved to `work`'s side of the whole file,
# which drops line nine's and the last line's changes as well.
conflicted() {
  fresh
  on work 'ours' edit 2 'two, ours'
  on theirs 'theirs' sh -c "$(declare -f edit append); edit 2 'two, theirs'; edit 9 'nine, theirs'; append 'eleven, theirs'"
  git -C "$repo" checkout -q work
  (cd "$repo" && git merge -q --no-edit theirs >/dev/null 2>&1) && return 1
  git -C "$repo" checkout -q --ours f.txt
  git -C "$repo" add f.txt
}

# A merge with no conflicts keeps both sides.
fresh
on work 'ours' edit 2 'two, ours'
on theirs 'theirs' append 'eleven, theirs'
git -C "$repo" checkout -q work
expect 'clean merge' 0 '' 'merge-check' git merge -q --no-edit theirs

# A commit that is not a merge passes.
expect 'not a merge' 0 '' '' sh -c 'echo x >g.txt && git add g.txt && git commit -qm g'

# The conflict resolved to one side: refused, naming the lines and the commit that added them, but
# not line two, which both sides changed.
conflicted
expect 'dropped, refused' 1 'eleven, theirs' 'two, theirs' git commit --no-edit
expect 'dropped, names the commit' 1 ' theirs' '' git commit --no-edit
expect 'dropped, line nine too' 1 'nine, theirs' '' git commit --no-edit
expect 'trailer for another file' 1 'eleven, theirs' '' git commit -qm Merge -m 'Dropped: g.txt: not this one'
expect 'trailer with no reason' 1 'eleven, theirs' '' git commit -qm Merge -m 'Dropped: f.txt'
expect 'trailer in the body, not the last paragraph' 1 'eleven, theirs' '' \
  git commit -qm Merge -m 'Dropped: f.txt: in the body' -m 'More text after it.'
expect 'trailer' 0 '' '' git commit -qm Merge -m 'Dropped: f.txt: the old wording stays'
expect 'replay of the excused merge' 0 '' '' .githooks/merge-check replay HEAD

conflicted
# shellcheck disable=SC2016 # the backticks are the message's, not a command
expect 'trailer in backticks, among others' 0 '' '' \
  git commit -qm Merge -m "$(printf 'Dropped: `f.txt`: kept as it was\nCo-Authored-By: someone <x@example.invalid>')"

# A merge recorded without the hooks (by plumbing), replayed: refused.
conflicted
bad=$(cd "$repo" && git commit-tree -p HEAD -p MERGE_HEAD -m Merge "$(git write-tree)")
expect 'replay of an unchecked merge' 1 'eleven, theirs' '' .githooks/merge-check replay "$bad"
git -C "$repo" merge --abort

# A dropped line that is only whitespace does not count.
fresh
on work 'ours' edit 2 'two, ours'
on theirs 'theirs' sh -c "$(declare -f edit); edit 2 'two, theirs'; edit 8 '   '"
git -C "$repo" checkout -q work
(cd "$repo" && git merge -q --no-edit theirs >/dev/null 2>&1) || true
git -C "$repo" checkout -q --ours f.txt
git -C "$repo" add f.txt
expect 'whitespace only' 0 '' '' git commit --no-edit

# A merge git records itself, with the `ours` strategy: checked in `commit-msg`.
fresh
on work 'ours' edit 2 'two, ours'
on theirs 'theirs' append 'eleven, theirs'
git -C "$repo" checkout -q work
expect 'no conflicts, -s ours, refused' 1 'eleven, theirs' '' git merge -q --no-edit -s ours theirs
# Refused, the merge stays in progress, and `git commit` concludes it.
expect 'no conflicts, -s ours, then a trailer' 0 '' '' git commit -qm Merge -m 'Dropped: f.txt: not wanted here'
git -C "$repo" reset -q --hard HEAD~1
expect 'no conflicts, -s ours, trailer at once' 0 '' '' \
  git merge -q -s ours -m Merge -m 'Dropped: f.txt: not wanted here' theirs

# A file the current side renamed: compared under its new name.
fresh
on work 'ours' sh -c "git mv f.txt g.txt && awk 'NR == 2 { print \"two, ours\"; next } { print }' g.txt >g.new && mv g.new g.txt"
on theirs 'theirs' sh -c "$(declare -f edit append); edit 2 'two, theirs'; append 'eleven, theirs'"
git -C "$repo" checkout -q work
(cd "$repo" && git merge -q --no-edit theirs >/dev/null 2>&1) || true
git -C "$repo" checkout -q --ours g.txt
git -C "$repo" add g.txt
expect 'renamed by the current side' 1 'g.txt' 'two, theirs' git commit --no-edit
expect 'renamed by the current side, trailer' 0 '' '' git commit -qm Merge -m 'Dropped: g.txt: kept as it was'

# Without the `commit-msg` hook (installed before the merge check), `pre-commit` checks instead and
# `pre-merge-commit` says that a merge with no conflicts goes unchecked.
conflicted
rm "$repo/.git/hooks/commit-msg"
expect 'no commit-msg hook, pre-commit refuses' 1 '.githooks/install' '' git commit --no-edit
git -C "$repo" merge --abort
git -C "$repo" checkout -q theirs
expect 'no commit-msg hook, pre-merge-commit says so' 0 'no commit-msg hook' '' \
  sh -c 'git checkout -qb other HEAD~1 && echo y >h.txt && git add h.txt && git commit -qm h && git merge -q --no-edit theirs'

# Installing again changes nothing and moves nothing aside.
expect 'install again' 0 'install: dispatchers for' 'moved' .githooks/install

if [ "$failures" -gt 0 ]; then
  echo "test-merge-check.sh: $failures case(s) failed"
  exit 1
fi
echo 'test-merge-check.sh: all cases passed'
