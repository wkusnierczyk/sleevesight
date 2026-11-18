package app.sleevesight.app.pipeline

import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings

/**
 * [provider]'s model [model], which the service does not list for the key, or refused (#540); how it
 * was refused, [refusal], when a call found it so (#970), else null.
 */
data class UnavailableModel(
    val provider: ProviderId,
    val model: String,
    val refusal: ModelRefusal? = null,
)

/** How a call found a model refused (#970), which the settings and Home say. */
enum class ModelRefusal {
    /** The service does not know the model ([AiErrorKind.MODEL_NOT_FOUND]): it is no longer served. */
    NOT_SERVED,

    /** The service refuses the model for the account ([AiErrorKind.MODEL_REFUSED]). */
    ACCOUNT,
    ;

    companion object {
        /** How [event] refuses the model, or null when it does not. */
        fun of(event: CompletionEvent): ModelRefusal? =
            when ((event as? CompletionEvent.Error)?.kind) {
                AiErrorKind.MODEL_NOT_FOUND -> NOT_SERVED
                AiErrorKind.MODEL_REFUSED -> ACCOUNT
                else -> null
            }
    }
}

/** Each service's models refused for its saved key (#970), by service, with how each was refused. */
typealias RefusedModels = Map<ProviderId, Map<String, ModelRefusal>>

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
 * A service's model found unavailable, [why], and how a call refused it, [refusal] (#970; null for
 * [UnavailableReason.LISTED_ABSENT]), by an answer made with the key whose fingerprint is [key] (a
 * SHA-256 of it, kept in memory only, never stored or logged); null for a mark restored at start
 * from the models kept as refused ([ModelMarks.selected]), made with the key saved then, which the
 * first read of the key fills in.
 */
class UnavailableMark(
    val model: String,
    val why: UnavailableReason,
    internal val key: String?,
    val refusal: ModelRefusal? = null,
) {
    /** The same mark, made with the key [print]. */
    internal fun madeWith(print: String?): UnavailableMark = UnavailableMark(model, why, print, refusal)
}

/**
 * The revisions of what an answer depends on, as they stood when its request was issued
 * ([ModelCheck.ticket]): each service's key, and each service's selection (its model, and whether
 * it is the one switched on). An answer whose service's revisions have moved on by the time it
 * arrives counts for nothing; so does any answer under a ticket [never] to count, which is what a
 * ticket asked for once the loop has ended is.
 */
class Ticket internal constructor(
    internal val keys: Map<ProviderId, Long>,
    internal val selection: Map<ProviderId, Long>,
    internal val never: Boolean = false,
) {
    internal companion object {
        /** A ticket under which no answer counts. */
        val NEVER = Ticket(emptyMap(), emptyMap(), never = true)
    }
}

/**
 * What [ModelCheck]'s loop keeps, and the rules it applies, one event at a time: only the loop
 * touches it, so each rule reads and writes it at once, with nothing in between.
 *
 * - The selection ([selected]): each service's selected model, and the one switched on. A
 *   service's revision moves when its model changes, or when it is switched on or off.
 * - Each service's key ([keyRead]): the fingerprint of the key last read, and the revision, which
 *   moves when the fingerprint does. A read that found no key while the key is still stored (a read
 *   that failed for now) is ignored, and counts as no read at all; of the others, one issued before
 *   the last one applied is stale and dropped.
 * - The marks ([listed], [answered]): an answer counts only if it was made with the key known now
 *   (when one is known), for the model selected now (once the settings are read), and both
 *   revisions are those of its [Ticket].
 * - The models refused for each service's saved key ([refused], #970), whichever is selected,
 *   [kept] when the loop starts: a call with one ends refused, and it joins them; one that answers,
 *   and it leaves them; another key read for the service, and they all go. A call counts for them
 *   if it was made with the key known now and its key revision is that of its [Ticket], whatever
 *   model is selected now.
 */
