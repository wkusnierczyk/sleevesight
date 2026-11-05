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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The artist's and the album's lines in the track's language in the app (#939): those kept for the
 * artist on another track are taken on this one, and the evidence is gathered again when *Search in
 * the track's language* or *Keep what sources said about each artist* changes.
 */
class AppLanguageLinesNotesTest {
    private val gordon = LanguageSubject.Artist("Gordon Lightfoot", "mb-gordon")
    private val korean =
        HomeFixtures.draft(SourceId.WIKIPEDIA, "고든 라이트풋은 가수이다.", scope = Scope.ARTIST).copy(language = "ko")

    // While set, the store below holds nothing, as the artist cache holds nothing the memory held.
    private var gone = false

    // Kept for the artist on another track; this test's track was not searched in the language.
    private val kept =
        object : ItemLanguageLines {
            override suspend fun kept(subject: LanguageSubject): List<EvidenceDraft> =
                if (subject == gordon && !gone) listOf(korean) else emptyList()

            override suspend fun keep(
                subject: LanguageSubject,
                language: String,
                lines: List<EvidenceDraft>,
            ) = Unit

            override suspend fun renew(subject: LanguageSubject) = Unit
        }

    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(CountingProvider(), Secret("key")),
                cache = CardCache(),
                languageLines = kept,
            ),
        )
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC))

    private fun TestScope.notes() = AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder))

    // A track of Sundown, with a song line of its own and the artist's line in English.
    private fun track(playing: NowPlaying): TrackState {
        val drafts =
            listOf(
                HomeFixtures.draft(SourceId.MUSICBRAINZ, "${playing.title} was recorded in Toronto."),
                HomeFixtures.draft(SourceId.WIKIPEDIA, ENGLISH, scope = Scope.ARTIST),
            )
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

    private suspend fun AppItemNotes.artistLines(track: TrackState): List<String>? =
        artistEvidence(track).first()?.lines?.map { it.text }

    @Test
    fun `the artist's lines kept on another track are taken, and Off gathers it again without them`() =
        runTest {
            val notes = notes()
            val track = track(HomeFixtures.sundown)
            notes.settled(track, InsightItem.ARTIST)
            assertEquals(listOf(ENGLISH, korean.text), notes.artistLines(track))

            settings.value = settings.value?.copy(trackLanguageSearch = TrackLanguageSearch.OFF)
            notes.settled(track, InsightItem.ARTIST)
            assertEquals(listOf(ENGLISH), notes.artistLines(track))
        }

    @Test
    fun `switching Keep what sources said about each artist gathers the artist's evidence again`() =
        runTest {
            val notes = notes()
            val track = track(HomeFixtures.sundown)
            notes.settled(track, InsightItem.ARTIST)
            assertEquals(listOf(ENGLISH, korean.text), notes.artistLines(track))

            // Where the lines are kept changed: gathered again, from what the store holds now (none).
            gone = true
            settings.value = settings.value?.copy(keepArtistResults = true)
            notes.settled(track, InsightItem.ARTIST)
            assertEquals(listOf(ENGLISH), notes.artistLines(track))
        }

    private companion object {
        const val ENGLISH = "Gordon Lightfoot was a Canadian singer."
    }
}
