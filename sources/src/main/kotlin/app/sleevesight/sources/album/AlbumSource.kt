package app.sleevesight.sources.album

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Edition
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
import app.sleevesight.sources.lastfm.LastFmAlbum
import app.sleevesight.sources.lastfm.LastFmAlbumInfo
import app.sleevesight.sources.lastfm.LastFmBio
import app.sleevesight.sources.lastfm.Lru
import app.sleevesight.sources.musicbrainz.MbAlbum
import app.sleevesight.sources.musicbrainz.ReleaseGroups
import app.sleevesight.sources.web.AlbumWebSearch
import app.sleevesight.sources.wikipedia.WikipediaClient
import app.sleevesight.sources.wikipedia.WikipediaSource
import app.sleevesight.sources.wikipedia.articleUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * - **Last.fm** (with [AlbumQuery.lastFm] and the user's key, [lastFm], #885): the album
 *   (`album.getInfo`, by its name without an edition, `Edition.stripEditions`), when its
 *   MusicBrainz id shows it is the album asked for ([isAlbum]: never by its names alone); its wiki
 *   summary's sentences, taken as an artist's bio's are ([LastFmBio]: whole, of a quotable length, at most three, none
 *   from a page several share, without the "Read more" link and the licence note), verbatim and
 *   attributed (D1, CC BY-SA), with the album's page. Not its listeners, play counts or tags, counts
 *   and lists of what many listeners did, which the artist's evidence leaves out too (`aggregate`):
 *   they change from one lookup to the next, and so would the album's fingerprint, and its card's key.
 * - **The web** (with [AlbumQuery.web] and an engine's key, [web], #885): the pages about the album
 *   a search finds (`ExaAlbumSearch`), web lines. The search is paid, so [lookup] never makes it:
 *   it gives the lines an earlier search kept, and says when one is due ([AlbumFound.webDue]);
 *   [searchWeb] makes it, before the album's insight is written, one at a time per album (a second
 *   write of the album while one searches takes its outcome), and keeps what it found, telling
 *   its caller when a request is sent, and only then (not for lines kept, or an album that cannot
 *   be checked), so a search is counted as paid only when it is.
 *
 * What the free sources found is kept per album in [kept], the artist-level table (#238, [PART]),
 * by the release group, else the album's and the artist's names folded, with the sources asked,
 * for the shortest of their lifetimes (D9); not when a source could not be asked. What the web
 * search found, none included, is kept apart ([WEB_PART]), by the names folded, in [webKept],
 * which the app keeps whatever *Keep what sources said about each artist* says, as it keeps a
 * track's own web findings (`SourceResult.trackOnly`), and for as long as the album's insight is
 * kept (*Keep history for*, [ArtistPart.keptAsHistory]), not for the web's own lifetime, counted
 * from the last card written from them ([renewWeb]): a later write, or a later lookup, of the
 * album finds the same lines with no search, and so the insight under the same key, on any day it
 * is kept. With a [webKept] that keeps
 * nothing ([ArtistResults.persists] false: the CLI, tests), in memory for the most recent
 * [REMEMBERED] albums instead. Never throws but for cancellation.
 */
@Suppress(
    "LongParameterList", // The sources, how names are read, the time, and where what they say is kept.
    "TooManyFunctions", // Each source's lines, the web's kept apart, and the keys.
)
class AlbumSource(
    private val groups: ReleaseGroups,
    private val wikidata: WikidataLookup,
    private val wikipedia: WikipediaClient,
    private val names: NameMatcher,
    private val clock: Clock = Clock.System,
    private val kept: ArtistResults = ArtistResults.None,
    private val lastFm: LastFmAlbumInfo? = null,
    private val web: AlbumWebSearch? = null,
    private val webKept: ArtistResults = ArtistResults.None,
) : AlbumLookup {
    // The web's lines by album, while [webKept] keeps nothing.
    private val remembered = Lru<String, List<EvidenceDraft>>(REMEMBERED)

    // The web searches under way, by album ([webKeyOf]), under [searching]: one at a time per album.
    private val searching = Mutex()
    private val inFlight = mutableMapOf<String, CompletableDeferred<Searched?>>()

    override suspend fun lookup(query: AlbumQuery): AlbumFound {
        val free = free(query)
        val searches = searches(query)
        val searched = if (searches) keptWeb(query) else null
        return AlbumFound(free.drafts + searched.orEmpty(), free.unreachable, webDue = searches && searched == null)
    }

    override suspend fun searchWeb(
        query: AlbumQuery,
        sent: () -> Unit,
    ): List<EvidenceDraft>? =
        web?.takeIf { searches(query) }?.let { search ->
            once(webKeyOf(query)) { keptWeb(query) ?: searched(search, query, sent) }
        }

    override suspend fun renewWeb(query: AlbumQuery) {
        val key = webKeyOf(query)
        if (webKept.persists) {
            webKept.get(WEB_PART, key)?.let { webKept.keep(WEB_PART, key, it.value) }
        } else {
            remembered.get(key)?.let { remembered.put(key, it) }
        }
    }

    // A search's outcome, shared with the writes that waited for it: its [lines], or null when it failed.
    private class Searched(
        val lines: List<EvidenceDraft>?,
    )

    // [search] for the album [key], one at a time: a second write of the album while one searches
    // waits for it and takes its outcome (the lines it kept), rather than paying for its own. When the
    // one under way is cancelled (a Stop), the next takes its place and reads, or searches, itself.
    private suspend fun once(
        key: String,
        search: suspend () -> List<EvidenceDraft>?,
    ): List<EvidenceDraft>? {
        var outcome: Searched? = null
        while (outcome == null) {
            val mine = CompletableDeferred<Searched?>()
            val pending =
                searching.withLock {
                    inFlight[key]?.takeIf { !it.isCompleted }
                        ?: mine.also { inFlight[key] = it }
                }
            outcome = if (pending === mine) searchedOnce(key, mine, search) else pending.await()
        }
        return outcome.lines
    }

    // [search] made for [key], its outcome told to [pending] whatever happens (null when cancelled).
    private suspend fun searchedOnce(
        key: String,
        pending: CompletableDeferred<Searched?>,
        search: suspend () -> List<EvidenceDraft>?,
    ): Searched =
        try {
            Searched(search()).also {
                pending.complete(it)
                searching.withLock { if (inFlight[key] === pending) inFlight.remove(key) }
            }
        } finally {
            // Cancelled or failed: those waiting go on, and the next takes its place.
            pending.complete(null)
        }

    // What [search] finds for [query]'s album, none included, kept; null when it failed, and nothing
    // is kept. [sent] is told when the request goes out.
    private suspend fun searched(
        search: AlbumWebSearch,
        query: AlbumQuery,
        sent: () -> Unit,
    ): List<EvidenceDraft>? {
        val found =
            when (val answer = search.search(query.title, query.artist, sent)) {
                is Lookup.Found -> answer.value
                Lookup.NotFound -> emptyList()
                is Lookup.Failed -> null
            }
        val key = webKeyOf(query)
        when {
            found == null -> Unit
            webKept.persists -> webKept.keep(WEB_PART, key, encode(found))
            else -> remembered.put(key, found)
        }
        return found
    }

    // Whether the web may be searched for [query]'s album: asked for, with an engine.
    private fun searches(query: AlbumQuery): Boolean = query.web && web != null

    // The web's lines kept for [query]'s album, none included; null when no search was kept.
    private suspend fun keptWeb(query: AlbumQuery): List<EvidenceDraft>? {
        val key = webKeyOf(query)
        return if (webKept.persists) {
            webKept.get(WEB_PART, key)?.let { decode(it.value) }
        } else {
            remembered.get(key)
        }
    }

    // What the free sources [query] may ask say about the album: kept, or gathered and kept.
    private suspend fun free(query: AlbumQuery): AlbumFound {
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
        lastFm?.takeIf { query.lastFm }?.let { drafts += lastFmLines(it, query, album, at, asking) }
        return AlbumFound(drafts, asking.unreachable)
    }

    // Last.fm's lines on the album, when its answer is the album asked for: its wiki summary's
    // sentences, as an artist's bio's are taken, quoted: "The Last.fm wiki of “Sundown” says: …".
    @Suppress("LongParameterList") // The client, the album, MusicBrainz's group, the time, and the failures.
    private suspend fun lastFmLines(
        client: LastFmAlbumInfo,
        query: AlbumQuery,
        album: MbAlbum?,
        at: Instant,
        asking: Asking,
    ): List<EvidenceDraft> {
        // With no release group, no answer could be confirmed ([isAlbum]): Last.fm is not asked.
        if (query.group == null) return emptyList()
        // Without the edition a player or store adds (#885: "Abbey Road (Remastered)" is "Abbey Road"),
        // which Last.fm's page for the album does not have.
        val title = Edition.stripEditions(query.title)
        val info = asking.read(client.albumInfo(query.artist, title), null)
        val sentences =
            if (info != null &&
                isAlbum(info, query, album, asking)
            ) {
                LastFmBio.sentences(info.summary)
            } else {
                null
            }
        return sentences.orEmpty().map { sentence ->
            EvidenceDraft(
                SourceId.LASTFM,
                Scope.RELEASE,
                "The Last.fm wiki of “$title” says: \"$sentence\"",
                info?.url,
                at,
            )
        }
    }

    /**
     * Whether Last.fm's [info] is the album [query] asks for, by its MusicBrainz id (a release's,
     * usually), never by its names alone, since another album's summary is worse than none: Last.fm
     * keeps one page per artist's name and album's title, which an artist's namesake may share and
     * its autocorrection may swap for another's, and nothing here tells such a page apart by name.
     * (An artist's own page is verified against the track's resolution, `LastFmMatch`, which an
     * album's lookup, by the album alone, does not have.)
     *
     * 1. The id is the album's release group's (as [album] or [query] has it) or one of the group's
     *    releases ([MbAlbum.releaseIds]): the album, whatever its names.
     * 2. Otherwise, while the group lists all its releases (fewer than [MbAlbum.LINKED_LIMIT]),
     *    another album.
     * 3. Otherwise, with MusicBrainz on, the group of the release the id names, asked of MusicBrainz
     *    ([ReleaseGroups.groupOfRelease]): the album when it is the album's group; not when it is
     *    another's, or the release is not found. A request that fails gives nothing, and makes the
     *    lookup unreachable, so it is not kept.
     *
     * No id, no group known (an album looked up by name), or MusicBrainz off with an id not the
     * group's: not the album, and its summary is left out. A page its summary says several share
     * gives no sentence either ([LastFmBio.sentences]).
     */
    private suspend fun isAlbum(
        info: LastFmAlbum,
        query: AlbumQuery,
        album: MbAlbum?,
        asking: Asking,
    ): Boolean {
        val mbid = info.mbid?.lowercase()
        val group = (album?.mbid ?: query.group)?.lowercase()
        val releases = album?.releaseIds.orEmpty().map(String::lowercase)
        val listsAll = releases.isNotEmpty() && releases.size < MbAlbum.LINKED_LIMIT
        return when {
            mbid == null || group == null -> false
            mbid == group || mbid in releases -> true
            listsAll || !query.musicBrainz -> false
            else -> asking.read(groups.groupOfRelease(mbid), null)?.lowercase() == group
        }
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

    // The album's key in the table: its release group, else its folded names; with the sources asked
    // (Last.fm only with its key).
    private fun keyOf(query: AlbumQuery): String {
        val album = query.group?.let { "mb:$it" } ?: namesKeyOf(query)
        val asked = listOf(query.musicBrainz, query.wikipedia, query.lastFm && lastFm != null)
        return "$album ${asked.joinToString("") { if (it) "1" else "0" }}"
    }

    // The album's key for its web lines: its folded names, which the search sends, never the group.
    private fun webKeyOf(query: AlbumQuery): String = namesKeyOf(query)

    // The album's and the artist's names folded, the artist's length first, so no two pairs meet.
    private fun namesKeyOf(query: AlbumQuery): String {
        val artist = names.albumKey(query.artist)
        return "name:${artist.length}:$artist${names.albumKey(query.title)}"
    }

    companion object {
        /**
         * An album's lines in the artist-level table (#238), by [keyOf]'s key: MusicBrainz's,
         * Wikidata's, Wikipedia's and Last.fm's, so kept for the shortest of their lifetimes.
         * Extraction 2 (#885): Last.fm's lines, and its switch in the key; an entry of 1 lacks them.
         * 3 (#850): Wikipedia's lines without the brackets a dropped pronunciation left empty.
         */
        val PART: ArtistPart =
            ArtistPart(
                "album.evidence",
                extraction = 3,
                encoding = 1,
                sources = setOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.WIKIDATA, SourceId.LASTFM),
            )

        /**
         * An album's web lines (#885), by the album's and the artist's names folded: what its web
         * search found, none included, kept whatever *Keep what sources said about each artist* says
         * (`PipelineWiring.TRACK_ONLY_PARTS`), and as long as the album's insight written from them
         * ([ArtistPart.keptAsHistory]: *Keep history for*), so its card's key stays the same across
         * days; the web's own lifetime would expire them first, and a later lookup without them
         * would key the album anew and pay for another search and call. Extraction 2: kept so; an
         * entry of 1 was kept for the web's lifetime.
         */
        val WEB_PART: ArtistPart =
            ArtistPart("album.web", extraction = 2, encoding = 1, sources = setOf(SourceId.WEB), keptAsHistory = true)

        /** The albums whose web lines are kept in memory, without a [webKept] that keeps them. */
        const val REMEMBERED: Int = 256

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
