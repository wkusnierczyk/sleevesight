---
# The settings page (#416), at `/docs/tutorials/settings/`: every setting on the app's settings
# screen, in the screen's order, with what it does, its default and why, and what changing it sends
# or keeps. Written from the settings screen's code (`app/.../ui/settings/`), its labels
# (`strings.xml`), the defaults and their reasons (`core/.../settings/Settings.kt`), the key store
# (`app/.../secrets/`), `privacy.md` and the credits screen. A change to a setting, its label, its
# default, or what it sends or keeps changes this page too.
title: Settings
permalink: /docs/tutorials/settings/
---

# Settings

The settings screen opens from the cogwheel. This page goes through it from top to bottom, in the order the screen shows, and says for each setting what it does, what it is set to at first and why, and what changing it sends or keeps. The [usage guide]({{ '/docs/tutorials/usage/' | relative_url }}) says how the rest of the app works, and the [privacy policy]({{ '/privacy.html' | relative_url }}) what leaves the phone, to whom.

<!-- screenshot: the top of the settings screen, with the AI service section, from the app version it is taken from -->

A few things hold for the whole screen:

- **Each change is saved at once.** There is no *Save* button for settings. If a change cannot be saved, because the phone's storage failed, the setting keeps its saved value, and a line above the list says *The last change could not be saved*; try again.
- **Some rows depend on others.** A row that has no effect under the current choices is shown but cannot be changed: *Write insights automatically* while *Write insights* is off, for example, or the web search engines while *Web search* is off. Some say why.
- **The labels in italics** on this page are the labels on the screen.
- **Settings and keys stay on the phone.** Neither is part of Android's backup or of a transfer to a new phone.

## Reset saved keys (only after a failure)

Normally the list starts with the AI service. Only when a saved key can no longer be read, or a key could not be saved because the phone's key store failed, a row *Reset saved keys* shows first. It removes every saved key, so you can enter them again; it does the same as *Remove all API keys* ([below](#api-keys)). Sleevesight never deletes your keys on its own. See [Where the keys are kept](#where-the-keys-are-kept).

## AI service

The AI service writes the insights: a few sentences about the playing track, each tied to the evidence the sources found. Each service has rows of its own: a switch, its API key, its model and its reasoning effort. The services offered are Anthropic, OpenAI and Google; a local model, through Ollama, is planned and not in the app yet.

- **The switch.** *The service switched on writes the insights, one service at a time.* Switching one on switches the one on before off. With all of them off, no insight is written. **Default: all off.** The key and its bill are yours, so the app writes nothing until you choose a service and save its key. A service switched on without a saved key says *Insights wait until its API key is saved*.
- **A service switched off keeps its key, model and effort**, so switching back restores them.
- **What it receives.** The service switched on gets the playing track's title, artists and album, and what the sources found, with your key. Each insight is a request of its own, with no conversation kept between them, and only for the playing track: the next track in the queue may be looked up ahead, but nothing is written for it until it plays. The service handles the request under the terms of your account with it ([Anthropic](https://www.anthropic.com/legal/privacy), [OpenAI](https://openai.com/policies/privacy-policy/), [Google](https://policies.google.com/privacy) with the [Gemini API terms](https://ai.google.dev/gemini-api/terms)).

### The API key

