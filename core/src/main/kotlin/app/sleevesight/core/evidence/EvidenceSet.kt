package app.sleevesight.core.evidence

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
 * [fingerprint] is a SHA-256 over the ordered (id, scope, text) triples, in lowercase hex. It is
 * part of the synthesis cache key (R2): a cached insight is reused only for the exact evidence it
 * was written from. The URL and the retrieval time are not part of it, so a refetch that finds
 * the same lines keeps the cached insight. Encoding, per line in order: for each of the id,
 * [Scope.key] and the text, its UTF-8 byte length in decimal, `:`, and its UTF-8 bytes; then
 * `\n`. The text is well-formed Unicode (see [EvidenceDraft]), so the encoding is the same on every
 * platform. Changing the encoding, [SOURCE_ORDER], a tag or a scope key changes every fingerprint,
 * which empties the synthesis cache in effect; a golden test pins them.
 */
class EvidenceSet private constructor(
    val lines: List<EvidenceLine>,
    val fingerprint: String,
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
     * sentence, cited `[M]`, which the user opted into. But only once the report names the track's
     * artists ([artistsKnown]): some players report the title and album before the artists, and a
     * card written from that early report would stand for the track, its `[M]` sentence written
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

    override fun equals(other: Any?): Boolean = other is EvidenceSet && lines == other.lines

    override fun hashCode(): Int = lines.hashCode()

    override fun toString(): String = "EvidenceSet(${ids.joinToString()}, fingerprint=$fingerprint)"

    companion object {
        /** Assigns ids to [drafts] and fingerprints the result with [hasher]. */
        fun of(
            drafts: List<EvidenceDraft>,
            hasher: Hasher,
        ): EvidenceSet {
            val lines =
                drafts
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
                            )
                        }
                    }
            return EvidenceSet(lines, fingerprint(lines, hasher))
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

        private fun sourceRank(source: SourceId): Int =
            SOURCE_ORDER.indexOf(source).also { require(it >= 0) { "$source is not in EvidenceSet.SOURCE_ORDER" } }

        // Each field is prefixed with its byte length, so no two different triples encode the same way.
        private fun fingerprint(
            lines: List<EvidenceLine>,
            hasher: Hasher,
        ): String {
            val parts = mutableListOf<ByteArray>()
            for (line in lines) {
                for (field in listOf(line.id, line.scope.key, line.text.withoutLoneSurrogates())) {
                    val bytes = field.encodeToByteArray()
                    parts += "${bytes.size}:".encodeToByteArray()
                    parts += bytes
                }
                parts += NEWLINE
            }
            return hasher.sha256(concat(parts)).toHex()
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
