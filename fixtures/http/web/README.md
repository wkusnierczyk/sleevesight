# Web search: synthetic Exa responses

Test fixtures for the web search adapter (`ExaWebSearch`, #40), one pair per track of the fixture
playlist (`../../below-soundtrack/`).

**The responses are synthetic.** Exa's terms of service (§4.2(a)) forbid copying, reproducing or
distributing information obtained through its services without its written permission, so no
text Exa returned is kept here. Each response has the shape of Exa's `POST /search` answer, cut to
the fields the adapter reads (each result's `url`, `title` and `highlights`), but the titles, URLs
(on `.example` domains, except one Wikipedia URL that tests the exclusion of the pipeline's own
sources, and the boilerplate fixture's pages on their sites' hosts, below) and highlights were written by the project, modelled on the kinds of result the live
spike returned (`design/design.md`, *Web search provider (D11)*). They are not facts about the
recordings. Live behaviour is checked by `ExaLiveTest`, which runs only with a key and keeps nothing.

`*.request.json` is the body the adapter sends, as JSON (a test checks it); the key goes in the
`x-api-key` header and is in no file.

| Files | Track | Case |
|---|---|---|
| `exa-01-sundown.*` | 1 Sundown, Gordon Lightfoot | Rich results: more pass than the cap of five; a Wikipedia result and another artist's "Sundown" are dropped |
| `exa-12-bad-energy.*` | 12 Bad Energy, Reem | Reem's song among other artists' "Bad Energy", and an undecodable page titled after the artist |
| `exa-23-fishin-in-a-dory.*` | 23 Fishin' In A Dory | Thin track: listings that name it (one spells the artist with `&`), an artist page, the traditional song, another recording |
| `exa-30-the-veil-between-worlds.*` | 30 THE VEIL BETWEEN WORLDS, DIE HEXEN | Thin track: two listings among another work of the same name and the artist's album page |
| `exa-34-theme.*` | 34 Theme - From "Coronation Street", London Music Works | The same artist's other themes are dropped |
| `exa-45-i-will-follow-you-into-the-dark.*` | 45 I Will Follow You Into The Dark, Yanjoco | A page for another of the artist's songs that lists this one is dropped |
| `exa-63-baby-please-dont-go.*` | 63 Baby, Please Don't Go, Them and Van Morrison | A version that says "them" but not Van Morrison is dropped |
| `exa-77-forever-young.*` | 77 Forever Young, dearoldfriend and Winston Hunte | Every result is another artist's song: all rejected |
| `exa-1179-k545-andante.*` | Not of the playlist (#1179): *Mozart: Piano Sonata No. 16 in C Major, K. 545 "Sonata facile": II. Andante*, Wolfgang Amadeus Mozart, Khatia Buniatishvili and Academy of St. Martin in the Fields, as Spotify gives it | A title that leads with its composer: searched without the lead and by its work; a page on the sonata that names a performer and one that names the composer are kept, as is a listing under the whole title; a page on another sonata titled after the performer, and Wikipedia, are dropped |
| `exa-01-sundown-boilerplate.response.json` | 1 Sundown, Gordon Lightfoot | Page boilerplate (#905, `WebBoilerplateTest`): parts that are only boilerplate, which go, and parts with a fact beside it, which stay whole; one page of each kind the #767 phone round met on thin tracks (a video site's footer and channel stats, a streaming service's navigation, a store's rights line, shows and supporters, a chord sheet, a lyrics site, a transcript, a rights notice). Each page is on its site's real host (YouTube, Spotify, Bandcamp, Ultimate Guitar, Chordu, Cifra Club), since the rules run by the result's site, at a made-up path; the sites' fixed text (a footer, navigation, field names) is as those pages had it, since the rules match it; every other word is the project's. No request file: it is the track's request |
