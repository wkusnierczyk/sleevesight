package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.musicBrainzLine
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.wikidataLine
import app.sleevesight.app.ui.home.HomeFixtures.wikipediaLine
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.theme.DEVELOPER_MARK_TAG
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Rejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The developer mark (#536) on Home: what *Show grounding details* adds (the full counts under the
 * insight, *Sources*' table with the sources that are off and the diagnostics, #662, the rejected
 * matches) is marked while marking is on; nothing is while it is off; and without the details
 * nothing on Home is marked, as nothing there is developer mode's alone.
 */
@RunWith(RobolectricTestRunner::class)
class DeveloperMarkHomeTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val sources =
        mapOf(
            SourceId.MUSICBRAINZ to done(evidence = listOf(musicBrainzLine)),
            SourceId.WIKIPEDIA to
                done(
                    evidence = listOf(wikipediaLine, wikidataLine),
                    rejections = listOf(Rejection("Sundown (film)", "a film", Scope.SONG)),
                ),
        )

    private val details = Settings(developerMode = true, showGroundingDetails = true)

    private fun show(
        settings: Settings,
        marked: Boolean,
        block: () -> Unit,
    ) {
        val insight = written("It was recorded in 1973 [MB1]. It topped the charts [W1].")
        val state =
            NowPlayingState(
                PipelineView(track(insight = insight, sources = sources), aiReady = true),
                HomeFixtures.sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = settings,
            )
        val actions = NowPlayingActions(normalizer::normalize, { "Spotify" }, { null })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(developerMark = marked) {
                        Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(SOURCES_CHIP_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            block()
        }
    }

    private fun marks() =
        compose.onAllNodesWithTag(DEVELOPER_MARK_TAG, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun marked(
        text: String,
        substring: Boolean = false,
    ) = compose
        .onAllNodes(
            hasText(text, substring = substring) and hasAnyAncestor(hasTestTag(DEVELOPER_MARK_TAG)),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        .size

    @Test
    fun theGroundingDetailsAreMarked() =
        show(details, marked = true) {
            // The counts, the sources' table, the rejected matches: each block marked once.
            assertEquals(3, marks())
            val table = hasTestTag(SOURCES_TABLE_TAG) and hasAnyAncestor(hasTestTag(DEVELOPER_MARK_TAG))
            assertEquals(1, compose.onAllNodes(table, useUnmergedTree = true).fetchSemanticsNodes().size)
            val grounded = app.resources.getQuantityString(R.plurals.home_meta_grounded, 2, 2, 2)
            assertEquals(1, marked(grounded, substring = true))
            assertEquals(1, marked(app.getString(R.string.home_rejected_title)))
            val wikipedia = app.getString(R.string.settings_source_wikipedia)
            val film = app.getString(R.string.home_rejected_subject, wikipedia, "Sundown (film)", "a film")
            assertEquals(1, marked(film))
            // The tag is drawn only: no text of it.
            val tag = app.getString(R.string.developer_mark_tag)
            assertEquals(0, compose.onAllNodesWithText(tag, useUnmergedTree = true).fetchSemanticsNodes().size)
        }

    @Test
    fun theSourcesLegendStaysOutsideTheMarkUnderIt() =
        show(details, marked = true) {
            // The table in the mark (#536); its legend not, under it (#723).
            val legend = compose.onNodeWithTag(SOURCES_LEGEND_TAG, useUnmergedTree = true).fetchSemanticsNode()
            val inMark = hasTestTag(SOURCES_LEGEND_TAG) and hasAnyAncestor(hasTestTag(DEVELOPER_MARK_TAG))
            assertEquals(0, compose.onAllNodes(inMark, useUnmergedTree = true).fetchSemanticsNodes().size)
            assertEquals(0, marked(app.getString(R.string.home_sources_legend_shown)))
            assertEquals(0, marked(app.getString(R.string.home_sources_legend_tap)))
            val mark =
                compose
                    .onAllNodes(
                        hasTestTag(DEVELOPER_MARK_TAG) and hasAnyDescendant(hasTestTag(SOURCES_TABLE_TAG)),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .single()
            val markBottom = mark.positionInRoot.y + mark.size.height
            val legendTop = legend.positionInRoot.y
            assertTrue("legend at $legendTop, mark ends at $markBottom", legendTop >= markBottom)
        }

    @Test
    fun withMarkingOffTheDetailsShowUnmarked() =
        show(details, marked = false) {
            assertEquals(0, marks())
            compose.onAllNodesWithText(app.getString(R.string.home_rejected_title)).fetchSemanticsNodes().single()
        }

    @Test
    fun withoutTheDetailsNothingOnHomeIsMarked() =
        show(Settings(developerMode = true), marked = true) {
            assertEquals(0, marks())
        }
}
