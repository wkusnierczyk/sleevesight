# Below Soundtrack

The playlist "Below Soundtrack Netflix" with frozen lookups, the fixture of the pipeline's tests
and of the `:cli` (`design/design.md`, *Test fixtures*; `design/architecture.md`, *Testing*).

The three files are the simulator's JSON blocks, unchanged. `design/simulator.html` still embeds
its own copy, so it runs from a single file; a test in `:core` fails if the two disagree, so a
change to one is made to both.

| File | Simulator block | Holds |
|---|---|---|
| `playlist.json` | `playlist-data` | The playlist as the player shows it: name, owner, Spotify URI, and the tracks in order, duplicates included. Each track: position `n` from 1, `title` and `artist` as the player reports them, duration `dur`, and `explicit` |
| `facts.json` | `facts-data` | One record per unique track, by its first position: the frozen lookups from Wikipedia (`status`, the article and its fact lines), Last.fm (`status`, listeners, tags, similar artists, bio facts) and web search (the query and each result's fact lines), with notes on the case. The statuses are the expected outcomes of the resolution tests; the fact lines are reference facts, written in advance, which extraction should find by content, not wording |
| `albums.json` | `albums-data` | One record per unique track: the release as the player files it (album, year, and where that came from), and the Spotify track, album and artist links |

JVM tests read them through the loader in `:core`'s test fixtures, `BelowSoundtrack`, with
`testImplementation(testFixtures(project(":core")))`. The design's regression cases are listed by
track in `RegressionCase`, for the tests that cover them to name.
