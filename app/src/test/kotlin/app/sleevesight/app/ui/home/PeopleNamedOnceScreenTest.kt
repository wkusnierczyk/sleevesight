package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.ArtistRoles
import app.sleevesight.core.sources.Resolution
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A track's people named once (#1355): while MusicBrainz has not answered, the rows under *Key
 * insights* are held back and the line under the title is empty, its height kept; they come in when
 * it answers, named by its roles, or after [NAMING_WAIT] named by the title, renamed once if it
 * answers later, a screen recreated meanwhile waiting only what was left. A title leading with its
 * composer, MusicBrainz switched off (with or without a run of the track yet) or unreachable, and a
 * track played before name them at once. A new track held back hands the focus from a row's open
 * panel to *Sources*' chip. On virtual time: the clock advances only as a test says.
 */
@RunWith(RobolectricTestRunner::class)
class PeopleNamedOnceScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val settings = Settings(writeAutomatically = WriteAutomatically.NEVER)

    private val musicBrainzOff =
        settings.copy(enabledSources = settings.enabledSources - SourceSwitch.MUSICBRAINZ)

    // [track] playing, as the pipeline has it; with none, [playing] before the pipeline has a run of it.
    private fun state(
        track: TrackState?,
        settings: Settings = this.settings,
        playing: NowPlaying = track?.nowPlaying ?: NINTH,
    ) = NowPlayingState(
        PipelineView(track, aiReady = true),
        playing,
        PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
        settings = settings,
    )

    // The screen's saved state, as an activity keeps it: [recreate] saves it, drops the content and
    // composes it afresh from what was saved, as Android does on a rotation (what
    // `StateRestorationTester` does, which needs a content rule these tests do not use).
    private var emitted by mutableStateOf(true)
    private var registry by mutableStateOf(SaveableStateRegistry(null) { true })

    private fun recreate() {
        val saved = registry.performSave()
        emitted = false
        frames()
        registry = SaveableStateRegistry(saved) { true }
        emitted = true
        frames()
    }

    // Home showing [initial], on a clock that moves only as the test says; [block] may change it.
    private fun show(
        initial: NowPlayingState,
        block: (MutableState<NowPlayingState>) -> Unit,
    ) {
        val current = mutableStateOf(initial)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    if (emitted) {
                        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                NowPlayingContent(current.value, NowPlayingActions(normalizer::normalize))
                            }
                        }
                    }
                }
            }
            frames()
            block(current)
        }
    }

    private fun show(
        initial: TrackState,
        block: (MutableState<NowPlayingState>) -> Unit,
    ) = show(state(initial), block)

    // A few frames: recompositions and effects run, the clock all but still; what the test changed
    // told first, so the next frame recomposes it.
    private fun frames() {
        Snapshot.sendApplyNotifications()
        repeat(FRAMES) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
    }

    private fun after(millis: Long) {
        compose.mainClock.advanceTimeBy(millis)
        frames()
    }

    private fun said(item: InsightItem): String =
        compose
            .onNodeWithTag(itemRowTag(item))
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
            .single()
            .removeSuffix(", uses AI")

    private fun assertHeld() {
        for (item in listOf(InsightItem.SONG, InsightItem.COMPOSER, InsightItem.ARTIST, InsightItem.ALBUM)) {
            compose.onNodeWithTag(itemRowTag(item)).assertDoesNotExist()
        }
        compose.onNodeWithTag(ARTISTS_HELD_TAG).assertExists()
        compose.onAllNodesWithText(BERLIN, substring = true).assertCountEquals(0)
    }

    @Test
    fun whileMusicBrainzHasNotAnsweredTheRowsAndTheLineUnderTheTitleAreHeldBack() =
        show(beethoven(answered = false)) {
            assertHeld()
            // The line's height kept: as tall as a line of the artists.
            val held =
                compose
                    .onNodeWithTag(ARTISTS_HELD_TAG)
                    .fetchSemanticsNode()
                    .size.height
            assertEquals(true, held > 0)
        }

    @Test
    fun whenMusicBrainzAnswersTheRowsComeInNamedByItsRoles() =
        show(beethoven(answered = false)) { current ->
            assertHeld()
            after(3_000)
            assertHeld()
            current.value = state(beethoven(answered = true))
            frames()
            compose.onNodeWithTag(ARTISTS_HELD_TAG).assertDoesNotExist()
            assertEquals("Music, $TITLE", said(InsightItem.SONG))
            assertEquals("Composer, $BEETHOVEN", said(InsightItem.COMPOSER))
            assertEquals(true, said(InsightItem.ARTIST).startsWith("Performer, "))
        }

    @Test
    fun withNoAnswerInTenSecondsTheRowsAreNamedByTheTitle() =
        show(beethoven(answered = false)) {
            after(NAMING_WAIT.inWholeMilliseconds - 1_000)
            assertHeld()
            after(1_000)
            compose.onNodeWithTag(ARTISTS_HELD_TAG).assertDoesNotExist()
            assertEquals("Song, $TITLE", said(InsightItem.SONG))
            assertEquals("Artist, $BEETHOVEN", said(InsightItem.ARTIST))
            compose.onNodeWithTag(itemRowTag(InsightItem.COMPOSER)).assertDoesNotExist()
        }

    @Test
    fun anAnswerAfterTheWaitRenamesTheRowsOnce() =
        show(beethoven(answered = false)) { current ->
            after(NAMING_WAIT.inWholeMilliseconds + 1_000)
            assertEquals("Song, $TITLE", said(InsightItem.SONG))
            current.value = state(beethoven(answered = true))
            frames()
            assertEquals("Music, $TITLE", said(InsightItem.SONG))
            assertEquals("Composer, $BEETHOVEN", said(InsightItem.COMPOSER))
        }

    @Test
    fun onceNamedATrackAskingMusicBrainzAgainKeepsItsRows() =
        show(beethoven(answered = true)) { current ->
            assertEquals("Composer, $BEETHOVEN", said(InsightItem.COMPOSER))
            // A *Retry*: MusicBrainz asked again, its answer not in yet.
            current.value = state(beethoven(answered = false))
            frames()
            compose.onNodeWithTag(ARTISTS_HELD_TAG).assertDoesNotExist()
            assertEquals("Song, $TITLE", said(InsightItem.SONG))
        }

    @Test
    fun aTitleLeadingWithItsComposerIsNamedAtOnce() =
        show(barber()) {
            compose.onNodeWithTag(ARTISTS_HELD_TAG).assertDoesNotExist()
            assertEquals("Music, Barber: Adagio for Strings", said(InsightItem.SONG))
            assertEquals("Composer, Samuel Barber", said(InsightItem.COMPOSER))
        }

    @Test
    fun withMusicBrainzOffTheRowsAreNamedAtOnceByTheTitle() =
        show(
            beethoven(answered = false).copy(
                sources =
                    mapOf(
                        SourceId.MUSICBRAINZ to done(Outcome.SourceOff),
                        SourceId.WIKIPEDIA to SourceState.Pending,
                    ),
            ),
        ) {
            compose.onNodeWithTag(ARTISTS_HELD_TAG).assertDoesNotExist()
            assertEquals("Song, $TITLE", said(InsightItem.SONG))
        }

    @Test
    fun aScreenRecreatedDuringTheWaitNamesTheRowsAtTheOriginalTenSeconds() =
        show(beethoven(answered = false)) {
            after(NAMING_WAIT.inWholeMilliseconds - 2_000)
            recreate()
            assertHeld()
            after(1_000)
            assertHeld()
            after(1_000)
            assertEquals("Song, $TITLE", said(InsightItem.SONG))
        }

    @Test
    fun aNewTrackHeldBackHandsTheFocusFromAnOpenPanelToSourcesChip() =
        show(beethoven(answered = true)) { current ->
            // The panel's opening and closing animate: the clock runs on by itself here.
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag(itemRowTag(InsightItem.SONG)).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(itemPanelTag(InsightItem.SONG) + ":close").assertIsFocused()
            val next = NINTH.copy(title = "$TITLE II", mediaId = "b2")
            current.value = state(beethoven(answered = false, playing = next))
            compose.waitForIdle()
            assertHeld()
            compose.onNodeWithTag(SOURCES_CHIP_TAG).assertIsFocused()
        }

    @Test
    fun withMusicBrainzOffATrackWithNoRunYetIsNamedAtOnce() =
        show(state(null, musicBrainzOff)) {
            compose.onNodeWithTag(ARTISTS_HELD_TAG).assertDoesNotExist()
            assertEquals("Song, $TITLE", said(InsightItem.SONG))
        }

    @Test
    fun switchingMusicBrainzOffDuringTheWaitNamesTheRowsAtOnce() =
        show(beethoven(answered = false)) { current ->
            after(2_000)
            assertHeld()
            // The run rebuilt for the new setting not published yet: MusicBrainz still pending in it.
            current.value = state(beethoven(answered = false), musicBrainzOff)
            frames()
            compose.onNodeWithTag(ARTISTS_HELD_TAG).assertDoesNotExist()
            assertEquals("Song, $TITLE", said(InsightItem.SONG))
        }

    @Test
    fun withMusicBrainzUnreachableTheRowsAreNamedAtOnceByTheTitle() =
        show(beethoven(answered = false).copy(resolution = Resolution.unreachable("offline"))) {
            compose.onNodeWithTag(ARTISTS_HELD_TAG).assertDoesNotExist()
            assertEquals("Song, $TITLE", said(InsightItem.SONG))
        }

    @Test
    fun aTrackPlayedBeforeIsNamedAtOnceByMusicBrainzsKeptAnswer() =
        show(
            beethoven(answered = false).copy(
                sources =
                    mapOf(
                        SourceId.MUSICBRAINZ to
                            done(cached = true).let { it.copy(result = it.result.copy(roles = ROLES)) },
                        SourceId.WIKIPEDIA to SourceState.Pending,
                    ),
            ),
        ) {
            compose.onNodeWithTag(ARTISTS_HELD_TAG).assertDoesNotExist()
            assertEquals("Composer, $BEETHOVEN", said(InsightItem.COMPOSER))
        }

    @Test
    fun aNewTrackIsHeldAgainAndWaitsItsOwnTenSeconds() =
        show(beethoven(answered = false)) { current ->
            after(NAMING_WAIT.inWholeMilliseconds + 1_000)
            assertEquals("Song, $TITLE", said(InsightItem.SONG))
            val next = NINTH.copy(title = "$TITLE II", mediaId = "b2")
            current.value = state(beethoven(answered = false, playing = next))
            frames()
            assertHeld()
            after(NAMING_WAIT.inWholeMilliseconds - 1_000)
            assertHeld()
            after(1_000)
            assertEquals("Song, $TITLE II", said(InsightItem.SONG))
        }

    private companion object {
        const val FRAMES = 3
        const val BEETHOVEN = "Ludwig van Beethoven"
        const val BERLIN = "Berliner Philharmoniker"
        const val KARAJAN = "Herbert von Karajan"
        const val TITLE = "Symphony No. 9 in D Minor, Op. 125 \"Choral\": I. Allegro ma non troppo"

        val PENDING = mapOf(SourceId.MUSICBRAINZ to SourceState.Pending, SourceId.WIKIPEDIA to SourceState.Pending)

        val ROLES = ArtistRoles(listOf(BEETHOVEN), listOf(BERLIN, KARAJAN))

        val NINTH: NowPlaying =
            sundown.copy(
                title = TITLE,
                artists = "$BEETHOVEN, $BERLIN, $KARAJAN",
                album = "Beethoven: Symphony No. 9",
                mediaId = "spotify:track:ninth",
            )

        // Karajan's Ninth, its sources out; with [answered], MusicBrainz's answer in, telling the composer.
        fun beethoven(
            answered: Boolean,
            playing: NowPlaying = NINTH,
        ): TrackState {
            val pending =
                HomeFixtures.track(
                    playing,
                    sources = PENDING,
                    evidence = null,
                )
            return if (answered) {
                pending.copy(
                    sources = mapOf(SourceId.MUSICBRAINZ to done(), SourceId.WIKIPEDIA to SourceState.Pending),
                    resolution = Resolution(recordingMbid = "r", roles = ROLES),
                )
            } else {
                pending
            }
        }

        // Barber's Adagio, the title leading with him, MusicBrainz not answered.
        fun barber(): TrackState =
            HomeFixtures.track(
                sundown.copy(
                    title = "Barber: Adagio for Strings",
                    artists = "Samuel Barber, Sir Simon Rattle, Berlin Philharmonic",
                    album = "Adagio",
                    mediaId = "spotify:track:adagio",
                ),
                sources = PENDING,
                evidence = null,
            )
    }
}
