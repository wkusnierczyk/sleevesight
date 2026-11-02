---
# The limitations page (#712), at `/docs/limitations/`: what Sleevesight cannot do, and why, each limit
# in a sentence or two: whether it is Android's, a service's rules or a choice, and the issue where it
# is planned to change, or a change is being looked at. Written from the app's code as of 0.1.15 (the
# build's `minSdk`, in `build-logic/.../sleevesight.android-app.gradle.kts`; the string resources, in
# `res/values/` only; the prompts, `core/.../synthesis/`; the Wikipedia and Wikidata clients; the
# session layer and skip detection, `app/.../session/`), `design/design.md` (*Track changes*, D5),
# `design/architecture.md` (*Session layer*, #595's Spotify Connect limit; *Synthesis*, the grounding
# check), the FAQ, the usage page (*Players*), the settings page and the solutions page (*Its limit*).
# Linked from the docs index, the README, the tester brief, the FAQ and the usage page, whose *Not
# supported* points here. A limit lifted or found, or a linked issue closed, changes this page too.
title: Limitations
permalink: /docs/limitations/
docs_version: 0.1.15
provenance: [ai-wrote, human-edited]
nav:
  - id: platform
    label: Platform
  - id: players
    label: Players
  - id: insights
    label: Insights
  - id: sources
    label: Sources
  - id: cost-and-connection
    label: Cost
  - id: language
    label: Language
---

# Limitations

What Sleevesight cannot do, and why. Some limits are Android's, some are a service's rules, and some are configuration choices, following from Sleevesight's first aim: to collect and send as little about you as it can. Where changes are considered, relevant GitHub issues are linked.

