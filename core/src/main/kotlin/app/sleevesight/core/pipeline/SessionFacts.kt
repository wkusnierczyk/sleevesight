package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.synthesis.GroundedText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * An artist-level evidence line ([Scope.ARTIST]) as the same fact on another track: its source and
 * text. Its id is a position among one track's lines (`W3` here may be `W2` on the next track), but
 * the artist-level cache (#238) gives every track by the artist the same lines, word for word.
 */
data class ArtistFact(
    val source: SourceId,
    val text: String,
) {
    companion object {
        /** [line]'s fact. */
        fun of(line: EvidenceLine): ArtistFact = ArtistFact(line.source, line.text)

        /** The artist-level lines of [lines] that [grounded] cites, as facts. */
        fun citedIn(
            grounded: GroundedText,
            lines: List<EvidenceLine>,
        ): Set<ArtistFact> {
            val cites =
                grounded.paragraphs
                    .flatten()
                    .flatMap { it.cites }
                    .toSet()
            return lines.filter { it.scope == Scope.ARTIST && it.id in cites }.map(::of).toSet()
        }
    }
}

/**
 * The artist facts the insights of the listening session now playing have cited (#239,
 * `design/design.md`, *Synthesis*). A track's insight is written without the artist-level lines
 * another track's insight in the session cited, so an album played through does not repeat its
 * artist's facts on every track, and its later prompts are shorter. The listening session is the
 * history's (*Start a new session after a break of*, `Settings.sessionGap`).
 */
interface SessionFacts {
    /** The facts cited in the session now playing by insights of tracks other than [track] (a lookup key). */
    suspend fun citedByOthers(track: String): Set<ArtistFact>

    /** Records that the insight of [track], shown in the session now playing, cites [facts]. */
    suspend fun cite(
        track: String,
        facts: Set<ArtistFact>,
    )

    /** No memory: every insight is written from all its evidence, as before #239. */
    data object None : SessionFacts {
        override suspend fun citedByOthers(track: String): Set<ArtistFact> = emptySet()

        override suspend fun cite(
            track: String,
            facts: Set<ArtistFact>,
        ) = Unit
    }
}

/**
 * [SessionFacts] kept in memory for the process, for the listening session [session] names (the
 * history's latest session number in the app; null while there is none): when it names another
 * session, what was kept is dropped, so a new session starts with every artist fact. A session
 * read that fails counts as the session last read, so a broken history neither drops nor mixes
 * what is kept. Lost with the process: the first insight after a restart may cite again what one
 * before it cited, which costs one artist sentence, not a call.
 *
 * Facts are kept with the tracks whose insights cited them, so a track written again (a tap on
 * Rewrite) is not denied the facts its own first insight cited.
 */
class ListeningSessionFacts(
    private val session: suspend () -> Long?,
) : SessionFacts {
    private val mutex = Mutex()
    private var current: Long? = null
    private var read = false
    private val citers = mutableMapOf<ArtistFact, MutableSet<String>>()

    override suspend fun citedByOthers(track: String): Set<ArtistFact> {
        val now = currentSession()
        return mutex.withLock {
            follow(now)
            citers.filterValues { tracks -> tracks.any { it != track } }.keys.toSet()
        }
    }

    override suspend fun cite(
        track: String,
        facts: Set<ArtistFact>,
    ) {
        if (facts.isEmpty()) return
        val now = currentSession()
        mutex.withLock {
            follow(now)
            facts.forEach { citers.getOrPut(it) { mutableSetOf() } += track }
        }
    }

    // The session now, or, when the read fails, the one last read.
    private suspend fun currentSession(): Result<Long?> =
        try {
            Result.success(session())
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Result.failure(e)
        }

    private fun follow(now: Result<Long?>) {
        val number = now.getOrElse { return }
        if (!read || number != current) {
            read = true
            current = number
            citers.clear()
        }
    }
}
