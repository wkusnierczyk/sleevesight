package app.sleevesight.app.ui.home

import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.SourceResult

// What *Sources* shows of each source (#440, #662): a row of its table for each source but those
// that are off, and a note only for what needs the listener; the sources that are off, and the
// statuses that are diagnostics, are behind *Show grounding details*.

/**
 * Of [chips], the rows of *Sources*' table (#662). With grounding [details], every one; without,
 * all but a source that is off ([isOff]), unless it gave lines to [evidence] (the insight kept from
 * before cites lines found while it was on).
 */
internal fun tableRows(
    chips: List<SourceChip>,
    evidence: EvidenceSet?,
    details: Boolean,
): List<SourceChip> {
    if (details) return chips
    val sources = evidence?.lines.orEmpty().mapTo(mutableSetOf()) { it.source }
    return chips.filter { chip -> !chip.isOff || chip.filters.any { it in sources } }
}

/** The app's strings by their resource ids, formatted with [args]: the resources', or a test's. */
internal fun interface Words {
    fun get(
        id: Int,
        vararg args: Any,
    ): String
}

/**
 * What a source found, as its row in *Sources*' table says it (#768): a list of what its own lines
 * are about ([Of]), or, for web search, whose lines are all [Scope.WEB], pages about the track
 * ([Pages]).
 */
internal sealed interface Finding {
    /** [scopes], each a thing found, in [FINDING_ORDER]'s order; never empty. */
    data class Of(
        val scopes: List<Scope>,
    ) : Finding

    /** Web search's pages. */
    data object Pages : Finding
}

/**
 * The order of a finding's list (#768), the track first: the track, the original song, the album
 * (#522: the album's own lines, of [Scope.RELEASE], which a source gives only for the album's insight;
 * the player's release line is no source's finding), the artist.
 */
internal val FINDING_ORDER: List<Scope> = listOf(Scope.SONG, Scope.ORIGINAL_SONG, Scope.RELEASE, Scope.ARTIST)

/**
 * What [chip]'s source found (#768), from the scopes of its own lines in [lines] (those of the
 * sources it [SourceChip.filters], in its language, #902: [SourceChip.own]), not from its single
 * outcome: for a lookup done with a found outcome (the song's article, the artist's, the original
 * song's), the scopes of [FINDING_ORDER] its lines have, in that order; web search's,
 * [Finding.Pages], with its lines shown or not; with no line of those scopes (its lines not in the
 * evidence shown), the outcome's own scope alone. A lookup that found nothing, of a source that
 * lists the album's own lines (#522), found the album; of web search, with pages about the track the
 * song's own search found since (#958), those pages. Null for any other status, which says what it
 * says ([statusText]).
 */
internal fun finding(
    chip: SourceChip,
    lines: List<EvidenceLine>,
): Finding? {
    val own = chip.own(lines)
    val outcome = (chip.status as? ChipStatus.Done)?.outcome
    val nothing = outcome == Outcome.None || outcome is Outcome.Rejected
    val album = chip.source != SourceId.PLAYER && own.any { it.scope == Scope.RELEASE }
    val alone = outcome?.let(FOUND_ALONE::get)
    return when {
        nothing && album -> Finding.Of(listOf(Scope.RELEASE))
        nothing && chip.source == SourceId.WEB && own.any { it.scope == Scope.WEB } -> Finding.Pages
        alone == null -> null
        chip.source == SourceId.WEB -> Finding.Pages
        else -> {
            val scopes = own.mapTo(mutableSetOf()) { it.scope }
            Finding.Of(FINDING_ORDER.filter { it in scopes }.ifEmpty { listOf(alone) })
        }
    }
}

/** The outcomes that found something, each with what it found alone (#768). */
private val FOUND_ALONE: Map<Outcome, Scope> =
    mapOf(
        Outcome.SongArticle to Scope.SONG,
        Outcome.ArtistArticle to Scope.ARTIST,
        Outcome.OriginalSong to Scope.ORIGINAL_SONG,
    )

