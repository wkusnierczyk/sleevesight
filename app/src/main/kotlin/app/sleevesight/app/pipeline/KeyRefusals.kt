package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.platform.JvmHasher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * The AI services whose saved key the service refused (#1157): a call, or the model list, answered
 * [AiErrorKind.UNAUTHORIZED], as OpenRouter does for a key deleted there. *AI services* says so
 * under OpenRouter, and offers the sign-in again. A mark is kept with the key it was made with, in
 * memory only, as a fingerprint: a call with that key that succeeds lifts it, and so does another key
 * saved, or the key removed ([keysChanged]). Each start begins with none.
 *
 * The keys saved are known from [keysChanged] on: a refusal that comes back for a key no longer the
 * one saved (a call made with the key before, answered after another was saved) marks nothing. The
 * marks and the keys saved change together, in one state, so no refusal can slip between them.
 */
class KeyRefusals {
    /** The marks, by service, each with its key's fingerprint; and the keys saved, as last read. */
    private data class Book(
        val marks: Map<ProviderId, String> = emptyMap(),
        val saved: Map<ProviderId, String?> = emptyMap(),
    ) {
        // Whether [print] is not [provider]'s key saved, as far as it is known.
        fun stale(
            provider: ProviderId,
            print: String,
        ): Boolean = provider in saved && saved[provider] != print
    }

    private val book = MutableStateFlow(Book())

    /** The services whose saved key was refused. */
    val refused: Flow<Set<ProviderId>> = book.map { it.marks.keys }.distinctUntilChanged()

    /** [provider], with its refusals heard: its calls' ends and its model lists. */
    fun watching(provider: AiProvider): AiProvider =
        object : AiProvider by provider {
            override suspend fun models(key: Secret): ModelList =
                provider.models(key).also { list ->
                    if (list is ModelList.Failed && list.kind == AiErrorKind.UNAUTHORIZED) mark(provider.id, key)
                    if (list is ModelList.Listed) lift(provider.id, key)
                }

            override fun stream(
                request: CompletionRequest,
                key: Secret,
            ): Flow<CompletionEvent> =
                provider.stream(request, key).onEach { event ->
                    when {
                        event is CompletionEvent.Error && event.kind == AiErrorKind.UNAUTHORIZED -> {
                            mark(provider.id, key)
                        }

                        event is CompletionEvent.Done -> {
                            lift(provider.id, key)
                        }
                    }
                }
        }

    /**
     * The saved keys changed: each service's key is read ([key], null when none is saved), and a mark
     * whose key is no longer the one saved is lifted.
     */
    suspend fun keysChanged(key: suspend (ProviderId) -> Secret?) {
        for (provider in ProviderId.entries) {
            val print = key(provider)?.let(::fingerprint)
            book.update { now ->
                val marks = now.marks.filterNot { (marked, refused) -> marked == provider && refused != print }
                now.copy(marks = marks, saved = now.saved + (provider to print))
            }
        }
    }

    // [provider]'s key [key] refused: marked, unless another key is saved by now.
    private fun mark(
        provider: ProviderId,
        key: Secret,
    ) {
        val print = fingerprint(key)
        book.update { if (it.stale(provider, print)) it else it.copy(marks = it.marks + (provider to print)) }
    }

    private fun lift(
        provider: ProviderId,
        key: Secret,
    ) = lift(provider, fingerprint(key))

    // [provider]'s mark, if it was made with the key [print].
    private fun lift(
        provider: ProviderId,
        print: String,
    ) = book.update { if (it.marks[provider] == print) it.copy(marks = it.marks - provider) else it }

    private companion object {
        // A key's SHA-256, to tell the key refused from the one saved now; in memory only.
        fun fingerprint(key: Secret): String =
            JvmHasher.sha256(key.reveal().encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
