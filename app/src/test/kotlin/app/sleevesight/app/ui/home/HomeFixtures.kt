package app.sleevesight.app.ui.home

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// The tracks and states the home screen's tests share.
internal object HomeFixtures {
    val normalizer = Normalizer(JvmAccentFolder)

    val sundown =
        NowPlaying(
            title = "Sundown",
            artists = "Gordon Lightfoot",
            album = "Sundown",
            duration = 3.minutes,
            player = Settings.SPOTIFY,
            mediaId = "spotify:track:1",
        )

    val carefree = sundown.copy(title = "Carefree Highway", mediaId = "spotify:track:2")

    private val at = Instant.parse("2026-10-17T12:00:00Z")

    fun draft(
        source: SourceId,
        text: String,
        url: String? = null,
        scope: Scope = Scope.SONG,
    ) = EvidenceDraft(source, scope, text, url, at)

    val musicBrainzLine =
        draft(SourceId.MUSICBRAINZ, "Sundown was recorded in 1973.", "https://musicbrainz.org/recording/1")
    val wikipediaLine = draft(SourceId.WIKIPEDIA, "It topped the charts.", "https://en.wikipedia.org/wiki/Sundown")
    val wikidataLine = draft(SourceId.WIKIDATA, "It is a single.", "https://www.wikidata.org/wiki/Q1")

    val evidence: EvidenceSet = EvidenceSet.of(listOf(musicBrainzLine, wikipediaLine, wikidataLine), JvmHasher)

    fun done(
        outcome: Outcome = Outcome.SongArticle,
        evidence: List<EvidenceDraft> = emptyList(),
        cached: Boolean = false,
        unverified: List<EvidenceDraft> = emptyList(),
        rejections: List<Rejection> = emptyList(),
    ) = SourceState.Done(SourceResult(outcome, evidence, 10.milliseconds, unverified, rejections), cached)

    /** [playing]'s state, gathered, with the evidence above and [insight]. */
    fun track(
        playing: NowPlaying = sundown,
        insight: InsightState = InsightState.Idle(canWrite = false),
        sources: Map<SourceId, SourceState> =
            mapOf(
                SourceId.MUSICBRAINZ to done(evidence = listOf(musicBrainzLine)),
                SourceId.WIKIPEDIA to done(evidence = listOf(wikipediaLine, wikidataLine)),
            ),
        evidence: EvidenceSet? = HomeFixtures.evidence,
    ) = TrackState(
        nowPlaying = playing,
        normalized = normalizer.normalize(playing),
        sources = sources,
        evidence = evidence,
        insight = insight,
    )

    /** [reply], checked against [evidence] as written, the model's own knowledge allowed or not. */
    fun written(
        reply: String,
        cached: Boolean = false,
        allowModel: Boolean = false,
    ): InsightState.Written {
        val grounded = (GroundingCheck.check(reply, evidence, allowModel) as InsightReply.Written).grounded
        return InsightState.Written(grounded, cached)
    }
}
