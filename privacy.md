---
# The privacy policy, at `/privacy.html`: the public URL Google Play asks for (#57). Written from
# `design/design.md` (*Privacy and security*) and the app's first-run screen; a change to what leaves
# the phone changes all three together.
layout: default
title: Privacy policy
permalink: /privacy.html
---

# Sleevesight privacy policy

Last updated: 18 October 2026.

This policy covers Sleevesight, the Android app (package `app.sleevesight`), and this website. Sleevesight is open source: the code that does everything described here is on [GitHub](https://github.com/wkusnierczyk/sleevesight).

## In short

Sleevesight collects nothing. It has no server, no accounts, no telemetry, no crash reporting, no analytics and no ads. To write about the song you are playing, it sends the track's names from your phone to the sources you switch on and to the AI service you choose, using your own API keys. Everything else stays on your phone.

## What the app reads on the phone

Sleevesight needs notification access, which is how Android lets an app read what a music player is playing: the title, artists, album, length and cover. It ignores the notifications themselves, and it never touches the audio. It follows only the players you switch on in its settings (Spotify by default).

## What leaves the phone, and to whom

Requests go straight from your phone to each service. None passes through a server of Sleevesight's.

- **To the sources you switch on** (MusicBrainz and Wikidata, Wikipedia, Last.fm, and the Exa web search): the title and artists of the playing track, cleaned of tags such as "2006 Remaster", and the album name. While *Prepare the next track* is on, which it is by default, the title and artists of the next track in the player's queue also go to MusicBrainz, if switched on, before that track plays.
- **To the AI service you choose** (Anthropic): the title, artists and album, what the sources found, and your style setting, including any text you write there. This is for the playing track only. Each insight is a request of its own, with no conversation kept between them. It is made with your API key, so the service handles it under its terms for your account.
- **Your API keys** go only to the service each belongs to, with each request to it.
- **To Spotify**, only if you connect a Spotify account, once the app offers that: requests for the playlist or album being played, made with your own token.
- **To the server named in a web address**, when a player gives the cover only as a web address: a request for that image. The address itself may identify the album or episode.

Each of these services also sees your phone's IP address, as every service you connect to does. What each does with what it receives is set by its own policy: [MetaBrainz](https://metabrainz.org/privacy) (MusicBrainz), the [Wikimedia Foundation](https://foundation.wikimedia.org/wiki/Policy:Privacy_policy) (Wikipedia and Wikidata), [Last.fm](https://www.last.fm/legal/privacy), [Exa](https://exa.ai/privacy-policy), [Anthropic](https://www.anthropic.com/legal/privacy).

By default, lookups and writing happen only while Sleevesight is on screen. With *Look up in the background* on, they also happen while it is not.

Nothing else leaves the phone.

## What stays on the phone

- The length and cover of each track.
- The history: the tracks Sleevesight has seen play, and the record of track changes it uses to tell your skips from natural ends.
- What the sources found, kept for a while so the same track is not looked up again (the lifetimes are in the settings).
- The insights written, your settings, and your API keys. The keys are encrypted with a key kept in the phone's Android Keystore, are never shown again once saved, and are never logged.

None of this is part of Android's backup or of a transfer to a new phone.

## What Sleevesight collects

Nothing. No data about you or your listening reaches the developer of Sleevesight, so none is stored, sold or shared by them.

Google Play, which distributes the app, handles your installation and its own reports under [Google's privacy policy](https://policies.google.com/privacy).

## Your choices

- Each source is a switch, and *Write insights* off makes no AI calls at all.
- *Write insights automatically* sets whether an insight is written for every track, only when you skip, or only when you tap Write.
- *Prepare the next track* off sends nothing about the next track.
- *Keep history for* sets how long the history is kept, from a week to a year, or forever. *Clear history* deletes it at once.
- A saved key can be removed in the settings, and *Reset saved keys* removes them all.
- Uninstalling Sleevesight deletes everything it kept on the phone.

What a service keeps of your requests, such as Anthropic for your account, is between you and that service.

## This website

The site is served by GitHub Pages, so GitHub receives your IP address and the pages you ask for, under [GitHub's privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement). The site sets no cookies and has no analytics. The front page keeps one note in your browser, that its intro has played. Design pages with diagrams load the diagram script from jsDelivr.

## Children

Sleevesight is not directed at children under 13.

## Changes to this policy

A change is published on this page, with the date above. Its history is in the [repository](https://github.com/wkusnierczyk/sleevesight/commits/main/privacy.md).

## Contact

[support@sleevesight.app](mailto:support@sleevesight.app)
