---
# The tester brief for the closed test on Google Play (#158), at `/docs/tester/alpha/`: linked from
# the invitation. Written from the build in the test, its first-run screen and settings, and the
# privacy policy (`privacy.md`); a change to what testers install, see or send changes it too, and
# the one-page flyer linked at its top with it (`assets/flyer/tester-flyer.html`, rendered by
# `assets/flyer/make_flyer.mjs` to the PDF and PNG the site serves; #566).
title: Testing Sleevesight
permalink: /docs/tester/alpha/
---

# Testing Sleevesight

**The short version:** the five steps of the test on one page, the [tester flyer]({{ '/assets/flyer/tester-flyer.pdf' | relative_url }}) (PDF, A4; also as an [image]({{ '/assets/flyer/tester-flyer.png' | relative_url }})). This page has the details.

Thank you for testing Sleevesight. This page says what the app is, how to install it, what to try, what to tell us, and what leaves your phone while you test.

## What Sleevesight is

An Android app that notices what your music player is playing and shows a short insight about the song: a few sentences on why it is interesting, each tied to the source it came from. Beside it, the cover, title, artists, album and year, genre labels, and the sources themselves, with the lines each gave and what could not be verified. An AI service of your choice writes the insight from what the sources found; the app checks every citation once the reply is complete, and marks a sentence that cites nothing. The [product page](https://sleevesight.app/) shows what a card looks like.

The app runs no server and has no accounts: nothing you play reaches its developer unless you send it. That also means we learn little unless you tell us: Google Play gives us only aggregated install statistics and, from phones that share usage and diagnostics with Google, crash reports that name no one. That is why your reports matter.

## Joining and installing

1. **Opt in.** You will receive an invitation email from Google Play Store. Open the opt-in link from your invitation, signed in to the Google account your phone's Play Store uses, and accept. Accepting from an account other than the one you received the invitation on will not work, as Play Store controls which accounts have been invited.
2. **Install.** Open the Play Store page from the opt-in page and install. Right after opting in, the Play Store on the phone may say *Item not found* for a while. Meanwhile, the store's web page, [play.google.com/store/apps/details?id=app.sleevesight](https://play.google.com/store/apps/details?id=app.sleevesight), opened in a browser with the same account, offers *Install* and sends the app to your phone.
3. **Install from Play only.** Only a copy installed from Play takes part in the test and gets its updates. On Android 13 or later, a copy installed any other way may also be refused notification access.

## First run

1. **The first-run screen** says what the app reads, what leaves the phone and to whom, and links the privacy policy.
2. **Notification access.** Android lets an app read what a player is playing only through notification access. Under the text, tap *Open notification access*, turn on Sleevesight in the settings that open, and come back. A player tells Android what it is playing (the track, artists, album, cover and position) in a *media session*, and Android shows a media session only to apps with notification access. Sleevesight reads the players' media sessions, not the notifications: it does not receive the audio, or your other notifications, such as messages or other apps' alerts. Once access is on, the screen offers *Continue*.
3. **Players.** Spotify is followed by default. More players appear under *Players* in Settings, by name and switched off, once any of them has played music (a track with a title and an artist) while Sleevesight has notification access turned on; switch those players on to follow them as well. Most video apps, which report no artist, and calls from the phone app are not listed.
4. **Sources.** MusicBrainz and Wikipedia need no key and are turned on by default. Last.fm and the Exa web search need your own keys; without them they are off and cannot be switched on, which is fine for the test. If you want to enable them, create API keys at their respective API consoles, and save each in its key row, under *Secrets* in Settings (each section of Settings unfolds with a tap on its name). To enter several keys at once, open [sleevesight.app/api-keys](https://sleevesight.app/api-keys) on a computer: it turns them into one QR code, which *Load keys*, under *Secrets*, reads. The page sends nothing anywhere. Saving a key does not switch its service on: switch it on under *Sources* once its key is saved.
5. **The AI service.** Save the API key of one service (Anthropic, OpenAI or Google) in its key row under *Secrets*, then switch that service on under *AI services*: until its key is saved, a service's switch is off and cannot be switched on, and the app shows the sources but writes no insight. Services are exclusive: at most one can be enabled at a time. You can use your own key, or the key we give you (below).

## The key we give you

So that you need not pay for testing, we can give you an Anthropic key of your own, on the project's account. It comes as a QR code, in person or in a private message, never on this page or anywhere public.

- **Saving it.** In Settings, under *Secrets*, tap *Load keys* and point the camera at the code. The app lists what it found, by the key's last four characters; *Save* saves it. Then switch on *Anthropic* under *AI services*. The app asks for the camera only while you scan, and no frame is stored on the device.
- **What it means for privacy.** With our key, your track's title, artists and album, and what the sources found, go to Anthropic under the project's account rather than your own. The developer still receives none of it: Anthropic's console shows how much each key is used, not what was sent. If you would rather keep it under your own account, use your own key instead.
- **Cost.** Each insight is a paid call (now and then two). *Reactive*, *On track change (auto)* by default, writes one on every track change, and that is where the cost is. The keys share a spending limit. If it is reached before the test ends, insights stop: *Key insights* says *Writing failed*, with a short reason such as *the AI service account needs credit, or has reached its spending limit*. If every insight fails like that, tell us at [support@sleevesight.app](mailto:support@sleevesight.app), and keep the app installed and opted in. With your own keys, [What it costs you]({{ '/docs/costs/' | relative_url }}) says what each service charges.
- **The key we give you is yours only.** Please do not share it. We can see each key's use, and can turn one off without touching the others.

## Using Sleevesight

- **Play music in Spotify** (or another player you follow), then open Sleevesight. The home screen follows the track: the cover, the title and artists, the genre labels, and under *Key insights* the insight.
- **Write insight.** Nothing is written automatically until your first action in the app: a tap on *Write insight*, or a mode chosen with the arrow beside it (the current one included). After that, under the default *Reactive: On track change (auto)*, a new insight is written for each new track as it starts, until Android closes the app in the background, often days later; after that, the next track waits for a tap on *Write insight*, or a mode chosen, again. *On track skip (auto)* writes only the tracks you skip to; *On demand* writes only when you tap *Write insight*. The arrow beside *Write insight* switches between them.
- **Skip detection.** Under *On track skip (auto)*, the app tells a skip from a track that ended on its own by the time left and, for Spotify, the player's states just before the change. It uses your crossfade setting, detected by default.
- **While you are away.** Lookups and writing happen only while the app is on screen, unless you turn on *Look up in the background*. Automatic writing pauses after 5 track changes away (*Saver*); *Key insights* then asks for a tap.
- **Citations.** Each sentence ends with chips for the sources it cites; a tap jumps to the evidence under *Sources*. A sentence that cites nothing is underlined. A note under the insight says how many sentences the sources do not back, only when there are any; *Show grounding details*, in the *Developer* settings, shows the check's full counts instead, and under *Sources* every source's status and the rejected matches.
- **Diagnostics.** The test build starts in developer mode, so Settings shows a *Developer* section, last. If it is not there (its *Developer mode* switch hides it), unfold *About* and tap *Version* seven times to bring it back. Under *Developer*, turn on *Diagnostics*, then *Open diagnostics*: what the app did since it started (each source's outcome and time, AI calls, the citation check), the track changes recorded, and what skip detection has learned. *Export* writes it to a file where you choose, which may be a cloud folder; nothing of it leaves the phone otherwise. *Report button*, *Auto* in the test build, shows the report button on the home screen (*What to tell us*, below).

## What to tell us

Anything that surprises you. In particular:

- **Insights:** a sentence that is wrong, one that its source does not support, an uncited sentence, or an insight that misses the point of the song.
- **Track changes:** a track change the app missed, or noticed late; the wrong track shown.
- **Skips:** under *On track skip (auto)*, an insight is automatically written only for a track you skip to, not for one that follows a track that played to its end. Tell us when the app took a track that ended on its own for a skip (and wrote an insight you did not ask for), or missed a skip (and wrote none).
- **Crashes**, freezes, or a screen that does not make sense.
- **Battery or data** use you did not expect.
- **The app icon:** how it looks on your home screen, with your launcher and, on Android 13 or later, with themed icons on. A screenshot of the home screen helps.
- **The product page's intro:** each time you open [sleevesight.app](https://sleevesight.app/) in the browser, one of several intros plays, picked at random (not on a reload, and not with *Reduce motion* on in your system settings); a click on the logo plays one again, picked afresh. Which did you like, and why? Did any feel too long? If one turned the logo, what did the turn remind you of?

**The report button.** The test build shows a small button with a bug on it at the bottom right of the home screen. It steps aside while the home screen scrolls, so it does not cover what passes under it, and comes back once the scrolling stops. Tap it as soon as you notice something, and choose *Report*: it takes what the app knows at that moment, so you need not note the track or the time. Before anything is sent, it shows you the whole report, in parts:

- **App:** when you tapped, with your time zone, and the app's version and build.
- **Settings:** your settings. Never your API keys.
- **Now playing:** the track as your player reports it, what the sources found, and the insight with its citation check.
- **Diagnostics:** what the app did recently, its last 500 steps (each source's outcome and time, the AI calls, the citation check), and what skip detection has learned.
- **Track-change history:** off until you turn it on. It lists the latest 100 track changes recorded on your phone, with the tracks' titles and times. For a track change or a skip it is what helps us most, but it lists the tracks you played, so turn it on only if you are happy to share it.

The report holds only what the app itself holds: its settings, what it received from your player, the sources and the AI service, and its diagnostics; never your API keys, and nothing else about your phone. Each part has a switch that leaves it out, and *Edit* to change its text; a part too long to edit there, as the diagnostics often are, you can leave out or edit in your mail app. A report too long for a mail app to take says so, and waits until you leave something out. Write what you expected under *What did you expect?*. *Open in mail app* then opens the report in your mail app, addressed to us, and it reaches us only when you send it from there; *Copy report* copies it instead, to send however you like. *Hide*, in the button's menu, puts the button away and says where to bring it back. To bring it back, or to keep it on screen even while the home screen scrolls, choose *Auto* or *Show* under Settings → *Developer* → *Report button* (if *Developer* is not there, see *Diagnostics* above).

For an insight that is wrong or offensive, *Report a problem*, under the insight, composes a report of that insight alone: it shows what it holds (the track, the insight and its evidence, the AI service and model), and opens it in your mail app, addressed to us, to send from there. Send anything else to [support@sleevesight.app](mailto:support@sleevesight.app), or, if you use GitHub, open an issue at [github.com/wkusnierczyk/sleevesight/issues](https://github.com/wkusnierczyk/sleevesight/issues). An issue there is public.

## What leaves your phone

In brief: requests go from your phone straight to each service, none through a server of ours.

- **To the sources you switch on:** the title and artists of the playing track (Wikidata gets only identifiers, never a name; for the genre labels, Last.fm also gets the album name, the only source that does). When a title names a film or show, as in "Main Theme (from *Some Film*)", Exa also gets that name. By default, the next track in the player's queue also goes to MusicBrainz, ahead of its play.
- **To the AI service:** the title, artists and album, and what the sources found, for the playing track only, with the key you saved.
- **To the server a cover's web address names,** when a player gives the cover only as an address.
- **Each of your API keys** is sent only to the corresponding service, with each request, over an encrypted (HTTPS) connection.
- **To us,** only a report you send from your mail app, which you see in full first, and never your keys. A tester report holds your settings, and the track changes only if you turn them on; a report of a problem with an insight holds neither. Until you send it, your mail app handles it as any message you write, and may keep a draft in your mail account.

Each of these services also sees your phone's IP address, as every service you connect to does. Everything else, the history included, stays on the phone, unless you export the diagnostics to a cloud folder or send the exported file yourself, or hand a report to your mail app (a tester report holds the track changes only if you add them); and while you type a key on a computer, devices nearby can see the phone's Bluetooth advertisement: a random identifier for that session, and the phone's name only with *Share phone's name with Type on a computer* on. Google Play, not the app, may also send us crash reports that name no one, if your phone shares usage and diagnostics with Google. The [privacy policy](https://sleevesight.app/privacy.html) says it in full.

## Staying in the test

Google Play asks for a closed test of at least 12 testers who stay opted in for 14 days in a row before the app can go public. So please stay opted in, and keep the app installed, for the whole 14 days, even on days you do not use it. If you want to leave earlier, tell us first, so we can find someone to take your place.

Thank you.
