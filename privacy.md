---
# The privacy policy, at `/privacy.html`: the public URL Google Play asks for (#57). Written from
# `design/design.md` (*Privacy and security*) and the app's first-run screen; a change to what leaves
# the phone changes all three together.
layout: default
title: Privacy policy
permalink: /privacy.html
---

# Sleevesight privacy policy

Last updated: 20 October 2026.

This policy covers Sleevesight, the Android app (package `app.sleevesight`), and this website. Sleevesight is open source: the code that does everything described here is on [GitHub](https://github.com/wkusnierczyk/sleevesight).

## In short

Sleevesight has no server, no accounts, no telemetry, no crash reporting of its own, no analytics and no ads: nothing you play, and nothing the app sends, reaches its developer. To write about the song you are playing, the app sends the track's names from your phone straight to the sources you switch on and to the AI service you choose, using your own API keys. Google Play's data safety section counts that as data the app collects, since it leaves the phone. Everything else stays on your phone.

## What the app reads on the phone

Sleevesight needs notification access, which is how Android lets an app read what a music player is playing. It notes which players have a media session, to list them in its settings, and follows only the players you switch on there (Spotify by default); from them it reads which player it is; the title, artists, album, length and cover; whether it is playing, and how far into the track; and the queue of tracks up next, with its name. It ignores the notifications themselves, and it never touches the audio.

## What leaves the phone, and to whom

Requests go straight from your phone to each service. None passes through a server of Sleevesight's.

- **To the sources you switch on** (MusicBrainz and Wikidata, Wikipedia, Last.fm, and the Exa web search): the title and artists of the playing track, cleaned of tags such as "2006 Remaster"; Exa also gets the film or show a title says it is from. Last.fm gets the artists' names only, and Wikidata only identifiers (from MusicBrainz or Wikipedia), never a name. The album name does not go to the sources. While *Prepare the next track* is set to *Evidence*, as it is by default, the title and artists of the next track in the player's queue also go to MusicBrainz, if switched on, before that track plays.
- **To the AI service you choose** (Anthropic): the title, artists and album, and what the sources found. This is for the playing track only. Each insight is a request of its own, with no conversation kept between them. It is made with your API key, so the service handles it under its terms for your account.
- **Your API keys** go only to the service each belongs to, with each request to it.
- **To Spotify**, only if you connect a Spotify account, once the app offers that: requests for the playlist or album being played, made with your own token.
- **To the server a cover's web address names**, when a player gives the cover only as a web address: a request for that image. The address itself may identify the album or episode.
- **To a file you choose**, only if you export the diagnostics: with the *Diagnostics* developer setting on, *Export* writes what the app did and every track change it recorded to a file where you choose, which may be a cloud folder. That copy is yours, outside the app.

Every request is made over HTTPS; Android refuses the app any unencrypted connection, so a cover given as an `http://` address is not fetched.

Each of these services also sees your phone's IP address, as every service you connect to does. What each does with what it receives is set by its own policy: [MetaBrainz](https://metabrainz.org/privacy) (MusicBrainz), the [Wikimedia Foundation](https://foundation.wikimedia.org/wiki/Policy:Privacy_policy) (Wikipedia and Wikidata), [Last.fm](https://www.last.fm/legal/privacy), [Exa](https://exa.ai/privacy-policy), [Anthropic](https://www.anthropic.com/legal/privacy).

By default, lookups and writing happen only while Sleevesight is on screen. With *Look up in the background* on, they also happen while it is not.

Nothing else leaves the phone.

## What stays on the phone

- The length and cover of each track.
- The history: the tracks Sleevesight has seen play, the record of track changes it uses to tell your skips from natural ends, and what it has learned from them for each player.
- What the sources found, kept for a while so the same track is not looked up again (the lifetimes are in the settings).
- The insights written, your settings, and your API keys. The keys are encrypted with a key kept in the phone's Android Keystore, are never shown again once saved, and are never logged.

None of this is part of Android's backup or of a transfer to a new phone.

## What reaches the developer

Nothing you play, and nothing the app sends: the developer of Sleevesight runs no server, so no data about you or your listening is stored, sold or shared by them.

Google Play, which distributes the app, handles your installation under [Google's privacy policy](https://policies.google.com/privacy). It gives the developer aggregated install statistics and, from phones whose owners allow usage and diagnostics sharing, crash reports that name no one.

## Your choices

- Each source is a switch, and *Write insights* off makes no AI calls at all.
- *Write insights automatically* sets whether an insight is written for every track, only when you skip, or only when you tap Write.
- *Prepare the next track* set to *Off* sends nothing about the next track.
- *Keep history for* sets how long the history is kept, from a week to a year, or forever. *Clear history* deletes it at once.
- A saved key can be removed in the settings, with the delete button on its row, and *Remove all API keys* removes them all.
- Android's *Clear storage* for Sleevesight, or uninstalling it, deletes everything it kept on the phone. Cached findings also expire on their own, on the lifetimes shown in the settings.

What a service keeps of your requests, such as Anthropic for your account, is between you and that service.

## This website

The site is served by GitHub Pages, so GitHub receives your IP address and the pages you ask for, under [GitHub's privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement). The site sets no cookies and has no analytics. The front page keeps nothing in your browser; its copy from before its redesign, under `design/`, keeps one note there, that its intro has played. Design pages with diagrams load the diagram script from jsDelivr, which receives your IP address ([jsDelivr's privacy policy](https://www.jsdelivr.com/terms/privacy-policy-jsdelivr-net)).

## Children

Sleevesight is not directed at children under 13.

## Changes to this policy

A change is published on this page, with the date above. Its history is in the [repository](https://github.com/wkusnierczyk/sleevesight/commits/main/privacy.md).

## Contact

Sleevesight is made by Waclaw Kusnierczyk, who is responsible for this policy. Questions about it, or about your data: [support@sleevesight.app](mailto:support@sleevesight.app).