A limit listed here is not a bug. Something that does not work as this page and the [usage page]({{ '/docs/tutorials/usage/' | relative_url }}) describe may be one: please tell us, at [support@sleevesight.app](mailto:support@sleevesight.app), or in an issue on [GitHub](https://github.com/wkusnierczyk/sleevesight/issues), which is public.

## Platform

- **Android 8 or later only.** The app needs Android 8.0 (API level 26) or later, and does not install on older versions.
- **No iPhone version.** iOS does not let one app read what another app is playing, which is all Sleevesight starts from. Reading it from Spotify's Web API and Apple Music instead is to be looked into in a later phase ([#243](https://github.com/wkusnierczyk/sleevesight/issues/243)).
- **Notification access is required.** A player says what it is playing in a *media session*, and Android lists other apps' media sessions only to an app that holds notification access. Android cannot narrow that access to one app, or to media sessions alone, and its dialog describes everything the access would allow, not what the app actually does with it. Without the access, Sleevesight sees nothing playing. For what it reads with it, and what it never reads, see the [FAQ]({{ '/docs/faq/#why-does-sleevesight-need-notification-access-does-it-read-my-notifications' | relative_url }}). Ways to see what is playing without it are being looked at ([#709](https://github.com/wkusnierczyk/sleevesight/issues/709)).
- **Installed from Google Play.** On Android 13 or later, Android may refuse notification access to a copy installed any other way (e.g., sideloaded).

## Players

- **Only players that report a title and an artist.** Sleevesight can follow a player that publishes a media session with at least a title and an artist. A player that publishes no media session cannot be read at all. One whose session reports no artist, as most video apps do, is never listed under [*Players*]({{ '/docs/tutorials/settings/#players' | relative_url }}). [Which players work]({{ '/docs/faq/#which-music-players-work-with-sleevesight' | relative_url }}) states which have been tested.
- **Calls are never read.** Calls from the phone app, and sessions Android marks as voice calls, are never read, whatever the switch, by choice. A calling app that shows its call as an ordinary media session is not recognised as a call, and an app you follow that also makes calls may show leftover caller details as a track for a moment after a call.
- **No looking ahead without a queue.** The next track is looked up ahead from the player's queue, so a player that publishes no queue gets no looking ahead.
- **Spotify on another device shows only while the Spotify app is open on the phone.** Spotify playing on a car or a speaker through Spotify Connect shows in Sleevesight while the Spotify app is open on the phone. With the app closed, Spotify publishes nothing on the phone about that playback, so Sleevesight has nothing to read until you open it (found in [#595](https://github.com/wkusnierczyk/sleevesight/issues/595)). Two routes that would not depend on the phone, a Spotify developer app of your own and Last.fm, are being looked at ([#709](https://github.com/wkusnierczyk/sleevesight/issues/709)).
- **No sign-in to Spotify, YouTube Music or other services.** Spotify's Web API serves an app registered in its *Development Mode* to at most five users, each added by hand, so one Sleevesight app registered with Spotify could not serve the public; YouTube Music has no public API for what is playing, Apple Music's gives the tracks played recently, not the one playing now, and Tidal, Deezer and Qobuz have no known open way to ask through an API. The [FAQ]({{ '/docs/faq/#why-not-connect-to-spotify-instead-with-one-connect-button-as-garmin-connect-links-with-strava' | relative_url }}) has the details and the dates they were checked. The same two routes are being looked at ([#709](https://github.com/wkusnierczyk/sleevesight/issues/709)).
- **No play, pause or skip from inside the app yet.** Use the player, or tap the player's name to open it. Player controls are planned for Phase 2 ([roadmap](https://github.com/wkusnierczyk/sleevesight#roadmap)).
- **No recognising music through the microphone.** A choice: it would mean sending the sound around you off the phone, or keeping a catalogue of songs on it, and a phone always listening asks for more trust than notification access ([FAQ]({{ '/docs/faq/#why-not-recognise-the-music-through-the-microphone' | relative_url }})).

## Insights

- **The citation check vouches for citations, not for truth.** Before an insight is kept, the app checks every citation in code: a sentence that cites nothing, or cites a line that does not exist, is marked as unverified. The check verifies that a sentence cites sources; it does not validate the truthfulness of the sourcs, so a false sentence with a proper citation passes.  For details, see [how the check works]({{ '/docs/solutions/#insights-that-cite-their-sources-checked-in-code' | relative_url }}).
- **A thinly documented track gets a short insight, or none.** The insight is written only from what the sources found. When they found little, it is short. When they found nothing to write from, none is written and no call is made: *Key insights* says *The sources give nothing to write an insight from.* Showing what the sources are missing, and where to add it, is planned ([#246](https://github.com/wkusnierczyk/sleevesight/issues/246)).
- **The model's own knowledge is not checked.** With [*AI service knowledge*]({{ '/docs/tutorials/settings/#sources' | relative_url }}) on, the AI service may add one sentence from what its model learned in training, marked *AI* and read as unverified. Nothing checks it, which is why it is off by default: a choice.
- **Skip detection is a judgement.** Under *On track skip (auto)*, an insight is written only for a track you skip to. A player's media session does not say whether a track was skipped, so the app judges it from the time the track had left and, for Spotify, the playback states just before the change. For other players it goes by the time alone: under *Crossfade*'s *Autodetect*, a change with up to 15 seconds left counts as a natural end, so a skip in a track's last 15 seconds is not told apart. Over Spotify Connect there are no such states to go by, so a track that crossfades into the next in a car may be taken for a skip, and written. In doubt, it judges a natural end: a missed skip costs a tap on *Write insight*, a false one a paid call. Learning skip detection per player, on the phone, is planned ([#22](https://github.com/wkusnierczyk/sleevesight/issues/22)).

## Sources

- **Two sources need your own key.** MusicBrainz, and Wikipedia with Wikidata, need no key. Last.fm and the Exa web search need your own API key, and stay disabled until they key is saved. For what each source covers, and what is missing without it, see [Setting up external services]({{ '/docs/services/#1-which-services-need-a-key-and-why' | relative_url }}).
- **A source that fails means less evidence.** A source that cannot be reached, fails, or refuses a request for its limits gives nothing for that track; the insight is written from the others' lines, and *Sources* says which failed. MusicBrainz is asked at one request a second, as its rules ask; Last.fm limits its rate; Exa's free credit is monthly.
- **A match the identity check cannot confirm is not used.** Each source's match is checked against the track before it is used, and one that fails is left out, so a track can get less than the sources hold. With MusicBrainz disabled, the other sources match by name alone, without its identifiers to check against.
- **English Wikipedia only.** Articles, and the genres' definitions, come from the English Wikipedia; a song or artist written up only in another language's Wikipedia is not found there.

## Cost and connection

- **Each insight is a call on your own AI key.** Sleevesight charges nothing and has no key of its own: each insight written is a call to the AI service you chose, billed to your account with it, now and then two or three calls; so is each AI answer in a genre's panel or for a source that has nothing. Under the default *On track change (auto)*, insights are written without a tap, so a track you play can be a call ([when automatic writing runs]({{ '/docs/tutorials/settings/#insights' | relative_url }})). The Exa web search, when on, is a search on your key, paid beyond its monthly free credit. A choice, since the app runs no server. What each service charges: [What it costs you]({{ '/docs/costs/' | relative_url }}). A cost estimate before a paid call is planned ([#331](https://github.com/wkusnierczyk/sleevesight/issues/331)), and a model on the phone, for insights with no key, is being looked at ([#272](https://github.com/wkusnierczyk/sleevesight/issues/272)).
- **It needs a connection.** Every lookup and every insight goes over the network, straight to each service. Offline, nothing new is looked up or written: what earlier lookups kept on the phone still shows, and the sources that could not be reached are asked again once the phone is back online.
- **Nothing is looked up in the background unless you ask.** While Sleevesight is not on screen, it starts no lookup and writes nothing new, unless [*Look up in the background*]({{ '/docs/tutorials/settings/#insights' | relative_url }}) is on; a lookup or call already under way when it leaves the screen still finishes. A choice: it is off by default, so no new paid or network work starts that nobody is looking at.

## Language

- **English only.** The app's interface is in English, and so are the insights: the prompts ask for plain English, and the citation check's detection of hedged sentences reads English only. Other interface languages, and insights in your language, are planned ([#290](https://github.com/wkusnierczyk/sleevesight/issues/290)).
