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
- **To a file you choose**, only if you export the diagnostics: with the *Diagnostics* developer setting on, *Export* writes what the diagnostics screen shows (the latest events of the app's current run, the track changes recorded, what skip detection has learned, and the caches' sizes) to a file where you choose, which may be a cloud folder. That copy is yours, outside the app.

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

The site is served by GitHub Pages, so GitHub receives your IP address and the pages you ask for, under [GitHub's privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement). The site sets no cookies and has no analytics. The front page keeps nothing in your browser; its copy from before its redesign, under `design/`, keeps one note there, that its intro has played. The design pages with diagrams load the diagram script from jsDelivr, which receives your IP address ([jsDelivr's privacy policy](https://www.jsdelivr.com/terms/privacy-policy-jsdelivr-net)). Everything else the site uses, its fonts and its other scripts included, is served from the site itself.

## The GDPR

The EU's General Data Protection Regulation ([Regulation (EU) 2016/679](https://eur-lex.europa.eu/eli/reg/2016/679/oj), the GDPR) covers personal data, which here is what the app sends about what you play, with your phone's IP address, and any email you send. This section and the next say what the app does and how its developer reads its position under these laws. They are not legal advice.

### Who is responsible for what

- **Email to the developer.** For email you send to [support@sleevesight.app](mailto:support@sleevesight.app), the developer, Waclaw Kusnierczyk, is the controller, the one who decides what is done with it (Article 4(7)).
- **This website.** GitHub serves it, and logs each visitor's IP address for security, under [its own privacy statement](https://docs.github.com/en/pages/getting-started-with-github-pages/about-github-pages#data-collection). The design pages with diagrams also have your browser fetch the diagram script from jsDelivr, run by Volentio JSD Limited and served by its CDN providers, which see your IP address too. The developer receives no logs and no analytics from either. Under Directive 95/46, which the GDPR replaced, the Court of Justice of the EU held that a website's operator whose page has its visitors' browsers send their data to a third party "can be considered to be a controller" of that collection and disclosure by transmission ([*Fashion ID*, C‑40/17](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62017CJ0040), point 2 of the ruling), jointly with the third party (paragraphs 76 and 84). So the developer takes that role for this site: for your IP address reaching GitHub and jsDelivr when you visit, not for what each does with it afterwards, which is theirs.
- **The app.** No personal data reaches the developer. Each request goes from your phone straight to the service it is for, and that service decides what it does with what it receives, under its own terms and policy (linked [above](#what-leaves-the-phone-and-to-whom)). The AI services handle your requests under your own account: Anthropic's privacy policy, for one, says it does not cover what Anthropic processes on behalf of its API customers, which its [commercial terms](https://www.anthropic.com/legal/commercial-terms) and data processing addendum cover instead.
- **The open question.** The developer designs the app, so the developer decides what a request carries and to which services it can go, though it never receives one. The Court of Justice of the EU has held that someone who influences the processing of personal data for their own purposes can be a controller without ever having access to the data ([*Jehovan todistajat*, C‑25/17](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62017CJ0025), paragraphs 68 and 69; [C‑683/21](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:62021CJ0683), paragraphs 30 and 31), and that an entity that had a mobile app developed can be a controller of what the app processes (C‑683/21, point 1 of the ruling). The developer's reading is that it is not a controller of what the app sends: it has no purpose of its own in those requests, which are made for you, to the services you switch on, with your own keys, and it gets nothing from them. Your own use of the app, to read about the music you play, is in the developer's reading a purely personal activity, which the GDPR does not cover (Article 2(2)(c)). Recital 18 adds that the GDPR "applies to controllers or processors which provide the means for processing personal data for such personal or household activities": it binds the services that receive your requests, and would bind the developer only if the developer were a controller or processor of them, which is the question above. Whatever the answer, the app is designed to send as little as it can: each service gets only what it needs (the album only to the AI service, only the artists to Last.fm, only identifiers to Wikidata), each flow can be switched off, and this policy says what goes where.

### On what basis

- **Email:** the developer's legitimate interest in answering you (Article 6(1)(f)). It is read and kept by the email service that handles the address, for as long as the conversation needs, and deleted when you ask, unless the law requires keeping it.
- **This website:** the developer's legitimate interest in publishing the app's documentation and this policy (Article 6(1)(f)): the site is served by GitHub Pages, and the design pages draw their diagrams with jsDelivr's script.
- **The app:** none is needed by the developer, which processes nothing of what the app sends. Each service you use states its own basis for what it receives.

### Your rights

The GDPR gives you the right to see your data, have it corrected or erased, restrict or object to its processing, and take a copy with you (Articles 15 to 21). For what the app keeps, all of it on your phone, they are in your hands:

- **To see it and take a copy:** only part of it can be seen or copied in the app today. The home screen shows the playing track, what the sources found for it, and its insight. With the *Diagnostics* developer setting on, the diagnostics screen shows the latest events of the app's current run (a few hundred at most, held only in memory and gone when the app's process ends), the track changes recorded (kept with the history), what skip detection has learned, and the caches' sizes, and *Export* writes those to a file you choose. The export is a diagnostic record, not a copy of everything the app keeps: it leaves out the list of tracks played, the insights written, the cached findings and the settings, and no screen lists the tracks played or the insights of earlier tracks. An export of the tracks played with their insights is planned for a later version.
- **To erase it:** *Clear history* deletes the list of tracks played and the record of track changes at once. It does not delete the insights written or the cached findings: the insights stay until newer ones push them out of the cache's size limit, and the findings until they expire, on the lifetimes shown in the settings. Each saved key has a delete button, and *Remove all API keys* removes them all. Android's *Clear storage* for Sleevesight, or uninstalling it, deletes everything it kept, the insights included.
- **To object, or restrict what is sent:** each source is a switch, *Write insights* off makes no AI calls, *Prepare the next track* set to *Off* sends nothing about the next track, and *Look up in the background*, off by default, keeps every request to the times the app is on screen ([Your choices](#your-choices)).
- **For what a service keeps** of your requests, ask that service: its privacy policy, linked above, says how.
- **For what the developer holds,** which is only email you sent, write to [support@sleevesight.app](mailto:support@sleevesight.app) for a copy or its deletion. You get an answer within one month (Article 12(3)).

### Outside the EU

The app's requests go from your phone straight to each service; none passes through the developer, which makes no transfer of them. The organisations that run those services are mostly in the United States (Anthropic, OpenAI, Google, Exa, the MetaBrainz Foundation, the Wikimedia Foundation), with Last.fm in the United Kingdom; a request may be answered by a server anywhere. Some contract with customers in Europe through a company there: Anthropic's commercial terms, for example, name Anthropic Ireland, Limited for customers in the EEA, Switzerland and the UK. Each service's terms and privacy policy, linked above, say how it protects what it receives, and whether it may use it to improve its own products; they differ, and they change.

This website is different: visiting it has your browser send your IP address to GitHub, in the United States, and on the design pages with diagrams to jsDelivr, and the developer, having chosen them, takes the controller's role for that ([above](#who-is-responsible-for-what)). GitHub's privacy statement says that for such transfers it generally relies on the Commission's standard contractual clauses ([Implementing Decision (EU) 2021/914](https://eur-lex.europa.eu/eli/dec_impl/2021/914/oj)), and that it has also certified to the EU–U.S. Data Privacy Framework, which the Commission found adequate ([Implementing Decision (EU) 2023/1795](https://eur-lex.europa.eu/eli/dec_impl/2023/1795/oj)). jsDelivr is run by a company registered in England and Wales, and the Commission finds the United Kingdom adequate ([Implementing Decision (EU) 2021/1772](https://eur-lex.europa.eu/eli/dec_impl/2021/1772/oj), renewed until 27 December 2031 by [Implementing Decision (EU) 2025/2574](https://eur-lex.europa.eu/eli/dec_impl/2025/2574/oj)); its requests are served by CDN providers, among them Cloudflare and Fastly in the United States, under jsDelivr's terms.

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

**What the app tells you.** Before anything is written, the first-run screen says that the AI service you choose writes the insights, and nothing is written until you choose one, and save its key, in the settings. Each insight is written by the service and model set there at the time; the card does not yet name them. Each sentence of an insight shows the evidence it cites, from the sources the app looked up; a sentence that cites nothing is underlined; and a sentence from the model's own knowledge, which is used only if you switch it on, carries an *M* chip in place of a source's, which a screen reader reads as "From the model's own knowledge, not verified", and the line under the insight counts such sentences.

**Interacting with an AI (Article 50(1)).** This duty is for AI systems meant to interact directly with people: they must be designed so that people know they are dealing with an AI system, unless that is obvious to a reasonably well-informed, observant and circumspect person. The developer's reading, which is arguable, is that the insights are outside it: they are written for you, and you do not exchange messages with the model. But the Commission's [guidelines on Article 50](https://digital-strategy.ec.europa.eu/en/library/guidelines-transparency-obligations-providers-and-deployers-ai-systems) (C(2026) 5054, 20 July 2026, point 30(ii)) allow even a single prompt and its reply to be an interaction, where the exchange has a conversational or responsive character, including one the person did not start, so another reading puts a tap on *Write insight* inside it. Either way, the app gives the information the duty asks for, and when Article 50(5) asks for it, at the first interaction at the latest: its first-run screen opens by saying that it "has the AI service you choose write about" what is playing, before any insight is written, and you choose that service, and save its key, yourself. Follow-up questions to the AI, planned for a later version, will be an exchange beyond doubt; when they come, the screen they are asked on will say that the answers come from an AI model.

**Machine-readable marking (Article 50(2)).** Article 50(2) requires providers of AI systems that generate text to ensure that the outputs are marked in a machine-readable format and detectable as artificially generated, and that their technical solutions are effective, interoperable, robust and reliable as far as this is technically feasible, taking into account the kind of content, the costs of implementation and the generally acknowledged state of the art. It does not apply to the extent a system performs an assistive function for standard editing or does not substantially alter the input it is given; the insights are new text written by the model. It applies from 2 August 2026; systems already on the market before that date have until 2 December 2026 (the Act's Article 111(4), added by the Omnibus), and Sleevesight was first released after 2 August 2026, in October 2026. The Commission's [Code of Practice on transparency of AI-generated content](https://digital-strategy.ec.europa.eu/en/policies/code-practice-ai-generated-content) (June 2026), which the developer has not signed, describes the techniques (Measure 1.1): digitally signed metadata, where the content's format can carry it, which it finds free-form text cannot; an imperceptible watermark, added as the model generates the text or afterwards ("post-hoc"), except on "very short text", under 200 tokens; and marking done elsewhere in the value chain, by an upstream model provider or a third party, which a system's provider may rely on "without prejudice to" its own responsibility that its system's outputs are marked. What the app does: its first-run screen says that the AI service you choose writes the insights; each sentence shows what it cites, and a sentence from the model's own knowledge carries an *M* chip; it adds no machine-readable mark of its own, neither as the text is written nor afterwards; and it asks the model for two to four sentences, without enforcing that length. Whether Anthropic, OpenAI or Google mark their output, the developer does not know. The insights are shown only on the app's own screen, kept only on the phone, and in no export. Once insights can leave the app, in the export of the tracks played with their insights planned for a later version, the exported file will mark each one as AI-written, with the service and model that wrote it. The developer is looking at how to meet the duty.

**Text published on matters of public interest (Article 50(4)).** This is a duty of deployers who publish such text, which is not how the app is used.

## Children

Sleevesight is not directed at children under 13.

## Changes to this policy

A change is published on this page, with the date above. Its history is in the [repository](https://github.com/wkusnierczyk/sleevesight/commits/main/privacy.md).

## Contact

Sleevesight is made by Waclaw Kusnierczyk, who is responsible for this policy. Questions about it, or about your data: [support@sleevesight.app](mailto:support@sleevesight.app).
