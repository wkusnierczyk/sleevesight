package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [provider]'s model [model], which the service does not list for the key, or refused (#540). */
data class UnavailableModel(
    val provider: ProviderId,
    val model: String,
)

/**
 * Whether each AI service's selected model (its default, when none is chosen) can be used (#540):
 * a model the service retired, or one the account cannot use, would otherwise fail every insight.
 * A model is found unavailable when the service's model list for the saved key does not hold it
 * ([listed]: the check at start, [run], and each opening of the model choice), and when a call
 * names it and the service answers that it does not know it or refuses it for the account
 * ([AiErrorKind.MODEL_NOT_FOUND], [AiErrorKind.MODEL_REFUSED]; [watching]). Nothing else counts: a
 * list that cannot be had (offline, the key refused) says nothing of the model.
 *
 * A model found unavailable stays so until the user chooses another for the service, or a later
 * list holds it again: no other model is used in its place, and the pipeline writes no insight with
 * it ([blocks]); the settings, under the service, and Home say so. Kept in memory only: each start
 * checks again.
 *
 * @param providers the services' clients, as the pipeline and the settings use them.
 * @param key the saved key of a service, or null when none is saved or it cannot be read.
 */
class ModelCheck(
    private val providers: Map<ProviderId, AiProvider>,
    private val key: suspend (ProviderId) -> Secret?,
) {
    private val found = MutableStateFlow<Map<ProviderId, String>>(emptyMap())

    /** Each service's model found unavailable, by service. */
    val unavailable: StateFlow<Map<ProviderId, String>> = found.asStateFlow()

    /** [provider]'s selected model in [settings], if it was found unavailable. */
    fun of(
        settings: Settings,
        provider: ProviderId,
    ): UnavailableModel? =
        settings.model(provider)?.takeIf { found.value[provider] == it }?.let { UnavailableModel(provider, it) }

    /** Whether the service switched on in [settings] has its selected model found unavailable. */
    fun blocks(settings: Settings): Boolean = settings.aiProvider?.let { of(settings, it) } != null

    /**
     * The service switched on and its model, as [settings] change, when that model was found
     * unavailable; null otherwise, and until the settings are read.
     */
    fun shown(settings: Flow<Settings?>): Flow<UnavailableModel?> =
        combine(settings, found) { now, _ -> now?.aiProvider?.let { of(now, it) } }.distinctUntilChanged()

    /**
     * What [provider]'s model list for the key held, [models]: [model], the one selected, is
     * unavailable if it is not among them, deprecated ones included ([ModelInfo.offered]: they still
     * answer for an account that uses them), and available again if it is.
     */
    fun listed(
        provider: ProviderId,
        model: String,
        models: List<ModelInfo>,
    ) {
        found.update { marked ->
            when {
                models.none { it.id == model } -> marked + (provider to model)
                marked[provider] == model -> marked - provider
                else -> marked
            }
        }
    }

    /** A call with [provider]'s [model] ended as [kind]: one that names the model marks it unavailable. */
    fun answered(
        provider: ProviderId,
        model: String,
        kind: AiErrorKind,
    ) {
        if (kind in MODEL_KINDS) found.update { it + (provider to model) }
    }

    /** Lists [provider]'s models with its saved key, and checks [model] against them ([listed]). */
    suspend fun check(
        provider: ProviderId,
        model: String,
    ) {
        val client = providers[provider] ?: return
        val secret = key(provider) ?: return
        (client.models(secret) as? ModelList.Listed)?.let { listed(provider, model, it.models) }
    }

    /**
     * The check at start (#540): once [allowed] (the pipeline's D6 gate: after the first-run
     * acknowledgement, while the app is visible or looks up in the background), the service
     * switched on in [settings] has its model checked ([check]), and again whenever that service,
     * its model or a saved key ([keyChanges]) changes. A model found unavailable for a service is
     * forgotten once the service's selected model is another. Runs until cancelled.
     */
    suspend fun run(
        settings: Flow<Settings>,
        keyChanges: Flow<Unit>,
        allowed: Flow<Boolean>,
    ) = coroutineScope {
        launch { settings.collect { forget(it) } }
        val selected =
            settings
                .map { s -> s.aiProvider?.let { p -> s.model(p)?.let { p to it } } }
                .distinctUntilChanged()
        combine(selected, keyChanges) { target, _ -> target }
            .collectLatest { target ->
                target ?: return@collectLatest
                allowed.first { it }
                check(target.first, target.second)
            }
    }

    /**
     * [provider], as the pipeline calls it: each call that ends naming its model as not known or
     * refused marks that model unavailable ([answered]).
     */
    fun watching(provider: AiProvider): AiProvider =
        object : AiProvider by provider {
            override fun stream(
                request: CompletionRequest,
                key: Secret,
            ): Flow<CompletionEvent> =
                provider.stream(request, key).onEach { event ->
                    if (event is CompletionEvent.Error) answered(provider.id, request.model, event.kind)
                }
        }

    // Forgets each model found unavailable that is no longer its service's selected model.
    private fun forget(settings: Settings) {
        found.update { marked -> marked.filter { (provider, model) -> settings.model(provider) == model } }
    }

    private companion object {
        /** The errors that say the model itself cannot be used, which another model mends. */
        val MODEL_KINDS = setOf(AiErrorKind.MODEL_NOT_FOUND, AiErrorKind.MODEL_REFUSED)
    }
}
