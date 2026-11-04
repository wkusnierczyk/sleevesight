# Ads in media sessions

What players report while they play an ad, for `AdDetection` (#880) and its test
(`AdFixturesTest` in `:app`), which reads every `.tsv` file here and checks that each line is told
as its first column says.

**Only synthetic files so far.** `spotify-free.synthetic.tsv` is written by hand from the candidate
signals in #880 (the advertisement flag, a `spotify:ad:` media or queue item id), not captured from a
player. The capture of a Spotify Free account playing an ad, on a phone with a debug build, is still
to come (#897); it will confirm these signals or add others.

## Adding a capture

1. On a debug build, play Spotify Free until an ad plays, with `adb logcat -s SleevesightDump`
   running (`SessionDump`: each metadata report with every key, each playback state with its active
   queue item, each queue with its items' media ids).
2. Copy the metadata reports of the tracks before and after the ad and of the ad itself into a new
   file, `<player>.captured.tsv`, one line each, tab-separated:
   `ad` or `track`, the player's package, the metadata as `SessionDump` logs it (`{key=value, …}`),
   and the media id of the queue item the state then names as active (from the state's `activeItem`
   and the queue's `id:mediaId:title` items), or `-`.
3. Lines starting with `#` are comments: say where and when it was captured. Leave out anything
   personal (the account's name in a title, say).
4. If the test fails on a line, the signals are not the ones `AdDetection` reads: change it to what
   the capture shows, in the same pull request.
