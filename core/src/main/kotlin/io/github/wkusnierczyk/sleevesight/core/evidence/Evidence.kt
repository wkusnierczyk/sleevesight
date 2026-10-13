package io.github.wkusnierczyk.sleevesight.core.evidence

import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import kotlin.time.Instant

/**
 * What an evidence line is about. The model is told, so it never credits an original song's
 * facts to a cover act (`design/design.md`, *Evidence model*).
 */
enum class Scope {
    SONG,
    ARTIST,
    ORIGINAL_SONG,
    RELEASE,
    WEB,
}

/**
 * An evidence line as an adapter makes it, before it has an id.
 *
 * @property text one claim: templated from structured data, or a short attributed quote, never a
 *   whole passage (D1).
 */
data class EvidenceDraft(
    val source: SourceId,
    val scope: Scope,
    val text: String,
    val url: String?,
    val retrievedAt: Instant,
)

/**
 * An evidence line with its id, the source tag plus its position among that source's lines, from
 * 1: `W1`, `L3`, `G2`, `P1`. Ids are assigned by [EvidenceSet].
 */
data class EvidenceLine(
    val id: String,
    val source: SourceId,
    val scope: Scope,
    val text: String,
    val url: String?,
    val retrievedAt: Instant,
)
