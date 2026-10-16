package app.sleevesight.app.session

import android.graphics.Bitmap
import android.media.session.PlaybackState
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.app.session.Sessions.bitmap
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.queueItem
import app.sleevesight.app.session.Sessions.state
import app.sleevesight.core.model.NowPlaying
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After

/** What [SessionRepositoryTest] and [SessionSettlingTest] share: a repository on fake sessions, in virtual time. */
internal abstract class SessionRepositoryFixture {
    protected val scheduler = TestCoroutineScheduler()

    /** Runs callbacks at once, and the settle window in virtual time ([settle]). */
    protected val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
    protected val acknowledged = MutableStateFlow(true)
    protected val followed = MutableStateFlow(setOf(SPOTIFY))
    protected val system = FakeActiveSessions()

    /** What an `http` art fetch waits for; each fetch takes the next one. */
    protected val fetches = mutableListOf<Pair<String, CompletableDeferred<ByteArray?>>>()
    private val http = HttpArt { uri -> CompletableDeferred<ByteArray?>().also { fetches += uri to it }.await() }
    private val resolver = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver

    protected val repository =
        SessionRepository(
            system,
            acknowledged,
            ArtLoader(resolver, http, Dispatchers.Unconfined),
            scope,
            followed,
            clock = { scheduler.currentTime },
        )

    protected val sundown = NowPlaying(title = "Sundown", artists = "Gordon Lightfoot", player = SPOTIFY)

    @After
    fun tearDown() = scope.cancel()

    /** Lets the settle window pass. */
    protected fun settle() = scheduler.advanceUntilIdle()

    protected fun FakeSession.settled(metadata: android.media.MediaMetadata) {
        send(metadata)
        settle()
    }

    protected fun FakeSession.settled(state: PlaybackState) {
        send(state)
        settle()
    }

    /** Every value [SessionRepository.nowPlaying] takes from now on, the current one first. */
    protected fun recordTracks(): List<NowPlaying?> {
        val tracks = mutableListOf<NowPlaying?>()
        scope.launch { repository.nowPlaying.collect { tracks += it } }
        return tracks
    }

    protected fun changes(tracks: List<NowPlaying?>) =
        tracks.filterNotNull().zipWithNext().count { (a, b) -> !TrackIdentity.same(a, b) }

    protected val carefree = metadata(title = "Carefree Highway")

    protected fun fetchesFor(uri: String) = fetches.filter { it.first == uri }.map { it.second }

    /** A YouTube Music session playing [metadata] as [item], with its "Up next" published or not. */
    protected fun youtubeMusic(
        queue: Boolean = false,
        metadata: android.media.MediaMetadata? = metadata(),
        item: Long = 4,
    ): FakeSession {
        followed.value = setOf(YOUTUBE_MUSIC)
        val session =
            FakeSession(
                YOUTUBE_MUSIC,
                metadata = metadata,
                playbackState = state(queueItem = item),
                queue = if (queue) listOf(queueItem(4, "Sundown"), queueItem(5, "Carefree Highway")) else null,
            )
        system.active = listOf(session)
        repository.start()
        return session
    }

    protected fun png(color: Int): ByteArray =
        java.io
            .ByteArrayOutputStream()
            .also {
                bitmap(
                    color,
                ).compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
}