internal class ModelMarks(
    private val providers: Set<ProviderId>,
    kept: RefusedModels = emptyMap(),
) {
    var marks: Map<ProviderId, UnavailableMark> = emptyMap()
        private set

    /** Each service's models refused for its saved key, which the list of models puts last (#970). */
    var refused: RefusedModels = kept.filterValues { it.isNotEmpty() }
        private set

    private class Selection(
        val on: ProviderId?,
        val models: Map<ProviderId, String?>,
    )

    private var selection: Selection? = null
    private val selectionRevisions = HashMap<ProviderId, Long>()
    private val prints = HashMap<ProviderId, String?>()
    private val readOrder = HashMap<ProviderId, Long>()
    private val keyRevisions = HashMap<ProviderId, Long>()

    // The services whose stored key could not be read before any was (#970).
    private val unread = HashSet<ProviderId>()

    /** The revisions now, for a request about to be issued. */
    fun ticket(): Ticket = Ticket(keyRevisions.toMap(), selectionRevisions.toMap())

    /**
     * The settings read: the marks of a model no longer selected, and, once another service is
     * switched on, those of the others, are dropped. At the first read, each service's selected
     * model kept as [refused] is marked so again (#970): it stays refused until another is chosen,
     * or a call with it answers. One chosen again later is not marked, so the next call tries it.
     */
    fun selected(settings: Settings) {
        val before = selection
        val now = Selection(settings.aiProvider, providers.associateWith { settings.model(it) })
        if (before == null) {
            marks = marks + restored(now)
        } else {
            for (provider in providers) {
                val toggled = (before.on == provider) != (now.on == provider)
                val moved = toggled || before.models[provider] != now.models[provider]
                if (moved) selectionRevisions[provider] = (selectionRevisions[provider] ?: 0L) + 1
            }
        }
        selection = now
        val switched = before != null && before.on != now.on
        marks =
            marks.filter { (provider, mark) ->
                now.models[provider] == mark.model && !(switched && provider != now.on)
            }
    }

    /**
     * [provider]'s key read, as [print] (null for none), the read issued as [order]; [stored] says
     * whether a key is stored all the same (the read failed for now). A key that changed drops the
     * service's marks made with another.
     */
    fun keyRead(
        provider: ProviderId,
        print: String?,
        order: Long,
        stored: Boolean,
    ) {
        // A read that failed for now says nothing, and must not stand in for the reads issued before
        // it; but the key it could not read may be replaced before one is read (#970).
        if (print == null && stored) {
            if (provider !in prints) unread += provider
            return
        }
        if (order <= (readOrder[provider] ?: 0L)) return
        readOrder[provider] = order
        val known = provider in prints
        val moved = known && prints[provider] != print
        if (moved) keyRevisions[provider] = (keyRevisions[provider] ?: 0L) + 1
        prints[provider] = print
        // The first key read after the one stored could not be: maybe the same, maybe one saved in its
        // place; what was refused is cleared rather than kept for a key it may not belong to.
        val unknown = !known && unread.remove(provider)
        keyMarks(provider, print, moved || unknown)
        // Another key saved for the service, or none: what the old one was refused says nothing of
        // the next (#970).
        if (moved || unknown || print == null) refused = refused - provider
    }

    /**
     * [provider]'s [model] chosen again from the list to try it (#970), whether or not it was the
     * selected model already: a refusal's mark of it is lifted, so the next call tries it, as a
     * fresh selection; it stays among [refused] until a call with it answers.
     */
    fun retry(
        provider: ProviderId,
        model: String,
    ) {
        val mark = marks[provider] ?: return
        if (mark.model != model || mark.why != UnavailableReason.REFUSED) return
        marks = marks - provider
        selectionRevisions[provider] = (selectionRevisions[provider] ?: 0L) + 1
    }

    // [provider]'s marks once its key is read as [print]: those made with another go. A mark
    // restored at start was made with the key saved then: the key read is it, unless it [moved]
    // since a read before.
    private fun keyMarks(
        provider: ProviderId,
        print: String?,
        moved: Boolean,
    ) {
        val holds = { mark: UnavailableMark -> print != null && (mark.key == print || (mark.key == null && !moved)) }
        marks =
            marks
                .filter { (p, mark) -> p != provider || holds(mark) }
                .mapValues { (p, mark) -> if (p == provider && mark.key == null) mark.madeWith(print) else mark }
    }

    /**
     * [provider]'s model list, made with the key [print] under [ticket], held [models]: [model] is
     * marked [UnavailableReason.LISTED_ABSENT] if it is not among them, deprecated ones included
     * ([ModelInfo.offered]); if it is, a [UnavailableReason.LISTED_ABSENT] mark of it is lifted, and
     * a refusal stays.
     */
    fun listed(
        provider: ProviderId,
        model: String,
        models: List<ModelInfo>,
        print: String,
        ticket: Ticket,
    ) {
        if (!counts(provider, model, print, ticket)) return
        val holds = models.any { it.id == model }
        val mark = marks[provider]?.takeIf { it.model == model }
        marks =
            when {
                !holds && mark == null -> marks + (provider to UnavailableMark(model, LISTED_ABSENT, print))
                holds && mark?.why == LISTED_ABSENT -> marks - provider
                else -> marks
            }
    }

    /**
     * A call with [provider]'s [model], made with the key [print] under [ticket], ended as [event]:
     * one that names the model as not known or refused marks it [UnavailableReason.REFUSED], with
     * how ([ModelRefusal]), and keeps it as [refused] for the key; one that succeeded lifts any mark
     * of it, and takes it out of [refused] (#970).
     */
    fun answered(
        provider: ProviderId,
        model: String,
        print: String,
        ticket: Ticket,
        event: CompletionEvent,
    ) {
        if (!sameKey(provider, print, ticket)) return
        val refusal = ModelRefusal.of(event)
        val kept = refused[provider].orEmpty()
        val now = if (refusal != null) kept + (model to refusal) else kept - model
        refused = if (now.isEmpty()) refused - provider else refused + (provider to now)
        if (!counts(provider, model, print, ticket)) return
        marks =
            when {
                refusal != null -> marks + (provider to UnavailableMark(model, REFUSED, print, refusal))
                marks[provider]?.model == model -> marks - provider
                else -> marks
            }
    }

    // The marks of each service's selected model kept as [refused], at the first read of [now].
    private fun restored(now: Selection): Map<ProviderId, UnavailableMark> =
        now.models
            .mapNotNull { (provider, model) ->
                val refusal = model?.let { refused[provider]?.get(it) } ?: return@mapNotNull null
                provider to UnavailableMark(model, UnavailableReason.REFUSED, key = null, refusal)
            }.toMap()

    // Whether an answer made with the key [print] under [ticket] was made with the key known now.
    private fun sameKey(
        provider: ProviderId,
        print: String,
        ticket: Ticket,
    ): Boolean =
        !ticket.never &&
            (provider !in prints || prints[provider] == print) &&
            (keyRevisions[provider] ?: 0L) == (ticket.keys[provider] ?: 0L)

    // Whether an answer made with the key [print] for [model], under [ticket], still counts.
    private fun counts(
        provider: ProviderId,
        model: String,
        print: String,
        ticket: Ticket,
    ): Boolean {
        val now = selection
        val sameSelection = (selectionRevisions[provider] ?: 0L) == (ticket.selection[provider] ?: 0L)
        return sameKey(provider, print, ticket) && sameSelection && (now == null || now.models[provider] == model)
    }

    companion object {
        private val LISTED_ABSENT = UnavailableReason.LISTED_ABSENT
        private val REFUSED = UnavailableReason.REFUSED

        /** The errors that say the model itself cannot be used, which another model mends. */
        private val MODEL_KINDS = setOf(AiErrorKind.MODEL_NOT_FOUND, AiErrorKind.MODEL_REFUSED)

        /** Whether [event] says the model cannot be used. */
        fun refuses(event: CompletionEvent): Boolean = event is CompletionEvent.Error && event.kind in MODEL_KINDS

        /** Whether [event] is one the marks hear: a refusal of the model, or a reply that came. */
        fun heard(event: CompletionEvent): Boolean = refuses(event) || event is CompletionEvent.Done
    }
}
