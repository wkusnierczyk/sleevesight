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
 * Each refusal is marked with the key its request used, whatever order the answers and the saves
 * come in, and a service is said refused only while its mark's key is the one saved, as
 * [keysChanged] last read it (or before it has read any): a refusal of the key before, answered after
 * another was saved, says nothing of the new one; a refusal of the new key, answered before
 * [keysChanged] has read it, is said once it has. The marks and the keys saved change together, in
 * one state.
 */
class KeyRefusals {
    /** The marks, each a service and its refused key's fingerprint; and the keys saved, as last read. */
    private data class Book(
        val marks: Set<Pair<ProviderId, String>> = emptySet(),
        val saved: Map<ProviderId, String?> = emptyMap(),
    ) {
        // The services whose key saved, as far as it is known, has a mark.
        fun refused(): Set<ProviderId> =
            marks.filter { (provider, print) -> provider !in saved || saved[provider] == print }.mapTo(mutableSetOf()) {
                it.first
            }
    }

    private val book = MutableStateFlow(Book())

    /** The services whose saved key was refused. */
    val refused: Flow<Set<ProviderId>> = book.map { it.refused() }.distinctUntilChanged()

    /**
     * [provider], with its refusals heard: its calls' ends and its model lists. Only a call that
     * completes lifts a mark: a model list is no proof, since OpenRouter's is public and lists
     * whatever key is sent.
     */
    fun watching(provider: AiProvider): AiProvider =
        object : AiProvider by provider {
            override suspend fun models(key: Secret): ModelList =
                provider.models(key).also { list ->
                    if (list is ModelList.Failed && list.kind == AiErrorKind.UNAUTHORIZED) mark(provider.id, key)
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
                // A mark of a key no longer saved can never be said again.
                val marks = now.marks.filterNot { (marked, refused) -> marked == provider && refused != print }.toSet()
                now.copy(marks = marks, saved = now.saved + (provider to print))
            }
        }
    }

    // [provider]'s key [key], the one its request used, refused.
    private fun mark(
        provider: ProviderId,
        key: Secret,
    ) {
        val print = fingerprint(key)
        book.update { it.copy(marks = it.marks + (provider to print)) }
    }

    private fun lift(
        provider: ProviderId,
        key: Secret,
    ) = lift(provider, fingerprint(key))

    // [provider]'s mark, if it was made with the key [print].
    private fun lift(
        provider: ProviderId,
        print: String,
    ) = book.update { it.copy(marks = it.marks - (provider to print)) }

    private companion object {
        // A key's SHA-256, to tell the key refused from the one saved now; in memory only.
        fun fingerprint(key: Secret): String =
            JvmHasher.sha256(key.reveal().encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
