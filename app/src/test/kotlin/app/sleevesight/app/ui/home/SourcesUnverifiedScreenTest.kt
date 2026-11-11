package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.app.ui.home.HomeFixtures.musicBrainzLine
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
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
import kotlin.math.abs
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * A source whose lines are all unverified in *Sources*' table (#951): Last.fm on *Madryt* by Kult,
 * an artist of that name found but not confirmed. Its row says what it found with *(unverified)*
 * after it; its chip is a switch as any source's with lines, filled grey while they show and outlined
 * while hidden, its state saying they are unverified; a tap hides and shows them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class SourcesUnverifiedScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private val kult = "There are at least four artists named Kult on Last.fm."
    private val listeners = "Last.fm counts 198,204 listeners for Kult."

    /** MusicBrainz's line, cited; Last.fm's two artist lines, unverified only. */
    private val madryt: TrackState =
        track(
            sources =
                mapOf(
                    SourceId.MUSICBRAINZ to done(evidence = listOf(musicBrainzLine)),
                    SourceId.LASTFM to
                        done(
                            Outcome.ArtistArticle,
                            unverified =
                                listOf(
                                    draft(SourceId.LASTFM, kult, scope = Scope.ARTIST),
                                    draft(SourceId.LASTFM, listeners, scope = Scope.ARTIST),
                                ),
                        ),
                ),
            evidence = EvidenceSet.of(listOf(musicBrainzLine), JvmHasher),
        )

    private fun show(
        dark: Boolean = false,
        fontScale: Float = 1f,
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(madryt, aiReady = true),
                madryt.nowPlaying,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(),
            )
        val actions = NowPlayingActions(normalizer::normalize)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        val density = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                            // On the theme's surface, as the app draws it, so the dark pictures are dark.
                            Column(
                                Modifier
                                    .background(MaterialTheme.colorScheme.surface)
                                    .verticalScroll(rememberScrollState()),
                            ) { NowPlayingContent(state, actions) }
                        }
                    }
                }
            }
            scenario.onActivity { block() }
        }
    }

    private fun unfold() = tap(SOURCES_CHIP_TAG)

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun shown(line: String) =
        compose.onAllNodesWithText(line, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** What the row of [tag] says in words a screen reader reads as text, in order. */
    private fun said(tag: String): List<String> {
        val words = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            node.config
                .getOrNull(SemanticsProperties.Text)
                ?.let { texts -> words += texts.map { it.text } }
            node.children.forEach(::walk)
        }
        walk(compose.onNodeWithTag(ROW_PREFIX + tag).fetchSemanticsNode())
        return words
    }

    private fun switch(shown: Boolean) =
        compose
            .onNodeWithTag(CHIP_PREFIX + "L")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Last.fm")))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState(shown)))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    text(if (shown) R.string.home_source_unverified_shown else R.string.home_source_unverified_hidden),
                ),
            )

    @Test
    fun aSourceWithOnlyUnverifiedLinesSaysWhatItFoundWithUnverifiedAfterIt() =
        show {
            unfold()
            val found = text(R.string.home_source_found, text(R.string.home_source_finding_artist))
            assertEquals("Found the artist (unverified)", text(R.string.home_source_unverified, found))
            assertEquals(listOf(text(R.string.home_source_unverified, found)), said("L"))
            // A source with cited lines says what it found as before.
            val track = text(R.string.home_source_found, text(R.string.home_source_finding_track))
            assertEquals(listOf(track), said("MB"))
        }

    @Test
    fun itsChipIsASwitchThatSaysItsLinesAreUnverifiedAndATapHidesAndShowsThem() =
        show {
            unfold()
            val chip = compose.onNodeWithTag(CHIP_PREFIX + "L").fetchSemanticsNode().config
            assertTrue(SemanticsActions.OnClick in chip)
            switch(shown = true)
            assertTrue(shown(kult))
            assertTrue(shown(listeners))
            tap(CHIP_PREFIX + "L")
            switch(shown = false)
            assertEquals(false, shown(kult))
            assertEquals(false, shown(listeners))
            // Nothing unverified left to show: no heading over none.
            assertEquals(false, shown(text(R.string.home_unverified)))
            // MusicBrainz's line stays.
            assertEquals(1, compose.onAllNodesWithTag(LINE_PREFIX + "MB1").fetchSemanticsNodes().size)
            tap(CHIP_PREFIX + "L")
            switch(shown = true)
            assertTrue(shown(kult))
            assertTrue(shown(text(R.string.home_unverified)))
        }

    @Test
    fun showingThemAgainBelowALongListUnfoldsToThemAndScrollsThemIntoView() {
        val facts = (1..20).map { draft(SourceId.MUSICBRAINZ, "Fact $it.") }
        val sources =
            mapOf(
                SourceId.MUSICBRAINZ to done(evidence = facts),
                SourceId.LASTFM to
                    done(
                        Outcome.ArtistArticle,
                        unverified = listOf(draft(SourceId.LASTFM, kult, scope = Scope.ARTIST)),
                    ),
            )
        val track = track(sources = sources, evidence = EvidenceSet.of(facts, JvmHasher))
        val state =
            NowPlayingState(
                PipelineView(track, aiReady = true),
                track.nowPlaying,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(),
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(state, NowPlayingActions(normalizer::normalize))
                    }
                }
            }
            scenario.onActivity {
                unfold()
                // Folded past the first screenful: Last.fm's line is not in the list yet.
                assertEquals(false, shown(kult))
                tap(CHIP_PREFIX + "L")
                tap(CHIP_PREFIX + "L")
                compose.waitForIdle()
                compose.onNodeWithText(kult).assertIsDisplayed()
            }
        }
    }

    @Test
    fun aSourceWithCitedLinesHidesItsUnverifiedLinesWithThem() {
        val maybe = "Perhaps Gordon Lightfoot wrote it in 1973."
        val sources =
            mapOf(
                SourceId.MUSICBRAINZ to
                    done(evidence = listOf(musicBrainzLine), unverified = listOf(draft(SourceId.MUSICBRAINZ, maybe))),
            )
        val track = track(sources = sources, evidence = EvidenceSet.of(listOf(musicBrainzLine), JvmHasher))
        val state =
            NowPlayingState(
                PipelineView(track, aiReady = true),
                track.nowPlaying,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(),
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(state, NowPlayingActions(normalizer::normalize))
                    }
                }
            }
            scenario.onActivity {
                unfold()
                assertTrue(shown(maybe))
                // Its chip as before: lines shown, not unverified.
                compose
                    .onNodeWithTag(CHIP_PREFIX + "MB")
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.StateDescription,
                            text(R.string.home_source_lines_shown),
                        ),
                    )
                tap(CHIP_PREFIX + "MB")
                assertEquals(false, shown(maybe))
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun itsChipIsFilledGreyWhileItsLinesShowAndOutlinedWhileHiddenLightAndDark() {
        for (dark in listOf(false, true)) {
            show(dark = dark) {
                unfold()
                keep("unverified-${if (dark) "dark" else "light"}")
                val grey = picture(CHIP_PREFIX + "L")
                val black = picture(CHIP_PREFIX + "MB")
                val ground = groundOf(grey)
                assertTrue("filled: ${hex(fillOf(grey))}", distance(fillOf(grey), ground) > DISTINCT)
                // Grey, not the verified chip's fill: between it and the panel.
                assertTrue(
                    "grey ${hex(fillOf(grey))}, verified ${hex(fillOf(black))}",
                    distance(fillOf(grey), fillOf(black)) > DISTINCT,
                )
                // Its letter at least 4.5:1 on it, as drawn (WCAG; the colours themselves in
                // `UnverifiedColoursTest`).
                val ink = darkestOrLightest(grey, fillOf(grey))
                val ratio = contrast(ComposeColor(ink), ComposeColor(fillOf(grey)))
                assertTrue("letter ${hex(ink)} on ${hex(fillOf(grey))}: $ratio", ratio >= 4.5f)
                tap(CHIP_PREFIX + "L")
                val hidden = picture(CHIP_PREFIX + "L")
                val fill = fillOf(hidden)
                assertTrue("not filled: ${hex(fill)}", distance(fill, groundOf(hidden)) <= TOLERANCE)
                assertTrue("outlined: ${hex(edgeOf(hidden))}", distance(edgeOf(hidden), groundOf(hidden)) > DISTINCT)
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun inALargeFontTheRowStillSaysUnverifiedAndTheChipStillToggles() =
        show(fontScale = 2f) {
            unfold()
            keep("unverified-font-200")
            val found = text(R.string.home_source_found, text(R.string.home_source_finding_artist))
            assertEquals(listOf(text(R.string.home_source_unverified, found)), said("L"))
            switch(shown = true)
            tap(CHIP_PREFIX + "L")
            switch(shown = false)
        }

    private fun picture(tag: String): Bitmap =
        compose
            .onNodeWithTag(tag)
            .performScrollTo()
            .captureToImage()
            .asAndroidBitmap()

    private fun px(dp: Float) = (dp * app.resources.displayMetrics.density).toInt()

    private fun fillOf(chip: Bitmap) = chip.getPixel(px(INSIDE), chip.height / 2)

    private fun groundOf(chip: Bitmap) = chip.getPixel(1, 1)

    private fun edgeOf(chip: Bitmap) = chip.getPixel(1, chip.height / 2)

    // The pixel of the chip's middle row furthest from its fill: its letter's.
    private fun darkestOrLightest(
        chip: Bitmap,
        fill: Int,
    ) = (0 until chip.width).map { chip.getPixel(it, chip.height / 2) }.maxBy { distance(it, fill) }

    private fun distance(
        a: Int,
        b: Int,
    ) = abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun hex(colour: Int) = "#%08X".format(colour)

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    // The screen as drawn, kept for a look when SLEEVESIGHT_PICTURES names a folder.
    private fun keep(name: String) {
        val dir = out ?: return
        compose.onNodeWithTag(SOURCES_LEGEND_TAG).performScrollTo()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val INSIDE = 4f
        const val TOLERANCE = 6
        const val DISTINCT = 60
    }
}
