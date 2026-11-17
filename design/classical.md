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
search), #1130 (album), #1131 (Key insights), #1132 (Dates: when composed), #1133 (Song: the music), section 5).
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

Done so in #1179, only for a title whose composer's lead the normalizer found (`composer` set):
the track web search, Wikipedia's song titles, the Genius search link, the song's own search (in
English and in the track's language), the language search, the AI's song question and the AI's
genre web searches (the genre reason's, `ReasonQuestion.query`, and the genres list's,
`GenreLists.genresQuery`) search by `searchTitle`. The track web search also quotes only the work
part (`NormalizedTrack.searchWork`: the searched title before its first colon, but for one a digit
follows as in `Hob. I:94`, a quoted nickname left out, `Piano Sonata No. 16 in C Major, K. 545`;
none when what is left has no digit, a number or a catalogue number, as `Symphony` of `Symphony
"Surprise"` or `Star Wars` would be too generic), and its filter (`RecordingFilter.isAbout`) takes a page that names the title as searched or the work, and
still a credited artist by full name, as before: a page on the sonata naming Buniatishvili, or
naming Wolfgang Amadeus Mozart, counts for the Song insight; one on another sonata does not. The
track's own lines (the track web search and the one in its language) are taken by the key insight as
facts about this recording, so there a page must also name a performer, whether it names the work
or the whole title (`isAbout`'s `recording`, #1195): a page on K. 545 naming Mozart alone may be
about another pianist's recording, its year or its label. This holds for every title that leads with
its composer, one with no work part (`Mozart: Piano Sonata No. 16 in C Major, K. 545`) and one the
language search names by its Wikidata label among them; a track credited to its composer alone has
no other performer, and the composer counts. The key
insight's prompt for such a track also says that a line about another recording of the work is not
about this track (`InsightPrompt.RECORDING_RULE`, #1195), as the album's does (#1130). Every
other title is searched exactly as before (`Star Wars: Main Title`). Two sources keep the whole
title on purpose: **Last.fm** and **LRCLIB** hold the names the players send, the lead included.
Checked 2026-11-10: Last.fm has a track page with tags (`classical`, `instrumental`) for
`Mozart: Piano Sonata No. 16 …` by Wolfgang Amadeus Mozart and none for the title without the lead;
LRCLIB's `get` finds Buniatishvili's recording by the whole title, album and length, and nothing by
the title without the lead. The song's kept web lines are keyed by `NormalizedTrack.searchKey`,
which differs from the lookup key only for such a title, so only its lines are searched again once;
the track web search's results live a day, and Wikipedia's were asked again after 0.6.3 anyway
(#1176 raised its extraction version).

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
into `MbArtistRelation.begin`, and its `end` into `MbArtistRelation.end`: *Dates* says when the music was
composed from them (#1132, `ComposedDates.ofWork`, `Resolution.composed`; `design.md`, *Key data*), else from
the infobox of that item's English Wikipedia article (`composed`, else `year`; #1273, `ComposedDates.ofInfobox`,
read from the lead request the adapter makes anyway), else from the inception (P571) of the song's Wikidata item
the Wikipedia identity check accepted (`ComposedDates.ofItem`).

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

Done so in #1180. **The roles** (`ArtistRoles`, `Resolution.roles`, `rolesOf`): when
the recording's work was looked up and names a composer, a credited artist the work names its
composer (by name), or the composer the title leads with (a work's composer relationship may name an
arrangement's arranger instead), is a composer unless they play on the recording. For a work of a
classical form (its type, or with none its parent's, as #1178 reads it) and for the title's composer on
a work of no type, only a performer relationship shows that they play: MusicBrainz often credits the
composer on a classical recording, its release imported from a streaming service's artists, composer
first. For any other work (a `Song`, …) the recording's credit shows it too (no id from its credit or
its performer relationships, no credit standing for them), so `Band: Song` by Band and a guest keeps
Band a performer. The other credited artists are performers, but an artist the title names beside its
composer whom the recording does not have (`(Arr. Busoni)`). A songwriter who sings their own song is
on the recording, so a performer: a pop song has no composer and its prompt is unchanged. With no
work, a failed work lookup or no composer relationship, `Resolution.roles` is null and the title's
lead decides (`ArtistRoles.of`: its composer, and `NormalizedTrack.performers`; without a lead, no
composer and every credited artist a performer). MusicBrainz's result keeps the roles
(`SourceResult.roles`, its extraction version 17), so a replay served from the cache, which makes no
resolution, has them too (`TrackState.roles`). **The search order** (`RecordingResolver`, `further`): without a
composer lead, when the first credited artist's search finds nothing that fits, the next credited
artists are searched in turn, up to three searches in all; a recording only those found counts only
when every name its credit gives is one of the track's. Of those that fit, the ones crediting the most
of the track's artists come first, then, of the earliest three, the one whose performer relationships
name the most of them (one recording lookup each, the chosen one's not asked again), the earliest
deciding a tie: a recording crediting the soloist alone is still accepted, and with no relationship to
tell them apart, the earliest such recording is taken, whoever conducts it. Checked 2026-11-14 on *Turandot: "Nessun
dorma!"* by Puccini, Pavarotti, Mehta and the Los Angeles Philharmonic (`fixtures/http/musicbrainz-1180/`):
MusicBrainz has nothing by Puccini; Pavarotti's search finds `38e73992`, credited to him alone, beside
his recordings with other orchestras (1991, Royal Philharmonic and Adler; 1993, New York
Philharmonic members and Magiera), which crediting others the rule leaves out, and his 2006 one,
credited to him alone too, whose relationships do not name Mehta or the orchestra; the work, *Turandot: Atto III, scena 1.
Aria "Nessun dorma"*, names Puccini its composer, and the recording names Mehta (`conductor`) and the
orchestra (`performing orchestra`): Puccini is the composer, the other three the performers.
**When nothing fits** (#1289; `RecordingFallbacks.kt`, `fixtures/http/musicbrainz-1289/`), three
bounded fallbacks, and a fourth for the work of a recording that links none (#1299). A track that
resolved before keeps its recording, and the alias lookup and the loose search run only when nothing
fits in any form (by name, as a band, under an honorific variant, in a joint credit), so it makes
neither, and a part-of-work reading is then not weighed as its recording; what it can gain is a lent
work (the third), only when its recording links no work and another recording of its artists of
MusicBrainz's `Work: “Part”` shape fits: at most two more lookups without a composer lead (the other
recording's detail, then its work), up to six with one (three recordings' details and the works they
link, each work looked up once); and, with a composer lead, when its recording still has no work, a
work found by a search (the fourth): one search more, and the lookup of the work it takes.
- *An alias of a credited artist.* A recording whose title, length and a performer fit, but whose
  credit names someone none of the track's artists is by name, has that artist's other names looked up
  (`artist/<id>?inc=aliases`, core data; at most two artists for the track, only while a performer of
  the track is left unnamed); an alias that is a track artist's name counts as theirs. With a composer
  lead, first among the recordings the searches by name found. Then, with or without one (#1330), when
  nothing found may be chosen in any way (read as a part of a work, credited in any form), among those
  the loose search (below) and the searches by the later credited artists left out for crediting
  someone else and that have the title's numbers; such a recording counts once the aliases make it the
  track's artists' and it stands for two or more of them, as the loose search's own, and, when only an
  alias makes it so, for two or more performers, the composer (the title's, else the first credited) not
  counted: another conductor's recording credited to the composer and the orchestra is not theirs. An alias looked up
  stands for every recording after it, the loose search's included. A track that resolved before makes
  no lookup it did not make. *Barber:
  Adagio for Strings* by Samuel Barber, Sir Simon Rattle and the Berlin Philharmonic: MusicBrainz
  credits Rattle and the Berliner Philharmoniker, whose aliases include Berlin Philharmonic. Four
  requests (two searches, the alias lookup, the recording), where it made two and found nothing.
  MusicBrainz links that recording to no work, so the title's lead tells the composer. *Symphony No. 9 in D Minor, Op. 125 "Choral": I.
  Allegro ma non troppo, un poco maestoso* by Beethoven, the Berlin Philharmonic and Karajan (the
  composer credited first, no lead; `fixtures/http/musicbrainz-1330/`): the three searches by name find
  no recording within the length's tolerance; the loose search finds Karajan's of 1983 (`eadd2605`,
  15:36), credited to the Berliner Philharmoniker and him, taken by the orchestra's alias. Seven
  requests (four searches, the alias lookup, the recording and its work), where it made four and found
  nothing; the work names Beethoven its composer.
- *A loose search.* With the player's length and two or more credited artists, one search by the
  title's words (`word~` from four letters on) and every credited artist at once
  (`searchRecordingsLoosely`). Only a recording whose credits stand for two or more of the track's
  artists (a joint credit for one of them counting once), and for no one else, counts, and only with
  the same numbers in its title as the track's (`NameMatcher.numberWords`: digits, Roman numerals,
  single letters, ordinals, numbers in words one to twelve, in the same order; *Gymnopédie No. 1* is not
  *No. 2*, nor *Op. 1 No. 2* *Op. 2 No. 1*, which the title's similarity alone would pass);
  its title is then weighed as any other's. *Nessun doma - Turandot* (sic) by Puccini, Adami, "Carlo
  Simoni", Pavarotti, Magiera and the Orchestra da camera di Bologna: the three searches by name end at
  the librettists; the loose search ranks first `4cf239b6`, credited to the last three. MusicBrainz's
  search does not always match a misspelled word loosely (`doma~` finds no `dorma`: a common word has
  more close neighbours than Lucene expands), so the other words and the artists carry it. Six
  requests, where it made three.
- *A title read as a part of a work.* MusicBrainz titles a part of a classical work `Work: “Part”`,
  often after an act (`Turandot: Atto III. “Nessun dorma”`); the player's title is weighed against the
  quoted part, as written or without the work before a colon or after a dash (`pieceSimilarity`), only
  for a title quoting one part, with nothing after it but a parenthetical (not `Medley: “Yesterday” /
  “Michelle”`), and, with the player's length, only for a recording whose length is known. Such a
  reading counts only when no recording read as written fits. When one does but links no work, the
  artist's recordings read so, of a known length that fits and standing for the same of the track's
  artists as the chosen one, and for the same people (an id in common for each), are looked up for one
  that does (`Leaders.lentWork`): the first one found without a composer lead; with one, up to three in
  turn, each one's work looked up to check its composer, until one names the composer the title leads
  with (Schubert's own *Ave Maria* after Verdi's *Otello* aria).
  That work is lent to the chosen recording as its own performance of it (no cover, not instrumental,
  no languages of the other's), the recording kept with its credits, relationships, resolved ids and
  dates: only the work is borrowed, never the recording swapped. With a composer lead, only a work whose
  composer relationship names that composer (`workOf`), whatever its type: a lead alone is only a
  credited name before a colon (`Band: Song`), and `Schubert: Ave Maria` is not lent Verdi's `Otello`
  aria. Without one, only a work of a classical form (its type as the recording's relationship gives
  it, an `Aria`, not a `Song`), so a pop match is never lent a song. That leaves a risk with no lead: a
  classical piece of the same name by the same singer (*Ave Maria* by a soprano alone, Schubert's
  recording linking no work, Verdi's aria by her linking its work) may be lent the other piece's work. A
  lookup that fails on the way (the chosen recording's, another's, or one weighed for the choice) leaves
  the resolution provisional. *Nessun Dorma* by Pavarotti, from an album MusicBrainz does not have: the
  earliest `Nessun Dorma` that fits (`d303669a`, 1994) links no work; his `Turandot: “Nessun dorma”` of
  1993 (`2dede7cc`) links the aria, which `d303669a` borrows. Five requests, where it made three.
- *A work search* (#1299; `WorkSearch.kt`). With a composer the title leads with, when the chosen
  recording links no work and none is lent it, one search of MusicBrainz's works by the title without
  the lead and the composer's name (`MusicBrainzLookup.searchWorks`, `work:"…" AND artist:"…"`, at most
  10 works, each with its disambiguation, aliases and artist relationships: core data; the search gives no
  relationships between works, nor to URLs, so no Wikidata item to tell the piece by). MusicBrainz keeps
  a work for each arrangement, version and setting of a much-played piece, most of the same title and
  composer, and negative checks alone kept missing kinds of them (`canonicalWork`, `WorkSearch.kt`). So a
  work is taken only with evidence that it is the piece itself, and none against it:
  - *Known by many names*: at least three aliases (`CANONICAL_ALIASES`). The pieces looked at have many,
    in the languages they are known in and their catalogue forms (Barber's *Adagio for Strings* 12,
    Ravel's *Boléro* 13, *Für Elise* 31, *Eine kleine Nachtmusik* 19 to 48); their arrangements,
    versions and catch-alls none to two. A lesser-known piece with fewer is given no work: the safe way.
  - *Only the composer composed it*: every `composer` or `writer` relationship names the lead, and one
    does (*Für Elise*'s production music version credits Giscard Rasquin as a composer too). A lyricist
    alone does not count against it: an art song or an aria has one.
  - *No arrangement*: no `arranger`, `instrument arranger`, `vocal arranger` or `orchestrator`, but the
    composer as its orchestrator (Barber orchestrated his own Adagio); the composer's own `arranger`
    credit is an arrangement (`arr. Ravel for 2 pianos`).
  - *No named version*: the disambiguation names none of its related artists but the composer (the last
    word of the name: `production music version, Rasquin`, `with lyrics by Georg Kreisler`), and does not
    mark it a catch-all for arrangements. No other words of it count (`original orchestration` names
    arrangements and is none).
  - *Its title fits the track's*, either way. Segment by segment: the titles split at a part's separators
    (a colon with whitespace after it, so not a catalogue number's `Hob. XVI:52`), as many segments, each
    alike as a recording's title, with the same numbers (`NameMatcher.numberWords`: Barber's *Essay for
    Orchestra No. 1* or *Third Essay* is not his *No. 2*); not the titles joined, which a whole work and
    its unnumbered movement pass (*Variations on an Original Theme, op. 36: “Enigma”* and its *:
    Nimrod*, as alike as titles that fit, with the same numbers). Or as the part a part of a work quotes
    (`pieceSimilarity`: `Vanessa: Act I. “Must the winter come so soon?”` for its aria's own title), with
    the same numbers.

  Of the searches recorded (`fixtures/http/musicbrainz-1299/`): *Boléro* takes the ballet (`487527b9`),
  not Ravel's own arrangement for two pianos; *Für Elise* and *Eine kleine Nachtmusik* take none, since
  the piece is titled by its catalogue form (`Bagatelle for Piano in A minor, WoO 59 “Für Elise”: Poco
  moto`, `Serenade no. 13 for Strings in G major, K. 525 „Eine kleine Nachtmusik“`), which the track's
  title does not fit, and the works of the short title are versions (Rasquin's, Gray's, Kreisler's song).
  Matching the track's title against a work's aliases would reach those pieces; it would widen what is
  taken, and is left for later.
  Only a single candidate is taken: with two, nothing tells which is the track's, and the recording
  keeps no work. It is linked as the recording's own performance of it
  (no cover, not instrumental) and looked up as any work, so the resolution keeps it as its work
  (`Resolution.work`), and the search's answer is held in memory with MusicBrainz's others
  (`RememberedMusicBrainz`). Without a lead, no search: a pop recording linking no work makes no request
  more. A search that fails leaves the resolution provisional. *Barber: Adagio for Strings* by Rattle and
  the Berlin Philharmonic: the search finds five works titled *Adagio for Strings*, all Barber's: the
  piece (`1ebe1abf`, which he orchestrated himself; composed 1936, Wikidata `Q346715`), a catch-all for
  its arrangements, and three arrangements (for organ, woodwind band and clarinet choir, each with its
  arranger); the piece, the one left, is taken. Six requests, where it made four. A much-recorded
  composer's answer is large, since each work comes with the recordings it links, which are not read
  (Beethoven's *Symphony No. 5*, 10 works: about half a megabyte), but the search is made only for a
  recording that links no work.
The roles then leave out a credited artist the work names its lyricist or librettist (by name) who does
not play on the recording, and on a classical work put the performers the recording has first, so the
lead is one who plays (Spotify credits *Nessun dorma*'s librettists before Pavarotti; its "Carlo
Simoni" is MusicBrainz's Renato Simoni, so stays a performer, last). A composer the player does not
credit (Puccini on *Nessun Dorma* by Pavarotti alone) is still not told apart: the work dates the music
and names who wrote the words, but the Composer row shows only credited artists.
**Where the first performer is read** (`ArtistRoles.lead`, the first performer, else the first
credited artist): the key insight's and the *Key insights*' prompts (`Composer:` and `Performers:` in
place of `Primary artist:`, `design.md`, *Appendix. Prompt contracts*), the song review search
(`SongQuestion.query`, `ExaSongSearch`, in English and in the track's language), the language search's
native track (`ExaLanguageQuery.nativeTrack`'s primary artist) and Wikipedia's song titles
(`WikipediaSource.songTitles`: asked before the resolution lands, so by the title's roles only).
**Where the first credited artist stays**: Last.fm's track and album tags and LRCLIB, which keep the
names the players send (Last.fm files Spotify's scrobbles under the first artist; LRCLIB has
*Nessun dorma* under Giacomo Puccini as often as under Luciano Pavarotti, checked 2026-11-14).
*Dates*' composition date and the Song insight's music part read the roles in #1132 and #1133; the
Artist panel in #1199 (section 3).

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

**Done so in #1199** (the D-Classical mock-up's pick, 2026-11-14; replaces d's composer with the
music): where the roles (#1180, `TrackState.roles`) tell a composer apart from at least one performer
(`ArtistSplit`), Home has a **Composer** row between Song and Artist, about the composer
(`InsightItem.COMPOSER`), and the **Artist** row is about the lead performer (`ArtistRoles.lead`: the
first performer in the player's order, the relationship order of this section not yet kept), its
other performers chips under *Also performing on this recording*, each opening that performer's
insight in place, written on the tap (`InsightItem.named`). Otherwise, one Artist row about the first
credited artist, as before: no prefix and no resolution, a pop song, a track credited to its
composer alone. The composer's, the artist's and a performer's insights are all artists' cards
(`CardKind.ARTIST`, keyed by the artist), so the one about an artist is written once and shown
wherever that artist is the subject. The Composer row is *Composer* for one composer; *Authors* for
more than one author, a lyricist or a librettist apart from the composer among them (MusicBrainz's
`lyricist` work relationship, read for #998, and `librettist`, #1274: *Nessun dorma* is Puccini's
under *Music by*, Giuseppe Adami's and Renato Simoni's under *Words by*), the panel then headed
*Music by* over the composer's insight and *Words by* over the chips of the lyricists, then the
librettists (*Music* and *Words* until #1331);
*Author* for one whose own words the work's lyricist (or librettist) relationship gives, on a title
that does not lead with them (a songwriter, not a classical work). The librettists are no sign of
vocals for the genres, which read the lyricists alone (#998), and the work's MusicBrainz lines name
them ("The libretto of this aria was written by …", the piece named by the work's MusicBrainz type
where a libretto is written for it: aria, cantata, musical, opera, operetta, oratorio or zarzuela;
else "this piece"). The Artist row is then
*Performer*, singular even when other performers are known (#1276): its insight is the lead
performer's, the others its chips; where nothing tells the roles apart, it stays *Artist*. Home's
artist lines put the composer on a line of their own under the performers
("Composer: Wolfgang Amadeus Mozart").

When the player credits only the performer (Spotify's *Nessun Dorma* by Luciano Pavarotti alone,
#1298, the mock-up's pick of 2026-11-17), the composers the resolved work names whom the track does
not credit (`ArtistRoles.uncredited`, by MusicBrainz's names, from its CC0 `composer` work
relationships alone, never its tags or other supplementary data) are shown as if credited: the
*Composer* or *Authors* row, *Performer* for the credited artist, the *Composer:* or *Authors:* line
under the title. Only on a classical work (the roles' classical form), and only when every credited
composer is one the work names by name, so a composer is never named twice in two spellings. A
composer the work names counts as credited, and is not added, when a credited artist is them by
name, by the MusicBrainz id it resolved to, or by an id of the joint credit the track was accepted
under (`Resolution.jointArtists`: the player's "G. Puccini & Luciano Pavarotti" is MusicBrainz's two
artists joined); or when a credit may name them (`mayName`): folded, its words all theirs ("Puccini")
or theirs all its, the credit as a whole or any member of a joint credit (`creditMembers`, split at
`&`, `/`, `+`, a comma, "and", "with", "x", "feat."; "Puccini & Adami" unresolved). Leaving a composer
out is the lesser fault than naming one twice. The line under the title then ends with one citation
chip of MusicBrainz's line on the work, as the insight's chips are ("MB4", read "Source MB4"), whose
tap opens *Sources* at it, whenever it names anyone the player does not credit
(`ArtistSplit.uncredited`, on a classical work only): the uncredited composer, and the librettists or
lyricists MusicBrainz names that no credited artist, nor a member of a joint credit, is by name (also
on Spotify's *Nessun doma - Turandot*, which credits Puccini; not when the player credits "Giuseppe
Adami & Renato Simoni"). By name alone there, since a result kept from an earlier play holds no ids.
The rows carry no mark. The key insight's prompt names them too (#1376): its `<track>` block reads
`Composer: Giacomo Puccini` and `Performers: Luciano Pavarotti` where it read `Primary artist:
Luciano Pavarotti` (`ArtistRoles.named`), as the item insights name them through `ArtistSplit`; its
card is keyed by its own prompt version (`InsightPrompt.UNCREDITED_VERSION`, 15; 16 for a
composer-led title naming one too), so a card kept from before is written again once. Wikipedia looks
up the uncredited composer's article as a credited artist's (#1376), by the MusicBrainz id of the
work's `composer` relationship naming them (`Resolution.credits`), never by name; its lines are about
that composer, so the composer's insight is written from them, and the lone credited performer's
insight leaves them out (`ItemInsights.artistLines`); a genre their article or item states goes to the
composer's own row, as for a credited composer (`GenreDescribed`). Wikipedia awaits MusicBrainz's
resolution already, and the key insight is written once every source has landed, so neither waits
for MusicBrainz any longer than before; when MusicBrainz does not answer on a play, the prompt and
the lookups read the player's credits alone, as before, and the next play that has the answer asks
again (a different prompt version, and Wikipedia's results kept under the earlier extraction
version, 10, looked up again under 11). The searches that run without waiting for MusicBrainz still
read the player's credits alone: the web search for the track (Exa), sent at once, and Last.fm's
artists, whose answers and tags feed the genre and similar-artist rows by credited artist. A web line's artist for the genres a description names
(`DescribedGenres`) is the Artist panel's subject, the lead performer, whose web search found it;
the lines the composer's or a performer's insight gathered are listed in *Sources* marked as about
that artist, so a genre their page names goes to that artist's described row.

On a classical track the Song row is called **Music** (#1331, the mock-up's pick, 2026-11-16), by
the test that names the Composer row (`ArtistSplit.classical`): beside a *Composer* row always, and
beside an *Authors* row when the work is a classical one (the roles' classical form,
`ArtistRoles.classical`, or a title leading with its composer), so an opera's aria is *Music* too.
Its panel's title, its page's title under *Separate pages*, its screen reader action ("show the
music's insight"), its no-evidence line and *Report the insight* ("the music's") follow it. Beside an
*Author* row (a songwriter's own song, or a cover of it), beside *Authors* on a work not classical,
and with no split, it stays *Song*. The insight's two parts stay *The music* and *This recording*,
the prompts' song kind stays as it is, and Settings keep *Song* (*Song, Artist and Album on a new
track*).

Beside a *Composer* or *Authors* row, the song's row names the title without its composer lead
(#1356, the mock-up's pick, 2026-11-17): Spotify's *Barber: Adagio for Strings* is **Music**
*Adagio for Strings*, the row beside it naming Barber. The lead is the one the normalizer finds
(`NormalizedTrack.composer`) and leaves out of the searches (`searchTitle`), or, when it finds none, a
lead naming a composer MusicBrainz names whom the player does not credit (#1376,
`ComposerPrefix.leadNaming`: *Barber: Adagio for Strings* with only the orchestra credited), and it
goes only when the row beside names that composer: a lead naming a performer (*Gould: Interview*, with Bach the
composer) stays, and with no split the title is as under the cover. The row, its panel's heading, its
page's title and what a screen reader reads ("Music, Adagio for Strings, uses AI…") all follow it;
the title under the cover keeps the player's title, and the version line (*Version: 2019 Remaster*)
is not affected, coming from the title's end.

A performer's or an author's chip gives way to its insight (#1288, the 0.7.0 phone check's pick):
the tap puts in the chip's place a panel titled with the name, *Close* at its top right, then the
insight, so the name is not shown twice; *Close* puts the chip back where it was. Each chip opens
and closes on its own, several at once, under both layouts (the panel's and the page's content is
the same). The screen reader's focus goes to the panel's *Close* on opening and back to the chip on
closing.

The rows are named once (#1355, the mock-up's pick, 2026-11-17). Before MusicBrainz answers, only a
title leading with its composer tells one, so a classical title without that lead would read *Song*
and *Artist* first, then *Music*, *Composer* and *Performer*. Home therefore holds back the rows
under *Key insights* and the artists' line under the title (its height kept, so the album's line
does not move) until the track's roles are known (`rolesKnown` in `RowsNaming.kt`): the title leads
with its composer, or the run has MusicBrainz's answer (its resolution, or its source landed: kept
from a track played before, off, failed, unreachable). With no answer within 10 seconds
(`NAMING_WAIT`) they are named by the title; an answer after that renames them once. Once named, a
track's rows are not held again (a *Retry* asking MusicBrainz again). The hold applies to every
track, since nothing tells a pop track from a classical one before the answer.

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

## 5. The Song insight: the music and this recording

**Done so in #1133** (the D-Classical mock-up's pick, 2026-11-14): where the roles tell a composer
apart from at least one performer (`ArtistSplit`, the same condition as the Composer row), the song's
insight is one insight in two parts, headed *The music* and *This recording* (`SongParts`), one call
and one credit line. Every other track's is written as before, in one part with no heading, under the
same card key: no prefix and no resolution, a pop song, a track credited to its composer alone.

- **Prompt** (`ItemPrompt.build` with `parts`, `ItemPrompt.PARTS_VERSION` 3 in the song card's key): its
  own task line (`PARTS_TASK`) and, in place of the song's subject rule, `PARTS_RULE`: two parts, each
  heading on a line of its own as given; the music part on the work itself (who composed it, when and
  why, what it is, where it sits in the composer's work, how it was received), the recording part on
  this performance (who plays it, when and where it was recorded, its release); one to three sentences
  each (its style line, `PARTS_STYLE`); a part no line supports left out with its heading; a line
  about another recording of the work in neither part. `PARTS_PLAIN_TEXT_RULE` in place of the plain-text rule (the two headings are the only
  ones), and no "artist" rule. Citations, the evidence-only rule and the grounding check hold for both
  parts as for any insight. Raising the version rewrites, once, each kept song card of such a track
  when it is next asked for; no other card changes key.
- **Reading the reply**: the grounding check, given `headings` for a card under `PARTS_VERSION`, reads a
  line that is one of the two headings (its case aside, perhaps with a Markdown `#`, emphasis or a
  colon) as a paragraph of its own, one sentence marked `CheckedSentence.heading`, uncited on purpose,
  never dimmed and counted in none of the counts; Home shows it bold, read as a heading
  (`SongPartHeading`), while it streams too.
- **Evidence for the music**: the song's own lines, as before (the work's MusicBrainz lines, Wikipedia's
  and Wikidata's lines of the song's item the identity check accepted, the track's web lines, which
  #1179 already searches by the work part of a title that leads with its composer). New: a composed
  work's part with no Wikidata item of its own is checked by the item of the work it is a part of
  (`ResolvedWork.whole`, the work's `parts` parent when it has only one, given with the work's own lookup;
  `IdentityCheck.wholeItems`), tried last, after the articles found by name, so a movement's own
  article (a Bach Air's) still comes before its suite's. K. 545's Andante, which has neither, takes its
  sonata's (Q991701, found by its MusicBrainz work id `a2ad223d`, P435), with *Piano Sonata No. 16
  (Mozart)*'s lead and Wikidata's date line ("Piano Sonata No. 16 was created on June 26, 1788."): one
  more free Wikidata search, only for such a part with nothing else confirmed. The search in the
  track's language reads only the items MusicBrainz links, as before. Wikipedia's extraction version is 7 for it. No paid search is added.
- **When the song's own lines only list it** (`SongListings`), the song's evidence on such a track takes
  the composers' artist-level lines for the music and the performers' for this recording (before, the
  first credited artist's, the composer's alone), each named in the prompt as a `composer` or a
  `performer` line (`PARTS_PEOPLE_RULE`), never a claim about the work or the recording; and the rule
  on a gap is the usual one, not the fixed release sentence of #958.
- **LRCLIB** is asked by the first credited artist as before and, when that finds nothing and the roles
  tell a composer apart, again by the lead performer (`LibraryQuery.byLead`), each answer kept under its
  own query: one more free request, on such a track only (*Nessun dorma* is filed under Puccini and
  Pavarotti alike).

## Filed from this spike

- #1178: a performance of a composed work is called a cover of its earliest recording by someone else.
- #1179: every source but MusicBrainz searches with the composer and the movement in the title.
- #1180: tell the composer from the performers once resolved (section 2), and search and prompt by
  the performers.
- #1181: which artist the Artist panel is about (section 3; a decision).
