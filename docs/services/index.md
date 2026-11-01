---
# External services setup guide, at `/docs/services/`: a focused, step-by-step guide on which
# external services need an API key and why (and what happens when they are off), where to get each
# key, how to enter keys on the phone (manual, Type on a computer, QR code), how to enable the
# services, and how to check that they work. Written from the settings screen's code
# (`app/.../ui/settings/`), `strings.xml`, `strings_settings.xml` and `strings_home.xml`.
title: Setting up external services
permalink: /docs/services/
nav:
  - id: 1-which-services-need-a-key-and-why
    label: Need a key
  - id: 2-how-to-get-the-keys
    label: Get keys
  - id: 3-how-to-enter-the-keys
    label: Enter keys
  - id: 4-how-to-enable-the-services
    label: Enable
  - id: 5-how-to-check-that-they-work
    label: Check
---

# Setting up external services

Sleevesight runs no server and includes no API keys of its own. You choose which external services to use, save your own keys for the ones that need them, and switch them on.

For every setting in detail, see [Settings]({{ '/docs/tutorials/settings/' | relative_url }}); for prices and free tiers, see [What it costs you]({{ '/docs/costs/' | relative_url }}).

## 1. Which services need a key, and why

Services fall into two groups in *Settings*: **Sources** (which gather facts about the playing track) and **AI services** (which write the short insight from those facts).

| Service | Where in Settings | Key needed? | What it does | What if it is not enabled? |
|---|---|---|---|---|
| **MusicBrainz** | *Sources* | No (on by default) | Identifies the recording, artists, release dates, credits and tags. | Other sources match by name alone, without MusicBrainz's identifier check; release years and MusicBrainz genre tags are missing. |
| **Wikipedia and Wikidata** | *Sources* | No (on by default) | Articles on songs, albums and artists, facts from Wikidata, and genre definitions. | No Wikipedia or Wikidata lines in the evidence, and no Wikipedia definition in a genre's panel. |
| **Last.fm** | *Sources* | **Yes** | Listeners' genre tags, artist biographies, listener counts and similar artists. | Stays off. The app works without it, using MusicBrainz and Wikipedia for facts and MusicBrainz alone for genre labels. |
| **Exa web search** | *Sources* | **Yes** | Searches the web for articles and quotes about the track. | Stays off. Insights rely on MusicBrainz, Wikipedia, Wikidata and Last.fm, which often suffices for well-known tracks; newer or less-documented tracks may have little or nothing to write from. |
| **Anthropic**, **OpenAI**, or **Google** (Gemini) | *AI services* | **Yes** (one service at a time) | Writes the track's insight (and *Why is this …?* in a genre's panel) from the sources' evidence, and answers *Search with AI* when a source has nothing. | The app still looks the track up and shows the genre labels and all evidence under *Sources*, but writes no insight (*Key insights* says *Choose an AI service and save its key in Settings to have insights written.*). |

Every request goes straight from your phone to the services you switch on, over HTTPS, with each API key sent only to its own service. A service that is off receives nothing.

## 2. How to get the keys

You only need keys for the services you want to use: **one AI service** (to have insights written), and optionally **Last.fm** and **Exa**.

