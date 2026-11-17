package app.sleevesight.app.ui.lab

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.synthesis.PromptKind

/**
 * What a comparison is about, as the insight shown knows it: its [kind], the track ([title],
 * [artist], [album]), the [card] it was written as (`WrittenBy.card`), by which the lab finds the
 * request that wrote it (`PromptLab.sent`), and, to check each version as the insight is checked, its [evidence] and
 * whether the model's own knowledge was allowed ([allowModel]); no evidence, and each version shows
 * as written.
 */
internal data class LabSubject(
    val kind: PromptKind,
    val title: String,
    val artist: String?,
    val album: String?,
    val card: String?,
    val evidence: EvidenceSet? = null,
    val allowModel: Boolean = false,
)

/**
 * What a comparison of [insight], [kind]'s, is about, on [track]: the card it was written as,
 * checked against [evidence], the insight's.
 */
internal fun labSubject(
    kind: PromptKind,
    track: TrackState,
    insight: InsightState.Written,
    evidence: EvidenceSet? = track.evidence,
): LabSubject =
    LabSubject(
        kind = kind,
        title = track.nowPlaying.title,
        artist = track.nowPlaying.artists,
        album = track.nowPlaying.album,
        card = insight.writtenBy?.card,
        evidence = evidence,
        allowModel = insight.grounded.allowModel,
    )
