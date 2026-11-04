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
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.AlbumFound
import app.sleevesight.core.sources.AlbumLookup
import app.sleevesight.core.sources.AlbumQuery
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceResult
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
 * when the album's web search is due before its insight is written (#885, [ItemInsights.searched]).
 */
class ItemEvidence(
    val evidence: EvidenceSet,
    val trackIds: Map<String, String>,
    val unreachable: Boolean = false,
    val webDue: Boolean = false,
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
 *   Wikidata item), those naming the primary artist when the track credits several;
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
 * Not written ([key] null) with *Show insights* off, no AI service ready, or before the track's
 * evidence is in. When the evidence for an item has nothing to narrate
 * ([EvidenceSet.nothingToNarrate]), no call is made, and the item says so
 * ([InsightState.NoEvidence]). A cache that throws is a miss, and a write that fails keeps
 * nothing. Each state written comes with its time by [clock]; a reply kept is kept with the time it
 * is shown with (#643).
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
     * none, it is empty.
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
            item == InsightItem.ARTIST -> numbered(artistLines(own, track), shown)
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
     * [evidence], the album's on [track] under [settings], with the lines of its web search (#885),
     * for a write of its insight only: the search is paid, so it is made here, never when the
     * evidence is gathered ([evidence]), and only while it is due ([ItemEvidence.webDue]); [albums]
     * keeps what it found, so the evidence gathered later holds the same lines, and the insight
     * written from them is found again by its key. Null when no search is due, or none could be made
     * (it failed, or the album lookup threw): the insight is then written from [evidence] as it is.
     */
    suspend fun searched(
        item: InsightItem,
        track: TrackState,
        settings: Settings,
        evidence: ItemEvidence,
    ): ItemEvidence? {
        val lookup = albums?.takeIf { evidence.webDue }
        val album = entity(item, track) as? CardEntity.Album
        val shown = track.evidence
        val found =
            if (lookup != null &&
                album != null
            ) {
                attempt { lookup.searchWeb(queryOf(album, settings)) }
            } else {
                null
            }
        if (found == null || shown == null) return null
        val more = found.filter { settings.isEnabled(it.source) && !it.aggregate }
        val drafts = evidence.evidence.lines.map(::draftOf) + more
        val lines = EvidenceSet.of(drafts, hasher)
        return ItemEvidence(lines, matching(lines.lines, shown), unreachable = evidence.unreachable)
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
        val model = access?.let { settings.model(it.provider.id) }
        val entity = entity(item, track)
        return if (access == null || model == null || entity == null) {
            null
        } else {
            CardKey(
                kind = item.kind,
                entity = entity,
                evidenceFingerprint = evidence.evidence.fingerprint,
                promptVersion = ItemPrompt.VERSION,
                styleHash = styleHash(hasher),
                provider = access.provider.id,
                model = model,
                modelKnowledge = settings.isEnabled(SourceId.MODEL),
                effort = settings.effort(access.provider.id),
            )
        }
    }

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
        return reply?.let { KeptItem(stateOf(it, key, cached = true, usage = null, complete = true), card.writtenAt) }
    }

    /**
     * Writes [key]'s insight about [item] on [track] from [evidence], each state as it comes to
     * [show] with when it came (by the clock, in whole milliseconds as the cache keeps it); returns
     * the last, or null when [mayCall] closed before the call, which then is not made. [mayCall] is
     * asked right before each call, with no suspension between. With nothing to narrate, no call, and
     * [InsightState.NoEvidence]. A complete reply is kept in the cache, `NO_EVIDENCE` too, so the same
     * item asks nothing again, with the one time it is shown with; a reply cut off (a Stop cancels
     * the call) is neither shown nor kept. A reply that revises itself where it would show (#798) is
     * cut to what follows the revision when that is usable; else asked for once more, a second call,
     * and with nothing usable then either, it fails, as the key insight's does. While it streams, the
     * text shown stops at such a revision. [counted] is told of each call.
     */
    @Suppress("LongParameterList") // The insight, what it is about and from, its gate, and where its states go.
    suspend fun write(
        key: CardKey,
        item: InsightItem,
        track: TrackState,
        evidence: ItemEvidence,
        mayCall: () -> Boolean = { true },
        counted: (QuestionCost) -> Unit = {},
        show: (InsightState, Instant) -> Unit,
    ): InsightState? {
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
            when (val reply = outcome(end, text, key, lines, billed)) {
                is Reply.RevisedAway -> {
                    if (calls >= CALLS) last = revisedAway(show) else asking = true
                    billed = reply.usage
                }

                is Reply.Read -> {
                    if (reply.kept) attempt { cache.keep(key, keptText(text, reply.state), at) }
                    val shown = (reply.state as? InsightState.Written)?.copy(writtenAt = at) ?: reply.state
                    last = shown.also { show(it, at) }
                }
            }
        }
        return last
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
    // the replies before it in this write ([billed]).
    @Suppress("LongParameterList") // The reply, its key and evidence, and what came before it.
    private fun outcome(
        end: CompletionEvent?,
        text: String,
        key: CardKey,
        evidence: EvidenceSet,
        billed: Usage?,
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
                            stateOf(reply, key, cached = false, usage = usage, complete = complete),
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

    // [reply] as [key]'s state.
    private fun stateOf(
        reply: InsightReply,
        key: CardKey,
        cached: Boolean,
        usage: Usage?,
        complete: Boolean,
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
