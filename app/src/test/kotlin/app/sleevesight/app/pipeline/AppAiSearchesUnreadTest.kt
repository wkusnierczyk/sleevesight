package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reply with no JSON object that parses (#1446) is logged with its shape and start in developer
 * mode only, and a lyrics reply in prose shows as nothing found, not a failure.
 */
class AppAiSearchesUnreadTest {
    private val provider = CountingProvider().apply { reply = { DECLINE } }
    private val clock = MovedClock()
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC))
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = CardCache(clock = clock) { settings.value?.historyLifetime },
            ),
        )
    private val answers = AiSearchAnswers(GenreVocabulary.load(JvmResourceReader), JvmAccentFolder, clock)
    private val track = HomeFixtures.track()
    private val facts = SearchFacts(track.normalized.title, track.normalized.artists)
    private val logged = mutableListOf<String>()

    private fun TestScope.searches() =
        AppAiSearches(parts, settings, answers, this, clock = clock, developerLog = { logged += it })

    @Test
    fun `a lyrics reply in prose is nothing found, and logged in developer mode`() =
        runTest {
            settings.value = Settings(aiProvider = ProviderId.ANTHROPIC, developerMode = true)
            val searches = searches()
            searches.ask(track, SearchKind.LYRICS, facts)
            advanceUntilIdle()
            assertTrue(searches.search(track, SearchKind.LYRICS).first() is AiSearch.NothingFound)
            assertEquals(
                listOf(
                    "Unread AI reply: kind=lyrics shape=no_braces read=nothing_found length=${DECLINE.length} " +
                        "start=$DECLINE",
                ),
                logged,
            )
        }

    @Test
    fun `a reply that cannot be read is logged in developer mode only`() =
        runTest {
            provider.reply = { "{\"album_date\": " }
            val searches = searches()
            searches.ask(track, SearchKind.DATES, facts)
            advanceUntilIdle()
            assertEquals(emptyList<String>(), logged)
            settings.value = Settings(aiProvider = ProviderId.ANTHROPIC, developerMode = true)
            searches.ask(track, SearchKind.DATES, facts)
            advanceUntilIdle()
            assertEquals(1, logged.size)
            val line = logged.single()
            assertTrue(line, line.startsWith("Unread AI reply: kind=dates shape=unparsed read=failed"))
        }

    private companion object {
        const val DECLINE = "I can't share song lyrics, but you can find them on licensed lyrics sites."
    }
}
