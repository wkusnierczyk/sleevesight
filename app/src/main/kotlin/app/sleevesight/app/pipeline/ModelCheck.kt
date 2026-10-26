package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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

/** Why a model is unavailable, which says what makes it available again ([ModelCheck]). */
enum class UnavailableReason {
    /** A model list for the key, had in full, does not hold it; a later list that holds it lifts it. */
    LISTED_ABSENT,

    /**
     * A call with it ended `MODEL_NOT_FOUND` or `MODEL_REFUSED`. OpenAI and Google list models they
     * refuse for the account, so no list lifts it: only another model or service chosen, another
     * key saved, or a later call with it that succeeds.
     */
    REFUSED,
}

/**
 * A service's model found unavailable, [why], by an answer made with the key whose fingerprint is
 * [key] (a SHA-256 of it, kept in memory only, never stored or logged).
 */
class UnavailableMark(
    val model: String,
    val why: UnavailableReason,
    internal val key: String,
)

/**
 * Whether each AI service's selected model (its default, when none is chosen) can be used (#540):
 * a model the service retired, or one the account cannot use, would otherwise fail every insight.
 * One mark at most per service, with its reason ([UnavailableReason]):
 *
 * - [UnavailableReason.LISTED_ABSENT]: the service's model list for the saved key does not hold the
 *   model ([listed]: the check at start, [run], and each opening of the model choice). A list that
 *   holds it lifts it. A list that cannot be had (offline, the key refused) says nothing.
 * - [UnavailableReason.REFUSED]: a call names the model and the service answers that it does not
 *   know it or refuses it for the account ([AiErrorKind.MODEL_NOT_FOUND],
 *   [AiErrorKind.MODEL_REFUSED]; [watching]). No list lifts it; a later call with the model that
 *   succeeds does.
 * - Either is dropped when the service's selected model changes, when another service is switched
 *   on, and when another key is saved for the service ([run]).
 *
 * Each answer, a list or a call, counts only if it was made with the key saved now, for the model
 * selected now (once the settings are read): an answer made with a key since replaced, or for a
 * model since left, is ignored, so it cannot mark or lift the model chosen after it.
 *
 * While the switched-on service's model is marked, no other model is used in its place, and the
 * pipeline writes no insight with it ([blocks]); the settings, under the service, and Home say so.
 * Kept in memory only: each start checks again.
 *
 * @param providers the services' clients, as the pipeline and the settings use them.
 * @param key the saved key of a service, or null when none is saved or it cannot be read.
 */
