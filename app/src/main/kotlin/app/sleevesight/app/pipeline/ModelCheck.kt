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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

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
 *   on, and when another key is read for the service ([run]), or one is saved, replaced or removed
 *   ([keyChanging], #1425).
 *
 * The selection, the keys' fingerprints and the marks are kept by one loop, launched in [scope],
 * which takes one event at a time ([ModelMarks]); nothing else changes them. The keys are read
 * outside it, and handed to it in the order the reads were issued; each answer, a list or a call,
 * comes to it with the key and the model it was made with and the revisions of its [Ticket], taken
 * as its request was issued, and counts only if none of them has moved on since. So an answer made
 * with a key since replaced, or for a model since left, cannot mark or lift the model chosen after
 * it, whatever order the coroutines run in.
 *
 * While the switched-on service's model is marked, no other model is used in its place, and the
 * pipeline writes no insight with it ([blocks]); the settings, under the service, and Home say so.
 * The marks are kept in memory only: each start checks again.
 *
 * Each service's models a call found refused with its saved key, whichever is selected ([refused],
 * #970), are kept across starts in [kept] (the models' ids and how each was refused only; nothing of
 * the key): the list of models puts them last, still to be chosen, and a selected model kept so is
 * marked again at start. One leaves them once a call with it answers, and they all go, from [kept]
 * too, before the service's key is saved, replaced or removed through the app ([keyChanging],
 * #1425), never on a read of it: a read that fails for now, at start or later, keeps them.
 *
 * @param providers the services' clients, as the pipeline and the settings use them.
 * @param key the saved key of a service, or null when none is saved or it cannot be read.
 * @param scope where the loop runs, as long as the process.
 * @param kept where the models refused for each service's key are kept across starts, read when
 *   the loop starts, and written as they change.
 * @param stored whether a key is stored for a service, readable or not, and true when that cannot
 *   be told (`SecretStore.isStored`): a read that found none while one may be stored failed for now,
 *   and drops no mark; only a confirmed absence does.
 */
