---
# The page on Sleevesight's less obvious solutions (#413), at `/docs/solutions/`: for each, the
# problem, what was done, why, and whether it is new here or a known technique reimplemented.
# Written from the code on `main` (`GroundingCheck`, `IdentityCheck`, the Last.fm adapter,
# `TrackIdentity`, `SkipDetection`, `PromptEvidence`, `ArtistResultCache`, `KeySetup`, `KeysQr`,
# `KeyboardSession`, `SessionLedger`, `assets/keyboard/`, `assets/api-keys/`, the checks in
# `build-logic/`) and from `design/design.md` and `design/architecture.md`. A change to any of
# them, or a solution added or removed, changes this page.
title: Solutions
permalink: /docs/solutions/
---

# Solutions

Some parts of Sleevesight solve their problems in ways a reader would not guess from the [product page]({{ '/' | relative_url }}). This page describes them: for each, the problem, what was done, why, and whether it is new here or a known technique written again on purpose. Most are rules written for this app's problems rather than inventions; where a library could have done the job, the page says why none was used.

Each section links the design section that holds the detail, and the issue where the work was done. The [architecture page]({{ '/docs/architecture/' | relative_url }}) shows how the parts fit together, and the [dependencies page]({{ '/docs/dependencies/' | relative_url }}) lists the libraries the app does use.

## Insights that cite their sources, checked in code

**The problem.** A language model asked about a song writes fluent text whether or not it knows anything. A wrong year or a misattributed cover reads as well as a true one.

**What was done.** The model is not asked what it knows. The app retrieves evidence first, from MusicBrainz, Wikipedia and Wikidata, Last.fm and, if switched on, web search, and gives each line an id (`W1`, `L3`). The prompt holds only those lines, fenced and labelled as data, and asks for every factual sentence to end with the ids of the lines that support it. The reply shows as it arrives; once it is complete, the grounding check (`GroundingCheck`) splits it into sentences and classifies each: it cites only lines that exist; it cites the model's own knowledge, `[M]`, which is allowed only with *The model’s own knowledge* switched on and is shown as unverified; it states a gap in the evidence; it cites nothing; or it cites a line that does not exist. Uncited sentences and citations to missing lines are marked on the card, not hidden. Each citation becomes a chip the app builds from its own ids, so the model's text never becomes a link.

**Its limit.** The check sees whether a sentence cites real lines, not whether those lines say what the sentence says: a plausible false sentence with a valid citation passes. It narrows what the model can invent; it does not prove the text true.

**New or known.** Asking a model to cite retrieved sources is a common technique. The check itself is this project's own code, first written for its browser prototype and ported to Kotlin, with the prototype's sentence splitting corrected where the test tracks showed it wrong (abbreviations such as `U.S.` and `feat.`, a citation after a closing quotation mark). No library was used: what counts as a sentence, a citation and a stated gap is defined by this app's prompts.

