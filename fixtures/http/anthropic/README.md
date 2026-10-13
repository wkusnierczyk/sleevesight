# Anthropic

Recorded responses of Anthropic's API, for the `:ai` provider's tests (`AnthropicProviderTest`),
recorded on 2026-10-14 with the developer's key.

| File | Request | Changed after recording |
|---|---|---|
| `messages-stream.sse` | `POST /v1/messages`, `stream: true`, model `claude-haiku-5-5`, `max_tokens` 256, the prompt "Say hello in three words." | The message id is `msg_recorded` |
| `models-page-1.json` | `GET /v1/models?limit=10` | Nothing |
| `models-page-2.json` | `GET /v1/models?limit=10&after_id=claude-sonnet-4-6` | Nothing |

Only bodies are kept: no request headers (the key), and no response headers (the request id, the
organization and workspace ids, the account's rate limits). The bodies hold no account data. The
model list is the one the key could see that day.

The stream is the server's bytes as sent, padding inside the JSON included.
