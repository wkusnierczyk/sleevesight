# Measuring what Sleevesight costs the phone

The method behind the docs page *How it performs* (`docs/performance/`, #626), kept here so the
numbers can be taken again for a later release and compared.

- `snapshot.sh <dir> [<label>]` reads the app's memory, CPU time, network bytes, battery use,
  wakeups, alarms and jobs, its installed size, and the phone's battery, into a new directory. It
  only reads, apart from `dumpsys netstats --poll`, which makes the system count the bytes so far.
- `report.py <before> <after> [--tracks N]` compares two snapshots.
- `startup.sh --yes [<runs>]` times starts (`am start -W`), with the median for each launch state
  (with notification access granted, every start is warm). It force-stops and opens the app,
  so it changes the phone: run it apart from the listening sessions, only with the phone owner's go-ahead.

The release build from Play is measured, on the phone's own settings, over wireless debugging
(Developer options), so that it drains on its battery and not the cable's. Snapshots go in
`.dev/scratchpad/perf/` (not tracked).

## Sessions

Each session is a snapshot before, the listening by hand, and a snapshot after; the version, the
Android version and the settings go on the page with its figures, and the date stays with the
snapshots (their directory names).

1. **Screen off, one hour**: default settings with one AI service switched on (its default model),
   Exa off, music from the usual player, Sleevesight in the background or left on top when the
   screen goes off (both count as off screen), screen off throughout, off the charger. Battery,
   CPU, data, wakeups.
   - **1b.** As 1, with *Look up in the background* on: the heavy case, someone who leaves it
     running on a drive. Saver stays on, so writing stops after its track changes.
2. **On screen, 20 minutes**: as 1, with Sleevesight open on *Now playing* and the screen on.
3. **Data, three runs of 10 tracks each**: insights off; insights with the default AI service;
   insights with the default AI service and Exa web search on. Data and CPU per track.
4. **Idle**: nothing playing, the screen off, for the time it takes (an hour at least): whether the
   app wakes at all (CPU time, wakeups, alarms, jobs).
5. **Memory** while an insight is being written: `snapshot.sh` during the writing, from session 3.
6. **Start-up**: `startup.sh --yes 10`, after the sessions.
7. **Size**: the app info screen's storage figures, before session 1 and after a day's listening.
