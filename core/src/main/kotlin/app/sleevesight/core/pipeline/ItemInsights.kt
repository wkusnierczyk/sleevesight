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
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.AlbumFound
import app.sleevesight.core.sources.AlbumLookup
import app.sleevesight.core.sources.AlbumQuery
import app.sleevesight.core.sources.ArtistWebLookup
import app.sleevesight.core.sources.ArtistWebQuery
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.ItemPrompt
import app.sleevesight.core.synthesis.SelfRevision
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
 * when a web search is due before its insight is written: the album's (#885), or the artist's,
 * whose sources say nothing about the artist (#912) ([ItemInsights.search], [ItemInsights.writeSearching]).
 */
class ItemEvidence(
    val evidence: EvidenceSet,
    val trackIds: Map<String, String>,
    val unreachable: Boolean = false,
    val webDue: Boolean = false,
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

    /** The item's [evidence] with the lines its search found, or an earlier one kept. */
    class Found(
        val evidence: ItemEvidence,
    ) : ItemSearch
}

/**
 * The artist's insight written while the AI service searched the web for the artist (#912,
 * [ItemInsights.writeSearching]): the artist's [evidence] then, the pages found among it, the [key]
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
 *   evidence less its artist-level lines ([Scope.ARTIST]), each keeping the track's id;
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
 * Not written ([key] null) with *Show insights* off, no AI service ready, or before the track's
 * evidence is in. When the evidence for an item has nothing to narrate
 * ([EvidenceSet.nothingToNarrate]), no call is made, and the item says so
 * ([InsightState.NoEvidence]). A cache that throws is a miss, and a write that fails keeps
 * nothing. Each state written comes with its time by [clock]; a reply kept is kept with the time it
 * is shown with (#643). Each kept under its own model: one written with another AI service, model or
 * effort is never served as [key]'s, but is found apart ([keptByOther], #911), for a row to show it
 * with *Rewrite insight* beside it, and a rewrite keeps both.
 */
@Suppress(
    "LongParameterList", // Where items are written, kept, and gathered, and the time.
    "TooManyFunctions", // Each step of an item: its name, entity, evidence, key, read and write.
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
     * release line and each source's result, of the sources [settings] switch on now; null before
     * every source answered. Not [TrackState.evidence], which Home may show from an earlier card, kept
     * from before, in place of the run's, and which a source switched off since still holds.
     */
    fun ownEvidence(
        track: TrackState,
        settings: Settings,
    ): EvidenceSet? {
        if (!track.gathered || track.evidence == null) return null
        val results = track.sources.values.mapNotNull { (it as? SourceState.Done)?.result }
        val drafts = playerLines(track.nowPlaying, clock.now()) + results.flatMap { it.evidence }
        return EvidenceSet.of(drafts.filter { settings.isEnabled(it.source) }, hasher)
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
            item == InsightItem.SONG -> songEvidence(own, shown)
            item == InsightItem.ARTIST -> artistEvidence(own, shown, track, settings)
            album == null -> null
            else -> albumEvidence(album, shown, settings)
        }
    }

    // The run's [own] evidence less its artist-level lines, each keeping its id, mapped to the lines [shown].
    private fun songEvidence(
        own: EvidenceSet,
        shown: EvidenceSet,
    ): ItemEvidence {
        val song = own.without(hasher) { it.scope == Scope.ARTIST }
        return ItemEvidence(song, matching(song.lines, shown))
    }

    // The artist-level lines of the run's [own] evidence, numbered on their own; with none, while *AI
    // web search* is used, the web's lines kept for the artist ([artistWeb], #912), a search due
    // while none is kept. A store that throws keeps nothing, so a search is due.
    private suspend fun artistEvidence(
        own: EvidenceSet,
        shown: EvidenceSet,
        track: TrackState,
        settings: Settings,
    ): ItemEvidence {
        val lines = artistLines(own, track)
        val lookup = artistWeb?.takeIf { lines.isEmpty() && searchesArtist(settings) }
        val query = artistQuery(track)
        if (lookup == null || query == null) return numbered(lines, shown)
        val kept = attempt { lookup.kept(query) }
        val set = EvidenceSet.of(kept.orEmpty().filter { settings.isEnabled(it.source) }, hasher)
        return ItemEvidence(set, matching(set.lines, shown), webDue = kept == null)
    }

    /**
     * Whether [item]'s evidence under [settings] may hold the web's lines a search for it keeps, or
     * call for one (#912): the artist's, with *AI web search* used and a lookup to keep and search
     * them ([artistWeb], an engine's key). Its evidence is gathered again when this changes.
     */
    fun searchesWeb(
        item: InsightItem,
        settings: Settings,
    ): Boolean = item == InsightItem.ARTIST && artistWeb != null && searchesArtist(settings)

    // Whether the artist may be searched for under [settings] (#912): *AI web search* used, which
    // needs *Exa web search* on.
    private fun searchesArtist(settings: Settings): Boolean = settings.aiWebSearchUsed != AiWebSearch.OFF

    // How the artist's search is made under [settings] (#912): [AiWebSearch.UP_TO] by the AI service
    // itself, while it writes, with a service whose client takes tools and the engine it searches
    // with ([WebQuestions.used]); else [AiWebSearch.ONCE], the app's own search; [AiWebSearch.OFF] for none.
    private fun artistWay(settings: Settings): AiWebSearch {
        val access = ai
        return when {
            !searchesArtist(settings) -> AiWebSearch.OFF
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
    // them out ([artistLines]), so the album's fingerprint does not change with them (#885).
    private suspend fun albumEvidence(
        album: CardEntity.Album,
        own: EvidenceSet,
        settings: Settings,
    ): ItemEvidence {
        val query = queryOf(album, settings)
        val found = albums?.let { attempt { it.lookup(query) } ?: AlbumFound(emptyList(), unreachable = true) }
        val drafts = found?.drafts.orEmpty().filter { settings.isEnabled(it.source) && !it.aggregate }
        val lines = EvidenceSet.of(drafts, hasher)
        return ItemEvidence(
            lines,
            matching(lines.lines, own),
            unreachable = found?.unreachable == true,
            webDue = found?.webDue == true,
        )
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
     *   threw), and the insight is written from [evidence] as it is.
     * - **The artist's** (#912): with *AI web search* used, the lines kept for the artist since the
     *   evidence was gathered (another track's write), with no search; else under *Once* (or *Up to 3
     *   times* with a service whose client takes no tools), the app's own search for the artist
     *   ([ArtistWebLookup.search]), [ItemSearch.Failed] when it fails, which keeps nothing. Under *Up
     *   to 3 times*, [ItemSearch.None]: the AI service searches itself while it writes ([writeSearching]).
     */
    suspend fun search(
        item: InsightItem,
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
        sent: () -> Unit = {},
    ): ItemSearch =
        when {
            !evidence.webDue -> ItemSearch.None
            item == InsightItem.ALBUM -> albumSearch(track, settings, evidence, sent)
            item == InsightItem.ARTIST -> artistSearch(track, settings, evidence, sent)
            else -> ItemSearch.None
        }

    // The album's web search for a write (#885), as [search] says.
    private suspend fun albumSearch(
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
        sent: () -> Unit,
    ): ItemSearch {
        val lookup = albums?.takeIf { settings.isEnabled(SourceId.WEB) }
        val album = entity(InsightItem.ALBUM, track) as? CardEntity.Album
        val found =
            if (lookup != null && album != null) attempt { lookup.searchWeb(queryOf(album, settings), sent) } else null
        return found?.let { withWeb(track, settings, evidence, it) }?.let(ItemSearch::Found) ?: ItemSearch.None
    }

    // The artist's web lines for a write (#912), as [search] says: kept, or *Once*'s search.
    private suspend fun artistSearch(
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
        sent: () -> Unit,
    ): ItemSearch {
        val lookup = artistWeb
        val query = artistQuery(track)
        val way = artistWay(settings)
        if (lookup == null || query == null || way == AiWebSearch.OFF) return ItemSearch.None
        val kept = attempt { lookup.kept(query) }
        val found =
            when {
                kept != null -> kept
                // The AI service searches itself while it writes ([writeSearching]).
                way == AiWebSearch.UP_TO -> null
                else -> attempt { lookup.search(query, sent) }
            }
        return when {
            found != null -> withWeb(track, settings, evidence, found)?.let(ItemSearch::Found) ?: ItemSearch.None
            way == AiWebSearch.UP_TO -> ItemSearch.None
            else -> ItemSearch.Failed
        }
    }

    /**
     * Whether [item]'s insight, from [evidence] under [settings], is written while the AI service
     * searches the web itself ([writeSearching], #912): the artist's, its search due, with *AI web
     * search* set to *Up to 3 times*, a service whose client takes tools, and the engine to search with.
     */
    fun searchesItself(
        item: InsightItem,
        evidence: ItemEvidence,
        settings: Settings,
    ): Boolean =
        item == InsightItem.ARTIST && evidence.webDue && artistWeb != null && artistWay(settings) == AiWebSearch.UP_TO

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
        val searching =
            Searching
                .of(ai, web, artistWeb, artistQuery(track), name(item, track))
                ?.takeIf { searchesItself(item, evidence, settings) }
                ?: return null
        val subject = ItemPrompt.Subject(item.kind, searching.name)
        val question = ArtistQuestion(subject, track, first.modelKnowledge, names, hasher, now())
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
                    question,
                    reply,
                    gates,
                )
        }
    }

    // What [writeSearching] asks with: the AI service, the engine its searches go to, where the pages
    // are kept, the artist they are kept for, and the name the insight goes by.
    private class Searching(
        val access: AiAccess,
        val engine: WebSearcher,
        val lookup: ArtistWebLookup,
        val query: ArtistWebQuery,
        val name: String,
    ) {
        companion object {
            fun of(
                access: AiAccess?,
                engine: WebSearcher?,
                lookup: ArtistWebLookup?,
                query: ArtistWebQuery?,
                name: String?,
            ): Searching? =
                if (access == null || engine == null || lookup == null) {
                    null
                } else {
                    query?.let { artist -> name?.let { Searching(access, engine, lookup, artist, it) } }
                }
        }
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
    // has it), those pages kept for the artist, "found nothing" included, when a search was made
    // (nothing when none was), the artist's evidence then, and the reply read against it, kept under
    // [first]'s key with its fingerprint when complete, with the lines kept again.
    @Suppress("LongParameterList") // What was asked, how it was searched, and what came back.
    private suspend fun searched(
        first: CardKey,
        asked: Asked,
        searching: Searching,
        question: ArtistQuestion,
        reply: WebReply,
        gates: WriteGates,
    ): SearchedWrite? {
        val drafts = question.drafts()
        val searched = reply.cost.searches > 0
        val found = if (searched) withWeb(asked.track, asked.settings, asked.evidence, drafts) else null
        val evidence = found ?: asked.evidence
        // Written by [first]'s service, model and effort, from the lines found.
        val key = first.copy(evidenceFingerprint = evidence.evidence.fingerprint)
        if (!gates.reserve(key)) return null
        if (searched) attempt { searching.lookup.keep(searching.query, drafts) }
        val rewritten = asked.rewriting?.let { rewriteParts(it, key.writtenBy()) }.orEmpty()
        val at = now()
        val state =
            when (val read = outcome(reply.end, reply.text, key, evidence.evidence, null, rewritten)) {
                is Reply.RevisedAway -> InsightState.Failed(ReplyFailures.SELF_REVISED)
                is Reply.Read -> {
                    if (read.kept) {
                        attempt { cache.keep(key, keptText(reply.text, read.state), at, rewritten) }
                        renewWeb(asked.item, asked.track)
                    }
                    (read.state as? InsightState.Written)?.copy(writtenAt = at) ?: read.state
                }
            }
        return SearchedWrite(evidence, key, state)
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

    // [lines], numbered on their own, with each id mapped to its line's id in the track's [own] evidence.
    private fun numbered(
        lines: List<EvidenceLine>,
        own: EvidenceSet,
    ): ItemEvidence {
        val set = EvidenceSet.of(lines.map(::draftOf), hasher)
        return ItemEvidence(set, matching(set.lines, own))
    }

    // [line] as a draft, to be numbered again.
    private fun draftOf(line: EvidenceLine): EvidenceDraft =
        EvidenceDraft(line.source, line.scope, line.text, line.url, line.retrievedAt, line.aggregate, line.about)

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
                promptVersion = ItemPrompt.VERSION,
                styleHash = styleHash(hasher),
                provider = provider,
                model = model,
                modelKnowledge = settings.isEnabled(SourceId.MODEL),
                effort = settings.effort(provider),
            )
        }
    }

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
     * ([ArtistWebLookup.renew]); a reply cut off (a Stop cancels
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
        val prompt = ItemPrompt.build(subject, track.nowPlaying, track.normalized, lines, key.modelKnowledge)
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
            val (text, end) = stream(access, request, key, show)
            val at = now()
            when (val reply = outcome(end, text, key, lines, billed, rewritten)) {
                is Reply.RevisedAway -> {
                    if (calls >= CALLS) last = revisedAway(show) else asking = true
                    billed = reply.usage
                }

                is Reply.Read -> {
                    if (reply.kept) {
                        attempt { cache.keep(key, keptText(text, reply.state), at, rewritten) }
                        renewWeb(item, track)
                    }
                    val shown = (reply.state as? InsightState.Written)?.copy(writtenAt = at) ?: reply.state
                    last = shown.also { show(it, at) }
                }
            }
        }
        return last
    }

    // The album's or the artist's web lines kept again from now with [item]'s card on [track] written
    // from them (#885, [AlbumLookup.renewWeb]; #912, [ArtistWebLookup.renew]), so they last as long as
    // this card, the last one kept; nothing for the song's, or when none are kept.
    private suspend fun renewWeb(
        item: InsightItem,
        track: TrackState,
    ) {
        val album = entity(item, track) as? CardEntity.Album
        val lookup = albums
        if (album != null && lookup != null) {
            attempt { lookup.renewWeb(AlbumQuery(album.title, album.artist, album.group)) }
        }
        val artist = artistWeb
        val query = artistQuery(track)
        if (item == InsightItem.ARTIST && artist != null && query != null) attempt { artist.renew(query) }
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
