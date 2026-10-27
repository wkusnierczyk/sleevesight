---
# What it costs you (#585), at `/docs/costs/`: who charges for what. Sleevesight charges nothing;
# each service you switch on bills you on your own key. Every price and free tier here is from the
# service's own public pricing page, dated where it was checked; the worked example uses the tokens
# the pipeline CLI measured (`:cli`, `live`, its report's *Tokens billed*). Linked from the product
# page, the docs index, the settings page (*What it costs*, the Exa entry, *Secrets*), the usage page,
# the tester brief and the README. A change to the default models, to what a call or a search
# sends, or to how often one is made changes this page too; so does a price, when it is checked
# again.
title: What it costs you
permalink: /docs/costs/
---

# What it costs you

## Sleevesight charges nothing

Sleevesight is free and open-source software, under the [GNU General Public License, version 3](https://github.com/wkusnierczyk/sleevesight/blob/main/LICENSE). It has no price, no usage charge and no subscription, and it gives no credit towards the services it uses. Each paid service you switch on bills you directly, on your own API key and your own account with it, under its own terms. Sleevesight never sees that bill, and takes no share of it.

What you may pay for is the writing of insights, by the AI service you choose, and the Exa web search, if you give it a key. The other sources cost nothing.

## The services and their prices

Prices as checked on 26 October 2026, on each service's own pricing page, linked in the table. Prices change, and the service's page is authoritative. Prices are in US dollars, before any tax, and *1M tokens* is a million tokens: a token is a few characters of text, about three quarters of a word in English.

### AI services

One service at a time writes the insights ([settings]({{ '/docs/tutorials/settings/#ai-services' | relative_url }})). Each insight written is one call, now and then two (the [usage page]({{ '/docs/tutorials/usage/#writing-an-insight' | relative_url }}) says when). The services bill by the token, in and out, the output including the model's reasoning. The default model is each service's cheapest current one; a model the service still lists for your key may be cheaper, and is named where it is.

| Service | Key | Default model: in / out, per 1M tokens | Cheaper model listed | Free tier | Pricing page |
|---|---|---|---|---|---|
| **Anthropic** | Yours | Claude Haiku 5.5: $0.10 / $0.50 | None | New accounts get a small free credit to try the API, once; after that, credit is bought by card. | [Anthropic's pricing](https://platform.claude.com/docs/en/about-claude/pricing) |
| **OpenAI** | Yours | GPT-6 Luna: $0.10 / $0.50 | GPT-5 nano: $0.05 / $0.40, until it shuts down on 11 December 2026 | None listed on its pricing page. | [OpenAI's pricing](https://developers.openai.com/api/docs/pricing) |
| **Google** (Gemini API) | Yours | Gemini 3.5 Flash-Lite: $0.30 / $2.50 | Gemini 3.1 Flash-Lite: $0.25 / $1.50, deprecated, until it shuts down on 7 May 2027. The 2.5 models cost less, but are open only to projects that used them before. | Free of charge, within rate limits set per project and shown in Google AI Studio; no billing needed until you move to the paid tier. On the free tier, outside the EEA, Switzerland and the UK, Google uses what is sent to improve its products; for apps made available to users in those three, its [terms](https://ai.google.dev/gemini-api/terms) allow only the paid tier. | [Google's pricing](https://ai.google.dev/gemini-api/docs/pricing) |

Anthropic's prices are for prompts of up to 100,000 tokens; an insight's prompt is far below that.

### Sources

| Source | Key | What is billed | Free tier | Requests Sleevesight makes | Pricing or terms |
|---|---|---|---|---|---|
| **Exa web search** | Yours | $4 per 1,000 searches, of up to 10 results, for the search type Sleevesight uses; the highlights it asks for cost nothing extra: Exa's pricing page lists no charge for them, and [Exa 3.0's announcement](https://exa.ai/blog/exa-3) says they are "included at no additional charge". The usage page of the [Exa dashboard](https://dashboard.exa.ai) shows what each search actually cost. | $10 of credit a month, enough for up to 2,500 searches: given at sign-up and reset to $10 on the first of each month; no card needed. | One search for each new track looked up, kept on the phone for a day (*Web search*'s lifetime, under [History]({{ '/docs/tutorials/settings/#history' | relative_url }})), so a track looked up again that day costs no search. | [Exa's pricing](https://exa.ai/pricing), [Exa 3.0](https://exa.ai/blog/exa-3) |
| **Last.fm** | Yours | Nothing. | Its API is licensed without charge, for non-commercial use. | For each track and artist looked up; rate-limited, not billed. | [Last.fm's API terms](https://www.last.fm/api/tos) |
| **MusicBrainz** | None | Nothing. | Free. | One request a second at most, as its rules ask. | [MusicBrainz API](https://musicbrainz.org/doc/MusicBrainz_API) |
| **Wikipedia and Wikidata** | None | Nothing. | Free. | For each track and artist looked up. | [Wikimedia's terms of use](https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use) |

## What a track costs, worked through

**The insight.** With the default settings (*On track change*, the default model, low reasoning effort), an insight took on average 1,051 tokens in and 157 out, measured with the pipeline's command-line tool over 90 insights (four albums and playlists, twice; 25 October 2026, Claude Haiku 5.5, web search off). At Claude Haiku 5.5's prices, that is about $0.0002 an insight, two hundredths of a cent:

- 1,051 tokens in at $0.10 per million: $0.000105;
- 157 tokens out at $0.50 per million: $0.000079.

Web search, on by default once Exa has a key, adds what it found to the prompt: at most ten results of up to 300 characters each, so at most about a thousand tokens more, $0.0001 more on Claude Haiku 5.5. The other services count tokens their own way, and their models reason at different lengths, so for them the same prices give a rough guide only: about $0.0002 an insight on GPT-6 Luna, and about $0.0007 on Gemini 3.5 Flash-Lite, whose prices are higher.

**The web search.** One Exa search for each new track, $0.004. With web search on, the search is most of what a new track costs.

**An hour's listening**, with Sleevesight on screen and the defaults, of tracks of about three and a half minutes: 17 new tracks.

| | Each | 17 tracks |
|---|---|---|
| Insights, Claude Haiku 5.5 | about $0.0002 | about $0.003 |
| Exa searches | $0.004 | $0.068 |
| **Together** | | **about $0.07** |

Tracks played before cost less: a track looked up again within the day costs no search, and a track played again with the same evidence and the same AI settings shows the insight written then, with no call ([*What costs nothing*]({{ '/docs/tutorials/usage/#what-costs-nothing' | relative_url }})).

**How far the free tiers go.**

- **Exa:** 2,500 searches a month are 2,500 new tracks, about 150 hours of the listening above, every month.
- **Anthropic:** the free credit is a small one-off amount, which the pricing page does not state. At about $0.0002 an insight, each dollar of credit writes about 5,000 insights.
- **OpenAI:** no free tier; each dollar writes about 5,000 insights at GPT-6 Luna's prices.
- **Google:** free as long as you stay within the free tier's daily limit for the model, which Google AI Studio shows for your project; mind its terms on data use, above.

## How to spend less

- **Write fewer insights.** *Reactive*'s *On track skip* writes only the tracks you skip to, and *Now* only those you tap *Write insight* for ([Insights]({{ '/docs/tutorials/settings/#insights' | relative_url }})). Even under *On track change*, nothing is written until your first tap after the app starts, and *Saver* pauses automatic writing after a few track changes away ([*Automatic writing*]({{ '/docs/tutorials/usage/#automatic-writing' | relative_url }})). *Stop* ends the writing, but once the request has reached the AI service, it may still charge for it.
- **Play tracks again.** A track played again costs less, often nothing, as above.
- **A cheaper model, or less reasoning.** The model and the reasoning effort are under [AI services]({{ '/docs/tutorials/settings/#ai-services' | relative_url }}); the defaults are each service's cheapest current model and a low effort, and an older model the service still lists may cost less, as the table shows.
- **Web search off.** Exa's searches are made for every track looked up, whatever *Reactive* says, so turning *Exa web search* off under [Sources]({{ '/docs/tutorials/settings/#sources' | relative_url }}) saves the most; the other sources still find what they can.
