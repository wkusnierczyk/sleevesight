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
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.ItemPrompt
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
 * *Sources* at its line. [unreachable] when a source of the album's could not be asked.
 */
class ItemEvidence(
    val evidence: EvidenceSet,
    val trackIds: Map<String, String>,
    val unreachable: Boolean = false,
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
 *   evidence gathered for the album ([albums], [AlbumLookup]).
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
                val resolved = track.resolution?.artists.orEmpty()
                val artist = resolved.firstOrNull { names.sameName(it.name, primary) } ?: resolved.firstOrNull()
                artist?.let(CardEntity.Artist::of) ?: CardEntity.Artist(primary, null)
            }

            InsightItem.ALBUM -> {
                album(track)?.let { title ->
                    val dates = track.resolution?.releaseDates.orEmpty()
                    CardEntity.Album.of(title, track.normalized.primary, dates, names::albumKey)
                }
            }
        }

    /**
     * The evidence [item]'s insight on [track] is written from, under [settings] (its sources'
     * switches): null while the track's evidence is not in, every source answered, or with no name
     * for it ([name]). The album's is asked of [albums] (free lookups, kept per album); with none, it
     * is empty.
     */
    suspend fun evidence(
        item: InsightItem,
        track: TrackState,
        settings: Settings,
    ): ItemEvidence? {
        val own = track.evidence?.takeIf { track.gathered && name(item, track) != null }
        val album = entity(item, track) as? CardEntity.Album
        return when {
            own == null -> null
            item == InsightItem.SONG -> songEvidence(own)
            item == InsightItem.ARTIST -> numbered(artistLines(own, track), own)
            album == null -> null
            else -> albumEvidence(album, own, settings)
        }
    }

    // The track's [own] evidence less its artist-level lines, each keeping its id.
    private fun songEvidence(own: EvidenceSet): ItemEvidence {
        val song = own.without(hasher) { it.scope == Scope.ARTIST }
        return ItemEvidence(song, song.ids.associateWith { it })
    }

    // The evidence gathered for [album], by [albums], from the sources [settings] switch on.
    private suspend fun albumEvidence(
        album: CardEntity.Album,
        own: EvidenceSet,
        settings: Settings,
    ): ItemEvidence {
        val query =
            AlbumQuery(
                album.title,
                album.artist,
                album.group,
                musicBrainz = settings.isEnabled(SourceId.MUSICBRAINZ),
                wikipedia = settings.isEnabled(SourceId.WIKIPEDIA),
            )
        val found = albums?.let { attempt { it.lookup(query) } ?: AlbumFound(emptyList(), unreachable = true) }
        val drafts = found?.drafts.orEmpty().filter { settings.isEnabled(it.source) }
        val lines = EvidenceSet.of(drafts, hasher)
        return ItemEvidence(lines, matching(lines.lines, own), unreachable = found?.unreachable == true)
    }

    // The artist-level lines of the track's [own] evidence: those naming the primary artist, when
    // [track] credits several; all of them when it credits one.
    private fun artistLines(
        own: EvidenceSet,
        track: TrackState,
    ): List<EvidenceLine> {
        val lines = own.lines.filter { it.scope == Scope.ARTIST }
        if (track.normalized.artists.size <= 1) return lines
        val primary = " ${names.key(track.normalized.primary)} "
        return lines.filter { primary.isNotBlank() && primary in " ${names.key(it.text)} " }
    }

    // [lines], numbered on their own, with each id mapped to its line's id in the track's [own] evidence.
    private fun numbered(
        lines: List<EvidenceLine>,
        own: EvidenceSet,
    ): ItemEvidence {
        val drafts = lines.map { EvidenceDraft(it.source, it.scope, it.text, it.url, it.retrievedAt, it.aggregate) }
        val set = EvidenceSet.of(drafts, hasher)
        return ItemEvidence(set, matching(set.lines, own))
    }

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
        val access = ai?.takeIf { settings.writeInsights }
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
     * cache says; null when none is kept.
     */
    suspend fun kept(
        key: CardKey,
        evidence: ItemEvidence,
    ): KeptItem? {
        val card = attempt { cache.keptCard(key) } ?: return null
        return KeptItem(
            checked(card.text, key, evidence.evidence, cached = true, usage = null, complete = true),
            card.writtenAt,
        )
    }

    /**
     * Writes [key]'s insight about [item] on [track] from [evidence], one call, each state as it
     * comes to [show] with when it came (by the clock, in whole milliseconds as the cache keeps it);
     * returns the last. With nothing to narrate, no call, and [InsightState.NoEvidence]. A complete
     * reply is kept in the cache, `NO_EVIDENCE` too, so the same item asks nothing again, with the
     * one time it is shown with. [counted] is told of the call.
     */
    @Suppress("LongParameterList") // The insight, what it is about and from, and where its states go.
    suspend fun write(
        key: CardKey,
        item: InsightItem,
        track: TrackState,
        evidence: ItemEvidence,
        counted: (QuestionCost) -> Unit = {},
        show: (InsightState, Instant) -> Unit,
    ): InsightState {
        val access = checkNotNull(ai) { "no AI service" }
        val lines = evidence.evidence
        if (lines.nothingToNarrate(key.modelKnowledge, artistsKnown = track.normalized.artists.isNotEmpty())) {
            return InsightState.NoEvidence.also { show(it, now()) }
        }
        val subject = ItemPrompt.Subject(item.kind, checkNotNull(name(item, track)))
        val prompt = ItemPrompt.build(subject, track.nowPlaying, track.normalized, lines, key.modelKnowledge)
        show(InsightState.Writing("", model = key.model), now())
        val request = CompletionRequest(key.model, prompt, CompletionRequest.maxTokensFor(key.effort), key.effort)
        counted(QuestionCost.CALL)
        val text = StringBuilder()
        var end: CompletionEvent? = null
        access.provider.stream(request, access.key).collect { event ->
            when (event) {
                is CompletionEvent.Delta -> {
                    text.append(event.text)
                    if (!GroundingCheck.mayBeNoEvidence(text.toString())) {
                        show(InsightState.Writing(text.toString(), model = key.model), now())
                    }
                }

                // Done or Error: no tools are sent, so never a tool call.
                else -> {
                    end = event
                }
            }
        }
        val at = now()
        val (state, kept) = outcome(end, text.toString(), key, lines)
        if (kept) attempt { cache.keep(key, keptText(text.toString(), state), at) }
        val shown = (state as? InsightState.Written)?.copy(writtenAt = at) ?: state
        return shown.also { show(it, at) }
    }

    // A reply's state, and whether it is kept: [end] and [text] read as [key]'s insight, checked against [evidence].
    private fun outcome(
        end: CompletionEvent?,
        text: String,
        key: CardKey,
        evidence: EvidenceSet,
    ): Pair<InsightState, Boolean> =
        when (end) {
            is CompletionEvent.Done -> {
                val blank = ReplyFailures.ofBlank(end, text)
                when {
                    end.stopReason == StopReason.REFUSAL -> InsightState.Failed(ReplyFailures.DECLINED) to false
                    // No text is no insight to show, nor to keep (#665).
                    blank != null -> InsightState.Failed(blank) to false
                    else -> {
                        val complete = end.stopReason == StopReason.END
                        checked(text, key, evidence, cached = false, usage = end.usage, complete = complete) to complete
                    }
                }
            }

            is CompletionEvent.Error -> InsightState.Failed(ReplyFailures.of(end)) to false
            else -> InsightState.Failed(ReplyFailures.INCOMPLETE) to false
        }

    // What is kept of a complete reply [text] read as [state]: `NO_EVIDENCE` for one read as none.
    private fun keptText(
        text: String,
        state: InsightState,
    ): String = if (state == InsightState.NoEvidence) GroundingCheck.NO_EVIDENCE else text

    @Suppress("LongParameterList") // The reply, its key and evidence, and how it came.
    private fun checked(
        text: String,
        key: CardKey,
        evidence: EvidenceSet,
        cached: Boolean,
        usage: Usage?,
        complete: Boolean,
    ): InsightState =
        when (val reply = GroundingCheck.check(text, evidence, key.modelKnowledge)) {
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
        /** Whether [item]'s insight may be offered on [track]: its evidence in, every source answered. */
        fun offered(track: TrackState): Boolean = track.gathered && track.evidence != null
    }
}
