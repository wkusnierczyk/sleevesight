# Google

Responses of Google's Gemini API for the `:ai` provider's tests (`GoogleProviderTest`).

The stream is **recorded**: on 2026-10-21, with the developer's key, by
`:cli record --record google --tracks 1` (the pipeline's prompt for "Sundown", replayed sources,
no Last.fm or web search), model `gemini-3.5-flash-lite` at effort `low`. The model lists are
**not recorded**, but built on 2026-10-21 from the API reference's schemas
([`models.list`](https://ai.google.dev/api/models), `Model`) and the
[models page](https://ai.google.dev/gemini-api/docs/models) for the ids.

| File | Request | Recorded or built as |
|---|---|---|
| `stream-generate-content.sse` | `POST /v1/models/gemini-3.5-flash-lite:streamGenerateContent?alt=sse`, `thinkingLevel` `low`, `maxOutputTokens` 4096, the pipeline's prompt for track 1 | Recorded. The response id is `resp_recorded` (by the recorder), and the last part's `thoughtSignature`, an opaque blob of about 6 KB, is the placeholder `c2lnbmF0dXJl` |
| `models-page-1.json` | `GET /v1/models?pageSize=1000` | Two text models, an embedding model and an image model that takes `generateContent`, with a `nextPageToken` |
| `models-page-2.json` | `GET /v1/models?pageSize=1000&pageToken=…` | Two text models, a speech model that takes `generateContent` and a video model, without a `nextPageToken` |

In the model lists, the ids are the models page's, and the page token is made up. Only bodies are
kept: no request headers (the key), and no response headers. The stream is the server's bytes as
sent, but for those two values: six chunks, each a `GenerateContentResponse` with the usage so far
(the thinking tokens from the first), the last with `finishReason` `STOP` and an empty text part
carrying the signature.
