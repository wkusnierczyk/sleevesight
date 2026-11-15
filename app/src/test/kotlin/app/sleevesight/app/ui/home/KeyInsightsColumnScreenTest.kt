package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.theme.CHIP_PADDING
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The chips under *Key insights* as one column (#1286), on Home as drawn on a phone: the rows'
 * buttons and *Sources*' chip all one width, the widest of them fitting its label and its **AI** mark
 * exactly, a pop track's narrower than a classical one's; the mark at the same place at every row
 * button's end, its name centred in the room left of it; *Sources*' name centred in the whole chip.
 * At the default font size and twice it, under both layouts, with *Show insights* on and off. With
 * `SLEEVESIGHT_PICTURES` set, [pictures] saves Home's rows and *Sources* there, light and dark, as
 * `ComposerPanelPicturesTest` saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2200dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyInsightsColumnScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private val onDemand = Settings(writeAutomatically = WriteAutomatically.NEVER)

    // K. 545 with the roles telling the composer apart: Song, Composer, Performer, Album.
    private val classical =
        sundown.copy(
            title = "Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante",
            artists = "$MOZART, Khatia Buniatishvili, Academy of St. Martin in the Fields",
            album = "Mozart: Piano Concertos Nos. 20 & 23",
            mediaId = "spotify:track:k545",
        )
    private val classicalRoles =
        ArtistRoles(listOf(MOZART), listOf("Khatia Buniatishvili", "Academy of St. Martin in the Fields"))

    // A pop track: Song, Artist, Album.
    private val pop =
        sundown.copy(
            title = "Good Love",
            artists = "Zola Blood",
            album = "Infinite Games",
            mediaId = "spotify:track:gl",
        )

    private fun track(
        playing: NowPlaying,
        roles: ArtistRoles?,
    ): TrackState =
        HomeFixtures
            .track(playing, sources = mapOf(SourceId.MUSICBRAINZ to done()))
            .copy(resolution = roles?.let { Resolution(recordingMbid = "r", roles = it) })

    private fun show(
        track: TrackState,
        settings: Settings,
        dark: Boolean = false,
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track, aiReady = true),
                track.nowPlaying,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = settings,
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.padding(16.dp).testTag(PICTURE)) {
                                NowPlayingContent(state, NowPlayingActions(normalizer::normalize))
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun bounds(
        matcher: SemanticsMatcher,
        unmerged: Boolean = false,
    ): Rect = compose.onNode(matcher, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    private fun px(dp: Dp): Float = dp.value * compose.density.density

    // A chip of the column: its bounds, its name's and its mark's, if any.
    private class Chip(
        val tag: String,
        val chip: Rect,
        val label: Rect,
        val mark: Rect?,
    )

    private fun chip(tag: String): Chip {
        val inside = hasAnyAncestor(hasTestTag(tag))
        val mark =
            compose
                .onAllNodes(hasTestTag(AI_MARK_TAG) and inside, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.boundsInRoot
        return Chip(tag, bounds(hasTestTag(tag)), bounds(hasTestTag(CHIP_LABEL_TAG) and inside, unmerged = true), mark)
    }

    // The tags of [track]'s rows' buttons, then *Sources*' chip's under [settings]' layout.
    private fun tags(
        track: TrackState,
        settings: Settings,
    ): List<String> {
        val normalized = normalizer.normalize(track.nowPlaying)
        val rows = itemRows(track.nowPlaying, normalized, splitOf(track, normalized)).map { itemRowTag(it.item) }
        return rows + if (settings.layout == Layout.SEPARATE_PAGES) SOURCES_PAGE_LINK_TAG else SOURCES_CHIP_TAG
    }

    private fun near(
        what: String,
        expected: Float,
        actual: Float,
    ) = assertTrue("$what: $expected expected, $actual", abs(expected - actual) <= TOLERANCE)

    // Every check of the column, on the screen shown; its width.
    private fun checkColumn(
        track: TrackState,
        settings: Settings,
    ): Float {
        val ai = settings.writeInsights
        val tags = tags(track, settings)
        val width = bounds(hasTestTag(tags.first())).width
        // One width, Sources' too.
        for (tag in tags) near("$tag's width", width, bounds(hasTestTag(tag)).width)
        val all = tags.map(::chip)
        val rows = all.dropLast(1)
        val sources = all.last()
        val padding = px(CHIP_PADDING)
        val gap = px(MARK_GAP)
        // The widest fits its content exactly: padding, its name, the gap and the mark, padding.
        val content = all.maxOf { it.label.width + (it.mark?.let { mark -> gap + mark.width } ?: 0f) }
        near("the column's width", content + 2 * padding, width)
        // Sources carries no mark; its name centred in the whole chip.
        assertEquals(null, sources.mark)
        near("Sources' name's centre", sources.chip.center.x, sources.label.center.x)
        // Where the first row's mark starts, from its chip's left edge.
        val firstMark = rows.first().mark?.left ?: 0f
        for (row in rows) {
            val mark = row.mark
            if (!ai) {
                assertEquals(null, mark)
                near("${row.tag}'s name's centre", row.chip.center.x, row.label.center.x)
                continue
            }
            checkNotNull(mark)
            // The mark at the chip's end, the same place on every chip; the name centred left of it.
            near("${row.tag}'s mark's end", row.chip.right - padding, mark.right)
            near("${row.tag}'s mark's start", firstMark - rows.first().chip.left, mark.left - row.chip.left)
            near("${row.tag}'s name's centre", (row.chip.left + padding + mark.left - gap) / 2, row.label.center.x)
        }
        return width
    }

    private fun check(scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        for (layout in Layout.entries) {
            for (ai in listOf(true, false)) {
                val settings = onDemand.copy(layout = layout, writeInsights = ai)
                val classical = track(classical, classicalRoles)
                val pop = track(pop, null)
                var wide = 0f
                show(classical, settings) { wide = checkColumn(classical, settings) }
                show(pop, settings) {
                    val narrow = checkColumn(pop, settings)
                    // The pop track's own column: Song, Artist and Album take less than Composer, Performer.
                    assertTrue("pop $narrow < classical $wide at $scale, $layout, AI $ai", narrow < wide)
                }
            }
        }
    }

    @Test
    fun theChipsAreOneColumnAtTheDefaultFontSize() = check(1f)

    @Test
    fun theChipsAreOneColumnAtTwiceIt() = check(2f)

    @Test
    fun theChipsReadAsBefore() =
        show(track(classical, classicalRoles), onDemand) {
            compose
                .onNodeWithTag(itemRowTag(InsightItem.COMPOSER))
                .assertContentDescriptionEquals("Composer, $MOZART, uses AI")
            compose.onNodeWithTag(SOURCES_CHIP_TAG).assertContentDescriptionEquals("Sources")
        }

    @Test
    fun pictures() {
        val dir = out ?: return
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for ((name, track) in listOf("classical" to track(classical, classicalRoles), "pop" to track(pop, null))) {
                for (dark in listOf(false, true)) {
                    show(track, onDemand, dark) {
                        val bitmap = compose.onNodeWithTag(PICTURE).captureToImage().asAndroidBitmap()
                        val theme = if (dark) "dark" else "light"
                        File(dir, "$name-$theme-$size.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val PICTURE = "picture"
        const val MOZART = "Wolfgang Amadeus Mozart"

        // A pixel's rounding at each of the edges a measure is taken between.
        const val TOLERANCE = 1.5f
    }
}
