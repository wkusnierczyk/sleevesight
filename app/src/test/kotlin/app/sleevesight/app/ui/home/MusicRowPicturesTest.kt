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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
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
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.NewTrackFold
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.WorkLyrics
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The song's row called *Music* on a classical track (#1331), by the test that tells the Composer row
 * from the Author row, and *Song* otherwise: Beethoven's Ninth (a Composer row), *Nessun dorma* with
 * its librettists (an Authors row, its panel's headings *Music by* and *Words by*), a cover of a
 * songwriter's own song (an Author row) and a pop track (no split). Each row is checked as a screen
 * reader reads it, with its button's action, its panel's title, and its no-evidence line, in the panel
 * and, under *Separate pages*, on its page under the page's title; the first two are drawn as on a
 * phone, light and dark, at the default font size and twice it, the Music panel open, and the
 * opera's Authors panel; with `SLEEVESIGHT_PICTURES` set, saved there as pictures, as
 * `ComposerPanelPicturesTest` saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h2200dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicRowPicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private val settings = Settings(writeAutomatically = WriteAutomatically.NEVER)

    // A situation: the track, its roles and its work's credits, what its song's row is read as, and its
    // song's and composer's insights. Its button's action and its no-evidence line name the music where
    // the row is Music, else the song.
    private class Situation(
        val playing: NowPlaying,
        val roles: ArtistRoles?,
        val lyrics: WorkLyrics,
        val songRow: String,
        val song: String,
        val composer: String? = null,
    ) {
        val music: Boolean get() = songRow.startsWith("Music, ")
        val action: String get() = if (music) MUSIC_ACTION else SONG_ACTION
        val none: String get() = if (music) MUSIC_NONE else SONG_NONE
    }

    private fun playing(
        title: String,
        artists: String,
        album: String,
    ) = sundown.copy(title = title, artists = artists, album = album, mediaId = "spotify:track:$title")

    private val ninth =
        Situation(
            playing(NINTH, "$BEETHOVEN, $KARAJAN, $BERLIN", "Beethoven: Symphony No. 9"),
            ArtistRoles(listOf(BEETHOVEN), listOf(KARAJAN, BERLIN), classical = true),
            WorkLyrics.NONE,
            "Music, $NINTH, uses AI",
            "The music\n\n" +
                "Beethoven finished the symphony in 1824, its finale setting Schiller's “Ode to Joy” for " +
                "soloists and chorus [W1].\n\n" +
                "This recording\n\n" +
                "Karajan recorded it with the Berlin Philharmonic in 1962 [W1].",
        )

    private val opera =
        Situation(
            playing("Nessun Dorma", "$PUCCINI, $PAVAROTTI, $MEHTA", "The Three Tenors in Concert 1994"),
            ArtistRoles(listOf(PUCCINI), listOf(PAVAROTTI, MEHTA), classical = true),
            WorkLyrics(librettists = listOf("Giuseppe Adami", "Renato Simoni")),
            "Music, Nessun Dorma, uses AI",
            "The aria closes the first scene of Turandot's last act [W1].",
            composer = "Giacomo Puccini (1858–1924) was an Italian composer of opera [W1].",
        )

    private val cover =
        Situation(
            playing("Hallelujah", "Jeff Buckley", "Grace"),
            ArtistRoles(listOf("Leonard Cohen"), listOf("Jeff Buckley")),
            WorkLyrics(lyricists = listOf("Leonard Cohen")),
            "Song, Hallelujah, uses AI",
            "Buckley learned the song from John Cale's version [W1].",
        )

    private val pop =
        Situation(
            playing("Somebody That I Used To Know", "Gotye, Kimbra", "Making Mirrors"),
            null,
            WorkLyrics.NONE,
            "Song, Somebody That I Used To Know, uses AI",
            "The song samples Luiz Bonfá's “Seville” [W1].",
        )

    private val lines: EvidenceSet =
        EvidenceSet.of(listOf(draft(SourceId.WIKIPEDIA, "A line about the song.")), JvmHasher)

    private fun track(situation: Situation): TrackState =
        HomeFixtures
            .track(situation.playing, sources = mapOf(SourceId.MUSICBRAINZ to done()))
            .copy(
                resolution =
                    situation.roles?.let { Resolution(recordingMbid = "r", roles = it, lyrics = situation.lyrics) },
            )

    private fun shown(
        text: String,
        parts: Boolean,
    ): ItemShown {
        val checked = GroundingCheck.check(text, lines.ids.toSet(), false, headings = parts)
        val written = InsightState.Written((checked as InsightReply.Written).grounded, cached = false)
        return ItemShown.Shown(written, ItemEvidence(lines, emptyMap()), "Claude Haiku 5.5")
    }

    // The song's insight written, or with nothing to write it from ([none]); the composer's written.
    private fun notes(
        situation: Situation,
        none: Boolean,
    ) = FakeItemNotes().apply {
        val song =
            if (none) {
                ItemShown.Shown(InsightState.NoEvidence, ItemEvidence(lines, emptyMap()))
            } else {
                shown(situation.song, parts = situation.song.startsWith("The music"))
            }
        shown.value =
            mapOfNotNull(
                InsightItem.SONG to song,
                situation.composer?.let { InsightItem.COMPOSER to shown(it, parts = false) },
            )
    }

    private fun <K, V> mapOfNotNull(vararg pairs: Pair<K, V>?): Map<K, V> = pairs.filterNotNull().toMap()

    private fun show(
        situation: Situation,
        none: Boolean,
        dark: Boolean = false,
        block: () -> Unit,
    ) {
        val track = track(situation)
        val normalized = normalizer.normalize(situation.playing)
        val split = splitOf(track, normalized)
        val rows = itemRows(situation.playing, normalized, split)
        val notes = notes(situation, none)
        val memory = ItemFoldMemory()
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
            block()
        }
    }

    private fun clickLabelled(label: String) =
        SemanticsMatcher("click labelled $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    @Test
    fun theSongsRowIsMusicOnAClassicalTrackAndSongOtherwise() {
        for (situation in listOf(ninth, opera, cover, pop)) {
            show(situation, none = true) {
                compose
                    .onNodeWithTag(itemRowTag(InsightItem.SONG))
                    .assertContentDescriptionEquals(situation.songRow)
                    .assert(clickLabelled(situation.action))
                    .performClick()
                compose.waitForIdle()
                val kind = if (situation.music) "Music" else "Song"
                compose
                    .onNodeWithTag(itemPanelTag(InsightItem.SONG) + ":heading")
                    .assert(hasText(kind))
                compose.onNodeWithText(situation.none).assertExists()
            }
        }
    }

    @Test
    fun underSeparatePagesTheSongsPageIsTitledAsItsRowIs() {
        for (situation in listOf(ninth, opera, cover, pop)) {
            val track = track(situation)
            val state =
                NowPlayingState(
                    PipelineView(track, aiReady = true),
                    track.nowPlaying,
                    PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                    settings = settings.copy(layout = Layout.SEPARATE_PAGES),
                )
            val actions = NowPlayingActions(normalizer::normalize, itemNotes = notes(situation, none = true))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.setContent { NowPlayingPageContent(HomePage.Item(InsightItem.SONG.name), state, actions) {} }
                }
                compose.waitForIdle()
                val (kind, other) = if (situation.music) "Music" to "Song" else "Song" to "Music"
                assertEquals(1, compose.onAllNodesWithText(kind).fetchSemanticsNodes().size)
                compose.onNodeWithText(other).assertDoesNotExist()
                compose.onNodeWithText(situation.none).assertExists()
            }
        }
    }

    @Test
    fun theAuthorsPanelsHeadingsAreMusicByAndWordsBy() =
        show(opera, none = false) {
            compose.onNodeWithTag(itemRowTag(InsightItem.COMPOSER)).performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Music by").assertExists()
            compose.onNodeWithText("Words by").assertExists()
            compose.onNodeWithText("Music").assertDoesNotExist()
            compose.onNodeWithText("Words").assertDoesNotExist()
        }

    @Test
    fun pictures() {
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                show(ninth, none = false, dark = dark) {
                    compose.onNodeWithTag(itemRowTag(InsightItem.SONG)).performClick()
                    compose.waitForIdle()
                    compose.onNodeWithTag(itemPanelTag(InsightItem.SONG)).assertExists()
                    save("ninth-$theme-$size-music")
                }
                show(opera, none = false, dark = dark) {
                    compose.onNodeWithTag(itemRowTag(InsightItem.COMPOSER)).performClick()
                    compose.waitForIdle()
                    compose.onNodeWithTag(itemPanelTag(InsightItem.COMPOSER)).assertExists()
                    save("opera-$theme-$size-authors")
                }
            }
        }
    }

    private fun save(name: String) {
        val dir = out ?: return
        val bitmap = compose.onNodeWithTag(PICTURE).captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val PICTURE = "picture"
        const val NINTH = "Symphony No. 9 in D Minor, Op. 125 \"Choral\": IV. Presto"
        const val BEETHOVEN = "Ludwig van Beethoven"
        const val KARAJAN = "Herbert von Karajan"
        const val BERLIN = "Berliner Philharmoniker"
        const val PUCCINI = "Giacomo Puccini"
        const val PAVAROTTI = "Luciano Pavarotti"
        const val MEHTA = "Zubin Mehta"
        const val MUSIC_ACTION = "show the music’s insight"
        const val SONG_ACTION = "show the song’s insight"
        const val MUSIC_NONE = "The sources give nothing to write the music’s insight from."
        const val SONG_NONE = "The sources give nothing to write the song’s insight from."
    }
}