/**
 * Where [chip]'s first unverified line is in the list as it shows with the sources [hidden] hide
 * (#951), and its entry's key ([unverifiedKey]): after the evidence [lines] shown, among the
 * [unverified] lines shown; null while its lines are hidden, or for a source with none.
 */
internal fun firstUnverified(
    chip: SourceChip,
    lines: List<EvidenceLine>,
    unverified: List<EvidenceDraft>,
    hidden: Set<SourceId>,
): Pair<Int, String>? {
    val shown = unverified.withIndex().filter { it.value.source !in hidden }
    val at = shown.indexOfFirst { it.value.source in chip.filters }
    return if (chip.filters.any { it in hidden } || at < 0) {
        null
    } else {
        lines.count { it.source !in hidden } + at to unverifiedKey(shown[at].index)
    }
}

/** The key of the list's entry of the unverified line at [index] among the track's (#951). */
internal fun unverifiedKey(index: Int): String = "unverified:$index"

/**
 * Whether [this] is a rejected match whose every rejection was only for want of what MusicBrainz
 * resolves (#1069, [Rejection.withoutMusicBrainz]): an artist's article with no MusicBrainz id to
 * confirm it, a song's found by name that only MusicBrainz could tie to the track. False for a
 * result that lists no rejection, whose reason alone cannot say.
 */
internal val SourceResult.withoutMusicBrainz: Boolean
    get() = outcome is Outcome.Rejected && rejections.isNotEmpty() && rejections.all { it.withoutMusicBrainz }

/**
 * Whether [this] source is off: switched off in the settings, or on but without its key, so not
 * asked (what *Sources* says of it in a note).
 */
internal val SourceChip.isOff: Boolean
    get() = status == ChipStatus.NeedsKey || (status as? ChipStatus.Done)?.outcome == Outcome.SourceOff

/**
 * What *Sources* tells the listener of a source (#440): only what needs them or went wrong; of its
 * lookup in the track's language when [language] (its code, #902) is set.
 */
internal sealed interface SourceNote {
    val source: SourceId
    val language: String? get() = null

    /** On, but its key is not saved: it was not asked. */
    data class NeedsKey(
        override val source: SourceId,
    ) : SourceNote

    /** It could not be reached. */
    data class Unreachable(
        override val source: SourceId,
        override val language: String? = null,
    ) : SourceNote

    /** Its lookup went wrong. */
    data class Failed(
        override val source: SourceId,
        override val language: String? = null,
    ) : SourceNote
}

/**
 * The notes of [chips], in their order (#440): a source that needs its key, one that could not be
 * reached, and one whose lookup failed; nothing for one that found nothing, was rejected or is off.
 * While the *Sources* section says the phone is [offline], which covers it, an unreachable source
 * has no note of its own.
 */
internal fun sourceNotes(
    chips: List<SourceChip>,
    offline: Boolean,
): List<SourceNote> =
    chips.mapNotNull { chip ->
        val status = chip.status
        when {
            status == ChipStatus.NeedsKey -> SourceNote.NeedsKey(chip.source)
            status == ChipStatus.Failed -> SourceNote.Failed(chip.source, chip.language)
            status is ChipStatus.Done && status.outcome == Outcome.Unreachable && !offline ->
                SourceNote.Unreachable(chip.source, chip.language)
            else -> null
        }
    }

/**
 * Whether the *Sources* section says the phone is offline (#192): while it is ([view]'s
 * `offline`), for a [track] with a source unreachable, or a lookup in its language (#902), which is
 * asked again once the phone is back online.
 */
internal fun offlineShown(
    view: PipelineView,
    track: TrackState?,
): Boolean {
    val states =
        track?.sources?.values.orEmpty() +
            track
                ?.inLanguage
                ?.sources
                ?.values
                .orEmpty()
    return view.offline && states.any { (it as? SourceState.Done)?.result?.outcome == Outcome.Unreachable }
}
