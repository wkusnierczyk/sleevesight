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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
 * A genre's broader genre in its panel (#1127): "Contemporary R&B is a style of R&B." under the
 * definition, R&B a link that opens its definition in place, with *Why is this R&B?* where the notes
 * offer it, and "Back to Contemporary R&B" back; no line for a genre with no broader genre. With
 * `SLEEVESIGHT_PICTURES` set, the panel is also saved there as pictures, light and dark, at the
 * default font size and twice it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenreBroaderScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val out = System.getenv("SLEEVESIGHT_PICTURES")?.let { File(it).apply { mkdirs() } }

    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    private val contemporary =
        GenreDefinition(
            "Contemporary R&B is a popular music genre that combines rhythm and blues with elements of pop, " +
                "soul, funk, hip hop and electronic music.",
            "Contemporary R&B",
            "https://en.wikipedia.org/wiki/Contemporary_R%26B",
        )
    private val rnb =
        GenreDefinition(
            "Rhythm and blues, frequently abbreviated as R&B, is a genre of popular music that originated " +
                "within African American communities in the 1940s.",
            "Rhythm and blues",
            "https://en.wikipedia.org/wiki/Rhythm_and_blues",
        )

    // *Thriller*: Contemporary R&B on the track's row, from [source]; Pop beside it, from Last.fm.
    private fun playing(source: LabelSource): TrackState {
        fun label(
            tag: String,
            from: LabelSource,
        ) = TrackLabel(
            vocabulary.labelsFor(listOf(tag), level = GenreLevel.TRACK).single(),
            listOf(LabelCredit(from, "https://example.org/$tag")),
        )
        return track().copy(
            genres =
                TrackGenres(
                    listOf(
                        GenreRow(
                            GenreLevel.TRACK,
                            null,
                            listOf(label("contemporary r&b", source), label("pop", LabelSource.LASTFM)),
                        ),
                    ),
                ),
        )
    }

    // Notes whose definitions are Wikipedia's, and whose reason for a genre opened as a broader genre
    // is [broader] (as `GenreReasons.path` gives it: offered where another source gives the genre,
    // none where only MusicBrainz does); a genre opened as itself, R&B included, is offered, so a
    // panel that asked for the wrong one would show a button.
    private inner class Notes(
        private val broader: GenreReason,
    ) : GenreNotes {
        val explainedBroader = mutableListOf<String>()

        override val vocabulary: GenreVocabulary = this@GenreBroaderScreenTest.vocabulary

        override fun definition(genre: String): Flow<DefinitionState?> =
            flowOf(
                when (genre) {
                    "contemporaryrb" -> DefinitionState.Found(contemporary)
                    "rb" -> DefinitionState.Found(rnb)
                    else -> DefinitionState.NotFound
                },
            )

        override fun reason(
            track: TrackState,
            genre: String,
        ): Flow<GenreReason> = flowOf(GenreReason.Offered)

        override fun broaderReason(
            track: TrackState,
            genre: String,
        ): Flow<GenreReason> = flowOf(broader)

        override fun explain(
            track: TrackState,
            genre: String,
        ) = Unit

        override fun explainBroader(
            track: TrackState,
            genre: String,
        ) {
            explainedBroader += genre
        }
    }

    private fun show(
        source: LabelSource,
        notes: Notes,
        dark: Boolean = false,
        page: Boolean = false,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    inputModes = LocalInputModeManager.current
                    SleevesightTheme(darkTheme = dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                                // On Home, unfolded under the chips; or in a panel of its own, on the genres' page.
                                if (page) {
                                    GenrePanel(playing(source), "contemporaryrb", PanelNotes(notes))
                                } else {
                                    GenresContent(playing(source), Settings(), notes = PanelNotes(notes))
                                }
                            }
                        }
                    }
                }
            }
            if (!page) compose.onNodeWithTag(GENRE_PREFIX + "track:contemporaryrb").unfold()
            compose.waitForIdle()
            block()
        }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun open() {
        compose.onNodeWithTag(GENRE_BROADER_TAG).performScrollTo().performFirstLinkClick {
            (it.item as? LinkAnnotation.Clickable)?.tag == GENRE_BROADER_LINK_TAG
        }
        compose.waitForIdle()
    }

    @Test
    fun theLineNamesTheBroaderGenreUnderTheDefinitionAndItsLinkOpensItInPlace() =
        show(LabelSource.LASTFM, Notes(GenreReason.Offered)) {
            compose.onNodeWithTag(GENRE_BROADER_TAG).assertTextEquals("Contemporary R&B is a style of R&B.")
            open()
            // R&B's definition in Contemporary R&B's place, with its *Why is this R&B?*.
            compose.onNodeWithText(rnb.text).assertExists()
            assertEquals(0, compose.onAllNodesWithText(contemporary.text).fetchSemanticsNodes().size)
            compose.onNodeWithText("Why is this R&B?", substring = true, useUnmergedTree = true).assertExists()
            assertEquals(0, count(GENRE_BROADER_TAG))
            // And back.
            compose.onNodeWithText("Back to Contemporary R&B").performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == GENRE_BROADER_BACK_TAG
            }
            compose.waitForIdle()
            compose.onNodeWithText(contemporary.text).assertExists()
            compose.onNodeWithTag(GENRE_BROADER_TAG).assertExists()
        }

    @Test
    fun aGenreOnlyMusicBrainzGivesShowsItsBroaderGenreWithNoAiRequest() =
        show(LabelSource.MUSICBRAINZ, Notes(GenreReason.None)) {
            open()
            compose.onNodeWithText(rnb.text).assertExists()
            assertEquals(0, count(GENRE_WHY_TAG))
            assertEquals(
                0,
                compose
                    .onAllNodesWithText("Why is this", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .size,
            )
        }

    private var inputModes: InputModeManager? = null

    // Enter on the link in the line tagged [tag], focused, as a keyboard does: a link takes the focus
    // only out of touch mode.
    @OptIn(ExperimentalComposeUiApi::class)
    private fun press(tag: String) {
        compose.runOnIdle { checkNotNull(inputModes).requestInputMode(InputMode.Keyboard) }
        linkIn(tag).requestFocus()
        linkIn(tag).performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
    }

    // The link inside the line tagged [tag]: the node that takes the focus.
    private fun linkIn(tag: String) =
        compose.onNode(hasClickAction() and hasParent(hasTestTag(tag)), useUnmergedTree = true)

    @Test
    fun theFocusGoesToTheLinkBackAsTheBroaderGenreOpensAndToItsLinkAgainOnTheWayBackInBothLayouts() {
        for (page in listOf(false, true)) {
            show(LabelSource.LASTFM, Notes(GenreReason.Offered), page = page) {
                compose.onNodeWithTag(GENRE_BROADER_TAG).performScrollTo()
                press(GENRE_BROADER_TAG)
                linkIn(GENRE_BROADER_BACK_LINE_TAG).assertIsFocused()
                press(GENRE_BROADER_BACK_LINE_TAG)
                linkIn(GENRE_BROADER_TAG).assertIsFocused()
            }
        }
    }

    @Test
    fun aTapOnTheBroaderGenresWhyAsksItAsABroaderGenre() {
        val notes = Notes(GenreReason.Offered)
        show(LabelSource.LASTFM, notes) {
            open()
            compose.onNodeWithTag(GENRE_WHY_TAG).performScrollTo().performClick()
            assertEquals(listOf("rb"), notes.explainedBroader)
        }
    }

    @Test
    fun aGenreWithNoBroaderGenreHasNoLine() =
        show(LabelSource.LASTFM, Notes(GenreReason.Offered)) {
            compose.onNodeWithTag(GENRE_PREFIX + "track:pop").unfold()
            compose.waitForIdle()
            assertEquals(0, count(GENRE_BROADER_TAG))
        }

    @Test
    fun pictures() {
        val dir = out ?: return
        for (scale in listOf(1f, 2f)) {
            RuntimeEnvironment.setFontScale(scale)
            val size = if (scale == 1f) "font100" else "font200"
            for (dark in listOf(false, true)) {
                val theme = if (dark) "dark" else "light"
                for (
                (name, source, reason) in
                listOf(
                    Triple("lastfm", LabelSource.LASTFM, GenreReason.Offered),
                    Triple("musicbrainz-only", LabelSource.MUSICBRAINZ, GenreReason.None),
                )
                ) {
                    show(source, Notes(reason), dark) {
                        compose.onNodeWithTag(GENRE_BROADER_TAG).performScrollTo()
                        save(dir, "broader-line-$name-$theme-$size")
                        open()
                        compose.onNodeWithText(rnb.text).performScrollTo()
                        save(dir, "broader-open-$name-$theme-$size")
                    }
                }
            }
        }
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun save(
        dir: File,
        name: String,
    ) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
