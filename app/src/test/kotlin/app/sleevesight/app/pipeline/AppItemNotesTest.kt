package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.AlbumFound
import app.sleevesight.core.sources.AlbumLookup
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The song's, the artist's and the album's insights in the app (#522): each written on its own, a
 * call each; the artist's and the album's found again with no call on the next track of the album;
 * nothing written for an item never opened; forgotten on *Clear history*.
 */
class AppItemNotesTest {
    private val provider = CountingProvider().apply { reply = { "Gordon Lightfoot wrote it [W1]." } }
    private val cache = CardCache()
    private val albums =
        AlbumLookup {
            AlbumFound(
                listOf(HomeFixtures.draft(SourceId.WIKIPEDIA, "Sundown is his tenth album.", scope = Scope.RELEASE)),
            )
        }
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = cache,
                albums = albums,
            ),
        )
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC))

    private fun TestScope.notes(epoch: ClearEpoch = ClearEpoch()) =
        AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder), epoch)

    // A track of Sundown, with a song line of its own and the artist's line.
    private fun track(playing: NowPlaying): TrackState {
        val drafts =
            listOf(
                HomeFixtures.draft(SourceId.MUSICBRAINZ, "${playing.title} was recorded in Toronto."),
                HomeFixtures.draft(SourceId.WIKIPEDIA, "Gordon Lightfoot was a Canadian singer.", scope = Scope.ARTIST),
            )
        return HomeFixtures.track(playing, evidence = EvidenceSet.of(drafts, JvmHasher)).copy(
            resolution =
                Resolution(
                    recordingMbid = "rec-${playing.title}",
                    artists = listOf(ResolvedArtist("Gordon Lightfoot", "mb-gordon")),
                    releaseDates = listOf(ReleaseDate("Sundown", "1974", "rg-sundown", "Album")),
                ),
        )
    }

    private suspend fun AppItemNotes.settled(
        track: TrackState,
        item: InsightItem,
    ): ItemShown =
        item(track, item).first {
            it is ItemShown.Offered ||
                (it as? ItemShown.Shown)?.state is InsightState.Written
        }

    @Test
    fun `the artist's insight is written once for an album's tracks, and the next track's opens with no call`() =
        runTest {
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            assertEquals(ItemShown.Offered, notes.settled(first, InsightItem.ARTIST))
            notes.write(first, InsightItem.ARTIST)
            advanceUntilIdle()
            assertTrue(notes.settled(first, InsightItem.ARTIST) is ItemShown.Shown)
            assertEquals(1, provider.requests)

            val next = track(HomeFixtures.carefree)
            val shown = notes.settled(next, InsightItem.ARTIST) as ItemShown.Shown
            assertTrue(shown.state is InsightState.Written)
            notes.write(next, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
        }

    @Test
    fun `the album's insight is written once for its tracks, and an item never opened costs nothing`() =
        runTest {
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.ALBUM)
            advanceUntilIdle()
            notes.write(track(HomeFixtures.carefree), InsightItem.ALBUM)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            // The song's was never opened: no call for it.
            assertEquals(0, provider.requestsFor("the song's own story"))
        }

    @Test
    fun `clear history forgets what was shown`() =
        runTest {
            val notes = notes()
            val first = track(HomeFixtures.sundown)
            notes.write(first, InsightItem.SONG)
            advanceUntilIdle()
            assertTrue(notes.settled(first, InsightItem.SONG) is ItemShown.Shown)
            notes.forget()
            cache.clearCards()
            assertEquals(ItemShown.Offered, notes.settled(first, InsightItem.SONG))
        }
}
