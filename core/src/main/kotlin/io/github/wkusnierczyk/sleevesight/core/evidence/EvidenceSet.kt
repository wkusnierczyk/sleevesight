package io.github.wkusnierczyk.sleevesight.core.evidence

/**
 * A track's evidence, with ids assigned and a fingerprint (`design/architecture.md`, *Evidence*).
 *
 * Ids: the lines are sorted by source, in [io.github.wkusnierczyk.sleevesight.core.model.SourceId]
 * order, keeping each source's own order, and numbered from 1 within each source. The sort makes
 * the ids independent of the order the sources land in, so the same retrieval gives the same ids,
 * as long as each adapter emits its lines in a deterministic order (the order of its response).
 * A source's ids do not depend on the other sources' lines.
 *
 * [fingerprint] is a SHA-256 over the ordered (id, scope, text) triples, in lowercase hex. It is
 * part of the synthesis cache key (R2): a cached insight is reused only for the exact evidence it
 * was written from. The URL and the retrieval time are not part of it, so a refetch that finds
 * the same lines keeps the cached insight.
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
                    .sortedBy { it.source.ordinal }
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

        // Each field is length-prefixed, so no two different triples encode the same way.
        private fun fingerprint(
            lines: List<EvidenceLine>,
            hasher: Hasher,
        ): String {
            val encoded =
                buildString {
                    for (line in lines) {
                        for (field in listOf(line.id, line.scope.name, line.text)) {
                            append(field.length).append(':').append(field)
                        }
                        append('\n')
                    }
                }
            return hasher.sha256(encoded.encodeToByteArray()).toHex()
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
