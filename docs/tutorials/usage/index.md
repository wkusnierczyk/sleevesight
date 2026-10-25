---
# The usage page (#415), at `/docs/tutorials/usage/`: each part of the app as it works now, from the
# home screen to what happens offline. Written from the app's code on `main` (`app/src/main`: the
# home screen, `ui/home/`; the pipeline's decisions, `pipeline/PipelineCoordinator.kt`; the session
# layer, `session/`; the logo intro, `MainActivity.kt` and `ui/logo/`), its strings
# (`res/values/strings.xml`, `strings_home.xml`), and the README's *Status*. A change to what the home
# screen shows, when an insight is written or what a tap costs changes it too. Settings are
# explained on the settings page, and only linked from here.
title: Using Sleevesight
permalink: /docs/tutorials/usage/
---

# Using Sleevesight

This page goes through each part of the app as it works now: the home screen from top to bottom, writing an insight and what it costs, the history, the players it can follow, the logo intro, and what the app does when something is missing or fails. Each setting named here is explained on the [settings page]({{ '/docs/tutorials/settings/' | relative_url }}); the [journey]({{ '/docs/journey/' | relative_url }}) goes from installing the app to using it day to day.

Buttons and settings are named as the app names them, in italics.

## Before the home screen

The first time it is opened, the app shows its first-run screen, which says what it reads and what leaves the phone, and leads to Android's notification access. Nothing is read or sent before you continue from it. The [journey]({{ '/docs/journey/' | relative_url }}) covers that screen step by step.

If notification access is turned off later, the home screen says *Sleevesight needs notification access to see what is playing.*, with *Turn on notification access*, which leads back to the first-run screen.

## The logo intro

When you open Sleevesight from the launcher while it is not open, it plays one of the product page's logo animations, picked at random, after Android's own splash screen. The animation ends on the logo, which comes apart and flies onto the home screen's header, where it sits on one line: "Sleevesight" and the record. It lasts a few seconds.

- A tap, Back or any key ends it at once. Volume and media keys still do their own work.
- It does not play when you return to the app from the background, on a rotation, or when Android restores a screen it had closed. What counts is whether the app was open, not whether it was running in the background for the notification listener.
- It never plays while the phone's *Remove animations* is on, while TalkBack is on, or with [*Logo intro at start*]({{ '/docs/tutorials/settings/' | relative_url }}) off.

## The home screen

<!-- screenshot: the home screen with a track playing, header to genre labels, in the cover's colours, from the app version it is taken from -->

### The header

The header stays at the top while the rest of the screen scrolls under it; a thin line shows under it once the screen has scrolled.

- **The logo**, at the left: a tap scrolls the home screen back to the top. A screen reader calls it "Sleevesight, scroll to top".
- **The cogwheel**, at the right, opens the settings.

At the bottom of the home screen, under a thin line, *What leaves the device?* opens the first-run screen again, to reread it. *What leaves the device*, under *About* in the settings, opens it too.

### The playing track

The track is shown on a card in colours taken from its cover: the app picks the cover's most suitable colour and builds the card's colour scheme from it, light or dark as the phone is. Without a cover, the app's own colours are used. While no player you follow has a track, the screen says *Nothing playing in a player you follow yet.*

From top to bottom, the card shows:

- **The player and its state**, such as "Spotify · Playing", or *Paused*, *Stopped*, *Buffering*. A tap opens the player's own screen; a player that has none says so.
- **The cover**, with the playback position under it when the player reports the track's length. Without a cover, a music note stands in.
- **The title**, large and bold, cleaned of what the player appends: a tag such as "2006 Remaster" is set aside, and featured artists ("feat. Van Morrison") join the artists. When the title names the work it is from or a variant ("From …", "acoustic"), that shows under it.
- **The artists**, each credited one, featured artists included.
- **The album**, in italics, followed by the year of first release when MusicBrainz found one: *Rubber Soul*, 1965. The year is the one the recording first came out, not the year of the release being played. When the player gives no album, the year stands alone, or, with no year either, the line reads *Album not reported*.

