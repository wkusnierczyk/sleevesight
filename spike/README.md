# Sleevesight probe

The Phase 0 platform spike (#11): a throwaway app that records what music players expose through Android's media sessions, to settle the **Unverified** items in `design/design.md` (*Platform facts*) and the Phase 0 measurements in `design/architecture.md`. It is removed when the Phase 0 gate passes.

It logs every session event, one JSON object per line, to `files/probe.jsonl` in the app's storage. Nothing leaves the phone.

## Install

From a build:

```sh
./gradlew :spike:installDebug        # with the phone connected and USB or wireless debugging on
```

Or download the `spike-debug-apk` artifact from a CI run and install it on the phone, which is also the restricted-settings test (step 7 below).

Then open **Sleevesight probe**, tap **Notification access**, and switch it on. The top of the screen says whether access is granted and what installed the app.

## What to do

Each step leaves its trace in the log; the screen shows the latest lines.

1. **Metadata.** Play a few tracks in Spotify, then in YouTube Music and a podcast app. Each `session_added` and `track_changed` line lists every metadata key the player sends, including `android.media.metadata.MEDIA_ID`; `metadata_updated` lines are updates to the same track, such as art or the length arriving later, and `metadata_cleared` lines are empty metadata between tracks.
2. **Queue.** Play a playlist in Spotify. `session_added` and `queue_changed` show whether `getQueue()` and `getQueueTitle()` are published.
3. **Transport.** Use **Prev**, **Play**, **Pause** and **Next** under each player. `probe_command` lines record the command; the following `playback_state` lines show whether it worked and which actions the player advertises.
4. **Opening the player.** Tap **Open player** (the session activity), **Open track** (a `spotify:track:` URI, if the media id is one) and **Search** (`spotify:search:`). `open_session_activity` and `deep_link` lines record what resolved and what was sent; **note which screen actually opened**, since Android can block a launch without telling the app.
5. **Track changes (D5).** In Spotify, with crossfade off:
   - let a track end on its own;
   - skip in Spotify itself, early and late in a track;
   - skip from the notification, headphones or a watch, if available;
   - skip with the probe's **Next**.

   Then switch crossfade on (Settings, Playback, Crossfade; try the maximum, 12 s) and let a few tracks end on their own. Each `track_changed` line has a `trackChange` object: the previous and next track, the previous track's remaining time at the change, whether the new track's playback state arrived before its metadata, every playback state in the 3 s before (with positions and times, so the estimate can be recomputed), and whether the probe sent that player a command just before.
6. **Art.** Covered by step 1: an `art_access` line for each art URI a player publishes, saying whether this app could read it, and the bitmap sizes in `values`.
7. **Restricted settings (D7).** Uninstall the probe, install the CI artifact by downloading it in the phone's browser, and try to grant notification access. Note whether Android blocks it and what unlocking takes. If you have a store client that installs APKs (Obtainium, or F-Droid with a repository), repeat through it. `probe_opened` records the installer, the initiating package and, on Android 13+, the package source. The originating package is always "unavailable": Android shows it only to installers.

## Reading the log

Export it from the app (**Export log**), or pull it over adb:

```sh
adb shell run-as io.github.wkusnierczyk.sleevesight.spike cat files/probe.jsonl > probe.jsonl
```

**Clear log** starts a fresh one. The findings go into `design/design.md` under #12.
