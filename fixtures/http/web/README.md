# Web search: synthetic Exa responses

Test fixtures for the web search adapter (`ExaWebSearch`, #40), one pair per track of the fixture
playlist (`../../below-soundtrack/`).

**The responses are synthetic.** Exa's terms of service (§4.2(a)) forbid copying, reproducing or
distributing information obtained through its services without its written permission, so no
text Exa returned is kept here. Each response has the shape of Exa's `POST /search` answer, cut to
the fields the adapter reads (each result's `url`, `title` and `highlights`), but the titles, URLs
(on `.example` domains, except one Wikipedia URL that tests the exclusion of the pipeline's own
sources) and highlights were written by the project, modelled on the kinds of result the live
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