A new track shows at once, with its sources and insight to come.

### Genre labels

Under the track come its genre labels: the tags Last.fm and MusicBrainz give the track, its artists and its album, kept only when they name a genre in a fixed list. With both on, Last.fm's labels come first on each level and MusicBrainz's fill the rest, five at most a level; a label both give is shown once.

Folded, *Genres ▸* shows the labels of the most specific level found: the track's, else the album's, else the artists'. A tap unfolds it, *Genres ▾*, to a table with a row for each level that has labels: *Track*, one *Artist* row per artist, its name before its labels, and *Album*. When only one level has labels, they show on their own, with no fold. A screen reader says whether the fold is folded or unfolded, and reads each row of the table as one.

A tap on a label shows where it came from: *… comes from the track's tags on:* (or the album's, or the artist's, named when the track has several), then each source, linked to its page there, and for MusicBrainz a link to its licence. A second tap closes it. Which sources give labels is set under [*Genres*]({{ '/docs/tutorials/settings/' | relative_url }}), and *Show genres* there turns them off altogether.

When there are none, it says *None found.*, or *Every genre source is off in Settings.*; with *Show genres* off, nothing of the genres shows.

### The insight

*Key insights* holds the insight: a few sentences written by the AI service you chose, from what the sources found. The insight is plain text with citation chips in it.

<!-- screenshot: the insight with its citation chips and the grounded-sentences line, from the app version it is taken from -->

- **Citation chips.** Each sentence cites the evidence lines it rests on, by their ids, such as `MB1` or `W3`. A tap on a chip unfolds *Sources* as far as that line, scrolls to it and flashes it. A screen reader reads each chip by what it points at ("Source W3"), and offers the lines as actions of the paragraph.
- **The model's own knowledge.** When [*AI service knowledge*]({{ '/docs/tutorials/settings/' | relative_url }}) is on, a claim from it is cited `M`, in a colour of its own, and a screen reader calls it "From the model's own knowledge, not verified". It is off by default.
- **Flagged sentences.** Before the insight is kept, the app checks every citation in code. A sentence that cites nothing is underlined in the error colour (a screen reader says "not cited"), and a citation of a line that does not exist shows in the error colour.
- **The grounded-sentences line**, under the insight, gives the check's counts: *4 of 5 sentences grounded*, then, where there are any, how many come from the model's own knowledge, how many are uncited, and how many cite missing evidence. *kept from before* says the insight was not written now but shown from what was written earlier.
- **Report a problem**, under the insight, is for an insight that is wrong or offensive. It asks what is wrong (*Offensive or inappropriate*, *Wrong* or *Other*), takes an optional note, and shows the whole report: the reason and the note; the track as the player reported it, with its MusicBrainz ids; the insight and the evidence lines it was written from; and the prompt version, AI service, model and effort it was written with. Never your keys, the history or the settings. *Open in mail app* hands it to your mail app, addressed to support@sleevesight.app, and it reaches the developer only if you send it from there; until then your mail app handles it as any message you write, which may keep a draft in your mail account. *Copy report* copies it instead, for any other way you write email; with no mail app on the phone, the dialog says so. The report stays of the insight it was opened on, even if the track changes meanwhile. The [privacy policy]({{ '/privacy.html' | relative_url }}) says what the developer does with it.

While the insight is being written, its text appears as it arrives, with its citation chips; the underlines wait for the check, which runs once the reply is complete.

When little is documented about a track, the insight is short rather than filled out.

### Sources

*Sources*, at the bottom of the card, shows what the insight was written from.

<!-- screenshot: Sources unfolded, with chips, evidence lines and a rejected match, from the app version it is taken from -->

**The chips.** One per source, each with its tag and what it found:

