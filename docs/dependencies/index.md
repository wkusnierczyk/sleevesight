---
# The dependencies page (#412), at `/docs/dependencies/`: the libraries the app ships, what each does
# in it, its licence and limits; the data sources, the font and the icons; the build-time tools.
# Written from the release runtime classpath of `:app` (`app/gradle.lockfile`), the artifacts' POMs,
# the module build files, `config/dependency-allowlist.txt`, the credits screen (`CreditsScreen.kt`)
# and `design/architecture.md`. `checkDependencyPage` fails when a shipped Maven group is missing
# from a `**Groups:**` line here, or a group named on one no longer ships; a change to a library's
# use or licence, the credits or the build tools changes this page by hand.
title: Dependencies
permalink: /docs/dependencies/
---

# Dependencies

What Sleevesight is built on: the libraries that ship in the app, what each one does there, under which licence, and its limits; then the data sources, the font and the icons the app credits; and last, the tools that only build and test it.

## What keeps this page current

The build checks this page against the libraries that actually ship, not against a list kept by hand. The task `checkDependencyPage` resolves the release build's runtime classpath of `:app`, which carries every module the app is made of and everything they pull in, and reads the Maven groups named on this page's *Groups* lines. It fails when a group on the classpath is not named here, or a group named here is no longer on the classpath. CI runs it with the other structural checks on every pull request that builds.

What the check covers, and what it does not:

