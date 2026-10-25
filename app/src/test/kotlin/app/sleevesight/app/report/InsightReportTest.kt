package app.sleevesight.app.report

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.WrittenBy
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.ResolvedWork
import app.sleevesight.core.sources.SourceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What a report of a problem with an insight carries (#390): what the issue lists, and nothing more. */
class InsightReportTest {
    private val by = WrittenBy(5, ProviderId.ANTHROPIC, "claude-opus-5-5", ReasoningEffort.HIGH)

    private val resolution =
        Resolution(
            recordingMbid = "rec-1",
            artists = listOf(ResolvedArtist("Gordon Lightfoot", "art-1")),
            firstRelease = FirstRelease("1974", "Sundown", "rg-1", "rel-1", "Album"),
            work = ResolvedWork("work-1", "Sundown"),
        )

    private fun insight(held: Set<String>? = null): InsightState.Written =
        written("It was recorded in 1973 [MB1]. It topped the charts [W1].", held = held).copy(writtenBy = by)

    private val playing = sundown.copy(duration = 225.seconds)

    @Test
    fun theReportCarriesTheReasonNoteTrackIdsServiceModelInsightAndEvidenceAndNothingElse() {
        val mail =
            insightReport(
                track(playing = playing).copy(resolution = resolution),
                insight(),
                ReportReason.WRONG,
                "  It charted in 1974.  ",
            )

        assertEquals(Mail.SUPPORT, mail.to)
        assertEquals("Sleevesight report: Wrong: Sundown by Gordon Lightfoot", mail.subject)
        assertEquals(
            """
            A problem with an insight, reported from Sleevesight.

            Reason: Wrong
            Note: It charted in 1974.

            Track, as the player reported it
            Title: Sundown
            Artists: Gordon Lightfoot
            Album: Sundown
            Length: 3:45

            MusicBrainz ids
            Recording: rec-1
            Artist: Gordon Lightfoot: art-1
            Work: work-1
            First release group: rg-1
            First release: rel-1

            Written with
            Prompt version: 5
            AI service: Anthropic
            Model: claude-opus-5-5
            Reasoning effort: high

            Insight
            It was recorded in 1973 [MB1]. It topped the charts [W1].

            Evidence
            [MB1] (MusicBrainz, song) Sundown was recorded in 1973.
              https://musicbrainz.org/recording/1
            [W1] (Wikipedia, song) It topped the charts.
              https://en.wikipedia.org/wiki/Sundown
            [WD1] (Wikidata, song) It is a single.
              https://www.wikidata.org/wiki/Q1

            """.trimIndent(),
            mail.body,
        )
    }

    @Test
    fun neitherThePlayerNorItsMediaIdIsInTheReport() {
        val mail = insightReport(track(playing = playing), insight(), ReportReason.OFFENSIVE, "")
        val all = mail.asText()

        assertFalse(sundown.player in all)
        assertFalse(sundown.mediaId!! in all)
        assertTrue("Note: none" in all)
        assertTrue("Reason: Offensive or inappropriate" in all)
    }

    @Test
    fun onlyTheLinesThePromptHeldAreInTheReport() {
        val mail = insightReport(track(), insight(held = setOf("MB1", "W1")), ReportReason.OTHER, "")

        assertTrue("[W1] (Wikipedia, song)" in mail.body)
        assertFalse("[WD1]" in mail.body)
    }

    @Test
    fun aTrackWhoseFindingsWereKeptNamesTheArtistIdsMusicBrainzKeptAndSaysNothingWasResolved() {
        val kept =
            done(cached = true).copy(
                result =
                    SourceResult(
                        outcome = Outcome.SongArticle,
                        evidence = listOf(HomeFixtures.musicBrainzLine),
                        latency = 10.milliseconds,
                        labelArtists = listOf(ResolvedArtist("Gordon Lightfoot", "art-1")),
                    ),
            )
        val mail =
            insightReport(track(sources = mapOf(SourceId.MUSICBRAINZ to kept)), insight(), ReportReason.WRONG, "")

        assertTrue("Not resolved in this run: the sources' findings were kept from before.\n" in mail.body)
        assertTrue("Artist: Gordon Lightfoot: art-1\n" in mail.body)
    }

    @Test
    fun anInsightWithNoRecordOfWhatWroteItSaysSo() {
        val mail = insightReport(track(), insight().copy(writtenBy = null), ReportReason.WRONG, "")

        assertTrue("Prompt version: not known\nAI service: not known\nModel: not known\n" in mail.body)
    }

    @Test
    fun theServicesDefaultEffortIsNamed() {
        val mail = insightReport(track(), insight().copy(writtenBy = by.copy(effort = null)), ReportReason.WRONG, "")

        assertTrue("Reasoning effort: the service's default\n" in mail.body)
    }
}
