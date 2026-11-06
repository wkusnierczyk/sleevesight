package app.sleevesight.core.evidence

import app.sleevesight.core.language.Language
import app.sleevesight.core.language.Languages
import app.sleevesight.core.model.SourceId

/**
 * A track's evidence, with ids assigned and a fingerprint (`design/architecture.md`, *Evidence*).
 *
 * Ids: the lines are sorted by source, in [SOURCE_ORDER], keeping each source's own order, and
 * numbered from 1 within each source. The sort makes the ids independent of the order the sources
 * land in, so the same retrieval gives the same ids, as long as each adapter emits its lines in a
 * deterministic order (the order of its response). A source's ids do not depend on the other
 * sources' lines.
 *
 * [fingerprint] is a SHA-256 over the ordered (id, scope, text) triples, in lowercase hex, each
 * with the line's language after its text when it has one (#902): a line in Hindi and the same
 * text unmarked are prompted differently, so they are different evidence. It is part of the
 * synthesis cache key (R2): a cached insight is reused only for the exact evidence it was written
 * from. The URL and the retrieval time are not part of it, so a refetch that finds the same lines
 * keeps the cached insight. Encoding, per line in order: for each of the id, [Scope.key], the text
 * and the language's code (only when set), its UTF-8 byte length in decimal, `:`, and its UTF-8
 * bytes; then `\n`. The text is well-formed Unicode (see [EvidenceDraft]), so the encoding is the
 * same on every platform. Changing the encoding, [SOURCE_ORDER], a tag or a scope key changes every fingerprint,
 * which empties the synthesis cache in effect; a golden test pins them. So web search's lines are
 * fingerprinted with their ids as they were before its tag became `S` (#769): `G1`, `G2`
 * ([SourceId.LEGACY_WEB_TAG]). A card kept citing `[G1]` is then still found for the same lines, and
 * reads its citations as `S1` (`citationId`).
 */
