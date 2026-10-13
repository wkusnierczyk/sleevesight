# Last.fm stub responses

**Hand-written from Last.fm's API documentation, not recorded.** The project has no Last.fm API
key, so nothing here came from Last.fm's servers. Each file is the body Last.fm's
[`artist.getInfo`](https://www.last.fm/api/show/artist.getInfo) or
[`artist.getTopTags`](https://www.last.fm/api/show/artist.getTopTags) would send with
`format=json`, in the shape those pages, and Last.fm's [error codes](https://www.last.fm/api/errorcodes),
describe. The tests of `:sources`' Last.fm adapter read them (`LastFmStubClient`).

The bio text was written for these stubs, from the fixture's own reference facts
(`fixtures/below-soundtrack/facts.json`); it is not Last.fm's user-contributed text, so it carries
no CC BY-SA attribution. The listener counts follow the fixture where it has one; play counts, tag
weights and the remaining similar artists are illustrative. The MusicBrainz ids of Gordon Lightfoot
and Jesaiah are the artists' real ones (MusicBrainz core data, CC0); those of PeleBoy, Tony K and
Reem are made up (`00000000-0000-4000-8000-…`), for the tests that resolve them.

Replace a stub with a recording once a key exists, keeping the Last.fm prose under CC BY-SA with its
attribution and checking Last.fm's API terms on storing responses (`design/plan.md`, *Recorded
responses*). Until then, the shape these files assume is the thing a live smoke test checks first
(`LastFmLiveTest`, run only with `SLEEVESIGHT_LASTFM_API_KEY` set).

| File | Case |
|---|---|
| `artist.getinfo/gordon-lightfoot.json`, `artist.gettoptags/…` | Track 1: rich data, an MBID, a bio with HTML, entities, Last.fm's "Read more" link and, in `content`, its licence note |
| `artist.getinfo/peleboy.json`, `artist.gettoptags/…` | Track 14, first artist: the name in another case (`PeleBoy`), no tags, an empty bio |
| `artist.getinfo/tony-k.json`, `artist.gettoptags/…` | Track 14, second artist: a single tag, which Last.fm's XML-derived JSON sends as an object, not a list; a bio too short to quote; the tests resolve Tony K to another id, so it is rejected |
| `artist.getinfo/jessiah.json`, `artist.gettoptags/…` | Track 61: asked for `Jessiah`, Last.fm's autocorrection answers with `Jesaiah`, an unrelated Swedish metalcore band, with that band's MBID |
| `artist.getinfo/reem.json`, `artist.gettoptags/…` | Tracks 12 and 20: a page shared by several artists named Reem, whose bio is a disambiguation note; it carries an MBID, which still does not verify it |
| `artist.getinfo/not-found.json`, `artist.gettoptags/not-found.json` | Error 6, an artist Last.fm does not know |
| `invalid-key.json` | Error 10, a key Last.fm does not accept |
