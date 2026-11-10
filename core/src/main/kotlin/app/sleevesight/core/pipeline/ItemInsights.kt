package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Edition
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.TrackLanguageSearch
import app.sleevesight.core.sources.AlbumFound
import app.sleevesight.core.sources.AlbumLookup
import app.sleevesight.core.sources.AlbumQuery
import app.sleevesight.core.sources.ArtistWebLookup
import app.sleevesight.core.sources.ArtistWebQuery
import app.sleevesight.core.sources.ItemLanguageLines
import app.sleevesight.core.sources.LanguageSubject
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SongWebLookup
import app.sleevesight.core.sources.SongWebQuery
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.ItemPrompt
import app.sleevesight.core.synthesis.SelfRevision
import app.sleevesight.core.text.DateForm
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The song's, the artist's and the album's insights under *Key insights* on Home (#522), each its
 * own [kind] of card.
 */
enum class InsightItem(
    val kind: CardKind,
) {
    SONG(CardKind.SONG),
    ARTIST(CardKind.ARTIST),
    ALBUM(CardKind.ALBUM),
}

/**
 * The evidence an item is written from ([ItemInsights.evidence]): [evidence], and of its ids those
 * that are lines of the track's own evidence, by the track's id ([trackIds]), so a citation opens
 * *Sources* at its line. [unreachable] when a source of the album's could not be asked. [webDue]
 * when a web search is due before its insight is written: the album's (#885), the artist's, whose
 * sources say nothing about the artist (#912), or the song's, whose sources only list it (#958)
 * ([ItemInsights.search], [ItemInsights.writeSearching]). [onlyListings] when it is the song's, and
 * none of its own lines describes it as they stand (`SongListings`, #958): its prompt says how to
 * write that ([ItemPrompt.ONLY_LISTINGS_RULE]), its [evidence] holds the artist's lines for it, and
 * its card has a prompt version of its own ([ItemPrompt.ONLY_LISTINGS_VERSION]).
 */
class ItemEvidence(
    val evidence: EvidenceSet,
    val trackIds: Map<String, String>,
    val unreachable: Boolean = false,
    val webDue: Boolean = false,
    val onlyListings: Boolean = false,
)

/**
 * What a web search on the way to an item's write came to ([ItemInsights.search], #885, #912): none
 * made ([None]: none due or allowed, or, for the artist, its insight writes it itself); one that
 * [Failed], keeping nothing; or the item's evidence with the web's lines ([Found]).
 */
sealed interface ItemSearch {
    /** No search was made, or, for the album, none could be (its lookup tells no failure apart). */
    data object None : ItemSearch

    /** The artist's search failed: nothing is kept, and it is due again at the next write. */
    data object Failed : ItemSearch

    /** The artist's or the album's search was not made: its gate closed after the waits, before the request. */
    data object Refused : ItemSearch

    /** The item's [evidence] with the lines its search found, or an earlier one kept. */
    class Found(
        val evidence: ItemEvidence,
    ) : ItemSearch
}

/**
 * The artist's insight (#912), or the song's (#958), written while the AI service searched the web
 * ([ItemInsights.writeSearching]): the item's [evidence] then, the pages found among it, the [key]
 * the insight is kept under (its fingerprint theirs), and its last [state].
 */
class SearchedWrite(
    val evidence: ItemEvidence,
    val key: CardKey,
    val state: InsightState,
)

/**
 * What the caller of [ItemInsights.writeSearching] lets it do (#912): pay for each search and call
 * ([mayPay], asked right before each), and write the key its pages give ([reserve], asked before
 * they are kept, which the caller claims then).
 */
class WriteGates(
    val mayPay: () -> Boolean = { true },
    val reserve: (CardKey) -> Boolean = { true },
)

/**
 * An item's insight kept in the cache: its [state] as checked, and when it was written
 * ([writtenAt]), if the cache says.
 */
data class KeptItem(
    val state: InsightState,
    val writtenAt: Instant?,
)

/**
 * The song's, the artist's and the album's insights (#522; `design/design.md`, *Detail content*):
 * each written by the AI service ([ai]), one call of its own ([ItemPrompt]), from the evidence for
 * its own subject ([evidence]), through the key insight's grounding check, and kept in the synthesis
 * cache ([cache]) under its own kind ([InsightItem.kind]) and entity ([CardEntity], #240):
 *
 * - **the song's** ([InsightItem.SONG]): about the track ([CardEntity.Track]), from the track's
 *   evidence less its artist-level lines ([Scope.ARTIST]), each keeping the track's id. When the
 *   song's own lines only list it (`SongListings`, #958) and *AI web search* is used, what a web search
 *   for the song found joins them ([songWeb]), made only on the way to a write, as the artist's is. When
 *   they still only list it, its prompt says so ([ItemEvidence.onlyListings]), and the artist's lines
 *   are among them;
 * - **the artist's** ([InsightItem.ARTIST]): about the track's primary artist ([CardEntity.Artist]),
 *   by its MusicBrainz id when the resolution has one, else by the name the player gives; from the
 *   artist-level lines of the track's evidence (#238: Last.fm's, the artist's Wikipedia article and
 *   Wikidata item), those naming the primary artist when the track credits several; when there is
 *   none, and *AI web search* is used ([Settings.aiWebSearchUsed], *Exa web search* on), from what a
 *   web search for the artist found ([artistWeb], #912), made only on the way to a write: *Once*,
 *   the app's own search ([search]); *Up to 3 times*, the AI service's own searches while it writes
 *   ([writeSearching]), *Once* for a service whose client takes no tools, as [WebQuestions.used];
 * - **the album's** ([InsightItem.ALBUM]): about the album ([CardEntity.Album], by the release
 *   group the player's album name names, else by its name and the primary artist), from the
 *   evidence gathered for the album ([albums], [AlbumLookup]), the pages its web search found
 *   among it once a write made the search ([searched], #885).
 *
 * The artist's and the album's lines are numbered on their own ([EvidenceSet.of]), not as the
 * track's: their fingerprint, part of the key, depends on those lines alone, so an album's tracks
 * share the artist's and the album's insights, written once, rather than each paying for its own.
 * Their ids that are lines of the track's evidence map to the track's ([ItemEvidence.trackIds]).
 *
 * A track MusicBrainz could not be asked about has no ids: its artist's and album's insights are
 * keyed by the names the player gives (`CardEntity.Artist.name`, `CardEntity.Album.title`) and
 * written from what the other sources found; once MusicBrainz answers, the ids and the evidence
 * change, and the insight is written again when next opened.
 *
 * The web's lines, "found nothing" included, are kept per album ([AlbumLookup]) and per artist
 * ([ArtistWebLookup]), so the search is not paid for again, and the insight written from them is
 * found again by its key; each card written from them keeps them again ([AlbumLookup.renewWeb],
 * [ArtistWebLookup.renew]).
 *
 * The lines the search in the track's language found about the artist and the album (#902) join
 * their evidence under *If needed* and *Always*, after the English ones, and are kept per artist and
 * per album ([languageLines], #939), the first found in each language: so each of their tracks
 * takes them, searched or not, and their fingerprints change once, when the lines are first found,
 * not from one track to the next. Each card written from them keeps them again
 * ([ItemLanguageLines.renew]).
 *
 * Not written ([key] null) with *Show insights* off, no AI service ready, or before the track's
 * evidence is in. When the evidence for an item has nothing to narrate
 * ([EvidenceSet.nothingToNarrate]), no call is made, and the item says so
 * ([InsightState.NoEvidence]). A cache that throws is a miss, and a write that fails keeps
 * nothing. Each state written comes with its time by [clock]; a reply kept is kept with the time it
 * is shown with (#643). Each kept under its own model: one written with another AI service, model or
 * effort is never served as [key]'s, but is found apart ([keptByOther], #911), for a row to show it
 * with *Rewrite insight* beside it, and a rewrite keeps both. Each prompt asks for dates in the form
 * [dates] gives as it is built (#957: the phone's, read anew for each write), which no key holds.
 */
