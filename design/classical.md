# Classical music: the composer, the performers, the work and the recording

**Date:** 2026-11-10  
**Belongs to:** [design.md](design.md), *Entity resolution*; spike [#1134](https://github.com/wkusnierczyk/sleevesight/issues/1134)

Streaming services file a classical track under its composer: Spotify gives Buniatishvili's K. 545
as `Mozart: Piano Sonata No. 16 in C Major, K. 545 "Sonata facile": II. Andante` by `Wolfgang
Amadeus Mozart, Khatia Buniatishvili, Academy of St. Martin in the Fields`. The app takes the first
artist as *the* artist (`NormalizedTrack.primary`, `Normalizer.kt:76`), so for classical music it
searches, filters, keys and prompts by the composer, while the recording and the pages about it name
the performers. This note maps where that happens, how to tell the roles apart, what the Artist panel
should be about, and a set of tracks to check the fixes against. Already filed: #1129 (MusicBrainz
search), #1130 (album), #1131 (Key insights), #1132 (Dates: when composed), #1133 (Song: the music).
Line numbers are those of `dev-0.6.4` at `227d7fef`, before #1129's PR (#1176), which adds
`NormalizedTrack.composer` and `searchTitle` (`ComposerPrefix`).

## 1. Where the first artist stands in for the performer

