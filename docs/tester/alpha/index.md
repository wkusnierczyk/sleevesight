---
# The tester brief for the closed test on Google Play (#158), at `/docs/tester/alpha/`: linked from
# the invitation. Written from the build in the test, its first-run screen and settings, and the
# privacy policy (`privacy.md`); a change to what testers install, see or send changes it too.
layout: default
title: Testing Sleevesight
permalink: /docs/tester/alpha/
logo: linear
---

<!-- The product page's type (`index.html`): Roboto Slab for the running text, Roboto Flex for the
     headings, Roboto Mono for code, from the page's own cut-down copies (`assets/fonts/make_page_font.py`),
     which hold printable ASCII, Latin-1 and the common punctuation, so this page's text needs no
     rebuild while it stays within them. -->
<style>
@font-face{font-family:"Sleevesight Flex";src:url({{ '/assets/fonts/RobotoFlex-page.woff2' | relative_url }}) format("woff2");
  font-weight:400 700;font-stretch:88% 100%;font-display:swap}
@font-face{font-family:"Sleevesight Slab";src:url({{ '/assets/fonts/RobotoSlab-page.woff2' | relative_url }}) format("woff2");
  font-weight:350 700;font-display:swap}
@font-face{font-family:"Sleevesight Mono";src:url({{ '/assets/fonts/RobotoMono-page.woff2' | relative_url }}) format("woff2");
  font-weight:500;font-display:swap}
.markdown-body{font-family:"Sleevesight Slab",ui-serif,Georgia,serif;font-weight:350;font-size:18px;line-height:1.55}
.markdown-body strong,.markdown-body b{font-weight:700}
.markdown-body h1,.markdown-body h2,.markdown-body h3{font-family:"Sleevesight Flex",system-ui,-apple-system,"Segoe UI",sans-serif;font-weight:700;letter-spacing:-.01em}
.markdown-body h1{font-stretch:90%}
.markdown-body h2,.markdown-body h3{font-stretch:92%}
.markdown-body code{font-family:"Sleevesight Mono",ui-monospace,"SFMono-Regular",Menlo,monospace;font-weight:500}
</style>

# Testing Sleevesight

Thank you for testing Sleevesight. This page says what the app is, how to install it, what to try, what to tell us, and what leaves your phone while you test.

## What Sleevesight is

