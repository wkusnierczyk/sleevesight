package app.sleevesight.core.lyrics

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.SourceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * *Lyrics*' first step of *Ask AI* (#801): a lyrics page among the track's web search results is
 * shown with no AI call, in the search's place, chosen as MusicBrainz's are; none without one.
 */
class WebLyricsTest {
    private val at = Instant.parse("2026-11-01T10:00:00Z")
    private val playing = NowPlaying(title = "The Black Oak", artists = "Emily Jane White", player = "p")

    private fun track(vararg urls: String): TrackState {
        val evidence = urls.map { EvidenceDraft(SourceId.WEB, Scope.WEB, "A line.", it, at) }
        val web =
            SourceState.Done(
                SourceResult(Outcome.SongArticle, evidence, Duration.ZERO, trackOnly = true),
                cached = false,
            )
        val notFound = SourceState.Done(SourceResult(Outcome.None, emptyList(), Duration.ZERO), cached = false)
        return TrackState(
            playing,
            Normalizer(JvmAccentFolder).normalize(playing),
            sources = mapOf(SourceId.WEB to web, SourceId.MUSICBRAINZ to notFound),
        )
    }

    @Test
    fun aLyricsPageAmongTheWebResultsIsTheNoLinksWebPage() {
        val genius = "https://genius.com/Emily-jane-white-the-black-oak-lyrics"
        val links =
            lyricsLinks(track("https://example.org/review", genius), search = true, accentFolder = JvmAccentFolder)
        assertEquals(
            LyricsLinks.NoLink(
                NoLinkReason.NotFound,
                LyricsLink(LyricsSite.GENIUS, searchUrl(Normalizer(JvmAccentFolder).normalize(playing))),
                web = listOf(LyricsLink(LyricsSite.GENIUS, genius)),
            ),
            links,
        )
    }

    @Test
    fun withNoneThereIsNoWebPage() {
        val links = lyricsLinks(track("https://example.org/review"), search = false, accentFolder = JvmAccentFolder)
        assertEquals(LyricsLinks.NoLink(NoLinkReason.NotFound, null), links)
    }
}
