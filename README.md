# Sleevesight

Liner notes for whatever you're playing. Sleevesight is an open-source Android app that reads what your music player is playing and shows short, sourced insights about the song, its artists, its album and its genres.

> **Status:** Phase 1, Android MVP, is under way. The platform spike and the pipeline are done: the pipeline runs on a computer over a test playlist ([Pipeline CLI](#pipeline-cli)), and their findings are in the design. The app so far: its first-run screen says what leaves the phone and leads to notification access, its settings screen holds the choices below and your keys, and it looks the playing track up in the sources you switched on and, when you tap Write, has your AI service write the insight; under the default *Write insights automatically*, every track after that tap is written too, for as long as the app's process lives; under *When I skip*, only the tracks you skip to in your player, told from tracks that end on their own by the time left and, for Spotify, the playback states just before the change, with *Crossfade* (autodetected by default). While it is not on screen, it sends nothing, the covers included, unless you turn on *Look up in the background*. Its home screen shows the playing track in colors taken from its cover: the player and its state (a tap opens the player), the cover with the playback position, the title, artists, album and year of first release, the genre labels (a tap on one shows where it came from), the insight, whose citations jump to their evidence lines, and the sources, with what each found, rejected or could not verify, and a chip per source that shows or hides its lines. An insight already written stays when a changed key or setting means it would now be written from other evidence, with Rewrite beside it; nothing is written again unless you tap, and if writing it again fails, the screen says so and keeps the one written before. While an insight is being written, Stop ends the call, and nothing of it is kept; one that failed or was stopped offers Write again, each try a new call on your key, never made on its own. The design is in [design/design.md](design/design.md), the architecture in [design/architecture.md](design/architecture.md), and an interactive [simulator](https://sleevesight.app/design/simulator.html) simulates the full flow. Its lookups are frozen, and it has no AI service: the insight cards show the first facts found in place of a written insight, and the genre and sounds-like cards stay unwritten.

## What it does

- Notices the track change in Spotify, or any other player that publishes an Android media session.
- Shows the cover, title, artists, album and release year, plus colored genre labels.
- Writes a few sentences on why the track is interesting. Every factual sentence links to the source it came from.
- Opens deeper insight on the song, each artist, the album, a genre, or a similar artist, either as separate pages or as boxes that expand in place.
- Lets you play, pause and skip from the app, and jump back to the player for any song, artist or album.

## How it works

1. **Read.** A notification listener gives the app the player's media session: title, artists, album, duration and album art. Sleevesight never touches the audio.
2. **Look up.** The track's title and artists are cleaned up: tags the player appends, such as "2006 Remaster" or "feat. Van Morrison", are set aside, and featured artists are added to the artist list, so the song can be found under its plain name. It is then matched against the sources you switched on: MusicBrainz, Wikipedia, Last.fm, Discogs, and open web search. Matches that fail an identity check are rejected and shown as rejected, not used quietly.
3. **Write.** The AI service you chose turns the retrieved evidence into the insight, citing evidence ids. It is not allowed to add facts of its own unless you turn that on, and then they are marked unverified.
4. **Check.** Before anything is shown, the citations are checked in code. Uncited sentences and citations to missing evidence are flagged.

When little is documented about a track, the insight is short instead of filling the gap. When the sources give nothing to write from, Sleevesight says so and makes no AI call. With the model's own knowledge on, the album the player reports is enough for a call, once the player has also reported the artists: the model may then add what it knows, marked unverified.

## You choose

- **Where to look.** Each source is a switch, and the model's own knowledge is a separate one, off by default. MusicBrainz and Wikipedia need no key; Last.fm and web search need your own API key. MusicBrainz is also how a track is identified, and gives its credits and release dates: with it off, the other sources match by name only, unchecked against MusicBrainz's identity of the recording and artists. Web search has a switch per engine, each with its key; Exa is the only one so far, and an engine joins the list when it is built. Discogs comes in Phase 3.
- **Where genre labels come from.** The colored labels are the artists' tags at Last.fm and at MusicBrainz, kept only when they name a genre in a fixed list; each is a switch. With both on, Last.fm's labels come first and MusicBrainz's fill the rest, five at most, and a label both give is shown once, crediting both. MusicBrainz's tags are by its contributors under CC BY-NC-SA 3.0: they are used for labels only, never sent to the AI service, and each label from them credits and links MusicBrainz. Web search gives no tags. The credits, at the end of the settings, list each data source with its licence.
- **Which AI.** Anthropic, OpenAI, Google, or a local model through Ollama, with your own API key and model. Only Anthropic is built so far; the others, local models included, come in Phase 3, unless one more comes in Phase 1.
- **When and how it writes.** Each insight is a call on your key. By default one is written for every track, and the next track in the player's queue is looked up ahead, so its sources come sooner when it starts. You can have insights written only when you skip, or only when you tap Write, and turn the looking ahead off. No insight is written ahead: the player's queue gives no album or length, so an insight written from it could not be reused when the track plays, and would be paid for twice. In every mode, lookups and writing happen only while the app is on screen, unless you turn on *Look up in the background*, and after the app starts in a new process, nothing is written until your first action in it. That action lasts as long as the process, which the notification listener usually keeps alive after you leave the app, often for days: coming back to the app, from the player, with the screen turned on again, or from the launcher, keeps it, and writing goes on without a new tap. A restart of the phone, an update of the app, or the system ending the process (often a swipe from Recents) starts afresh. A style setting, with presets or your own words, sets the length and tone; it cannot loosen the citation rules.
- **Which players.** Spotify by default; any player with a media session can be followed.
- **Spotify account.** Optional. Connecting it, with your own Spotify developer app, lets Sleevesight see the whole playlist or album being played. Without it, the app knows the tracks it has seen play.

### Settings

Each change is saved at once; one that cannot be saved leaves the setting as it was, and the settings say so. The defaults, and why:

| Setting | Default | Why |
|---|---|---|
| AI service, model, API key | No service until you choose one; then Anthropic's cheapest current model, Claude Haiku 5.5 | The key and its bill are yours, and an insight narrates a few lines, which needs no larger model. The model list comes from the service, with your key |
| Reasoning effort | Low | An insight is short and waited for; the citation check, not the model's deliberation, keeps it honest |
| Players to follow | Spotify on | A player appears, switched off, once it has had a media session (open, playing or paused) while Sleevesight has notification access; switching it on follows it at once |
| Where to look | Every source on except the model's own knowledge | Last.fm and web search also need your key, entered under their switches |
| Web search engines | Exa on | It searches only once its key is saved; the switch turns it off without removing the key |
| Genre labels | MusicBrainz and Last.fm on | MusicBrainz needs no key, and without it a new user, who has no Last.fm key, gets no labels at all (on the test playlist it labels 29 of 78 tracks alone). Last.fm gives labels once its key is saved, and only while Last.fm is on under *Where to look*; so does MusicBrainz. If MusicBrainz has not confirmed how its tags may be used by the Play release, they are off by default there |
| Write insights | On | Off makes no AI calls at all; the evidence is still shown |
| Write insights automatically | On every track change | The card is written as each track starts, with its lookups mostly done ahead |
| Crossfade | Autodetect | Used to tell your skips from natural ends under *When I skip* |
| Prepare the next track | Evidence | Looks the next track up ahead, by name, so MusicBrainz, the slow source, has mostly answered when it plays; no insight is written ahead, and none is paid for |
| Look up in the background | Off | No paid or network activity nobody is looking at |
| How long findings are kept | MusicBrainz and Wikidata 30 days, Wikipedia and Last.fm 7, web search 1 | Freshness against repeated requests and paid searches |
| Keep history for | 90 days | The tracks played, and the track changes Sleevesight will learn your skips from, which needs a season of them rather than a week; 90 days keeps them to a few MB. Counted back from today. From a week to a year, or *Forever* |
| Start a new session after a break of | 30 minutes | Longer than a pause to answer the door, shorter than the gap between the morning's listening and the evening's. Counted from when the last track was heard; a change applies from the next track that starts |
| Diagnostics | Off | A developer setting, at the end of the settings: while it is on, they lead to the diagnostics screen |

The diagnostics screen shows what Sleevesight did for the last few hundred steps since it started (each source's outcome and time, cache hits, AI calls, the citation check's counts), with the caches' sizes; the track changes recorded, with what skip detection learns from; and what skip detection has learned (in Phase 1, the crossfade of each player), with a reset that lasts until it learns again, and keeps the history. The steps are kept in memory only. *Export* writes all of it, every track change included, to a file you choose; nothing else of it leaves the phone.

The history stays on the phone. *Clear history*, with the history settings, deletes the tracks played and the track changes together, once you confirm. Old entries go by the phone's clock: if the clock is moved while Sleevesight is running, nothing more is deleted until it next starts, but a clock set ahead while it was not running, or across a restart of the phone, deletes by that clock.

Your API keys are encrypted with a key kept in the phone's Android Keystore, are never shown again once saved, and are never logged. Neither they nor the settings are part of Android's backup or a transfer to a new phone. If a saved key can no longer be read, as after the phone's Keystore is reset, the settings say so, and you enter it again. If saving fails or keeps failing, *Reset saved keys* removes every saved key, with the phone's key that protects them, so you can enter them afresh; Sleevesight never deletes them on its own.

## Privacy

Track, artist and album names for the playing track go to the sources you enabled, and, with what those sources found and your style setting, to the AI service you chose. By default, the title and artists of the next track in the player's queue also go to MusicBrainz, if you switched it on, ahead of its play. If you connect a Spotify account, the app also asks Spotify for the playlist or album being played. When a player gives the cover only as a web address, the app fetches it from the server that address names, while it is on screen (or with *Look up in the background* on), and the address itself may identify the album or episode. Nothing else leaves the phone. No accounts of our own, no telemetry, no analytics, no ads. Keys are stored encrypted on the device.

## Roadmap

| Phase | Output | Status |
|---|---|---|
| 0. Platform spike | Verified behavior of media sessions, queues, transport controls and deep links on real players | Done |
| 0.5. Pipeline CLI | Coverage report and generated insights over a test playlist, from the shared pipeline module | Done |
| 1. Android MVP | Now-playing screen, sources, grounded insight, settings | Under way |
| 1.5. Play release | Signed app bundle, store listing and privacy policy, Google Play's review | |
| 2. Detail and discovery | Song, artist and album insight; genre and sounds-like cards; layout setting; player controls | |
| 3. Context and reach | Optional Spotify account, more players, more AI providers and local models, Discogs | |

Details and the reasoning behind each decision are in the [design document](design/design.md); the work to the MVP and its release, with its dependencies, is in the [plan](design/plan.md).

## Requirements

Android only. iOS does not let one app read what another app is playing. Sleevesight is to be distributed through Google Play.

## Building

Requires JDK 21 or later (Robolectric's Android 16 runtime, used by the app's tests, needs it) and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

```sh
./gradlew build                                              # compile, tests, Detekt, ktlint, Android lint
./gradlew checkDependencies checkManifest checkModuleGraph   # the structural checks
./gradlew :app:assembleDebug                                 # the debug APK, in app/build/outputs/apk/debug/
```

The structural checks enforce the design's constraints: only allowlisted dependencies (`config/dependency-allowlist.txt`), only allowlisted permissions (`config/permission-allowlist.txt`), and the module boundaries (`config/module-graph.properties`). After changing dependencies, refresh the lockfiles with `./gradlew resolveAndLockAll --write-locks`. CI keeps the debug APK of each run as a workflow artifact, `sleevesight-debug-apk`.

## Pipeline CLI

`:cli` runs the pipeline over the test playlist (`fixtures/below-soundtrack/`) and prints a coverage report: per source the count of each outcome, per track the evidence lines and genre labels, the insights with their grounding-check counts, the Phase 0.5 gate's criteria, and the diagnostics log. The exit status is 1 when a gate criterion fails, 2 for a usage error or a source request with no recording, and 3 when a criterion the run should have checked could not be (a source unreachable or failed, an insight call failed).

```sh
./gradlew :cli:run --args="replay"                         # recorded responses only: no network, no keys
./gradlew :cli:run --args="live --tracks 1,63"             # live sources and AI, with your keys
./gradlew :cli:run --args="live --tracks all --out .dev/coverage.md"
./gradlew :cli:run --args="record --record musicbrainz"    # refresh fixtures/http/ from the live service
```

`replay` runs the design's regression tracks (the default `--tracks regression`) from `fixtures/http/`, and fails on any source request with no recording (a missing recorded AI reply is reported, not fatal); the build runs it as a test. `live` reads `SLEEVESIGHT_ANTHROPIC_API_KEY`, `SLEEVESIGHT_EXA_API_KEY` and `SLEEVESIGHT_LASTFM_API_KEY` from the environment or from `~/.config/sleevesight/keys.env`; a source or the AI without a key is reported as off. Each insight is a paid call on your key, and each web search a paid search; the cache (`.dev/cli/cache.db`) keeps web search results for a day, as it keeps the other sources' for theirs, so a track looked up again within that day costs no new search (when MusicBrainz was reachable the first time: without its resolution nothing is cached). The report never quotes a web search snippet (Exa's terms), and it marks the insights written from web results, which are not to be committed or posted. Run it with no arguments for the options.

## License

[GPL-3.0](LICENSE).
