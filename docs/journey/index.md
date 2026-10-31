---
# A typical user journey (#414), at `/docs/journey/`: from installing Sleevesight to using it day
# to day, step by step, each step naming the screen, what the user decides there and what follows.
# Written from the app's code on `main` (the first-run screen and its flow, the home screen, the
# settings and their defaults, `strings.xml` and `strings_home.xml`), the privacy policy
# (`privacy.md`) and the tester brief (`docs/tester/alpha/`). A change to the first-run flow, the
# home screen's states, a setting's label or default, or how the app is installed changes it too.
# Screenshots are placeholders in the source until real ones from the app are taken; each records
# the app version it comes from.
title: A typical journey
permalink: /docs/journey/
nav:
  - id: 1-installing-and-the-first-run-screen
    label: First run
  - id: 2-notification-access
    label: Notifications
  - id: 3-choosing-an-ai-service-and-saving-its-key
    label: AI & key
  - id: 4-choosing-the-sources
    label: Sources
  - id: 5-playing-music
    label: Playing
  - id: 6-write-automatic-writing-and-the-history
    label: Writing
---

# A typical journey

This page follows one person from installing Sleevesight to using it day to day: what each screen shows, what it asks you to decide, and what follows from your choice. [Usage]({{ '/docs/tutorials/usage/' | relative_url }}) describes each part of the app in detail, and [Settings]({{ '/docs/tutorials/settings/' | relative_url }}) every setting, with its default and why. What leaves the phone, and to whom, is in the [privacy policy]({{ '/privacy.html' | relative_url }}).

In short: you install the app, read what it sends where, and give it notification access; that is all the first run asks. Choosing an AI service, saving its key and choosing the sources happen in the settings, whenever you like. From then on you play music as usual, and Sleevesight follows.

## 1. Installing, and the first-run screen

**Installing.** Sleevesight is not yet listed on Google Play for everyone: it is in closed testing, which testers join by invitation; [Testing Sleevesight]({{ '/docs/tester/alpha/' | relative_url }}) says how to opt in and install. Only a copy installed from Google Play takes part in the test and gets its updates.

**The first-run screen.** Opened for the first time, Sleevesight shows one screen before anything else, and reads, builds or sends nothing until you continue past it. It says, under three headings:

