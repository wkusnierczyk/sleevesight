package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.AlbumChoice
import app.sleevesight.app.pipeline.AlbumsKey
import app.sleevesight.app.pipeline.AlbumsState
import app.sleevesight.app.pipeline.NamedAlbum
import app.sleevesight.app.pipeline.TracklistKey
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.ui.logo.LocalAnimatorDurationScale
import app.sleevesight.core.resolution.MbMedium
import app.sleevesight.core.resolution.MbReleaseGroup
import app.sleevesight.core.resolution.MbTrack
import app.sleevesight.core.resolution.MbTracklist
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Several albums of one name on the back of Home's cover (#1080): the note's *See the {n} albums*
 * opens the list, looking the albums up; an album opens its tracklist, *All albums* goes back, and
 * *Select* keeps it; the line above a tracklist picked among them opens the albums with *Change*, the
 * chosen one marked "Your choice", and *Let Sleevesight decide* forgets the choice. Read by a screen
 * reader as what each says.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rGB-w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SameNamedAlbumsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val same =
        SameName("greatest hits\u001fbilly idol", "Greatest Hits", listOf("g-2001", "g-2006", "g-undated"), "rec-eyes")

    private fun tracklist(
        date: String?,
        label: String?,
        vararg titles: String,
    ) = MbTracklist(
        "r",
        listOf(MbMedium(1, titles.mapIndexed { i, t -> MbTrack(i + 1, t, 240_000L, "rec-${t.lowercase()}") }, "CD")),
        date = date,
        country = "Europe".takeIf { label != null },
        labels = listOfNotNull(label),
        group = MbReleaseGroup("g", "Greatest Hits", "Compilation"),
    )

    private val albums =
        listOf(
            NamedAlbum("g-2006", tracklist("2006-03", "EMI", "White Wedding", "Rebel Yell")),
            NamedAlbum("g-2001", tracklist("2001-04-24", "Capitol", "Mony Mony", "Eyes Without a Face")),
            NamedAlbum("g-undated", tracklist(null, null, "Rebel Yell", "Eyes Without a Face")),
        )

    private val looked = mutableListOf<AlbumsKey>()
    private val chosen = mutableListOf<Pair<String, AlbumChoice?>>()
    private var back by mutableStateOf<CoverBack>(CoverBack.Ambiguous(same))
    private var states by mutableStateOf<Map<AlbumsKey, AlbumsState>>(emptyMap())
    private var albumArtist by mutableStateOf(same.name)

    // The rows as Home orders them, the song found on the 2001 album and the undated one by title.
    private fun rows(found: List<NamedAlbum>): List<AlbumRow> {
        val playing =
            HomeFixtures.track(
                HomeFixtures.sundown.copy(title = "Eyes Without a Face", album = "Greatest Hits"),
            )
        return albumRows(found, playing, playing.nowPlaying)
    }

    private fun shown(
        initial: CoverBack,
        block: () -> Unit,
    ) {
        back = initial
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    CompositionLocalProvider(LocalAnimatorDurationScale provides 0f) {
                        val turn =
                            CoverTurn(
                                album = "greatest hits",
                                back = back,
                                key = TracklistKey("g-2001"),
                                look = {},
                                retry = {},
                                openSources = {},
                                colors = MaterialTheme.colorScheme,
                                albums =
                                    AlbumChoosing(
                                        states = states,
                                        look = { key -> looked += key },
                                        choose = { name, group -> chosen += name to group },
                                        rows = ::rows,
                                        song = "Eyes Without a Face",
                                    ),
                                albumArtist = albumArtist,
                            )
                        ArtAndProgress(null, PlaybackInfo.NONE, null, turn)
                    }
                }
            }
            compose.onNodeWithTag(COVER_TAG).performClick()
            block()
        }
    }

    @Test
    fun theNotesButtonOpensTheAlbumsLookingThemUpThenTheirList() =
        shown(CoverBack.Ambiguous(same)) {
            compose.onNodeWithText(text(R.string.home_tracklist_ambiguous)).assertExists()
            compose.onNodeWithText("See the 3 albums").performClick()
            compose.waitForIdle()
            assertEquals(listOf(same.key), looked)
            compose.onNodeWithText(text(R.string.home_musicbrainz_looking), substring = true).assertExists()
            states = mapOf(same.key to AlbumsState.Found(albums))
            compose.onNodeWithText("3 albums named Greatest Hits").assertExists()
            // Those with the song first, the earliest first, undated last; then the others.
            compose
                .onNodeWithContentDescription(
                    "2001, Compilation, 2 tracks, Capitol, Europe, CD, Eyes Without a Face: track 2",
                ).assertExists()
            compose
                .onNodeWithContentDescription(
                    "No date, Compilation, 2 tracks, CD, Eyes Without a Face: track 2",
                ).assertExists()
            compose.onNodeWithContentDescription("2006, Compilation, 2 tracks, EMI, Europe, CD").assertExists()
            // No choice yet: nothing to undo.
            compose.onNodeWithTag(ALBUMS_DECIDE_TAG).assertDoesNotExist()
            // The back stays: the list's taps are its own.
            compose.onNodeWithTag(COVER_BACK_TAG).assertExists()
            // Another song playing while the albums are open: nothing more is asked, the list stays.
            back = CoverBack.Ambiguous(same.copy(recording = "rec-rebel"))
            compose.waitForIdle()
            assertEquals(listOf(same.key), looked)
            compose.onNodeWithText("3 albums named Greatest Hits").assertExists()
            // Another artist's album of the same name: the albums close, and come back for no one else.
            albumArtist = "greatest hits\u001fqueen"
            back = CoverBack.Ambiguous(same.copy(name = albumArtist))
            compose.onNodeWithTag(ALBUMS_LIST_TAG).assertDoesNotExist()
            compose.onNodeWithTag(ALBUMS_SEE_TAG).assertExists()
            albumArtist = same.name
            compose.onNodeWithTag(ALBUMS_LIST_TAG).assertDoesNotExist()
            assertEquals(listOf(same.key), looked)
        }

    @Test
    fun anAlbumOpensItsTracklistAllAlbumsGoesBackAndSelectKeepsIt() =
        shown(CoverBack.Ambiguous(same)) {
            states = mapOf(same.key to AlbumsState.Found(albums))
            compose.onNodeWithTag(ALBUMS_SEE_TAG).performClick()
            compose.onNodeWithTag(albumRowTag("g-2006")).performClick()
            compose.onNodeWithTag(ALBUMS_OPENED_TAG).assertExists()
            compose.onNodeWithText("2006 · Compilation · EMI · Europe · CD").assertExists()
            compose.onNodeWithText("White Wedding", useUnmergedTree = true).assertExists()
            compose.onNodeWithText(text(R.string.home_albums_all)).performClick()
            compose.onNodeWithTag(ALBUMS_LIST_TAG).assertExists()
            compose.onNodeWithTag(albumRowTag("g-2001")).performClick()
            compose
                .onNodeWithContentDescription("2, Eyes Without a Face, 4 minutes, playing")
                .assertExists()
            compose.onNodeWithText(text(R.string.home_albums_select)).performClick()
            assertEquals(listOf(same.name to AlbumChoice("g-2001", same.groups)), chosen)
            // The albums close; the back shows what it shows.
            compose.onNodeWithTag(ALBUMS_OPENED_TAG).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.home_tracklist_ambiguous)).assertExists()
        }

    @Test
    fun aChoiceShowsItsLineWhoseChangeMarksItAndLetsSleevesightDecide() {
        val mine = same.copy(chosen = "g-2006")
        val found =
            CoverBack.Found(
                listOf(BackDisc(1, albums[0].tracklist.media[0].tracks)),
                null,
                albums = mine,
                pickedBy = PickedBy.LISTENER,
            )
        shown(found) {
            states = mapOf(same.key to AlbumsState.Found(albums))
            compose.onNodeWithText("Chosen by you from 3 albums of this name.").assertExists()
            compose.onNodeWithText(text(R.string.home_albums_change)).performClick()
            compose
                .onNodeWithContentDescription(
                    "2006, Compilation, 2 tracks, EMI, Europe, CD, Your choice",
                ).assertExists()
            val action =
                compose
                    .onNodeWithTag(albumRowTag("g-2006"))
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .label
            assertEquals(text(R.string.home_albums_open), action)
            compose.onNodeWithText(text(R.string.home_albums_decide)).performClick()
            assertEquals(listOf(same.name to null), chosen)
            compose.onNodeWithTag(ALBUMS_LIST_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun aPickByDateSaysSoAndTheAlbumsFailingToComeOfferRetry() {
        val found =
            CoverBack.Found(
                listOf(BackDisc(1, albums[1].tracklist.media[0].tracks)),
                TrackAt(0, 1),
                albums = same,
                pickedBy = PickedBy.DATE,
            )
        shown(found) {
            compose.onNodeWithText("Picked by date from 3 albums of this name.").assertExists()
            compose.onNodeWithTag(ALBUMS_CHANGE_TAG).performClick()
            states = mapOf(same.key to AlbumsState.Failed)
            compose.onNodeWithText(text(R.string.home_musicbrainz_unreachable), substring = true).assertExists()
        }
    }
}
