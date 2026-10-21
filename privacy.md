---
# The privacy policy, at `/privacy.html`: the public URL Google Play asks for (#57). Written from
# `design/design.md` (*Privacy and security*) and the app's first-run screen; a change to what leaves
# the phone changes all three together.
layout: default
title: Privacy policy
permalink: /privacy.html
---

# Sleevesight privacy policy

Last updated: 21 October 2026.

This policy covers Sleevesight, the Android app (package `app.sleevesight`), and this website. Sleevesight is open source: the code that does everything described here is on [GitHub](https://github.com/wkusnierczyk/sleevesight). Near the end, it says where Sleevesight stands under the EU's data protection law, [the GDPR](#the-gdpr), and its law on artificial intelligence, [the AI Act](#the-eu-ai-act).

## In short

Sleevesight has no server, no accounts, no telemetry, no crash reporting of its own, no analytics and no ads: nothing you play, and nothing the app sends, reaches its developer. To write about the song you are playing, the app sends the track's names from your phone straight to the sources you switch on and to the AI service you choose, using your own API keys. Google Play's data safety section counts that as data the app collects, since it leaves the phone. Everything else stays on your phone.

## What the app reads on the phone

Sleevesight needs notification access, which is how Android lets an app read what a music player is playing. It notes which players have a media session, to list them in its settings, and follows only the players you switch on there (Spotify by default); from them it reads which player it is; the title, artists, album, length and cover; whether it is playing, and how far into the track; and the queue of tracks up next, with its name. It ignores the notifications themselves, and it never touches the audio.

## What leaves the phone, and to whom

Requests go straight from your phone to each service. None passes through a server of Sleevesight's.

- **To the sources you switch on** (MusicBrainz and Wikidata, Wikipedia, Last.fm, and the Exa web search): the title and artists of the playing track, cleaned of tags such as "2006 Remaster"; Exa also gets the film or show a title says it is from. Last.fm gets the artists' names only, and Wikidata only identifiers (from MusicBrainz or Wikipedia), never a name. The album name does not go to the sources. While *Prepare the next track* is set to *Evidence*, as it is by default, the title and artists of the next track in the player's queue also go to MusicBrainz, if switched on, before that track plays.
- **To the AI service you choose** (Anthropic, OpenAI or Google): the title, artists and album, and what the sources found. This is for the playing track only. Each insight is a request of its own, with no conversation kept between them. It is made with your API key, so the service handles it under its terms for your account.
- **Your API keys** go only to the service each belongs to, with each request to it.
- **To Spotify**, only if you connect a Spotify account, once the app offers that: requests for the playlist or album being played, made with your own token.
- **To the server a cover's web address names**, when a player gives the cover only as a web address: a request for that image. The address itself may identify the album or episode.
- **To a file you choose**, only if you export the diagnostics: with the *Diagnostics* developer setting on, *Export* writes what the app did and every track change it recorded to a file where you choose, which may be a cloud folder. That copy is yours, outside the app.

Every request is made over HTTPS; Android refuses the app any unencrypted connection, so a cover given as an `http://` address is not fetched.

Each of these services also sees your phone's IP address, as every service you connect to does. What each does with what it receives is set by its own policy: [MetaBrainz](https://metabrainz.org/privacy) (MusicBrainz), the [Wikimedia Foundation](https://foundation.wikimedia.org/wiki/Policy:Privacy_policy) (Wikipedia and Wikidata), [Last.fm](https://www.last.fm/legal/privacy), [Exa](https://exa.ai/privacy-policy), [Anthropic](https://www.anthropic.com/legal/privacy), [OpenAI](https://openai.com/policies/privacy-policy/), [Google](https://policies.google.com/privacy) (with the [Gemini API terms](https://ai.google.dev/gemini-api/terms)).

By default, lookups and writing happen only while Sleevesight is on screen. With *Look up in the background* on, they also happen while it is not.

Nothing else leaves the phone.

## What stays on the phone

- The length and cover of each track.
- The history: the tracks Sleevesight has seen play, the record of track changes it uses to tell your skips from natural ends, and what it has learned from them for each player.
- What the sources found, kept for a while so the same track is not looked up again (the lifetimes are in the settings).
- The insights written, your settings, and your API keys. The keys are encrypted with a key kept in the phone's Android Keystore, are never shown again once saved, and are never logged.

None of this is part of Android's backup or of a transfer to a new phone.

## What reaches the developer

Nothing you play, and nothing the app sends: the developer of Sleevesight runs no server, so no data about you or your listening is stored, sold or shared by them.

Google Play, which distributes the app, handles your installation under [Google's privacy policy](https://policies.google.com/privacy). It gives the developer aggregated install statistics and, from phones whose owners allow usage and diagnostics sharing, crash reports that name no one.

## Your choices

- Each source is a switch, and *Write insights* off makes no AI calls at all.
- *Write insights automatically* sets whether an insight is written for every track, only when you skip, or only when you tap Write.
- *Prepare the next track* set to *Off* sends nothing about the next track.
- *Keep history for* sets how long the history is kept, from a week to a year, or forever. *Clear history* deletes it at once.
- A saved key can be removed in the settings, with the delete button on its row, and *Remove all API keys* removes them all.
- Android's *Clear storage* for Sleevesight, or uninstalling it, deletes everything it kept on the phone. Cached findings also expire on their own, on the lifetimes shown in the settings.

What a service keeps of your requests, such as Anthropic for your account, is between you and that service.

## This website

The site is served by GitHub Pages, so GitHub receives your IP address and the pages you ask for, under [GitHub's privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement). The site sets no cookies and has no analytics. The front page keeps nothing in your browser; its copy from before its redesign, under `design/`, keeps one note there, that its intro has played. Design pages with diagrams load the diagram script from jsDelivr, which receives your IP address ([jsDelivr's privacy policy](https://www.jsdelivr.com/terms/privacy-policy-jsdelivr-net)).

## The GDPR

The EU's General Data Protection Regulation ([Regulation (EU) 2016/679](https://eur-lex.europa.eu/eli/reg/2016/679/oj), the GDPR) covers personal data, which here is what the app sends about what you play, with your phone's IP address, and any email you send. This section and the next say what the app does and how its developer reads its position under these laws. They are not legal advice.

### Who is responsible for what

- **Email to the developer.** For email you send to [support@sleevesight.app](mailto:support@sleevesight.app), the developer, Waclaw Kusnierczyk, is the controller, the one who decides what is done with it (Article 4(7)).
- **This website.** GitHub serves it, and logs each visitor's IP address for security, under [its own privacy statement](https://docs.github.com/en/pages/getting-started-with-github-pages/about-github-pages#data-collection). The developer receives no logs and no analytics from it.
- **The app.** No personal data reaches the developer. Each request goes from your phone straight to the service it is for, and that service decides what it does with what it receives, under its own terms and policy (linked [above](#what-leaves-the-phone-and-to-whom)). The AI services handle your requests under your own account: Anthropic's privacy policy, for one, says it does not cover what Anthropic processes on behalf of its API customers, which its [commercial terms](https://www.anthropic.com/legal/commercial-terms) and data processing addendum cover instead.
- **The open question.** The developer designs the app, so the developer decides what a request carries and to which services it can go, though it never receives one. The Court of Justice of the EU has held that someone who influences the processing of personal data for their own purposes can be a controller without ever having access to the data ([*Jehovan todistajat*, C‑25/17](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62017CJ0025), paragraphs 68 and 69), and that an entity that had a mobile app developed can be a controller of what the app processes ([C‑683/21](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62021CJ0683), paragraphs 30 and 31). The developer's reading is that it is not a controller of what the app sends: it has no purpose of its own in those requests, which are made for you, to the services you switch on, with your own keys, and it gets nothing from them. Your own use of the app, to read about the music you play, is in the developer's reading a purely personal activity, which the GDPR does not cover (Article 2(2)(c)); the services that receive your requests remain bound by it (recital 18). Whatever the answer, the app is designed to send as little as it can: each service gets only what it needs (the album only to the AI service, only the artists to Last.fm, only identifiers to Wikidata), each flow can be switched off, and this policy says what goes where.

### On what basis

- **Email:** the developer's legitimate interest in answering you (Article 6(1)(f)). It is read and kept by the email service that handles the address, for as long as the conversation needs, and deleted when you ask, unless the law requires keeping it.
- **This website:** publishing it, on GitHub Pages, rests on the developer's legitimate interest in publishing the app's documentation and this policy (Article 6(1)(f)).
- **The app:** none is needed by the developer, which processes nothing of what the app sends. Each service you use states its own basis for what it receives.

### Your rights

The GDPR gives you the right to see your data, have it corrected or erased, restrict or object to its processing, and take a copy with you (Articles 15 to 21). For what the app keeps, all of it on your phone, they are in your hands:

- **To see it and take a copy:** with the *Diagnostics* developer setting on, *Export* writes what the app did and every track change it recorded to a file you choose.
- **To erase it:** *Clear history* deletes the history and the record of track changes at once; each saved key has a delete button, and *Remove all API keys* removes them all; Android's *Clear storage* for Sleevesight, or uninstalling it, deletes everything it kept. Cached findings expire on their own.
- **To object, or restrict what is sent:** each source is a switch, *Write insights* off makes no AI calls, *Prepare the next track* set to *Off* sends nothing about the next track, and *Look up in the background*, off by default, keeps every request to the times the app is on screen ([Your choices](#your-choices)).
- **For what a service keeps** of your requests, ask that service: its privacy policy, linked above, says how.
- **For what the developer holds,** which is only email you sent, write to [support@sleevesight.app](mailto:support@sleevesight.app) for a copy or its deletion. You get an answer within one month (Article 12(3)).

### Outside the EU

The developer transfers no data: none passes through it. The app's requests go from your phone straight to each service, and the organisations that run them are mostly in the United States (Anthropic, OpenAI, Google, Exa, the MetaBrainz Foundation, the Wikimedia Foundation, and GitHub for this website), with Last.fm in the United Kingdom; a request may be answered by a server anywhere. Some contract with customers in Europe through a company there: Anthropic's commercial terms, for example, name Anthropic Ireland, Limited for customers in the EEA, Switzerland and the UK. Each service's terms and privacy policy, linked above, say how it protects what it receives, and whether it may use it to improve its own products; they differ, and they change.

### Complaints

You can lodge a complaint with a data protection authority in the EU or EEA country where you live or work, or where you think the infringement took place (Article 77); the European Data Protection Board [lists them](https://www.edpb.europa.eu/about-edpb/about-edpb/members_en). You are welcome to write to [support@sleevesight.app](mailto:support@sleevesight.app) first, but you do not have to.

## The EU AI Act

The EU's AI Act ([Regulation (EU) 2024/1689](https://eur-lex.europa.eu/eli/reg/2024/1689/oj), as amended by the Digital Omnibus on AI, [Regulation (EU) 2026/1744](https://eur-lex.europa.eu/eli/reg/2026/1744/oj)) sets duties according to the role each party has.

### Where Sleevesight stands

- **What it is:** an AI system (Article 3(1)) that writes its insights with a general-purpose AI model (Article 3(63)) from the AI service you choose: Claude from Anthropic, GPT from OpenAI, or Gemini from Google. The developer is its provider (Article 3(3)), a *downstream provider* of the model it uses (Article 3(68)); each AI service is the provider of its own model, with the duties the Act sets for such models.
- **Not high-risk, and nothing prohibited:** writing a few sentences about a song is none of the uses the Act prohibits (Article 5) or lists as high-risk (Article 6 and Annex III).
- **You are not a deployer:** a deployer, in the Act, is someone using an AI system under their authority "except where the AI system is used in the course of a personal non-professional activity" (Article 3(4)), and the Act does not apply to the duties of people using one that way (Article 2(10)). Reading about the music you play is such use.
- **Free software is not exempt from transparency:** the Act leaves out AI systems released under free and open-source licences, as Sleevesight is (GPL 3), unless they are prohibited, high-risk, or covered by the transparency duties of Article 50 (Article 2(12)). Sleevesight is covered by Article 50, so those duties apply to it.

### Transparency (Article 50)

**What the app tells you.** Before anything is written, the first-run screen says that the AI service you choose writes the insights, and nothing is written until you choose one, and save its key, in the settings. Each insight is written by the service and model set there at the time; the card does not yet name them. Each sentence of an insight shows the evidence it cites, from the sources the app looked up; a sentence that cites nothing is underlined; and a sentence from the model's own knowledge, which is used only if you switch it on, is marked "From the model's own knowledge, not verified".

**Conversations with an AI (Article 50(1)).** This duty is for AI systems meant to interact directly with people, which the Commission's [guidelines on Article 50](https://digital-strategy.ec.europa.eu/en/library/guidelines-transparency-obligations-providers-and-deployers-ai-systems) (C(2026) 5054, 20 July 2026) describe as a two-way exchange, such as a chatbot. The developer reads the insights as outside it: they are written for you, and you do not exchange messages with the model. Follow-up questions to the AI, planned for a later version, will be such an exchange; when they come, the screen they are asked on will say that the answers come from an AI model.

**Machine-readable marking (Article 50(2)).** Providers of AI systems that generate text must make sure it is marked in a machine-readable format and detectable as artificially generated, as far as that is technically feasible. The duty applies from 2 August 2026; the Omnibus gives systems already on the market before that date until 2 December 2026 (the Act's Article 111(4), which it adds), but Sleevesight was first released after it, so that period does not apply to it. The developer reads it as covering the insights: they are new text, not a light edit of text you gave, the exception the Act makes (Article 50(2), last sentence). How to mark them is less settled. The Commission's [Code of Practice on transparency of AI-generated content](https://digital-strategy.ec.europa.eu/en/policies/code-practice-ai-generated-content) (June 2026), which the developer has not signed, finds that free-form text cannot carry metadata, and asks for no watermark on text shorter than 200 tokens, as too short to watermark reliably. An insight is a few sentences of free-form text, shown only on the app's own screen, kept only on the phone, and in no export. So the app adds no machine-readable mark of its own today. Once insights can leave the app, in the export of the tracks played with their insights planned for a later version, the exported file will mark each one as AI-written, with the service and model that wrote it.

**Text published on matters of public interest (Article 50(4)).** This is a duty of deployers who publish such text, which is not how the app is used.

## Children

Sleevesight is not directed at children under 13.

## Changes to this policy

A change is published on this page, with the date above. Its history is in the [repository](https://github.com/wkusnierczyk/sleevesight/commits/main/privacy.md).

## Contact

Sleevesight is made by Waclaw Kusnierczyk, who is responsible for this policy. Questions about it, or about your data: [support@sleevesight.app](mailto:support@sleevesight.app).
