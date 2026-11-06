---
# How it performs (#626), at `/docs/performance/`: what Sleevesight costs the phone (battery, data,
# CPU, memory, start-up, size), measured on one phone with the Play build. Every figure is from
# `tools/perf/` (snapshots before and after a session, read over wireless debugging), with its
# settings, so it can be taken again and compared. Linked from the docs index, the costs
# page and the README. A change that makes the app do more in the background, on screen or per
# track (a new source, an animation, a poll) changes these figures: take them again then.
title: How it performs
permalink: /docs/performance/
docs_version: 0.3.0
nav:
  - id: in-short
    label: In short
  - id: battery
    label: Battery
  - id: data
    label: Data
  - id: processor-and-memory
    label: Processor
  - id: start-up-and-size
    label: Size
provenance: [ai-wrote, human-reviewed]
---

# How it performs

What Sleevesight costs your phone while you listen: battery, data, processor time, memory, start-up and storage. Each figure was measured, on one phone, and says how. That phone is a fast, recent one, so the figures are an example, not a promise: on a mid-range or older phone, the processor time and the start-up will be longer, and the battery drain larger. What it costs in money is on [What it costs you]({{ '/docs/costs/' | relative_url }}).

**Measured on** a Samsung Galaxy S25 Ultra (SM-S938B), Android 17, with Sleevesight 0.3.0 as installed from Google Play. The phone ran on its battery, connected over Wi-Fi; the readings were taken over wireless debugging, so the cable's charge did not hide any drain.

**Settings:** the defaults, with Anthropic as the AI service (Claude Haiku 5.5, low reasoning effort), Exa web search off, *Look up in the background* off, unless a row says otherwise. Music came from the phone's usual player.

## In short

- **Screen off**, while you listen: almost nothing. Over an hour, Android put 0.1 mAh of battery on Sleevesight, a five-hundredth of a percent of the phone's 5,000 mAh, and it used under 7 seconds of processor time and no data to speak of.
- **Nothing playing**: nothing at all. Overnight, for seven and a half hours, it made no network call, used 8.5 seconds of processor time, and the system put no battery on it.
- **On screen**: the screen is most of the cost, about 2 to 3 % of the battery an hour. The spinning logo adds a constant load on the processor while music plays; [*Spin the logo while music plays*]({{ '/docs/tutorials/settings/#appearance' | relative_url }}) off removes it.
- **Data**: under 100 KB a track, in and out together under 120 KB, insights and web search included. At about 17 tracks an hour, that is about 2 MB an hour.
- **Memory**: 80 to 120 MB with the screen off, up to about 170 MB on screen while insights are written.
- **Start-up**: it opens in about 70 milliseconds.
- **Size**: about 13 MB installed, and it did not grow over a day's listening.

## Battery

The battery figure for Sleevesight is Android's own estimate of the share used by the app (`dumpsys batterystats`), in milliampere-hours (mAh); it includes the screen while the app is in front. The phone's drain is the whole phone, every other app and the wireless debugging included, so it is an upper bound, not Sleevesight's share.

| Session | Length | Sleevesight's battery | The whole phone |
|---|---|---|---|
| Screen off, listening | 63 min | 0.1 mAh | about 3 % an hour |
| Screen off, listening, *Look up in the background* on | 98 min | 0.3 mAh | about 3 % an hour |
| Nothing playing, screen off, overnight | 451 min | 0.0 mAh | 75 % to 52 % |
| On screen, *Now playing*, logo spinning | 20 min | 44.6 mAh, of which the screen 38.2 and the processor 5.6 | about 9 % an hour |
| On screen, *Now playing*, logo still | 6 min | 9.0 mAh, of which the processor 0.0 | |
| On screen, logo still, 9 tracks, insights off | 10 min | 18.6 mAh | |
| On screen, logo still, 10 tracks, insights on | 12 min | 19.2 mAh | |
| On screen, logo still, 10 tracks, insights and *AI web search* on | 11 min | 20.6 mAh | |

