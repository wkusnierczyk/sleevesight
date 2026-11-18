package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.logo.LocalAnimatorDurationScale
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The *Artists* row's fit rule against its layout (#1168): "{first}, others" is shown exactly when it
 * fits the name's cell as the row lays it out, to the pixel, with the turning record before the name
 * (its insight being written) and without, at several font sizes, on a phone of 420 dpi (density
 * 2.625, where a sum of rounded widths and a rounded sum differ), its text measured by the native
 * graphics as on a phone; and a name of symbols alone is an
 * artist of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h4000dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtistsRowFitTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val settings = Settings(writeAutomatically = WriteAutomatically.NEVER)

    // First artists of growing length, from short enough to fit with "others" to too long to.
    private val firsts = (2..64 step 2).map { n -> LETTERS.take(n).trimEnd() }.distinct()

    private fun playing(first: String) =
        sundown.copy(title = "T", artists = "$first, Kimbra", album = "A", mediaId = "spotify:track:$first")

    @Test
    fun theRowShowsTheFirstAndOthersExactlyWhenItFitsItsCellWithAndWithoutTheRecord() {
        for (scale in listOf(1f, 1.3f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            for (writing in listOf(false, true)) sweep(writing, "$scale×${if (writing) " writing" else ""}")
        }
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun sweep(
        writing: Boolean,
        case: String,
    ) {
        // The artist's insight being written: the turning record before every row's name.
        val notes =
            FakeItemNotes().apply {
                if (writing) shown.value = mapOf(InsightItem.ARTIST to ItemShown.Asking(ITEM_KEY))
            }
        lateinit var measurer: TextMeasurer
        lateinit var style: TextStyle
        lateinit var density: Density
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    measurer = rememberTextMeasurer()
                    style = MaterialTheme.typography.bodyLarge
                    density = LocalDensity.current
                    // The record standing still, so the frames settle.
                    androidx.compose.runtime.CompositionLocalProvider(LocalAnimatorDurationScale provides 0f) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            firsts.forEach { first -> Rows(first, notes) }
                        }
                    }
                }
            }
            compose.waitForIdle()
            val buttons = compose.onAllNodesWithTag(itemRowTag(InsightItem.ARTIST)).fetchSemanticsNodes()
            val cells = compose.onAllNodesWithTag(itemNameTag(InsightItem.ARTIST) + ":cell", useUnmergedTree = true)
            assertEquals(firsts.size, buttons.size)
            var both = 0
            var log = ""
            firsts.forEachIndexed { i, first ->
                val full = "$first, others"
                val wide = measurer.measure(full, style, maxLines = 1, softWrap = false).size.width
                val cell = cells[i].getUnclippedBoundsInRoot()
                val room = with(density) { (cell.right - cell.left).toPx() }.roundToInt()
                val said = buttons[i].config[SemanticsProperties.ContentDescription].single()
                val shown = said.removePrefix("Artists, ").substringBefore(", uses AI")
                assertEquals("$case: $first ($wide px in $room px)", if (wide <= room) full else first, shown)
                if (shown == full) both++
                log += "${first.length}:$wide/$room "
            }
            // The sweep crosses the boundary: some fit with "others", some do not.
            assertTrue("$case: $both of ${firsts.size} fit; $log", both in 1 until firsts.size)
        }
    }

    @Test
    fun atEveryPixelOfWidthTheRowShowsTheFirstAndOthersExactlyWhenItFitsWithTheRecord() {
        for (scale in listOf(1f, 1.3f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            pixels("$scale×")
        }
        RuntimeEnvironment.setFontScale(1f)
    }

    // One row of [GOTYE]'s, its insight being written, in lines one pixel apart in width around the
    // width where "{first}, others" stops fitting (where the name starts in a wide line, plus the
    // string's width): each shows it exactly when it fits its cell.
    private fun pixels(case: String) {
        val notes = FakeItemNotes().apply { shown.value = mapOf(InsightItem.ARTIST to ItemShown.Asking(ITEM_KEY)) }
        val full = "$GOTYE, others"
        lateinit var density: Density
        var wide = 0
        // Where the name starts, from a line wide enough for anything.
        var start = 0
        start =
            lines(notes, listOf(null)) { d, w ->
                density = d
                wide = w
            }.single().left
        val widths = ((start + wide - 4)..(start + wide + 4)).toList()
        val rows = lines(notes, widths) { _, _ -> }
        var both = 0
        widths.indices.forEach { i ->
            val room = rows[i].room
            val said = rows[i].said
            val shown = said.removePrefix("Artists, ").substringBefore(", uses AI")
            assertEquals("$case: line ${widths[i]} px, $wide px in $room px", if (wide <= room) full else GOTYE, shown)
            if (shown == full) both++
        }
        assertTrue("$case: $both of ${widths.size}", both in 1 until widths.size)
    }

    // [GOTYE]'s row in lines [widths] pixels wide (null: the whole screen), as [notes] say; the
    // density and the width of "{first}, others" told to [seen]; each row's description and name cell.
    private fun lines(
        notes: FakeItemNotes,
        widths: List<Int?>,
        seen: (Density, Int) -> Unit,
    ): List<Line> {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val density = LocalDensity.current
                    val style = MaterialTheme.typography.bodyLarge
                    seen(
                        density,
                        rememberTextMeasurer()
                            .measure(
                                "$GOTYE, others",
                                style,
                                maxLines = 1,
                                softWrap = false,
                            ).size.width,
                    )
                    androidx.compose.runtime.CompositionLocalProvider(LocalAnimatorDurationScale provides 0f) {
                        Column {
                            widths.forEach { px ->
                                val line =
                                    px?.let { Modifier.width(with(density) { it.toDp() }) } ?: Modifier.fillMaxWidth()
                                Box(line) { Rows(GOTYE, notes) }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            val density =
                Density(
                    RuntimeEnvironment
                        .getApplication()
                        .resources.displayMetrics.density,
                )
            val buttons = compose.onAllNodesWithTag(itemRowTag(InsightItem.ARTIST)).fetchSemanticsNodes()
            val cells = compose.onAllNodesWithTag(itemNameTag(InsightItem.ARTIST) + ":cell", useUnmergedTree = true)
            assertEquals(widths.size, buttons.size)
            return widths.indices.map { i ->
                val cell = cells[i].getUnclippedBoundsInRoot()
                Line(
                    buttons[i].config[SemanticsProperties.ContentDescription].single(),
                    with(density) { cell.left.toPx() }.roundToInt(),
                    with(density) { (cell.right - cell.left).toPx() }.roundToInt(),
                )
            }
        }
    }

    // A row as read: its description, where its name's cell starts and how wide it is, in pixels.
    private class Line(
        val said: String,
        val left: Int,
        val room: Int,
    )

    @Composable
    private fun Rows(
        first: String,
        notes: FakeItemNotes,
    ) {
        val playing = playing(first)
        val track = HomeFixtures.track(playing, sources = mapOf(SourceId.MUSICBRAINZ to done()))
        val context = ItemContext(track, settings, notes, false, {}, { _, _, _ -> }, readAloud = null)
        val rows = itemRows(playing, normalizer.normalize(playing)).filter { it.item == InsightItem.ARTIST }
        ItemRows(rows, false, itemFolds(ItemFoldMemory(), playing, NewTrackFold.FOLDED), context) {}
    }

    @Test
    fun aRowOfNamesOfSymbolsAloneKeepsEachArtist() {
        val header = sundown.copy(title = "Hello? Is This Thing On?", artists = "!!!, †††", album = "A")
        val row = itemRows(header, normalizer.normalize(header)).single { it.item == InsightItem.ARTIST }
        assertEquals("!!!", row.name)
        assertEquals(listOf("†††"), row.others)
        // The same symbols twice are one artist.
        val twice = header.copy(artists = "!!!, !!!")
        assertEquals(
            emptyList<String>(),
            itemRows(twice, normalizer.normalize(twice))
                .single {
                    it.item ==
                        InsightItem.ARTIST
                }.others,
        )
    }

    @Test
    fun bothSymbolArtistsHaveTheirSectionInThePanel() {
        val header = sundown.copy(title = "Hello? Is This Thing On?", artists = "!!!, †††", album = "A", mediaId = "s")
        val track = HomeFixtures.track(header, sources = mapOf(SourceId.MUSICBRAINZ to done()))
        val notes = FakeItemNotes()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val context = ItemContext(track, settings, notes, false, {}, { _, _, _ -> }, readAloud = null)
                    ItemPage(
                        InsightItem.ARTIST,
                        itemRow(InsightItem.ARTIST, header, normalizer.normalize(header), track),
                        context,
                        tapped = true,
                    )
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(alsoOnTrackTag("†††")).assertExists()
            compose.onNodeWithTag(itemWriteTag(InsightItem.named("†††"))).assertExists()
        }
    }

    private companion object {
        const val GOTYE = "Gotye"
        const val LETTERS = "Wnmo Ilfw Qrsa Txyz Bcde Fghj Kpuv Wnmo Ilfw Qrsa Txyz Bcde Fghj Kpuv"
    }
}
