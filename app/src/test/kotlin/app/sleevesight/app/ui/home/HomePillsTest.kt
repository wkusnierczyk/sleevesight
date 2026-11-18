package app.sleevesight.app.ui.home

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.AlbumsKey
import app.sleevesight.app.pipeline.AlbumsState
import app.sleevesight.app.pipeline.NamedAlbum
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.resolution.MbMedium
import app.sleevesight.core.resolution.MbReleaseGroup
import app.sleevesight.core.resolution.MbTrack
import app.sleevesight.core.resolution.MbTracklist
import app.sleevesight.core.synthesis.SearchKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Home's buttons drawn in the controls' colour, as the pill's (#1404), not the theme's accent, in
 * light and dark: *Ask AI* and *Why is this …?* (chip-shaped), *Rewrite insight* and *Write insight*
 * of an item, *Look up again*, *See the n albums*, *Change*, an opened album's *All albums* and
 * *Select*, *Let Sleevesight decide*, and a page's *Close*. With `SLEEVESIGHT_PICTURES` set, each
 * screen is also saved there as a picture, as `PanelLineButtonEdgeTest` saves its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomePillsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private val same =
        SameName("greatest hits\u001fbilly idol", "Greatest Hits", listOf("g-2001", "g-2006"), "rec-eyes")

    private fun tracklist(
        date: String,
        vararg titles: String,
    ) = MbTracklist(
        "r",
        listOf(MbMedium(1, titles.mapIndexed { i, t -> MbTrack(i + 1, t, 240_000L, "rec-${t.lowercase()}") }, "CD")),
        date = date,
        country = "Europe",
        labels = listOf("EMI"),
        group = MbReleaseGroup("g", "Greatest Hits", "Compilation"),
    )

    private val albums =
        listOf(
            NamedAlbum("g-2006", tracklist("2006-03", "White Wedding", "Rebel Yell")),
            NamedAlbum("g-2001", tracklist("2001-04-24", "Mony Mony", "Eyes Without a Face")),
        )

    private fun choosing() =
        AlbumChoosing(
            states = mapOf<AlbumsKey, AlbumsState>(same.key to AlbumsState.Found(albums)),
            rows = { found ->
                val playing = HomeFixtures.track(HomeFixtures.sundown.copy(title = "Eyes Without a Face"))
                albumRows(found, playing, playing.nowPlaying)
            },
            song = "Eyes Without a Face",
        )

    // [content] in [dark] or light, saved as [name]; then each of [tags] is in the controls' colour.
    private fun check(
        name: String,
        dark: Boolean,
        tags: List<String>,
        content: @Composable () -> Unit,
    ) {
        var ink = Color.Unspecified
        var accent = Color.Unspecified
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        ink = MaterialTheme.colorScheme.onSurface
                        accent = MaterialTheme.colorScheme.primary
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                    }
                }
            }
            compose.waitForIdle()
            val mode = if (dark) "dark" else "light"
            out?.let { dir ->
                val screen = compose.onRoot().captureToImage().asAndroidBitmap()
                File(dir, "$name-$mode.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            for (tag in tags) {
                val button = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
                assertTrue("$name $mode: $tag is in the controls' colour", pixelsNear(button, ink) >= MIN_INK)
                assertTrue("$name $mode: $tag is not in the accent", pixelsNear(button, accent) < MIN_INK)
            }
        }
    }

    @Composable
    private fun Buttons() {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SearchButton(SearchTexts.of(SearchKind.DATES), enabled = true, Dp.Unspecified, Modifier) {}
            WhyButton("Why is this folk rock?") {}
            RewriteButton(InsightItem.SONG, enabled = true) {}
            RewriteButton(InsightItem.ARTIST, enabled = false) {}
            WriteButton(InsightItem.ALBUM) {}
            ClearedInsight {}
            AmbiguousNote(same) {}
            PickedLine(same, PickedBy.LISTENER) {}
            PageCloseButton({})
        }
    }

    private val buttons =
        listOf(
            SearchTexts.of(SearchKind.DATES).tag(AI_SEARCH_TAG),
            GENRE_WHY_TAG,
            itemWriteTag(InsightItem.SONG),
            itemWriteTag(InsightItem.ALBUM),
            LOOK_UP_AGAIN_TAG,
            ALBUMS_SEE_TAG,
            ALBUMS_CHANGE_TAG,
            PAGE_CLOSE_TAG,
        )

    @Composable
    private fun Albums(
        opened: String?,
        chosen: String? = null,
    ) = Box(Modifier.height(480.dp)) {
        SameNamedAlbums(same.copy(chosen = chosen), choosing(), opened, {}, {}) { _, _ -> }
    }

    @Test
    fun homesButtonsAreInTheControlsColourInLight() {
        check("home-pills", dark = false, buttons) { Buttons() }
        check("albums-opened", dark = false, listOf(ALBUMS_ALL_TAG, ALBUMS_SELECT_TAG)) { Albums("g-2001") }
        check("albums-list", dark = false, listOf(ALBUMS_DECIDE_TAG)) { Albums(null, chosen = "g-2006") }
    }

    @Test
    fun homesButtonsAreInTheControlsColourInDark() {
        check("home-pills", dark = true, buttons) { Buttons() }
        check("albums-opened", dark = true, listOf(ALBUMS_ALL_TAG, ALBUMS_SELECT_TAG)) { Albums("g-2001") }
        check("albums-list", dark = true, listOf(ALBUMS_DECIDE_TAG)) { Albums(null, chosen = "g-2006") }
    }
}
