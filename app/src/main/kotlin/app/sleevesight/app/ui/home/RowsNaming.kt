package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.app.ui.theme.FocusHandoff
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

// Who the track's people are, named once (#1355): the rows under *Key insights* and the line under the
// title wait for MusicBrainz's answer, which tells a composer apart from the performers, rather than
// be named by the title first and renamed when it answers.

/**
 * Whether the roles of [normalized]'s artists are as known as they will be without waiting
 * (#1355): the title leads with its composer ([NormalizedTrack.composer], "Barber: Adagio for
 * Strings"), which names them at once; or [track], the pipeline's run of it, has MusicBrainz's
 * answer: its resolution, or its source landed (served from the cache on a track played before, off,
 * failed, or not reached). False before the pipeline reaches the track.
 */
internal fun rolesKnown(
    track: TrackState?,
    normalized: NormalizedTrack,
): Boolean =
    normalized.composer != null ||
        (
            track != null &&
                track.normalized == normalized &&
                (track.resolution != null || track.sources[SourceId.MUSICBRAINZ] !is SourceState.Pending)
        )

/**
 * The track whose people Home has named (#1355), [track] as first shown, whether it has ([named]),
 * and when its wait ends ([deadline], on the frame clock's monotonic nanoseconds, the clock
 * `withFrameNanos` reads; null until its wait starts): kept across recompositions and a recreated
 * screen, so once named a track's rows are never held back again on it (a *Retry* asking
 * MusicBrainz again renames them when it answers), and a screen recreated mid-wait (a rotation)
 * waits only what was left of it.
 */
@Stable
internal class NamingMemory(
    track: NowPlaying? = null,
    named: Boolean = false,
    deadline: Long? = null,
) {
    var track: NowPlaying? by mutableStateOf(track)
    var named: Boolean by mutableStateOf(named)
    var deadline: Long? = deadline

    companion object {
        /** Saved as plain values: whether named, the deadline, then the track as [savedTrack] keeps it. */
        val Saver: Saver<NamingMemory, Any> =
            listSaver(
                save = { memory -> listOf<Any?>(memory.named, memory.deadline) + savedTrack(memory.track) },
                restore = { saved ->
                    NamingMemory(restoredTrack(saved.drop(2)), saved[0] as Boolean, saved[1] as Long?)
                },
            )
    }
}

/** The naming's memory, remembered above whatever comes and goes with the track, as the rows' is. */
@Composable
internal fun rememberNamingMemory(): NamingMemory = rememberSaveable(saver = NamingMemory.Saver) { NamingMemory() }

/**
 * Whether Home names [shown]'s people (#1355): the rows under *Key insights* and the line under the
 * title, by [normalized], the track as read. At once when their roles are known ([rolesKnown]), when
 * MusicBrainz is switched off (whether or not the pipeline has a run of the track yet), or when the
 * track is one the last *Clear history* holds; else held back until MusicBrainz answers, or [wait]
 * has passed since the track was first shown, when they are named by the title as it reads them.
 * Once named, a track stays named; MusicBrainz answering later renames them once.
 */
@Composable
internal fun peopleNamed(
    memory: NamingMemory,
    shown: Showing,
    normalized: NormalizedTrack,
    wait: Duration = NAMING_WAIT,
): Boolean {
    val playing = shown.playing
    val same = memory.track?.let { TrackIdentity.playsOn(it, playing) } == true
    val known = shown.cleared || shown.musicBrainzOff || rolesKnown(shown.track, normalized)
    val named = known || (same && memory.named)
    SideEffect {
        if (!same) {
            memory.track = playing
            memory.deadline = null
        }
        if (!same || named) memory.named = named
    }
    // Started on each new track, as the memory takes it; a track named already waits for nothing. The
    // deadline is set once, at the wait's first frame, so a recreated screen waits only what is left.
    val held = memory.track
    LaunchedEffect(held) {
        if (held != null && !memory.named) {
            val now = withFrameNanos { it }
            val deadline = memory.deadline ?: (now + wait.inWholeNanoseconds).also { memory.deadline = it }
            delay((deadline - now).coerceAtLeast(0).nanoseconds)
            if (memory.track === held) memory.named = true
        }
    }
    return named
}

/**
 * Home's people while they are named (#1355): whether they are ([named], [peopleNamed]), and where
 * the focus goes from a row's panel a new track closed while its rows are held back ([elsewhere]).
 */
internal class PeopleShown(
    val named: Boolean,
    val elsewhere: RowsHandBack,
)

/** [shown]'s [PeopleShown], by [normalized], the track as read; their memory kept as the rows' is. */
@Composable
internal fun peopleShown(
    shown: Showing,
    normalized: NormalizedTrack,
): PeopleShown = PeopleShown(peopleNamed(rememberNamingMemory(), shown, normalized), rememberRowsHandBack())

/**
 * How long Home holds back the naming of a track's people for MusicBrainz's answer (#1355) before
 * naming them by the title: a fresh track's resolution takes a few seconds (a request a second, a
 * few of them for a recording and its work).
 */
internal val NAMING_WAIT: Duration = 10.seconds

/**
 * Where the focus goes when a new track closes a row's panel that held it and has no such row to go
 * back to (#1355: its rows held back while its people are named): [target], under [modifier] on a
 * control that stays, *Sources*' chip, or its *Close* while its panel is open; moved by [handoff].
 */
@Stable
internal class RowsHandBack(
    private val handoff: FocusHandoff,
    private val target: FocusRequester,
) {
    /** The control's modifier: the target of the move. */
    val modifier: Modifier get() = Modifier.handoffTarget(handoff, target)

    /** Moves the focus to the control once it is laid out. */
    fun move() = handoff.moveTo(target)
}

/** A [RowsHandBack] kept across recompositions, its moves run here. */
@Composable
internal fun rememberRowsHandBack(): RowsHandBack {
    val handoff = rememberFocusHandoff()
    return remember(handoff) { RowsHandBack(handoff, FocusRequester()) }
}