| Chip says | Meaning |
|---|---|
| *looking* | The source has not answered yet |
| *the song* | It found the song |
| *the artist only* | It found the artist, not the song |
| *the original song* | It found the song this one covers |
| *release* | The `P` chip: the release the player reports, the album as a line of evidence |
| *nothing found* | It answered, with nothing on this track |
| *rejected* | What it found failed the identity check (shown in the error colour) |
| *unreachable* | It could not be reached |
| *failed* | The lookup went wrong (shown in the error colour) |
| *off* | You switched the source off under *Sources* |
| *needs your key, in Settings* | Last.fm or web search is on, but its key is not saved |
| *allowed* | The `M` chip: the model's own knowledge may be cited |
| *(kept)*, after any of these | The answer was kept on the phone from an earlier lookup |

The tags are `MB` MusicBrainz, `W` Wikipedia (its chip covers Wikidata's `WD` lines too), `L` Last.fm, `G` web search, `P` the player and `M` the model's own knowledge.

A chip of a source with lines shows or hides those lines. That only changes what you see: what the AI service may use is set under [*Sources*]({{ '/docs/tutorials/settings/' | relative_url }}).

**The list.** Under the chips come the evidence lines, each with its id, its text, what it is about (*song*, *artist*, *original song*, *release*, *web*) and a link to the page it was found on, which opens in the browser. After them, under their own headings, come *Found, but not verified, so not cited*, lines from matches that could not be checked against the track, which are shown but never given to the insight, and *Rejected matches*, each with its source and the reason it was rejected.

**Folded and unfolded.** The list starts folded: the heading, the chips, and *show ▸* with how many entries it holds ("12 entries, folded"). It unfolds 8 entries at a time:

- *show ▸* unfolds the first 8;
- *more…* the next 8, and *show all ▸▸* the rest, while entries remain;
- *… less* goes back to the first 8, once past them;
- *hide ▾*, above the list and under it, folds it again.

While the list is folded, a chip unfolds it to its source's first line instead of hiding the lines. A citation tapped in the insight unfolds the list as far as its line. When the chips hide every line, the count says so, and *show ▸* shows them again.

Each new track's list starts folded, so the insight is what you see first. With [*Keep Sources as you left them*]({{ '/docs/tutorials/settings/' | relative_url }}) on, it stays as you left it on the track before. Either way, the fold is kept through a rotation and when Android restores the screen, not when the app starts afresh.

## Writing an insight

Each insight written is a call to your AI service (now and then two, below), on your own API key, and the service bills you for it. A call that failed or was stopped is never made again without a tap from you, and the buttons below say when a tap is a new call. One case makes a second call on its own: to keep the prompt short, the first call may leave out lines about the artist, and if the service then answers that it has nothing to write from, the app asks once more at once, with every line, so that you get an insight rather than none. Each of the two calls is billed.

```mermaid
flowchart TD
  A[Track starts] --> B[Gathering evidence…]
  B --> C{Written automatically?}
  C -- yes --> D[Writing…, with Stop]
  C -- no --> E[Write insight]
  E -- tap --> D
  D -- reply checked --> F[The insight]
  D -- Stop, or a failure --> G[Stopped or failed, with Write insight]
  G -- tap --> D
  F -- evidence or service changed --> H[The insight, with Rewrite]
  H -- tap --> D
```

### *Write insight*

*Write insight* writes the insight for the track on screen, from the sources shown under it: one call, or two in the case above. It is offered with *Tap Write insight to have it written from the sources below.* when the insight is not written on its own (see [Automatic writing](#automatic-writing)). Until the sources have answered, the insight says *Gathering evidence…*, and Write is not offered yet.

### *Stop*

While the insight is being written, the card says *Writing…* and offers *Stop*. Stop ends the call: the card says *Stopped before the reply ended, so nothing was kept.* Nothing of the partial reply is kept, though the service may still bill what it had written before the stop, by its own terms.

### Write again

After a call that was stopped or failed, the card offers *Write insight* again, with *Write insight tries again, as a new call to your AI service.* Each try is a new call on your key, and it is never made on its own.

### *Rewrite*

An insight already written stays when the track is looked up again and the insight would now be written differently: after you save, replace or remove a key, switch a web search engine, or choose another AI service, the sources may find other evidence, or another service would write it. The card then keeps the insight written earlier, with *Written earlier, from the evidence under Sources. Rewrite writes it again from what the sources find now, with your current AI service.* and *Rewrite*. Under it, *Sources* shows the lines that insight cites, found when it was written.

Nothing is written again unless you tap *Rewrite*, which is one call, or two in the case above. If the rewrite fails, the card says *Rewriting failed:* and the reason, and keeps the insight written earlier; if you stop it, *Rewriting stopped. This is the insight written earlier.* *Rewrite* is offered again either way.

### What costs nothing

- **A track played again.** When a track you played before comes back, the sources find the same evidence, and the AI service, model, reasoning effort and *AI service knowledge* are as they were, the insight written then is shown again, marked *kept from before*, with no call. Otherwise it is written afresh, as for a new track. Kept insights are dropped, least recently shown first, once they pass the cache's size limit.
- **Nothing to write from.** When the sources give nothing to write an insight from, the card says *The sources give nothing to write an insight from.*, and no call is made. The same line can also follow a call, when the AI service finds nothing in the evidence to write from. With the model's own knowledge on, the album the player reports is enough for a call, once the player has also reported the artists; the model may then add what it knows, marked unverified.
- **The next track.** The track next in the player's queue is looked up ahead (by default, at MusicBrainz), so its sources come sooner when it plays. No insight is written ahead.

### Automatic writing

By default, [*Reactive*]({{ '/docs/tutorials/settings/' | relative_url }}) writes an insight for every track (*On track change*), so each track change is a call. *On skip* writes only the tracks you skip to in your player; a track that ended on its own gets its sources and *Write insight*. Skips are told from natural ends by the time left on the track, and, for Spotify, by the playback states just before the change, with the player's [*Crossfade*]({{ '/docs/tutorials/settings/' | relative_url }}) detected by default. *Manual: only when I tap Write* offers *Write insight* on every track.

Automatic writing needs a first tap:

- **After the app starts afresh**, nothing is written until your first tap on *Write insight*, in every mode. That tap lasts as long as the app's process, which the notification listener usually keeps alive after you leave the app, often for days: coming back to the app, from the player, the launcher or the screen turned on again, keeps it, and writing goes on without a new tap. A restart of the phone, an update of the app, or the system ending the process (on some phones, a swipe from Recents) starts afresh.
- **The pause.** So that one tap does not keep paying for days, automatic writing pauses once the app has not been on screen for more track changes than [*Saver*]({{ '/docs/tutorials/settings/' | relative_url }}) names (5 by default). The card then offers *Write insight* with *Automatic writing paused: tracks changed while Sleevesight was not on screen. Tap Write insight to write this one and resume.* A tap writes the track and resumes. Each time you open the app the count starts again.

<!-- screenshot: the insight card with the automatic-writing-paused line and Write insight, from the app version it is taken from -->

### While the app is not on screen

While Sleevesight is not on screen, it looks nothing up and sends nothing, the covers included, unless you turn on [*Look up in the background*]({{ '/docs/tutorials/settings/' | relative_url }}). When you come back, the track playing then is looked up. With the setting on, lookups go on while you are away, and so does automatic writing: those are paid calls made while nobody is looking, which the pause above bounds. The app counts as on screen in split screen, or with a dialog over it.

## The history

Sleevesight keeps, on the phone only, the tracks it has seen play and the track changes between them, from which skip detection learns. There is no history screen in the app yet: the history is not shown as a list.

What there is:

- **The settings' *History* group**: [*Keep history for*]({{ '/docs/tutorials/settings/' | relative_url }}) (90 days by default), [*New session idle trigger*]({{ '/docs/tutorials/settings/' | relative_url }}), which decides where one listening session ends, and *Clear history*, which deletes the tracks played and the track changes together, once you confirm.
- **The diagnostics screen**, under the developer settings, which lists the track changes recorded, with what skip detection learns from them, and can export them to a file you choose.

The history is left out of Android's backup, and the app never sends it anywhere; only an export you make from the diagnostics screen takes a copy of the track changes off the app.

## Players

**Followed.** Spotify is followed by default. Any player whose media session reports music, a track with a title and an artist, can be followed: once it has played music while Sleevesight has notification access, it appears by its name, switched off, under [*Players*]({{ '/docs/tutorials/settings/' | relative_url }}), and switching it on follows it at once. Following Spotify needs no Spotify account in Sleevesight and no developer app, whichever Spotify plan you have: the app reads the phone's media session, not Spotify's Web API.

With several players followed, the home screen shows the one playing; with none playing, the one most recently active.

**Not followed.** A player that is not switched on is only checked for a title and an artist, to be listed; nothing of it is kept or sent, and the home screen does not show it. While only such players play, the home screen says *Nothing playing in a player you follow yet.*

**Not supported.**

- A player that publishes no media session cannot be read at all.
- A player whose session reports no artist, as most video apps do, is never listed.
- Calls from the phone app, and sessions Android marks as voice calls, are never read, whatever the switch. A calling app that shows its call as an ordinary media session is not recognised as a call, and an app you follow that also makes calls may show leftover caller details as a track for a moment after a call.
- A player that publishes no queue gets no looking ahead at its next track.

Playing, pausing and skipping from inside Sleevesight are not built yet; use the player, or tap the player's name to open it.

## When something is missing or fails

| Situation | What you see |
|---|---|
| **Offline** | Sources that cannot be reached show *unreachable*, and *Sources* says *Offline. The sources that could not be reached are asked again when the phone is back online.* Once the phone is back online, the playing track's unreachable sources are asked again, and show *looking* meanwhile; the rest keep what they found. What was kept on the phone from earlier lookups still shows, marked *(kept)*. An insight that cannot be written for want of a connection says *The insight could not be written: no connection.* |
| **No AI service on, or no key for it** | The sources are still looked up and shown. The insight says *Choose an AI service and save its key in Settings to have insights written.*, and no Write is offered. Set it up under [*AI services*]({{ '/docs/tutorials/settings/' | relative_url }}), with its key under *Secrets*. |
| **Insights switched off** | *Insights are off. Turn them on in Settings.* No AI calls at all; the evidence is still shown. |
| **No key for Last.fm or web search** | Its chip says *needs your key, in Settings*, and it is not asked. MusicBrainz and Wikipedia need no key. |
| **A source rejects a match** | Its chip says *rejected*, and the match is listed under *Rejected matches* with the reason. A rejected match is never used. |
| **A match that cannot be verified** | Its lines show under *Found, but not verified, so not cited*, and the insight is not given them. Web search results have nothing to be verified against: those its filter keeps are given to the insight, and may be cited. |
| **A source fails** | Its chip says *failed*; the other sources' lines are used. |
| **The AI service refuses or fails** | *The insight could not be written:* and the reason: the service did not accept the key, the account needs credit, its quota is used up for now, it is busy, it is not offered in your country, it does not know the model, and the like. *Write insight* tries again, as a new call. |
| **The reply stopped early** | The insight shows with *The reply stopped early, so this is incomplete, and it was not kept.* |
| **The app itself fails while writing** | *The insight could not be written: something went wrong.*, with no Write; the next track runs as usual. |

A key that can no longer be read, or one that cannot be saved, is dealt with in the settings; the [settings page]({{ '/docs/tutorials/settings/' | relative_url }}) says how.
