package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.TrackLanguageSearch
import app.sleevesight.core.sources.ItemLanguageLines
import app.sleevesight.core.sources.LanguageSubject
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The artist's and the album's lines in the track's language in the app (#939): kept once a track's
 * evidence is in, whether or not their insights are opened, and taken on the artist's and the
 * album's other tracks; their evidence gathered again when *Search in the track's language* or *Keep
 * what sources said about each artist* changes.
 */
class AppLanguageLinesNotesTest {
    private val gordon = LanguageSubject.Artist("Gordon Lightfoot", "mb-gordon")
    private val korean =
        HomeFixtures.draft(SourceId.WIKIPEDIA, "고든 라이트풋은 가수이다.", scope = Scope.ARTIST).copy(language = "ko")
    private val release =
        HomeFixtures
            .draft(SourceId.WIKIPEDIA, "선다운은 앨범이다.", scope = Scope.RELEASE)
            .copy(about = "Sundown", language = "ko")

    // The lines kept per subject, the first found in each language, as `LanguageLinesSource` keeps them.
    private class Lines : ItemLanguageLines {
        val entries = mutableMapOf<LanguageSubject, List<EvidenceDraft>>()

        override suspend fun kept(subject: LanguageSubject): List<EvidenceDraft> = entries[subject].orEmpty()

        override suspend fun keep(
            subject: LanguageSubject,
            language: String,
            lines: List<EvidenceDraft>,
        ) {
            val now = entries[subject].orEmpty()
            if (lines.isNotEmpty() && now.none { it.language == language }) entries[subject] = now + lines
        }

        override suspend fun renew(subject: LanguageSubject) = Unit
    }

    private val kept = Lines()
    private val provider = CountingProvider().apply { reply = { "Gordon Lightfoot was a singer [W1]." } }

    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = CardCache(),
                languageLines = kept,
            ),
        )
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC))

    private fun TestScope.notes() = AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder))

    // A track of Sundown, with a song line of its own, the artist's line in English, and [found], what
    // its search in the track's language found.
    private fun track(
        playing: NowPlaying,
        found: List<EvidenceDraft> = emptyList(),
    ): TrackState {
        val drafts =
            listOf(
                HomeFixtures.draft(SourceId.MUSICBRAINZ, "${playing.title} was recorded in Toronto."),
                HomeFixtures.draft(SourceId.WIKIPEDIA, ENGLISH, scope = Scope.ARTIST),
            ) + found
        val sources = drafts.groupBy { it.source }.mapValues { (_, lines) -> HomeFixtures.done(evidence = lines) }
        return HomeFixtures.track(playing, sources = sources, evidence = EvidenceSet.of(drafts, JvmHasher)).copy(
            resolution =
                Resolution(
                    recordingMbid = "rec-${playing.title}",
                    artists = listOf(ResolvedArtist("Gordon Lightfoot", "mb-gordon")),
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

    private suspend fun AppItemNotes.artistLines(track: TrackState): List<String>? {
        settled(track, InsightItem.ARTIST)
        return artistEvidence(track).first()?.lines?.map { it.text }
    }

    @Test
    fun `a track searched with no insight opened keeps its lines for the artist's and the album's other tracks`() =
        runTest {
            val notes = notes()
            backgroundScope.launch { notes.keepAhead(flowOf(track(HomeFixtures.sundown, listOf(korean, release)))) }
            runCurrent()
            assertEquals(listOf(korean), kept.entries[gordon])

            val next = track(HomeFixtures.carefree)
            assertEquals(listOf(ENGLISH, korean.text), notes.artistLines(next))
            notes.settled(next, InsightItem.ALBUM)
            assertEquals(
                listOf(release.text),
                notes
                    .albumEvidence(next)
                    .first()
                    ?.lines
                    ?.map { it.text },
            )
        }

    @Test
    fun `nothing is kept ahead before the parts come, with no AI service or insights off, until they do`() =
        runTest {
            val made = checkNotNull(parts.value)
            parts.value = null
            settings.value = settings.value?.copy(writeInsights = false)
            val notes = notes()
            backgroundScope.launch { notes.keepAhead(flowOf(track(HomeFixtures.sundown, listOf(korean, release)))) }
            runCurrent()

            // Parts with no AI service ready, then insights off: nothing is written from the lines.
            parts.value =
                PipelineParts(made.normalizer, made.adapters, made.resolver, made.hasher, languageLines = kept)
            runCurrent()
            settings.value = settings.value?.copy(writeInsights = true)
            runCurrent()
            parts.value = made
            settings.value = settings.value?.copy(writeInsights = false)
            runCurrent()
            assertTrue(kept.entries.isEmpty())

            // Insights on, for the same track: kept now.
            settings.value = settings.value?.copy(writeInsights = true)
            runCurrent()
            assertEquals(listOf(korean), kept.entries[gordon])
        }

    @Test
    fun `nothing is kept ahead while the search in the language is off, and the same track is kept once it is on`() =
        runTest {
            settings.value = settings.value?.copy(trackLanguageSearch = TrackLanguageSearch.OFF)
            val notes = notes()
            backgroundScope.launch { notes.keepAhead(flowOf(track(HomeFixtures.sundown, listOf(korean, release)))) }
            runCurrent()
            assertTrue(kept.entries.isEmpty())

            settings.value = settings.value?.copy(trackLanguageSearch = TrackLanguageSearch.ALWAYS)
            runCurrent()
            assertEquals(listOf(korean), kept.entries[gordon])
        }

    @Test
    fun `the artist's lines kept on another track are taken, and Off gathers it again without them`() =
        runTest {
            kept.entries[gordon] = listOf(korean)
            val notes = notes()
            val track = track(HomeFixtures.sundown)
            assertEquals(listOf(ENGLISH, korean.text), notes.artistLines(track))

            settings.value = settings.value?.copy(trackLanguageSearch = TrackLanguageSearch.OFF)
            assertEquals(listOf(ENGLISH), notes.artistLines(track))
        }

    @Test
    fun `Keep what sources said about each artist switched on and off again gathers the evidence again`() =
        runTest {
            kept.entries[gordon] = listOf(korean)
            val notes = notes()
            val track = track(HomeFixtures.sundown)
            assertEquals(listOf(ENGLISH, korean.text), notes.artistLines(track))

            // On: the lines held in memory dropped, as the app's store drops them.
            kept.entries.clear()
            settings.value = settings.value?.copy(keepArtistResults = true)
            notes.artistKeepingChanged()
            assertEquals(listOf(ENGLISH), notes.artistLines(track))

            // Off again: not the evidence gathered before under the same key, but what is kept now, which
            // the write is made from.
            settings.value = settings.value?.copy(keepArtistResults = false)
            notes.artistKeepingChanged()
            notes.settled(track, InsightItem.ARTIST)
            notes.write(track, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertFalse(korean.text in provider.prompts.single())
        }

    private companion object {
        const val ENGLISH = "Gordon Lightfoot was a Canadian singer."
    }
}
