# OpenAI

Responses of OpenAI's API, for the `:ai` provider's tests (`OpenAiProviderTest`). They are not
recordings: they were built on 2026-10-21 from the examples and schemas of OpenAI's API reference,
since no key was at hand. They are to be replaced by recordings of a live run with the developer's
key (`OpenAiLiveTest` checks the same request against the real API).

| File | Request | Built from |
|---|---|---|
| `responses-stream.sse` | `POST /v1/responses`, `stream: true`, `store: false`, model `gpt-6-luna`, `max_output_tokens` 256, `reasoning.effort` `low`, the input "Say hello in three words." | The [streaming events](https://developers.openai.com/api/reference/resources/responses/streaming-events) reference: each event's example, in the order a text response with a reasoning item sends them, as server-sent events with `event:` and `data:` lines. The ids (`resp_example`, `rs_example`, `msg_example`), the times, the text and the token counts are made up |
| `models.json` | `GET /v1/models` | The [list models](https://developers.openai.com/api/reference/resources/models/methods/list) reference's example, with model ids from the [models](https://developers.openai.com/api/docs/models) and [pricing](https://developers.openai.com/api/docs/pricing) pages: text models and ones the client leaves out (embeddings, speech, images, moderation, search, deep research, instruct). The shutdown dates are those of the [deprecations](https://developers.openai.com/api/docs/deprecations) page; the creation times are made up |

They hold no key and no account data.