- **Groups, not single artifacts.** A library is one Maven group or several (`io.ktor` holds all of Ktor's modules). A new module of a group already listed passes; a new group fails.
- **Not versions.** The exact versions are in the lockfiles, such as [`app/gradle.lockfile`](https://github.com/wkusnierczyk/sleevesight/blob/main/app/gradle.lockfile), which the build resolves from.
- **Not what is written about each group.** The uses, licences and limits below are written by hand; the licences are taken from each artifact's POM, as published on Maven Central or Google's Maven repository.
- **Not the build-time tools**, at the end of the page, which never ship.

A second check, `checkDependencies`, holds the same classpath to [`config/dependency-allowlist.txt`](https://github.com/wkusnierczyk/sleevesight/blob/main/config/dependency-allowlist.txt): a new group there is a reviewed edit with its licence, so no analytics, crash-reporting or advertising library can arrive unseen. This page says what each allowed group is for.

The release build is shrunk by R8, which drops the code nothing calls; being on the classpath means a library may ship, not that all of it does.

## Licences and GPL-3.0

Sleevesight is under the [GNU General Public License, version 3](https://github.com/wkusnierczyk/sleevesight/blob/main/LICENSE). Every shipped library is under a permissive licence, but for one data file inside OkHttp under the MPL-2.0, a file-level copyleft licence; each of them lets its code be combined into a GPL-3.0 program and the whole distributed under the GPL:

- **Apache-2.0**, the licence of almost everything below. The Free Software Foundation counts it compatible with GPL version 3 (not with version 2, because of its patent terms). It asks that each copy carry the licence and the NOTICE file the library's distribution has, if it has one.
- **MIT**, which asks only that its copyright and permission notice go with each copy.
- **BSD-3-Clause**, which asks the same and adds that the authors' names are not used to endorse the product.
- **MPL-2.0**, for one file of data inside OkHttp, the Public Suffix List. It asks that recipients learn the file's licence and where its source is, and allows the file to be distributed in a larger work under the GPL (its section 3.3).
- **Unicode-3.0**, for Unicode's IDNA mapping table, which OkHttp compiles into its code to read international host names. It asks that its copyright and permission notice go with each copy, as MIT does.

The credits screen in the app (*Credits*, under *About* in *Settings*) lists the libraries too: by Maven group those under the Apache License alone, and by name each notice that goes further, with each licence's text in full. It also links to this page for what each library does in the app. The build writes that list from the libraries' POMs and [`config/licence-notices.txt`](https://github.com/wkusnierczyk/sleevesight/blob/main/config/licence-notices.txt), and fails when a shipped library's licence needs a notice the app does not carry ([#488](https://github.com/wkusnierczyk/sleevesight/issues/488)).

## Shipped libraries

### Kotlin and its libraries

- **Groups:** `org.jetbrains.kotlin`, `org.jetbrains.kotlinx`, `org.jetbrains`
- **Where:** every module.
- **Licence:** Apache-2.0, with the NOTICE files of kotlinx.coroutines, kotlinx.serialization and kotlinx-io; the standard library's `kotlin.time` has code from the ThreeTen backport, under BSD-3-Clause.

The Kotlin standard library; coroutines, which every module uses and `:core`'s API carries (a source adapter's lookup takes a `Deferred`, and a new track cancels the work for the last one); and kotlinx.serialization, the JSON of the genre vocabulary in `:core`, of the sources' and AI services' responses in `:sources` and `:ai`, of the cached results in `:store`, and of the screens' typed routes in `:app`. `kotlinx-io` comes in with Ktor, and `org.jetbrains:annotations` with the standard library and coroutines.

Chosen against: Moshi for JSON, since kotlinx.serialization uses no reflection and runs on Kotlin Multiplatform, where the pipeline modules are meant to be able to go; and `java.time`, for the same reason, in favour of `kotlin.time`.

### Ktor and OkHttp: every network call, in `:net` alone

- **Groups:** `io.ktor`, `com.squareup.okhttp3`, `com.squareup.okio`, `org.slf4j`
- **Where:** `:net`; Okio also under DataStore in `:app`.
- **Licence:** Apache-2.0; SLF4J's API, MIT; the Public Suffix List in OkHttp's Android artifact, MPL-2.0; Unicode's IDNA mapping table compiled into OkHttp, Unicode-3.0.

Ktor's HTTP client, on OkHttp as its engine, makes every request the app sends: to the sources, the AI services and, for players that give their cover only as a web address, the cover's server. Only `:net` may depend on it. It takes Ktor as an `implementation` dependency, so the modules above it see `:net`'s own types and never Ktor's; the module graph (`config/module-graph.properties`), Detekt's rules and a check of the compiled classes stop any other module from reaching an HTTP client. Okio is OkHttp's I/O library.

Limits as used here: `ktor-client-core` brings modules the app does not use, such as WebSockets and server-sent events support (the AI services' streams are read by `:ai`'s own parser); R8 drops what nothing calls. OkHttp's Android artifact sets the app's `compileSdk`, which it requires to be at least 37, and brings AndroidX Startup with it. Ktor logs through the SLF4J API, and only the API ships: with no SLF4J binding, what Ktor would log (request lines, addresses, headers) goes nowhere. The app's own network log, kept by `:net` in memory on the phone, shows each request's method, host, path where it is not hidden, status and timing, never a header value or a key.

Chosen against: Retrofit, since Ktor is the same client on the JVM (the command-line tool) and on Android, and keeps the network behind one module.

### SQLDelight: the caches and the history

- **Groups:** `app.cash.sqldelight`, `androidx.sqlite`
- **Where:** the runtime in `:store`; the Android driver, on AndroidX SQLite, in `:app`.
- **Licence:** Apache-2.0.

`:store` keeps two SQLite databases, each from its own schema and queries: the caches of the sources' results, which may be evicted or rebuilt, and the session history, which is the user's. SQLDelight generates typed Kotlin from the SQL. Every schema version is kept in the repository and each migration is checked against the version before it, so an update never meets a database it cannot open.

Chosen against: Room, since SQLDelight has a plain JVM driver as well as Android's, so the command-line tool and the tests run the same schema and queries off the phone.

### DataStore: the settings and the saved keys

- **Groups:** `androidx.datastore`
- **Where:** `:app`.
- **Licence:** Apache-2.0, except `datastore-preferences-external-protobuf`, a repackaged copy of Protocol Buffers, which its POM gives as BSD-3-Clause.

Preferences DataStore files hold the settings (only those moved off their defaults), the API keys (each sealed with AES-GCM under a key held in the Android Keystore) and what skip detection has learned. The Keystore is part of Android, not a library. None of them is backed up or transferred to another phone.

Limits as used here: DataStore reads only asynchronously, so the few values the app reads synchronously as it starts, such as whether the first-run screen was acknowledged and copies of the *Font* and *Logo intro at start* settings, stay in `SharedPreferences`.

Chosen against: Jetpack Security's `EncryptedSharedPreferences`, which is deprecated.

### Jetpack Compose, Material 3 and Navigation: the screens

- **Groups:** `androidx.compose`, `androidx.compose.animation`, `androidx.compose.foundation`, `androidx.compose.material`, `androidx.compose.material3`, `androidx.compose.runtime`, `androidx.compose.ui`, `androidx.activity`, `androidx.navigation`, `androidx.navigationevent`, `androidx.lifecycle`, `androidx.savedstate`
- **Where:** `:app`.
- **Licence:** Apache-2.0.

Every screen is Compose, in Material 3; Activity Compose hosts it, and Navigation Compose moves between the screens on typed routes. `androidx.compose:compose-bom` is the bill of materials that keeps the Compose libraries' versions in step; `androidx.compose.material` is Material's ripple, which Material 3 uses. Compose's preview annotations (`ui-tooling-preview`) are on the release classpath because the screens' previews are written beside them; the tooling that renders them is in the debug build only.

### AndroidX libraries those bring in

- **Groups:** `androidx.annotation`, `androidx.arch.core`, `androidx.autofill`, `androidx.collection`, `androidx.concurrent`, `androidx.core`, `androidx.customview`, `androidx.emoji2`, `androidx.graphics`, `androidx.interpolator`, `androidx.profileinstaller`, `androidx.startup`, `androidx.tracing`, `androidx.versionedparcelable`, `androidx.window`, `com.google.guava`, `org.jspecify`
- **Where:** `:app`, pulled in by the libraries above.
- **Licence:** Apache-2.0.

The app's build declares none of these; Compose, Activity, Lifecycle, SQLDelight's Android driver and OkHttp's Android artifact bring them. The app's code calls a few of them directly, such as `androidx.core`'s compatibility helpers. From Guava only `listenablefuture` ships, a single interface, not Guava itself; JSpecify is nullness annotations.

The dependency allowlist names each of these groups exactly rather than allowing `androidx.*`, which would also admit `androidx.privacysandbox.ads`, an advertising API.

### ZXing: reading an API key's QR code

- **Groups:** `com.google.zxing`
- **Where:** `:app`, which reads QR codes; also the command-line tool, which draws them in a terminal and does not ship.
- **Licence:** Apache-2.0 (from its parent POM, `zxing-parent`), with its NOTICE file, which names Barcode4J's authors.

*Scan keys*, and *Scan QR code* beside each key's field, read a QR code from the camera's frames: one key, or a setup code holding several, such as the [API keys page]({{ '/api-keys.html' | relative_url }}) draws. Only ZXing's core and its QR code reader are used, on the phone; nothing of a frame is kept or sent.

Limits as used here: the camera is driven by the app's own code on Android's camera2 API, and ZXing sees only each frame's brightness. ZXing's project is in maintenance mode, changed by contributed fixes.

Chosen against: Google's code scanner, whose ML Kit reports usage to Google; and CameraX, which would bring a dozen libraries for one view and one stream of frames.

### material-color-utilities: the colours from the cover

- **Groups:** `com.materialkolor`, `dev.drewhamilton.poko`
- **Where:** `:app`.
- **Licence:** MIT (materialkolor); Apache-2.0 (Poko's annotations, which it brings).

The now-playing screen takes its colours from the album art: the art's most suitable colour, as the library's `Score` picks it, seeds a Material 3 tonal-spot scheme, light or dark as the phone is. This is Google's material-color-utilities in the Kotlin port published as `com.materialkolor:material-color-utilities`, since Google publishes no artifact of its own for it.

Limits as used here: the port is a third party's, under MIT; Google's original is under Apache-2.0. The port's POM names MIT only, while its sources keep Google's Apache-2.0 headers, so the app's notice for it names both.

## Data sources

The app's credits screen lists each source with what the app takes from it and its licence or terms. Every evidence line in the app links the page it came from.

| Source | What the app takes from it | Licence or terms |
|---|---|---|
| MusicBrainz | Recordings, releases, artists, credits and dates | Core data under [CC0](https://creativecommons.org/publicdomain/zero/1.0/); the web service is free for non-commercial use |
| MusicBrainz tags | Genre labels from the artists' tags; used for labels only, never sent to the AI service | Supplementary data under [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/), credited to the MusicBrainz contributors |
| Wikipedia | Sentences from articles | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/), by the Wikipedia contributors |
| Wikidata | Facts on songs and artists | [CC0](https://creativecommons.org/publicdomain/zero/1.0/) |
| Last.fm | Artist bios; listeners, tags, genre labels and similar artists | Bios under CC BY-SA, by Last.fm users; the rest under Last.fm's API terms. Needs the user's own key |
| Exa | Web search results | Exa's terms of service. Needs the user's own key |

Two of these are open questions rather than settled ones. MusicBrainz's tags are non-commercial and share-alike: showing them as labels on the user's own screen fits the licence while the app stays free, without advertising or paid features, but MetaBrainz's own reading is still to be asked for before the Play release relies on it, and the tags stay out of every prompt. Exa's terms forbid displaying or passing on what its service returns without its written leave; what that means for an app that shows a result to the user who searched with their own key is not settled. The reasoning, with the sources quoted, is in [`design/architecture.md`](https://github.com/wkusnierczyk/sleevesight/blob/main/design/architecture.md#licensing) under *Licensing*. Discogs is planned as a source for a later phase, and is not in the app.

The AI services that write the insights (Anthropic, OpenAI or Google, on the user's own key) are services, not sources of what the app shows; what each receives is in the [privacy policy]({{ '/privacy.html' | relative_url }}).

## The font and the icons

| Asset | What it is | Licence |
|---|---|---|
| Roboto | The app's font, from [googlefonts/roboto-3-classic](https://github.com/googlefonts/roboto-3-classic), bundled with the app; *Font* in Settings switches between *App font (Roboto)* and *System font* | [SIL Open Font License 1.1](https://openfontlicense.org), copyright 2011 The Roboto Project Authors |
| Material Symbols | The icons in the app, such as the cogwheel, by Google | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) |

The Open Font License lets a font be bundled with any program, a GPL one included, as long as each copy carries its copyright notice and licence. Apache-2.0 asks that each copy carry the licence. So both licences' texts ship inside the app, and the credits screen names the font with its copyright, and the icon set, each with its licence.

## Build-time tools

These build, check and test the app; none of them is in what ships, and `checkDependencyPage` does not read this section. The licences are from the artifacts' POMs unless the table says otherwise.

| Tool | What it does here | Licence |
|---|---|---|
| Android Gradle plugin, with Android lint | Builds the APK and the bundle, runs R8, lints | Apache-2.0 |
| Kotlin Gradle plugin, with its serialization and Compose compiler plugins | Compiles the Kotlin | Apache-2.0 |
| SQLDelight Gradle plugin | Generates `:store`'s code from its SQL; keeps and verifies the schema versions | Apache-2.0 |
| Detekt | Static analysis, with the project's rules on what each module may use | Apache-2.0 |
| ktlint, through the ktlint Gradle plugin | Formatting | ktlint: MIT. The plugin's POM names none; its jar carries the Apache License 2.0 text |
| ASM (`asm-commons`) | Reads the compiled classes for the check that no module but `:net` reaches the network | BSD-3-Clause |
| `kotlin-test`, `kotlinx-coroutines-test` | Tests in the JVM modules, with virtual time | Apache-2.0 |
| Ktor's `MockEngine`, OkHttp's `mockwebserver3` | Network tests: replayed responses, and a loopback server for what the real engine sends | Apache-2.0 |
| Robolectric, JUnit 4, Compose's `ui-test-junit4` | The app's tests, on the JVM, without a phone | Robolectric: MIT. JUnit 4: Eclipse Public License 1.0. Compose: Apache-2.0 |
| Compose's `ui-tooling` | Renders the screens' previews in the IDE; debug build only | Apache-2.0 |
| SQLite JDBC driver (`org.xerial:sqlite-jdbc`) | `:store`'s and the app's tests, and the command-line tool's database | Apache-2.0 |

The Eclipse Public License 1.0 does not combine with the GPL, which does not matter for JUnit: it only runs the tests and is never distributed with the app.

The command-line tool (`:cli`), which runs the pipeline over the test playlist and draws the keys' QR codes, is a developer's tool built from the same modules; it is not published, and its own additions are the SQLite JDBC driver and ZXing.
