<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/logo/mark-dark.svg">
    <img src="assets/logo/mark-light.svg" alt="Sleevesight" width="208" height="112">
  </picture>
</p>

<p align="center"><b>Insights on what's playing</b></p>

<!-- chips: images from GitHub and shields.io, left out of the site's copy, which cuts from here to the closing marker (`readme-page.html`); keep both markers -->
<p align="center">
  <a href="https://github.com/wkusnierczyk/sleevesight/actions/workflows/ci.yml?query=branch%3Amain"><img src="https://github.com/wkusnierczyk/sleevesight/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI on main"></a>
  <a href="https://github.com/wkusnierczyk/sleevesight/actions/workflows/release.yml"><img src="https://github.com/wkusnierczyk/sleevesight/actions/workflows/release.yml/badge.svg" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License: GPL-3.0"></a>
</p>
<!-- /chips -->

Sleevesight is an open-source Android app that reads what your music player is playing and shows short, sourced insights about the song, its artists, its album and its genres. Every factual sentence cites the source it came from, and the citations are checked in code.

**Where it stands.** The Android MVP is done, and the app is in closed testing on Google Play, which testers join by invitation, before its public release (Phase 1.5, [Roadmap](#roadmap)). For now, it is Android only: iOS does not let one app read what another app is playing. The [product page](https://sleevesight.app/) shows what it does; the [documentation](https://sleevesight.app/docs/) says how to use it, how it works and how it is made.

## What it does

- Notices the track change in Spotify, or any other player you follow that publishes an Android media session.
- Shows the cover, title, artists and album, with *Dates*, *Genres* and *Lyrics* buttons: when the album and the track came out, the track's genres with what each one is, and links to the lyrics; when the sources have nothing, *Search with AI* asks your AI service.
- Writes a few sentences on why the track is interesting, each factual one linked to the source it came from.
- Shows the sources behind the insight: what each one found, and where.
- Reads the insight aloud, in the phone's own voice, for listening without looking at the phone.

[Usage](https://sleevesight.app/docs/tutorials/usage/) goes through each part of the app. Planned ([Roadmap](#roadmap)): deeper insight on the song, each artist, the album, a genre or a similar artist; follow-up questions; playing, pausing and skipping from the app.

## How it works

1. **Read.** A notification listener gives the app the player's media session: the title, artists, album and cover. Sleevesight never touches the audio, and never reads your notifications ([why it needs notification access](https://sleevesight.app/docs/faq/)).
2. **Look up.** The title and artists are cleaned up and matched against the sources you switched on: MusicBrainz, Wikipedia and Wikidata, Last.fm, and the Exa web search. Matches that fail an identity check are never used.
3. **Write.** The AI service you chose turns what the sources found into the insight, citing it line by line. It is told to add no facts of its own unless you allow it, and those it adds are then marked unverified.
4. **Check.** The citations are checked in code: a sentence with no citation, or one citing evidence that does not exist, is flagged.
5. **Report.** *Report a problem* sends the developer an insight that is wrong or offensive, from your own mail app, once you have seen what it holds.

[Architecture](https://sleevesight.app/docs/architecture/) shows the parts and what talks to what.

## You choose

Every choice is a setting, with a reasoned default:

- **Players:** Spotify by default; any player that reports music can be followed.
- **Sources:** each one a switch; MusicBrainz and Wikipedia need no key, Last.fm and Exa your own.
- **AI service:** Anthropic, OpenAI or Google, with your own key, model and reasoning effort.
- **When it writes:** on every track change, only on a skip, or only when you tap *Write insight*.
- **History:** what is kept on the phone, for how long, and *Clear history*.
- **Keys:** typed, scanned from a QR code, or typed on a computer; kept encrypted on the phone.

[Setting up external services](https://sleevesight.app/docs/services/) gets the keys and switches the services on; [Settings](https://sleevesight.app/docs/tutorials/settings/) explains every setting; [What it costs you](https://sleevesight.app/docs/costs/) lists each service's prices and free tier. Sleevesight itself charges nothing.

## Privacy

No accounts, no ads, and no telemetry, crash reporting or analytics of its own. Your API keys are encrypted with a key in the phone's Android Keystore and go only to the service they belong to. The playing track goes to the sources and the AI service you switched on, and a cover the player gives only as a web address is fetched from that address’s server; the history and the insights are kept on the phone. *Read aloud* hands the insight to the phone’s text-to-speech engine, which may send it to its own server. Nothing the app sends on its own reaches the developer; only a report you send does.

The [privacy policy](https://sleevesight.app/privacy.html) says what leaves the phone and to whom, and where Sleevesight stands under the GDPR and the EU AI Act.

The [FAQ](https://sleevesight.app/docs/faq/) says what notification access lets the app read.

## Documentation

Using the app:

- [A typical journey](https://sleevesight.app/docs/journey/), from installing the app to using it day to day.
- [Setting up external services](https://sleevesight.app/docs/services/): which services need a key, where to get it, and how to enter it.
- [Usage](https://sleevesight.app/docs/tutorials/usage/): each part of the app in detail.
- [Settings](https://sleevesight.app/docs/tutorials/settings/): every setting, its default and why.
- [Frequently asked questions](https://sleevesight.app/docs/faq/): notification access, Spotify, other players.
- [What it costs you](https://sleevesight.app/docs/costs/): what each service charges, and what a track costs.
- [Privacy policy](https://sleevesight.app/privacy.html).

How it works and how it is made:

- [Architecture](https://sleevesight.app/docs/architecture/), [dependencies](https://sleevesight.app/docs/dependencies/) and [solutions](https://sleevesight.app/docs/solutions/): how the app is put together, what it is built on, and what it solves in its own way.
- [Process](https://sleevesight.app/docs/process/): how it is built, reviewed, tested and released.
- For developers: [building and the pipeline CLI](design/development.md), the [design](design/design.md), the [architecture](design/architecture.md) and the [plan](design/plan.md).

## Roadmap

| Phase | Output | Status |
|---|---|---|
| 0. Platform spike | Verified behavior of media sessions, queues, transport controls and deep links on real players | Done |
| 0.5. Pipeline CLI | Coverage report and generated insights over a test playlist, from the shared pipeline module | Done |
| 1. Android MVP | Now-playing screen, sources, grounded insight, settings | Done |
| 1.5. Play release | Signed app bundle, store listing and privacy policy, Google Play's review; OpenAI and Google as AI services, beside Anthropic; the layout setting, expandable boxes or separate pages; song, artist and album insights (#522) | Under way |
| 2. Detail and discovery | The song, artist and album detail beyond their insights (the tracks by an artist or from an album in the playlist or session), genre cards beyond a genre's panel and sounds-like cards, under the layout setting; lyrics language; follow-up questions to the AI; export of the list of tracks played, with their insights; the insights' style, with presets or your own words; player controls | |
| 3. Context and reach | Optional Spotify account, more players, a local model through Ollama, Discogs | |

Details and the reasoning behind each decision are in the [design document](design/design.md); the work to the MVP and its release, with its dependencies, is in the [plan](design/plan.md).

## Building

Requires JDK 21 or later and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

```sh
./gradlew build                                                                  # compile, tests, Detekt, ktlint, Android lint
./gradlew checkDependencies checkDependencyPage checkManifest checkModuleGraph   # the structural checks
./gradlew :app:assembleDebug                                                     # the debug APK, in app/build/outputs/apk/debug/
.githooks/install                                                                # the Git hooks, once per clone
```

The hooks format the staged Kotlin before each commit and run the static checks before each push; they need Java and [Git LFS](https://git-lfs.com). [Building and the pipeline CLI](design/development.md) has the details: the structural checks, the debug build, CI and the hooks.

## Pipeline CLI

`:cli` runs the pipeline over the test playlist (`fixtures/below-soundtrack/`) and prints a coverage report.

```sh
./gradlew :cli:run --args="replay"                 # recorded responses only: no network, no keys
./gradlew :cli:run --args="live --tracks 1,63"     # live sources and AI, with your keys
./gradlew -q :cli:installDist
cli/build/install/sleevesight-cli/bin/sleevesight-cli keys-qr   # a setup code of your keys, for the app's Load keys
```

`live` reads the keys from `SLEEVESIGHT_*_API_KEY` variables or `~/.config/sleevesight/keys.env`; each insight is a paid call on your key. Run it with no arguments for the options; [Building and the pipeline CLI](design/development.md) has them all.

## License

[GPL-3.0](LICENSE), except the two photos in `assets/brand/`, each under its own licence ([listed there](https://github.com/wkusnierczyk/sleevesight/tree/main/assets/brand)).
