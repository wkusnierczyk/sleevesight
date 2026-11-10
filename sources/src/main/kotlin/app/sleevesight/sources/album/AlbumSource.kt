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
import app.sleevesight.sources.KeptDrafts
import app.sleevesight.sources.OneSearchAtATime
import app.sleevesight.sources.lastfm.LastFmAlbum
import app.sleevesight.sources.lastfm.LastFmAlbumInfo
import app.sleevesight.sources.lastfm.LastFmBio
import app.sleevesight.sources.lastfm.Lru
import app.sleevesight.sources.musicbrainz.MbAlbum
import app.sleevesight.sources.musicbrainz.ReleaseGroups
import app.sleevesight.sources.web.AlbumWebSearch
import app.sleevesight.sources.web.getCarried
import app.sleevesight.sources.wikipedia.WikipediaClient
import app.sleevesight.sources.wikipedia.WikipediaSource
import app.sleevesight.sources.wikipedia.articleUrl
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
 *   (`Title (Artist album)`, `Title (album)`, `Title`; as given, then without its editions, bracketed
 *   or not, [titlesOf]) whose item is an album ([ALBUM_CLASSES]) by
 *   the artist (a performer, P175, of the artist's name; with [AlbumQuery.performers], of one of
 *   theirs, #1130); then the item's record label and producers,
 *   templated (CC0), and its English article's lead, at most three sentences naming the album,
 *   verbatim and linked (D1, CC BY-SA 4.0), as Wikipedia's lines on a track are
 *   ([WikipediaSource.sentences]).
 * - **Last.fm** (with [AlbumQuery.lastFm] and the user's key, [lastFm], #885): the album
 *   (`album.getInfo`, by its name without a bracketed edition, `Edition.stripEditions`, then, when
 *   that finds no album, by the names without an unbracketed one too, least stripped first,
 *   `Edition.strippedNames`), when its
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
 *   be checked), so a search is counted as paid only when it is. The caller's gate is asked after
 *   the waits (the kept lines read, another write's search), right before the request, and the
 *   caller is given what was found before it is kept, to claim the key it gives (#935).
 *
 * What the free sources found is kept per album in [kept], the artist-level table (#238, [PART]),
 * by the release group, else the album's and the artist's names folded (and the performers', with
 * [AlbumQuery.performers]), with the sources asked,
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

    // The web searches under way, by album ([webKeyOf]): one at a time per album.
    private val searching = OneSearchAtATime<List<EvidenceDraft>>()

    override suspend fun lookup(query: AlbumQuery): AlbumFound {
        val free = free(query)
        val searches = searches(query)
        val searched = if (searches) keptWeb(query) else null
        return AlbumFound(free.drafts + searched.orEmpty(), free.unreachable, webDue = searches && searched == null)
    }

    override val searchesWeb: Boolean get() = web != null

    override suspend fun searchWeb(
        query: AlbumQuery,
        mayPay: () -> Boolean,
        keeping: (List<EvidenceDraft>) -> Unit,
        sent: () -> Unit,
    ): List<EvidenceDraft>? =
        web?.takeIf { searches(query) }?.let { search ->
            try {
                searching.once(webKeyOf(query)) { keptWeb(query) ?: searched(search, query, sent, mayPay, keeping) }
            } catch (
                @Suppress("SwallowedException") e: NotPaid,
            ) {
                // Its gate closed after the waits: nothing sent, nothing kept; a write waiting for this
                // one searches, or reads, itself.
                null
            }
        }

    override suspend fun renewWeb(query: AlbumQuery) {
        val key = webKeyOf(query)
        if (webKept.persists) {
            webKept.getCarried(WEB_PART, WEB_BEFORE_DECODING, key)?.let { webKept.keep(WEB_PART, key, it.value) }
        } else {
            remembered.get(key)?.let { remembered.put(key, it) }
        }
    }

    // What [search] finds for [query]'s album, none included, kept; null when it failed, and nothing
    // is kept. [sent] is told when the request goes out; [mayPay] is asked right before it, with no
    // suspension between (closed, [NotPaid]); [keeping] is given what was found before it is kept.
    private suspend fun searched(
        search: AlbumWebSearch,
        query: AlbumQuery,
        sent: () -> Unit,
        mayPay: () -> Boolean,
        keeping: (List<EvidenceDraft>) -> Unit,
    ): List<EvidenceDraft>? {
        if (!mayPay()) throw NotPaid()
        val found =
            when (val answer = search.search(query.title, query.artist, query.performers, query.credited, sent)) {
                is Lookup.Found -> answer.value
                Lookup.NotFound -> emptyList()
                is Lookup.Failed -> null
            }
        found?.let(keeping)
        val key = webKeyOf(query)
        when {
            found == null -> Unit
            webKept.persists -> webKept.keep(WEB_PART, key, KeptDrafts.encode(found))
            else -> remembered.put(key, found)
        }
        return found
    }

    // A search its caller's gate refused right before the request (#935, as `ArtistWebSource`'s).
    private class NotPaid : Exception("not paid")

    // Whether the web may be searched for [query]'s album: asked for, with an engine.
    private fun searches(query: AlbumQuery): Boolean = query.web && web != null

    // The web's lines kept for [query]'s album, none included; null when no search was kept.
    private suspend fun keptWeb(query: AlbumQuery): List<EvidenceDraft>? {
        val key = webKeyOf(query)
        return if (webKept.persists) {
            webKept.getCarried(WEB_PART, WEB_BEFORE_DECODING, key)?.let { KeptDrafts.decode(it.value) }
        } else {
            remembered.get(key)
        }
    }

    // What the free sources [query] may ask say about the album: kept, or gathered and kept.
    private suspend fun free(query: AlbumQuery): AlbumFound {
        val key = keyOf(query)
        kept.get(PART, key)?.let { stored -> KeptDrafts.decode(stored.value)?.let { return AlbumFound(it) } }
        val found = gather(query)
        if (!found.unreachable) kept.keep(PART, key, KeptDrafts.encode(found.drafts))
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
        // which Last.fm's page for the album does not have; then, until an album is confirmed, by the
        // names without an unbracketed one too, least stripped first, at most two (#1070:
        // "Decksandrumsandrockandroll 20th anniversary"; "Paris 1919 Remastered" is "Paris 1919", then
        // "Paris"). A request that fails ends them: Last.fm is down, and the next would wait as long.
        var title = Edition.stripEditions(query.title)
        var info: LastFmAlbum? = null
        val names = (listOf(title) + Edition.strippedNames(query.title)).distinct().iterator()
        var failed = false
        while (info == null && !failed && names.hasNext()) {
            val name = names.next()
            val lookup = client.albumInfo(query.artist, name)
            val answer = asking.read(lookup, null)
            failed = lookup is Lookup.Failed
            if (answer != null && isAlbum(answer, query, album, asking)) {
                title = name
                info = answer
            }
        }
        val sentences = info?.let { LastFmBio.sentences(it.summary) }
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

    // The article the album's name finds whose item is an album by the artist, or with
    // [AlbumQuery.performers] (#1130), by one of them: a classical album's item names its performers
    // (P175), seldom the composer the track credits first, and one naming the composer alone may be
    // another recording of the same works.
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
        val by = query.performers.ifEmpty { listOf(query.artist) }
        return albums.firstOrNull { entity ->
            entity.values(Wd.PERFORMER).any { id ->
                labels[id]?.let { label -> by.any { names.sameName(label, it) } } == true
            }
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

    // The article titles an album would have, most specific first: by its name as given, then by
    // its names without its editions (#1070, `Edition.strippedNames`), least stripped first; with
    // [AlbumQuery.performers] (#1130), `Title (<first performer> album)`, as the album's article
    // would be disambiguated by its performer, not its composer.
    private fun titlesOf(query: AlbumQuery): List<String> =
        titlesOf(query.title, query.performers.firstOrNull() ?: query.artist)

    // The album's key in the table: its release group, else its folded names; with the sources asked
    // (Last.fm only with its key).
    private fun keyOf(query: AlbumQuery): String {
        val album = query.group?.let { "mb:$it" } ?: namesKeyOf(query)
        val asked = listOf(query.musicBrainz, query.wikipedia, query.lastFm && lastFm != null)
        return "$album ${asked.joinToString("") { if (it) "1" else "0" }}"
    }

    // The album's key for its web lines: its folded names, which the search sends, never the group.
    private fun webKeyOf(query: AlbumQuery): String = namesKeyOf(query)

    // The album's and the artist's names folded, the artist's length first, so no two pairs meet;
    // with [AlbumQuery.performers] (#1130), each performer's too, before them, so two recordings of
    // one title by one composer are kept apart, and an album's key without performers is as before.
    private fun namesKeyOf(query: AlbumQuery): String {
        val artist = names.albumKey(query.artist)
        val performers =
            query.performers.joinToString("") {
                val performer = names.albumKey(it)
                "performer:${performer.length}:$performer "
            }
        return "${performers}name:${artist.length}:$artist${names.albumKey(query.title)}"
    }

    companion object {
        /**
         * The article titles an album named [album] by [artist] would have, most specific first
         * (`Title (Artist album)`, `Title (album)`, `Title`): by the name as given, then by the names
         * without its editions, bracketed or not, least stripped first ([Edition.strippedNames],
         * #1070: "Decksandrumsandrockandroll 20th anniversary" is "Decksandrumsandrockandroll";
         * "Paris 1919 Remastered" is "Paris 1919", then "Paris"). The by-name check (an album item by
         * the artist) still decides the match, the first such title's album taken.
         */
        fun titlesOf(
            album: String,
            artist: String,
        ): List<String> {
            val given = album.trim()
            if (given.isEmpty()) return emptyList()
            val by = artist.trim().takeIf { it.isNotEmpty() }
            return (listOf(given) + Edition.strippedNames(given))
                .flatMap { title -> listOfNotNull(by?.let { "$title ($it album)" }, "$title (album)", title) }
        }

        /**
         * An album's lines in the artist-level table (#238), by [keyOf]'s key: MusicBrainz's,
         * Wikidata's, Wikipedia's and Last.fm's, so kept for the shortest of their lifetimes.
         * Extraction 2 (#885): Last.fm's lines, and its switch in the key; an entry of 1 lacks them.
         * 3 (#850): Wikipedia's lines without the brackets a dropped pronunciation left empty.
         * 4 (#1070): the album's name also tried without an unbracketed edition ("… 20th
         *   anniversary"), whose article or Last.fm page an entry of 3 may have missed.
         */
        val PART: ArtistPart =
            ArtistPart(
                "album.evidence",
                extraction = 4,
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
         * entry of 1 was kept for the web's lifetime. 3 (#905): the lines without the parts that are
         * only page boilerplate (`ExaWebSearch.EXTRACTION_VERSION` 3), so an album's lines kept
         * before are searched again. 4 (#1165): Exa's text with its HTML character references
         * decoded (`ExaWebSearch.EXTRACTION_VERSION` 4), likewise.
         */
        val WEB_PART: ArtistPart =
            ArtistPart("album.web", extraction = 4, encoding = 1, sources = setOf(SourceId.WEB), keptAsHistory = true)

        /** [WEB_PART] before Exa's references were decoded (#1165), whose lines are carried over without one. */
        internal val WEB_BEFORE_DECODING: ArtistPart = WEB_PART.copy(extraction = 3)

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
    }
}