On screen, the cost is about 90 to 130 mAh an hour, 2 to 3 % of the battery, nearly all of it the screen's; writing insights and searching the web add little to it. Off screen, Sleevesight waits for the next track and does little else, so on a long drive it is the screen that matters, not the app.

## Data

Bytes sent and received by Sleevesight (`dumpsys netstats`), all over Wi-Fi; on mobile data the amounts are the same. The track runs were 10 new tracks each (9 in the first, which began with a track already playing), skipped on after about a minute, with Sleevesight on screen and the logo still.

| Session | Received | Sent | Per track, received / sent |
|---|---|---|---|
| 9 tracks, insights off | 637 KB | 151 KB | about 71 / 17 KB |
| 10 tracks, insights on | 448 KB | 172 KB | about 45 / 17 KB |
| 10 tracks, insights and *AI web search* on (*Once*) | 896 KB | 267 KB | about 90 / 27 KB |
| Screen off, an hour, listening | 1.7 KB | 2.5 KB | |
| Screen off, 98 min, *Look up in the background* on | 194 KB | 87 KB | |
| Nothing playing, overnight | 0 | 0 | |

What a track needs varies more with the track than with the settings: a track with a large cover, or one the sources know a lot about, brings in more. The run with insights off happened to have more of those than the run with insights on; the AI calls themselves are text, a few kilobytes each. The web search adds the pages it finds: in this run, about 45 KB a track more.

With *Look up in the background* off, Sleevesight starts no lookup while it is off screen; one already under way may finish. The 1.7 KB in that hour were genre definitions fetched ahead off screen, which Sleevesight no longer does since 0.4.0.

## Processor and memory

Processor time is the time the app's process ran, on any core (`ps`); memory is the proportional set size (PSS) from `dumpsys meminfo`, the app's own memory plus its share of what it shares with others.

| Session | Processor time | Memory |
|---|---|---|
| Screen off, listening, an hour | 6.6 s | 86 MB |
| Screen off, listening, *Look up in the background* on | 8.2 s in 98 min | 118 MB |
| Nothing playing, overnight | 8.5 s in 7.5 hours | 84 MB |
| On screen, logo spinning | 18 s a minute, about 30 % of one core | 159 MB |
| On screen, logo still | 0.6 s a minute | 160 MB |
| A track on screen, logo still, insights off | 0.7 s | 98 MB |
| A track on screen, logo still, insights on | 2.4 s | 166 MB while writing, 79 MB after |
| A track on screen, logo still, insights and *AI web search* on | 3.8 s | 151 MB while writing, 167 MB after |

The spinning logo is nearly all of the processor time on screen: a known cost, [#963](https://github.com/wkusnierczyk/sleevesight/issues/963), to be brought down. Until then, turn *Spin the logo while music plays* off under [Appearance]({{ '/docs/tutorials/settings/#appearance' | relative_url }}) to save it.

## Start-up and size

**Start-up.** Sleevesight opens to its first frame in 70 milliseconds, the median of 10 starts (63 to 87 ms; `am start -W`). With notification access granted, Android keeps Sleevesight's process running to pass it the music, and starts it again within half a second if it is stopped, so opening the app never starts it from nothing: these are the starts you get in use.

**Size.** From the phone's app info screen, before the first session (0.3.0) and after a day's listening (0.3.1, to which Google Play had updated it):

| | App | Data | Cache | Total |
|---|---|---|---|---|
| Before | 12.24 MB | 451 KB | 524 KB | 13.22 MB |
| After a day | 12.27 MB | 438 KB | 524 KB | 13.24 MB |

The history and the caches did not grow: what Sleevesight keeps is text, and it drops each kind after the time set under [History]({{ '/docs/tutorials/settings/#history' | relative_url }}).

## Taking the numbers again

The method is kept with the code, in [`tools/perf/`](https://github.com/wkusnierczyk/sleevesight/tree/main/tools/perf): a snapshot script that only reads from the phone, a report that compares two snapshots, and a start-up timer. The sessions are listed there, so the figures can be taken again for a later release, or on your own phone, and compared with these.
