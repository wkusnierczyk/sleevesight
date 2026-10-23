---
# The tester brief for the closed test on Google Play (#158), at `/docs/tester/alpha/`: linked from
# the invitation. Written from the build in the test, its first-run screen and settings, and the
# privacy policy (`privacy.md`); a change to what testers install, see or send changes it too.
layout: default
title: Testing Sleevesight
permalink: /docs/tester/alpha/
---

# Testing Sleevesight

Thank you for testing Sleevesight. This page says what the app is, how to install it, what to try, what to tell us, and what leaves your phone while you test.

## What Sleevesight is

An Android app that notices what your music player is playing and shows a short insight about the song: a few sentences on why it is interesting, each tied to the source it came from. Beside it, the cover, title, artists, album and year, genre labels, and the sources themselves, with what each found, rejected or could not verify. An AI service of your choice writes the insight from what the sources found; the app checks every citation before showing it. The [product page](https://sleevesight.app/) shows what a card looks like.

The app runs no server and has no accounts: nothing you play reaches its developer. That also means we learn nothing unless you tell us, which is why your reports matter.

## Joining and installing

1. **Opt in.** Open the opt-in link from your invitation, signed in to the Google account your phone's Play Store uses, and accept. A link for another account, or an *internal app sharing* link, will not work.
2. **Install.** Open the Play Store page from the opt-in page and install. Right after opting in, the Play Store on the phone may say *Item not found* for a while. Meanwhile, the store's web page, [play.google.com/store/apps/details?id=app.sleevesight](https://play.google.com/store/apps/details?id=app.sleevesight), opened in a browser with the same account, offers *Install* and sends the app to your phone.
3. **Install from Play only.** Only a copy installed from Play takes part in the test and gets its updates. On Android 13 or later, a copy installed any other way may also be refused notification access.

## First run

1. **The first-run screen** says what the app reads, what leaves the phone and to whom, and links the privacy policy.
2. **Notification access.** Android lets an app read what a player is playing only through notification access. Under the text, tap *Open notification access*, turn on Sleevesight in the settings that open, and come back. The app ignores the notifications themselves. Once access is on, the screen offers *Continue*.
3. **Players.** Spotify is followed by default. Another player appears under *Players to follow* in Settings, switched off, once it has played something while Sleevesight has notification access; switch it on to follow it.
4. **Sources.** Under *Where to look*, MusicBrainz and Wikipedia need no key and are on. Last.fm and the Exa web search need your own keys; without them they find nothing, which is fine for the test.
5. **The AI service.** Under *AI service*, switch on one service (Anthropic, OpenAI or Google) and save its API key; until you do, the app shows the sources but writes no insight. You can use your own key, or the key we give you (below).

## The key we give you

So that you need not pay for testing, we can give you an Anthropic key of your own, on the project's account. It comes as a QR code, in person or in a private message, never on this page or anywhere public.

- **Saving it.** In Settings, under *API keys*, tap *Scan keys* and point the camera at the code. The app lists what it found, by the key's last four characters; *Save* saves it. Then switch on *Anthropic* under *AI service*. The app asks for the camera only while you scan, and keeps no frame.
- **What it means for privacy.** With our key, your track names, what the sources found and the request go to Anthropic under the project's account rather than your own. The developer still receives none of it: Anthropic's console shows how much each key is used, not what was sent. If you would rather keep it under your own account, use your own key instead.
- **Cost.** Each insight is a paid call. *Write insights automatically*, on by default, writes one on every track change, and that is where the cost is. The keys share a spending limit. If it is reached before the test ends, insights stop: the card says *The insight could not be written*, with a short reason such as *something went wrong* or *the AI service account needs credit*. If every insight fails like that, tell us at [support@sleevesight.app](mailto:support@sleevesight.app), and keep the app installed and opted in.
- **Your key is yours.** Please do not share it. We can see each key's use, and can turn one off without touching the others.

## Using it

- **Play music in Spotify** (or another player you follow), then open Sleevesight. The home screen follows the track: the cover, the title and artists, the genre labels, and under *Why it's interesting*, the insight.
- **Write insight.** Nothing is written until your first tap on *Write insight*. After that, under the default *Write insights automatically: On every track change*, each new track is written as it starts, until Android closes the app in the background, often days later; after that, the next track waits for a tap on *Write insight* again. *When I skip* writes only the tracks you skip to; *Never: only when I tap Write* writes only when you tap.
- **Skip detection.** Under *When I skip*, the app tells a skip from a track that ended on its own by the time left and, for Spotify, the player's states just before the change. It uses your crossfade setting, detected by default.
- **While you are away.** Lookups and writing happen only while the app is on screen, unless you turn on *Look up in the background*. Automatic writing pauses after 5 track changes away (*Pause automatic writing after*); the card then asks for a tap.
- **Citations.** Each sentence ends with chips for the sources it cites; a tap jumps to the evidence under *Sources*. A sentence that cites nothing is underlined. A line under the insight counts the sentences grounded.
- **Diagnostics.** At the end of Settings, turn on *Diagnostics*, then *Open diagnostics*: what the app did since it started (each source's outcome and time, AI calls, the citation check), the track changes recorded, and what skip detection has learned. *Export* writes it to a file you choose; nothing of it leaves the phone otherwise.

## What to tell us

Anything that surprises you. In particular:

- **Insights:** a sentence that is wrong, one that its source does not support, an uncited sentence, or an insight that misses the point of the song.
- **Track changes:** a track change the app missed, or noticed late; the wrong track shown.
- **Skips:** a skip taken for a track that ended on its own, or the reverse (under *When I skip*).
- **Crashes**, freezes, or a screen that does not make sense.
- **Battery or data** use you did not expect.
- **The app icon:** how it looks on your home screen, with your launcher and, on Android 13 or later, with themed icons on. A screenshot of the home screen helps.
- **The product page's intro:** each time you open [sleevesight.app](https://sleevesight.app/), one of several intros plays, picked at random (not with *Reduce motion* on in your system settings), and a click on the logo plays another. Which did you like, and why? Did any feel too long? If one turned the logo, what did the turn remind you of?

With each report, say which track was playing, when (roughly, with the time zone), and what you expected. For a track change or a skip, a diagnostics export helps us most: it holds the track changes and what the app did with them. It also lists the tracks you played around then, so send it only if you are happy to.

Send reports to [support@sleevesight.app](mailto:support@sleevesight.app), or, if you use GitHub, open an issue at [github.com/wkusnierczyk/sleevesight/issues](https://github.com/wkusnierczyk/sleevesight/issues). An issue there is public.

## What leaves your phone

In brief: requests go from your phone straight to each service, none through a server of ours.

- **To the sources you switch on:** the title and artists of the playing track (Last.fm gets only the artists, Wikidata only identifiers, and Exa also the film or show a title says it is from). By default, the next track in the player's queue also goes to MusicBrainz, ahead of its play.
- **To the AI service:** the title, artists and album, and what the sources found, for the playing track only, with the key you saved.
- **To the server a cover's web address names,** when a player gives the cover only as an address.
- **Your API keys** go only to the service each belongs to, with each request to it.

Each of these services also sees your phone's IP address, as every service you connect to does. Everything else, the history included, stays on the phone, unless you export the diagnostics and send them yourself. The [privacy policy](https://sleevesight.app/privacy.html) says it in full.

## Staying in the test

Google Play asks for a closed test of at least 12 testers who stay opted in for 14 days in a row before the app can go public. So please stay opted in, and keep the app installed, for the whole 14 days, even on days you do not use it. If you want to leave earlier, tell us first, so we can find someone to take your place.

Thank you.