An Android app that notices what your music player is playing and shows a short insight about the song: a few sentences on why it is interesting, each tied to the source it came from. Beside it, the cover, title, artists, album and year, genre labels, and the sources themselves, with what each found, rejected or could not verify. An AI service of your choice writes the insight from what the sources found; the app checks every citation before showing it. The [product page](https://sleevesight.app/) shows what a card looks like.

The app runs no server and has no accounts: nothing you play reaches its developer. That also means we learn little unless you tell us: Google Play gives us only aggregated install statistics and, from phones that share usage and diagnostics with Google, crash reports that name no one. That is why your reports matter.

## Joining and installing

1. **Opt in.** You will receive an invitation email from Google Play Store. Open the opt-in link from your invitation, signed in to the Google account your phone's Play Store uses, and accept. Accepting from an account other than the one you received the invitation on will not work, as Play Store controls which accounts have been invited.
2. **Install.** Open the Play Store page from the opt-in page and install. Right after opting in, the Play Store on the phone may say *Item not found* for a while. Meanwhile, the store's web page, [play.google.com/store/apps/details?id=app.sleevesight](https://play.google.com/store/apps/details?id=app.sleevesight), opened in a browser with the same account, offers *Install* and sends the app to your phone.
3. **Install from Play only.** Only a copy installed from Play takes part in the test and gets its updates. On Android 13 or later, a copy installed any other way may also be refused notification access.

## First run

1. **The first-run screen** says what the app reads, what leaves the phone and to whom, and links the privacy policy.
2. **Notification access.** Android lets an app read what a player is playing only through notification access. Under the text, tap *Open notification access*, turn on Sleevesight in the settings that open, and come back. A player tells Android what it is playing (the track, artists, album, cover and position) in a *media session*, and Android shows a media session only to apps with notification access. Sleevesight reads the players' media sessions, not the notifications: it does not receive the audio, or your other notifications, such as messages or other apps' alerts. Once access is on, the screen offers *Continue*.
3. **Players.** Spotify is followed by default. More players appear under *Players to follow* in Settings, switched off, once any of them has played something while Sleevesight has notification access turned on; switch those players on to follow them as well.
4. **Sources.** MusicBrainz and Wikipedia need no key and are turned on by default. Last.fm and the Exa web search need your own keys; without them they find nothing, which is fine for the test. If you want to enable them, create API keys at their respective API consoles, and add them in Settings under *Secrets*.
5. **The AI service.** Under *AI service*, switch on one service (Anthropic, OpenAI or Google); services are exclusive and at most one can be enabled at a time. Save the corresponding API key in *Secrets*; until you do, the app shows the sources but writes no insight. You can use your own key, or the key we give you (below).

## The key we give you

So that you need not pay for testing, we can give you an Anthropic key of your own, on the project's account. It comes as a QR code, in person or in a private message, never on this page or anywhere public.

- **Saving it.** In Settings, under *Secrets*, tap *Scan keys* and point the camera at the code. The app lists what it found, by the key's last four characters; *Save* saves it. Then switch on *Anthropic* under *AI service*. The app asks for the camera only while you scan, and no frame is stored on the device.
- **What it means for privacy.** With our key, your track's title, artists and album, and what the sources found, go to Anthropic under the project's account rather than your own. The developer still receives none of it: Anthropic's console shows how much each key is used, not what was sent. If you would rather keep it under your own account, use your own key instead.
- **Cost.** Each insight is a paid call. *Write insights automatically*, on by default, writes one on every track change, and that is where the cost is. The keys share a spending limit. If it is reached before the test ends, insights stop: the card says *The insight could not be written*, with a short reason such as *something went wrong* or *the AI service account needs credit*. If every insight fails like that, tell us at [support@sleevesight.app](mailto:support@sleevesight.app), and keep the app installed and opted in.
- **The key we give you is yours only.** Please do not share it. We can see each key's use, and can turn one off without touching the others.

## Using Sleevesight

- **Play music in Spotify** (or another player you follow), then open Sleevesight. The home screen follows the track: the cover, the title and artists, the genre labels, and under *Highlights* the key insights.
- **Write insight.** Nothing is written until your first tap on *Write insight*. After that, under the default *Write insights automatically: On every track change*, a new insight is written for each new track as it starts, until Android closes the app in the background, often days later; after that, the next track waits for a tap on *Write insight* again. *When I skip* writes only the tracks you skip to; *Never: only when I tap Write* writes only when you tap.
- **Skip detection.** Under *When I skip*, the app tells a skip from a track that ended on its own by the time left and, for Spotify, the player's states just before the change. It uses your crossfade setting, detected by default.
- **While you are away.** Lookups and writing happen only while the app is on screen, unless you turn on *Look up in the background*. Automatic writing pauses after 5 track changes away (*Pause automatic writing after*); the card then asks for a tap.
- **Citations.** Each sentence ends with chips for the sources it cites; a tap jumps to the evidence under *Sources*. A sentence that cites nothing is underlined. A line under the insight counts the sentences grounded.
- **Diagnostics.** In Settings, under *Developer* (developer mode is on in the test build), turn on *Diagnostics*, then *Open diagnostics*: what the app did since it started (each source's outcome and time, AI calls, the citation check), the track changes recorded, and what skip detection has learned. *Export* writes it to a file where you choose, which may be a cloud folder; nothing of it leaves the phone otherwise.

## What to tell us

Anything that surprises you. In particular:

- **Insights:** a sentence that is wrong, one that its source does not support, an uncited sentence, or an insight that misses the point of the song.
- **Track changes:** a track change the app missed, or noticed late; the wrong track shown.
- **Skips:** under *When I skip*, an insight is automatically written only for a track you skip to, not for one that follows a track that played to its end. Tell us when the app took a track that ended on its own for a skip (and wrote an insight you did not ask for), or missed a skip (and wrote none).
- **Crashes**, freezes, or a screen that does not make sense.
- **Battery or data** use you did not expect.
- **The app icon:** how it looks on your home screen, with your launcher and, on Android 13 or later, with themed icons on. A screenshot of the home screen helps.
- **The product page's intro:** each time you open [sleevesight.app](https://sleevesight.app/) in the browser, one of several intros plays, picked at random (not on a reload, and not with *Reduce motion* on in your system settings); a click on the logo plays one again, picked afresh. Which did you like, and why? Did any feel too long? If one turned the logo, what did the turn remind you of?

With each report, say which track was playing, when (roughly, with the time zone), and what you expected. For a track change or a skip, a diagnostics export helps us most: it holds what the app did, and every track change recorded on the phone, with the tracks' titles and times. That is the whole history kept (90 days by default), not only the tracks around your report, so send it only if you are happy to.

Send reports to [support@sleevesight.app](mailto:support@sleevesight.app), or, if you use GitHub, open an issue at [github.com/wkusnierczyk/sleevesight/issues](https://github.com/wkusnierczyk/sleevesight/issues). An issue there is public.

## What leaves your phone

In brief: requests go from your phone straight to each service, none through a server of ours.

- **To the sources you switch on:** the title and artists of the playing track (Last.fm gets only the artists, and Wikidata only identifiers, never a name). When a title names a film or show, as in "Main Theme (from *Some Film*)", Exa also gets that name. By default, the next track in the player's queue also goes to MusicBrainz, ahead of its play.
- **To the AI service:** the title, artists and album, and what the sources found, for the playing track only, with the key you saved.
- **To the server a cover's web address names,** when a player gives the cover only as an address.
- **Each of your API keys** is sent only to the corresponding service, with each request, over an encrypted (HTTPS) connection.

Each of these services also sees your phone's IP address, as every service you connect to does. Everything else, the history included, stays on the phone, unless you export the diagnostics to a cloud folder or send the exported file yourself. Google Play, not the app, may also send us crash reports that name no one, if your phone shares usage and diagnostics with Google. The [privacy policy](https://sleevesight.app/privacy.html) says it in full.

## Staying in the test

Google Play asks for a closed test of at least 12 testers who stay opted in for 14 days in a row before the app can go public. So please stay opted in, and keep the app installed, for the whole 14 days, even on days you do not use it. If you want to leave earlier, tell us first, so we can find someone to take your place.

Thank you.
