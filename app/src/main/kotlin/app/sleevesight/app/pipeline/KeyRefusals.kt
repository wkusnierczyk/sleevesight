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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * The AI services whose saved key the service refused (#1157): a call, or the model list, answered
 * [AiErrorKind.UNAUTHORIZED], as OpenRouter does for a key deleted there. *AI services* says so
 * under OpenRouter, and offers the sign-in again. A mark is kept with the key it was made with, in
 * memory only, as a fingerprint: a call with that key that succeeds lifts it, and so does another key
 * saved, or the key removed ([keysChanged]). Each start begins with none.
 */
class KeyRefusals {
    private val marks = MutableStateFlow<Map<ProviderId, String>>(emptyMap())

    /** The services whose saved key was refused. */
    val refused: Flow<Set<ProviderId>> = marks.map { it.keys }

    /** The marks, with the key each was made with, for tests. */
    internal val now: StateFlow<Map<ProviderId, String>> = marks.asStateFlow()

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
     * The saved keys changed: a mark whose service's key ([key], null when none is saved) is no
     * longer the one refused is lifted.
     */
    suspend fun keysChanged(key: suspend (ProviderId) -> Secret?) {
        for ((provider, print) in marks.value) {
            val saved = key(provider)
            // Only the mark read: one made since, with the key now saved, stays.
            if (saved == null || fingerprint(saved) != print) lift(provider, print)
        }
    }

    private fun mark(
        provider: ProviderId,
        key: Secret,
    ) {
        val print = fingerprint(key)
        marks.update { it + (provider to print) }
    }

    private fun lift(
        provider: ProviderId,
        key: Secret,
    ) = lift(provider, fingerprint(key))

    // [provider]'s mark, if it was made with the key [print].
    private fun lift(
        provider: ProviderId,
        print: String,
    ) = marks.update { if (it[provider] == print) it - provider else it }

    private companion object {
        // A key's SHA-256, to tell the key refused from the one saved now; in memory only.
        fun fingerprint(key: Secret): String =
            JvmHasher.sha256(key.reveal().encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
