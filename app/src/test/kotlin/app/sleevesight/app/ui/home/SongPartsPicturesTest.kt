package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
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
import app.sleevesight.core.synthesis.SongParts
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Song panel's insight in two parts (#1133), drawn as on a phone, light and dark, at the default
 * font size and twice it, for situations of the D-Classical mock-up: K. 545 resolved (1), its two parts
 * under *The music* and *This recording*, each heading read as a heading; and a pop track (5), written
 * in one part as before, with no heading. Each is checked, and with `SLEEVESIGHT_PICTURES` set, also
 * saved there as a picture, as `ComposerPanelPicturesTest` saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2200dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SongPartsPicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private val settings = Settings(writeAutomatically = WriteAutomatically.NEVER)

    // A situation: its number, the track, its roles, and the song's insight as written.
    private class Situation(
        val number: Int,
        val playing: NowPlaying,
        val roles: ArtistRoles?,
        val reply: String,
        val parts: Boolean,
    )

    private fun playing(
        title: String,
        artists: String,
        album: String,
    ) = sundown.copy(title = title, artists = artists, album = album, mediaId = "spotify:track:$title")

    private val situations =
        listOf(
            Situation(
                1,
                playing(K545, "$MOZART, $KHATIA, $ACADEMY", "Mozart: Piano Concertos Nos. 20 & 23"),
                ArtistRoles(listOf(MOZART), listOf(KHATIA, ACADEMY)),
                "The music\n\n" +
                    "Mozart entered the sonata in his own catalogue on 26 June 1788 as a little keyboard sonata " +
                    "“for beginners” [W1]. Publishers later called it the Sonata facile [MB1]. The Andante, in G " +
                    "major, is the middle of its three movements: a long singing melody over a steady " +
                    "accompaniment [W1].\n\n" +
                    "This recording\n\n" +
                    "Buniatishvili recorded it for her 2024 album, where the solo sonata sits between the two " +
                    "concertos [S1].",
                parts = true,
            ),
            Situation(
                5,
                playing("Somebody That I Used To Know", "Gotye, Kimbra", "Making Mirrors"),
                null,
                "The song samples Luiz Bonfá's “Seville” [W1], and Kimbra sings the second verse from the " +
                    "other side of the break-up [W1].",
                parts = false,
            ),
        )

    private val lines: EvidenceSet =
        EvidenceSet.of(
            listOf(
                draft(SourceId.WIKIPEDIA, "A line about the song."),
                draft(SourceId.MUSICBRAINZ, "A line about the work."),
                draft(SourceId.WEB, "A page about the album.", url = "https://example.com/album"),
            ),
            JvmHasher,
        )

    private fun track(situation: Situation): TrackState =
        HomeFixtures
            .track(situation.playing, sources = mapOf(SourceId.MUSICBRAINZ to done()))
            .copy(resolution = situation.roles?.let { Resolution(recordingMbid = "r", roles = it) })

    private fun notes(situation: Situation) =
        FakeItemNotes().apply {
            val checked = GroundingCheck.check(situation.reply, lines.ids.toSet(), false, headings = situation.parts)
            val written = InsightState.Written((checked as InsightReply.Written).grounded, cached = false)
            shown.value =
                mapOf(
                    InsightItem.SONG to
                        ItemShown.Shown(written, ItemEvidence(lines, emptyMap()), "Claude Haiku 5.5"),
                )
        }

    private fun pictures(
        situation: Situation,
        size: String,
        dark: Boolean,
    ) {
        val track = track(situation)
        val normalized = normalizer.normalize(situation.playing)
        val split = splitOf(track, normalized)
        val rows = itemRows(situation.playing, normalized, split)
        val notes = notes(situation)
        val memory = ItemFoldMemory()
        val theme = if (dark) "dark" else "light"
        val name = "${situation.number}-$theme-$size-song"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp).testTag(PICTURE),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                val context =
                                    ItemContext(track, settings, notes, false, {}, { _, _, _ -> }, readAloud = null)
                                val folds = itemFolds(memory, situation.playing, NewTrackFold.FOLDED)
                                ItemRows(rows, false, folds, context) {}
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(itemRowTag(InsightItem.SONG)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(itemPanelTag(InsightItem.SONG)).assertExists()
            for (heading in SongParts.HEADINGS) {
                val node = compose.onNodeWithTag(songPartTag(heading))
                if (situation.parts) {
                    node.assertTextEquals(heading).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                } else {
                    node.assertDoesNotExist()
                }
            }
            save(name)
        }
    }

    private fun save(name: String) {
        val dir = out ?: return
        val bitmap = compose.onNodeWithTag(PICTURE).captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun pictures() {
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (situation in situations) {
                for (dark in listOf(false, true)) pictures(situation, size, dark)
            }
        }
    }

    private companion object {
        const val PICTURE = "picture"
        const val K545 = "Mozart: Piano Sonata No. 16 in C Major, K. 545 \"Sonata facile\": II. Andante"
        const val MOZART = "Wolfgang Amadeus Mozart"
        const val KHATIA = "Khatia Buniatishvili"
        const val ACADEMY = "Academy of St. Martin in the Fields"
    }
}
