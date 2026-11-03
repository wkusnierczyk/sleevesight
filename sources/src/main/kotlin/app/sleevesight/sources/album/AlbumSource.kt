package app.sleevesight.sources.album

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.EvidenceText
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.core.sources.AlbumFound
import app.sleevesight.core.sources.AlbumLookup
import app.sleevesight.core.sources.AlbumQuery
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.sources.musicbrainz.MbAlbum
import app.sleevesight.sources.musicbrainz.ReleaseGroups
import app.sleevesight.sources.wikipedia.WikipediaClient
import app.sleevesight.sources.wikipedia.WikipediaSource
import app.sleevesight.sources.wikipedia.articleUrl
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The evidence for an album's insight under *Key insights* (#522; `design/architecture.md`, *Source
 * adapters*): what is said about the album itself, never the track playing, so each of its tracks
 * finds the same lines.
 *
 * - **MusicBrainz** (with [AlbumQuery.musicBrainz], for an album whose release group is known): the
 *   release group ([groups]), one templated line, its title, type, credit and first release (CC0),
 *   and the Wikidata item it links.
 * - **Wikidata and Wikipedia** (with [AlbumQuery.wikipedia]): the album's item, the one MusicBrainz
 *   links, else the one whose MusicBrainz release group (P436) is the album's, else, for an album
 *   MusicBrainz does not name (or could not be asked about), the article the album's name finds
 *   (`Title (Artist album)`, `Title (album)`, `Title`) whose item is an album ([ALBUM_CLASSES]) by
 *   the artist (a performer, P175, of the artist's name); then the item's record label and producers,
 *   templated (CC0), and its English article's lead, at most three sentences naming the album,
 *   verbatim and linked (D1, CC BY-SA 4.0), as Wikipedia's lines on a track are
 *   ([WikipediaSource.sentences]).
 *
 * Last.fm's album (`album.getInfo`, its wiki summary) and a web search for the album are not asked:
 * the album's insight is written from these alone.
 *
 * What it found is kept per album in [kept], the artist-level table (#238, [PART]), by the release
 * group, else the album's and the artist's names folded, with the sources asked, for the shortest
 * of their lifetimes (D9); not when a source could not be asked. Never throws but for cancellation.
 */
class AlbumSource(
    private val groups: ReleaseGroups,
    private val wikidata: WikidataLookup,
    private val wikipedia: WikipediaClient,
    private val names: NameMatcher,
    private val clock: Clock = Clock.System,
    private val kept: ArtistResults = ArtistResults.None,
) : AlbumLookup {
    override suspend fun lookup(query: AlbumQuery): AlbumFound {
        val key = keyOf(query)
        kept.get(PART, key)?.let { stored -> decode(stored.value)?.let { return AlbumFound(it) } }
        val found = gather(query)
        if (!found.unreachable) kept.keep(PART, key, encode(found.drafts))
        return found
    }

    // What the sources [query] may ask say about the album.
    private suspend fun gather(query: AlbumQuery): AlbumFound {
        // In whole milliseconds, as the table keeps it, so a line read back is the line found.
        val at = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())
        val asking = Asking()
        val album = query.group?.takeIf { query.musicBrainz }?.let { asking.read(groups.releaseGroup(it), null) }
        val drafts = mutableListOf<EvidenceDraft>()
        album?.let { drafts += musicBrainzLine(it, at) }
        if (query.wikipedia) item(query, album, asking)?.let { drafts += wikiLines(it, query, at, asking) }
        return AlbumFound(drafts, asking.unreachable)
    }

    // MusicBrainz's line on the release group: "“Sundown” is an album by Gordon Lightfoot, first
    // released in January 1974."
    private fun musicBrainzLine(
        album: MbAlbum,
        at: Instant,
    ): EvidenceDraft {
        val type = typeOf(album)
        val by =
            album.credit
                .takeIf { it.isNotBlank() }
                ?.let { " by $it" }
                .orEmpty()
        val released = album.firstReleaseDate?.let { ", first released ${EvidenceText.date(it)}" }.orEmpty()
        val text = "“${album.title}” is ${article(type)} $type$by$released."
        return EvidenceDraft(SourceId.MUSICBRAINZ, Scope.RELEASE, text, "$MUSICBRAINZ_GROUP${album.mbid}", at)
    }

    // Whether a source could not be asked, across one album's lookups.
    private class Asking {
        var unreachable = false

        // [found]'s value; [none] when it is not found, or failed, which is noted.
        fun <T> read(
            found: Lookup<T>,
            none: T,
        ): T =
            when (found) {
                is Lookup.Found -> found.value
                Lookup.NotFound -> none
                is Lookup.Failed -> none.also { unreachable = true }
            }
    }

    // The album's Wikidata item: the one MusicBrainz links, else the one that names the release group
    // (neither needs a check); found by name only when MusicBrainz names no release group for it.
    private suspend fun item(
        query: AlbumQuery,
        album: MbAlbum?,
        asking: Asking,
    ): WdEntity? {
        val group = query.group
        val ids =
            album?.wikidataIds.orEmpty().ifEmpty {
                group
                    ?.let {
                        asking.read(
                            wikidata.itemsWithStatement(MUSICBRAINZ_RELEASE_GROUP, it),
                            emptyList(),
                        )
                    }.orEmpty()
            }
        return when {
            ids.isNotEmpty() -> asking.read(wikidata.entities(ids.take(1)), emptyMap())[ids.first()]
            group == null -> byName(query, asking)
            else -> null
        }
    }

    // The article the album's name finds whose item is an album by the artist.
    private suspend fun byName(
        query: AlbumQuery,
        asking: Asking,
    ): WdEntity? {
        val titles = titlesOf(query)
        val pages = if (titles.isEmpty()) emptyMap() else asking.read(wikipedia.pages(titles), emptyMap())
        val items = titles.mapNotNull { pages[it]?.takeIf { page -> !page.disambiguation }?.item }.distinct()
        val entities = if (items.isEmpty()) emptyMap() else asking.read(wikidata.entities(items), emptyMap())
        val albums =
            items.mapNotNull { entities[it] }.filter { entity ->
                entity.values(Wd.INSTANCE_OF).any { it in ALBUM_CLASSES }
            }
        val performers = albums.flatMap { it.values(Wd.PERFORMER) }.distinct()
        val labels = if (performers.isEmpty()) emptyMap() else asking.read(wikidata.labels(performers), emptyMap())
        return albums.firstOrNull { entity ->
            entity.values(Wd.PERFORMER).any { id -> labels[id]?.let { names.sameName(it, query.artist) } == true }
        }
    }

    // The item's lines: its article's lead sentences, verbatim; its producers and record label, templated.
    private suspend fun wikiLines(
        entity: WdEntity,
        query: AlbumQuery,
        at: Instant,
        asking: Asking,
    ): List<EvidenceDraft> {
        val name = entity.label ?: query.title
        val title = entity.enwiki
        val lead = title?.let { asking.read(wikipedia.lead(it), null) }
        val quoted =
            lead?.let { WikipediaSource.sentences(it, listOf(name, query.title), names) }.orEmpty().map {
                EvidenceDraft(SourceId.WIKIPEDIA, Scope.RELEASE, it, title?.let(::articleUrl), at)
            }
        val producers = entity.values(PRODUCER)
        val labels = entity.values(Wd.RECORD_LABEL)
        val ids = (producers + labels).distinct()
        val named = if (ids.isEmpty()) emptyMap() else asking.read(wikidata.labels(ids), emptyMap())
        val url = Wd.url(entity.id)
        val templated =
            listOfNotNull(
                templated(producers, named) { "“$name” was produced by $it." },
                templated(labels, named) { "“$name” was released on $it." },
            ).map { EvidenceDraft(SourceId.WIKIDATA, Scope.RELEASE, it, url, at) }
        return quoted + templated
    }

    // [ids], by their [named] labels, in a sentence [said]; null for none named.
    private fun templated(
        ids: List<String>,
        named: Map<String, String>,
        said: (String) -> String,
    ): String? = ids.mapNotNull { named[it] }.takeIf { it.isNotEmpty() }?.let { said(EvidenceText.list(it)) }

    // The article titles an album would have, most specific first.
    private fun titlesOf(query: AlbumQuery): List<String> {
        val title = query.title.trim()
        if (title.isEmpty()) return emptyList()
        val artist = query.artist.trim()
        return listOfNotNull(artist.takeIf { it.isNotEmpty() }?.let { "$title ($it album)" }, "$title (album)", title)
    }

    // The album's key in the table: its release group, else its folded names; with the sources asked.
    private fun keyOf(query: AlbumQuery): String {
        val album =
            query.group?.let { "mb:$it" }
                ?: "name:${names.albumKey(
                    query.artist,
                ).length}:${names.albumKey(query.artist)}${names.albumKey(query.title)}"
        return "$album ${if (query.musicBrainz) 1 else 0}${if (query.wikipedia) 1 else 0}"
    }

    companion object {
        /**
         * An album's lines in the artist-level table (#238), by [keyOf]'s key: MusicBrainz's, Wikidata's
         * and Wikipedia's, so kept for the shortest of their lifetimes.
         */
        val PART: ArtistPart =
            ArtistPart(
                "album.evidence",
                extraction = 1,
                encoding = 1,
                sources = setOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.WIKIDATA),
            )

        /** Wikidata's MusicBrainz release group ID. */
        const val MUSICBRAINZ_RELEASE_GROUP: String = "P436"

        /** Wikidata's producer. */
        const val PRODUCER: String = "P162"

        /**
         * An album's Wikidata classes: album, studio album, live album, compilation album, EP,
         * soundtrack album and remix album. Matched directly, as the song classes are (`Wd.SONG_CLASSES`).
         */
        val ALBUM_CLASSES: Set<String> =
            setOf("Q482994", "Q208569", "Q209939", "Q222910", "Q169930", "Q4176708", "Q1366112")

        private const val MUSICBRAINZ_GROUP = "https://musicbrainz.org/release-group/"

        private val json = Json { encodeDefaults = false }

        // "Album" is an album; "Live" with "Album", a live album; none, a release.
        internal fun typeOf(album: MbAlbum): String {
            val primary = album.primaryType?.let { if (it == "EP") it else it.lowercase() } ?: "release"
            val secondary = album.secondaryTypes.joinToString(" ") { it.lowercase() }
            return if (secondary.isEmpty()) primary else "$secondary $primary"
        }

        // "a" or "an" before [word].
        private fun article(word: String): String =
            if (word.first().lowercaseChar() in "aeio" ||
                word == "EP"
            ) {
                "an"
            } else {
                "a"
            }

        @Serializable
        private class KeptLine(
            val s: String,
            val c: String,
            val t: String,
            val u: String? = null,
            val a: Long,
        )

        private fun encode(drafts: List<EvidenceDraft>): String =
            json.encodeToString(
                drafts.map {
                    KeptLine(
                        it.source.name,
                        it.scope.key,
                        it.text,
                        it.url,
                        it.retrievedAt.toEpochMilliseconds(),
                    )
                },
            )

        // The lines kept, or null when they cannot be read (the table's entry from another encoding).
        private fun decode(value: String): List<EvidenceDraft>? =
            try {
                json.decodeFromString<List<KeptLine>>(value).map { line ->
                    EvidenceDraft(
                        SourceId.valueOf(line.s),
                        Scope.entries.first { it.key == line.c },
                        line.t,
                        line.u,
                        Instant.fromEpochMilliseconds(line.a),
                    )
                }
            } catch (
                @Suppress("SwallowedException") e: SerializationException,
            ) {
                null
            } catch (
                @Suppress("SwallowedException") e: IllegalArgumentException,
            ) {
                null
            } catch (
                @Suppress("SwallowedException") e: NoSuchElementException,
            ) {
                null
            }
    }
}