@Suppress("TooManyFunctions") // What the loop is told, one function each, and the check that asks.
class ModelCheck(
    private val providers: Map<ProviderId, AiProvider>,
    private val key: suspend (ProviderId) -> Secret?,
    scope: CoroutineScope,
    private val kept: RefusedModelStore = RefusedModelStore.NONE,
    private val stored: suspend (ProviderId) -> Boolean = { false },
) {
    private sealed interface Event {
        class Selected(
            val settings: Settings,
        ) : Event

        class KeyRead(
            val provider: ProviderId,
            val print: String?,
            val order: Long,
            val stored: Boolean,
        ) : Event

        class KeyChanging(
            val provider: ProviderId,
            // Whether what was refused is kept forgotten; null when the loop did not take it.
            val done: CompletableDeferred<Boolean?>,
        ) : Event

        class KeyChanged(
            val provider: ProviderId,
            val order: Long,
            val print: String?,
            val written: Boolean,
        ) : Event

        class Listed(
            val provider: ProviderId,
            val model: String,
            val models: List<ModelInfo>,
            val print: String,
            val ticket: Ticket,
        ) : Event

        class Answered(
            val provider: ProviderId,
            val model: String,
            val print: String,
            val ticket: Ticket,
            val event: CompletionEvent,
        ) : Event

        class Ask(
            val ticket: CompletableDeferred<Ticket>,
        ) : Event

        class Retry(
            val provider: ProviderId,
            val model: String,
        ) : Event
    }

    // A ticket asked for and never taken by the loop (left in the queue when it ends, or handed to
    // it as it was cancelled) is answered with one under which nothing counts, so no one waits; a
    // key change, with the loop's part not done, which its caller then does itself.
    private val events =
        Channel<Event>(Channel.UNLIMITED) { left ->
            (left as? Event.Ask)?.ticket?.complete(Ticket.NEVER)
            (left as? Event.KeyChanging)?.done?.complete(null)
        }
    private val found = MutableStateFlow<Map<ProviderId, UnavailableMark>>(emptyMap())
    private val refusedNow = MutableStateFlow<RefusedModels>(emptyMap())
    private val reads = AtomicLong()

    // Counts the keys' writes ended ([keyChanged]): each one checks the selected model again ([run]).
    private val edits = MutableStateFlow(0L)

    init {
        val loop =
            scope.launch {
                val book = ModelMarks(providers.keys, kept.load())
                // A save of [refused] failed: the next event tries again.
                var unsaved = false
                refusedNow.value = book.refused
                for (event in events) {
                    when (event) {
                        is Event.Selected -> book.selected(event.settings)
                        is Event.KeyRead -> book.keyRead(event.provider, event.print, event.order, event.stored)
                        is Event.KeyChanging -> book.keyChanging(event.provider)
                        is Event.KeyChanged -> with(event) { book.keyChanged(provider, order, print, written) }
                        is Event.Listed -> with(event) { book.listed(provider, model, models, print, ticket) }
                        is Event.Answered -> with(event) { book.answered(provider, model, print, ticket, this.event) }
                        is Event.Ask -> event.ticket.complete(book.ticket())
                        is Event.Retry -> book.retry(event.provider, event.model)
                    }
                    found.value = book.marks
                    val changing = event as? Event.KeyChanging
                    // Kept again before a key is written, whatever memory says: a save that failed
                    // left what it should have replaced on disk (#1425).
                    if (book.refused != refusedNow.value || unsaved || changing != null) {
                        unsaved = !kept.save(book.refused)
                        refusedNow.value = book.refused
                    }
                    // Kept, and then only, the key may be written.
                    changing?.done?.complete(!unsaved)
                }
            }
        // The loop ended (its scope cancelled, before it started too): nothing more is taken, and
        // what is left in the queue goes to the channel's handler of undelivered events.
        loop.invokeOnCompletion { events.cancel() }
    }

    /**
     * [provider]'s key about to be saved, replaced or removed through the app (#1425), told by the
     * key store before it writes: what was refused with the key is forgotten, in [kept] too, before
     * this returns, so a key written is never followed by it, even across a crash; and no read or
     * answer counts for the service until the write ends ([keyChanged]) ([ModelMarks.keyChanging]).
     * With the loop ended, [kept] is cleared here. False when what is kept could not be written:
     * the key is then not saved.
     */
    suspend fun keyChanging(provider: ProviderId): Boolean {
        val done = CompletableDeferred<Boolean?>()
        val sent = events.trySend(Event.KeyChanging(provider, done)).isSuccess
        // Not taken by the loop (it ended): cleared here, from what is kept.
        return (if (sent) done.await() else null) ?: kept.save(kept.load() - provider)
    }

    /**
     * [provider]'s key write ended (#1425): the key holds [now] (null for none) when [written];
     * when not, what it holds is not known ([ModelMarks.keyChanged]). Told in the order of the
     * reads, so a read issued before it, which may have found the key replaced, is dropped; the
     * selected model is checked again after it ([run]).
     */
    fun keyChanged(
        provider: ProviderId,
        written: Boolean,
        now: Secret?,
    ) {
        val print = now?.takeIf { written }?.let(::fingerprint)
        events.trySend(Event.KeyChanged(provider, reads.incrementAndGet(), print, written))
        edits.update { it + 1 }
    }

    /** Each service's model found unavailable, by service, as the loop keeps them. */
    val unavailable: StateFlow<Map<ProviderId, UnavailableMark>> = found.asStateFlow()

    /**
     * Each service's models a call found refused with its saved key (#970), by service, as the loop
     * keeps them: the list of models puts them last.
     */
    val refused: StateFlow<RefusedModels> = refusedNow.asStateFlow()

    /** [provider]'s selected model in [settings], if it was found unavailable, with how it was refused. */
    fun of(
        settings: Settings,
        provider: ProviderId,
    ): UnavailableModel? {
        val mark = found.value[provider]
        return settings
            .model(provider)
            ?.takeIf { mark?.model == it }
            ?.let { UnavailableModel(provider, it, mark?.refusal) }
    }

    /** Whether the service switched on in [settings] has its selected model found unavailable. */
    fun blocks(settings: Settings): Boolean = settings.aiProvider?.let { of(settings, it) } != null

    /**
     * The revisions now, for a request about to be issued: its answer, given back with them
     * ([listed], [answered]), counts only if they have not moved on. Every event sent before it is
     * applied first, so this also waits for those. Once the loop has ended, a ticket under which no
     * answer counts ([Ticket.NEVER]), at once.
     */
    suspend fun ticket(): Ticket {
        val asked = CompletableDeferred<Ticket>()
        return if (events.trySend(Event.Ask(asked)).isSuccess) asked.await() else Ticket.NEVER
    }

    /**
     * What [provider]'s model list, made with [used] under [ticket], held, [models], for [model]
     * ([ModelMarks.listed]).
     */
    fun listed(
        ticket: Ticket,
        provider: ProviderId,
        model: String,
        models: List<ModelInfo>,
        used: Secret,
    ) {
        events.trySend(Event.Listed(provider, model, models, fingerprint(used), ticket))
    }

    /**
     * [provider]'s [model] chosen from the list's refused models, to try it again (#970): a mark of
     * its refusal is lifted, so the next call tries it, though it was the selected model already
     * ([ModelMarks.retry]).
     */
    fun retry(
        provider: ProviderId,
        model: String,
    ) {
        events.trySend(Event.Retry(provider, model))
    }

    /** A call with [provider]'s [model], made with [used] under [ticket], ended as [event] ([ModelMarks.answered]). */
    fun answered(
        ticket: Ticket,
        provider: ProviderId,
        model: String,
        used: Secret,
        event: CompletionEvent,
    ) {
        if (ModelMarks.heard(event)) events.trySend(Event.Answered(provider, model, fingerprint(used), ticket, event))
    }

    /**
     * Lists [provider]'s models with its saved key, read and handed to the loop first, and checks
     * [model] against them ([listed]).
     */
    suspend fun check(
        provider: ProviderId,
        model: String,
    ) {
        val client = providers[provider] ?: return
        val secret = readKey(provider) ?: return
        val ticket = ticket()
        (client.models(secret) as? ModelList.Listed)?.let { listed(ticket, provider, model, it.models, secret) }
    }

    /**
     * The check at start (#540): once [allowed] (the pipeline's D6 gate: after the first-run
     * acknowledgement, while the app is visible or looks up in the background), the service
     * switched on in [settings] has its model checked ([check]), and again whenever that service,
     * its model or a saved key ([keyChanges], and the end of each key write, [keyChanged]) changes;
     * not while *Show insights* is off, when no insight is written either way. Hands the loop each
     * selection, before the check it leads to, and each service's key after each change. Runs until
     * cancelled.
     */
    suspend fun run(
        settings: Flow<Settings>,
        keyChanges: Flow<Unit>,
        allowed: Flow<Boolean>,
    ) = coroutineScope {
        launch { keyChanges.collect { providers.keys.forEach { readKey(it) } } }
        val target =
            settings
                .onEach { events.trySend(Event.Selected(it)) }
                .map { s -> s.aiProvider?.takeIf { s.writeInsights }?.let { p -> s.model(p)?.let { p to it } } }
                .distinctUntilChanged()
        combine(target, keyChanges, edits) { chosen, _, _ -> chosen }
            .collectLatest { chosen ->
                chosen ?: return@collectLatest
                allowed.first { it }
                check(chosen.first, chosen.second)
            }
    }

    /**
     * [provider], as the pipeline calls it: each call reads the saved key ([readKey]), so the loop
     * knows the key saved now before the call's answer comes, whenever the key store says it
     * changed; takes a [ticket] after it; and its end is heard ([answered]) with it, the key and the
     * model it was made with. A call made with a key since replaced counts for nothing.
     */
    fun watching(provider: AiProvider): AiProvider =
        object : AiProvider by provider {
            override fun stream(
                request: CompletionRequest,
                key: Secret,
            ): Flow<CompletionEvent> =
                flow {
                    readKey(provider.id)
                    val ticket = ticket()
                    emitAll(
                        provider.stream(request, key).onEach { answered(ticket, provider.id, request.model, key, it) },
                    )
                }
        }

    // [provider]'s saved key, read outside the loop and handed to it in the order the reads were
    // issued, so a read that took longer than a later one counts for nothing.
    private suspend fun readKey(provider: ProviderId): Secret? {
        val order = reads.incrementAndGet()
        val secret = key(provider)
        val print = secret?.let(::fingerprint)
        events.trySend(Event.KeyRead(provider, print, order, stored = print == null && stored(provider)))
        return secret
    }

    private companion object {
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

/**
 * Where [ModelCheck] keeps the models refused for each service's saved key across starts (#970):
 * their ids and how each was refused, nothing of the key.
 */
interface RefusedModelStore {
    /** The models kept, read once as the check starts. */
    fun load(): RefusedModels

    /**
     * Keeps [refused] in place of what was kept, durably before it returns (#1425): false when it
     * could not, and what was kept before may still be there.
     */
    fun save(refused: RefusedModels): Boolean

    companion object {
        /** Keeps nothing: for previews and tests. */
        val NONE: RefusedModelStore =
            object : RefusedModelStore {
                override fun load(): RefusedModels = emptyMap()

                override fun save(refused: RefusedModels) = true
            }
    }
}
