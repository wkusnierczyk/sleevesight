package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.pipeline.SpellingOutcome
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.FoundAs
import app.sleevesight.core.pipeline.SearchWay
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The spelling check on Home (#1293), with the searches faked: *Check the spelling* in *Ask AI*'s
 * place under "MusicBrainz didn’t find this song.", read and asked as picked; *Ask AI* back once it
 * found no other spelling, or MusicBrainz did not find the one it found; and "Found as “…”" under
 * the player's title once the track was looked up in another spelling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class SpellingCheckScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val model = "Claude Haiku 5.5"

    private val feed =
        object : AiSearchFeed {
            val states = SearchKind.entries.associateWith { MutableStateFlow<AiSearch>(AiSearch.Offered) }
            val outcome = MutableStateFlow<SpellingOutcome?>(null)
            val asked = mutableListOf<Pair<SearchKind, SearchFacts>>()
            val stoppedSpelling = mutableListOf<SearchAnswer.Spelling>()

            override fun stopSpelling(
                track: TrackState,
                spelling: SearchAnswer.Spelling,
            ) {
                stoppedSpelling += spelling
                outcome.value = SpellingOutcome.Stopped
            }

            override fun search(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ): Flow<AiSearch> = states.getValue(kind)

            override fun spelled(
                track: TrackState,
                spelling: SearchAnswer.Spelling,
            ): Flow<SpellingOutcome?> = outcome

            override fun ask(
                track: TrackState,
                kind: SearchKind,
                facts: SearchFacts,
                automatic: Boolean,
                genre: String?,
            ): Boolean {
                asked += kind to facts
                return true
            }

            override fun stop(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ) = Unit
        }

    // Where the links opened the settings.
    private val opened = mutableListOf<SettingsTarget>()

    private val actions =
        NowPlayingActions(normalizer::normalize, aiSearches = feed, openSettingsAt = { opened += it })
    private val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)
    private val playing = HomeFixtures.sundown.copy(title = "Sundwon")

    // Sundwon, which MusicBrainz did not find.
    private val notFound: TrackState =
        track(playing, sources = mapOf(SourceId.MUSICBRAINZ to done(Outcome.None), SourceId.LASTFM to done()))

    private fun state(track: TrackState) =
        NowPlayingState(PipelineView(track, aiReady = true), track.nowPlaying, playback, settings = Settings())

    private val current = mutableStateOf(state(notFound))

    private fun show(
        track: TrackState = notFound,
        block: () -> Unit,
    ) {
        current.value = state(track)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(current.value, actions) }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun set(
        kind: SearchKind,
        search: AiSearch,
    ) {
        feed.states.getValue(kind).value = search
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun shows(line: String) = compose.onAllNodesWithText(line).fetchSemanticsNodes().isNotEmpty()

    private val check = spellingTag(AI_SEARCH_TAG)

    @Test
    fun checkTheSpellingStandsInAskAisPlaceReadAsPickedAndAsksWithThePlayersTitle() =
        show {
            tap(DATES_CHIP_TAG)
            assertTrue(shows(text(R.string.home_musicbrainz_not_found)))
            assertEquals(0, count(AI_SEARCH_TAG))
            compose
                .onNodeWithTag(check)
                .performScrollTo()
                .assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Check the spelling")),
                ).assert(
                    SemanticsMatcher("click labelled as picked") {
                        it.config.getOrNull(SemanticsActions.OnClick)?.label ==
                            "check the spelling on the web with your AI service, a paid call"
                    },
                )
            tap(check)
            val (kind, facts) = feed.asked.single()
            assertEquals(SearchKind.SPELLING, kind)
            assertEquals("Sundwon", facts.title)
            assertEquals(listOf("Gordon Lightfoot"), facts.artists)
            set(SearchKind.SPELLING, AiSearch.Searching(web = true))
            compose.onNodeWithTag(spellingTag(AI_SEARCHING_TAG)).assertTextEquals("Checking the spelling on the web…")
            assertEquals(1, count(spellingTag(AI_SEARCH_STOP_TAG)))
        }

    @Test
    fun noOtherSpellingSaysSoAndAskAiFollows() =
        show {
            tap(DATES_CHIP_TAG)
            set(SearchKind.SPELLING, AiSearch.NothingFound(model, SearchWay(web = true)))
            compose
                .onNodeWithTag(spellingTag(AI_SEARCH_NOTHING_TAG))
                .assertTextEquals("Claude Haiku 5.5 found no other spelling on the web.")
            assertEquals(0, count(AUTO_ASKED_LINK_TAG))
            assertEquals(1, count(AI_SEARCH_TAG))
            assertEquals(0, count(check))
        }

    @Test
    fun noOtherSpellingFromACheckThatRanByItselfSaysSoAtItsEndLinkedToTheSetting() =
        show {
            tap(DATES_CHIP_TAG)
            set(SearchKind.SPELLING, AiSearch.NothingFound(model, SearchWay(automatic = true, web = true)))
            compose
                .onNodeWithTag(spellingTag(AI_SEARCH_NOTHING_TAG))
                .assertTextEquals("Claude Haiku 5.5 found no other spelling on the web; asked automatically.")
            followAutoAskedLink()
        }

    // The note's link (#956): read "asked automatically", named with where it goes, and opening the
    // settings at *Ask AI automatically*.
    private fun followAutoAskedLink() {
        val link = compose.onNodeWithTag(AUTO_ASKED_LINK_TAG)
        link.assertTextEquals("asked automatically")
        val click = link.fetchSemanticsNode().config[SemanticsActions.OnClick]
        assertEquals("open the Ask AI automatically setting", click.label)
        link.performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(SettingsTarget.searchWithAi), opened)
    }

    @Test
    fun aSpellingMusicBrainzDidNotFindSaysSoAndAskAiFollows() =
        show {
            tap(DATES_CHIP_TAG)
            val spelling = SearchAnswer.Spelling("Sundown", emptyList(), "https://en.wikipedia.org/wiki/Sundown")
            set(SearchKind.SPELLING, AiSearch.Answered(spelling, model, SearchWay(web = true)))
            // Asked of MusicBrainz still: the check runs on.
            compose.onNodeWithTag(spellingTag(AI_SEARCHING_TAG)).assertTextEquals("Checking the spelling on the web…")
            assertEquals(0, count(AI_SEARCH_TAG))
            feed.outcome.value = SpellingOutcome.NotFound
            compose.waitForIdle()
            compose
                .onNodeWithTag(spellingTag(AI_SEARCH_NOTHING_TAG))
                .assertTextEquals("MusicBrainz didn’t find it as “Sundown” either.")
            assertEquals(0, count(AUTO_ASKED_LINK_TAG))
            assertEquals(1, count(AI_SEARCH_TAG))
        }

    @Test
    fun aSpellingMusicBrainzDidNotFindFromACheckThatRanByItselfSaysSoAtItsEnd() =
        show {
            tap(DATES_CHIP_TAG)
            val spelling = SearchAnswer.Spelling("Sundown", emptyList(), "https://en.wikipedia.org/wiki/Sundown")
            set(SearchKind.SPELLING, AiSearch.Answered(spelling, model, SearchWay(automatic = true, web = true)))
            feed.outcome.value = SpellingOutcome.NotFound
            compose.waitForIdle()
            compose
                .onNodeWithTag(spellingTag(AI_SEARCH_NOTHING_TAG))
                .assertTextEquals("MusicBrainz didn’t find it as “Sundown” either; asked automatically.")
            followAutoAskedLink()
        }

    @Test
    fun stopStaysWhileMusicBrainzIsAskedAndCancelsIt() =
        show {
            tap(DATES_CHIP_TAG)
            val spelling = SearchAnswer.Spelling("Sundown", emptyList(), "https://en.wikipedia.org/wiki/Sundown")
            set(SearchKind.SPELLING, AiSearch.Answered(spelling, model, SearchWay(web = true)))
            feed.outcome.value = SpellingOutcome.Asking
            compose.waitForIdle()
            assertEquals(1, count(spellingTag(AI_SEARCH_STOP_TAG)))
            tap(spellingTag(AI_SEARCH_STOP_TAG))
            assertEquals(listOf(spelling), feed.stoppedSpelling)
            // Stopped: *Ask AI* back, no line.
            assertEquals(1, count(AI_SEARCH_TAG))
            assertEquals(0, count(spellingTag(AI_SEARCH_STOP_TAG)))
        }

    @Test
    fun foundInAnotherSpellingHomeKeepsThePlayersTitleWithTheLineUnderIt() {
        val found =
            notFound.copy(
                normalized = normalizer.normalize(playing.copy(title = "Sundown")),
                foundAs = FoundAs("Sundown", playing.artists, notFound.normalized, model),
                sources = mapOf(SourceId.MUSICBRAINZ to done(), SourceId.LASTFM to done()),
            )
        show(found) {
            assertTrue(shows("Sundwon"))
            compose.onNodeWithTag(FOUND_AS_TAG).assertTextEquals("Found as “Sundown”")
            assertEquals(0, count(check))
        }
    }
}