class ModelCheck(
    private val providers: Map<ProviderId, AiProvider>,
    private val key: suspend (ProviderId) -> Secret?,
) {
    private val found = MutableStateFlow<Map<ProviderId, UnavailableMark>>(emptyMap())

    // What the settings select, once read: each service's model, and the one switched on.
    @Volatile
    private var selection: Selection? = null

    private class Selection(
        val on: ProviderId?,
        val models: Map<ProviderId, String?>,
    )

    /** Each service's model found unavailable, by service. */
    val unavailable: StateFlow<Map<ProviderId, UnavailableMark>> = found.asStateFlow()

    /** [provider]'s selected model in [settings], if it was found unavailable. */
    fun of(
        settings: Settings,
        provider: ProviderId,
    ): UnavailableModel? =
        settings
            .model(provider)
            ?.takeIf { found.value[provider]?.model == it }
            ?.let { UnavailableModel(provider, it) }

    /** Whether the service switched on in [settings] has its selected model found unavailable. */
    fun blocks(settings: Settings): Boolean = settings.aiProvider?.let { of(settings, it) } != null

    /**
     * What [provider]'s model list, made with [used], held, [models]: [model] is marked
     * [UnavailableReason.LISTED_ABSENT] if it is not among them, deprecated ones included
     * ([ModelInfo.offered]: they still answer for an account that uses them); if it is, a
     * [UnavailableReason.LISTED_ABSENT] mark of it is lifted, and a refusal stays.
     */
    suspend fun listed(
        provider: ProviderId,
        model: String,
        models: List<ModelInfo>,
        used: Secret,
    ) {
        val print = current(provider, model, used) ?: return
        val holds = models.any { it.id == model }
        found.update { marks ->
            val mark = marks[provider]?.takeIf { it.model == model }
            when {
                !holds && mark == null -> marks + (provider to UnavailableMark(model, LISTED_ABSENT, print))
                holds && mark?.why == LISTED_ABSENT -> marks - provider
                else -> marks
            }
        }
    }

    /**
     * A call with [provider]'s [model], made with [used], ended as [event]: one that names the
     * model as not known or refused marks it [UnavailableReason.REFUSED]; one that succeeded lifts
     * any mark of it.
     */
    suspend fun answered(
        provider: ProviderId,
        model: String,
        used: Secret,
        event: CompletionEvent,
    ) {
        val refused = event is CompletionEvent.Error && event.kind in MODEL_KINDS
        if (!refused && event !is CompletionEvent.Done) return
        val print = current(provider, model, used) ?: return
        found.update { marks ->
            when {
                refused -> marks + (provider to UnavailableMark(model, UnavailableReason.REFUSED, print))
                marks[provider]?.model == model -> marks - provider
                else -> marks
            }
        }
    }

    /** Lists [provider]'s models with its saved key, and checks [model] against them ([listed]). */
    suspend fun check(
        provider: ProviderId,
        model: String,
    ) {
        val client = providers[provider] ?: return
        val secret = key(provider) ?: return
        (client.models(secret) as? ModelList.Listed)?.let { listed(provider, model, it.models, secret) }
    }

    /**
     * The check at start (#540): once [allowed] (the pipeline's D6 gate: after the first-run
     * acknowledgement, while the app is visible or looks up in the background), the service
     * switched on in [settings] has its model checked ([check]), and again whenever that service,
     * its model or a saved key ([keyChanges]) changes; not while *Show insights* is off, when no
     * insight is written either way. Follows the selection and the keys, dropping the marks they
     * leave behind. Runs until cancelled.
     */
    suspend fun run(
        settings: Flow<Settings>,
        keyChanges: Flow<Unit>,
        allowed: Flow<Boolean>,
    ) = coroutineScope {
        launch { settings.collect { selected(it) } }
        launch { keyChanges.collect { keysRead() } }
        val target =
            settings
                .map { s -> s.aiProvider?.takeIf { s.writeInsights }?.let { p -> s.model(p)?.let { p to it } } }
                .distinctUntilChanged()
        combine(target, keyChanges) { chosen, _ -> chosen }
            .collectLatest { chosen ->
                chosen ?: return@collectLatest
                allowed.first { it }
                check(chosen.first, chosen.second)
            }
    }

    /**
     * [provider], as the pipeline calls it: each call's end is heard ([answered]), with the key and
     * the model it was made with.
     */
    fun watching(provider: AiProvider): AiProvider =
        object : AiProvider by provider {
            override fun stream(
                request: CompletionRequest,
                key: Secret,
            ): Flow<CompletionEvent> =
                provider.stream(request, key).onEach { event -> answered(provider.id, request.model, key, event) }
        }

    // The fingerprint of [used] if an answer made with it counts: it is the key saved now for
    // [provider], and [model] the service's selected model, once the settings are read; else null.
    private suspend fun current(
        provider: ProviderId,
        model: String,
        used: Secret,
    ): String? {
        val print = fingerprint(used)
        val now = selection
        val saved = key(provider)?.let(::fingerprint)
        return print.takeIf { saved == it && (now == null || now.models[provider] == model) }
    }

    // The settings read: the marks of a model no longer selected, and, once another service is
    // switched on, those of the others, are dropped.
    private fun selected(settings: Settings) {
        val before = selection
        val now = Selection(settings.aiProvider, providers.keys.associateWith { settings.model(it) })
        selection = now
        val switched = before != null && before.on != now.on
        found.update { marks ->
            marks.filter { (provider, mark) -> now.models[provider] == mark.model && !(switched && provider != now.on) }
        }
    }

    // A key saved, replaced or removed: the marks made with a key no longer saved are dropped.
    private suspend fun keysRead() {
        val prints = providers.keys.associateWith { key(it)?.let(::fingerprint) }
        found.update { marks -> marks.filter { (provider, mark) -> prints[provider] == mark.key } }
    }

    private companion object {
        val LISTED_ABSENT = UnavailableReason.LISTED_ABSENT

        /** The errors that say the model itself cannot be used, which another model mends. */
        val MODEL_KINDS = setOf(AiErrorKind.MODEL_NOT_FOUND, AiErrorKind.MODEL_REFUSED)

        // A key's SHA-256, to tell the key an answer was made with from the one saved now; in memory only.
        fun fingerprint(key: Secret): String =
            JvmHasher.sha256(key.reveal().encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The service switched on and its model, as [settings] change, when that model was found
 * unavailable; null otherwise, and until the settings are read.
 */
fun ModelCheck.shown(settings: Flow<Settings?>): Flow<UnavailableModel?> =
    combine(settings, unavailable) { now, _ -> now?.aiProvider?.let { of(now, it) } }.distinctUntilChanged()

/**
 * Once each time the switched-on service's model becomes, or stops being, unavailable, as
 * [settings] change: what the pipeline is rebuilt on, to leave out or bring back its AI access.
 * Not at first: the first build reads the marks itself.
 */
fun ModelCheck.rebuilds(settings: Flow<Settings?>): Flow<Unit> = shown(settings).drop(1).map {}
