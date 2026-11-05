package app.sleevesight.sources.language

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.ItemLanguageLines
import app.sleevesight.core.sources.LanguageSubject
import app.sleevesight.sources.KeptDrafts
import app.sleevesight.sources.lastfm.Lru
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The lines about an artist or an album the search in the track's language found (#939;
 * `design/architecture.md`, *Search in the track's language*), kept per artist ([ARTIST_PART]: by
 * its MusicBrainz id, else its name folded) and per album ([ALBUM_PART]: by its release group, else
 * its and its artist's names folded), as their cards are keyed, every language's in one entry, the
 * first lines found in each language kept ([keep]).
 *
 * They are what sources said about the artist, so they follow *Keep what sources said about each
 * artist* ([keeps]): on, they are kept in [kept], the artist-level table, for as long as the
 * artist's or the album's insight is (*Keep history for*, [ArtistPart.keptAsHistory]), counted from
 * the last card written from them ([renew]), and its switching off clears them; off, or with a [kept]
 * that keeps nothing ([ArtistResults.persists] false: the CLI without a cache, tests), in memory for
 * the most recent [REMEMBERED] artists and albums instead, for the process, so the artist's and the
 * album's insights still take them on each track played meanwhile. The memory is read only while
 * the table is not, and dropped when the table is read again, so a value is never in both; [forget]
 * drops it for *Clear history*, and a write by work started before that clear is dropped ([epoch],
 * #547). Never throws but for cancellation.
 */
class LanguageLinesSource(
    private val names: NameMatcher,
    private val kept: ArtistResults = ArtistResults.None,
    private val keeps: () -> Boolean = { true },
    private val epoch: ClearEpoch = ClearEpoch(),
) : ItemLanguageLines {
    // The lines by artist and by album, while [kept] is not used.
    private val artists = Lru<String, List<EvidenceDraft>>(REMEMBERED)
    private val albums = Lru<String, List<EvidenceDraft>>(REMEMBERED)

    // One read, or read and write, of the lines at a time, each wholly in the table or wholly in
    // memory, as the switch read once at its start says: so a language found on two tracks at once is
    // kept once, two languages found at once are both kept, and lines read from memory are never
    // written to the table when the switch turns on between the read and the write.
    private val turns = Mutex()

    override suspend fun kept(subject: LanguageSubject): List<EvidenceDraft> =
        turns.withLock { read(subject, inTable()).orEmpty() }

    override suspend fun keep(
        subject: LanguageSubject,
        language: String,
        lines: List<EvidenceDraft>,
    ) {
        if (lines.isEmpty()) return
        turns.withLock {
            val table = inTable()
            val now = read(subject, table).orEmpty()
            if (now.none { it.language == language }) {
                write(subject, (now + lines).sortedBy { it.language.orEmpty() }, table)
            }
        }
    }

    override suspend fun renew(subject: LanguageSubject) {
        turns.withLock {
            val table = inTable()
            read(subject, table)?.let { write(subject, it, table) }
        }
    }

    /** Drops the lines kept in memory, for *Clear history* (#547); the table's are cleared with it. */
    suspend fun forget() {
        turns.withLock { dropMemory() }
    }

    // [subject]'s lines kept, null for none: in the [table], else in memory.
    private suspend fun read(
        subject: LanguageSubject,
        table: Boolean,
    ): List<EvidenceDraft>? =
        if (table) {
            kept.get(partOf(subject), keyOf(subject))?.let { KeptDrafts.decode(it.value) }
        } else {
            memoryOf(subject).get(keyOf(subject))
        }

    // Keeps [lines] for [subject]: in the [table], else in memory, unless *Clear history* came since
    // the work began.
    private suspend fun write(
        subject: LanguageSubject,
        lines: List<EvidenceDraft>,
        table: Boolean,
    ) {
        if (table) {
            kept.keep(partOf(subject), keyOf(subject), KeptDrafts.encode(lines))
        } else {
            epoch.ifCurrent(currentCoroutineContext()) { memoryOf(subject).put(keyOf(subject), lines) }
        }
    }

    // Whether the table is used now, with [turns] held: it keeps, and *Keep what sources said about
    // each artist* is on. Then the memory is dropped, so a value is never kept in both.
    private suspend fun inTable(): Boolean {
        val table = kept.persists && keeps()
        if (table) dropMemory()
        return table
    }

    private suspend fun dropMemory() {
        artists.clear()
        albums.clear()
    }

    private fun memoryOf(subject: LanguageSubject): Lru<String, List<EvidenceDraft>> =
        when (subject) {
            is LanguageSubject.Artist -> artists
            is LanguageSubject.Album -> albums
        }

    // [subject]'s key, as its card's entity is keyed: an artist by its MusicBrainz id, else its name
    // folded; an album by its release group, else its and its artist's names folded, the artist's
    // length first, so no two pairs meet.
    private fun keyOf(subject: LanguageSubject): String =
        when (subject) {
            is LanguageSubject.Artist -> subject.mbid?.let { "mb:$it" } ?: "name:${names.key(subject.name)}"
            is LanguageSubject.Album ->
                subject.group?.let { "mb:$it" } ?: run {
                    val artist = names.albumKey(subject.artist)
                    "name:${artist.length}:$artist${names.albumKey(subject.title)}"
                }
        }

    companion object {
        /**
         * An artist's lines found in the track's language (#939), by its MusicBrainz id, else its name
         * folded, every language's: kept only while *Keep what sources said about each artist* is
         * on, and as long as the artist's insight written from them ([ArtistPart.keptAsHistory]:
         * *Keep history for*), so its card's key stays the same across days.
         */
        val ARTIST_PART: ArtistPart =
            ArtistPart(
                "artist.language",
                extraction = 1,
                encoding = 1,
                sources = setOf(SourceId.WIKIPEDIA, SourceId.WEB),
                keptAsHistory = true,
            )

        /**
         * An album's lines found in the track's language (#939), by its release group, else its and its
         * artist's names folded, every language's: kept as [ARTIST_PART].
         */
        val ALBUM_PART: ArtistPart = ARTIST_PART.copy(name = "album.language")

        /** The artists, and the albums, whose lines are kept in memory, while the table is not used. */
        const val REMEMBERED: Int = 256

        private fun partOf(subject: LanguageSubject): ArtistPart =
            when (subject) {
                is LanguageSubject.Artist -> ARTIST_PART
                is LanguageSubject.Album -> ALBUM_PART
            }
    }
}
