# Sleevesight

Liner notes for whatever is playing. Sleevesight is an open-source Android app that reads what your music player is playing and shows short, sourced insights about the song, its artists, its album and its genres.

> **Status:** Phase 0.5, pipeline CLI. The platform spike is done and its findings are in the design; the app itself is not written yet. The design is in [design/design.md](design/design.md), the architecture in [design/architecture.md](design/architecture.md), and an interactive [simulator](https://wkusnierczyk.github.io/sleevesight/design/simulator.html) simulates the full flow. Its lookups are frozen, and it has no AI service: the insight cards show the first facts found in place of a written insight, and the genre and sounds-like cards stay unwritten.

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

When little is documented about a track, Sleevesight says so instead of filling the gap.

## You choose

- **Where to look.** Each source is a switch, and the model's own knowledge is a separate one, off by default.
- **Which AI.** Anthropic, OpenAI, Google, or a local model through Ollama, with your own API key and model.
- **When and how it writes.** Each insight is a call on your key. By default one is written for every track, and the next track in the queue is prepared ahead so its card is ready when it starts. You can have insights written only when you skip, or only when you tap Write, and prepare only the evidence or nothing. In every mode, lookups and writing happen only while the app is on screen, unless you turn on *Look up in the background*, and nothing is written after you open the app until your first action in it. A style setting, with presets or your own words, sets the length and tone; it cannot loosen the citation rules.
- **Which players.** Spotify by default; any player with a media session can be followed.
- **Spotify account.** Optional. Connecting it, with your own Spotify developer app, lets Sleevesight see the whole playlist or album being played. Without it, the app knows the tracks it has seen play.

## Privacy

Track, artist and album names, for the playing track and, by default, the next one in the queue, go to the sources you enabled, and, with what those sources found and your style setting, to the AI service you chose. If you connect a Spotify account, the app also asks Spotify for the playlist or album being played. Nothing else leaves the phone. No accounts of our own, no telemetry, no analytics, no ads. Keys are stored encrypted on the device.

## Roadmap

| Phase | Output |
|---|---|
| 0. Platform spike | Verified behavior of media sessions, queues, transport controls and deep links on real players |
| 0.5. Pipeline CLI | Coverage report and generated insights over a test playlist, from the shared pipeline module |
| 1. Android MVP | Now-playing screen, sources, grounded insight, settings |
| 1.5. Play release | Signed app bundle, store listing and privacy policy, Google Play's review |
| 2. Detail and discovery | Song, artist and album insight; genre and sounds-like cards; layout setting; player controls |
| 3. Context and reach | Optional Spotify account, more players, local models, Discogs |

Details and the reasoning behind each decision are in the [design document](design/design.md); the work to the MVP and its release, with its dependencies, is in the [plan](design/plan.md).

## Requirements

Android only. iOS does not let one app read what another app is playing. Sleevesight is to be distributed through Google Play.

## Building

Requires JDK 17 or later and the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`).

```sh
./gradlew build                                # compile, tests, Detekt, ktlint
./gradlew checkDependencies checkModuleGraph   # the structural checks
```

The structural checks enforce the design's constraints: only allowlisted dependencies (`config/dependency-allowlist.txt`), only allowlisted permissions (`config/permission-allowlist.txt`, checked by `checkManifest` once the app exists), and the module boundaries (`config/module-graph.properties`). After changing dependencies, refresh the lockfiles with `./gradlew resolveAndLockAll --write-locks`.

## License

[GPL-3.0](LICENSE).
