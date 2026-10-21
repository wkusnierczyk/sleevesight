# Google

Responses of Google's Gemini API for the `:ai` provider's tests (`GoogleProviderTest`). They are
**not recorded**: no key was at hand when the client was written (#229), so they are built, on
2026-10-21, from the API reference's schemas and the guides' examples:
[`models.streamGenerateContent`](https://ai.google.dev/api/generate-content) (`GenerateContentResponse`,
`Candidate`, `UsageMetadata`), [`models.list`](https://ai.google.dev/api/models) (`ListModelsResponse`,
`Model`), and the [models page](https://ai.google.dev/gemini-api/docs/models) for the ids. To be
replaced by recordings from a live run with the developer's key, as `anthropic/` was.

| File | Request | Built as |
|---|---|---|
| `stream-generate-content.sse` | `POST /v1/models/gemini-3.5-flash-lite:streamGenerateContent?alt=sse`, the prompt "Say hello in three words." | Three chunks, each a `GenerateContentResponse` with the usage so far; the last with `finishReason` `STOP`, the thinking tokens and a `thoughtSignature` on its part, as the thinking guide describes |
| `models-page-1.json` | `GET /v1/models?pageSize=1000` | Two text models and an embedding model, with a `nextPageToken` |
| `models-page-2.json` | `GET /v1/models?pageSize=1000&pageToken=…` | Two text models and a video model, without a `nextPageToken` |

The ids are the models page's; the token counts, the page token, the response id and the signature
are made up.
