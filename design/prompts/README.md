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
| 12 (#902) | `insight-v12-system.txt`, `insight-v12-system-model.txt`; the user message is version 9's | Version 10 with the rule on a line found in the track's language, translated and cited as usual. Not compared: a track with no such line is prompted as before but for the rule. |
| 11, rejected | `insight-v11-rejected-system.txt` | Version 10 with the length left out "even when a web page gives it", after a second blind A/B against version 8 (12 tracks) came out even (3 each, 6 the same) and version 10 named a web listing's length twice. Worse over two runs each: 6 and 9 of 159 and 168 sentences uncited (version 10: 2 and 4 of 147 and 153), the length named once and three times (version 10: twice each run), "the evidence …" opening thin cards 8 and 10 times. Never shipped. |
| 10, rejected | `insight-v10-rejected-system.txt` | Version 10 with an id rule and a rule against talk of the evidence of their own: 10 of 155 sentences uncited, nearly all "The evidence here is thin.". Never shipped. |

## Genre reason (`GenreReasonPrompt`)

| Version | Files | How it fared |
|---|---|---|
| 5 (#695) | `genre-reason-v5-1.txt`, `genre-reason-v5-1-model.txt`: one message | The baseline of #767's blind A/B. |
| 6 (#767) | `genre-reason-v6-system.txt`, `genre-reason-v6-system-model-search.txt`, `genre-reason-v6-1-user.txt` | Even with version 5 in the blind A/B (8 prompts: 1 each, 6 the same); kept for the insight's structure. |
| 7 (#902) | `genre-reason-v7-system.txt`, `genre-reason-v7-system-model-search.txt`; the user message is version 6's | Version 6 with the insight's rule on a line found in the track's language. Not compared. |
