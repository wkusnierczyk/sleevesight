# Recorded HTTP responses

Real responses of the keyless sources for the fixture playlist's regression cases (`design/design.md`,
*Test fixtures*), which the adapter tests replay so CI never calls a live service
(`design/architecture.md`, *Testing*). Only what the tests need is recorded: the requests the code
makes for the first occurrence of each regression track, and for the tracks on which the Phase 0.5
gate run found resolution errors (`RecordedTracks` in `:sources`' tests).

| Directory | Source | Licence of the content |
|---|---|---|
| `musicbrainz/` | MusicBrainz web service, `https://musicbrainz.org/ws/2` | Core data, CC0 ([Data License](https://musicbrainz.org/doc/About/Data_License)). Requested without `inc=genres`, and without `inc=tags` but on a recording's lookup, which asks for its tags for the track's genre labels (#439); the `tags` and `genres` fields (supplementary data, CC BY-NC-SA 3.0), asked for or returned unasked, are removed when recording, and nothing else is changed |
| `musicbrainz-906/` | MusicBrainz web service: the recording search for *Dream On* by Aerosmith (#906), recorded by hand on 2026-11-04, not by `RecordHttpFixtures` or `:cli record`: a first page from a request whose ranking held none of the 1973 studio recording (the ranking varies from one request to the next), and the search for those released by 1978-10, the earliest date on it that could be chosen | As `musicbrainz/`, `tags` and `genres` removed |
| `musicbrainz-1065/` | MusicBrainz web service: the recording search for *Beat It* by Michael Jackson (#1065), recorded by hand on 2026-11-08, not by `RecordHttpFixtures` or `:cli record`: a first page of 100 of its 107 recordings, with *Thriller 25 Super Deluxe Edition*'s *Beat It* (first released on *Thriller* in 1982, the reissue in the same release group) and its *Beat It 2008*, and the search for those released by 1982-11-30, the earliest date on it that could be chosen | As `musicbrainz/`, `tags` and `genres` removed |
| `wikidata/` | Wikidata action API, `https://www.wikidata.org/w/api.php` | CC0 ([Wikidata licensing](https://www.wikidata.org/wiki/Wikidata:Licensing)); stored as received |
| `wikipedia/` | English Wikipedia action API, `https://en.wikipedia.org/w/api.php` | Article text: Wikipedia contributors, [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); each response holds the article titles, so the articles and their histories, which credit the authors, can be found at `https://en.wikipedia.org/wiki/<title>`. Stored as received |

Each directory has an `index.json`: one entry per request (method, URL, query) with the status and
the file of its response. `ReplayNetClient` in `:sources`' test fixtures serves them, matching a
request exactly; a request with no recording fails the test, naming it.

## Recording

Live, keyless, through the real `NetClient` and its rate limits (MusicBrainz at one request a second,
with the app's User-Agent naming the repository as contact):

```
SLEEVESIGHT_RECORD_HTTP=musicbrainz,wikidata,wikipedia ./gradlew :sources:test --tests '*RecordHttpFixtures*' --rerun
python3 -I fixtures/http/record.py sources/build/test-results/test --prune
```

`RecordHttpFixtures` runs the same lookups as the tests through `RecordingNetClient`, which prints
each exchange to the test report and refuses any request carrying credentials; `record.py` writes
them here and updates the indexes; `--prune` then drops the recorded sources' requests the run no
longer made. Prune only after a run whose test passed: a lookup that failed was not recorded, and
pruning would remove its old recording. Without the variable the test does nothing. Re-recording changes
responses as the sources' data changes, so the tests' expectations are checked again after it.

Recorded 2026-10-14; track 50 on 2026-10-16, with `:cli record` (#79); the releases of track 63's original
(`inc=releases+release-groups`, a cover's one more lookup) on 2026-10-31 (#724). Track 55's search by date (`firstreleasedate:[* TO 1956-05]`) and its recording `ffe4a448…` on 2026-11-04 with `:cli record` (#906); its first page and work kept from before. The releases of the recordings of *The Veil Between Worlds* (Die Hexen) and *Here Comes The Sun* (Alexander S.) (`inc=releases+release-groups`, `recording-…-2.json`), the tracklists' recorded language the search in the track's language asks for (#902), on 2026-11-05, by hand as `record.py` writes them (neither holds `tags` or `genres`).

### With the CLI

`:cli record` (architecture, *Testing*) makes the pipeline's own requests for the playlist positions
given, live for the sources named and replayed for the others, and writes what the live ones answer
here, replacing earlier recordings of the same requests and updating the indexes as `record.py`
does (MusicBrainz without `tags` and `genres`):

```
./gradlew :cli:run --args="record --record musicbrainz,wikidata,wikipedia --tracks regression"
./gradlew :cli:run --args="replay"
```

It does not prune. Web search is never recorded: Exa's results are not committed (`web/README.md`).
`--record anthropic` records the model's replies to the pipeline's prompts, with the developer's key
in the environment, into `anthropic/` (an `index.json` whose entries also carry the request body's
SHA-256, `bodySha256`, since every prompt goes to the same URL; each reply as `messages-<hash>.sse`
with its request body beside it, the message id replaced, and no header kept). `--record google`
(which runs Google) does the same for Google's replies, into `google/` (the response id replaced);
the provider tests' stream there is one such recording, kept apart from the replay's index
(`google/README.md`). No recording for the replay is committed yet: the first ones (tracks 1 and 63,
2026-10-14) would fail the gate's uncited criterion on a sentence the check splits at "U.S." (#46),
and a re-recorded source changes the prompts they answer. A reply the replay does not find is
reported, not fatal; a source's request it does not find fails the run.