- *What it reads*: first, that it reads what apps say they are playing, to find your music players and follow the ones you switch on, and that Android requires notification access for this; then what it checks of every player, and what the players you follow report (the player, the title, artists, album and its year, length and cover, whether it is playing and how far in, and the queue of tracks up next); and, as a line of its own, that Sleevesight receives no audio.
- *What leaves the phone, and to whom*: the playing track's title and artists to the sources you switch on; the title, artists, album and what the sources found to the AI service you choose; each API key only to its own service; a request for a cover's image when a player gives the cover only as a web address; an insight's text to the phone's text-to-speech engine, only when it is read aloud (some engines, such as Google's network voices, send it to their server to be spoken); and to the developer, only if you send it from your mail app, a report of a problem with an insight, or, in developer mode, a tester's report of the app's state; until you send it, your mail app handles it as any message you write, and may keep a draft in your mail account. Each service also sees your phone's IP address.
- *What stays on the phone*: everything else, apart from a diagnostics export you make yourself, a report you hand to your mail app, and, while you type a key on a computer, the phone's Bluetooth advertisement, which devices nearby can see; the history and your keys, which are stored encrypted, included.

At the bottom, a link opens the privacy policy in your browser.

**What you decide.** Nothing yet, apart from whether you are happy with this. The screen can be read again at any time from *What leaves the device?*, at the bottom of the home screen, or *What leaves the device* under *About* in the settings. Opened again, it has a back arrow at the top and *Close* at the bottom in place of *Continue*: both bring you back to where you opened it. Its introduction then leaves out "Before you start".

<!-- screenshot: the first-run screen, top, under the app's name: the intro and "What it reads", from app version … -->

## 2. Notification access

**Why.** A player reports what it is playing in a *media session*, and Android lists other apps' media sessions only to an app with notification access. Sleevesight reads the media sessions of the players you follow, not your notifications. The access cannot be limited to one app, so Android's screen for it warns that the app could read all your notifications.

**On the first-run screen**, under the text, a panel says *Notification access is off.*, tells you to turn on Sleevesight in the settings that open and come back, and offers *Open notification access*. Right above the button, under *Why notification access*, it explains why: Android lets an app see what music apps are playing only with notification access, and Sleevesight uses it for that alone; on Android 12 and newer, delivery of notifications to Sleevesight is disabled at system level and Sleevesight receives no notifications, and on older versions Sleevesight ignores all notifications; it never reads your messages or contacts. Then it says Sleevesight is open source, so you can check this yourself; the privacy policy's link follows the panel. The button opens Android's settings, where you turn on Sleevesight and come back. If no such settings screen opens, the app says to look for it under Settings, Apps, Special app access. Once access is on, the panel says *Notification access is on.* and offers *Continue*.

**What follows.** *Continue* takes you to the home screen, where the app opens from then on. If you turn notification access off later, the home screen says *Sleevesight needs notification access to see what is playing.*, with a short note under it: *Sleevesight uses notification access only to see what is playing. It receives no notifications on Android 12 and newer, and ignores them on older versions.*, and offers *Turn on notification access*, which brings back the first-run screen, with its back arrow and *Close* to return to the home screen.

With nothing playing yet, the home screen lists your music players, A to Z, each a row *Play on …* that opens the player, where you start the music; a player switched off is dimmed, with *Enable in Settings*, and with no player seen yet the screen says *No music player seen yet. Play music in one, and it is listed here and in Settings → Players.* Under the players, a small picture of the home screen while a track plays, with hand-written notes on what each part does: the guide, until you dismiss it ([usage]({{ '/docs/tutorials/usage/' | relative_url }}#nothing-playing)). At the top, the logo scrolls the screen back to the top, the theme button beside the cogwheel switches between the light, dark and system themes, and the cogwheel opens *Settings*.

<!-- screenshot: the first-run screen, bottom, with "Notification access is off." and "Open notification access", from app version … -->
<!-- screenshot: the first-run screen, bottom, with "Notification access is on." and "Continue", from app version … -->
<!-- screenshot: the home screen with nothing playing: the header with the logo and the cogwheel, the players, and the guide, from app version … -->

## 3. Choosing an AI service and saving its key

The first run does not ask for an AI service or a key. Without one, Sleevesight still looks the track up and shows what the sources found, and the insight's place says *Choose an AI service and save its key in Settings to have insights written.* To have insights written, open *Settings* with the cogwheel. For a focused guide to setting up all external services (AI, Last.fm and Exa), see [Setting up external services]({{ '/docs/services/' | relative_url }}).

The settings are in sections, each a chip with its name and, beside it, a line on what it holds: a tap on a chip opens that section as a panel in its place, with *Close* at its top right to turn it back into the chip, and several can be open at once. An AI service takes two steps, each in its own section: its switch, under *AI services*, and its key, under *Secrets*.

**The AI service.** Under *AI services*, *Anthropic*, *OpenAI* and *Google* each have a switch. Under each service, *Set your Anthropic API key here.* (or OpenAI's, or Google Gemini's) links to its key under *Secrets*. Until its key is saved, the service's switch is off and cannot be switched on, and the service says *Needs your Anthropic key.* (or OpenAI's, or Google's), above that link. Save the key (below), then switch the service on: saving a key does not switch its service on, and the link then reads *Update your Anthropic API key here.* One service writes at a time: switching one on switches the other off. Switching off a service does not erase its key or the most recently used model and effort settings, so you can switch back. Removing a service's key switches the service off.

**The key.** Each service that needs a key (Anthropic, OpenAI, Google Gemini, Last.fm and Exa) has its row under *Secrets* (for example *Anthropic API key*, *Not set*). Tap it, and the dialog offers three ways to enter the key, the last two as icons in the *Key* field:

- **Type it** into the *Key* field and tap *Save*. Once something is entered, an eye in the field shows it, to check it before it is saved; a saved key is never shown again.
- **Scan QR code** (the QR code icon) opens a screen that says what code to scan, one of this key alone, and where to make it, [sleevesight.app/api-keys]({{ '/api-keys.html' | relative_url }}), above the camera's view; it fills the field from the code, once the key has that service's form. A key read from a QR code is never shown, even before it is saved.
- **Type on a computer** (the keyboard icon) connects the phone to [sleevesight.app/keyboard.html]({{ '/keyboard.html' | relative_url }}), opened in Chrome or Edge on a computer with Bluetooth. Its screen says what it is and the steps, with a link to the page: you scan the code the page shows, choose *Connect* on the page and pick the phone (the only device listed, as *Unknown or Unsupported Device* unless *Share phone's name with Type on a computer* is on), and what you type on the computer appears in the field. The app asks for Bluetooth (*Nearby devices*) only then.

If you keep the key in a password manager, it offers its saved entries on the *Key* field, as on any password field; it is not asked to save the key.

To save several keys at once, *Load keys*, under *Secrets*, reads one QR code of several keys, made on a computer at [sleevesight.app/api-keys]({{ '/api-keys.html' | relative_url }}), which its row links, with the guide to loading keys. Its screen first shows five words, to type on the page: the code opens only with them, so a photo or a screenshot of the code alone gives nothing away. Then it says how to make the code, and reads it with the camera, opening it with the words; a code made with other words is refused, and the phone takes one code, within 5 minutes of showing the words, forgetting them when the screen is left. Before saving, it lists each key it found by its last four characters, says which would replace a key already saved, and names every problem with the code; *Save* saves them, and the screen then says which were saved. The camera is asked for only while you scan, and no frame is kept or sent. A key is stored encrypted on the phone, sent only to its own service, and never shown again once saved.

**What it costs.** Each insight written is a paid call on your key (now and then two; the [usage page]({{ '/docs/tutorials/usage/' | relative_url }}) says when), billed by the service to your account; Sleevesight takes no payment of its own. Each service starts on its cheapest current text model and a low reasoning effort (*Anthropic model*, *Anthropic reasoning effort* and their like), which you can change. How many calls you make depends on when insights are written: see step 6.

<!-- screenshot: Settings, the "AI services" section, with Anthropic's key not set, its switch off and greyed ("Needs your Anthropic key." and "Set your Anthropic API key here."), from app version … -->
<!-- screenshot: the key dialog: the masked "Key" field with its QR code, keyboard and eye icons, from app version … -->
<!-- screenshot: "Save these keys?" after "Load keys", listing keys by their last four characters, from app version … (with test keys only) -->

## 4. Choosing the sources

**The sources.** The sources are what the AI may use, under *Sources* in the settings, each with a link to its website:

- *MusicBrainz*, and *Wikipedia and Wikidata*: no key needed; on by default.
- *Last.fm*: it needs your own Last.fm API key ([create an API account](https://www.last.fm/api/account/create), free for non-commercial use), saved under *Secrets* in the same ways as an AI key above (typed, from a computer, or from a QR code); without one its switch is off and cannot be switched on. Once the key is saved, switch it on.
- *Exa web search*: likewise off until your Exa API key ([Exa dashboard](https://dashboard.exa.ai/api-keys); $10 of free credit a month, then billed per search) is saved under *Secrets* and you switch it on.
- *AI service knowledge*: off by default. On, the insight may hold at most one claim from what the model knows, marked unverified.

**What you decide.** Which sources to use, and whether to add keys for Last.fm and Exa. Each source you switch on receives the playing track's title and artists (Last.fm the artists, and for the genre labels also the title and the album, Wikidata only identifiers, and Exa also the film or show a title says it is from); a source switched off receives nothing. Under *Genres* you choose whose tags give the genre labels, or, with *Show genres* off, none at all. With *Proactive* on, as it is by default, under *Insights*, the next track in the player's queue is also looked up at MusicBrainz ahead of its play; off sends nothing about it. The [privacy policy]({{ '/privacy.html' | relative_url }}) lists what goes to each.

<!-- screenshot: Settings, "Sources", with the defaults: MusicBrainz and Wikipedia on, Last.fm and Exa without keys, the AI service knowledge off, from app version … -->

## 5. Playing music

**The players.** Sleevesight follows Spotify by default. Any other player that plays music (a track with a title and an artist) while Sleevesight has notification access appears under *Players* in the settings, by name and switched off; switch it on to follow it too. *Open the app ↗* under its name opens it. Nothing is kept or sent of a player you do not follow.

**The track appears.** Play music in a player you follow and open Sleevesight. The home screen follows the track, top to bottom: the player and its state (a tap opens the player), the cover with the playback position, the title, artists, album and year, the *Dates*, *Genres* and *Lyrics* buttons (*Genres* opens the genre labels), *Key insights*, and *Sources*. Lookups and writing run while the app is on screen.

**An insight is written.** While the sources are asked, *Key insights* says *Gathering evidence…*; while the AI service writes, *Writing…*, with *Stop writing*. The insight is a few sentences on why the track is interesting. On the very first track, and after the app starts afresh, it waits for a tap on *Write insight*, or for a writing mode chosen (step 6).

**Its citations.** Each sentence ends with chips for the sources it cites. A tap on a chip opens the *Sources* panel and jumps to the line it cites. A sentence the sources do not back (it cites nothing, or a line that does not exist) is underlined in red, and a note under the insight points to it, such as *The sentence underlined in red is not backed by the sources.*; otherwise there is none.

**The *Sources* list.** Under the insight, *Sources* starts folded, a chip alone, like the *Dates* and *Genres* buttons; a tap on it opens its panel. In the panel, a table of the sources that are on: each with the letter its citations use, as a chip that shows or hides its lines (dimmed when it gave none), its name and what it found; a note says when a source needs your key, could not be reached or failed; then come the evidence lines, and below them what was found but not verified, so not cited.

**Listening instead of reading.** *Read aloud*, the speaker beside the *Key insights* heading, has the phone's text-to-speech engine read the insight to you, the music lowered meanwhile; it makes no AI call. It is the phone's own built-in text-to-speech voice, so it sounds only as good as the phone's voice: not a natural or studio voice. A sentence the sources do not back, or one from the AI model's own knowledge, is read after "Unverified:". A tap on it while it reads (*Stop reading aloud*), a track change or leaving the app stops it. Whether the music is lowered or paused, the track line read first, and new insights read by themselves are settings under *Insights*.

**What you decide.** Nothing, as a rule: you listen. A wrong-looking sentence can be checked against its line under *Sources*. [Usage]({{ '/docs/tutorials/usage/' | relative_url }}) says what each state of *Key insights* means, offline or when a source fails.

<!-- screenshot: the home screen with a track playing: player row, cover, title and artists, genre labels, from app version … -->
<!-- screenshot: "Key insights" with an insight, its citation chips and the grounded line, from app version … -->
<!-- screenshot: "Sources" unfolded, with each source's chip and evidence lines, from app version … -->

## 6. Write, automatic writing, and the history

**Write insight.** Each time the app starts afresh, nothing is written automatically until you act in it: a tap on *Write insight*, which writes the track playing, or a writing mode chosen in the menu beside it (the current one included), which writes nothing by itself. Until then *Key insights* says *Tap Write insight to have it written from the sources below.* That first action is what lets automatic writing go on. Android usually keeps the app's process alive after you leave it, often for days, so coming back keeps it going; a restart of the phone, an update, or the system ending the app asks for it again.

**Automatic writing.** *Reactive*, under *Insights* in the settings, decides which tracks are written without a tap; the arrow beside *Write insight*, under *Key insights*, opens the same choices:

- *On demand*: only when you tap *Write insight*.
- *On track skip (auto)*: only for a track you skip to, not one that follows a track played to its end, which offers *Write insight* and says why. *Crossfade* (*Autodetect* by default), shown under it, helps tell the two apart.
- *On track change (auto)*, the default: a new insight for each new track.

*Show insights* off makes no AI calls at all; the sources are still shown.

**While you are away.** With *Look up in the background* off, the default, nothing is looked up or written while Sleevesight is not on screen. *Saver* (on, after 5 track changes, by default) stops one forgotten action from paying for days: past that many track changes away, automatic writing pauses. Opening the app resumes it; the track playing then is not written on its own, and *Key insights* says *Automatic writing was paused while Sleevesight was not on screen, and resumes now.*, with *Write insight*. With *Look up in the background* on, insights are written while you are away, up to that count, each a paid call on your key.

**An insight kept from before.** An insight is kept on the phone once written, so a track played again shows it at once, with no new call, as long as the sources find the same evidence and the AI service, model, effort and *AI service knowledge* are as they were; otherwise it is written afresh, as for a new track. While a track plays, when you save, replace or remove a key or switch a web search engine and the sources then find something different, or you choose another AI service or model, or change its effort or *AI service knowledge*, the kept insight is still shown, with *As written previously. Tap Rewrite insight to write it with the updated source or model settings.* under it, and is not written again on its own while the track plays: *Rewrite insight* writes it again on a tap. If an insight could not be written, *Key insights* says why (for example, *Writing failed: the AI service did not accept the key.*), and *Write insight* tries again, as a new call.

**The history.** Sleevesight keeps, on the phone only, information about the tracks it has seen play (titles, artists, albums, and when they played), never the music, and the track changes it uses to tell your skips from natural ends. *Keep history for* (90 days by default), which the insights and genre explanations written follow too, with *Clear history* under it, and *New session idle trigger* (30 minutes) are under *History*. There is no screen that lists the history yet; the track changes recorded can be seen on the diagnostics screen (*Diagnostics*, under *Developer*, which seven taps on *Version*, under *About*, show).

<!-- screenshot: Key insights waiting for the first tap: "Tap Write insight…" with the Write insight button, from app version … -->
<!-- screenshot: an insight written earlier, shown after the evidence changed, with "As written previously.", from app version … -->
<!-- screenshot: Settings, "Insights": Show insights, Reactive, Proactive, Saver, Look up in the background, from app version … -->

## 7. Changing settings later, and clearing what is kept

**Settings.** Every choice above can be changed later, and each change is saved at once. [Settings]({{ '/docs/tutorials/settings/' | relative_url }}) describes each one, with its default and what changing it sends or keeps; among those not met above are *Theme* (light, dark or as the phone is, which the theme button on the home screen sets too) and *Theme button*, *Layout* (the sources and the genres in place on the home screen, or each on a page of its own), *Keep Sources as you left them*, *Font*, *Logo intro at start* and *Restore dismissed messages*, under *Appearance*; how long each source's findings are kept, under *History*; the reading-aloud rows, under *Insights*; and *Only enabled players on Home*, under *Players*.

**Clearing what is kept.** Everything Sleevesight keeps stays on the phone and is left out of Android's backup. To remove it:

- **The history:** *Clear history*, under *Keep history for* in *History*, deletes everything kept about the tracks you played (the list of tracks played and the track changes, the insights and genre explanations written, and what the sources found), once you confirm with *Clear*; your API keys and settings stay, and your music is not touched. *Keep history for* sets how far back it reaches.
- **What sources said about artists:** *Keep what sources said about each artist* is off by default; turning it off after turning it on deletes what was kept.
- **The keys:** the delete button beside a saved key's row, or *Remove* in its dialog, deletes that key; *Purge keys*, under *Secrets*, lists them and deletes them all. If a saved key can no longer be read, *Purge keys* says so, as the way to enter the keys afresh.
- **Everything:** Android's *Clear storage* for Sleevesight, or uninstalling it, deletes all it kept, the insights written and the sources' findings included. Findings also expire on their own, on the lifetimes the settings show.

What a service keeps of the requests it received, such as the AI service for your account, is between you and that service; the [privacy policy]({{ '/privacy.html' | relative_url }}) says more.

<!-- screenshot: Settings, "History": Keep history for, Clear history, New session idle trigger, from app version … -->
<!-- screenshot: the "Clear history?" dialog, from app version … -->
