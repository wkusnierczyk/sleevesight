# Prompt history

Each version's exact prompt, frozen as it was sent, so a later version can be compared with it or
taken back. Written from the fixtures the tests use, never from a real run's evidence: the insight's from
`FixtureEvidence`'s track 1 (*Sundown*) and track 41 (an original song); the genre reason's
version 5 from `GenreReasonPromptTest`'s *So What* ("Modal Jazz"), and its version 6 user
message from track 1 with "Folk", the stored `genre-reason-1-user.txt`.
The code that builds each one is in the repository's history (`git log --
core/src/main/kotlin/app/sleevesight/core/synthesis/`); the version history with the reasons is in the KDoc of `InsightPrompt.VERSION` and `GenreReasonPrompt.VERSION`,
and the contracts in `design.md`, *Appendix. Prompt contracts*. Versions before these are in the
history of `core/src/test/resources/prompts/`.

To take a version back: restore its texts from these files (the stored prompts the tests pin are
the current version's), raise `VERSION` (never reuse a number: the synthesis cache keys cards by
it), and say so in the KDoc.

## Key insight (`InsightPrompt`)

| Version | Files | How it fared |
|---|---|---|
| 8 (#800) | `insight-v8-1.txt`, `insight-v8-41.txt`: one message, the data then the rules | The baseline of #767's blind A/B. |
| 9 (#767) | `insight-v9-system.txt`, `insight-v9-system-model.txt`, `insight-v9-1-user.txt`, `insight-v9-41-user.txt`: a system prompt and a user message | Lost the blind A/B to version 8 (20 tracks, 3 passes each): version 8 preferred on 9, version 9 on 3, 6 the same. 15 of 54 cards empty against 6, two replies revising themselves. |
| 10 (#767) | `insight-v10-system.txt`, `insight-v10-system-model.txt`; the user message is version 9's | Version 9's opening and structure with the A/B's fixes: 2 and 4 of 147 and 153 sentences uncited over two runs, 6 empty, no reply revising itself. Even with version 8 in a second blind A/B (12 tracks: 3 each, 6 the same), at about a third less cost per card. |
| 11, rejected | `insight-v11-rejected-system.txt` | Version 10 with the length left out "even when a web page gives it", after a second blind A/B against version 8 (12 tracks) came out even (3 each, 6 the same) and version 10 named a web listing's length twice. Worse over two runs each: 6 and 9 of 159 and 168 sentences uncited (version 10: 2 and 4 of 147 and 153), the length named once and three times (version 10: twice each run), "the evidence …" opening thin cards 8 and 10 times. Never shipped. |
| 10, rejected | `insight-v10-rejected-system.txt` | Version 10 with an id rule and a rule against talk of the evidence of their own: 10 of 155 sentences uncited, nearly all "The evidence here is thin.". Never shipped. |

## Genre reason (`GenreReasonPrompt`)

| Version | Files | How it fared |
|---|---|---|
| 5 (#695) | `genre-reason-v5-1.txt`, `genre-reason-v5-1-model.txt`: one message | The baseline of #767's blind A/B. |
| 6 (#767) | `genre-reason-v6-system.txt`, `genre-reason-v6-system-model-search.txt`, `genre-reason-v6-1-user.txt` | Even with version 5 in the blind A/B (8 prompts: 1 each, 6 the same); kept for the insight's structure. |

## Changes made without a version

| Change | Prompts | Why no version |
|---|---|---|
| A rule on a line found in the track's language (#902): `- A line marked with a language, like (Wikipedia, release, Hindi), is in that language: translate what you use of it into English, and cite it as usual.` (`InsightPrompt.TRANSLATION_RULE`), after the rule on using only the evidence | The insight's (version 10), the song's, artist's and album's (`ItemPrompt`, version 1) and the genre reason's (version 6); the files above keep each version as it first shipped, the stored prompts the tests pin hold the rule | The lines' languages are in the evidence fingerprint, part of every card's key: a track that gains such lines is written anew anyway, and a raised version would only pay again for the cards kept without them. |
| A rule on dates (#957): `- Write dates in the form 17 October 2026, October 2026 or 2026.` (`InsightPrompt.dateRule`, its examples made by the app's formatter, `DateWords`), after the built-in style; on the phone, its examples in the phone's form, `- Write dates in the form October 17, 2026, October 2026 or 2026.` on a phone set to US English (`DateForm`, the app's `dateLocale`) | The insight's (version 10), the song's, artist's and album's (version 1) and the genre reason's (version 6), in their style; a genre's definition (`AiSearchPrompt.DEFINITION_VERSION` 1) and what the song is about (`AiSearchPrompt.EXPLAIN_VERSION` 1), in their rules; the files above keep each version as it first shipped, the stored prompts the tests pin hold the rule in the app's own form | A card kept keeps its dates as written, and a raised version would pay again for every card kept only to change their form. The form is in no card's key either, so a card written on one phone is shown as written on another. |
| The genre list the genres search chooses from (#1011): the vocabulary widened from 79 tags to 235, and one name per genre (its first tag; `AiSearchAnswers.genreNames`) in place of every tag, so 219 names, 2,498 characters (were 79, 804) | *Search with AI*'s genres search (`AiSearchPrompt.VERSION` 1, and `WEB_VERSION` 1 for its *once* and *agent* prompts with web search) | The reply is read through the vocabulary, which keeps only genres on it, so a genres answer kept from the shorter list is still a valid one; the version is shared with the dates and lyrics searches, and a raised one would pay again for every answer kept for them too. |
