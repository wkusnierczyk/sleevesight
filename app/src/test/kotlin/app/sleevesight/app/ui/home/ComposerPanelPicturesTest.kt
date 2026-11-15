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
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.ItemEvidence
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Composer row beside the Artist row on a classical track (#1199), drawn as on a phone, light and
 * dark, at the default font size and twice it, for the situations of the D-Classical mock-up: K. 545
 * resolved (1), *Nessun Dorma* with the roles MusicBrainz told (2), the roles unknown (4) and a pop
 * track (5). Each shows the artists under the title and the rows closed, then the Artist panel open
 * with another performer's chip opened, its panel in the chip's place (#1288), then that panel
 * closed, the chip back, then the Composer panel open; each is checked, and with
 * `SLEEVESIGHT_PICTURES` set, also saved there as a picture, as `SongVersionPicturesTest` saves its
 * own. The Artist row is *Performer* where the Composer row shows (#1276), *Artist* otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2200dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerPanelPicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private val settings = Settings(writeAutomatically = WriteAutomatically.NEVER)

    // A situation: its number, the track, its sample insights by item, and what is checked.
    private class Situation(
        val number: Int,
        val playing: NowPlaying,
        val roles: ArtistRoles?,
        val insights: Map<InsightItem, String>,
        val artistRow: String,
        val chip: String? = null,
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
                mapOf(
                    InsightItem.COMPOSER to
                        "Wolfgang Amadeus Mozart (1756–1791) was an Austrian composer of the Classical period, " +
                        "who wrote more than 600 works [W1].",
                    InsightItem.ARTIST to
                        "Khatia Buniatishvili is a Georgian-French pianist, born in Tbilisi in 1987 [W1].",
                    InsightItem.named(ACADEMY) to
                        "The Academy of St Martin in the Fields is a chamber orchestra founded in London in 1958 [W1].",
                ),
                "Performer, Khatia Buniatishvili, uses AI",
                chip = ACADEMY,
            ),
            Situation(
                2,
                playing("Nessun Dorma", "$PUCCINI, $PAVAROTTI, $MEHTA, $LA_PHIL", "The Three Tenors in Concert 1994"),
                ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI, MEHTA, LA_PHIL)),
                mapOf(
                    InsightItem.COMPOSER to
                        "Giacomo Puccini (1858–1924) was an Italian composer of opera, " +
                        "among them La bohème and Tosca [W1].",
                    InsightItem.ARTIST to "Luciano Pavarotti (1935–2007) was an Italian tenor [W1].",
                    InsightItem.named(MEHTA) to "Zubin Mehta is an Indian conductor, born in Bombay in 1936 [W1].",
                ),
                "Performer, Luciano Pavarotti, uses AI",
                chip = MEHTA,
            ),
            Situation(
                4,
                playing("Nessun Dorma", "$PUCCINI, $PAVAROTTI, $MEHTA, $LA_PHIL", "The Three Tenors in Concert 1994"),
                null,
                mapOf(
                    InsightItem.ARTIST to
                        "Giacomo Puccini (1858–1924) was an Italian composer of opera, " +
                        "among them La bohème and Tosca [W1].",
                ),
                "Artist, Giacomo Puccini, uses AI",
            ),
            Situation(
                5,
                playing("Somebody That I Used To Know", "Gotye, Kimbra", "Making Mirrors"),
                null,
                mapOf(
                    InsightItem.ARTIST to
                        "Gotye is the stage name of Wouter De Backer, a Belgian-Australian musician [W1].",
                ),
                "Artist, Gotye, uses AI",
            ),
        )

    private val lines: EvidenceSet =
        EvidenceSet.of(listOf(draft(SourceId.WIKIPEDIA, "A line about the artist.", scope = Scope.ARTIST)), JvmHasher)

    private fun track(situation: Situation): TrackState =
        HomeFixtures
            .track(situation.playing, sources = mapOf(SourceId.MUSICBRAINZ to done()))
            .copy(resolution = situation.roles?.let { Resolution(recordingMbid = "r", roles = it) })

    private fun notes(situation: Situation) =
        FakeItemNotes().apply {
            shown.value =
                situation.insights.mapValues { (_, text) ->
                    ItemShown.Shown(written(text, from = lines), ItemEvidence(lines, emptyMap()), "Claude Haiku 5.5")
                }
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
        val name = "${situation.number}-$theme-$size"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Column(
                                Modifier.fillMaxWidth().padding(16.dp).testTag(PICTURE),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                KeyData(normalized, situation.playing, HeaderYears(), split)
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
            compose.onNodeWithTag(itemRowTag(InsightItem.ARTIST)).assertContentDescriptionEquals(situation.artistRow)
            save("$name-a-rows")
            compose.onNodeWithTag(itemRowTag(InsightItem.ARTIST)).performClick()
            compose.waitForIdle()
            situation.chip?.let { chip ->
                compose.onNodeWithText("Also performing on this recording").assertExists()
                compose.onNodeWithTag(itemChipTag(chip)).performClick()
                compose.waitForIdle()
                compose.onNodeWithTag(itemPanelTag(InsightItem.named(chip))).assertExists()
                compose.onNodeWithTag(itemChipTag(chip)).assertDoesNotExist()
            }
            save("$name-b-artist")
            situation.chip?.let { chip ->
                compose.onNodeWithTag(itemPanelTag(InsightItem.named(chip)) + ":close").performClick()
                compose.waitForIdle()
                compose.onNodeWithTag(itemPanelTag(InsightItem.named(chip))).assertDoesNotExist()
                compose.onNodeWithTag(itemChipTag(chip)).assertExists()
                save("$name-b2-artist-closed")
            }
            if (split != null) {
                compose.onNodeWithTag(itemPanelTag(InsightItem.ARTIST) + ":close").performClick()
                compose.onNodeWithTag(itemRowTag(InsightItem.COMPOSER)).performClick()
                compose.waitForIdle()
                compose.onNodeWithTag(itemPanelTag(InsightItem.COMPOSER)).assertExists()
                save("$name-c-composer")
            }
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
        const val PUCCINI = "Giacomo Puccini"
        const val PAVAROTTI = "Luciano Pavarotti"
        const val MEHTA = "Zubin Mehta"
        const val LA_PHIL = "Los Angeles Philharmonic"
    }
}
