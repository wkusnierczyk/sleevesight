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
 * with the artist-level cache (#238) on, every track by the artist gets the same lines, word for
 * word. With it off (#420, the default), each track's artist lines are fetched again, and a source
 * normally gives the same text again, but may not to the character: an article's typography or
 * spacing edited in between, say. So two facts are the same when their sources are and their texts
 * match once folded ([matchKey]): case, spacing, quotation marks, apostrophes, dashes and a closing
 * full stop do not tell them apart. A fact whose words changed is another fact, and may be told
 * again.
 */
class ArtistFact(
    val source: SourceId,
    val text: String,
) {
    private val key: String = matchKey(text)

    override fun equals(other: Any?): Boolean = other is ArtistFact && source == other.source && key == other.key

    override fun hashCode(): Int = (source to key).hashCode()

    override fun toString(): String = "ArtistFact(source=$source, text=$text)"

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

        // Java's `\s` is ASCII white space only; `\p{Zs}` adds every other space (thin, hair, no-break).
        private val SPACES = Regex("[\\s\\p{Zs}]+")

        /**
         * [text] folded for matching: lowercased, typographic quotation marks, apostrophes and dashes
         * as their plain forms, every run of white space (any Unicode space too) as one space, and no
         * space at either end, nor a full stop at the end.
         */
        internal fun matchKey(text: String): String =
            text
                .lowercase()
                .map { FOLDED[it] ?: it }
                .joinToString("")
                .replace(SPACES, " ")
                .trim()
                .removeSuffix(".")
                .trimEnd()

        private val FOLDED: Map<Char, Char> =
            mapOf(
                '\u2018' to '\'',
                '\u2019' to '\'',
                '\u201B' to '\'',
                '\u2032' to '\'',
                '\u201C' to '"',
                '\u201D' to '"',
                '\u201E' to '"',
                '\u2033' to '"',
                '\u2010' to '-',
                '\u2011' to '-',
                '\u2012' to '-',
                '\u2013' to '-',
                '\u2014' to '-',
                '\u2212' to '-',
            )
    }
}

/**
 * The artist facts the insights of the listening session now playing have cited (#239,
 * `design/design.md`, *Synthesis*). A track's insight is written without the artist-level lines
 * another track's insight in the session cited, so an album played through does not repeat its
 * artist's facts on every track, and its later prompts are shorter. The listening session is the
 * history's (*New session idle trigger*, `Settings.sessionGap`).
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
 * Rewrite) is not denied the facts its own first insight cited. Each call reads the session under
 * the same lock as the facts, so concurrent calls apply their reads in the order they hold it: a
 * slow read of the session before cannot clear what a later read's session kept.
 */
class ListeningSessionFacts(
    private val session: suspend () -> Long?,
) : SessionFacts {
    private val mutex = Mutex()
    private var current: Long? = null
    private var read = false
    private val citers = mutableMapOf<ArtistFact, MutableSet<String>>()

    override suspend fun citedByOthers(track: String): Set<ArtistFact> =
        mutex.withLock {
            follow(currentSession())
            citers.filterValues { tracks -> tracks.any { it != track } }.keys.toSet()
        }

    override suspend fun cite(
        track: String,
        facts: Set<ArtistFact>,
    ) {
        if (facts.isEmpty()) return
        mutex.withLock {
            follow(currentSession())
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
