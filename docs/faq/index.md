---
# The FAQ (#711), at `/docs/faq/`: the questions testers ask first, about notification access and the
# ways round it, each answered short, with a link to where the detail is. Written from the privacy
# policy (`privacy.md`, *What the app reads on the phone*), the listener service
# (`app/.../session/SleevesightListenerService.kt`), `design/design.md` (*Spotify Web API
# constraints*), the usage page (*Players*) and the issues named; what other services offer is from
# their own documentation, dated where it can change. Linked from the docs index, the tester brief and
# the README. A change to what the app reads, to what a player shows, to Spotify's terms or to the plans
# named here (#709) changes this page too; so does the first-run statement on notification access (#708).
title: Frequently asked questions
permalink: /docs/faq/
nav:
  - id: why-does-sleevesight-need-notification-access-does-it-read-my-notifications
    label: Notifications
  - id: why-not-connect-to-spotify-instead-with-one-connect-button-as-garmin-connect-links-with-strava
    label: Spotify
  - id: what-about-youtube-music-and-other-services
    label: Other players
  - id: spotify-playing-on-my-amplifier-or-in-the-car-shows-nothing
    label: Spotify Connect
  - id: why-not-recognise-the-music-through-the-microphone
    label: Microphone
---

# Frequently asked questions

Sleevesight's first aim is to collect and send as little about you as it can. It has no server and no accounts, and nothing reaches its developer unless you send it yourself. Several of the answers below follow from that.

Notification access may sound alarming, but the app uses very little of it: what the players you follow say is playing. Sleevesight is open source, so anyone can check what it does with the access: the code is on [GitHub](https://github.com/wkusnierczyk/sleevesight), and the [privacy policy]({{ '/privacy.html' | relative_url }}) says in full what the app reads and what leaves the phone.

## Why does Sleevesight need notification access? Does it read my notifications?

It does not read your notifications. A player reports its track in a *media session*, and Android lists other apps' media sessions (through `MediaSessionManager`) only to an app that holds notification access. Android cannot narrow that access to one app, or to media sessions alone, and its dialog describes everything the access would allow, not what the app does with it.

Sleevesight reads the media sessions of the players you switch on (Spotify by default): the track, its artists, album (and its year), length and cover; whether it is playing and how far in; and the queue of tracks up next. It never reads the notifications themselves, your messages or your contacts, and never the audio. Of another app with a media session, it reads only enough to tell whether it plays music, so that it can be listed under *Players*, switched off. The part of the code that holds the access, [`SleevesightListenerService`](https://github.com/wkusnierczyk/sleevesight/blob/main/app/src/main/kotlin/app/sleevesight/app/session/SleevesightListenerService.kt), only starts and stops the reading of the players; nothing in it handles a notification: it overrides none of the notification callbacks, so they are ignored, and on Android 12 and later its manifest entry (`default_filter_types=""` and `disabled_filter_types="conversations|alerting|silent|ongoing"`) also tells Android not to deliver any notifications to it at all. On a test phone, a build with that entry received none, while the same build without it received every notification posted.

The [privacy policy]({{ '/privacy.html#what-the-app-reads-on-the-phone' | relative_url }}) says what is read, in full. The first-run screen says the same before Android's dialog opens ([a typical journey]({{ '/docs/journey/' | relative_url }}), step 2): right above *Open notification access*, under *Why Sleevesight asks for notification access*, it explains that Sleevesight uses Android's media-session APIs to discover music players and read information about the music they are playing; that Android requires notification-listener access for this functionality even though Sleevesight does not need your notifications; that on Android 12 and newer Sleevesight configures Android not to deliver any notification types to the app; and that on all Android versions Sleevesight does not use notification contents and never collects or transmits your messages, contacts, or other notification contents. The home screen's *Turn on notification access* prompt, which leads to that screen, says it in one line.

## Why not connect to Spotify instead, with one "Connect" button, as Garmin Connect links with Strava?

That button would be Spotify's Web API, with a sign-in to Spotify (OAuth), which can read what you play on any device. It is easy to build. Spotify's rules are the obstacle. As checked on 28 October 2026: since February 2026, a developer app in Spotify's *Development Mode* serves at most five users, each added to its allowlist by hand, and works only while its owner has Spotify Premium; *extended quota*, for more users, is open to organisations only, not to individuals ([Spotify's quota modes](https://developer.spotify.com/documentation/web-api/concepts/quota-modes), and [its announcement of the change](https://developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security)). So one Sleevesight app registered with Spotify could serve five people, not the public.

A Spotify developer app of your own would avoid the cap: you would register it in Spotify's developer dashboard, which takes a few minutes, add yourself to its allowlist, and give its client ID to Sleevesight, and as its only user you would be well within the five. It needs Spotify Premium. It is being looked at as an option beside notification access, not a replacement for it, subject to Spotify's terms ([#709](https://github.com/wkusnierczyk/sleevesight/issues/709)).

## What about YouTube Music and other services?

None of them offers a way in that would replace notification access, as far as we know (28 October 2026): YouTube Music has no public API for what is playing; Apple Music's API gives the tracks played recently, not the one playing now; and Tidal, Deezer and Qobuz have no known open way to ask what is playing.

On the phone, Sleevesight follows these players as it follows Spotify, through their media sessions: a player is listed under [*Players*]({{ '/docs/tutorials/settings/#players' | relative_url }}) in the settings once it has played music while Sleevesight has notification access, and you switch it on there.

Last.fm is being looked at ([#709](https://github.com/wkusnierczyk/sleevesight/issues/709)). Some services can report what you play to Last.fm themselves (scrobbling), and Last.fm's API marks the track playing now. For those services, it could show what is playing without notification access.

## Spotify playing on my amplifier or in the car shows nothing

Spotify playing on another device through Spotify Connect shows in Sleevesight while the Spotify app is open on the phone. With the Spotify app closed on the phone, Spotify publishes nothing on the phone about that playback, so Sleevesight has nothing to read until you open Spotify ([usage, *Players*]({{ '/docs/tutorials/usage/#players' | relative_url }}); the limit found in [#595](https://github.com/wkusnierczyk/sleevesight/issues/595)). Both routes being looked at in [#709](https://github.com/wkusnierczyk/sleevesight/issues/709), a Spotify developer app of your own and Last.fm, could cover it, since neither depends on what the phone is told.

## Why not recognise the music through the microphone?

That is out of scope, by choice. Recognising a song means matching the sound against a catalogue of songs: either a recognition service's, such as Shazam's (ShazamKit), which means sending fingerprints of the sound around you off the phone, more data leaving the phone, not less; or a catalogue of songs kept on the phone and kept up to date, a product of its own. Both are outside Sleevesight's scope. Pixel phones' *Now Playing*, which recognises songs on the phone, is Google's own and not offered to other apps. And a phone that is always listening asks for more trust than notification access, not less. Apps that recognise music already exist.
