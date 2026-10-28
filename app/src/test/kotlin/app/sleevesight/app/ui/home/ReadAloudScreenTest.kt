package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.speech.FakeFocus
import app.sleevesight.app.speech.FakeSpeech
import app.sleevesight.app.speech.ReadAloud
import app.sleevesight.app.speech.Reading
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * *Read aloud* on Home (#325): under a written insight, at the end of the split button's row, a
 * 48 dp icon button named *Read aloud*, with no **AI** mark; a tap reads the insight, and the button
 * becomes *Stop reading aloud*, which stops it. None beside an insight not written. At twice the
 * font size on a narrow phone it keeps its place and size, and the split button wraps beside it.
 * Under a genre's written reason, the same button reads its question, then the reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadAloudScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val speech = FakeSpeech()
    private val readAloud =
        ReadAloud(
            speech,
            FakeFocus(),
            MutableStateFlow(Settings()),
            SpokenWords("Unverified:") { title, artists -> "$title, by $artists." },
            { false },
            { _, _ -> },
            CoroutineScope(Dispatchers.Main.immediate),
        ) { Locale.UK }

    private val insight = written("Sundown was recorded in 1973 [MB1]. It was a hit in Canada.")

    private fun home(
        shown: InsightState,
        block: () -> Unit,
    ) {
        val actions = NowPlayingActions(normalizer::normalize, readAloud = readAloud)
        val view = PipelineView(track(insight = shown), aiReady = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)
                    val state = NowPlayingState(view, sundown, playback, settings = Settings())
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            compose.onNodeWithTag(WRITE_HALVES_TAG).performScrollTo()
            block()
        }
    }

    // The card alone, in a column 16 dp in from the screen's edges, as on Home.
    private fun card(block: () -> Unit) {
        val context =
            InsightContext(track(insight = insight), aiReady = true, insightsOn = true, false, InsightOffers())
        val control = ReadAloudControl(reading = false, onRead = {}, onStop = {})
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = false) {
                        Column(Modifier.padding(16.dp).fillMaxWidth().testTag(CARD)) {
                            InsightCard(
                                insight,
                                context,
                                InsightActions({}, {}, {}, onReport = { _, _ -> }),
                                readAloud = control,
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    // Where a tap on the button answers: its 48 dp target, around the 40 dp it draws.
    private fun target(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot

    private val density get() = compose.density.density

    @Test
    fun aWrittenInsightHasReadAloudAtTheEndOfTheSplitButtonsRowWithNoAiMark() =
        home(insight) {
            val button = compose.onNodeWithTag(READ_ALOUD_TAG)
            button.assertContentDescriptionEquals("Read aloud")
            val read = target(READ_ALOUD_TAG)
            val split = bounds(WRITE_HALVES_TAG)
            assertTrue("after the split button, apart from it", read.left > split.right)
            assertEquals("on the split button's row", split.center.y, read.center.y, density)
            assertTrue("a 48 dp target", read.width / density >= 47.5f && read.height / density >= 47.5f)
            val marks =
                compose
                    .onAllNodes(hasTestTag(AI_MARK_TAG) and hasAnyAncestor(hasTestTag(READ_ALOUD_TAG)), true)
                    .fetchSemanticsNodes()
            assertEquals("no AI mark", 0, marks.size)
        }

    @Test
    fun aTapReadsTheInsightAndTheButtonThenStopsIt() =
        home(insight) {
            compose.onNodeWithTag(READ_ALOUD_TAG).performClick()
            compose.waitForIdle()
            assertEquals(
                listOf(listOf("Sundown was recorded in 1973.", "Unverified: It was a hit in Canada.")),
                speech.spoken,
            )
            compose.onNodeWithTag(READ_ALOUD_TAG).assertContentDescriptionEquals("Stop reading aloud")

            compose.onNodeWithTag(READ_ALOUD_TAG).performClick()
            compose.waitForIdle()
            assertEquals(1, speech.stops)
            compose.onNodeWithTag(READ_ALOUD_TAG).assertContentDescriptionEquals("Read aloud")
        }

    @Test
    fun theButtonFollowsTheReadingsEnd() =
        home(insight) {
            compose.onNodeWithTag(READ_ALOUD_TAG).performClick()
            compose.waitForIdle()
            speech.finish()
            compose.waitForIdle()
            compose.onNodeWithTag(READ_ALOUD_TAG).assertContentDescriptionEquals("Read aloud")
        }

    @Test
    fun noInsightWrittenHasNoReadAloud() {
        for (state in listOf(InsightState.Idle(canWrite = true), InsightState.Writing("Sundown was"))) {
            home(state) { compose.onNodeWithTag(READ_ALOUD_TAG).assertDoesNotExist() }
        }
    }

    @Test
    fun atTheUsualFontSizeTheButtonIsAtTheRowsEnd() =
        card {
            val read = target(READ_ALOUD_TAG)
            assertEquals("at the row's end", bounds(CARD).right, read.right, 1f)
        }

    @Test
    @Config(qualifiers = "w320dp-h640dp", fontScale = 2f)
    fun atTwiceTheFontSizeOnANarrowPhoneTheButtonKeepsItsPlaceAndTheSplitButtonWraps() =
        card {
            val read = target(READ_ALOUD_TAG)
            val split = bounds(WRITE_HALVES_TAG)
            val row = bounds(CARD)
            assertEquals("at the row's end", row.right, read.right, 1f)
            assertTrue("clear of the split button", read.left >= split.right)
            assertTrue("the split button on the screen", split.left >= row.left - 1f)
            assertTrue("still a 48 dp target", read.width / density >= 47.5f)
        }

    // Notes whose reason for jazz is written.
    private class Written(
        reason: InsightState.Written,
    ) : GenreNotes {
        val shown = MutableStateFlow<GenreReason>(GenreReason.Shown(reason))

        override fun definition(genre: String): Flow<Lookup<GenreDefinition>?> = flowOf(null)

        override fun reason(
            track: TrackState,
            genre: String,
        ): Flow<GenreReason> = shown

        override fun explain(
            track: TrackState,
            genre: String,
        ) = Unit
    }

    @Test
    fun aGenresWrittenReasonHasReadAloudWhichReadsItsQuestionThenTheReason() {
        val label =
            TrackLabel(
                GenreLabel("jazz", "jazzblues", "Jazz", "jazz", level = GenreLevel.ARTIST, artist = "Gordon Lightfoot"),
                listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/jazz")),
            )
        val playing =
            track().copy(
                genres = TrackGenres(listOf(GenreRow(GenreLevel.ARTIST, "Gordon Lightfoot", listOf(label)))),
            )
        val notes = PanelNotes(Written(written("Its modal tunes are jazz [MB1].")), readAloud = readAloud)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                    ) { GenrePanel(playing, "jazz", notes = notes) }
                }
            }
            compose.onNodeWithTag(GENRE_READ_ALOUD_TAG).performScrollTo().assertContentDescriptionEquals("Read aloud")
            compose.onNodeWithTag(GENRE_READ_ALOUD_TAG).performClick()
            compose.waitForIdle()
            assertEquals(listOf(listOf("Why is this jazz?", "Its modal tunes are jazz.")), speech.spoken)
        }
    }

    @Test
    fun aGenresReasonOfferedAgainWhileItIsReadStopsTheReading() {
        val label =
            TrackLabel(
                GenreLabel("jazz", "jazzblues", "Jazz", "jazz", level = GenreLevel.ARTIST, artist = "Gordon Lightfoot"),
                listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/jazz")),
            )
        val playing =
            track().copy(
                genres = TrackGenres(listOf(GenreRow(GenreLevel.ARTIST, "Gordon Lightfoot", listOf(label)))),
            )
        val reasons = Written(written("Its modal tunes are jazz [MB1]."))
        val notes = PanelNotes(reasons, readAloud = readAloud)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                    ) { GenrePanel(playing, "jazz", notes = notes) }
                }
            }
            compose.onNodeWithTag(GENRE_READ_ALOUD_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals("jazz's reason is read", Reading.genreReason("jazz"), readAloud.reading.value?.id)
            // A settings change offers it again: what was read is withdrawn.
            reasons.shown.value = GenreReason.Offered
            compose.waitForIdle()
            assertEquals(null, readAloud.reading.value)
            assertEquals(1, speech.stops)
        }
    }

    private companion object {
        const val CARD = "card"
    }
}
