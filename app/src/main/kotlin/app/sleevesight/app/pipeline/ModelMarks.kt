package app.sleevesight.app.pipeline

import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings

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
 * The revisions of what an answer depends on, as they stood when its request was issued
 * ([ModelCheck.ticket]): each service's key, and the selection. An answer whose revisions have moved
 * on by the time it arrives counts for nothing.
 */
class Ticket internal constructor(
    internal val keys: Map<ProviderId, Long>,
    internal val selection: Long,
)

/**
 * What [ModelCheck]'s loop keeps, and the rules it applies, one event at a time: only the loop
 * touches it, so each rule reads and writes it at once, with nothing in between.
 *
 * - The selection ([selected]): each service's selected model, and the one switched on. Its
 *   revision moves when either changes.
 * - Each service's key ([keyRead]): the fingerprint of the key last read, and the revision, which
 *   moves when the fingerprint does. A read issued before the last one applied is stale and
 *   dropped; one that found no key while the key is still stored (a read that failed for now) keeps
 *   what was known.
 * - The marks ([listed], [answered]): an answer counts only if it was made with the key known now
 *   (when one is known), for the model selected now (once the settings are read), and both
 *   revisions are those of its [Ticket].
 */
internal class ModelMarks(
    private val providers: Set<ProviderId>,
) {
    var marks: Map<ProviderId, UnavailableMark> = emptyMap()
        private set

    private class Selection(
        val on: ProviderId?,
        val models: Map<ProviderId, String?>,
    )

    private var selection: Selection? = null
    private var selectionRevision = 0L
    private val prints = HashMap<ProviderId, String?>()
    private val readOrder = HashMap<ProviderId, Long>()
    private val keyRevisions = HashMap<ProviderId, Long>()

    /** The revisions now, for a request about to be issued. */
    fun ticket(): Ticket = Ticket(keyRevisions.toMap(), selectionRevision)

    /**
     * The settings read: the marks of a model no longer selected, and, once another service is
     * switched on, those of the others, are dropped.
     */
    fun selected(settings: Settings) {
        val before = selection
        val now = Selection(settings.aiProvider, providers.associateWith { settings.model(it) })
        if (before != null && (before.on != now.on || before.models != now.models)) selectionRevision++
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
        if (order <= (readOrder[provider] ?: 0L)) return
        readOrder[provider] = order
        if (print == null && stored) return
        val known = provider in prints
        if (known && prints[provider] != print) keyRevisions[provider] = (keyRevisions[provider] ?: 0L) + 1
        prints[provider] = print
        marks = marks.filter { (p, mark) -> p != provider || mark.key == print }
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
     * one that names the model as not known or refused marks it [UnavailableReason.REFUSED]; one that
     * succeeded lifts any mark of it.
     */
    fun answered(
        provider: ProviderId,
        model: String,
        print: String,
        ticket: Ticket,
        event: CompletionEvent,
    ) {
        if (!counts(provider, model, print, ticket)) return
        marks =
            when {
                refuses(event) -> marks + (provider to UnavailableMark(model, UnavailableReason.REFUSED, print))
                marks[provider]?.model == model -> marks - provider
                else -> marks
            }
    }

    // Whether an answer made with the key [print] for [model], under [ticket], still counts.
    private fun counts(
        provider: ProviderId,
        model: String,
        print: String,
        ticket: Ticket,
    ): Boolean {
        val now = selection
        val sameKey = provider !in prints || prints[provider] == print
        val sameRevisions =
            (keyRevisions[provider] ?: 0L) == (ticket.keys[provider] ?: 0L) && selectionRevision == ticket.selection
        return sameKey && sameRevisions && (now == null || now.models[provider] == model)
    }

    companion object {
        private val LISTED_ABSENT = UnavailableReason.LISTED_ABSENT

        /** The errors that say the model itself cannot be used, which another model mends. */
        private val MODEL_KINDS = setOf(AiErrorKind.MODEL_NOT_FOUND, AiErrorKind.MODEL_REFUSED)

        /** Whether [event] says the model cannot be used. */
        fun refuses(event: CompletionEvent): Boolean = event is CompletionEvent.Error && event.kind in MODEL_KINDS

        /** Whether [event] is one the marks hear: a refusal of the model, or a reply that came. */
        fun heard(event: CompletionEvent): Boolean = refuses(event) || event is CompletionEvent.Done
    }
}
