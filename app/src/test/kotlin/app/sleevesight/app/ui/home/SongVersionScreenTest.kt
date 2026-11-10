package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.evidence
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The song's row and the version its title names (#1160): with a player's title such as "Material
 * Girl - 2024 Remaster", the *Song* row reads the title as under the cover, "Material Girl", and the
 * version the normalizer dropped is said under the song's name in its panel's heading, in every state
 * of the panel, and on its page under *Separate pages*, read with the heading: "Song, Material Girl,
 * Version: 2024 Remaster". With no version in the title, no note: the row and the panel as before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class SongVersionScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val onDemand = Settings(writeAutomatically = WriteAutomatically.NEVER)

    private val notes = FakeItemNotes()

    private val actions = NowPlayingActions(normalizer::normalize, itemNotes = notes)

    private fun state(
        playing: NowPlaying,
        settings: Settings = onDemand,
    ) = track(playing).let {
        NowPlayingState(
            PipelineView(it, aiReady = true),
            it.nowPlaying,
            PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
            settings = settings,
        )
    }

    private fun show(
        content: @Composable () -> Unit,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { content() } }
            compose.waitForIdle()
            block()
        }
    }

    private fun home(
        playing: NowPlaying,
        settings: Settings = onDemand,
        block: () -> Unit,
    ) = show({
        Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state(playing, settings), actions) }
    }, block)

    private fun row(item: InsightItem) = compose.onNodeWithTag(itemRowTag(item))

    private fun SemanticsNodeInteraction.texts() = fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }

    private fun openSong() {
        row(InsightItem.SONG).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private val heading get() = compose.onNodeWithTag(itemPanelTag(InsightItem.SONG) + ":heading")

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test
    fun theSongRowReadsTheTitleAsUnderTheCoverWithoutTheVersion() =
        home(materialGirl) {
            row(InsightItem.SONG).performScrollTo()
            assertEquals(
                "Song, Material Girl, uses AI",
                row(InsightItem.SONG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single(),
            )
            // Closed, the row says nothing of the version.
            assertEquals(0, count(VERSION))
        }

    @Test
    fun theOpenPanelsHeadingReadsTheVersionUnderTheNameAsOneHeading() =
        home(materialGirl) {
            openSong()
            val node = heading.fetchSemanticsNode()
            assertEquals(listOf("Song", "Material Girl", VERSION), heading.texts())
            assertTrue(SemanticsProperties.Heading in node.config)
            // On a line of its own under the name.
            val name = bounds(itemPanelTag(InsightItem.SONG) + ":subtitle")
            val note = textBounds(VERSION)
            assertTrue("$name $note", name.bottom <= note.top)
            assertEquals(1, count(VERSION))
        }

    @Test
    fun theVersionStaysInTheHeadingInEveryStateOfThePanel() =
        home(materialGirl) {
            openSong()
            val written =
                ItemShown.Shown(
                    written("Material Girl was a single [MB1]."),
                    ItemEvidence(evidence, mapOf("MB1" to "MB1")),
                    "Claude Haiku 4.5",
                )
            val states =
                listOf(
                    ItemShown.Offered,
                    ItemShown.Gathering,
                    ItemShown.Asking(ITEM_KEY),
                    ItemShown.Shown(
                        InsightState.Writing("Material Girl was"),
                        ItemEvidence(evidence, emptyMap()),
                        "Claude Haiku 4.5",
                    ),
                    written,
                    ItemShown.Shown(
                        InsightState.Failed("the service did not answer"),
                        ItemEvidence(evidence, emptyMap()),
                        "Claude Haiku 4.5",
                    ),
                )
            for (shown in states) {
                notes.shown.value = mapOf(InsightItem.SONG to shown)
                compose.waitForIdle()
                assertEquals(shown.toString(), listOf("Song", "Material Girl", VERSION), heading.texts())
            }
        }

    @Test
    fun withNoVersionInTheTitleThereIsNoNoteAndTheRowAndPanelAreAsBefore() =
        home(sundown) {
            row(InsightItem.SONG).performScrollTo()
            assertEquals(
                "Song, Sundown, uses AI",
                row(InsightItem.SONG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single(),
            )
            openSong()
            assertEquals(listOf("Song", "Sundown"), heading.texts())
            assertEquals(0, count("Version:"))
        }

    @Test
    fun onTheSongsPageTheVersionIsUnderTheNameReadWithIt() =
        show({
            NowPlayingPageContent(
                HomePage.Item(InsightItem.SONG.name),
                state(materialGirl, onDemand.copy(layout = Layout.SEPARATE_PAGES)),
                actions,
            ) {}
        }) {
            // The node the name is read in, by its text: the name and the version, as one stop.
            assertEquals(
                listOf("Material Girl", VERSION),
                compose.onNodeWithText("Material Girl", substring = true).texts(),
            )
            val name = bounds(ITEM_PAGE_NAME_TAG)
            val note = textBounds(VERSION)
            assertTrue("$name $note", name.bottom <= note.top)
        }

    @Test
    fun onAPageWithNoVersionOnlyTheName() =
        show({
            NowPlayingPageContent(
                HomePage.Item(InsightItem.SONG.name),
                state(sundown, onDemand.copy(layout = Layout.SEPARATE_PAGES)),
                actions,
            ) {}
        }) {
            assertEquals(listOf("Sundown"), compose.onNodeWithTag(ITEM_PAGE_NAME_TAG, useUnmergedTree = true).texts())
            assertEquals(0, count("Version:"))
        }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun textBounds(text: String) =
        compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private companion object {
        val materialGirl =
            sundown.copy(
                title = "Material Girl - 2024 Remaster",
                artists = "Madonna",
                album = "Material Girl (2024 Remaster)",
                mediaId = "spotify:track:3",
            )

        const val VERSION = "Version: 2024 Remaster"
    }
}
