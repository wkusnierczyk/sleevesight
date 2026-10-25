<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/logo/tile-dark.svg">
    <img src="assets/logo/tile-light.svg" alt="Sleevesight" width="112" height="112">
  </picture>
</p>

<h1 align="center">Sleevesight</h1>

<p align="center"><b>Insights on what's playing.</b></p>

<!-- chips: images from GitHub and shields.io, left out of the site's copy, which cuts from here to the closing marker (`readme-page.html`); keep both markers -->
<p align="center">
  <a href="https://github.com/wkusnierczyk/sleevesight/actions/workflows/ci.yml?query=branch%3Amain"><img src="https://github.com/wkusnierczyk/sleevesight/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI on main"></a>
  <a href="https://github.com/wkusnierczyk/sleevesight/actions/workflows/release.yml"><img src="https://github.com/wkusnierczyk/sleevesight/actions/workflows/release.yml/badge.svg" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License: GPL-3.0"></a>
</p>
<!-- /chips -->

Sleevesight is an open-source Android app that reads what your music player is playing and shows short, sourced insights about the song, its artists, its album and its genres. Every factual sentence cites the source it came from, and the citations are checked in code.

**Where it stands.** The Android MVP is done, and the app is on its way to Google Play: builds go to Play's internal test track, and a closed test comes next (Phase 1.5, [Roadmap](#roadmap)). The [product page](https://sleevesight.app/) shows what it does; the [documentation](https://sleevesight.app/docs/) says how to use it, how it works and how it is made.

## Documentation