Design: [*Synthesis*](https://github.com/wkusnierczyk/sleevesight/blob/main/design/design.md#synthesis) in the design, [*Synthesis and grounding*](https://github.com/wkusnierczyk/sleevesight/blob/main/design/architecture.md#synthesis-and-grounding-core-ai) in the architecture. Issues: [#42](https://github.com/wkusnierczyk/sleevesight/issues/42), [#423](https://github.com/wkusnierczyk/sleevesight/issues/423).

## The right song, the right article, the right artist

**The problem.** Players report a title and an artist as text. Many songs share a title, many artists share a name, and the sources are searched by name: a search for a small act's song can land on a famous song of the same name, or on another artist's Wikipedia article.

**What was done.** Every source is asked by name at once, and MusicBrainz resolves the track alongside them: the recording, chosen from up to 100 candidates by title, credited artists and length, the earliest released of those that fit; its artists' MusicBrainz ids; whether it is a cover, and of what. The answers of MusicBrainz, Wikipedia, Wikidata and Last.fm are then checked against those ids before any of their lines may be cited. Web search results have no id to check: a text filter keeps only pages that name the song and a distinctive credited artist, and their lines may be cited without that verification. A Wikipedia article is accepted only if its Wikidata item says it is a song, single or musical work performed by one of the credited artists, matched by MusicBrainz id where there is one; an artist's article only through the artist's MusicBrainz id. Of the sources checked against the ids, a match that cannot be checked is shown but never cited, and one that fails is shown as rejected, with the reason.

**New or known.** Matching records across databases through shared identifiers is a known technique (entity resolution, or record linkage). The rules are this project's own, tuned on its test tracks: bands credited under their leader's name (`Dick Dale and His Del-Tones` for `Dick Dale`), names with honorifics, covers whose earlier recordings are undated, a songwriter's own song later made famous by others. The rules are specific to MusicBrainz's and Wikidata's data and decide what may be cited, so they are written and tested here, with the test tracks' known cases as tests.

Design: [*Entity resolution*](https://github.com/wkusnierczyk/sleevesight/blob/main/design/architecture.md#entity-resolution-core-logic-sources-clients) in the architecture. Issues: [#36](https://github.com/wkusnierczyk/sleevesight/issues/36), [#37](https://github.com/wkusnierczyk/sleevesight/issues/37).

## Last.fm pages that belong to more than one artist

**The problem.** Last.fm keeps one page per artist name, so every artist of that name shares it, and it corrects names it does not know to ones it does. A small act can land on an unrelated band's page; a page's listener counts, tags and similar artists may mix several artists; its biography may be about someone else.

**What was done.** Each credited artist's Last.fm page is judged against the resolved artist. A page whose MusicBrainz id differs is rejected, and so is a name Last.fm corrected, unless its MusicBrainz id is the resolved artist's. A biography that says the name is shared ("There are several artists …", or a numbered list) gives no sentence to cite. Otherwise one MusicBrainz artist search for the name decides: if the search shows the resolved artist is the only one of that name, the whole page is verified; if another artist of the name exists, or the search cannot rule one out, only the biography's sentences may be cited, and the counts and tags are shown but not cited. A search that fails leaves the page unverified and is not cached, so a later lookup can verify it.

**New or known.** Not taken from a library or from Last.fm's documentation: it is a rule written for this problem, using MusicBrainz's identifiers to decide how much of a Last.fm page to trust. The biography check is deliberately too eager, since taking a shared page for one artist's is the unsafe mistake.

Design: [*Last.fm verification*](https://github.com/wkusnierczyk/sleevesight/blob/main/design/architecture.md#lastfm-verification) in the architecture. Issues: [#39](https://github.com/wkusnierczyk/sleevesight/issues/39), [#75](https://github.com/wkusnierczyk/sleevesight/issues/75).

## Telling a skip from a song that ended

**The problem.** *Reactive* can write only when you skip (*On skip*), so the app has to tell a skip from a song that ended on its own. Android's media session does not say: none of the players measured reported a skip state, and a skip in the player, from earphones or from the notification looks the same as a natural end. A player's crossfade makes a natural end come seconds before the track's end, where a skip may also fall. Players also re-send the same track with a new length or new art, which must not count as a change at all.

**What was done.** Both rules come from measurements of real players on a phone. A track's identity is its media id when the player sends one, else the active queue item where the player publishes one, else its title and artists; a new length or new art is an update, not a change. At each change the app reads the time the previous track had left and the playback states the player reported just before. A change with little time left is a natural end. For Spotify, a cluster of playing states in the second and a half before the change marks a natural end even inside a crossfade, since Spotify publishes one before every natural end and none before a skip. Under *Crossfade*, *Autodetect* learns the player's crossfade on the phone from natural ends that two in a row agree on, and never from its own guesses; a fixed value can be set instead. When in doubt the rules choose a natural end: a missed skip costs a tap on *Write insight*, while a false one costs a paid call nobody asked for.

**New or known.** Worked out here, from measurements on a phone, and written as the app's own code; it rests on what the measured players were seen to report. The Spotify cluster is undocumented behaviour, so it is used with the timing, never alone, and other players are judged by timing only until they are measured. How identity is decided for players that send no media id is still being reworked ([#297](https://github.com/wkusnierczyk/sleevesight/issues/297)).

Design: [*Track changes*](https://github.com/wkusnierczyk/sleevesight/blob/main/design/design.md#track-changes) in the design, [*Session layer*](https://github.com/wkusnierczyk/sleevesight/blob/main/design/architecture.md#session-layer-app) and [*Learning skip detection*](https://github.com/wkusnierczyk/sleevesight/blob/main/design/architecture.md#learning-skip-detection) in the architecture. Issues: [#48](https://github.com/wkusnierczyk/sleevesight/issues/48), [#53](https://github.com/wkusnierczyk/sleevesight/issues/53).

## Artist facts told once, and kept per artist

**The problem.** What Last.fm, Wikipedia and Wikidata say about an artist is the same on every track by that artist. Played through an album, every insight's prompt carried the same artist lines, and where the songs were thinly documented the model filled each insight with them: the same facts, track after track, paid for each time. Each track also looked the artist up again.

**What was done.** Two separate changes. First, the prompt leaves artist lines out where the track does not need them (`PromptEvidence`): a track with enough evidence of its own gets none, and a track with less gets only the artist lines no other insight in the listening session has cited. When leaving them out would leave nothing to write from, the prompt gets them all. A fact is matched by its source and its text, so it is told once a session. A setting, *Tell an artist's facts once per session*, on by default, turns this off for those who want every insight to introduce the artist ([#407](https://github.com/wkusnierczyk/sleevesight/issues/407)). Measured on three albums and a playlist when it was built ([#239](https://github.com/wkusnierczyk/sleevesight/issues/239)), the repeated facts disappeared and the prompts got shorter. Second, with *Keep what sources said about each artist* on, what the sources said about an artist is kept on the phone per artist, not per track, so a new track by a known artist asks only what is about the track. It is off by default, since what it keeps amounts to a list of the artists you have heard; with it off, each track's artists are looked up again, so their names go to the sources with every track.

**New or known.** Caching by part of a request rather than the whole is a known technique, written here over the app's own database because each part has to expire with the shortest lifetime of the sources behind it. Leaving out facts a session has already told is this project's own rule; no library was involved.

Design: [*Synthesis*](https://github.com/wkusnierczyk/sleevesight/blob/main/design/design.md#synthesis) (*The insight stays on the track*) in the design, [*Cache and history*](https://github.com/wkusnierczyk/sleevesight/blob/main/design/architecture.md#cache-and-history-store) in the architecture. Issues: [#238](https://github.com/wkusnierczyk/sleevesight/issues/238), [#239](https://github.com/wkusnierczyk/sleevesight/issues/239), [#407](https://github.com/wkusnierczyk/sleevesight/issues/407), [#420](https://github.com/wkusnierczyk/sleevesight/issues/420).

## API keys by QR code

**The problem.** Sleevesight uses your own API keys: an AI service's, and optionally Last.fm's and Exa's. They are long random strings, and typing them on a phone is slow and error-prone. Online QR generators would receive the keys.

**What was done.** A key's dialog has *Scan QR code*, which fills the field from a code holding that one key, once the key has its service's form (an Anthropic key starts `sk-ant-`, a Last.fm key is 32 hexadecimal digits, and so on). *Load keys*, under *Secrets* in the settings, reads a setup code holding several, a first line `sleevesight-keys:v1` and one `name=value` line per key, and lists what it found, each key by its last four characters, before anything is saved. The setup code is drawn on a computer in one of two ways:

- [sleevesight.app/api-keys](https://sleevesight.app/api-keys), a page that reads your keys file or the keys pasted in its fields, checks each key's form as the app does, and draws the code in the browser. Its content security policy forbids any network request and any code from elsewhere, and it stores nothing.
- `keys-qr`, a command of the developer's command-line tool, which draws the code in the terminal with half-block characters, two rows of the code to a line, and prints no key as text.

The phone decodes the camera's frames itself; no frame is kept or sent, the camera is asked for only when you scan, and the key dialogs and the scan cannot be captured in a screenshot.

**New or known.** QR codes are a standard. Decoding on the phone and drawing in the terminal use the ZXing library. Google's code scanner was not used because its ML Kit reports usage to Google. The page loads no code from elsewhere, ZXing included, and draws the code with its own encoder (next section). The setup code's format is this project's own; the page's copy of the key checks is tested against the app's.

Design: [*Privacy and security*](https://github.com/wkusnierczyk/sleevesight/blob/main/design/design.md#privacy-and-security) in the design. Issues: [#268](https://github.com/wkusnierczyk/sleevesight/issues/268), [#436](https://github.com/wkusnierczyk/sleevesight/issues/436).

## Typing on a computer, over Bluetooth, with no server

**The problem.** Some fields are easier to fill from a computer, where the key often is already. Sending text from a computer to a phone usually goes through a server, a cloud clipboard, or a page the phone serves over Wi-Fi, which would be plain HTTP on a shared network.

**What was done.** [sleevesight.app/keyboard.html](https://sleevesight.app/keyboard.html) makes a session for that visit: a random 8-byte id and a random 128-bit AES key. It shows both as a QR code. In a key's dialog, *Type on a computer* scans it. The phone then offers a Bluetooth Low Energy service whose UUID is made from the session id, so the browser's device chooser lists only that phone; *Connect* on the page links to it through Web Bluetooth (Chrome and Edge). At each change the page sends the whole text, at most 480 bytes, sealed with AES-GCM under the session key:

- each message carries a counter, starting at 1; the nonce is the session id and the counter, and the associated data the code's prefix and the id;
- the phone takes a message only if it opens under the key and its counter is above the last one it took, so a message recorded over the air and sent again is refused;
- a session is run once: the phone keeps the ids of the sessions it has run (the last 512, never the key), so the same code scanned again, whose counter would start over, is refused, and a new session needs the page reloaded. If that record cannot be read or written, it is kept in memory for the time being rather than refusing every session;
- closing the dialog, or *Stop*, wipes the key on the phone.

The phone sends nothing back but Bluetooth's acknowledgements, and the page is allowed no network request: the text goes over Bluetooth alone.

```mermaid
sequenceDiagram
  participant B as Browser (keyboard.html)
  participant P as Phone (Sleevesight)
  B->>B: new session: random id and AES key
  B-->>P: QR code (id and key), scanned with the camera
  P->>P: refuse a code run before; advertise the service named for the id
  B->>P: Connect, over Web Bluetooth
  loop each change of the text
    B->>P: counter, and the text sealed with AES-GCM
    P->>P: open it; take it only if the counter is above the last
  end
  P->>P: dialog closed: the key wiped
```

**New or known.** Each part is standard: AES-GCM, from the browser's Web Crypto and Java's standard cryptography on the phone; Bluetooth Low Energy; QR codes. Putting them together as a keyboard with no server is this project's own design. It was chosen over a page served by the phone on Wi-Fi, which a page served over HTTPS may not reach, and over sending the text as QR codes, which is not live. The page's QR encoder is its own, written from the QR standard (ISO/IEC 18004) as Project Nayuki's open-source encoder implements it, rather than loaded, so the page fetches nothing from elsewhere; the app's tests read codes it drew with ZXing, and check messages the page sealed against the app's side of the protocol.

Design: [*Privacy and security*](https://github.com/wkusnierczyk/sleevesight/blob/main/design/design.md#privacy-and-security) in the design; the protocol is in [`assets/keyboard/protocol.js`](https://github.com/wkusnierczyk/sleevesight/blob/main/assets/keyboard/protocol.js) and [`KeyboardSession.kt`](https://github.com/wkusnierczyk/sleevesight/blob/main/app/src/main/kotlin/app/sleevesight/app/keyboard/KeyboardSession.kt). Issue: [#394](https://github.com/wkusnierczyk/sleevesight/issues/394).

## Logs that hold no key

**The problem.** Logs of network requests are what a developer needs to find a fault, and what most often leaks a key: in a header, in a URL's query, in a redirect's address.

**What was done.** A key is a `Secret`, a type whose text form is redacted, and it is revealed only into the request's own field for credentials. The network log holds no header name or value, and no query value; a URL's path and query names are logged only when the request is known to be public, so a request whose URL may hold a key (marked so, or with a query parameter named like a key), and any hop after a redirect, whose address the server chose, shows its host but neither its path nor its query names. Tests log requests with a key in each of those places and compare the whole lines.

**New or known.** Redacting secrets in logs is a known practice. The rule here is stricter than matching values: rather than finding keys in the log and hiding them, the log is built only from what is known to be public. It is this project's own code, part of its one network module.

Design: [*Networking*](https://github.com/wkusnierczyk/sleevesight/blob/main/design/architecture.md#networking) in the architecture.

## Promises the build enforces

**The problem.** A privacy promise such as "no analytics" or "nothing goes on the network except through the sources" is easy to break without noticing: a library can bring an analytics SDK or a permission with it, and a call to a network API can be added in any module.

**What was done.** The build fails when one is broken, on every pull request:

- `checkDependencies` fails on any library on the shipped app's classpath whose group is not in a reviewed allowlist, which also records each group's licence; Gradle's dependency locking pins the versions;
- `checkManifest` fails on any permission in the merged manifest that is not in a reviewed allowlist, so a permission a library merges in cannot arrive unseen;
- `checkModuleGraph` fails when a module depends on one it may not, or when an HTTP client is reachable from a module other than `:net`, even through a third-party library;
- in the pipeline modules, a check of the compiled classes fails when code outside `:net` refers to a network API, however the source spelled it, and another when the code uses a construct that only runs on the JVM; the app's own module cannot even compile against `:net`;
- a check of the APK fails when its code refers to one of the project's classes that the APK does not contain.

**New or known.** Allowlists and dependency rules are known practice, and tools such as Detekt, which the project also runs, cover part of it. These checks are small Gradle tasks written here, because they encode this app's own rules (which modules may reach the network, which permissions may be asked for) and read the build's real outputs, the merged manifest and the compiled classes, rather than the sources.

Design: [*Structural constraints*](https://github.com/wkusnierczyk/sleevesight/blob/main/design/architecture.md#structural-constraints) in the architecture. Issues: [#10](https://github.com/wkusnierczyk/sleevesight/issues/10), [#113](https://github.com/wkusnierczyk/sleevesight/issues/113), [#251](https://github.com/wkusnierczyk/sleevesight/issues/251).
