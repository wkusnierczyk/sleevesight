package app.sleevesight.app.ui.lab

import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.synthesis.PromptKind

/**
 * A text of [kind] on screen, as shown ([text]), whose credit names the prompt lab's variant that
 * wrote it (#1003, `CreditRow`): given in the debug build only.
 */
internal data class LabText(
    val kind: PromptKind,
    val text: String,
)

/**
 * What a comparison is about, as the insight shown knows it: its [kind], the track ([title],
 * [artist], [album]), the [text] shown, by which the lab finds the request that wrote it
 * (`PromptLab.written`), and, to check each version as the insight is checked, its [evidence] and
 * whether the model's own knowledge was allowed ([allowModel]); no evidence, and each version shows
 * as written.
 */
internal data class LabSubject(
    val kind: PromptKind,
    val title: String,
    val artist: String?,
    val album: String?,
    val text: String,
    val evidence: EvidenceSet? = null,
    val allowModel: Boolean = false,
)

/**
 * What a comparison of [insight], [kind]'s, is about, on [track]: its text as shown, checked against
 * [evidence], the insight's.
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
        text = insight.grounded.text,
        evidence = evidence,
        allowModel = insight.grounded.allowModel,
    )