class EvidenceSet private constructor(
    val lines: List<EvidenceLine>,
    val fingerprint: String,
    // The track's language its lines' scripts were read with ([of]), for the lines [with] adds.
    private val known: Language?,
) {
    private val byId: Map<String, EvidenceLine> = lines.associateBy { it.id }

    /** The ids, in order. */
    val ids: List<String> get() = lines.map { it.id }

    /** The line with [id], or null when there is none. */
    operator fun get(id: String): EvidenceLine? = byId[id]

    fun isEmpty(): Boolean = lines.isEmpty()

    /**
     * Whether an insight written from this set has nothing to narrate, so no model call is made
     * (#193): the set has no line, or only the player's own release line ([SourceId.PLAYER], `P`)
     * unless [allowModel] and [artistsKnown] both hold. That line repeats what the player reports,
     * which the insight prompt gives the model in its track block anyway, so from it alone the
     * model could only restate the release the card's header shows. With [allowModel] on (the
     * *Model's own knowledge* switch), such a set is narrated: the model may add its one unverified
     * sentence, cited `[AI]`, which the user opted into. But only once the report names the track's
     * artists ([artistsKnown]): some players report the title and album before the artists, and a
     * card written from that early report would stand for the track, its `[AI]` sentence written
     * without the artist, since nothing is written twice for one track without a tap (D5). Decided
     * with no call, that early report leaves the track unwritten, so the report with the artists,
     * under a new key, is decided afresh (#204). A set with no line at all is never narrated,
     * whatever the switch.
     */
    fun nothingToNarrate(
        allowModel: Boolean,
        artistsKnown: Boolean,
    ): Boolean =
        lines.isEmpty() ||
            (lines.all { it.source == SourceId.PLAYER } && !(allowModel && artistsKnown))

    /**
     * This set without the lines [leaveOut] picks, fingerprinted with [hasher]. Each line kept keeps
     * its id, so what is written from the smaller set cites the lines of this one (#239: the
     * artist-level lines left out of a prompt). This set itself when nothing is left out.
     */
    fun without(
        hasher: Hasher,
        leaveOut: (EvidenceLine) -> Boolean,
    ): EvidenceSet {
        val kept = lines.filterNot(leaveOut)
        return if (kept.size == lines.size) this else EvidenceSet(kept, fingerprint(kept, hasher), known)
    }

    /**
     * This set with [more] lines after its own, fingerprinted with [hasher]: each line of this set
     * keeps its id, and a source's new lines are numbered after its own (#801: the pages a genre's
     * reason found on the web are `S6`, `S7` after the track's `S1` to `S5`). This set itself with
     * none. The lines added are read by their script with this set's track language (#949).
     */
    fun with(
        more: List<EvidenceDraft>,
        hasher: Hasher,
    ): EvidenceSet {
        if (more.isEmpty()) return this
        return of(lines.map { it.draft() } + more, hasher, known)
    }

    override fun equals(other: Any?): Boolean = other is EvidenceSet && lines == other.lines

    override fun hashCode(): Int = lines.hashCode()

    override fun toString(): String = "EvidenceSet(${ids.joinToString()}, fingerprint=$fingerprint)"

    companion object {
        /**
         * Assigns ids to [drafts] and fingerprints the result with [hasher]. A draft with no language
         * whose script names one gets it, marked [EvidenceDraft.scripted] (#949: a page in Hindi that
         * the English web search found is marked as Hindi too): [Languages.ofLine], with the track's
         * language [known] when given, else the one the search in it found the drafts' lines in.
         */
        fun of(
            drafts: List<EvidenceDraft>,
            hasher: Hasher,
            known: Language? = null,
        ): EvidenceSet {
            val language = known ?: drafts.firstNotNullOfOrNull { it.found }?.let(Languages::ofCode)
            val lines =
                drafts
                    .map { it.scriptedIn(language) }
                    .sortedBy { sourceRank(it.source) }
                    .groupBy { it.source }
                    .flatMap { (source, ofSource) ->
                        ofSource.mapIndexed { index, draft ->
                            EvidenceLine(
                                id = "${source.tag}${index + 1}",
                                source = draft.source,
                                scope = draft.scope,
                                text = draft.text,
                                url = draft.url,
                                retrievedAt = draft.retrievedAt,
                                aggregate = draft.aggregate,
                                about = draft.about,
                                language = draft.language,
                                scripted = draft.scripted,
                            )
                        }
                    }
            return EvidenceSet(lines, fingerprint(lines, hasher), language)
        }

        /**
         * The order of the sources in a set, and so in the fingerprint. It is the declaration
         * order of `SourceId` when this was written, fixed here so a new or moved `SourceId` does
         * not change it; a new source goes at the end.
         */
        val SOURCE_ORDER: List<SourceId> =
            listOf(
                SourceId.MUSICBRAINZ,
                SourceId.WIKIPEDIA,
                SourceId.WIKIDATA,
                SourceId.LASTFM,
                SourceId.DISCOGS,
                SourceId.WEB,
                SourceId.MODEL,
                SourceId.PLAYER,
            )

        // [this], with the language its script names when it has none.
        private fun EvidenceDraft.scriptedIn(known: Language?): EvidenceDraft {
            val written = if (language == null) Languages.ofLine(text, known) else null
            return written?.let { copy(language = it.code, scripted = true) } ?: this
        }

        private fun sourceRank(source: SourceId): Int =
            SOURCE_ORDER.indexOf(source).also { require(it >= 0) { "$source is not in EvidenceSet.SOURCE_ORDER" } }

        // Each field is prefixed with its byte length, so no two different triples encode the same way.
        private fun fingerprint(
            lines: List<EvidenceLine>,
            hasher: Hasher,
        ): String {
            val parts = mutableListOf<ByteArray>()
            for (line in lines) {
                // A line's language only when it has one (#902), so the lines found before it keep their fingerprints.
                val fields =
                    listOfNotNull(fingerprintId(line), line.scope.key, line.text.withoutLoneSurrogates(), line.language)
                for (field in fields) {
                    val bytes = field.encodeToByteArray()
                    parts += "${bytes.size}:".encodeToByteArray()
                    parts += bytes
                }
                parts += NEWLINE
            }
            return hasher.sha256(concat(parts)).toHex()
        }

        // A line's id as the fingerprint spells it: web search's with its tag before #769, `G`.
        private fun fingerprintId(line: EvidenceLine): String =
            if (line.source == SourceId.WEB) {
                SourceId.LEGACY_WEB_TAG + line.id.removePrefix(SourceId.WEB.tag)
            } else {
                line.id
            }

        private val NEWLINE = "\n".encodeToByteArray()

        private fun concat(parts: List<ByteArray>): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var offset = 0
            for (part in parts) {
                part.copyInto(out, offset)
                offset += part.size
            }
            return out
        }

        private const val HEX = "0123456789abcdef"
        private const val NIBBLE = 4
        private const val LOW_NIBBLE = 0x0F

        private fun ByteArray.toHex(): String =
            buildString(size * 2) {
                for (byte in this@toHex) {
                    val value = byte.toInt()
                    append(HEX[(value shr NIBBLE) and LOW_NIBBLE])
                    append(HEX[value and LOW_NIBBLE])
                }
            }
    }
}