@Suppress(
    "LongParameterList", // Where items are written, kept, and gathered, and the time.
    "TooManyFunctions", // Each step of an item: its name, entity, evidence, key, read and write.
    "LargeClass", // Those steps, with the album's and the artist's web searches among them (#885, #912).
)
class ItemInsights(
    private val ai: AiAccess?,
    private val cache: PipelineCache,
    private val hasher: Hasher,
    private val names: NameMatcher,
    private val clock: Clock = Clock.System,
    private val albums: AlbumLookup? = null,
    private val modelUnavailable: Boolean = false,
    private val artistWeb: ArtistWebLookup? = null,
    private val web: WebSearcher? = null,
    private val dates: () -> DateForm = { DateForm.APP },
    private val languageLines: ItemLanguageLines? = null,
    private val songWeb: SongWebLookup? = null,
) {
    /**
     * The name [item] goes by on [track]'s row: the title the player gives, the primary artist, or
     * the player's album name; null for an album when the player names none, which shows no row.
     */
    fun name(
        item: InsightItem,
        track: TrackState,
    ): String? =
        when (item) {
            InsightItem.SONG -> track.nowPlaying.title
            InsightItem.ARTIST -> track.normalized.primary
            InsightItem.ALBUM -> album(track)
        }?.takeIf { it.isNotBlank() }

    /** What [item]'s insight on [track] is about, its entity in the synthesis cache's key (#240). */
    fun entity(
        item: InsightItem,
        track: TrackState,
    ): CardEntity? =
        when (item) {
            InsightItem.SONG -> {
                CardEntity.Track(track.normalized, track.nowPlaying.duration)
            }

            InsightItem.ARTIST -> {
                val primary = track.normalized.primary
                val resolved = resolvedArtists(track)
                val artist = resolved.firstOrNull { names.sameName(it.name, primary) } ?: resolved.firstOrNull()
                artist?.let(CardEntity.Artist::of) ?: CardEntity.Artist(primary, null)
            }

            InsightItem.ALBUM -> {
                album(track)?.let { title ->
                    CardEntity.Album.of(title, track.normalized.primary, releaseDates(track), names::albumKey)
                }
            }
        }

    // [track]'s artists with their MusicBrainz ids: the resolution's, made on this play; else, for a
    // track served whole from the retrieval cache, which makes none, those MusicBrainz's kept result
    // holds (`SourceResult.labelArtists`), so a replay keys the artist as its first play did.
    private fun resolvedArtists(track: TrackState): List<ResolvedArtist> =
        track.resolution?.artists?.ifEmpty { null } ?: musicBrainz(track)?.labelArtists.orEmpty()

    // [track]'s releases, by title and release group: the resolution's, else MusicBrainz's kept result's.
    private fun releaseDates(track: TrackState): List<ReleaseDate> =
        track.resolution?.releaseDates?.ifEmpty { null } ?: musicBrainz(track)?.releaseDates.orEmpty()

    private fun musicBrainz(track: TrackState): SourceResult? =
        (track.sources[SourceId.MUSICBRAINZ] as? SourceState.Done)?.result

    /**
     * The evidence of [track]'s own run under [settings], as the run assembles it: the player's
     * release line and each source's result, with those of the search in the track's language
     * (#902), of the sources [settings] switch on now; null before
     * every source answered. Not [TrackState.evidence], which Home may show from an earlier card, kept
     * from before, in place of the run's, and which a source switched off since still holds.
     */
    fun ownEvidence(
        track: TrackState,
        settings: Settings,
    ): EvidenceSet? {
        if (!track.gathered || track.evidence == null) return null
        val results = track.sources.values.mapNotNull { (it as? SourceState.Done)?.result }
        // With what the search in the track's language found (#902), after the English lines, as the run has them.
        val drafts =
            playerLines(track.nowPlaying, clock.now()) + results.flatMap { it.evidence } + track.languageDrafts()
        return EvidenceSet.of(drafts.filter { settings.isEnabled(it.source) }, hasher, track.knownLanguage())
    }

    /**
     * The evidence [item]'s insight on [track] is written from, under [settings] (its sources'
     * switches), from the run's own evidence ([ownEvidence]), each id mapped to the line Home shows
     * ([TrackState.evidence]) where it is one: null while the track's evidence is not in, every
     * source answered, or with no name for it ([name]). The album's is asked of [albums] (free
     * lookups, kept per album, and the web's lines a search kept, never a search of its own); with
     * none, it is empty. The artist's, when the track's evidence has no line about the artist and
     * *AI web search* is used, is the web's lines a search kept for the artist ([artistWeb], #912),
     * with [ItemEvidence.webDue] while none is kept; never a search of its own.
     */
    suspend fun evidence(
        item: InsightItem,
        track: TrackState,
        settings: Settings,
    ): ItemEvidence? {
        val own = ownEvidence(track, settings)?.takeIf { name(item, track) != null }
        val shown = track.evidence
        val album = entity(item, track) as? CardEntity.Album
        return when {
            own == null || shown == null -> null
            item == InsightItem.SONG -> songEvidence(own, shown, track, settings)
            item == InsightItem.ARTIST -> artistEvidence(own, shown, track, settings)
            album == null -> null
            else -> albumEvidence(album, shown, settings, own)
        }
    }

    // The run's [own] evidence less its artist-level lines, each keeping its id, mapped to the lines
    // [shown]. When the song's own lines only list it (#958): with what a web search for it kept, a
    // search due while none is, under *AI web search* used; as it was while a source that may describe
    // it could not be asked ([unanswered]), with nothing kept, whose lines say nothing yet.
    private suspend fun songEvidence(
        own: EvidenceSet,
        shown: EvidenceSet,
        track: TrackState,
        settings: Settings,
    ): ItemEvidence {
        val song = own.without(hasher) { it.scope == Scope.ARTIST }
        if (!SongListings.listingOnly(song.lines)) return ItemEvidence(song, matching(song.lines, shown))
        val lookup = songWeb?.takeIf { usesWebSearch(settings) }
        val kept = lookup?.let { attempt { it.kept(songQuery(track, settings)) } }
        return if (kept == null && unanswered(track, settings, SONG_SOURCES)) {
            ItemEvidence(song, matching(song.lines, shown))
        } else {
            songWith(own, shown, track, settings, kept.orEmpty(), webDue = lookup != null && kept == null)
        }
    }

    // The song's evidence from the run's [own], with the web's [pages] after its lines (each line
    // keeping its id; a page one of them quotes not twice), those [settings] switch on. While the
    // song's own lines still only list it, with the artist's description ([artistLines]: not the counts
    // and lists of what many listeners did, tags among them, whose numbers and order change from one
    // lookup to the next), each keeping its id; so only then does the card's key read those, and
    // [webDue] whether a search is due then.
    @Suppress("LongParameterList") // The lines, what they are about, and what is added to them.
    private fun songWith(
        own: EvidenceSet,
        shown: EvidenceSet,
        track: TrackState,
        settings: Settings,
        pages: List<EvidenceDraft>,
        webDue: Boolean = false,
    ): ItemEvidence {
        val quoted = own.lines.filter { it.source == SourceId.WEB }.mapNotNullTo(mutableSetOf()) { it.url }
        val all = own.with(pages.filter { settings.isEnabled(it.source) && it.url !in quoted }, hasher)
        val song = all.without(hasher) { it.scope == Scope.ARTIST }
        if (!SongListings.listingOnly(song.lines)) return ItemEvidence(song, matching(song.lines, shown))
        val artist = artistLines(all, track).mapTo(mutableSetOf()) { it.id }
        val set = all.without(hasher) { it.scope == Scope.ARTIST && it.id !in artist }
        // No line at all, not even the release: nothing to write from, and nothing for the plain sentence
        // to be true of; no call, whatever is known of the artist.
        return if (song.isEmpty()) {
            ItemEvidence(song, emptyMap(), webDue = webDue)
        } else {
            ItemEvidence(set, matching(set.lines, shown), webDue = webDue, onlyListings = true)
        }
    }

    // [track]'s song as its web search is asked for and kept by (#958), in its language when one is
    // known, [settings] allowing, with this play's resolution for Wikidata's names in it.
    private fun songQuery(
        track: TrackState,
        settings: Settings,
    ): SongWebQuery {
        val language = track.knownLanguage()?.takeUnless { settings.trackLanguageSearch == TrackLanguageSearch.OFF }
        val resolution = track.resolution?.takeIf { settings.isEnabled(SourceId.WIKIDATA) }
        return SongWebQuery(track.normalized, language, resolution)
    }

    // The artist-level lines of the run's [own] evidence, numbered on their own, those in the track's
    // language kept for the artist with them ([inLanguage], #939); with none, while *AI web search* is
    // used, the web's lines kept for the artist ([artistWeb], #912), a search due while none is kept.
    // A store that throws keeps nothing, so a search is due. Not while a source that gives
    // artist-level lines (or MusicBrainz, whose resolution names the artist they look up) could not be
    // asked ([unanswered]): it may have something on the artist, so no search is due, and the evidence
    // is unreachable, gathered again, as an album's is when a source fails.
    private suspend fun artistEvidence(
        own: EvidenceSet,
        shown: EvidenceSet,
        track: TrackState,
        settings: Settings,
    ): ItemEvidence {
        val query = artistQuery(track)
        val (english, found) = artistLines(own, track).partition { it.found == null }
        val subject = query?.let { LanguageSubject.Artist(it.name, it.mbid) }
        val lines = english.map(::draftOf) + inLanguage(subject, found, settings)
        val lookup = artistWeb?.takeIf { lines.isEmpty() && usesWebSearch(settings) }
        return when {
            lookup == null || query == null -> numbered(lines, shown)
            unanswered(track, settings) ->
                ItemEvidence(EvidenceSet.of(emptyList(), hasher), emptyMap(), unreachable = true)
            else -> {
                val kept = attempt { lookup.kept(query) }
                val set = EvidenceSet.of(kept.orEmpty().filter { settings.isEnabled(it.source) }, hasher)
                ItemEvidence(set, matching(set.lines, shown), webDue = kept == null)
            }
        }
    }

    /**
     * Whether [item]'s evidence under [settings] may hold the web's lines a search for it keeps, or
     * call for one: the artist's (#912), with *AI web search* used and a lookup to keep and search
     * them ([artistWeb], an engine's key); the song's likewise (#958, [songWeb]); the album's (#935),
     * with web search switched on and an album lookup that can search ([AlbumLookup.searchesWeb], an
     * engine's key). Its evidence is gathered again when this changes.
     */
    fun searchesWeb(
        item: InsightItem,
        settings: Settings,
    ): Boolean =
        when (item) {
            InsightItem.ARTIST -> artistWeb != null && usesWebSearch(settings)
            InsightItem.ALBUM -> albums?.searchesWeb == true && settings.isEnabled(SourceId.WEB)
            InsightItem.SONG -> songWeb != null && usesWebSearch(settings)
        }

    // Whether the artist (#912) or the song (#958) may be searched for under [settings]: *AI web
    // search* used, which needs *Exa web search* on.
    private fun usesWebSearch(settings: Settings): Boolean = settings.aiWebSearchUsed != AiWebSearch.OFF

    // How the artist's or the song's search is made under [settings] (#912, #958): [AiWebSearch.UP_TO] by
    // the AI service itself, while it writes, with a service whose client takes tools and the engine
    // it searches with ([WebQuestions.used]); else [AiWebSearch.ONCE], the app's own search;
    // [AiWebSearch.OFF] for none.
    private fun webWay(settings: Settings): AiWebSearch {
        val access = ai
        return when {
            !usesWebSearch(settings) -> AiWebSearch.OFF
            access != null && WebQuestions(access, web).used(settings.aiWebSearchUsed) == AiWebSearch.UP_TO ->
                AiWebSearch.UP_TO
            else -> AiWebSearch.ONCE
        }
    }

    // The artist of [track]'s artist's card, as its web lines are searched for and kept by (#912).
    private fun artistQuery(track: TrackState): ArtistWebQuery? =
        (entity(InsightItem.ARTIST, track) as? CardEntity.Artist)
            ?.takeIf { it.name.isNotBlank() }
            ?.let { ArtistWebQuery(it.name, it.mbid) }

    // The evidence gathered for [album], by [albums], from the sources [settings] switch on: not the
    // counts and lists of what many listeners did (`EvidenceDraft.aggregate`), as the artist's leaves
    // them out ([artistLines]), so the album's fingerprint does not change with them (#885). With the
    // lines the search in the track's language found on the album's own article (#902), those of the
    // run's [track] evidence about a release named as the album is ([albumInLanguage]), after the
    // album's, kept for the album with those found on its other tracks ([inLanguage], #939).
    private suspend fun albumEvidence(
        album: CardEntity.Album,
        own: EvidenceSet,
        settings: Settings,
        track: EvidenceSet,
    ): ItemEvidence {
        val query = queryOf(album, settings)
        val found = albums?.let { attempt { it.lookup(query) } ?: AlbumFound(emptyList(), unreachable = true) }
        val subject = LanguageSubject.Album(album.title, album.artist, album.group)
        val drafts =
            found?.drafts.orEmpty().filter { settings.isEnabled(it.source) && !it.aggregate } +
                inLanguage(subject, albumInLanguage(album, track), settings)
        val lines = EvidenceSet.of(drafts, hasher)
        return ItemEvidence(
            lines,
            matching(lines.lines, own),
            unreachable = found?.unreachable == true,
            webDue = found?.webDue == true,
        )
    }

    // The lines of the track's [evidence] found in its language (#902) on the article of a release
    // (a film's or an album's) named as [album] is, by its English name ([EvidenceLine.about]), its
    // editions aside: the same lines whichever of the album's tracks was searched.
    private fun albumInLanguage(
        album: CardEntity.Album,
        evidence: EvidenceSet,
    ): List<EvidenceLine> {
        val keys = setOf(names.albumKey(album.title), names.albumKey(Edition.strip(album.title))) - ""
        return evidence.lines.filter { line ->
            line.found != null &&
                line.scope == Scope.RELEASE &&
                line.about?.let { names.albumKey(it) in keys } == true
        }
    }

    // [album] as [albums] is asked about it, with the sources [settings] switch on.
    private fun queryOf(
        album: CardEntity.Album,
        settings: Settings,
    ) = AlbumQuery(
        album.title,
        album.artist,
        album.group,
        musicBrainz = settings.isEnabled(SourceId.MUSICBRAINZ),
        wikipedia = settings.isEnabled(SourceId.WIKIPEDIA),
        lastFm = settings.isEnabled(SourceId.LASTFM),
        web = settings.isEnabled(SourceId.WEB),
    )

    /**
     * [search]'s evidence, when it found or reused lines; null otherwise.
     */
    suspend fun searched(
        item: InsightItem,
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
        sent: () -> Unit = {},
    ): ItemEvidence? = (search(item, track, settings, evidence, sent) as? ItemSearch.Found)?.evidence

    /**
     * [evidence], [item]'s on [track] under [settings], with the lines of its web search, for a write
     * of its insight only: the search is paid, so it is made here, never when the evidence is
     * gathered ([evidence]), and only while it is due ([ItemEvidence.webDue]) and [settings], which
     * the caller reads right before, allow it (the lookups' engines and keys are the parts' then).
     * What it found is kept, so the evidence gathered later holds the same lines, and the insight
     * written from them is found again by its key. [sent] is told each time a request goes out to
     * the engine.
     *
     * - **The album's** (#885): with web search switched on ([AlbumQuery.web]), through
     *   [AlbumLookup.searchWeb]; [ItemSearch.None] when none could be made (it failed, or the lookup
     *   threw), and the insight is written from [evidence] as it is. [reserve] is asked for the key
     *   the lines a search made now give, before they are kept (#935), which the caller claims then;
     *   so for the artist's *Once* search.
     * - **The artist's** (#912): with *AI web search* used, the lines kept for the artist since the
     *   evidence was gathered (another track's write), with no search; else under *Once* (or *Up to 3
     *   times* with a service whose client takes no tools), the app's own search for the artist
     *   ([ArtistWebLookup.search]), [ItemSearch.Failed] when it fails, which keeps nothing. Under *Up
     *   to 3 times*, [ItemSearch.None]: the AI service searches itself while it writes ([writeSearching]).
     * - **The song's** (#958): as the artist's, through [SongWebLookup].
     *
     * Either way, [mayPay] is asked after the lookup's waits (its kept lines read, another write's
     * search), right before the request: closed, none is sent, and [ItemSearch.Refused] (#912's
     * review; the album's, #935).
     */
    @Suppress("LongParameterList") // The item, what it is searched for from and under, and its gates.
    suspend fun search(
        item: InsightItem,
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
        sent: () -> Unit = {},
        reserve: (CardKey) -> Boolean = { true },
        mayPay: () -> Boolean = { true },
    ): ItemSearch =
        when {
            !evidence.webDue -> ItemSearch.None
            item == InsightItem.ALBUM -> albumSearch(track, settings, evidence, sent, WriteGates(mayPay, reserve))
            item == InsightItem.ARTIST -> artistSearch(track, settings, evidence, sent, WriteGates(mayPay, reserve))
            else -> songSearch(track, settings, evidence, sent, WriteGates(mayPay, reserve))
        }

    // The album's web search for a write (#885), as [search] says: made only while [gates]' `mayPay`,
    // asked right before its request, says so, [ItemSearch.Refused] when it did not; the key the lines
    // found give reserved before they are kept (#935). A reservation refused (another write of that
    // key under way) keeps them still: the same lines, which that write is written from.
    private suspend fun albumSearch(
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
        sent: () -> Unit,
        gates: WriteGates,
    ): ItemSearch {
        val lookup = albums?.takeIf { settings.isEnabled(SourceId.WEB) }
        val album = entity(InsightItem.ALBUM, track) as? CardEntity.Album
        var refused = false
        val mayPay = { gates.mayPay().also { if (!it) refused = true } }
        val keeping = reserving(InsightItem.ALBUM, track, settings, evidence, gates)
        val found =
            if (lookup != null && album != null) {
                attempt { lookup.searchWeb(queryOf(album, settings), mayPay, keeping, sent) }
            } else {
                null
            }
        return when {
            found != null -> withWeb(track, settings, evidence, found)?.let(ItemSearch::Found) ?: ItemSearch.None
            refused -> ItemSearch.Refused
            else -> ItemSearch.None
        }
    }

    // What a search for [item] is given, right before the lines it found are kept: the key they give
    // [evidence] reserved through [gates] (#935), so the write claims it before another track can
    // gather them. A reservation refused keeps them still.
    private fun reserving(
        item: InsightItem,
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
        gates: WriteGates,
    ): (List<EvidenceDraft>) -> Unit =
        { lines ->
            webEvidence(item, track, settings, evidence, lines)
                ?.let { key(item, track, it, settings) }
                ?.let { gates.reserve(it) }
        }

    // The artist's web lines for a write (#912), as [search] says.
    private suspend fun artistSearch(
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
        sent: () -> Unit,
        gates: WriteGates,
    ): ItemSearch {
        val lookup = artistWeb
        val query = artistQuery(track)
        if (lookup == null || query == null) return ItemSearch.None
        val ways = WebWays({ lookup.kept(query) }) { keeping, mayPay -> lookup.search(query, sent, keeping, mayPay) }
        return keptOrSearched(InsightItem.ARTIST, track, settings, evidence, gates, ways)
    }

    // The song's web lines for a write (#958), as [search] says.
    private suspend fun songSearch(
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
        sent: () -> Unit,
        gates: WriteGates,
    ): ItemSearch {
        val lookup = songWeb ?: return ItemSearch.None
        val query = songQuery(track, settings)
        val ways = WebWays({ lookup.kept(query) }) { keeping, mayPay -> lookup.search(query, sent, keeping, mayPay) }
        return keptOrSearched(InsightItem.SONG, track, settings, evidence, gates, ways)
    }

    // Where an item's web lines come from: those [kept], or a [search] of the app's.
    private class WebWays(
        val kept: suspend () -> List<EvidenceDraft>?,
        val search: suspend ((List<EvidenceDraft>) -> Unit, () -> Boolean) -> List<EvidenceDraft>?,
    )

    // [item]'s web lines for a write: kept, or *Once*'s search, made only while [gates]' `mayPay`, asked
    // right before its request, says so; [ItemSearch.Refused] when it did not; the key its pages give
    // reserved before they are kept (#935), as the album's.
    @Suppress("LongParameterList") // The item, what it is searched for from and under, and how.
    private suspend fun keptOrSearched(
        item: InsightItem,
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
        gates: WriteGates,
        ways: WebWays,
    ): ItemSearch {
        val way = webWay(settings)
        if (way == AiWebSearch.OFF) return ItemSearch.None
        var refused = false
        val kept = attempt { ways.kept() }
        val found =
            when {
                kept != null -> kept
                // The AI service searches itself while it writes ([writeSearching]).
                way == AiWebSearch.UP_TO -> null
                else -> {
                    val keeping = reserving(item, track, settings, evidence, gates)
                    attempt { ways.search(keeping) { gates.mayPay().also { if (!it) refused = true } } }
                }
            }
        return when {
            found != null ->
                webEvidence(item, track, settings, evidence, found)?.let(ItemSearch::Found) ?: ItemSearch.None
            way == AiWebSearch.UP_TO -> ItemSearch.None
            refused -> ItemSearch.Refused
            else -> ItemSearch.Failed
        }
    }

    /**
     * Whether [item]'s insight, from [evidence] under [settings], is written while the AI service
     * searches the web itself ([writeSearching], #912): the artist's, its search due, with *AI web
     * search* set to *Up to 3 times*, a service whose client takes tools, and the engine to search
     * with; the song's likewise (#958).
     */
    fun searchesItself(
        item: InsightItem,
        evidence: ItemEvidence,
        settings: Settings,
    ): Boolean =
        evidence.webDue &&
            webWay(settings) == AiWebSearch.UP_TO &&
            when (item) {
                InsightItem.ARTIST -> artistWeb != null
                InsightItem.SONG -> songWeb != null
                InsightItem.ALBUM -> false
            }

    /**
     * The artist's insight on [track], [first]'s, written while the AI service searches the web for
     * the artist itself (#912, *Up to 3 times*: [searchesItself]), from [evidence], which has no line
     * about the artist: the artist's prompt with the search tool ([ArtistQuestion]), asked by
     * [WebQuestions] with at most [Settings.AI_WEB_SEARCH_LIMIT] searches and one call more, each
     * counted to [counted] as it is made. Each search's pages that name the artist are evidence lines,
     * numbered from `S1`, which the reply cites; once it ends, they are kept for the artist
     * ([ArtistWebLookup.keep], none included, when a search was made), and are the artist's evidence
     * ([SearchedWrite.evidence], no search due), so the reply is checked against them, as any item's,
     * and a complete one is kept under [first]'s key with their fingerprint ([SearchedWrite.key]),
     * which a later gathering of the artist finds again; the lines are kept again with it
     * ([ArtistWebLookup.renew]). A reply that revises itself away fails (no second loop: a tap writes
     * it again from the pages kept, with the plain prompt). When every search failed, nothing is kept,
     * the evidence stays as it was, a search due, and the write fails ([AiSearches.WEB_SEARCH_FAILED]).
     * [rewriting] as [write]'s. Nothing is shown while it runs: the reply comes whole, at the end of
     * the loop.
     *
     * [gates] (#912's review): [WriteGates.mayPay] is asked right before each search and each call,
     * with no suspension between; once it says no, nothing more is paid for, nothing is kept, and
     * the write ends with null. [WriteGates.reserve] is asked for the key the pages give, before they
     * are kept, so the caller claims it while the pages and the card are kept: another track that
     * gathers the pages meanwhile finds the key being written, rather than writing it again; told no
     * (another write of that key under way), nothing is kept, and the write ends with null.
     *
     * Null too when it does not apply ([searchesItself] false, or no entity).
     *
     * The song's (#958) is written the same way, its prompt the song's with its rule on listings, from
     * [evidence], which has the lines that list it and the artist's ([SongQuestion]): each search's pages
     * about the song are numbered after the track's own lines, and kept for the track
     * ([SongWebLookup.keep]); its evidence then is what they give ([songWith]), a card's key with it.
     * When a description was found, that evidence no longer has the artist's lines the model was given:
     * a sentence citing one, and the plain sentence, are taken out before the reply is read and kept
     * ([withoutCiting]), with no further call. With nothing left, the write fails as for
     * an empty reply, no card is kept, the pages are, and *Retry* writes it from them, with no search.
     * A complete reply with no search made keeps "found nothing" for the song, so no search stays due.
     */
    @Suppress("LongParameterList") // The insight, what it is about and from, and where its cost goes.
    suspend fun writeSearching(
        first: CardKey,
        item: InsightItem,
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
        counted: (QuestionCost) -> Unit = {},
        rewriting: WrittenBy? = null,
        gates: WriteGates = WriteGates(),
    ): SearchedWrite? {
        val searching = searching(first, item, track, settings, evidence) ?: return null
        val question = searching.question
        val base = CompletionRequest(first.model, "", CompletionRequest.maxTokensFor(first.effort), first.effort)
        val questions = WebQuestions(searching.access, searching.engine)
        val reply =
            questions.ask(question, AiWebSearch.UP_TO, Settings.AI_WEB_SEARCH_LIMIT, base, counted, gates.mayPay)
        return when {
            reply.refused -> null
            reply.searchFailed -> SearchedWrite(evidence, first, InsightState.Failed(AiSearches.WEB_SEARCH_FAILED))
            else ->
                searched(
                    first,
                    Asked(item, track, settings, evidence, rewriting),
                    searching,
                    reply,
                    gates,
                )
        }
    }

    // What [writeSearching] asks with: the AI service, the engine its searches go to, the question, and
    // where the pages it numbered are kept.
    private class Searching(
        val access: AiAccess,
        val engine: WebSearcher,
        val question: ItemWebQuestion,
        val keep: suspend (List<EvidenceDraft>) -> Unit,
    )

    // What [writeSearching] asks with for [item] on [track], when it applies ([searchesItself]).
    @Suppress("LongParameterList") // The key, the item, what it is about and from.
    private suspend fun searching(
        first: CardKey,
        item: InsightItem,
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
    ): Searching? {
        val access = ai?.takeIf { searchesItself(item, evidence, settings) }
        val engine = web
        val name = name(item, track)
        if (access == null || engine == null || name == null) return null
        val subject = ItemPrompt.Subject(item.kind, name)
        return when (item) {
            InsightItem.ARTIST -> artistQuestion(subject, first, track)
            else -> songQuestion(subject, first, track, settings, evidence)
        }?.let { (question, keep) -> Searching(access, engine, question, keep) }
    }

    // The artist's question (#912), and where its pages are kept.
    private fun artistQuestion(
        subject: ItemPrompt.Subject,
        first: CardKey,
        track: TrackState,
    ): Pair<ItemWebQuestion, suspend (List<EvidenceDraft>) -> Unit>? {
        val lookup = artistWeb
        val query = artistQuery(track)
        if (lookup == null || query == null) return null
        val line = { page: WebPage -> lookup.pageLine(query, page) }
        val question = ArtistQuestion(subject, track, first.modelKnowledge, names, hasher, now(), line, dates())
        return question to { lookup.keep(query, it) }
    }

    // The song's question (#958), and where its pages are kept.
    @Suppress("LongParameterList") // What it is about, its key, and what it is from.
    private suspend fun songQuestion(
        subject: ItemPrompt.Subject,
        first: CardKey,
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
    ): Pair<ItemWebQuestion, suspend (List<EvidenceDraft>) -> Unit>? {
        val lookup = songWeb
        val own = ownEvidence(track, settings)
        if (lookup == null || own == null) return null
        val query = songQuery(track, settings)
        val at = now()
        val lines = lookup.pageLines(query)
        val question =
            SongQuestion(
                subject,
                track,
                first.modelKnowledge,
                evidence,
                own,
                hasher,
                query.language?.name,
                { page -> lines(page, at) },
                dates(),
            )
        return question to { lookup.keep(query, it) }
    }

    // What [writeSearching] was asked to write: the item on its track, under its settings, from its
    // evidence, and what it rewrites, if anything.
    private class Asked(
        val item: InsightItem,
        val track: TrackState,
        val settings: Settings,
        val evidence: ItemEvidence,
        val rewriting: WrittenBy?,
    )

    // [asked]'s insight once the AI service's searches ended in [reply] (#912, [writeSearching]): the
    // key the pages [question] numbered give reserved ([WriteGates.reserve]; null when another write
    // has it), those pages kept for the artist, "found nothing" included, when a search was made, the
    // artist's evidence then, and the reply read against it, kept under [first]'s key with its
    // fingerprint when complete, with the lines kept again. With no search made and a complete reply
    // of `NO_EVIDENCE`, "found nothing" is kept too, as for a search that found none: else a search
    // would stay due, and every later write would pay for the loop again.
    @Suppress("LongParameterList") // What was asked, how it was searched, and what came back.
    private suspend fun searched(
        first: CardKey,
        asked: Asked,
        searching: Searching,
        reply: WebReply,
        gates: WriteGates,
    ): SearchedWrite? {
        val drafts = searching.question.drafts()
        val searched = reply.cost.searches > 0
        val found =
            if (searched) webEvidence(asked.item, asked.track, asked.settings, asked.evidence, drafts) else null
        val lines = (found ?: asked.evidence).evidence
        // The lines the model was given that the evidence no longer has (#958: the artist's, once a
        // description was found): a sentence citing one goes, before the reply is read and kept.
        val text = withoutGone(reply.text, asked.evidence, found ?: asked.evidence)
        // Written by [first]'s service, model and effort, from the lines found.
        val key =
            first.copy(
                evidenceFingerprint = lines.fingerprint,
                promptVersion = promptVersion(found ?: asked.evidence),
            )
        if (!gates.reserve(key)) return null
        val rewritten = asked.rewriting?.let { rewriteParts(it, key.writtenBy()) }.orEmpty()
        val read = outcome(reply.end, text, key, lines, null, rewritten)
        val nothing = !searched && foundNothing(asked.item, read)
        if (searched || nothing) attempt { searching.keep(drafts) }
        // With "found nothing" kept, the item's evidence has no search due.
        val none =
            if (nothing) webEvidence(asked.item, asked.track, asked.settings, asked.evidence, emptyList()) else null
        val evidence = found ?: none ?: asked.evidence
        return SearchedWrite(evidence, key, kept(read, key, text, rewritten, asked))
    }

    // [text] as written from [now], the lines it was asked from having been [was] (#958): without the
    // sentences citing a line gone, nor the plain sentence once a line is read as describing the song.
    private fun withoutGone(
        text: String,
        was: ItemEvidence,
        now: ItemEvidence,
    ): String {
        val gone = was.evidence.ids.toSet() - now.evidence.ids.toSet()
        return withoutCiting(text, gone, was.onlyListings && !now.onlyListings)
    }

    // Whether [read], a reply with no search made, keeps "found nothing" for [item]: a complete one of
    // `NO_EVIDENCE`; for the song, any complete one (#958), whose card has the key it would have with
    // none kept, so a search left due would have that card set aside, and written and paid for, at
    // every play.
    private fun foundNothing(
        item: InsightItem,
        read: Reply,
    ): Boolean = read is Reply.Read && read.kept && (read.state == InsightState.NoEvidence || item == InsightItem.SONG)

    // The state [read], [key]'s reply [text] as read, comes to (a reply that revised itself away
    // fails), kept when complete, with the item's web lines kept again, at the time it is shown with.
    @Suppress("LongParameterList") // The reply as read and as written, its key, and what it was asked.
    private suspend fun kept(
        read: Reply,
        key: CardKey,
        text: String,
        rewritten: Set<RewritePart>,
        asked: Asked,
    ): InsightState {
        val at = now()
        return when (read) {
            is Reply.RevisedAway -> InsightState.Failed(ReplyFailures.SELF_REVISED)
            is Reply.Read -> {
                if (read.kept) {
                    attempt { cache.keep(key, keptText(text, read.state), at, rewritten) }
                    renewWeb(asked.item, asked.track, asked.evidence.evidence)
                }
                (read.state as? InsightState.Written)?.copy(writtenAt = at) ?: read.state
            }
        }
    }

    /**
     * [evidence], the album's (or the artist's, #912) gathered on [track] under [settings] while its
     * web search was due, with the web's lines of [searched], its evidence that search gave on another
     * of its tracks (#885), with no search: numbered as the item's lines are, each id mapped to
     * [track]'s own line where it is one, so its insight has the key the other track's write keeps it
     * under. Null before [track]'s evidence is in.
     */
    fun withWebOf(
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
        searched: ItemEvidence,
    ): ItemEvidence? =
        withWeb(
            track,
            settings,
            evidence,
            searched.evidence.lines
                .filter { it.source == SourceId.WEB }
                .map(::draftOf),
        )

    // [item]'s [evidence] on [track] with the web's [lines]: as the song's own is made ([songWith]), the
    // others' [withWeb].
    @Suppress("LongParameterList") // The item, what it is from and under, and what is added.
    private fun webEvidence(
        item: InsightItem,
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
        lines: List<EvidenceDraft>,
    ): ItemEvidence? {
        if (item != InsightItem.SONG) return withWeb(track, settings, evidence, lines)
        val own = ownEvidence(track, settings)
        val shown = track.evidence
        return if (own == null || shown == null) null else songWith(own, shown, track, settings, lines)
    }

    // [evidence] on [track] with the web's [lines], those [settings] switch on.
    private fun withWeb(
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
        lines: List<EvidenceDraft>,
    ): ItemEvidence? {
        val shown = track.evidence ?: return null
        val more = lines.filter { settings.isEnabled(it.source) && !it.aggregate }
        val set = EvidenceSet.of(evidence.evidence.lines.map(::draftOf) + more, hasher)
        return ItemEvidence(set, matching(set.lines, shown), unreachable = evidence.unreachable)
    }

    // The artist-level lines of the track's [own] evidence about the primary artist: all of them when
    // [track] credits one; when it credits several, those its adapter says are about the primary
    // artist ([EvidenceLine.about]), so a collaboration gives the lines a solo track does, whatever
    // their text names. A line that does not say (kept before #522) is taken when it names the artist.
    private fun artistLines(
        own: EvidenceSet,
        track: TrackState,
    ): List<EvidenceLine> {
        // Not the counts and lists of what many listeners did (Last.fm's listeners, tags, similar
        // artists: `EvidenceLine.aggregate`), whose numbers and order change from one lookup to the
        // next: in the artist's fingerprint, they would key the same artist anew on each.
        val lines = own.lines.filter { it.scope == Scope.ARTIST && !it.aggregate }
        val primary = track.normalized.primary
        if (track.normalized.artists.size <= 1) return lines
        val key = " ${names.key(primary)} "
        return lines.filter { line ->
            line.about?.let { names.sameName(it, primary) } ?: (key.isNotBlank() && key in " ${names.key(line.text)} ")
        }
    }

    /**
     * Whether [item]'s evidence under [settings] holds the lines found in the track's language (#902,
     * #939): the artist's and the album's, but under *Search in the track's language: Off*. Its
     * evidence is gathered again when this changes, and when *Keep what sources said about each
     * artist* does, which says where they are kept ([ItemLanguageLines]).
     */
    fun takesInLanguage(
        item: InsightItem,
        settings: Settings,
    ): Boolean = item != InsightItem.SONG && settings.trackLanguageSearch != TrackLanguageSearch.OFF

    // The lines in the track's language for [subject]'s evidence (#902, #939), from the sources
    // [settings] switch on: those [found] on this track kept for it, each language's only while none is
    // kept, then every language's kept for it ([languageLines]), with those found here in a language
    // none is kept in after (a store that throws). A language kept gives its kept lines alone, even
    // where this track found others, so every track of the artist or album gives the same. None
    // under *Off*. Without [languageLines] (the CLI, tests), those found here.
    private suspend fun inLanguage(
        subject: LanguageSubject?,
        found: List<EvidenceLine>,
        settings: Settings,
    ): List<EvidenceDraft> {
        if (settings.trackLanguageSearch == TrackLanguageSearch.OFF) return emptyList()
        val drafts = found.map(::draftOf)
        val lookup = languageLines
        val kept =
            if (lookup == null || subject == null) {
                emptyList()
            } else {
                keep(lookup, subject, drafts)
                attempt { lookup.kept(subject) }.orEmpty()
            }
        val keptIn = kept.mapNotNullTo(mutableSetOf()) { it.language }
        val more = drafts.filter { it.language !in keptIn }
        return (kept + more).filter { settings.isEnabled(it.source) }
    }

    // [drafts], found in the track's language, kept for [subject] in [lookup], each language's apart.
    private suspend fun keep(
        lookup: ItemLanguageLines,
        subject: LanguageSubject,
        drafts: List<EvidenceDraft>,
    ) = drafts.groupBy { it.language }.forEach { (language, lines) ->
        language?.let { attempt { lookup.keep(subject, it, lines) } }
    }

    /**
     * Keeps what the search in [track]'s language found about its artist and its album (#939), as
     * their evidence ([evidence]) would, under [settings]: the artist-level lines about the primary
     * artist, and those found on the article of a release named as the album is; for when the track's
     * evidence is in, whether or not the artist's or the album's insight is opened, so the next of
     * their tracks finds them. Nothing under *Off*, without [languageLines], or before the track's
     * evidence is in.
     */
    suspend fun keepInLanguage(
        track: TrackState,
        settings: Settings,
    ) {
        val lookup = languageLines ?: return
        val own = ownEvidence(track, settings)
        if (own == null || settings.trackLanguageSearch == TrackLanguageSearch.OFF) return
        artistQuery(track)?.let { query ->
            val found = artistLines(own, track).filter { it.found != null }
            keep(lookup, LanguageSubject.Artist(query.name, query.mbid), found.map(::draftOf))
        }
        (entity(InsightItem.ALBUM, track) as? CardEntity.Album)?.let { album ->
            val found = albumInLanguage(album, own)
            keep(lookup, LanguageSubject.Album(album.title, album.artist, album.group), found.map(::draftOf))
        }
    }

    // [lines], numbered on their own, with each id mapped to its line's id in the track's [own] evidence.
    private fun numbered(
        lines: List<EvidenceDraft>,
        own: EvidenceSet,
    ): ItemEvidence {
        val set = EvidenceSet.of(lines, hasher)
        return ItemEvidence(set, matching(set.lines, own))
    }

    // [line] as a draft, to be numbered again; without a language read from its script (#949), which
    // the item's own set reads again from its own lines, so it is the same on each of the item's
    // tracks, whether or not the track was searched in its language.
    private fun draftOf(line: EvidenceLine): EvidenceDraft =
        line.draft().let { if (it.scripted) it.copy(language = null, scripted = false) else it }

    // Each of [lines]' ids whose line is in [own], the track's evidence, by its id there.
    private fun matching(
        lines: List<EvidenceLine>,
        own: EvidenceSet,
    ): Map<String, String> {
        val byLine = own.lines.associate { Triple(it.source, it.scope, it.text) to it.id }
        return lines
            .mapNotNull { line ->
                byLine[Triple(line.source, line.scope, line.text)]?.let { line.id to it }
            }.toMap()
    }

    /**
     * The key of [item]'s insight on [track] under [settings], written from [evidence], or null when
     * none may be written: *Show insights* off, no AI service ready, or no entity ([entity]).
     */
    fun key(
        item: InsightItem,
        track: TrackState,
        evidence: ItemEvidence,
        settings: Settings,
    ): CardKey? {
        // The service the parts were built with, while it is the one selected: a switch to another
        // waits for the rebuild that brings it.
        val access = ai?.takeIf { settings.writeInsights && settings.aiProvider == it.provider.id }
        return access?.let { keyFor(item, track, evidence, settings, it.provider.id) }
    }

    /**
     * The key [item]'s insight on [track] is kept under, under [settings], whether or not it may be
     * written now (#911): [key]'s while an AI service is ready; with none because the selected model
     * is found unavailable ([modelUnavailable], #540, `PipelineParts.modelUnavailable`), the one the
     * service and model selected would write under, so the insight kept for them, or another model's
     * in its place, still shows, though nothing is written, as the key insight's does. Null with *Show
     * insights* off, no service or model selected, no entity, no AI service for another reason (no
     * key), or while the parts' service is not yet the one selected (a switch waits for its rebuild).
     */
    fun keptKey(
        item: InsightItem,
        track: TrackState,
        evidence: ItemEvidence,
        settings: Settings,
    ): CardKey? {
        val provider = settings.aiProvider?.takeIf { settings.writeInsights && modelUnavailable }
        return when {
            ai != null -> key(item, track, evidence, settings)
            provider == null -> null
            else -> keyFor(item, track, evidence, settings, provider)
        }
    }

    // [item]'s key on [track] from [evidence], with [provider]'s model and effort under [settings].
    private fun keyFor(
        item: InsightItem,
        track: TrackState,
        evidence: ItemEvidence,
        settings: Settings,
        provider: ProviderId,
    ): CardKey? {
        val model = settings.model(provider)
        val entity = entity(item, track)
        return if (model == null || entity == null) {
            null
        } else {
            CardKey(
                kind = item.kind,
                entity = entity,
                evidenceFingerprint = evidence.evidence.fingerprint,
                promptVersion = promptVersion(evidence),
                styleHash = styleHash(hasher),
                provider = provider,
                model = model,
                modelKnowledge = settings.isEnabled(SourceId.MODEL),
                effort = settings.effort(provider),
            )
        }
    }

    // The version of the prompt a card from [evidence] is written with, part of its key.
    private fun promptVersion(evidence: ItemEvidence): Int =
        if (evidence.onlyListings) ItemPrompt.ONLY_LISTINGS_VERSION else ItemPrompt.VERSION

    /** Whether an insight may be written now: an AI service ready (none while its model is unavailable, #540). */
    val writes: Boolean get() = ai != null

    /**
     * The insight kept for [key], checked against [evidence], with when it was written where the
     * cache says; null when none is kept, or the one kept revises itself with nothing usable left
     * (#798, read as the key insight's is: [readReply]).
     */
    suspend fun kept(
        key: CardKey,
        evidence: ItemEvidence,
    ): KeptItem? {
        val card = attempt { cache.keptCard(key) } ?: return null
        val reply = readReply(card.text, evidence.evidence, key.modelKnowledge)
        return reply?.let {
            KeptItem(stateOf(it, key, cached = true, usage = null, complete = true, card.rewritten), card.writtenAt)
        }
    }

    /**
     * The insight kept for what [key]'s is about, from the same [evidence], written with another AI
     * service, model or effort (#911, [PipelineCache.keptByOthers]): the most recently written that
     * reads as an insight, checked as [kept]'s, its state written by its own key, so its byline names
     * the model that wrote it; null for none. What a row shows in place of none under [key], with
     * *Rewrite insight* offered beside it, and what a rewrite then replaces ([write]'s `rewriting`).
     */
    suspend fun keptByOther(
        key: CardKey,
        evidence: ItemEvidence,
    ): KeptItem? {
        val others = attempt { cache.keptByOthers(key) }.orEmpty()
        return others.firstNotNullOfOrNull { other ->
            val reply = readReply(other.card.text, evidence.evidence, key.modelKnowledge) as? InsightReply.Written
            reply?.let {
                val state = stateOf(it, other.key, cached = true, usage = null, complete = true, other.card.rewritten)
                KeptItem(state, other.card.writtenAt)
            }
        }
    }

    /**
     * Writes [key]'s insight about [item] on [track] from [evidence], each state as it comes to
     * [show] with when it came (by the clock, in whole milliseconds as the cache keeps it); returns
     * the last, or null when [mayCall] closed before the call, which then is not made. [mayCall] is
     * asked right before each call, with no suspension between. With nothing to narrate, no call, and
     * [InsightState.NoEvidence]. A complete reply is kept in the cache, `NO_EVIDENCE` too, so the same
     * item asks nothing again, with the one time it is shown with, and, for the album's, its web lines
     * kept again with it ([AlbumLookup.renewWeb]), and for the artist's, the artist's
     * ([ArtistWebLookup.renew]), and for the song's, the song's ([SongWebLookup.renew]); a reply cut
     * off (a Stop cancels
     * the call) is neither shown nor kept. A reply that revises itself where it would show (#798) is
     * cut to what follows the revision when that is usable; else asked for once more, a second call,
     * and with nothing usable then either, it fails, as the key insight's does. While it streams, the
     * text shown stops at such a revision. [counted] is told of each call. [rewriting] is what wrote
     * the insight the write replaces, kept by another AI service, model or effort (*Rewrite insight*,
     * #911), null for none: the insight written then says what the rewrite changed
     * ([InsightState.Written.rewritten], [rewriteParts]), and keeps it with the card, so a later play
     * says it too.
     */
    @Suppress("LongParameterList") // The insight, what it is about and from, its gate, and where its states go.
    suspend fun write(
        key: CardKey,
        item: InsightItem,
        track: TrackState,
        evidence: ItemEvidence,
        mayCall: () -> Boolean = { true },
        counted: (QuestionCost) -> Unit = {},
        rewriting: WrittenBy? = null,
        show: (InsightState, Instant) -> Unit,
    ): InsightState? {
        val rewritten = rewriting?.let { rewriteParts(it, key.writtenBy()) }.orEmpty()
        val access = checkNotNull(ai) { "no AI service" }
        val lines = evidence.evidence
        if (lines.nothingToNarrate(key.modelKnowledge, artistsKnown = track.normalized.artists.isNotEmpty())) {
            return InsightState.NoEvidence.also { show(it, now()) }
        }
        val subject = ItemPrompt.Subject(item.kind, checkNotNull(name(item, track)))
        val prompt =
            ItemPrompt.build(
                subject,
                track.nowPlaying,
                track.normalized,
                lines,
                key.modelKnowledge,
                dates(),
                evidence.onlyListings,
            )
        val request = CompletionRequest(key.model, prompt, CompletionRequest.maxTokensFor(key.effort), key.effort)
        var billed: Usage? = null
        var calls = 0
        var last: InsightState? = null
        var asking = true
        while (asking) {
            asking = false
            // A second call, after a reply that revised itself, is not made once the gate has closed.
            if (!mayCall()) {
                last = if (calls == 0) null else revisedAway(show)
                continue
            }
            calls++
            show(InsightState.Writing("", model = key.model), now())
            counted(QuestionCost.CALL)
            // Its tries again (#1164, `AiRetry`) under the same gate, each counted.
            val (text, end) = underTerms(mayCall, { counted(QuestionCost.CALL) }) { stream(access, request, key, show) }
            val at = now()
            when (val reply = outcome(end, text, key, lines, billed, rewritten)) {
                is Reply.RevisedAway -> {
                    if (calls >= CALLS) last = revisedAway(show) else asking = true
                    billed = reply.usage
                }

                is Reply.Read -> {
                    if (reply.kept) {
                        attempt { cache.keep(key, keptText(text, reply.state), at, rewritten) }
                        renewWeb(item, track, lines)
                    }
                    val shown = (reply.state as? InsightState.Written)?.copy(writtenAt = at) ?: reply.state
                    last = shown.also { show(it, at) }
                }
            }
        }
        return last
    }

    // The album's or the artist's web lines kept again from now with [item]'s card on [track] written
    // from them (#885, [AlbumLookup.renewWeb]; #912, [ArtistWebLookup.renew]), and its lines in the
    // track's language (#939, [ItemLanguageLines.renew]) when the card's [evidence] holds some, so they
    // last as long as this card, the last one kept; the song's web lines likewise (#958); nothing when
    // none are kept.
    private suspend fun renewWeb(
        item: InsightItem,
        track: TrackState,
        evidence: EvidenceSet,
    ) {
        val album = entity(item, track) as? CardEntity.Album
        val lookup = albums
        if (album != null && lookup != null) {
            attempt { lookup.renewWeb(AlbumQuery(album.title, album.artist, album.group)) }
        }
        val artist = artistWeb
        val query = artistQuery(track)
        if (item == InsightItem.ARTIST && artist != null && query != null) attempt { artist.renew(query) }
        if (item == InsightItem.SONG) songWeb?.let { attempt { it.renew(SongWebQuery(track.normalized)) } }
        val subject =
            when (item) {
                InsightItem.ARTIST -> query?.let { LanguageSubject.Artist(it.name, it.mbid) }
                InsightItem.ALBUM -> album?.let { LanguageSubject.Album(it.title, it.artist, it.group) }
                InsightItem.SONG -> null
            }
        if (subject != null) renewInLanguage(subject, evidence)
    }

    // [subject]'s lines in the track's language kept again (#939) when [evidence] holds some.
    private suspend fun renewInLanguage(
        subject: LanguageSubject,
        evidence: EvidenceSet,
    ) {
        val lines = languageLines
        if (lines != null && evidence.lines.any { it.found != null }) attempt { lines.renew(subject) }
    }

    // One call of [request]: its text, each piece shown as it comes while it may not be `NO_EVIDENCE`
    // and has not revised itself (#798), and its end.
    private suspend fun stream(
        access: AiAccess,
        request: CompletionRequest,
        key: CardKey,
        show: (InsightState, Instant) -> Unit,
    ): Pair<String, CompletionEvent?> {
        val text = StringBuilder()
        var end: CompletionEvent? = null
        var frozen = false
        access.provider.stream(request, access.key).collect { event ->
            when (event) {
                is CompletionEvent.Delta -> {
                    text.append(event.text)
                    val now = text.toString()
                    frozen = frozen || SelfRevision.ofPartial(now).freezes()
                    if (!GroundingCheck.mayBeNoEvidence(now) && !frozen) {
                        show(InsightState.Writing(now, model = key.model), now())
                    }
                }

                // Done or Error: no tools are sent, so never a tool call.
                else -> {
                    end = event
                }
            }
        }
        return text.toString() to end
    }

    // A reply that revised itself twice with nothing usable left: failed, as the key insight's (#798).
    private fun revisedAway(show: (InsightState, Instant) -> Unit): InsightState =
        InsightState.Failed(ReplyFailures.SELF_REVISED).also { show(it, now()) }

    // What a reply came to: read, as a state and whether it is kept; or revised with nothing usable
    // left, with the tokens paid so far.
    private sealed interface Reply {
        class Read(
            val state: InsightState,
            val kept: Boolean,
        ) : Reply

        class RevisedAway(
            val usage: Usage,
        ) : Reply
    }

    // [end] and [text] read as [key]'s insight, checked against [evidence], counting the tokens of
    // the replies before it in this write ([billed]), written by a rewrite that changed [rewritten].
    @Suppress("LongParameterList") // The reply, its key and evidence, and what came before it.
    private fun outcome(
        end: CompletionEvent?,
        text: String,
        key: CardKey,
        evidence: EvidenceSet,
        billed: Usage?,
        rewritten: Set<RewritePart>,
    ): Reply =
        when (end) {
            is CompletionEvent.Done -> {
                val blank = ReplyFailures.ofBlank(end, text)
                val usage =
                    billed?.let {
                        Usage(
                            end.usage.inputTokens + it.inputTokens,
                            end.usage.outputTokens + it.outputTokens,
                        )
                    }
                        ?: end.usage
                val reply =
                    if (blank == null &&
                        end.stopReason != StopReason.REFUSAL
                    ) {
                        readReply(text, evidence, key.modelKnowledge)
                    } else {
                        null
                    }
                val complete = end.stopReason == StopReason.END
                when {
                    end.stopReason == StopReason.REFUSAL ->
                        Reply.Read(
                            InsightState.Failed(ReplyFailures.DECLINED),
                            kept = false,
                        )
                    // No text is no insight to show, nor to keep (#665).
                    blank != null -> Reply.Read(InsightState.Failed(blank), kept = false)
                    reply == null -> Reply.RevisedAway(usage)
                    else ->
                        Reply.Read(
                            stateOf(reply, key, cached = false, usage = usage, complete = complete, rewritten),
                            complete,
                        )
                }
            }

            is CompletionEvent.Error -> Reply.Read(InsightState.Failed(ReplyFailures.of(end)), kept = false)
            else -> Reply.Read(InsightState.Failed(ReplyFailures.INCOMPLETE), kept = false)
        }

    // What is kept of a complete reply [text] read as [state]: `NO_EVIDENCE` for one read as none.
    private fun keptText(
        text: String,
        state: InsightState,
    ): String = if (state == InsightState.NoEvidence) GroundingCheck.NO_EVIDENCE else text

    // [reply] as [key]'s state, written by a rewrite that changed [rewritten] (#911), none for one
    // written the first time.
    @Suppress("LongParameterList") // The reply, its key, and how it came.
    private fun stateOf(
        reply: InsightReply,
        key: CardKey,
        cached: Boolean,
        usage: Usage?,
        complete: Boolean,
        rewritten: Set<RewritePart> = emptySet(),
    ): InsightState =
        when (reply) {
            InsightReply.NoEvidence -> InsightState.NoEvidence
            is InsightReply.Written ->
                InsightState.Written(
                    reply.grounded,
                    cached,
                    usage,
                    complete,
                    writtenBy = key.writtenBy(),
                    rewritten = rewritten,
                )
        }

    // The player's album name, trimmed, or null.
    private fun album(track: TrackState): String? =
        track.nowPlaying.album
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    // The clock's time, in whole milliseconds, as the cache keeps it.
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

    // [call], or null when it throws anything but cancellation: a cache or source that fails is a miss.
    private suspend fun <T> attempt(call: suspend () -> T): T? =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            null
        }

    companion object {
        // A reply that revises itself is asked for once more, no further (#798).
        private const val CALLS = 2

        /** Whether [item]'s insight may be offered on [track]: its evidence in, every source answered. */
        fun offered(track: TrackState): Boolean = track.gathered && track.evidence != null
    }
}

// The sources whose answer the artist's web search waits for (#912): those that give artist-level
// lines, and MusicBrainz, whose resolution names the artist they look up.
private val ARTIST_SOURCES = setOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.LASTFM)

// The sources whose answer says whether the song's lines only list it (#958): those that may describe
// it, and the web's, which also makes its search.
private val SONG_SOURCES = ARTIST_SOURCES + SourceId.WEB

// Whether a source [settings] switch on of [sources] (by default, those the artist's lines come from,
// and MusicBrainz) ended on [track] unreachable (the network, the source), or failed in a way its
// adapter did not map.
private fun unanswered(
    track: TrackState,
    settings: Settings,
    sources: Set<SourceId> = ARTIST_SOURCES,
): Boolean =
    sources.any { source ->
        settings.isEnabled(source) &&
            when (val state = track.sources[source]) {
                is SourceState.Failed -> true
                is SourceState.Done -> state.result.outcome == Outcome.Unreachable
                else -> false
            }
    }
