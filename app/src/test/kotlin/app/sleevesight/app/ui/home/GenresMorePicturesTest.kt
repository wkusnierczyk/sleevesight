package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.PlacedLabel
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.settings.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * *Genres*' panel with genres past the rows' five (#1281), drawn as on a phone, light and dark, at the
 * default font size and twice it: folded, under *Show more*; then shown, under "Weaker evidence", with
 * one of them open and each row's extras outlined in *Genre tags*. The pictures are written only when
 * `SLEEVESIGHT_PICTURES` names a directory; otherwise each case only checks the pill is there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenresMorePicturesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private fun label(
        tag: String,
        level: GenreLevel,
        source: LabelSource = LabelSource.LASTFM,
    ): TrackLabel {
        val shown = tag.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        val artist = "Radiohead".takeIf { level == GenreLevel.ARTIST }
        val genre = GenreLabel(tag, shown, tag.replace(" ", ""), level = level, artist = artist)
        return TrackLabel(genre, listOf(LabelCredit(source, "https://example.org/$tag")), TrackGenres.MIN_VOTES)
    }

    private fun row(
        level: GenreLevel,
        five: List<String>,
        extras: List<Pair<String, LabelSource>>,
    ) = GenreRow(
        level,
        "Radiohead".takeIf { level == GenreLevel.ARTIST },
        five.map { label(it, level) },
        extras = extras.map { (tag, source) -> label(tag, level, source) },
    )

    private val lastFm = LabelSource.LASTFM
    private val mb = LabelSource.MUSICBRAINZ

    private val trackRow =
        row(
            GenreLevel.TRACK,
            listOf("alternative rock", "art rock", "progressive rock", "rock", "experimental"),
            listOf("electronic" to lastFm, "britpop" to lastFm, "psychedelic rock" to mb, "indie rock" to lastFm),
        )

    private val genres =
        TrackGenres(
            listOf(
                trackRow,
                row(
                    GenreLevel.ARTIST,
                    listOf("alternative rock", "art rock", "electronic", "rock", "experimental"),
                    listOf("indie rock" to lastFm, "britpop" to lastFm, "post-rock" to mb, "trip hop" to lastFm),
                ),
                row(
                    GenreLevel.ALBUM,
                    listOf("alternative rock", "art rock", "rock", "experimental", "progressive rock"),
                    listOf("britpop" to lastFm, "space rock" to mb),
                ),
            ),
            credited = listOf("Radiohead"),
        )

    private fun show(
        dark: Boolean,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                                GenresContent(track().copy(genres = genres), Settings())
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun picture(name: String) {
        val dir = out ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun pictures() {
        val britpop = tagOf(GENRE_PREFIX, PlacedLabel(trackRow, trackRow.extras[1]))
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                show(dark) {
                    compose.onNodeWithTag(GENRES_MORE_TAG).assertExists()
                    picture("more-folded-$theme-$size")
                    compose.onNodeWithTag(GENRES_MORE_TAG).performScrollTo().performClick()
                    compose.onNodeWithTag(britpop).performScrollTo().performClick()
                    compose.waitForIdle()
                    picture("more-shown-$theme-$size")
                    compose.onNodeWithTag(GENRE_TAGS_TAG).performScrollTo()
                    compose.waitForIdle()
                    picture("more-tags-$theme-$size")
                }
            }
        }
        RuntimeEnvironment.setFontScale(1f)
    }
}