- [A typical journey](https://sleevesight.app/docs/journey/), from installing the app to using it day to day.
- [Usage](https://sleevesight.app/docs/tutorials/usage/): the home screen, the insight and its citations, the sources, writing and rewriting, the players, and what happens offline or when something fails.
- [Settings](https://sleevesight.app/docs/tutorials/settings/): every setting, its default and why, and what changing it sends or keeps; the API keys, and how to enter them.
- [Architecture](https://sleevesight.app/docs/architecture/), [dependencies](https://sleevesight.app/docs/dependencies/), [solutions](https://sleevesight.app/docs/solutions/) and [process](https://sleevesight.app/docs/process/): how the app is put together, what it is built on, what it solves in its own way, and how it is built, reviewed and released.
- [Privacy policy](https://sleevesight.app/privacy.html).
- The design, for developers: [design/design.md](design/design.md), [design/architecture.md](design/architecture.md), and the [plan](design/plan.md).

## What it does

- Notices the track change in Spotify, or any other player you follow that publishes an Android media session.
- Shows the cover, title, artists, album and release year, plus the genre labels of the track, its artists and its album, folded to the most specific level found, each telling where it came from.
- Writes a few sentences on why the track is interesting. Every factual sentence links to the source it came from.
- Shows the sources themselves, with what each found, rejected or could not verify.
- Opens the player with a tap on its name.

Planned ([Roadmap](#roadmap)): deeper insight on the song, each artist, the album, a genre or a similar artist; follow-up questions; playing, pausing and skipping from the app.

## How it works

1. **Read.** A notification listener gives the app the player's media session: which player it is; the title, artists, album, duration and cover; whether it is playing, and how far in; and the queue up next, with its name. Sleevesight never touches the audio.
2. **Look up.** The track's title and artists are cleaned up: tags the player appends, such as "2006 Remaster" or "feat. Van Morrison", are set aside, and featured artists are added to the artist list, so the song can be found under its plain name. It is then matched against the sources you switched on: MusicBrainz, Wikipedia and Wikidata, Last.fm, and the Exa web search. Matches that fail an identity check are rejected and shown as rejected, not used quietly. A source that cannot be reached shows as unreachable; while the phone is offline the app says so, and once it is back online the playing track's unreachable sources are asked again.
3. **Write.** The AI service you chose turns the retrieved evidence into the insight, citing evidence ids. It is not allowed to add facts of its own unless you turn that on, and then they are marked unverified.
4. **Check.** The citations are checked in code: uncited sentences and citations to missing evidence are flagged on the card, and a note under the insight says how many sentences the sources do not back, shown only when there are any.
5. **Report.** *Report a problem*, under the insight, sends the developer a report of an insight that is wrong or offensive, by email from your own mail app, once you have seen what it holds ([Privacy](#privacy)).

When little is documented about a track, the insight is short instead of filling the gap, for instance by restating the album a source has already named. The insight stays on the track: what the sources say about the artist is sent to the AI service only for a track that has little evidence of its own, and a fact about the artist that one insight told is not sent again for the rest of the listening session, so an album played through does not repeat its artist's biography on every track. When the sources give nothing to write from, Sleevesight says so, and makes no AI call when it can tell beforehand. With the model's own knowledge on, the album the player reports is enough for a call, once the player has also reported the artists: the model may then add what it knows, marked unverified.

## You choose

Each of these is a setting; the [settings page](https://sleevesight.app/docs/tutorials/settings/) explains every one, with its default and why. The settings are in sections, each folded under its name and a line on what it holds: *Players*, *Sources*, *AI services*, *Secrets*, *Genres*, *Insights*, *History*, *Appearance* and *About*, with *Developer* after seven taps on *Version*, as on Android (from the start in the test build on Google Play's testing tracks).

- **Sources.** Each source is a switch, with a link to its website, and the AI service's own knowledge is a separate one, off by default. MusicBrainz and Wikipedia need no key; Last.fm and the Exa web search need your own API key. Discogs comes in Phase 3.
- **Genres.** *Show genres* off asks no source for genres, and the home screen shows none. The genre labels are the tags Last.fm and MusicBrainz give the track, its artists and its album, kept only when they name a genre in a fixed list; each source is a switch. MusicBrainz gives the track's (with no request of its own) and the artists', Last.fm all three. MusicBrainz's tags are by its contributors under CC BY-NC-SA 3.0: they are used for labels only, never sent to the AI service, and each label from them credits and links MusicBrainz.
- **Which AI.** Anthropic, OpenAI or Google (the Gemini API), with your own API key, model (shown by its name) and reasoning effort; one service at a time, and with none on, nothing is written. A local model through Ollama comes in Phase 3. Google's [Gemini API terms](https://ai.google.dev/gemini-api/terms) set what its free tier allows: on it, outside the EEA, Switzerland and the UK, Google uses what is sent to improve its products, and for apps made available to users in those three, the terms allow only the paid tier. Each service is used under its provider's terms, on your own account, and its key dialog says, in a line with a link, what they require: Anthropic's set no minimum age and are not for consumer use, though Anthropic welcomes individuals on the API; OpenAI's let minors use the API only with a parent's or guardian's consent; Google's require you to be 18 or older, and are not for consumer use. Sleevesight itself sets no minimum age beyond its privacy policy's (it is not directed at children under 13); the [privacy policy](https://sleevesight.app/privacy.html#children-and-the-ai-services-own-terms) quotes the terms, as checked on 24 October 2026.
- **When it writes.** Each insight is a call on your key (now and then two). By default one is written for every track change, once you have tapped *Write insight* in the app's current process; you can have insights written only when you skip, or only when you tap (*Reactive*). Lookups and writing happen only while the app is on screen, unless you turn on *Look up in the background*, and automatic writing pauses after a few track changes away from the app (*Saver*).
- **What it tells.** Within a listening session, each fact about an artist is told once, and a track with enough about the song itself gets none (*Tell an artist's facts once per session*, on by default), so an album played through does not repeat its artist on every track. Off, every insight may tell all of them. Each artist is looked up once either way when *Keep what sources said about each artist* is on.
- **Which players.** Spotify by default; any player whose media session reports music can be followed, and opened from its row. Calls from the phone app are never read. Sleevesight reads the phone's media session, not Spotify's Web API, so following Spotify needs no Spotify developer app and no Spotify login.
- **What is kept.** The history and the sources' findings stay on the phone, for as long as the settings say; *Keep what sources said about each artist* is off by default, since it amounts to a record of the artists you play.
- **How it looks.** Light or dark as the phone is, or always one of them (*Theme*); the app's own font or the phone's; and the logo's intro at start, or none.
- **Your API keys.** Typed, scanned from a QR code (one key, or several at once from a setup code made on a computer at [sleevesight.app/api-keys](https://sleevesight.app/api-keys)), filled from your password manager, which offers its saved entries on each key's field but is not asked to save a key, or typed on a computer over Bluetooth ([sleevesight.app/keyboard.html](https://sleevesight.app/keyboard.html)); scanning and typing on a computer are icons in the key's field. They are encrypted with a key in the phone's Android Keystore, never shown again once saved (the field's eye shows only a key being entered), never logged, and left out of backups; each can be removed, or all at once (*Purge keys*). In development, the CLI's `keys-qr` draws a setup code too ([Pipeline CLI](#pipeline-cli)).

## Privacy

The playing track's title and artists go to the sources you enabled (Wikidata gets only identifiers; for the genre labels, Last.fm also gets the album), and its title, artists and album, with what those sources found, to the AI service you chose. By default, the title and artists of the next track in the player's queue also go to MusicBrainz, if you switched it on, ahead of its play. When a player gives the cover only as a web address, the app fetches it from the server that address names, while it is on screen (or with *Look up in the background* on), and the address itself may identify the album or episode. Each API key goes only to the service it belongs to. *Keep what sources said about each artist*, off by default, trades the two: on, what the sources said about each artist is kept on the phone and fewer requests about artists go out; off, none of it is kept and each track's artists are looked up again. Each track's own lookups send its artists' names either way. Nothing else leaves the phone, unless you export the diagnostics to a file yourself, or report a problem with an insight: *Report a problem* shows the whole report (the reason and your note; the track as the player reported it, with its MusicBrainz ids; the insight and the evidence it was written from; the prompt version, AI service, model and effort; never your keys, the history or the settings) and hands it to your mail app, addressed to support@sleevesight.app; it reaches the developer only if you send it, and until then your mail app handles it as any message you write, drafts included. In developer mode, *Report button* (on by default only in the test build on Google Play's testing tracks) puts a button on the home screen that does the same for a tester's report of the app's state: when it was tapped, the version, the settings (never your keys), what the player reported and what the app made of it, and the diagnostics, with the latest track changes only if you add them; you can edit or leave out each part before your mail app opens. Nothing the app sends on its own reaches the developer. No accounts and no ads, and no telemetry, crash reporting or analytics of its own; if any is added, it will never carry your data. Keys are stored encrypted on the device, and the settings remove any one of them, or all at once. The full [privacy policy](https://sleevesight.app/privacy.html) is on the site, with where Sleevesight stands under the GDPR (who is responsible for what, your rights and how to use them, transfers outside the EU) and the EU AI Act (the app's role, and how it shows that a model writes the insights). The first-run screen and the settings link to it.

## Roadmap

| Phase | Output | Status |
|---|---|---|
| 0. Platform spike | Verified behavior of media sessions, queues, transport controls and deep links on real players | Done |
| 0.5. Pipeline CLI | Coverage report and generated insights over a test playlist, from the shared pipeline module | Done |
| 1. Android MVP | Now-playing screen, sources, grounded insight, settings | Done |
| 1.5. Play release | Signed app bundle, store listing and privacy policy, Google Play's review; OpenAI and Google as AI services, beside Anthropic | Under way |
| 2. Detail and discovery | Song, artist and album insight; genre and sounds-like cards; lyrics language; follow-up questions to the AI; export of the tracks played, with their insights; the insights' style, with presets or your own words; layout setting; player controls | |
| 3. Context and reach | Optional Spotify account, more players, a local model through Ollama, Discogs | |

Details and the reasoning behind each decision are in the [design document](design/design.md); the work to the MVP and its release, with its dependencies, is in the [plan](design/plan.md).

## Requirements

Android only. iOS does not let one app read what another app is playing. Sleevesight is to be distributed through Google Play.

## Building

Requires JDK 21 or later (Robolectric's Android 16 runtime, used by the app's tests, needs it) and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

```sh
./gradlew build                                                                  # compile, tests, Detekt, ktlint, Android lint
./gradlew checkDependencies checkDependencyPage checkManifest checkModuleGraph   # the structural checks
./gradlew :build-logic:functionalTest                                            # the build logic's functional tests (needs Maven Central)
./gradlew :app:assembleDebug                                                     # the debug APK, in app/build/outputs/apk/debug/
```

The structural checks enforce the design's constraints: only allowlisted dependencies (`config/dependency-allowlist.txt`), each shipped one on the [dependencies page](https://sleevesight.app/docs/dependencies/) (`docs/dependencies/index.md`), only allowlisted permissions (`config/permission-allowlist.txt`), and the module boundaries (`config/module-graph.properties`). After changing dependencies, refresh the lockfiles with `./gradlew resolveAndLockAll --write-locks`. CI keeps the debug APK of each run that builds as a workflow artifact, `sleevesight-debug-apk`. A pull request that changes only documents the build never reads, such as this README or the release notes, skips the build; the list is in `.github/workflows/ci.yml`.

In October 2026, on GitHub's standard 4-core Linux runner, a CI run that builds took a minute or a minute and a half when no code had changed and it restored a current build cache. It took 4 to 11 minutes when code had changed or the restored cache was behind `main`, depending on how much of the module graph had to rebuild. In a run that rebuilt nearly everything (about 11 minutes), `./gradlew build` took about 8¾ minutes and the build logic's functional tests about 1¾; setting up the job and restoring the caches, the structural checks, the debug APK and its upload took under a minute together. Within that `build`, the longest stretches were the pipeline modules' compilation, checks and tests (about 3 minutes, in parallel) and compiling and running the app's Robolectric tests (about 2¾ minutes).

## Pipeline CLI

`:cli` runs the pipeline over the test playlist (`fixtures/below-soundtrack/`) and prints a coverage report: per source the count of each outcome, per track the evidence lines and genre labels, the insights with their grounding-check counts, the Phase 0.5 gate's criteria, and the diagnostics log. The exit status is 1 when a gate criterion fails, 2 for a usage error or a source request with no recording, and 3 when a criterion the run should have checked could not be (a source unreachable or failed, an insight call failed).

```sh
./gradlew :cli:run --args="replay"                         # recorded responses only: no network, no keys
./gradlew :cli:run --args="live --tracks 1,63"             # live sources and AI, with your keys
./gradlew :cli:run --args="live --tracks all --out .dev/coverage.md"
./gradlew :cli:run --args="record --record musicbrainz"    # refresh fixtures/http/ from the live service
./gradlew :cli:run --args="live --tracklist fixtures/tracklists/sundown.json"  # an album, in its order
./gradlew :cli:run --args="live --repeat 3"                # the regression tracks three times, for the gate
```

`replay` runs the design's regression tracks (the default `--tracks regression`) from `fixtures/http/`, and fails on any source request with no recording (a missing recorded AI reply is reported, not fatal); the build runs it as a test. `live` reads `SLEEVESIGHT_ANTHROPIC_API_KEY`, `SLEEVESIGHT_OPENAI_API_KEY`, `SLEEVESIGHT_GOOGLE_API_KEY`, `SLEEVESIGHT_EXA_API_KEY` and `SLEEVESIGHT_LASTFM_API_KEY` from the environment or from `~/.config/sleevesight/keys.env`; a source or the AI without a key is reported as off. The AI service is `--provider anthropic`, `openai` or `google`: by default the one `--record` names, else Anthropic. `openai` runs only `live`, since only Anthropic's and Google's replies are recorded (`--record anthropic`, `--record google`) and replayed. Each insight is a paid call on your key, and each web search a paid search; the cache (`.dev/cli/cache.db`) keeps web search results for a day, as it keeps the other sources' for theirs, so a track looked up again within that day costs no new search (when MusicBrainz was reachable the first time: without its resolution nothing is cached). What a source says about an artist (Last.fm's answers, the Wikipedia artist article) is kept per artist too, so another track by the same artist does not ask for it again; the report counts those hits. The report never quotes a web search snippet (Exa's terms), and it marks the insights written from web results, which are not to be committed or posted. `--tracklist` runs the tracks of a JSON file in place of the playlist, one after another as a player would report them (an album played through, or one artist's playlist; `fixtures/tracklists/`), and the report's *Artist-level evidence* section shows, per track, how much of the evidence and of the prompt is about the artist and which artist facts each insight cites again from the ones before it ([#239](https://github.com/wkusnierczyk/sleevesight/issues/239)). `--artist-facts-every-track` gives every insight all its artist's facts, as the app's *Tell an artist's facts once per session* off does, and writes every insight afresh, since the cache keys a card by the evidence, not the setting ([#407](https://github.com/wkusnierczyk/sleevesight/issues/407)); a default run after it may be served its cards, so compare with `--cache none` or a fresh `--cache` file. It is for `live`: the recorded replies `replay` serves answer the default prompts, so with it a track whose default prompt left lines out has no recorded reply. `--repeat <n>` runs the tracks n times, each pass a listening session of its own with every insight written afresh, and the gate pools the passes: the model's replies vary from one run to the next, so uncited sentences in live replies are judged over 50 sentences or more, from one pass or several (one pass of `--tracks all` is enough; the regression tracks need about three); on fewer, the run fails only at 15 percent uncited or more, and otherwise the report gives their count without a verdict, and how many passes would make the sample ([#399](https://github.com/wkusnierczyk/sleevesight/issues/399)). Run it with no arguments for the options.

`keys-qr` draws a setup code of your keys in the terminal, for the app's *Load keys*: the keys from the environment or the keys file, as `live` reads them; with `--keys <file>`, from that file alone, never the environment; or, with `--anthropic-from`, `--openai-from`, `--google-from`, `--exa-from` or `--lastfm-from`, only the keys in the files given, `-` for standard input (not combined with `--keys`). That way a key you hand someone else, such as a tester's, is neither saved beside your own nor typed on the command line, where the shell history would keep it. Nothing is written to a file or sent anywhere, and no key is printed as text. A key that does not have its service's form is refused. Clear the terminal once the code is scanned.

```sh
./gradlew -q --console=plain :cli:run --args="keys-qr"
pbpaste | ./gradlew -q --console=plain :cli:run --args="keys-qr --anthropic-from -"   # a key copied from the console
```

## License

[GPL-3.0](LICENSE), except the two photos in `assets/brand/`, each under its own licence ([listed there](https://github.com/wkusnierczyk/sleevesight/tree/main/assets/brand)).
