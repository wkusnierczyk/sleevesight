package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
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

/**
 * The *Artists* row of a track crediting several artists (#1168), featured ones the same: "{first},
 * others" beside it where that fits on one line, else the first alone, read as shown; its panel (and
 * its page) the first artist's insight as before, then *Also on this track* and a section for each
 * further artist in the credit's order, each with its own *Write insight*, a call on its tap alone,
 * in every mode, an insight kept for that artist shown at once. A track crediting one artist, and a
 * classical one whose roles tell a composer apart (#1199, #1276, #1288), keep their rows and panels.
 * Drawn as on a phone, light and dark, at the default font size and twice it; with
 * `SLEEVESIGHT_PICTURES` set, saved there, as `MusicRowPicturesTest` saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2200dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtistsPanelScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private val onDemand = Settings(writeAutomatically = WriteAutomatically.NEVER)

    private fun playing(
        title: String,
        artists: String,
        album: String,
    ) = sundown.copy(title = title, artists = artists, album = album, mediaId = "spotify:track:$title")

    private val duet = playing("Somebody That I Used To Know", "$GOTYE, $KIMBRA", "Making Mirrors")
    private val many =
        playing(
            "So Appalled (feat. Jay-Z, Pusha T, Swizz Beatz, RZA)",
            "Kanye West",
            "My Beautiful Dark Twisted Fantasy",
        )
    private val long = playing("Hymn", "$LONG_NAME, $KIMBRA", "Hymns")
    private val solo = playing("Settle Down", KIMBRA, "Vows")

    private val lines: EvidenceSet =
        EvidenceSet.of(listOf(draft(SourceId.WIKIPEDIA, "A line about the artist.")), JvmHasher)

    private fun track(
        playing: NowPlaying,
        roles: ArtistRoles? = null,
    ): TrackState =
        HomeFixtures
            .track(playing, sources = mapOf(SourceId.MUSICBRAINZ to done()))
            .copy(resolution = roles?.let { Resolution(recordingMbid = "r", roles = it) })

    private fun written(text: String): ItemShown {
        val checked = GroundingCheck.check(text, lines.ids.toSet(), false, headings = false)
        val state = InsightState.Written((checked as InsightReply.Written).grounded, cached = true)
        return ItemShown.Shown(state, ItemEvidence(lines, emptyMap()), "Claude Haiku 5.5")
    }

    // Home's rows of [playing] under *Expandable boxes*, or with [page] the artist's page; the artist's
    // row opened by a tap unless [open] is false.
    @Suppress("LongParameterList")
    private fun show(
        playing: NowPlaying,
        notes: FakeItemNotes,
        page: Boolean = false,
        settings: Settings = onDemand,
        roles: ArtistRoles? = null,
        dark: Boolean = false,
        open: Boolean = true,
        block: () -> Unit,
    ) {
        val track = track(playing, roles)
        val normalized = normalizer.normalize(playing)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp)
                                    .testTag(PICTURE),
                            ) {
                                val context =
                                    ItemContext(track, settings, notes, false, {}, { _, _, _ -> }, readAloud = null)
                                if (page) {
                                    val row = itemRow(InsightItem.ARTIST, playing, normalized, track)
                                    ItemPage(InsightItem.ARTIST, row, context, tapped = true)
                                } else {
                                    val rows = itemRows(playing, normalized, splitOf(track, normalized))
                                    val folds = itemFolds(ItemFoldMemory(), playing, NewTrackFold.FOLDED)
                                    ItemRows(rows, false, folds, context) {}
                                }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            if (!page && open) {
                compose.onNodeWithTag(itemRowTag(InsightItem.ARTIST)).performScrollTo().performClick()
                compose.waitForIdle()
            }
            block()
        }
    }

    private fun clickLabelled(label: String) =
        SemanticsMatcher("click labelled $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    private fun top(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top

    private fun named(name: String) = InsightItem.named(name)

    @Test
    fun severalArtistsMakeOneArtistsRowByTheFirstWithTheOthersInTheCreditsOrder() {
        val rows = itemRows(duet, normalizer.normalize(duet)).single { it.item == InsightItem.ARTIST }
        assertEquals(R.string.home_item_artists, rows.kind)
        assertEquals(R.string.home_item_show_artists, rows.action)
        assertEquals(GOTYE, rows.name)
        assertEquals(listOf(KIMBRA), rows.others)
        // Featured artists the same, in the credit's order.
        val feat = itemRows(many, normalizer.normalize(many)).single { it.item == InsightItem.ARTIST }
        assertEquals("Kanye West", feat.name)
        assertEquals(listOf("Jay-Z", "Pusha T", "Swizz Beatz", "RZA"), feat.others)
    }

    @Test
    fun oneArtistKeepsTheArtistRowAsBefore() {
        val row = itemRows(solo, normalizer.normalize(solo)).single { it.item == InsightItem.ARTIST }
        assertEquals(R.string.home_item_artist, row.kind)
        assertEquals(R.string.home_item_show_artist, row.action)
        assertEquals(emptyList<String>(), row.others)
        // The same name twice is one artist.
        val twice = playing("Settle Down", "$KIMBRA, kimbra", "Vows")
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
    fun theRowReadsTheFirstAndOthersWhereThatFitsAsShown() =
        show(duet, FakeItemNotes(), open = false) {
            compose
                .onNodeWithTag(itemRowTag(InsightItem.ARTIST))
                .assertContentDescriptionEquals("Artists, $GOTYE, others, uses AI")
                .assert(clickLabelled("show the artists’ insights"))
        }

    @Test
    fun whereTheFirstAndOthersDoNotFitTheRowReadsTheFirstAloneNeverACutList() =
        show(long, FakeItemNotes(), open = false) {
            compose
                .onNodeWithTag(itemRowTag(InsightItem.ARTIST))
                .assertContentDescriptionEquals("Artists, $LONG_NAME, uses AI")
        }

    @Test
    fun theOneArtistsRowReadsAsBefore() =
        show(solo, FakeItemNotes(), open = false) {
            compose
                .onNodeWithTag(
                    itemRowTag(InsightItem.ARTIST),
                ).assertContentDescriptionEquals("Artist, $KIMBRA, uses AI")
        }

    @Test
    fun thePanelHasOneCloseTheFirstArtistsInsightThenASectionPerFurtherArtistUnderBoxes() = panelSections(page = false)

    @Test
    fun thePageHasTheFirstArtistsInsightThenASectionPerFurtherArtist() = panelSections(page = true)

    private fun panelSections(page: Boolean) {
        val notes = FakeItemNotes().apply { takenUp = false }
        show(many, notes, page = page) {
            if (!page) {
                compose.onNodeWithTag(itemPanelTag(InsightItem.ARTIST) + ":heading").assert(hasText("Artists"))
                // One *Close*, at the top: none in the sections.
                assertEquals(
                    1,
                    compose.onAllNodesWithTag(itemPanelTag(InsightItem.ARTIST) + ":close").fetchSemanticsNodes().size,
                )
                compose.onAllNodesWithTag(itemPanelTag(named("Jay-Z")) + ":close").assertCountEquals(0)
            }
            compose.onNodeWithTag(ALSO_ON_TRACK_TAG).assertExists()
            compose
                .onNode(hasText("Also on this track") and hasAnyAncestor(hasTestTag(ALSO_ON_TRACK_TAG)))
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            val names = listOf("Jay-Z", "Pusha T", "Swizz Beatz", "RZA")
            names.forEach { name ->
                compose
                    .onNode(hasText(name) and hasAnyAncestor(hasTestTag(alsoOnTrackTag(name))))
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                compose.onNodeWithTag(itemWriteTag(named(name))).assertExists()
            }
            // In the credit's order, under the first artist's insight.
            assertTrue(top(itemWriteTag(InsightItem.ARTIST)) < top(ALSO_ON_TRACK_TAG))
            names.map { top(alsoOnTrackTag(it)) }.zipWithNext().forEach { (a, b) -> assertTrue(a < b) }
            // The opening wrote the first artist's alone (a page opened here with no write due); the others none.
            val first = if (page) emptyList() else listOf(InsightItem.ARTIST)
            assertEquals(first, notes.writes)
            // A section's *Write insight*: that artist's alone, one call.
            compose.onNodeWithTag(itemWriteTag(named("Pusha T"))).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(first + named("Pusha T"), notes.writes)
        }
    }

    @Test
    fun underAnAutomaticModeTheFurtherArtistsAreNotWrittenByThemselves() {
        val notes = FakeItemNotes()
        val automatic = Settings(writeAutomatically = WriteAutomatically.ON_EVERY_TRACK_CHANGE)
        show(duet, notes, settings = automatic) {
            compose.onNodeWithTag(alsoOnTrackTag(KIMBRA)).assertExists()
            compose.onNodeWithTag(itemWriteTag(named(KIMBRA))).assertExists()
            assertEquals(emptyList<InsightItem>(), notes.writes.filter { it.artist != null })
            compose.onNodeWithTag(itemWriteTag(named(KIMBRA))).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(named(KIMBRA)), notes.writes.filter { it.artist != null })
        }
    }

    @Test
    fun aFurtherArtistsKeptInsightShowsAtOnceWithNoWrite() {
        val notes = FakeItemNotes().apply { shown.value = mapOf(named(KIMBRA) to written(KIMBRA_TEXT)) }
        show(duet, notes) {
            compose
                .onNode(
                    hasText("New Zealand singer", substring = true) and
                        hasAnyAncestor(hasTestTag(alsoOnTrackTag(KIMBRA))),
                ).assertExists()
            compose.onNodeWithTag(itemWriteTag(named(KIMBRA))).assertDoesNotExist()
            assertEquals(emptyList<InsightItem>(), notes.writes.filter { it.artist != null })
        }
    }

    @Test
    fun oneArtistsPanelHasNoFurtherSections() =
        show(solo, FakeItemNotes()) {
            compose.onNodeWithTag(itemPanelTag(InsightItem.ARTIST) + ":heading").assert(hasText("Artist"))
            compose.onNodeWithTag(ALSO_ON_TRACK_TAG).assertDoesNotExist()
        }

    @Test
    fun aClassicalTrackKeepsItsPerformerRowAndItsChips() {
        val nessun = playing("Nessun Dorma", "$PUCCINI, $PAVAROTTI, $MEHTA", "The Three Tenors in Concert 1994")
        val roles = ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI, MEHTA), classical = true)
        show(nessun, FakeItemNotes(), roles = roles) {
            compose.onNodeWithTag(itemPanelTag(InsightItem.ARTIST) + ":heading").assert(hasText("Performer"))
            compose.onNodeWithTag(ALSO_ON_TRACK_TAG).assertDoesNotExist()
            compose.onNodeWithText("Also performing on this recording").assertExists()
            compose.onNodeWithTag(itemChipTag(MEHTA)).assertExists()
        }
    }

    @Test
    fun pictures() {
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                show(duet, FakeItemNotes().apply { takenUp = false }, dark = dark, open = false) {
                    save("1168-duet-$theme-$size-row")
                }
                val notes =
                    FakeItemNotes().apply {
                        takenUp = false
                        shown.value =
                            mapOf(InsightItem.ARTIST to written(GOTYE_TEXT), named("Jay-Z") to written(JAY_TEXT))
                    }
                show(many, notes, dark = dark) { save("1168-many-$theme-$size-panel") }
                show(long, FakeItemNotes().apply { takenUp = false }, dark = dark, open = false) {
                    save("1168-long-$theme-$size-row")
                }
            }
        }
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun save(name: String) {
        val dir = out ?: return
        val bitmap = compose.onNodeWithTag(PICTURE).captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val PICTURE = "picture"
        const val GOTYE = "Gotye"
        const val KIMBRA = "Kimbra"
        const val LONG_NAME = "The Orchestra and Chorus of the Festival of Somewhere Far Away"
        const val PUCCINI = "Giacomo Puccini"
        const val PAVAROTTI = "Luciano Pavarotti"
        const val MEHTA = "Zubin Mehta"
        const val GOTYE_TEXT = "Kanye West is an American rapper and producer [W1]."
        const val JAY_TEXT = "Jay-Z is an American rapper and businessman [W1]."
        const val KIMBRA_TEXT = "Kimbra is a New Zealand singer and songwriter [W1]."
    }
}