Each service's key row (*Anthropic API key*, *OpenAI API key*, *Google Gemini API key*) shows *Not set*, *Saved*, or *Saved, but it can no longer be read*; a saved key is never shown again. Tapping the row opens the key's dialog, which also says, in a line with a link to them, what the service's terms require: Anthropic's set no minimum age and say the API is not for consumer use, though Anthropic welcomes individuals and hobbyists on it; OpenAI's are for businesses and developers, and let minors use the API only with a parent's or guardian's consent; Google's require you to be 18 or older, and are for professional or business purposes, not for consumer use. Using the key means agreeing to them. The [privacy policy]({{ '/privacy.html#children-and-the-ai-services-own-terms' | relative_url }}) quotes them. How to get a key, enter it and remove it is under [Entering a key](#entering-a-key); where it is stored, under [Where the keys are kept](#where-the-keys-are-kept).

- **Anthropic:** create a key in the [Claude Console](https://platform.claude.com/settings/keys).
- **OpenAI:** create a key on the [OpenAI platform](https://platform.openai.com/api-keys).
- **Google:** create a Gemini API key in [Google AI Studio](https://aistudio.google.com/apikey). Google's [Gemini API terms](https://ai.google.dev/gemini-api/terms) set what its free tier allows: on it, outside the EEA, Switzerland and the UK, Google uses what is sent to improve its products, and for apps made available to users in those three, the terms allow only the paid tier. A key from a Google Cloud project with billing on is on the paid tier.

### The model

*Anthropic model* (and *OpenAI model*, *Google model*) shows the model in use. **Default: each service's cheapest current text model**, marked *(default)* in the list: Claude Haiku 5.5 (`claude-haiku-5-5`) for Anthropic, GPT-6 Luna (`gpt-6-luna`) for OpenAI, Gemini 3.5 Flash-Lite (`gemini-3.5-flash-lite`) for Google. An insight narrates a few lines of evidence in a few sentences, which needs no larger model, and the bill is yours.

Opening the row lists the service's models as the service itself reports them for your key, so the dialog sends your key to that service, and to no other, to ask for the list. It says *Save the API key to list the service's models* without a key, and why when the list cannot be had (the key refused, no connection, the quota used up, the service not offered in your country). The default stays offered either way, and so does a model you chose earlier. Choosing the default keeps no choice of your own, so a later version that changes the default takes you with it.

### The reasoning effort

*Anthropic reasoning effort* (and the others): *How much the model may reason before it writes. Less is faster and cheaper.* Each service offers its own levels, as it names them, and *The service's default*, which sends no effort at all and leaves it to the service.

| Service | Levels offered | Default |
|---|---|---|
| Anthropic | *Low*, *Medium*, *High* | *Low* |
| OpenAI | *Low*, *Medium*, *High* | *Low* |
| Google | *Minimal*, *Low*, *Medium*, *High* | *Low* |

**Why low:** an insight is a few sentences from a few lines of evidence, and you wait for it on the now-playing screen, so speed matters more than depth. What keeps the text honest is the citation check the app runs on every sentence, not the model's deliberation. Low is also the least level every current model of each service takes, so the default means the same whichever model you choose, and cards from the services are compared at one effort. For more deliberate cards, raise it; for Google's fastest, choose *Minimal*.

### What it costs

Each insight written is a paid call on your key, now and then two (the [usage page]({{ '/docs/tutorials/usage/' | relative_url }}) says when). What a call costs is set by the service's price for the model you choose: the fixed instructions, the track and its evidence go in, and a few sentences come out, more with a higher reasoning effort, which the services bill as output. Check your service's pricing page for the model you use. How many calls are made is up to the [Insights](#insights) settings: by default one for every track you play while the app is on screen, and none while it is not.

## Players to follow

*Sleevesight follows only the players switched on here.* Each player is a switch, with its name and its package name under it.

- **Default: Spotify on.** Other players appear here by name, switched off, once one of them has reported music (a track with a title and an artist) while Sleevesight has notification access; switching one on follows it at once. Most video apps report no artist, so they are not listed.
- **A player not switched on** is only checked for a title and an artist, to be listed here; nothing of it is kept or sent.
- **Calls are never read.** Calls from the phone app, and sessions Android marks as voice calls, are never read, whatever the switch. A calling app that shows its call as an ordinary media session is not recognised as a call. An app you follow that also makes calls (not the phone app, which is never read) may show leftover caller details as a track for a moment after a call, and a call Sleevesight did not see (after a restart, or while notification access is off) is read like any player's once Android no longer marks it.
- **No Spotify login.** Sleevesight reads the phone's media session, not Spotify's Web API, so following Spotify needs no Spotify account connection or developer app, whichever plan you have. Connecting a Spotify account, to see the whole playlist or album, is planned and not in the app yet.

## Where to look

*What the AI may use.* Each source is a switch. **Default: every source on, except the model's own knowledge.** Turning a source off means nothing is sent to it and nothing from it reaches the AI service. What each one receives is the playing track's title and artists, cleaned of tags such as "2006 Remaster", unless said otherwise below; the album name goes to none of them. Each also sees your phone's IP address, as every service you connect to does. The credits, under [About](#about), list each source with its licence.

- ***MusicBrainz*** (no key). Identifies the recording and its artists, and gives its credits and release dates. It is also what the other sources' matches are checked against: with it off, they match by name only. It is asked at one request a second, as its rules ask, which makes it the slowest source. Its core data is under [CC0](https://creativecommons.org/publicdomain/zero/1.0/); its use is under the [MusicBrainz API's rules](https://musicbrainz.org/doc/MusicBrainz_API) and [MetaBrainz's privacy policy](https://metabrainz.org/privacy). While *Prepare the next track* is set to *Evidence*, MusicBrainz also gets the next queued track's title and artists ([Insights](#insights)).
- ***Wikipedia and Wikidata*** (no key, one switch). Sentences from Wikipedia articles about the song and the artists, and facts from Wikidata. Wikidata gets only identifiers (from MusicBrainz or Wikipedia), never a name. Wikipedia's text is by its contributors under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); Wikidata's data is under [CC0](https://creativecommons.org/publicdomain/zero/1.0/); both under the [Wikimedia Foundation's privacy policy](https://foundation.wikimedia.org/wiki/Policy:Privacy_policy).
- ***Last.fm*** (your key; *Last.fm API key* under the switch). Artist bios, listeners, tags and similar artists. Last.fm gets the artists' names only, not the title. Without a key it is not asked, which is fine: the app works without it. Get a key by [creating an API account](https://www.last.fm/api/account/create) on Last.fm. Bios are by Last.fm users under CC BY-SA; the rest is under [Last.fm's API terms](https://www.last.fm/api/tos) and its [privacy policy](https://www.last.fm/legal/privacy).
- ***Web search*** (your key per engine). Pages about the recording, found by a web search engine. Each engine has a switch of its own under *Web search*, with its key; Exa is the only engine so far, and another joins the list once it is built. **Default: every engine on**, but an engine searches only once its key is saved: the switch is for turning off one whose key is saved, without removing the key. While *Web search* is off, the engines show off, cannot be changed, and say *Off while "Web search" is off*; with it on and no engine on, it says *No engine is on, so nothing is searched*.
  - ***Exa*** (*Exa API key*): gets the title and artists, and the film or show a title says it is from. Get a key from the [Exa dashboard](https://dashboard.exa.ai/api-keys). Exa bills per search, with a monthly free credit at the time of writing ([Exa's pricing](https://exa.ai/pricing)); its results are used under [Exa's terms of service](https://exa.ai/terms) and [privacy policy](https://exa.ai/privacy-policy).
- ***The model's own knowledge*** (**off by default**). *At most one claim from what the model knows, marked unverified.* It sends nothing extra anywhere: it lets the AI service add one sentence from its own knowledge, which the card marks with an *M* chip instead of a source's, read by a screen reader as "From the model's own knowledge, not verified". Off by default because nothing checks such a sentence against a source.

Discogs, as a further source, is planned for a later phase and not in the app yet.

## Genre labels

*Where the colored genre labels come from: the artists' tags at each source, kept only when they name a genre.* Each source of labels is a switch. **Default: both on**, Last.fm and MusicBrainz.

- **With both on**, Last.fm's labels come first and MusicBrainz's fill the rest, five at most, and a label both give is shown once, crediting both.
- **A source gives labels only while it is on under *Where to look***, since the tags come with its lookup: until then its switch shows off, cannot be changed, and says *Off under "Where to look"*. Last.fm also needs its key: without one it says *Needs your Last.fm API key, under "Where to look"*.
- **MusicBrainz's tags** are by its contributors under [CC BY-NC-SA 3.0](https://creativecommons.org/licenses/by-nc-sa/3.0/). They are used for labels only, never sent to the AI service, and each label from them credits and links MusicBrainz. They are on by default because MusicBrainz needs no key, and a new user, who has no Last.fm key, would otherwise get no labels at all. Whether they stay on by default in the Google Play release depends on MetaBrainz's answer about how its tags may be used ([#100](https://github.com/wkusnierczyk/sleevesight/issues/100)).
- Web search gives no tags.

## Insights

When an insight is written, and what is looked up ahead.

- ***Write insights*** (**default: on**). *Off: no AI calls at all. The evidence is still shown.* The master switch: with it off, nothing is written, and the rows about writing (*Write insights automatically*, *Pause automatic writing after*, *Crossfade*) cannot be changed. *Prepare the next track* still can, and still looks the next track up at MusicBrainz ahead of its play while it is set to *Evidence*.
- ***Write insights automatically*** (**default: *On every track change***). *Each insight written is a paid call on your AI service key.*
  - *On every track change*: an insight is written as each track starts, with its lookups mostly done ahead.
  - *When I skip*: only when you skip a track, which the app tells from a natural end by watching the playback (see *Crossfade*).
  - *Never: only when I tap Write*: nothing is written until you tap *Write insight* on the card.
- ***Pause automatic writing after*** (**default: 5 track changes away**; also 3, 10, or *Off: never pause*). *Track changes while Sleevesight is not on screen, counted again from each time you open it.* Only while something is written automatically; otherwise it cannot be changed.
  - Why it is there: after the app starts, nothing is written until your first action in it. That action then lasts as long as the app's process, which the notification listener usually keeps alive after you leave the app, often for days: coming back from the player, with the screen turned on again, or from the launcher, keeps it, and writing goes on without a new tap. A restart of the phone, an update of the app, or the system ending the process (on some phones, a swipe from Recents) starts afresh. So one tap could keep paying for days.
  - With *Look up in the background* on, up to five tracks are written while you are away, and from the sixth on, nothing more is written until you tap *Write insight*, with a line on the card saying why. Those five are paid calls made while nobody is looking.
  - With *Look up in the background* off, as by default, nothing is written while you are away anyway; the count decides whether coming back writes the track playing (back within five track changes) or waits for your tap (back after more). The row's text under it says which applies.
  - Opening the app starts the count again, so coming back after exactly five keeps writing.
- ***Crossfade*** (**default: *Autodetect***; or a fixed 0 to 15 seconds). *The player's crossfade, for telling your skips from natural ends under "When I skip".* With *Autodetect*, the app learns Spotify's crossfade from its track changes, on the phone; for any other player it goes by timing alone, taking a change with up to 15 seconds left as a natural end, so a skip in the last 15 seconds is not told apart. A fixed value is for a player whose crossfade you know.
- ***Prepare the next track*** (**default: *Evidence***; or *Off*). *Looks up the next track in the player's queue ahead, so its sources come sooner when it plays. No insight is written ahead.* Under *Evidence*, the next track's title and artists go to MusicBrainz, if it is on, before that track plays, so MusicBrainz, the slow source, has mostly answered by then. No insight is written ahead, and none is paid for: the queue, as the app reads it, gives no album or length, so an insight written from it could not be reused when the track plays, and would be paid for twice. *Off* sends nothing about the next track.

A setting for the insights' style, with presets or your own words for their length and tone, is planned; it will not loosen the citation rules.

## Home screen

- ***Keep Sources as you left them*** (**default: off**). *Off: the sources under each new track's insight start folded. On: they stay folded, or unfolded, as you left them on the track before; shown past their first 8 entries but not in full, they come back at their first 8.* Off by default so that the insight is what you see first on each new track: unfolding the sources to check one insight says nothing about the next. The fold is kept through a rotation, and when Android restores a screen it closed, not when the app starts afresh. Nothing is sent or kept either way.

## Lookups

- ***Look up in the background*** (**default: off**). *Off: lookups and insights only while Sleevesight is on screen. On: insights written automatically are paid calls on your key, made while Sleevesight is not on screen.* Off by default, so there is no paid or network activity that nobody is looking at. On, the sources are asked, and insights are written automatically (within *Pause automatic writing after*), while the app is in the background too.
- ***How long each source's findings are kept.*** What each source found for a track is kept on the phone for a while, so the same track is not looked up again, and is not asked for again from a paid search. Each source has its own lifetime, chosen from 1, 3, 7, 14, 30 or 90 days. **Defaults:** *MusicBrainz* and *Wikidata* 30 days, whose data changes slowly; *Wikipedia* and *Last.fm* 7 days, whose articles and bios are edited more often; *Web search* 1 day. A shorter lifetime means fresher findings and more requests; a longer one, fewer requests. Old entries are deleted at each start of the app, and are never used once expired.
- ***Keep what sources said about each artist*** (**default: off**). A privacy choice, so its title is shown in the theme's error colour. What the sources say about an artist (Last.fm's answers and tags, the Wikipedia artist article and its Wikidata facts, MusicBrainz's tags), kept together, amounts to a record of the artists you have played, so it is kept only if you choose.
  - **Off:** none of it is kept, per artist or with a track's findings (nor the sources' verdicts on artists' pages, nor the artists' MusicBrainz ids). Each track's artists are looked up again, and their names go to the sources you switch on with every track. Of a track's findings, only the web search's (pages about the recording, which may name or describe its artists) and those of a lookup that found no artist to look up are kept. So a track played before is looked up again at MusicBrainz (its match held in memory for half an hour only), Wikipedia and Last.fm, unless it has no credited artist; lookups are slower, and more requests go out.
  - **On:** what the sources say about each artist you play stays on the phone for as long as its sources' findings, so another track by the same artist does not look the artist up again, and fewer requests leave the phone. An artist's MusicBrainz identity is then kept for 90 days, a fixed lifetime. Each track's own lookups still send its artists' names.
  - **Either way**, what stays is the record of which tracks you played (the history, and the keys of the kept findings) and the insights written, which may retell some of what the sources said. MusicBrainz's answers are held in the app's memory for half an hour, for the track about to play, and never written to storage.
  - **Turning it off deletes what was kept**, and so does every start of the app while it is off, which also removes what earlier versions kept without asking.

## History

*The tracks Sleevesight has seen play, and the track changes it learns your skips from. Kept only on this phone.*

- ***Keep history for*** (**default: 90 days**; also 7, 30 or 365 days, or *Forever*). How long the tracks played and the track changes are kept, counted back from today. The track changes are what skip detection learns from (today, Spotify's crossfade; learning from rarer signals, such as a tap on *Write insight*, is planned), so it wants a season of them rather than a week; 90 days keeps them to a few MB. A week is the least offered: shorter would empty the list of someone who listens once a week. A shorter lifetime applies at once.
- ***Start a new session after a break of*** (**default: 30 minutes**; also 10, 20 or 60 minutes, or 2 hours). *Counted from when the last track was heard. A change applies from the next track that starts.* Longer than a pause to answer the door or change trains, shorter than the gap between the morning's listening and the evening's. Sessions also shape the insights: an artist fact the AI has already told in this session is not offered again for the next track by the same artist.
- ***Clear history***. *Deletes the tracks played and the track changes*, once you confirm; it cannot be undone. Skip detection then forgets what it learned from them. It does not delete the insights written or the sources' findings: those go by their own lifetimes and limits ([What stays on the phone](#what-stays-on-the-phone)).

Old entries go by the phone's clock. Each time Sleevesight starts, with the phone or after it was stopped, it takes the clock as right; if the clock is then moved by more than an hour, ahead or back, nothing is deleted until it is set right again. So a clock set ahead before Sleevesight starts deletes old entries by that clock.

## Appearance

- ***Font*** (**default: *App font (Roboto)***; or *System font*). *The app's own font looks the same on every phone. The system font is the one chosen in your phone's settings.* Many phones' system font is not Roboto (Samsung's, for one), so without its own font the app would look different from phone to phone, and from its store screenshots. Roboto comes with the app, so nothing is downloaded. The change shows at once, in the whole app; the diagnostics screen's monospace text stays monospace.
- ***Logo intro at start*** (**default: on**). *When you open Sleevesight while it is not already open, its logo plays a few seconds of animation first; a tap, Back or any key skips it. It never plays while your phone's Remove animations or TalkBack is on.* Turn it off for no wait at all. It applies from the next time the app is opened.

## API keys

*Your API keys, stored encrypted on this phone. Each is sent only to its own service.* Each key's own row sits with what it is for: the AI services' under [AI service](#ai-service), Last.fm's and Exa's under [Where to look](#where-to-look). This section holds what concerns all keys:

- ***Scan keys***: *Save several keys from one QR code* ([below](#several-keys-at-once-scan-keys)).
- ***Remove all API keys***, shown while any key is saved: *Deletes every saved key from this phone*, once you confirm, and the phone's key for them with it. Until you enter a key again, the service it belongs to is not used.

### Entering a key

Tap a key's row. The dialog says who the key goes to (*Stored encrypted on this phone, and sent only to …*), and offers a field and its ways to fill it. *Save* saves what the field holds; spaces around it, as a paste often brings, are dropped. The field is masked, what is typed is kept only in the dialog's memory, and the dialog, like every key screen, cannot be captured in a screenshot or a screen recording.

- **Typed or pasted** into the field.
- ***Scan QR code***: reads a QR code holding the key, and fills the field with it once it has that service's form: an Anthropic key starts `sk-ant-`, an OpenAI key `sk-` but not `sk-ant-`, a Google key `AIza` or `AQ.`, a Last.fm key is 32 hexadecimal digits, an Exa key 16 to 200 characters. Otherwise it says why, and leaves the field as it was. A code of several keys is pointed to *Scan keys*. Sleevesight asks for the camera only when you scan, and reads the code on the phone; no camera frame is kept or sent.
- ***Type on a computer***: type the key on a computer's keyboard instead of the phone's. Open [sleevesight.app/keyboard.html]({{ '/keyboard.html' | relative_url }}) on a computer with Bluetooth, in Chrome or Edge (other browsers have no Web Bluetooth); tap *Type on a computer* and scan the code the page shows; then choose *Connect* on the page and pick the phone. The field follows what you type on the computer, up to 480 bytes, and *Save* saves it as usual; *Stop*, or closing the dialog, ends the session. The text goes over Bluetooth alone, encrypted with a key the page makes for that visit and shows only in its QR code; no server is involved, the page is allowed no network request, and the phone sends nothing back but Bluetooth's acknowledgements. Each code works once: reload the page for a new one. Sleevesight asks for Bluetooth (*Nearby devices*) only then.

A saved key's row has a delete button that removes that key once you confirm; so does *Remove* in its dialog. Saving a new key over a saved one replaces it.

### Several keys at once: Scan keys

*Scan keys*, under *API keys*, reads a setup code holding several keys, and saves them together.

1. On a computer, open [sleevesight.app/api-keys]({{ '/api-keys.html' | relative_url }}). Give it your keys file (`NAME=value` lines, such as `SLEEVESIGHT_ANTHROPIC_API_KEY=sk-ant-…`), chosen or dropped onto the page, or paste the keys one per service. It checks each against its service's form, leaves out and names those that fail, and draws one QR code of the rest, listing each key by its last four characters. The page is allowed no network request and stores nothing: the keys are kept only while the tab is open, and *Clear* removes them.
2. On the phone, tap *Scan keys* and point the camera at the code. When the code holds an AI service's key, the list adds that using it means agreeing to that service's terms, which its key dialog states.
3. Before anything is saved, the app lists what it found (*Save these keys?*), each key by its last four characters, says which would replace a key already saved, and names the keys refused and any names this version does not know. *Save* saves them.

Never make such a code with an online QR generator: it would receive your keys. A code that is not a Sleevesight setup code is refused, and one from a newer version of the page asks you to update the app.

### Where the keys are kept

Your API keys are encrypted (AES-GCM) with a key kept in the phone's Android Keystore, which never leaves it, and stored in a file of their own, apart from the settings. They are never shown again once saved, and never logged. Neither they nor the settings are part of Android's backup or of a transfer to a new phone. Each key is sent only to its own service, with each request to it.

If a saved key can no longer be read, as after the phone's Keystore is reset, its row says *Saved, but it can no longer be read*, and you enter it again. On Android 11 and earlier, while any key is saved, entering one then fails, and *Reset saved keys*, shown above the settings, is the way out. If the phone's key store cannot save a key, or keeps failing, the same *Reset saved keys* removes every key, so you can enter them afresh. If the phone's storage fails instead, the key dialog says so, and you try again.

## About

- ***Credits***: *The data sources, the font and the icons, with their licences.* Sleevesight is free software, under the GNU General Public License, version 3. The credits list each data source with what the app takes from it and its licence (MusicBrainz under CC0, its tags under CC BY-NC-SA 3.0, Wikipedia under CC BY-SA 4.0, Wikidata under CC0, Last.fm and Exa under their terms), the app's font, Roboto, under the SIL Open Font License 1.1, and its icons, Material Symbols, under the Apache License 2.0.
- ***How to use Sleevesight***: opens the [usage page]({{ '/docs/tutorials/usage/' | relative_url }}) in your browser.
- ***Every setting explained***: opens this page in your browser.
- ***Privacy policy***: opens the [privacy policy]({{ '/privacy.html' | relative_url }}) in your browser. Its row shows the address, to type in where no browser opens.

## Developer

- ***Diagnostics*** (**default: off**). *Shows the way to the diagnostics screen: what the pipeline did, and the track changes recorded.* Off by default: a listener has no use for it. While it is on, *Open diagnostics* appears under it.

The diagnostics screen shows what Sleevesight did in its last few hundred steps since it started (each source's outcome and time, cache hits, AI calls, the citation check's counts), with the caches' sizes; the track changes recorded, which skip detection learns from; and what skip detection has learned, per player, with *Reset*, which makes it forget until it learns again and keeps the track changes. The steps are kept in memory only, and are gone when the app's process ends. *Export* writes all of it, every track change included, to a file you choose, which may be a cloud folder; that copy is yours, outside the app. Nothing else of it leaves the phone.

## What stays on the phone

Everything the app keeps is on the phone, and none of it is part of Android's backup or a transfer to a new phone. In short, with how long each is kept and how to clear it:

| What | Kept for | Cleared by |
|---|---|---|
| The history: the tracks played, with their length, and the track changes | *Keep history for* (90 days by default) | *Clear history* |
| What skip detection has learned, per player | Until reset | *Reset* on the diagnostics screen; *Clear history* |
| What each source found for a track | *How long each source's findings are kept* | Expiry, at the next start |
| What the sources said about each artist | Only with *Keep what sources said about each artist* on; as long as its sources' findings, an artist's MusicBrainz identity 90 days | Turning that switch off |
| The insights written | Until the caches' size limit (a fixed 50 MB) pushes out the least recently used | Android's *Clear storage* |
| The diagnostics' steps | The app's current run, in memory only | Ending the app |
| Your settings and API keys | Until you change or remove them | *Remove all API keys*; Android's *Clear storage* |

Android's *Clear storage* for Sleevesight, or uninstalling it, deletes everything it kept.
