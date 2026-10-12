# Sleevesight considerations

**Date:** 2026-10-12  
**Belongs to:** [architecture.md](architecture.md), decisions D2 and D3

The reasoning behind architecture choices that need more room than a row in the decisions table.

## D2. Plain Kotlin or Kotlin Multiplatform for the pipeline

"Plain Kotlin" means ordinary Kotlin libraries compiled for the JVM. Android apps run JVM bytecode, so the command-line tool and the app share them directly.

### Plain Kotlin

Pros:

- The simplest build. The libraries are standard Kotlin and Java ones, with no per-platform split in the source.
- Any JVM library works: OkHttp, the SQLite driver, JUnit 5.
- Faster builds and fewer build-plugin version clashes.
- Moving to Multiplatform later is cheap if `:core` stays pure Kotlin. The rules are no `java.*` APIs where a Kotlin one exists, and no Android imports.

Cons:

- The pipeline only ever runs on the JVM. No iOS, browser or native build.
- Java APIs can slip in by habit, such as `java.time` or `java.util.regex`. That is fine for now, but each one is work if Multiplatform comes later.

### Kotlin Multiplatform

Pros:

- The same pipeline could run in a browser (the simulator rebuilt from the real code) or as a native command-line tool.
- It keeps the door open to iOS if Apple ever lets one app read another's now-playing information.

Cons:

- More build complexity: target and source-set setup, `expect`/`actual` declarations, slower builds, rougher tooling.
- Only Multiplatform libraries can be used. Ktor, kotlinx.serialization and SQLDelight are, but the choice narrows, and regex and date handling need care.
- The design rules out iOS, so today it buys nothing.

### Recommendation

Plain Kotlin, with `:core` kept clean enough that a switch later is a build change, not a rewrite. The one thing that would change this is wanting the browser simulator to run the real pipeline.

## D3. Small clients or vendor SDKs for the AI providers

The app needs two calls per provider: stream a completion, and list the available models.

### Small clients

Pros:

- One HTTP stack for everything (`:net`). Rate limits, timeouts, the logger that hides keys, and the network rule are enforced in one place.
- A small dependency list. Each SDK brings its own HTTP client, JSON library and transitive packages, and every one goes through the allowlist and needs a GPL-compatibility check.
- One way of handling errors, retries and cancellation across all four providers, which the pipeline relies on.
- Recorded-response tests work the same for every provider.
- Smaller APK, and nothing tied to SDK release schedules.

Cons:

- We write and maintain the streaming parsers. Anthropic, OpenAI and Google each have their own server-sent event format, and Ollama sends JSON lines. That is roughly a few hundred lines in total.
- API changes are ours to follow; an SDK would absorb some of them.
- Features beyond basic streaming, such as structured output, prompt caching and the provider-specific usage counts, have to be wired by hand.

### Vendor SDKs

Pros:

- Officially supported, typed, and they keep up with new API features.
- Less code to write at the start.

Cons:

- Each has its own HTTP stack. That breaks the single network boundary, and the dependency, manifest and module checks have to account for each one.
- Some are Java-first, with their own async models, which fit awkwardly with coroutine cancellation.
- Behaviour differs between providers, so the pipeline needs a layer over them anyway, which is most of the work of writing the small clients.

### Recommendation

Small clients. The surface used is small and stable, and keeping network access in `:net` is one of the design's structural constraints. If one provider later needs a feature that is painful to hand-roll, adding its SDK just for that is a contained exception.
