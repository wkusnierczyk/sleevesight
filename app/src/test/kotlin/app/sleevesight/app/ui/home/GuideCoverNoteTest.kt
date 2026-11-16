package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The guide's note on the cover (#994): third, between *Player* and *Genres*, "tap to see contents",
 * read as "Album art: tap to see contents", a button, whose panel says what a tap on the cover shows;
 * the magnifier still on the cover's corner, and the notes apart, at the default font size, 130 % and
 * twice it. Drawn as on a phone, the guide and the cover's panel open; with `SLEEVESIGHT_PICTURES` set,
 * saved there as pictures, as `MusicRowPicturesTest` saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class GuideCoverNoteTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private fun show(block: () -> Unit) {
        val actions = NowPlayingActions(normalizer::normalize, playerLabel = { "Spotify" })
        val settings = Settings(followedPlayers = mapOf(Settings.SPOTIFY to true))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = false) {
                        Surface(Modifier.fillMaxWidth()) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                NowPlayingContent(
                                    NowPlayingState(PipelineView(), playing = null, settings = settings),
                                    actions,
                                )
                            }
                        }
                    }
                }
            }
            compose.awaitIdleHome()
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithTag(guideNoteTag(GuidePart.SOURCES)).fetchSemanticsNodes().isNotEmpty()
            }
            block()
        }
    }

    private fun note(part: GuidePart) = compose.onNodeWithTag(guideNoteTag(part))

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun panels() = compose.onAllNodesWithTag(GUIDE_NOTE_PANEL_TAG).fetchSemanticsNodes()

    @Test
    fun theCoversNoteComesThirdBetweenPlayerAndGenres() =
        show {
            assertEquals(
                listOf(GuidePart.SETTINGS, GuidePart.PLAYER, GuidePart.COVER, GuidePart.GENRES),
                GuidePart.entries.take(4),
            )
            // Drawn there, top to bottom, and read there.
            val tops =
                listOf(GuidePart.PLAYER, GuidePart.COVER, GuidePart.GENRES).map { bounds(guideNoteTextTag(it)).top }
            assertEquals(tops.sorted(), tops)
            val index = { part: GuidePart ->
                note(part).fetchSemanticsNode().config.getOrNull(SemanticsProperties.TraversalIndex)!!
            }
            assertTrue(index(GuidePart.PLAYER) < index(GuidePart.COVER))
            assertTrue(index(GuidePart.COVER) < index(GuidePart.GENRES))
        }

    @Test
    fun theCoversNoteIsReadAsAnAlbumArtButton() =
        show {
            assertEquals("tap to see contents", app.getString(GuidePart.COVER.note))
            assertEquals("Album art", app.getString(GuidePart.COVER.label))
            val node = note(GuidePart.COVER).fetchSemanticsNode()
            assertEquals(
                listOf("Album art: tap to see contents"),
                node.config.getOrNull(SemanticsProperties.ContentDescription),
            )
            assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
        }

    @Test
    fun aTapOnTheCoversNoteOpensItsPanelAndCloseClosesIt() =
        show {
            note(GuidePart.COVER).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(1, panels().size)
            compose
                .onNodeWithTag(GUIDE_NOTE_PANEL_TEXT_TAG)
                .assert(hasText("Album art"))
                .assert(hasText(app.getString(R.string.home_guide_about_cover)))
            val about = app.getString(R.string.home_guide_about_cover)
            assertTrue(about, about.startsWith("The album’s cover. A tap turns it over"))
            save("guide-cover-panel")
            compose.onNodeWithTag(GUIDE_NOTE_CLOSE_TAG).performClick()
            compose.waitForIdle()
            assertEquals(0, panels().size)
            note(GuidePart.COVER).performScrollTo().performClick()
            compose.waitForIdle()
            note(GuidePart.COVER).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(0, panels().size)
        }

    @Test
    fun theMagnifierStaysOnTheCoversCornerAndTheNotesApart() = coverCornerAndNotesApart("guide")

    @Test
    @Config(fontScale = 1.3f)
    fun atALargerFontTheMagnifierStaysOnTheCoversCornerAndTheNotesApart() = coverCornerAndNotesApart("guide-130")

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeTheMagnifierStaysOnTheCoversCornerAndTheNotesApart() {
        coverCornerAndNotesApart("guide-200")
        show {
            note(GuidePart.COVER).performScrollTo().performClick()
            compose.waitForIdle()
            save("guide-cover-panel-200")
        }
    }

    private fun coverCornerAndNotesApart(name: String) =
        show {
            val drawn = bounds(GUIDE_DRAWN_TAG)
            val magnifier = bounds(GUIDE_MAGNIFY_TAG)
            // On the cover's corner, at the picture's right, well above the picture's own bottom corner.
            assertTrue("$magnifier on $drawn", magnifier.right <= drawn.right && magnifier.right > drawn.center.x)
            assertTrue(
                "$magnifier on $drawn",
                magnifier.top > drawn.top && magnifier.bottom < drawn.top + drawn.height * 0.7f,
            )
            val texts = GuidePart.entries.map { bounds(guideNoteTextTag(it)) }
            for ((i, a) in texts.withIndex()) {
                for (b in texts.drop(i + 1)) {
                    assertTrue("$a and $b", a.intersect(b).let { it.width <= 0f || it.height <= 0f })
                }
                assertTrue("$a and $magnifier", a.intersect(magnifier).let { it.width <= 0f || it.height <= 0f })
            }
            save(name)
        }

    private fun save(name: String) {
        val dir = out ?: return
        val bitmap = compose.onNodeWithTag(GUIDE_PICTURE_TAG).captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
