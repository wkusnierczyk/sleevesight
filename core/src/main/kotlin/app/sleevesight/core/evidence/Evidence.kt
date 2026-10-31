package app.sleevesight.core.evidence

import app.sleevesight.core.model.SourceId
import kotlin.time.Instant

/**
 * What an evidence line is about. The model is told, so it never credits an original song's
 * facts to a cover act (`design/design.md`, *Evidence model*).
 *
 * @property key the scope as the evidence fingerprint encodes it. It is fixed, not the constant's
 *   name, so renaming a constant does not change every fingerprint.
 */
enum class Scope(
    val key: String,
) {
    SONG("song"),
    ARTIST("artist"),
    ORIGINAL_SONG("original_song"),
    RELEASE("release"),
    WEB("web"),
}

/**
 * An evidence line as an adapter makes it, before it has an id.
 *
 * [text] is one claim: templated from structured data, or a short attributed quote, never a whole
 * passage (D1). Web text can hold anything, so a lone UTF-16 surrogate in it is replaced with
 * U+FFFD here, once: the text stored, shown and fingerprinted is then the same well-formed
 * Unicode on every platform.
 *
 * [aggregate] marks a line that counts or lists what many listeners did (Last.fm's listeners, the
 * tags they gave, the artists they find similar), whose numbers and order change from one lookup to
 * the next: *Look again* (#617) compares it with its digits masked and its list as a set
 * ([LineContent]). Neither its text nor the evidence's fingerprint reads it.
 */
class EvidenceDraft(
    val source: SourceId,
    val scope: Scope,
    text: String,
    val url: String?,
    val retrievedAt: Instant,
    val aggregate: Boolean = false,
) {
    val text: String = text.withoutLoneSurrogates()

    @Suppress("LongParameterList") // One for each of the draft's own fields.
    fun copy(
        source: SourceId = this.source,
        scope: Scope = this.scope,
        text: String = this.text,
        url: String? = this.url,
        retrievedAt: Instant = this.retrievedAt,
        aggregate: Boolean = this.aggregate,
    ): EvidenceDraft = EvidenceDraft(source, scope, text, url, retrievedAt, aggregate)

    override fun equals(other: Any?): Boolean =
        other is EvidenceDraft &&
            source == other.source &&
            scope == other.scope &&
            text == other.text &&
            url == other.url &&
            retrievedAt == other.retrievedAt &&
            aggregate == other.aggregate

    override fun hashCode(): Int = listOf(source, scope, text, url, retrievedAt, aggregate).hashCode()

    override fun toString(): String =
        "EvidenceDraft(source=$source, scope=$scope, text=$text, url=$url, retrievedAt=$retrievedAt" +
            (if (aggregate) ", aggregate=true)" else ")")
}

/**
 * An evidence line with its id, the source tag plus its position among that source's lines, from
 * 1: `W1`, `L3`, `S2`, `P1`. Ids are assigned by [EvidenceSet], from [EvidenceDraft]s, whose text
 * is already well-formed.
 */
data class EvidenceLine(
    val id: String,
    val source: SourceId,
    val scope: Scope,
    val text: String,
    val url: String?,
    val retrievedAt: Instant,
    val aggregate: Boolean = false,
)

/** [this] with every unpaired UTF-16 surrogate replaced by U+FFFD. */
internal fun String.withoutLoneSurrogates(): String {
    if (none { it.isSurrogate() }) return this
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        when {
            c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> {
                out.append(c).append(this[i + 1])
                i++
            }
            c.isSurrogate() -> out.append(REPLACEMENT)
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

private const val REPLACEMENT = '�'
