package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.WorkLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A classical work's authors the player does not credit (#1298): Spotify credits *Nessun Dorma* to
 * Pavarotti alone, and MusicBrainz's work names Puccini its composer and Adami and Simoni its
 * librettists. Home names them as if credited (an Authors row, Pavarotti's *Performer*, the *Authors:*
 * line under the title), and that line ends with one citation chip of MusicBrainz's line on the work,
 * which opens *Sources* there; a line naming only credited authors (Beethoven's Ninth) has none.
 */
@RunWith(RobolectricTestRunner::class)
class UncreditedAuthorsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val alone =
        sundown.copy(title = "Nessun Dorma", artists = PAVAROTTI, album = "The Essential Pavarotti")
    private val doma =
        sundown.copy(
            title = "Nessun doma - Turandot",
            artists = "$PUCCINI, $PAVAROTTI, London Philharmonic Orchestra, Zubin Mehta",
            album = "Puccini: Turandot",
        )
    private val ninth =
        sundown.copy(
            title = "Symphony No. 9 in D Minor, Op. 125: IV. Presto",
            artists = "$BEETHOVEN, Berliner Philharmoniker, Herbert von Karajan",
            album = "Beethoven: Symphony No. 9",
        )

    private val libretto = WorkLyrics(librettists = listOf(ADAMI, SIMONI))

    // MusicBrainz's lines as its evidence writes them: the release (MB1), then on the work its composer
    // (MB2) and its librettists (MB3).
    private val lines =
        EvidenceSet.of(
            listOf(
                draft(SourceId.MUSICBRAINZ, "“Nessun Dorma” was first released in 1990.", "$MB/release-group/g"),
                draft(SourceId.MUSICBRAINZ, "The music of “Nessun dorma” was composed by $PUCCINI.", "$MB/work/w"),
                draft(
                    SourceId.MUSICBRAINZ,
                    "The libretto of this aria was written by $ADAMI and $SIMONI.",
                    "$MB/work/w",
                ),
            ),
            JvmHasher,
        )

    private fun split(
        playing: NowPlaying,
        roles: ArtistRoles,
        lyrics: WorkLyrics,
    ) = splitOf(
        HomeFixtures.track(playing).copy(resolution = Resolution(recordingMbid = "r", roles = roles, lyrics = lyrics)),
        normalizer.normalize(playing),
    )

    private val aloneRoles =
        ArtistRoles(emptyList(), listOf(PAVAROTTI), classical = true, uncredited = listOf(PUCCINI))

    @Test
    fun theUncreditedComposerAndLibrettistsAreNamedAsIfCreditedTheRowsUnmarked() {
        val split = checkNotNull(split(alone, aloneRoles, libretto))
        val rows = itemRows(alone, normalizer.normalize(alone), split)
        val items = listOf(InsightItem.SONG, InsightItem.COMPOSER, InsightItem.ARTIST, InsightItem.ALBUM)
        assertEquals(items, rows.map { it.item })
        assertEquals("$PUCCINI, $ADAMI, $SIMONI", rows[1].name)
        assertEquals(R.string.home_item_authors, rows[1].kind)
        assertEquals(PAVAROTTI, rows[2].name)
        assertEquals(R.string.home_item_performer, rows[2].kind)
        assertEquals(R.string.home_item_song_classical, rows[0].kind)
    }

    @Test
    fun theCitedLineNamesAnUncreditedAuthorAndNoneIsCitedWhenAllAreCredited() {
        // Puccini uncredited: the composer's line; Puccini credited, the librettists not: theirs.
        assertEquals("MB2", authorsCite(split(alone, aloneRoles, libretto), lines))
        val credited = ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI), classical = true)
        assertEquals("MB3", authorsCite(split(doma, credited, libretto), lines))
        val ninthRoles = ArtistRoles(listOf(BEETHOVEN), listOf("Berliner Philharmoniker"), classical = true)
        assertNull(authorsCite(split(ninth, ninthRoles, WorkLyrics.NONE), lines))
        // No line on the work in *Sources*: no chip.
        assertNull(authorsCite(split(alone, aloneRoles, libretto), null))
    }

    @Test
    fun theCitedLineNamesTheAuthorWholeAndIsAlwaysACreditLine() {
        val split = split(alone, aloneRoles, libretto)
        val music = "The music of “Nessun dorma” was composed by $PUCCINI and X."
        val lyrics = "The lyrics of “Nessun dorma” were written by $PUCCINI Jr."
        // A line on the work that credits no one, then one naming a name that holds Puccini's, then his.
        val work = "$MB/work/w"
        val tricky =
            EvidenceSet.of(
                listOf(
                    draft(SourceId.MUSICBRAINZ, "“Nessun dorma” is a traditional song.", work),
                    draft(SourceId.MUSICBRAINZ, lyrics, work),
                    draft(SourceId.MUSICBRAINZ, music, work),
                ),
                JvmHasher,
            )
        assertEquals("MB3", authorsCite(split, tricky))
        // None names an uncredited author: the first credit line, never the one that credits no one.
        val none = EvidenceSet.of(tricky.lines.take(2).map { draft(it.source, it.text, it.url) }, JvmHasher)
        assertEquals("MB2", authorsCite(split, none))
    }

    @Test
    fun theLineEndsWithOneChipReadAsItsSourceWhoseTapOpensSourcesThere() {
        val split = checkNotNull(split(alone, aloneRoles, libretto))
        val cited = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    KeyData(
                        normalizer.normalize(alone),
                        alone,
                        HeaderYears(),
                        split,
                        notes = PanelNotes(onCite = { id -> cited += id }, lines = lines),
                    )
                }
            }
            val line = compose.onNodeWithTag(KEY_AUTHORS_TAG)
            val text = line.fetchSemanticsNode().config[SemanticsProperties.Text].single()
            assertEquals("Authors: $PUCCINI, $ADAMI, $SIMONI $CHIP", text.text)
            // One chip, the line's only link, at its end.
            val chip = text.getLinkAnnotations(0, text.length).single()
            assertEquals(CHIP, text.text.substring(chip.start, chip.end))
            assertEquals(text.length, chip.end)
            line.assertContentDescriptionEquals("Authors: $PUCCINI, $ADAMI, $SIMONI (Source MB2)")
            val action = line.fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
            assertEquals("Source MB2", action.label)
            compose.runOnIdle { action.action() }
            assertEquals(listOf("MB2"), cited)
        }
    }

    @Test
    fun aLineNamingOnlyCreditedAuthorsHasNoChip() {
        val roles = ArtistRoles(listOf(BEETHOVEN), listOf("Berliner Philharmoniker", "Herbert von Karajan"), true)
        val split = checkNotNull(split(ninth, roles, WorkLyrics.NONE))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    KeyData(normalizer.normalize(ninth), ninth, HeaderYears(), split, notes = PanelNotes(lines = lines))
                }
            }
            compose.onNodeWithTag(KEY_AUTHORS_TAG).assertTextEquals("Composer: $BEETHOVEN")
            val node = compose.onNodeWithTag(KEY_AUTHORS_TAG).fetchSemanticsNode()
            assertEquals(false, node.config.contains(SemanticsActions.CustomActions))
        }
    }

    private companion object {
        const val MB = "https://musicbrainz.org"

        // A chip's text, as the insight's (`citeLabel`).
        val CHIP = citeLabel("MB2")
        const val PUCCINI = "Giacomo Puccini"
        const val PAVAROTTI = "Luciano Pavarotti"
        const val ADAMI = "Giuseppe Adami"
        const val SIMONI = "Renato Simoni"
        const val BEETHOVEN = "Ludwig van Beethoven"
    }
}
