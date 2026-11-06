package app.sleevesight.app.session

import android.media.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Every report in `fixtures/sessions/ads/` (#880) is told as the fixture says: an ad, or a track.
 * The fixtures are synthetic until the capture of Spotify Free playing an ad comes (#897); a capture
 * is added as a file of its own there (its `README.md`), and this test takes it as it is.
 */
@RunWith(RobolectricTestRunner::class)
class AdFixturesTest {
    // The tests run in `app/`.
    private val dir = File("../fixtures/sessions/ads")

    private val files = dir.listFiles { file -> file.extension == "tsv" }.orEmpty().sortedBy { it.name }

    @Test
    fun `there are fixtures, and each report in them is told as it says`() {
        assertTrue("no fixtures in $dir", files.isNotEmpty())
        val wrong =
            files.flatMap { file ->
                file
                    .readLines()
                    .withIndex()
                    .filter { (_, line) -> line.isNotBlank() && !line.startsWith("#") }
                    .mapNotNull { (index, line) ->
                        val columns = line.split('\t')
                        val kind = columns[0]
                        val item = columns[3].takeUnless { it == "-" }
                        val ad = AdDetection.isAd(columns[1], parse(columns[2]), item)
                        "${file.name}:${index + 1}: $kind".takeIf { ad != (kind == "ad") }
                    }
            }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `a report is read back as SessionDump logged it`() {
        val metadata = parse("{android.media.metadata.ADVERTISEMENT=1, android.media.metadata.ARTIST=A, B}")
        assertEquals(1L, metadata.getLong(AdDetection.KEY_ADVERTISEMENT))
        assertEquals("A, B", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
    }

    // The metadata as `SessionDump.describe` logs it: `{key=value, …}`, keys sorted; a long key's
    // value as a number, a bitmap as its size (left out here), any other value as text.
    private fun parse(dump: String): MediaMetadata {
        val builder = MediaMetadata.Builder()
        dump
            .removePrefix("{")
            .removeSuffix("}")
            .split(ENTRY)
            .filter { it.isNotBlank() }
            .forEach { entry ->
                val key = entry.substringBefore('=')
                val value = entry.substringAfter('=')
                when {
                    value.startsWith("bitmap ") -> Unit
                    key in LONG_KEYS -> builder.putLong(key, value.toLong())
                    value.startsWith(LONG) -> builder.putLong(key, longOf(value))
                    else -> builder.putString(key, value.removePrefix("text:"))
                }
            }
        return builder.build()
    }

    // A long under a key `SessionDump` does not know: `long:5`, or `long:0 (or not a long)`.
    private fun longOf(value: String): Long = value.removePrefix(LONG).substringBefore(' ').toLong()

    private companion object {
        // How `SessionDump` logs a long under a key it does not know.
        const val LONG = "long:"

        // A comma that begins the next key: a value may hold commas of its own ("A, B").
        val ENTRY = Regex(""", (?=[A-Za-z0-9_.]+=)""")

        val LONG_KEYS =
            setOf(
                MediaMetadata.METADATA_KEY_DURATION,
                MediaMetadata.METADATA_KEY_YEAR,
                MediaMetadata.METADATA_KEY_TRACK_NUMBER,
                MediaMetadata.METADATA_KEY_NUM_TRACKS,
                MediaMetadata.METADATA_KEY_DISC_NUMBER,
                "android.media.metadata.BT_FOLDER_TYPE",
                AdDetection.KEY_ADVERTISEMENT,
                "android.media.metadata.DOWNLOAD_STATUS",
            )
    }
}