| Service | Where to get the key | Cost & documentation |
|---|---|---|
| **Anthropic** | [Claude Console → API keys](https://platform.claude.com/settings/keys) | Paid per token (a small one-off free credit for new accounts); about $0.0002 an insight on the default model. [Anthropic docs](https://platform.claude.com/docs) · [Pricing](https://platform.claude.com/docs/en/about-claude/pricing) |
| **OpenAI** | [OpenAI Platform → API keys](https://platform.openai.com/api-keys) | Paid per token; about $0.0002 an insight on the default model. [OpenAI docs](https://platform.openai.com/docs) · [Pricing](https://developers.openai.com/api/docs/pricing) |
| **Google** (Gemini) | [Google AI Studio → Get API key](https://aistudio.google.com/apikey) | Free tier within daily limits (outside the EEA, Switzerland and the UK, Google uses free-tier requests to improve its products; a Google Cloud project with billing on uses the paid tier). [Gemini API docs](https://ai.google.dev/gemini-api/docs) · [Pricing](https://ai.google.dev/gemini-api/docs/pricing) |
| **Last.fm** | [Create a Last.fm API account](https://www.last.fm/api/account/create) (copy the *API Key*, a 32-character hex string; the *Shared secret* is not needed) | Free for non-commercial use. [Last.fm API docs](https://www.last.fm/api) · [Terms](https://www.last.fm/api/tos) |
| **Exa** | [Exa dashboard → API keys](https://dashboard.exa.ai/api-keys) | $10 of free credit every month (up to 2,500 searches, no card needed); $0.004 per search after that. [Exa docs](https://docs.exa.ai/) · [Pricing](https://exa.ai/pricing) |

If you are in the closed test on Google Play, you can also use the Anthropic key we provide ([Testing Sleevesight]({{ '/docs/tester/alpha/#the-key-we-give-you' | relative_url }})).

## 3. How to enter the keys

Open **Settings** (the cogwheel at the top right of the home screen) and tap the **Secrets** chip to open its panel. Keys are stored encrypted on the phone, never backed up, and never shown again once saved.

### Entering one key (`Anthropic API key`, `Last.fm API key`, …)

Tap the service's row under *Secrets* (or tap *Set your … API key here.* directly under the service in *AI services* or *Sources*). The dialog offers three ways to fill the *Key* field, then tap **Save**:

- **Manual (paste, type, or password manager):** paste or type the key into the *Key* field, or pick it from your password manager's autofill prompt. While typing or pasting, the eye icon (*Show key*) lets you check it before saving.
- **Type on a computer (keyboard icon in the field):** if the key is on your computer, open [sleevesight.app/keyboard.html]({{ '/keyboard.html' | relative_url }}) in Chrome or Edge on the computer (with Bluetooth on). Tap the keyboard icon in the phone's *Key* field, scan the QR code on the computer screen, then click *Connect* on the computer and pick the phone. What you type or paste on the computer is sent encrypted over Bluetooth straight into the field.
- **QR code of a single key (QR icon in the field):** open [sleevesight.app/api-keys]({{ '/api-keys.html' | relative_url }}) on a computer, uncheck *Seal the code with the phone's words*, and paste that single key into its field. Tap the QR icon in the phone's *Key* field and scan the code. (A key read from a QR code is kept hidden and cannot be revealed with the eye icon.)

### Entering several keys at once (*Load keys*)

To load any combination of AI, Last.fm and Exa keys in one scan:

1. On the phone, under *Secrets*, tap **Load keys**. The screen shows five random words.
2. On a computer, open [sleevesight.app/api-keys]({{ '/api-keys.html' | relative_url }}) (the page makes no network requests and sends your keys nowhere). Type the five words into the first box (the first three letters of each word are enough), then paste your keys into their fields or drop a `NAME=value` keys file onto the page. The page draws one QR code sealed with those words.
3. On the phone, tap **Next** and scan the QR code. The phone unseals it, lists the keys it found by their last four characters, and saves them when you tap **Save**.

## 4. How to enable the services

**Saving a key does not switch its service on.** After saving your keys, open the service sections in *Settings* and flip their switches on:

- **AI service:** open **AI services** and switch on **Anthropic**, **OpenAI** or **Google**. At most one AI service can be on at a time; switching one on switches another off. (Here you can also change the service's *model* and *reasoning effort*; each defaults to its cheapest current model and *Low* effort.)
- **Sources:** open **Sources** and switch on **Last.fm** and/or **Exa web search**.
- **Genre labels from Last.fm:** under **Genres**, *Last.fm* is on by default, and starts providing genre tags as soon as *Last.fm* is switched on under *Sources*.

## 5. How to check that they work

Play a song in a music player you follow (Spotify is followed by default; enable any other under *Settings → Players*) and return to Sleevesight's home screen:

- **Check the sources (*Last.fm* and *Exa*):** tap the **Sources** chip at the bottom of the home screen to unfold the sources panel.
  - The table at the top lists every enabled source: `MB` (*MusicBrainz*), `W` (*Wikipedia*), `L` (*Last.fm*) and `S` (*Web search*, for Exa).
  - Under each source's name, its status shows *Looking…* and then what it found (such as *Found the track and the artist*, *Found the artist*, or *Nothing found*). When a source contributed lines to the evidence below, its letter chip on the left is filled (`L`, `S`).
  - If a key was rejected or a source could not be reached, a note right under the table says so (for example, *Last.fm failed.* or *Web search could not be reached.*).
- **Check the AI service:** look at **Key insights** on the home screen.
  - Once the sources finish (*Gathering evidence…*), tap **Write insight** (after your first tap, automatic modes write on their own on the next track changes).
  - The card shows *Writing…* and streams the insight with citation chips (`MB1`, `W1`, `L1`, `S1`) at the end of each factual sentence.
  - If there is a problem with your AI key, account credit, or model availability, *Key insights* says *Writing failed:* followed by the exact reason (for example, *the AI service did not accept the key*, *the AI service account needs credit, or has reached its spending limit*, or *The currently selected … model … is not available*).
- **Detailed timings and status (optional):** in *Settings → Developer* (shown in test builds, or enabled by tapping *Settings → About → Version* seven times), turn on **Diagnostics** and tap **Open diagnostics** to see each source lookup and AI request with its exact outcome and duration.
