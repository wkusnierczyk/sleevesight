# Recorded HTTP responses

Real responses of the keyless sources for the fixture playlist's regression cases (`design/design.md`,
*Test fixtures*), which the adapter tests replay so CI never calls a live service
(`design/architecture.md`, *Testing*). Only what the tests need is recorded: the requests the code
makes for the first occurrence of each regression track (`RecordedTracks` in `:sources`' tests).

| Directory | Source | Licence of the content |
|---|---|---|
| `musicbrainz/` | MusicBrainz web service, `https://musicbrainz.org/ws/2` | Core data, CC0 ([Data License](https://musicbrainz.org/doc/About/Data_License)). Requested without `inc=tags` or `inc=genres`; the `tags` and `genres` fields a search returns unasked (supplementary data, CC BY-NC-SA 3.0) are removed when recording, and nothing else is changed |
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

Recorded 2026-10-14. `:cli record` (architecture, *Testing*) is to take this over.
