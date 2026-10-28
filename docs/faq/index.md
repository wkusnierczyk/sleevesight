---
# The FAQ (#711), at `/docs/faq/`: the questions testers ask first, about notification access and the
# ways round it, each answered short, with a link to where the detail is. Written from the privacy
# policy (`privacy.md`, *What the app reads on the phone*), the listener service
# (`app/.../session/SleevesightListenerService.kt`), `design/design.md` (*Spotify Web API
# constraints*), the usage page (*Players*) and the issues named; what other services offer is from
# their own documentation, dated where it can change. Linked from the docs index, the tester brief and
# the README. A change to what the app reads, to what a player shows, to Spotify's terms or to the plans
# named here (#708, #709) changes this page too.
title: Frequently asked questions
permalink: /docs/faq/
---

# Frequently asked questions

Sleevesight's first aim is to collect and send as little about you as it can. It has no server and no accounts, and nothing reaches its developer unless you send it yourself. Several of the answers below follow from that.

Notification access may sound alarming, but the app uses very little of it: what the players you follow say is playing. Sleevesight is open source, so anyone can check what it does with the access: the code is on [GitHub](https://github.com/wkusnierczyk/sleevesight), and the [privacy policy]({{ '/privacy.html' | relative_url }}) says in full what the app reads and what leaves the phone.

## Why does Sleevesight need notification access? Does it read my notifications?

It does not read your notifications. Notification access is Android's only way for an app to see what another app is playing. A player reports its track in a *media session*, and Android lists other apps' media sessions (through `MediaSessionManager`) only to an app that holds notification access. Android cannot narrow that access to one app, or to media sessions alone, and its dialog describes everything the access would allow, not what the app does with it.

Sleevesight reads the media sessions of the players you switch on (Spotify by default): the track, its artists, album and cover, and whether it is playing and how far in. It never reads the notifications themselves, your messages or your contacts, and never the audio. Of another app with a media session, it reads only enough to tell whether it plays music, so that it can be listed under *Players*, switched off. The part of the code that holds the access, [`SleevesightListenerService`](https://github.com/wkusnierczyk/sleevesight/blob/main/app/src/main/kotlin/app/sleevesight/app/session/SleevesightListenerService.kt), only starts and stops the reading of the players; nothing in it receives a notification.

The [privacy policy]({{ '/privacy.html#what-the-app-reads-on-the-phone' | relative_url }}) says what is read, in full. The first-run screen says the same before Android's dialog opens ([a typical journey]({{ '/docs/journey/' | relative_url }}), step 2), and is being made plainer about it ([#708](https://github.com/wkusnierczyk/sleevesight/issues/708)).

## Why not connect to Spotify instead, with one "Connect" button, as Garmin Connect links with Strava?

That button would be Spotify's Web API, with a sign-in to Spotify (OAuth), which can read what you play on any device. It is easy to build. Spotify's rules are the obstacle. As checked on 28 October 2026: since February 2026, a developer app in Spotify's *Development Mode* serves at most five users, each added to its allowlist by hand, and works only while its owner has Spotify Premium; *extended quota*, for more users, is open to organisations only, not to individuals ([Spotify's quota modes](https://developer.spotify.com/documentation/web-api/concepts/quota-modes), and [its announcement of the change](https://developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security)). So one Sleevesight app registered with Spotify could serve five people, not the public.

The way round it is a Spotify developer app of your own: you register it in Spotify's developer dashboard, which takes a few minutes, add yourself to its allowlist, and give its client ID to Sleevesight. As its only user, you are well within the five. It needs Spotify Premium. It is planned as an option beside notification access, not a replacement for it ([#709](https://github.com/wkusnierczyk/sleevesight/issues/709)).

## What about YouTube Music and other services?

None of them offers a way in that would replace notification access, as far as we know (28 October 2026): YouTube Music has no public API for what is playing; Apple Music's API gives the tracks played recently, not the one playing now; and Tidal, Deezer and Qobuz have no known open way to ask what is playing.

On the phone, Sleevesight follows these players as it follows Spotify, through their media sessions: switch one on under [*Players*]({{ '/docs/tutorials/settings/#players' | relative_url }}) in the settings.

Last.fm is being looked at ([#709](https://github.com/wkusnierczyk/sleevesight/issues/709)). Some services can report what you play to Last.fm themselves (scrobbling), and Last.fm's API marks the track playing now. For those services, it could show what is playing without notification access.

## Spotify playing on my amplifier or in the car shows nothing

Spotify playing on another device through Spotify Connect shows in Sleevesight while the Spotify app is open on the phone. With the Spotify app closed on the phone, Spotify publishes nothing on the phone about that playback, so Sleevesight has nothing to read until you open Spotify ([usage, *Players*]({{ '/docs/tutorials/usage/#players' | relative_url }}); the limit found in [#595](https://github.com/wkusnierczyk/sleevesight/issues/595)). Both routes being looked at in [#709](https://github.com/wkusnierczyk/sleevesight/issues/709), a Spotify developer app of your own and Last.fm, could cover it, since neither depends on what the phone is told.

## Why not recognise the music through the microphone?

That is out of scope, by choice. Recognising a song takes a database of songs and a server to match against: either one of our own, which would be a backend Sleevesight does not want, or a recognition service such as Shazam's (ShazamKit), which means sending fingerprints of the sound around you off the phone. That is more data leaving the phone, not less. Recognition on the phone itself, as Pixel phones' *Now Playing* does it, is Google's own and not offered to other apps. And a phone that is always listening asks for more trust than notification access, not less. Apps that recognise music already exist.
