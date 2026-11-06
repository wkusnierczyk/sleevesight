package app.sleevesight.app.pipeline

import android.util.Log
import app.sleevesight.app.BuildConfig
import app.sleevesight.app.session.SessionDump
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ToolExchange
import app.sleevesight.core.ai.ToolTurn
import app.sleevesight.core.model.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Debug builds only: logs every request the app sends an AI service and what came back, under
 * [TAG], for comparing the app's prompts with others sent from the command line (#767,
 * `sleevesight-cli prompt`). Read it with `adb logcat -s SleevesightPrompt -v raw`. A release
 * build logs nothing of this, and [watching] hands back the provider itself.
 *
 * Each request gets a number, from 1 at the process's start, and each record is
 * `#<number> <section> <part>/<parts> <text>`, the sections in this order: `request` (the
 * provider, model, token limit, effort, tools and the time, as `name=value` pairs), `system` (when
 * the request has one), `prompt`, `results` (the tools' answers sent back, after tool use), then
 * `calls` (a turn that called tools), `reply` (the text as it came), and one of `done` (the tokens,
 * the stop reason and the time taken), `error` (its kind, and how a reply broke off) or `cancelled`
 * (the collector stopped first). Requests may interleave, each record carrying its number. A text's
 * backslashes and line breaks are escaped (`\\`, `\n`, `\r`), so each record is one line, and the
 * text is then cut in numbered parts ([SessionDump.parts]), since logcat cuts a record of about
 * 4 kB: join a section's parts in order, then unescape. The key is never logged.
 *
 * Records are logged on a thread of their own, in order, so no call waits on logcat.
 */
internal object PromptDump {
    const val TAG = "SleevesightPrompt"

    private val enabled: Boolean = BuildConfig.DEBUG
    private val worker by lazy { Executors.newSingleThreadExecutor { Thread(it, TAG).apply { isDaemon = true } } }
    private val requests = AtomicInteger()

    /** [provider], each call through it logged (debug builds); in a release build, [provider] itself. */
    fun watching(provider: AiProvider): AiProvider = if (enabled) Watched(provider) else provider

    private class Watched(
        private val provider: AiProvider,
    ) : AiProvider by provider {
        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> =
            flow {
                val number = requests.incrementAndGet()
                val started = TimeSource.Monotonic.markNow()
                log(requestRecords(number, provider.id, request, Clock.System.now().toString()))
                val reply = StringBuilder()
                var ended = false
                try {
                    provider.stream(request, key).collect { event ->
                        when (event) {
                            is CompletionEvent.Delta -> reply.append(event.text)
                            is CompletionEvent.ToolUse -> log(records(number, "calls", calls(event.turn)))
                            is CompletionEvent.Done, is CompletionEvent.Error -> {
                                ended = true
                                val ms = started.elapsedNow().inWholeMilliseconds
                                log(records(number, "reply", reply.toString()) + endRecords(number, event, ms))
                            }
                        }
                        emit(event)
                    }
                } finally {
                    if (!ended) {
                        val ms = started.elapsedNow().inWholeMilliseconds
                        log(records(number, "reply", reply.toString()) + records(number, "cancelled", "ms=$ms"))
                    }
                }
            }
    }

    private fun log(lines: List<String>) {
        worker.execute { lines.forEach { Log.d(TAG, it) } }
    }

    /** The records that open request [number]: its details, then its system prompt, prompt and tools' answers. */
    fun requestRecords(
        number: Int,
        provider: ProviderId,
        request: CompletionRequest,
        at: String,
    ): List<String> {
        val details =
            listOf(
                "provider=${provider.name.lowercase()}",
                "model=${request.model}",
                "maxTokens=${request.maxTokens}",
                "effort=${request.effort?.name?.lowercase() ?: "default"}",
                "tools=${request.tools.joinToString(",") { it.name }.ifEmpty { "none" }}",
                "toolsAllowed=${request.toolsAllowed}",
                "exchanges=${request.exchanges.size}",
                "at=$at",
            ).joinToString(" ")
        return records(number, "request", details) +
            (request.system?.let { records(number, "system", it) } ?: emptyList()) +
            records(number, "prompt", request.prompt) +
            request.exchanges
                .takeIf { it.isNotEmpty() }
                ?.let { records(number, "results", results(it)) }
                .orEmpty()
    }

    /** The record that ends a request with [event], a [CompletionEvent.Done] or [CompletionEvent.Error]. */
    fun endRecords(
        number: Int,
        event: CompletionEvent,
        ms: Long,
    ): List<String> =
        when (event) {
            is CompletionEvent.Done -> {
                records(
                    number,
                    "done",
                    "inputTokens=${event.usage.inputTokens} outputTokens=${event.usage.outputTokens} " +
                        "stopReason=${event.stopReason.name.lowercase()} ms=$ms",
                )
            }

            is CompletionEvent.Error -> {
                val breakoff =
                    event.breakoff?.let {
                        " breakoff=${it.cause.name.lowercase()} inputTokens=${it.usage.inputTokens} " +
                            "outputTokens=${it.usage.outputTokens}" +
                            (it.detail?.let { detail -> " detail=${detail.replace(WHITESPACE, "_")}" } ?: "")
                    } ?: ""
                val wait = event.retryAfter?.let { " retryAfter=$it" } ?: ""
                records(number, "error", "kind=${event.kind.name.lowercase()}$wait$breakoff ms=$ms")
            }

            else -> {
                emptyList()
            }
        }

    /** [text] as the records of [section] of request [number]: escaped, then in parts. */
    fun records(
        number: Int,
        section: String,
        text: String,
    ): List<String> {
        val parts = SessionDump.parts(escape(text))
        return parts.mapIndexed { index, part -> "#$number $section ${index + 1}/${parts.size} $part" }
    }

    /** [text] on one line: backslashes, then line feeds and carriage returns, escaped as `\\`, `\n`, `\r`. */
    fun escape(text: String): String =
        text
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    // A turn's calls, one a line: the tool's name, the call's id and its input.
    private fun calls(turn: ToolTurn): String = turn.calls.joinToString("\n") { "${it.name} ${it.id} ${it.arguments}" }

    // The tools' answers of each exchange, each under a line naming its exchange and call.
    private fun results(exchanges: List<ToolExchange>): String =
        exchanges
            .flatMapIndexed { index, exchange ->
                exchange.results.map { result ->
                    val error = if (result.isError) " (error)" else ""
                    "--- exchange ${index + 1}, call ${result.callId}$error\n${result.content}"
                }
            }.joinToString("\n")

    private val WHITESPACE = Regex("\\s+")
}