| Area | Where | What the composer does there | Issue |
|---|---|---|---|
| MusicBrainz search | `RecordingResolver.kt:57`, date search `:742` | Searched as the artist; the recording credits the performers | #1129 |
| Album, web | `ItemInsights.kt:235` → `AlbumQuery.artist` (`AlbumLookup.kt:76`) → `ExaWebSearch.kt:456-463`; `RecordingFilter.kt:54-60` | A page naming the album title and the composer passes, whoever plays | #1130 |
| Album, Wikidata | `AlbumSource.kt:361` (`byName`) | The album item's performers must include the composer, so the right item is dropped | #1130 |
| Album card key, unresolved | `CardEntity.Album` (`CardEntity.kt:50-75`), `ItemLanguageLines.kt:55` | Title + composer: two recordings of *Piano Concertos Nos. 20 & 23* share a key | #1130 |
| Artist insight | `ItemInsights.kt:212`, `:226-231` (entity), `:427-430` (web query), `:960-977` (lines kept) | A Mozart biography; nothing on the soloist | #1181 |
| Artist row and page name | `ItemFolds.kt:40`, `ItemRows.kt:303`, `NowPlayingPages.kt:174` | The row reads "Wolfgang Amadeus Mozart" | #1181 |
| Prompts | `InsightPrompt.kt:175`, `ItemPrompt.kt:95` | `Primary artist: Wolfgang Amadeus Mozart`, as if he played | #1180 |
| Song review search | `SongQuestion.kt:46` (`songReviewQuery`, `:100`), `ExaSongSearch.kt:100-102` | `"<title>" Wolfgang Amadeus Mozart review interview` | #1180 |
| Language search | `ExaLanguageSearch.kt:168` | The composer's native name leads | #1180 |
| Genres | Last.fm track and album tags `TrackLabels.kt:198-213`; a web line's artist `DescribedGenres.kt:306` | Tags of "Mozart"'s track (Last.fm files scrobbles under Spotify's first artist, so these may well match); a web line on artists goes to the composer | #1180 (low) |
| Lyrics | LRCLIB `LyricsLibrary.kt:59-61`; Genius search `LyricsUrls.kt:72`; Wikipedia song titles `WikipediaSource.kt:492` (`Title (Mozart song)`) | Little harm for instrumental works; a vocal work (opera, choral) misses | #1180 (low) |
| Lookup key | `Normalizer.kt:84` (`lookupKey`) | Composer + title; the retrieval key adds every artist (`RetrievalCache.kt:160`), so no clash | none |
| Tracklist | `CoverBackModel.kt:84` → `AlbumPick` | The group is chosen by title; the artist is unused. Here empty because resolution missed; a resolved track can still have none (no album group the name matches, no tracklist, empty media: `CoverBackModel.kt:138`, `:171-179`) | #1129 |
| Dates | resolution only; cover/original `RecordingResolver.kt:288-331`, `:627-645` | See *Work and recording* below | #1178 |
| History | `HistoryRecorder.kt`, `SessionHistory.kt` | Keeps the player's whole artist string; no first-artist use | none |

Not by artist, but the same root: the **title with the composer prefix and the movement** is searched
whole by every source but MusicBrainz (#1176 strips it for MusicBrainz only): the track web search
and its filter, which needs the whole title as a phrase in the page (`ExaWebSearch.kt:430-449`,
`RecordingFilter.kt:40-46`, `:99`), Wikipedia's song titles, Last.fm's track lookup, LRCLIB,
Genius and the song review search (#1179). That alone likely explains the empty track-level web lines
behind #1131. Catalogue numbers differ between services too (Spotify `FWV 8`, MusicBrainz `M. 8`
for Franck's sonata), which a title-similarity match should tolerate; not filed, and out of #1179's
scope.

### Work and recording: the cover/original logic

Once #1129 resolves K. 545 (`e0a505da-…`), the recording is marked a cover with a 1949 recording by
another pianist as its "original" (found by #1176). `withWork` (`RecordingResolver.kt:288-331`) calls
a recording a cover when someone else's recording of the work predates all of ours
(`predates && !ownUndated`, `:316`), and `originalOf` (`:627-645`) takes the work's earliest dated
recording by anyone not credited on ours. For a pop song that is the original artist; for a composed
work it is merely the earliest recording in MusicBrainz, and *Dates* then says "The song was
originally recorded by …" (`DatesModel.kt:136-138`), the evidence "This recording … is a cover of …"
(`MusicBrainzEvidence.kt:83-96`). A performance of a composed work is not a cover. Signals, both free
(no extra request): the work's composer (`workCredits`, the work lookup the resolution already makes,
`RecordingResolver.kt:255-256`) is a credited artist of the track but not of the recording; or the
work's own type, else its parent's, is of a classical form (MusicBrainz work type `Sonata`,
`Concerto`, `Symphony`, `Opera`, `Aria`, `Mass`, …), the parent's given in the same answer by adding
`work-rels` to `WORK_INC` (`MusicBrainzClient.kt:330`) (K. 545's movement has no type; its parent,
*Sonata for Piano no. 16*, is `Sonata`). Then keep only MusicBrainz's own cover mark, and name no
original and no `earliest`. The composer
relationship's `begin` (`1788` for K. 545) is the composition date #1132 needs, also free. Filed as
#1178, and done so (`composed` in `RecordingResolver.kt`, `RecordingResolver.COMPOSED_FORMS`), with
two narrowings: the composer signal counts only when the title leads with that composer (`Mozart: …`,
signal A), since a songwriter credited on a pop track would pass it too; and a type of the work's own
that is no classical form is not composed, but for `Song` with that lead or as part of a `Song-cycle`
(a Lied of *Winterreise*). The composer's `begin` is read
into `MbArtistRelation.begin`, not yet used.

## 2. Telling the composer from the performers

| Signal | When available | Cost | Catches | Misses |
|---|---|---|---|---|
| **A. Title prefix** naming a credited artist (`ComposerPrefix`, #1176) | Before any request | 0 | `Mozart:`, `Franck:`, `Allegri:` (King's), `Puccini: Turandot …` | No prefix (*Nessun Dorma* by Puccini, Mehta, Pavarotti …; Richter's *Four Seasons Recomposed*); a prefix naming an uncredited composer (Tallis Scholars' `Allegri: Miserere …`) |
| **B. MusicBrainz work** composer relationship vs the recording's own credit | After resolution | 0 extra: the work lookup is made already; reaching the recording costs at most 2 more searches (#1176's retry by each artist) | Resolved tracks whose work and its composer MusicBrainz gives, prefix or not; also gives the composition date | Unresolved tracks, among them most composer-first tracks with no prefix: #1176 searches by the first artist, the composer, first and stops at the first non-empty answer, which a composer's name usually gives, so the performer's recording is rarely reached; a resolution with no work, a work lookup that failed, or a work with no composer relationship (`RecordingResolver.kt:255-270`) |
| **C. Wikidata occupation** (P106 = composer, Q36834) | Once each artist's item is known | 1 batched `wbgetentities`, plus finding the items (about 1 request per artist) | Composers with no prefix and no resolution | Unreliable: pianist-composers (Richter), conductor-composers (Bernstein), singer-songwriters all carry *composer* |

**Recommendation: A then B, not C.** Before resolution, the prefix (A) decides and the search order
puts a titled composer last (#1176). After resolution, B refines A where the work data is there: a
credited artist who is the work's composer and absent from the recording's credit is the composer,
and the other credited artists are performers. Where it is not (no work, the work lookup failed, no
composer relationship), A's result stands: a composer the prefix found stays the composer. A
composer-first track with no prefix (*Nessun Dorma*, Richter) stays as today unless the search order
changes, since its search by the composer rarely reaches the performers' recording (tracked in
#1180). The roles go on the resolution (composers, performers), and everything in the table above
reads "the first performer" where it reads `primary` today, falling back to `primary` when nothing
tells. C adds requests for a signal that is wrong for many performers; not worth it.

## 3. Which artist the Artist panel is about (proposal, for a decision)

| Option | For | Against |
|---|---|---|
| a. The composer (today) | Sources are rich; matches the player's first artist | Says nothing of who plays; the same card on every recording of the composer |
| b. The lead performer (soloist, else ensemble, else conductor) | It is the recording that plays; the performer's pages are what #1130 needs too | Composer context moves elsewhere; the lead is a guess where the player's order varies (Spotify puts Mehta before Pavarotti on one release, after him on another) |
| c. Each credited artist in turn, chips opening each in place | Nothing hidden; works for any multi-artist track | One web search and one AI call per artist opened; a busier panel |
| d. Performers under Artist (as b, others as chips per c), the composer with the music (#1133's work part) | Mirrors the work/recording split; nothing lost | Depends on #1133's mock-up |

**Proposed: d.** The Artist panel is about the lead performer, chosen by MusicBrainz's recording
relationships when resolved, in an order this proposal sets: `vocal`/`instrument`, then `performer`,
then `performing orchestra`, then `conductor`. `PERFORMER_TYPES` (`RecordingResolver.kt:537-538`) is
an unordered set, and `recordingCredits` (`:655-658`) folds every type into `CreditRole.PERFORMER`,
so the relationship type must be kept on the credit first. Else the first performer in the player's
order; the other credited
performers are chips that open their own insight in place, on tap only. The composer belongs to the
music: #1133's part on the work, with a chip there. Where nothing tells the roles apart, the panel is
as today. If the choice stays a judgement call, a setting (*Artist for classical tracks: Performer
(default) / Composer*) is the fallback. Needs a mock-up before any code.

## 4. Classical regression tracks

As Spotify reports them (Spotify search, 2026-11-10); MusicBrainz recordings from free searches the
same day.

| Case | Title (Spotify) | Artists (Spotify, in order) | Album | MusicBrainz recording | What it tests |
|---|---|---|---|---|---|
| Solo | `Mozart: Piano Sonata No. 16 in C Major, K. 545 "Sonata facile": II. Andante` | Wolfgang Amadeus Mozart, Khatia Buniatishvili, Academy of St. Martin in the Fields | *Mozart: Piano Concertos Nos. 20 & 23* (2024) | `e0a505da-98a7-4b76-a120-dbfb56a2ac02` | Prefix; composer first; cover/original; album namesakes (#1129–#1133) |
| Concerto | `Mozart: Piano Concerto No. 20 in D Minor, K.466: II. Romance` | Wolfgang Amadeus Mozart, Khatia Buniatishvili, Academy of St. Martin in the Fields | same | `76547831-c881-49f6-8a79-ee1d1acfa60e` | Soloist and ensemble; the album's other track |
| Chamber | `Franck: Violin Sonata in A Major, FWV 8: I. Allegretto ben moderato` | César Franck, Renaud Capuçon, Khatia Buniatishvili | *Franck, Grieg, Dvorak: Sonatas for violin & piano* | `aa8100ab-d414-4ade-a4b3-237ac5103a84` | Catalogue `FWV 8` vs MusicBrainz `M. 8`; two soloists. Another release of it credits the composers last |
| Opera | `Puccini: Turandot, Act 3: "Nessun dorma" (Live)` | Giacomo Puccini, Luciano Pavarotti, Zubin Mehta, Los Angeles Philharmonic | *The Three Tenors in Concert, 1994 (Live)* | `38e73992-38de-43b1-ba07-420c9e2e9cc1` | Vocal work (lyrics); MusicBrainz credits Pavarotti alone; conductor and orchestra |
| Choral | `Allegri: Miserere mei, Deus` | Gregorio Allegri, Choir of King's College, Cambridge, Gerald Finley, Timothy Beasley-Murray, Stephen Cleobury | *The Renaissance of Italian Music (National Gallery)* | `e48f58ac-ba2d-4766-82af-5f642f6c4a36` | Choir, soloists, conductor; MusicBrainz credits the choir alone; no work on MusicBrainz: the title prefix alone decides |
| Choral, composer uncredited | `Allegri: Miserere - 01. Miserere Mei, Deus` | The Tallis Scholars, Peter Phillips | *Allegri: Miserere; Palestrina: Missa Papae Marcelli; Mundy: Vox Patris Caelestis* | none matched; nearest `0c646f60-a809-4dd7-b473-d628a7625216` (68 s vs 72 s, another release) | Prefix naming no credited artist; a work split into sections |
| Crossover | `The Four Seasons Recomposed, Spring (After A. Vivaldi): I. —` | Max Richter, Francisco Fullana, City of Birmingham Symphony Orchestra, Carlos Izcaray | *Through the Lens of Time* | `3b70f90c-5c41-45b8-8d2e-1fcabcc09ce8` (`The Four Seasons Recomposed: Spring: I`) | Composer first with no prefix, not on the recording; an arrangement of another composer's work; title shape differs |

A non-classical control stays in the existing fixtures: a pop title with a colon that names no
credited artist (#1176's `Star Wars: Main Title` case).

## Filed from this spike

- #1178: a performance of a composed work is called a cover of its earliest recording by someone else.
- #1179: every source but MusicBrainz searches with the composer and the movement in the title.
- #1180: tell the composer from the performers once resolved (section 2), and search and prompt by
  the performers.
- #1181: which artist the Artist panel is about (section 3; a decision).
