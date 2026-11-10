package app.sleevesight.app.ui.home

import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.core.pipeline.InsightItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The names the rows under *Key insights* go by (#1160): the song's title as under the cover, with
 * the version the normalizer dropped kept for its panel; a featured artist, a source work and a
 * variant left out as under the cover, with no note; the player's whole title, with no version, when
 * normalizing leaves no title.
 */
class ItemRowsTest {
    private fun song(title: String): ItemRow {
        val header = sundown.copy(title = title)
        return itemRows(header, normalizer.normalize(header)).single { it.item == InsightItem.SONG }
    }

    @Test
    fun aDroppedVersionIsLeftOutOfTheNameAndKept() {
        val row = song("Material Girl - 2024 Remaster")
        assertEquals("Material Girl", row.name)
        assertEquals("2024 Remaster", row.version)
    }

    @Test
    fun aVariantIsLeftOutAsUnderTheCoverWithNoVersion() {
        val row = song("Sundown (Acoustic)")
        assertEquals("Sundown", row.name)
        assertNull(row.version)
    }

    @Test
    fun aFeaturedArtistAndASourceWorkAreLeftOutAsUnderTheCover() {
        assertEquals("Sundown", song("Sundown (feat. Anne Murray)").name)
        assertEquals("Sundown", song("Sundown (From \"A Film\")").name)
        assertNull(song("Sundown (feat. Anne Murray)").version)
    }

    @Test
    fun aTitleNormalizingLeavesEmptyKeepsThePlayersWholeTitleAndNoVersion() {
        val title = "(feat. Anne Murray) - Live"
        // What the fallback is for: nothing left, a version dropped.
        val normalized = normalizer.normalize(sundown.copy(title = title))
        assertEquals("", normalized.title)
        assertEquals("Live", normalized.dropped)
        val row = song(title)
        assertEquals(title, row.name)
        assertNull(row.version)
    }

    @Test
    fun aPlainTitleIsTheNameWithNoVersion() {
        val row = song("Sundown")
        assertEquals("Sundown", row.name)
        assertNull(row.version)
    }
}
