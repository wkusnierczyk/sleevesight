# Web search: recorded Exa responses

Recorded 2026-10-14 from Exa's `POST https://api.exa.ai/search` for the web search adapter's tests
(`ExaWebSearch`, #40), one pair per track of the fixture playlist (`../../below-soundtrack/`):

| Files | Track | Case |
|---|---|---|
| `exa-01-sundown.*` | 1 Sundown, Gordon Lightfoot | Rich results; more kept than the adapter's cap |
| `exa-12-bad-energy.*` | 12 Bad Energy, Reem | Two about Reem's song among other artists' "Bad Energy" |
| `exa-23-fishin-in-a-dory.*` | 23 Fishin' In A Dory | Thin track: a few listings name it, the rest are other recordings of the song |
| `exa-30-the-veil-between-worlds.*` | 30 THE VEIL BETWEEN WORLDS, DIE HEXEN | Thin track: two listings among other works of the same name |
| `exa-77-forever-young.*` | 77 Forever Young, dearoldfriend and Winston Hunte | Every result is another artist's song: all rejected |

- `*.request.json` is the body the adapter sends, as JSON (a test checks it). The
  key went in the `x-api-key` header, which is not recorded.
- `*.response.json` keeps only what the adapter reads: each result's `url`, `title` and
  `highlights`. Exa's `requestId`, cost, timings and each result's id, image, favicon, author and
  date are dropped.

**Third-party text.** The highlights are short extracts of the pages at their URLs, which belong to
their publishers; each stays with its URL. Exa's terms of service (§4.2(a)) restrict copying or
distributing information obtained through its services beyond what the terms or Exa permit in
writing, so these recordings are kept to the minimum the tests need: five searches, 300 characters
per highlight. Before the repository is made public, either confirm with Exa that test recordings
are acceptable or replace them with synthetic responses of the same shape (`design/design.md`, D11).
