package app.sleevesight.core.evidence

import app.sleevesight.core.model.SourceId
import app.sleevesight.core.text.SourceText
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
 * Unicode on every platform. It is cleaned here too, once, as all text from players and sources
 * is ([SourceText], #1041): no invisible characters, and the fullwidth `＜` as `<`, so *Sources*, a
 * genre's quote, a report and the prompt all hold the text a person checking it sees.
 *
 * [aggregate] marks a line that counts or lists what many listeners did (Last.fm's listeners, the
 * tags they gave, the artists they find similar), whose numbers and order change from one lookup to
 * the next: *Look again* (#617) compares it with its digits masked and its list as a set
 * ([LineContent]). Neither its text nor the evidence's fingerprint reads it.
 *
 * [about] names the artist an artist-level line ([Scope.ARTIST]) is about, as the track credits it
 * (#522): the adapter that looked the artist up says, so the artist's insight takes that artist's
 * lines whatever their text names (a Wikipedia lead's "Gordon Meredith Lightfoot Jr."), and the same
 * lines on a solo track and on a collaboration. On a release-level line ([Scope.RELEASE]) found in the
 * track's language (#902), it names the release (the film, the album) by its English name, so the
 * album's insight takes the lines of its own article. Null for any other line, and for one an adapter
 * or a kept result does not say. The fingerprint does not read it.
 *
 * [language] is the language the line's text is in, by its code (`hi`, `Language.code`): the prompt
 * marks the line with it, so the model translates what it uses, and Sources shows it. Set when the
 * search in the track's language found the line (#902), or, for a line any other lookup found, when
 * its script names a language ([scripted], #949: `EvidenceSet.of` reads it). Null for English or not
 * known. The fingerprint reads it, when set, so two lines that differ only by it are never taken for
 * the same evidence.
 *
 * [scripted] marks a [language] read from the line's script (#949), not from the search in the
 * track's language: such a line is the lookup's that found it, in its row of Sources, and not kept
 * with what that search found per artist and album ([found]). The fingerprint does not read it.
 */
@Suppress("LongParameterList") // One for each of the draft's own fields.
class EvidenceDraft(
    val source: SourceId,
    val scope: Scope,
    text: String,
    val url: String?,
    val retrievedAt: Instant,
    val aggregate: Boolean = false,
    val about: String? = null,
    val language: String? = null,
    val scripted: Boolean = false,
) {
    val text: String = SourceText.clean(text)

    /** The language the search in the track's language found this line in (#902), not one read from its script. */
    val found: String? get() = language.takeUnless { scripted }

    @Suppress("LongParameterList") // One for each of the draft's own fields.
    fun copy(
        source: SourceId = this.source,
        scope: Scope = this.scope,
        text: String = this.text,
        url: String? = this.url,
        retrievedAt: Instant = this.retrievedAt,
        aggregate: Boolean = this.aggregate,
        about: String? = this.about,
        language: String? = this.language,
        scripted: Boolean = this.scripted,
    ): EvidenceDraft = EvidenceDraft(source, scope, text, url, retrievedAt, aggregate, about, language, scripted)

    override fun equals(other: Any?): Boolean =
        other is EvidenceDraft &&
            source == other.source &&
            scope == other.scope &&
            text == other.text &&
            url == other.url &&
            retrievedAt == other.retrievedAt &&
            aggregate == other.aggregate &&
            about == other.about &&
            language == other.language &&
            scripted == other.scripted

    override fun hashCode(): Int =
        listOf(source, scope, text, url, retrievedAt, aggregate, about, language, scripted).hashCode()

    override fun toString(): String =
        "EvidenceDraft(source=$source, scope=$scope, text=$text, url=$url, retrievedAt=$retrievedAt" +
            (if (aggregate) ", aggregate=true" else "") + (about?.let { ", about=$it" } ?: "") +
            (language?.let { ", language=$it" } ?: "") + (if (scripted) ", scripted=true)" else ")")
}

/**
 * An evidence line with its id, the source tag plus its position among that source's lines, from
 * 1: `W1`, `L3`, `S2`, `P1`. Ids are assigned by [EvidenceSet], from [EvidenceDraft]s, whose text
 * is already well-formed. [language] and [scripted] as the draft's ([EvidenceDraft.language]): null
 * for English or not known.
 */
data class EvidenceLine(
    val id: String,
    val source: SourceId,
    val scope: Scope,
    val text: String,
    val url: String?,
    val retrievedAt: Instant,
    val aggregate: Boolean = false,
    val about: String? = null,
    val language: String? = null,
    val scripted: Boolean = false,
) {
    /** The language the search in the track's language found this line in (#902), not one read from its script. */
    val found: String? get() = language.takeUnless { scripted }

    /** This line as a draft, to be numbered again. */
    fun draft(): EvidenceDraft =
        EvidenceDraft(source, scope, text, url, retrievedAt, aggregate, about, language, scripted)
}
